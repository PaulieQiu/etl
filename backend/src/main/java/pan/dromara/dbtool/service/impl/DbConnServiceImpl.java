package pan.dromara.dbtool.service.impl;

import pan.dromara.dbtool.core.DbConnCrypto;
import pan.dromara.dbtool.core.DbConnectionManager;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.domain.vo.DbResultVo;
import pan.dromara.dbtool.mapper.DbConnMapper;
import pan.dromara.dbtool.service.IDbConnService;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 数据库连接配置 服务实现
 *
 * @author doubao
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DbConnServiceImpl implements IDbConnService {

    /**
     * 本地 Redis 数据源参数(启动时自动创建, 按本机环境配置调整)
     */
    private static final String LOCAL_REDIS_NAME = "本地Redis";
    private static final String LOCAL_REDIS_HOST = "127.0.0.1";
    private static final int LOCAL_REDIS_PORT = 6379;
    private static final String LOCAL_REDIS_DB = "0";
    private static final String LOCAL_REDIS_PASSWORD = "pansoft2026";

    private final DbConnMapper dbConnMapper;
    private final DbConnectionManager connectionManager;

    /**
     * 启动时自动创建本地 Redis 数据源(若不存在)
     */
    @PostConstruct
    public void initLocalRedisDataSource() {
        try {
            DbConn exist = dbConnMapper.selectOne(new LambdaQueryWrapper<DbConn>()
                .eq(DbConn::getDbType, "REDIS")
                .eq(DbConn::getHost, LOCAL_REDIS_HOST)
                .eq(DbConn::getPort, LOCAL_REDIS_PORT)
                .last("LIMIT 1"));
            if (exist != null) {
                return;
            }
            DbConn redis = new DbConn();
            redis.setConnName(LOCAL_REDIS_NAME);
            redis.setDbType("REDIS");
            redis.setDbVersion("7");
            redis.setHost(LOCAL_REDIS_HOST);
            redis.setPort(LOCAL_REDIS_PORT);
            redis.setDatabaseName(LOCAL_REDIS_DB);
            redis.setUsername("");
            redis.setPassword(DbConnCrypto.encrypt(LOCAL_REDIS_PASSWORD));
            redis.setStatus("0");
            dbConnMapper.insert(redis);
            log.info("已自动创建本地 Redis 数据源: {}:{}", LOCAL_REDIS_HOST, LOCAL_REDIS_PORT);
        } catch (Exception e) {
            log.warn("自动创建本地 Redis 数据源失败: {}", e.getMessage());
        }
    }

    @Override
    public Page<DbConn> pageList(Integer pageNum, Integer pageSize, String connName, String dbType) {
        LambdaQueryWrapper<DbConn> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StrUtil.isNotBlank(connName), DbConn::getConnName, connName)
            .orderByDesc(DbConn::getCreateTime);
        // 数据库类型支持多选（逗号分隔）
        if (StrUtil.isNotBlank(dbType)) {
            List<String> types = StrUtil.split(dbType, ',').stream()
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .map(String::toUpperCase)
                .toList();
            if (!types.isEmpty()) {
                wrapper.in(DbConn::getDbType, types);
            }
        }
        return dbConnMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    @Override
    public List<DbConn> listAll() {
        return dbConnMapper.selectList(new LambdaQueryWrapper<DbConn>()
            .eq(DbConn::getStatus, "0")
            .orderByAsc(DbConn::getConnName));
    }

    @Override
    public DbConn getById(Long id) {
        return dbConnMapper.selectById(id);
    }

    @Override
    public boolean add(DbConn conn) {
        conn.setAccessMode(normalizeAccessMode(conn.getAccessMode()));
        return dbConnMapper.insert(conn) > 0;
    }

    @Override
    public boolean update(DbConn conn) {
        if (conn.getAccessMode() != null) {
            conn.setAccessMode(normalizeAccessMode(conn.getAccessMode()));
        }
        return dbConnMapper.updateById(conn) > 0;
    }

    private String normalizeAccessMode(String accessMode) {
        String normalized = StrUtil.blankToDefault(accessMode, "READ_WRITE").trim().toUpperCase();
        if (!"READ_WRITE".equals(normalized) && !"READ_ONLY".equals(normalized)) {
            throw new ServiceException("不支持的数据库连接访问模式: " + accessMode);
        }
        return normalized;
    }

    @Override
    public boolean remove(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        return dbConnMapper.deleteByIds(ids) > 0;
    }

    @Override
    public DbResultVo testConnection(DbConn conn) {
        DbResultVo result = new DbResultVo();
        long start = System.currentTimeMillis();
        String error = connectionManager.testConnection(conn);
        result.setSuccess(error == null);
        result.setMessage(error == null ? "连接成功" : "连接失败: " + error);
        result.setCostMs(System.currentTimeMillis() - start);
        return result;
    }

    @Override
    public String encryptPassword(String plain) {
        return DbConnCrypto.encrypt(plain);
    }

    @Override
    public String decryptPassword(String cipher) {
        return DbConnCrypto.decrypt(cipher);
    }
}
