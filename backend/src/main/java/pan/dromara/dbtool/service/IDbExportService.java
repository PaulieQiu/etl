package pan.dromara.dbtool.service;

import java.io.Writer;
import java.util.List;
import java.util.Map;

/**
 * 大结果导出服务(P2)
 * <p>对单条 SELECT 流式导出 CSV/JSON/INSERT, 不受工作台 1000 行查询上限约束。</p>
 *
 * @author doubao
 */
public interface IDbExportService {

    /**
     * 导出预览: 校验 SQL 为单条查询, 返回列信息与估算行数
     */
    Map<String, Object> preview(Long connId, String sql);

    /**
     * 流式导出到 writer(逐行写出, 内存安全)
     *
     * @param connId  连接ID
     * @param sql     单条 SELECT/SHOW 等查询语句
     * @param format  csv/json/sql(INSERT)
     * @param maxRows 行数上限
     * @param writer  输出目标
     * @return 实际导出行数
     */
    long exportTo(Long connId, String sql, String format, long maxRows, Writer writer);

    /**
     * 获取导出列信息(SELECT 元数据; 供 INSERT 表名推断)
     */
    List<String> describeColumns(Long connId, String sql);
}
