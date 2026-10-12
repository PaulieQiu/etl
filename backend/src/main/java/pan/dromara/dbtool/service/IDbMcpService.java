package pan.dromara.dbtool.service;

import java.util.Map;

/**
 * MCP(Model Context Protocol) Server 服务(P5): HTTP + JSON-RPC 2.0
 * <p>只暴露只读工具(白名单连接), 默认禁止写工具; 每次调用审计。</p>
 *
 * @author doubao
 */
public interface IDbMcpService {

    /**
     * 处理 JSON-RPC 请求(initialize/tools/list/tools/call)
     * <p>返回 JSON-RPC 响应对象(含 id 回显)。</p>
     */
    Map<String, Object> handle(Map<String, Object> request, Long userId);
}
