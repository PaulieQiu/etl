package pan.dromara.etl.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ETL 引擎配置
 *
 * @author doubao
 */
@Data
@Component
@ConfigurationProperties(prefix = "etl")
public class EtlProperties {

    /**
     * 引擎根目录(含 datax/ 与 kettle/ 子目录)
     */
    private String engineDir = "D:/project/etl-engine";

    /**
     * DataX 任务临时目录(默认 engineDir/datax/job)
     */
    private String jobTempDir = "";

    /**
     * 执行超时(分钟, 0 不限制)
     */
    private int timeoutMin = 0;

    public String getJobTempDir() {
        if (jobTempDir == null || jobTempDir.isBlank()) {
            return engineDir + "/datax/job";
        }
        return jobTempDir;
    }

    public String getDataxHome() {
        return engineDir + "/datax";
    }
}
