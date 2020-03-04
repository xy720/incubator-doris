// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.load.loadv2;

import org.apache.doris.analysis.BrokerDesc;
import org.apache.doris.catalog.Catalog;
import org.apache.doris.catalog.Database;
import org.apache.doris.catalog.OlapTable;
import org.apache.doris.common.Config;
import org.apache.doris.common.FeMetaVersion;
import org.apache.doris.common.MetaNotFoundException;
import org.apache.doris.common.UserException;
import org.apache.doris.common.util.LogBuilder;
import org.apache.doris.common.util.LogKey;
import org.apache.doris.load.BrokerFileGroup;
import org.apache.doris.load.BrokerFileGroupAggInfo.FileGroupAggKey;
import org.apache.doris.load.EtlJobType;
import org.apache.doris.load.FailMsg;
import org.apache.doris.thrift.TUniqueId;
import org.apache.doris.transaction.TransactionState;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * There are 3 steps in BrokerLoadJob: BrokerPendingTask, LoadLoadingTask, CommitAndPublishTxn.
 * Step1: BrokerPendingTask will be created on method of unprotectedExecuteJob.
 * Step2: LoadLoadingTasks will be created by the method of onTaskFinished when BrokerPendingTask is finished.
 * Step3: CommitAndPublicTxn will be called by the method of onTaskFinished when all of LoadLoadingTasks are finished.
 */
public class BrokerLoadJob extends BulkLoadJob {

    private static final Logger LOG = LogManager.getLogger(BrokerLoadJob.class);

    // input params
    private BrokerDesc brokerDesc;

    // only for log replay
    public BrokerLoadJob() {
        super();
        this.jobType = EtlJobType.BROKER;
    }

    BrokerLoadJob(long dbId, String label, BrokerDesc brokerDesc, String originStmt)
            throws MetaNotFoundException {
        super(dbId, label, originStmt);
        this.timeoutSecond = Config.broker_load_default_timeout_second;
        this.brokerDesc = brokerDesc;
        this.jobType = EtlJobType.BROKER;
    }

    @Override
    protected void unprotectedExecuteJob() {
        LoadTask task = new BrokerLoadPendingTask(this, fileGroupAggInfo.getAggKeyToFileGroups(), brokerDesc);
        idToTasks.put(task.getSignature(), task);
        Catalog.getCurrentCatalog().getLoadTaskScheduler().submit(task);
    }

    /**
     * Situation1: When attachment is instance of BrokerPendingTaskAttachment, this method is called by broker pending task.
     * LoadLoadingTask will be created after BrokerPendingTask is finished.
     * Situation2: When attachment is instance of BrokerLoadingTaskAttachment, this method is called by LoadLoadingTask.
     * CommitTxn will be called after all of LoadingTasks are finished.
     *
     * @param attachment
     */
    @Override
    public void onTaskFinished(TaskAttachment attachment) {
        if (attachment instanceof BrokerPendingTaskAttachment) {
            onPendingTaskFinished((BrokerPendingTaskAttachment) attachment);
        } else if (attachment instanceof BrokerLoadingTaskAttachment) {
            onLoadingTaskFinished((BrokerLoadingTaskAttachment) attachment);
        }
    }

    /**
     * step1: divide job into loading task
     * step2: init the plan of task
     * step3: submit tasks into loadingTaskExecutor
     * @param attachment BrokerPendingTaskAttachment
     */
    private void onPendingTaskFinished(BrokerPendingTaskAttachment attachment) {
        writeLock();
        try {
            // check if job has been cancelled
            if (isTxnDone()) {
                LOG.warn(new LogBuilder(LogKey.LOAD_JOB, id)
                                 .add("state", state)
                                 .add("error_msg", "this task will be ignored when job is: " + state)
                                 .build());
                return;
            }

            if (finishedTaskIds.contains(attachment.getTaskId())) {
                LOG.warn(new LogBuilder(LogKey.LOAD_JOB, id)
                                 .add("task_id", attachment.getTaskId())
                                 .add("error_msg", "this is a duplicated callback of pending task "
                                         + "when broker already has loading task")
                                 .build());
                return;
            }

            // add task id into finishedTaskIds
            finishedTaskIds.add(attachment.getTaskId());
        } finally {
            writeUnlock();
        }

        try {
            Database db = getDb();
            createLoadingTask(db, attachment);
        } catch (UserException e) {
            LOG.warn(new LogBuilder(LogKey.LOAD_JOB, id)
                             .add("database_id", dbId)
                             .add("error_msg", "Failed to divide job into loading task.")
                             .build(), e);
            cancelJobWithoutCheck(new FailMsg(FailMsg.CancelType.ETL_RUN_FAIL, e.getMessage()), true, true);
            return;
        }

        loadStartTimestamp = System.currentTimeMillis();
    }

    private void createLoadingTask(Database db, BrokerPendingTaskAttachment attachment) throws UserException {
        // divide job into broker loading task by table
        db.readLock();
        try {
            List<LoadLoadingTask> newLoadingTasks = Lists.newArrayList();
            for (Map.Entry<FileGroupAggKey, List<BrokerFileGroup>> entry : fileGroupAggInfo.getAggKeyToFileGroups().entrySet()) {
                FileGroupAggKey aggKey = entry.getKey();
                List<BrokerFileGroup> brokerFileGroups = entry.getValue();
                long tableId = aggKey.getTableId();
                OlapTable table = (OlapTable) db.getTable(tableId);
                if (table == null) {
                    LOG.warn(new LogBuilder(LogKey.LOAD_JOB, id)
                                     .add("database_id", dbId)
                                     .add("table_id", tableId)
                                     .add("error_msg", "Failed to divide job into loading task when table not found")
                                     .build());
                    throw new MetaNotFoundException("Failed to divide job into loading task when table "
                                                            + tableId + " not found");
                }

                // Generate loading task and init the plan of task
                LoadLoadingTask task = new LoadLoadingTask(db, table, brokerDesc,
                        brokerFileGroups, getDeadlineMs(), execMemLimit,
                        strictMode, transactionId, this, timezone, timeoutSecond);
                UUID uuid = UUID.randomUUID();
                TUniqueId loadId = new TUniqueId(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
                task.init(loadId, attachment.getFileStatusByTable(aggKey), attachment.getFileNumByTable(aggKey));
                idToTasks.put(task.getSignature(), task);
                // idToTasks contains previous LoadPendingTasks, so idToTasks is just used to save all tasks.
                // use newLoadingTasks to save new created loading tasks and submit them later.
                newLoadingTasks.add(task);
                // load id will be added to loadStatistic when executing this task

                // save all related tables and rollups in transaction state
                TransactionState txnState = Catalog.getCurrentGlobalTransactionMgr().getTransactionState(transactionId);
                if (txnState == null) {
                    throw new UserException("txn does not exist: " + transactionId);
                }
                txnState.addTableIndexes(table);
            }
            // submit all tasks together
            for (LoadTask loadTask : newLoadingTasks) {
                Catalog.getCurrentCatalog().getLoadTaskScheduler().submit(loadTask);
            }
        } finally {
            db.readUnlock();
        }
    }

    private void onLoadingTaskFinished(BrokerLoadingTaskAttachment attachment) {
        writeLock();
        try {
            // check if job has been cancelled
            if (isTxnDone()) {
                LOG.warn(new LogBuilder(LogKey.LOAD_JOB, id)
                                 .add("state", state)
                                 .add("error_msg", "this task will be ignored when job is: " + state)
                                 .build());
                return;
            }

            // check if task has been finished
            if (finishedTaskIds.contains(attachment.getTaskId())) {
                LOG.warn(new LogBuilder(LogKey.LOAD_JOB, id)
                                 .add("task_id", attachment.getTaskId())
                                 .add("error_msg", "this is a duplicated callback of loading task").build());
                return;
            }

            // update loading status
            finishedTaskIds.add(attachment.getTaskId());
            updateLoadingStatus(attachment);

            // begin commit txn when all of loading tasks have been finished
            if (finishedTaskIds.size() != idToTasks.size()) {
                return;
            }
        } finally {
            writeUnlock();
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug(new LogBuilder(LogKey.LOAD_JOB, id)
                              .add("commit_infos", Joiner.on(",").join(commitInfos))
                              .build());
        }

        // check data quality
        if (!checkDataQuality()) {
            cancelJobWithoutCheck(new FailMsg(FailMsg.CancelType.ETL_QUALITY_UNSATISFIED, QUALITY_FAIL_MSG),
                    true, true);
            return;
        }
        Database db = null;
        try {
            db = getDb();
        } catch (MetaNotFoundException e) {
            LOG.warn(new LogBuilder(LogKey.LOAD_JOB, id)
                             .add("database_id", dbId)
                             .add("error_msg", "db has been deleted when job is loading")
                             .build(), e);
            cancelJobWithoutCheck(new FailMsg(FailMsg.CancelType.LOAD_RUN_FAIL, e.getMessage()), true, true);
        }
        db.writeLock();
        try {
            LOG.info(new LogBuilder(LogKey.LOAD_JOB, id)
                             .add("txn_id", transactionId)
                             .add("msg", "Load job try to commit txn")
                             .build());
            Catalog.getCurrentGlobalTransactionMgr().commitTransaction(
                    dbId, transactionId, commitInfos,
                    new LoadJobFinalOperation(id, loadingStatus, progress, loadStartTimestamp,
                                              finishTimestamp, state, failMsg));
        } catch (UserException e) {
            LOG.warn(new LogBuilder(LogKey.LOAD_JOB, id)
                             .add("database_id", dbId)
                             .add("error_msg", "Failed to commit txn with error:" + e.getMessage())
                             .build(), e);
            cancelJobWithoutCheck(new FailMsg(FailMsg.CancelType.LOAD_RUN_FAIL, e.getMessage()), true, true);
            return;
        } finally {
            db.writeUnlock();
        }
    }

    private void updateLoadingStatus(BrokerLoadingTaskAttachment attachment) {
        loadingStatus.replaceCounter(DPP_ABNORMAL_ALL,
                                     increaseCounter(DPP_ABNORMAL_ALL, attachment.getCounter(DPP_ABNORMAL_ALL)));
        loadingStatus.replaceCounter(DPP_NORMAL_ALL,
                                     increaseCounter(DPP_NORMAL_ALL, attachment.getCounter(DPP_NORMAL_ALL)));
        loadingStatus.replaceCounter(UNSELECTED_ROWS,
                                     increaseCounter(UNSELECTED_ROWS, attachment.getCounter(UNSELECTED_ROWS)));
        if (attachment.getTrackingUrl() != null) {
            loadingStatus.setTrackingUrl(attachment.getTrackingUrl());
        }
        commitInfos.addAll(attachment.getCommitInfoList());
        progress = (int) ((double) finishedTaskIds.size() / idToTasks.size() * 100);
        if (progress == 100) {
            progress = 99;
        }
    }

    private String increaseCounter(String key, String deltaValue) {
        long value = 0;
        if (loadingStatus.getCounters().containsKey(key)) {
            value = Long.valueOf(loadingStatus.getCounters().get(key));
        }
        if (deltaValue != null) {
            value += Long.valueOf(deltaValue);
        }
        return String.valueOf(value);
    }

    @Override
    protected void replayTxnAttachment(TransactionState txnState) {
        if (txnState.getTxnCommitAttachment() == null) {
            // The txn attachment maybe null when broker load has been cancelled without attachment.
            // The end log of broker load has been record but the callback id of txnState hasn't been removed
            // So the callback of txn is executed when log of txn aborted is replayed.
            return;
        }
        unprotectReadEndOperation((LoadJobFinalOperation) txnState.getTxnCommitAttachment());
    }

    @Override
    public void write(DataOutput out) throws IOException {
        super.write(out);
        brokerDesc.write(out);
    }

    public void readFields(DataInput in) throws IOException {
        super.readFields(in, brokerDesc);
        if (Catalog.getCurrentCatalogJournalVersion() >= FeMetaVersion.VERSION_73) {
            brokerDesc = BrokerDesc.read(in);
        }
    }

}
