package pan.dromara.dbtool.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 数据库驱动管理 实体
 *
 * @author doubao
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_db_driver")
public class DbDriver extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 数据库类型: MYSQL/POSTGRESQL/...
     */
    private String dbType;

    /**
     * 驱动名称(artifact 名)
     */
    private String driverName;

    /**
     * 驱动版本
     */
    private String driverVersion;

    /**
     * 驱动类名
     */
    private String driverClass;

    /**
     * jar 文件路径(服务端)
     */
    private String jarPath;

    /**
     * 来源: 1内置 2自动下载 3手动上传
     */
    private String source;

    /**
     * 文件大小(字节)
     */
    private Long fileSize;

    /**
     * 状态(0正常 1停用)
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 删除标志(0存在 1删除)
     */
    @TableLogic
    private String delFlag;
}
