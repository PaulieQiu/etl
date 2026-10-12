-- =====================================================================
-- ETL 集成模块(DataX + Kettle) 建表 + 菜单 SQL
-- 数据库：ry-vue
-- 说明：数据源复用 sys_db_conn(数据库管理工具), 引擎为外部进程(DataX/Kettle)
-- =====================================================================

-- 1. ETL 任务表
CREATE TABLE IF NOT EXISTS etl_job (
    id            BIGINT       NOT NULL COMMENT '主键',
    job_name      VARCHAR(100) NOT NULL COMMENT '任务名称',
    job_type      VARCHAR(20)  NOT NULL DEFAULT 'DATAX' COMMENT '任务类型(DATAX/KETTLE)',
    src_conn_id   BIGINT       DEFAULT NULL COMMENT '源数据源(引用sys_db_conn.id, 源类型TABLE时)',
    src_type      VARCHAR(16)  DEFAULT 'TABLE' COMMENT '源类型(TABLE/EXCEL/API)',
    src_schema    VARCHAR(100) DEFAULT NULL COMMENT '源库/模式',
    src_table     VARCHAR(200) DEFAULT NULL COMMENT '源表',
    src_where     VARCHAR(1000) DEFAULT NULL COMMENT '源过滤条件(where)',
    src_excel_path TEXT        DEFAULT NULL COMMENT '源Excel文件路径',
    src_excel_sheet VARCHAR(100) DEFAULT NULL COMMENT '源Excel工作表',
    src_excel_header INT       DEFAULT 0 COMMENT '源Excel表头行(0=首行)',
    src_api_url   TEXT         DEFAULT NULL COMMENT '源API地址',
    src_api_method VARCHAR(10)  DEFAULT 'GET' COMMENT '源API方法(GET/POST)',
    src_api_headers TEXT        DEFAULT NULL COMMENT '源API请求头JSON',
    src_api_body  TEXT         DEFAULT NULL COMMENT '源API请求体',
    src_api_data_path VARCHAR(200) DEFAULT NULL COMMENT '源API数据路径(JSON数组指针, 如data.list, 空=根数组)',
    src_api_page_field VARCHAR(100) DEFAULT NULL COMMENT '源API分页参数名(页码/游标)',
    src_api_page_size INT       DEFAULT 0 COMMENT '源API每页大小(0不分页)',
    src_api_auth   TEXT         DEFAULT NULL COMMENT '源API鉴权配置JSON(NONE/STATIC/TOKEN)',
    dst_conn_id   BIGINT       DEFAULT NULL COMMENT '目标数据源(引用sys_db_conn.id, 目标类型TABLE时)',
    dst_type      VARCHAR(16)  DEFAULT 'TABLE' COMMENT '目标类型(TABLE/EXCEL/API)',
    dst_schema    VARCHAR(100) DEFAULT NULL COMMENT '目标库/模式',
    dst_table     VARCHAR(200) DEFAULT NULL COMMENT '目标表',
    dst_excel_path TEXT        DEFAULT NULL COMMENT '目标Excel文件路径',
    dst_excel_sheet VARCHAR(100) DEFAULT NULL COMMENT '目标Excel工作表',
    dst_api_url   TEXT         DEFAULT NULL COMMENT '目标API地址(支持${字段}占位)',
    dst_api_method VARCHAR(10)  DEFAULT 'POST' COMMENT '目标API方法(POST/PUT)',
    dst_api_headers TEXT        DEFAULT NULL COMMENT '目标API请求头JSON',
    dst_api_body_tpl TEXT       DEFAULT NULL COMMENT '目标API请求体模板(${字段}占位)',
    dst_api_auth   TEXT         DEFAULT NULL COMMENT '目标API鉴权配置JSON(NONE/STATIC/TOKEN)',
    dst_write_mode VARCHAR(20) DEFAULT 'INSERT' COMMENT '写入模式(INSERT/REPLACE/TRUNCATE, TABLE目标)',
    field_mapping TEXT         DEFAULT NULL COMMENT '字段映射JSON(缺省按同名自动)',
    speed_channel INT          DEFAULT 3 COMMENT '并发通道数',
    speed_byte    BIGINT       DEFAULT 1048576 COMMENT '限速(字节/秒, 0不限)',
    batch_size    INT          DEFAULT 1024 COMMENT '批大小',
    job_json      LONGTEXT     DEFAULT NULL COMMENT '生成的DataX配置JSON(缓存)',
    kettle_type   VARCHAR(16)  DEFAULT 'TRANS' COMMENT 'Kettle任务类型(TRANS转换/JOB作业)',
    kettle_xml    LONGTEXT     DEFAULT NULL COMMENT 'Kettle转换/作业XML定义',
    status        CHAR(1)      DEFAULT '0' COMMENT '状态(0正常 1停用)',
    remark        VARCHAR(500) DEFAULT NULL COMMENT '备注',
    del_flag      CHAR(1)      DEFAULT '0' COMMENT '删除标志(0存在 1删除)',
    create_dept   BIGINT       DEFAULT NULL,
    create_by     BIGINT       DEFAULT NULL,
    create_time   DATETIME     DEFAULT NULL,
    update_by     BIGINT       DEFAULT NULL,
    update_time   DATETIME     DEFAULT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COMMENT ='ETL任务配置';

-- 2. ETL 执行日志表
CREATE TABLE IF NOT EXISTS etl_job_log (
    id            BIGINT       NOT NULL COMMENT '主键',
    job_id        BIGINT       NOT NULL COMMENT '任务ID',
    run_status    VARCHAR(20)  DEFAULT 'RUNNING' COMMENT '执行状态(RUNNING/SUCCESS/FAILED)',
    start_time    DATETIME     DEFAULT NULL COMMENT '开始时间',
    end_time      DATETIME     DEFAULT NULL COMMENT '结束时间',
    total_records BIGINT       DEFAULT NULL COMMENT '读写总条数',
    read_speed    VARCHAR(50)  DEFAULT NULL COMMENT '读取速度(条/秒)',
    write_speed   VARCHAR(50)  DEFAULT NULL COMMENT '写入速度(条/秒)',
    log_text      LONGTEXT     DEFAULT NULL COMMENT '引擎输出日志',
    error_msg     TEXT         DEFAULT NULL COMMENT '错误信息',
    create_time   DATETIME     DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COMMENT ='ETL任务执行日志';

-- 3. 菜单（一级：数据集成ETL；子级：同步任务、执行日志）
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark)
VALUES (1761400000000030000, '数据集成ETL', 0, 8, 'etl', '', '', 'Y', 'Y', 'M', '0', '0', '', 'download', 103, 1, sysdate(), '数据集成ETL目录');

INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark)
VALUES (1761400000000030001, '同步任务', 1761400000000030000, 1, 'job', 'etl/job/index', '', 'Y', 'Y', 'C', '0', '0', 'etl:job:list', 'job', 103, 1, sysdate(), 'ETL同步任务管理');

INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark)
VALUES (1761400000000030002, '执行日志', 1761400000000030000, 2, 'log', 'etl/log/index', '', 'Y', 'Y', 'C', '0', '0', 'etl:log:list', 'log', 103, 1, sysdate(), 'ETL执行日志');
