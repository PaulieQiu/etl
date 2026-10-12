package pan.dromara.dbtool.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 已保存 SQL 脚本 实体(P1 服务端化, 替代浏览器 localStorage)
 *
 * @author doubao
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dbtool_sql_script")
public class DbSqlScript extends BaseEntity {

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
     * 脚本名称
     */
    private String title;

    /**
     * SQL 文本
     */
    private String sqlText;

    /**
     * 连接ID(可为空 = 未绑定连接)
     */
    private Long connId;

    /**
     * 连接名(冗余)
     */
    private String connName;

    /**
     * Schema
     */
    private String schemaName;

    /**
     * 共享范围: PRIVATE(当前仅私有, 预留)
     */
    private String shareScope;

    /**
     * 删除标志(0存在 1删除)
     */
    @TableLogic
    private String delFlag;

}
