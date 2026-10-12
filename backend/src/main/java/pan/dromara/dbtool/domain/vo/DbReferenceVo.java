package pan.dromara.dbtool.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 数据库引用信息 VO（其他表引用本表）
 *
 * @author doubao
 */
@Data
public class DbReferenceVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 外键名
     */
    private String fkName;

    /**
     * 引用表名
     */
    private String tableName;

    /**
     * 引用列名
     */
    private String columnName;

    /**
     * 列序号
     */
    private Integer ordinalPosition;

    /**
     * 被引用表名(本表)
     */
    private String referencedTableName;

    /**
     * 被引用列名
     */
    private String referencedColumnName;

    /**
     * 更新规则
     */
    private String updateRule;

    /**
     * 删除规则
     */
    private String deleteRule;
}
