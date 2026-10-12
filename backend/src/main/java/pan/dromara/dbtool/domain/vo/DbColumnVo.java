package pan.dromara.dbtool.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 数据库列信息 VO
 *
 * @author doubao
 */
@Data
public class DbColumnVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 列名
     */
    private String columnName;

    /**
     * 数据类型(如 varchar/int)
     */
    private String dataType;

    /**
     * 完整列类型(如 varchar(64))
     */
    private String columnType;

    /**
     * 是否可空(YES/NO)
     */
    private String nullable;

    /**
     * 默认值
     */
    private String defaultValue;

    /**
     * 列注释
     */
    private String columnComment;

    /**
     * 键类型(PRI/UNI/MUL)
     */
    private String columnKey;

    /**
     * 是否主键
     */
    private Boolean primary;

    /**
     * 额外信息(auto_increment)
     */
    private String extra;

    /**
     * 字符集
     */
    private String characterSet;

    /**
     * 排序规则
     */
    private String collation;
}
