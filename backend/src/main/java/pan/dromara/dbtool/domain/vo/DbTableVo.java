package pan.dromara.dbtool.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 数据库表信息 VO
 *
 * @author doubao
 */
@Data
public class DbTableVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 表名
     */
    private String tableName;

    /**
     * 表注释
     */
    private String tableComment;

    /**
     * 表类型(BASE TABLE / VIEW)
     */
    private String tableType;

    /**
     * 引擎
     */
    private String engine;

    /**
     * 创建时间
     */
    private String createTime;

    /**
     * 行数(估算)
     */
    private Long tableRows;
}
