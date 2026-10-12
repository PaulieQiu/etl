package pan.dromara.dbtool.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 数据库连接配置 实体
 *
 * @author doubao
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_db_conn")
public class DbConn extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 连接名称
     */
    private String connName;

    /**
     * 数据库类型: MYSQL/POSTGRESQL/ORACLE/SQLSERVER
     */
    private String dbType;

    /**
     * 数据库版本(如 8.0.33)
     */
    private String dbVersion;

    /**
     * 访问模式: READ_WRITE / READ_ONLY
     */
    private String accessMode;

    /**
     * 主机地址
     */
    private String host;

    /**
     * 端口
     */
    private Integer port;

    /**
     * 默认数据库
     */
    private String databaseName;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码(AES加密存储)
     */
    private String password;

    /**
     * 扩展参数(如 useSSL=false...)
     */
    private String params;

    /**
     * 是否启用 SSH 隧道(0否 1是)
     */
    private String sshUseTunnel;

    /**
     * SSH 跳板机主机
     */
    private String sshHost;

    /**
     * SSH 跳板机端口(默认22)
     */
    private Integer sshPort;

    /**
     * SSH 用户名
     */
    private String sshUser;

    /**
     * SSH 密码(AES加密存储)
     */
    private String sshPassword;

    /**
     * 状态(0正常 1停用)
     */
    private String status;

    /**
     * 删除标志(0存在 1删除)
     */
    @TableLogic
    private String delFlag;

}
