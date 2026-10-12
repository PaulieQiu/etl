package pan.dromara.dbtool.service;

import pan.dromara.dbtool.domain.DbSqlHistory;
import pan.dromara.dbtool.domain.DbSqlScript;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

/**
 * SQL 历史与脚本库 服务(P1 服务端化)
 *
 * @author doubao
 */
public interface IDbSqlLibraryService {

    /**
     * 记录一条执行历史(自动按用户保留最近 MAX_HISTORY 条)
     */
    void recordHistory(DbSqlHistory history);

    /**
     * 分页查询执行历史(当前用户)
     */
    Page<DbSqlHistory> listHistory(Long userId, Long connId, String schema, String keyword,
                                   String status, String beginTime, String endTime,
                                   long pageNum, long pageSize);

    /**
     * 删除执行历史(仅本人)
     */
    void deleteHistory(Long userId, Long id);

    /**
     * 保存脚本(无 id 新增, 有 id 且属本人则更新)
     */
    void saveScript(Long userId, DbSqlScript script);

    /**
     * 查询已保存脚本(当前用户)
     */
    List<DbSqlScript> listScripts(Long userId, String keyword);

    /**
     * 删除脚本(仅本人)
     */
    void deleteScript(Long userId, Long id);

}
