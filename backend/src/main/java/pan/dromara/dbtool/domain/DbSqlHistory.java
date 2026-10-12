package pan.dromara.dbtool.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * SQL 执行历史 实体(P1 服务端化, 替代浏览器 localStorage)
 *
 * @author doubao
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dbtool_sql_history")
public class DbSqlHistory extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 用户ID(归属)
     */
    private Long userId;

    /**
     * 连接ID
     */
    private Long connId;

    /**
     * 连接名(冗余, 连接删除后仍可展示)
     */
    private String connName;

    /**
     * Schema
     */
    private String schemaName;

    /**
     * SQL 文本
     */
    private String sqlText;

    /**
     * 执行状态: SUCCESS/FAILED/CANCELLED/TIMED_OUT
     */
    private String status;

    /**
     * 影响行数
     */
    private Long rowsAffected;

    /**
     * 耗时(毫秒)
     */
    private Long durationMs;

    /**
     * 错误摘要(截断)
     */
    private String errorMsg;

    /**
     * 执行时间
     */
    private Date executedAt;

}
