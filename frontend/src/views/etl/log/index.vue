<template>
  <div class="p-2 app-container etl-log-page">
    <div class="search-wrap">
      <el-card shadow="never" class="search-panel">
        <template #header>
          <div class="panel-heading">
            <div>

              <h3>执行日志</h3>
            </div>
          </div>
        </template>
        <el-form :model="queryParams" :inline="true" class="query-form" @submit.prevent>
          <el-form-item label="任务" prop="jobId">
            <el-tree-select
              v-model="queryParams.jobId"
              :data="jobTree"
              :props="{ label: 'jobName', value: 'id', children: 'children' }"
              check-strictly
              placeholder="选择任务(总/子)"
              clearable
              filterable
              style="width: 200px"
              @change="onJobChange"
            />
          </el-form-item>
          <el-form-item label="子任务" prop="subtaskId">
            <el-select v-model="queryParams.subtaskId" placeholder="全部子任务" clearable filterable style="width: 180px" @change="handleQuery">
              <el-option v-for="s in childJobs" :key="s.id" :label="s.jobName" :value="s.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="节点类型" prop="nodeType">
            <el-select v-model="queryParams.nodeType" placeholder="全部" clearable style="width: 150px" @change="handleQuery">
              <el-option v-for="t in nodeTypes" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="节点ID" prop="nodeId">
            <el-input v-model="queryParams.nodeId" placeholder="表名/规则名/数据源ID" clearable style="width: 160px" @keyup.enter="handleQuery" />
          </el-form-item>
          <el-form-item label="执行状态" prop="runStatus">
            <el-select v-model="queryParams.runStatus" placeholder="全部" clearable style="width: 120px" @change="handleQuery">
              <el-option label="执行中" value="RUNNING" />
              <el-option label="成功" value="SUCCESS" />
              <el-option label="失败" value="FAILED" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
            <el-button icon="Refresh" @click="resetQuery">重置</el-button>
          </el-form-item>
        </el-form>
      </el-card>
    </div>

    <el-card shadow="never" class="table-panel">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">

            <h3>执行历史</h3>
            <p>按 任务 → 子任务 → 数据源 → 表 → 清洗规则 → 质量规则 维度筛选各级执行日志。</p>
          </div>
          <div class="view-switch">
            <el-radio-group v-model="viewMode" size="small" @change="onViewModeChange">
              <el-radio-button value="table">平铺</el-radio-button>
              <el-radio-button value="tree">树形</el-radio-button>
            </el-radio-group>
          </div>
        </div>
      </template>

      <el-table v-if="viewMode === 'table'" v-loading="loading" border class="data-table" :data="logList" @row-dblclick="row => handleDetail(row)">
        <el-table-column label="日志ID" prop="id" width="170" align="center" />
        <el-table-column label="任务ID" prop="jobId" width="140" align="center" />
        <el-table-column label="子任务" prop="subtaskId" width="120" align="center">
          <template #default="{ row }">{{ row.subtaskId ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="节点类型" width="130" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.nodeType" :type="nodeTypeTag(row.nodeType)" size="small">{{ nodeTypeLabel(row.nodeType) }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="节点名称" prop="nodeName" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.nodeName || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" prop="runStatus" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.runStatus)" size="small">{{ row.runStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="总条数" prop="totalRecords" width="90" align="center">
          <template #default="{ row }">{{ row.totalRecords ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="开始时间" prop="startTime" width="170" align="center" />
        <el-table-column label="结束时间" prop="endTime" width="170" align="center">
          <template #default="{ row }">{{ row.endTime || '执行中...' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" align="center" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link icon="View" @click="handleDetail(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无执行日志, 双击行查看详情" />
        </template>
      </el-table>

      <div v-else class="log-tree-wrap">
        <el-tree
          v-loading="loading"
          :data="logTree"
          :props="{ label: 'label', children: 'children' }"
          node-key="id"
          default-expand-all
          highlight-current
          empty-text="暂无执行日志"
          @node-click="onTreeNodeClick"
        >
          <template #default="{ data }">
            <span v-if="data.log" class="tree-leaf">
              <el-tag v-if="data.log.nodeType" size="small" :type="nodeTypeTag(data.log.nodeType)" class="leaf-type">{{ nodeTypeLabel(data.log.nodeType) }}</el-tag>
              <span class="leaf-name" :title="data.label">{{ data.label }}</span>
              <el-tag size="small" :type="statusType(data.log.runStatus)" class="leaf-status">{{ data.log.runStatus }}</el-tag>
              <span class="leaf-time">{{ data.log.startTime }}</span>
            </span>
            <span v-else class="tree-group">{{ data.label }}</span>
          </template>
        </el-tree>
      </div>

      <pagination v-show="total > 0" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" :total="total" @pagination="getList" />
    </el-card>

    <!-- 日志详情弹窗 -->
    <el-dialog v-model="logOpen" title="执行日志详情" width="900px" append-to-body top="5vh" class="etl-log-dialog">
      <div v-if="logDetail" class="log-summary">
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="日志ID">{{ logDetail.id }}</el-descriptions-item>
          <el-descriptions-item label="任务ID">{{ logDetail.jobId }}</el-descriptions-item>
          <el-descriptions-item label="节点类型">
            <el-tag v-if="logDetail.nodeType" type="info" size="small">{{ nodeTypeLabel(logDetail.nodeType) }}</el-tag>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="节点名称">{{ logDetail.nodeName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="logStatusType" size="small">{{ logDetail.runStatus }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="总条数">{{ logDetail.totalRecords ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="开始时间">{{ logDetail.startTime }}</el-descriptions-item>
          <el-descriptions-item label="结束时间">{{ logDetail.endTime }}</el-descriptions-item>
        </el-descriptions>
      </div>
      <div class="log-box">
        <div class="log-box-head">
          <span class="log-box-title">执行日志</span>
          <el-button type="primary" size="small" plain icon="CopyDocument" @click="copyLog">复制日志</el-button>
        </div>
        <pre class="log-text">{{ logDetail?.logText || '暂无日志' }}</pre>
      </div>
      <div v-if="logDetail?.errorMsg" class="log-error">
        <div class="log-error-title">错误信息</div>
        <pre class="log-error-text">{{ logDetail.errorMsg }}</pre>
      </div>
      <template #footer>
        <el-button type="primary" @click="logOpen = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="EtlJobLog">
import { ref, computed, onMounted, getCurrentInstance } from 'vue'
import { listEtlJobLog, getEtlJobLog, listEtlJob } from '@/api/etl/index'

const { proxy } = getCurrentInstance()
const logList = ref([])
const loading = ref(true)
const total = ref(0)
const logOpen = ref(false)
const logDetail = ref(null)
const jobTree = ref([])
const childJobs = ref([])
const viewMode = ref('table')
const logTree = ref([])

const nodeTypes = [
  { label: '总任务', value: 'TASK' },
  { label: '子任务', value: 'SUBTASK' },
  { label: '数据源', value: 'DATA_SOURCE' },
  { label: '表', value: 'TABLE' },
  { label: '清洗规则', value: 'CLEAN_RULE' },
  { label: '质量规则', value: 'QUALITY_RULE' }
]

function nodeTypeLabel(t) {
  return nodeTypes.find(n => n.value === t)?.label || t
}
function nodeTypeTag(t) {
  return { TASK: 'warning', SUBTASK: 'primary', DATA_SOURCE: 'info', TABLE: 'success', CLEAN_RULE: '', QUALITY_RULE: 'danger' }[t] || 'info'
}
function statusType(s) {
  if (s === 'SUCCESS') return 'success'
  if (s === 'FAILED') return 'danger'
  return 'warning'
}

const queryParams = ref({ pageNum: 1, pageSize: 10, jobId: undefined, subtaskId: undefined, nodeType: undefined, nodeId: undefined, runStatus: undefined })

const logStatusType = computed(() => statusType(logDetail.value?.runStatus))

function getList() {
  loading.value = true
  listEtlJobLog(queryParams.value).then(res => {
    logList.value = res.data?.rows || []
    total.value = Number(res.data?.total || 0)
    loading.value = false
    if (viewMode.value === 'tree') buildTree()
  })
}

function loadJobs() {
  listEtlJob({ pageNum: 1, pageSize: 200 }).then(res => {
    jobTree.value = res.data?.rows || []
  })
}

function onJobChange(val) {
  childJobs.value = []
  queryParams.value.subtaskId = undefined
  if (!val) { handleQuery(); return }
  const found = jobTree.value.find(j => j.id === val)
  if (found && found.children?.length) {
    childJobs.value = found.children
  }
  handleQuery()
}

function resetQuery() {
  queryParams.value = { pageNum: 1, pageSize: 10, jobId: undefined, subtaskId: undefined, nodeType: undefined, nodeId: undefined, runStatus: undefined }
  childJobs.value = []
  handleQuery()
}
function handleQuery() { queryParams.value.pageNum = 1; getList() }

function handleDetail(row) {
  logDetail.value = null
  logOpen.value = true
  getEtlJobLog(row.id).then(res => { logDetail.value = res.data })
}

function onViewModeChange() {
  if (viewMode.value === 'tree') buildTree()
}

function buildTree() {
  const jobNameMap = {}
  jobTree.value.forEach(j => {
    jobNameMap[j.id] = j.jobName
    ;(j.children || []).forEach(c => { jobNameMap[c.id] = c.jobName })
  })
  const groups = new Map()
  logList.value.forEach(l => {
    const k = l.subtaskId || l.jobId || 'unknown'
    if (!groups.has(k)) {
      groups.set(k, { id: 'job-' + k, label: jobNameMap[k] || ('任务 ' + k), children: [] })
    }
    const g = groups.get(k)
    const nt = nodeTypeLabel(l.nodeType) || '其他'
    const gid = 'nt-' + nt + '-' + k
    let ng = g.children.find(c => c.id === gid)
    if (!ng) { ng = { id: gid, label: nt, children: [] }; g.children.push(ng) }
    ng.children.push({
      id: 'log-' + l.id,
      label: l.nodeName || l.nodeId || ('日志 ' + l.id),
      log: l
    })
  })
  logTree.value = [...groups.values()]
}

function onTreeNodeClick(data) {
  if (data.log) handleDetail(data.log)
}

function copyLog() {
  const t = logDetail.value?.logText || ''
  if (!t) { proxy.$modal.msgWarning('暂无日志内容'); return }
  const done = () => proxy.$modal.msgSuccess('日志已复制到剪贴板')
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(t).then(done).catch(() => fallbackCopy(t, done))
  } else {
    fallbackCopy(t, done)
  }
}
function fallbackCopy(t, done) {
  const ta = document.createElement('textarea')
  ta.value = t
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.select()
  try { document.execCommand('copy') } catch (e) { /* ignore */ }
  document.body.removeChild(ta)
  done()
}

onMounted(() => {
  getList()
  loadJobs()
})
</script>

<style lang="scss" scoped>
.etl-log-page {
  .view-switch {
    display: flex;
    align-items: center;
  }
  .log-tree-wrap {
    max-height: 560px;
    overflow: auto;
    border: 1px solid #ebeef5;
    border-radius: 6px;
    padding: 8px;
    .tree-leaf {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      width: 100%;
      .leaf-type { flex-shrink: 0; }
      .leaf-name {
        flex: 1;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .leaf-status { flex-shrink: 0; }
      .leaf-time {
        flex-shrink: 0;
        font-size: 12px;
        color: var(--imp-text-secondary);
        margin-right: 16px;
      }
    }
    .tree-group {
      font-weight: 600;
      color: #303133;
    }
  }
  .log-error {
    margin-top: 10px;
    .log-error-title { color: #f56c6c; font-weight: 600; margin-bottom: 4px; }
    .log-error-text {
      margin: 0;
      background: #fef0f0;
      border: 1px solid #fbc4c4;
      border-radius: 6px;
      padding: 8px;
      color: #f56c6c;
      font-size: 12px;
      max-height: 160px;
      overflow: auto;
      white-space: pre-wrap;
    }
  }
}
</style>
<!-- 日志弹窗 append-to-body 渲染在 body 下，需非 scoped 样式才能命中 -->
<style lang="scss">
.etl-log-dialog {
  .el-dialog__body {
    display: flex;
    flex-direction: column;
    max-height: calc(100vh - 160px);
    overflow: hidden;
  }
  .log-summary {
    flex-shrink: 0;
  }
  .log-box {
    margin-top: 12px;
    flex: 1 1 auto;
    min-height: 120px;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    background: #1e1e1e;
    border-radius: 6px;
    .log-box-head {
      flex-shrink: 0;
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 6px 10px;
      background: #2b2b2b;
      border-radius: 6px 6px 0 0;
      .log-box-title {
        color: #d4d4d4;
        font-size: 12px;
        font-weight: 600;
      }
    }
    .log-text {
      flex: 1 1 auto;
      margin: 0;
      padding: 10px;
      color: #d4d4d4;
      font-size: 12px;
      line-height: 1.6;
      font-family: 'Cascadia Code', Consolas, monospace;
      white-space: pre-wrap;
      word-break: break-all;
      overflow: auto;
    }
  }
  .log-error {
    flex-shrink: 0;
  }
}
</style>
