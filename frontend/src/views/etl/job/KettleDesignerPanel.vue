<template>
  <div class="kettle-designer-panel">
    <!-- 顶部操作 -->
    <div class="kdp-actions">
      <span class="kdp-title">可视化设计器</span>
      <span class="kdp-tip">{{ tipText }}</span>
      <div v-if="kettleType !== 'TRANS'" class="kdp-btns">
        <el-button size="small" type="success" plain icon="MagicStick" :loading="generating" @click="genDesignXml">生成 XML</el-button>
        <el-button size="small" type="primary" icon="Check" @click="applyDesign">应用到表单</el-button>
      </div>
    </div>

    <div class="kdp-body">
      <!-- JOB: 节点库 -->
      <div v-if="kettleType === 'JOB'" class="kdp-toolbar">
        <div class="kdp-toolbar-title">节点库</div>
        <el-button type="primary" link size="small" @click="addSqlNode">+ SQL 执行</el-button>
        <el-divider style="margin: 6px 0" />
        <div class="kdp-toolbar-title">节点列表</div>
        <div v-for="n in nodes" :key="n.id" class="kdp-node-item" :class="{ active: n.id === selectedId }" @click="selectNode(n.id)">
          <el-icon><Document /></el-icon>
          <span class="kdp-node-item-name">{{ n.props.name || 'SQL' }}</span>
          <el-icon class="kdp-node-item-del" @click.stop="removeNode(n.id)"><Close /></el-icon>
        </div>
        <el-empty v-if="!nodes.length" description="暂无节点" :image-size="40" />
      </div>

      <!-- 画布 -->
      <div class="kdp-canvas" @click.self="selectNode(null)">
        <svg class="kdp-edges" :width="canvasW" :height="canvasH">
          <line v-for="e in edges" :key="e.id"
                :x1="nodeCenter(nodes.find(n => n.id === e.fromNodeId))?.x" :y1="nodeCenter(nodes.find(n => n.id === e.fromNodeId))?.y"
                :x2="nodeCenter(nodes.find(n => n.id === e.toNodeId))?.x" :y2="nodeCenter(nodes.find(n => n.id === e.toNodeId))?.y"
                class="kdp-edge" />
        </svg>
        <div v-for="n in nodes" :key="n.id" class="kdp-node" :class="[n.type, { selected: n.id === selectedId }]"
             :style="{ left: n.x + 'px', top: n.y + 'px' }"
             @mousedown="startDrag($event, n)" @click.stop="selectNode(n.id)">
          <div class="kdp-node-head">
            <el-icon><component :is="nodeIcon(n.type)" /></el-icon>
            <span>{{ nodeLabel(n.type) }}</span>
          </div>
          <div class="kdp-node-body">{{ nodeSummary(n) }}</div>
        </div>
        <div v-if="!nodes.length" class="kdp-empty">{{ kettleType === 'JOB' ? '从左侧节点库添加「SQL 执行」节点开始设计' : '配置数据源后自动生成 表输入 → 表输出; 多个源表时自动加入追加流合并' }}</div>
      </div>

      <!-- 属性面板 / TRANS 右侧数据源配置栏 -->
      <div v-if="kettleType === 'TRANS'" class="kdp-right">
        <div class="kdp-btns">
          <el-button size="small" type="success" plain icon="MagicStick" :loading="generating" @click="genDesignXml">生成 XML</el-button>
          <el-button size="small" type="primary" icon="Check" @click="applyDesign">应用到表单</el-button>
        </div>
        <el-divider content-position="left">数据源 (源)<el-button link type="primary" size="small" icon="Plus" style="margin-left: 6px" @click="addSrcRow">添加源表</el-button></el-divider>
        <div v-for="(row, idx) in srcRows" :key="idx" class="kdp-src-row">
          <div class="kdp-src-row-head">
            <span>源 {{ idx + 1 }}</span>
            <el-button v-if="srcRows.length > 1" link type="danger" size="small" icon="Delete" @click="removeSrcRow(idx)" />
          </div>
          <el-form label-width="64px" size="small">
            <el-form-item label="数据源">
              <el-select v-model="row.srcConnId" filterable placeholder="选择源数据源" style="width: 100%" @change="v => onSrcConnChange(idx, v)">
                <el-option v-for="c in connList" :key="c.id" :label="c.connName + ' (' + c.dbType + ')'" :value="c.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="数据库">
              <el-select v-model="row.srcSchema" filterable clearable placeholder="默认库" style="width: 100%" @change="v => onSrcDbChange(idx, v)">
                <el-option v-for="d in srcDbOpts[idx] || []" :key="d" :label="d" :value="d" />
              </el-select>
            </el-form-item>
            <el-form-item label="源表">
              <el-select v-model="row.srcTable" filterable allow-create clearable default-first-option placeholder="输入或选择表" style="width: 100%">
                <el-option v-for="t in srcTableOpts[idx] || []" :key="t" :label="t.tableName || t" :value="t.tableName || t" />
              </el-select>
            </el-form-item>
            <el-form-item label="过滤条件">
              <el-input v-model="row.srcWhere" placeholder="可选, 如: status = '1'" />
            </el-form-item>
          </el-form>
        </div>
        <el-divider content-position="left">数据源 (目标)</el-divider>
        <el-form label-width="64px" size="small">
          <el-form-item label="数据源">
            <el-select v-model="form.dstConnId" filterable placeholder="选择目标数据源" style="width: 100%" @change="onDstConnChange">
              <el-option v-for="c in connList" :key="c.id" :label="c.connName + ' (' + c.dbType + ')'" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="数据库">
            <el-select v-model="form.dstSchema" filterable clearable placeholder="默认库" style="width: 100%" @change="onDstDbChange">
              <el-option v-for="d in dstDbs" :key="d" :label="d" :value="d" />
            </el-select>
          </el-form-item>
          <el-form-item label="目标表">
            <el-select v-model="form.dstTable" filterable allow-create clearable default-first-option placeholder="输入或选择表" style="width: 100%">
              <el-option v-for="t in dstTables" :key="t" :label="t.tableName || t" :value="t.tableName || t" />
            </el-select>
          </el-form-item>
          <el-form-item label="写入模式">
            <el-radio-group v-model="form.dstWriteMode">
              <el-radio-button value="INSERT">INSERT 追加</el-radio-button>
              <el-radio-button value="REPLACE">REPLACE 覆盖</el-radio-button>
              <el-radio-button value="TRUNCATE">TRUNCATE 清空</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="批大小">
            <el-input-number v-model="form.batchSize" :min="1" :step="256" style="width: 100%" />
          </el-form-item>
        </el-form>
      </div>
      <div v-if="selectedNode && selectedNode.type === 'sql'" class="kdp-props">
        <div class="kdp-props-title">{{ selectedNode.props.name || 'SQL 执行' }} 属性</div>
        <el-form label-width="70px" size="small">
          <el-form-item label="名称">
            <el-input v-model="selectedNode.props.name" />
          </el-form-item>
          <el-form-item label="数据源" required>
            <el-select v-model="selectedNode.props.connId" filterable placeholder="选择数据源" style="width: 100%" @change="v => loadDbs(v)">
              <el-option v-for="c in connList" :key="c.id" :label="c.connName + ' (' + c.dbType + ')'" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="数据库">
            <el-select v-model="selectedNode.props.schema" filterable clearable placeholder="默认库" style="width: 100%" @change="v => loadTables(selectedNode.props.connId, v)">
              <el-option v-for="d in dbMap[selectedNode.props.connId] || []" :key="d" :label="d" :value="d" />
            </el-select>
          </el-form-item>
          <el-form-item label="SQL" required>
            <el-input v-model="selectedNode.props.sql" type="textarea" :rows="6" placeholder="如: TRUNCATE TABLE `sys_menu`&#10;如: UPDATE `sys_user` SET `status`='0' WHERE `user_id`=1" />
          </el-form-item>
        </el-form>
      </div>
      <div v-else-if="kettleType === 'JOB'" class="kdp-props kdp-props-empty">选择画布节点编辑属性</div>
    </div>

    <!-- XML 预览 -->
    <div class="kdp-preview">
      <div class="kdp-preview-head">
        <span>XML 预览</span>
        <el-button size="small" type="warning" plain icon="Delete" @click="previewXml = ''">清空</el-button>
      </div>
      <el-input v-model="previewXml" type="textarea" :rows="6" class="kdp-preview-xml" placeholder="生成后展示 Kettle XML, 可手动微调; 「应用到表单」回填到任务 XML" />
    </div>
  </div>
</template>

<script setup name="EtlKettleDesignerPanel">
import { ref, computed, watch, getCurrentInstance } from 'vue'
import { designKettleXml, listDatabases, listTables } from '@/api/etl/index'

const props = defineProps({
  kettleType: { type: String, default: 'TRANS' },
  connList: { type: Array, default: () => [] },
  form: { type: Object, required: true },
  jobName: { type: String, default: '' }
})
const emit = defineEmits(['apply'])

const { proxy } = getCurrentInstance()

const nodes = ref([])
const edges = ref([])
const selectedId = ref(null)
const previewXml = ref('')
const generating = ref(false)
const dbMap = ref({})
const tableMap = ref({})
const dragState = ref(null)
const srcRows = ref([])
const srcDbOpts = ref({})
const srcTableOpts = ref({})
const dstDbs = ref([])
const dstTables = ref([])
const canvasW = 620
const canvasH = 300
let seq = 0
function uid() { seq += 1; return 'n' + Date.now() + '_' + seq }

const tipText = computed(() => props.kettleType === 'JOB'
  ? '作业由 SQL 执行作业项串行组成, 每个节点配置数据源与 SQL'
  : '在右侧配置数据源(源)/数据源(目标), 支持多源表 → 追加流合并 → 单目标表')

const nodeTypeMap = { tableInput: '表输入', tableOutput: '表输出', append: '追加流', sql: 'SQL 执行' }
const nodeIconMap = { tableInput: 'Download', tableOutput: 'Upload', append: 'Connection', sql: 'Document' }
function nodeLabel(t) { return nodeTypeMap[t] || t }
function nodeIcon(t) { return nodeIconMap[t] || 'Cpu' }
const selectedNode = computed(() => nodes.value.find(n => n.id === selectedId.value) || null)

// 节点概要: TRANS 实时引用表单配置, JOB 读节点 props
function nodeSummary(n) {
  if (n.type === 'tableInput') {
    const idx = n.id === 'tin' ? 0 : ((parseInt((n.id || '').replace('tin', '')) || 1) - 1)
    const r = srcRows.value[idx] || {}
    return (r.srcSchema ? r.srcSchema + '.' : '') + (r.srcTable || '未选表') + (r.srcWhere ? ' WHERE ' + r.srcWhere : '')
  }
  if (n.type === 'append') {
    return '追加流 (' + Math.max(1, srcRows.value.length) + ' 路合并)'
  }
  if (n.type === 'tableOutput') {
    const f = props.form
    return (f.dstSchema ? f.dstSchema + '.' : '') + (f.dstTable || '未选表') + ' [' + (f.dstWriteMode || 'INSERT') + ']'
  }
  if (n.type === 'sql') {
    const p = n.props
    return p.sql ? p.sql.replace(/\s+/g, ' ').slice(0, 40) + (p.sql.length > 40 ? '…' : '') : '未填写 SQL'
  }
  return ''
}

function nodeCenter(n) {
  if (!n) return { x: 0, y: 0 }
  return { x: n.x + 86, y: n.y + 40 }
}

// ===== 节点管理 =====
function ensureTransNodes() {
  const n = Math.max(1, srcRows.value.length)
  const list = []
  const es = []
  if (n <= 1) {
    list.push({ id: 'tin', type: 'tableInput', x: 30, y: 100, props: {} })
    list.push({ id: 'tout', type: 'tableOutput', x: 340, y: 100, props: {} })
    es.push({ id: 'e1', fromNodeId: 'tin', toNodeId: 'tout' })
  } else {
    for (let i = 0; i < n; i++) {
      list.push({ id: 'tin' + (i + 1), type: 'tableInput', x: 30, y: 40 + i * 80, props: {} })
      es.push({ id: 'ei' + (i + 1), fromNodeId: 'tin' + (i + 1), toNodeId: 'append' })
    }
    list.push({ id: 'append', type: 'append', x: 230, y: 60, props: {} })
    es.push({ id: 'eo', fromNodeId: 'append', toNodeId: 'tout' })
    list.push({ id: 'tout', type: 'tableOutput', x: 430, y: 100, props: {} })
  }
  nodes.value = list
  edges.value = es
  selectedId.value = null
}
function addSqlNode() {
  const n = { id: uid(), type: 'sql', x: 60 + nodes.value.length * 24, y: 90 + nodes.value.length * 24, props: { name: 'SQL-' + (nodes.value.filter(x => x.type === 'sql').length + 1), connId: undefined, schema: undefined, sql: '' } }
  nodes.value.push(n)
  reorderJobEdges()
  selectedId.value = n.id
}
function removeNode(id) {
  nodes.value = nodes.value.filter(n => n.id !== id)
  edges.value = edges.value.filter(e => e.fromNodeId !== id && e.toNodeId !== id)
  if (selectedId.value === id) selectedId.value = null
  reorderJobEdges()
}
function selectNode(id) { selectedId.value = id }
function reorderJobEdges() {
  const sqls = nodes.value.filter(n => n.type === 'sql')
  edges.value = []
  for (let i = 0; i + 1 < sqls.length; i++) {
    edges.value.push({ id: uid(), fromNodeId: sqls[i].id, toNodeId: sqls[i + 1].id })
  }
}

// ===== 拖动 =====
function startDrag(e, n) {
  e.preventDefault()
  selectedId.value = n.id
  dragState.value = { id: n.id, startX: e.clientX, startY: e.clientY, ox: n.x, oy: n.y }
  window.addEventListener('mousemove', onDragMove)
  window.addEventListener('mouseup', onDragEnd)
}
function onDragMove(e) {
  const st = dragState.value
  if (!st) return
  const node = nodes.value.find(x => x.id === st.id)
  if (!node) return
  node.x = Math.max(0, Math.min(canvasW - 172, st.ox + (e.clientX - st.startX)))
  node.y = Math.max(0, Math.min(canvasH - 64, st.oy + (e.clientY - st.startY)))
}
function onDragEnd() {
  dragState.value = null
  window.removeEventListener('mousemove', onDragMove)
  window.removeEventListener('mouseup', onDragEnd)
}

// ===== 库/表联动 (TRANS 多源 + JOB SQL 节点) =====
function addSrcRow() {
  srcRows.value.push({ srcConnId: undefined, srcSchema: undefined, srcTable: undefined, srcWhere: undefined })
  syncToForm()
}
function removeSrcRow(idx) {
  srcRows.value.splice(idx, 1)
  if (!srcRows.value.length) addSrcRow()
  syncToForm()
}
function onSrcConnChange(idx, id) {
  const row = srcRows.value[idx]
  if (!row) return
  row.srcSchema = undefined
  row.srcTable = undefined
  srcDbOpts.value[idx] = []
  srcTableOpts.value[idx] = []
  if (id) listDatabases(id).then(res => { srcDbOpts.value[idx] = res.data || res.rows || [] })
}
function onSrcDbChange(idx, db) {
  const row = srcRows.value[idx]
  if (!row) return
  row.srcTable = undefined
  srcTableOpts.value[idx] = []
  if (row.srcConnId && db) listTables(row.srcConnId, db).then(res => { srcTableOpts.value[idx] = res.data || res.rows || [] })
}
function onDstConnChange(id) {
  props.form.dstSchema = undefined
  props.form.dstTable = undefined
  dstDbs.value = []
  dstTables.value = []
  if (id) listDatabases(id).then(res => { dstDbs.value = res.data || res.rows || [] })
}
function onDstDbChange(db) {
  props.form.dstTable = undefined
  dstTables.value = []
  if (props.form.dstConnId && db) listTables(props.form.dstConnId, db).then(res => { dstTables.value = res.data || res.rows || [] })
}
function syncToForm() {
  const json = JSON.stringify(srcRows.value)
  if (json !== props.form.srcList) props.form.srcList = json
  const f0 = srcRows.value[0]
  if (f0) {
    props.form.srcConnId = f0.srcConnId
    props.form.srcSchema = f0.srcSchema
    props.form.srcTable = f0.srcTable
    props.form.srcWhere = f0.srcWhere
  }
}
function rebuildFromSrcList(json) {
  let list = []
  try {
    const arr = JSON.parse(json)
    if (Array.isArray(arr)) list = arr.filter(r => r && typeof r === 'object')
  } catch (e) { /* ignore */ }
  if (!list.length) {
    list = [{ srcConnId: props.form.srcConnId, srcSchema: props.form.srcSchema, srcTable: props.form.srcTable, srcWhere: props.form.srcWhere }]
  }
  srcRows.value = list
  srcDbOpts.value = {}
  srcTableOpts.value = {}
  list.forEach((row, i) => {
    if (row.srcConnId) listDatabases(row.srcConnId).then(res => { srcDbOpts.value[i] = res.data || res.rows || [] })
    if (row.srcConnId && row.srcSchema) listTables(row.srcConnId, row.srcSchema).then(res => { srcTableOpts.value[i] = res.data || res.rows || [] })
  })
}
function loadDbs(connId) {
  if (!connId || dbMap.value[connId]) return
  listDatabases(connId).then(res => { dbMap.value[connId] = res.data || res.rows || [] })
}
function loadTables(connId, schema) {
  if (!connId || !schema) return
  const k = connId + ':' + schema
  if (tableMap.value[k]) return
  listTables(connId, schema).then(res => {
    tableMap.value[k] = (res.data || res.rows || []).map(t => t.tableName || t)
  })
}
// 编辑回显: 目标数据源与库变化时加载对应库/表下拉
watch(() => props.form.dstConnId, v => { if (v) listDatabases(v).then(res => { dstDbs.value = res.data || res.rows || [] }) })
watch(() => props.form.dstSchema, v => { if (props.form.dstConnId && v) listTables(props.form.dstConnId, v).then(res => { dstTables.value = res.data || res.rows || [] }) })

// ===== 生成 / 应用 =====
function genDesignXml() {
  if (props.kettleType === 'JOB') {
    const sqls = nodes.value.filter(n => n.type === 'sql')
    if (!sqls.length) { proxy.$modal.msgWarning('作业设计至少需要一个「SQL 执行」节点'); return }
    if (sqls.some(n => !n.props.connId || !n.props.sql)) { proxy.$modal.msgWarning('请为每个 SQL 节点填写数据源与 SQL'); return }
    doGen({
      nodes: nodes.value.map(n => ({ id: n.id, type: n.type, x: n.x, y: n.y, props: n.props })),
      edges: edges.value.map(e => ({ id: e.id, fromNodeId: e.fromNodeId, toNodeId: e.toNodeId }))
    })
    return
  }
  // TRANS (支持多源表 → 追加流 → 单目标表)
  const f = props.form
  const rows = srcRows.value.length ? srcRows.value : [{ srcConnId: f.srcConnId, srcSchema: f.srcSchema, srcTable: f.srcTable, srcWhere: f.srcWhere }]
  if (!rows.length || rows.some(r => !r.srcConnId || !r.srcTable)) { proxy.$modal.msgWarning('请完善所有源表(数据源 + 源表)'); return }
  if (!f.dstConnId || !f.dstTable) { proxy.$modal.msgWarning('请在「数据源(目标)」中填写目标数据源与目标表'); return }
  const outNode = nodes.value.find(n => n.type === 'tableOutput') || { x: 340, y: 100 }
  const inNodes = rows.map((r, i) => ({
    id: rows.length > 1 ? 'tin' + (i + 1) : 'tin',
    type: 'tableInput',
    x: 30,
    y: 100,
    props: { srcConnId: r.srcConnId, srcSchema: r.srcSchema, srcTable: r.srcTable, srcWhere: r.srcWhere }
  }))
  doGen({
    nodes: [
      ...inNodes,
      { id: 'tout', type: 'tableOutput', x: outNode.x, y: outNode.y, props: { dstConnId: f.dstConnId, dstSchema: f.dstSchema, dstTable: f.dstTable, writeMode: f.dstWriteMode, batchSize: f.batchSize } }
    ],
    edges: []
  })
}
function doGen(payload) {
  generating.value = true
  designKettleXml({ kettleType: props.kettleType, jobName: props.jobName || props.form.jobName || '', ...payload }).then(res => {
    if (res.data) { previewXml.value = res.data } else { proxy.$modal.msgError(res.msg || '生成失败') }
  }).finally(() => { generating.value = false })
}
function applyDesign() {
  if (!previewXml.value) { proxy.$modal.msgWarning('请先点击「生成 XML」'); return }
  emit('apply', previewXml.value)
  proxy.$modal.msgSuccess('已应用到任务 XML')
}

// ===== 初始化 =====
watch(() => props.kettleType, t => {
  nodes.value = []
  edges.value = []
  selectedId.value = null
  if (t === 'TRANS') ensureTransNodes()
}, { immediate: true })

// 多源列表同步到 form.srcList(持久化) + 第一源同步到 form 单源字段(列表页展示)
watch(srcRows, () => { syncToForm() }, { deep: true })
// 编辑回显: form.srcList 就绪后重建多源列表
watch(() => props.form.srcList, v => {
  if (v) rebuildFromSrcList(v)
  else {
    srcRows.value = [{ srcConnId: props.form.srcConnId, srcSchema: props.form.srcSchema, srcTable: props.form.srcTable, srcWhere: props.form.srcWhere }]
    syncToForm()
  }
}, { immediate: true })
// 源数量变化 → 重建画布节点(追加流自动插入)
watch(() => srcRows.value.length, () => {
  if (props.kettleType === 'TRANS') ensureTransNodes()
})

// 编辑回显: 表单 XML 回填时同步到预览区(仅在预览为空时, 不覆盖用户手动生成内容)
watch(() => props.form.kettleXml, v => {
  if (v && !previewXml.value) previewXml.value = v
}, { immediate: true })
</script>

<style lang="scss" scoped>
.kettle-designer-panel {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px;
  background: var(--el-fill-color-blank);
  .kdp-actions {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 10px;
    flex-wrap: wrap;
    .kdp-title { font-size: 14px; font-weight: 600; color: var(--el-text-color-primary); }
    .kdp-tip { font-size: 12px; color: var(--el-text-color-secondary); flex: 1; min-width: 200px; }
    .kdp-btns { display: flex; gap: 8px; }
  }
  .kdp-body {
    display: flex;
    gap: 10px;
    align-items: stretch;
  }
  .kdp-toolbar {
    width: 150px;
    flex-shrink: 0;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 6px;
    padding: 8px;
    display: flex;
    flex-direction: column;
    gap: 4px;
    .kdp-toolbar-title { font-size: 12px; font-weight: 600; color: var(--el-text-color-secondary); }
    .kdp-node-item {
      display: flex; align-items: center; gap: 6px; padding: 4px 6px; border-radius: 4px; cursor: pointer;
      font-size: 13px; color: var(--el-text-color-regular);
      &:hover { background: var(--el-fill-color-light); }
      &.active { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
      .kdp-node-item-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .kdp-node-item-del { opacity: .5; &:hover { opacity: 1; color: var(--el-color-danger); } }
    }
  }
  .kdp-canvas {
    position: relative;
    flex: 1;
    height: 300px;
    border: 1px dashed var(--el-border-color);
    border-radius: 6px;
    background-image: linear-gradient(rgba(0,0,0,.03) 1px, transparent 1px), linear-gradient(90deg, rgba(0,0,0,.03) 1px, transparent 1px);
    background-size: 20px 20px;
    overflow: hidden;
    .kdp-edges {
      position: absolute; left: 0; top: 0; pointer-events: none;
      .kdp-edge { stroke: var(--el-color-primary); stroke-width: 2; stroke-dasharray: 6 4; }
    }
    .kdp-node {
      position: absolute; width: 172px;
      border: 1px solid var(--el-border-color); border-radius: 8px; background: #fff;
      box-shadow: 0 1px 4px rgba(0,0,0,.06);
      cursor: move; user-select: none;
      .kdp-node-head {
        display: flex; align-items: center; gap: 6px;
        padding: 6px 8px; border-bottom: 1px solid var(--el-border-color-lighter);
        border-radius: 8px 8px 0 0;
        font-size: 13px; font-weight: 600;
        background: var(--el-fill-color-light);
      }
      .kdp-node-body {
        padding: 8px; font-size: 12px; color: var(--el-text-color-regular);
        word-break: break-all; min-height: 34px;
      }
      &.selected { border-color: var(--el-color-primary); box-shadow: 0 0 0 2px var(--el-color-primary-light-7); }
      &.tableInput .kdp-node-head { background: var(--el-color-success-light-9); color: var(--el-color-success); }
      &.tableOutput .kdp-node-head { background: var(--el-color-warning-light-9); color: var(--el-color-warning); }
      &.sql .kdp-node-head { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
    }
    .kdp-empty {
      position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
      color: var(--el-text-color-placeholder); font-size: 13px;
    }
  }
  .kdp-right {
    width: 340px;
    flex-shrink: 0;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 6px;
    padding: 10px;
    overflow-y: auto;
    max-height: 480px;
    .kdp-btns { display: flex; gap: 8px; margin-bottom: 4px; }
    .el-divider { margin: 10px 0 8px; }
    .kdp-src-row {
      border: 1px dashed var(--el-border-color-lighter);
      border-radius: 6px;
      padding: 6px 8px;
      margin-bottom: 8px;
      .kdp-src-row-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        font-size: 12px;
        font-weight: 600;
        color: var(--el-text-color-secondary);
        margin-bottom: 4px;
      }
    }
  }
  .kdp-props {
    width: 230px; flex-shrink: 0;
    border: 1px solid var(--el-border-color-lighter); border-radius: 6px; padding: 8px;
    .kdp-props-title { font-size: 13px; font-weight: 600; margin-bottom: 8px; color: var(--el-text-color-primary); }
    &.kdp-props-readonly {
      display: flex; flex-direction: column; gap: 8px;
      color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6;
      .el-icon { color: var(--el-color-primary); font-size: 16px; }
    }
    &.kdp-props-empty { display: flex; align-items: center; justify-content: center; color: var(--el-text-color-placeholder); font-size: 12px; }
  }
  .kdp-preview {
    margin-top: 10px;
    .kdp-preview-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; font-size: 13px; font-weight: 600; }
  }
}
</style>
