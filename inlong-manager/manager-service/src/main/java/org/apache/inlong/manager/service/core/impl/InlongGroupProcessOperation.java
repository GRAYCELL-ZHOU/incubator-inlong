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

package org.apache.inlong.manager.service.core.impl;

import org.apache.inlong.manager.common.enums.GroupState;
import org.apache.inlong.manager.common.pojo.group.InlongGroupInfo;
import org.apache.inlong.manager.common.pojo.stream.InlongStreamInfo;
import org.apache.inlong.manager.common.pojo.stream.StreamBriefResponse;
import org.apache.inlong.manager.common.pojo.workflow.WorkflowResult;
import org.apache.inlong.manager.common.pojo.workflow.form.NewGroupProcessForm;
import org.apache.inlong.manager.common.pojo.workflow.form.UpdateGroupProcessForm;
import org.apache.inlong.manager.common.pojo.workflow.form.UpdateGroupProcessForm.OperateType;
import org.apache.inlong.manager.common.util.CommonBeanUtils;
import org.apache.inlong.manager.dao.entity.InlongStreamEntity;
import org.apache.inlong.manager.dao.mapper.InlongStreamEntityMapper;
import org.apache.inlong.manager.service.core.InlongGroupService;
import org.apache.inlong.manager.service.core.InlongStreamService;
import org.apache.inlong.manager.service.workflow.ProcessName;
import org.apache.inlong.manager.service.workflow.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Operation related to inlong group process
 */
@Service
public class InlongGroupProcessOperation {

    private static final Logger LOGGER = LoggerFactory.getLogger(InlongGroupProcessOperation.class);
    @Autowired
    private InlongGroupService groupService;
    @Autowired
    private WorkflowService workflowService;
    @Autowired
    private InlongStreamService streamService;

    @Autowired
    private InlongStreamEntityMapper streamMapper;

    /**
     * Allocate resource application groups for access services and initiate an approval process
     *
     * @param groupId Inlong group id
     * @param operator Operator name
     * @return Workflow result
     */
    public WorkflowResult startProcess(String groupId, String operator) {
        LOGGER.info("begin to start approve process, groupId = {}, operator = {}", groupId, operator);
        groupService.updateStatus(groupId, GroupState.GROUP_WAIT_APPROVAL.getCode(), operator);
        // Initiate the approval process
        NewGroupProcessForm form = genNewGroupProcessForm(groupId);
        return workflowService.start(ProcessName.NEW_GROUP_PROCESS, operator, form);
    }

    /**
     * Suspend resource application group which is started up successfully,
     * stop source and sort task related to application group asynchronously,
     * persist the application status if necessary.
     *
     * @return Workflow result
     */
    public WorkflowResult suspendProcess(String groupId, String operator) {
        LOGGER.info("begin to suspend process, groupId = {}, operator = {}", groupId, operator);
        groupService.updateStatus(groupId, GroupState.GROUP_SUSPEND_ING.getCode(), operator);
        UpdateGroupProcessForm form = genUpdateGroupProcessForm(groupId, OperateType.SUSPEND);
        return workflowService.start(ProcessName.SUSPEND_GROUP_PROCESS, operator, form);
    }

    /**
     * Restart resource application group which is suspended successfully,
     * starting from the last persist snapshot.
     *
     * @return Workflow result
     */
    public WorkflowResult restartProcess(String groupId, String operator) {
        LOGGER.info("begin to restart process, groupId = {}, operator = {}", groupId, operator);
        groupService.updateStatus(groupId, GroupState.GROUP_RESTART_ING.getCode(), operator);
        UpdateGroupProcessForm form = genUpdateGroupProcessForm(groupId, OperateType.RESTART);
        return workflowService.start(ProcessName.RESTART_GROUP_PROCESS, operator, form);
    }

    /**
     * Delete resource application group logically and delete related resource
     */
    public boolean deleteProcess(String groupId, String operator) {
        LOGGER.info("begin to delete process, groupId = {}, operator = {}", groupId, operator);
        try {
            UpdateGroupProcessForm form = genUpdateGroupProcessForm(groupId, OperateType.DELETE);
            workflowService.start(ProcessName.DELETE_GROUP_PROCESS, operator, form);
        } catch (Exception ex) {
            LOGGER.error("exception while delete process, groupId = {}, operator = {}", groupId, operator, ex);
            throw ex;
        }
        return groupService.delete(groupId, operator);
    }

    /**
     * Generate the form of [New Group Workflow]
     */
    public NewGroupProcessForm genNewGroupProcessForm(String groupId) {
        NewGroupProcessForm form = new NewGroupProcessForm();
        InlongGroupInfo groupInfo = groupService.get(groupId);
        form.setGroupInfo(groupInfo);
        List<StreamBriefResponse> infoList = streamService.getBriefList(groupInfo.getInlongGroupId());
        form.setStreamInfoList(infoList);
        return form;
    }

    private UpdateGroupProcessForm genUpdateGroupProcessForm(String groupId, OperateType operateType) {
        InlongGroupInfo groupInfo = groupService.get(groupId);
        UpdateGroupProcessForm form = new UpdateGroupProcessForm();
        if (OperateType.RESTART == operateType) {
            List<InlongStreamEntity> inlongStreamEntityList =
                    streamMapper.selectByGroupId(groupInfo.getInlongGroupId());
            List<InlongStreamInfo> inlongStreamInfoList = CommonBeanUtils.copyListProperties(inlongStreamEntityList,
                    InlongStreamInfo::new);
            form.setInlongStreamInfoList(inlongStreamInfoList);
        }
        form.setGroupInfo(groupInfo);
        form.setOperateType(operateType);
        return form;
    }

}
