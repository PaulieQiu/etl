-- =====================================================================
-- 数据库工作台（dbtool）独立建库脚本
-- 目标数据库：MySQL 8.x
-- 用法：先建库 CREATE DATABASE dbtool DEFAULT CHARSET utf8mb4; 再执行本脚本
-- =====================================================================

CREATE DATABASE IF NOT EXISTS dbtool DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE dbtool;

-- 1. 数据源配置表
CREATE TABLE IF NOT EXISTS sys_db_conn (
    id              BIGINT       NOT NULL COMMENT '主键',
    conn_name       VARCHAR(100) NOT NULL COMMENT '连接名称',
    db_type         VARCHAR(20)  NOT NULL DEFAULT 'MYSQL' COMMENT '数据库类型',
    db_version      VARCHAR(50)  DEFAULT NULL COMMENT '数据库版本',
    access_mode     VARCHAR(16)  NOT NULL DEFAULT 'READ_WRITE' COMMENT '访问模式(READ_WRITE/READ_ONLY)',
    host            VARCHAR(100) NOT NULL COMMENT '主机地址',
    port            INT          NOT NULL DEFAULT 3306 COMMENT '端口',
    database_name   VARCHAR(100) DEFAULT NULL COMMENT '默认数据库',
    username        VARCHAR(100) NOT NULL COMMENT '用户名',
    password        VARCHAR(500) DEFAULT NULL COMMENT '密码(AES加密)',
    params          VARCHAR(500) DEFAULT NULL COMMENT '扩展参数',
    ssh_use_tunnel  CHAR(1)      DEFAULT '0' COMMENT '是否启用SSH隧道(0否 1是)',
    ssh_host        VARCHAR(100) DEFAULT NULL COMMENT 'SSH跳板机主机',
    ssh_port        INT          DEFAULT 22 COMMENT 'SSH跳板机端口',
    ssh_user        VARCHAR(100) DEFAULT NULL COMMENT 'SSH用户名',
    ssh_password    VARCHAR(500) DEFAULT NULL COMMENT 'SSH密码(AES加密)',
    status          CHAR(1)      DEFAULT '0' COMMENT '状态(0正常 1停用)',
    remark          VARCHAR(500) DEFAULT NULL COMMENT '备注',
    del_flag        CHAR(1)      DEFAULT '0' COMMENT '删除标志(0存在 1删除)',
    create_dept     BIGINT       DEFAULT NULL,
    create_by       BIGINT       DEFAULT NULL,
    create_time     DATETIME     DEFAULT NULL,
    update_by       BIGINT       DEFAULT NULL,
    update_time     DATETIME     DEFAULT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='数据库连接配置';

-- 2. 驱动管理表
CREATE TABLE IF NOT EXISTS sys_db_driver (
    id              BIGINT       NOT NULL COMMENT '主键',
    db_type         VARCHAR(20)  NOT NULL COMMENT '数据库类型',
    driver_name     VARCHAR(200) DEFAULT NULL COMMENT '驱动名称(artifact)',
    driver_version  VARCHAR(50)  DEFAULT NULL COMMENT '驱动版本',
    driver_class    VARCHAR(200) DEFAULT NULL COMMENT '驱动类名',
    jar_path        VARCHAR(500) DEFAULT NULL COMMENT 'jar文件路径',
    source          CHAR(1)      DEFAULT '2' COMMENT '来源: 1内置 2自动下载 3手动上传',
    file_size       BIGINT       DEFAULT NULL COMMENT '文件大小(字节)',
    status          CHAR(1)      DEFAULT '0' COMMENT '状态(0正常 1停用)',
    remark          VARCHAR(500) DEFAULT NULL COMMENT '备注',
    del_flag        CHAR(1)      DEFAULT '0' COMMENT '删除标志(0存在 1删除)',
    create_dept     BIGINT       DEFAULT NULL,
    create_by       BIGINT       DEFAULT NULL,
    create_time     DATETIME     DEFAULT NULL,
    update_by       BIGINT       DEFAULT NULL,
    update_time     DATETIME     DEFAULT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='数据库驱动管理';

-- 3. SQL 执行历史（服务端化，替代浏览器 localStorage）
CREATE TABLE IF NOT EXISTS dbtool_sql_history (
    id            BIGINT       NOT NULL COMMENT '主键',
    user_id       BIGINT       DEFAULT NULL COMMENT '归属用户ID',
    conn_id       BIGINT       DEFAULT NULL COMMENT '连接ID',
    conn_name     VARCHAR(100) DEFAULT NULL COMMENT '连接名(冗余)',
    schema_name   VARCHAR(100) DEFAULT NULL COMMENT 'Schema',
    sql_text      LONGTEXT     COMMENT 'SQL文本',
    status        VARCHAR(20)  DEFAULT NULL COMMENT '状态 SUCCESS/FAILED/CANCELLED/TIMED_OUT',
    rows_affected BIGINT       DEFAULT NULL,
    duration_ms   BIGINT       DEFAULT NULL,
    error_msg     VARCHAR(1000) DEFAULT NULL,
    executed_at   DATETIME     DEFAULT NULL,
    create_dept   BIGINT       DEFAULT NULL,
    create_by     BIGINT       DEFAULT NULL,
    create_time   DATETIME     DEFAULT NULL,
    update_by     BIGINT       DEFAULT NULL,
    update_time   DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_user_conn (user_id, conn_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='SQL执行历史';

-- 4. 已保存 SQL 脚本
CREATE TABLE IF NOT EXISTS dbtool_sql_script (
    id            BIGINT       NOT NULL COMMENT '主键',
    user_id       BIGINT       DEFAULT NULL COMMENT '归属用户ID',
    title         VARCHAR(200) DEFAULT NULL COMMENT '脚本名称',
    sql_text      LONGTEXT     COMMENT 'SQL文本',
    conn_id       BIGINT       DEFAULT NULL,
    conn_name     VARCHAR(100) DEFAULT NULL,
    schema_name   VARCHAR(100) DEFAULT NULL,
    share_scope   VARCHAR(20)  DEFAULT 'PRIVATE',
    del_flag      CHAR(1)      DEFAULT '0',
    create_dept   BIGINT       DEFAULT NULL,
    create_by     BIGINT       DEFAULT NULL,
    create_time   DATETIME     DEFAULT NULL,
    update_by     BIGINT       DEFAULT NULL,
    update_time   DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='已保存SQL脚本';

-- 5. 内置驱动种子数据（MySQL/PostgreSQL 等常用驱动由 Maven 依赖自带，无需上传）
-- 其余类型驱动可在「驱动管理」页通过「自动下载」或「手动上传」注册。
