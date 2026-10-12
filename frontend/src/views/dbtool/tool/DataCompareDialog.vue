<template>
  <el-dialog v-model="visible" title="数据对比与同步" width="1100px" append-to-body destroy-on-close class="dc-dialog" @closed="emit('close')">
    <div class="dc-selectors">
      <div class="dc-side">
        <el-text type="primary">源端</el-text>
        <el-select v-model="form.sourceConnId" placeholder="选择源连接" filterable @change="loadSchemas('source')">
          <el-option v-for="conn in connections" :key="conn.id" :label="conn.connName" :value="conn.id" />
        </el-select>
        <el-select v-model="form.sourceSchema" placeholder="选择源数据库" filterable @change="loadTables('source')">
          <el-option v-for="schema in sourceSchemas" :key="schema" :label="schema" :value="schema" />
        </el-select>
        <el-select v-model="form.sourceTable" placeholder="选择源表" filterable @change="onSourceTableChange">
          <el-option v-for="table in sourceTables" :key="table" :label="table" :value="table" />
        </el-select>
      </div>
      <span class="dc-arrow">→</span>
      <div class="dc-side">
        <el-text type="warning">目标端</el-text>
        <el-select v-model="form.targetConnId" placeholder="选择目标连接" filterable @change="loadSchemas('target')">
          <el-option v-for="conn in connections" :key="conn.id" :label="conn.connName" :value="conn.id" />
        </el-select>
        <el-select v-model="form.targetSchema" placeholder="选择目标数据库" filterable @change="loadTables('target')">
          <el-option v-for="schema in targetSchemas" :key="schema" :label="schema" :value="schema" />
        </el-select>
        <el-select v-model="form.targetTable" placeholder="选择目标表" filterable>
          <el-option v-for="table in targetTables" :key="table" :label="table" :value="table" />
        </el-select>
      </div>
    </div>

    <el-form label-width="90px" size="small" class="dc-form">
      <el-form-item label="匹配键">
        <el-select v-model="form.keyColumns" multiple filterable placeholder="默认源表主键, 可多选" class="dc-full">
          <el-option v-for="column in sourceColumns" :key="column.columnName" :label="column.columnName" :value="column.columnName" />
        </el-select>
      </el-form-item>
      <el-form-item label="忽略列">
        <el-select v-model="form.ignoreColumns" multiple filterable placeholder="比较时忽略的列(如时间戳/备注)" class="dc-full">
          <el-option v-for="column in sourceColumns" :key="column.columnName" :label="column.columnName" :value="column.columnName" />
        </el-select>
      </el-form-item>
      <el-form-item label="过滤条件">
        <el-input v-model="form.whereSql" placeholder="可选, 追加 WHERE, 如 status = 1 (用于大表缩小比较范围)" clearable />
      </el-form-item>
    </el-form>

    <div class="dc-actions">
      <el-button type="primary" :loading="loading" :disabled="!canCompare" @click="doPreview">执行对比</el-button>
      <el-checkbox-group v-model="form.operations" class="dc-ops">
        <el-checkbox value="INSERT">新增写入目标</el-checkbox>
        <el-checkbox value="UPDATE">差异更新目标</el-checkbox>
        <el-checkbox value="DELETE">缺失删除目标</el-checkbox>
      </el-checkbox-group>
      <el-button
        type="danger"
        :disabled="!summary || !form.operations.length"
        :loading="applying"
        @click="confirmApply"
      >应用同步</el-button>
      <el-input-number v-model="form.batchSize" :min="50" :max="5000" :step="100" size="small" title="批次大小" />
    </div>

    <template v-if="summary">
      <el-alert
        :title="`源 ${summary.sourceRows} 行 / 目标 ${summary.targetRows} 行 · 新增 ${summary.added} · 缺失 ${summary.missing} · 不同 ${summary.diff} · 相同 ${summary.same}`"
        :type="summary.added || summary.missing || summary.diff ? 'warning' : 'success'"
        :closable="false"
        show-icon
        class="dc-summary"
      />
      <el-tabs v-if="hasSamples" class="dc-tabs">
        <el-tab-pane :label="`新增 (${summary.added})`" name="added">
          <el-table :data="samples.added" height="240" border>
            <el-table-column v-for="column in sampleColumns" :key="column" :prop="column" :label="column" min-width="120" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>
        <el-tab-pane :label="`缺失 (${summary.missing})`" name="missing">
          <el-table :data="samples.missing" height="240" border>
            <el-table-column v-for="column in sampleColumns" :key="column" :prop="column" :label="column" min-width="120" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>
        <el-tab-pane :label="`不同 (${summary.diff})`" name="diff">
          <el-table :data="samples.diff" height="240" border>
            <el-table-column v-for="column in sampleColumns" :key="column" :prop="column" :label="column + ' (源)'" min-width="120" show-overflow-tooltip />
            <el-table-column v-for="column in sampleColumns" :key="'t_' + column" :prop="'_target.' + column" :label="column + ' (目标)'" min-width="120" show-overflow-tooltip>
              <template #default="{ row }">{{ row?._target?.[column] ?? '' }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
      <el-alert
        v-if="summary.sampleLimited"
        title="明细仅抽样展示前 50 条, 总量以上方汇总为准"
        type="info"
        :closable="false"
        class="dc-sampled"
      />
    </template>

    <template v-if="applyResult">
      <el-alert
        :title="`应用完成: 新增 ${applyResult.applied.insert} · 更新 ${applyResult.applied.update} · 删除 ${applyResult.applied.delete} · 失败批次 ${applyResult.failed.length}`"
        :type="applyResult.failed.length ? 'error' : 'success'"
        :closable="false"
        show-icon
        class="dc-summary"
      />
      <el-table v-if="applyResult.failed.length" :data="applyResult.failed" height="160" border>
        <el-table-column prop="batch" label="批次" width="80" />
        <el-table-column prop="operation" label="操作" width="100" />
        <el-table-column prop="count" label="行数" width="80" />
        <el-table-column prop="error" label="错误" min-width="300" show-overflow-tooltip />
      </el-table>
    </template>

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
  listTables,
  listColumns,
  compareDataPreview,
  compareDataApply,
  compareDataConfirmation
} from '@/api/dbtool/index'

const visible = ref(true)
const emit = defineEmits(['close'])
const connections = ref([])
const sourceSchemas = ref([])
const targetSchemas = ref([])
const sourceTables = ref([])
const targetTables = ref([])
const sourceColumns = ref([])
const loading = ref(false)
const applying = ref(false)
const summary = ref(null)
const samples = ref({ added: [], missing: [], diff: [] })
const sampleColumns = ref([])
const applyResult = ref(null)
const form = reactive({
  sourceConnId: undefined,
  targetConnId: undefined,
  sourceSchema: '',
  targetSchema: '',
  sourceTable: '',
  targetTable: '',
  keyColumns: [],
  ignoreColumns: [],
  whereSql: '',
  operations: ['INSERT', 'UPDATE'],
  batchSize: 500
})

const canCompare = computed(
  () => form.sourceConnId && form.targetConnId && form.sourceSchema && form.targetSchema && form.sourceTable && form.targetTable
)
const hasSamples = computed(
  () => samples.value.added.length || samples.value.missing.length || samples.value.diff.length
)

async function loadSchemas(side) {
  const isSource = side === 'source'
  const connId = isSource ? form.sourceConnId : form.targetConnId
  if (isSource) {
    form.sourceSchema = ''
    sourceSchemas.value = []
    sourceTables.value = []
  } else {
    form.targetSchema = ''
    targetSchemas.value = []
    targetTables.value = []
  }
  resetResult()
  if (!connId) return
  try {
    const response = await listDatabases(connId)
    if (isSource) sourceSchemas.value = response.data || []
    else targetSchemas.value = response.data || []
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '加载数据库列表失败')
  }
}

async function loadTables(side) {
  const isSource = side === 'source'
  const connId = isSource ? form.sourceConnId : form.targetConnId
  const schema = isSource ? form.sourceSchema : form.targetSchema
  if (isSource) {
    form.sourceTable = ''
    sourceTables.value = []
  } else {
    form.targetTable = ''
    targetTables.value = []
  }
  resetResult()
  if (!connId || !schema) return
  try {
    const response = await listTables(connId, schema)
    const tables = (response.data || []).filter((table) => String(table.tableType || '').toUpperCase() === 'BASE TABLE')
    const names = tables.map((table) => table.tableName)
    if (isSource) sourceTables.value = names
    else targetTables.value = names
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '加载表列表失败')
  }
}

async function onSourceTableChange() {
  sourceColumns.value = []
  form.keyColumns = []
  form.ignoreColumns = []
  resetResult()
  if (!form.sourceConnId || !form.sourceSchema || !form.sourceTable) return
  try {
    const response = await listColumns(form.sourceConnId, form.sourceSchema, form.sourceTable)
    const columns = response.data || []
    sourceColumns.value = columns
    form.keyColumns = columns.filter((column) => String(column.columnKey || '').toUpperCase() === 'PRI').map((column) => column.columnName)
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '加载列信息失败')
  }
}

function resetResult() {
  summary.value = null
  samples.value = { added: [], missing: [], diff: [] }
  sampleColumns.value = []
  applyResult.value = null
}

async function doPreview() {
  if (!canCompare.value) return
  if (!form.keyColumns.length) {
    ElMessage.warning('请选择匹配键(源表无主键时必选)')
    return
  }
  loading.value = true
  applyResult.value = null
  try {
    const response = await compareDataPreview({ ...form })
    summary.value = response.data?.summary || null
    samples.value = response.data?.samples || { added: [], missing: [], diff: [] }
    sampleColumns.value = response.data?.columns || []
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '数据对比失败')
  } finally {
    loading.value = false
  }
}

async function confirmApply() {
  if (!summary.value || !form.operations.length) return
  const total =
    (form.operations.includes('INSERT') ? summary.value.added : 0) +
    (form.operations.includes('UPDATE') ? summary.value.diff : 0) +
    (form.operations.includes('DELETE') ? summary.value.missing : 0)
  try {
    await ElMessageBox.confirm(
      `将按批次(${form.batchSize})对目标库执行: 新增 ${form.operations.includes('INSERT') ? summary.value.added : 0} 行, 更新 ${form.operations.includes('UPDATE') ? summary.value.diff : 0} 行, 删除 ${form.operations.includes('DELETE') ? summary.value.missing : 0} 行。操作不可整体回滚, 失败批次将跳过并记录。是否继续?`,
      '确认应用数据同步',
      { type: 'warning', confirmButtonText: '继续执行', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  applying.value = true
  try {
    // 与服务端 canonicalPayload 保持一致的确认摘要
    const payload = `COMPARE_APPLY:${form.targetConnId}:${form.targetSchema}.${form.targetTable}:${form.keyColumns.join(',')}:${form.operations.join(',')}`
    const tokenResponse = await compareDataConfirmation({ connId: form.targetConnId, payload })
    const token = tokenResponse.data
    const response = await compareDataApply({ ...form, confirmationToken: token })
    applyResult.value = response.data || { applied: { insert: 0, update: 0, delete: 0 }, failed: [] }
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '应用同步失败')
  } finally {
    applying.value = false
  }
}

onMounted(async () => {
  try {
    const response = await listAllDbConn()
    connections.value = (response.data || []).filter((connection) =>
      ['MYSQL', 'MARIADB'].includes(String(connection.dbType || '').toUpperCase())
    )
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '加载数据库连接失败')
  }
})
</script>

<style scoped>
.dc-selectors {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  gap: 16px;
  align-items: center;
}

.dc-side {
  display: grid;
  grid-template-columns: auto 1fr;
  grid-template-rows: repeat(3, auto);
  align-items: center;
  column-gap: 10px;
  row-gap: 8px;
}

.dc-side > .el-text {
  grid-row: 1 / 4;
  align-self: center;
  white-space: nowrap;
}

.dc-arrow {
  color: var(--el-text-color-secondary);
  font-size: 20px;
}

.dc-form {
  margin-top: 14px;
}

.dc-full {
  width: 100%;
}

.dc-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 12px 0;
  flex-wrap: wrap;
}

.dc-ops {
  margin-left: 8px;
}

.dc-summary {
  margin: 6px 0;
}

.dc-tabs {
  margin-top: 10px;
}

.dc-sampled {
  margin-top: 8px;
}

/* 收紧内容与底栏间距, 消除"关闭"按钮下方大空白 */
.dc-dialog :deep(.el-dialog__body) {
  padding-bottom: 6px;
}

.dc-dialog :deep(.el-dialog__footer) {
  padding: 4px 20px 14px;
}

@media (max-width: 760px) {
  .dc-selectors {
    grid-template-columns: 1fr;
  }

  .dc-side {
    grid-template-columns: 1fr;
    grid-template-rows: none;
  }

  .dc-side > .el-text {
    grid-row: auto;
  }
}
</style>
