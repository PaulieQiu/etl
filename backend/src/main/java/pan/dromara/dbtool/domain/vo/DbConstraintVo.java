package pan.dromara.dbtool.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 数据库约束信息 VO
 *
 * @author doubao
 */
@Data
public class DbConstraintVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 约束名
     */
    private String constraintName;

    /**
     * 约束类型(PRIMARY KEY/UNIQUE/CHECK/FOREIGN KEY)
     */
    private String constraintType;

    /**
     * 约束列名
     */
    private String columnName;

    /**
     * 列序号
     */
    private Integer ordinalPosition;

    /**
     * 约束定义
     */
    private String definition;
}
