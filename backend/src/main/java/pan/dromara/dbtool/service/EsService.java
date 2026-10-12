package pan.dromara.dbtool.service;

import pan.dromara.dbtool.core.DbConnectionManager;
import pan.dromara.dbtool.core.DbAccessPolicy;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.mapper.DbConnMapper;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Elasticsearch 可视化管理 服务
 * <p>索引管理(创建/删除/刷新/关闭/打开)、数据浏览(_search 分页)、文档 CRUD、
 * Dev Tools 通用 REST 代理、SQL 查询、集群监控</p>
 * <p>全部走 ES REST API(Basic 许可可用), 与 DBeaver/ElasticHD/Kibana 一致, 不使用 JDBC</p>
 *
 * @author doubao
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class EsService {

    private final DbConnMapper dbConnMapper;
    private final DbConnectionManager connectionManager;

    private static final Set<String> CONSOLE_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "HEAD");

    private DbConn getConn(Long connId) {
        DbConn conn = dbConnMapper.selectById(connId);
        if (conn == null) {
            throw new ServiceException("数据库连接配置不存在: " + connId);
        }
        if (!"ELASTICSEARCH".equalsIgnoreCase(conn.getDbType())) {
            throw new ServiceException("该连接不是 Elasticsearch 类型, 无法执行 ES 管理操作");
        }
        return conn;
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 索引列表(_cat/indices 增强: 健康/状态/文档数/存储/分片)
     */
    public Object indices(Long connId, boolean sys) {
        DbConn conn = getConn(connId);
        JSONArray arr = (JSONArray) connectionManager.esRest(conn, "GET", "/_cat/indices?format=json&bytes=b", null);
        List<Map<String, Object>> list = new ArrayList<>();
        if (arr != null) {
            for (Object o : arr) {
                JSONObject it = (JSONObject) o;
                String name = it.getStr("index");
                if (name != null && name.startsWith(".") && !sys) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", name);
                m.put("health", it.getStr("health"));
                m.put("status", it.getStr("status"));
                m.put("pri", it.getInt("pri", 0));
                m.put("rep", it.getInt("rep", 0));
                m.put("docsCount", it.getInt("docs.count", 0));
                m.put("storeSize", it.getStr("store.size"));
                m.put("uuid", it.getStr("uuid"));
                list.add(m);
            }
        }
        list.sort(Comparator.comparing(m -> String.valueOf(m.get("index"))));
        return list;
    }

    /**
     * 集群信息: 根信息(版本) + 集群健康 + 节点列表
     */
    public Map<String, Object> cluster(Long connId) {
        DbConn conn = getConn(connId);
        Map<String, Object> out = new LinkedHashMap<>();
        JSONObject root = (JSONObject) connectionManager.esRest(conn, "GET", "/", null);
        Map<String, Object> version = new LinkedHashMap<>();
        if (root != null) {
            version.put("name", root.getStr("name"));
            version.put("clusterName", root.getStr("cluster_name"));
            JSONObject v = root.getJSONObject("version");
            if (v != null) {
                version.put("number", v.getStr("number"));
                version.put("luceneVersion", v.getStr("lucene_version"));
                version.put("buildDate", v.getStr("build_date"));
                version.put("buildFlavor", v.getStr("build_flavor"));
            }
        }
        out.put("version", version);
        JSONObject health = (JSONObject) connectionManager.esRest(conn, "GET", "/_cluster/health", null);
        Map<String, Object> h = new LinkedHashMap<>();
        if (health != null) {
            for (String k : new String[]{
                "cluster_name", "status", "timed_out", "number_of_nodes", "number_of_data_nodes",
                "active_primary_shards", "active_shards", "relocating_shards", "initializing_shards",
                "unassigned_shards", "delayed_unassigned_shards", "number_of_pending_tasks",
                "number_of_in_flight_fetch", "task_max_waiting_in_queue_millis", "active_shards_percent_as_number"
            }) {
                if (health.containsKey(k)) {
                    h.put(k, health.get(k));
                }
            }
        }
        out.put("health", h);
        JSONArray nodes = (JSONArray) connectionManager.esRest(
            conn, "GET", "/_cat/nodes?format=json&h=name,ip,heap.percent,ram.percent,cpu,load_1m,disk.used_percent,master&full_id=false", null);
        out.put("nodes", nodes == null ? new JSONArray() : nodes);
        return out;
    }

    /**
     * 索引详情: settings + mappings + aliases + 统计
     */
    public Map<String, Object> indexInfo(Long connId, String index) {
        DbConn conn = getConn(connId);
        checkIndex(index);
        Map<String, Object> out = new LinkedHashMap<>();
        JSONObject info = (JSONObject) connectionManager.esRest(conn, "GET", "/" + enc(index), null);
        JSONObject idx = info == null ? null : info.getJSONObject(index);
        out.put("settings", idx == null ? new JSONObject() : idx.getJSONObject("settings"));
        out.put("mappings", idx == null ? new JSONObject() : idx.getJSONObject("mappings"));
        out.put("aliases", idx == null ? new JSONObject() : idx.getJSONObject("aliases"));
        JSONObject stats = (JSONObject) connectionManager.esRest(conn, "GET", "/" + enc(index) + "/_stats", null);
        Map<String, Object> s = new LinkedHashMap<>();
        if (stats != null) {
            JSONObject all = stats.getJSONObject("_all");
            if (all != null) {
                JSONObject total = all.getJSONObject("total");
                if (total != null) {
                    JSONObject docs = total.getJSONObject("docs");
                    JSONObject store = total.getJSONObject("store");
                    JSONObject indexing = total.getJSONObject("indexing");
                    s.put("docsCount", docs == null ? 0 : docs.getInt("count", 0));
                    s.put("docsDeleted", docs == null ? 0 : docs.getInt("deleted", 0));
                    s.put("storeSizeBytes", store == null ? 0L : store.getLong("size_in_bytes", 0L));
                    s.put("indexTotal", indexing == null ? 0L : indexing.getLong("index_total", 0L));
                }
            }
        }
        out.put("stats", s);
        return out;
    }

    /**
     * 创建索引(settings/mappings 可选)
     */
    public void createIndex(Long connId, String index, Object settings, Object mappings) {
        DbConn conn = getConn(connId);
        DbAccessPolicy.requireWritable(conn);
        checkIndex(index);
        JSONObject body = new JSONObject();
        if (settings != null) {
            body.set("settings", settings);
        }
        if (mappings != null) {
            body.set("mappings", mappings);
        }
        connectionManager.esRest(conn, "PUT", "/" + enc(index), body.isEmpty() ? null : body);
    }

    /**
     * 删除索引(危险操作, 前端二次确认)
     */
    public void deleteIndex(Long connId, String index) {
        DbConn conn = getConn(connId);
        DbAccessPolicy.requireWritable(conn);
        checkIndex(index);
        connectionManager.esRest(conn, "DELETE", "/" + enc(index), null);
    }

    /**
     * 索引操作: refresh/flush/close/open/clearCache
     */
    public Object indexOp(Long connId, String index, String op) {
        DbConn conn = getConn(connId);
        DbAccessPolicy.requireWritable(conn);
        checkIndex(index);
        String suffix;
        switch (op == null ? "" : op.toLowerCase()) {
            case "refresh":
                suffix = "/_refresh";
                break;
            case "flush":
                suffix = "/_flush";
                break;
            case "close":
                suffix = "/_close";
                break;
            case "open":
                suffix = "/_open";
                break;
            case "clearcache":
                suffix = "/_cache/clear";
                break;
            default:
                throw new ServiceException("不支持的索引操作: " + op + "(支持 refresh/flush/close/open/clearCache)");
        }
        return connectionManager.esRest(conn, "POST", "/" + enc(index) + suffix, null);
    }

    /**
     * 数据浏览: _search 分页 + 排序 + query DSL
     */
    public Map<String, Object> search(Long connId, String index, Integer from, Integer size, Object sort, Object query) {
        DbConn conn = getConn(connId);
        checkIndex(index);
        JSONObject body = new JSONObject();
        body.set("from", from == null || from < 0 ? 0 : from);
        body.set("size", size == null || size <= 0 || size > 500 ? 100 : size);
        body.set("query", query == null ? new JSONObject().set("match_all", new JSONObject()) : query);
        if (sort != null) {
            body.set("sort", sort);
        }
        JSONObject resp = (JSONObject) connectionManager.esRest(conn, "POST", "/" + enc(index) + "/_search", body);
        Map<String, Object> out = new LinkedHashMap<>();
        long total = 0;
        List<Map<String, Object>> docs = new ArrayList<>();
        if (resp != null) {
            JSONObject hits = resp.getJSONObject("hits");
            if (hits != null) {
                JSONObject t = hits.getJSONObject("total");
                total = t == null ? 0L : t.getLong("value", 0L);
                JSONArray hs = hits.getJSONArray("hits");
                if (hs != null) {
                    for (Object o : hs) {
                        JSONObject hit = (JSONObject) o;
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("_id", hit.getStr("_id"));
                        d.put("_index", hit.getStr("_index"));
                        d.put("_score", hit.get("_score"));
                        JSONObject src = hit.getJSONObject("_source");
                        d.put("_source", src == null ? new JSONObject() : src);
                        docs.add(d);
                    }
                }
            }
        }
        out.put("total", total);
        out.put("docs", docs);
        return out;
    }

    /**
     * 单文档读取
     */
    public Object doc(Long connId, String index, String id) {
        DbConn conn = getConn(connId);
        checkIndex(index);
        if (StrUtil.isBlank(id)) {
            throw new ServiceException("文档 _id 不能为空");
        }
        return connectionManager.esRest(conn, "GET", "/" + enc(index) + "/_doc/" + enc(id), null);
    }

    /**
     * 新增/更新文档(id 为空则自动生成)
     */
    public Object saveDoc(Long connId, String index, String id, Object doc) {
        DbConn conn = getConn(connId);
        DbAccessPolicy.requireWritable(conn);
        checkIndex(index);
        if (doc == null) {
            throw new ServiceException("文档内容不能为空");
        }
        String path = "/" + enc(index) + "/_doc" + (StrUtil.isNotBlank(id) ? "/" + enc(id) : "");
        return connectionManager.esRest(conn, "POST", path, doc);
    }

    /**
     * 删除文档
     */
    public void deleteDoc(Long connId, String index, String id) {
        DbConn conn = getConn(connId);
        DbAccessPolicy.requireWritable(conn);
        checkIndex(index);
        if (StrUtil.isBlank(id)) {
            throw new ServiceException("文档 _id 不能为空");
        }
        connectionManager.esRest(conn, "DELETE", "/" + enc(index) + "/_doc/" + enc(id), null);
    }

    /**
     * Dev Tools 通用 REST 代理(Kibana Console 风格): method + path + JSON body
     */
    public Map<String, Object> console(Long connId, String method, String path, String body) {
        DbConn conn = getConn(connId);
        String m = method == null ? "GET" : method.trim().toUpperCase();
        if (!CONSOLE_METHODS.contains(m)) {
            throw new ServiceException("不支持的方法: " + method + "(支持 GET/POST/PUT/DELETE/HEAD)");
        }
        if (StrUtil.isBlank(path)) {
            throw new ServiceException("请求路径不能为空");
        }
        if (!path.startsWith("/")) {
            throw new ServiceException("请求路径必须以 / 开头, 例如 /_cluster/health 或 /myindex/_search");
        }
        if (DbAccessPolicy.isReadOnly(conn) && !isReadOnlyConsoleRequest(m, path)) {
            DbAccessPolicy.requireWritable(conn);
        }
        Object parsed = null;
        if (StrUtil.isNotBlank(body)) {
            try {
                parsed = cn.hutool.json.JSONUtil.parse(body);
            } catch (Exception e) {
                throw new ServiceException("请求体不是合法 JSON: " + e.getMessage());
            }
        }
        if (DbAccessPolicy.isReadOnly(conn) && "POST".equals(m)
            && path.split("\\?", 2)[0].matches("/(?:[^/]+/)?_sql")) {
            String sql = parsed instanceof JSONObject json ? json.getStr("query") : null;
            DbAccessPolicy.requireReadOnlyStatement(sql);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        long t0 = System.currentTimeMillis();
        Object resp = connectionManager.esRest(conn, m, path, parsed);
        out.put("costMs", System.currentTimeMillis() - t0);
        out.put("body", resp);
        return out;
    }

    public static boolean isReadOnlyConsoleRequest(String method, String path) {
        String normalizedMethod = method == null ? "" : method.trim().toUpperCase(java.util.Locale.ROOT);
        if ("GET".equals(normalizedMethod) || "HEAD".equals(normalizedMethod)) {
            return true;
        }
        if (!"POST".equals(normalizedMethod)) {
            return false;
        }
        if (StrUtil.isBlank(path)) {
            return false;
        }
        String normalizedPath = path.split("\\?", 2)[0].toLowerCase(java.util.Locale.ROOT);
        return normalizedPath.matches("/(?:[^/]+/)?_(search|sql|msearch)$");
    }

    /**
     * SQL 查询(ES REST SQL, Basic 许可可用)
     */
    public Map<String, Object> sql(Long connId, String sql, Integer limit) {
        DbConn conn = getConn(connId);
        if (StrUtil.isBlank(sql)) {
            throw new ServiceException("SQL 不能为空");
        }
        if (DbAccessPolicy.isReadOnly(conn)) {
            DbAccessPolicy.requireReadOnlyStatement(sql);
        }
        JSONObject body = new JSONObject();
        body.set("query", sql);
        body.set("fetch_size", 200);
        JSONObject resp = (JSONObject) connectionManager.esRest(conn, "POST", "/_sql?format=json", body);
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> cols = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        if (resp != null) {
            JSONArray columns = resp.getJSONArray("columns");
            if (columns != null) {
                for (Object o : columns) {
                    JSONObject c = (JSONObject) o;
                    cols.add(c.getStr("name"));
                }
            }
            JSONArray ra = resp.getJSONArray("rows");
            if (ra != null) {
                int max = limit == null || limit <= 0 ? 500 : Math.min(limit, 500);
                for (int i = 0; i < ra.size() && i < max; i++) {
                    JSONArray r = ra.getJSONArray(i);
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int j = 0; j < r.size() && j < cols.size(); j++) {
                        row.put(cols.get(j), r.get(j));
                    }
                    rows.add(row);
                }
            }
        }
        out.put("columns", cols);
        out.put("rows", rows);
        out.put("total", rows.size());
        return out;
    }

    private static void checkIndex(String index) {
        if (StrUtil.isBlank(index)) {
            throw new ServiceException("索引名称不能为空");
        }
        if (index.contains("/") || index.contains("?") || index.contains("#") || index.contains("\\")) {
            throw new ServiceException("索引名称包含非法字符");
        }
    }
}
