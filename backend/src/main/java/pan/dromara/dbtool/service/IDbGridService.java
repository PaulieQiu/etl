package pan.dromara.dbtool.service;

import java.util.List;
import java.util.Map;

/**
 * 服务端网格查询服务(P2)
 * <p>对单条 SELECT 支持: 结构化过滤(列名白名单+参数化)、排序、真实 COUNT 总量与分页。
 * 替代"仅对已取回行客户端过滤/排序/分页"的能力缺口。</p>
 *
 * @author doubao
 */
public interface IDbGridService {

    /**
     * 服务端网格分页查询
     *
     * @param connId  连接ID
     * @param sql     单条 SELECT
     * @param pageNum 页码(从1)
     * @param pageSize 每页行数
     * @param filters 结构化过滤条件 [{column, op, value}] (op: eq/ne/gt/ge/lt/le/like/in/between/isnull/notnull)
     * @param sort    排序 {column, order(asc/desc)}, 可为 null
     * @return {columns, columnTypes, rows, total, costMs}
     */
    Map<String, Object> query(Long connId, String sql, int pageNum, int pageSize,
                              List<Map<String, Object>> filters, Map<String, Object> sort);
}
