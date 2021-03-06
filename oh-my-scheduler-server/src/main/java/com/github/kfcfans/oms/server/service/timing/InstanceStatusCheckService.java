package com.github.kfcfans.oms.server.service.timing;

import com.github.kfcfans.oms.common.InstanceStatus;
import com.github.kfcfans.oms.common.SystemInstanceResult;
import com.github.kfcfans.oms.common.TimeExpressionType;
import com.github.kfcfans.oms.server.common.constans.JobStatus;
import com.github.kfcfans.oms.server.akka.OhMyServer;
import com.github.kfcfans.oms.server.persistence.core.model.AppInfoDO;
import com.github.kfcfans.oms.server.persistence.core.model.InstanceInfoDO;
import com.github.kfcfans.oms.server.persistence.core.model.JobInfoDO;
import com.github.kfcfans.oms.server.persistence.core.repository.AppInfoRepository;
import com.github.kfcfans.oms.server.persistence.core.repository.InstanceInfoRepository;
import com.github.kfcfans.oms.server.persistence.core.repository.JobInfoRepository;
import com.github.kfcfans.oms.server.service.DispatchService;
import com.github.kfcfans.oms.server.service.instance.InstanceManager;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 定时状态检查
 *
 * @author tjq
 * @since 2020/4/7
 */
@Slf4j
@Service
public class InstanceStatusCheckService {

    private static final int MAX_BATCH_NUM = 10;
    private static final long DISPATCH_TIMEOUT_MS = 30000;
    private static final long RECEIVE_TIMEOUT_MS = 60000;
    private static final long RUNNING_TIMEOUT_MS = 60000;

    @Resource
    private DispatchService dispatchService;
    @Resource
    private AppInfoRepository appInfoRepository;
    @Resource
    private InstanceInfoRepository instanceInfoRepository;
    @Resource
    private JobInfoRepository jobInfoRepository;

    @Async("omsTimingPool")
    @Scheduled(fixedRate = 10000)
    public void timingStatusCheck() {
        Stopwatch stopwatch = Stopwatch.createStarted();
        try {
            innerCheck();
        }catch (Exception e) {
            log.error("[InstanceStatusCheckService] status check failed.", e);
        }
        log.info("[InstanceStatusCheckService] status check using {}.", stopwatch.stop());
    }

    private void innerCheck() {

        // 查询DB获取该Server需要负责的AppGroup
        List<AppInfoDO> appInfoList = appInfoRepository.findAllByCurrentServer(OhMyServer.getActorSystemAddress());
        if (CollectionUtils.isEmpty(appInfoList)) {
            log.info("[InstanceStatusCheckService] current server has no app's job to check");
            return;
        }
        List<Long> allAppIds = appInfoList.stream().map(AppInfoDO::getId).collect(Collectors.toList());

        Lists.partition(allAppIds, MAX_BATCH_NUM).forEach(partAppIds -> {

            // 1. 检查等待 WAITING_DISPATCH 状态的任务
            long threshold = System.currentTimeMillis() - DISPATCH_TIMEOUT_MS;
            List<InstanceInfoDO> waitingDispatchInstances = instanceInfoRepository.findByAppIdInAndStatusAndExpectedTriggerTimeLessThan(partAppIds, InstanceStatus.WAITING_DISPATCH.getV(), threshold);
            if (!CollectionUtils.isEmpty(waitingDispatchInstances)) {
                log.warn("[InstanceStatusCheckService] instances({}) is not triggered as expected.", waitingDispatchInstances);
                waitingDispatchInstances.forEach(instance -> {

                    // 过滤因为失败重试而改成 WAITING_DISPATCH 状态的任务实例
                    long t = System.currentTimeMillis() - instance.getGmtModified().getTime();
                    if (t < DISPATCH_TIMEOUT_MS) {
                        return;
                    }

                    // 重新派发(orElseGet用于消除编译器警告...)
                    JobInfoDO jobInfoDO = jobInfoRepository.findById(instance.getJobId()).orElseGet(JobInfoDO::new);
                    dispatchService.redispatch(jobInfoDO, instance.getInstanceId(), 0);
                });
            }

            // 2. 检查 WAITING_WORKER_RECEIVE 状态的任务
            threshold = System.currentTimeMillis() - RECEIVE_TIMEOUT_MS;
            List<InstanceInfoDO> waitingWorkerReceiveInstances = instanceInfoRepository.findByAppIdInAndStatusAndActualTriggerTimeLessThan(partAppIds, InstanceStatus.WAITING_WORKER_RECEIVE.getV(), threshold);
            if (!CollectionUtils.isEmpty(waitingWorkerReceiveInstances)) {
                log.warn("[InstanceStatusCheckService] instances({}) did n’t receive any reply from worker.", waitingWorkerReceiveInstances);
                waitingWorkerReceiveInstances.forEach(instance -> {
                    // 重新派发
                    JobInfoDO jobInfoDO = jobInfoRepository.findById(instance.getJobId()).orElseGet(JobInfoDO::new);
                    dispatchService.redispatch(jobInfoDO, instance.getInstanceId(), 0);
                });
            }

            // 3. 检查 RUNNING 状态的任务（一定时间没收到 TaskTracker 的状态报告，视为失败）
            threshold = System.currentTimeMillis() - RUNNING_TIMEOUT_MS;
            List<InstanceInfoDO> failedInstances = instanceInfoRepository.findByAppIdInAndStatusAndGmtModifiedBefore(partAppIds, InstanceStatus.RUNNING.getV(), new Date(threshold));
            if (!CollectionUtils.isEmpty(failedInstances)) {
                log.warn("[InstanceStatusCheckService] instances({}) has not received status report for a long time.", failedInstances);
                failedInstances.forEach(instance -> {

                    JobInfoDO jobInfoDO = jobInfoRepository.findById(instance.getJobId()).orElseGet(JobInfoDO::new);
                    TimeExpressionType timeExpressionType = TimeExpressionType.of(jobInfoDO.getTimeExpressionType());
                    JobStatus jobStatus = JobStatus.of(jobInfoDO.getStatus());

                    // 如果任务已关闭，则不进行重试，将任务置为失败即可；秒级任务也直接置为失败，由派发器重新调度
                    if (jobStatus != JobStatus.ENABLE || TimeExpressionType.frequentTypes.contains(timeExpressionType.getV())) {
                        updateFailedInstance(instance);
                        return;
                    }

                    // CRON 和 API一样，失败次数 + 1，根据重试配置进行重试
                    if (instance.getRunningTimes() > jobInfoDO.getInstanceRetryNum()) {
                        dispatchService.redispatch(jobInfoDO, instance.getInstanceId(), instance.getRunningTimes());
                    }else {
                        updateFailedInstance(instance);
                    }

                });
            }
        });
    }

    /**
     * 处理上报超时而失败的任务实例
     */
    private void updateFailedInstance(InstanceInfoDO instance) {

        log.warn("[InstanceStatusCheckService] detected instance(instanceId={},jobId={})'s TaskTracker report timeout,this instance is considered a failure.", instance.getInstanceId(), instance.getJobId());

        instance.setStatus(InstanceStatus.FAILED.getV());
        instance.setFinishedTime(System.currentTimeMillis());
        instance.setGmtModified(new Date());
        instance.setResult(SystemInstanceResult.REPORT_TIMEOUT);
        instanceInfoRepository.saveAndFlush(instance);

        InstanceManager.processFinishedInstance(instance.getInstanceId(), InstanceStatus.FAILED.getV());
    }
}
