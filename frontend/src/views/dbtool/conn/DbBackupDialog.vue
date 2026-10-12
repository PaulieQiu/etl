<template>
  <el-dialog v-model="visible" title="数据库备份 / 恢复" width="1000px" append-to-body destroy-on-close @closed="emit('close')">
    <el-form label-width="90px" size="small" class="backup-form">
      <el-form-item label="连接">
        <el-select v-model="form.connId" placeholder="选择 MySQL/MariaDB 连接" filterable class="backup-full">
          <el-option v-for="conn in connections" :key="conn.id" :label="conn.connName" :value="conn.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="数据库">
        <el-select v-model="form.schema" placeholder="选择数据库" filterable class="backup-full">
          <el-option v-for="schema in schemas" :key="schema" :label="schema" :value="schema" />
        </el-select>
      </el-form-item>
      <el-form-item label="恢复文件">
        <el-select v-model="form.filePath" placeholder="选择备份文件(恢复时)" filterable clearable class="backup-full">
          <el-option v-for="file in backupFiles" :key="file" :label="file" :value="file" />
        </el-select>
      </el-form-item>
    </el-form>

    <div class="backup-actions">
      <el-button type="primary" :loading="dumping" :disabled="!canDump" @click="doDump">开始备份</el-button>
      <el-button type="danger" :loading="restoring" :disabled="!canRestore" @click="confirmRestore">恢复到此库</el-button>
      <el-button :loading="listing" @click="loadJobs">刷新任务</el-button>
    </div>
    <el-alert
      title="备份通过 mysqldump 全库导出(单事务); 恢复会覆盖目标库数据, 属高危操作, 需二次确认且仅允许本服务备份目录内文件。备份/恢复任务串行执行。"
      type="warning"
      :closable="false"
      show-icon
      class="backup-tip"
    />

    <el-table v-loading="listing" :data="jobs" height="360" border>
      <el-table-column prop="id" label="任务" width="70" />
      <el-table-column prop="connName" label="连接" min-width="110" show-overflow-tooltip />
      <el-table-column prop="schema" label="数据库" min-width="110" show-overflow-tooltip />
      <el-table-column prop="jobType" label="类型" width="80">
        <template #default="{ row }">
          <el-tag :type="row.jobType === 'DUMP' ? 'primary' : 'warning'">{{ row.jobType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度" width="120">
        <template #default="{ row }">
          <el-progress :percentage="row.progress || 0" :stroke-width="8" />
        </template>
      </el-table-column>
      <el-table-column label="结果/错误" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.errorMsg || row.resultMsg || '—' }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="提交时间" width="150" />
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'SUCCEEDED' && row.filePath" link type="primary" @click="downloadJob(row)">下载</el-button>
          <el-button v-if="row.status === 'RUNNING' || row.status === 'QUEUED'" link type="danger" @click="cancelJob(row)">取消</el-button>
        </template>
      </el-table-column>
    </el-table>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listAllDbConn,
  listDatabases,
  backupDump,
  backupRestore,
  backupConfirmation,
  backupJobs,
  backupCancel,
  backupDownload
} from '@/api/dbtool/index'

const visible = ref(true)
const emit = defineEmits(['close'])
const connections = ref([])
const schemas = ref([])
const jobs = ref([])
const backupFiles = ref([])
const dumping = ref(false)
const restoring = ref(false)
const listing = ref(false)
const form = reactive({ connId: undefined, schema: '', filePath: '' })

const canDump = computed(() => form.connId && form.schema)
const canRestore = computed(() => form.connId && form.schema && form.filePath)

function statusText(status) {
  return { QUEUED: '排队中', RUNNING: '执行中', SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已取消' }[status] || status
}

function statusType(status) {
  return { QUEUED: 'info', RUNNING: 'warning', SUCCEEDED: 'success', FAILED: 'danger', CANCELLED: 'info' }[status] || 'info'
}

async function loadSchemas() {
  schemas.value = []
  form.schema = ''
  form.filePath = ''
  if (!form.connId) return
  try {
    const response = await listDatabases(form.connId)
    schemas.value = response.data || []
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '加载数据库列表失败')
  }
}

async function doDump() {
  if (!canDump.value) return
  dumping.value = true
  try {
    const response = await backupDump({ connId: form.connId, schema: form.schema })
    ElMessage.success(`备份任务已提交 (任务 #${response.data})`)
    await loadJobs()
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '提交备份失败')
  } finally {
    dumping.value = false
  }
}

async function confirmRestore() {
  if (!canRestore.value) return
  try {
    await ElMessageBox.confirm(
      `将用备份文件覆盖目标库 ${form.schema}, 该操作不可整体回滚。是否继续?`,
      '确认恢复(高危操作)',
      { type: 'warning', confirmButtonText: '继续执行', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  restoring.value = true
  try {
    const fileName = form.filePath.split(/[\\/]/).pop()
    const payload = `BACKUP_RESTORE:${form.connId}:${form.schema}:${fileName}`
    const tokenResponse = await backupConfirmation({ connId: form.connId, payload })
    const response = await backupRestore({
      connId: form.connId,
      schema: form.schema,
      filePath: form.filePath,
      confirmationToken: tokenResponse.data
    })
    ElMessage.success(`恢复任务已提交 (任务 #${response.data})`)
    await loadJobs()
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '提交恢复失败')
  } finally {
    restoring.value = false
  }
}

async function loadJobs() {
  listing.value = true
  try {
    const response = await backupJobs({ pageNum: 1, pageSize: 20, connId: form.connId })
    const data = response.data || {}
    jobs.value = data.rows || []
    backupFiles.value = jobs.value
      .filter((job) => job.jobType === 'DUMP' && job.status === 'SUCCEEDED' && job.filePath)
      .map((job) => job.filePath)
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '加载任务列表失败')
  } finally {
    listing.value = false
  }
}

async function cancelJob(row) {
  try {
    await ElMessageBox.confirm(`确认取消任务 #${row.id}?`, '取消任务', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await backupCancel(row.id)
    ElMessage.success('任务已取消')
    await loadJobs()
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '取消失败')
  }
}

async function downloadJob(row) {
  try {
    const response = await backupDownload(row.id)
    const blob = new Blob([response])
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = row.filePath.split(/[\\/]/).pop() || `backup_${row.id}.sql`
    link.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '下载失败')
  }
}

onMounted(async () => {
  try {
    const response = await listAllDbConn()
    connections.value = (response.data || []).filter((connection) =>
      ['MYSQL', 'MARIADB'].includes(String(connection.dbType || '').toUpperCase())
    )
    await loadJobs()
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '加载连接失败')
  }
})
</script>

<style scoped>
.backup-form {
  margin-bottom: 4px;
}

.backup-full {
  width: 100%;
}

.backup-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 12px 0;
  flex-wrap: wrap;
}

.backup-tip {
  margin-bottom: 12px;
}
</style>
