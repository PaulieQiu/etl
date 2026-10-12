package pan.dromara.dbtool.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * SQL 执行结果 VO
 *
 * @author doubao
 */
@Data
public class DbResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 消息
     */
    private String message;

    /**
     * 语句类型: select / update / ddl / other
     */
    private String sqlType;

    /**
     * 列名(查询语句)
     */
    private List<String> columns;

    /**
     * 列类型(查询语句, 与 columns 一一对应, 如 VARCHAR/INT/DATETIME)
     */
    private List<String> columnTypes;

    /**
     * 数据行(查询语句)
     */
    private List<Map<String, Object>> rows;

    /**
     * 总条数
     */
    private Long total;

    /**
     * 影响行数(更新语句)
     */
    private Integer affectedRows;

    /**
     * 耗时(ms)
     */
    private Long costMs;

    /**
     * 是否高危SQL(需确认后执行)
     */
    private Boolean highRisk;

    /**
     * 高危SQL警告信息
     */
    private String warning;

    /**
     * 一次性高危操作确认凭证
     */
    private String confirmationToken;
}
