<template>
  <el-dialog v-model="dialogVisible" title="数据迁移" width="820px" append-to-body destroy-on-close @closed="emit('close')">
    <div class="dm-form">
      <!-- 源 -->
      <div class="dm-section">
        <div class="dm-title"><el-icon><Right /></el-icon> 源数据</div>
        <div class="dm-row">
          <el-select v-model="form.srcConnId" size="small" placeholder="源连接" filterable style="width: 220px" @change="onSrcConn">
            <el-option v-for="c in conns" :key="c.id" :label="c.connName" :value="c.id" />
          </el-select>
          <el-select v-model="form.srcSchema" size="small" placeholder="源库" filterable style="width: 180px" @change="onSrcSchema">
            <el-option v-for="d in srcDatabases" :key="d" :label="d" :value="d" />
          </el-select>
          <el-select v-model="form.srcTable" size="small" placeholder="源表" filterable style="width: 240px" @change="onSrcTable">
            <el-option v-for="t in srcTables" :key="t.tableName" :label="t.tableName" :value="t.tableName" />
          </el-select>
        </div>
      </div>
      <!-- 目标 -->
      <div class="dm-section">
        <div class="dm-title"><el-icon><Left /></el-icon> 目标数据</div>
        <div class="dm-row">
          <el-select v-model="form.dstConnId" size="small" placeholder="目标连接" filterable style="width: 220px" @change="onDstConn">
            <el-option v-for="c in conns" :key="c.id" :label="c.connName" :value="c.id" />
          </el-select>
          <el-select v-model="form.dstSchema" size="small" placeholder="目标库" filterable style="width: 180px" @change="onDstSchema">
            <el-option v-for="d in dstDatabases" :key="d" :label="d" :value="d" />
          </el-select>
          <el-select
            v-model="form.dstTable"
            size="small"
            placeholder="目标表(选择已有表或输入新表名)"
            filterable
            allow-create
            default-first-option
            style="width: 240px"
            @change="onDstTable"
          >
            <el-option v-for="t in dstTables" :key="t.tableName" :label="t.tableName" :value="t.tableName" />
          </el-select>
        </div>
      </div>
      <!-- 字段映射 -->
      <div class="dm-section" v-if="mappings.length">
        <div class="dm-title">
          字段映射
          <span class="dm-hint">源字段 → 目标字段（类型自动映射, 已按目标方言预览）</span>
        </div>
        <el-table :data="mappings" size="small" border max-height="200">
          <el-table-column label="源字段" width="170" show-overflow-tooltip>
            <template #default="{ row }">{{ row.src }}</template>
          </el-table-column>
          <el-table-column label="源类型" width="130">
            <template #default="{ row }">
              <span class="dm-type">{{ row.srcType }}</span>
            </template>
          </el-table-column>
          <el-table-column label="目标字段" min-width="180">
            <template #default="{ row }">
              <el-select v-model="row.dst" size="small" filterable allow-create default-first-option placeholder="输入或选择目标字段" style="width: 100%">
                <el-option v-for="dc in dstColumnOptions" :key="dc" :label="dc" :value="dc" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="目标类型(预览)" width="140">
            <template #default="{ row }">
              <span class="dm-type dm-type-dst">{{ row.dstType }}</span>
            </template>
          </el-table-column>
          <el-table-column label="" width="60" align="center">
            <template #default="{ $index }">
              <el-button link type="danger" size="small" @click="removeMapping($index)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <!-- 选项 -->
      <div class="dm-section">
        <div class="dm-title">选项</div>
        <el-checkbox v-model="form.withStructure">同步表结构(建表)</el-checkbox>
        <el-checkbox v-model="form.withData">复制数据</el-checkbox>
        <el-checkbox v-model="form.dropTarget">删除已存在的目标表</el-checkbox>
        <span class="dm-hint">支持不同数据库类型互迁(MySQL/PostgreSQL/Oracle/DM/SQLServer), 字段类型自动映射</span>
      </div>
    </div>
    <!-- 结果 -->
    <template v-if="result">
      <el-divider content-position="left">迁移结果</el-divider>
      <el-alert v-if="result.error" :title="result.error" type="error" :closable="false" />
      <template v-else>
        <el-alert type="success" :closable="false" :title="`迁移完成: 目标表 ${result.targetTable}, 迁移 ${result.moved} 行`" />
        <div v-if="result.mappings && result.mappings.length" class="dm-mapped">
          <span class="dm-mapped-item" v-for="(m, i) in result.mappings" :key="i">{{ m.src }} → {{ m.dst }}</span>
        </div>
        <div class="dm-ddl">
          <div class="dm-ddl-title">目标建表 DDL</div>
          <pre>{{ result.ddl }}</pre>
        </div>
      </template>
    </template>
    <template #footer>
      <el-button size="small" @click="dialogVisible = false">关闭</el-button>
      <el-button size="small" type="primary" :loading="loading" :disabled="!canRun" @click="runMigrate">执行迁移</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listAllDbConn, listDatabases, listTables, listColumns, migrateTable } from '@/api/dbtool'

const emit = defineEmits(['close'])
const dialogVisible = ref(true)
const loading = ref(false)
const conns = ref([])
const srcDatabases = ref([])
const srcTables = ref([])
const dstDatabases = ref([])
const dstTables = ref([])
const dstColumnOptions = ref([])
const mappings = ref([])
const result = ref(null)

const form = reactive({
  srcConnId: null,
  srcSchema: null,
  srcTable: null,
  dstConnId: null,
  dstSchema: null,
  dstTable: '',
  withStructure: true,
  withData: true,
  dropTarget: false
})

const canRun = computed(() => form.srcConnId && form.srcSchema && form.srcTable && form.dstConnId && form.dstSchema && form.dstTable)

async function loadConns() {
  try {
    const res = await listAllDbConn()
    conns.value = res.data || []
  } catch (e) {
    ElMessage.error('连接加载失败: ' + (e.msg || ''))
  }
}
loadConns()

async function onSrcConn() {
  form.srcSchema = null
  form.srcTable = null
  srcDatabases.value = []
  srcTables.value = []
  mappings.value = []
  try {
    const res = await listDatabases(form.srcConnId)
    srcDatabases.value = res.data || []
  } catch (e) {
    ElMessage.error('源库加载失败: ' + (e.msg || ''))
  }
}

async function onSrcSchema() {
  form.srcTable = null
  srcTables.value = []
  mappings.value = []
  try {
    const res = await listTables(form.srcConnId, form.srcSchema)
    srcTables.value = res.data || []
  } catch (e) {
    ElMessage.error('源表加载失败: ' + (e.msg || ''))
  }
}

// 简化目标方言类型预览(与后端 mapType 对齐)
function previewDstType(srcType) {
  const t = (srcType || '').toLowerCase()
  if (t.includes('varchar') || t.includes('char') || t.includes('enum') || t.includes('set')) {
    const m = t.match(/\((\d+)\)/)
    const len = m ? m[1] : 255
    return form.dstDbType === 'oracle' ? `VARCHAR2(${len})` : form.dstDbType === 'postgresql' ? `VARCHAR(${len})` : form.dstDbType === 'sqlserver' ? `NVARCHAR(${len})` : `VARCHAR(${len})`
  }
  if (t.includes('text') || t.includes('long')) {
    return form.dstDbType === 'oracle' ? 'CLOB' : form.dstDbType === 'postgresql' ? 'TEXT' : form.dstDbType === 'sqlserver' ? 'NVARCHAR(MAX)' : 'TEXT'
  }
  if (t.includes('bigint')) return form.dstDbType === 'oracle' ? 'NUMBER(19)' : form.dstDbType === 'postgresql' ? 'BIGINT' : 'BIGINT'
  if (t.includes('smallint')) return form.dstDbType === 'oracle' ? 'NUMBER(5)' : 'SMALLINT'
  if (t.includes('tinyint')) return form.dstDbType === 'oracle' ? 'NUMBER(3)' : 'TINYINT'
  if (t.includes('int')) return form.dstDbType === 'oracle' ? 'NUMBER(10)' : form.dstDbType === 'postgresql' ? 'INTEGER' : 'INT'
  if (t.includes('datetime') || t.includes('timestamp')) return 'TIMESTAMP'
  if (t.includes('date')) return 'DATE'
  if (t.includes('decimal') || t.includes('numeric')) {
    const m = t.match(/\((\d+),\s*(\d+)\)/)
    const p = m ? m[1] : 18
    const s = m ? m[2] : 2
    return form.dstDbType === 'oracle' ? `NUMBER(${p},${s})` : form.dstDbType === 'postgresql' ? `NUMERIC(${p},${s})` : `DECIMAL(${p},${s})`
  }
  if (t.includes('double')) return form.dstDbType === 'oracle' ? 'NUMBER(18,2)' : form.dstDbType === 'postgresql' ? 'DOUBLE PRECISION' : 'DOUBLE'
  if (t.includes('float')) return form.dstDbType === 'oracle' ? 'NUMBER(18,2)' : form.dstDbType === 'postgresql' ? 'REAL' : 'FLOAT'
  if (t.includes('blob') || t.includes('binary')) return form.dstDbType === 'oracle' ? 'BLOB' : form.dstDbType === 'postgresql' ? 'BYTEA' : form.dstDbType === 'sqlserver' ? 'VARBINARY(MAX)' : 'LONGBLOB'
  if (t.includes('json')) return form.dstDbType === 'postgresql' ? 'JSONB' : 'JSON'
  return 'VARCHAR(255)'
}

async function onSrcTable() {
  mappings.value = []
  result.value = null
  try {
    const res = await listColumns(form.srcConnId, form.srcSchema, form.srcTable)
    const cols = res.data || []
    mappings.value = cols.map((c) => ({
      src: c.columnName,
      srcType: c.columnType || c.dataType || '',
      dst: c.columnName,
      dstType: previewDstType(c.columnType || c.dataType || '')
    }))
  } catch (e) {
    ElMessage.error('源列加载失败: ' + (e.msg || ''))
  }
}

async function onDstConn() {
  form.dstSchema = null
  form.dstTable = ''
  dstDatabases.value = []
  dstTables.value = []
  dstColumnOptions.value = []
  try {
    const res = await listDatabases(form.dstConnId)
    dstDatabases.value = res.data || []
    const conn = conns.value.find((c) => c.id === form.dstConnId)
    form.dstDbType = conn ? (conn.dbType || 'mysql').toLowerCase() : 'mysql'
  } catch (e) {
    ElMessage.error('目标库加载失败: ' + (e.msg || ''))
  }
}

async function onDstSchema() {
  form.dstTable = ''
  dstTables.value = []
  dstColumnOptions.value = []
  refreshDstTypePreview()
  try {
    const res = await listTables(form.dstConnId, form.dstSchema)
    dstTables.value = res.data || []
  } catch (e) {
    ElMessage.error('目标表加载失败: ' + (e.msg || ''))
  }
}

function refreshDstTypePreview() {
  mappings.value.forEach((m) => {
    m.dstType = previewDstType(m.srcType)
  })
}

async function onDstTable(val) {
  result.value = null
  // 若选择已有表 → 加载目标列供映射选择
  const exists = dstTables.value.find((t) => t.tableName === val)
  if (exists) {
    try {
      const res = await listColumns(form.dstConnId, form.dstSchema, val)
      dstColumnOptions.value = (res.data || []).map((c) => c.columnName)
      // 已有表: 目标字段默认按同名列, 若无同名则留空待选
      mappings.value.forEach((m) => {
        if (!dstColumnOptions.value.includes(m.src)) m.dst = ''
      })
    } catch (e) {
      dstColumnOptions.value = []
    }
  } else {
    dstColumnOptions.value = []
    mappings.value.forEach((m) => {
      if (!m.dst) m.dst = m.src
    })
  }
}

function removeMapping(i) {
  mappings.value.splice(i, 1)
}

async function runMigrate() {
  loading.value = true
  result.value = null
  try {
    const fieldMappings = mappings.value
      .filter((m) => m.src && m.dst)
      .map((m) => ({ src: m.src, dst: m.dst }))
    const res = await migrateTable({
      srcConnId: form.srcConnId,
      srcSchema: form.srcSchema,
      srcTable: form.srcTable,
      dstConnId: form.dstConnId,
      dstSchema: form.dstSchema,
      dstTable: form.dstTable,
      withStructure: form.withStructure,
      withData: form.withData,
      dropTarget: form.dropTarget,
      fieldMappings
    })
    result.value = res.data
    ElMessage.success('迁移完成')
  } catch (e) {
    result.value = { error: e.msg || e.message || '迁移失败' }
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.dm-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.dm-section {
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 6px;
  padding: 10px 12px;
}
.dm-title {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}
.dm-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.dm-hint {
  font-size: 11px;
  color: var(--imp-text-secondary);
  margin-left: 12px;
}
.dm-type {
  font-size: 11px;
  color: #409eff;
  background: #ecf5ff;
  border-radius: 4px;
  padding: 1px 6px;
  white-space: nowrap;
}
.dm-type-dst {
  color: #67c23a;
  background: #f0f9eb;
}
.dm-mapped {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.dm-mapped-item {
  font-size: 11px;
  color: var(--imp-text-secondary);
  background: #f5f7fa;
  border-radius: 4px;
  padding: 2px 8px;
}
.dm-ddl {
  margin-top: 8px;
  background: #1e1e1e;
  border-radius: 6px;
  padding: 10px;
}
.dm-ddl-title {
  color: #6a9955;
  font-size: 12px;
  margin-bottom: 6px;
}
.dm-ddl pre {
  margin: 0;
  color: #d4d4d4;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 220px;
  overflow: auto;
}
</style>
