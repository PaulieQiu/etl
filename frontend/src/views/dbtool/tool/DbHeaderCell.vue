<template>
  <div class="db-header-cell" @click="onClick" @contextmenu="onContextMenu">
    <div class="header-content">
      <span :class="['header-type', typeClass]">{{ colType }}</span>
      <span class="header-name">{{ colName }}</span>
    </div>
    <span class="header-sort">
      <span :class="['sort-icon', sort === 'asc' ? 'sort-active' : 'sort-inactive']">▲</span>
      <span :class="['sort-icon', sort === 'desc' ? 'sort-active' : 'sort-inactive']">▼</span>
    </span>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, onUnmounted } from 'vue'

const props = defineProps({
  params: { type: Object, required: true }
})

const sort = ref(null)

const colType = computed(() => {
  return props.params?.headerType || props.params?.colDef?.headerType || ''
})

const colName = computed(() => {
  return props.params?.displayName || props.params?.colDef?.headerName || ''
})

// 根据字段类型返回对应的CSS类名
const typeClass = computed(() => {
  const type = (colType.value || '').toUpperCase().trim()
  if (!type) return 'type-default'
  
  // 数值类型
  if (/^(INT|BIGINT|SMALLINT|TINYINT|MEDIUMINT|DECIMAL|NUMERIC|FLOAT|DOUBLE|REAL|BIT|BOOL|BOOLEAN|NUMBER)$/.test(type)) {
    return 'type-number'
  }
  // 字符串类型
  if (/^(CHAR|VARCHAR|TINYTEXT|TEXT|MEDIUMTEXT|LONGTEXT|ENUM|SET|STRING|NVARCHAR|NCHAR|CLOB)$/.test(type)) {
    return 'type-string'
  }
  // 日期时间类型
  if (/^(DATE|TIME|DATETIME|TIMESTAMP|YEAR|INTERVAL)$/.test(type)) {
    return 'type-date'
  }
  // 二进制类型
  if (/^(BINARY|VARBINARY|TINYBLOB|BLOB|MEDIUMBLOB|LONGBLOB|IMAGE|RAW)$/.test(type)) {
    return 'type-binary'
  }
  // JSON类型
  if (/^(JSON|JSONB)$/.test(type)) {
    return 'type-json'
  }
  // 空间类型
  if (/^(GEOMETRY|POINT|LINESTRING|POLYGON|MULTIPOINT|MULTILINESTRING|MULTIPOLYGON|GEOMETRYCOLLECTION)$/.test(type)) {
    return 'type-spatial'
  }
  // 默认
  return 'type-default'
})

function updateSort() {
  if (props.params?.column) {
    sort.value = props.params.column.getSort()
  } else if (props.params?.sort) {
    sort.value = props.params.sort
  }
}

function onClick(e) {
  if (props.params?.progressSort) {
    props.params.progressSort(e.shiftKey)
  }
  setTimeout(updateSort, 50)
}

function onContextMenu(e) {
  if (props.params?.showColumnMenu) {
    props.params.showColumnMenu(e)
  }
}

onMounted(() => {
  updateSort()
  // 监听排序变化
  if (props.params?.column) {
    props.params.column.addEventListener('sortChanged', updateSort)
  }
})

onUnmounted(() => {
  if (props.params?.column) {
    props.params.column.removeEventListener('sortChanged', updateSort)
  }
})
</script>

<style scoped>
.db-header-cell {
  display: flex !important;
  align-items: center !important;
  justify-content: flex-start !important;
  height: 100%;
  width: 100%;
  min-width: 100%;
  max-width: 100%;
  padding: 0 10px;
  cursor: pointer;
  user-select: none;
  background: #f5f7fa;
  border-bottom: 2px solid #409eff;
  transition: background 0.2s ease;
  white-space: nowrap;
  overflow: hidden;
  box-sizing: border-box;
}

.db-header-cell:hover {
  background: #ecf5ff;
}

.header-content {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 6px;
  flex: 0 1 auto;
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  white-space: nowrap;
}

/* 字段类型标签 - 通用样式（弱化: 纯色文字, 无背景无边框, 10px） */
.header-type {
  font-size: 10px;
  font-weight: 400;
  white-space: nowrap;
  letter-spacing: 0.2px;
  text-transform: uppercase;
  line-height: 1;
  /* 可收缩: 窄列时类型标签压缩省略, 防止溢出到相邻列头 */
  flex-shrink: 1;
  min-width: 0;
  max-width: 45%;
  overflow: hidden;
  text-overflow: ellipsis;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  opacity: 0.85;
}

/* 数值类型 - 橙色 */
.header-type.type-number {
  color: #e6a23c;
}

/* 字符串类型 - 蓝色 */
.header-type.type-string {
  color: #409eff;
}

/* 日期时间类型 - 绿色 */
.header-type.type-date {
  color: #67c23a;
}

/* 二进制类型 - 紫色 */
.header-type.type-binary {
  color: #8e44ad;
}

/* JSON类型 - 红色 */
.header-type.type-json {
  color: #f56c6c;
}

/* 空间类型 - 青色 */
.header-type.type-spatial {
  color: #13c2c2;
}

/* 默认类型 - 灰色 */
.header-type.type-default {
  color: var(--imp-text-secondary);
}

.header-name {
  font-size: 14px;
  color: #303133;
  font-weight: 400;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex-shrink: 1;
  min-width: 20px;
  max-width: 100%;
  line-height: 1;
  display: inline-block;
  /* 字体度量微调: 10px Consolas 与 14px 默认字体字形中心相差约2px(像素实测),
     用 margin-top 上移字段名使字形中心与类型标签对齐;
     不用 transform(会因 overflow:hidden 裁剪底部)。实际生效处为 AgGridResult.vue
     的 :deep 覆盖(AG Grid cellRenderer 丢失 scoped scopeId), 此处保持源一致 */
  margin-top: -2px;
}

/* 排序图标 - 固定在单元格最右侧，正倒三角组合垂直排列 */
.header-sort {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-left: 4px;
  line-height: 1;
}

.sort-icon {
  font-size: 9px;
  line-height: 1;
  color: #c0c4cc;
  transition: color 0.2s ease;
}

.sort-icon.sort-active {
  color: #409eff;
  font-weight: bold;
}
</style>
