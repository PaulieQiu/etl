<template>
  <el-dialog v-model="dialogVisible" title="Excel 多工作表导入" width="860px" append-to-body destroy-on-close @closed="emit('close')">
    <div class="ei-body">
      <!-- 选择目标 -->
      <div class="ei-row">
        <el-select v-model="connId" size="small" placeholder="目标连接" filterable style="width: 220px" @change="onConn">
          <el-option v-for="c in conns" :key="c.id" :label="c.connName" :value="c.id" />
        </el-select>
        <el-select v-model="schema" size="small" placeholder="目标库" filterable style="width: 180px">
          <el-option v-for="d in databases" :key="d" :label="d" :value="d" />
        </el-select>
        <el-upload
          :auto-upload="false"
          :limit="1"
          accept=".xlsx,.xls"
          :on-change="onFileChange"
          :file-list="fileList"
          style="display: inline-flex"
        >
          <el-button size="small" type="primary" plain>选择 Excel 文件</el-button>
        </el-upload>
      </div>
      <!-- sheet 映射 -->
      <el-table v-if="sheets.length" :data="sheets" size="small" border max-height="300">
        <el-table-column label="工作表" prop="sheetName" width="180" show-overflow-tooltip />
        <el-table-column label="数据行数" prop="rows" width="80" align="center" />
        <el-table-column label="目标表名" width="200">
          <template #default="{ row }">
            <el-input v-model="row.tableName" size="small" placeholder="新建或已有表名" />
          </template>
        </el-table-column>
        <el-table-column label="方式" width="90" align="center">
          <template #default="{ row }">
            <el-select v-model="row.mode" size="small">
              <el-option label="新建" value="create" />
              <el-option label="追加" value="append" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="数据预览(前3行)">
          <template #default="{ row }">
            <div class="ei-preview">
              <div v-for="(pv, i) in row.preview" :key="i" class="ei-preview-row">
                <span v-for="(v, j) in pv" :key="j" class="ei-cell">{{ v === null || v === '' ? '-' : v }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-alert v-else-if="uploaded" type="info" :closable="false" title="文件已选择, 请点击解析工作表" style="margin-top: 8px" />
      <!-- 结果 -->
      <template v-if="result">
        <el-divider content-position="left">导入结果</el-divider>
        <div v-for="(s, i) in result" :key="i" class="ei-result">
          <el-alert
            :type="s.success ? 'success' : 'error'"
            :closable="false"
            :title="`${s.sheet} → ${s.table} (${s.mode === 'create' ? '新建' : '追加'}): ${s.success ? '导入 ' + s.rows + ' 行' : s.error}`"
          />
        </div>
      </template>
    </div>
    <template #footer>
      <el-button size="small" @click="dialogVisible = false">关闭</el-button>
      <el-button size="small" @click="parseSheets" :disabled="!file" :loading="parsing">解析工作表</el-button>
      <el-button size="small" type="primary" :loading="loading" :disabled="!canRun" @click="runImport">执行导入</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { listAllDbConn, listDatabases, excelSheets, excelImport } from '@/api/dbtool'

const emit = defineEmits(['close'])
const dialogVisible = ref(true)
const conns = ref([])
const databases = ref([])
const connId = ref(null)
const schema = ref(null)
const file = ref(null)
const fileList = ref([])
const sheets = ref([])
const parsing = ref(false)
const loading = ref(false)
const uploaded = ref(false)
const result = ref(null)

const canRun = computed(() => connId.value && schema.value && sheets.value.length && sheets.value.every(s => s.tableName))

loadConns()
async function loadConns() {
  try {
    const res = await listAllDbConn()
    conns.value = res.data || []
  } catch (e) {
    ElMessage.error('连接加载失败')
  }
}

async function onConn() {
  schema.value = null
  try {
    const res = await listDatabases(connId.value)
    databases.value = res.data || []
  } catch (e) {
    ElMessage.error('库加载失败')
  }
}

function onFileChange(uf) {
  file.value = uf.raw
  sheets.value = []
  result.value = null
  uploaded.value = true
}

async function parseSheets() {
  parsing.value = true
  result.value = null
  try {
    const res = await excelSheets(file.value)
    const list = res.data || []
    sheets.value = list.map(s => ({ ...s, tableName: '', mode: 'create' }))
    ElMessage.success(`解析完成: ${list.length} 个工作表`)
  } catch (e) {
    ElMessage.error('解析失败: ' + (e.msg || ''))
  } finally {
    parsing.value = false
  }
}

async function runImport() {
  loading.value = true
  result.value = null
  try {
    const params = {
      connId: connId.value,
      schema: schema.value,
      sheets: sheets.value.map(s => ({ sheetName: s.sheetName, tableName: s.tableName, mode: s.mode }))
    }
    const res = await excelImport(params, file.value)
    result.value = (res.data && res.data.sheets) || []
    ElMessage.success('导入完成')
  } catch (e) {
    ElMessage.error('导入失败: ' + (e.msg || ''))
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.ei-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.ei-row {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.ei-preview {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.ei-preview-row {
  display: flex;
  gap: 4px;
}
.ei-cell {
  background: #f5f7fa;
  border-radius: 3px;
  padding: 1px 6px;
  font-size: 11px;
  color: var(--imp-text-secondary);
  max-width: 90px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ei-result {
  margin-bottom: 6px;
}
</style>
