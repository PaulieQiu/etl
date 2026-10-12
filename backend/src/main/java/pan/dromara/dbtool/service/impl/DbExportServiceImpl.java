package pan.dromara.dbtool.service.impl;

import pan.dromara.dbtool.core.DbAccessPolicy;
import pan.dromara.dbtool.core.DbConnectionManager;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.service.IDbExportService;
import pan.dromara.dbtool.service.IDbMetaService;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Writer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大结果导出实现: 流式逐行写出 CSV/JSON/INSERT
 *
 * @author doubao
 */
@Slf4j
@Service
public class DbExportServiceImpl implements IDbExportService {

    /** 默认导出行数上限 */
    private static final long DEFAULT_MAX_ROWS = 100000;
    /** 导出语句超时(秒) */
    private static final int EXPORT_TIMEOUT_SECONDS = 300;

    private static final Pattern SELECT_START = Pattern.compile(
        "^\\s*(select|show|describe|desc|explain|with)\\b", Pattern.CASE_INSENSITIVE);
    /** 简单单表表名提取: FROM/INTO 后第一个标识符(支持反引号) */
    private static final Pattern TABLE_NAME = Pattern.compile(
        "(?:FROM|INTO|UPDATE|JOIN)\\s+(?:`([^`]+)`|([A-Za-z0-9_\\u4e00-\\u9fa5]+))(?:\\s|$)",
        Pattern.CASE_INSENSITIVE);

    private final DbConnectionManager connectionManager;
    private final IDbMetaService metaService;

    public DbExportServiceImpl(DbConnectionManager connectionManager, IDbMetaService metaService) {
        this.connectionManager = connectionManager;
        this.metaService = metaService;
    }

    @Override
    public Map<String, Object> preview(Long connId, String sql) {
        String trimSql = validateQuery(connId, sql);
        Map<String, Object> result = new LinkedHashMap<>();
        DbConn conn = metaService.getConn(connId);
        long estimated = -1;
        try (Connection connection = connectionManager.getConnection(conn);
             Statement stmt = connection.createStatement()) {
            stmt.setQueryTimeout(EXPORT_TIMEOUT_SECONDS);
            try (ResultSet rs = stmt.executeQuery(trimSql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                }
                result.put("columns", columns);
            }
        } catch (SQLException e) {
            throw new ServiceException("导出预览失败: " + e.getMessage());
        }
        // 估算行数: COUNT 包装(失败不阻断, 返回 -1)
        try (Connection connection = connectionManager.getConnection(conn);
             Statement stmt = connection.createStatement()) {
            stmt.setQueryTimeout(EXPORT_TIMEOUT_SECONDS);
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS c FROM (" + trimSql + ") t")) {
                if (rs.next()) {
                    estimated = rs.getLong(1);
                }
            }
        } catch (Exception ignore) {
            estimated = -1;
        }
        result.put("estimatedRows", estimated);
        return result;
    }

    @Override
    public long exportTo(Long connId, String sql, String format, long maxRows, Writer writer) {
        String trimSql = validateQuery(connId, sql);
        long limit = maxRows > 0 ? Math.min(maxRows, DEFAULT_MAX_ROWS) : DEFAULT_MAX_ROWS;
        DbConn conn = metaService.getConn(connId);
        String fmt = format == null ? "csv" : format.toLowerCase();
        if (!List.of("csv", "json", "sql").contains(fmt)) {
            throw new ServiceException("不支持的导出格式: " + fmt + "(可选 csv/json/sql)");
        }
        String tableName = "sql".equals(fmt) ? inferTableName(trimSql) : null;
        long exported = 0;
        try (Connection connection = connectionManager.getConnection(conn);
             Statement stmt = connection.createStatement()) {
            stmt.setQueryTimeout(EXPORT_TIMEOUT_SECONDS);
            stmt.setFetchSize(1000);
            try (ResultSet rs = stmt.executeQuery(trimSql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<String> columns = new ArrayList<>();
                List<String> types = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                    types.add(meta.getColumnTypeName(i));
                }
                if ("csv".equals(fmt)) {
                    writeCsvHeader(writer, columns);
                } else if ("json".equals(fmt)) {
                    writer.write("[");
                }
                boolean first = true;
                while (rs.next() && exported < limit) {
                    if ("csv".equals(fmt)) {
                        writeCsvRow(writer, rs, colCount);
                    } else if ("json".equals(fmt)) {
                        if (!first) {
                            writer.write(",");
                        }
                        writeJsonRow(writer, rs, colCount, columns);
                    } else {
                        writeInsertRow(writer, rs, colCount, columns, types, tableName);
                    }
                    first = false;
                    exported++;
                }
                if ("json".equals(fmt)) {
                    writer.write("]");
                }
                writer.flush();
            }
        } catch (SQLException e) {
            throw new ServiceException("导出失败: " + e.getMessage());
        } catch (IOException e) {
            throw new ServiceException("导出写入失败: " + e.getMessage());
        }
        return exported;
    }

    @Override
    public List<String> describeColumns(Long connId, String sql) {
        return (List<String>) preview(connId, sql).get("columns");
    }

    private String validateQuery(Long connId, String sql) {
        if (connId == null) {
            throw new ServiceException("connId 不能为空");
        }
        String trimSql = DbAccessPolicy.normalizeLeadingComments(sql);
        if (StrUtil.isBlank(trimSql)) {
            throw new ServiceException("SQL 不能为空");
        }
        if (!SELECT_START.matcher(trimSql).find()) {
            throw new ServiceException("仅支持导出查询语句(select/show/describe/desc/explain)");
        }
        if (trimSql.endsWith(";")) {
            trimSql = trimSql.substring(0, trimSql.length() - 1).trim();
        }
        if (trimSql.contains(";")) {
            throw new ServiceException("仅支持单条 SQL");
        }
        return trimSql;
    }

    private String inferTableName(String sql) {
        Matcher m = TABLE_NAME.matcher(sql);
        if (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        throw new ServiceException("无法从 SQL 推断目标表名(INSERT 导出仅支持单表简单查询)");
    }

    private static void writeCsvHeader(Writer writer, List<String> columns) throws IOException {
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                writer.write(",");
            }
            writer.write(csvEscape(columns.get(i)));
        }
        writer.write("\r\n");
    }

    private static void writeCsvRow(Writer writer, ResultSet rs, int colCount) throws SQLException, IOException {
        for (int i = 1; i <= colCount; i++) {
            if (i > 1) {
                writer.write(",");
            }
            String value = rs.getString(i);
            writer.write(csvEscape(value));
        }
        writer.write("\r\n");
    }

    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains("\"") || value.contains(",") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static void writeJsonRow(Writer writer, ResultSet rs, int colCount, List<String> columns)
        throws SQLException, IOException {
        writer.write("{");
        for (int i = 1; i <= colCount; i++) {
            if (i > 1) {
                writer.write(",");
            }
            writer.write(jsonEscape(columns.get(i - 1)));
            writer.write(":");
            String value = rs.getString(i);
            writer.write(value == null ? "null" : jsonEscape(value));
        }
        writer.write("}");
    }

    private static String jsonEscape(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append("\"").toString();
    }

    private static void writeInsertRow(Writer writer, ResultSet rs, int colCount, List<String> columns,
                                       List<String> types, String tableName) throws SQLException, IOException {
        writer.write("INSERT INTO `" + tableName + "` (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                writer.write(",");
            }
            writer.write("`" + columns.get(i).replace("`", "``") + "`");
        }
        writer.write(") VALUES (");
        for (int i = 1; i <= colCount; i++) {
            if (i > 1) {
                writer.write(",");
            }
            String value = rs.getString(i);
            if (value == null) {
                writer.write("NULL");
            } else if (isNumericType(types.get(i - 1))) {
                writer.write(value);
            } else {
                writer.write("'" + value.replace("'", "''") + "'");
            }
        }
        writer.write(");\n");
    }

    private static boolean isNumericType(String type) {
        if (type == null) {
            return false;
        }
        String t = type.toUpperCase();
        return t.contains("INT") || t.contains("DECIMAL") || t.contains("NUMERIC")
            || t.contains("FLOAT") || t.contains("DOUBLE") || t.contains("REAL")
            || t.equals("NUMBER") || t.equals("BIGINT") || t.equals("SMALLINT");
    }
}
