<template>
  <div class="p-2 app-container etl-job-page">
    <div class="etl-layout">
    <!-- 左侧目录树 -->
    <el-card shadow="never" class="dir-panel">
      <template #header>
        <div class="dir-heading">
          <el-icon><FolderOpened /></el-icon>
          <span>目录</span>
        </div>
      </template>
      <el-input v-model="dirKeyword" placeholder="搜索目录" clearable prefix-icon="Search" class="dir-search" />
      <div class="dir-tree-wrap">
        <el-tree v-if="!dirLoading" ref="dirTreeRef" :data="dirTreeData" :props="dirProps" node-key="id"
                 :filter-node-method="dirFilter" highlight-current :expand-on-click-node="false"
                 default-expand-all @node-click="onDirClick">
          <template #default="{ data }">
            <div class="dir-node">
              <el-icon class="dir-folder"><Folder /></el-icon>
              <span class="dir-name" :class="{ 'is-disabled': data.status === '1' }">{{ data.jobName }}</span>
              <el-tag v-if="data.status === '1'" size="small" type="warning" class="dir-stop">停用</el-tag>
              <el-dropdown trigger="click" class="dir-ops" @command="c => dirAction(c, data)">
                <el-icon class="dir-more"><MoreFilled /></el-icon>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="parent">新增上级目录</el-dropdown-item>
                    <el-dropdown-item command="sibling">新增同级目录</el-dropdown-item>
                    <el-dropdown-item command="child">新增子目录</el-dropdown-item>
                    <el-dropdown-item command="toggle" divided>{{ data.status === '1' ? '启用' : '停用' }}</el-dropdown-item>
                    <el-dropdown-item command="del" divided>删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-tree>
      </div>
    </el-card>
    <!-- 右侧主体 -->
    <div class="etl-main">
    <!-- 搜索区 -->
    <div class="search-wrap">
      <el-card shadow="never" class="search-panel">
        <template #header>
          <div class="panel-heading">
            <div>
              <h3>同步任务
                <el-tag v-if="currentDir" type="warning" size="small" class="cur-dir-tag">
                  目录：{{ currentDir.jobName }}
                  <el-icon class="cur-dir-close" @click.stop="backToAll"><Close /></el-icon>
                </el-tag>
              </h3>
            </div>
          </div>
        </template>
        <el-form :model="queryParams" :inline="true" class="query-form" @submit.prevent>
          <el-form-item label="任务名称" prop="jobName">
            <el-input v-model="queryParams.jobName" placeholder="任务名称" clearable @keyup.enter="handleQuery" />
          </el-form-item>
          <el-form-item label="任务类型" prop="jobType">
            <el-select v-model="queryParams.jobType" placeholder="全部" clearable style="width: 130px" @change="handleQuery">
              <el-option label="DataX" value="DATAX" />
              <el-option label="Kettle" value="KETTLE" />
            </el-select>
          </el-form-item>
          <el-form-item label="源数据源" prop="srcConnId">
            <el-select v-model="queryParams.srcConnId" placeholder="全部" clearable filterable style="width: 180px" @change="handleQuery">
              <el-option v-for="c in connList" :key="c.id" :label="c.connName" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="目标数据源" prop="dstConnId">
            <el-select v-model="queryParams.dstConnId" placeholder="全部" clearable filterable style="width: 180px" @change="handleQuery">
              <el-option v-for="c in connList" :key="c.id" :label="c.connName" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
            <el-button icon="Refresh" @click="resetQuery">重置</el-button>
          </el-form-item>
        </el-form>
      </el-card>
    </div>

    <!-- 列表 -->
    <el-card shadow="never" class="table-panel">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">
            <h3>任务列表</h3>
            <p>总任务 — 子任务 树形结构, 子任务按步骤(数据源→源表→清洗→质量→目标)设计, 各级支持日志。</p>
          </div>
          <div class="toolbar-actions">
            <el-dropdown trigger="click" @command="handleAdd">
              <el-button v-hasPermi="['etl:job:add']" type="primary" plain icon="Plus">
                新增<el-icon class="el-icon--right"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="DIR" icon="FolderOpened">新增目录</el-dropdown-item>
                  <el-dropdown-item command="PARENT" icon="Folder">新增总任务</el-dropdown-item>
                  <el-dropdown-item command="DATAX" icon="DataLine">新增 DataX 子任务</el-dropdown-item>
                  <el-dropdown-item command="KETTLE" icon="Cpu">新增 Kettle 子任务</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button
              v-hasPermi="['etl:job:remove']"
              type="danger"
              plain
              icon="Delete"
              :disabled="multiple"
              @click="handleDelete()"
            >删除</el-button>
          </div>
        </div>
      </template>

      <el-table
        v-if="!loading"
        border
        class="data-table"
        :data="getRows()"
        row-key="id"
        :tree-props="{ children: 'children' }"
        default-expand-all
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="50" align="center" />
        <el-table-column label="任务名称" min-width="320" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="job-name-cell">
              <el-icon v-if="row.nodeType === 'DIR'" class="parent-icon dir-icon"><FolderOpened /></el-icon>
              <el-icon v-else-if="row.nodeType === 'PARENT'" class="parent-icon"><Folder /></el-icon>
              <el-icon v-else class="child-icon"><Document /></el-icon>
              <span class="job-name-text">{{ row.jobName }}</span>
              <el-tag v-if="row.nodeType === 'DIR'" type="danger" size="small" class="role-tag">目录</el-tag>
              <el-tag v-else-if="row.nodeType === 'PARENT'" type="warning" size="small" class="role-tag">总任务</el-tag>
              <el-tag v-else type="info" size="small" class="role-tag">子任务</el-tag>
              <el-tag v-if="row.children && row.children.length" type="success" size="small" class="cnt-tag">
                {{ row.nodeType === 'DIR' ? row.children.length + ' 个节点' : row.children.length + ' 个子任务' }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" prop="jobType" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.nodeType === 'CHILD'" :type="row.jobType === 'DATAX' ? 'primary' : 'success'" size="small">{{ row.jobType }}</el-tag>
            <el-tag v-else-if="row.nodeType === 'DIR'" type="danger" size="small">DIR</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="源 → 目标" min-width="280">
          <template #default="{ row }">
            <div v-if="row.nodeType === 'PARENT'" class="src-dst-cell parent-cell">
              <el-icon class="arrow"><Right /></el-icon>
              <span class="dst">{{ row.runMode === 'PARALLEL' ? '并行执行' : '顺序执行' }} {{ row.children?.length || 0 }} 个子任务</span>
            </div>
            <div v-else-if="row.nodeType === 'DIR'" class="src-dst-cell parent-cell">
              <el-icon class="arrow"><FolderOpened /></el-icon>
              <span class="dst">目录 {{ row.children?.length || 0 }} 个节点</span>
            </div>
            <div v-else class="src-dst-cell">
              <span class="src">{{ srcName(row.srcConnId) }}.{{ row.srcTable }}</span>
              <el-icon class="arrow"><Right /></el-icon>
              <span class="dst">{{ dstName(row.dstConnId) }}.{{ row.dstTable }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="写入模式" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.nodeType === 'CHILD'" :type="row.dstWriteMode === 'TRUNCATE' ? 'warning' : 'info'" size="small">{{ row.dstWriteMode || 'INSERT' }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="调度" width="170" align="center">
          <template #default="{ row }">
            <template v-if="row.scheduleEnabled === '1' && row.scheduleCron">
              <el-tag type="success" size="small">已启用</el-tag>
              <div class="cron-cell">{{ row.scheduleCron }}</div>
            </template>
            <el-tag v-else type="info" size="small">未启用</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" prop="status" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === '0' ? 'success' : 'danger'" size="small">{{ row.status === '0' ? '正常' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="运行状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.nodeType === 'CHILD'" :type="row.execState === 'RUNNING' ? 'danger' : 'info'" size="small">{{ row.execState === 'RUNNING' ? '运行中' : '空闲' }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createTime" width="170" align="center" />
        <el-table-column label="操作" width="400" align="center" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
            <template v-if="row.nodeType === 'DIR'">
              <el-button v-hasPermi="['etl:job:edit']" type="primary" link icon="Edit" @click="handleUpdate(row)">编辑</el-button>
              <el-dropdown trigger="click" @command="c => handleAddInDir(row, c)">
                <el-button v-hasPermi="['etl:job:add']" type="primary" link icon="Plus">新增<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="PARENT">新增总任务</el-dropdown-item>
                    <el-dropdown-item command="DIR">新增子目录</el-dropdown-item>
                    <el-dropdown-item command="DATAX">新增 DataX 子任务</el-dropdown-item>
                    <el-dropdown-item command="KETTLE">新增 Kettle 子任务</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <el-button v-hasPermi="['etl:job:remove']" type="danger" link icon="Delete" @click="handleDelete(row)">删除</el-button>
            </template>
            <template v-else-if="row.nodeType === 'PARENT'">
              <el-button v-hasPermi="['etl:job:edit']" type="primary" link icon="Edit" @click="handleUpdate(row)">设计</el-button>
              <el-button v-hasPermi="['etl:job:edit']" :type="row.status === '0' ? 'warning' : 'success'" link :icon="row.status === '0' ? 'CircleClose' : 'CircleCheck'" @click="handleToggleStatus(row)">{{ row.status === '0' ? '停用' : '启用' }}</el-button>
              <el-button v-hasPermi="['etl:job:run']" type="primary" link icon="VideoPlay" @click="handleRun(row)">执行</el-button>
              <el-button v-hasPermi="['etl:log:list']" type="primary" link icon="Document" @click="handleLogs(row)">日志</el-button>
              <el-button v-hasPermi="['etl:job:remove']" type="danger" link icon="Delete" @click="handleDelete(row)">删除</el-button>
            </template>
            <template v-else>
              <el-button v-hasPermi="['etl:job:edit']" type="primary" link icon="Edit" @click="handleUpdate(row)">编辑</el-button>
              <el-button v-hasPermi="['etl:job:run']" type="primary" link icon="VideoPlay" @click="handleRun(row)">执行</el-button>
              <el-button v-if="row.execState === 'RUNNING'" v-hasPermi="['etl:job:run']" type="danger" link icon="CircleClose" @click="handleCancel(row)">取消</el-button>
              <el-button v-hasPermi="['etl:log:list']" type="primary" link icon="Document" @click="handleLogs(row)">日志</el-button>
              <el-button v-hasPermi="['etl:job:remove']" type="danger" link icon="Delete" @click="handleDelete(row)">删除</el-button>
            </template>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <pagination v-show="total > 0" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" :total="total" @pagination="getList" />
    </el-card>

    <!-- 执行日志弹窗 -->
    <el-dialog v-model="logOpen" :title="logTitle" width="820px" append-to-body top="5vh">
      <div v-if="logDetail" class="log-detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="状态">
            <el-tag :type="logStatusType" size="small">{{ logDetail.runStatus }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="节点类型">
            <el-tag v-if="logDetail.nodeType" type="info" size="small">{{ logDetail.nodeType }}</el-tag>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="节点名称">{{ logDetail.nodeName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="写入行数">{{ logDetail.totalRecords ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="开始时间">{{ logDetail.startTime }}</el-descriptions-item>
          <el-descriptions-item label="结束时间">{{ logDetail.endTime }}</el-descriptions-item>
        </el-descriptions>
        <div v-if="logDetail.errorMsg" class="log-error">
          <div class="log-error-title">错误信息</div>
          <pre class="log-error-text">{{ logDetail.errorMsg }}</pre>
        </div>
        <div class="log-box">
          <pre class="log-text">{{ logDetail.logText || '暂无日志' }}</pre>
        </div>
      </div>
      <el-empty v-else description="日志加载中…" />
    </el-dialog>

    <!-- 目录新增/编辑弹窗 -->
    <el-dialog v-model="dirOpen" :title="dirForm.id ? '编辑目录' : '新增目录'" width="480px" append-to-body>
      <el-form :model="dirForm" label-width="90px">
        <el-form-item label="目录名称" required>
          <el-input v-model="dirForm.jobName" placeholder="如: 财务域 / 人事域 / 供应链域" maxlength="50" />
        </el-form-item>
        <el-form-item label="上级目录">
          <el-select v-model="dirForm.parentId" clearable placeholder="根目录(不选=顶层)" style="width: 100%">
            <el-option label="根目录" :value="'0'" />
            <el-option v-for="d in dirList" :key="d.id" :label="d.jobName" :value="d.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dirOpen = false">取消</el-button>
        <el-button type="primary" @click="saveDir">确定</el-button>
      </template>
    </el-dialog>
      </div>
    </div>
    </div>
</template>

<script setup name="EtlJob">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { listEtlJob, addEtlJob, updateEtlJob, delEtlJob, runEtlJob, getEtlJobLog, listAllConn, listEtlJobLog, cancelEtlJob, listEtlJobDirTree } from '@/api/etl/index'

const { proxy } = getCurrentInstance()
const router = useRouter()
const jobList = ref([])
const connList = ref([])
const loading = ref(true)
const total = ref(0)
const ids = ref([])
const multiple = ref(true)
const logOpen = ref(false)
const logDetail = ref(null)
const logTitle = ref('执行日志')
const logTimer = ref(null)
const dirOpen = ref(false)
const dirForm = ref({ id: undefined, jobName: '', parentId: '0' })
// 目录选项(排除自身及其子孙, 防环)
const dirList = computed(() => {
  const walk = (list) => list.flatMap(j => {
    if (j.nodeType !== 'DIR') return []
    if (j.id === dirForm.value.id) return []
    return [{ id: j.id, jobName: j.jobName }, ...walk(j.children || [])]
  })
  return walk(jobList.value)
})

const queryParams = ref({ pageNum: 1, pageSize: 10, jobName: undefined, jobType: undefined, srcConnId: undefined, dstConnId: undefined, dirId: undefined })

const logStatusType = computed(() => {
  const s = logDetail.value?.runStatus
  if (s === 'SUCCESS') return 'success'
  if (s === 'FAILED') return 'danger'
  return 'warning'
})

function srcName(id) { return connList.value.find(c => c.id === id)?.connName || id }
function dstName(id) { return connList.value.find(c => c.id === id)?.connName || id }
function getRows() { return jobList.value }
function getList() {
  loading.value = true
  // 过滤 undefined 参数(避免 dirId=undefined 传入后端 Long 解析失败)
  const params = {}
  Object.keys(queryParams.value).forEach(k => {
    if (queryParams.value[k] !== undefined && queryParams.value[k] !== null && queryParams.value[k] !== '') params[k] = queryParams.value[k]
  })
  listEtlJob(params).then(res => {
    const rows = res.data?.rows || []
    total.value = Number(res.data?.total || 0)
    // 就地修改数组(保持引用不变, 配合 el-table v-if 延迟挂载渲染)
    jobList.value.splice(0, jobList.value.length, ...rows)
    loading.value = false
  })
}

function resetQuery() {
  queryParams.value = { pageNum: 1, pageSize: 10, jobName: undefined, jobType: undefined, srcConnId: undefined, dstConnId: undefined, dirId: undefined }
  currentDir.value = null
  handleQuery()
}
function handleQuery() { queryParams.value.pageNum = 1; getList() }
function handleSelectionChange(selection) { ids.value = selection.map(i => i.id); multiple.value = !selection.length }

function loadConns() {
  listAllConn().then(res => { connList.value = res.data || res.rows || [] })
}

// ===== 左侧目录树 =====
const dirTreeRef = ref()
const dirKeyword = ref('')
const dirTreeData = ref([])
const dirLoading = ref(true)
const dirProps = { children: 'children', label: 'jobName' }
const currentDir = ref(null)

function loadDirTree() {
  dirLoading.value = true
  listEtlJobDirTree().then(res => {
    dirTreeData.value = res.data || res.rows || []
  }).catch(() => { dirTreeData.value = [] }).finally(() => { dirLoading.value = false })
}
function dirFilter(value, data) {
  if (!value) return true
  return !!(data.jobName && String(data.jobName).includes(value))
}
watch(dirKeyword, (v) => { dirTreeRef.value && dirTreeRef.value.filter(v) })

function onDirClick(data) {
  currentDir.value = { id: data.id, jobName: data.jobName }
  queryParams.value.dirId = data.id
  handleQuery()
}
function backToAll() {
  currentDir.value = null
  queryParams.value.dirId = undefined
  handleQuery()
}
function dirAction(cmd, data) {
  if (cmd === 'parent' || cmd === 'sibling' || cmd === 'child') {
    const parentId = cmd === 'parent'
      ? (data.parentId ? String(data.parentId) : '0')
      : cmd === 'sibling' ? (data.parentId ? String(data.parentId) : '0') : String(data.id)
    dirForm.value = { id: undefined, jobName: '', parentId: parentId, afterCreate: cmd === 'parent' ? String(data.id) : null }
    dirOpen.value = true
  } else if (cmd === 'toggle') {
    const target = data.status === '1' ? '0' : '1'
    updateEtlJob({ id: data.id, status: target }).then(() => {
      proxy.$modal.msgSuccess(target === '1' ? '目录已启用' : '目录已停用')
      loadDirTree()
      getList()
    }).catch(e => proxy.$modal.msgError('操作失败: ' + (e?.msg || e?.message || '未知错误')))
  } else if (cmd === 'del') {
    proxy.$modal.confirm('确认删除目录「' + data.jobName + '」吗？目录下存在任务记录时不允许删除。').then(() => {
      return delEtlJob(data.id)
    }).then(() => {
      proxy.$modal.msgSuccess('删除成功')
      if (currentDir.value && currentDir.value.id === data.id) backToAll()
      loadDirTree()
      getList()
    }).catch(e => {
      if (e && e.msg) proxy.$modal.msgError(e.msg)
    })
  }
}

// ===== 新增/编辑 → 独立页面 =====
function handleAdd(type) {
  if (type === 'DIR') {
    dirForm.value = { id: undefined, jobName: '', parentId: '0' }
    dirOpen.value = true
  } else if (type === 'PARENT') {
    router.push('/etl/job/parentForm')
  } else if (type === 'KETTLE') {
    router.push('/etl/job/kettleForm')
  } else {
    router.push('/etl/job/dataxForm')
  }
}

// 目录行: 新增下级(总任务/子目录/子任务)
function handleAddInDir(row, c) {
  if (c === 'PARENT') router.push('/etl/job/parentForm?parentId=' + row.id)
  else if (c === 'DIR') { dirForm.value = { id: undefined, jobName: '', parentId: String(row.id) }; dirOpen.value = true }
  else if (c === 'KETTLE') router.push('/etl/job/kettleForm?parentId=' + row.id)
  else router.push('/etl/job/dataxForm?parentId=' + row.id)
}

// 目录保存(新增/编辑); 新增上级时创建新父目录后把当前目录挂载到其下
function saveDir() {
  if (!dirForm.value.jobName) { proxy.$modal.msgWarning('请输入目录名称'); return }
  const payload = { id: dirForm.value.id, jobName: dirForm.value.jobName, jobType: 'DIR', parentId: dirForm.value.parentId || '0' }
  const afterCreate = dirForm.value.afterCreate || null
  const req = payload.id ? updateEtlJob(payload) : addEtlJob(payload)
  req.then(res => {
    const newId = payload.id ? payload.id : (res && (res.data || res.msg))
    if (!payload.id && afterCreate && newId) {
      return updateEtlJob({ id: afterCreate, parentId: String(newId) }).then(() => newId)
    }
    return newId
  }).then(() => {
    proxy.$modal.msgSuccess(payload.id ? '目录已更新' : '目录已创建')
    dirOpen.value = false
    loadDirTree()
    getList()
  }).catch(e => proxy.$modal.msgError('保存失败: ' + (e?.msg || e?.message || '未知错误')))
}
function handleUpdate(row) {
  const q = 'jobName=' + encodeURIComponent(row.jobName || '')
  if (row.nodeType === 'DIR') {
    dirForm.value = { id: row.id, jobName: row.jobName, parentId: row.parentId ? String(row.parentId) : '0' }
    dirOpen.value = true
  } else if (row.nodeType === 'PARENT') {
    router.push('/etl/job/parentForm?id=' + row.id + '&' + q)
  } else if (row.jobType === 'KETTLE') {
    router.push('/etl/job/kettleForm?id=' + row.id + '&' + q)
  } else {
    router.push('/etl/job/dataxForm?id=' + row.id + '&' + q)
  }
}

// 总任务启用/停用: 停用后执行接口将拒绝运行
function handleToggleStatus(row) {
  const target = row.status === '0' ? '1' : '0'
  const tip = target === '1' ? '确认停用总任务「' + row.jobName + '」吗？停用后不可执行。' : '确认启用总任务「' + row.jobName + '」吗？'
  proxy.$modal.confirm(tip).then(() => {
    return updateEtlJob({ id: row.id, status: target })
  }).then(() => {
    proxy.$modal.msgSuccess(target === '1' ? '已停用' : '已启用')
    getList()
  }).catch(() => {})
}

function handleDelete(row) {
  const delIds = row?.id || ids.value.join(',')
  const tip = row?.nodeType === 'DIR'
    ? '确认删除目录「' + row.jobName + '」吗？目录下存在任务记录时不允许删除。'
    : row?.nodeType === 'PARENT' ? '确认删除该总任务及其全部子任务吗？'
    : '确认删除选中的任务吗？'
  proxy.$modal.confirm(tip).then(() => {
    return delEtlJob(delIds)
  }).then(() => { getList(); proxy.$modal.msgSuccess('删除成功') }).catch(e => {
    if (e && e.msg) proxy.$modal.msgError(e.msg)
  })
}

// ===== 执行与日志 =====
function handleCancel(row) {
  proxy.$modal.confirm('确认取消任务「' + row.jobName + '」吗？运行中的引擎进程将被终止。').then(() => {
    return cancelEtlJob(row.id)
  }).then(() => {
    proxy.$modal.msgSuccess('已发送取消指令')
    getList()
  }).catch(() => {})
}

function handleRun(row) {
  const tip = row.nodeType === 'PARENT'
    ? '确认立即执行总任务「' + row.jobName + '」（将' + (row.runMode === 'PARALLEL' ? '按依赖分层并行' : '顺序') + '执行全部子任务）吗？'
    : '确认立即执行任务「' + row.jobName + '」吗？'
  proxy.$modal.confirm(tip).then(() => {
    return runEtlJob(row.id)
  }).then(res => {
    proxy.$modal.msgSuccess('任务已提交执行')
    openLog(res.data, row.parentId === 0 ? '总任务执行日志' : '执行日志')
  })
}

function openLog(logId, title = '执行日志') {
  logOpen.value = true
  logTitle.value = title
  logDetail.value = null
  clearInterval(logTimer.value)
  const poll = () => {
    getEtlJobLog(logId).then(res => {
      logDetail.value = res.data
      if (res.data?.runStatus && res.data.runStatus !== 'RUNNING') {
        clearInterval(logTimer.value)
      }
    })
  }
  poll()
  logTimer.value = setInterval(poll, 2000)
}

function handleLogs(row) {
  const isParent = row.parentId === 0
  import('@/api/etl/index').then(m => m.listEtlJobLog({ pageNum: 1, pageSize: 1, jobId: row.id })).then(res => {
    const rows = res.data?.rows || res.rows || []
    if (!rows.length) { proxy.$modal.msgWarning('暂无执行日志'); return }
    openLog(rows[0].id, isParent ? '总任务执行日志' : '执行日志')
  })
}

function formatTime(t) {
  if (!t) return ''
  const d = new Date(t)
  const p = n => String(n).padStart(2, '0')
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds())
}

const runTimer = ref(null)
function refreshRunning() {
  const anyRunning = jobList.value.some(j => j.execState === 'RUNNING' || (j.children && j.children.some(ch => ch.execState === 'RUNNING')))
  if (anyRunning) {
    getList()
  }
}
onMounted(() => {
  getList(); loadDirTree()
  loadConns()
  runTimer.value = setInterval(refreshRunning, 5000)
})
onUnmounted(() => {
  clearInterval(logTimer.value)
  clearInterval(runTimer.value)
})
</script>

<style lang="scss" scoped>
.etl-job-page {
  .job-name-cell {
    display: flex;
    align-items: center;
    gap: 6px;
    white-space: nowrap;
    .parent-icon { color: var(--el-color-warning); flex-shrink: 0; }
    .child-icon { color: var(--el-color-primary); flex-shrink: 0; }
    .job-name-text { font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0; }
    .role-tag { flex-shrink: 0; }
    .cnt-tag { flex-shrink: 0; }
  }
  .src-dst-cell {
    display: flex;
    align-items: center;
    gap: 6px;
    .src { color: var(--el-text-color-primary); font-weight: 500; }
    .dst { color: var(--el-color-primary); font-weight: 500; }
    .arrow { color: var(--el-text-color-placeholder); }
    &.parent-cell { color: var(--el-color-warning); }
  }
  .log-box {
    margin-top: 12px;
    height: 380px;
    overflow: auto;
    background: #1e1e1e;
    border-radius: 6px;
    padding: 10px;
    .log-text {
      margin: 0;
      color: #d4d4d4;
      font-size: 12px;
      line-height: 1.6;
      font-family: 'Cascadia Code', Consolas, monospace;
      white-space: pre-wrap;
      word-break: break-all;
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
      max-height: 180px;
      overflow: auto;
      white-space: pre-wrap;
    }
  }
  .cron-cell {
    margin-top: 2px;
    font-family: 'Cascadia Code', Consolas, monospace;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
  .table-actions {
    display: flex;
    align-items: center;
    justify-content: center;
    flex-wrap: nowrap;
    gap: 8px;
    .el-button {
      flex: 0 0 auto !important;
      width: auto !important;
      min-width: 0;
      margin-left: 0;
      padding: 0 4px;
      white-space: nowrap;
      .el-icon { flex-shrink: 0; }
      span { flex-shrink: 0; }
    }
  }
}

/* ===== 左右布局: 左侧目录树 + 右侧任务列表 ===== */
.etl-layout {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.dir-panel {
  width: 280px;
  flex-shrink: 0;
  border: 1px solid var(--imp-border);
  border-radius: var(--imp-radius-md);
  .el-card__body { padding: var(--imp-space-2); }
  .dir-heading {
    display: flex;
    align-items: center;
    gap: 6px;
    font-weight: 600;
    font-size: 15px;
    .el-icon { color: var(--el-color-primary); }
  }
  .dir-search { margin-bottom: 8px; }
  .dir-tree-wrap {
    max-height: calc(100vh - 260px);
    overflow: auto;
  }
  .dir-node {
    display: flex;
    align-items: center;
    gap: 4px;
    flex: 1;
    min-width: 0;
    padding-right: 4px;
    .dir-folder { color: #e6a23c; flex-shrink: 0; }
    .dir-name {
      flex: 1;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: 13px;
      &.is-disabled { color: #a8abb2; }
    }
    .dir-stop { flex-shrink: 0; transform: scale(.85); }
    .dir-ops {
      flex-shrink: 0;
      .dir-more {
        color: var(--imp-text-secondary);
        cursor: pointer;
        padding: 2px;
        border-radius: 4px;
        &:hover { background: #f0f2f5; color: #409eff; }
      }
    }
  }
}
.etl-main {
  flex: 1;
  min-width: 0;
}
.search-panel,
.table-panel {
  border: 1px solid var(--imp-border);
  border-radius: var(--imp-radius-md);
  margin-bottom: var(--imp-space-2);
}
.search-panel .panel-heading h3,
.table-panel .table-heading h3 {
  font-size: 15px;
  font-weight: 600;
  margin: 0;
  color: var(--el-text-color-primary);
}
.search-panel .panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.table-panel .table-heading p {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.cur-dir-tag {
  margin-left: 8px;
  vertical-align: 2px;
  .cur-dir-close { cursor: pointer; margin-left: 2px; }
}

/* 任务名称展开箭头: 大于号替换为文件夹图标(收起/展开两态) */
.etl-job-page :deep(.el-table__expand-icon) {
  position: relative;
  width: 18px;
  height: 18px;
  .el-icon { display: none !important; }
}
.etl-job-page :deep(.el-table__expand-icon)::before {
  content: '';
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  width: 15px;
  height: 15px;
  background: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='%23e6a23c'%3E%3Cpath d='M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z'/%3E%3C/svg%3E") no-repeat center/contain;
}
.etl-job-page :deep(.el-table__expand-icon.el-table__expand-icon--expanded)::before {
  background: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='%23409eff'%3E%3Cpath d='M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 12H4V8h16v10z'/%3E%3C/svg%3E") no-repeat center/contain;
}
</style>
