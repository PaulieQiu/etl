package pan.dromara.etl.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pan.dromara.dbtool.core.DbConnectionManager;
import pan.dromara.dbtool.core.DbConnCrypto;
import pan.dromara.dbtool.core.DbTypeInfo;
import pan.dromara.dbtool.core.DbTypeRegistry;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.service.IDbConnService;
import pan.dromara.etl.config.EtlProperties;
import pan.dromara.etl.domain.EtlJob;
import pan.dromara.etl.domain.EtlJobLog;
import pan.dromara.etl.mapper.EtlJobLogMapper;
import pan.dromara.etl.mapper.EtlJobMapper;
import pan.dromara.etl.util.CsvRuleProcessor;
import pan.dromara.etl.util.EtlFileBridge;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DataX 引擎服务: 配置生成 / 进程执行 / 日志解析
 * 支持双段模式: 配置了清洗结果表时, 先 源→清洗结果表(段1), 再 清洗结果表→目标表(段2)
 *
 * @author doubao
 */
@Slf4j
@Service
public class DataXService {

    private static final Pattern P_TOTAL = Pattern.compile("读出记录总数\\s*[:：]\\s*(\\d+)");
    private static final Pattern P_READ_SPEED = Pattern.compile("记录写入速度\\s*[:：]\\s*([\\d.]+\\s*rec/s)");
    private static final Pattern P_FAILED = Pattern.compile("读写失败总数\\s*[:：]\\s*(\\d+)");

    private final EtlProperties etlProperties;
    private final DbConnectionManager connectionManager;
    private final IDbConnService dbConnService;
    private final EtlJobLogMapper jobLogMapper;
    private final EtlJobMapper jobMapper;

    /** 运行中的 DataX 进程 (jobId#segLogId -> Process), 供取消; 多源并行时按段注册 */
    private static final Map<String, Process> RUNNING = new ConcurrentHashMap<>();

    public DataXService(EtlProperties etlProperties,
                        DbConnectionManager connectionManager,
                        IDbConnService dbConnService,
                        EtlJobLogMapper jobLogMapper,
                        EtlJobMapper jobMapper) {
        this.etlProperties = etlProperties;
        this.connectionManager = connectionManager;
        this.dbConnService = dbConnService;
        this.jobLogMapper = jobLogMapper;
        this.jobMapper = jobMapper;
    }

    private static String runKey(EtlJob job, EtlJobLog jobLog) {
        String suf = jobLog == null || jobLog.getId() == null ? String.valueOf(System.nanoTime()) : String.valueOf(jobLog.getId());
        return job.getId() + "#" + suf;
    }

    /** 取消运行中的 DataX 任务(终止外部进程, 含多源并行的全部段) */
    public static void cancel(Long jobId) {
        if (jobId == null) {
            return;
        }
        String prefix = jobId + "#";
        for (java.util.Map.Entry<String, Process> e : RUNNING.entrySet()) {
            if (e.getKey().equals(String.valueOf(jobId)) || e.getKey().startsWith(prefix)) {
                try {
                    e.getValue().destroy();
                } catch (Exception ignored) {
                }
                RUNNING.remove(e.getKey());
            }
        }
    }

    /** 是否正在运行(含多源并行段) */
    public static boolean isRunning(Long jobId) {
        if (jobId == null) {
            return false;
        }
        String prefix = jobId + "#";
        for (String k : RUNNING.keySet()) {
            if (k.equals(String.valueOf(jobId)) || k.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成 DataX 配置 JSON (单段: 源→目标, 兼容旧任务; 密码为明文仅用于临时执行文件)
     */
    public String buildJobJson(EtlJob job) {
        return buildJobJsonWithRules(job);
    }

    /**
     * JOIN WHERE 轻量限定: 将裸列名(后跟比较运算符/IN/LIKE/IS/BETWEEN)加主表别名 t0.,
     * 已带 t0./t1./别名/库名限定符的跳过, 单引号字符串内容不处理。
     */
    private String qualifyJoinWhere(String where) {
        if (StrUtil.isBlank(where)) {
            return where;
        }
        StringBuilder sb = new StringBuilder();
        boolean inStr = false;
        int n = where.length();
        for (int i = 0; i < n; i++) {
            char ch = where.charAt(i);
            if (ch == '\'' && (i == 0 || where.charAt(i - 1) != '\\')) {
                inStr = !inStr;
            }
            if (inStr || !Character.isLetter(ch)) {
                sb.append(ch);
                continue;
            }
            int j = i;
            while (j < n && (Character.isLetterOrDigit(where.charAt(j)) || where.charAt(j) == '_')) {
                j++;
            }
            String word = where.substring(i, j);
            // 是否已带限定符(前一个非空字符是 . 或已是 t0./t1.)
            boolean qualified = i > 0 && where.charAt(i - 1) == '.';
            // 跳过关键字(WHERE/AND/OR/NOT/NULL/TRUE/FALSE)
            boolean kw = "AND".equalsIgnoreCase(word) || "OR".equalsIgnoreCase(word) || "WHERE".equalsIgnoreCase(word)
                || "NOT".equalsIgnoreCase(word) || "NULL".equalsIgnoreCase(word) || "TRUE".equalsIgnoreCase(word)
                || "FALSE".equalsIgnoreCase(word);
            int k = j;
            while (k < n && where.charAt(k) == ' ') {
                k++;
            }
            boolean isCmp = k < n && (where.charAt(k) == '=' || where.charAt(k) == '<' || where.charAt(k) == '>');
            String up = k < n ? where.substring(k, Math.min(n, k + 8)).toUpperCase() : "";
            boolean isWordCmp = up.startsWith("LIKE") || up.startsWith("IN ") || up.startsWith("IS ") || up.startsWith("BETWEEN");
            if (!qualified && !kw && (isCmp || isWordCmp)) {
                sb.append("t0.").append(word);
            } else {
                sb.append(word);
            }
            i = j - 1;
        }
        return sb.toString();
    }

    private boolean isJoinMode(EtlJob job) {
        if (StrUtil.isBlank(job.getSrcList())) {
            return false;
        }
        try {
            cn.hutool.json.JSONArray arr = JSONUtil.parseArray(job.getSrcList());
            for (int i = 0; i < arr.size(); i++) {
                Object o = arr.get(i);
                if (o instanceof cn.hutool.json.JSONObject) {
                    String jt = ((cn.hutool.json.JSONObject) o).getStr("joinType", "");
                    if ("JOIN".equalsIgnoreCase(jt) || "LEFT_JOIN".equalsIgnoreCase(jt) || "LEFT JOIN".equalsIgnoreCase(jt)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private String srcArrFirstTable(EtlJob job) {
        if (StrUtil.isNotBlank(job.getSrcList())) {
            try {
                cn.hutool.json.JSONArray arr = JSONUtil.parseArray(job.getSrcList());
                if (!arr.isEmpty()) {
                    String t = arr.getJSONObject(0).getStr("srcTable", "");
                    if (StrUtil.isNotBlank(t)) {
                        return t;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return job.getSrcTable();
    }

    /**
     * 多源 JOIN 组合(同库): SELECT 映射列 FROM t0 [LEFT JOIN t_i ON t0.joinRef = t_i.joinField ...] WHERE ...
     * → 目标表; reader 用 mysqlreader/postgresqlreader 的 querySql 模式单条 SQL 读取。
     * 跨库 JOIN 不支持(请使用 Kettle 引擎); 增量条件经 job.srcWhere 注入 WHERE。
     */
    public String buildJobJsonJoin(EtlJob job) {
        return buildJobJsonJoin(job, null);
    }

    /**
     * 构建 JOIN DataX job JSON; extraWhere 为分片条件(已带主表 t0. 别名), 追加到 WHERE 末尾。
     */
    public String buildJobJsonJoin(EtlJob job, String extraWhere) {
        DbConn src = dbConnService.getById(job.getSrcConnId());
        DbConn dst = dbConnService.getById(job.getDstConnId());
        if (src == null || dst == null) {
            throw new RuntimeException("数据源不存在: srcConnId=" + job.getSrcConnId() + ", dstConnId=" + job.getDstConnId());
        }
        String srcSchema = StrUtil.blankToDefault(job.getSrcSchema(), src.getDatabaseName());
        String dstSchema = StrUtil.blankToDefault(job.getDstSchema(), dst.getDatabaseName());
        cn.hutool.json.JSONArray srcArr = JSONUtil.parseArray(job.getSrcList());
        if (srcArr == null || srcArr.size() < 2) {
            throw new RuntimeException("JOIN 模式至少需要 2 张源表(srcList)");
        }
        String q = "MYSQL".equalsIgnoreCase(src.getDbType()) ? "`" : "\"";
        for (int i = 1; i < srcArr.size(); i++) {
            cn.hutool.json.JSONObject row = srcArr.getJSONObject(i);
            Long cid = row.getLong("srcConnId");
            if (cid != null && !cid.equals(job.getSrcConnId())) {
                throw new RuntimeException("跨库 JOIN 请使用 Kettle 引擎(DataX 仅支持同库 JOIN)");
            }
        }
        // 列映射: fieldMapping 分组(byTable) → t{i}.srcField AS dstField
        java.util.Map<String, cn.hutool.json.JSONArray> byTable = new java.util.LinkedHashMap<>();
        if (StrUtil.isNotBlank(job.getFieldMapping())) {
            cn.hutool.json.JSONArray arr = JSONUtil.parseArray(job.getFieldMapping());
            if (!arr.isEmpty() && arr.getJSONObject(0).containsKey("maps")) {
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject g = arr.getJSONObject(i);
                    byTable.put(g.getStr("srcTable", ""), g.getJSONArray("maps"));
                }
            }
        }
        java.util.List<String> selCols = new java.util.ArrayList<>();
        java.util.List<String> dstCols = new java.util.ArrayList<>();
        java.util.LinkedHashSet<String> dstSeen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < srcArr.size(); i++) {
            String tbl = srcArr.getJSONObject(i).getStr("srcTable", "");
            cn.hutool.json.JSONArray maps = byTable.get(tbl);
            if (maps == null || maps.isEmpty()) {
                if (dstSeen.isEmpty()) {
                    selCols.add("t0.*");
                }
            } else {
                for (int j = 0; j < maps.size(); j++) {
                    JSONObject m = maps.getJSONObject(j);
                    String srcF = m.getStr("srcField", "");
                    String dstF = m.getStr("dstField", "");
                    if (StrUtil.isBlank(srcF)) {
                        continue;
                    }
                    String out = StrUtil.isBlank(dstF) ? srcF : dstF;
                    if (dstSeen.contains(out)) {
                        continue;
                    }
                    dstSeen.add(out);
                    selCols.add("t" + i + "." + q + srcF + q + " AS " + q + out + q);
                    dstCols.add(out);
                }
            }
        }
        StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", selCols))
            .append(" FROM ").append(quoteName(srcSchema, srcArr.getJSONObject(0).getStr("srcTable", ""), src.getDbType())).append(" t0");
        for (int i = 1; i < srcArr.size(); i++) {
            cn.hutool.json.JSONObject item = srcArr.getJSONObject(i);
            String jt = item.getStr("joinType", "JOIN");
            boolean left = "LEFT_JOIN".equalsIgnoreCase(jt) || "LEFT JOIN".equalsIgnoreCase(jt);
            String joinField = item.getStr("joinField", "");
            String joinRef = item.getStr("joinRef", "");
            sql.append(left ? " LEFT JOIN " : " JOIN ")
               .append(quoteName(srcSchema, item.getStr("srcTable", ""), src.getDbType())).append(" t").append(i);
            if (StrUtil.isNotBlank(joinRef) && StrUtil.isNotBlank(joinField)) {
                String[] refs = joinRef.split(",");
                String[] flds = joinField.split(",");
                boolean firstOn = true;
                for (int ki = 0; ki < Math.min(refs.length, flds.length); ki++) {
                    String rf = refs[ki].trim();
                    String ff = flds[ki].trim();
                    if (StrUtil.isBlank(rf) || StrUtil.isBlank(ff)) {
                        continue;
                    }
                    sql.append(firstOn ? " ON " : " AND ")
                       .append("t0.").append(q).append(rf).append(q)
                       .append(" = t").append(i).append(".").append(q).append(ff).append(q);
                    firstOn = false;
                }
            }
        }
        String w0 = srcArr.getJSONObject(0).getStr("srcWhere", "");
        String wj = job.getSrcWhere();
        // 裸列名限定到主表 t0.(JOIN 后多表同名列会产生歧义); 已带别名/限定符的跳过
        w0 = qualifyJoinWhere(w0);
        wj = qualifyJoinWhere(wj);
        String where = StrUtil.isBlank(w0) ? wj : (StrUtil.isBlank(wj) ? w0 : "(" + w0 + ") AND (" + wj + ")");
        // 清洗规则(JOIN 合并流): 过滤类追加 WHERE; 去重 → SELECT DISTINCT; 唯一/列表达式 JOIN 场景不支持(建议清洗结果表管线)
        java.util.List<cn.hutool.json.JSONObject> cleanRules = parseRuleList(job.getCleanRules());
        RuleSql rsJoin = compileRules(cleanRules, null, src, null, null);
        String ruleWhere = rsJoin.whereExtra();
        if (rsJoin.distinct) {
            sql.replace(0, sql.indexOf(" FROM "), "SELECT DISTINCT");
        }
        if (StrUtil.isNotBlank(ruleWhere)) {
            where = StrUtil.isBlank(where) ? ruleWhere : "(" + where + ") AND (" + ruleWhere + ")";
        }
        if (StrUtil.isNotBlank(where)) {
            sql.append(" WHERE ").append(where);
        }
        if (StrUtil.isNotBlank(extraWhere)) {
            sql.append(StrUtil.isNotBlank(where) ? " AND (" : " WHERE (").append(extraWhere).append(")");
        }
        // reader: querySql 模式(DataX mysqlreader 的 querySql 必须放在 connection 元素内, 与 table 二选一)
        JSONObject readerParam = new JSONObject();
        readerParam.set("username", src.getUsername());
        readerParam.set("password", DbConnCrypto.decrypt(src.getPassword()));
        JSONObject srcConn = new JSONObject();
        srcConn.set("querySql", new cn.hutool.json.JSONArray().set(sql.toString()));
        srcConn.set("jdbcUrl", new cn.hutool.json.JSONArray().set(buildJdbcUrl(src, srcSchema)));
        readerParam.set("connection", new cn.hutool.json.JSONArray().set(srcConn));
        JSONObject reader = new JSONObject();
        reader.set("name", readerName(src.getDbType()));
        reader.set("parameter", readerParam);
        // writer
        JSONObject writerParam = new JSONObject();
        writerParam.set("username", dst.getUsername());
        writerParam.set("password", DbConnCrypto.decrypt(dst.getPassword()));
        writerParam.set("column", dstCols.isEmpty() ? new String[]{"*"} : dstCols.toArray(new String[0]));
        writerParam.set("writeMode", normalizeWriteMode(job.getDstWriteMode()));
        if ("TRUNCATE".equalsIgnoreCase(job.getDstWriteMode())) {
            writerParam.set("preSql", new cn.hutool.json.JSONArray().set("truncate table " + quoteName(dstSchema, job.getDstTable(), dst.getDbType())));
        }
        JSONObject dstConn = new JSONObject();
        dstConn.set("table", new cn.hutool.json.JSONArray().set(job.getDstTable()));
        dstConn.set("jdbcUrl", buildJdbcUrl(dst, dstSchema));
        writerParam.set("connection", new cn.hutool.json.JSONArray().set(dstConn));
        JSONObject writer = new JSONObject();
        writer.set("name", writerName(dst.getDbType()));
        writer.set("parameter", writerParam);

        JSONObject content = new JSONObject();
        content.set("reader", reader);
        content.set("writer", writer);

        JSONObject speed = new JSONObject();
        int channel = job.getSpeedChannel() == null ? 3 : job.getSpeedChannel();
        speed.set("channel", channel);
        Long byteLimit = job.getSpeedByte() == null ? 0L : job.getSpeedByte();
        if (byteLimit != null && byteLimit > 0) {
            speed.set("byte", Math.max(1L, byteLimit / Math.max(1, channel)));
        }
        JSONObject errorLimit = new JSONObject();
        errorLimit.set("record", job.getErrorLimitCount() == null ? 0 : job.getErrorLimitCount());
        errorLimit.set("percentage", job.getErrorLimitPercent() == null ? 0.0 : job.getErrorLimitPercent());
        JSONObject setting = new JSONObject();
        setting.set("speed", speed);
        setting.set("errorLimit", errorLimit);
        JSONObject jobObj = new JSONObject();
        jobObj.set("content", new cn.hutool.json.JSONArray().set(content));
        jobObj.set("setting", setting);
        JSONObject root = new JSONObject();
        root.set("job", jobObj);
        if (byteLimit != null && byteLimit > 0) {
            long per = Math.max(1L, byteLimit / Math.max(1, channel));
            JSONObject chSpeed = new JSONObject();
            chSpeed.set("speed", new JSONObject().set("byte", per));
            root.set("core", new JSONObject().set("transport",
                new JSONObject().set("channel", chSpeed)));
        }
        return root.toStringPretty();
    }

    /**
     * 构造单段 DataX job JSON: reader(源连接/表/列) → writer(目标连接/表/列)
     *
     * @param mode    INSERT/REPLACE/TRUNCATE/UPDATE
     * @param splitPk 是否尝试为 reader 设置 splitPk
     */
    public String buildJobJsonSeg(EtlJob job, DbConn src, String srcSchema, String srcTable, String srcWhere,
                                  String[] srcCols, DbConn dst, String dstSchema, String dstTable, String[] dstCols,
                                  String mode, Integer channel, Long byteLimit, boolean splitPk) {
        JSONObject readerParam = new JSONObject();
        readerParam.set("username", src.getUsername());
        readerParam.set("password", DbConnCrypto.decrypt(src.getPassword()));
        readerParam.set("column", srcCols == null || srcCols.length == 0 ? new String[]{"*"} : srcCols);
        if (splitPk) {
            String srcPk = findPk(src, srcSchema, srcTable);
            if (srcPk != null) {
                readerParam.set("splitPk", srcPk);
            }
        }
        JSONObject srcConn = new JSONObject();
        srcConn.set("table", new JSONArray().set(srcTable));
        srcConn.set("jdbcUrl", new JSONArray().set(buildJdbcUrl(src, srcSchema)));
        readerParam.set("connection", new JSONArray().set(srcConn));
        if (StrUtil.isNotBlank(srcWhere)) {
            readerParam.set("where", srcWhere);
        }
        JSONObject reader = new JSONObject();
        reader.set("name", readerName(src.getDbType()));
        reader.set("parameter", readerParam);

        JSONObject writerParam = new JSONObject();
        writerParam.set("username", dst.getUsername());
        writerParam.set("password", DbConnCrypto.decrypt(dst.getPassword()));
        writerParam.set("column", dstCols == null || dstCols.length == 0 ? new String[]{"*"} : dstCols);
        writerParam.set("writeMode", normalizeWriteMode(mode));
        if ("TRUNCATE".equalsIgnoreCase(mode)) {
            JSONArray preSql = new JSONArray().set("truncate table " + quoteName(dstSchema, dstTable, dst.getDbType()));
            writerParam.set("preSql", preSql);
        } else if ("DELETE_INSERT".equalsIgnoreCase(mode)) {
            String key = job == null ? null : job.getDstReplaceKey();
            if (StrUtil.isNotBlank(key) && StrUtil.isNotBlank(srcTable)) {
                String q = normalizeType(dst.getDbType()).equals("MYSQL") ? "`" : "\"";
                String del = "DELETE FROM " + quoteName(dstSchema, dstTable, dst.getDbType())
                    + " WHERE " + q + key + q + " IN (SELECT " + q + key + q + " FROM "
                    + quoteName(srcSchema, srcTable, src.getDbType()) + ")";
                writerParam.set("preSql", new JSONArray().set(del));
            }
        }
        JSONObject dstConn = new JSONObject();
        dstConn.set("table", new JSONArray().set(dstTable));
        dstConn.set("jdbcUrl", buildJdbcUrl(dst, dstSchema));
        writerParam.set("connection", new JSONArray().set(dstConn));
        JSONObject writer = new JSONObject();
        writer.set("name", writerName(dst.getDbType()));
        writer.set("parameter", writerParam);

        JSONObject content = new JSONObject();
        content.set("reader", reader);
        content.set("writer", writer);

        JSONObject speed = new JSONObject();
        speed.set("channel", channel == null ? 3 : channel);
        if (byteLimit != null && byteLimit > 0) {
            // DataX 的 speed.byte 为单 channel 速率: 总限速均分到各通道, 保证非空正数
            int ch = channel == null ? 3 : Math.max(1, channel);
            speed.set("byte", Math.max(1, byteLimit / ch));
        }
        JSONObject errorLimit = new JSONObject();
        errorLimit.set("record", job.getErrorLimitCount() == null ? 0 : job.getErrorLimitCount());
        errorLimit.set("percentage", job.getErrorLimitPercent() == null ? 0.0 : job.getErrorLimitPercent());
        JSONObject setting = new JSONObject();
        setting.set("speed", speed);
        setting.set("errorLimit", errorLimit);

        JSONObject jobObj = new JSONObject();
        jobObj.set("content", new JSONArray().set(content));
        jobObj.set("setting", setting);

        JSONObject root = new JSONObject();
        root.set("job", jobObj);
        if (byteLimit != null && byteLimit > 0) {
            // 该 DataX 版本校验: 设置 job.setting.speed.byte(总限速) 后, 必须同时存在
            // root 层 core.transport.channel.speed.byte(单通道限速), 否则报
            // "在有总bps限速条件下，单个channel的bps值不能为空". 此处显式注入通道限速。
            int ch = channel == null ? 3 : Math.max(1, channel);
            long per = Math.max(1L, byteLimit / ch);
            JSONObject chSpeed = new JSONObject();
            chSpeed.set("speed", new JSONObject().set("byte", per));
            root.set("core", new JSONObject().set("transport",
                new JSONObject().set("channel", chSpeed)));
        }
        return root.toStringPretty();
    }

    // ==================== 清洗/质量规则引擎 (规则 -> SQL 注入) ====================

    /** 解析规则 JSON: 兼容分组 [{srcTable, rules:[...]}] 与平铺 [{ruleName,ruleType,ruleConfig}] */
    private java.util.List<cn.hutool.json.JSONObject> parseRuleList(String rulesJson) {
        java.util.List<cn.hutool.json.JSONObject> out = new java.util.ArrayList<>();
        if (StrUtil.isBlank(rulesJson)) {
            return out;
        }
        try {
            cn.hutool.json.JSONArray arr = JSONUtil.parseArray(rulesJson);
            for (Object o : arr) {
                if (!(o instanceof cn.hutool.json.JSONObject)) {
                    continue;
                }
                cn.hutool.json.JSONObject item = (cn.hutool.json.JSONObject) o;
                cn.hutool.json.JSONArray inner = item.getJSONArray("rules");
                if (inner != null) {
                    for (Object io : inner) {
                        if (io instanceof cn.hutool.json.JSONObject) {
                            cn.hutool.json.JSONObject r = (cn.hutool.json.JSONObject) io;
                            if (StrUtil.isNotBlank(r.getStr("ruleType", "")) || StrUtil.isNotBlank(r.getStr("ruleName", ""))) {
                                out.add(r);
                            }
                        }
                    }
                } else if (StrUtil.isNotBlank(item.getStr("ruleType", "")) || StrUtil.isNotBlank(item.getStr("ruleName", ""))) {
                    out.add(item);
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return out;
    }

    /** 清洗规则按源表过滤(分组格式带 srcTable) */
    private java.util.List<cn.hutool.json.JSONObject> rulesForTable(String rulesJson, String table) {
        java.util.List<cn.hutool.json.JSONObject> out = new java.util.ArrayList<>();
        if (StrUtil.isBlank(rulesJson)) {
            return out;
        }
        try {
            cn.hutool.json.JSONArray arr = JSONUtil.parseArray(rulesJson);
            for (Object o : arr) {
                if (!(o instanceof cn.hutool.json.JSONObject)) {
                    continue;
                }
                cn.hutool.json.JSONObject item = (cn.hutool.json.JSONObject) o;
                cn.hutool.json.JSONArray inner = item.getJSONArray("rules");
                if (inner != null) {
                    String tbl = item.getStr("srcTable", "");
                    if (StrUtil.isNotBlank(tbl) && !tbl.equalsIgnoreCase(table)) {
                        continue;
                    }
                    for (Object io : inner) {
                        if (io instanceof cn.hutool.json.JSONObject) {
                            cn.hutool.json.JSONObject r = (cn.hutool.json.JSONObject) io;
                            if (StrUtil.isNotBlank(r.getStr("ruleType", "")) || StrUtil.isNotBlank(r.getStr("ruleName", ""))) {
                                out.add(r);
                            }
                        }
                    }
                } else {
                    out.add(item);
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return out;
    }

    /** 规则编译结果: 过滤条件 / 去重 / 列表达式 / 唯一字段 */
    private static class RuleSql {
        java.util.List<String> whereParts = new java.util.ArrayList<>();
        boolean distinct = false;
        String uniqueField = null;
        java.util.Map<String, String> colExpr = new java.util.LinkedHashMap<>();
        // 默认值新增列 SELECT 片段(如 UUID() AS `f_guid`)
        java.util.List<String> extraSel = new java.util.ArrayList<>();

        boolean needSqlMode() {
            return distinct || uniqueField != null || !colExpr.isEmpty();
        }

        String whereExtra() {
            return whereParts.isEmpty() ? "" : String.join(" AND ", whereParts);
        }
    }

    private boolean inCols(String[] cols, String f) {
        if (cols == null || f == null) {
            return false;
        }
        for (String c : cols) {
            if (f.equalsIgnoreCase(c)) {
                return true;
            }
        }
        return false;
    }

    /** 从规则配置中提取字段名 */
    private String ruleField(cn.hutool.json.JSONObject cfg, String[] cols) {
        if (cfg != null) {
            String f = cfg.getStr("field", "");
            if (StrUtil.isNotBlank(f)) {
                return f;
            }
            String c = cfg.getStr("column", "");
            if (StrUtil.isNotBlank(c)) {
                return c;
            }
        }
        return (cols != null && cols.length > 0) ? cols[0] : "";
    }

    private String qi(DbConn conn, String name) {
        String q = "MYSQL".equalsIgnoreCase(conn.getDbType()) ? "`" : "\"";
        return q + name + q;
    }

    /** 编译规则列表为 SQL 片段 */
    private RuleSql compileRules(java.util.List<cn.hutool.json.JSONObject> rules, String[] cols, DbConn conn,
                                 String srcSchema, String table) {
        RuleSql rs = new RuleSql();
        if (rules == null || rules.isEmpty()) {
            return rs;
        }
        for (cn.hutool.json.JSONObject r : rules) {
            String type = StrUtil.blankToDefault(r.getStr("ruleType", ""), r.getStr("ruleName", ""));
            String config = r.getStr("ruleConfig", "");
            cn.hutool.json.JSONObject cfg = null;
            try {
                if (StrUtil.isNotBlank(config) && config.trim().startsWith("{")) {
                    cfg = JSONUtil.parseObj(config);
                }
            } catch (Exception ignored) {
                cfg = null;
            }
            String field = ruleField(cfg, cols);
            String qf = StrUtil.isBlank(field) ? "" : qi(conn, field);
            switch (type) {
                case "去空值":
                case "非空":
                case "非空校验":
                case "NOT_NULL":
                    if (StrUtil.isNotBlank(qf)) {
                        rs.whereParts.add(qf + " IS NOT NULL");
                    }
                    break;
                case "去重复":
                case "去重":
                case "DEDUP":
                case "DISTINCT":
                case "唯一校验":
                case "UNIQUE":
                    if (StrUtil.isNotBlank(field)) {
                        // 指定字段: 按字段去重/唯一(窗口函数保留每组一行)
                        rs.uniqueField = field;
                    } else {
                        rs.distinct = true;
                    }
                    break;
                case "格式规范化":
                case "FORMAT":
                    if (StrUtil.isNotBlank(field)) {
                        String action = cfg == null ? "" : StrUtil.blankToDefault(cfg.getStr("action", ""), "trim");
                        String act = action.toLowerCase();
                        String expr = qi(conn, field);
                        if ("lower".equals(act) || "小写".equals(act)) {
                            expr = "LOWER(" + qi(conn, field) + ")";
                        } else if ("upper".equals(act) || "大写".equals(act)) {
                            expr = "UPPER(" + qi(conn, field) + ")";
                        } else if ("date".equals(act) || "日期".equals(act)) {
                            String fmt = cfg == null ? "%Y-%m-%d" : StrUtil.blankToDefault(cfg.getStr("pattern", ""), "%Y-%m-%d");
                            if ("MYSQL".equalsIgnoreCase(conn.getDbType())) {
                                expr = "DATE_FORMAT(" + qi(conn, field) + ", '" + fmt.replace("'", "''") + "')";
                            } else {
                                expr = "CAST(" + qi(conn, field) + " AS VARCHAR(32))";
                            }
                        } else {
                            expr = "TRIM(" + qi(conn, field) + ")";
                        }
                        rs.colExpr.put(field, expr + " AS " + qi(conn, field));
                    }
                    break;
                case "字段映射":
                case "MAPPING":
                    if (cfg != null) {
                        String sf = cfg.getStr("srcField", "");
                        String df = cfg.getStr("dstField", "");
                        if (StrUtil.isNotBlank(sf) && StrUtil.isNotBlank(df)) {
                            rs.colExpr.put(sf, qi(conn, sf) + " AS " + qi(conn, df));
                        }
                    }
                    break;
                case "单位换算":
                case "UNIT":
                    if (StrUtil.isNotBlank(field)) {
                        double factor = cfg == null ? 1.0 : cfg.getDouble("factor", 1.0);
                        double offset = cfg == null ? 0.0 : cfg.getDouble("offset", 0.0);
                        rs.colExpr.put(field, "(" + qi(conn, field) + " * " + factor + " + " + offset + ") AS " + qi(conn, field));
                    }
                    break;
                case "默认值填充":
                case "DEFAULT":
                    if (StrUtil.isNotBlank(field)) {
                        String dv = cfg == null ? "" : StrUtil.blankToDefault(cfg.getStr("defaultValue", ""), cfg.getStr("value", ""));
                        if (!inCols(cols, field)) {
                            // 源无此列: 作为新增列直接给常量值
                            rs.extraSel.add("'" + dv.replace("'", "''") + "' AS " + qi(conn, field));
                        } else if ("MYSQL".equalsIgnoreCase(conn.getDbType())) {
                            rs.colExpr.put(field, "IFNULL(" + qi(conn, field) + ", '" + dv.replace("'", "''") + "') AS " + qi(conn, field));
                        } else {
                            rs.colExpr.put(field, "COALESCE(" + qi(conn, field) + ", '" + dv.replace("'", "''") + "') AS " + qi(conn, field));
                        }
                    }
                    break;
                case "UUID填充":
                case "UUID":
                    if (StrUtil.isNotBlank(field)) {
                        String uexpr = "MYSQL".equalsIgnoreCase(conn.getDbType()) ? "UUID()" : "SYS_GUID()";
                        if (inCols(cols, field)) {
                            rs.colExpr.put(field, uexpr + " AS " + qi(conn, field));
                        } else {
                            rs.extraSel.add(uexpr + " AS " + qi(conn, field));
                        }
                    }
                    break;
                case "当前时间填充":
                case "NOW":
                    if (StrUtil.isNotBlank(field)) {
                        String nexpr = "MYSQL".equalsIgnoreCase(conn.getDbType()) ? "NOW()" : "CURRENT_TIMESTAMP";
                        if (inCols(cols, field)) {
                            rs.colExpr.put(field, nexpr + " AS " + qi(conn, field));
                        } else {
                            rs.extraSel.add(nexpr + " AS " + qi(conn, field));
                        }
                    }
                    break;
                case "范围校验":
                case "RANGE":
                    if (StrUtil.isNotBlank(qf)) {
                        java.util.List<String> parts = new java.util.ArrayList<>();
                        if (cfg != null) {
                            if (cfg.get("min") != null) {
                                parts.add(qf + " >= " + cfg.get("min"));
                            }
                            if (cfg.get("max") != null) {
                                parts.add(qf + " <= " + cfg.get("max"));
                            }
                        }
                        if (!parts.isEmpty()) {
                            rs.whereParts.add(String.join(" AND ", parts));
                        }
                    }
                    break;
                case "枚举校验":
                case "ENUM":
                    if (StrUtil.isNotBlank(qf)) {
                        java.util.List<String> vals = new java.util.ArrayList<>();
                        if (cfg != null && cfg.getJSONArray("values") != null) {
                            for (Object v : cfg.getJSONArray("values")) {
                                vals.add("'" + String.valueOf(v).replace("'", "''") + "'");
                            }
                        } else if (cfg != null && StrUtil.isNotBlank(cfg.getStr("values", ""))) {
                            for (String v : cfg.getStr("values", "").split(",")) {
                                vals.add("'" + v.trim().replace("'", "''") + "'");
                            }
                        }
                        if (!vals.isEmpty()) {
                            rs.whereParts.add(qf + " IN (" + String.join(", ", vals) + ")");
                        }
                    }
                    break;
                case "格式校验":
                case "REGEXP":
                    if (StrUtil.isNotBlank(qf) && cfg != null && StrUtil.isNotBlank(cfg.getStr("pattern", ""))) {
                        String pat = cfg.getStr("pattern", "").replace("'", "''");
                        if ("MYSQL".equalsIgnoreCase(conn.getDbType())) {
                            rs.whereParts.add(qf + " REGEXP '" + pat + "'");
                        } else {
                            rs.whereParts.add("REGEXP_LIKE(" + qf + ", '" + pat + "')");
                        }
                    }
                    break;
                case "关联完整性":
                case "FK":
                    if (StrUtil.isNotBlank(qf) && cfg != null
                        && StrUtil.isNotBlank(cfg.getStr("refTable", "")) && StrUtil.isNotBlank(cfg.getStr("refField", ""))) {
                        String rt = cfg.getStr("refTable", "").replace("'", "''");
                        String rf = cfg.getStr("refField", "").replace("'", "''");
                        rs.whereParts.add(qf + " IN (SELECT " + qi(conn, rf) + " FROM " + rt + ")");
                    }
                    break;
                case "叶子标记":
                case "LEAF":
                    if (cfg != null && StrUtil.isNotBlank(table)) {
                        String fld = StrUtil.blankToDefault(cfg.getStr("field", ""), "");
                        String codeField = StrUtil.blankToDefault(cfg.getStr("codeField", ""), "");
                        String parentField = StrUtil.blankToDefault(cfg.getStr("parentField", ""), "");
                        String leafValue = StrUtil.blankToDefault(cfg.getStr("leafValue", ""), "1");
                        String nonLeafValue = StrUtil.blankToDefault(cfg.getStr("nonLeafValue", ""), "0");
                        if (StrUtil.isNotBlank(fld) && StrUtil.isNotBlank(codeField) && StrUtil.isNotBlank(parentField)) {
                            String tbl = quoteName(srcSchema, table, conn.getDbType());
                            String expr = "CASE WHEN " + qi(conn, codeField) + " IN (SELECT " + qi(conn, parentField)
                                + " FROM " + tbl + ") THEN '" + nonLeafValue + "' ELSE '" + leafValue + "' END";
                            if (inCols(cols, fld)) {
                                rs.colExpr.put(fld, expr + " AS " + qi(conn, fld));
                            } else {
                                rs.extraSel.add(expr + " AS " + qi(conn, fld));
                            }
                        }
                    }
                    break;
                case "SQL 清洗":
                case "自定义 SQL":
                case "SQL":
                    if (cfg != null && StrUtil.isNotBlank(cfg.getStr("sql", ""))) {
                        rs.whereParts.add("(" + cfg.getStr("sql", "") + ")");
                    } else if (StrUtil.isNotBlank(config) && !config.trim().startsWith("{")) {
                        rs.whereParts.add("(" + config + ")");
                    }
                    break;
                default:
                    break;
            }
        }
        return rs;
    }

    /** 编译后的 SELECT 列(应用列表达式) */
    private java.util.List<String> selectCols(String[] srcCols, RuleSql rs, DbConn conn) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (srcCols == null || srcCols.length == 0) {
            out.add("*");
            return out;
        }
        for (String col : srcCols) {
            if ("*".equals(col)) {
                out.add("*");
            } else {
                out.add(rs.colExpr.containsKey(col) ? rs.colExpr.get(col) : qi(conn, col));
            }
        }
        return out;
    }

    /** 提取列片段别名(AS 后名字, 无别名取原列) */
    private String selColName(String col) {
        int idx = col.toLowerCase().lastIndexOf(" as ");
        return (idx >= 0 ? col.substring(idx + 4).trim() : col);
    }

    /** 拼装规则后的源查询 SQL(querySql 模式) */
    private String buildRuleSql(DbConn src, String srcSchema, String srcTable, String srcWhere,
                                String[] srcCols, RuleSql rs, boolean joinAlias) {
        String q = "MYSQL".equalsIgnoreCase(src.getDbType()) ? "`" : "\"";
        String qt = StrUtil.isBlank(srcSchema) ? q + srcTable + q
            : q + srcSchema + q + "." + q + srcTable + q;
        java.util.List<String> sel = selectCols(srcCols, rs, src);
        // 唯一校验: 内层追加 ROW_NUMBER, 外层过滤 rn=1 并还原列
        if (rs.uniqueField != null) {
            java.util.List<String> origin = new java.util.ArrayList<>();
            for (String s : sel) {
                // selColName 已含标识符引号(如 `category_id`), 直接拼 tq. 前缀, 避免二次引号
                origin.add("tq." + selColName(s));
            }
            for (String e : rs.extraSel) {
                origin.add("tq." + selColName(e));
            }
            String inner = (rs.distinct ? "SELECT DISTINCT " : "SELECT ") + String.join(", ", sel)
                + ", ROW_NUMBER() OVER (PARTITION BY " + q + rs.uniqueField + q
                + " ORDER BY (SELECT NULL)) AS " + q + "rn" + q + " FROM " + qt;
            if (StrUtil.isNotBlank(srcWhere) || !rs.whereParts.isEmpty()) {
                inner += " WHERE " + mergeWhere(srcWhere, rs.whereExtra());
            }
            return "SELECT " + String.join(", ", origin) + " FROM (" + inner + ") tq WHERE tq." + q + "rn" + q + " = 1";
        }
        if (!rs.extraSel.isEmpty()) {
            sel.addAll(rs.extraSel);
        }
        String sql = (rs.distinct ? "SELECT DISTINCT " : "SELECT ") + String.join(", ", sel) + " FROM " + qt;
        if (StrUtil.isNotBlank(srcWhere) || !rs.whereParts.isEmpty()) {
            sql += " WHERE " + mergeWhere(srcWhere, rs.whereExtra());
        }
        return sql;
    }

    private String mergeWhere(String base, String extra) {
        if (StrUtil.isBlank(base)) {
            return StrUtil.blankToDefault(extra, "1=1");
        }
        if (StrUtil.isBlank(extra)) {
            return base;
        }
        return "(" + base + ") AND (" + extra + ")";
    }

    /**
     * 规则模式单段 DataX job: reader 用 querySql(整条 SELECT 含规则), writer 同 buildJobJsonSeg
     */
    public String buildJobJsonSegSql(EtlJob job, DbConn src, String srcSchema, String srcTable, String srcWhere,
                                     String[] srcCols, RuleSql rs, DbConn dst, String dstSchema, String dstTable,
                                     String[] dstCols, String mode, Integer channel, Long byteLimit) {
        String sql = buildRuleSql(src, srcSchema, srcTable, srcWhere, srcCols, rs, false);
        JSONObject readerParam = new JSONObject();
        readerParam.set("username", src.getUsername());
        readerParam.set("password", DbConnCrypto.decrypt(src.getPassword()));
        JSONObject srcConn = new JSONObject();
        srcConn.set("jdbcUrl", new JSONArray().set(buildJdbcUrl(src, srcSchema)));
        srcConn.set("querySql", new JSONArray().set(sql));
        readerParam.set("connection", new JSONArray().set(srcConn));
        JSONObject reader = new JSONObject();
        reader.set("name", readerName(src.getDbType()));
        reader.set("parameter", readerParam);

        JSONObject writerParam = new JSONObject();
        writerParam.set("username", dst.getUsername());
        writerParam.set("password", DbConnCrypto.decrypt(dst.getPassword()));
        // writer column = 映射目标列 + 清洗规则 extraSel 新增列(默认值/UUID/当前时间/叶子标记等)
        java.util.List<String> wcols2 = new java.util.ArrayList<>();
        if (dstCols != null) {
            for (String dc : dstCols) {
                wcols2.add(dc);
            }
        }
        if (rs != null && rs.extraSel != null) {
            for (String e : rs.extraSel) {
                int ai = e.lastIndexOf(" AS ");
                if (ai >= 0) {
                    String col = e.substring(ai + 4).trim().replace("`", "").replace("\"", "");
                    if (!wcols2.contains(col)) {
                        wcols2.add(col);
                    }
                }
            }
        }
        writerParam.set("column", wcols2.isEmpty() ? new String[]{"*"} : wcols2.toArray(new String[0]));
        writerParam.set("writeMode", normalizeWriteMode(mode));
        if ("TRUNCATE".equalsIgnoreCase(mode)) {
            JSONArray preSql = new JSONArray().set("truncate table " + quoteName(dstSchema, dstTable, dst.getDbType()));
            writerParam.set("preSql", preSql);
        } else if ("DELETE_INSERT".equalsIgnoreCase(mode)) {
            String key = job == null ? null : job.getDstReplaceKey();
            if (StrUtil.isNotBlank(key) && StrUtil.isNotBlank(srcTable)) {
                String q = normalizeType(dst.getDbType()).equals("MYSQL") ? "`" : "\"";
                String del = "DELETE FROM " + quoteName(dstSchema, dstTable, dst.getDbType())
                    + " WHERE " + q + key + q + " IN (SELECT " + q + key + q + " FROM "
                    + quoteName(srcSchema, srcTable, src.getDbType()) + ")";
                writerParam.set("preSql", new JSONArray().set(del));
            }
        }
        JSONObject dstConn = new JSONObject();
        dstConn.set("table", new JSONArray().set(dstTable));
        dstConn.set("jdbcUrl", buildJdbcUrl(dst, dstSchema));
        writerParam.set("connection", new JSONArray().set(dstConn));
        JSONObject writer = new JSONObject();
        writer.set("name", writerName(dst.getDbType()));
        writer.set("parameter", writerParam);

        JSONObject content = new JSONObject();
        content.set("reader", reader);
        content.set("writer", writer);

        JSONObject speed = new JSONObject();
        speed.set("channel", channel == null ? 3 : channel);
        if (byteLimit != null && byteLimit > 0) {
            int ch = channel == null ? 3 : Math.max(1, channel);
            speed.set("byte", Math.max(1, byteLimit / ch));
        }
        JSONObject errorLimit = new JSONObject();
        errorLimit.set("record", job.getErrorLimitCount() == null ? 0 : job.getErrorLimitCount());
        errorLimit.set("percentage", job.getErrorLimitPercent() == null ? 0.0 : job.getErrorLimitPercent());
        JSONObject setting = new JSONObject();
        setting.set("speed", speed);
        setting.set("errorLimit", errorLimit);

        JSONObject jobObj = new JSONObject();
        jobObj.set("content", new JSONArray().set(content));
        jobObj.set("setting", setting);

        JSONObject root = new JSONObject();
        root.set("job", jobObj);
        if (byteLimit != null && byteLimit > 0) {
            int ch = channel == null ? 3 : Math.max(1, channel);
            long per = Math.max(1L, byteLimit / ch);
            JSONObject chSpeed = new JSONObject();
            chSpeed.set("speed", new JSONObject().set("byte", per));
            root.set("core", new JSONObject().set("transport",
                new JSONObject().set("channel", chSpeed)));
        }
        return root.toStringPretty();
    }

    /**
     * Excel/API 源 DataX job JSON: 源桥接 CSV → 行级规则预清洗(CsvRuleProcessor) → txtfilereader → DB writer。
     * 目标列 = dstMapping 的 dstField(无映射时 CSV 列); 目标表不存在时按列自动建表(VARCHAR(255))。
     * SQL 清洗/自定义 SQL 仅数据库表源支持, CSV 源由 CsvRuleProcessor 记录日志跳过。
     */
    private String buildJobJsonCsvSrc(EtlJob job) {
        DbConn dst = dbConnService.getById(job.getDstConnId());
        if (dst == null) {
            throw new RuntimeException("目标数据源不存在: dstConnId=" + job.getDstConnId());
        }
        try {
            String dstSchema = StrUtil.blankToDefault(job.getDstSchema(), dst.getDatabaseName());
            EtlFileBridge.BridgeResult br = EtlFileBridge.srcToCsv(job);
            // 目标列: dstMapping 的 dstField; 无映射 → CSV 列
            List<String> dstColList = dstFieldList(job.getDstMapping());
            if (dstColList.isEmpty()) {
                dstColList = new java.util.ArrayList<>(br.columns);
            }
            // 目标表不存在时按列自动建表
            ensureTableByCols(dst, dstSchema, job.getDstTable(), dstColList.toArray(new String[0]));
            // 规则预清洗(含 dstMapping 列重排)
            String csv = CsvRuleProcessor.process(job, br.csvPath, br.columns);
            List<String> outHeader = EtlFileBridge.readCsvHeader(csv);
            String[] writerCols = outHeader.isEmpty()
                ? dstColList.toArray(new String[0]) : outHeader.toArray(new String[0]);
            // reader: txtfilereader (path 会被当正则处理, 必须用正斜杠避免 \U 等非法转义)
            JSONObject readerParam = new JSONObject();
            readerParam.set("path", new String[]{csv.replace('\\', '/')});
            readerParam.set("encoding", "UTF-8");
            readerParam.set("fieldDelimiter", ",");
            readerParam.set("skipHeader", true);
            JSONArray column = new JSONArray();
            for (int i = 0; i < outHeader.size(); i++) {
                JSONObject col = new JSONObject();
                col.set("index", i);
                col.set("type", "string");
                col.set("name", outHeader.get(i));
                column.add(col);
            }
            readerParam.set("column", column);
            JSONObject reader = new JSONObject();
            reader.set("name", "txtfilereader");
            reader.set("parameter", readerParam);
            // writer: 同 buildJobJsonSeg
            JSONObject writerParam = new JSONObject();
            writerParam.set("username", dst.getUsername());
            writerParam.set("password", DbConnCrypto.decrypt(dst.getPassword()));
            writerParam.set("column", writerCols);
            writerParam.set("writeMode", normalizeWriteMode(job.getDstWriteMode()));
            if ("TRUNCATE".equalsIgnoreCase(job.getDstWriteMode())) {
                JSONArray preSql = new JSONArray().set("truncate table " + quoteName(dstSchema, job.getDstTable(), dst.getDbType()));
                writerParam.set("preSql", preSql);
            }
            JSONObject dstConn = new JSONObject();
            dstConn.set("table", new JSONArray().set(job.getDstTable()));
            dstConn.set("jdbcUrl", buildJdbcUrl(dst, dstSchema));
            writerParam.set("connection", new JSONArray().set(dstConn));
            JSONObject writer = new JSONObject();
            writer.set("name", writerName(dst.getDbType()));
            writer.set("parameter", writerParam);
            JSONObject content = new JSONObject();
            content.set("reader", reader);
            content.set("writer", writer);
            JSONObject speed = new JSONObject();
            int channel = job.getSpeedChannel() == null ? 3 : job.getSpeedChannel();
            speed.set("channel", channel);
            Long byteLimit = job.getSpeedByte() == null ? 0L : job.getSpeedByte();
            if (byteLimit != null && byteLimit > 0) {
                speed.set("byte", Math.max(1L, byteLimit / Math.max(1, channel)));
            }
            JSONObject errorLimit = new JSONObject();
            errorLimit.set("record", job.getErrorLimitCount() == null ? 0 : job.getErrorLimitCount());
            errorLimit.set("percentage", job.getErrorLimitPercent() == null ? 0.0 : job.getErrorLimitPercent());
            JSONObject setting = new JSONObject();
            setting.set("speed", speed);
            setting.set("errorLimit", errorLimit);
            JSONObject jobObj = new JSONObject();
            jobObj.set("content", new JSONArray().set(content));
            jobObj.set("setting", setting);
            JSONObject root = new JSONObject();
            root.set("job", jobObj);
            if (byteLimit != null && byteLimit > 0) {
                long per = Math.max(1L, byteLimit / Math.max(1, channel));
                JSONObject chSpeed = new JSONObject();
                chSpeed.set("speed", new JSONObject().set("byte", per));
                root.set("core", new JSONObject().set("transport",
                    new JSONObject().set("channel", chSpeed)));
            }
            return root.toStringPretty();
        } catch (Exception e) {
            throw new RuntimeException("构建 " + job.getSrcType() + " 源 DataX 任务失败: " + e.getMessage(), e);
        }
    }

    /** 目标映射平铺/分组 → dstField 列表 */
    private List<String> dstFieldList(String mappingJson) {
        List<String> out = new java.util.ArrayList<>();
        if (StrUtil.isBlank(mappingJson)) {
            return out;
        }
        try {
            JSONArray arr = JSONUtil.parseArray(mappingJson);
            if (arr.size() == 0) {
                return out;
            }
            boolean grouped = arr.getJSONObject(0).containsKey("maps");
            for (int i = 0; i < arr.size(); i++) {
                JSONObject m = arr.getJSONObject(i);
                if (grouped) {
                    JSONArray maps = m.getJSONArray("maps");
                    if (maps == null) {
                        continue;
                    }
                    for (int j = 0; j < maps.size(); j++) {
                        String c = maps.getJSONObject(j).getStr("dstField", "");
                        if (StrUtil.isNotBlank(c) && !out.contains(c)) {
                            out.add(c);
                        }
                    }
                } else {
                    String c = StrUtil.blankToDefault(m.getStr("dst"), m.getStr("dstField", ""));
                    if (StrUtil.isNotBlank(c) && !out.contains(c)) {
                        out.add(c);
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return out;
    }

    /** 目标表不存在时按列建表(VARCHAR(255)); 列含 * 时用 id 兜底 */
    private void ensureTableByCols(DbConn dst, String dstSchema, String dstTable, String[] cols) {
        if (dst == null || StrUtil.isBlank(dstTable) || !isSafeName(dstTable)) {
            return;
        }
        try {
            if (tableExists(dst, dstSchema, dstTable)) {
                return;
            }
            StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
            sb.append(quoteName(dstSchema, dstTable, dst.getDbType())).append(" (");
            if (cols == null || cols.length == 0 || (cols.length == 1 && "*".equals(cols[0]))) {
                sb.append("id BIGINT AUTO_INCREMENT PRIMARY KEY");
            } else {
                boolean first = true;
                for (String col : cols) {
                    if (!isSafeName(col)) {
                        continue;
                    }
                    if (!first) {
                        sb.append(", ");
                    }
                    sb.append(quoteCol(col, dst.getDbType())).append(" VARCHAR(255)");
                    first = false;
                }
            }
            sb.append(")");
            try (java.sql.Connection c = connectionManager.getConnection(dst);
                 java.sql.Statement st = c.createStatement()) {
                st.execute(sb.toString());
            }
        } catch (Exception e) {
            log.warn("按列自动建表失败(可手工建表后重试): {} - {}", dstTable, e.getMessage());
        }
    }

    /** 列名引用(按库类型) */
    private String quoteCol(String col, String dbType) {
        String q = "MYSQL".equalsIgnoreCase(dbType) ? "`" : "\"";
        return q + col + q;
    }

    /**
     * 单表任务(无 srcList): 注入清洗规则; 返回构建后的 job JSON
     */
    public String buildJobJsonWithRules(EtlJob job) {
        // Excel/API 源: 桥接 CSV + 行级规则预清洗 + txtfilereader 读取(DataX 无 Excel/API reader)
        if (EtlFileBridge.isBridgeSrc(job)) {
            return buildJobJsonCsvSrc(job);
        }
        DbConn src = dbConnService.getById(job.getSrcConnId());
        DbConn dst = dbConnService.getById(job.getDstConnId());
        if (src == null || dst == null) {
            throw new RuntimeException("数据源不存在: srcConnId=" + job.getSrcConnId() + ", dstConnId=" + job.getDstConnId());
        }
        String srcSchema = StrUtil.blankToDefault(job.getSrcSchema(), src.getDatabaseName());
        String dstSchema = StrUtil.blankToDefault(job.getDstSchema(), dst.getDatabaseName());
        String[] srcCols = resolveColumnsFor(job.getFieldMapping(), true, src, srcSchema, job.getSrcTable());
        String[] dstCols = resolveColumnsFor(job.getFieldMapping(), false, dst, dstSchema, job.getDstTable());
        java.util.List<cn.hutool.json.JSONObject> cleanRules = rulesForTable(job.getCleanRules(), job.getSrcTable());
        RuleSql rs = compileRules(cleanRules, srcCols, src, srcSchema, job.getSrcTable());
        // 目标映射默认值新增列(srcField 空 + dstDefault 非空): SELECT 追加 UUID()/NOW()/固定值
        rs.extraSel.addAll(defaultValueSelects(job.getFieldMapping(), src));
        if (rs.needSqlMode() || !rs.extraSel.isEmpty()) {
            return buildJobJsonSegSql(job, src, srcSchema, job.getSrcTable(), job.getSrcWhere(), srcCols, rs,
                dst, dstSchema, job.getDstTable(), dstCols, job.getDstWriteMode(),
                job.getSpeedChannel() == null ? 3 : job.getSpeedChannel(),
                job.getSpeedByte() == null ? 0L : job.getSpeedByte());
        }
        String effWhere = rs.whereExtra().isEmpty() ? job.getSrcWhere() : mergeWhere(job.getSrcWhere(), rs.whereExtra());
        return buildJobJsonSeg(job, src, srcSchema, job.getSrcTable(), effWhere, srcCols,
            dst, dstSchema, job.getDstTable(), dstCols, job.getDstWriteMode(),
            job.getSpeedChannel() == null ? 3 : job.getSpeedChannel(),
            job.getSpeedByte() == null ? 0L : job.getSpeedByte(), true);
    }


    /** 解析 fieldMapping 中的默认值新增列(dstDefault), 返回 SELECT 片段列表 */
    private java.util.List<String> defaultValueSelects(String mappingJson, DbConn src) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (StrUtil.isBlank(mappingJson)) {
            return out;
        }
        try {
            cn.hutool.json.JSONArray arr = cn.hutool.json.JSONUtil.parseArray(mappingJson);
            if (arr.size() == 0 || !arr.getJSONObject(0).containsKey("maps")) {
                return out;
            }
            String q = normalizeType(src.getDbType()).equals("MYSQL") ? "`" : "\"";
            for (int i = 0; i < arr.size(); i++) {
                cn.hutool.json.JSONArray maps = arr.getJSONObject(i).getJSONArray("maps");
                if (maps == null) {
                    continue;
                }
                for (int j = 0; j < maps.size(); j++) {
                    cn.hutool.json.JSONObject mm = maps.getJSONObject(j);
                    String sf = StrUtil.blankToDefault(mm.getStr("srcField"), "");
                    String df = StrUtil.blankToDefault(mm.getStr("dstField"), "");
                    String dv = StrUtil.blankToDefault(mm.getStr("dstDefault"), "");
                    if (StrUtil.isBlank(sf) && StrUtil.isNotBlank(df) && StrUtil.isNotBlank(dv)) {
                        String expr;
                        if ("UUID".equalsIgnoreCase(dv.trim()) || "__UUID__".equalsIgnoreCase(dv.trim())) {
                            expr = "UUID()";
                        } else if ("NOW".equalsIgnoreCase(dv.trim()) || "__NOW__".equalsIgnoreCase(dv.trim())) {
                            expr = "NOW()";
                        } else {
                            expr = "'" + dv.replace("'", "''") + "'";
                        }
                        out.add(expr + " AS " + q + df.replace("`", "").replace("\"", "") + q);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 单表段(注入清洗规则): 需 SQL 模式(去重/唯一/列表达式)用 querySql, 否则 table+where 保持 splitPk */
    private String buildSegWithClean(EtlJob job, DbConn src, String srcSchema, String srcTable, String srcWhere,
                                     String[] srcCols, DbConn dst, String dstSchema, String dstTable, String[] dstCols,
                                     String mode, Integer channel, Long byteLimit) {
        java.util.List<cn.hutool.json.JSONObject> rules = rulesForTable(job.getCleanRules(), srcTable);
        RuleSql rs = compileRules(rules, srcCols, src, srcSchema, srcTable);
        if (rs.needSqlMode()) {
            return buildJobJsonSegSql(job, src, srcSchema, srcTable, srcWhere, srcCols, rs,
                dst, dstSchema, dstTable, dstCols, mode, channel, byteLimit);
        }
        String effWhere = rs.whereExtra().isEmpty() ? srcWhere : mergeWhere(srcWhere, rs.whereExtra());
        return buildJobJsonSeg(job, src, srcSchema, srcTable, effWhere, srcCols,
            dst, dstSchema, dstTable, dstCols, mode, channel, byteLimit, true);
    }

    /** 单表段(注入质量规则, 作用于清洗结果表→目标表) */
    private String buildSegWithQuality(EtlJob job, DbConn src, String srcSchema, String srcTable, String srcWhere,
                                       String[] srcCols, DbConn dst, String dstSchema, String dstTable, String[] dstCols,
                                       String mode, Integer channel, Long byteLimit) {
        java.util.List<cn.hutool.json.JSONObject> rules = parseRuleList(job.getQualityRules());
        RuleSql rs = compileRules(rules, srcCols, src, srcSchema, srcTable);
        if (rs.needSqlMode()) {
            return buildJobJsonSegSql(job, src, srcSchema, srcTable, srcWhere, srcCols, rs,
                dst, dstSchema, dstTable, dstCols, mode, channel, byteLimit);
        }
        String effWhere = rs.whereExtra().isEmpty() ? srcWhere : mergeWhere(srcWhere, rs.whereExtra());
        return buildJobJsonSeg(job, src, srcSchema, srcTable, effWhere, srcCols,
            dst, dstSchema, dstTable, dstCols, mode, channel, byteLimit, true);
    }


    /**
     * JOIN 模式分片并行执行: 按主表主键 [min,max] 均分 channel 片, 每片独立 SUBTASK 日志并行执行;
     * TRUNCATE/REPLACE 语义先预清空目标表一次(各片统一 INSERT); 聚合 total 到 jobLog。
     * 无主键/空表/范围查询失败时回退单次执行。
     */
    private EtlJobLog runJoinSharded(EtlJob job, EtlJobLog jobLog) {
        DbConn src = dbConnService.getById(job.getSrcConnId());
        DbConn dst = dbConnService.getById(job.getDstConnId());
        if (src == null || dst == null) {
            throw new RuntimeException("数据源不存在: src=" + job.getSrcConnId() + ", dst=" + job.getDstConnId());
        }
        String srcSchema = StrUtil.blankToDefault(job.getSrcSchema(), src.getDatabaseName());
        String dstSchema = StrUtil.blankToDefault(job.getDstSchema(), dst.getDatabaseName());
        cn.hutool.json.JSONArray srcArr = JSONUtil.parseArray(job.getSrcList());
        String mainTbl = srcArr.getJSONObject(0).getStr("srcTable", "");
        String desc = "多源JOIN: 源组合→目标(" + job.getDstTable() + ")";
        int channel = job.getSpeedChannel() == null ? 3 : Math.max(1, job.getSpeedChannel());

        String pk = findPk(src, srcSchema, mainTbl);
        if (StrUtil.isBlank(pk)) {
            // 无主键: 回退单次执行(单通道)
            EtlJobLog jl = executeJobJson(job, buildJobJsonJoin(job), jobLog, desc);
            if (jl != null && "SUCCESS".equals(jl.getRunStatus())) {
                updateIncrLastValue(job);
            }
            return jl;
        }
        // 主表 min/max(裸列名条件: 主表 srcWhere + 增量条件去 t0. 前缀)
        String w0 = srcArr.getJSONObject(0).getStr("srcWhere", "");
        String wjRaw = stripAlias(job.getSrcWhere());
        String whereRaw = StrUtil.isBlank(w0) ? wjRaw
            : (StrUtil.isBlank(wjRaw) ? w0 : "(" + w0 + ") AND (" + wjRaw + ")");
        long[] range = joinPkRange(src, srcSchema, mainTbl, pk, whereRaw);
        if (range == null) {
            EtlJobLog jl = executeJobJson(job, buildJobJsonJoin(job), jobLog, desc);
            if (jl != null && "SUCCESS".equals(jl.getRunStatus())) {
                updateIncrLastValue(job);
            }
            return jl;
        }
        long min = range[0], max = range[1];
        int n = (max == min) ? 1 : channel;
        String q = "MYSQL".equalsIgnoreCase(src.getDbType()) ? "`" : "\"";
        // TRUNCATE/REPLACE 语义: 先清空目标表一次, 各片统一 INSERT
        String mode = StrUtil.blankToDefault(job.getDstWriteMode(), "INSERT");
        boolean needTruncate = "TRUNCATE".equalsIgnoreCase(mode) || "REPLACE".equalsIgnoreCase(mode);
        if (needTruncate) {
            truncateTable(dst, dstSchema, job.getDstTable());
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(4, n));
        java.util.List<java.util.concurrent.Future<EtlJobLog>> futures = new java.util.ArrayList<>();
        for (int k = 0; k < n; k++) {
            final long lo = min + (max - min) * k / n;
            // 半开区间: 除最后一片外, hi = 下一片 lo - 1, 避免 BETWEEN 闭区间边界行被相邻片重复读取
            final long hi = (k == n - 1) ? max : (min + (max - min) * (k + 1) / n - 1);
            final int idx = k;
            final String extra = "t0." + q + pk + q + " >= " + lo + " AND t0." + q + pk + q + " <= " + hi;
            futures.add(pool.submit(() -> {
                EtlJobLog seg = new EtlJobLog();
                seg.setJobId(job.getId());
                seg.setNodeType("SUBTASK");
                seg.setNodeId(String.valueOf(job.getId()));
                seg.setNodeName("JOIN分片[" + (idx + 1) + "/" + n + "]");
                seg.setRunStatus("RUNNING");
                seg.setStartTime(new Date());
                seg.setCreateTime(new Date());
                jobLogMapper.insert(seg);
                return executeJobJson(job, buildJobJsonJoin(job, extra), seg,
                    "JOIN分片[" + (idx + 1) + "/" + n + "]→目标(" + job.getDstTable() + ")");
            }));
        }
        pool.shutdown();
        long totalSum = 0L;
        EtlJobLog failed = null;
        try {
            for (java.util.concurrent.Future<EtlJobLog> f : futures) {
                EtlJobLog r = f.get();
                if (r != null) {
                    totalSum += (r.getTotalRecords() == null ? 0L : r.getTotalRecords());
                    if (!"SUCCESS".equals(r.getRunStatus())) {
                        failed = r;
                    }
                }
            }
        } catch (Exception e) {
            failed = new EtlJobLog();
            failed.setRunStatus("FAILED");
            failed.setErrorMsg("JOIN 分片执行异常: " + e.getMessage());
        }
        if (failed != null) {
            jobLog.setRunStatus("FAILED");
            jobLog.setErrorMsg(failed.getErrorMsg());
            jobLog.setEndTime(new Date());
            jobLogMapper.updateById(jobLog);
            return jobLog;
        }
        jobLog.setRunStatus("SUCCESS");
        jobLog.setTotalRecords(totalSum);
        jobLog.setEndTime(new Date());
        jobLogMapper.updateById(jobLog);
        updateIncrLastValue(job);
        return jobLog;
    }

    /**
     * 执行 DataX 任务(阻塞, 实时日志入库)。
     * 双段模式: 配置了清洗结果表(cleanConnId+cleanTable)时, 先执行 源→清洗结果表(段1),
     * 再执行 清洗结果表→目标表(段2, 段2映射 dstMapping); 否则单段 源→目标。
     */
    public EtlJobLog run(EtlJob job, EtlJobLog jobLog) {
        try {
            boolean hasClean = job.getCleanConnId() != null && StrUtil.isNotBlank(job.getCleanTable());
            // Excel/API 源: 清洗/质量规则在 CSV 预清洗一次完成, 直达目标(不建清洗结果表, 目标表按 CSV 列自动建)
            if (EtlFileBridge.isBridgeSrc(job)) {
                EtlJobLog r = executeJobJson(job, buildJobJsonCsvSrc(job), jobLog,
                    "单段: " + job.getSrcType() + "源→目标(" + job.getDstTable() + ")");
                if (r != null && "SUCCESS".equals(r.getRunStatus())) {
                    updateIncrLastValue(job);
                }
                return r;
            }
            // 自动建表: 目标表/清洗结果表不存在时按源表结构(同库 LIKE 克隆)或映射列(VARCHAR(255))创建, 与 Kettle 管线 ensureDstTable 行为对齐
            ensureTables(job, hasClean);
            if (hasClean) {
                DbConn src = dbConnService.getById(job.getSrcConnId());
                DbConn clean = dbConnService.getById(job.getCleanConnId());
                DbConn dst = dbConnService.getById(job.getDstConnId());
                if (src == null || clean == null || dst == null) {
                    throw new RuntimeException("数据源不存在: src=" + job.getSrcConnId()
                            + ", clean=" + job.getCleanConnId() + ", dst=" + job.getDstConnId());
                }
                String srcSchema = StrUtil.blankToDefault(job.getSrcSchema(), src.getDatabaseName());
                String cleanSchema = StrUtil.blankToDefault(job.getCleanSchema(), clean.getDatabaseName());
                String dstSchema = StrUtil.blankToDefault(job.getDstSchema(), dst.getDatabaseName());
                // 段1: 源 → 清洗结果表
                String[] seg1SrcCols = resolveColumnsFor(job.getFieldMapping(), true, src, srcSchema, job.getSrcTable());
                String[] seg1DstCols = resolveColumnsFor(job.getFieldMapping(), false, clean, cleanSchema, job.getCleanTable());
                String seg1 = buildSegWithClean(job, src, srcSchema, job.getSrcTable(), job.getSrcWhere(), seg1SrcCols,
                        clean, cleanSchema, job.getCleanTable(), seg1DstCols,
                        StrUtil.blankToDefault(job.getCleanWriteMode(), "INSERT"),
                        job.getSpeedChannel() == null ? 3 : job.getSpeedChannel(),
                        job.getSpeedByte() == null ? 0L : job.getSpeedByte());
                EtlJobLog r1 = executeJobJson(job, seg1, jobLog, "段1: 源→清洗结果表(" + job.getCleanTable() + ")");
                if (r1 == null || !"SUCCESS".equals(r1.getRunStatus())) {
                    return r1;
                }
                updateIncrLastValue(job);
                // 段2: 清洗结果表 → 目标表
                String[] seg2SrcCols = resolveColumnsFor(job.getDstMapping(), true, clean, cleanSchema, job.getCleanTable());
                String[] seg2DstCols = resolveColumnsFor(job.getDstMapping(), false, dst, dstSchema, job.getDstTable());
                return executeJobJson(job, buildSegWithQuality(job, clean, cleanSchema, job.getCleanTable(), null, seg2SrcCols,
                        dst, dstSchema, job.getDstTable(), seg2DstCols, job.getDstWriteMode(),
                        job.getSpeedChannel() == null ? 3 : job.getSpeedChannel(),
                        job.getSpeedByte() == null ? 0L : job.getSpeedByte()), jobLog, "段2: 清洗结果表→目标表(" + job.getDstTable() + ")");
            }
            // 多源: JOIN 组合(同库单条 querySql) / UNION 同构合并(逐表执行)
            if (StrUtil.isNotBlank(job.getSrcList())) {
                try {
                    cn.hutool.json.JSONArray srcArr = JSONUtil.parseArray(job.getSrcList());
                    if (isJoinMode(job)) {
                        return runJoinSharded(job, jobLog);
                    }
                    if (srcArr.size() > 1) {
                        DbConn src = dbConnService.getById(job.getSrcConnId());
                        DbConn dst = dbConnService.getById(job.getDstConnId());
                        if (src == null || dst == null) {
                            throw new RuntimeException("数据源不存在: src=" + job.getSrcConnId() + ", dst=" + job.getDstConnId());
                        }
                        String srcSchema = StrUtil.blankToDefault(job.getSrcSchema(), src.getDatabaseName());
                        String dstSchema = StrUtil.blankToDefault(job.getDstSchema(), dst.getDatabaseName());
                        String[] dstCols = resolveColumnsFor(job.getFieldMapping(), false, dst, dstSchema, job.getDstTable());
                        EtlJobLog last = null;
                        long totalSum = 0L;
                        java.util.List<String> segTbls = new java.util.ArrayList<>();
                        java.util.List<String> segWheres = new java.util.ArrayList<>();
                        // 首表串行执行(保持 TRUNCATE/REPLACE 清空语义), 其余源表并行执行(INSERT 追加)
                        for (int i = 0; i < srcArr.size(); i++) {
                            cn.hutool.json.JSONObject row = srcArr.getJSONObject(i);
                            String tbl = row.getStr("srcTable", "");
                            if (StrUtil.isBlank(tbl)) {
                                continue;
                            }
                            String tblWhere = row.getStr("srcWhere", "");
                            String effWhere = StrUtil.isBlank(tblWhere) ? job.getSrcWhere()
                                : (StrUtil.isBlank(job.getSrcWhere()) ? tblWhere : "(" + tblWhere + ") AND (" + job.getSrcWhere() + ")");
                            String mode = i == 0 ? StrUtil.blankToDefault(job.getDstWriteMode(), "INSERT") : "INSERT";
                            String[] srcCols = resolveColumnsFor(job.getFieldMapping(), true, src, srcSchema, tbl);
                            if (i == 0) {
                                last = executeJobJson(job, buildSegWithClean(job, src, srcSchema, tbl, effWhere, srcCols,
                                        dst, dstSchema, job.getDstTable(), dstCols, mode,
                                        job.getSpeedChannel() == null ? 3 : job.getSpeedChannel(),
                                        job.getSpeedByte() == null ? 0L : job.getSpeedByte()),
                                    jobLog, "源表[" + tbl + "]→目标(" + job.getDstTable() + ")");
                                if (last == null || !"SUCCESS".equals(last.getRunStatus())) {
                                    return last;
                                }
                                totalSum += (last.getTotalRecords() == null ? 0L : last.getTotalRecords());
                            } else {
                                segTbls.add(tbl);
                                segWheres.add(effWhere);
                            }
                        }
                        if (segTbls.size() > 0) {
                            // 其余源表并行: 各段独立 SUBTASK 日志, 全部成功后聚合行数
                            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(4, segTbls.size()));
                            java.util.List<java.util.concurrent.Future<EtlJobLog>> futures = new java.util.ArrayList<>();
                            for (int k = 0; k < segTbls.size(); k++) {
                                final String tbl = segTbls.get(k);
                                final String effWhere = segWheres.get(k);
                                futures.add(pool.submit(() -> {
                                    EtlJobLog segLog = new EtlJobLog();
                                    segLog.setJobId(job.getId());
                                    segLog.setNodeType("SUBTASK");
                                    segLog.setNodeId(String.valueOf(job.getId()));
                                    segLog.setNodeName("并行段[" + tbl + "]");
                                    segLog.setRunStatus("RUNNING");
                                    segLog.setStartTime(new Date());
                                    segLog.setCreateTime(new Date());
                                    jobLogMapper.insert(segLog);
                                    String[] srcCols2 = resolveColumnsFor(job.getFieldMapping(), true, src, srcSchema, tbl);
                                    return executeJobJson(job, buildSegWithClean(job, src, srcSchema, tbl, effWhere, srcCols2,
                                            dst, dstSchema, job.getDstTable(), dstCols, "INSERT",
                                            job.getSpeedChannel() == null ? 3 : job.getSpeedChannel(),
                                            job.getSpeedByte() == null ? 0L : job.getSpeedByte()),
                                        segLog, "源表[" + tbl + "]→目标(" + job.getDstTable() + ")");
                                }));
                            }
                            pool.shutdown();
                            EtlJobLog failed = null;
                            for (java.util.concurrent.Future<EtlJobLog> f : futures) {
                                try {
                                    EtlJobLog r = f.get();
                                    if (r != null && !"SUCCESS".equals(r.getRunStatus())) {
                                        failed = r;
                                    }
                                    if (r != null) {
                                        totalSum += (r.getTotalRecords() == null ? 0L : r.getTotalRecords());
                                    }
                                } catch (Exception ex) {
                                    if (failed == null) {
                                        failed = last;
                                    }
                                    if (failed != null) {
                                        failed.setErrorMsg("并行段执行异常: " + ex.getMessage());
                                    }
                                }
                            }
                            if (failed != null) {
                                last.setRunStatus("FAILED");
                                last.setErrorMsg("多源并行执行失败: " + (failed.getErrorMsg() == null ? "" : failed.getErrorMsg()));
                                last.setEndTime(new Date());
                                jobLogMapper.updateById(last);
                                return last;
                            }
                        }
                        last.setTotalRecords(totalSum);
                        jobLogMapper.updateById(last);
                        updateIncrLastValue(job);
                        return last;
                    }
                } catch (Exception e) {
                    jobLog.setRunStatus("FAILED");
                    jobLog.setErrorMsg("多源执行失败: " + e.getMessage());
                    jobLog.setEndTime(new Date());
                    jobLogMapper.updateById(jobLog);
                    return jobLog;
                }
            }
            EtlJobLog r = executeJobJson(job, buildJobJson(job), jobLog, "单段: 源→目标(" + job.getDstTable() + ")");
            if (r != null && "SUCCESS".equals(r.getRunStatus())) {
                updateIncrLastValue(job);
            }
            return r;
        } catch (Exception e) {
            jobLog.setRunStatus("FAILED");
            jobLog.setErrorMsg(e.getMessage());
            jobLog.setEndTime(new Date());
            jobLogMapper.updateById(jobLog);
            return jobLog;
        }
    }

    /** 执行单个 DataX job.json(阻塞, 实时日志入库) */
    private EtlJobLog executeJobJson(EtlJob job, String jobJson, EtlJobLog jobLog, String segLabel) {
        File jsonFile = null;
        Process process = null;
        try {
            String jobTempDir = etlProperties.getJobTempDir();
            Path dir = Paths.get(jobTempDir);
            Files.createDirectories(dir);
            jsonFile = Files.createTempFile(dir, "datax_job_", ".json").toFile();
            Files.writeString(jsonFile.toPath(), jobJson, StandardCharsets.UTF_8);

            String dataxHome = etlProperties.getDataxHome();
            File libDir = new File(dataxHome, "lib");
            if (!libDir.exists()) {
                throw new RuntimeException("DataX 引擎未就绪: " + dataxHome + " 不存在, 请先部署 DataX");
            }
            String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
            List<String> cmd = List.of(
                javaBin,
                "-Ddatax.home=" + dataxHome,
                "-cp", new File(libDir, "*").getAbsolutePath(),
                "com.alibaba.datax.core.Engine",
                "-mode", "standalone",
                "-jobid", "-1",
                "-job", jsonFile.getAbsolutePath()
            );
            log.info("执行 DataX {}: {}", segLabel, String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(new File(dataxHome));
            process = pb.start();
            if (job != null && job.getId() != null) {
                RUNNING.put(runKey(job, jobLog), process);
            }

            StringBuilder sb = new StringBuilder(8192);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (sb.length() > 400_000) {
                        sb.delete(0, sb.length() - 200_000);
                    }
                    if (sb.length() % 4000 < 200) {
                        jobLog.setLogText(sb.toString());
                        jobLogMapper.updateById(jobLog);
                    }
                }
            }
            int exit = process.waitFor();
            jobLog.setLogText(sb.toString());
            jobLog.setEndTime(new Date());
            if (exit == 0) {
                jobLog.setRunStatus("SUCCESS");
                parseStat(jobLog, sb.toString());
            } else {
                jobLog.setRunStatus("FAILED");
                String err = lastErrorLines(sb.toString());
                jobLog.setErrorMsg(segLabel + " 执行失败:\n" + err);
                jobLog.setTotalRecords(0L);
            }
            jobLogMapper.updateById(jobLog);
            return jobLog;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            jobLog.setRunStatus("FAILED");
            jobLog.setErrorMsg(segLabel + " 引擎调用异常: " + e.getMessage());
            jobLog.setEndTime(new Date());
            jobLogMapper.updateById(jobLog);
            return jobLog;
        } catch (Exception e) {
            jobLog.setRunStatus("FAILED");
            jobLog.setErrorMsg(segLabel + " 执行异常: " + e.getMessage());
            jobLog.setEndTime(new Date());
            jobLogMapper.updateById(jobLog);
            return jobLog;
        } finally {
            if (job != null && job.getId() != null) {
                RUNNING.remove(runKey(job, jobLog));
            }
            if (process != null) {
                process.destroy();
            }
            if (jsonFile != null && jsonFile.exists()) {
                jsonFile.delete();
            }
        }
    }

    /**
     * 增量同步: 执行成功后回写源表增量字段最大值到 job.incrLastValue
     */
    private void updateIncrLastValue(EtlJob job) {
        try {
            if (job == null || job.getId() == null || StrUtil.isBlank(job.getIncrField())) {
                return;
            }
            DbConn src = dbConnService.getById(job.getSrcConnId());
            if (src == null) {
                return;
            }
            String field = job.getIncrField();
            java.util.List<String> tables = new java.util.ArrayList<>();
            java.util.List<DbConn> conns = new java.util.ArrayList<>();
            java.util.List<String> schemas = new java.util.ArrayList<>();
            boolean joinMode = isJoinMode(job);
            // 多源: UNION 遍历 srcList 全部源表(各自连接/库)取最大值; JOIN 只取主表(从表 ID 不应推高游标)
            if (StrUtil.isNotBlank(job.getSrcList())) {
                try {
                    cn.hutool.json.JSONArray arr = JSONUtil.parseArray(job.getSrcList());
                    for (int k = 0; k < arr.size(); k++) {
                        JSONObject row = arr.getJSONObject(k);
                        String tbl = row.getStr("srcTable", "");
                        if (StrUtil.isBlank(tbl)) {
                            continue;
                        }
                        DbConn conn = src;
                        String sch = StrUtil.blankToDefault(job.getSrcSchema(), src.getDatabaseName());
                        if (row.getLong("srcConnId") != null) {
                            DbConn c2 = dbConnService.getById(row.getLong("srcConnId"));
                            if (c2 != null) {
                                conn = c2;
                                sch = StrUtil.blankToDefault(row.getStr("srcSchema"), c2.getDatabaseName());
                            }
                        }
                        tables.add(tbl);
                        conns.add(conn);
                        schemas.add(sch);
                    }
                } catch (Exception ignored) {
                }
            }
            if (joinMode && tables.isEmpty()) {
                // JOIN 模式仅主表: 无 srcList 时回退任务级主表
                String mainTbl = srcArrFirstTable(job);
                if (StrUtil.isNotBlank(mainTbl)) {
                    tables.add(mainTbl);
                    conns.add(src);
                    schemas.add(StrUtil.blankToDefault(job.getSrcSchema(), src.getDatabaseName()));
                }
            }
            if (tables.isEmpty() && StrUtil.isNotBlank(job.getSrcTable())) {
                tables.add(job.getSrcTable());
                conns.add(src);
                schemas.add(StrUtil.blankToDefault(job.getSrcSchema(), src.getDatabaseName()));
            }
            if (joinMode && tables.size() > 1) {
                // JOIN 模式只保留主表
                tables = new java.util.ArrayList<>(tables.subList(0, 1));
                conns = new java.util.ArrayList<>(conns.subList(0, 1));
                schemas = new java.util.ArrayList<>(schemas.subList(0, 1));
            }
            if (tables.isEmpty()) {
                return;
            }
            Object best = null;
            for (int i = 0; i < tables.size(); i++) {
                String q = "SELECT MAX(" + field + ") FROM " + quoteName(schemas.get(i), tables.get(i), conns.get(i).getDbType());
                try (Connection conn = connectionManager.getConnection(conns.get(i));
                     Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(q)) {
                    if (rs.next() && rs.getObject(1) != null) {
                        Object v = rs.getObject(1);
                        if (best == null || compareInc(v, best) > 0) {
                            best = v;
                        }
                    }
                }
            }
            if (best != null) {
                String s = best instanceof java.sql.Timestamp
                    ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(best)
                    : String.valueOf(best);
                job.setIncrLastValue(s);
                // 只更新增量游标, 禁止整行写回(运行期 srcWhere 可能已注入增量条件, 不得落库)
                jobMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<EtlJob>()
                    .eq(EtlJob::getId, job.getId())
                    .set(EtlJob::getIncrLastValue, s));
            }
        } catch (Exception e) {
            log.warn("增量回写失败: {}", e.getMessage());
        }
    }

    private int compareInc(Object a, Object b) {
        try {
            if (a instanceof Number && b instanceof Number) {
                return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
            }
            return String.valueOf(a).compareTo(String.valueOf(b));
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== 私有工具 ====================

    /**
     * 通用字段映射列解析(兼容分组 [{srcTable,maps:[{srcField,dstField}]}] 与平铺 [{src,dst}]/[{srcField,dstField}]):
     * isSrc=true 取源侧列, false 取目标侧列; 空映射返回 ["*"]
     */
    private String[] resolveColumnsFor(String mappingJson, boolean isSrc, DbConn conn, String schema, String table) {
        if (StrUtil.isBlank(mappingJson)) {
            return new String[]{"*"};
        }
        try {
            JSONArray arr = JSONUtil.parseArray(mappingJson);
            if (arr.size() == 0) {
                return new String[]{"*"};
            }
            List<String> cols = new java.util.ArrayList<>();
            boolean grouped = arr.getJSONObject(0).containsKey("maps");
            for (int i = 0; i < arr.size(); i++) {
                JSONObject m = arr.getJSONObject(i);
                if (grouped) {
                    JSONArray maps = m.getJSONArray("maps");
                    if (maps == null) continue;
                    for (int j = 0; j < maps.size(); j++) {
                        JSONObject mm = maps.getJSONObject(j);
                        String c = isSrc ? mm.getStr("srcField") : mm.getStr("dstField");
                        if (StrUtil.isNotBlank(c) && !cols.contains(c)) {
                            cols.add(c);
                        }
                    }
                } else {
                    String c = isSrc ? StrUtil.blankToDefault(m.getStr("src"), m.getStr("srcField", ""))
                                     : StrUtil.blankToDefault(m.getStr("dst"), m.getStr("dstField", ""));
                    if (StrUtil.isNotBlank(c) && !cols.contains(c)) {
                        cols.add(c);
                    }
                }
            }
            return cols.isEmpty() ? new String[]{"*"} : cols.toArray(new String[0]);
        } catch (Exception e) {
            return new String[]{"*"};
        }
    }

    /**
     * 执行前确保目标表/清洗结果表存在(不存在则自动建表)
     */
    private void ensureTables(EtlJob job, boolean hasClean) {
        DbConn src = dbConnService.getById(job.getSrcConnId());
        DbConn dst = dbConnService.getById(job.getDstConnId());
        if (src == null || dst == null) {
            return;
        }
        String srcSchema = StrUtil.blankToDefault(job.getSrcSchema(), src.getDatabaseName());
        String dstSchema = StrUtil.blankToDefault(job.getDstSchema(), dst.getDatabaseName());
        String[] dstCols = resolveColumnsFor(job.getDstMapping(), false, dst, dstSchema, job.getDstTable());
        if (dstCols == null || dstCols.length == 0 || (dstCols.length == 1 && "*".equals(dstCols[0]))) {
            dstCols = resolveColumnsFor(job.getFieldMapping(), false, dst, dstSchema, job.getDstTable());
        }
        ensureTable(src, srcSchema, job.getSrcTable(), dst, dstSchema, job.getDstTable(), dstCols);
        if (hasClean) {
            DbConn clean = dbConnService.getById(job.getCleanConnId());
            String cleanSchema = StrUtil.blankToDefault(job.getCleanSchema(), clean == null ? null : clean.getDatabaseName());
            String[] cleanCols = resolveColumnsFor(job.getFieldMapping(), false, clean, cleanSchema, job.getCleanTable());
            if (cleanCols == null || cleanCols.length == 0 || (cleanCols.length == 1 && "*".equals(cleanCols[0]))) {
                cleanCols = resolveColumnsFor(job.getFieldMapping(), false, dst, dstSchema, job.getDstTable());
            }
            ensureTable(src, srcSchema, job.getSrcTable(), clean, cleanSchema, job.getCleanTable(), cleanCols);
        }
    }

    /**
     * 目标表不存在时自动建表: 同连接优先 CREATE TABLE LIKE 克隆源表结构(类型/索引/主键保真), 否则按列建 VARCHAR(255)
     */
    private void ensureTable(DbConn src, String srcSchema, String srcTable,
                             DbConn dst, String dstSchema, String dstTable, String[] cols) {
        if (dst == null || StrUtil.isBlank(dstTable) || !isSafeName(dstTable)) {
            return;
        }
        try {
            if (tableExists(dst, dstSchema, dstTable)) {
                return;
            }
            boolean sameConn = src != null && src.getId() != null && src.getId().equals(dst.getId())
                && StrUtil.isNotBlank(srcTable) && isSafeName(srcTable);
            String ddl;
            if (sameConn) {
                // 同库: 克隆源表结构
                ddl = "CREATE TABLE IF NOT EXISTS " + quoteName(dstSchema, dstTable, dst.getDbType())
                    + " LIKE " + quoteName(srcSchema, srcTable, src.getDbType());
            } else {
                // 跨库: 按列建 VARCHAR(255)
                StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
                sb.append(quoteName(dstSchema, dstTable, dst.getDbType())).append(" (");
                if (cols == null || cols.length == 0 || (cols.length == 1 && "*".equals(cols[0]))) {
                    sb.append("id BIGINT AUTO_INCREMENT PRIMARY KEY");
                } else {
                    boolean first = true;
                    for (String col : cols) {
                        if (!isSafeName(col)) {
                            continue;
                        }
                        if (!first) {
                            sb.append(", ");
                        }
                        sb.append('`').append(col).append("` VARCHAR(255)");
                        first = false;
                    }
                    if (first) {
                        sb.append("id BIGINT AUTO_INCREMENT PRIMARY KEY");
                    }
                }
                sb.append(")");
                ddl = sb.toString();
            }
            try (Connection conn = connectionManager.getConnection(dst);
                 Statement st = conn.createStatement()) {
                st.execute(ddl);
                log.info("自动建表成功: {}", ddl);
                // JOIN/多源场景: 映射目标列包含从表附加列(同库 LIKE 克隆结构没有) → 自动补列 VARCHAR(255)
                if (sameConn && cols != null && cols.length > 0
                    && !(cols.length == 1 && "*".equals(cols))) {
                    java.util.Set<String> existCols = new java.util.HashSet<>();
                    try (ResultSet rc = st.executeQuery(
                        "SELECT LOWER(COLUMN_NAME) FROM information_schema.columns WHERE table_schema='"
                        + srcSchema.replace("'", "''") + "' AND table_name='" + srcTable.replace("'", "''") + "'")) {
                        while (rc.next()) {
                            existCols.add(rc.getString(1));
                        }
                    } catch (Exception ignore) {
                    }
                    for (String col : cols) {
                        if (!isSafeName(col)) {
                            continue;
                        }
                        if (existCols.contains(col.toLowerCase())) {
                            continue;
                        }
                        st.execute("ALTER TABLE " + quoteName(dstSchema, dstTable, dst.getDbType())
                            + " ADD COLUMN `" + col + "` VARCHAR(255) NULL");
                        log.info("自动建表补列: {}", col);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("自动建表失败(任务将继续, 由引擎报错): {}", e.getMessage());
        }
    }

    /** 表是否存在 */
    private boolean tableExists(DbConn conn, String schema, String table) {
        try (Connection c = connectionManager.getConnection(conn);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='"
                 + schema.replace("'", "''") + "' AND table_name='" + table.replace("'", "''") + "'")) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 标识符安全校验(防注入) */
    private boolean isSafeName(String s) {
        return s != null && s.matches("[A-Za-z0-9_-]{1,128}");
    }

    private String findPk(DbConn conn, String schema, String table) {
        if (conn == null || StrUtil.isBlank(table) || !isSafeName(table)) {
            return null;
        }
        // 目前仅 MySQL 场景真实探测主键(information_schema); 其他库返回 null 回退单通道执行
        if (!"MYSQL".equalsIgnoreCase(conn.getDbType())) {
            return null;
        }
        String sch = StrUtil.blankToDefault(schema, conn.getDatabaseName());
        try (Connection jc = connectionManager.getConnection(conn);
             Statement st = jc.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
                 + "WHERE TABLE_SCHEMA='" + sch.replace("'", "''") + "' AND TABLE_NAME='" + table.replace("'", "''")
                 + "' AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION LIMIT 1")) {
            if (rs.next()) {
                String pk = rs.getString(1);
                if (StrUtil.isNotBlank(pk)) {
                    return pk;
                }
            }
        } catch (Exception e) {
            log.warn("探测主键失败: {}.{}: {}", sch, table, e.getMessage());
        }
        return null;
    }

    /**
     * JOIN 主表主键 min/max 范围(用于分片); whereRaw 为裸列名条件(主表单表查询, 无别名歧义)。
     * 空表/查询失败返回 null。
     */
    private long[] joinPkRange(DbConn conn, String schema, String table, String pk, String whereRaw) {
        if (conn == null || StrUtil.isBlank(table) || StrUtil.isBlank(pk) || !isSafeName(table) || !isSafeName(pk)) {
            return null;
        }
        String sch = StrUtil.blankToDefault(schema, conn.getDatabaseName());
        String q = "MYSQL".equalsIgnoreCase(conn.getDbType()) ? "`" : "\"";
        String sql = "SELECT MIN(" + q + pk + q + "), MAX(" + q + pk + q + ") FROM "
            + quoteName(sch, table, conn.getDbType());
        if (StrUtil.isNotBlank(whereRaw)) {
            sql += " WHERE " + whereRaw;
        }
        try (Connection jc = connectionManager.getConnection(conn);
             Statement st = jc.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                Object mn = rs.getObject(1);
                Object mx = rs.getObject(2);
                if (mn == null || mx == null) {
                    return null; // 空表或无数据
                }
                long min = ((Number) mn).longValue();
                long max = ((Number) mx).longValue();
                if (min > max) {
                    return null;
                }
                return new long[]{min, max};
            }
        } catch (Exception e) {
            log.warn("JOIN 主键范围查询失败: {}.{}: {}", sch, table, e.getMessage());
        }
        return null;
    }

    /** 去掉 SQL 条件中的 t0. 主表别名前缀(单表查询无别名歧义) */
    private String stripAlias(String where) {
        if (StrUtil.isBlank(where)) {
            return where;
        }
        return where.replaceAll("\\bt0\\.", "");
    }

    /** 预清空目标表(TRUNCATE/REPLACE 语义: 分片前先清空一次, 各片统一 INSERT) */
    private void truncateTable(DbConn conn, String schema, String table) {
        String sql = "TRUNCATE TABLE " + quoteName(schema, table, conn.getDbType());
        try (Connection jc = connectionManager.getConnection(conn);
             Statement st = jc.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException("预清空目标表失败(" + schema + "." + table + "): " + e.getMessage());
        }
    }

    private String buildJdbcUrl(DbConn conn, String schema) {
        DbTypeInfo info = DbTypeRegistry.of(conn.getDbType());
        if (info == null) {
            info = DbTypeRegistry.of("MYSQL");
        }
        return info.buildUrl(conn.getHost(), conn.getPort(), schema, conn.getParams());
    }

    private String readerName(String dbType) {
        return switch (normalizeType(dbType)) {
            case "ORACLE" -> "oraclereader";
            case "POSTGRESQL" -> "postgresqlreader";
            case "SQLSERVER" -> "sqlserverreader";
            default -> "mysqlreader";
        };
    }

    private String writerName(String dbType) {
        return switch (normalizeType(dbType)) {
            case "ORACLE" -> "oraclewriter";
            case "POSTGRESQL" -> "postgresqlwriter";
            case "SQLSERVER" -> "sqlserverwriter";
            default -> "mysqlwriter";
        };
    }

    private String normalizeType(String dbType) {
        if (dbType == null) {
            return "MYSQL";
        }
        return dbType.toUpperCase();
    }

    private String normalizeWriteMode(String mode) {
        if (mode == null) {
            return "insert";
        }
        return switch (mode.toUpperCase()) {
            case "REPLACE" -> "replace";
            case "UPDATE" -> "update";
            default -> "insert";
        };
    }

    private String quoteName(String schema, String table, String dbType) {
        String q = normalizeType(dbType).equals("MYSQL") ? "`" : "\"";
        String s = schema == null ? "" : q + schema + q + ".";
        return s + q + table + q;
    }

    private void parseStat(EtlJobLog jobLog, String logText) {
        Matcher m1 = P_TOTAL.matcher(logText);
        if (m1.find()) {
            jobLog.setTotalRecords(Long.parseLong(m1.group(1)));
        }
        Matcher m2 = P_READ_SPEED.matcher(logText);
        if (m2.find()) {
            jobLog.setWriteSpeed(m2.group(1));
        }
        Matcher m3 = P_FAILED.matcher(logText);
        if (m3.find() && Long.parseLong(m3.group(1)) > 0) {
            jobLog.setRunStatus("FAILED");
            jobLog.setErrorMsg("读写失败总数: " + m3.group(1));
        }
    }

    private String lastErrorLines(String logText) {
        String[] lines = logText.split("\\R");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = lines.length - 1; i >= 0 && count < 12; i--) {
            String l = lines[i];
            if (l.contains("Exception") || l.contains("ERROR") || l.contains("错误") || l.contains("失败")) {
                sb.insert(0, l + "\n");
                count++;
            }
        }
        if (count == 0 && lines.length > 0) {
            for (int i = Math.max(0, lines.length - 8); i < lines.length; i++) {
                sb.append(lines[i]).append('\n');
            }
        }
        return sb.toString();
    }
}
