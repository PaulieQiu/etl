<template>
  <div class="es-manager">
    <!-- 顶部工具栏 -->
    <div class="es-toolbar">
      <span class="es-conn-name" :title="connName">
        <el-icon><Coin /></el-icon>
        {{ connName }}
      </span>
      <el-tag v-if="readOnly" type="warning" size="small">只读连接</el-tag>
      <template v-if="view === 'indices'">
        <el-input v-model="indexKw" size="small" class="es-index-search" placeholder="搜索索引名称" clearable>
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-checkbox v-model="showSys" size="small" @change="loadIndices">显示系统索引</el-checkbox>
        <el-button size="small" icon="Plus" type="primary" :disabled="readOnly" @click="openCreateIndex">新建索引</el-button>
        <el-button size="small" icon="Delete" type="danger" plain :disabled="readOnly || !currentIndex" @click="handleDeleteIndex">删除索引</el-button>
      </template>
      <template v-else-if="view === 'discover'">
        <el-select v-model="discoverIndex" size="small" class="es-discover-index" filterable placeholder="选择索引" @change="onDiscoverIndexChange">
          <el-option v-for="it in indices" :key="it.index" :label="it.index" :value="it.index" />
        </el-select>
        <el-input v-model="discoverQuery" size="small" class="es-discover-query" placeholder="Query DSL JSON, 留空 = 查询全部" clearable>
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button size="small" type="primary" icon="Search" @click="loadDocuments">搜索</el-button>
        <el-button size="small" icon="Plus" :disabled="readOnly || !discoverIndex" @click="openDocDialog()">新增文档</el-button>
      </template>
      <template v-else-if="view === 'console'">
        <span class="es-console-title">Dev Tools - REST 请求控制台</span>
      </template>
      <template v-else>
        <span class="es-console-title">集群监控</span>
      </template>
      <span class="es-toolbar-right">
        <el-button size="small" icon="Refresh" @click="onRefresh">刷新</el-button>
        <el-button size="small" :type="view === 'indices' ? 'primary' : 'default'" plain @click="switchView('indices')">索引管理</el-button>
        <el-button size="small" :type="view === 'discover' ? 'primary' : 'default'" plain @click="switchView('discover')">数据浏览</el-button>
        <el-button size="small" :type="view === 'console' ? 'primary' : 'default'" plain @click="switchView('console')">查询控制台</el-button>
        <el-button size="small" :type="view === 'monitor' ? 'primary' : 'default'" plain @click="switchView('monitor')">集群监控</el-button>
      </span>
    </div>

    <!-- 索引管理 -->
    <div v-if="view === 'indices'" class="es-body">
      <!-- 左侧索引列表 -->
      <div class="es-index-pane">
        <div class="es-index-head">
          <span class="es-index-total">共 {{ filteredIndices.length }} 个索引</span>
        </div>
        <div class="es-index-list">
          <div
            v-for="it in filteredIndices"
            :key="it.index"
            :class="['es-index-item', { active: currentIndex === it.index }]"
            @click="selectIndex(it.index)"
          >
            <span :class="['es-health-dot', 'es-health-' + (it.health || '').toLowerCase()]" :title="'健康状态: ' + (it.health || '-')" />
            <span class="es-index-name" :title="it.index">{{ it.index }}</span>
            <span class="es-index-meta">{{ it.docsCount || 0 }} 文档</span>
            <span class="es-index-size">{{ it.storeSize || '-' }}</span>
          </div>
          <el-empty v-if="!filteredIndices.length" description="无索引, 点击「新建索引」创建" :image-size="60" />
        </div>
      </div>
      <!-- 右侧索引详情 -->
      <div class="es-detail-pane">
        <template v-if="currentIndex">
          <div class="es-detail-head">
            <span class="es-detail-index" :title="currentIndex">{{ currentIndex }}</span>
            <el-tag :type="healthTagType(currentHealth)" size="small" effect="dark">{{ currentHealth || '-' }}</el-tag>
            <span class="es-detail-toolbar">
              <el-button size="small" icon="Refresh" @click="loadIndexDetail">重载</el-button>
              <el-button size="small" icon="RefreshRight" :disabled="readOnly" @click="indexOp('refresh')">刷新</el-button>
              <el-button size="small" icon="Odometer" :disabled="readOnly" @click="indexOp('flush')">Flush</el-button>
              <el-button v-if="currentStatus !== 'close'" size="small" icon="SwitchButton" :disabled="readOnly" @click="indexOp('close')">关闭</el-button>
              <el-button v-else size="small" icon="VideoPlay" type="success" plain :disabled="readOnly" @click="indexOp('open')">打开</el-button>
              <el-button size="small" icon="Broom" :disabled="readOnly" @click="indexOp('clearCache')">清缓存</el-button>
            </span>
          </div>
          <el-tabs v-model="detailTab" class="es-detail-tabs">
            <el-tab-pane label="概览" name="overview">
              <el-descriptions :column="3" border size="small" class="es-desc">
                <el-descriptions-item label="健康状态">{{ currentHealth || '-' }}</el-descriptions-item>
                <el-descriptions-item label="状态">{{ currentStatus || '-' }}</el-descriptions-item>
                <el-descriptions-item label="主分片">{{ currentRow.pri ?? '-' }}</el-descriptions-item>
                <el-descriptions-item label="副本数">{{ currentRow.rep ?? '-' }}</el-descriptions-item>
                <el-descriptions-item label="文档数">{{ detailStats.docsCount ?? '-' }}</el-descriptions-item>
                <el-descriptions-item label="存储大小">{{ currentRow.storeSize || '-' }}</el-descriptions-item>
                <el-descriptions-item label="删除文档">{{ detailStats.docsDeleted ?? '-' }}</el-descriptions-item>
                <el-descriptions-item label="存储字节">{{ detailStats.storeSizeBytes ?? '-' }}</el-descriptions-item>
                <el-descriptions-item label="索引写入总数">{{ detailStats.indexTotal ?? '-' }}</el-descriptions-item>
              </el-descriptions>
            </el-tab-pane>
            <el-tab-pane label="映射 Mapping" name="mapping">
              <div class="es-json-head">
                <span class="es-json-hint">字段结构定义(_mapping), 只读</span>
                <el-button size="small" text type="primary" @click="copyJson(detailMappings)">复制</el-button>
              </div>
              <pre class="es-json-view">{{ prettyJson(detailMappings) }}</pre>
            </el-tab-pane>
            <el-tab-pane label="设置 Settings" name="settings">
              <div class="es-json-head">
                <span class="es-json-hint">索引配置(_settings), 只读</span>
                <el-button size="small" text type="primary" @click="copyJson(detailSettings)">复制</el-button>
              </div>
              <pre class="es-json-view">{{ prettyJson(detailSettings) }}</pre>
            </el-tab-pane>
            <el-tab-pane label="别名 Aliases" name="aliases">
              <el-table :data="aliasRows" size="small" border class="es-alias-table">
                <el-table-column prop="alias" label="别名" />
                <el-table-column prop="filter" label="过滤条件">
                  <template #default="{ row }">{{ prettyJson(row.filter) }}</template>
                </el-table-column>
                <el-table-column prop="isWriteIndex" label="写索引" width="90">
                  <template #default="{ row }">{{ row.isWriteIndex ? '是' : '-' }}</template>
                </el-table-column>
              </el-table>
              <el-empty v-if="!aliasRows.length" description="无别名" :image-size="50" />
            </el-tab-pane>
          </el-tabs>
        </template>
        <el-empty v-else description="点击左侧索引查看详情" :image-size="100" class="es-detail-empty" />
      </div>
    </div>

    <!-- 数据浏览 -->
    <div v-else-if="view === 'discover'" class="es-body es-discover-body">
      <div class="es-discover-panel">
        <div class="es-discover-head">
          <span class="es-discover-total">
            索引 <b>{{ discoverIndex || '-' }}</b> · 共 {{ totalDocs }} 条文档
            <span v-if="docCostMs" class="es-cost">({{ docCostMs }} ms)</span>
          </span>
          <span class="es-discover-pager">
            <el-pagination
              small
              background
              layout="prev, pager, next"
              :total="totalDocs"
              :page-size="discoverSize"
              v-model:current-page="discoverPage"
              @current-change="loadDocuments"
            />
          </span>
        </div>
        <el-table :data="docs" size="small" border height="100%" class="es-doc-table" @row-dblclick="(row) => viewDoc(row)">
          <el-table-column prop="_id" label="_id" width="120" show-overflow-tooltip />
          <el-table-column v-for="col in docColumns" :key="col" :label="col" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">
              <span :class="{ 'es-cell-obj': isObj(row._source[col]) }">{{ cellText(row, col) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="viewDoc(row)">JSON</el-button>
              <el-button size="small" text type="warning" :disabled="readOnly" @click="editDoc(row)">编辑</el-button>
              <el-button size="small" text type="danger" :disabled="readOnly" @click="deleteDoc(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 查询控制台 Dev Tools -->
    <div v-else-if="view === 'console'" class="es-body es-console-body">
      <div class="es-console-left">
        <div class="es-console-req">
          <el-select v-model="consoleMethod" size="small" class="es-method-select">
            <el-option v-for="m in (readOnly ? ['GET', 'POST', 'HEAD'] : ['GET', 'POST', 'PUT', 'DELETE', 'HEAD'])" :key="m" :label="m" :value="m" />
          </el-select>
          <el-input v-model="consolePath" size="small" class="es-path-input" placeholder="/index/_search" @keyup.enter="runConsole" />
          <el-button size="small" type="primary" icon="VideoPlay" :loading="consoleLoading" @click="runConsole">发送请求</el-button>
        </div>
        <div class="es-console-body-input">
          <el-input
            v-model="consoleBody"
            type="textarea"
            class="es-body-editor"
            placeholder="请求体 JSON (可选), 如: {&quot;query&quot;:{&quot;match_all&quot;:{}},&quot;size&quot;:10}"
            @keydown.ctrl.enter="runConsole"
          />
        </div>
        <div class="es-console-samples">
          <span class="es-samples-label">示例:</span>
          <el-button v-for="s in consoleSamples" :key="s.label" size="small" text @click="applySample(s)">{{ s.label }}</el-button>
        </div>
      </div>
      <div class="es-console-right">
        <div class="es-console-resp-head">
          <span class="es-resp-title">响应</span>
          <span v-if="consoleResult" class="es-resp-meta">
            <el-tag :type="consoleOk ? 'success' : 'danger'" size="small" effect="plain">{{ consoleOk ? '成功' : '失败' }}</el-tag>
            <span class="es-cost">{{ consoleResult.costMs }} ms</span>
          </span>
          <span class="es-resp-actions">
            <el-button size="small" text type="primary" :disabled="!consoleRespText" @click="copyText(consoleRespText)">复制</el-button>
            <el-button size="small" text :disabled="!consoleHistory.length" @click="consoleHistory = []">清空历史</el-button>
          </span>
        </div>
        <pre class="es-console-resp" :class="{ 'es-resp-error': !consoleOk }">{{ consoleRespText || '// 执行请求后在此显示响应' }}</pre>
      </div>
    </div>

    <!-- 集群监控 -->
    <div v-else-if="view === 'monitor'" class="es-body es-monitor-body">
      <div class="es-monitor-cards">
        <div class="es-monitor-card">
          <div class="es-mc-title">集群信息</div>
          <div class="es-mc-row"><span class="es-mc-label">集群名称</span><span class="es-mc-value">{{ cluster.version.clusterName || '-' }}</span></div>
          <div class="es-mc-row"><span class="es-mc-label">节点名称</span><span class="es-mc-value">{{ cluster.version.name || '-' }}</span></div>
          <div class="es-mc-row"><span class="es-mc-label">ES 版本</span><span class="es-mc-value">{{ cluster.version.number || '-' }}</span></div>
          <div class="es-mc-row"><span class="es-mc-label">Lucene</span><span class="es-mc-value">{{ cluster.version.luceneVersion || '-' }}</span></div>
          <div class="es-mc-row"><span class="es-mc-label">构建时间</span><span class="es-mc-value">{{ cluster.version.buildDate || '-' }}</span></div>
        </div>
        <div class="es-monitor-card">
          <div class="es-mc-title">集群健康</div>
          <div class="es-mc-row">
            <span class="es-mc-label">状态</span>
            <span class="es-mc-value">
              <span :class="['es-health-dot', 'es-health-' + (cluster.health.status || '').toLowerCase()]" />
              {{ cluster.health.status || '-' }}
            </span>
          </div>
          <div class="es-mc-row"><span class="es-mc-label">节点数</span><span class="es-mc-value">{{ cluster.health.number_of_nodes ?? '-' }}</span></div>
          <div class="es-mc-row"><span class="es-mc-label">数据节点</span><span class="es-mc-value">{{ cluster.health.number_of_data_nodes ?? '-' }}</span></div>
          <div class="es-mc-row"><span class="es-mc-label">活跃分片</span><span class="es-mc-value">{{ cluster.health.active_shards ?? '-' }} / {{ cluster.health.active_primary_shards ?? '-' }} 主</span></div>
          <div class="es-mc-row"><span class="es-mc-label">未分配分片</span><span class="es-mc-value">{{ cluster.health.unassigned_shards ?? '-' }}</span></div>
          <div class="es-mc-row"><span class="es-mc-label">分片可用率</span><span class="es-mc-value">{{ cluster.health.active_shards_percent_as_number ?? '-' }}%</span></div>
        </div>
      </div>
      <div class="es-nodes-card">
        <div class="es-mc-title">节点列表</div>
        <el-table :data="clusterNodes" size="small" border class="es-nodes-table">
          <el-table-column prop="name" label="节点名称" min-width="140" show-overflow-tooltip />
          <el-table-column prop="ip" label="IP" width="130" />
          <el-table-column prop="heap.percent" label="堆内存%" width="90" />
          <el-table-column prop="ram.percent" label="内存%" width="80" />
          <el-table-column prop="cpu" label="CPU%" width="80" />
          <el-table-column prop="load_1m" label="负载(1m)" width="90" />
          <el-table-column prop="disk.used_percent" label="磁盘%" width="80" />
          <el-table-column prop="master" label="主节点" width="80">
            <template #default="{ row }">{{ row.master === '*' ? '是' : '-' }}</template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 新建索引弹窗 -->
    <el-dialog v-model="createDialog" title="新建索引" width="640px" append-to-body>
      <el-form label-width="110px" size="small">
        <el-form-item label="索引名称" required>
          <el-input v-model="createForm.index" placeholder="小写字母/数字/_-., 如 user_logs_2026" />
        </el-form-item>
        <el-form-item label="主分片数">
          <el-input-number v-model="createForm.shards" :min="1" :max="64" size="small" />
        </el-form-item>
        <el-form-item label="副本数">
          <el-input-number v-model="createForm.replicas" :min="0" :max="10" size="small" />
        </el-form-item>
        <el-form-item label="映射 Mapping">
          <el-input
            v-model="createForm.mappings"
            type="textarea"
            :rows="8"
            class="es-form-json"
            placeholder='可选, JSON 格式, 如: {"properties":{"name":{"type":"keyword"},"age":{"type":"integer"}}}'
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="createDialog = false">取消</el-button>
        <el-button size="small" type="primary" @click="confirmCreateIndex">创建</el-button>
      </template>
    </el-dialog>

    <!-- 文档查看/编辑弹窗 -->
    <el-dialog v-model="docDialog" :title="docDialogTitle" width="720px" append-to-body>
      <el-form label-width="90px" size="small" v-if="docEditMode">
        <el-form-item label="_id">
          <el-input v-model="docForm.id" :disabled="!!docForm.originalId" placeholder="留空由 ES 自动生成" />
        </el-form-item>
        <el-form-item label="_source" required>
          <el-input v-model="docForm.source" type="textarea" :rows="14" class="es-form-json" placeholder='{"field":"value"}' />
        </el-form-item>
      </el-form>
      <pre v-else class="es-json-view es-doc-view">{{ docViewText }}</pre>
      <template #footer>
        <el-button size="small" @click="docDialog = false">关闭</el-button>
        <el-button v-if="docEditMode" size="small" type="primary" :disabled="readOnly" @click="confirmSaveDoc">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Coin, Search } from '@element-plus/icons-vue'
import {
  esIndices, esCluster, esIndexInfo, esCreateIndex, esDeleteIndex, esIndexOp,
  esSearch, esDoc, esSaveDoc, esDeleteDoc, esConsole
} from '@/api/dbtool'

const props = defineProps({
  connId: { type: [Number, String], required: true },
  connName: { type: String, default: '' },
  readOnly: { type: Boolean, default: false }
})

// ============ 视图 ============
const view = ref('indices')
const indices = ref([])
const loadingIndices = ref(false)
const indexKw = ref('')
const showSys = ref(false)
const currentIndex = ref('')
const currentRow = ref({})
const currentHealth = ref('')
const currentStatus = ref('')
const detailTab = ref('overview')
const detailMappings = ref({})
const detailSettings = ref({})
const detailAliases = ref({})
const detailStats = ref({})

const filteredIndices = computed(() => {
  const kw = (indexKw.value || '').trim().toLowerCase()
  if (!kw) return indices.value
  return indices.value.filter((it) => (it.index || '').toLowerCase().includes(kw))
})

function healthTagType(h) {
  return { green: 'success', yellow: 'warning', red: 'danger' }[String(h || '').toLowerCase()] || 'info'
}

// ============ 索引管理 ============
async function loadIndices() {
  loadingIndices.value = true
  try {
    const res = await esIndices(props.connId, showSys.value)
    indices.value = res.data || []
    const existed = indices.value.find((it) => it.index === currentIndex.value)
    if (currentIndex.value && existed) {
      currentRow.value = existed
      currentHealth.value = existed.health
      currentStatus.value = existed.status
      loadIndexDetail()
    } else {
      currentIndex.value = ''
    }
  } catch (e) {
    ElMessage.error('索引列表加载失败: ' + (e.msg || e.message || ''))
  } finally {
    loadingIndices.value = false
  }
}

function selectIndex(index) {
  currentIndex.value = index
  const row = indices.value.find((it) => it.index === index)
  currentRow.value = row || {}
  currentHealth.value = (row && row.health) || ''
  currentStatus.value = (row && row.status) || ''
  loadIndexDetail()
}

async function loadIndexDetail() {
  if (!currentIndex.value) return
  try {
    const res = await esIndexInfo(props.connId, currentIndex.value)
    const d = res.data || {}
    detailMappings.value = d.mappings || {}
    detailSettings.value = d.settings || {}
    detailAliases.value = d.aliases || {}
    detailStats.value = d.stats || {}
  } catch (e) {
    ElMessage.error('索引详情加载失败: ' + (e.msg || e.message || ''))
  }
}

const aliasRows = computed(() =>
  Object.entries(detailAliases.value || {}).map(([alias, cfg]) => ({
    alias,
    filter: (cfg && cfg.filter) || null,
    isWriteIndex: cfg && cfg.is_write_index
  }))
)

async function indexOp(op) {
  if (!currentIndex.value) return
  const labels = { refresh: '刷新', flush: 'Flush', close: '关闭', open: '打开', clearCache: '清空缓存' }
  if (op === 'close') {
    try {
      await ElMessageBox.confirm(`确认关闭索引「${currentIndex.value}」? 关闭后不可读写, 可重新打开`, '关闭确认', { type: 'warning' })
    } catch (e) {
      return
    }
  }
  try {
    await esIndexOp({ connId: props.connId, index: currentIndex.value, op })
    ElMessage.success(labels[op] + '成功')
    loadIndices()
  } catch (e) {
    ElMessage.error(labels[op] + '失败: ' + (e.msg || e.message || ''))
  }
}

// ============ 新建索引 ============
const createDialog = ref(false)
const createForm = reactive({ index: '', shards: 1, replicas: 0, mappings: '' })

function openCreateIndex() {
  createForm.index = ''
  createForm.shards = 1
  createForm.replicas = 0
  createForm.mappings = ''
  createDialog.value = true
}

async function confirmCreateIndex() {
  const index = (createForm.index || '').trim()
  if (!index) {
    ElMessage.warning('请输入索引名称')
    return
  }
  let mappings = null
  if ((createForm.mappings || '').trim()) {
    try {
      mappings = JSON.parse(createForm.mappings.trim())
    } catch (e) {
      ElMessage.warning('Mapping 不是合法 JSON')
      return
    }
  }
  const settings = {
    index: {
      number_of_shards: createForm.shards || 1,
      number_of_replicas: createForm.replicas || 0
    }
  }
  try {
    await esCreateIndex({ connId: props.connId, index, settings, mappings })
    ElMessage.success('索引创建成功')
    createDialog.value = false
    currentIndex.value = ''
    await loadIndices()
    currentIndex.value = index
    const row = indices.value.find((it) => it.index === index)
    currentRow.value = row || {}
    currentHealth.value = (row && row.health) || ''
    currentStatus.value = (row && row.status) || ''
    loadIndexDetail()
  } catch (e) {
    ElMessage.error('创建失败: ' + (e.msg || e.message || ''))
  }
}

async function handleDeleteIndex() {
  if (!currentIndex.value) return
  try {
    await ElMessageBox.confirm(
      `确认删除索引「${currentIndex.value}」? 将永久删除该索引及其全部数据, 且不可恢复!`,
      '删除索引',
      { type: 'warning', confirmButtonText: '确认删除', confirmButtonClass: 'el-button--danger' }
    )
  } catch (e) {
    return
  }
  try {
    await esDeleteIndex({ connId: props.connId, index: currentIndex.value })
    ElMessage.success('索引已删除')
    currentIndex.value = ''
    loadIndices()
  } catch (e) {
    ElMessage.error('删除失败: ' + (e.msg || e.message || ''))
  }
}

// ============ 数据浏览 ============
const discoverIndex = ref('')
const discoverQuery = ref('')
const docs = ref([])
const totalDocs = ref(0)
const discoverPage = ref(1)
const discoverSize = ref(50)
const docCostMs = ref(null)
const docLoading = ref(false)

const docColumns = computed(() => {
  const cols = []
  for (const d of docs.value) {
    for (const k of Object.keys(d._source || {})) {
      if (!cols.includes(k)) cols.push(k)
    }
  }
  return cols
})

function isObj(v) {
  return v !== null && typeof v === 'object'
}

function cellText(row, col) {
  const v = row._source ? row._source[col] : undefined
  return v === undefined || v === null ? '' : (isObj(v) ? JSON.stringify(v) : String(v))
}

function onDiscoverIndexChange() {
  discoverPage.value = 1
  loadDocuments()
}

async function loadDocuments() {
  if (!discoverIndex.value) return
  docLoading.value = true
  try {
    let query = null
    if ((discoverQuery.value || '').trim()) {
      try {
        query = JSON.parse(discoverQuery.value.trim())
      } catch (e) {
        ElMessage.warning('Query DSL 不是合法 JSON')
        return
      }
    }
    const from = (discoverPage.value - 1) * discoverSize.value
    const res = await esSearch({
      connId: props.connId,
      index: discoverIndex.value,
      from,
      size: discoverSize.value,
      query
    })
    const d = res.data || {}
    docs.value = d.docs || []
    totalDocs.value = d.total || 0
    docCostMs.value = null
  } catch (e) {
    ElMessage.error('文档加载失败: ' + (e.msg || e.message || ''))
  } finally {
    docLoading.value = false
  }
}

// ============ 文档查看/编辑 ============
const docDialog = ref(false)
const docEditMode = ref(false)
const docDialogTitle = ref('文档')
const docViewText = ref('')
const docForm = reactive({ id: '', originalId: '', source: '' })

function viewDoc(row) {
  docEditMode.value = false
  docDialogTitle.value = '文档查看 - ' + row._id
  docViewText.value = JSON.stringify({ _id: row._id, _index: row._index, _source: row._source }, null, 2)
  docDialog.value = true
}

function editDoc(row) {
  docEditMode.value = true
  docDialogTitle.value = '编辑文档 - ' + row._id
  docForm.id = row._id
  docForm.originalId = row._id
  docForm.source = JSON.stringify(row._source || {}, null, 2)
  docDialog.value = true
}

function openDocDialog() {
  docEditMode.value = true
  docDialogTitle.value = '新增文档'
  docForm.id = ''
  docForm.originalId = ''
  docForm.source = '{}'
  docDialog.value = true
}

async function confirmSaveDoc() {
  if (!discoverIndex.value) return
  let doc
  try {
    doc = JSON.parse(docForm.source.trim())
  } catch (e) {
    ElMessage.warning('文档内容不是合法 JSON')
    return
  }
  try {
    const res = await esSaveDoc({
      connId: props.connId,
      index: discoverIndex.value,
      id: docForm.id || null,
      doc
    })
    const d = res.data || {}
    ElMessage.success('文档已保存 (_id: ' + (d._id || docForm.id || '-') + ')')
    docDialog.value = false
    await refreshDiscover()
    loadDocuments()
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.msg || e.message || ''))
  }
}

// 写操作后刷新索引, 让新增/修改文档立即可见(ES 近实时)
async function refreshDiscover() {
  try {
    await esIndexOp({ connId: props.connId, index: discoverIndex.value, op: 'refresh' })
  } catch (e) {
    // 忽略刷新失败, 不影响主流程
  }
}

async function deleteDoc(row) {
  if (!discoverIndex.value) return
  try {
    await ElMessageBox.confirm(`确认删除文档 _id=${row._id}?`, '删除文档', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await esDeleteDoc({ connId: props.connId, index: discoverIndex.value, id: row._id })
    ElMessage.success('文档已删除')
    await refreshDiscover()
    loadDocuments()
  } catch (e) {
    ElMessage.error('删除失败: ' + (e.msg || e.message || ''))
  }
}

// ============ 查询控制台 ============
const consoleMethod = ref('GET')
const consolePath = ref('/_cluster/health')
const consoleBody = ref('')
const consoleLoading = ref(false)
const consoleResult = ref(null)
const consoleRespText = ref('')
const consoleOk = ref(true)
const consoleHistory = ref([])

const consoleSamples = [
  { label: '集群健康', method: 'GET', path: '/_cluster/health', body: '' },
  { label: '索引列表', method: 'GET', path: '/_cat/indices?format=json', body: '' },
  { label: '节点列表', method: 'GET', path: '/_cat/nodes?format=json', body: '' },
  { label: '查询全部', method: 'POST', path: '/INDEX/_search', body: '{\n  "query": { "match_all": {} },\n  "size": 10\n}' },
  { label: '条件查询', method: 'POST', path: '/INDEX/_search', body: '{\n  "query": { "term": { "name": "value" } },\n  "size": 10\n}' },
  { label: '聚合统计', method: 'POST', path: '/INDEX/_search', body: '{\n  "size": 0,\n  "aggs": { "byField": { "terms": { "field": "FIELD.keyword", "size": 10 } } }\n}' },
  { label: '映射信息', method: 'GET', path: '/INDEX/_mapping', body: '' },
  { label: '设置信息', method: 'GET', path: '/INDEX/_settings', body: '' },
  { label: '索引统计', method: 'GET', path: '/INDEX/_stats', body: '' },
  { label: 'SQL 查询', method: 'POST', path: '/_sql?format=json', body: '{\n  "query": "SELECT * FROM INDEX LIMIT 10"\n}' }
]

function applySample(s) {
  const target = discoverIndex.value || currentIndex.value || 'INDEX'
  consoleMethod.value = s.method
  consolePath.value = s.path.replace(/INDEX/g, target)
  consoleBody.value = s.body.replace(/INDEX/g, target)
}

async function runConsole() {
  const path = (consolePath.value || '').trim()
  if (!path) {
    ElMessage.warning('请输入请求路径')
    return
  }
  consoleLoading.value = true
  try {
    const res = await esConsole({
      connId: props.connId,
      method: consoleMethod.value,
      path,
      body: (consoleBody.value || '').trim() || null
    })
    const d = res.data || {}
    consoleResult.value = d
    consoleOk.value = true
    consoleRespText.value = JSON.stringify(d.body, null, 2)
    consoleHistory.value.unshift({ method: consoleMethod.value, path, costMs: d.costMs })
    if (consoleHistory.value.length > 30) consoleHistory.value.pop()
  } catch (e) {
    consoleOk.value = false
    consoleResult.value = { error: e.msg || e.message || '请求失败' }
    consoleRespText.value = (e.msg || e.message || '请求失败')
    consoleHistory.value.unshift({ method: consoleMethod.value, path, costMs: null })
    if (consoleHistory.value.length > 30) consoleHistory.value.pop()
  } finally {
    consoleLoading.value = false
  }
}

// ============ 集群监控 ============
const cluster = ref({ version: {}, health: {}, nodes: [] })
const clusterNodes = computed(() => {
  const arr = cluster.value.nodes || []
  return Array.isArray(arr) ? arr : []
})

async function loadCluster() {
  try {
    const res = await esCluster(props.connId)
    cluster.value = res.data || { version: {}, health: {}, nodes: [] }
  } catch (e) {
    ElMessage.error('集群信息加载失败: ' + (e.msg || e.message || ''))
  }
}

// ============ 通用 ============
function prettyJson(o) {
  if (o === null || o === undefined) return ''
  if (typeof o === 'string') {
    try {
      o = JSON.parse(o)
    } catch (e) {
      return o
    }
  }
  return JSON.stringify(o, null, 2)
}

function copyJson(o) {
  copyText(prettyJson(o))
}

function copyText(t) {
  if (!t) return
  const textarea = document.createElement('textarea')
  textarea.value = t
  document.body.appendChild(textarea)
  textarea.select()
  try {
    document.execCommand('copy')
    ElMessage.success('已复制')
  } catch (e) {
    ElMessage.warning('复制失败, 请手动选择复制')
  }
  document.body.removeChild(textarea)
}

function switchView(v) {
  view.value = v
  if (v === 'discover' && !discoverIndex.value && indices.value.length) {
    discoverIndex.value = indices.value[0].index
    loadDocuments()
  }
  if (v === 'monitor') loadCluster()
  if (v === 'console' && !consoleHistory.value.length) {
    consoleRespText.value = '// 执行请求后在此显示响应'
  }
}

function onRefresh() {
  if (view.value === 'indices') loadIndices()
  else if (view.value === 'discover') loadDocuments()
  else if (view.value === 'monitor') loadCluster()
  else ElMessage.success('已刷新')
}

onMounted(() => {
  loadIndices()
})
</script>

<style scoped>
.es-manager {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 480px;
  border: 1px solid var(--el-border-color-light, #ebeef5);
  border-radius: 4px;
  overflow: hidden;
  background: #fff;
}
.es-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--el-border-color-lighter, #f0f0f0);
  background: #fafafa;
  flex-wrap: nowrap;
  overflow-x: auto;
}
.es-conn-name {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  font-weight: 600;
  color: #409eff;
  white-space: nowrap;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.es-index-search {
  width: 200px;
}
.es-discover-index {
  width: 220px;
}
.es-discover-query {
  width: 320px;
}
.es-console-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}
.es-toolbar-right {
  margin-left: auto;
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}
.es-body {
  display: flex;
  flex: 1;
  min-height: 0;
}
/* 索引列表 */
.es-index-pane {
  width: 340px;
  min-width: 280px;
  border-right: 1px solid var(--el-border-color-light, #ebeef5);
  display: flex;
  flex-direction: column;
}
.es-index-head {
  padding: 6px 10px;
  background: #f5f7fa;
  border-bottom: 1px solid var(--el-border-color-lighter, #f0f0f0);
  font-size: 12px;
  color: var(--imp-text-secondary);
}
.es-index-list {
  flex: 1;
  overflow: auto;
  padding: 4px;
}
.es-index-item {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 7px 8px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12.5px;
}
.es-index-item:hover {
  background: #f5f7fa;
}
.es-index-item.active {
  background: #ecf5ff;
}
.es-health-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.es-health-green { background: #67c23a; }
.es-health-yellow { background: #e6a23c; }
.es-health-red { background: #f56c6c; }
.es-health- { background: var(--imp-text-secondary); }
.es-index-name {
  flex: 1;
  min-width: 0;
  font-weight: 500;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.es-index-meta {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--imp-text-secondary);
}
.es-index-size {
  flex-shrink: 0;
  font-size: 11px;
  color: #c0c4cc;
}
/* 索引详情 */
.es-detail-pane {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.es-detail-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter, #f0f0f0);
  flex-wrap: wrap;
}
.es-detail-index {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.es-detail-toolbar {
  margin-left: auto;
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}
.es-detail-tabs {
  flex: 1;
  min-height: 0;
  padding: 6px 10px;
}
.es-detail-tabs :deep(.el-tabs__content) {
  height: calc(100% - 40px);
  overflow: auto;
}
.es-desc {
  margin-top: 4px;
}
.es-json-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.es-json-hint {
  font-size: 12px;
  color: var(--imp-text-secondary);
}
.es-json-view {
  background: #282c34;
  color: #abb2bf;
  border-radius: 4px;
  padding: 10px 12px;
  font-family: 'JetBrains Mono', Consolas, Menlo, monospace;
  font-size: 12px;
  line-height: 1.55;
  overflow: auto;
  max-height: 480px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}
.es-alias-table {
  max-height: 420px;
}
.es-detail-empty {
  margin: auto;
}
/* 数据浏览 */
.es-discover-body {
  flex-direction: column;
}
.es-discover-panel {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
.es-discover-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  background: #f5f7fa;
  border-bottom: 1px solid var(--el-border-color-lighter, #f0f0f0);
}
.es-discover-total {
  font-size: 12px;
  color: var(--imp-text-secondary);
}
.es-cost {
  color: var(--imp-text-secondary);
  margin-left: 4px;
}
.es-discover-pager {
  display: flex;
}
.es-doc-table {
  flex: 1;
  min-height: 200px;
}
.es-cell-obj {
  color: #409eff;
  font-family: Consolas, monospace;
  font-size: 12px;
}
/* 查询控制台 */
.es-console-body {
  flex-direction: row;
}
.es-console-left {
  width: 48%;
  min-width: 380px;
  border-right: 1px solid var(--el-border-color-light, #ebeef5);
  display: flex;
  flex-direction: column;
}
.es-console-req {
  display: flex;
  gap: 6px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--el-border-color-lighter, #f0f0f0);
  align-items: center;
}
.es-method-select {
  width: 100px;
}
.es-path-input {
  flex: 1;
}
.es-console-body-input {
  flex: 1;
  min-height: 0;
  position: relative;
  border-bottom: 1px solid var(--el-border-color-lighter, #f0f0f0);
}
.es-body-editor {
  position: absolute;
  inset: 0;
}
.es-body-editor :deep(.el-textarea__inner) {
  height: 100% !important;
  font-family: 'JetBrains Mono', Consolas, Menlo, monospace;
  font-size: 13px;
  line-height: 1.6;
  resize: none;
  background: #fafafa;
}
.es-console-samples {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 6px 8px;
  flex-wrap: wrap;
  background: #fafafa;
}
.es-samples-label {
  font-size: 12px;
  color: var(--imp-text-secondary);
  margin-right: 4px;
  flex-shrink: 0;
}
.es-console-right {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #282c34;
}
.es-console-resp-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border-bottom: 1px solid #333;
  background: #21252b;
}
.es-resp-title {
  font-size: 13px;
  font-weight: 600;
  color: #abb2bf;
}
.es-resp-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #abb2bf;
}
.es-resp-actions {
  margin-left: auto;
  display: flex;
  gap: 2px;
}
.es-resp-actions :deep(.el-button) {
  color: #61afef;
}
.es-console-resp {
  flex: 1;
  margin: 0;
  padding: 10px 14px;
  overflow: auto;
  font-family: 'JetBrains Mono', Consolas, Menlo, monospace;
  font-size: 12.5px;
  line-height: 1.6;
  color: #98c379;
  white-space: pre-wrap;
  word-break: break-all;
}
.es-resp-error {
  color: #e06c75;
}
/* 集群监控 */
.es-monitor-body {
  flex-direction: column;
  overflow: auto;
  padding: 12px;
  gap: 12px;
}
.es-monitor-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 10px;
}
.es-monitor-card {
  border: 1px solid var(--el-border-color-light, #ebeef5);
  border-radius: 4px;
  overflow: hidden;
}
.es-mc-title {
  padding: 6px 10px;
  font-size: 13px;
  font-weight: 600;
  background: #f5f7fa;
  border-bottom: 1px solid var(--el-border-color-lighter, #f0f0f0);
}
.es-mc-row {
  display: flex;
  justify-content: space-between;
  padding: 6px 10px;
  font-size: 12.5px;
  border-bottom: 1px solid var(--el-border-color-lighter, #f0f0f0);
}
.es-mc-label {
  color: var(--imp-text-secondary);
}
.es-mc-value {
  color: #303133;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 6px;
}
.es-nodes-card {
  border: 1px solid var(--el-border-color-light, #ebeef5);
  border-radius: 4px;
  overflow: hidden;
}
.es-nodes-table {
  width: 100%;
}
.es-form-json :deep(.el-textarea__inner) {
  font-family: 'JetBrains Mono', Consolas, Menlo, monospace;
  font-size: 12.5px;
  line-height: 1.6;
}
.es-doc-view {
  max-height: 560px;
}
</style>
