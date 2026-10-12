package pan.dromara.dbtool.service.impl;

import pan.dromara.dbtool.domain.DbSqlHistory;
import pan.dromara.dbtool.domain.DbSqlScript;
import pan.dromara.dbtool.mapper.DbSqlHistoryMapper;
import pan.dromara.dbtool.mapper.DbSqlScriptMapper;
import pan.dromara.dbtool.service.IDbSqlLibraryService;

import cn.hutool.core.date.DateField;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * SQL 历史与脚本库 服务实现(P1 服务端化)
 * <p>替代前端 localStorage: 历史/脚本按 user_id 隔离持久化到 ry-vue 库;
 * 执行工作流经前端 record 调用写入; 保留策略: 历史每用户最近 500 条、脚本 200 条。</p>
 *
 * @author doubao
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DbSqlLibraryServiceImpl implements IDbSqlLibraryService {

    /**
     * 每用户执行历史上限(超出删除最旧)
     */
    private static final int MAX_HISTORY = 500;

    /**
     * 每用户脚本上限(超出拒绝新增)
     */
    private static final int MAX_SCRIPTS = 200;

    private final DbSqlHistoryMapper historyMapper;
    private final DbSqlScriptMapper scriptMapper;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 启动时幂等建表(对齐 MaterialDeviceController 启动 DDL 先例; DDL 亦同步于 dbtool 初始化 SQL)
     */
    @PostConstruct
    public void ensureTables() {
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS dbtool_sql_history (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL COMMENT '用户ID',
                    conn_id BIGINT DEFAULT NULL COMMENT '连接ID',
                    conn_name VARCHAR(128) DEFAULT NULL COMMENT '连接名',
                    schema_name VARCHAR(128) DEFAULT NULL COMMENT 'Schema',
                    sql_text MEDIUMTEXT COMMENT 'SQL文本',
                    status VARCHAR(16) DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILED/CANCELLED/TIMED_OUT',
                    rows_affected BIGINT DEFAULT NULL COMMENT '影响行数',
                    duration_ms BIGINT DEFAULT NULL COMMENT '耗时ms',
                    error_msg VARCHAR(1000) DEFAULT NULL COMMENT '错误摘要',
                    executed_at DATETIME DEFAULT NULL COMMENT '执行时间',
                    create_dept BIGINT DEFAULT NULL COMMENT '创建部门',
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    create_by VARCHAR(64) DEFAULT NULL,
                    update_by VARCHAR(64) DEFAULT NULL,
                    remark VARCHAR(500) DEFAULT NULL,
                    KEY idx_history_user_time (user_id, executed_at)
                ) COMMENT='dbtool SQL执行历史'
                """);
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS dbtool_sql_script (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL COMMENT '用户ID',
                    title VARCHAR(200) NOT NULL COMMENT '脚本名称',
                    sql_text MEDIUMTEXT COMMENT 'SQL文本',
                    conn_id BIGINT DEFAULT NULL COMMENT '连接ID',
                    conn_name VARCHAR(128) DEFAULT NULL COMMENT '连接名',
                    schema_name VARCHAR(128) DEFAULT NULL COMMENT 'Schema',
                    share_scope VARCHAR(16) DEFAULT 'PRIVATE' COMMENT '共享范围',
                    del_flag CHAR(1) DEFAULT '0' COMMENT '删除标志',
                    create_dept BIGINT DEFAULT NULL COMMENT '创建部门',
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    create_by VARCHAR(64) DEFAULT NULL,
                    update_by VARCHAR(64) DEFAULT NULL,
                    remark VARCHAR(500) DEFAULT NULL,
                    KEY idx_script_user (user_id, del_flag)
                ) COMMENT='dbtool已保存SQL脚本'
                """);
            // 已存在但缺审计列的旧表补齐(MySQL 8 无 ADD COLUMN IF NOT EXISTS, 按 information_schema 判存)
            ensureAuditColumn("dbtool_sql_history");
            ensureAuditColumn("dbtool_sql_script");
        } catch (Exception e) {
            log.error("dbtool_sql_history / dbtool_sql_script 建表失败: {}", e.getMessage());
        }
    }

    /**
     * 幂等补齐审计列 create_dept
     */
    private void ensureAuditColumn(String table) {
        try {
            Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'create_dept'
                """, Integer.class, table);
            if (count == null || count == 0) {
                jdbcTemplate.execute("ALTER TABLE " + table
                    + " ADD COLUMN create_dept BIGINT DEFAULT NULL COMMENT '创建部门' AFTER user_id");
            }
        } catch (Exception e) {
            log.warn("补齐审计列失败: table={}, {}", table, e.getMessage());
        }
    }

    @Override
    public void recordHistory(DbSqlHistory history) {
        if (history == null || StrUtil.isBlank(history.getSqlText())) {
            return;
        }
        history.setSqlText(history.getSqlText().trim());
        history.setStatus(StrUtil.blankToDefault(history.getStatus(), "SUCCESS"));
        history.setExecutedAt(history.getExecutedAt() != null ? history.getExecutedAt() : new Date());
        history.setErrorMsg(StrUtil.maxLength(history.getErrorMsg(), 1000));
        // 幂等: 同一用户+连接+SQL 的重复执行(如载入脚本再次执行)更新最近一条记录,
        // 不再无限新增历史, 避免列表出现重复执行记录
        DbSqlHistory exist = historyMapper.selectOne(new LambdaQueryWrapper<DbSqlHistory>()
            .eq(DbSqlHistory::getUserId, history.getUserId())
            .eq(history.getConnId() != null, DbSqlHistory::getConnId, history.getConnId())
            .eq(DbSqlHistory::getSqlText, history.getSqlText())
            .orderByDesc(DbSqlHistory::getExecutedAt)
            .orderByDesc(DbSqlHistory::getId)
            .last("LIMIT 1"));
        if (exist != null) {
            exist.setStatus(history.getStatus());
            exist.setDurationMs(history.getDurationMs());
            exist.setRowsAffected(history.getRowsAffected());
            exist.setErrorMsg(history.getErrorMsg());
            exist.setExecutedAt(history.getExecutedAt());
            exist.setSchemaName(StrUtil.blankToDefault(history.getSchemaName(), exist.getSchemaName()));
            exist.setConnName(StrUtil.blankToDefault(history.getConnName(), exist.getConnName()));
            historyMapper.updateById(exist);
            return;
        }
        historyMapper.insert(history);
        trimHistory(history.getUserId());
    }

    /** 保留最近 MAX_HISTORY 条: 删除该用户超出部分的最旧记录 */
    private void trimHistory(Long userId) {
        jdbcTemplate.update("""
            DELETE h FROM dbtool_sql_history h
            JOIN (
                SELECT id FROM dbtool_sql_history
                WHERE user_id = ?
                ORDER BY executed_at DESC, id DESC
                LIMIT 1 OFFSET ?
            ) k ON h.id = k.id
            """, userId, MAX_HISTORY);
    }

    @Override
    public Page<DbSqlHistory> listHistory(Long userId, Long connId, String schema, String keyword,
                                          String status, String beginTime, String endTime,
                                          long pageNum, long pageSize) {
        LambdaQueryWrapper<DbSqlHistory> qw = new LambdaQueryWrapper<>();
        qw.eq(DbSqlHistory::getUserId, userId);
        if (connId != null) {
            qw.eq(DbSqlHistory::getConnId, connId);
        }
        if (StrUtil.isNotBlank(schema)) {
            qw.eq(DbSqlHistory::getSchemaName, schema);
        }
        if (StrUtil.isNotBlank(status)) {
            qw.eq(DbSqlHistory::getStatus, status);
        }
        if (StrUtil.isNotBlank(keyword)) {
            qw.and(w -> w.like(DbSqlHistory::getSqlText, keyword)
                .or().like(DbSqlHistory::getConnName, keyword));
        }
        if (StrUtil.isNotBlank(beginTime)) {
            qw.ge(DbSqlHistory::getExecutedAt, DateUtil.parse(beginTime.trim(), "yyyy-MM-dd"));
        }
        if (StrUtil.isNotBlank(endTime)) {
            // 排他上界: 次日 00:00:00, 避免 23:59:59.999 边界漏查
            Date end = DateUtil.parse(endTime.trim(), "yyyy-MM-dd");
            qw.lt(DbSqlHistory::getExecutedAt, DateUtil.offset(end, DateField.DAY_OF_MONTH, 1));
        }
        qw.orderByDesc(DbSqlHistory::getExecutedAt).orderByDesc(DbSqlHistory::getId);
        return historyMapper.selectPage(new Page<>(pageNum, pageSize), qw);
    }

    @Override
    public void deleteHistory(Long userId, Long id) {
        if (id == null) {
            return;
        }
        historyMapper.delete(new LambdaQueryWrapper<DbSqlHistory>()
            .eq(DbSqlHistory::getUserId, userId)
            .eq(DbSqlHistory::getId, id));
    }

    @Override
    public void saveScript(Long userId, DbSqlScript script) {
        if (script == null || StrUtil.isBlank(script.getTitle())) {
            throw new ServiceException("脚本名称不能为空");
        }
        String sqlText = StrUtil.blankToDefault(script.getSqlText(), "").trim();
        if (StrUtil.isBlank(sqlText)) {
            throw new ServiceException("脚本 SQL 不能为空");
        }
        if (script.getId() != null) {
            DbSqlScript exist = scriptMapper.selectOne(new LambdaQueryWrapper<DbSqlScript>()
                .eq(DbSqlScript::getId, script.getId())
                .eq(DbSqlScript::getUserId, userId)
                .last("LIMIT 1"));
            if (exist == null) {
                throw new ServiceException("脚本不存在或无权修改");
            }
            exist.setTitle(script.getTitle().trim());
            exist.setSqlText(sqlText);
            exist.setConnId(script.getConnId());
            exist.setConnName(script.getConnName());
            exist.setSchemaName(script.getSchemaName());
            scriptMapper.updateById(exist);
            return;
        }
        Long count = scriptMapper.selectCount(new LambdaQueryWrapper<DbSqlScript>()
            .eq(DbSqlScript::getUserId, userId));
        if (count != null && count >= MAX_SCRIPTS) {
            throw new ServiceException("脚本数量已达上限(" + MAX_SCRIPTS + " 条)");
        }
        script.setId(null);
        script.setUserId(userId);
        script.setTitle(script.getTitle().trim());
        script.setSqlText(sqlText);
        script.setShareScope(StrUtil.blankToDefault(script.getShareScope(), "PRIVATE"));
        script.setDelFlag("0");
        scriptMapper.insert(script);
    }

    @Override
    public List<DbSqlScript> listScripts(Long userId, String keyword) {
        LambdaQueryWrapper<DbSqlScript> qw = new LambdaQueryWrapper<>();
        qw.eq(DbSqlScript::getUserId, userId);
        if (StrUtil.isNotBlank(keyword)) {
            qw.and(w -> w.like(DbSqlScript::getTitle, keyword)
                .or().like(DbSqlScript::getSqlText, keyword));
        }
        qw.orderByDesc(DbSqlScript::getUpdateTime).orderByDesc(DbSqlScript::getId);
        return scriptMapper.selectList(qw);
    }

    @Override
    public void deleteScript(Long userId, Long id) {
        if (id == null) {
            return;
        }
        scriptMapper.delete(new LambdaQueryWrapper<DbSqlScript>()
            .eq(DbSqlScript::getUserId, userId)
            .eq(DbSqlScript::getId, id));
    }

}
