package pan.dromara.dbtool.ai;

/**
 * AI-SQL 助手服务接口
 * <p>支持两种模式: 直接对接大模型API (轻量快速) / DBHub MCP-Server (面向未来扩展)</p>
 * <p>P2 长期规划: 自然语言转SQL、SQL解释、SQL优化、高危SQL拦截</p>
 *
 * @author doubao
 */
public interface AiSqlService {

    /**
     * 自然语言转 SQL
     *
     * @param connId    数据源ID
     * @param schema    数据库名
     * @param naturalLanguage 自然语言描述
     * @return 生成的 SQL
     */
    String naturalLanguageToSql(Long connId, String schema, String naturalLanguage);

    /**
     * SQL 解释
     *
     * @param sql SQL语句
     * @return 解释文本
     */
    String explainSql(String sql);

    /**
     * SQL 优化建议
     *
     * @param connId 数据源ID
     * @param schema 数据库名
     * @param sql    SQL语句
     * @return 优化建议
     */
    String optimizeSql(Long connId, String schema, String sql);

    /**
     * 检测高危 SQL
     *
     * @param sql SQL语句
     * @return 风险等级 (LOW/MEDIUM/HIGH/CRITICAL)
     */
    String detectRisk(String sql);

    /**
     * 生成测试数据
     *
     * @param connId  数据源ID
     * @param schema  数据库名
     * @param table   表名
     * @param rowCount 行数
     * @return INSERT 语句
     */
    String generateTestData(Long connId, String schema, String table, Integer rowCount);
}
