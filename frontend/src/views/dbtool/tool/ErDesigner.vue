<template>
  <div class="er-designer">
    <div class="er-toolbar">
      <div class="er-tb-group">
        <el-tooltip content="刷新" placement="bottom"><el-button size="small" circle icon="Refresh" @click="renderEr" /></el-tooltip>
        <el-tooltip content="居中显示" placement="bottom"><el-button size="small" circle icon="FullScreen" @click="fitView" /></el-tooltip>
        <el-tooltip content="放大" placement="bottom"><el-button size="small" circle icon="ZoomIn" @click="zoomIn" /></el-tooltip>
        <el-tooltip content="缩小" placement="bottom"><el-button size="small" circle icon="ZoomOut" @click="zoomOut" /></el-tooltip>
        <span class="er-zoom">{{ Math.round(scale * 100) }}%</span>
      </div>
      <div class="er-tb-group">
        <el-tooltip content="撤销 (Ctrl+Z)" placement="bottom"><el-button size="small" circle icon="Back" :disabled="!undoStack.length" @click="undo" /></el-tooltip>
        <el-tooltip content="重做 (Ctrl+Y)" placement="bottom"><el-button size="small" circle icon="Right" :disabled="!redoStack.length" @click="redo" /></el-tooltip>
        <el-tooltip content="自动布局 (避免重叠)" placement="bottom"><el-button size="small" circle icon="Grid" @click="autoLayout" /></el-tooltip>
        <el-tooltip content="全选/取消全选" placement="bottom"><el-button size="small" circle icon="Select" :type="selectedAll ? 'primary' : 'default'" @click="toggleSelectAll" /></el-tooltip>
      </div>
      <div class="er-tb-group">
        <el-button size="small" type="primary" plain icon="Plus" @click="openAddTable">添加表</el-button>
        <el-tooltip content="新增连线" placement="bottom"><el-button size="small" circle type="primary" plain icon="Link" @click="openEditRel(null)" /></el-tooltip>
        <el-tooltip content="编辑连线" placement="bottom"><el-button size="small" circle type="warning" plain icon="EditPen" :disabled="!selectedRel" @click="openEditRel(selectedRel)" /></el-tooltip>
        <el-tooltip content="删除连线" placement="bottom"><el-button size="small" circle type="danger" plain icon="Delete" :disabled="!selectedRel" @click="removeRel" /></el-tooltip>
      </div>
      <div class="er-tb-group">
        <el-tooltip content="保存布局" placement="bottom"><el-button size="small" circle type="success" plain icon="FolderChecked" @click="saveLayout" /></el-tooltip>
        <el-dropdown trigger="click" size="small" @command="onExport">
          <el-button size="small" icon="Download">
            导出<el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="png">导出图片 (PNG)</el-dropdown-item>
              <el-dropdown-item command="pdf">导出 PDF</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <span class="er-saved">最后保存 {{ lastSaved || '未保存' }}</span>
      </div>
      <span class="er-hint">拖动空白处平移 · 滚轮缩放 · Ctrl/Shift 点选多表 · 拖动端点改连/换边 · 拖中点调弯曲 · 拖标签沿线滑动</span>
    </div>
    <div ref="canvasRef" class="er-canvas" @wheel.prevent="onWheel" @mousedown="onCanvasMouseDown">
      <div class="er-viewport" :style="viewportStyle">
        <svg class="er-svg" :width="svgWidth" :height="svgHeight">
          <defs>
            <marker id="er-arr-1n" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
              <polygon points="0 0, 8 3, 0 6" fill="#94a3b8" />
            </marker>
            <marker id="er-arr-11" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
              <polygon points="0 0, 8 3, 0 6" fill="#409eff" />
            </marker>
            <marker id="er-arr-nm" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
              <polygon points="0 0, 8 3, 0 6" fill="#67c23a" />
            </marker>
          </defs>
          <g v-for="rel in visibleRels" :key="rel.id" class="er-rel-group">
            <path
              :d="rel.path"
              :stroke="relStyle(rel).color"
              :stroke-width="selectedRel && selectedRel.id === rel.id ? 2.5 : 1.6"
              :stroke-dasharray="relStyle(rel).dash"
              :marker-end="'url(#er-arr-' + relStyle(rel).marker + ')'"
              fill="none"
              class="er-rel-line"
              :class="{ 'er-rel-selected': selectedRel && selectedRel.id === rel.id }"
              @click.stop="selectRel(rel)"
              @dblclick.stop="openEditRel(rel)"
            />
            <g v-for="(end, ei) in relEnds(rel)" :key="'e' + ei" :transform="`translate(${end.x}, ${end.y}) rotate(${end.rot})`" class="er-rel-cardinal">
              <template v-if="end.mark === '1'">
                <line x1="0" y1="-5" x2="0" y2="5" :stroke="relStyle(rel).color" stroke-width="1.8" />
              </template>
              <template v-else>
                <line x1="0" y1="-5" x2="0" y2="5" :stroke="relStyle(rel).color" stroke-width="1.8" />
                <line x1="0" y1="-5" x2="-5" y2="-10" :stroke="relStyle(rel).color" stroke-width="1.8" />
                <line x1="0" y1="5" x2="-5" y2="10" :stroke="relStyle(rel).color" stroke-width="1.8" />
              </template>
            </g>
            <g
              class="er-rel-label"
              :transform="`translate(${relLabelPos(rel).x}, ${relLabelPos(rel).y})`"
              @click.stop="selectRel(rel)"
              @dblclick.stop="openEditRel(rel)"
              @mousedown.stop="startDragLabel($event, rel)"
            >
              <rect x="-14" y="-8" width="28" height="16" rx="8" fill="#ffffff" stroke="#cbd5e1" stroke-width="1" />
              <text x="0" y="3" text-anchor="middle" font-size="8" fill="#475569" font-weight="600" font-family="inherit">{{ rel.cardinality }}</text>
            </g>
            <circle
              :cx="rel.x1" :cy="rel.y1" r="6"
              :fill="selectedRel && selectedRel.id === rel.id ? '#409eff' : 'transparent'"
              stroke="#409eff" stroke-width="2"
              class="er-rel-handle"
              @mousedown.stop="startDragHandle($event, rel, 'src')"
            />
            <circle
              :cx="rel.x2" :cy="rel.y2" r="6"
              :fill="selectedRel && selectedRel.id === rel.id ? '#409eff' : 'transparent'"
              stroke="#409eff" stroke-width="2"
              class="er-rel-handle"
              @mousedown.stop="startDragHandle($event, rel, 'dst')"
            />
            <circle
              v-if="selectedRel && selectedRel.id === rel.id"
              :cx="rel.mx" :cy="rel.my" r="5.5"
              fill="#ffffff" stroke="#409eff" stroke-width="2"
              class="er-bend-handle"
              @mousedown.stop="startBendDrag($event, rel)"
            />
            <circle
              v-if="handleDragPreview.visible"
              :cx="handleDragPreview.x" :cy="handleDragPreview.y" r="6"
              fill="#409eff" stroke="#ffffff" stroke-width="2"
              class="er-preview-dot"
            />
          </g>
        </svg>
        <div
          v-for="(node, i) in nodes"
          :key="'node-' + i"
          class="er-table-node"
          :class="{ 'er-table-main': node.isMain, 'er-node-all': node.selected }"
          :style="{ left: node.x + 'px', top: node.y + 'px', width: node.width + 'px' }"
          @mousedown="startDrag($event, node)"
          @click.stop
        >
          <div class="er-table-header">
            <el-icon class="er-tb-icon"><Document /></el-icon>
            <span class="er-table-name">{{ node.name }}</span>
            <span v-if="node.comment" class="er-table-comment" :title="node.comment">{{ node.comment }}</span>
          </div>
          <div class="er-table-body">
            <div
              v-for="(col, ci) in node.columns"
              :key="ci"
              class="er-col-row"
              :class="{ 'er-col-pk-row': col.columnKey === 'PRI' }"
              :data-col="col.columnName"
            >
              <span class="er-col-badge" v-if="col.columnKey === 'PRI'" title="主键">
                <svg viewBox="0 0 16 16" width="11" height="11" fill="none" stroke="currentColor" stroke-width="1.4"><rect x="2.5" y="6.5" width="11" height="7" rx="2"/><path d="M5.5 6.5V5a2.5 2.5 0 0 1 5 0v1.5"/></svg>
              </span>
              <span class="er-col-badge er-col-badge-fk" v-else-if="node.fkSet.has(col.columnName)" title="外键">
                <svg viewBox="0 0 16 16" width="11" height="11" fill="none" stroke="currentColor" stroke-width="1.4"><path d="M13 8l-3.5-3.5M13 8L9.5 11.5M13 8H3"/></svg>
              </span>
              <span class="er-col-badge er-col-badge-plain" v-else />
              <span class="er-col-name">{{ col.columnName }}</span>
              <span class="er-col-type" :style="typeStyle(col)">{{ shortType(col.columnType) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
    <div v-if="!nodes.length" class="er-empty">
      <el-empty description="暂无表数据，请先在属性页加载表结构" />
    </div>

    <!-- 连线编辑弹窗 -->
    <el-dialog v-model="relDialog.visible" :title="relDialog.id ? '编辑连接线' : '新增连接线'" width="480px" append-to-body destroy-on-close>
      <el-form label-width="70px">
        <el-form-item label="源表" required>
          <el-select v-model="relDialog.srcTable" filterable style="width: 100%" @change="onSrcTableChange">
            <el-option v-for="n in nodes" :key="n.name" :label="n.name" :value="n.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="源字段" required>
          <el-select v-model="relDialog.srcColumn" filterable allow-create default-first-option style="width: 100%">
            <el-option v-for="c in srcCols" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标表" required>
          <el-select v-model="relDialog.dstTable" filterable style="width: 100%" @change="onDstTableChange">
            <el-option v-for="n in nodes" :key="n.name" :label="n.name" :value="n.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标字段" required>
          <el-select v-model="relDialog.dstColumn" filterable allow-create default-first-option style="width: 100%">
            <el-option v-for="c in dstCols" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="关系类型">
          <el-radio-group v-model="relDialog.cardinality">
            <el-radio-button value="1:1">1 : 1</el-radio-button>
            <el-radio-button value="1:N">1 : N</el-radio-button>
            <el-radio-button value="N:M">N : M</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" :disabled="!relDialog.srcTable || !relDialog.dstTable" @click="saveRel">保存</el-button>
        <el-button @click="relDialog.visible = false">取消</el-button>
      </template>
    </el-dialog>

    <!-- 添加表(融合添加字段)弹窗 -->
    <el-dialog v-model="addTableDialog.visible" title="添加表" width="760px" append-to-body destroy-on-close>
      <div class="at-wrapper">
        <div class="at-left">
          <div class="at-side-title">表清单</div>
          <el-input v-model="addTableDialog.keyword" placeholder="搜索表名" clearable size="small" class="at-search" />
          <div class="at-table-list">
            <div
              v-for="t in erTableList" :key="t.tableName"
              class="at-table-item"
              :class="{ 'at-active': addTableDialog.selectedTable === t.tableName }"
              @click="onPickTable(t.tableName)"
            >{{ t.tableName }}</div>
            <el-empty v-if="!erTableList.length" description="暂无表，请从右侧添加" :image-size="40" />
          </div>
        </div>
        <div class="at-right">
          <el-form label-width="66px" class="at-form">
            <div class="at-form-row">
              <el-form-item label="数据源" required class="at-fi">
                <el-select
                  v-model="addTableDialog.connId"
                  size="small" placeholder="选择数据源" filterable
                  style="width: 100%"
                  @change="onPickConn"
                >
                  <el-option v-for="c in connList" :key="c.id" :label="c.connName" :value="c.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="数据库" required class="at-fi">
                <el-select
                  v-model="addTableDialog.schema"
                  size="small" placeholder="选择数据库" filterable clearable
                  style="width: 100%"
                  @change="onPickSchema"
                >
                  <el-option v-for="d in addTableDialog.dbList" :key="d" :label="d" :value="d" />
                </el-select>
              </el-form-item>
            </div>
            <el-form-item label="表名" required>
              <el-select
                v-model="addTableDialog.name"
                size="small" placeholder="输入或从所选库选择表" filterable allow-create default-first-option
                style="width: 100%"
                @change="onPickTableByName"
              >
                <el-option v-for="t in filteredTableList" :key="t.tableName" :label="t.tableName" :value="t.tableName" />
              </el-select>
            </el-form-item>
            <el-form-item label="注释">
              <el-input v-model="addTableDialog.comment" size="small" placeholder="选填" />
            </el-form-item>
          </el-form>
          <div class="at-fields">
            <div class="at-fields-head">
              <el-checkbox
                :model-value="allFieldsChecked"
                @change="toggleAllFields"
                :disabled="!addTableDialog.fields.length"
              >全选</el-checkbox>
              <span class="at-fh-name">字段名</span>
              <span class="at-fh-type">类型</span>
              <el-button size="small" type="danger" plain :disabled="!hasCheckedFields" @click="removeSelFields">删除选中</el-button>
            </div>
            <div class="at-fields-body">
              <div v-for="(f, fi) in addTableDialog.fields" :key="fi" class="at-field-row">
                <el-checkbox v-model="f.checked" />
                <el-input v-model="f.name" size="small" placeholder="字段名" />
                <el-input v-model="f.type" size="small" placeholder="类型, 如 varchar" />
                <el-button size="small" text type="danger" title="删除该字段" @click="removeField(fi)">✕</el-button>
              </div>
              <el-empty v-if="!addTableDialog.fields.length" description="选择左侧表自动带出字段, 或点下方添加字段" :image-size="50" />
            </div>
          </div>
          <div class="at-footer">
            <el-button size="small" @click="addFieldRow">+ 添加字段</el-button>
            <div style="flex:1"></div>
            <el-button type="primary" :disabled="!addTableDialog.name.trim()" @click="doAddTable">确定</el-button>
            <el-button @click="addTableDialog.visible = false">取消</el-button>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { Document, Link, Delete, EditPen, ArrowDown } from '@element-plus/icons-vue'
import { listDatabases, listTables, listColumns } from '@/api/dbtool'

const props = defineProps({
  tables: { type: Array, default: () => [] },
  columnsMap: { type: Object, default: () => ({}) },
  connId: { type: [Number, String], default: null },
  schema: { type: String, default: '' },
  connList: { type: Array, default: () => [] },
  // 真实外键关系: [{ srcTable, srcColumn, dstTable, dstColumn }] (src 引用 dst)
  fkRelations: { type: Array, default: () => [] }
})

const canvasRef = ref(null)
const scale = ref(1)
const offsetX = ref(0)
const offsetY = ref(0)
const nodes = ref([])
const rels = ref([])
const selectedRel = ref(null)
const selectedAll = ref(false)
const svgWidth = ref(2000)
const svgHeight = ref(1500)
const lastSaved = ref('')

const relDialog = reactive({ visible: false, id: null, srcTable: null, srcColumn: null, dstTable: null, dstColumn: null, cardinality: '1:N' })
// 添加表(融合添加字段): 支持选择数据源/库下的表, 选择后带出字段, 单个/批量删除字段
const addTableDialog = reactive({ visible: false, name: '', comment: '', keyword: '', connId: null, schema: '', dbList: [], selectedTable: null, fields: [] })
const pickTables = ref([])

const undoStack = ref([])
const redoStack = ref([])

const HEADER_H = 38
const ROW_H = 28

let dragState = null
let handleDragState = null
const handleDragPreview = ref({ visible: false, x: 0, y: 0 })

const viewportStyle = computed(() => ({
  transform: `translate(${offsetX.value}px, ${offsetY.value}px) scale(${scale.value})`,
  transformOrigin: '0 0'
}))

const srcCols = computed(() => {
  const n = nodes.value.find(x => x.name === relDialog.srcTable)
  return n ? n.columns.map(c => c.columnName) : []
})
const dstCols = computed(() => {
  const n = nodes.value.find(x => x.name === relDialog.dstTable)
  return n ? n.columns.map(c => c.columnName) : []
})

function anchor(node, colName, side) {
  let y = node.y + node.height / 2
  let x = node.x + node.width / 2
  if (colName) {
    const idx = node.columns.findIndex(c => c.columnName === colName)
    if (idx >= 0) {
      y = node.y + HEADER_H + 3 + idx * ROW_H + ROW_H / 2
    }
  }
  if (side === 'right') x = node.x + node.width
  else if (side === 'left') x = node.x
  else if (side === 'top') y = node.y
  else if (side === 'bottom') y = node.y + node.height
  return { x, y }
}

// 根据鼠标相对表中心的位置判断锚点侧(左右优先, 上下次之)
function sideOf(node, wx, wy) {
  const dx = wx - (node.x + node.width / 2)
  const dy = wy - (node.y + node.height / 2)
  if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? 'right' : 'left'
  return dy > 0 ? 'bottom' : 'top'
}

const visibleRels = computed(() =>
  rels.value
    .map(r => {
      const src = nodes.value.find(n => n.name === r.srcTable)
      const dst = nodes.value.find(n => n.name === r.dstTable)
      if (!src || !dst) return null
      const p1 = anchor(src, r.srcColumn, r.srcSide || 'right')
      const p2 = anchor(dst, r.dstColumn, r.dstSide || 'left')
      // bend: 用户拖动中点句柄造成的控制点偏移, 决定曲线弯曲程度
      const bx = (r.bend && r.bend.x) || 0
      const by = (r.bend && r.bend.y) || 0
      const mx = (p1.x + p2.x) / 2 + bx
      const my = (p1.y + p2.y) / 2 + by
      const cx = (p1.x + p2.x) / 2
      const path = `M ${p1.x} ${p1.y} Q ${cx} ${p1.y} ${mx} ${my} T ${p2.x} ${p2.y}`
      return { ...r, x1: p1.x, y1: p1.y, x2: p2.x, y2: p2.y, mx, my, path }
    })
    .filter(Boolean)
)

function relStyle(rel) {
  if (rel.cardinality === '1:1') return { color: '#409eff', dash: '', marker: '11' }
  if (rel.cardinality === 'N:M') return { color: '#67c23a', dash: '6,3', marker: 'nm' }
  return { color: '#94a3b8', dash: '5,3', marker: '1n' }
}

function relEnds(rel) {
  if (!rel.path) return []
  const c = rel.cardinality
  // 1:1 -> 两端1; 1:N -> src=1,dst=N; N:1 -> src=N,dst=1; N:M -> 两端N
  const srcMark = c === '1:1' || c === '1:N' ? '1' : 'N'
  const dstMark = c === '1:1' || c === 'N:1' ? '1' : 'N'
  return [
    { x: rel.x1, y: rel.y1, mark: srcMark, rot: 0 },
    { x: rel.x2, y: rel.y2, mark: dstMark, rot: 180 }
  ]
}

// 关系标签沿连线路径定位(可拖动)
function quadPoint(p0, p1, p2, t) {
  const u = 1 - t
  return { x: u * u * p0.x + 2 * u * t * p1.x + t * t * p2.x, y: u * u * p0.y + 2 * u * t * p1.y + t * t * p2.y }
}
function relPoint(rel, t) {
  const cx = (rel.x1 + rel.x2) / 2
  const p0 = { x: rel.x1, y: rel.y1 }
  const p1 = { x: cx, y: rel.y1 }
  const p2 = { x: rel.mx, y: rel.my }
  const p3 = { x: 2 * rel.mx - cx, y: 2 * rel.my - rel.y1 }
  const p4 = { x: rel.x2, y: rel.y2 }
  if (t <= 0.5) return quadPoint(p0, p1, p2, t * 2)
  return quadPoint(p2, p3, p4, (t - 0.5) * 2)
}
function relLabelPos(rel) {
  const t = rel.param != null ? rel.param : 0.5
  const p = relPoint(rel, t)
  // 数值求法线, 标签垂直偏移 16px 不压线
  const a = relPoint(rel, Math.max(0, t - 0.001))
  const b = relPoint(rel, Math.min(1, t + 0.001))
  const dx = b.x - a.x, dy = b.y - a.y
  const len = Math.sqrt(dx * dx + dy * dy) || 1
  return { x: p.x - (dy / len) * 16, y: p.y + (dx / len) * 16 }
}

const TYPE_COLORS = {
  num: { bg: '#fdf6ec', fg: '#e6a23c' },
  str: { bg: '#ecf5ff', fg: '#409eff' },
  date: { bg: '#f0f9eb', fg: '#67c23a' },
  blob: { bg: '#f5f0ff', fg: '#8e44ad' },
  json: { bg: '#fef0f0', fg: '#f56c6c' },
  bool: { bg: '#fdf6ec', fg: '#b88230' },
  other: { bg: '#f4f4f5', fg: 'var(--imp-text-secondary)' }
}
function typeKind(t) {
  const s = (t || '').toLowerCase()
  if (/(int|serial|number|decimal|numeric|float|double|real|money)/.test(s)) return 'num'
  if (/(char|text|string|enum|set)/.test(s)) return 'str'
  if (/(date|time|year)/.test(s)) return 'date'
  if (/(blob|binary|byte|image|bit)/.test(s)) return 'blob'
  if (/json/.test(s)) return 'json'
  if (/bool/.test(s)) return 'bool'
  return 'other'
}
function typeStyle(col) {
  const c = TYPE_COLORS[typeKind(col.columnType || col.dataType)]
  return { color: c.fg, background: c.bg }
}
function shortType(t) {
  const s = (t || '').toLowerCase()
  const m = s.match(/^(varchar|char|decimal|numeric|double|float|int|bigint|tinyint|smallint|datetime|timestamp|date|text|longtext|mediumtext|blob|longblob|json|enum|set|bit|time|year|boolean|serial|number|clob|nvarchar|nchar|bytea)\b/)
  // 只显示类型名, 不显示长度: varchar -> VARCHAR (不带(32))
  if (!m) return (t || '').length > 16 ? t.slice(0, 15) + '…' : (t || '')
  return m[1].toUpperCase()
}

// ---------- 布局/渲染 ----------
function renderEr() {
  if (!props.tables.length) {
    nodes.value = []
    rels.value = []
    return
  }
  // 尝试恢复已保存布局
  const saved = loadSavedLayout()
  if (saved && saved.tables === props.tables.map(t => t.tableName).join(',')) {
    restoreNodes(saved)
    return
  }
  const cols = 2
  const nodeWidth = 250
  const gapX = 90
  const gapY = 50
  const rowHeights = []

  nodes.value = props.tables.map((t, i) => {
    const col = i % cols
    const row = Math.floor(i / cols)
    const tableCols = props.columnsMap[t.tableName] || []
    const height = HEADER_H + Math.max(tableCols.length, 1) * ROW_H + 8
    // 行内最大表高决定下一行起始 y, 保证不重叠
    let y = 50
    for (let r = 0; r < row; r++) y += (rowHeights[r] || 0) + gapY
    rowHeights[row] = Math.max(rowHeights[row] || 0, height)
    return reactive({
      name: t.tableName,
      comment: t.tableComment || '',
      isMain: i === 0,
      columns: tableCols.slice(0, 18),
      x: 70 + col * (nodeWidth + gapX),
      y,
      width: nodeWidth,
      height,
      fkSet: new Set(),
      selected: false
    })
  })
  calcFk()
  // 优先使用真实外键关系(字段级), 无则回退启发式
  if (props.fkRelations && props.fkRelations.length) buildFkRels()
  else buildDefaultRels()
  selectedRel.value = null

  svgWidth.value = Math.max(1000, cols * (nodeWidth + gapX) + 160)
  svgHeight.value = Math.max(700, Math.ceil(nodes.value.length / cols) * 240 + 80)
  nextTick(resetInitialView)
}

// 按真实外键关系生成连线: 起点=外键字段, 终点=被引用字段
function buildFkRels() {
  rels.value = (props.fkRelations || [])
    .filter(fk => fk.srcTable && fk.dstTable && fk.srcColumn && fk.dstColumn)
    .map((fk, i) => reactive({
      id: 'rel-' + Date.now() + '-' + i,
      srcTable: fk.srcTable,
      srcColumn: fk.srcColumn,
      dstTable: fk.dstTable,
      dstColumn: fk.dstColumn,
      cardinality: 'N:1',
      param: 0.5,
      srcSide: 'right',
      dstSide: 'left',
      bend: { x: 0, y: 0 }
    }))
}

function calcFk() {
  const allPkCols = new Set()
  nodes.value.forEach(n => n.columns.forEach(c => { if (c.columnKey === 'PRI') allPkCols.add(c.columnName.toLowerCase()) }))
  nodes.value.forEach(n => {
    const ownPks = new Set(n.columns.filter(c => c.columnKey === 'PRI').map(c => c.columnName.toLowerCase()))
    n.fkSet = new Set(n.columns.filter(c => c.columnKey !== 'PRI' && allPkCols.has(c.columnName.toLowerCase()) && !ownPks.has(c.columnName.toLowerCase())).map(c => c.columnName))
  })
}

function buildDefaultRels() {
  const existing = rels.value.map(r => r.srcTable + ':' + r.dstTable)
  const defaults = []
  if (nodes.value.length > 1) {
    const main = nodes.value[0]
    const mainPks = main.columns.filter(c => c.columnKey === 'PRI').map(c => c.columnName)
    for (let i = 1; i < nodes.value.length; i++) {
      const other = nodes.value[i]
      const key = main.name + ':' + other.name
      if (existing.includes(key)) continue
      const same = mainPks.find(pk => other.columns.some(c => c.columnName === pk))
      defaults.push(reactive({
        id: 'rel-' + Date.now() + '-' + i,
        srcTable: main.name,
        srcColumn: same || mainPks[0] || null,
        dstTable: other.name,
        dstColumn: same || null,
        cardinality: '1:N',
        param: 0.5,
        srcSide: 'right',
        dstSide: 'left',
        bend: { x: 0, y: 0 }
      }))
    }
  }
  rels.value = [...rels.value, ...defaults]
}

// 自动布局: 网格排列, 表不重叠
function autoLayout() {
  snapshot()
  const nodeWidth = 250
  const gapX = 100
  const gapY = 60
  const cols = Math.max(1, Math.min(3, Math.ceil(Math.sqrt(nodes.value.length))))
  const rowHeights = []
  nodes.value.forEach((n, i) => {
    const col = i % cols
    const row = Math.floor(i / cols)
    const x = 70 + col * (nodeWidth + gapX)
    let y = 50
    for (let r = 0; r < row; r++) y += (rowHeights[r] || 0) + gapY
    n.x = x
    n.y = y
    rowHeights[row] = Math.max(rowHeights[row] || 0, n.height)
  })
  fitView()
}

function fitView() {
  if (!nodes.value.length) return
  const pad = 80
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  nodes.value.forEach(n => {
    minX = Math.min(minX, n.x); minY = Math.min(minY, n.y)
    maxX = Math.max(maxX, n.x + n.width); maxY = Math.max(maxY, n.y + n.height)
  })
  const bw = maxX - minX, bh = maxY - minY
  const cw = canvasRef.value ? canvasRef.value.clientWidth : 900
  const ch = canvasRef.value ? canvasRef.value.clientHeight : 600
  const s = Math.min((cw - pad) / (bw + pad), (ch - pad) / (bh + pad), 1.25)
  scale.value = Math.max(0.3, Math.min(3, s))
  offsetX.value = (cw - bw * scale.value) / 2 - minX * scale.value
  offsetY.value = (ch - bh * scale.value) / 2 - minY * scale.value
}
function zoomIn() { scale.value = Math.min(3, scale.value + 0.1) }
function zoomOut() { scale.value = Math.max(0.3, scale.value - 0.1) }
function toggleSelectAll() {
  selectedAll.value = !selectedAll.value
  nodes.value.forEach(n => n.selected = selectedAll.value)
}
function onWheel(e) {
  const delta = e.deltaY > 0 ? -0.1 : 0.1
  scale.value = Math.min(3, Math.max(0.3, scale.value + delta))
}

// 画布平移(空白处拖动)
let panState = null
function onCanvasMouseDown(e) {
  if (e.target.closest('.er-table-node') || e.target.closest('.er-rel-group') || e.target.closest('.er-rel-handle')) return
  panState = { startX: e.clientX, startY: e.clientY, ox: offsetX.value, oy: offsetY.value }
  document.addEventListener('mousemove', onPanMove)
  document.addEventListener('mouseup', stopPan)
}
function onPanMove(e) {
  if (!panState) return
  offsetX.value = panState.ox + (e.clientX - panState.startX)
  offsetY.value = panState.oy + (e.clientY - panState.startY)
}
function stopPan() {
  panState = null
  document.removeEventListener('mousemove', onPanMove)
  document.removeEventListener('mouseup', stopPan)
}

// 首次加载 100% 显示(表小于视口时居中, 超出则保持 100% 从左上排布, 可平移查看)
function initialView() {
  if (!nodes.value.length) { scale.value = 1; offsetX.value = 0; offsetY.value = 0; return }
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  nodes.value.forEach(n => {
    minX = Math.min(minX, n.x); minY = Math.min(minY, n.y)
    maxX = Math.max(maxX, n.x + n.width); maxY = Math.max(maxY, n.y + n.height)
  })
  const bw = maxX - minX, bh = maxY - minY
  const cw = canvasRef.value ? canvasRef.value.clientWidth : 0
  const ch = canvasRef.value ? canvasRef.value.clientHeight : 0
  scale.value = 1
  // 容器未就绪时保守处理: 保持 100% 从画布原点排布, 不产生负偏移
  if (!cw || !ch) { offsetX.value = -minX + 16; offsetY.value = -minY + 16; return }
  // 表组小于容器 -> 居中; 大于容器 -> 顶部/左边对齐留 10px, 保证首表完整可见
  offsetX.value = bw <= cw ? (cw - bw) / 2 - minX : 10 - minX
  offsetY.value = bh <= ch ? (ch - bh) / 2 - minY : 10 - minY
}

// 容器就绪后再计算初始视图(tab 切换/懒渲染时 clientWidth 可能为 0)
let ro = null
let initialDone = false
function ensureInitialView() {
  if (initialDone) return
  const c = canvasRef.value
  if (c && c.clientWidth > 0) {
    initialDone = true
    initialView()
    if (ro) { ro.disconnect(); ro = null }
  }
}
function resetInitialView() {
  initialDone = false
  if (canvasRef.value) ensureInitialView()
  else if (ro) ro.observe(canvasRef.value)
}

// 重叠检测与自动避让
function detectOverlaps() {
  const ns = nodes.value.map(n => ({ n, x: n.x, y: n.y, w: n.width, h: n.height }))
  const pairs = []
  for (let i = 0; i < ns.length; i++) {
    for (let j = i + 1; j < ns.length; j++) {
      const a = ns[i], b = ns[j]
      if (a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y) pairs.push([i, j])
    }
  }
  return pairs
}
function resolveOverlaps() {
  let moved = false
  for (let round = 0; round < 3; round++) {
    const pairs = detectOverlaps()
    if (!pairs.length) break
    moved = true
    for (const [i, j] of pairs) {
      const a = nodes.value[i], b = nodes.value[j]
      if (b.y < a.y + a.height + 40) b.y = a.y + a.height + 40
    }
  }
  return moved
}

// ---------- 撤销/重做 ----------
function snapshot() {
  undoStack.value.push(cloneState())
  if (undoStack.value.length > 60) undoStack.value.shift()
  redoStack.value = []
}
function cloneState() {
  return {
    nodes: nodes.value.map(n => ({ name: n.name, comment: n.comment, isMain: n.isMain, columns: n.columns.map(c => ({ ...c })), x: n.x, y: n.y, width: n.width, height: n.height, fkSet: [...(n.fkSet || [])] })),
    rels: rels.value.map(r => ({ ...r, bend: r.bend ? { ...r.bend } : null }))
  }
}
function restoreState(s) {
  nodes.value = s.nodes.map(n => reactive({ ...n, fkSet: new Set(n.fkSet || []) }))
  rels.value = s.rels.map(r => reactive({ ...r }))
}
function undo() {
  if (!undoStack.value.length) return
  redoStack.value.push(cloneState())
  restoreState(undoStack.value.pop())
}
function redo() {
  if (!redoStack.value.length) return
  undoStack.value.push(cloneState())
  restoreState(redoStack.value.pop())
}

// ---------- 保存/恢复(本地持久化) ----------
function storageKey() {
  return 'dbtool-er:' + (props.tables[0]?.tableName || 'tables')
}
function loadSavedLayout() {
  try {
    const raw = localStorage.getItem(storageKey())
    return raw ? JSON.parse(raw) : null
  } catch { return null }
}
function restoreNodes(saved) {
  nodes.value = (saved.nodes || []).map(n => reactive({ ...n, fkSet: new Set(n.fkSet || []) }))
  rels.value = (saved.rels || []).map(r => reactive({ ...r }))
  selectedRel.value = null
  lastSaved.value = formatTime(saved.savedAt)
  // 恢复布局若存在重叠则自动避让, 再 100% 居中显示
  if (resolveOverlaps()) {
    calcFk()
  }
  nextTick(resetInitialView)
}
function saveLayout() {
  snapshot()
  const data = {
    tables: props.tables.map(t => t.tableName).join(','),
    nodes: nodes.value.map(n => ({ name: n.name, comment: n.comment, isMain: n.isMain, columns: n.columns.map(c => ({ ...c })), x: n.x, y: n.y, width: n.width, height: n.height, fkSet: [...(n.fkSet || [])] })),
    rels: rels.value.map(r => ({ ...r })),
    savedAt: Date.now()
  }
  localStorage.setItem(storageKey(), JSON.stringify(data))
  lastSaved.value = formatTime(Date.now())
}
function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const p = v => String(v).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

// ---------- 添加表(融合添加字段) ----------
// 左侧: 本 ER 图已有表清单(不按数据库过滤)
const erTableList = computed(() => {
  const kw = (addTableDialog.keyword || '').toLowerCase().trim()
  return nodes.value
    .map(n => ({ tableName: n.name }))
    .filter(t => !kw || t.tableName.toLowerCase().includes(kw))
})
// 表名下拉: 从所选库选择表(过滤已存在, 避免重复新增)
const filteredTableList = computed(() => {
  const kw = (addTableDialog.keyword || '').toLowerCase().trim()
  const existing = new Set(nodes.value.map(n => n.name))
  return pickTables.value.filter(t => !existing.has(t.tableName) && (!kw || t.tableName.toLowerCase().includes(kw)))
})
const allFieldsChecked = computed(() => addTableDialog.fields.length > 0 && addTableDialog.fields.every(f => f.checked))
const hasCheckedFields = computed(() => addTableDialog.fields.some(f => f.checked))
function openAddTable() {
  addTableDialog.name = ''
  addTableDialog.comment = ''
  addTableDialog.keyword = ''
  addTableDialog.selectedTable = null
  addTableDialog.fields = []
  // 默认当前数据源、当前数据库
  addTableDialog.connId = props.connId != null ? props.connId : (props.connList[0]?.id ?? null)
  addTableDialog.schema = props.schema || ''
  addTableDialog.dbList = []
  pickTables.value = []
  addTableDialog.visible = true
  if (addTableDialog.connId != null) loadDbs()
}
async function loadDbs() {
  if (addTableDialog.connId == null) { addTableDialog.dbList = []; pickTables.value = []; return }
  try {
    const res = await listDatabases(addTableDialog.connId)
    addTableDialog.dbList = res.data || []
    if (!addTableDialog.schema || !addTableDialog.dbList.includes(addTableDialog.schema)) {
      addTableDialog.schema = addTableDialog.dbList[0] || ''
    }
    loadTables()
  } catch (e) { addTableDialog.dbList = []; pickTables.value = [] }
}
async function loadTables() {
  if (addTableDialog.connId == null || !addTableDialog.schema) { pickTables.value = []; return }
  try {
    const res = await listTables(addTableDialog.connId, addTableDialog.schema)
    pickTables.value = res.data || []
  } catch (e) { pickTables.value = [] }
}
function onPickConn() {
  addTableDialog.schema = ''
  addTableDialog.dbList = []
  pickTables.value = []
  addTableDialog.selectedTable = null
  addTableDialog.fields = []
  loadDbs()
}
function onPickSchema() {
  addTableDialog.selectedTable = null
  addTableDialog.fields = []
  loadTables()
}
// 表名下拉选择: 选中库中已有表时带出字段; 手输新表名不动字段
function onPickTableByName(tn) {
  if (!tn) return
  if (pickTables.value.some(t => t.tableName === tn)) {
    if (addTableDialog.selectedTable !== tn) onPickTable(tn)
  }
}
// 左侧点击: 带出该表(已有)字段到右侧编辑
function onPickTable(tn) {
  addTableDialog.selectedTable = tn
  const node = nodes.value.find(n => n.name === tn)
  const apply = (cols) => {
    addTableDialog.fields = (cols || []).map(c => ({ name: c.columnName, type: shortType(c.columnType || 'varchar'), checked: false }))
    addTableDialog.name = tn
  }
  if (node) { apply(node.columns); return }
  // 不在 ER 图(跨库场景): 从所选库查字段
  if (addTableDialog.connId != null && addTableDialog.schema) {
    listColumns(addTableDialog.connId, addTableDialog.schema, tn)
      .then(res => apply(res.data || []))
      .catch(() => apply([]))
  } else { apply([]) }
}
function addFieldRow() {
  addTableDialog.fields.push({ name: '', type: 'varchar', checked: false })
}
function removeField(fi) {
  addTableDialog.fields.splice(fi, 1)
}
function toggleAllFields(checked) {
  addTableDialog.fields.forEach(f => f.checked = !!checked)
}
function removeSelFields() {
  addTableDialog.fields = addTableDialog.fields.filter(f => !f.checked)
}
function doAddTable() {
  const name = addTableDialog.name.trim()
  if (!name) return
  snapshot()
  const cols = addTableDialog.fields
    .filter(f => f.name && f.name.trim())
    .map(f => ({
      columnName: f.name.trim(),
      columnType: f.type || 'varchar',
      columnKey: '',
      isNullable: 'YES',
      columnComment: ''
    }))
  const exist = nodes.value.find(n => n.name === name)
  if (exist) {
    // 表已存在: 更新字段与高度
    exist.columns = cols
    exist.height = HEADER_H + Math.max(cols.length, 1) * ROW_H + 8
  } else {
    // 新表: 找空白位置
    let x = 70, y = 50
    let placed = false
    while (!placed) {
      placed = true
      for (const n of nodes.value) {
        if (Math.abs(n.x - x) < 260 && Math.abs(n.y - y) < 200) {
          x += 300
          if (x > 1600) { x = 70; y += 250 }
          placed = false
          break
        }
      }
    }
    nodes.value.push(reactive({
      name, comment: addTableDialog.comment, isMain: nodes.value.length === 0,
      columns: cols, x, y, width: 250, height: HEADER_H + Math.max(cols.length, 1) * ROW_H + 8, fkSet: new Set(), selected: false
    }))
  }
  calcFk()
  addTableDialog.visible = false
}

// ---------- 连线操作 ----------
function selectRel(rel) { selectedRel.value = rel }
function removeRel() {
  if (!selectedRel.value) return
  snapshot()
  rels.value = rels.value.filter(r => r.id !== selectedRel.value.id)
  selectedRel.value = null
}
function openEditRel(rel) {
  if (rel) {
    relDialog.id = rel.id
    relDialog.srcTable = rel.srcTable
    relDialog.srcColumn = rel.srcColumn
    relDialog.dstTable = rel.dstTable
    relDialog.dstColumn = rel.dstColumn
    relDialog.cardinality = rel.cardinality || '1:N'
  } else {
    relDialog.id = null
    relDialog.srcTable = null
    relDialog.srcColumn = null
    relDialog.dstTable = null
    relDialog.dstColumn = null
    relDialog.cardinality = '1:N'
  }
  relDialog.visible = true
}
function onSrcTableChange() { relDialog.srcColumn = srcCols.value[0] || null }
function onDstTableChange() { relDialog.dstColumn = dstCols.value[0] || null }
function saveRel() {
  if (!relDialog.srcTable || !relDialog.dstTable) return
  snapshot()
  if (relDialog.id) {
    const r = rels.value.find(x => x.id === relDialog.id)
    if (r) Object.assign(r, {
      srcTable: relDialog.srcTable, srcColumn: relDialog.srcColumn,
      dstTable: relDialog.dstTable, dstColumn: relDialog.dstColumn,
      cardinality: relDialog.cardinality
    })
  } else {
    rels.value.push(reactive({
      id: 'rel-' + Date.now(),
      srcTable: relDialog.srcTable, srcColumn: relDialog.srcColumn,
      dstTable: relDialog.dstTable, dstColumn: relDialog.dstColumn,
      cardinality: relDialog.cardinality,
      param: 0.5,
      srcSide: 'right',
      dstSide: 'left',
      bend: { x: 0, y: 0 }
    }))
  }
  relDialog.visible = false
  selectedRel.value = null
}

// ---------- 节点拖动(支持 Ctrl/Shift 多选) ----------
function startDrag(e, node) {
  if (e.shiftKey || e.ctrlKey) {
    // 多选切换: 保持其他选中, 切换当前表
    node.selected = !node.selected
  } else if (!node.selected) {
    // 普通点击: 单选
    nodes.value.forEach(n => n.selected = false)
    node.selected = true
  }
  selectedAll.value = nodes.value.length > 0 && nodes.value.every(n => n.selected)
  dragState = { node, startX: e.clientX, startY: e.clientY, origSnapshot: cloneState() }
  const sel = nodes.value.filter(n => n.selected)
  if (sel.length > 1) {
    dragState.snap = sel.map(n => ({ n, x: n.x, y: n.y }))
  } else {
    dragState.origX = node.x
    dragState.origY = node.y
  }
  document.addEventListener('mousemove', onDrag)
  document.addEventListener('mouseup', stopDrag)
}
// rAF 节流: 拖动节点/端点/中点/标签时, mousemove 高频合并到动画帧, 降低重算开销
let dragRaf = null
function scheduleDragApply(fn) {
  if (!dragRaf) dragRaf = requestAnimationFrame(() => { dragRaf = null; fn() })
}
function onDrag(e) {
  if (!dragState) return
  dragState.lastClientX = e.clientX
  dragState.lastClientY = e.clientY
  scheduleDragApply(applyDragMove)
}
function applyDragMove() {
  const ds = dragState
  if (!ds) return
  const dx = (ds.lastClientX - ds.startX) / scale.value
  const dy = (ds.lastClientY - ds.startY) / scale.value
  if (ds.snap) {
    ds.snap.forEach(({ n, x, y }) => { n.x = x + dx; n.y = y + dy })
  } else {
    ds.node.x = ds.origX + dx
    ds.node.y = ds.origY + dy
  }
}
function stopDrag() {
  if (dragRaf) { cancelAnimationFrame(dragRaf); dragRaf = null }
  if (dragState) {
    // 仅当确实发生移动时压入"移动前"快照, 撤销即回到原位
    const moved = dragState.snap
      ? dragState.snap.some(({ n, x, y }) => Math.abs(n.x - x) > 0.5 || Math.abs(n.y - y) > 0.5)
      : (Math.abs(dragState.node.x - dragState.origX) > 0.5 || Math.abs(dragState.node.y - dragState.origY) > 0.5)
    if (moved) {
      undoStack.value.push(dragState.origSnapshot)
      if (undoStack.value.length > 60) undoStack.value.shift()
      redoStack.value = []
    }
  }
  dragState = null
  document.removeEventListener('mousemove', onDrag)
  document.removeEventListener('mouseup', stopDrag)
}

// 关系标签拖拽(沿连线滑动)
let labelDragState = null
function startDragLabel(e, rel) {
  e.stopPropagation()
  selectedRel.value = rel
  labelDragState = { rel, t0: rel.param != null ? rel.param : 0.5 }
  document.addEventListener('mousemove', onLabelDrag)
  document.addEventListener('mouseup', stopLabelDrag)
}
function onLabelDrag(e) {
  if (!labelDragState) return
  labelDragState.lastX = e.clientX
  labelDragState.lastY = e.clientY
  scheduleDragApply(applyLabelDrag)
}
function applyLabelDrag() {
  const ls = labelDragState
  if (!ls || ls.lastX == null) return
  const rect = canvasRef.value.getBoundingClientRect()
  const wx = (ls.lastX - rect.left - offsetX.value) / scale.value
  const wy = (ls.lastY - rect.top - offsetY.value) / scale.value
  const rel = ls.rel
  // 采样 120 点找路径上最近参数
  let best = ls.t0, bestD = Infinity
  for (let i = 0; i <= 120; i++) {
    const t = i / 120
    const p = relPoint(rel, t)
    const d = (p.x - wx) ** 2 + (p.y - wy) ** 2
    if (d < bestD) { bestD = d; best = t }
  }
  // 写回原始响应式对象(visibleRels 是副本)
  const orig = rels.value.find(x => x.id === rel.id)
  if (orig) orig.param = best
}
function stopLabelDrag() {
  if (dragRaf) { cancelAnimationFrame(dragRaf); dragRaf = null }
  if (labelDragState) snapshot()
  labelDragState = null
  document.removeEventListener('mousemove', onLabelDrag)
  document.removeEventListener('mouseup', stopLabelDrag)
}

// 命中表内字段行: 返回 { col, index } 或 null(空白区)
function hitField(node, wx, wy) {
  const fy = wy - (node.y + HEADER_H + 3)
  const idx = Math.floor(fy / ROW_H)
  if (idx >= 0 && idx < node.columns.length) {
    return { col: node.columns[idx], index: idx }
  }
  return null
}

// 端点拖拽改连
function startDragHandle(e, rel, side) {
  selectedRel.value = rel
  const orig = rels.value.find(x => x.id === rel.id) || rel
  handleDragState = { rel: orig, side }
  document.addEventListener('mousemove', onHandleDrag)
  document.addEventListener('mouseup', stopHandleDrag2)
}
function onHandleDrag(e) {
  if (!handleDragState) return
  handleDragState.lastX = e.clientX
  handleDragState.lastY = e.clientY
  scheduleDragApply(applyHandleDrag)
}
function applyHandleDrag() {
  const h = handleDragState
  if (!h || h.lastX == null) return
  const rect = canvasRef.value.getBoundingClientRect()
  const wx = (h.lastX - rect.left - offsetX.value) / scale.value
  const wy = (h.lastY - rect.top - offsetY.value) / scale.value
  h.wx = wx
  h.wy = wy
  handleDragPreview.value = { visible: true, x: wx, y: wy }
  const hover = nodes.value.find(n =>
    wx >= n.x && wx <= n.x + n.width && wy >= n.y && wy <= n.y + n.height
  )
  h.hover = hover
  h.hoverSide = hover ? sideOf(hover, wx, wy) : null
}
function stopHandleDrag2(e) {
  const h = handleDragState
  handleDragState = null
  handleDragPreview.value = { visible: false, x: 0, y: 0 }
  if (dragRaf) { cancelAnimationFrame(dragRaf); dragRaf = null }
  document.removeEventListener('mousemove', onHandleDrag)
  document.removeEventListener('mouseup', stopHandleDrag2)
  if (!h) return
  let wx = h.wx, wy = h.wy
  if (wx == null) {
    const rect = canvasRef.value.getBoundingClientRect()
    wx = (e.clientX - rect.left - offsetX.value) / scale.value
    wy = (e.clientY - rect.top - offsetY.value) / scale.value
  }
  const target = nodes.value.find(n =>
    wx >= n.x && wx <= n.x + n.width && wy >= n.y && wy <= n.y + n.height
  )
  if (target && h.rel) {
    snapshot()
    const side = sideOf(target, wx, wy)
    const hit = hitField(target, wx, wy)
    const pickCol = (keepCol) => {
      if (hit) return hit.col.columnName
      // 未命中字段行: 目标表存在原字段则保留, 否则取首个字段
      if (keepCol && target.columns.some(c => c.columnName === keepCol)) return keepCol
      return (target.columns[0] && target.columns[0].columnName) || null
    }
    if (h.side === 'src') {
      h.rel.srcTable = target.name
      h.rel.srcSide = side
      h.rel.srcColumn = pickCol(h.rel.srcColumn)
    } else {
      h.rel.dstTable = target.name
      h.rel.dstSide = side
      h.rel.dstColumn = pickCol(h.rel.dstColumn)
    }
  }
}

// 连线弯曲: 拖动中点句柄调整控制点偏移(bend)
let bendDragState = null
function startBendDrag(e, rel) {
  e.stopPropagation()
  selectedRel.value = rel
  const orig = rels.value.find(x => x.id === rel.id) || rel
  if (!orig.bend) orig.bend = { x: 0, y: 0 }
  bendDragState = { rel: orig }
  document.addEventListener('mousemove', onBendDrag)
  document.addEventListener('mouseup', stopBendDrag)
}
function onBendDrag(e) {
  if (!bendDragState) return
  bendDragState.lastX = e.clientX
  bendDragState.lastY = e.clientY
  scheduleDragApply(applyBendDrag)
}
function applyBendDrag() {
  const bs = bendDragState
  if (!bs || bs.lastX == null) return
  const rect = canvasRef.value.getBoundingClientRect()
  const wx = (bs.lastX - rect.left - offsetX.value) / scale.value
  const wy = (bs.lastY - rect.top - offsetY.value) / scale.value
  const rel = bs.rel
  const src = nodes.value.find(n => n.name === rel.srcTable)
  const dst = nodes.value.find(n => n.name === rel.dstTable)
  if (!src || !dst) return
  const p1 = anchor(src, rel.srcColumn, rel.srcSide || 'right')
  const p2 = anchor(dst, rel.dstColumn, rel.dstSide || 'left')
  rel.bend.x = wx - (p1.x + p2.x) / 2
  rel.bend.y = wy - (p1.y + p2.y) / 2
}
function stopBendDrag() {
  if (dragRaf) { cancelAnimationFrame(dragRaf); dragRaf = null }
  if (bendDragState) snapshot()
  bendDragState = null
  document.removeEventListener('mousemove', onBendDrag)
  document.removeEventListener('mouseup', stopBendDrag)
}

// ---------- 导出 ----------
function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.arcTo(x + w, y, x + w, y + h, r)
  ctx.arcTo(x + w, y + h, x, y + h, r)
  ctx.arcTo(x, y + h, x, y, r)
  ctx.arcTo(x, y, x + w, y, r)
  ctx.closePath()
}
function renderPngDataUrl(ratio = 2) {
  if (!nodes.value.length) return null
  const pad = 50
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  nodes.value.forEach(n => {
    minX = Math.min(minX, n.x); minY = Math.min(minY, n.y)
    maxX = Math.max(maxX, n.x + n.width); maxY = Math.max(maxY, n.y + n.height)
  })
  const bw = maxX - minX, bh = maxY - minY
  const cv = document.createElement('canvas')
  cv.width = Math.round((bw + pad * 2) * ratio)
  cv.height = Math.round((bh + pad * 2) * ratio)
  const ctx = cv.getContext('2d')
  ctx.scale(ratio, ratio)
  ctx.translate(pad - minX, pad - minY)
  // 背景
  ctx.fillStyle = '#f8fafc'
  ctx.fillRect(minX - pad, minY - pad, bw + pad * 2, bh + pad * 2)
  // 网格点
  ctx.fillStyle = '#d6dee8'
  for (let gx = Math.floor(minX / 22) * 22; gx <= maxX; gx += 22) {
    for (let gy = Math.floor(minY / 22) * 22; gy <= maxY; gy += 22) {
      ctx.beginPath(); ctx.arc(gx, gy, 1, 0, 7); ctx.fill()
    }
  }
  // 连线
  visibleRels.value.forEach(rel => {
    const style = relStyle(rel)
    const cx = (rel.x1 + rel.x2) / 2
    ctx.strokeStyle = style.color
    ctx.lineWidth = 1.6
    ctx.setLineDash(style.dash ? style.dash.split(',').map(Number) : [])
    ctx.beginPath()
    ctx.moveTo(rel.x1, rel.y1)
    ctx.quadraticCurveTo(cx, rel.y1, rel.mx, rel.my)
    ctx.quadraticCurveTo(2 * rel.mx - cx, 2 * rel.my - rel.y1, rel.x2, rel.y2)
    ctx.stroke()
    ctx.setLineDash([])
    // 端点箭头
    const dx = rel.x2 - rel.mx, dy = rel.y2 - rel.my
    const len = Math.sqrt(dx * dx + dy * dy) || 1
    const ux = dx / len, uy = dy / len
    ctx.fillStyle = style.color
    ctx.beginPath()
    ctx.moveTo(rel.x2 + ux * 8, rel.y2 + uy * 8)
    ctx.lineTo(rel.x2 - uy * 4 + ux * 1, rel.y2 + ux * 4 + uy * 1)
    ctx.lineTo(rel.x2 + uy * 4 + ux * 1, rel.y2 - ux * 4 + uy * 1)
    ctx.closePath()
    ctx.fill()
    // 基数标记
    ctx.strokeStyle = style.color
    ctx.lineWidth = 1.8
    relEnds(rel).forEach(end => {
      const rot = end.rot === 0 ? 0 : Math.PI
      const ex = end.x, ey = end.y
      ctx.save()
      ctx.translate(ex, ey)
      ctx.rotate(rot)
      ctx.beginPath(); ctx.moveTo(0, -5); ctx.lineTo(0, 5); ctx.stroke()
      if (end.mark === 'N') {
        ctx.beginPath(); ctx.moveTo(0, -5); ctx.lineTo(-5, -10); ctx.stroke()
        ctx.beginPath(); ctx.moveTo(0, 5); ctx.lineTo(-5, 10); ctx.stroke()
      }
      ctx.restore()
    })
    // 关系标签(白底小胶囊, 位于线中点上侧)
    const lw = 28, lh = 16
    const ly = rel.my - 16
    ctx.fillStyle = '#ffffff'
    ctx.strokeStyle = '#cbd5e1'
    ctx.lineWidth = 1
    roundRect(ctx, rel.mx - lw / 2, ly - lh / 2, lw, lh, 8)
    ctx.fill()
    ctx.stroke()
    ctx.fillStyle = '#475569'
    ctx.font = '600 8px sans-serif'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText(rel.cardinality, rel.mx, ly + 0.5)
  })
  // 节点
  nodes.value.forEach(n => {
    ctx.save()
    ctx.shadowColor = 'rgba(15, 23, 42, 0.18)'
    ctx.shadowBlur = 14
    ctx.shadowOffsetY = 5
    roundRect(ctx, n.x, n.y, n.width, n.height, 10)
    ctx.fillStyle = '#fff'
    ctx.fill()
    ctx.restore()
    // 标题栏
    const hh = HEADER_H
    ctx.fillStyle = n.isMain ? '#dbeafe' : '#f1f5f9'
    roundRect(ctx, n.x, n.y, n.width, hh, 10)
    ctx.fill()
    ctx.fillRect(n.x, n.y + 6, n.width, hh - 6)
    ctx.fillStyle = n.isMain ? '#1d4ed8' : '#1e293b'
    ctx.font = '600 13px sans-serif'
    ctx.textAlign = 'left'
    ctx.textBaseline = 'middle'
    ctx.fillText(n.name, n.x + 12, n.y + hh / 2)
    if (n.comment) {
      ctx.fillStyle = '#94a3b8'
      ctx.font = '400 11px sans-serif'
      const cw = ctx.measureText(n.comment).width
      ctx.fillText(n.comment.length > 12 ? n.comment.slice(0, 12) + '…' : n.comment, n.x + n.width - 16 - Math.min(cw, 120), n.y + hh / 2)
    }
    // 列
    n.columns.forEach((c, i) => {
      const rowY = n.y + hh + 3 + i * ROW_H
      if (c.columnKey === 'PRI') {
        ctx.fillStyle = '#f8fbff'
        ctx.fillRect(n.x + 1, rowY, n.width - 2, ROW_H)
      }
      const tc = TYPE_COLORS[typeKind(c.columnType || c.dataType)]
      ctx.fillStyle = c.columnKey === 'PRI' ? '#1d4ed8' : '#334155'
      ctx.font = '600 12px monospace'
      ctx.textAlign = 'left'
      ctx.fillText(c.columnName, n.x + 16, rowY + ROW_H / 2)
      const tn = shortType(c.columnType || c.dataType)
      ctx.font = '500 10.5px monospace'
      const tw = ctx.measureText(tn).width
      ctx.fillStyle = tc.bg
      roundRect(ctx, n.x + n.width - 18 - tw - 12, rowY + 4, tw + 12, ROW_H - 8, 5)
      ctx.fill()
      ctx.fillStyle = tc.fg
      ctx.fillText(tn, n.x + n.width - 18 - tw - 6, rowY + ROW_H / 2)
    })
  })
  return cv.toDataURL('image/png')
}
function onExport(cmd) {
  if (cmd === 'png') exportPng()
  else if (cmd === 'pdf') exportPdf()
}
function exportPng() {
  const url = renderPngDataUrl(2)
  if (!url) return
  const a = document.createElement('a')
  a.href = url
  a.download = (props.tables[0]?.tableName || 'er') + '-er-diagram.png'
  document.body.appendChild(a)
  a.click()
  a.remove()
}
function exportPdf() {
  const url = renderPngDataUrl(3)
  if (!url) return
  const w = window.open('', '_blank')
  if (!w) return
  w.document.write(`<html><head><title>ER Diagram</title></head><body style="margin:0;background:#f8fafc;text-align:center;">
    <img src="${url}" style="max-width:100%;height:auto;"/>
    <script>window.onload=function(){setTimeout(function(){window.print()},400)}<\/script>
    </body></html>`)
  w.document.close()
}

// 快捷键: Ctrl+Z / Ctrl+Y / Ctrl+S (输入框内不拦截)
function onKeydown(e) {
  const tag = e.target && e.target.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || (e.target && e.target.isContentEditable)) return
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && !e.shiftKey) { e.preventDefault(); undo() }
  else if ((e.ctrlKey || e.metaKey) && (e.key.toLowerCase() === 'y' || (e.key.toLowerCase() === 'z' && e.shiftKey))) { e.preventDefault(); redo() }
  else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') { e.preventDefault(); saveLayout() }
}

watch(() => props.tables, () => { nextTick(renderEr) }, { deep: true })
watch(() => props.columnsMap, () => { nextTick(renderEr) }, { deep: true })

// SVG 尺寸随表位置动态扩展(节流), 避免连线/表拖出固定尺寸后被裁剪
let svgSizeRaf = null
function updateSvgSize() {
  svgSizeRaf = null
  if (!nodes.value.length) return
  let minX = Infinity, maxX = -Infinity, maxY = -Infinity
  nodes.value.forEach(n => {
    minX = Math.min(minX, n.x)
    maxX = Math.max(maxX, n.x + n.width)
    maxY = Math.max(maxY, n.y + n.height)
  })
  svgWidth.value = Math.max(1000, maxX + 400, (minX < 0 ? -minX : 0) + maxX + 400)
  svgHeight.value = Math.max(700, maxY + 400)
}
watch(() => nodes.value.map(n => [n.x, n.y, n.width, n.height]).flat(), () => {
  if (!svgSizeRaf) svgSizeRaf = requestAnimationFrame(updateSvgSize)
})

onMounted(() => {
  nextTick(renderEr)
  document.addEventListener('keydown', onKeydown)
  // 监听容器尺寸, 就绪后计算一次初始视图
  ro = new ResizeObserver(() => ensureInitialView())
  if (canvasRef.value) ro.observe(canvasRef.value)
})
onUnmounted(() => {
  stopDrag()
  if (ro) { ro.disconnect(); ro = null }
  document.removeEventListener('keydown', onKeydown)
})
</script>

<style scoped>
.er-designer {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #f8fafc;
}
.er-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  border-bottom: 1px solid #e2e8f0;
  background: #fff;
  flex-shrink: 0;
  flex-wrap: wrap;
}
.er-tb-group {
  display: flex;
  align-items: center;
  gap: 6px;
}
.er-zoom {
  font-size: 12px;
  color: #64748b;
  min-width: 42px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}
.er-saved {
  font-size: 12px;
  color: var(--imp-text-secondary);
  margin-left: 2px;
  white-space: nowrap;
}
.er-hint {
  color: #94a3b8;
  font-size: 12px;
  margin-left: 6px;
}
.er-canvas {
  flex: 1;
  overflow: hidden;
  position: relative;
  background-color: #f8fafc;
  background-image: radial-gradient(circle, #d6dee8 1px, transparent 1px);
  background-size: 22px 22px;
}
.er-viewport {
  position: absolute;
  top: 0;
  left: 0;
}
.er-svg {
  position: absolute;
  top: 0;
  left: 0;
  pointer-events: none;
  z-index: 3;
}
.er-rel-line {
  pointer-events: stroke;
  cursor: pointer;
  transition: stroke-width 0.15s;
}
.er-rel-selected {
  filter: drop-shadow(0 0 4px rgba(64, 158, 255, 0.55));
}
.er-rel-label {
  pointer-events: all;
  cursor: pointer;
}
.er-rel-label text {
  font-size: 8px !important;
  font-family: inherit !important;
  font-weight: 600 !important;
  fill: #475569;
}
.er-rel-cardinal {
  pointer-events: none;
}
.er-rel-handle {
  pointer-events: all;
  cursor: crosshair;
}
.er-bend-handle {
  pointer-events: all;
  cursor: grab;
}
.er-bend-handle:active {
  cursor: grabbing;
}
.er-preview-dot {
  pointer-events: none;
}
.er-table-node {
  position: absolute;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.06), 0 8px 24px -12px rgba(15, 23, 42, 0.15);
  cursor: move;
  user-select: none;
  overflow: hidden;
  transition: box-shadow 0.18s ease, border-color 0.18s ease;
  z-index: 1;
}
.er-table-node:hover {
  box-shadow: 0 2px 6px rgba(15, 23, 42, 0.08), 0 14px 32px -12px rgba(64, 158, 255, 0.28);
  border-color: #b8d4f8;
}
.er-table-main {
  border-color: #a5c9f7;
}
.er-node-all {
  border-color: #409eff !important;
  box-shadow: 0 0 0 1.5px rgba(64, 158, 255, 0.45), 0 8px 24px -10px rgba(64, 158, 255, 0.35);
}
.er-table-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 9px 12px;
  background: #ffffff;
  border-bottom: 1px solid #e2e8f0;
  font-weight: 600;
  font-size: 13px;
  color: #1e293b;
}
.er-table-main .er-table-header {
  background: #eff6ff;
  border-bottom-color: #bfdbfe;
  color: #1d4ed8;
}
.er-tb-icon {
  color: #64748b;
  font-size: 14px;
}
.er-table-main .er-tb-icon {
  color: #3b82f6;
}
.er-table-name {
  flex: 1;
  font-family: 'JetBrains Mono', Consolas, monospace;
  letter-spacing: 0.2px;
}
.er-table-comment {
  font-weight: normal;
  font-size: 11px;
  color: #94a3b8;
  max-width: 130px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.er-table-body {
  padding: 3px 0;
}
.er-col-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  font-size: 12px;
  height: 28px;
  box-sizing: border-box;
  border-bottom: 1px solid #f6f8fa;
  transition: background 0.12s;
}
.er-col-row:last-child {
  border-bottom: none;
}
.er-col-row:hover {
  background: #f1f7fe;
}
.er-col-pk-row {
  background: #f8fbff;
}
.er-col-badge {
  width: 13px;
  height: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #e6a23c;
  flex-shrink: 0;
}
.er-col-badge-fk {
  color: #8e44ad;
}
.er-col-badge-plain {
  color: transparent;
}
.er-col-name {
  flex: 1;
  color: #334155;
  font-family: 'JetBrains Mono', Consolas, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.er-col-pk-row .er-col-name {
  color: #1d4ed8;
  font-weight: 600;
}
.er-col-type {
  font-size: 10.5px;
  border-radius: 5px;
  padding: 1px 6px;
  font-family: 'JetBrains Mono', Consolas, monospace;
  white-space: nowrap;
  flex-shrink: 0;
}
.er-empty {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
}

/* 添加表(融合添加字段)弹窗 */
.at-wrapper {
  display: flex;
  gap: 12px;
  height: 420px;
}
.at-left {
  width: 210px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  overflow: hidden;
}
.at-side-title {
  padding: 8px 10px;
  font-size: 12px;
  font-weight: 600;
  color: #475569;
  background: #f8fafc;
  border-bottom: 1px solid #eef2f7;
}
.at-search {
  padding: 8px;
  border-bottom: 1px solid #eef2f7;
}
.at-table-list {
  flex: 1;
  overflow: auto;
  padding: 4px;
}
.at-table-item {
  padding: 7px 10px;
  font-size: 12.5px;
  font-family: 'JetBrains Mono', Consolas, monospace;
  color: #334155;
  border-radius: 6px;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.at-table-item:hover {
  background: #f1f7fe;
  color: #1d4ed8;
}
.at-table-item.at-active {
  background: #dbeafe;
  color: #1d4ed8;
  font-weight: 600;
}
.at-right {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.at-form .el-form-item__label {
  white-space: nowrap;
}
.at-form-row {
  display: flex;
  gap: 12px;
}
.at-form-row .at-fi {
  flex: 1;
  min-width: 0;
}
.at-fields {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  overflow: hidden;
}
.at-fields-head,
.at-field-row {
  display: grid;
  grid-template-columns: 60px minmax(80px, 1fr) 120px 60px;
  align-items: center;
  gap: 8px;
}
.at-fields-head {
  padding: 6px 8px;
  background: #f8fafc;
  border-bottom: 1px solid #eef2f7;
}
.at-fields-head .el-button {
  width: 100%;
  padding-left: 4px;
  padding-right: 4px;
}
.at-fh-name {
  font-size: 12px;
  color: #64748b;
  padding-left: 5px;
}
.at-fh-type {
  font-size: 12px;
  color: #64748b;
  padding-left: 5px;
}
.at-fields-body {
  flex: 1;
  overflow: auto;
  padding: 4px 8px;
}
.at-field-row {
  padding: 3px 0;
}
.at-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}
</style>
