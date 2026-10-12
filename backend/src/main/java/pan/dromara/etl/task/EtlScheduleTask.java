package pan.dromara.etl.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pan.dromara.etl.domain.EtlJob;
import pan.dromara.etl.service.IEtlJobService;

import java.util.Date;
import java.util.List;

/**
 * ETL 任务调度扫描器
 * 每 30 秒扫描启用调度的任务, 到达 nextRunTime 即触发执行并更新下次执行时间
 *
 * @author doubao
 */
@Slf4j
@Component
public class EtlScheduleTask {

    private final IEtlJobService jobService;

    public EtlScheduleTask(IEtlJobService jobService) {
        this.jobService = jobService;
    }

    @Scheduled(fixedRate = 30_000, initialDelay = 20_000)
    public void scan() {
        try {
            List<EtlJob> jobs = jobService.listScheduleEnabledJobs();
            Date now = new Date();
            for (EtlJob job : jobs) {
                Date next = job.getNextRunTime();
                if (next != null && !next.after(now)) {
                    log.info("ETL 调度触发任务: id={}, name={}", job.getId(), job.getJobName());
                    try {
                        jobService.execute(job.getId());
                        jobService.updateNextRunTime(job.getId());
                    } catch (Exception e) {
                        log.error("ETL 调度执行失败: id={}, err={}", job.getId(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("ETL 调度扫描异常: {}", e.getMessage());
        }
    }
}
