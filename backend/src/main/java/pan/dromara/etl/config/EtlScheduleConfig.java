package pan.dromara.etl.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ETL 模块调度开关
 * 独立于 snail-job, 供 ETL 任务 cron 扫描使用
 *
 * @author doubao
 */
@Configuration
@EnableScheduling
public class EtlScheduleConfig {
}
