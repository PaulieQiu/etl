package pan.dromara.etl.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * ETL任务执行日志 实体
 *
 * @author doubao
 */
@Data
@TableName("etl_job_log")
public class EtlJobLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 任务ID
     */
    private Long jobId;

    /**
     * 子任务ID(总任务执行时的子任务)
     */
    private Long subtaskId;

    /**
     * 节点类型: TASK/SUBTASK/DATA_SOURCE/TABLE/CLEAN_RULE/QUALITY_RULE
     */
    private String nodeType;

    /**
     * 节点ID(数据源ID/表名/规则名)
     */
    private String nodeId;

    /**
     * 节点名称
     */
    private String nodeName;

    /**
     * 执行状态: RUNNING/SUCCESS/FAILED
     */
    private String runStatus;

    /**
     * 开始时间
     */
    private Date startTime;

    /**
     * 结束时间
     */
    private Date endTime;

    /**
     * 读写总条数
     */
    private Long totalRecords;

    /**
     * 读取速度(条/秒)
     */
    private String readSpeed;

    /**
     * 写入速度(条/秒)
     */
    private String writeSpeed;

    /**
     * 引擎输出日志
     */
    private String logText;

    /**
     * 错误信息
     */
    private String errorMsg;

    /**
     * 创建时间
     */
    private Date createTime;
}
