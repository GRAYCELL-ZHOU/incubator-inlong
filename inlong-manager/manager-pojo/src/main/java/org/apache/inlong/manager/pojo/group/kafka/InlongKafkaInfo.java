/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.manager.pojo.group.kafka;

import io.swagger.annotations.ApiModel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.apache.inlong.manager.common.consts.MQType;
import org.apache.inlong.manager.pojo.group.InlongGroupInfo;
import org.apache.inlong.manager.common.util.CommonBeanUtils;
import org.apache.inlong.manager.common.util.JsonTypeDefine;

/**
 * Inlong group info for Kafka
 */
@Data
@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@JsonTypeDefine(value = MQType.KAFKA)
@ApiModel("Inlong group info for Kafka")
public class InlongKafkaInfo extends InlongGroupInfo {

    // partition number
    private Integer numPartitions;
    // replicationFactor number
    private Short replicationFactor = 1;
    //consumer grouping
    private String groupId;
    // autocommit interval
    private String autoCommit;

    public InlongKafkaInfo() {
        this.setMqType(MQType.KAFKA);
    }

    @Override
    public InlongKafkaRequest genRequest() {
        return CommonBeanUtils.copyProperties(this, InlongKafkaRequest::new);
    }

}
