package pan.dromara.dbtool.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 数据库索引信息 VO
 *
 * @author doubao
 */
@Data
public class DbIndexVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 索引名
     */
    private String indexName;

    /**
     * 索引列
     */
    private String columnName;

    /**
     * 列在索引中的序号
     */
    private Integer seqInIndex;

    /**
     * 是否非唯一(0唯一 1非唯一)
     */
    private Integer nonUnique;

    /**
     * 索引类型(BTREE等)
     */
    private String indexType;

    /**
     * 索引注释
     */
    private String indexComment;

    /**
     * 索引方式
     */
    private String indexMethod;
}
