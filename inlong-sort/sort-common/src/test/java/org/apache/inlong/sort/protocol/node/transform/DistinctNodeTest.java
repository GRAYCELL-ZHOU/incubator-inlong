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

package org.apache.inlong.sort.protocol.node.transform;

import org.apache.inlong.sort.formats.common.StringFormatInfo;
import org.apache.inlong.sort.formats.common.TimestampFormatInfo;
import org.apache.inlong.sort.protocol.FieldInfo;
import org.apache.inlong.sort.protocol.node.Node;
import org.apache.inlong.sort.protocol.node.NodeBaseTest;
import org.apache.inlong.sort.protocol.transformation.FieldRelationShip;
import org.apache.inlong.sort.protocol.transformation.OrderDirection;

import java.util.Arrays;

/**
 * Test for {@link DistinctNode}
 */
public class DistinctNodeTest extends NodeBaseTest {

    @Override
    public Node getNode() {
        return new DistinctNode("1", null,
                Arrays.asList(new FieldInfo("f1", new StringFormatInfo()),
                        new FieldInfo("f2", new StringFormatInfo()),
                        new FieldInfo("f3", new StringFormatInfo()),
                        new FieldInfo("ts", new TimestampFormatInfo())),
                Arrays.asList(
                        new FieldRelationShip(new FieldInfo("f1", new StringFormatInfo()),
                                new FieldInfo("f1", new StringFormatInfo())),
                        new FieldRelationShip(new FieldInfo("f2", new StringFormatInfo()),
                                new FieldInfo("f2", new StringFormatInfo())),
                        new FieldRelationShip(new FieldInfo("f3", new StringFormatInfo()),
                                new FieldInfo("f3", new StringFormatInfo())),
                        new FieldRelationShip(new FieldInfo("ts", new StringFormatInfo()),
                                new FieldInfo("ts", new StringFormatInfo()))
                ),
                null,
                Arrays.asList(new FieldInfo("f1", new StringFormatInfo()),
                        new FieldInfo("f2", new StringFormatInfo())),
                new FieldInfo("ts", new StringFormatInfo()), OrderDirection.ASC);
    }

    @Override
    public String getExpectSerializeStr() {
        return "{\"type\":\"distinct\",\"id\":\"1\",\"fields\":[{\"type\":\"base\",\"name\":\"f1\","
                + "\"formatInfo\":{\"type\":\"string\"}},{\"type\":\"base\",\"name\":\"f2\","
                + "\"formatInfo\":{\"type\":\"string\"}},{\"type\":\"base\",\"name\":\"f3\","
                + "\"formatInfo\":{\"type\":\"string\"}},{\"type\":\"base\",\"name\":\"ts\","
                + "\"formatInfo\":{\"type\":\"timestamp\",\"format\":\"yyyy-MM-dd HH:mm:ss\",\"precision\":2}}],"
                + "\"fieldRelationShips\":[{\"type\":\"fieldRelationShip\",\"inputField\":{\"type\":\"base\","
                + "\"name\":\"f1\",\"formatInfo\":{\"type\":\"string\"}},\"outputField\":{\"type\":\"base\","
                + "\"name\":\"f1\",\"formatInfo\":{\"type\":\"string\"}}},{\"type\":\"fieldRelationShip\","
                + "\"inputField\":{\"type\":\"base\",\"name\":\"f2\",\"formatInfo\":{\"type\":\"string\"}},"
                + "\"outputField\":{\"type\":\"base\",\"name\":\"f2\",\"formatInfo\":{\"type\":\"string\"}}},"
                + "{\"type\":\"fieldRelationShip\",\"inputField\":{\"type\":\"base\",\"name\":\"f3\","
                + "\"formatInfo\":{\"type\":\"string\"}},\"outputField\":{\"type\":\"base\",\"name\":\"f3\","
                + "\"formatInfo\":{\"type\":\"string\"}}},{\"type\":\"fieldRelationShip\","
                + "\"inputField\":{\"type\":\"base\",\"name\":\"ts\",\"formatInfo\":{\"type\":\"string\"}},"
                + "\"outputField\":{\"type\":\"base\",\"name\":\"ts\",\"formatInfo\":{\"type\":\"string\"}}}],"
                + "\"distinctFields\":[{\"type\":\"base\",\"name\":\"f1\",\"formatInfo\":{\"type\":\"string\"}},"
                + "{\"type\":\"base\",\"name\":\"f2\",\"formatInfo\":{\"type\":\"string\"}}],"
                + "\"orderField\":{\"type\":\"base\",\"name\":\"ts\",\"formatInfo\":{\"type\":\"string\"}},"
                + "\"orderDirection\":\"ASC\"}";
    }
}