package pan.dromara.dbtool.service;

import pan.dromara.dbtool.domain.DbDriver;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 数据库驱动 服务接口
 *
 * @author doubao
 */
public interface IDbDriverService {

    /**
     * 分页查询驱动列表
     */
    Page<DbDriver> pageList(Integer pageNum, Integer pageSize, String dbType, String keyword);

    /**
     * 查询全部驱动
     */
    List<DbDriver> listAll();

    /**
     * 自动下载驱动
     *
     * @param dbType  数据库类型
     * @param version 版本
     * @return 驱动记录
     */
    DbDriver download(String dbType, String version);

    /**
     * 手动上传驱动
     *
     * @param dbType  数据库类型
     * @param version 版本
     * @param file    jar 文件
     * @return 驱动记录
     */
    DbDriver upload(String dbType, String version, MultipartFile file);

    /**
     * 删除驱动
     *
     * @param ids id 列表
     */
    boolean remove(List<Long> ids);

    /**
     * 编辑驱动信息(内置驱动仅允许修改状态/备注)
     *
     * @param driver 驱动信息
     */
    boolean update(DbDriver driver);
}
