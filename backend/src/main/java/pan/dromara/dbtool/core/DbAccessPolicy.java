package pan.dromara.dbtool.core;

import pan.dromara.dbtool.domain.DbConn;

import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.exception.ServiceException;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-side access checks for database connections.
 */
public final class DbAccessPolicy {

    private static final Pattern READ_ONLY_STATEMENT = Pattern.compile("^(select|show|describe|desc|explain)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLAIN_ANALYZE = Pattern.compile("^explain\\b.*\\banalyze\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SELECT_INTO = Pattern.compile("\\binto\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS_DROP = Pattern.compile("^\\s*drop\\s+(database|schema|table|index|view|trigger|procedure|function|event)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS_TRUNCATE = Pattern.compile("^\\s*truncate\\s+(table\\s+)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS_ALTER = Pattern.compile("^\\s*alter\\s+(table|database|schema|view)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS_DELETE = Pattern.compile("^\\s*delete\\s+from\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS_UPDATE = Pattern.compile("^\\s*update\\s+\\S+\\s+set\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HAS_WHERE = Pattern.compile("\\bwhere\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> READ_ONLY_REDIS_COMMANDS = Set.of(
        "get", "mget", "hget", "hgetall", "hmget", "lrange", "llen", "smembers", "scard",
        "sismember", "zrange", "zrevrange", "zscore", "zcard", "type", "ttl", "pttl", "exists",
        "scan", "dbsize", "info", "ping", "keys"
    );

    private DbAccessPolicy() {
    }

    public static boolean isReadOnly(DbConn conn) {
        return conn != null && "READ_ONLY".equalsIgnoreCase(conn.getAccessMode());
    }

    public static void requireWritable(DbConn conn) {
        if (isReadOnly(conn)) {
            throw new ServiceException("该数据库连接已设置为只读模式，不能执行写入、DDL 或管理操作");
        }
    }

    /**
     * Remove leading SQL comments so comments cannot disguise a statement's first keyword.
     */
    public static String normalizeLeadingComments(String sql) {
        if (StrUtil.isBlank(sql)) {
            return "";
        }
        String remaining = sql;
        while (true) {
            remaining = remaining.stripLeading();
            if (remaining.startsWith("--") || remaining.startsWith("#")) {
                int newline = remaining.indexOf('\n');
                if (newline < 0) {
                    return "";
                }
                remaining = remaining.substring(newline + 1);
            } else if (remaining.startsWith("/*")) {
                int end = remaining.indexOf("*/", 2);
                if (end < 0) {
                    throw new ServiceException("SQL 注释未闭合");
                }
                remaining = remaining.substring(end + 2);
            } else {
                return remaining.trim();
            }
        }
    }

    /**
     * Read-only connections accept only statements that are clearly non-mutating.
     */
    public static boolean isReadOnlyStatement(String sql) {
        String normalized = normalizeLeadingComments(sql);
        if (normalized.isEmpty()) {
            return false;
        }
        if (EXPLAIN_ANALYZE.matcher(normalized).find()
            || (READ_ONLY_STATEMENT.matcher(normalized).find()
            && normalized.regionMatches(true, 0, "SELECT", 0, "SELECT".length())
            && SELECT_INTO.matcher(normalized).find())) {
            return false;
        }
        return READ_ONLY_STATEMENT.matcher(normalized).find();
    }

    public static void requireReadOnlyStatement(String sql) {
        if (!isReadOnlyStatement(sql)) {
            throw new ServiceException("只读连接仅允许 SELECT/SHOW/DESCRIBE/EXPLAIN 查询；WITH、写入及无法识别的语句均被拒绝");
        }
    }

    public static boolean isReadOnlyRedisCommand(String command) {
        if (StrUtil.isBlank(command)) {
            return false;
        }
        String operation = command.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return READ_ONLY_REDIS_COMMANDS.contains(operation) || operation.startsWith("keys*");
    }

    /**
     * 高危 SQL 检测(与工作台执行同一套规则): 命中返回中文警示文案, 未命中返回 null。
     * <p>事务会话执行复用该检测, 保证事务内写操作同样需要确认。</p>
     */
    public static String dangerWarning(String sql) {
        if (StrUtil.isBlank(sql)) {
            return null;
        }
        if (DANGEROUS_DROP.matcher(sql).find()) {
            return "检测到 DROP 操作, 将永久删除数据库对象, 请确认是否继续!";
        }
        if (DANGEROUS_TRUNCATE.matcher(sql).find()) {
            return "检测到 TRUNCATE 操作, 将清空表中所有数据且不可回滚, 请确认是否继续!";
        }
        if (DANGEROUS_ALTER.matcher(sql).find()) {
            return "检测到 ALTER 操作, 将修改表结构, 请确认是否继续!";
        }
        if (DANGEROUS_DELETE.matcher(sql).find() && !HAS_WHERE.matcher(sql).find()) {
            return "检测到无条件 DELETE 操作, 将删除表中所有数据, 请确认是否继续!";
        }
        if (DANGEROUS_UPDATE.matcher(sql).find() && !HAS_WHERE.matcher(sql).find()) {
            return "检测到无条件 UPDATE 操作, 将更新表中所有数据, 请确认是否继续!";
        }
        return null;
    }
}
