package pan.dromara.etl.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import pan.dromara.etl.domain.EtlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * ETL 文件/接口桥接工具
 * <p>自编译 Kettle 引擎不含 Excel/JSON 输入步骤, 采用「后端桥接 + Kettle 原生步骤」混合方案:
 * 源 Excel/API 由本类解析为临时 CSV, Kettle 用 CsvInput 读取; 目标 Excel 由 Kettle TextFileOutput
 * 输出 CSV 后本类转换为 xlsx。API 目标由 Kettle HTTPPost 步骤逐行推送。</p>
 *
 * @author doubao
 */
public class EtlFileBridge {

    private static final Logger log = LoggerFactory.getLogger(EtlFileBridge.class);

    public static final String TABLE = "TABLE";
    public static final String EXCEL = "EXCEL";
    public static final String API = "API";

    /** 上传/输出统一文件目录(系统属性 etl.file.dir 可覆盖, 默认项目工作目录) */
    public static String fileDir() {
        String dir = System.getProperty("etl.file.dir",
            "D:" + File.separator + "project" + File.separator + "etl-engine" + File.separator + "files");
        File d = new File(dir);
        if (!d.exists()) {
            d.mkdirs();
        }
        return dir;
    }

    /** 路径解析: 绝对路径原样返回; 相对文件名/路径拼接到统一文件目录 */
    public static String resolveFile(String path) {
        if (StrUtil.isBlank(path)) {
            return path;
        }
        File f = new File(path);
        if (f.isAbsolute()) {
            return path;
        }
        return new File(fileDir(), path).getAbsolutePath();
    }

    /** Token 缓存(key=authJson, 5 分钟) */
    private static final Map<String, TokenHolder> TOKEN_CACHE = new HashMap<>();

    private static class TokenHolder {
        String token;
        long expireAt;
        TokenHolder(String token, long expireAt) {
            this.token = token;
            this.expireAt = expireAt;
        }
    }

    /** 鉴权解析结果: 合并后请求头 + 处理后的 URL */
    public static class AuthContext {
        public Map<String, String> headers = new HashMap<>();
        public String url;
    }

    /**
     * 解析 API 鉴权: NONE/STATIC 仅用静态 headers; TOKEN 先获取(缓存 5 分钟)并替换 ${token}
     * @param authJson 鉴权配置JSON(可为空)
     * @param staticHeadersJson 静态请求头JSON(ETL 任务已有 headers 列)
     * @param url 原始 URL
     */
    public static AuthContext resolveAuth(String authJson, String staticHeadersJson, String url) throws Exception {
        AuthContext ctx = new AuthContext();
        ctx.url = url;
        if (StrUtil.isNotBlank(staticHeadersJson)) {
            JSONObject hj = JSONUtil.parseObj(staticHeadersJson);
            for (Map.Entry<String, Object> e : hj.entrySet()) {
                if (e.getValue() != null) {
                    ctx.headers.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }
        }
        if (StrUtil.isBlank(authJson)) {
            return ctx;
        }
        JSONObject auth;
        try {
            auth = JSONUtil.parseObj(authJson);
        } catch (Exception e) {
            throw new RuntimeException("API鉴权配置JSON解析失败: " + authJson);
        }
        String type = StrUtil.blankToDefault(auth.getStr("type"), "NONE").toUpperCase();
        if (!"TOKEN".equals(type)) {
            return ctx;
        }
        String tokenUrl = auth.getStr("tokenUrl");
        if (StrUtil.isBlank(tokenUrl)) {
            throw new RuntimeException("TOKEN 鉴权未配置 tokenUrl");
        }
        String token = getToken(auth);
        if (StrUtil.isBlank(token)) {
            throw new RuntimeException("API 鉴权 Token 获取失败: tokenPath=" + auth.getStr("tokenPath"));
        }
        // 替换 headers 与 URL 中的 ${token}
        for (Map.Entry<String, String> e : ctx.headers.entrySet()) {
            e.setValue(e.getValue().replace("${token}", token));
        }
        ctx.url = ctx.url.replace("${token}", token);
        return ctx;
    }

    /** 获取 token(缓存 5 分钟, 按 authJson 为 key) */
    private static String getToken(JSONObject auth) throws Exception {
        String authKey = auth.toString();
        synchronized (TOKEN_CACHE) {
            TokenHolder h = TOKEN_CACHE.get(authKey);
            if (h != null && h.expireAt > System.currentTimeMillis()) {
                return h.token;
            }
        }
        String tokenUrl = auth.getStr("tokenUrl");
        String method = StrUtil.blankToDefault(auth.getStr("tokenMethod"), "POST").toUpperCase();
        String tokenHeaders = auth.getStr("tokenHeaders");
        String tokenBody = auth.getStr("tokenBody");
        String tokenPath = StrUtil.blankToDefault(auth.getStr("tokenPath"), "token");
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            Map<String, String> tHeaders = new HashMap<>();
            if (StrUtil.isNotBlank(tokenHeaders)) {
                JSONObject hj = JSONUtil.parseObj(tokenHeaders);
                for (Map.Entry<String, Object> e : hj.entrySet()) {
                    if (e.getValue() != null) {
                        tHeaders.put(e.getKey(), String.valueOf(e.getValue()));
                    }
                }
            }
            JSONObject resp = fetchJson(client, tokenUrl, method, tHeaders, tokenBody);
            Object v = tokenPath.startsWith("$") ? resp.getByPath(tokenPath) : resp.getByPath(tokenPath);
            if (v == null) {
                v = resp.get(tokenPath);
            }
            String token = v == null ? "" : String.valueOf(v);
            synchronized (TOKEN_CACHE) {
                TOKEN_CACHE.put(authKey, new TokenHolder(token, System.currentTimeMillis() + 300_000));
            }
            return token;
        }
    }

    /** 桥接临时目录 */
    public static String bridgeDir() {
        String dir = System.getProperty("java.io.tmpdir") + File.separator + "etl_bridge";
        File d = new File(dir);
        if (!d.exists()) {
            d.mkdirs();
        }
        return dir;
    }

    /** 源桥接结果: CSV 路径 + 列名(顺序) */
    public static class BridgeResult {
        public String csvPath;
        public List<String> columns;
        public BridgeResult(String csvPath, List<String> columns) {
            this.csvPath = csvPath;
            this.columns = columns;
        }
    }

    /** 任务级源文件唯一名(固定名: 同任务不并发, 执行前清理) */
    public static String srcCsvPath(EtlJob job) {
        return bridgeDir() + File.separator + "src_" + job.getId() + ".csv";
    }

    /** 目标 TextFileOutput 输出的 CSV 路径(固定名, 写入 Kettle XML; 执行后转 xlsx) */
    public static String dstCsvPath(EtlJob job) {
        return bridgeDir() + File.separator + "dst_" + job.getId() + ".csv";
    }

    /** 是否非表源(需桥接) */
    public static boolean isBridgeSrc(EtlJob job) {
        return job != null && !TABLE.equalsIgnoreCase(StrUtil.blankToDefault(job.getSrcType(), TABLE));
    }

    /** 是否非表目标 */
    public static boolean isBridgeDst(EtlJob job) {
        return job != null && !TABLE.equalsIgnoreCase(StrUtil.blankToDefault(job.getDstType(), TABLE));
    }

    /**
     * 源桥接: EXCEL/API → 临时 CSV(UTF-8, 首行表头), 返回 CSV 路径与列名
     */
    public static BridgeResult srcToCsv(EtlJob job) throws Exception {
        String type = StrUtil.blankToDefault(job.getSrcType(), TABLE).toUpperCase();
        String csv = srcCsvPath(job);
        Files.deleteIfExists(Paths.get(csv));
        List<String> cols;
        if (EXCEL.equals(type)) {
            if (StrUtil.isBlank(job.getSrcExcelPath())) {
                throw new RuntimeException("源Excel文件路径未配置(srcExcelPath)");
            }
            cols = excelToCsv(job.getSrcExcelPath(), job.getSrcExcelSheet(),
                job.getSrcExcelHeader() == null ? 0 : job.getSrcExcelHeader(),
                job.getSrcExcelDataRow(), job.getSrcExcelColFrom(), job.getSrcExcelColTo(), csv);
        } else if (API.equals(type)) {
            if (StrUtil.isBlank(job.getSrcApiUrl())) {
                throw new RuntimeException("源API地址未配置(srcApiUrl)");
            }
            cols = apiToCsv(job, csv);
        } else {
            throw new RuntimeException("源类型不支持桥接: " + type);
        }
        return new BridgeResult(csv, cols);
    }

    /**
     * Excel → CSV: 表头行(headerRow, 0=首行)为列名; 数据起始行 dataStartRow(null/-1=表头下一行);
     * 列范围 colFrom/colTo(1-based, null/0=不限); 返回列名
     */
    public static List<String> excelToCsv(String excelPath, String sheetName, int headerRow,
                                          Integer dataStartRow, Integer colFrom, Integer colTo,
                                          String csvPath) throws Exception {
        File xf = new File(resolveFile(excelPath));
        if (!xf.exists() || !xf.isFile()) {
            throw new RuntimeException("源Excel文件不存在: " + excelPath + "(相对路径基于 " + fileDir() + ")");
        }
        try (Workbook wb = WorkbookFactory.create(xf, null, true);
             BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(csvPath), StandardCharsets.UTF_8))) {
            Sheet sheet = StrUtil.isBlank(sheetName) ? wb.getSheetAt(0) : wb.getSheet(sheetName);
            if (sheet == null) {
                throw new RuntimeException("Excel工作表不存在: " + sheetName);
            }
            DataFormatter fmt = new DataFormatter();
            int first = sheet.getFirstRowNum();
            int last = sheet.getLastRowNum();
            if (last < first || headerRow > last) {
                throw new RuntimeException("Excel无有效数据行: headerRow=" + headerRow);
            }
            Row head = sheet.getRow(headerRow);
            if (head == null) {
                throw new RuntimeException("Excel表头行为空: row=" + headerRow);
            }
            int cf = (colFrom == null || colFrom <= 0) ? 0 : colFrom - 1;
            int ct = (colTo == null || colTo <= 0) ? Integer.MAX_VALUE : colTo - 1;
            List<String> cols = new ArrayList<>();
            int lastCol = head.getLastCellNum() == -1 ? 0 : head.getLastCellNum() - 1;
            for (int ci = cf; ci <= lastCol && ci <= ct; ci++) {
                Cell c = head.getCell(ci);
                String name = (c == null ? "" : fmt.formatCellValue(c)).trim();
                if (StrUtil.isBlank(name)) {
                    name = "col" + (ci + 1);
                }
                cols.add(name);
            }
            if (cols.isEmpty()) {
                throw new RuntimeException("Excel表头无有效列(列范围: " + colFrom + "~" + colTo + ")");
            }
            // 写表头
            w.write(joinCsv(cols));
            w.newLine();
            // 写数据(从数据起始行起; 空行跳过)
            int dataStart = (dataStartRow == null || dataStartRow < 0) ? headerRow + 1 : dataStartRow;
            int maxCol = Math.min(ct, Math.max(lastCol, cf));
            for (int r = dataStart; r <= last; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                List<String> vals = new ArrayList<>();
                for (int ci = cf; ci <= maxCol; ci++) {
                    Cell c = row.getCell(ci);
                    vals.add(c == null ? "" : fmt.formatCellValue(c));
                }
                w.write(joinCsv(vals));
                w.newLine();
            }
            return cols;
        }
    }

    /**
     * API → CSV: 请求(分页) → JSON 数组 → 列名=首条记录 key 顺序(后续补齐缺失列)
     */
    public static List<String> apiToCsv(EtlJob job, String csvPath) throws Exception {
        AuthContext auth = resolveAuth(job.getSrcApiAuth(), job.getSrcApiHeaders(), job.getSrcApiUrl());
        String url = auth.url;
        String method = StrUtil.blankToDefault(job.getSrcApiMethod(), "GET").toUpperCase();
        String dataPath = StrUtil.blankToDefault(job.getSrcApiDataPath(), "");
        String pageField = job.getSrcApiPageField();
        int pageSize = job.getSrcApiPageSize() == null ? 0 : job.getSrcApiPageSize();

        CloseableHttpClient client = HttpClients.createDefault();
        LinkedHashSet<String> colSet = new LinkedHashSet<>();
        List<Map<String, String>> rows = new ArrayList<>();
        int page = 1;
        try {
            while (true) {
                String reqUrl = url;
                if (pageSize > 0) {
                    String sep = reqUrl.contains("?") ? "&" : "?";
                    reqUrl = reqUrl + sep + pageField + "=" + page + (pageSize > 0 ? "&" + pageSizeParam(pageSize, reqUrl) : "");
                }
                JSONObject body = fetchJson(client, reqUrl, method, auth.headers, job.getSrcApiBody());
                JSONArray arr = extractArray(body, dataPath);
                int got = 0;
                if (arr != null) {
                    for (Object o : arr) {
                        if (!(o instanceof JSONObject jo)) {
                            continue;
                        }
                        Map<String, String> row = new LinkedHashMap<>();
                        for (Map.Entry<String, Object> e : jo.entrySet()) {
                            String k = e.getKey();
                            colSet.add(k);
                            row.put(k, e.getValue() == null ? "" : String.valueOf(e.getValue()));
                        }
                        rows.add(row);
                        got++;
                    }
                }
                if (pageSize <= 0 || got < pageSize) {
                    break;
                }
                page++;
            }
        } finally {
            client.close();
        }
        if (colSet.isEmpty()) {
            throw new RuntimeException("API 返回为空或无有效 JSON 数组, 请检查 URL/鉴权/数据路径(dataPath=" + dataPath + ")");
        }
        List<String> cols = new ArrayList<>(colSet);
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(csvPath), StandardCharsets.UTF_8))) {
            w.write(joinCsv(cols));
            w.newLine();
            for (Map<String, String> row : rows) {
                List<String> vals = new ArrayList<>();
                for (String c : cols) {
                    vals.add(row.getOrDefault(c, ""));
                }
                w.write(joinCsv(vals));
                w.newLine();
            }
        }
        return cols;
    }

    private static String pageSizeParam(int pageSize, String url) {
        // 页大小参数名默认 pageSize, 可后续扩展配置
        return "pageSize=" + pageSize;
    }

    /** 执行 API 请求并返回 JSON 对象(根数组时包装); headers 为已合并(含鉴权)的请求头 */
    private static JSONObject fetchJson(CloseableHttpClient client, String url, String method,
                                        Map<String, String> headers, String bodyStr) throws Exception {
        HttpRequestBase req;
        if ("POST".equalsIgnoreCase(method)) {
            HttpPost post = new HttpPost(url);
            if (StrUtil.isNotBlank(bodyStr)) {
                post.setEntity(new StringEntity(bodyStr, StandardCharsets.UTF_8));
            }
            req = post;
        } else {
            req = new HttpGet(url);
        }
        req.setConfig(RequestConfig.custom().setConnectTimeout(15000).setSocketTimeout(60000).build());
        // 默认 JSON 请求头
        req.setHeader("Accept", "application/json");
        req.setHeader("Content-Type", "application/json");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getValue() != null) {
                    req.setHeader(e.getKey(), e.getValue());
                }
            }
        }
        try (var resp = client.execute(req)) {
            int code = resp.getStatusLine().getStatusCode();
            String text = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
            if (code >= 400) {
                throw new RuntimeException("API 请求失败 HTTP " + code + " " + url + " -> " + StrUtil.maxLength(text, 300));
            }
            Object obj = JSONUtil.parse(text);
            if (obj instanceof JSONArray ja) {
                JSONObject wrap = new JSONObject();
                wrap.set("_root", ja);
                return wrap;
            }
            return (JSONObject) obj;
        }
    }

    /** 提取 JSON 数组: 根数组或按点路径(如 data.list) */
    private static JSONArray extractArray(JSONObject body, String dataPath) {
        if (StrUtil.isBlank(dataPath)) {
            Object root = body.get("_root");
            return root instanceof JSONArray ja ? ja : null;
        }
        Object v = body.getByPath(dataPath);
        if (v instanceof JSONArray ja) {
            return ja;
        }
        if (v instanceof JSONObject jo) {
            // 单对象包装为单行数组
            JSONArray single = new JSONArray();
            single.add(jo);
            return single;
        }
        return null;
    }

    /** CSV → Excel(xlsx): 首行表头, 数据行写入; 支持覆盖原文件 */
    public static void csvToExcel(String csvPath, String xlsxPath, String sheetName) throws Exception {
        File cf = new File(csvPath);
        if (!cf.exists()) {
            throw new RuntimeException("目标CSV不存在(TextFileOutput未产出): " + csvPath);
        }
        File xf = new File(resolveFile(xlsxPath));
        if (xf.getParentFile() != null && !xf.getParentFile().exists()) {
            xf.getParentFile().mkdirs();
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(csvPath), StandardCharsets.UTF_8));
             Workbook wb = new XSSFWorkbook();
             OutputStream os = new FileOutputStream(xf)) {
            Sheet sheet = wb.createSheet(StrUtil.isBlank(sheetName) ? "Sheet1" : sheetName);
            String line;
            int rn = 0;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                List<String> cells = splitCsv(line);
                Row row = sheet.createRow(rn++);
                for (int ci = 0; ci < cells.size(); ci++) {
                    row.createCell(ci).setCellValue(cells.get(ci));
                }
            }
            for (int ci = 0; ci < (rn > 0 ? sheet.getRow(0).getLastCellNum() : 0); ci++) {
                sheet.autoSizeColumn(ci);
            }
            wb.write(os);
        }
    }

    /** 读取 CSV 首行(列名) */
    public static List<String> readCsvHeader(String csvPath) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(csvPath), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            return line == null ? new ArrayList<>() : splitCsv(line);
        }
    }

    /**
     * CSV(首行表头) → 目标 API: 逐行将 ${字段} 占位替换为当前行值后推送
     * @return 成功推送行数
     */
    public static int pushCsvToApi(String csvPath, EtlJob job) throws Exception {
        AuthContext auth = resolveAuth(job.getDstApiAuth(), job.getDstApiHeaders(), job.getDstApiUrl());
        String url = auth.url;
        String method = StrUtil.blankToDefault(job.getDstApiMethod(), "POST").toUpperCase();
        String bodyTpl = StrUtil.blankToDefault(job.getDstApiBodyTpl(), "");
        Map<String, String> headers = auth.headers;
        if (StrUtil.isBlank(url)) {
            throw new RuntimeException("目标API地址未配置(dstApiUrl)");
        }
        File cf = new File(csvPath);
        if (!cf.exists()) {
            throw new RuntimeException("目标API桥接CSV不存在: " + csvPath);
        }
        int pushed = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(csvPath), StandardCharsets.UTF_8))) {
            String headerLine = r.readLine();
            if (headerLine == null) {
                throw new RuntimeException("目标API桥接CSV无表头: " + csvPath);
            }
            List<String> cols = splitCsv(headerLine);
            try (CloseableHttpClient client = HttpClients.createDefault()) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    List<String> vals = splitCsv(line);
                    Map<String, String> row = new HashMap<>();
                    for (int i = 0; i < cols.size(); i++) {
                        row.put(cols.get(i), i < vals.size() ? vals.get(i) : "");
                    }
                    // 替换 ${field}; bodyTpl 为原始模板, 每次循环重新替换(避免上一行替换结果污染后续行)
                    String reqUrl = replaceVars(url, row);
                    String body = StrUtil.isNotBlank(bodyTpl) ? replaceVars(bodyTpl, row) : "";
                    pushRow(client, reqUrl, method, headers, body);
                    pushed++;
                }
            }
        }
        return pushed;
    }

    /** 逐行替换 ${key} 占位 */
    private static String replaceVars(String tpl, Map<String, String> row) {
        if (StrUtil.isBlank(tpl) || row.isEmpty()) {
            return tpl;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < tpl.length()) {
            int s = tpl.indexOf("${", i);
            if (s < 0) {
                sb.append(tpl, i, tpl.length());
                break;
            }
            sb.append(tpl, i, s);
            int e = tpl.indexOf('}', s + 2);
            if (e < 0) {
                sb.append(tpl, s, tpl.length());
                break;
            }
            String key = tpl.substring(s + 2, e);
            sb.append(row.getOrDefault(key, ""));
            i = e + 1;
        }
        return sb.toString();
    }

    /** 单行推送(非 2xx 抛错中止); headers 为已合并(含鉴权)请求头 */
    private static void pushRow(CloseableHttpClient client, String url, String method,
                                Map<String, String> headers, String bodyStr) throws Exception {
        HttpRequestBase req;
        if ("GET".equalsIgnoreCase(method)) {
            req = new HttpGet(url);
        } else {
            HttpPost post = new HttpPost(url);
            if (StrUtil.isNotBlank(bodyStr)) {
                post.setEntity(new StringEntity(bodyStr, StandardCharsets.UTF_8));
            }
            req = post;
        }
        req.setConfig(RequestConfig.custom().setConnectTimeout(15000).setSocketTimeout(60000).build());
        req.setHeader("Content-Type", "application/json");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getValue() != null) {
                    req.setHeader(e.getKey(), e.getValue());
                }
            }
        }
        try (var resp = client.execute(req)) {
            int code = resp.getStatusLine().getStatusCode();
            if (code >= 400) {
                String text = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                throw new RuntimeException("目标API推送失败 HTTP " + code + " " + url + " -> " + StrUtil.maxLength(text, 300));
            }
        }
    }

    /** 统计 CSV 数据行数(不含表头) */
    public static long csvRowCount(String csvPath) throws Exception {
        long n = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(csvPath), StandardCharsets.UTF_8))) {
            String line = r.readLine(); // 表头
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 删除桥接文件 */
    public static void cleanup(String... paths) {
        for (String p : paths) {
            if (StrUtil.isNotBlank(p)) {
                try {
                    Files.deleteIfExists(Paths.get(p));
                } catch (IOException ignore) {
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

    /** 解析单行 CSV(简单实现: 逗号/双引号包裹) */
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
