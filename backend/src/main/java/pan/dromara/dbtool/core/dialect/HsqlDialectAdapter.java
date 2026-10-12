package pan.dromara.dbtool.core.dialect;

import java.util.Locale;

/**
 * HSQLDB 方言适配器(C.3.34 新增)
 * <p>HSQLDB 2.x 与 H2 同属 Java 嵌入式数据库, INFORMATION_SCHEMA 结构基本一致
 * (SCHEMATA/TABLES/COLUMNS/TABLE_CONSTRAINTS/KEY_COLUMN_USAGE/REFERENTIAL_CONSTRAINTS/TRIGGERS),
 * 分页同为 LIMIT size OFFSET offset、标识符同为双引号, 因此继承 {@link H2DialectAdapter}。
 * 唯一差异: HSQLDB 索引目录位于 INFORMATION_SCHEMA.SYSTEM_INDEXINFO(JDBC getIndexInfo 风格),
 * 而非 H2 的 INFORMATION_SCHEMA.INDEXES, 故覆写 {@link #indexesSql()}。</p>
 * <p>标注"已适配待实测": SYSTEM_INDEXINFO 列级兼容性需真实 HSQLDB 实例回归确认。</p>
 *
 * @author doubao
 */
public class HsqlDialectAdapter extends H2DialectAdapter {

    @Override
    public boolean supports(String dbType) {
        return dbType != null && "HSQLDB".equalsIgnoreCase(dbType.trim());
    }

    @Override
    public String indexesSql() {
        // HSQLDB: INFORMATION_SCHEMA.SYSTEM_INDEXINFO(JDBC DatabaseMetaData.getIndexInfo 风格)
        return "SELECT INDEX_NAME AS INDEX_NAME, COLUMN_NAME AS COLUMN_NAME, "
            + "ORDINAL_POSITION AS SEQ_IN_INDEX, NON_UNIQUE AS NON_UNIQUE, "
            + "'' AS INDEX_TYPE, '' AS INDEX_COMMENT "
            + "FROM INFORMATION_SCHEMA.SYSTEM_INDEXINFO WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
            + "ORDER BY INDEX_NAME, ORDINAL_POSITION";
    }
}
