package pan.dromara.dbtool.diff;

import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.domain.vo.DbColumnVo;
import pan.dromara.dbtool.domain.vo.DbForeignKeyVo;
import pan.dromara.dbtool.domain.vo.DbIndexVo;
import pan.dromara.dbtool.domain.vo.DbTableVo;
import pan.dromara.dbtool.domain.vo.DbTriggerVo;
import pan.dromara.dbtool.service.IDbMetaService;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Compares relational table and column metadata and generates additive DDL for review.
 */
@Service
@RequiredArgsConstructor
public class SchemaDiffServiceImpl implements SchemaDiffService {

    private final IDbMetaService metaService;

    @Override
    public List<SchemaDiffItem> diff(Long sourceConnId, Long targetConnId, String sourceSchema, String targetSchema) {
        if (StrUtil.isBlank(sourceSchema) || StrUtil.isBlank(targetSchema)) {
            throw new ServiceException("源和目标数据库名称不能为空");
        }
        DbConn source = metaService.getConn(sourceConnId);
        DbConn target = metaService.getConn(targetConnId);
        requireCompatibleRelationalConnections(source, target);
        List<DbTableVo> sourceTables = baseTables(metaService.tables(sourceConnId, sourceSchema));
        List<DbTableVo> targetTables = baseTables(metaService.tables(targetConnId, targetSchema));
        Map<String, DbTableVo> targetByName = indexTables(targetTables);
        Map<String, DbTableVo> sourceByName = indexTables(sourceTables);
        List<SchemaDiffItem> items = new ArrayList<>();

        for (DbTableVo sourceTable : sourceTables) {
            String key = normalize(sourceTable.getTableName());
            DbTableVo targetTable = targetByName.get(key);
            if (targetTable == null) {
                SchemaDiffItem item = item("TABLE", sourceTable.getTableName(), "ADD",
                    metaService.ddl(sourceConnId, sourceSchema, sourceTable.getTableName()), null,
                    "表仅存在于源端；建表语句仅生成预览，请核对约束和数据库默认值");
                item.setSourceSchema(sourceSchema);
                item.setTargetSchema(targetSchema);
                items.add(item);
                continue;
            }
            Map<String, DbColumnVo> sourceColumns = indexColumns(
                metaService.columns(sourceConnId, sourceSchema, sourceTable.getTableName()));
            Map<String, DbColumnVo> targetColumns = indexColumns(
                metaService.columns(targetConnId, targetSchema, targetTable.getTableName()));
            for (DbColumnVo sourceColumn : sourceColumns.values()) {
                DbColumnVo targetColumn = targetColumns.get(normalize(sourceColumn.getColumnName()));
                if (targetColumn == null) {
                    SchemaDiffItem item = item("COLUMN", sourceTable.getTableName() + "." + sourceColumn.getColumnName(),
                        "ADD", columnDefinition(sourceColumn), null,
                        canGenerateAddColumn(sourceColumn) ? "列仅存在于源端" : "列仅存在于源端；需人工评估主键或生成属性，不自动生成迁移语句");
                    item.setSourceSchema(sourceSchema);
                    item.setTargetSchema(targetSchema);
                    items.add(item);
                } else if (!sameColumnDefinition(sourceColumn, targetColumn)) {
                    SchemaDiffItem item = item("COLUMN", sourceTable.getTableName() + "." + sourceColumn.getColumnName(),
                        "MODIFY", columnDefinition(sourceColumn), columnDefinition(targetColumn), "列定义不一致；修改脚本需人工评估数据兼容性");
                    item.setSourceSchema(sourceSchema);
                    item.setTargetSchema(targetSchema);
                    items.add(item);
                }
            }
            for (DbColumnVo targetColumn : targetColumns.values()) {
                if (!sourceColumns.containsKey(normalize(targetColumn.getColumnName()))) {
                    SchemaDiffItem item = item("COLUMN", targetTable.getTableName() + "." + targetColumn.getColumnName(),
                        "DROP", null, columnDefinition(targetColumn), "列仅存在于目标端；不会自动生成删除操作");
                    item.setSourceSchema(sourceSchema);
                    item.setTargetSchema(targetSchema);
                    items.add(item);
                }
            }
            Map<String, IndexDefinition> sourceIndexes = indexDefinitions(
                metaService.indexes(sourceConnId, sourceSchema, sourceTable.getTableName()));
            Map<String, IndexDefinition> targetIndexes = indexDefinitions(
                metaService.indexes(targetConnId, targetSchema, targetTable.getTableName()));
            for (IndexDefinition sourceIndex : sourceIndexes.values()) {
                IndexDefinition targetIndex = targetIndexes.get(normalize(sourceIndex.name()));
                if (targetIndex == null) {
                    SchemaDiffItem item = item("INDEX", sourceTable.getTableName() + "." + sourceIndex.name(),
                        "ADD", sourceIndex.definition(), null,
                        sourceIndex.canGenerateAdd() ? "索引仅存在于源端" : "唯一索引或不支持的索引定义需人工评估，不自动生成迁移语句");
                    item.setSourceSchema(sourceSchema);
                    item.setTargetSchema(targetSchema);
                    items.add(item);
                } else if (!Objects.equals(sourceIndex.signature(), targetIndex.signature())) {
                    SchemaDiffItem item = item("INDEX", sourceTable.getTableName() + "." + sourceIndex.name(),
                        "MODIFY", sourceIndex.definition(), targetIndex.definition(), "索引定义不一致；修改脚本需人工评估");
                    item.setSourceSchema(sourceSchema);
                    item.setTargetSchema(targetSchema);
                    items.add(item);
                }
            }
            for (IndexDefinition targetIndex : targetIndexes.values()) {
                if (!sourceIndexes.containsKey(normalize(targetIndex.name()))) {
                    SchemaDiffItem item = item("INDEX", targetTable.getTableName() + "." + targetIndex.name(),
                        "DROP", null, targetIndex.definition(), "索引仅存在于目标端；不会自动生成删除操作");
                    item.setSourceSchema(sourceSchema);
                    item.setTargetSchema(targetSchema);
                    items.add(item);
                }
            }
            comparePrimaryKeys(sourceConnId, targetConnId, sourceSchema, targetSchema, sourceTable, targetTable, items);
            compareForeignKeys(sourceConnId, targetConnId, sourceSchema, targetSchema, sourceTable, targetTable, items);
            compareTriggers(sourceConnId, targetConnId, sourceSchema, targetSchema, sourceTable, targetTable, items);
        }
        compareViews(sourceConnId, targetConnId, sourceSchema, targetSchema, items);
        for (DbTableVo targetTable : targetTables) {
            if (!sourceByName.containsKey(normalize(targetTable.getTableName()))) {
                SchemaDiffItem item = item("TABLE", targetTable.getTableName(), "DROP", null, targetTable.getTableType(),
                    "表仅存在于目标端；不会自动生成删除操作");
                item.setSourceSchema(sourceSchema);
                item.setTargetSchema(targetSchema);
                items.add(item);
            }
        }
        return items;
    }

    @Override
    public String generateMigrationScript(List<SchemaDiffItem> diffItems, String direction) {
        if (diffItems == null || diffItems.isEmpty()) {
            return "-- 没有结构差异";
        }
        if (!"source-to-target".equalsIgnoreCase(direction)) {
            throw new ServiceException("当前仅支持生成源端到目标端的结构迁移预览");
        }
        List<String> statements = new ArrayList<>();
        for (SchemaDiffItem item : diffItems) {
            if ("ADD".equalsIgnoreCase(item.getDiffType()) && "TABLE".equalsIgnoreCase(item.getObjectType())) {
                String targetDdl = qualifyCreateTable(item.getSourceDefinition(), item.getTargetSchema(), item.getObjectName());
                statements.add(targetDdl);
            } else if ("ADD".equalsIgnoreCase(item.getDiffType()) && "COLUMN".equalsIgnoreCase(item.getObjectType())
                && item.getSourceDefinition() != null
                && item.getDescription() != null
                && !item.getDescription().contains("不自动生成")) {
                int dot = item.getObjectName().lastIndexOf('.');
                if (dot < 1) {
                    throw new ServiceException("列差异名称不完整: " + item.getObjectName());
                }
                String tableName = item.getObjectName().substring(0, dot);
                String columnDdl = item.getSourceDefinition();
                statements.add("ALTER TABLE " + quoteIdentifier(item.getTargetSchema()) + "."
                    + quoteIdentifier(tableName) + " ADD COLUMN " + columnDdl + ";");
            } else if ("ADD".equalsIgnoreCase(item.getDiffType()) && "INDEX".equalsIgnoreCase(item.getObjectType())
                && item.getDescription() != null && !item.getDescription().contains("不自动生成")) {
                int dot = item.getObjectName().lastIndexOf('.');
                if (dot < 1) {
                    throw new ServiceException("索引差异名称不完整: " + item.getObjectName());
                }
                String tableName = item.getObjectName().substring(0, dot);
                String indexName = item.getObjectName().substring(dot + 1);
                IndexDefinition definition = parseIndexDefinition(item.getSourceDefinition());
                statements.add("ALTER TABLE " + quoteIdentifier(item.getTargetSchema()) + "."
                    + quoteIdentifier(tableName) + " ADD INDEX " + quoteIdentifier(indexName)
                    + " USING BTREE (" + String.join(", ", definition.columns().stream().map(this::quoteIdentifier).toList()) + ");");
            } else if ("ADD".equalsIgnoreCase(item.getDiffType()) && "FOREIGN KEY".equalsIgnoreCase(item.getObjectType())
                && item.getSourceDefinition() != null
                && item.getDescription() != null && !item.getDescription().contains("不自动生成")) {
                int dot = item.getObjectName().lastIndexOf('.');
                if (dot < 1) {
                    throw new ServiceException("外键差异名称不完整: " + item.getObjectName());
                }
                String tableName = item.getObjectName().substring(0, dot);
                statements.add("ALTER TABLE " + quoteIdentifier(item.getTargetSchema()) + "."
                    + quoteIdentifier(tableName) + " ADD " + item.getSourceDefinition() + ";");
            }
        }
        if (statements.isEmpty()) {
            return "-- 未生成自动执行语句：差异仅包含删除或修改项，请人工评估后编写迁移脚本";
        }
        return "-- 仅包含 ADD 操作；DROP/MODIFY 不会自动生成，执行前请人工复核\n"
            + String.join("\n\n", statements);
    }

    @Override
    public String previewMigration(Long sourceConnId, Long targetConnId, String sourceSchema, String targetSchema) {
        return generateMigrationScript(diff(sourceConnId, targetConnId, sourceSchema, targetSchema), "source-to-target");
    }

    private void requireCompatibleRelationalConnections(DbConn source, DbConn target) {
        if (source == null || target == null) {
            throw new ServiceException("源或目标数据库连接不存在");
        }
        String sourceType = normalize(source.getDbType());
        String targetType = normalize(target.getDbType());
        if (!sourceType.equals("mysql") && !sourceType.equals("mariadb")) {
            throw new ServiceException("Schema Diff 当前仅支持 MySQL/MariaDB，其他关系型数据库暂不生成可执行 DDL");
        }
        if (!sourceType.equals(targetType)) {
            throw new ServiceException("Schema Diff 暂要求源端与目标端数据库类型一致，避免生成不兼容 DDL");
        }
    }

    private void comparePrimaryKeys(Long sourceConnId, Long targetConnId, String sourceSchema, String targetSchema,
                                     DbTableVo sourceTable, DbTableVo targetTable, List<SchemaDiffItem> items) {
        List<String> sourcePk = primaryColumns(metaService.columns(sourceConnId, sourceSchema, sourceTable.getTableName()));
        List<String> targetPk = primaryColumns(metaService.columns(targetConnId, targetSchema, targetTable.getTableName()));
        String sourceDef = primaryDefinition(sourceTable.getTableName(), sourcePk);
        String targetDef = primaryDefinition(targetTable.getTableName(), targetPk);
        if (!sourcePk.equals(targetPk)) {
            String diff = sourcePk.isEmpty() ? "ADD" : targetPk.isEmpty() ? "DROP" : "MODIFY";
            SchemaDiffItem item = item("PRIMARY KEY", sourceTable.getTableName(), diff,
                sourcePk.isEmpty() ? null : sourceDef, targetPk.isEmpty() ? null : targetDef,
                "主键集合不一致；主键变更影响数据唯一性与索引，需人工评估后编写迁移语句，不自动生成");
            item.setSourceSchema(sourceSchema);
            item.setTargetSchema(targetSchema);
            items.add(item);
        }
    }

    private List<String> primaryColumns(List<DbColumnVo> columns) {
        List<String> result = new ArrayList<>();
        if (columns != null) {
            columns.stream().filter(Objects::nonNull)
                .filter(c -> "PRI".equalsIgnoreCase(normalize(c.getColumnKey())))
                .forEach(c -> result.add(normalize(c.getColumnName())));
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private String primaryDefinition(String tableName, List<String> pk) {
        return "PRIMARY KEY (" + String.join(", ", pk.stream().map(this::quoteIdentifier).toList()) + ")";
    }

    private void compareForeignKeys(Long sourceConnId, Long targetConnId, String sourceSchema, String targetSchema,
                                    DbTableVo sourceTable, DbTableVo targetTable, List<SchemaDiffItem> items) {
        Map<String, ForeignKeyDefinition> sourceFks = foreignKeyDefinitions(
            metaService.foreignKeys(sourceConnId, sourceSchema, sourceTable.getTableName()));
        Map<String, ForeignKeyDefinition> targetFks = foreignKeyDefinitions(
            metaService.foreignKeys(targetConnId, targetSchema, targetTable.getTableName()));
        for (ForeignKeyDefinition sourceFk : sourceFks.values()) {
            ForeignKeyDefinition targetFk = targetFks.get(normalize(sourceFk.name()));
            if (targetFk == null) {
                SchemaDiffItem item = item("FOREIGN KEY", sourceTable.getTableName() + "." + sourceFk.name(),
                    "ADD", sourceFk.definition(), null,
                    sourceFk.canGenerateAdd() ? "外键仅存在于源端" : "外键定义复杂(如复合引用列)，已生成预览，请人工复核");
                item.setSourceSchema(sourceSchema);
                item.setTargetSchema(targetSchema);
                items.add(item);
            } else if (!Objects.equals(sourceFk.signature(), targetFk.signature())) {
                SchemaDiffItem item = item("FOREIGN KEY", sourceTable.getTableName() + "." + sourceFk.name(),
                    "MODIFY", sourceFk.definition(), targetFk.definition(), "外键定义不一致；修改需先删后建，人工评估后编写");
                item.setSourceSchema(sourceSchema);
                item.setTargetSchema(targetSchema);
                items.add(item);
            }
        }
        for (ForeignKeyDefinition targetFk : targetFks.values()) {
            if (!sourceFks.containsKey(normalize(targetFk.name()))) {
                SchemaDiffItem item = item("FOREIGN KEY", targetTable.getTableName() + "." + targetFk.name(),
                    "DROP", null, targetFk.definition(), "外键仅存在于目标端；不会自动生成删除操作");
                item.setSourceSchema(sourceSchema);
                item.setTargetSchema(targetSchema);
                items.add(item);
            }
        }
    }

    private Map<String, ForeignKeyDefinition> foreignKeyDefinitions(List<DbForeignKeyVo> fks) {
        Map<String, List<DbForeignKeyVo>> grouped = new LinkedHashMap<>();
        if (fks != null) {
            fks.stream().filter(Objects::nonNull)
                .filter(fk -> StrUtil.isNotBlank(fk.getFkName()))
                .forEach(fk -> grouped.computeIfAbsent(normalize(fk.getFkName()), key -> new ArrayList<>()).add(fk));
        }
        Map<String, ForeignKeyDefinition> definitions = new LinkedHashMap<>();
        grouped.forEach((key, rows) -> {
            rows.sort(Comparator.comparing(DbForeignKeyVo::getOrdinalPosition, Comparator.nullsLast(Integer::compareTo)));
            DbForeignKeyVo first = rows.get(0);
            List<String> columns = rows.stream().map(DbForeignKeyVo::getColumnName).map(this::normalize).toList();
            List<String> refColumns = rows.stream().map(DbForeignKeyVo::getReferencedColumnName).map(this::normalize).toList();
            String updateRule = StrUtil.nullToEmpty(first.getUpdateRule()).toUpperCase(Locale.ROOT);
            String deleteRule = StrUtil.nullToEmpty(first.getDeleteRule()).toUpperCase(Locale.ROOT);
            String signature = String.join("\u0000", columns) + "\u0000" + normalize(first.getReferencedTableName())
                + "\u0000" + String.join("\u0000", refColumns) + "\u0000" + updateRule + "\u0000" + deleteRule;
            StringBuilder definition = new StringBuilder("CONSTRAINT " + quoteIdentifier(first.getFkName())
                + " FOREIGN KEY (" + String.join(", ", columns.stream().map(this::quoteIdentifier).toList())
                + ") REFERENCES " + quoteIdentifier(first.getReferencedTableName())
                + " (" + String.join(", ", refColumns.stream().map(this::quoteIdentifier).toList()) + ")");
            if (StrUtil.isNotBlank(deleteRule) && !"NO ACTION".equals(deleteRule) && !"RESTRICT".equals(deleteRule)) {
                definition.append(" ON DELETE ").append(deleteRule);
            }
            if (StrUtil.isNotBlank(updateRule) && !"NO ACTION".equals(updateRule) && !"RESTRICT".equals(updateRule)) {
                definition.append(" ON UPDATE ").append(updateRule);
            }
            definitions.put(key, new ForeignKeyDefinition(first.getFkName(), signature, definition.toString()));
        });
        return definitions;
    }

    private void compareTriggers(Long sourceConnId, Long targetConnId, String sourceSchema, String targetSchema,
                                 DbTableVo sourceTable, DbTableVo targetTable, List<SchemaDiffItem> items) {
        Map<String, TriggerDefinition> sourceTriggers = triggerDefinitions(
            metaService.triggers(sourceConnId, sourceSchema, sourceTable.getTableName()));
        Map<String, TriggerDefinition> targetTriggers = triggerDefinitions(
            metaService.triggers(targetConnId, targetSchema, targetTable.getTableName()));
        for (TriggerDefinition sourceTrigger : sourceTriggers.values()) {
            TriggerDefinition targetTrigger = targetTriggers.get(normalize(sourceTrigger.name()));
            if (targetTrigger == null) {
                SchemaDiffItem item = item("TRIGGER", sourceTable.getTableName() + "." + sourceTrigger.name(),
                    "ADD", sourceTrigger.definition(), null, "触发器仅存在于源端；触发器 DDL 含分隔符处理，需人工迁移，不自动生成");
                item.setSourceSchema(sourceSchema);
                item.setTargetSchema(targetSchema);
                items.add(item);
            } else if (!Objects.equals(sourceTrigger.signature(), targetTrigger.signature())) {
                SchemaDiffItem item = item("TRIGGER", sourceTable.getTableName() + "." + sourceTrigger.name(),
                    "MODIFY", sourceTrigger.definition(), targetTrigger.definition(), "触发器定义不一致；需人工评估后编写迁移语句");
                item.setSourceSchema(sourceSchema);
                item.setTargetSchema(targetSchema);
                items.add(item);
            }
        }
        for (TriggerDefinition targetTrigger : targetTriggers.values()) {
            if (!sourceTriggers.containsKey(normalize(targetTrigger.name()))) {
                SchemaDiffItem item = item("TRIGGER", targetTable.getTableName() + "." + targetTrigger.name(),
                    "DROP", null, targetTrigger.definition(), "触发器仅存在于目标端；不会自动生成删除操作");
                item.setSourceSchema(sourceSchema);
                item.setTargetSchema(targetSchema);
                items.add(item);
            }
        }
    }

    private Map<String, TriggerDefinition> triggerDefinitions(List<DbTriggerVo> triggers) {
        Map<String, TriggerDefinition> definitions = new LinkedHashMap<>();
        if (triggers != null) {
            triggers.stream().filter(Objects::nonNull)
                .filter(trigger -> StrUtil.isNotBlank(trigger.getTriggerName()))
                .forEach(trigger -> {
                    String timing = StrUtil.nullToEmpty(trigger.getTiming()).toUpperCase(Locale.ROOT);
                    String event = StrUtil.nullToEmpty(trigger.getEvent()).toUpperCase(Locale.ROOT);
                    String body = normalize(StrUtil.nullToEmpty(trigger.getStatement()));
                    String signature = timing + "\u0000" + event + "\u0000" + body;
                    definitions.put(normalize(trigger.getTriggerName()),
                        new TriggerDefinition(trigger.getTriggerName(), signature,
                            "TRIGGER " + quoteIdentifier(trigger.getTriggerName()) + " " + timing + " " + event));
                });
        }
        return definitions;
    }

    private void compareViews(Long sourceConnId, Long targetConnId, String sourceSchema, String targetSchema,
                              List<SchemaDiffItem> items) {
        Map<String, String> sourceViews = viewsByName(metaService.tables(sourceConnId, sourceSchema));
        Map<String, String> targetViews = viewsByName(metaService.tables(targetConnId, targetSchema));
        for (Map.Entry<String, String> sourceView : sourceViews.entrySet()) {
            if (!targetViews.containsKey(sourceView.getKey())) {
                SchemaDiffItem item = item("VIEW", sourceView.getValue(), "ADD",
                    viewDefinition(sourceConnId, sourceSchema, sourceView.getValue()), null,
                    "视图仅存在于源端；视图 DDL 依赖底层表与权限，需人工评估后迁移，不自动生成");
                item.setSourceSchema(sourceSchema);
                item.setTargetSchema(targetSchema);
                items.add(item);
            }
        }
        for (Map.Entry<String, String> targetView : targetViews.entrySet()) {
            if (!sourceViews.containsKey(targetView.getKey())) {
                SchemaDiffItem item = item("VIEW", targetView.getValue(), "DROP",
                    null, viewDefinition(targetConnId, targetSchema, targetView.getValue()), "视图仅存在于目标端；不会自动生成删除操作");
                item.setSourceSchema(sourceSchema);
                item.setTargetSchema(targetSchema);
                items.add(item);
            }
        }
    }

    private Map<String, String> viewsByName(List<DbTableVo> tables) {
        Map<String, String> views = new LinkedHashMap<>();
        if (tables != null) {
            tables.stream().filter(Objects::nonNull)
                .filter(table -> "VIEW".equalsIgnoreCase(table.getTableType()))
                .forEach(table -> views.put(normalize(table.getTableName()), table.getTableName()));
        }
        return views;
    }

    private String viewDefinition(Long connId, String schema, String viewName) {
        try {
            String ddl = metaService.ddl(connId, schema, viewName);
            return StrUtil.isNotBlank(ddl) ? ddl : null;
        } catch (Exception e) {
            return null;
        }
    }

    private List<DbTableVo> baseTables(List<DbTableVo> tables) {
        if (tables == null) {
            return List.of();
        }
        return tables.stream().filter(Objects::nonNull)
            .filter(table -> "BASE TABLE".equalsIgnoreCase(table.getTableType())).toList();
    }

    private Map<String, DbTableVo> indexTables(List<DbTableVo> tables) {
        Map<String, DbTableVo> indexed = new LinkedHashMap<>();
        tables.forEach(table -> indexed.put(normalize(table.getTableName()), table));
        return indexed;
    }

    private Map<String, DbColumnVo> indexColumns(List<DbColumnVo> columns) {
        Map<String, DbColumnVo> indexed = new LinkedHashMap<>();
        if (columns != null) {
            columns.stream().filter(Objects::nonNull)
                .forEach(column -> indexed.put(normalize(column.getColumnName()), column));
        }
        return indexed;
    }

    private Map<String, IndexDefinition> indexDefinitions(List<DbIndexVo> indexes) {
        Map<String, List<DbIndexVo>> grouped = new LinkedHashMap<>();
        if (indexes != null) {
            indexes.stream().filter(Objects::nonNull)
                .filter(index -> StrUtil.isNotBlank(index.getIndexName()))
                .filter(index -> !"PRIMARY".equalsIgnoreCase(index.getIndexName()))
                .forEach(index -> grouped.computeIfAbsent(normalize(index.getIndexName()), key -> new ArrayList<>()).add(index));
        }
        Map<String, IndexDefinition> definitions = new LinkedHashMap<>();
        grouped.forEach((key, rows) -> {
            rows.sort(Comparator.comparing(DbIndexVo::getSeqInIndex, Comparator.nullsLast(Integer::compareTo)));
            DbIndexVo first = rows.get(0);
            List<String> columns = rows.stream().map(DbIndexVo::getColumnName).toList();
            boolean nonUnique = Integer.valueOf(1).equals(first.getNonUnique());
            String type = StrUtil.blankToDefault(first.getIndexType(), "BTREE").toUpperCase(Locale.ROOT);
            String comment = StrUtil.nullToEmpty(first.getIndexComment());
            String signature = String.join("\u0000", columns) + "\u0000" + nonUnique + "\u0000" + type + "\u0000" + comment;
            String definition = (nonUnique ? "INDEX " : "UNIQUE INDEX ") + quoteIdentifier(first.getIndexName())
                + " (" + String.join(", ", columns.stream().map(this::quoteIdentifier).toList()) + ") USING " + type
                + (StrUtil.isNotBlank(comment) ? " COMMENT '" + comment.replace("'", "''") + "'" : "");
            definitions.put(key, new IndexDefinition(first.getIndexName(), columns, nonUnique, type, signature, definition));
        });
        return definitions;
    }

    private IndexDefinition parseIndexDefinition(String definition) {
        if (StrUtil.isBlank(definition) || !definition.startsWith("INDEX ")) {
            throw new ServiceException("索引定义不可自动迁移");
        }
        int open = findUnquoted(definition, '(', 0);
        int close = findUnquoted(definition, ')', open + 1);
        if (open < 0 || close < 0) {
            throw new ServiceException("无法识别索引定义");
        }
        String columns = definition.substring(open + 1, close);
        List<String> names = new ArrayList<>();
        for (String column : splitIndexColumns(columns)) {
            String trimmed = column.trim();
            if (!trimmed.matches("`(?:``|[^`])+`")) {
                throw new ServiceException("索引包含函数或不支持的列定义，需人工编写迁移语句");
            }
            names.add(trimmed.substring(1, trimmed.length() - 1).replace("``", "`"));
        }
        String type = definition.substring(close + 1).trim();
        if (!type.startsWith("USING BTREE")) {
            throw new ServiceException("仅支持预览普通 BTREE 索引迁移");
        }
        return new IndexDefinition(null, names, true, "BTREE", "", definition);
    }

    private List<String> splitIndexColumns(String columns) {
        List<String> parts = new ArrayList<>();
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < columns.length(); i++) {
            char current = columns.charAt(i);
            if (current == '`') {
                if (quoted && i + 1 < columns.length() && columns.charAt(i + 1) == '`') {
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                parts.add(columns.substring(start, i));
                start = i + 1;
            }
        }
        if (quoted) {
            throw new ServiceException("索引列定义中的标识符引号不完整");
        }
        parts.add(columns.substring(start));
        return parts;
    }

    private int findUnquoted(String value, char target, int start) {
        boolean quoted = false;
        for (int i = start; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '`') {
                if (quoted && i + 1 < value.length() && value.charAt(i + 1) == '`') {
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == target && !quoted) {
                return i;
            }
        }
        return -1;
    }

    private boolean sameColumnDefinition(DbColumnVo left, DbColumnVo right) {
        return Objects.equals(normalize(left.getColumnType()), normalize(right.getColumnType()))
            && Objects.equals(normalize(left.getNullable()), normalize(right.getNullable()))
            && Objects.equals(left.getDefaultValue(), right.getDefaultValue())
            && Objects.equals(normalize(left.getColumnKey()), normalize(right.getColumnKey()))
            && Objects.equals(normalize(left.getExtra()), normalize(right.getExtra()));
    }

    private String columnDefinition(DbColumnVo column) {
        StringBuilder definition = new StringBuilder(quoteIdentifier(column.getColumnName()))
            .append(' ').append(StrUtil.blankToDefault(column.getColumnType(), column.getDataType()));
        if ("NO".equalsIgnoreCase(column.getNullable())) {
            definition.append(" NOT NULL");
        } else {
            definition.append(" NULL");
        }
        if (column.getDefaultValue() != null) {
            definition.append(" DEFAULT ").append(defaultExpression(column.getDefaultValue()));
        }
        if (StrUtil.isNotBlank(column.getExtra())) {
            definition.append(' ').append(column.getExtra());
        }
        if (StrUtil.isNotBlank(column.getColumnComment())) {
            definition.append(" COMMENT '").append(column.getColumnComment().replace("'", "''")).append("'");
        }
        return definition.toString();
    }

    private boolean canGenerateAddColumn(DbColumnVo column) {
        return column != null
            && StrUtil.isNotBlank(column.getColumnType())
            && !Boolean.TRUE.equals(column.getPrimary())
            && StrUtil.isBlank(column.getExtra());
    }

    private String defaultExpression(String value) {
        String trimmed = value.trim();
        if (trimmed.matches("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)")
            || trimmed.matches("(?i)NULL|CURRENT_TIMESTAMP(?:\\(\\d+\\))?|CURRENT_DATE|CURRENT_TIME(?:\\(\\d+\\))?")
            || trimmed.matches("(?i)0x[0-9a-f]+|b'[01]+'")) {
            return trimmed;
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private String qualifyCreateTable(String ddl, String targetSchema, String tableName) {
        if (StrUtil.isBlank(ddl) || ddl.startsWith("--")) {
            throw new ServiceException("源端未能提供可迁移的建表 DDL: " + tableName);
        }
        int open = ddl.indexOf('(');
        if (open < 0) {
            throw new ServiceException("无法识别建表 DDL: " + tableName);
        }
        String header = ddl.substring(0, open);
        int create = header.toUpperCase(Locale.ROOT).indexOf("CREATE TABLE");
        if (create < 0) {
            throw new ServiceException("无法识别建表 DDL: " + tableName);
        }
        return header.substring(0, create) + "CREATE TABLE " + quoteIdentifier(targetSchema) + "."
            + quoteIdentifier(tableName) + ddl.substring(open) + ";";
    }

    private String quoteIdentifier(String identifier) {
        return "`" + StrUtil.nullToEmpty(identifier).replace("`", "``") + "`";
    }

    private SchemaDiffItem item(String type, String name, String diff, String source, String target, String description) {
        SchemaDiffItem item = new SchemaDiffItem();
        item.setObjectType(type);
        item.setObjectName(name);
        item.setDiffType(diff);
        item.setSourceDefinition(source);
        item.setTargetDefinition(target);
        item.setDescription(description);
        return item;
    }

    private String normalize(String value) {
        return StrUtil.nullToEmpty(value).toLowerCase(Locale.ROOT);
    }

    private record IndexDefinition(String name, List<String> columns, boolean nonUnique, String type,
                                   String signature, String definition) {
        private boolean canGenerateAdd() {
            return nonUnique && "BTREE".equals(type) && !columns.isEmpty()
                && columns.stream().allMatch(StrUtil::isNotBlank);
        }
    }

    private record ForeignKeyDefinition(String name, String signature, String definition) {
        private boolean canGenerateAdd() {
            return StrUtil.isNotBlank(definition);
        }
    }

    private record TriggerDefinition(String name, String signature, String definition) {
    }
}
