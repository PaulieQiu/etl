import request from '@/utils/request'

// ============ 数据库连接 ============
// 分页查询数据库连接
export function listDbConn(query) {
  return request({
    url: '/dbtool/conn/list',
    method: 'get',
    params: query
  })
}

// 查询全部连接(工作台选择)
export function listAllDbConn() {
  return request({
    url: '/dbtool/conn/listAll',
    method: 'get'
  })
}

// 查询连接详情
export function getDbConn(id) {
  return request({
    url: `/dbtool/conn/${id}`,
    method: 'get'
  })
}

// 新增连接
export function addDbConn(data) {
  return request({
    url: '/dbtool/conn',
    method: 'post',
    data: data
  })
}

// 修改连接
export function updateDbConn(data) {
  return request({
    url: '/dbtool/conn',
    method: 'put',
    data: data
  })
}

// 删除连接
export function delDbConn(ids) {
  return request({
    url: `/dbtool/conn/${ids}`,
    method: 'delete'
  })
}

// 测试连接
export function testDbConn(data) {
  return request({
    url: '/dbtool/conn/test',
    method: 'post',
    data: data
  })
}

// 关闭连接池
export function closeDbConn(id) {
  return request({
    url: `/dbtool/conn/close/${id}`,
    method: 'get'
  })
}

// ============ 数据库元数据/工作台 ============
// 数据库列表
export function listDatabases(connId) {
  return request({
    url: '/dbtool/meta/databases',
    method: 'get',
    params: { connId }
  })
}

// 表列表
export function listTables(connId, schema) {
  return request({
    url: '/dbtool/meta/tables',
    method: 'get',
    params: { connId, schema }
  })
}

// 列结构
export function listColumns(connId, schema, table) {
  return request({
    url: '/dbtool/meta/columns',
    method: 'get',
    params: { connId, schema, table }
  })
}

// 索引列表
export function listIndexes(connId, schema, table) {
  return request({
    url: '/dbtool/meta/indexes',
    method: 'get',
    params: { connId, schema, table }
  })
}

// 约束列表
export function listConstraints(connId, schema, table) {
  return request({
    url: '/dbtool/meta/constraints',
    method: 'get',
    params: { connId, schema, table }
  })
}

// 外键列表(本表引用其他表)
export function listForeignKeys(connId, schema, table) {
  return request({
    url: '/dbtool/meta/foreignKeys',
    method: 'get',
    params: { connId, schema, table }
  })
}

// 引用列表(其他表引用本表)
export function listReferences(connId, schema, table) {
  return request({
    url: '/dbtool/meta/references',
    method: 'get',
    params: { connId, schema, table }
  })
}

// 触发器列表
export function listTriggers(connId, schema, table) {
  return request({
    url: '/dbtool/meta/triggers',
    method: 'get',
    params: { connId, schema, table }
  })
}

// 建表DDL
export function getTableDdl(connId, schema, table) {
  return request({
    url: '/dbtool/meta/ddl',
    method: 'get',
    params: { connId, schema, table }
  })
}

// 创建数据库
export function createDatabase(data) {
  return request({
    url: '/dbtool/meta/createDatabase',
    method: 'post',
    data: data
  })
}

// 删除表
export function dropTable(data) {
  return request({
    url: '/dbtool/meta/dropTable',
    method: 'post',
    data: data
  })
}

// 为删除表/列签发一次性后端确认凭证
export function issueStructureChangeConfirmation(data) {
  return request({
    url: '/dbtool/meta/structure-changes/confirmation',
    method: 'post',
    data: data
  })
}

// 重命名表
export function renameTable(data) {
  return request({
    url: '/dbtool/meta/renameTable',
    method: 'post',
    data: data
  })
}

// 新增列
export function addColumn(data) {
  return request({
    url: '/dbtool/meta/addColumn',
    method: 'post',
    data: data
  })
}

// 删除列
export function dropColumn(params) {
  return request({
    url: '/dbtool/meta/dropColumn',
    method: 'delete',
    params: params
  })
}

// 修改列
export function modifyColumn(data) {
  return request({
    url: '/dbtool/meta/modifyColumn',
    method: 'post',
    data: data
  })
}

// 执行DDL
export function executeDdl(data) {
  return request({
    url: '/dbtool/meta/executeDdl',
    method: 'post',
    data: data
  })
}

// 查询数据
export function queryDbData(data) {
  return request({
    url: '/dbtool/meta/query',
    method: 'post',
    data: data
  })
}

// 执行SQL
export function executeSql(data) {
  return request({
    url: '/dbtool/meta/execute',
    method: 'post',
    data: data
  })
}

// 取消当前用户正在执行的 JDBC 查询
export function cancelSqlExecution(executionId) {
  return request({
    url: '/dbtool/meta/cancel',
    method: 'post',
    data: { executionId }
  })
}

// 对比源端与目标端表/列结构
export function compareDbSchemas(data) {
  return request({
    url: '/dbtool/meta/schema-diff/compare',
    method: 'post',
    data: data
  })
}

// 生成仅供人工复核的 Schema 迁移脚本预览
export function previewDbSchemaMigration(data) {
  return request({
    url: '/dbtool/meta/schema-diff/preview',
    method: 'post',
    data: data
  })
}

// 预览并生成一次性确认凭证(结果网格变更)
export function previewGridChanges(data) {
  return request({
    url: '/dbtool/meta/grid-changes/preview',
    method: 'post',
    data: data
  })
}

// 原子提交已预览的结果网格变更
export function commitGridChanges(data) {
  return request({
    url: '/dbtool/meta/grid-changes/commit',
    method: 'post',
    data: data
  })
}

// ============ 服务端网格查询(P2) ============
// 服务端过滤/排序/分页: {connId, sql, pageNum, pageSize, filters:[{column,op,value}], sort:{column,order}}
export function queryGrid(data) {
  return request({
    url: '/dbtool/grid/query',
    method: 'post',
    data: data
  })
}

// ============ 备份/恢复(P4) ============
// 启动备份任务
export function backupDump(data) {
  return request({
    url: '/dbtool/backup/dump',
    method: 'post',
    data: data
  })
}

// 恢复确认凭证(一次性)
export function backupConfirmation(data) {
  return request({
    url: '/dbtool/backup/confirmation',
    method: 'post',
    data: data
  })
}

// 启动恢复任务
export function backupRestore(data) {
  return request({
    url: '/dbtool/backup/restore',
    method: 'post',
    data: data
  })
}

// 任务列表
export function backupJobs(params) {
  return request({
    url: '/dbtool/backup/jobs',
    method: 'get',
    params: params
  })
}

// 取消任务
export function backupCancel(id) {
  return request({
    url: `/dbtool/backup/cancel/${id}`,
    method: 'post'
  })
}

// 下载备份文件(blob)
export function backupDownload(id) {
  return request({
    url: `/dbtool/backup/download/${id}`,
    method: 'get',
    responseType: 'blob'
  })
}

// ============ 连接配置导入(P4) ============
// 解析导入文件, 返回候选连接列表
export function connImportPreview(data) {
  return request({
    url: '/dbtool/conns/import/preview',
    method: 'post',
    data: data
  })
}

// 按选择项导入连接
export function connImportApply(data) {
  return request({
    url: '/dbtool/conns/import/apply',
    method: 'post',
    data: data
  })
}

// ============ 数据对比与同步(P3) ============
// 数据对比预览(只读)
export function compareDataPreview(data) {
  return request({
    url: '/dbtool/compare/preview',
    method: 'post',
    data: data
  })
}

// 数据同步确认凭证(一次性)
export function compareDataConfirmation(data) {
  return request({
    url: '/dbtool/compare/confirmation',
    method: 'post',
    data: data
  })
}

// 分批应用同步
export function compareDataApply(data) {
  return request({
    url: '/dbtool/compare/apply',
    method: 'post',
    data: data
  })
}

// ============ 大结果导出(P2) ============
// 导出预览: 列信息 + 估算行数
export function exportPreview(data) {
  return request({
    url: '/dbtool/export/preview',
    method: 'post',
    data: data
  })
}

// 流式导出下载(返回 Blob)
export function exportDownload(data) {
  return request({
    url: '/dbtool/export/download',
    method: 'post',
    data: data,
    responseType: 'blob'
  })
}

// ============ 事务会话(P2) ============
// 开启事务会话
export function txnBegin(data) {
  return request({
    url: '/dbtool/txn/begin',
    method: 'post',
    data: data
  })
}

// 事务内执行单条 SQL
export function txnExecute(data) {
  return request({
    url: '/dbtool/txn/execute',
    method: 'post',
    data: data
  })
}

// 提交事务
export function txnCommit(data) {
  return request({
    url: '/dbtool/txn/commit',
    method: 'post',
    data: data
  })
}

// 回滚事务
export function txnRollback(data) {
  return request({
    url: '/dbtool/txn/rollback',
    method: 'post',
    data: data
  })
}

// 当前用户活动事务会话列表
export function txnList() {
  return request({
    url: '/dbtool/txn/list',
    method: 'get'
  })
}

// 关闭事务会话
export function txnClose(sessionId) {
  return request({
    url: `/dbtool/txn/${sessionId}`,
    method: 'delete'
  })
}

// ============ SQL 历史与脚本库(P1 服务端化) ============
// 记录一条执行历史
export function recordSqlHistory(data) {
  return request({
    url: '/dbtool/sql/history/record',
    method: 'post',
    data: data
  })
}

// 分页查询执行历史
export function listSqlHistory(query) {
  return request({
    url: '/dbtool/sql/history/list',
    method: 'get',
    params: query
  })
}

// 删除执行历史
export function delSqlHistory(id) {
  return request({
    url: `/dbtool/sql/history/${id}`,
    method: 'delete'
  })
}

// 保存脚本(无 id 新增, 有 id 更新)
export function saveSqlScript(data) {
  return request({
    url: '/dbtool/sql/script/save',
    method: 'post',
    data: data
  })
}

// 查询已保存脚本
export function listSqlScripts(keyword) {
  return request({
    url: '/dbtool/sql/script/list',
    method: 'get',
    params: keyword ? { keyword } : {}
  })
}

// 删除脚本
export function delSqlScript(id) {
  return request({
    url: `/dbtool/sql/script/${id}`,
    method: 'delete'
  })
}

// ============ 数据库类型 ============
// 数据库类型清单(分类/图标/端口/驱动/版本)
export function listDbTypes() {
  return request({
    url: '/dbtool/type/list',
    method: 'get'
  })
}

// ============ 数据库驱动管理 ============
// 分页查询驱动列表
export function listDbDriver(query) {
  return request({
    url: '/dbtool/driver/list',
    method: 'get',
    params: query
  })
}

// 查询全部驱动
export function listAllDbDriver() {
  return request({
    url: '/dbtool/driver/listAll',
    method: 'get'
  })
}

// 自动下载驱动
export function downloadDbDriver(dbType, version) {
  return request({
    url: '/dbtool/driver/download',
    method: 'post',
    params: { dbType, version }
  })
}

// 手动上传驱动
export function uploadDbDriver(data) {
  return request({
    url: '/dbtool/driver/upload',
    method: 'post',
    data: data,
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

// 编辑驱动
export function updateDbDriver(data) {
  return request({
    url: '/dbtool/driver',
    method: 'put',
    data: data
  })
}

// 删除驱动
export function delDbDriver(ids) {
  return request({
    url: `/dbtool/driver/${ids}`,
    method: 'delete'
  })
}

// ============ Redis 可视化管理 ============
// 键列表(SCAN)
export function redisKeys(connId, db, pattern, cursor, count) {
  return request({
    url: '/dbtool/redis/keys',
    method: 'get',
    params: { connId, db, pattern, cursor, count }
  })
}

// 键信息
export function redisKeyInfo(connId, db, key) {
  return request({
    url: '/dbtool/redis/keyInfo',
    method: 'get',
    params: { connId, db, key }
  })
}

// 键值读取
export function redisValue(connId, db, key, type) {
  return request({
    url: '/dbtool/redis/value',
    method: 'get',
    params: { connId, db, key, type }
  })
}

// 键写入
export function redisSet(data) {
  return request({
    url: '/dbtool/redis/set',
    method: 'post',
    data: data
  })
}

// 键删除
export function redisDel(data) {
  return request({
    url: '/dbtool/redis/del',
    method: 'post',
    data: data
  })
}

// TTL 更新
export function redisExpire(data) {
  return request({
    url: '/dbtool/redis/expire',
    method: 'post',
    data: data
  })
}

// INFO 信息
export function redisInfo(connId, db) {
  return request({
    url: '/dbtool/redis/info',
    method: 'get',
    params: { connId, db }
  })
}

// CLI 命令执行
export function redisCli(data) {
  return request({
    url: '/dbtool/redis/cli',
    method: 'post',
    data: data
  })
}

// ============ Elasticsearch 可视化管理 ============
// 索引列表
export function esIndices(connId, sys) {
  return request({
    url: '/dbtool/es/indices',
    method: 'get',
    params: { connId, sys: !!sys }
  })
}

// 集群信息(版本/健康/节点)
export function esCluster(connId) {
  return request({
    url: '/dbtool/es/cluster',
    method: 'get',
    params: { connId }
  })
}

// 索引详情(settings/mappings/aliases/统计)
export function esIndexInfo(connId, index) {
  return request({
    url: '/dbtool/es/indexInfo',
    method: 'get',
    params: { connId, index }
  })
}

// 创建索引
export function esCreateIndex(data) {
  return request({
    url: '/dbtool/es/createIndex',
    method: 'post',
    data: data
  })
}

// 删除索引
export function esDeleteIndex(data) {
  return request({
    url: '/dbtool/es/deleteIndex',
    method: 'post',
    data: data
  })
}

// 索引操作(refresh/flush/close/open/clearCache)
export function esIndexOp(data) {
  return request({
    url: '/dbtool/es/indexOp',
    method: 'post',
    data: data
  })
}

// 数据浏览(_search)
export function esSearch(data) {
  return request({
    url: '/dbtool/es/search',
    method: 'post',
    data: data
  })
}

// 单文档
export function esDoc(connId, index, id) {
  return request({
    url: '/dbtool/es/doc',
    method: 'get',
    params: { connId, index, id }
  })
}

// 新增/更新文档
export function esSaveDoc(data) {
  return request({
    url: '/dbtool/es/saveDoc',
    method: 'post',
    data: data
  })
}

// 删除文档
export function esDeleteDoc(params) {
  return request({
    url: '/dbtool/es/doc',
    method: 'delete',
    params: params
  })
}

// Dev Tools 通用 REST 代理
export function esConsole(data) {
  return request({
    url: '/dbtool/es/console',
    method: 'post',
    data: data
  })
}

// SQL 查询(ES REST SQL)
export function esSql(data) {
  return request({
    url: '/dbtool/es/sql',
    method: 'post',
    data: data
  })
}

// ============ 数据迁移 & Excel 导入 ============

// 跨库表数据迁移
export function migrateTable(data) {
  return request({
    url: '/dbtool/transfer/migrate',
    method: 'post',
    data: data
  })
}

// Excel 多工作表导入(multipart)
export function excelImport(params, file) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('params', JSON.stringify(params))
  return request({
    url: '/dbtool/transfer/excel-import',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
// 解析 Excel 工作表结构(名称/行数/预览)
export function excelSheets(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request({
    url: '/dbtool/transfer/excel-sheets',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

// ============ AI SQL 助手(P5) ============
// 生成 SQL(自然语言需求 → SQL; 只返回不执行)
export function aiAssist(data) {
  return request({
    url: '/dbtool/ai/assist',
    method: 'post',
    data: data
  })
}

// 解释 SQL(类型/涉及表/风险分级)
export function aiExplain(data) {
  return request({
    url: '/dbtool/ai/explain',
    method: 'post',
    data: data
  })
}

// 元数据上下文(库/表/列, 供提示)
export function aiContext(connId, schema) {
  return request({
    url: '/dbtool/ai/context',
    method: 'get',
    params: { connId: connId, schema: schema }
  })
}

// ============ MCP(P5) ============
// MCP JSON-RPC 调用(initialize/tools/list/tools/call)
export function mcpCall(data) {
  return request({
    url: '/dbtool/mcp',
    method: 'post',
    data: data
  })
}
