package pan.dromara.dbtool.service.impl;

import pan.dromara.dbtool.core.DbAccessPolicy;
import pan.dromara.dbtool.core.DbConnCrypto;
import pan.dromara.dbtool.core.DbOperationConfirmationService;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.service.IDbBackupService;
import pan.dromara.dbtool.service.IDbMetaService;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 备份/恢复实现: 异步进程执行 mysqldump / mysql client
 * <p>任务记录持久化到 dbtool_backup_job 表(P4 遗留项落库): 提交即 INSERT,
 * 状态变更(排队→运行→成功/失败/取消)即 UPDATE; 重启后历史/状态可从库恢复。</p>
 *
 * @author doubao
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbBackupServiceImpl implements IDbBackupService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final IDbMetaService metaService;
    private final DbOperationConfirmationService confirmationService;
    private final JdbcTemplate jdbcTemplate;

    /** 备份文件目录(默认 D:/project/dbtool_backups) */
    @Value("${dbtool.backup.dir:D:/project/dbtool_backups}")
    private String backupDir;
    /** MySQL 客户端目录(默认探测本机 MySQL Server 8.0 安装) */
    @Value("${dbtool.backup.mysqlHome:}")
    private String mysqlHome;
    /** 备份文件保留天数(<=0 不按天清理) */
    @Value("${dbtool.backup.retentionDays:30}")
    private int retentionDays;
    /** 每库最多保留备份份数(<=0 不按份数清理) */
    @Value("${dbtool.backup.maxKeep:20}")
    private int maxKeep;

    /** 单线程后台任务执行器(备份/恢复串行, 避免并发 mysqldump 抢占) */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "dbtool-backup-worker");
        thread.setDaemon(true);
        return thread;
    });

    /** 运行中任务: jobId -> (Future, Process); 任务记录权威数据在 dbtool_backup_job 表 */
    private final Map<Long, JobHandle> runningJobs = new ConcurrentHashMap<>();
    /** 任务ID序列: 启动时基于表 MAX(id) 初始化, 重启不撞历史ID */
    private final AtomicLong jobIdSeq = new AtomicLong(0);

    /**
     * 启动时幂等建表 + 初始化任务ID序列
     */
    @PostConstruct
    public void init() {
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS dbtool_backup_job (
                    id BIGINT PRIMARY KEY COMMENT '任务ID',
                    conn_id BIGINT DEFAULT NULL COMMENT '连接ID',
                    conn_name VARCHAR(128) DEFAULT NULL COMMENT '连接名',
                    schema_name VARCHAR(128) DEFAULT NULL COMMENT '数据库名',
                    job_type VARCHAR(16) DEFAULT NULL COMMENT 'DUMP/RESTORE',
                    status VARCHAR(16) DEFAULT NULL COMMENT 'QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED',
                    progress INT DEFAULT 0 COMMENT '进度',
                    file_path VARCHAR(500) DEFAULT NULL COMMENT '备份文件路径',
                    result_msg VARCHAR(500) DEFAULT NULL COMMENT '结果说明',
                    error_msg VARCHAR(1000) DEFAULT NULL COMMENT '错误摘要',
                    created_by VARCHAR(64) DEFAULT NULL COMMENT '提交用户',
                    created_at DATETIME DEFAULT NULL COMMENT '提交时间',
                    finished_at DATETIME DEFAULT NULL COMMENT '完成时间',
                    create_dept BIGINT DEFAULT NULL COMMENT '创建部门',
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                    KEY idx_job_conn (conn_id, created_at),
                    KEY idx_job_time (created_at)
                ) COMMENT='dbtool备份/恢复任务记录'
                """);
        } catch (Exception e) {
            log.warn("dbtool_backup_job 建表失败: {}", e.getMessage());
        }
        Long maxId = jdbcTemplate.queryForObject("SELECT IFNULL(MAX(id), 0) FROM dbtool_backup_job", Long.class);
        jobIdSeq.set(maxId == null ? 0L : maxId);
        // P4 遗留边界: 重启时中断任务状态收敛 + 备份文件保留策略
        recoverInterruptedJobs();
        cleanupExpiredBackups();
    }

    @Override
    public Long startDump(Long connId, String schema, String userId) {
        if (connId == null || StrUtil.isBlank(schema)) {
            throw new ServiceException("连接ID与数据库名不能为空");
        }
        DbConn conn = metaService.getConn(connId);
        requireMysql(conn);
        if (isAnyRunning()) {
            throw new ServiceException("已有备份/恢复任务正在执行, 请等待完成后再提交");
        }
        Long jobId = jobIdSeq.incrementAndGet();
        JobRecord record = new JobRecord();
        record.id = jobId;
        record.connId = connId;
        record.connName = conn.getConnName();
        record.schema = schema;
        record.jobType = "DUMP";
        record.status = "QUEUED";
        record.createdBy = userId;
        record.createdAt = LocalDateTime.now();
        insertRecord(record);

        Path targetDir = ensureBackupDir();
        String fileName = schema + "_" + STAMP.format(LocalDateTime.now()) + ".sql";
        Path target = targetDir.resolve(fileName);
        Future<?> future = executor.submit(() -> runDump(conn, schema, target, record));
        runningJobs.put(jobId, new JobHandle(future, null));
        return jobId;
    }

    @Override
    public Long startRestore(Long connId, String schema, String filePath, String userId, String confirmationToken) {
        if (connId == null || StrUtil.isBlank(schema) || StrUtil.isBlank(filePath)) {
            throw new ServiceException("连接ID、目标数据库与备份文件不能为空");
        }
        DbConn conn = metaService.getConn(connId);
        requireMysql(conn);
        DbAccessPolicy.requireWritable(conn);
        // 恢复会覆盖目标库数据, 属高危操作, 必须持有与目标匹配的一次性确认凭证
        String payload = "BACKUP_RESTORE:" + connId + ":" + schema + ":" + Paths.get(filePath).getFileName();
        if (!confirmationService.consume(confirmationToken, connId, payload, userId)) {
            throw new ServiceException("缺少有效的恢复确认凭证, 请先发起确认");
        }
        // 仅允许本服务备份目录内的文件, 防任意文件读取/写入
        Path backupRoot = ensureBackupDir().toAbsolutePath().normalize();
        Path source = Paths.get(filePath).toAbsolutePath().normalize();
        if (!source.startsWith(backupRoot) || !Files.isRegularFile(source)) {
            throw new ServiceException("备份文件不存在或不在备份目录内");
        }
        if (isAnyRunning()) {
            throw new ServiceException("已有备份/恢复任务正在执行, 请等待完成后再提交");
        }
        Long jobId = jobIdSeq.incrementAndGet();
        JobRecord record = new JobRecord();
        record.id = jobId;
        record.connId = connId;
        record.connName = conn.getConnName();
        record.schema = schema;
        record.jobType = "RESTORE";
        record.status = "QUEUED";
        record.filePath = source.toString();
        record.createdBy = userId;
        record.createdAt = LocalDateTime.now();
        insertRecord(record);

        Future<?> future = executor.submit(() -> runRestore(conn, schema, source, record));
        runningJobs.put(jobId, new JobHandle(future, null));
        return jobId;
    }

    @Override
    public Map<String, Object> listJobs(Integer pageNum, Integer pageSize, Long connId, String userId) {
        int page = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 10 : pageSize;
        StringBuilder sql = new StringBuilder("SELECT id,conn_id,conn_name,schema_name,job_type,status,progress,")
            .append("file_path,result_msg,error_msg,created_by,created_at,finished_at FROM dbtool_backup_job");
        List<Object> args = new ArrayList<>();
        if (connId != null) {
            sql.append(" WHERE conn_id = ?");
            args.add(connId);
        }
        sql.append(" ORDER BY created_at DESC, id DESC");
        int total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dbtool_backup_job" + (connId != null ? " WHERE conn_id = ?" : ""),
            Integer.class, args.toArray());
        sql.append(" LIMIT ? OFFSET ?");
        args.add(size);
        args.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbcTemplate.query(sql.toString(), this::rowToView, args.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("rows", rows);
        return result;
    }

    @Override
    public boolean cancelJob(Long jobId, String userId) {
        JobHandle handle = runningJobs.get(jobId);
        if (handle == null) {
            JobRecord record = loadRecord(jobId);
            if (record != null && ("RUNNING".equals(record.status) || "QUEUED".equals(record.status))) {
                record.status = "CANCELLED";
                record.errorMsg = "任务已取消";
                record.finishedAt = LocalDateTime.now();
                updateRecord(record);
                return true;
            }
            return false;
        }
        try {
            handle.future.cancel(true);
        } catch (Exception ignored) {
        }
        Process process = handle.process;
        if (process != null && process.isAlive()) {
            process.destroy();
        }
        JobRecord record = loadRecord(jobId);
        if (record != null) {
            record.status = "CANCELLED";
            record.errorMsg = "任务已取消";
            record.finishedAt = LocalDateTime.now();
            updateRecord(record);
        }
        runningJobs.remove(jobId);
        return true;
    }

    @Override
    public Map<String, Object> getJob(Long jobId, String userId) {
        JobRecord record = loadRecord(jobId);
        if (record == null) {
            throw new ServiceException("任务不存在");
        }
        return view(record);
    }

    // ================= 执行 =================

    private void runDump(DbConn conn, String schema, Path target, JobRecord record) {
        record.status = "RUNNING";
        record.progress = 10;
        updateRecord(record);
        Process process = null;
        try {
            List<String> command = new ArrayList<>();
            command.add(mysqlBin("mysqldump"));
            command.add("--host=" + conn.getHost());
            command.add("--port=" + (conn.getPort() == null ? "3306" : String.valueOf(conn.getPort())));
            command.add("--user=" + conn.getUsername());
            command.add("--single-transaction");
            command.add("--routines");
            command.add("--triggers");
            command.add("--default-character-set=utf8mb4");
            command.add("--result-file=" + target.toString());
            command.add(schema);
            process = runProcess(command, decrypt(conn.getPassword()));
            runningJobs.get(record.id).process = process;
            int exit = process.waitFor();
            if (exit != 0) {
                String stderr = readStderr(process);
                record.status = "FAILED";
                record.errorMsg = StrUtil.blankToDefault(stderr, "mysqldump 退出码 " + exit);
                record.finishedAt = LocalDateTime.now();
                updateRecord(record);
                return;
            }
            record.progress = 100;
            record.status = "SUCCEEDED";
            record.filePath = target.toString();
            record.resultMsg = "备份完成, 文件大小 " + fileSize(target) + " MB";
            record.finishedAt = LocalDateTime.now();
            updateRecord(record);
            // 备份成功后执行保留策略(按天/按份数清理旧文件)
            cleanupExpiredBackups();
        } catch (Exception e) {
            log.warn("备份任务失败: {}", e.getMessage());
            record.status = "FAILED";
            record.errorMsg = e.getMessage();
            record.finishedAt = LocalDateTime.now();
            updateRecord(record);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
            }
            runningJobs.remove(record.id);
        }
    }

    private void runRestore(DbConn conn, String schema, Path source, JobRecord record) {
        record.status = "RUNNING";
        record.progress = 10;
        updateRecord(record);
        Process process = null;
        try {
            List<String> command = new ArrayList<>();
            command.add(mysqlBin("mysql"));
            command.add("--host=" + conn.getHost());
            command.add("--port=" + (conn.getPort() == null ? "3306" : String.valueOf(conn.getPort())));
            command.add("--user=" + conn.getUsername());
            command.add("--default-character-set=utf8mb4");
            command.add(schema);
            process = runProcess(command, decrypt(conn.getPassword()));
            // 备份文件作为 stdin 输入
            process.getOutputStream().write(Files.readAllBytes(source));
            process.getOutputStream().flush();
            process.getOutputStream().close();
            runningJobs.get(record.id).process = process;
            int exit = process.waitFor();
            if (exit != 0) {
                String stderr = readStderr(process);
                record.status = "FAILED";
                record.errorMsg = StrUtil.blankToDefault(stderr, "mysql 导入退出码 " + exit);
                record.finishedAt = LocalDateTime.now();
                updateRecord(record);
                return;
            }
            record.progress = 100;
            record.status = "SUCCEEDED";
            record.resultMsg = "恢复完成, 已导入 " + source.getFileName();
            record.finishedAt = LocalDateTime.now();
            updateRecord(record);
        } catch (Exception e) {
            log.warn("恢复任务失败: {}", e.getMessage());
            record.status = "FAILED";
            record.errorMsg = e.getMessage();
            record.finishedAt = LocalDateTime.now();
            updateRecord(record);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
            }
            runningJobs.remove(record.id);
        }
    }

    // ================= 持久化 =================

    private void insertRecord(JobRecord record) {
        try {
            jdbcTemplate.update("""
                INSERT INTO dbtool_backup_job
                    (id, conn_id, conn_name, schema_name, job_type, status, progress,
                     file_path, result_msg, error_msg, created_by, created_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.id, record.connId, record.connName, record.schema, record.jobType, record.status,
                record.progress, record.filePath, record.resultMsg, record.errorMsg,
                record.createdBy, record.createdAt, record.finishedAt);
        } catch (Exception e) {
            log.warn("备份任务记录写入失败(jobId={}): {}", record.id, e.getMessage());
        }
    }

    private void updateRecord(JobRecord record) {
        try {
            jdbcTemplate.update("""
                UPDATE dbtool_backup_job SET status = ?, progress = ?, file_path = ?,
                    result_msg = ?, error_msg = ?, finished_at = ?
                WHERE id = ?
                """,
                record.status, record.progress, record.filePath, record.resultMsg, record.errorMsg,
                record.finishedAt, record.id);
        } catch (Exception e) {
            log.warn("备份任务记录更新失败(jobId={}): {}", record.id, e.getMessage());
        }
    }

    private JobRecord loadRecord(Long jobId) {
        List<JobRecord> list = jdbcTemplate.query("""
            SELECT id,conn_id,conn_name,schema_name,job_type,status,progress,
                   file_path,result_msg,error_msg,created_by,created_at,finished_at
            FROM dbtool_backup_job WHERE id = ?
            """, (rs, i) -> mapRecord(rs), jobId);
        return list.isEmpty() ? null : list.get(0);
    }

    private JobRecord mapRecord(ResultSet rs) throws SQLException {
        JobRecord record = new JobRecord();
        record.id = rs.getLong("id");
        record.connId = rs.getLong("conn_id");
        record.connName = rs.getString("conn_name");
        record.schema = rs.getString("schema_name");
        record.jobType = rs.getString("job_type");
        record.status = rs.getString("status");
        record.progress = rs.getInt("progress");
        record.filePath = rs.getString("file_path");
        record.resultMsg = rs.getString("result_msg");
        record.errorMsg = rs.getString("error_msg");
        record.createdBy = rs.getString("created_by");
        record.createdAt = rs.getObject("created_at", LocalDateTime.class);
        record.finishedAt = rs.getObject("finished_at", LocalDateTime.class);
        return record;
    }

    private Map<String, Object> rowToView(ResultSet rs, int rowNum) throws SQLException {
        return view(mapRecord(rs));
    }

    private Process runProcess(List<String> command, String password) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        // 密码走环境变量, 不进进程参数列表(避免 ps 可见明文)
        builder.environment().put("MYSQL_PWD", StrUtil.nullToEmpty(password));
        builder.redirectErrorStream(false);
        return builder.start();
    }

    private String readStderr(Process process) {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && sb.length() < 2000) {
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    private String mysqlBin(String binary) {
        // 自定义 mysqlHome 优先; 其次探测常见安装路径
        if (StrUtil.isNotBlank(mysqlHome)) {
            return Paths.get(mysqlHome, binary + ".exe").toString();
        }
        for (String candidate : new String[]{
            "C:/Program Files/MySQL/MySQL Server 8.0/bin/" + binary + ".exe",
            "C:/Program Files/MySQL/MySQL Server 5.7/bin/" + binary + ".exe",
            "C:/Program Files/MySQL/MySQL Server 5.6/bin/" + binary + ".exe"}) {
            if (Files.isRegularFile(Paths.get(candidate))) {
                return candidate;
            }
        }
        // 最后尝试 PATH 中的命令
        return binary;
    }

    private Path ensureBackupDir() {
        try {
            Path dir = Paths.get(backupDir);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            return dir;
        } catch (Exception e) {
            throw new ServiceException("备份目录不可用: " + e.getMessage());
        }
    }

    /**
     * 重启恢复边界(P4 遗留): 服务重启后, 遗留的 QUEUED/RUNNING 任务不可能再被
     * 单线程执行器续跑(进程已丢), 启动时统一收敛为 FAILED 并注明中断原因,
     * 避免任务长期停留在运行中状态造成误判。
     */
    private void recoverInterruptedJobs() {
        int updated = 0;
        try {
            updated = jdbcTemplate.update("""
                UPDATE dbtool_backup_job SET status = 'FAILED', error_msg = '服务重启, 任务中断',
                    finished_at = NOW()
                WHERE status IN ('QUEUED', 'RUNNING')
                """);
        } catch (Exception e) {
            log.warn("重启中断任务收敛失败: {}", e.getMessage());
        }
        if (updated > 0) {
            log.info("重启收敛中断备份任务 {} 条(置为 FAILED)", updated);
        }
    }

    /**
     * 备份文件保留策略(P4 遗留):
     * 1) 按天数: 超过 dbtool.backup.retentionDays 天的备份文件删除(retentionDays<=0 关闭);
     * 2) 按份数: 每库(文件名前缀)最多保留最近 dbtool.backup.maxKeep 份(maxKeep<=0 关闭)。
     * 仅清理备份文件, DB 任务记录保留(历史可审计; 恢复接口已校验文件存在性, 已删文件无法被恢复)。
     */
    private void cleanupExpiredBackups() {
        Path dir;
        try {
            dir = ensureBackupDir();
        } catch (Exception e) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".sql"))
                .filter(Files::isRegularFile)
                .forEach(files::add);
        } catch (Exception e) {
            log.warn("备份目录扫描失败: {}", e.getMessage());
            return;
        }
        if (files.isEmpty()) {
            return;
        }
        int removed = 0;
        // 1) 按天数清理
        if (retentionDays > 0) {
            long cutoff = System.currentTimeMillis() - retentionDays * 24L * 3600 * 1000L;
            for (Path f : files) {
                try {
                    if (Files.getLastModifiedTime(f).toMillis() < cutoff) {
                        Files.deleteIfExists(f);
                        removed++;
                    }
                } catch (Exception e) {
                    log.warn("删除过期备份文件失败 {}: {}", f, e.getMessage());
                }
            }
        }
        // 2) 按份数清理(每库保留最近 maxKeep 份)
        if (maxKeep > 0) {
            Map<String, List<Path>> groups = new LinkedHashMap<>();
            for (Path f : files) {
                if (!Files.exists(f)) {
                    continue;
                }
                String name = f.getFileName().toString();
                int idx = name.indexOf('_');
                String key = idx > 0 ? name.substring(0, idx) : name;
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
            }
            for (List<Path> group : groups.values()) {
                if (group.size() <= maxKeep) {
                    continue;
                }
                group.sort((a, b) -> Long.compare(lastModified(b), lastModified(a)));
                for (int i = maxKeep; i < group.size(); i++) {
                    try {
                        Files.deleteIfExists(group.get(i));
                        removed++;
                    } catch (Exception e) {
                        log.warn("删除超量备份文件失败 {}: {}", group.get(i), e.getMessage());
                    }
                }
            }
        }
        if (removed > 0) {
            log.info("备份保留策略清理文件 {} 个(目录 {})", removed, dir);
        }
    }

    private long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    private void requireMysql(DbConn conn) {
        if (conn == null) {
            throw new ServiceException("数据库连接不存在");
        }
        String type = StrUtil.nullToEmpty(conn.getDbType()).toUpperCase();
        if (!"MYSQL".equals(type) && !"MARIADB".equals(type)) {
            throw new ServiceException("备份/恢复当前仅支持 MySQL/MariaDB 连接");
        }
    }

    private boolean isAnyRunning() {
        return runningJobs.values().stream().anyMatch(h -> !h.future.isDone());
    }

    private String decrypt(String cipher) {
        try {
            return DbConnCrypto.decrypt(cipher);
        } catch (Exception e) {
            throw new ServiceException("连接密码解密失败: " + e.getMessage());
        }
    }

    private String fileSize(Path path) {
        try {
            return String.format("%.2f", Files.size(path) / 1024.0 / 1024.0);
        } catch (Exception e) {
            return "?";
        }
    }

    private Map<String, Object> view(JobRecord record) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", record.id);
        view.put("connId", record.connId);
        view.put("connName", record.connName);
        view.put("schema", record.schema);
        view.put("jobType", record.jobType);
        view.put("status", record.status);
        view.put("progress", record.progress);
        view.put("filePath", record.filePath);
        view.put("resultMsg", record.resultMsg);
        view.put("errorMsg", record.errorMsg);
        view.put("createdBy", record.createdBy);
        view.put("createdAt", record.createdAt == null ? null : record.createdAt.toString());
        view.put("finishedAt", record.finishedAt == null ? null : record.finishedAt.toString());
        return view;
    }

    /** 任务句柄: Future + 运行进程 */
    private static final class JobHandle {
        private final Future<?> future;
        private volatile Process process;

        private JobHandle(Future<?> future, Process process) {
            this.future = future;
            this.process = process;
        }
    }

    /** 任务记录(权威数据持久化于 dbtool_backup_job 表) */
    private static final class JobRecord {
        private Long id;
        private Long connId;
        private String connName;
        private String schema;
        private String jobType;
        private String status;
        private Integer progress = 0;
        private String filePath;
        private String resultMsg;
        private String errorMsg;
        private String createdBy;
        private LocalDateTime createdAt;
        private LocalDateTime finishedAt;
    }
}
