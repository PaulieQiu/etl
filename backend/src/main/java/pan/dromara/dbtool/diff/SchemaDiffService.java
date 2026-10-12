package pan.dromara.dbtool.diff;

import java.util.List;

/**
 * 表结构对比与迁移预览服务接口。
 *
 * @author doubao
 */
public interface SchemaDiffService {

    /**
     * 对比两个数据源的表结构
     *
     * @param sourceConnId 源数据源ID
     * @param targetConnId 目标数据源ID
     * @param sourceSchema 源数据库名
     * @param targetSchema 目标数据库名
     * @return 差异列表
     */
    List<SchemaDiffItem> diff(Long sourceConnId, Long targetConnId,
                              String sourceSchema, String targetSchema);

    /**
     * 生成迁移脚本
     *
     * @param diffItems 差异列表
     * @param direction 迁移方向 (source-to-target / target-to-source)
     * @return DDL 脚本
     */
    String generateMigrationScript(List<SchemaDiffItem> diffItems, String direction);

    /**
     * 预览迁移脚本 (不执行)
     *
     * @param sourceConnId 源数据源ID
     * @param targetConnId 目标数据源ID
     * @param sourceSchema 源数据库名
     * @param targetSchema 目标数据库名
     * @return DDL 脚本
     */
    String previewMigration(Long sourceConnId, Long targetConnId,
                            String sourceSchema, String targetSchema);

    /**
     * 差异项
     */
    class SchemaDiffItem {
        /** 对象类型 (TABLE/COLUMN) */
        private String objectType;
        /** 对象名 */
        private String objectName;
        /** 差异类型 (ADD/MODIFY/DROP) */
        private String diffType;
        /** 源定义 */
        private String sourceDefinition;
        /** 目标定义 */
        private String targetDefinition;
        /** 差异描述 */
        private String description;
        /** 源端 Schema */
        private String sourceSchema;
        /** 目标端 Schema */
        private String targetSchema;

        // getters and setters
        public String getObjectType() { return objectType; }
        public void setObjectType(String objectType) { this.objectType = objectType; }
        public String getObjectName() { return objectName; }
        public void setObjectName(String objectName) { this.objectName = objectName; }
        public String getDiffType() { return diffType; }
        public void setDiffType(String diffType) { this.diffType = diffType; }
        public String getSourceDefinition() { return sourceDefinition; }
        public void setSourceDefinition(String sourceDefinition) { this.sourceDefinition = sourceDefinition; }
        public String getTargetDefinition() { return targetDefinition; }
        public void setTargetDefinition(String targetDefinition) { this.targetDefinition = targetDefinition; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getSourceSchema() { return sourceSchema; }
        public void setSourceSchema(String sourceSchema) { this.sourceSchema = sourceSchema; }
        public String getTargetSchema() { return targetSchema; }
        public void setTargetSchema(String targetSchema) { this.targetSchema = targetSchema; }
    }
}
