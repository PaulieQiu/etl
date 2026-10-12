package pan.dromara.etl.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * ETL任务配置 实体
 *
 * @author doubao
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("etl_job")
public class EtlJob extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 父任务ID(0=总任务, 其他=子任务)
     */
    private Long parentId;

    /**
     * 子任务列表(树形展示用, 非表字段)
     */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private java.util.List<EtlJob> children;

    /**
     * 节点类型(树形展示用, 非表字段): DIR=目录 / PARENT=总任务 / CHILD=子任务
     */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String nodeType;

    /**
     * 任务名称
     */
    private String jobName;

    /**
     * 任务类型: DATAX/KETTLE
     */
    private String jobType;

    /**
     * 调度cron表达式
     */
    private String scheduleCron;

    /**
     * 调度启用: 0否 1是
     */
    private String scheduleEnabled;

    /**
     * 上次执行时间
     */
    private java.util.Date lastRunTime;

    /**
     * 下次执行时间
     */
    private java.util.Date nextRunTime;

    /**
     * 源数据源ID(引用sys_db_conn.id, 源类型TABLE时)
     */
    private Long srcConnId;

    /**
     * 源类型: TABLE/EXCEL/API
     */
    private String srcType;

    /**
     * 源库/模式
     */
    private String srcSchema;

    /**
     * 源表
     */
    private String srcTable;

    /**
     * 源过滤条件(where, 不含where关键字)
     */
    private String srcWhere;

    /**
     * 源Excel文件路径
     */
    private String srcExcelPath;

    /**
     * 源Excel工作表
     */
    private String srcExcelSheet;

    /**
     * 源Excel表头行(0=首行)
     */
    private Integer srcExcelHeader;

    /**
     * 源Excel数据起始行(0-based; null=表头下一行)
     */
    private Integer srcExcelDataRow;

    /**
     * 源Excel列范围起始列(1-based, 如C=3; null=不限)
     */
    private Integer srcExcelColFrom;

    /**
     * 源Excel列范围结束列(1-based, 如AL=38; null=不限)
     */
    private Integer srcExcelColTo;

    /**
     * 源API地址
     */
    private String srcApiUrl;

    /**
     * 源API方法(GET/POST)
     */
    private String srcApiMethod;

    /**
     * 源API请求头JSON
     */
    private String srcApiHeaders;

    /**
     * 源API请求体
     */
    private String srcApiBody;

    /**
     * 源API数据路径(JSON数组指针, 如data.list, 空=根数组)
     */
    private String srcApiDataPath;

    /**
     * 源API分页参数名(页码/游标)
     */
    private String srcApiPageField;

    /**
     * 源API每页大小(0不分页)
     */
    private Integer srcApiPageSize;

    /**
     * 多源表配置JSON(数组: [{srcConnId,srcSchema,srcTable,srcWhere}])
     */
    private String srcList;

    /**
     * 清洗规则JSON(数组: [{ruleName,ruleType,ruleConfig,connId,schema,table}])
     */
    private String cleanRules;

    /**
     * 质量规则JSON(数组: [{ruleName,ruleType,ruleConfig}])
     */
    private String qualityRules;

    /**
     * 清洗结果数据源ID
     */
    private Long cleanConnId;

    /**
     * 清洗结果库/模式
     */
    private String cleanSchema;

    /**
     * 清洗结果表
     */
    private String cleanTable;

    /**
     * 清洗结果写入模式: INSERT/REPLACE/TRUNCATE
     */
    private String cleanWriteMode;

    /**
     * 段2映射JSON(结果表字段→目标表字段): [{srcField,dstField}], 未配清洗结果表时为空
     */
    private String dstMapping;

    /**
     * 目标数据源ID(引用sys_db_conn.id, 目标类型TABLE时)
     */
    private Long dstConnId;

    /**
     * 目标类型: TABLE/EXCEL/API
     */
    private String dstType;

    /**
     * 目标库/模式
     */
    private String dstSchema;

    /**
     * 目标表
     */
    private String dstTable;

    /**
     * 目标Excel文件路径
     */
    private String dstExcelPath;

    /**
     * 目标Excel工作表
     */
    private String dstExcelSheet;

    /**
     * 目标API地址(支持${字段}占位)
     */
    private String dstApiUrl;

    /**
     * 目标API方法(POST/PUT)
     */
    private String dstApiMethod;

    /**
     * 目标API请求头JSON
     */
    private String dstApiHeaders;

    /**
     * API鉴权配置JSON(源): {"type":"NONE|STATIC|TOKEN","tokenUrl":"...","tokenMethod":"POST",
     * "tokenHeaders":"{...}","tokenBody":"{...}","tokenPath":"data.token"}
     */
    private String srcApiAuth;

    /**
     * API鉴权配置JSON(目标): 结构同 srcApiAuth
     */
    private String dstApiAuth;

    /**
     * 目标API请求体模板(${字段}占位)
     */
    private String dstApiBodyTpl;

    /**
     * 写入模式: INSERT/REPLACE/TRUNCATE/DELETE_INSERT
     */
    private String dstWriteMode;

    /**
     * 按字段替换键(DELETE_INSERT 模式: 删除目标表中匹配该键的记录后插入)
     */
    private String dstReplaceKey;

    /**
     * 字段映射JSON(缺省按同名自动)
     */
    private String fieldMapping;

    /**
     * 并发通道数
     */
    private Integer speedChannel;

    /**
     * 限速(字节/秒, 0不限)
     */
    private Long speedByte;

    /**
     * 批大小
     */
    private Integer batchSize;

    /**
     * 生成的DataX配置JSON(缓存)
     */
    private String jobJson;

    /**
     * Kettle任务类型: TRANS转换/JOB作业
     */
    private String kettleType;

    /**
     * Kettle转换/作业XML定义
     */
    private String kettleXml;

    /**
     * 管线段1转换XML(源→清洗结果表), Job模式用
     */
    private String pipeSeg1Xml;

    /**
     * 管线段2转换XML(清洗结果表→目标表), Job模式用
     */
    private String pipeSeg2Xml;

    /**
     * 失败重试次数
     */
    private Integer retryCount;

    /**
     * 重试间隔(秒)
     */
    private Integer retryInterval;

    /**
     * 子任务执行方式: SEQUENTIAL顺序/PARALLEL并行
     */
    private String runMode;

    /**
     * 增量字段(为空则不增量)
     */
    private String incrField;

    /**
     * 增量方式: NONE/DATE/ID
     */
    private String incrMode;

    /**
     * 上次增量值(执行后自动更新为字段最大值)
     */
    private String incrLastValue;

    /**
     * 告警方式: NONE/MAIL
     */
    private String notifyType;

    /**
     * 告警邮箱(逗号分隔)
     */
    private String notifyMails;

    /**
     * 告警时机: FAILED/SUCCESS/ALL
     */
    private String notifyOn;

    /**
     * DataX 脏数据比例容忍(0=不容忍)
     */
    private Double errorLimitPercent;

    /**
     * DataX 脏数据条数容忍
     */
    private Long errorLimitCount;

    /**
     * 运行进程PID(运行中记录, 供取消)
     */
    private Long execPid;

    /**
     * 前置依赖子任务ID(逗号分隔, 执行前需前置任务全部成功)
     */
    private String depIds;

    /**
     * 运行状态: RUNNING/IDLE
     */
    private String execState;

    /**
     * 状态: 0正常 1停用
     */
    private String status;

    /**
     * 删除标志: 0存在 1删除
     */
    @TableLogic
    private String delFlag;
}
