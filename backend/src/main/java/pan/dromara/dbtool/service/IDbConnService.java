package pan.dromara.dbtool.service;

import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.domain.vo.DbResultVo;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

/**
 * 数据库连接配置 服务层
 *
 * @author doubao
 */
public interface IDbConnService {

    /**
     * 分页查询连接配置
     */
    Page<DbConn> pageList(Integer pageNum, Integer pageSize, String connName, String dbType);

    /**
     * 查询全部启用的连接
     */
    List<DbConn> listAll();

    /**
     * 查询单个连接
     */
    DbConn getById(Long id);

    /**
     * 新增连接
     */
    boolean add(DbConn conn);

    /**
     * 修改连接
     */
    boolean update(DbConn conn);

    /**
     * 删除连接
     */
    boolean remove(List<Long> ids);

    /**
     * 测试数据库连接(使用传入参数, 密码支持明文或已加密)
     *
     * @param conn 连接配置(带明文或密文密码)
     * @return 测试结果
     */
    DbResultVo testConnection(DbConn conn);

    /**
     * 加密明文密码(AES)
     */
    String encryptPassword(String plain);

    /**
     * 解密存储的密码
     */
    String decryptPassword(String cipher);
}
