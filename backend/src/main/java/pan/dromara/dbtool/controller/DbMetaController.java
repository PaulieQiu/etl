package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.domain.vo.DbColumnVo;
import pan.dromara.dbtool.domain.vo.DbGridChangePreviewVo;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.domain.bo.DbGridChangeBo;
import pan.dromara.dbtool.domain.bo.DbGridChangeCommitBo;
import pan.dromara.dbtool.domain.bo.DbSchemaDiffBo;
import pan.dromara.dbtool.domain.bo.DbStructureChangeBo;
import pan.dromara.dbtool.domain.vo.DbConstraintVo;
import pan.dromara.dbtool.domain.vo.DbForeignKeyVo;
import pan.dromara.dbtool.domain.vo.DbIndexVo;
import pan.dromara.dbtool.domain.vo.DbReferenceVo;
import pan.dromara.dbtool.domain.vo.DbResultVo;
import pan.dromara.dbtool.domain.vo.DbTableVo;
import pan.dromara.dbtool.domain.vo.DbTriggerVo;
import pan.dromara.dbtool.core.DbAccessPolicy;
import pan.dromara.dbtool.core.DbOperationConfirmationService;
import pan.dromara.dbtool.core.DbStructureChangeConfirmation;
import pan.dromara.dbtool.diff.SchemaDiffService;
import pan.dromara.dbtool.service.IDbMetaService;

import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据库元数据/工作台 控制器
 *
 * @author doubao
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/dbtool/meta")
public class DbMetaController {

    private final IDbMetaService metaService;
    private final DbOperationConfirmationService confirmationService;
    private final SchemaDiffService schemaDiffService;

    /**
     * 数据库列表
     */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/databases")
    public R<List<String>> databases(@RequestParam Long connId) {
        return R.ok(metaService.databases(connId));
    }

    /**
     * Compare tables and columns between two compatible schemas.
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/schema-diff/compare")
    public R<List<SchemaDiffService.SchemaDiffItem>> compareSchemas(@Validated @RequestBody DbSchemaDiffBo body) {
        return R.ok(schemaDiffService.diff(body.getSourceConnId(), body.getTargetConnId(),
            body.getSourceSchema(), body.getTargetSchema()));
    }

    /**
     * Generate a non-executable-by-default migration preview for review.
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/schema-diff/preview")
    public R<String> previewSchemaMigration(@Validated @RequestBody DbSchemaDiffBo body) {
        return R.ok(schemaDiffService.previewMigration(body.getSourceConnId(), body.getTargetConnId(),
            body.getSourceSchema(), body.getTargetSchema()));
    }

    /**
     * Issue a short-lived approval for a destructive table or column operation.
     */
    @Log(title = "数据库结构变更确认", businessType = BusinessType.OTHER)
    @PostMapping("/structure-changes/confirmation")
    public R<String> issueStructureChangeConfirmation(@RequestBody DbStructureChangeBo body) {
        String operation = body == null ? "" : String.valueOf(body.getOperation()).toUpperCase(java.util.Locale.ROOT);
        String permission = switch (operation) {
            case "DROP_TABLE" -> "dbtool:meta:remove";
            case "DROP_COLUMN" -> "dbtool:meta:edit";
            default -> throw new ServiceException("不支持该结构变更确认操作");
        };
        if (!StpUtil.hasPermission(permission)) {
            throw new NotPermissionException("该结构变更操作需要 " + permission + " 权限");
        }
        DbConn conn = metaService.getConn(body.getConnId());
        DbAccessPolicy.requireWritable(conn);
        String payload = DbStructureChangeConfirmation.canonicalPayload(body);
        return R.ok(confirmationService.issue(body.getConnId(), payload, StpUtil.getLoginIdAsString()));
    }

    /**
     * 表列表
     */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/tables")
    public R<List<DbTableVo>> tables(@RequestParam Long connId,
                                     @RequestParam(required = false) String schema) {
        return R.ok(metaService.tables(connId, schema));
    }

    /**
     * 列结构
     */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/columns")
    public R<List<DbColumnVo>> columns(@RequestParam Long connId,
                                       @RequestParam(required = false) String schema,
                                       @RequestParam String table) {
        return R.ok(metaService.columns(connId, schema, table));
    }

    /**
     * 索引列表
     */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/indexes")
    public R<List<DbIndexVo>> indexes(@RequestParam Long connId,
                                      @RequestParam(required = false) String schema,
                                      @RequestParam String table) {
        return R.ok(metaService.indexes(connId, schema, table));
    }

    /**
     * 约束列表
     */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/constraints")
    public R<List<DbConstraintVo>> constraints(@RequestParam Long connId,
                                               @RequestParam(required = false) String schema,
                                               @RequestParam String table) {
        return R.ok(metaService.constraints(connId, schema, table));
    }

    /**
     * 外键列表(本表引用其他表)
     */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/foreignKeys")
    public R<List<DbForeignKeyVo>> foreignKeys(@RequestParam Long connId,
                                               @RequestParam(required = false) String schema,
                                               @RequestParam String table) {
        return R.ok(metaService.foreignKeys(connId, schema, table));
    }

    /**
     * 引用列表(其他表引用本表)
     */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/references")
    public R<List<DbReferenceVo>> references(@RequestParam Long connId,
                                             @RequestParam(required = false) String schema,
                                             @RequestParam String table) {
        return R.ok(metaService.references(connId, schema, table));
    }

    /**
     * 触发器列表
     */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/triggers")
    public R<List<DbTriggerVo>> triggers(@RequestParam Long connId,
                                         @RequestParam(required = false) String schema,
                                         @RequestParam String table) {
        return R.ok(metaService.triggers(connId, schema, table));
    }

    /**
     * 建表 DDL
     */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/ddl")
    public R<String> ddl(@RequestParam Long connId,
                         @RequestParam(required = false) String schema,
                         @RequestParam String table) {
        return R.data(metaService.ddl(connId, schema, table));
    }

    /**
     * 创建数据库
     */
    @SaCheckPermission("dbtool:meta:add")
    @Log(title = "数据库管理", businessType = BusinessType.INSERT)
    @PostMapping("/createDatabase")
    public R<Void> createDatabase(@RequestBody Map<String, String> body) {
        metaService.createDatabase(Long.valueOf(body.get("connId")), body.get("dbName"), body.get("charset"));
        return R.ok();
    }

    /**
     * 删除表
     */
    @SaCheckPermission("dbtool:meta:remove")
    @Log(title = "数据库管理", businessType = BusinessType.DELETE)
    @PostMapping("/dropTable")
    public R<Void> dropTable(@RequestBody DbStructureChangeBo body) {
        metaService.dropTable(body.getConnId(), body.getSchema(), body.getTable(),
            body.getConfirmationToken(), StpUtil.getLoginIdAsString());
        return R.ok();
    }

    /**
     * 重命名表
     */
    @SaCheckPermission("dbtool:meta:edit")
    @Log(title = "数据库管理", businessType = BusinessType.UPDATE)
    @PostMapping("/renameTable")
    public R<Void> renameTable(@RequestBody Map<String, String> body) {
        metaService.renameTable(Long.valueOf(body.get("connId")), body.get("schema"), body.get("oldName"), body.get("newName"));
        return R.ok();
    }

    /**
     * 新增列
     */
    @SaCheckPermission("dbtool:meta:edit")
    @Log(title = "数据库管理", businessType = BusinessType.UPDATE)
    @PostMapping("/addColumn")
    public R<Void> addColumn(@RequestBody Map<String, Object> body) {
        Long connId = Long.valueOf(String.valueOf(body.get("connId")));
        String schema = (String) body.get("schema");
        String table = (String) body.get("table");
        DbColumnVo column = new DbColumnVo();
        Map<String, Object> col = (Map<String, Object>) body.get("column");
        column.setColumnName((String) col.get("columnName"));
        column.setDataType((String) col.get("dataType"));
        column.setColumnType((String) col.get("columnType"));
        column.setNullable((String) col.get("nullable"));
        column.setDefaultValue((String) col.get("defaultValue"));
        column.setColumnComment((String) col.get("columnComment"));
        column.setExtra((String) col.get("extra"));
        metaService.addColumn(connId, schema, table, column);
        return R.ok();
    }

    /**
     * 删除列
     */
    @SaCheckPermission("dbtool:meta:edit")
    @Log(title = "数据库管理", businessType = BusinessType.UPDATE)
    @DeleteMapping("/dropColumn")
    public R<Void> dropColumn(@RequestParam Long connId,
                              @RequestParam(required = false) String schema,
                              @RequestParam String table,
                              @RequestParam String columnName,
                              @RequestParam String confirmationToken) {
        metaService.dropColumn(connId, schema, table, columnName,
            confirmationToken, StpUtil.getLoginIdAsString());
        return R.ok();
    }

    /**
     * 修改列
     */
    @SaCheckPermission("dbtool:meta:edit")
    @Log(title = "数据库管理", businessType = BusinessType.UPDATE)
    @PostMapping("/modifyColumn")
    public R<Void> modifyColumn(@RequestBody Map<String, Object> body) {
        Long connId = Long.valueOf(String.valueOf(body.get("connId")));
        String schema = (String) body.get("schema");
        String table = (String) body.get("table");
        String oldColumnName = (String) body.get("oldColumnName");
        DbColumnVo column = new DbColumnVo();
        Map<String, Object> col = (Map<String, Object>) body.get("column");
        column.setColumnName((String) col.get("columnName"));
        column.setDataType((String) col.get("dataType"));
        column.setColumnType((String) col.get("columnType"));
        column.setNullable((String) col.get("nullable"));
        column.setDefaultValue((String) col.get("defaultValue"));
        column.setColumnComment((String) col.get("columnComment"));
        column.setExtra((String) col.get("extra"));
        metaService.modifyColumn(connId, schema, table, oldColumnName, column);
        return R.ok();
    }

    /**
     * 执行 DDL(建表/索引等)
     */
    @SaCheckPermission("dbtool:meta:add")
    @Log(title = "数据库管理", businessType = BusinessType.OTHER)
    @PostMapping("/executeDdl")
    public R<Void> executeDdl(@RequestBody Map<String, String> body) {
        metaService.executeDdl(Long.valueOf(body.get("connId")), body.get("sql"));
        return R.ok();
    }

    /**
     * 查询数据(分页)
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/query")
    public R<DbResultVo> query(@RequestBody Map<String, Object> body) {
        Long connId = Long.valueOf(String.valueOf(body.get("connId")));
        String sql = (String) body.get("sql");
        Integer pageNum = body.get("pageNum") == null ? 1 : Integer.valueOf(String.valueOf(body.get("pageNum")));
        Integer pageSize = body.get("pageSize") == null ? 100 : Integer.valueOf(String.valueOf(body.get("pageSize")));
        return R.ok(metaService.query(connId, sql, pageNum, pageSize));
    }

    /**
     * 执行 SQL(查询/更新)
     */
    @SaCheckPermission("dbtool:meta:list")
    @Log(title = "数据库工作台", businessType = BusinessType.OTHER)
    @PostMapping("/execute")
    public R<DbResultVo> execute(@RequestBody Map<String, Object> body) {
        Long connId = Long.valueOf(String.valueOf(body.get("connId")));
        String sql = (String) body.get("sql");
        if (!DbAccessPolicy.isReadOnlyStatement(sql) && !StpUtil.hasPermission("dbtool:meta:edit")) {
            throw new NotPermissionException("执行写入 SQL 需要 dbtool:meta:edit 权限");
        }
        String confirmationToken = body.get("confirmationToken") == null ? null : String.valueOf(body.get("confirmationToken"));
        String executionId = body.get("executionId") == null ? null : String.valueOf(body.get("executionId"));
        Object dbNameRaw = body.get("dbName") != null ? body.get("dbName") : body.get("schema");
        String dbName = dbNameRaw == null ? null : String.valueOf(dbNameRaw);
        return R.ok(metaService.execute(connId, sql, confirmationToken, StpUtil.getLoginIdAsString(), executionId, dbName));
    }

    /**
     * Cancel an active JDBC statement belonging to the current user.
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/cancel")
    public R<Boolean> cancelQuery(@RequestBody Map<String, String> body) {
        return R.ok(metaService.cancelQuery(body.get("executionId"), StpUtil.getLoginIdAsString()));
    }

    /**
     * Preview prepared row changes before commit.
     */
    @SaCheckPermission("dbtool:meta:edit")
    @Log(title = "数据库数据编辑", businessType = BusinessType.UPDATE)
    @PostMapping("/grid-changes/preview")
    public R<DbGridChangePreviewVo> previewGridChanges(@RequestBody DbGridChangeBo body) {
        return R.ok(metaService.previewGridChanges(body, StpUtil.getLoginIdAsString()));
    }

    /**
     * Commit row changes reviewed by the current user.
     */
    @SaCheckPermission("dbtool:meta:edit")
    @Log(title = "数据库数据编辑", businessType = BusinessType.UPDATE)
    @PostMapping("/grid-changes/commit")
    public R<Integer> commitGridChanges(@RequestBody DbGridChangeCommitBo body) {
        return R.ok(metaService.commitGridChanges(body.getChanges(), body.getConfirmationToken(), StpUtil.getLoginIdAsString()));
    }
}
