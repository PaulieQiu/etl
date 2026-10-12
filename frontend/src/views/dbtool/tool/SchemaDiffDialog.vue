<template>
  <el-dialog v-model="visible" title="数据库结构对比" width="1000px" append-to-body destroy-on-close class="diff-dialog" @closed="emit('close')">
    <div class="diff-selectors">
      <div class="diff-side">
        <el-text type="primary">源端</el-text>
        <el-select v-model="form.sourceConnId" placeholder="选择源连接" filterable @change="loadSchemas('source')">
          <el-option v-for="conn in connections" :key="conn.id" :label="conn.connName" :value="conn.id" />
        </el-select>
        <el-select v-model="form.sourceSchema" placeholder="选择源数据库" filterable>
          <el-option v-for="schema in sourceSchemas" :key="schema" :label="schema" :value="schema" />
        </el-select>
      </div>
      <span class="diff-arrow">→</span>
      <div class="diff-side">
        <el-text type="warning">目标端</el-text>
        <el-select v-model="form.targetConnId" placeholder="选择目标连接" filterable @change="loadSchemas('target')">
          <el-option v-for="conn in connections" :key="conn.id" :label="conn.connName" :value="conn.id" />
        </el-select>
        <el-select v-model="form.targetSchema" placeholder="选择目标数据库" filterable>
          <el-option v-for="schema in targetSchemas" :key="schema" :label="schema" :value="schema" />
        </el-select>
      </div>
    </div>
    <div class="diff-actions">
      <el-button type="primary" :loading="loading" :disabled="!canCompare" @click="compare">比较结构</el-button>
      <el-button :disabled="!diffs.length" :loading="previewLoading" @click="loadPreview">生成迁移预览</el-button>
      <el-tag v-if="diffs.length" type="info">差异 {{ diffs.length }} 项</el-tag>
    </div>
    <el-alert
      title="当前只支持同类型 MySQL/MariaDB 对比。生成脚本仅包含新增表、列和普通 BTREE 索引；唯一索引、修改及删除项不会自动生成或执行，请人工审查。"
      type="warning"
      :closable="false"
      show-icon
      class="diff-warning"
    />
    <el-table v-loading="loading" :data="diffs" height="360" border>
      <el-table-column prop="objectType" label="对象" width="100" />
      <el-table-column prop="objectName" label="名称" min-width="200" show-overflow-tooltip />
      <el-table-column prop="diffType" label="差异" width="95">
        <template #default="{ row }">
          <el-tag :type="row.diffType === 'ADD' ? 'success' : row.diffType === 'DROP' ? 'danger' : 'warning'">{{ row.diffType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="说明" min-width="260" show-overflow-tooltip />
      <el-table-column label="源端定义" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.sourceDefinition || '—' }}</template>
      </el-table-column>
      <el-table-column label="目标端定义" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.targetDefinition || '—' }}</template>
      </el-table-column>
    </el-table>
    <el-input
      v-if="script"
      v-model="script"
      type="textarea"
      :rows="9"
      readonly
      class="diff-script"
      aria-label="迁移脚本预览"
    />
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listAllDbConn, listDatabases, compareDbSchemas, previewDbSchemaMigration } from '@/api/dbtool/index'

const visible = ref(true)
const emit = defineEmits(['close'])
const connections = ref([])
const sourceSchemas = ref([])
const targetSchemas = ref([])
const diffs = ref([])
const script = ref('')
const loading = ref(false)
const previewLoading = ref(false)
const form = reactive({ sourceConnId: undefined, targetConnId: undefined, sourceSchema: '', targetSchema: '' })
const canCompare = computed(() => form.sourceConnId && form.targetConnId && form.sourceSchema && form.targetSchema)

async function loadSchemas(side) {
  const isSource = side === 'source'
  const connId = isSource ? form.sourceConnId : form.targetConnId
  if (isSource) {
    form.sourceSchema = ''
    sourceSchemas.value = []
  } else {
    form.targetSchema = ''
    targetSchemas.value = []
  }
  diffs.value = []
  script.value = ''
  if (!connId) return
  try {
    const response = await listDatabases(connId)
    if (isSource) sourceSchemas.value = response.data || []
    else targetSchemas.value = response.data || []
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '加载数据库列表失败')
  }
}

async function compare() {
  if (!canCompare.value) return
  loading.value = true
  script.value = ''
  try {
    const response = await compareDbSchemas(form)
    diffs.value = response.data || []
    if (!diffs.value.length) ElMessage.success('两个数据库结构一致')
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || 'Schema 对比失败')
  } finally {
    loading.value = false
  }
}

async function loadPreview() {
  previewLoading.value = true
  try {
    const response = await previewDbSchemaMigration(form)
    script.value = response.data || ''
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '生成迁移预览失败')
  } finally {
    previewLoading.value = false
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
.diff-selectors {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  gap: 16px;
  align-items: center;
}

.diff-side {
  display: grid;
  grid-template-columns: auto 1fr;
  grid-template-rows: repeat(2, auto);
  align-items: center;
  column-gap: 10px;
  row-gap: 8px;
}

.diff-side > .el-text {
  grid-row: 1 / 3;
  align-self: center;
  white-space: nowrap;
}

.diff-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 16px 0 10px;
}

.diff-arrow {
  color: var(--el-text-color-secondary);
  font-size: 20px;
}

.diff-warning {
  margin-bottom: 12px;
}

.diff-script {
  margin-top: 14px;
}

/* 收紧内容与底栏间距, 消除"关闭"按钮下方大空白 */
.diff-dialog :deep(.el-dialog__body) {
  padding-bottom: 6px;
}

.diff-dialog :deep(.el-dialog__footer) {
  padding: 4px 20px 14px;
}

@media (max-width: 760px) {
  .diff-selectors {
    grid-template-columns: 1fr;
  }

  .diff-side {
    grid-template-columns: 1fr;
    grid-template-rows: none;
  }

  .diff-side > .el-text {
    grid-row: auto;
  }
}
</style>
