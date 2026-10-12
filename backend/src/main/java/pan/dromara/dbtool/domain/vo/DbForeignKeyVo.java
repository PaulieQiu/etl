package pan.dromara.dbtool.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 数据库外键信息 VO（本表引用其他表）
 *
 * @author doubao
 */
@Data
public class DbForeignKeyVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 外键名
     */
    private String fkName;

    /**
     * 本表名
     */
    private String tableName;

    /**
     * 本表列名
     */
    private String columnName;

    /**
     * 列序号
     */
    private Integer ordinalPosition;

    /**
     * 被引用表名
     */
    private String referencedTableName;

    /**
     * 被引用列名
     */
    private String referencedColumnName;

    /**
     * 更新规则(NO ACTION/CASCADE/SET NULL/SET DEFAULT/RESTRICT)
     */
    private String updateRule;

    /**
     * 删除规则
     */
    private String deleteRule;
}
