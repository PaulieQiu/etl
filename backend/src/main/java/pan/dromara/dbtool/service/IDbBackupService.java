package pan.dromara.dbtool.service;

import java.util.Map;

/**
 * 数据库备份/恢复服务(P4)
 * <p>MySQL/MariaDB 全库备份(mysqldump)与恢复(mysql client), 异步后台任务执行:
 * 状态/进度/取消/失败记录; 备份文件可下载; 恢复为高危操作需一次性确认凭证。</p>
 *
 * @author doubao
 */
public interface IDbBackupService {

    /**
     * 启动备份任务(异步)
     *
     * @param connId 连接ID
     * @param schema 数据库名
     * @param userId 操作人
     * @return 任务ID
     */
    Long startDump(Long connId, String schema, String userId);

    /**
     * 启动恢复任务(异步, 需确认凭证)
     *
     * @param connId       连接ID
     * @param schema       目标数据库名
     * @param filePath     备份文件绝对路径(必须来自本服务备份目录)
     * @param userId       操作人
     * @param confirmationToken 一次性确认凭证
     * @return 任务ID
     */
    Long startRestore(Long connId, String schema, String filePath, String userId, String confirmationToken);

    /**
     * 任务列表(分页, 按创建时间倒序)
     */
    Map<String, Object> listJobs(Integer pageNum, Integer pageSize, Long connId, String userId);

    /**
     * 取消执行中的任务(进程销毁, 状态置 CANCELLED)
     */
    boolean cancelJob(Long jobId, String userId);

    /**
     * 查询任务详情
     */
    Map<String, Object> getJob(Long jobId, String userId);
}
