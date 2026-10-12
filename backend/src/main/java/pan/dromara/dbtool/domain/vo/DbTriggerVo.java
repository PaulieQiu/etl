package pan.dromara.dbtool.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 数据库触发器信息 VO
 *
 * @author doubao
 */
@Data
public class DbTriggerVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 触发器名
     */
    private String triggerName;

    /**
     * 触发时机(BEFORE/AFTER/INSTEAD OF)
     */
    private String timing;

    /**
     * 触发事件(INSERT/UPDATE/DELETE)
     */
    private String event;

    /**
     * 表名
     */
    private String tableName;

    /**
     * 触发器定义/语句
     */
    private String statement;

    /**
     * 创建时间
     */
    private String created;

    /**
     * 状态(ENABLED/DISABLED)
     */
    private String status;
}
