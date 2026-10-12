<template>
  <el-dialog v-model="visible" title="SQL 历史与脚本" width="820px" append-to-body>
    <div class="library-toolbar">
      <el-input v-model="keyword" clearable placeholder="搜索 SQL / 脚本名称" :prefix-icon="Search" @input="onKeyword" />
      <el-button type="primary" :disabled="!currentSql.trim()" @click="saveCurrent">保存当前 SQL</el-button>
    </div>
    <el-tabs v-model="activeTab">
      <el-tab-pane label="执行历史" name="history">
        <div v-if="historyRows.length" class="library-list">
          <div v-for="item in historyRows" :key="item.id" class="library-item">
            <div class="library-item-main">
              <div class="library-item-meta">
                <span>{{ item.connName || '未知连接' }}<template v-if="item.schemaName"> / {{ item.schemaName }}</template></span>
                <span>{{ formatDate(item.executedAt) }}</span>
                <span v-if="item.durationMs != null" class="library-meta-sub">{{ item.durationMs }}ms</span>
                <span v-if="item.status && item.status !== 'SUCCESS'" class="library-meta-sub" :class="item.status === 'FAILED' ? 'status-failed' : ''">{{ statusText(item.status) }}</span>
              </div>
              <pre class="library-sql">{{ item.sqlText }}</pre>
              <div v-if="item.errorMsg" class="library-error">{{ item.errorMsg }}</div>
            </div>
            <div class="library-item-actions">
              <el-button link type="primary" @click="applyHistory(item)">载入</el-button>
              <el-button link @click="saveAs(item)">保存</el-button>
              <el-button link type="danger" @click="removeHistory(item.id)">删除</el-button>
            </div>
          </div>
        </div>
        <el-empty v-else description="暂无匹配的执行历史" />
      </el-tab-pane>
      <el-tab-pane label="已保存脚本" name="saved">
        <div v-if="savedScripts.length" class="library-list">
          <div v-for="item in savedScripts" :key="item.id" class="library-item">
            <div class="library-item-main">
              <div class="library-item-meta">
                <strong>{{ item.title }}</strong>
                <span>{{ item.connName || '未绑定连接' }}<template v-if="item.schemaName"> / {{ item.schemaName }}</template></span>
                <span>更新于 {{ formatDate(item.updateTime || item.createTime) }}</span>
              </div>
              <pre class="library-sql">{{ item.sqlText }}</pre>
            </div>
            <div class="library-item-actions">
              <el-button link type="primary" @click="applyScript(item)">载入</el-button>
              <el-button link type="danger" @click="removeScript(item.id)">删除</el-button>
            </div>
          </div>
        </div>
        <el-empty v-else description="暂无匹配的已保存脚本" />
      </el-tab-pane>
    </el-tabs>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { useUserStore } from '@/store/modules/user'
import { recordSqlHistory, listSqlHistory, delSqlHistory, saveSqlScript, listSqlScripts, delSqlScript } from '@/api/dbtool/index'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  connections: { type: Array, default: () => [] },
  currentSql: { type: String, default: '' },
  connId: { type: [Number, String], default: undefined },
  schema: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue', 'apply'])

const userStore = useUserStore()
const visible = ref(props.modelValue)
const activeTab = ref('history')
const keyword = ref('')
const historyRows = ref([])
const savedScripts = ref([])

watch(() => props.modelValue, (value) => {
  visible.value = value
  if (value) loadLibrary()
})
watch(visible, (value) => emit('update:modelValue', value))

// 打开时加载服务端历史/脚本; 首次若服务端为空且存在旧 localStorage 数据则一次性迁移
async function loadLibrary() {
  try {
    const [hres, sres] = await Promise.all([
      listSqlHistory({ pageNum: 1, pageSize: 100, keyword: keyword.value || undefined }),
      listSqlScripts(keyword.value || undefined)
    ])
    const rows = hres.data?.records || []
    const scripts = sres.data || []
    historyRows.value = rows
    savedScripts.value = scripts
    if (!rows.length && !scripts.length) {
      migrateLegacyLocalStorage()
    }
  } catch (error) {
    ElMessage.error('加载 SQL 历史失败: ' + (error?.msg || error?.message || '请检查后端服务'))
  }
}

function onKeyword() {
  loadLibrary()
}

// 旧版 localStorage 一次性导入(不静默覆盖; 迁移成功才清理本地数据)
const legacyKey = () => `dbtool_sql_library_${userStore.userId || 'anonymous'}`
async function migrateLegacyLocalStorage() {
  try {
    const raw = localStorage.getItem(legacyKey())
    if (!raw) return
    const data = JSON.parse(raw)
    const history = Array.isArray(data.history) ? data.history : []
    const scripts = Array.isArray(data.scripts) ? data.scripts : []
    if (!history.length && !scripts.length) return
    let migrated = 0
    for (const item of history.slice(0, 100)) {
      await recordSqlHistory({
        sqlText: item.sql,
        connId: item.connId,
        connName: item.connName,
        schemaName: item.schema,
        status: 'SUCCESS'
      })
      migrated++
    }
    for (const item of scripts.slice(0, 100)) {
      try {
        await saveSqlScript({
          title: item.title,
          sqlText: item.sql,
          connId: item.connId,
          connName: item.connName,
          schemaName: item.schema
        })
        migrated++
      } catch (scriptError) {
        // 单个脚本迁移失败(如重名/上限)跳过, 不中断整体迁移
      }
    }
    localStorage.removeItem(legacyKey())
    if (migrated) {
      ElMessage.success(`已将 ${migrated} 条本地 SQL 记录迁移到服务端`)
      loadLibrary()
    }
  } catch (error) {
    // 迁移失败不阻塞使用; 保留 localStorage 数据
  }
}

const filteredHistory = computed(() => historyRows.value)
const filteredScripts = computed(() => savedScripts.value)

function connectionName(connId) {
  return props.connections.find((item) => String(item.id) === String(connId))?.connName || ''
}

function statusText(status) {
  const map = { FAILED: '失败', CANCELLED: '已取消', TIMED_OUT: '超时' }
  return map[status] || status
}

async function record(sql, connId, schema, meta = {}) {
  const text = String(sql || '').trim()
  if (!text) return
  try {
    await recordSqlHistory({
      sqlText: text,
      connId,
      connName: connectionName(connId) || undefined,
      schemaName: schema || undefined,
      status: meta.status || 'SUCCESS',
      durationMs: meta.durationMs || undefined,
      rowsAffected: meta.rowsAffected ?? undefined,
      errorMsg: meta.errorMsg || undefined
    })
  } catch (error) {
    // 记录失败不影响执行结果展示, 静默降级
  }
}

async function saveCurrent() {
  await saveScript({
    sql: props.currentSql,
    connId: props.connId,
    connName: connectionName(props.connId),
    schema: props.schema
  })
}

function saveAs(item) {
  // 历史/脚本行字段为 sqlText/schemaName; 另存为脚本必须是"新增"(不携带原行 id, 否则会误走更新分支)
  return saveScript({
    sql: item.sqlText || item.sql,
    connId: item.connId,
    connName: item.connName || connectionName(item.connId),
    schema: item.schemaName || item.schema
  })
}

async function saveScript(item) {
  const defaultTitle = String(item.sql || '').trim().split(/\s+/).slice(0, 6).join(' ').slice(0, 60) || 'SQL 脚本'
  try {
    const { value } = await ElMessageBox.prompt('为脚本输入一个便于查找的名称', '保存 SQL 脚本', {
      inputValue: item.title || defaultTitle,
      inputValidator: (value) => !!value.trim() || '脚本名称不能为空'
    })
    await saveSqlScript({
      id: item.id,
      title: value.trim(),
      sqlText: String(item.sql || '').trim(),
      connId: item.connId,
      connName: item.connName || connectionName(item.connId),
      schemaName: item.schema || undefined
    })
    activeTab.value = 'saved'
    ElMessage.success(item.id ? 'SQL 脚本已更新' : 'SQL 脚本已保存到服务端')
    loadLibrary()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error?.msg || error?.message || '保存 SQL 脚本失败')
    }
  }
}

function applyHistory(item) {
  emit('apply', { ...item, sql: item.sqlText, schema: item.schemaName })
  visible.value = false
}

function applyScript(item) {
  emit('apply', { ...item, sql: item.sqlText, schema: item.schemaName })
  visible.value = false
}

async function removeHistory(id) {
  try {
    await delSqlHistory(id)
    historyRows.value = historyRows.value.filter((item) => item.id !== id)
  } catch (error) {
    ElMessage.error('删除历史失败: ' + (error?.msg || error?.message || ''))
  }
}

async function removeScript(id) {
  try {
    await delSqlScript(id)
    savedScripts.value = savedScripts.value.filter((item) => item.id !== id)
  } catch (error) {
    ElMessage.error('删除脚本失败: ' + (error?.msg || error?.message || ''))
  }
}

function formatDate(value) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString()
}

defineExpose({ record })
</script>

<style scoped>
.library-toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.library-list {
  max-height: 480px;
  overflow: auto;
}

.library-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.library-item-main {
  flex: 1;
  min-width: 0;
}

.library-item-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.library-meta-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.status-failed {
  color: var(--el-color-danger);
}

.library-error {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-color-danger);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 48px;
  overflow: auto;
}

.library-sql {
  max-height: 84px;
  margin: 8px 0 0;
  overflow: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-family: Consolas, 'Courier New', monospace;
  font-size: 12px;
}

.library-item-actions {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
</style>
