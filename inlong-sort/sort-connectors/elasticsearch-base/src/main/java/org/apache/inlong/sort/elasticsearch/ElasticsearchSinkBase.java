/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.sort.elasticsearch;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.streaming.connectors.elasticsearch.ActionRequestFailureHandler;
import org.apache.flink.streaming.connectors.elasticsearch.RequestIndexer;
import org.apache.flink.util.InstantiationUtil;
import org.apache.inlong.sort.base.metric.SinkMetricData;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.rest.RestStatus;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.inlong.sort.base.Constants.DELIMITER;

/**
 * Base class for all Flink Elasticsearch Sinks.
 *
 * <p>This class implements the common behaviour across Elasticsearch versions, such as the use of
 * an internal {@link BulkProcessor} to buffer multiple {@link ActionRequest}s before sending the
 * requests to the cluster, as well as passing input records to the user provided {@link
 * ElasticsearchSinkFunction} for processing.
 *
 * <p>The version specific API calls for different Elasticsearch versions should be defined by a
 * concrete implementation of a {@link ElasticsearchApiCallBridge}, which is provided to the
 * constructor of this class. This call bridge is used, for example, to create a Elasticsearch
 * {@link Client}, handle failed item responses, etc.
 *
 * @param <T> Type of the elements handled by this sink
 * @param <C> Type of the Elasticsearch client, which implements {@link AutoCloseable}
 */
@Internal
public abstract class ElasticsearchSinkBase<T, C extends AutoCloseable> extends RichSinkFunction<T>
        implements CheckpointedFunction {

    public static final String CONFIG_KEY_BULK_FLUSH_MAX_ACTIONS = "bulk.flush.max.actions";

    // ------------------------------------------------------------------------
    //  Internal bulk processor configuration
    // ------------------------------------------------------------------------
    public static final String CONFIG_KEY_BULK_FLUSH_MAX_SIZE_MB = "bulk.flush.max.size.mb";
    public static final String CONFIG_KEY_BULK_FLUSH_INTERVAL_MS = "bulk.flush.interval.ms";
    public static final String CONFIG_KEY_BULK_FLUSH_BACKOFF_ENABLE = "bulk.flush.backoff.enable";
    public static final String CONFIG_KEY_BULK_FLUSH_BACKOFF_TYPE = "bulk.flush.backoff.type";
    public static final String CONFIG_KEY_BULK_FLUSH_BACKOFF_RETRIES = "bulk.flush.backoff.retries";
    public static final String CONFIG_KEY_BULK_FLUSH_BACKOFF_DELAY = "bulk.flush.backoff.delay";
    private static final long serialVersionUID = -1007596293618451942L;
    private final Integer bulkProcessorFlushMaxActions;
    private final Integer bulkProcessorFlushMaxSizeMb;
    private final Long bulkProcessorFlushIntervalMillis;
    private final BulkFlushBackoffPolicy bulkProcessorFlushBackoffPolicy;
    /**
     * The config map that contains configuration for the bulk flushing behaviours.
     *
     * <p>For {@link org.elasticsearch.client.transport.TransportClient} based implementations, this
     * config map would also contain Elasticsearch-shipped configuration, and therefore this config
     * map would also be forwarded when creating the Elasticsearch client.
     */
    private final Map<String, String> userConfig;
    /**
     * The function that is used to construct multiple {@link ActionRequest ActionRequests} from
     * each incoming element.
     */
    private final ElasticsearchSinkFunction<T> elasticsearchSinkFunction;

    // ------------------------------------------------------------------------
    //  User-facing API and configuration
    // ------------------------------------------------------------------------
    /**
     * User-provided handler for failed {@link ActionRequest ActionRequests}.
     */
    private final ActionRequestFailureHandler failureHandler;
    /**
     * Call bridge for different version-specific.
     */
    private final ElasticsearchApiCallBridge<C> callBridge;
    /**
     * This is set from inside the {@link BulkProcessor.Listener} if a {@link Throwable} was thrown
     * in callbacks and the user considered it should fail the sink via the {@link
     * ActionRequestFailureHandler#onFailure(ActionRequest, Throwable, int, RequestIndexer)} method.
     *
     * <p>Errors will be checked and rethrown before processing each input element, and when the
     * sink is closed.
     */
    private final AtomicReference<Throwable> failureThrowable = new AtomicReference<>();
    private final String inlongMetric;
    /**
     * If true, the producer will wait until all outstanding action requests have been sent to
     * Elasticsearch.
     */
    private boolean flushOnCheckpoint = true;
    /**
     * Provided to the user via the {@link ElasticsearchSinkFunction} to add {@link ActionRequest
     * ActionRequests}.
     */
    private transient RequestIndexer requestIndexer;

    // ------------------------------------------------------------------------
    //  Internals for the Flink Elasticsearch Sink
    // ------------------------------------------------------------------------
    /**
     * Provided to the {@link ActionRequestFailureHandler} to allow users to re-index failed
     * requests.
     */
    private transient BufferingNoOpRequestIndexer failureRequestIndexer;
    /**
     * Number of pending action requests not yet acknowledged by Elasticsearch. This value is
     * maintained only if {@link ElasticsearchSinkBase#flushOnCheckpoint} is {@code true}.
     *
     * <p>This is incremented whenever the user adds (or re-adds through the {@link
     * ActionRequestFailureHandler}) requests to the {@link RequestIndexer}. It is decremented for
     * each completed request of a bulk request, in {@link BulkProcessor.Listener#afterBulk(long,
     * BulkRequest, BulkResponse)} and {@link BulkProcessor.Listener#afterBulk(long, BulkRequest,
     * Throwable)}.
     */
    private AtomicLong numPendingRequests = new AtomicLong(0);
    /**
     * Elasticsearch client created using the call bridge.
     */
    private transient C client;
    /**
     * Bulk processor to buffer and send requests to Elasticsearch, created using the client.
     */
    private transient BulkProcessor bulkProcessor;
    private SinkMetricData sinkMetricData;

    public ElasticsearchSinkBase(
            ElasticsearchApiCallBridge<C> callBridge,
            Map<String, String> userConfig,
            ElasticsearchSinkFunction<T> elasticsearchSinkFunction,
            ActionRequestFailureHandler failureHandler,
            String inlongMetric) {
        this.inlongMetric = inlongMetric;
        this.callBridge = checkNotNull(callBridge);
        this.elasticsearchSinkFunction = checkNotNull(elasticsearchSinkFunction);
        this.failureHandler = checkNotNull(failureHandler);
        // we eagerly check if the user-provided sink function and failure handler is serializable;
        // otherwise, if they aren't serializable, users will merely get a non-informative error
        // message
        // "ElasticsearchSinkBase is not serializable"

        checkArgument(
                InstantiationUtil.isSerializable(elasticsearchSinkFunction),
                "The implementation of the provided ElasticsearchSinkFunction is not serializable. "
                        + "The object probably contains or references non-serializable fields.");

        checkArgument(
                InstantiationUtil.isSerializable(failureHandler),
                "The implementation of the provided ActionRequestFailureHandler is not serializable. "
                        + "The object probably contains or references non-serializable fields.");

        // extract and remove bulk processor related configuration from the user-provided config,
        // so that the resulting user config only contains configuration related to the
        // Elasticsearch client.

        checkNotNull(userConfig);

        // copy config so we can remove entries without side-effects
        userConfig = new HashMap<>(userConfig);

        ParameterTool params = ParameterTool.fromMap(userConfig);

        if (params.has(CONFIG_KEY_BULK_FLUSH_MAX_ACTIONS)) {
            bulkProcessorFlushMaxActions = params.getInt(CONFIG_KEY_BULK_FLUSH_MAX_ACTIONS);
            userConfig.remove(CONFIG_KEY_BULK_FLUSH_MAX_ACTIONS);
        } else {
            bulkProcessorFlushMaxActions = null;
        }

        if (params.has(CONFIG_KEY_BULK_FLUSH_MAX_SIZE_MB)) {
            bulkProcessorFlushMaxSizeMb = params.getInt(CONFIG_KEY_BULK_FLUSH_MAX_SIZE_MB);
            userConfig.remove(CONFIG_KEY_BULK_FLUSH_MAX_SIZE_MB);
        } else {
            bulkProcessorFlushMaxSizeMb = null;
        }

        if (params.has(CONFIG_KEY_BULK_FLUSH_INTERVAL_MS)) {
            bulkProcessorFlushIntervalMillis = params.getLong(CONFIG_KEY_BULK_FLUSH_INTERVAL_MS);
            userConfig.remove(CONFIG_KEY_BULK_FLUSH_INTERVAL_MS);
        } else {
            bulkProcessorFlushIntervalMillis = null;
        }

        boolean bulkProcessorFlushBackoffEnable =
                params.getBoolean(CONFIG_KEY_BULK_FLUSH_BACKOFF_ENABLE, true);
        userConfig.remove(CONFIG_KEY_BULK_FLUSH_BACKOFF_ENABLE);

        if (bulkProcessorFlushBackoffEnable) {
            this.bulkProcessorFlushBackoffPolicy = new BulkFlushBackoffPolicy();

            if (params.has(CONFIG_KEY_BULK_FLUSH_BACKOFF_TYPE)) {
                bulkProcessorFlushBackoffPolicy.setBackoffType(
                        FlushBackoffType.valueOf(params.get(CONFIG_KEY_BULK_FLUSH_BACKOFF_TYPE)));
                userConfig.remove(CONFIG_KEY_BULK_FLUSH_BACKOFF_TYPE);
            }

            if (params.has(CONFIG_KEY_BULK_FLUSH_BACKOFF_RETRIES)) {
                bulkProcessorFlushBackoffPolicy.setMaxRetryCount(
                        params.getInt(CONFIG_KEY_BULK_FLUSH_BACKOFF_RETRIES));
                userConfig.remove(CONFIG_KEY_BULK_FLUSH_BACKOFF_RETRIES);
            }

            if (params.has(CONFIG_KEY_BULK_FLUSH_BACKOFF_DELAY)) {
                bulkProcessorFlushBackoffPolicy.setDelayMillis(
                        params.getLong(CONFIG_KEY_BULK_FLUSH_BACKOFF_DELAY));
                userConfig.remove(CONFIG_KEY_BULK_FLUSH_BACKOFF_DELAY);
            }

        } else {
            bulkProcessorFlushBackoffPolicy = null;
        }

        this.userConfig = userConfig;
    }

    /**
     * Disable flushing on checkpoint. When disabled, the sink will not wait for all pending action
     * requests to be acknowledged by Elasticsearch on checkpoints.
     *
     * <p>NOTE: If flushing on checkpoint is disabled, the Flink Elasticsearch Sink does NOT provide
     * any strong guarantees for at-least-once delivery of action requests.
     */
    public void disableFlushOnCheckpoint() {
        this.flushOnCheckpoint = false;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        client = callBridge.createClient(userConfig);
        if (inlongMetric != null && !inlongMetric.isEmpty()) {
            String[] inlongMetricArray = inlongMetric.split(DELIMITER);
            String groupId = inlongMetricArray[0];
            String streamId = inlongMetricArray[1];
            String nodeId = inlongMetricArray[2];
            sinkMetricData = new SinkMetricData(groupId, streamId, nodeId, getRuntimeContext().getMetricGroup());
            sinkMetricData.registerMetricsForDirtyBytes();
            sinkMetricData.registerMetricsForDirtyRecords();
            sinkMetricData.registerMetricsForNumBytesOut();
            sinkMetricData.registerMetricsForNumRecordsOut();
            sinkMetricData.registerMetricsForNumBytesOutPerSecond();
            sinkMetricData.registerMetricsForNumRecordsOutPerSecond();
        }
        callBridge.verifyClientConnection(client);
        bulkProcessor = buildBulkProcessor(new BulkProcessorListener(sinkMetricData));
        requestIndexer =
                callBridge.createBulkProcessorIndexer(
                        bulkProcessor, flushOnCheckpoint, numPendingRequests);
        failureRequestIndexer = new BufferingNoOpRequestIndexer();
        elasticsearchSinkFunction.open(getRuntimeContext());
    }

    @Override
    public void invoke(T value, Context context) throws Exception {
        checkAsyncErrorsAndRequests();
        elasticsearchSinkFunction.process(value, getRuntimeContext(), requestIndexer);
    }

    @Override
    public void initializeState(FunctionInitializationContext context) throws Exception {
        // no initialization needed
    }

    @Override
    public void snapshotState(FunctionSnapshotContext context) throws Exception {
        checkAsyncErrorsAndRequests();

        if (flushOnCheckpoint) {
            while (numPendingRequests.get() != 0) {
                bulkProcessor.flush();
                checkAsyncErrorsAndRequests();
            }
        }
    }

    @Override
    public void close() throws Exception {
        elasticsearchSinkFunction.close();
        if (bulkProcessor != null) {
            bulkProcessor.close();
            bulkProcessor = null;
        }

        if (client != null) {
            client.close();
            client = null;
        }

        callBridge.cleanup();

        // make sure any errors from callbacks are rethrown
        checkErrorAndRethrow();
    }

    /**
     * Build the {@link BulkProcessor}.
     *
     * <p>Note: this is exposed for testing purposes.
     */
    @VisibleForTesting
    protected BulkProcessor buildBulkProcessor(BulkProcessor.Listener listener) {
        checkNotNull(listener);

        BulkProcessor.Builder bulkProcessorBuilder =
                callBridge.createBulkProcessorBuilder(client, listener);

        // This makes flush() blocking
        bulkProcessorBuilder.setConcurrentRequests(0);

        if (bulkProcessorFlushMaxActions != null) {
            bulkProcessorBuilder.setBulkActions(bulkProcessorFlushMaxActions);
        }

        if (bulkProcessorFlushMaxSizeMb != null) {
            configureBulkSize(bulkProcessorBuilder);
        }

        if (bulkProcessorFlushIntervalMillis != null) {
            configureFlushInterval(bulkProcessorBuilder);
        }

        // if backoff retrying is disabled, bulkProcessorFlushBackoffPolicy will be null
        callBridge.configureBulkProcessorBackoff(
                bulkProcessorBuilder, bulkProcessorFlushBackoffPolicy);

        return bulkProcessorBuilder.build();
    }

    private void configureBulkSize(BulkProcessor.Builder bulkProcessorBuilder) {
        final ByteSizeUnit sizeUnit;
        if (bulkProcessorFlushMaxSizeMb == -1) {
            // bulk size can be disabled with -1, however the ByteSizeValue constructor accepts -1
            // only with BYTES as the size unit
            sizeUnit = ByteSizeUnit.BYTES;
        } else {
            sizeUnit = ByteSizeUnit.MB;
        }
        bulkProcessorBuilder.setBulkSize(new ByteSizeValue(bulkProcessorFlushMaxSizeMb, sizeUnit));
    }

    private void configureFlushInterval(BulkProcessor.Builder bulkProcessorBuilder) {
        if (bulkProcessorFlushIntervalMillis == -1) {
            bulkProcessorBuilder.setFlushInterval(null);
        } else {
            bulkProcessorBuilder.setFlushInterval(
                    TimeValue.timeValueMillis(bulkProcessorFlushIntervalMillis));
        }
    }

    private void checkErrorAndRethrow() {
        Throwable cause = failureThrowable.get();
        if (cause != null) {
            throw new RuntimeException("An error occurred in ElasticsearchSink.", cause);
        }
    }

    private void checkAsyncErrorsAndRequests() {
        checkErrorAndRethrow();
        failureRequestIndexer.processBufferedRequests(requestIndexer);
    }

    @VisibleForTesting
    long getNumPendingRequests() {
        if (flushOnCheckpoint) {
            return numPendingRequests.get();
        } else {
            throw new UnsupportedOperationException(
                    "The number of pending requests is not maintained when flushing on checkpoint is disabled.");
        }
    }

    /**
     * Used to control whether the retry delay should increase exponentially or remain constant.
     */
    @PublicEvolving
    public enum FlushBackoffType {
        CONSTANT,
        EXPONENTIAL
    }

    /**
     * Provides a backoff policy for bulk requests. Whenever a bulk request is rejected due to
     * resource constraints (i.e. the client's internal thread pool is full), the backoff policy
     * decides how long the bulk processor will wait before the operation is retried internally.
     *
     * <p>This is a proxy for version specific backoff policies.
     */
    public static class BulkFlushBackoffPolicy implements Serializable {

        private static final long serialVersionUID = -6022851996101826049L;

        // the default values follow the Elasticsearch default settings for BulkProcessor
        private FlushBackoffType backoffType = FlushBackoffType.EXPONENTIAL;
        private int maxRetryCount = 8;
        private long delayMillis = 50;

        public FlushBackoffType getBackoffType() {
            return backoffType;
        }

        public void setBackoffType(FlushBackoffType backoffType) {
            this.backoffType = checkNotNull(backoffType);
        }

        public int getMaxRetryCount() {
            return maxRetryCount;
        }

        public void setMaxRetryCount(int maxRetryCount) {
            checkArgument(maxRetryCount >= 0);
            this.maxRetryCount = maxRetryCount;
        }

        public long getDelayMillis() {
            return delayMillis;
        }

        public void setDelayMillis(long delayMillis) {
            checkArgument(delayMillis >= 0);
            this.delayMillis = delayMillis;
        }
    }

    private class BulkProcessorListener implements BulkProcessor.Listener {

        private SinkMetricData sinkMetricData;

        public BulkProcessorListener(SinkMetricData sinkMetricData) {
            this.sinkMetricData = sinkMetricData;
        }

        @Override
        public void beforeBulk(long executionId, BulkRequest request) {
        }

        @Override
        public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
            if (response.hasFailures()) {
                BulkItemResponse itemResponse;
                Throwable failure;
                RestStatus restStatus;
                DocWriteRequest actionRequest;

                try {
                    for (int i = 0; i < response.getItems().length; i++) {
                        itemResponse = response.getItems()[i];
                        failure = callBridge.extractFailureCauseFromBulkItemResponse(itemResponse);
                        if (failure != null) {
                            restStatus = itemResponse.getFailure().getStatus();
                            actionRequest = request.requests().get(i);
                            if (sinkMetricData.getDirtyRecords() != null) {
                                sinkMetricData.getDirtyRecords().inc();
                            }
                            if (restStatus == null) {
                                if (actionRequest instanceof ActionRequest) {
                                    failureHandler.onFailure(
                                            (ActionRequest) actionRequest,
                                            failure,
                                            -1,
                                            failureRequestIndexer);
                                } else {
                                    throw new UnsupportedOperationException(
                                            "The sink currently only supports ActionRequests");
                                }
                            } else {
                                if (actionRequest instanceof ActionRequest) {
                                    failureHandler.onFailure(
                                            (ActionRequest) actionRequest,
                                            failure,
                                            restStatus.getStatus(),
                                            failureRequestIndexer);
                                } else {
                                    throw new UnsupportedOperationException(
                                            "The sink currently only supports ActionRequests");
                                }
                            }
                        }
                        if (sinkMetricData.getNumRecordsOut() != null) {
                            sinkMetricData.getNumRecordsOut().inc();
                        }
                    }
                } catch (Throwable t) {
                    // fail the sink and skip the rest of the items
                    // if the failure handler decides to throw an exception
                    failureThrowable.compareAndSet(null, t);
                }
            }

            if (flushOnCheckpoint) {
                numPendingRequests.getAndAdd(-request.numberOfActions());
            }
        }

        @Override
        public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
            try {
                for (DocWriteRequest writeRequest : request.requests()) {
                    if (sinkMetricData.getDirtyRecords() != null) {
                        sinkMetricData.getDirtyRecords().inc();
                    }
                    if (writeRequest instanceof ActionRequest) {
                        failureHandler.onFailure(
                                (ActionRequest) writeRequest, failure, -1, failureRequestIndexer);
                    } else {
                        throw new UnsupportedOperationException(
                                "The sink currently only supports ActionRequests");
                    }
                }
            } catch (Throwable t) {
                // fail the sink and skip the rest of the items
                // if the failure handler decides to throw an exception
                failureThrowable.compareAndSet(null, t);
            }

            if (flushOnCheckpoint) {
                numPendingRequests.getAndAdd(-request.numberOfActions());
            }
        }
    }
}
