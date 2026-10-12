# ETL OpenSource · 数据集成同步工具

> 基于 [RuoYi-Vue-Plus](https://gitee.com/dromara/RuoYi-Vue-Plus) 与 **豆包大模型** 联合开发的轻量级数据集成 ETL 平台，支持可视化配置「同步任务」与「执行日志」查看，可独立部署运行。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Spring Boot 3.2](https://img.shields.io/badge/Spring%20Boot-3.2-green)
![Vue 3 + Vite](https://img.shields.io/badge/Vue-3%20+%20Vite-brightgreen)

---

## 一、项目简介

本项目从 RuoYi-Vue-Plus 单体工程中抽取 `ruoyi-etl` 与 `ruoyi-dbtool` 两个模块，剥离对 RuoYi 全家桶（SnailJob、Redisson 集群、Sa-Token 鉴权体系、多租户）的强耦合，替换为**轻量 shim**，形成一个开箱即用的独立开源项目。

核心功能：

- **数据源管理**：MySQL / PostgreSQL / Oracle / SQLServer / ClickHouse / MongoDB / Redis / ElasticSearch 等十余种数据源连接配置（含 SSH 隧道）。
- **同步任务**：可视化配置源表 → 目标表的同步任务，支持：
  - 单表同步、多源 JOIN / UNION ALL、父子任务 DAG 调度
  - DataX / Kettle 双引擎自动选择
  - 增量字段抽取、失败重试、并行/串行执行
  - Cron 定时调度（内置扫描器，无需额外调度中心）
  - 邮件告警（SMTP 可插拔）
- **执行日志**：每次执行生成任务日志 + 节点级日志（数据源节点 / 表节点 / 清洗规则 / 质量规则），支持失败原因、行数、耗时排查。

---

## 二、技术架构

```
┌──────────────────────────────────────────────────────────────┐
│  前端  Vue 3 + Vite + Element Plus + Pinia                  │
│        (views/etl/job, views/etl/log, views/dbtool/conn)    │
└──────────────────────────┬───────────────────────────────────┘
                           │  HTTP / JSON (axios)
┌──────────────────────────┴───────────────────────────────────┐
│  后端  Spring Boot 3.2 + MyBatis-Plus 3.5 + Sa-Token 1.38  │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ pan.dromara.etl                                       │ │
│  │  ├─ controller/EtlJobController    同步任务 CRUD/执行   │ │
│  │  ├─ controller/EtlFileController   文件桥接(Excel/API)  │ │
│  │  ├─ service/EtlJobServiceImpl      任务编排/调度/日志    │ │
│  │  ├─ service/DataXService          DataX 引擎执行       │ │
│  │  ├─ service/KettleService         Kettle 引擎执行      │ │
│  │  └─ task/EtlScheduleTask          Cron 扫描器(30s)     │ │
│  ├────────────────────────────────────────────────────────┤ │
│  │ pan.dromara.dbtool (连接管理底座)                       │ │
│  │  ├─ DbConn / DbDriver / DbConnectionManager           │ │
│  │  └─ DbTypeRegistry / DbDialect                          │ │
│  ├────────────────────────────────────────────────────────┤ │
│  │ org.dromara.common.*  (RuoYi 兼容 shim, 仅 ~10 个类)   │ │
│  │  R / PageResult / ServiceException / BaseEntity /      │ │
│  │  BaseMapperPlus / BaseController / @Log / MailBuilder │ │
│  └────────────────────────────────────────────────────────┘ │
└──────────────────────────┬───────────────────────────────────┘
                           │
        ┌──────────────────┼──────────────────┐
        ▼                  ▼                  ▼
   MySQL (元数据库)   Redis (缓存/会话)   DataX / Kettle (外部引擎进程)
```

### 主要依赖

| 层 | 技术 | 版本 |
|---|---|---|
| 运行时 | JDK | 17+ |
| 后端框架 | Spring Boot | 3.2.5 |
| ORM | MyBatis-Plus | 3.5.5 |
| 鉴权 | Sa-Token | 1.38.0 |
| 工具 | Hutool / Lombok | 最新 |
| 前端框架 | Vue | 3.x |
| 构建 | Vite | 5.x |
| UI | Element Plus | 2.x |
| 编辑器 | Monaco Editor | 0.50 |

---

## 三、目录结构

```
etl-opensource/
├── backend/                       # Spring Boot 后端
│   ├── pom.xml
│   └── src/main/
│       ├── java/
│       │   ├── pan/dromara/
│       │   │   ├── dbtool/        # 数据源管理底座(连接池/方言/驱动)
│       │   │   └── etl/          # ETL 核心: 任务/日志/调度/引擎
│       │   └── org/dromara/common/ # RuoYi 兼容 shim
│       └── resources/
│           └── application.yml
├── frontend/                      # Vue 3 + Vite 前端
│   ├── package.json
│   └── src/
│       ├── api/{dbtool,etl}/
│       └── views/
│           ├── dbtool/conn/       # 数据源管理
│           └── etl/
│               ├── job/          # 同步任务(列表/表单/设计器)
│               └── log/           # 执行日志
├── sql/
│   ├── schema.sql                 # dbtool 元数据表
│   └── etl.sql                    # etl_job / etl_job_log 表
├── README.md
└── LICENSE                        # MIT
```

---

## 四、快速开始

### 4.1 环境要求

- JDK 17+
- Maven 3.8+
- Node.js 18+
- MySQL 8.0+
- Redis 6.0+（可选，未配置时自动降级为本地缓存）
- （可选）DataX / Kettle 引擎：仅当需要真实执行任务时安装；仅做任务配置与日志查看可不装。

### 4.2 初始化数据库

```bash
# 创建元数据库
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS etl DEFAULT CHARSET utf8mb4;"

# 导入表结构
mysql -uroot -p etl < sql/schema.sql
mysql -uroot -p etl < sql/etl.sql
```

### 4.3 启动后端

```bash
cd backend

# 修改 src/main/resources/application.yml 中的 MySQL / Redis 连接信息
# 默认:
#   spring.datasource.url = jdbc:mysql://127.0.0.1:3306/etl
#   spring.data.redis.host = 127.0.0.1

mvn spring-boot:run
# 后端默认监听 http://127.0.0.1:8080
```

### 4.4 启动前端

```bash
cd frontend
npm install
npm run dev
# 前端默认监听 http://127.0.0.1:5173
```

浏览器打开 http://127.0.0.1:5173 即可。

---

## 五、使用流程

1. **配置数据源**：进入「数据源管理」，新增一个 MySQL 连接（如本地业务库）。
2. **新建同步任务**：进入「同步任务」，点击「新增」：
   - 选择源数据源 + 源表
   - 选择目标数据源 + 目标表
   - 配置增量字段、Cron 表达式（可选）、失败重试次数
3. **执行任务**：在任务列表点击「执行」，或等待 Cron 自动触发。
4. **查看日志**：进入「执行日志」，按任务 / 状态 / 时间筛选，点开单条日志可查看完整日志文本、错误堆栈、节点明细。

---

## 六、致谢

本项目由以下开源项目与 AI 协作共建：

- **[RuoYi-Vue-Plus](https://gitee.com/dromara/RuoYi-Vue-Plus)** — 基于 Spring Boot 3 + Vue 3 的后台管理框架，本项目抽取其 `ruoyi-etl`、`ruoyi-dbtool` 模块并做独立化改造。
- **[豆包大模型](https://www.doubao.com/)** — 在模块抽取、shim 编写、README 撰写、问题排查全过程中承担主要代码生成与调试工作。

> 本项目仅作学习与内部工具使用，DataX / Kettle 外部引擎的版权归其各自所有者。

---

## 七、License

[MIT](LICENSE)
