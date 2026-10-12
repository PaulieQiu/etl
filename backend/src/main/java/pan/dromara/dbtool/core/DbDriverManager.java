package pan.dromara.dbtool.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据库驱动管理器
 * <p>负责驱动 jar 的下载(Maven Central)、目录管理、外部 ClassLoader 加载与驱动注册</p>
 *
 * @author doubao
 */
@Slf4j
@Component
public class DbDriverManager {

    /**
     * Maven Central 仓库地址
     */
    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

    /**
     * 驱动存放目录
     */
    public static final String DRIVER_DIR = System.getProperty("user.dir") + File.separator + "driver";

    /**
     * 外部驱动 ClassLoader 缓存 (dbType -> loader)
     */
    private final Map<String, URLClassLoader> loaderCache = new ConcurrentHashMap<>();

    /**
     * 已注册驱动缓存, 避免重复注册
     */
    private final Map<String, Boolean> registered = new ConcurrentHashMap<>();

    /**
     * 获取驱动存放目录(不存在则创建)
     */
    public static File getDriverDir() {
        File dir = new File(DRIVER_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * 根据 maven 坐标生成中央仓库下载地址
     *
     * @param artifact groupId:artifactId
     * @param version  版本
     * @return 下载 URL
     */
    public static String mavenUrl(String artifact, String version) {
        String[] parts = artifact.split(":");
        String groupPath = parts[0].replace('.', '/');
        String artifactId = parts[1];
        return MAVEN_CENTRAL + groupPath + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".jar";
    }

    /**
     * 自动下载驱动 jar 到驱动目录
     *
     * @param dbType  数据库类型
     * @param version 版本
     * @return 保存后的 jar 文件
     */
    public File downloadDriver(String dbType, String version) {
        DbTypeInfo info = DbTypeRegistry.of(dbType);
        if (info == null || StrUtil.isBlank(info.getArtifact())) {
            throw new IllegalArgumentException("数据库类型不存在或未配置 Maven 坐标: " + dbType);
        }
        String ver = StrUtil.blankToDefault(version, info.getDefaultVersion());
        String url = mavenUrl(info.getArtifact(), ver);
        File dir = getDriverDir();
        String jarName = (info.getCode() + "-" + ver + ".jar").toLowerCase();
        File target = new File(dir, jarName);
        if (target.exists() && target.length() > 0) {
            return target;
        }
        log.info("开始下载驱动: {} -> {}", info.getCode(), url);
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new RuntimeException("驱动下载失败, HTTP " + code + " : " + url);
            }
            try (InputStream in = conn.getInputStream()) {
                Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                conn.disconnect();
            }
            log.info("驱动下载完成: {} ({} bytes)", target.getAbsolutePath(), target.length());
            // 清理该类型缓存, 下次重新加载
            loaderCache.remove(info.getCode().toUpperCase());
            registered.remove(info.getCode().toUpperCase());
            return target;
        } catch (Exception e) {
            log.error("驱动下载失败: {}", e.getMessage());
            throw new RuntimeException("驱动下载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 保存手动上传的驱动 jar
     *
     * @param dbType      数据库类型
     * @param version     版本
     * @param inputStream 文件流
     * @param originalName 原文件名
     * @return 保存后的 jar 文件
     */
    public File saveUploadedDriver(String dbType, String version, InputStream inputStream, String originalName) {
        File dir = getDriverDir();
        String ver = StrUtil.blankToDefault(version, "custom");
        String jarName = (StrUtil.blankToDefault(dbType, "unknown") + "-" + ver + ".jar").toLowerCase();
        File target = new File(dir, jarName);
        try {
            Files.copy(inputStream, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            loaderCache.remove(dbType.toUpperCase());
            registered.remove(dbType.toUpperCase());
            return target;
        } catch (Exception e) {
            throw new RuntimeException("驱动保存失败: " + e.getMessage(), e);
        }
    }

    /**
     * 扫描驱动目录, 返回全部 jar 文件
     */
    public static List<File> listDriverJars() {
        File dir = getDriverDir();
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
        List<File> list = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                list.add(f);
            }
        }
        return list;
    }

    /**
     * 加载外部驱动并注册到 DriverManager
     * <p>内置驱动(位于应用 classpath)直接返回 true; 外部驱动需从驱动目录 jar 加载</p>
     *
     * @param dbType 数据库类型
     * @return 是否可用
     */
    public boolean ensureDriver(String dbType) {
        if (StrUtil.isBlank(dbType)) {
            return false;
        }
        String key = dbType.toUpperCase();
        DbTypeInfo info = DbTypeRegistry.of(key);
        if (info == null) {
            return false;
        }
        // 内置驱动(已打入应用 jar)直接可用
        if (Boolean.TRUE.equals(info.getBuiltin())) {
            try {
                Class.forName(info.getDriverClass());
                return true;
            } catch (ClassNotFoundException e) {
                // 内置声明但 classpath 缺失, 走外部加载
            }
        }
        // 尝试从外部驱动目录加载
        if (Boolean.TRUE.equals(registered.get(key))) {
            return true;
        }
        synchronized (this) {
            if (Boolean.TRUE.equals(registered.get(key))) {
                return true;
            }
            try {
                URLClassLoader loader = buildLoader(key);
                Class<?> cls = loader.loadClass(info.getDriverClass());
                Driver driver = (Driver) cls.getDeclaredConstructor().newInstance();
                // 注册到 DriverManager(该驱动由我们的 loader 加载, DriverManager 可代理)
                DriverManager.registerDriver(new DriverShim(driver));
                loaderCache.put(key, loader);
                registered.put(key, true);
                log.info("外部驱动加载成功: {} -> {}", key, info.getDriverClass());
                return true;
            } catch (Exception e) {
                log.warn("外部驱动加载失败 {}: {}", key, e.getMessage());
                return false;
            }
        }
    }

    /**
     * 判断某类型的 JDBC 驱动是否已就绪(内置/已注册/原生协议类型)
     */
    public boolean isDriverReady(String dbType) {
        if (StrUtil.isBlank(dbType)) {
            return false;
        }
        String key = dbType.toUpperCase();
        DbTypeInfo info = DbTypeRegistry.of(key);
        if (info == null) {
            return false;
        }
        // 内置驱动直接可用
        if (Boolean.TRUE.equals(info.getBuiltin())) {
            return true;
        }
        // driverClass 为空的类型(REST/RESP 原生协议)视为就绪
        if (StrUtil.isBlank(info.getDriverClass())) {
            return true;
        }
        return Boolean.TRUE.equals(registered.get(key));
    }

    /**
     * 构造某类型的外部驱动 ClassLoader(加载该类型匹配的 jar)
     */
    private URLClassLoader buildLoader(String dbType) throws Exception {
        List<File> jars = listDriverJars();
        List<URL> urls = new ArrayList<>();
        for (File jar : jars) {
            if (jar.getName().toLowerCase().startsWith(dbType.toLowerCase() + "-")) {
                urls.add(jar.toURI().toURL());
            }
        }
        if (urls.isEmpty()) {
            throw new IllegalStateException("未找到 " + dbType + " 的驱动 jar, 请先在驱动管理中下载或上传");
        }
        // 用线程上下文类加载器作为 parent, 保证外部驱动能访问应用 classpath 中的依赖(如 slf4j)
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        if (parent == null) {
            parent = DbDriverManager.class.getClassLoader();
        }
        return new URLClassLoader(urls.toArray(new URL[0]), parent);
    }

    /**
     * 删除驱动 jar 与缓存
     */
    public void deleteDriver(String dbType) {
        if (StrUtil.isBlank(dbType)) {
            return;
        }
        String key = dbType.toUpperCase();
        // 关闭 ClassLoader
        URLClassLoader loader = loaderCache.remove(key);
        if (loader != null) {
            try {
                loader.close();
            } catch (Exception ignored) {
            }
        }
        registered.remove(key);
        // 删除目录下匹配的 jar
        for (File jar : listDriverJars()) {
            if (jar.getName().toLowerCase().startsWith(key.toLowerCase() + "-")) {
                FileUtil.del(jar);
            }
        }
    }

    /**
     * 驱动代理(让外部 loader 加载的 Driver 注册到 DriverManager)
     */
    public static class DriverShim implements Driver {

        private final Driver delegate;

        public DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.sql.Connection connect(String url, java.util.Properties info) throws java.sql.SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws java.sql.SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info) throws java.sql.SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
    }
}
