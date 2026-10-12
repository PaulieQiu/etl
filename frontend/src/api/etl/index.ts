import request from '@/utils/request'

// ETL 任务
export function listEtlJob(query) {
  return request({ url: '/etl/job/list', method: 'get', params: query })
}

export function getEtlJob(id) {
  return request({ url: '/etl/job/' + id, method: 'get' })
}

// 目录树(DIR 节点)
export function listEtlJobDirTree() {
  return request({ url: '/etl/job/dirTree', method: 'get' })
}

// 查询某总任务的子任务列表
export function listEtlJobChildren(parentId) {
  return request({ url: '/etl/job/children/' + parentId, method: 'get' })
}

export function addEtlJob(data) {
  return request({ url: '/etl/job', method: 'post', data: data })
}

export function updateEtlJob(data) {
  return request({ url: '/etl/job', method: 'put', data: data })
}

export function delEtlJob(ids) {
  return request({ url: '/etl/job/' + ids, method: 'delete' })
}

export function previewEtlJob(data) {
  return request({ url: '/etl/job/preview', method: 'post', data: data })
}

// 预览转换 XML(按表单配置即时生成, 不落库)
export function previewEtlXml(data) {
  return request({ url: '/etl/job/previewXml', method: 'post', data: data })
}

// Kettle 可视化设计: 画布节点/连线 → Kettle 转换/作业 XML
export function designKettleXml(data) {
  return request({ url: '/etl/job/designXml', method: 'post', data: data })
}

export function runEtlJob(id) {
  return request({ url: '/etl/job/run/' + id, method: 'post' })
}

export function runSyncEtlJob(id) {
  return request({ url: '/etl/job/runSync/' + id, method: 'post' })
}

// 取消运行中的任务
export function cancelEtlJob(id) {
  return request({ url: '/etl/job/cancel/' + id, method: 'post' })
}

// 查询任务运行状态: RUNNING/IDLE
export function stateEtlJob(id) {
  return request({ url: '/etl/job/state/' + id, method: 'get' })
}

// 校验 cron 返回下次执行时间
export function cronNext(cron) {
  return request({ url: '/etl/job/cron/next', method: 'post', data: cron || '' })
}

// ETL 执行日志
export function listEtlJobLog(query) {
  return request({ url: '/etl/job/log/list', method: 'get', params: query })
}

export function getEtlJobLog(id) {
  return request({ url: '/etl/job/log/' + id, method: 'get' })
}

// 复用数据源管理
export function listAllConn() {
  return request({ url: '/dbtool/conn/listAll', method: 'get' })
}

export function listDatabases(connId) {
  return request({ url: '/dbtool/meta/databases', method: 'get', params: { connId } })
}

export function listTables(connId, schema) {
  return request({ url: '/dbtool/meta/tables', method: 'get', params: { connId, schema } })
}

export function listColumns(connId, schema, table) {
  return request({ url: '/dbtool/meta/columns', method: 'get', params: { connId, schema, table } })
}
