package pan.dromara.dbtool.ssh;

/**
 * SSH 隧道服务接口
 * <p>基于 JSch 实现 SSH 本地端口转发, 支持通过 SSH 跳板机访问远端数据库</p>
 * <p>P1 长期规划: 后端 Java 进程建立隧道, JDBC 连接访问本地转发端口</p>
 *
 * @author doubao
 */
public interface SshTunnelService {

    /**
     * 建立 SSH 隧道
     *
     * @param connId 数据源ID
     * @return 本地转发端口
     */
    Integer createTunnel(Long connId);

    /**
     * 关闭 SSH 隧道
     *
     * @param connId 数据源ID
     */
    void closeTunnel(Long connId);

    /**
     * 检查隧道状态
     *
     * @param connId 数据源ID
     * @return 是否活跃
     */
    boolean isTunnelActive(Long connId);

    /**
     * 获取隧道本地端口
     *
     * @param connId 数据源ID
     * @return 本地端口, 未建立返回 null
     */
    Integer getLocalPort(Long connId);

    /**
     * 关闭所有隧道 (应用关闭时调用)
     */
    void closeAllTunnels();
}
