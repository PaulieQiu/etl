<template>
  <div class="ai-assistant-panel">
    <div class="ai-header">
      <span class="ai-title">AI SQL 助手 (P5)</span>
      <el-button link size="small" @click="$emit('close')">关闭</el-button>
    </div>
    <div class="ai-selects">
      <el-select v-model="connId" placeholder="选择连接(白名单)" size="small" filterable @change="onConnChange">
        <el-option v-for="conn in connList" :key="conn.id" :label="conn.connName + ' (' + conn.dbType + ')'" :value="conn.id" />
      </el-select>
      <el-select v-model="schema" placeholder="选择数据库" size="small" clearable>
        <el-option v-for="db in dbList" :key="db" :label="db" :value="db" />
      </el-select>
    </div>
    <div class="ai-messages" ref="messagesRef">
      <div v-for="(msg, i) in messages" :key="i" :class="['ai-msg', msg.role]">
        <div class="msg-avatar">{{ msg.role === 'user' ? '我' : 'AI' }}</div>
        <div class="msg-content">
          <div class="msg-text">
            <span v-if="msg.risk" :class="['risk-tag', 'risk-' + (msg.risk || 'low').toLowerCase()]">{{ msg.risk }}</span>
            {{ msg.content }}
          </div>
          <div v-if="msg.sql" class="msg-sql">
            <pre><code>{{ msg.sql }}</code></pre>
            <div class="msg-sql-actions">
              <el-button size="small" type="primary" @click="insertSql(msg.sql)">插入编辑器</el-button>
              <el-button size="small" @click="copySql(msg.sql)">复制</el-button>
            </div>
          </div>
        </div>
      </div>
      <div v-if="loading" class="ai-msg ai">
        <div class="msg-avatar">AI</div>
        <div class="msg-content"><span class="loading-dots">处理中...</span></div>
      </div>
    </div>
    <div class="ai-input">
      <el-input
        v-model="inputText"
        type="textarea"
        :rows="2"
        placeholder="输入自然语言(如: 查询 mdm_material 的数量) 或粘贴 SQL 请求解释"
        @keydown.enter.exact.prevent="sendMessage"
      />
      <div class="ai-actions">
        <el-button size="small" type="primary" @click="sendMessage" :disabled="!inputText.trim() || loading || !connId">生成 SQL</el-button>
        <el-button size="small" @click="explainCurrentSql" :disabled="!currentSql || loading || !connId">解释当前 SQL</el-button>
        <el-button size="small" @click="showContext" :disabled="!connId">查看元数据</el-button>
      </div>
    </div>
    <div class="ai-notice">
      <el-alert type="info" :closable="false" show-icon>
        仅白名单连接可用(dbtool.ai.allowConns); 助手只生成/解释 SQL, 不自动执行; 写语句与高危语句会标注风险。
      </el-alert>
    </div>
  </div>
</template>

<script setup name="AiAssistant">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { aiAssist, aiExplain, aiContext, listDatabases } from '@/api/dbtool'

const props = defineProps({
  currentSql: { type: String, default: '' },
  connList: { type: Array, default: () => [] }
})
const emit = defineEmits(['close', 'insert-sql'])

const messages = ref([
  { role: 'ai', content: '你好! 我是 AI SQL 助手(P5), 基于连接元数据离线生成/解释 SQL, 只读白名单 + 审计。' }
])
const inputText = ref('')
const loading = ref(false)
const messagesRef = ref(null)
const connId = ref(undefined)
const schema = ref('')
const dbList = ref([])

function onConnChange() {
  schema.value = ''
  dbList.value = []
  if (!connId.value) return
  listDatabases(connId.value).then((res) => {
    dbList.value = res.data || []
    if (dbList.value.length === 1) schema.value = dbList.value[0]
  }).catch(() => ElMessage.warning('加载数据库列表失败, 请确认连接在白名单内'))
}

async function sendMessage() {
  if (!inputText.value.trim() || loading.value || !connId.value) {
    if (!connId.value) ElMessage.warning('请先选择连接')
    return
  }
  const text = inputText.value.trim()
  messages.value.push({ role: 'user', content: text })
  inputText.value = ''
  loading.value = true
  try {
    const res = await aiAssist({ connId: connId.value, schema: schema.value || undefined, request: text })
    const data = res.data || {}
    messages.value.push({
      role: 'ai',
      content: (data.connName ? '[' + data.connName + (data.schema ? '/' + data.schema : '') + '] ' : '')
        + (data.warning ? '风险提示: ' + data.warning + '; ' : '')
        + '已生成 SQL(仅返回不执行):',
      sql: data.sql,
      risk: data.riskLevel
    })
  } catch (e) {
    ElMessage.error(e.msg || 'AI 助手调用失败, 请检查连接白名单配置')
    messages.value.push({ role: 'ai', content: '调用失败: ' + (e.msg || e.message || '未知错误') })
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

async function explainCurrentSql() {
  if (!props.currentSql) {
    ElMessage.warning('请先在编辑器中输入SQL')
    return
  }
  if (!connId.value) {
    ElMessage.warning('请先选择连接')
    return
  }
  const text = '解释SQL: ' + props.currentSql.substring(0, 80) + (props.currentSql.length > 80 ? '...' : '')
  messages.value.push({ role: 'user', content: text })
  loading.value = true
  try {
    const res = await aiExplain({ connId: connId.value, sql: props.currentSql })
    const data = res.data || {}
    messages.value.push({
      role: 'ai',
      content: '语句类型: ' + data.statementType
        + '\n只读: ' + data.readOnly
        + '\n风险级别: ' + data.riskLevel
        + (data.warning ? '\n警示: ' + data.warning : '')
        + '\n涉及表: ' + (data.tables || []).join(', ') || '(未识别)'
        + (data.suggestLimit ? '\n建议: 查询未带 LIMIT, 可加 LIMIT 100 防止大结果集' : ''),
      risk: data.riskLevel
    })
  } catch (e) {
    ElMessage.error(e.msg || '解释失败')
    messages.value.push({ role: 'ai', content: '解释失败: ' + (e.msg || e.message || '未知错误') })
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

async function showContext() {
  if (!connId.value) {
    ElMessage.warning('请先选择连接')
    return
  }
  loading.value = true
  try {
    const res = await aiContext(connId.value, schema.value || undefined)
    const data = res.data || {}
    const tableNames = (data.tables || []).slice(0, 30).map((t) => t.name).join(', ')
    messages.value.push({
      role: 'ai',
      content: '元数据上下文: 连接=' + data.connName + ' 库=' + data.schema
        + ' 表数=' + data.tableCount
        + (tableNames ? '\n表: ' + tableNames + (data.tableCount > 30 ? ' ...' : '') : '')
    })
  } catch (e) {
    ElMessage.error(e.msg || '元数据获取失败')
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

function insertSql(sql) {
  emit('insert-sql', sql)
  ElMessage.success('SQL 已插入编辑器')
}

function copySql(sql) {
  const input = document.createElement('textarea')
  input.value = sql
  document.body.appendChild(input)
  input.select()
  document.execCommand('copy')
  document.body.removeChild(input)
  ElMessage.success('已复制')
}

function scrollToBottom() {
  setTimeout(() => {
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  }, 50)
}
</script>

<style scoped>
.ai-assistant-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #fff;
}
.ai-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #e4e7ed;
  background: #f5f7fa;
}
.ai-title {
  font-weight: 600;
  font-size: 14px;
}
.ai-selects {
  display: flex;
  gap: 8px;
  padding: 10px 16px;
  border-bottom: 1px solid #e4e7ed;
}
.ai-selects .el-select {
  flex: 1;
}
.ai-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}
.ai-msg {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}
.ai-msg.user {
  flex-direction: row-reverse;
}
.msg-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #409eff;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  flex-shrink: 0;
}
.ai-msg.user .msg-avatar {
  background: #67c23a;
}
.msg-content {
  max-width: 82%;
}
.msg-text {
  background: #f5f7fa;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}
.ai-msg.user .msg-text {
  background: #ecf5ff;
}
.risk-tag {
  display: inline-block;
  margin-right: 6px;
  padding: 1px 8px;
  border-radius: 8px;
  font-size: 11px;
  color: #fff;
}
.risk-low {
  background: #67c23a;
}
.risk-medium {
  background: #e6a23c;
}
.risk-high {
  background: #f56c6c;
}
.msg-sql {
  margin-top: 8px;
  background: #1e1e1e;
  border-radius: 6px;
  padding: 10px;
}
.msg-sql pre {
  margin: 0 0 8px 0;
  color: #d4d4d4;
  font-size: 12px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.msg-sql-actions {
  display: flex;
  gap: 8px;
}
.loading-dots {
  color: var(--imp-text-secondary);
  font-size: 13px;
}
.ai-input {
  padding: 12px 16px;
  border-top: 1px solid #e4e7ed;
}
.ai-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
  flex-wrap: wrap;
}
.ai-notice {
  padding: 8px 16px;
}
</style>
