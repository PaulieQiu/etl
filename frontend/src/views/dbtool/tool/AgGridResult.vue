<template>
  <div class="ag-grid-result" :style="{ height: height }" ref="rootRef">
    <ag-grid-vue
      style="width: 100%; height: 100%;"
      class="ag-theme-alpine ag-grid-custom"
      theme="legacy"
      :columnDefs="columnDefs"
      :rowData="rowData"
      :defaultColDef="defaultColDef"
      :rowSelection="{ mode: 'multiRow', checkboxes: false, enableClickSelection: false, headerCheckbox: false }"
      :pagination="pagination"
      :paginationPageSize="paginationPageSize"
      :animateRows="true"
      :locale-text="gridLocaleText"
      :getRowStyle="getRowStyle"
      :rowClassRules="rowClassRules"
      :singleClickEdit="false"
      :suppressRowTransform="true"
      @cell-value-changed="onCellValueChanged"
      @cell-editing-started="onCellEditingStarted"
      @cell-editing-stopped="onCellEditingStopped"
      @cell-double-clicked="onCellDoubleClicked"
      @cell-context-menu="onCellContextMenu"
      @selection-changed="onSelectionChanged"
      @row-clicked="onRowClicked"
      @grid-ready="onGridReady"
      @column-resized="onColumnResized"
    >
    </ag-grid-vue>
  </div>
</template>

<script setup name="AgGridResult">
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { AgGridVue } from 'ag-grid-vue3'
import { ModuleRegistry, AllCommunityModule, ValidationModule } from 'ag-grid-community'
import 'ag-grid-community/styles/ag-grid.css'
import 'ag-grid-community/styles/ag-theme-alpine.css'
import { ElMessage } from 'element-plus'
import DbHeaderCell from './DbHeaderCell.vue'

// 注册AG-Grid所有社区版模块（包含单元格编辑、行选择、分页、导出等）
// 注: enableRangeSelection 已移除(AllCommunityModule 不含 CellSelection 且非核心功能, 避免运行时 error #200)
// ValidationModule: dev 模式显示完整错误信息(error #239 具体内容), 生产构建时被 tree-shake
ModuleRegistry.registerModules([AllCommunityModule, ValidationModule])

const props = defineProps({
  columns: { type: Array, default: () => [] },
  columnTypes: { type: Array, default: () => [] },
  rows: { type: Array, default: () => [] },
  height: { type: String, default: '400px' },
  pagination: { type: Boolean, default: true },
  paginationPageSize: { type: Number, default: 100 },
  editable: { type: Boolean, default: false },
  primaryKeys: { type: Array, default: () => [] }
})

const emit = defineEmits(['cell-edit', 'selection-change', 'grid-ready', 'cell-editing-started', 'cell-editing-stopped', 'cell-context-menu'])

const gridApi = ref(null)
const rootRef = ref(null)
const rowData = ref([])
const selectedRowKeys = ref(new Set())

// 行类规则 - 选中行高亮
const rowClassRules = {
  'ag-row-selected-custom': (params) => {
    const key = getRowKey(params.data)
    return selectedRowKeys.value.has(key)
  }
}

// ag-grid 中文本地化（initial property，创建时注入）
const gridLocaleText = {
  noRowsToShow: '暂无数据，执行 SQL 后结果将显示在这里',
  loadingOoo: '加载中...',
  page: '页', to: '到', of: '/', pageSize: '条/页',
  blank: '空白', filterOoo: '筛选...', equals: '等于', notEqual: '不等于',
  lessThan: '小于', greaterThan: '大于', lessThanOrEqual: '小于等于',
  greaterThanOrEqual: '大于等于', contains: '包含', notContains: '不包含',
  startsWith: '开头是', endsWith: '结尾是',
  applyFilter: '应用筛选', clearFilter: '清除筛选',
  firstPage: '第一页', lastPage: '最后一页', nextPage: '下一页', previousPage: '上一页',
  sortAscending: '升序', sortDescending: '降序'
}

const defaultColDef = {
  sortable: true,
  filter: true,
  resizable: true,
  minWidth: 80,
  editable: props.editable,
  singleClickEdit: false,
  suppressMovable: false,
  autoHeight: false,
  wrapText: false,
  cellStyle: { display: 'flex', alignItems: 'center', overflow: 'hidden' }
}

// 估算表头宽度(类型标签+字段名+排序图标+内边距), 保证表头文字不溢出列边界
// 中文字符按14px、英文/数字按8px、Consolas 类型标签按6.5px/字符估算
function estimateHeaderWidth(colName, colType) {
  const namePx = [...String(colName)].reduce((sum, ch) => sum + (ch.charCodeAt(0) > 255 ? 14 : 8), 0)
  const typePx = String(colType || '').length * 6.5
  const sortPx = 16
  const gapPx = 8
  const padPx = 20
  return Math.ceil(namePx + typePx + sortPx + gapPx + padPx)
}

// 列定义
const columnDefs = ref([])

function buildColumnDefs() {
  const cols = []
  // 最左侧序号列: 固定左侧, 点击列头全选
  cols.push({
    field: '__rowNo',
    headerName: '#',
    width: 46,
    minWidth: 46,
    maxWidth: 46,
    sortable: false,
    filter: false,
    resizable: false,
    editable: false,
    suppressMovable: true,
    pinned: 'left',
    headerClass: 'db-rowno-header',
    headerTooltip: '点击全选',
    cellStyle: { display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden', color: 'var(--imp-text-secondary)', cursor: 'default' },
    cellRenderer: (params) => {
      return '<span style="color:var(--imp-text-secondary);font-family:Consolas,monospace;user-select:none;">' + (params.node.rowIndex + 1) + '</span>'
    }
  })
  props.columns.forEach((col, index) => {
    const colType = props.columnTypes[index] || ''
    const isNumeric = /INT|DECIMAL|FLOAT|DOUBLE|NUMBER|BIT/i.test(colType)
    const isDate = /DATE|TIME|TIMESTAMP/i.test(colType)
    cols.push({
      field: col,
      headerName: col,
      headerTooltip: `${col} (${colType})`,
      sortable: true,
      filter: true,
      resizable: true,
      minWidth: isNumeric ? 80 : 100,
      width: Math.max(isNumeric ? 80 : 100, estimateHeaderWidth(col, colType)),
      editable: true,
      // 自定义 header 组件：字段类型小而淡，字段名大而加粗
      headerComponent: DbHeaderCell,
      headerComponentParams: {
        headerType: colType
      },
      cellStyle: { display: 'flex', alignItems: 'center', overflow: 'hidden' },
      // 值变更后（保存/取消前）单元格显示浅橙色背景
      cellClassRules: {
        'db-cell-dirty': (params) => {
          const d = params.data
          return !!(d && d.dirtyCols && d.dirtyCols[params.colDef.field])
        }
      },
      cellRenderer: (params) => {
        if (params.value === null || params.value === undefined) {
          return '<span style="color:var(--imp-text-secondary);font-style:italic;display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1;min-width:0;">NULL</span>'
        }
        return '<span style="display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1;min-width:0;">' + String(params.value) + '</span>'
      }
    })
  })
  return cols
}

// 获取行的唯一标识
function getRowKey(data) {
  if (data.rowKey) return data.rowKey
  if (props.primaryKeys.length > 0) {
    return props.primaryKeys.map(k => data[k]).join('|')
  }
  return JSON.stringify(data)
}

// 行样式 - 隔行换色
function getRowStyle(params) {
  if (params.node.rowIndex % 2 === 1) {
    return { background: '#fafafa' }
  }
  return { background: '#ffffff' }
}

// 行点击事件 - 手动管理选择（默认单选, Shift+点击多选）
function onRowClicked(params) {
  const key = getRowKey(params.data)
  const isShift = !!(params.event && params.event.shiftKey)
  if (isShift) {
    // Shift+点击: 多选, 切换当前行选中状态
    if (selectedRowKeys.value.has(key)) {
      selectedRowKeys.value.delete(key)
    } else {
      selectedRowKeys.value.add(key)
    }
  } else {
    // 普通点击: 单选, 仅选中当前行
    selectedRowKeys.value.clear()
    selectedRowKeys.value.add(key)
  }
  // 直接操作DOM更新行样式
  updateAllRowStyles()
  // 触发选择变化事件
  emitSelectionChange()
}

// 单元格双击事件 - 启动编辑
function onCellDoubleClicked(params) {
  if (gridApi.value) {
    gridApi.value.setFocusedCell(params.rowIndex, params.colDef.field)
    gridApi.value.startEditingCell({
      rowIndex: params.rowIndex,
      colKey: params.colDef.field
    })
  }
}

// 单元格右键事件 - 触发自定义右键菜单
function onCellContextMenu(params) {
  const evt = params.event || window.event
  const x = evt ? evt.clientX : 0
  const y = evt ? evt.clientY : 0
  emit('cell-context-menu', {
    rowIndex: params.rowIndex,
    data: params.data,
    x,
    y,
    event: evt
  })
}

// 单元格编辑开始事件
function onCellEditingStarted(params) {
  console.log('AG-Grid cell editing started:', params.colDef.field, params.value)
  emit('cell-editing-started', {
    rowIndex: params.rowIndex,
    colId: params.colDef.field,
    value: params.value,
    data: params.data
  })
}

// 单元格编辑结束事件
function onCellEditingStopped(params) {
  console.log('AG-Grid cell editing stopped:', params.colDef.field)
  emit('cell-editing-stopped', {
    rowIndex: params.rowIndex,
    colId: params.colDef.field,
    data: params.data
  })
}

// 更新所有行的样式
function updateAllRowStyles() {
  if (!gridApi.value) return
  // 按本网格实例查找行元素(全局 querySelector 在多网格共存时会匹配到其他网格的行)
  let rootEl = null
  try {
    rootEl = gridApi.value.getGridElement()
  } catch (e) {
    rootEl = null
  }
  gridApi.value.forEachNode((node) => {
    const key = getRowKey(node.data)
    const rowEl = rootEl ? rootEl.querySelector(`[row-index="${node.rowIndex}"]`) : null
    if (rowEl) {
      if (selectedRowKeys.value.has(key)) {
        rowEl.classList.add('ag-row-selected-custom')
      } else {
        rowEl.classList.remove('ag-row-selected-custom')
      }
    }
  })
}

// 触发选择变化事件
function emitSelectionChange() {
  const selectedRows = []
  if (gridApi.value) {
    gridApi.value.forEachNode((node) => {
      const key = getRowKey(node.data)
      if (selectedRowKeys.value.has(key)) {
        selectedRows.push(node.data)
      }
    })
  }
  emit('selection-change', selectedRows)
}

// 清空选择
function clearSelection() {
  selectedRowKeys.value.clear()
  updateAllRowStyles()
  emitSelectionChange()
}

// 全选
function selectAll() {
  if (gridApi.value) {
    gridApi.value.forEachNode((node) => {
      const key = getRowKey(node.data)
      selectedRowKeys.value.add(key)
    })
    updateAllRowStyles()
    emitSelectionChange()
  }
}

function onGridReady(params) {
  gridApi.value = params.api
  // 暴露到window以便调试
  if (import.meta.env.DEV) {
    window.agGridApi = params.api
  }
  // 设置列定义
  columnDefs.value = buildColumnDefs()
  // 设置行数据
  rowData.value = [...props.rows]
  emit('grid-ready', params)
  // 自动调整列宽
  setTimeout(() => {
    autoSizeAllColumns()
  }, 200)
  // 双击列头右侧边框自动调整列宽 + 点击序号列标题全选/取消全选
  // 用 params.api.getGridElement() 按网格实例绑定(而非全局 querySelector), 多实例(SQL tab/数据tab)互不干扰
  setTimeout(() => {
    let gridEl = null
    try {
      gridEl = params.api.getGridElement()
    } catch (e) {
      gridEl = null
    }
    if (gridEl && gridEl.isConnected && !gridEl.dataset.dbHeaderBound) {
      // dataset 去重: 组件重建/重挂载时禁止重复绑定, 否则监听器叠加导致全选行为错乱
      gridEl.dataset.dbHeaderBound = '1'
      gridEl.addEventListener('dblclick', (e) => {
        // 只有双击右侧边框时才自适应列宽
        const resizeHandle = e.target.closest('.ag-header-cell-resize')
        if (resizeHandle && gridApi.value) {
          const headerCell = resizeHandle.closest('.ag-header-cell')
          if (headerCell) {
            const colId = headerCell.getAttribute('col-id')
            if (colId) {
              // AG-Grid 36.x 使用 autoSizeColumns（复数），传入列ID数组
              gridApi.value.autoSizeColumns([colId])
              console.log('AG-Grid autoSizeColumns by dblclick border:', colId)
            }
          }
        }
      })
      // 点击序号列标题 -> 全选/取消全选
      gridEl.addEventListener('click', (e) => {
        const headerCell = e.target.closest('.ag-header-cell[col-id="__rowNo"]')
        if (headerCell && gridApi.value) {
          // 语义: 仅当所有行均已选中时才取消全选, 否则一律全选
          // (修复: 原实现 size>0 即清空, 导致"先选中1行再点#期望全选"却被清空)
          let total = 0
          gridApi.value.forEachNode(() => total++)
          const allSelected = total > 0 && selectedRowKeys.value.size >= total
          if (allSelected) {
            clearSelection()
          } else {
            selectAll()
          }
        }
      })
    }
  }, 500)
}

// 列宽调整事件
function onColumnResized(params) {
  // 列宽调整后的处理（如需）
}

function autoSizeAllColumns() {
  if (!gridApi.value) return
  const allColumnIds = []
  gridApi.value.getColumns().forEach((column) => {
    allColumnIds.push(column.getColId())
  })
  if (allColumnIds.length > 0) {
    gridApi.value.autoSizeColumns(allColumnIds, false)
    // 保证列宽至少容纳表头(类型+字段名+排序图标), 防止表头文字溢出列边界、
    // 列分割线与数据列不一致; 不逐列均分多余宽度(否则字段名与排序图标间产生大片空白)
    gridApi.value.getColumns().forEach((col) => {
      const headerMin = col.getColDef().width || 100
      if (col.getActualWidth() < headerMin) {
        col.setActualWidth(headerMin)
      }
    })
  }
}

function onCellValueChanged(params) {
  emit('cell-edit', {
    rowIndex: params.rowIndex,
    colId: params.colDef.field,
    oldValue: params.oldValue,
    newValue: params.newValue,
    data: params.data
  })
  // 值变更后立即刷新单元格, 使 db-cell-dirty 橙色背景生效
  setTimeout(() => {
    if (gridApi.value) {
      gridApi.value.refreshCells({ force: true, columns: [params.colDef.field], rowNodes: [params.node] })
    }
  }, 0)
}

function onSelectionChanged(params) {
  // AG-Grid内置选择变化事件，这里不使用，使用手动管理的选择
}

// 暴露方法
defineExpose({
  getSelectedRows() {
    const selectedRows = []
    if (gridApi.value) {
      gridApi.value.forEachNode((node) => {
        const key = getRowKey(node.data)
        if (selectedRowKeys.value.has(key)) {
          selectedRows.push(node.data)
        }
      })
    }
    return selectedRows
  },
  refreshCells() {
    gridApi.value?.redrawRows()
  },
  sizeColumnsToFit() {
    gridApi.value?.sizeColumnsToFit()
  },
  autoSizeColumns() {
    autoSizeAllColumns()
  },
  exportDataAsCsv() {
    gridApi.value?.exportDataAsCsv()
  },
  setFilterModel(model) {
    gridApi.value?.setFilterModel(model)
  },
  getFilterModel() {
    return gridApi.value?.getFilterModel()
  },
  applyTransaction(transaction) {
    return gridApi.value?.applyTransaction(transaction)
  },
  addRow(rowData) {
    return gridApi.value?.applyTransaction({ add: [rowData] })
  },
  removeRows(rowData) {
    // 从选中集合中移除
    rowData.forEach((row) => {
      const key = getRowKey(row)
      selectedRowKeys.value.delete(key)
    })
    return gridApi.value?.applyTransaction({ remove: rowData })
  },
  getAllRows() {
    const rows = []
    gridApi.value?.forEachNode((node) => rows.push(node.data))
    return rows
  },
  clearSelection() {
    clearSelection()
  },
  selectAll() {
    selectAll()
  },
  // 选中指定行（用于新增行后自动选中）
  selectRows(rows) {
    if (!rows || !rows.length || !gridApi.value) return
    rows.forEach((row) => {
      const key = getRowKey(row)
      if (key) {
        selectedRowKeys.value.add(key)
      }
    })
    updateAllRowStyles()
    emitSelectionChange()
  }
})

// 监听数据变化
watch(() => props.rows, (newRows) => {
  rowData.value = [...newRows]
  // 清空选择
  selectedRowKeys.value.clear()
  setTimeout(() => {
    autoSizeAllColumns()
  }, 200)
}, { deep: true })

// 监听列变化
watch(() => props.columns, () => {
  columnDefs.value = buildColumnDefs()
  setTimeout(() => {
    autoSizeAllColumns()
  }, 200)
})

// 注册全局双击复制字段名函数
onMounted(() => {
  window.agGridCopyColumnName = (colName) => {
    const ta = document.createElement('textarea')
    ta.value = colName
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    try {
      document.execCommand('copy')
      ElMessage.success(`已复制字段名: ${colName}`)
    } catch (e) {
      ElMessage.error('复制失败')
    }
    document.body.removeChild(ta)
  }
  // 事件委托：只有双击字段名称时才复制，双击列边框不复制
  // 按实例根元素绑定 + dataset 去重, 避免多实例/重建时监听叠加
  const gridEl = rootRef.value
  if (gridEl && !gridEl.dataset.dbCopyBound) {
    gridEl.dataset.dbCopyBound = '1'
    gridEl.addEventListener('dblclick', (e) => {
      // 双击列边框时不复制（用于调整列宽）
      if (e.target.closest('.ag-header-cell-resize')) {
        return
      }
      // 只有双击字段名称（.header-name）时才复制
      const headerNameEl = e.target.closest('.header-name')
      if (headerNameEl) {
        // 首先选择字段名称文本
        const selection = window.getSelection()
        const range = document.createRange()
        range.selectNodeContents(headerNameEl)
        selection.removeAllRanges()
        selection.addRange(range)
        // 然后复制字段名
        const colName = headerNameEl.textContent.trim()
        if (colName && window.agGridCopyColumnName) {
          window.agGridCopyColumnName(colName)
        }
      }
    })
  }
})

onUnmounted(() => {
  delete window.agGridCopyColumnName
})
</script>

<style scoped>
.ag-grid-result {
  width: 100%;
  overflow: hidden;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.06);
}

/* 自定义浅色主题 - 基于主题色 #409eff */
:deep(.ag-grid-custom) {
  --ag-background-color: #ffffff;
  --ag-header-background-color: #f5f7fa;
  --ag-odd-row-background-color: #fafcff;
  --ag-even-row-background-color: #ffffff;
  --ag-row-hover-color: #ecf5ff;
  --ag-selected-row-background-color: #d9ecff;
  --ag-border-color: #ebeef5;
  --ag-header-foreground-color: #303133;
  --ag-foreground-color: #303133;
  --ag-font-size: 12px;
  --ag-font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', Arial, sans-serif;
  --ag-row-height: 38px;
  --ag-header-height: 42px;
}

/* 单元格垂直居中 + 超长显示省略号 */
:deep(.ag-grid-custom .ag-cell) {
  padding: 0 12px;
  border-right: 1px solid #f0f0f0;
  display: flex !important;
  align-items: center !important;
  overflow: hidden !important;
  transition: background-color 0.15s ease;
}

/* 单元格内容span - 确保省略号生效 */
:deep(.ag-grid-custom .ag-cell > span) {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}

/* 单元格编辑框 - 高度与单元格一致 */
:deep(.ag-grid-custom .ag-cell-edit-input) {
  height: 100% !important;
  min-height: 36px !important;
  padding: 4px 8px !important;
  font-size: 12px !important;
  border: 2px solid #409eff !important;
  border-radius: 4px !important;
  outline: none !important;
  box-sizing: border-box !important;
  background-color: #ffffff !important;
}

/* 单元格编辑状态 */
:deep(.ag-grid-custom .ag-cell.ag-cell-inline-editing) {
  padding: 0 !important;
  background-color: #ffffff !important;
}

/* 列宽调整手柄 - 确保可拖动 */
:deep(.ag-grid-custom .ag-header-cell-resize) {
  cursor: col-resize;
  width: 8px;
  right: -4px;
  z-index: 10;
}

:deep(.ag-grid-custom .ag-header-cell-resize:hover) {
  background-color: #409eff;
  opacity: 0.6;
}

/* 自定义列头组件占满整个宽度 */
:deep(.ag-grid-custom .ag-header-cell) {
  padding: 0 !important;
}

:deep(.ag-grid-custom .ag-header-cell .ag-header-cell-comp-wrapper) {
  width: 100% !important;
  height: 100% !important;
  display: block !important;
}

:deep(.ag-grid-custom .ag-header-cell .db-header-cell) {
  width: 100% !important;
  height: 100% !important;
  display: flex !important;
  align-items: center !important;
  justify-content: space-between !important;
  box-sizing: border-box !important;
  padding: 0 10px !important;
}

:deep(.ag-grid-custom .ag-header-cell .db-header-cell .header-content) {
  flex: 0 1 auto !important;
  min-width: 0 !important;
  max-width: calc(100% - 30px) !important;
  overflow: hidden !important;
  display: flex !important;
  align-items: center !important;
  justify-content: flex-start !important;
  gap: 6px !important;
}

:deep(.ag-grid-custom .ag-header-cell .db-header-cell .header-type) {
  flex-shrink: 0 !important;
  min-width: 0 !important;
  overflow: hidden !important;
  text-overflow: ellipsis !important;
}

:deep(.ag-grid-custom .ag-header-cell .db-header-cell .header-name) {
  overflow: hidden !important;
  text-overflow: ellipsis !important;
  white-space: nowrap !important;
  min-width: 20px !important;
  flex-shrink: 1 !important;
}

:deep(.ag-grid-custom .ag-header-cell .db-header-cell .header-sort) {
  flex-shrink: 0 !important;
  margin-left: 4px !important;
  display: flex !important;
  flex-direction: column !important;
  align-items: center !important;
  justify-content: center !important;
  line-height: 1 !important;
}

/* ========== 字段类型标签样式 - 强制覆盖（弱化: 纯色文字, 无背景无边框, 10px, line-height:1 对齐） ========== */
:deep(.ag-grid-custom .ag-header-cell .db-header-cell .header-type) {
  font-size: 10px !important;
  font-weight: 400 !important;
  letter-spacing: 0.2px !important;
  text-transform: uppercase !important;
  line-height: 1 !important;
  flex-shrink: 0 !important;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace !important;
  white-space: nowrap !important;
  display: inline-block !important;
  padding: 0 !important;
  border: none !important;
  border-radius: 0 !important;
  background-color: transparent !important;
  opacity: 0.85 !important;
}

/* 数值类型 - 橙色（纯文字） */
:deep(.ag-grid-custom .ag-header-cell .db-header-cell .header-type.type-number) {
  color: #e6a23c !important;
}

/* 字符串类型 - 蓝色（纯文字） */
:deep(.ag-grid-custom .ag-header-cell .db-header-cell .header-type.type-string) {
  color: #409eff !important;
}

/* 日期时间类型 - 绿色（纯文字） */
:deep(.ag-grid-custom .ag-header-cell .db-header-cell .header-type.type-date) {
  color: #67c23a !important;
}

/* 二进制类型 - 紫色（纯文字） */
:deep(.ag-grid-custom .ag-header-cell .db-header-cell .header-type.type-binary) {
  color: #8e44ad !important;
}

/* JSON类型 - 红色（纯文字） */
:deep(.ag-grid-custom .ag-header-cell .db-header-cell .header-type.type-json) {
  color: #f56c6c !important;
}

/* 空间类型 - 青色（纯文字） */
:deep(.ag-grid-custom .ag-header-cell .db-header-cell .header-type.type-spatial) {
  color: #13c2c2 !important;
}

/* 默认类型 - 灰色（纯文字） */
:deep(.ag-grid-custom .ag-header-cell .db-header-cell .header-type.type-default) {
  color: var(--imp-text-secondary) !important;
}

/* ========== 字段名样式 - 强制覆盖 ========== */
:deep(.ag-grid-custom .ag-header-cell .db-header-cell .header-name) {
  font-size: 14px !important;
  font-weight: 400 !important;
  color: #303133 !important;
  white-space: nowrap !important;
  overflow: hidden !important;
  text-overflow: ellipsis !important;
  flex-shrink: 1 !important;
  min-width: 20px !important;
  max-width: 100% !important;
  line-height: 1 !important;
  display: inline-block !important;
  vertical-align: middle !important;
  /* 字体度量微调: AG Grid cellRenderer 动态渲染组件丢失 scoped scopeId,
     DbHeaderCell.vue 的 scoped 样式不生效, 此 :deep 规则是唯一生效处。
     用 margin-top 而非 transform: transform 位移会超出自身盒边界,
     被本规则 overflow:hidden 裁剪(字段名底部被挡住)。margin 移动整个盒,
     字形始终在盒内。像素实测: margin-top -2px 时 14px 字段名字形中心
     与 10px Consolas 类型标签字形中心一致(均 605.5) */
  margin-top: -2px !important;
}

/* ========== 内容区域间距 ========== */
:deep(.ag-grid-custom .ag-header-cell .db-header-cell .header-content) {
  gap: 6px !important;
}

/* ========== 排序图标样式 - 正倒三角组合 ========== */
:deep(.ag-grid-custom .ag-header-cell .db-header-cell .sort-icon) {
  font-size: 9px !important;
  line-height: 1 !important;
  color: #c0c4cc !important;
  transition: color 0.2s ease !important;
}

/* 已排序的三角为蓝色 */
:deep(.ag-grid-custom .ag-header-cell .db-header-cell .sort-icon.sort-active) {
  color: #409eff !important;
  font-weight: bold !important;
}

/* 确保自定义header组件不阻挡列宽调整 */
:deep(.ag-grid-custom .ag-header-cell .db-header-cell) {
  pointer-events: auto;
}

:deep(.ag-grid-custom .ag-header-cell .ag-header-cell-resize) {
  pointer-events: auto !important;
}

/* 单元格选中效果 */
:deep(.ag-grid-custom .ag-cell.ag-cell-focus) {
  background-color: #ecf5ff !important;
  outline: 2px solid #409eff;
  outline-offset: -2px;
}

/* 列头垂直居中 */
:deep(.ag-grid-custom .ag-header-cell) {
  padding: 0;
  border-right: 1px solid #e4e7ed;
  font-weight: 600;
  display: flex !important;
  align-items: center !important;
}

:deep(.ag-grid-custom .ag-header-cell-label) {
  display: flex !important;
  align-items: center !important;
  width: 100%;
  height: 100%;
}

:deep(.ag-grid-custom .ag-header-cell-text) {
  display: flex !important;
  align-items: center !important;
}

/* 行样式 */
:deep(.ag-grid-custom .ag-row) {
  border-bottom: 1px solid #f0f0f0;
  cursor: pointer;
  transition: background-color 0.15s ease;
}

/* 隔行换色 - 奇数行 */
:deep(.ag-grid-custom .ag-row-odd) {
  background-color: #fafcff !important;
}

/* 隔行换色 - 偶数行 */
:deep(.ag-grid-custom .ag-row-even) {
  background-color: #ffffff !important;
}

/* 选中行高亮 - 主题色浅色 */
:deep(.ag-grid-custom .ag-row.ag-row-selected-custom) {
  background-color: #ecf5ff !important;
}

:deep(.ag-grid-custom .ag-row.ag-row-selected-custom .ag-cell) {
  color: #303133;
  font-weight: 500;
}

/* 值变更单元格（保存/取消前）- 浅橙色背景 */
:deep(.ag-grid-custom .ag-cell.db-cell-dirty) {
  background-color: #fdf6ec !important;
}

/* 序号列标题 - 可点击全选 */
:deep(.ag-grid-custom .ag-header-cell.db-rowno-header) {
  cursor: pointer !important;
}

:deep(.ag-grid-custom .ag-header-cell.db-rowno-header .ag-header-cell-label) {
  justify-content: center !important;
}

:deep(.ag-grid-custom .ag-header-cell.db-rowno-header .ag-header-cell-text) {
  font-size: 13px;
  color: var(--imp-text-secondary);
  font-weight: 600;
}

:deep(.ag-grid-custom .ag-header-cell.db-rowno-header:hover) {
  background-color: #e8f4ff !important;
}

/* 行悬停 */
:deep(.ag-grid-custom .ag-row:hover) {
  background-color: #f5f9ff !important;
}

/* 列头悬停 */
:deep(.ag-grid-custom .ag-header-cell:hover) {
  background-color: #e8f4ff;
}

/* 排序图标 */
:deep(.ag-grid-custom .ag-sort-ascending-icon),
:deep(.ag-grid-custom .ag-sort-descending-icon),
:deep(.ag-grid-custom .ag-sort-none-icon) {
  opacity: 1 !important;
  color: var(--imp-text-secondary);
}

:deep(.ag-grid-custom .ag-sort-ascending-icon:hover),
:deep(.ag-grid-custom .ag-sort-descending-icon:hover) {
  color: #409eff;
}

/* 列宽调整手柄已在上面定义 */

:deep(.ag-header-custom) {
  cursor: pointer;
  display: flex;
  align-items: center;
  height: 100%;
}

/* 滚动条美化 */
:deep(.ag-grid-custom ::-webkit-scrollbar) {
  width: 8px;
  height: 8px;
}

:deep(.ag-grid-custom ::-webkit-scrollbar-track) {
  background: #f5f7fa;
  border-radius: 4px;
}

:deep(.ag-grid-custom ::-webkit-scrollbar-thumb) {
  background: #c0c4cc;
  border-radius: 4px;
}

:deep(.ag-grid-custom ::-webkit-scrollbar-thumb:hover) {
  background: var(--imp-text-secondary);
}

/* 分页区域美化 */
:deep(.ag-grid-custom .ag-paging-panel) {
  background-color: #f5f7fa;
  border-top: 1px solid #ebeef5;
  padding: 8px 12px;
}
</style>
