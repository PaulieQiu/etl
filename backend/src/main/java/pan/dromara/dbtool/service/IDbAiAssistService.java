package pan.dromara.dbtool.service;

import java.util.List;
import java.util.Map;

/**
 * AI SQL 助手(P5): 基于连接元数据离线生成/解释 SQL, 只读白名单 + 审计
 *
 * @author doubao
 */
public interface IDbAiAssistService {

    /**
     * 生成 SQL: 输入自然语言需求, 基于连接元数据(库/表/列)规则生成 SQL
     * <p>只返回 SQL 文本, 不执行; 写类需求生成模板并标注需人工确认。</p>
     */
    Map<String, Object> assist(Long connId, String schema, String request, Long userId);

    /**
     * 解释 SQL: 拆语句、识别类型、提取涉及表、风险分级
     */
    Map<String, Object> explain(Long connId, String sql, Long userId);

    /**
     * 元数据上下文(供前端提示/调试): 库列表、表列表(含列)
     */
    Map<String, Object> context(Long connId, String schema, Long userId);
}
