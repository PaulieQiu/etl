package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.service.EsService;
import pan.dromara.dbtool.core.DbAccessPolicy;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Elasticsearch 可视化管理 控制器
 * <p>索引管理/数据浏览/文档 CRUD/Dev Tools REST 代理/SQL 查询/集群监控,
 * 界面参考 Kibana(Discover/Dev Tools/Index Management)、ElasticHD、ES-Client</p>
 *
 * @author doubao
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/dbtool/es")
public class EsController {

    private final EsService esService;

    /**
     * 索引列表(可含系统索引)
     */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/indices")
    public R<Object> indices(@RequestParam Long connId,
                             @RequestParam(defaultValue = "false") boolean sys) {
        return R.ok(esService.indices(connId, sys));
    }

    /**
     * 集群信息(版本/健康/节点)
     */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/cluster")
    public R<Map<String, Object>> cluster(@RequestParam Long connId) {
        return R.ok(esService.cluster(connId));
    }

    /**
     * 索引详情(settings/mappings/aliases/统计)
     */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/indexInfo")
    public R<Map<String, Object>> indexInfo(@RequestParam Long connId,
                                            @RequestParam String index) {
        return R.ok(esService.indexInfo(connId, index));
    }

    /**
     * 创建索引
     */
    @SaCheckPermission("dbtool:meta:edit")
    @PostMapping("/createIndex")
    public R<Void> createIndex(@RequestBody Map<String, Object> body) {
        Long connId = Long.valueOf(String.valueOf(body.get("connId")));
        String index = (String) body.get("index");
        esService.createIndex(connId, index, body.get("settings"), body.get("mappings"));
        return R.ok();
    }

    /**
     * 删除索引(危险操作)
     */
    @SaCheckPermission("dbtool:meta:edit")
    @PostMapping("/deleteIndex")
    public R<Void> deleteIndex(@RequestBody Map<String, Object> body) {
        Long connId = Long.valueOf(String.valueOf(body.get("connId")));
        String index = (String) body.get("index");
        esService.deleteIndex(connId, index);
        return R.ok();
    }

    /**
     * 索引操作(refresh/flush/close/open/clearCache)
     */
    @SaCheckPermission("dbtool:meta:edit")
    @PostMapping("/indexOp")
    public R<Object> indexOp(@RequestBody Map<String, Object> body) {
        Long connId = Long.valueOf(String.valueOf(body.get("connId")));
        String index = (String) body.get("index");
        String op = (String) body.get("op");
        return R.ok(esService.indexOp(connId, index, op));
    }

    /**
     * 数据浏览(_search 分页)
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/search")
    public R<Map<String, Object>> search(@RequestBody Map<String, Object> body) {
        Long connId = Long.valueOf(String.valueOf(body.get("connId")));
        String index = (String) body.get("index");
        Integer from = body.get("from") == null ? null : Integer.valueOf(String.valueOf(body.get("from")));
        Integer size = body.get("size") == null ? null : Integer.valueOf(String.valueOf(body.get("size")));
        return R.ok(esService.search(connId, index, from, size, body.get("sort"), body.get("query")));
    }

    /**
     * 单文档读取
     */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/doc")
    public R<Object> doc(@RequestParam Long connId,
                         @RequestParam String index,
                         @RequestParam String id) {
        return R.ok(esService.doc(connId, index, id));
    }

    /**
     * 新增/更新文档
     */
    @SaCheckPermission("dbtool:meta:edit")
    @PostMapping("/saveDoc")
    public R<Object> saveDoc(@RequestBody Map<String, Object> body) {
        Long connId = Long.valueOf(String.valueOf(body.get("connId")));
        String index = (String) body.get("index");
        String id = (String) body.get("id");
        return R.ok(esService.saveDoc(connId, index, id, body.get("doc")));
    }

    /**
     * 删除文档
     */
    @SaCheckPermission("dbtool:meta:edit")
    @DeleteMapping("/doc")
    public R<Void> deleteDoc(@RequestParam Long connId,
                             @RequestParam String index,
                             @RequestParam String id) {
        esService.deleteDoc(connId, index, id);
        return R.ok();
    }

    /**
     * Dev Tools 通用 REST 代理(Kibana Console 风格)
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/console")
    public R<Map<String, Object>> console(@RequestBody Map<String, Object> body) {
        Long connId = Long.valueOf(String.valueOf(body.get("connId")));
        String method = (String) body.get("method");
        String path = (String) body.get("path");
        String reqBody = (String) body.get("body");
        if (!EsService.isReadOnlyConsoleRequest(method, path) && !StpUtil.hasPermission("dbtool:meta:edit")) {
            throw new NotPermissionException("执行 Elasticsearch 写入请求需要 dbtool:meta:edit 权限");
        }
        return R.ok(esService.console(connId, method, path, reqBody));
    }

    /**
     * SQL 查询(ES REST SQL)
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/sql")
    public R<Map<String, Object>> sql(@RequestBody Map<String, Object> body) {
        Long connId = Long.valueOf(String.valueOf(body.get("connId")));
        String sql = (String) body.get("sql");
        Integer limit = body.get("limit") == null ? null : Integer.valueOf(String.valueOf(body.get("limit")));
        if (!DbAccessPolicy.isReadOnlyStatement(sql) && !StpUtil.hasPermission("dbtool:meta:edit")) {
            throw new NotPermissionException("执行 Elasticsearch 写入 SQL 需要 dbtool:meta:edit 权限");
        }
        return R.ok(esService.sql(connId, sql, limit));
    }
}
