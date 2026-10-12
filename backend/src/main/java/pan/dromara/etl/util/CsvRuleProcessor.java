package pan.dromara.etl.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pan.dromara.etl.domain.EtlJob;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * CSV 行级清洗/质量规则处理器
 * <p>DataX 引擎的 Excel/API 源无 SQL 执行环境, 由本类在 CSV 上按行执行字段级规则
 * (去空值/去重复/格式规范化/单位换算/默认值填充/枚举/范围/格式/关联完整性), 再交由
 * DataX txtfilereader 搬运。SQL 清洗/自定义 SQL 仅数据库表源支持, 此处跳过并记录日志。</p>
 *
 * @author doubao
 */
public class CsvRuleProcessor {

    private static final Logger log = LoggerFactory.getLogger(CsvRuleProcessor.class);

    /** 清洗后 CSV 路径(固定名, 与源桥接 CSV 区分) */
    public static String cleanedCsvPath(EtlJob job) {
        return EtlFileBridge.bridgeDir() + File.separator + "clean_" + job.getId() + ".csv";
    }

    /** 读取 CSV 表头 */
    public static List<String> readHeader(String csv) throws Exception {
        List<String> header = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        readCsv(csv, header, rows);
        return header;
    }

    /**
     * 对源桥接 CSV 执行清洗/质量规则 + 目标映射列重排, 返回可交给 txtfilereader 的 CSV 路径。
     * 无规则且无映射时原样返回源 CSV 路径。
     */
    public static String process(EtlJob job, String srcCsv, List<String> srcCols) throws Exception {
        List<JSONObject> cleanRules = parseRules(job.getCleanRules());
        List<JSONObject> qualityRules = parseRules(job.getQualityRules());
        List<Map<String, String>> dstMapping = parseMapping(job.getDstMapping());
        boolean hasRules = !cleanRules.isEmpty() || !qualityRules.isEmpty();
        boolean hasMap = !dstMapping.isEmpty();
        if (!hasRules && !hasMap) {
            return srcCsv;
        }
        // 读源 CSV
        List<String> header = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        readCsv(srcCsv, header, rows);
        if (header.isEmpty()) {
            throw new RuntimeException("源CSV无表头: " + srcCsv);
        }
        // 新增列: 规则/映射中引用的非现有字段追加为输出列(默认值填充/叶子标记等)
        Set<String> knownCols = new HashSet<>(header);
        for (JSONObject r : cleanRules) {
            JSONObject cfg = ruleConfig(r);
            String f = cfg == null ? "" : cfg.getStr("field", "");
            if (StrUtil.isNotBlank(f) && knownCols.add(f)) {
                header.add(f);
            }
        }
        for (Map<String, String> m : dstMapping) {
            for (String f : new String[]{m.get("srcField"), m.get("dstField")}) {
                if (StrUtil.isNotBlank(f) && knownCols.add(f)) {
                    header.add(f);
                }
            }
        }
        // 叶子标记规则(LEAF): 需全表"被引用上级编码"集合, 单独预置列值
        List<JSONObject> leafRules = new ArrayList<>();
        List<JSONObject> restClean = new ArrayList<>();
        for (JSONObject r : cleanRules) {
            String t = StrUtil.blankToDefault(r.getStr("ruleType", ""), r.getStr("ruleName", ""));
            if ("叶子标记".equals(t) || "LEAF".equalsIgnoreCase(t) || "叶子判断".equals(t)) {
                leafRules.add(r);
            } else {
                restClean.add(r);
            }
        }
        Set<String> parentSet = new HashSet<>();
        if (!leafRules.isEmpty()) {
            JSONObject cfg = ruleConfig(leafRules.get(0));
            String pf = cfg == null ? "F_NSXXSJJD" : StrUtil.blankToDefault(cfg.getStr("parentField", ""), "F_NSXXSJJD");
            for (List<String> row : rows) {
                Map<String, String> rec = new HashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    rec.put(header.get(i), i < row.size() ? row.get(i) : "");
                }
                String pv = rec.getOrDefault(pf, "");
                if (StrUtil.isNotBlank(pv)) {
                    parentSet.add(pv);
                }
            }
        }
        // 行级规则(全部规则按顺序应用; 命中剔除的行不再进入后续规则)
        List<List<String>> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<String> row : rows) {
            Map<String, String> rec = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                rec.put(header.get(i), i < row.size() ? row.get(i) : "");
            }
            for (JSONObject r : leafRules) {
                applyLeaf(r, rec, parentSet);
            }
            boolean drop = false;
            for (JSONObject r : restClean) {
                if (apply(r, rec, seen)) {
                    drop = true;
                    break;
                }
            }
            if (!drop) {
                for (JSONObject r : qualityRules) {
                    if (apply(r, rec, seen)) {
                        drop = true;
                        break;
                    }
                }
            }
            if (!drop) {
                List<String> outRow = new ArrayList<>();
                for (String h : header) {
                    outRow.add(rec.getOrDefault(h, ""));
                }
                kept.add(outRow);
            }
        }
        // 目标映射重排: 输出列序 = dstMapping 顺序, 表头用 dstField
        List<String> outHeader;
        List<List<String>> outRows;
        if (hasMap) {
            outHeader = new ArrayList<>();
            for (Map<String, String> m : dstMapping) {
                String dstF = StrUtil.isBlank(m.get("dstField")) ? m.get("srcField") : m.get("dstField");
                if (StrUtil.isNotBlank(dstF)) {
                    outHeader.add(dstF);
                }
            }
            outRows = new ArrayList<>();
            for (List<String> row : kept) {
                Map<String, String> rec = new HashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    rec.put(header.get(i), i < row.size() ? row.get(i) : "");
                }
                List<String> outRow = new ArrayList<>();
                for (Map<String, String> m : dstMapping) {
                    String srcF = StrUtil.isBlank(m.get("srcField")) ? m.get("dstField") : m.get("srcField");
                    outRow.add(rec.getOrDefault(srcF, ""));
                }
                outRows.add(outRow);
            }
        } else {
            outHeader = header;
            outRows = kept;
        }
        // 写清洗后 CSV
        String outPath = cleanedCsvPath(job);
        Files.deleteIfExists(Paths.get(outPath));
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outPath), StandardCharsets.UTF_8))) {
            w.write(joinCsv(outHeader));
            w.newLine();
            for (List<String> row : outRows) {
                w.write(joinCsv(row));
                w.newLine();
            }
        }
        return outPath;
    }

    /**
     * 应用单条规则到行记录; 返回 true 表示该行应被剔除。
     * seen 集合供去重复规则复用。
     */
    private static boolean apply(JSONObject r, Map<String, String> rec, Set<String> seen) {
        String type = StrUtil.blankToDefault(r.getStr("ruleType", ""), r.getStr("ruleName", ""));
        String config = r.getStr("ruleConfig", "");
        JSONObject cfg = null;
        try {
            if (StrUtil.isNotBlank(config) && config.trim().startsWith("{")) {
                cfg = JSONUtil.parseObj(config);
            }
        } catch (Exception ignored) {
            cfg = null;
        }
        String field = ruleField(cfg, rec);
        String val = StrUtil.isBlank(field) ? "" : rec.getOrDefault(field, "");
        switch (type) {
            case "去空值":
            case "非空":
            case "非空校验":
            case "NOT_NULL":
                return StrUtil.isBlank(val);
            case "去重复":
            case "去重":
            case "DEDUP":
            case "DISTINCT":
            case "唯一校验":
            case "UNIQUE": {
                String key = StrUtil.isBlank(field) ? rec.toString() : (field + "=" + val);
                if (seen.contains(key)) {
                    return true;
                }
                seen.add(key);
                return false;
            }
            case "格式规范化":
            case "FORMAT":
            case "NORMALIZE":
                if (StrUtil.isNotBlank(field)) {
                    String action = cfg == null ? "" : StrUtil.blankToDefault(cfg.getStr("action", ""), "upper").toLowerCase();
                    if ("lower".equals(action) || "小写".equals(action)) {
                        rec.put(field, val.toLowerCase());
                    } else if ("trim".equals(action)) {
                        rec.put(field, val.trim());
                    } else {
                        rec.put(field, val.toUpperCase());
                    }
                }
                return false;
            case "单位换算":
            case "UNIT":
            case "UNIT_CONVERT":
                if (StrUtil.isNotBlank(field) && StrUtil.isNotBlank(val)) {
                    try {
                        double factor = cfg == null ? 1.0 : cfg.getDouble("factor", 1.0);
                        double offset = cfg == null ? 0.0 : cfg.getDouble("offset", 0.0);
                        double nv = Double.parseDouble(val.trim()) * factor + offset;
                        rec.put(field, String.valueOf(nv));
                    } catch (NumberFormatException ignored) {
                        // 非数值原样保留
                    }
                }
                return false;
            case "默认值填充":
            case "DEFAULT":
            case "DEFAULT_VALUE":
                if (StrUtil.isNotBlank(field) && StrUtil.isBlank(val)) {
                    // 条件填充：whenField 配置后，仅当 whenField 的值等于 whenValue（支持多条件 whenField2/whenValue2）才填充
                    boolean condOk = true;
                    if (cfg != null) {
                        String wf = cfg.getStr("whenField", "");
                        String wv = cfg.getStr("whenValue", "");
                        if (StrUtil.isNotBlank(wf)) {
                            String cur = rec.get(wf);
                            condOk = cur != null && StrUtil.equals(cur, wv == null ? "" : wv);
                        }
                        if (condOk) {
                            String wf2 = cfg.getStr("whenField2", "");
                            String wv2 = cfg.getStr("whenValue2", "");
                            if (StrUtil.isNotBlank(wf2)) {
                                String cur2 = rec.get(wf2);
                                condOk = cur2 != null && StrUtil.equals(cur2, wv2 == null ? "" : wv2);
                            }
                        }
                    }
                    if (condOk) {
                        String dv = cfg == null ? "" : StrUtil.blankToDefault(cfg.getStr("defaultValue", ""), cfg.getStr("value", ""));
                        if (StrUtil.isNotBlank(dv)) {
                            rec.put(field, dv);
                        }
                    }
                }
                return false;
            case "当前时间填充":
            case "NOW":
            case "NOW_FILL":
            case "时间戳":
                if (StrUtil.isNotBlank(field)) {
                    rec.put(field, new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
                }
                return false;
            case "UUID填充":
            case "UUID":
            case "UUID_FILL":
                if (StrUtil.isNotBlank(field)) {
                    rec.put(field, java.util.UUID.randomUUID().toString());
                }
                return false;
            case "范围校验":
            case "RANGE": {
                if (StrUtil.isNotBlank(field) && StrUtil.isNotBlank(val)) {
                    try {
                        double v = Double.parseDouble(val.trim());
                        if (cfg != null && cfg.get("min") != null && v < cfg.getDouble("min", 0.0)) {
                            return true;
                        }
                        if (cfg != null && cfg.get("max") != null && v > cfg.getDouble("max", 0.0)) {
                            return true;
                        }
                    } catch (NumberFormatException ignored) {
                        // 非数值不参与范围校验
                    }
                }
                return false;
            }
            case "枚举校验":
            case "ENUM": {
                String list = cfg == null ? "" : StrUtil.blankToDefault(cfg.getStr("list", ""), cfg.getStr("values", ""));
                if (StrUtil.isBlank(field) || StrUtil.isBlank(list)) {
                    return false;
                }
                Set<String> allowed = parseList(list);
                return !allowed.isEmpty() && !allowed.contains(val);
            }
            case "格式校验":
            case "REGEXP":
            case "FORMAT_CHECK": {
                String pattern = cfg == null ? "" : cfg.getStr("pattern", "");
                if (StrUtil.isBlank(field) || StrUtil.isBlank(pattern) || StrUtil.isBlank(val)) {
                    return false;
                }
                try {
                    return !val.matches(pattern);
                } catch (Exception ignored) {
                    return false;
                }
            }
            case "关联完整性":
            case "FK":
                return StrUtil.isBlank(val);
            case "SQL 清洗":
            case "SQL_CLEAN":
            case "自定义 SQL":
            case "CUSTOM_SQL":
                log.warn("清洗/质量规则[{}] 在 CSV 源下不执行: 仅数据库表源支持 SQL 清洗/自定义 SQL", type);
                return false;
            case "字段映射":
            case "MAPPING":
                log.warn("清洗规则[字段映射] 由表单目标映射(dstMapping)配置生效, CSV 源按目标映射重排输出列");
                return false;
            default:
                return false;
        }
    }

    /** 从规则配置中提取字段名 */
    private static String ruleField(JSONObject cfg, Map<String, String> rec) {
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
        return rec.isEmpty() ? "" : rec.keySet().iterator().next();
    }

    /** 规则配置对象(ruleConfig JSON 字符串) */
    private static JSONObject ruleConfig(JSONObject r) {
        if (r == null) {
            return null;
        }
        String config = r.getStr("ruleConfig", "");
        if (StrUtil.isBlank(config) || !config.trim().startsWith("{")) {
            return null;
        }
        try {
            return JSONUtil.parseObj(config);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 叶子标记规则: 该行 F_CODE 未被任何行 F_NSXXSJJD(上级编码) 引用 且 非根 → 叶子(1), 否则 0
     */
    private static void applyLeaf(JSONObject r, Map<String, String> rec, Set<String> parentSet) {
        JSONObject cfg = ruleConfig(r);
        String field = cfg == null ? "F_MX" : cfg.getStr("field", "F_MX");
        String codeField = cfg == null ? "F_CODE" : cfg.getStr("codeField", "F_CODE");
        String parentField = cfg == null ? "F_NSXXSJJD" : cfg.getStr("parentField", "F_NSXXSJJD");
        String leafV = cfg == null ? "1" : cfg.getStr("leafValue", "1");
        String nonLeafV = cfg == null ? "0" : cfg.getStr("nonLeafValue", "0");
        String code = rec.getOrDefault(codeField, "");
        String parent = rec.getOrDefault(parentField, "");
        boolean leaf = StrUtil.isNotBlank(parent) && !parentSet.contains(code);
        rec.put(field, leaf ? leafV : nonLeafV);
    }

    /** 解析枚举列表: JSON 数组 或 逗号分隔 */
    private static Set<String> parseList(String list) {
        Set<String> out = new LinkedHashSet<>();
        String trimmed = list.trim();
        if (trimmed.startsWith("[")) {
            try {
                JSONArray arr = JSONUtil.parseArray(trimmed);
                for (Object o : arr) {
                    if (o != null) {
                        out.add(String.valueOf(o));
                    }
                }
            } catch (Exception ignored) {
                out.clear();
            }
        }
        if (out.isEmpty()) {
            for (String v : list.split(",")) {
                String t = v.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /** 解析规则 JSON(兼容分组与平铺) */
    private static List<JSONObject> parseRules(String rulesJson) {
        List<JSONObject> out = new ArrayList<>();
        if (StrUtil.isBlank(rulesJson)) {
            return out;
        }
        try {
            JSONArray arr = JSONUtil.parseArray(rulesJson);
            for (Object o : arr) {
                if (!(o instanceof JSONObject)) {
                    continue;
                }
                JSONObject item = (JSONObject) o;
                JSONArray inner = item.getJSONArray("rules");
                if (inner != null) {
                    for (Object io : inner) {
                        if (io instanceof JSONObject) {
                            JSONObject r = (JSONObject) io;
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

    /** 解析目标映射 [{srcField,dstField}] */
    private static List<Map<String, String>> parseMapping(String mappingJson) {
        List<Map<String, String>> out = new ArrayList<>();
        if (StrUtil.isBlank(mappingJson)) {
            return out;
        }
        try {
            JSONArray arr = JSONUtil.parseArray(mappingJson);
            for (Object o : arr) {
                if (!(o instanceof JSONObject)) {
                    continue;
                }
                JSONObject m = (JSONObject) o;
                String srcF = m.getStr("srcField", "");
                String dstF = m.getStr("dstField", "");
                if (StrUtil.isBlank(srcF) && StrUtil.isBlank(dstF)) {
                    continue;
                }
                Map<String, String> mm = new LinkedHashMap<>();
                mm.put("srcField", srcF);
                mm.put("dstField", dstF);
                out.add(mm);
            }
        } catch (Exception ignored) {
            // ignore
        }
        return out;
    }

    /** 读 CSV: 首行表头 + 数据行 */
    private static void readCsv(String path, List<String> header, List<List<String>> rows) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            if (line != null) {
                header.addAll(splitCsv(line));
            }
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) {
                    rows.add(splitCsv(line));
                }
            }
        }
    }

    /** 单行 CSV(逗号分隔, 双引号包裹含逗号/引号/换行的值) */
    private static String joinCsv(List<String> vals) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vals.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String v = vals.get(i) == null ? "" : vals.get(i);
            if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(v);
            }
        }
        return sb.toString();
    }

    /** 解析单行 CSV(逗号/双引号包裹) */
    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQ) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQ = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                inQ = true;
            } else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
