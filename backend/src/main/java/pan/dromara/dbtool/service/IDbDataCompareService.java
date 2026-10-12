package pan.dromara.dbtool.service;

import java.util.List;
import java.util.Map;

/**
 * 数据对比与同步服务(P3)
 * <p>选择源/目标连接与表, 按匹配键比较: 新增/缺失/不同/相同; 输出总量与明细抽样;
 * 分批应用 INSERT/UPDATE/DELETE(每批独立事务, 可审计检查点, 不做跨库原子性假设)。</p>
 *
 * @author doubao
 */
public interface IDbDataCompareService {

    /**
     * 数据对比预览(只读)
     *
     * @param params sourceConnId/targetConnId/sourceSchema/targetSchema/sourceTable/targetTable/
     *               keyColumns[]/ignoreColumns[]/whereSql
     * @return {summary:{sourceRows,targetRows,added,missing,diff,same}, samples:{added:[...],missing:[...],diff:[...]},
     *         columns:[...], keyColumns:[...]}
     */
    Map<String, Object> preview(Map<String, Object> params);

    /**
     * 分批应用同步(INSERT/UPDATE/DELETE, 参数化执行)
     *
     * @param params 同 preview + operations[]/batchSize/confirmationToken
     * @return {applied:{insert,update,delete}, failed:[{batch,operation,count,error}], summary}
     */
    Map<String, Object> apply(Map<String, Object> params);
}
