<template>
  <div class="ag-grid-table" :style="{ height }">
    <ag-grid-vue
      style="width: 100%; height: 100%;"
      class="ag-theme-alpine ag-grid-table-custom"
      theme="legacy"
      :columnDefs="columnDefs"
      :rowData="rowData"
      :defaultColDef="defaultColDef"
      :suppressRowTransform="true"
      :locale-text="gridLocaleText"
      :getRowStyle="getRowStyle"
      :rowClassRules="rowClassRules"
      @grid-ready="onGridReady"
      @row-clicked="onRowClicked"
    >
    </ag-grid-vue>
  </div>
</template>

<script setup name="AgGridTable">
import { ref, watch, onBeforeUnmount } from 'vue'
import { AgGridVue } from 'ag-grid-vue3'
import { ModuleRegistry, AllCommunityModule, ValidationModule } from 'ag-grid-community'
import 'ag-grid-community/styles/ag-grid.css'
import 'ag-grid-community/styles/ag-theme-alpine.css'

ModuleRegistry.registerModules([AllCommunityModule, ValidationModule])

const props = defineProps({
  // 列描述: [{ label, field, width, minWidth, align, render: 'text'|'tag', tagType(v, row) -> {type,label}, formatter(v) }]
  columns: { type: Array, default: () => [] },
  rows: { type: Array, default: () => [] },
  height: { type: String, default: '100%' },
  // 当前选中行的 rowKey 值 (用于高亮)
  currentKey: { type: [String, Number], default: null }
})
const emit = defineEmits(['row-click', 'current-change'])

const gridApi = ref(null)
const rowData = ref([])
const selectedKey = ref(props.currentKey)

// ag-grid 中文本地化（initial property，创建时注入）
const gridLocaleText = {
  noRowsToShow: '暂无数据',
  loadingOoo: '加载中...',
  filterOoo: '筛选...', blank: '空白',
  equals: '等于', notEqual: '不等于', contains: '包含', notContains: '不包含'
}

const defaultColDef = {
  sortable: true,
  filter: false,
  resizable: true,
  minWidth: 80,
  suppressMovable: true,
  cellStyle: { display: 'flex', alignItems: 'center', overflow: 'hidden' }
}

const columnDefs = ref([])

function buildColumnDefs() {
  columnDefs.value = props.columns.map((c) => {
    const def = {
      field: c.field,
      headerName: c.label,
      sortable: c.sortable !== false,
      filter: false,
      resizable: c.resizable !== false,
      width: c.width,
      minWidth: c.minWidth || 80,
      suppressMovable: true,
      headerClass: 'agt-header-cell',
      cellStyle: {
        display: 'flex',
        alignItems: 'center',
        overflow: 'hidden',
        justifyContent: c.align === 'center' ? 'center' : 'flex-start'
      }
    }
    // 单元格渲染: tag / 文本(带省略号)
    if (c.render === 'tag' || typeof c.tagType === 'function') {
      def.cellRenderer = (params) => {
        const row = params.data
        const v = params.value
        let tag = { type: 'info', label: v === null || v === undefined || v === '' ? '-' : String(v) }
        if (typeof c.tagType === 'function') {
          const t = c.tagType(v, row)
          if (t) tag = t
        }
        return '<span class="agt-tag agt-tag--' + (tag.type || 'info') + '">' + escapeHtml(tag.label) + '</span>'
      }
    } else if (typeof c.formatter === 'function') {
      def.cellRenderer = (params) => {
        const v = params.value
        if (v === null || v === undefined) {
          return '<span class="agt-null">-</span>'
        }
        return '<span class="agt-text">' + escapeHtml(String(c.formatter(v))) + '</span>'
      }
    } else {
      def.cellRenderer = (params) => {
        const v = params.value
        if (v === null || v === undefined || v === '') {
          return '<span class="agt-null">-</span>'
        }
        return '<span class="agt-text">' + escapeHtml(String(v)) + '</span>'
      }
    }
    return def
  })
}

function escapeHtml(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

function getRowKey(data) {
  if (data && data.rowKey !== undefined && data.rowKey !== null) return data.rowKey
  // 无 __rowKey 时按首列值
  const f = props.columns[0] && props.columns[0].field
  return f && data ? data[f] : JSON.stringify(data)
}

// 隔行换色 (保留 el-table stripe 效果)
function getRowStyle(params) {
  if (params.node.rowIndex % 2 === 1) {
    return { background: '#fafafa' }
  }
  return { background: '#ffffff' }
}

// 选中行高亮 (highlight-current-row)
const rowClassRules = {
  'agt-row-current': (params) => {
    const key = getRowKey(params.data)
    return selectedKey.value !== null && selectedKey.value !== undefined && String(key) === String(selectedKey.value)
  }
}

function onRowClicked(params) {
  const key = getRowKey(params.data)
  selectedKey.value = key
  refreshRowStyles()
  emit('row-click', params.data)
  emit('current-change', params.data)
}

// rowClassRules 变更后需手动刷新才能生效
function refreshRowStyles() {
  if (gridApi.value) {
    gridApi.value.redrawRows()
  }
}

function onGridReady(params) {
  gridApi.value = params.api
}

watch(() => props.currentKey, (v) => {
  selectedKey.value = v
  refreshRowStyles()
})

watch(() => props.rows, (newRows) => {
  rowData.value = [...newRows]
}, { deep: true })

watch(() => props.columns, () => {
  buildColumnDefs()
}, { deep: true })

buildColumnDefs()
rowData.value = [...props.rows]

onBeforeUnmount(() => {
  gridApi.value?.destroy()
})
</script>

<style scoped>
.ag-grid-table {
  width: 100%;
  min-height: 0;
}
/* 复刻 el-table 视觉样式 */
.ag-grid-table-custom {
  --ag-header-background-color: #f5f7fa;
  --ag-header-foreground-color: var(--imp-text-secondary);
  --ag-header-column-separator-display: none;
  --ag-border-color: #ebeef5;
  --ag-row-border-color: #ebeef5;
  --ag-row-hover-color: #f5f7fa;
  --ag-font-size: 13px;
  --ag-font-family: var(--el-font-family, 'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', Arial, sans-serif);
  --ag-cell-horizontal-padding: 10px;
  --ag-header-height: 44px;
  --ag-row-height: 36px;
}
.ag-grid-table-custom :deep(.ag-header-cell) {
  font-weight: 600;
  border-bottom: 1px solid #ebeef5;
}
.ag-grid-table-custom :deep(.ag-cell) {
  color: var(--imp-text-secondary);
  padding-left: 10px;
  padding-right: 10px;
  border-right: 1px solid transparent;
}
.ag-grid-table-custom :deep(.ag-row) {
  border-bottom: 1px solid #ebeef5;
}
/* 当前行高亮 (el-table highlight-current-row 效果) */
.ag-grid-table-custom :deep(.ag-row.agt-row-current) {
  background: #f0f9ff !important;
}
.ag-grid-table-custom :deep(.ag-row.agt-row-current .ag-cell) {
  color: #409eff;
}
/* 标签 (el-tag 外观) */
.agt-tag {
  display: inline-block;
  padding: 0 6px;
  height: 20px;
  line-height: 18px;
  font-size: 12px;
  border-radius: 3px;
  border: 1px solid;
  box-sizing: border-box;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
  vertical-align: middle;
}
.agt-tag--warning {
  background-color: #fdf6ec;
  border-color: #f5dab1;
  color: #e6a23c;
}
.agt-tag--success {
  background-color: #f0f9eb;
  border-color: #e1f3d8;
  color: #67c23a;
}
.agt-tag--info {
  background-color: #f4f4f5;
  border-color: #e9e9eb;
  color: var(--imp-text-secondary);
}
.agt-tag--danger {
  background-color: #fef0f0;
  border-color: #fbc4c4;
  color: #f56c6c;
}
.agt-tag--primary {
  background-color: #ecf5ff;
  border-color: #d9ecff;
  color: #409eff;
}
.agt-text {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}
.agt-null {
  color: #c0c4cc;
  font-style: italic;
}
</style>
