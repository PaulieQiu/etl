package pan.dromara.dbtool.lsp;

/**
 * SQL LSP (Language Server Protocol) 代理服务接口
 * <p>对接 duck-sqllsp, 提供 SQL 语法校验、语义智能补全、Hover 查看建表语句</p>
 * <p>P3 长期规划: 后端托管 duck-sqllsp 子进程, 前端通过 WebSocket 通信</p>
 *
 * @author doubao
 */
public interface SqlLspService {

    /**
     * 为指定数据源启动 LSP 会话
     *
     * @param connId 数据源ID
     * @param schema 数据库名
     * @return 会话ID
     */
    String startSession(Long connId, String schema);

    /**
     * 销毁 LSP 会话
     *
     * @param sessionId 会话ID
     */
    void stopSession(String sessionId);

    /**
     * 发送 LSP 请求 (completion/hover/definition 等)
     *
     * @param sessionId 会话ID
     * @param method    LSP 方法名
     * @param params    请求参数
     * @return LSP 响应
     */
    Object sendRequest(String sessionId, String method, Object params);

    /**
     * 刷新 LSP 上下文元数据 (表/列/建表语句)
     *
     * @param sessionId 会话ID
     */
    void refreshMetadata(String sessionId);
}
