<template>
  <el-dialog
    v-if="dialogVisible"
    :model-value="true"
    @update:model-value="onModelChange"
    title="选择数据库类型"
    width="860px"
    append-to-body
    class="db-type-picker"
  >
    <div class="dtp-body">
      <!-- 搜索与排序 -->
      <div class="dtp-toolbar">
        <div v-if="multiple" class="dtp-bulk">
          <el-button link size="small" @click="selectAll">全选</el-button>
          <el-button link size="small" @click="selectCat">目录全选</el-button>
          <el-button link size="small" type="danger" @click="clearAll">清空</el-button>
        </div>
        <el-input v-model="keyword" placeholder="搜索数据库..." clearable size="small" style="width: 260px">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <div class="dtp-sort">
          <span>排序方式:</span>
          <el-radio-group v-model="sortMode" size="small">
            <el-radio-button value="name">名称</el-radio-button>
            <el-radio-button value="hot">热度</el-radio-button>
          </el-radio-group>
        </div>
      </div>
      <!-- 已选(多选模式) -->
      <div v-if="multiple && internalSelected.length" class="dtp-selected">
        <span class="dtp-selected-label">已选:</span>
        <el-tag
          v-for="code in internalSelected"
          :key="code"
          closable
          size="small"
          @close="toggle(code)"
        >{{ typeName(code) }}</el-tag>
      </div>
      <div class="dtp-main">
        <!-- 左侧分类 -->
        <div class="dtp-cats">
          <div
            v-for="c in catList"
            :key="c.code"
            class="dtp-cat"
            :class="{ active: currentCat === c.code }"
            @click="currentCat = c.code"
          >
            {{ c.name }}
          </div>
        </div>
        <!-- 右侧图标网格 -->
        <div class="dtp-grid">
          <div
            v-for="t in filteredTypes"
            :key="t.code"
            class="dtp-item"
            :class="{ selected: isSelected(t.code) }"
            @click="handleClick(t)"
          >            <div class="dtp-icon" :style="{ background: hasIcon(t.code) ? '#ffffff' : typeColor(t.code) }">
              <img v-if="hasIcon(t.code)" :src="typeIcon(t.code)" class="dtp-icon-img" :alt="t.name" />
              <span v-else class="dtp-icon-text">{{ typeShort(t.code) }}</span>
            </div>
            <div class="dtp-name">
              <el-tooltip :content="capTooltip(t)" placement="top" :show-after="250" :offset="6">
                <span>{{ t.name }}</span>
              </el-tooltip>
            </div>
            <div v-if="capBadge(t)" class="dtp-cap" :class="capBadge(t).cls">{{ capBadge(t).text }}</div>
            <div v-if="multiple" class="dtp-check" :class="{ checked: isSelected(t.code) }">
              <el-icon v-if="isSelected(t.code)" color="#fff"><Check /></el-icon>
            </div>
          </div>
          <el-empty v-if="!filteredTypes.length" description="无匹配的数据库类型" :image-size="80" />
        </div>
      </div>
    </div>
    <template #footer>
      <div class="dtp-footer">
        <span v-if="multiple" class="dtp-count">已选 {{ internalSelected.length }} 项</span>
        <el-button type="primary" :disabled="!canConfirm" @click="confirm">确定</el-button>
        <el-button @click="close">取消</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup name="DbTypePicker">
import { listDbTypes } from '@/api/dbtool/index'
import { ref, computed, watch } from 'vue'
import { Search, Check } from '@element-plus/icons-vue'

const props = defineProps({
  // 单选: String; 多选: Array<string>
  modelValue: { type: [String, Array], default: '' },
  visible: { type: Boolean, default: false },
  multiple: { type: Boolean, default: false },
  // 打开弹窗时的初始搜索词(支持外部输入筛选联动)
  initialKeyword: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue', 'update:visible', 'select'])

const types = ref([])
const keyword = ref('')
const currentCat = ref('all')
const sortMode = ref('name')
// 多选内部选中态(弹窗内独立维护, 确定时一次性回传父组件, 避免 v-model 数组双向不同步)
const internalSelected = ref([])

/**
 * 弹窗可见性由内部 ref 自管理:
 * - 父组件把 visible 置 true 时打开; 关闭时内部先销毁弹窗, 再通知父组件同步状态
 * - 使用 v-if 销毁式关闭, 直接移除 DOM, 不依赖 el-dialog 内部关闭动画(避免过渡卡住导致关不掉)
 */
const dialogVisible = ref(false)
watch(
  () => props.visible,
  (v) => {
    dialogVisible.value = !!v
  },
  { immediate: true }
)

// 任何方式关闭弹窗(取消/确定/X/ESC/遮罩)时, 同步父组件 visible 状态, 便于再次打开
watch(dialogVisible, (v) => {
  if (!v && props.visible) {
    emit('update:visible', false)
  }
})

// 统一关闭: 切换内部状态, v-if 立即移除弹窗 DOM
function close() {
  dialogVisible.value = false
}

// el-dialog 用户主动关闭(X/ESC/遮罩)时回调, 与 close() 等效
function onModelChange(v) {
  if (!v) {
    close()
  }
}

// 单选选中值
const selectedSingle = computed(() => {
  if (props.multiple) return ''
  return Array.isArray(props.modelValue) ? '' : (props.modelValue || '')
})

const canConfirm = computed(() => {
  if (props.multiple) {
    return internalSelected.value.length > 0
  }
  return !!selectedSingle.value
})

// 分类列表(全部 + 热门 + 注册分类)
const catList = computed(() => {
  const cats = [{ code: 'all', name: '全部' }, { code: 'hot', name: '热门' }]
  const seen = new Set()
  types.value.forEach((t) => {
    if (t.category && !seen.has(t.category)) {
      seen.add(t.category)
      cats.push({ code: t.category, name: t.category })
    }
  })
  return cats
})

// 过滤 + 排序
const filteredTypes = computed(() => {
  let list = types.value.slice()
  if (currentCat.value === 'hot') {
    list = list.filter((t) => t.hot)
  } else if (currentCat.value !== 'all') {
    list = list.filter((t) => t.category === currentCat.value)
  }
  if (keyword.value) {
    const kw = keyword.value.toLowerCase()
    list = list.filter((t) => t.name.toLowerCase().includes(kw) || t.code.toLowerCase().includes(kw))
  }
  if (sortMode.value === 'hot') {
    list.sort((a, b) => (b.hot ? 1 : 0) - (a.hot ? 1 : 0) || a.name.localeCompare(b.name))
  } else {
    list.sort((a, b) => a.name.localeCompare(b.name, 'zh'))
  }
  return list
})

function isSelected(code) {
  if (props.multiple) {
    return internalSelected.value.includes(code)
  }
  return code === selectedSingle.value
}

function toggle(code) {
  if (!props.multiple) {
    return
  }
  const list = internalSelected.value.slice()
  const idx = list.indexOf(code)
  if (idx > -1) {
    list.splice(idx, 1)
  } else {
    list.push(code)
  }
  internalSelected.value = list
}

// 多选: 全选全部类型
function selectAll() {
  if (!props.multiple) return
  internalSelected.value = types.value.map((t) => t.code)
}

// 多选: 全选当前目录(热门/分类/过滤结果)下的类型
function selectCat() {
  if (!props.multiple) return
  const codes = filteredTypes.value.map((t) => t.code)
  internalSelected.value = Array.from(new Set([...internalSelected.value, ...codes]))
}

// 多选: 清空全部
function clearAll() {
  if (!props.multiple) return
  internalSelected.value = []
}

function handleClick(t) {
  if (props.multiple) {
    toggle(t.code)
  } else {
    emit('select', t)
    emit('update:modelValue', t.code)
    close()
  }
}

function confirm() {
  if (props.multiple) {
    const list = internalSelected.value.slice()
    const selected = types.value.filter((t) => list.includes(t.code))
    emit('update:modelValue', list)
    emit('select', selected)
  }
  close()
}

function typeName(code) {
  const t = types.value.find((x) => x.code === code)
  return t ? t.name : code
}

// 能力矩阵徽标(后端 /dbtool/type/list 返回 capabilities)
const CAP_LABELS = {
  dialect: '方言适配', verified: '链路实测', browse: '元数据浏览', edit: '网格写入',
  ddl: '结构操作', diff: 'Schema对比', query: 'SQL执行', connect: '可连接'
}
function capBadge(t) {
  const caps = t.capabilities || []
  if (caps.includes('dialect')) {
    return caps.includes('verified')
      ? { text: '已适配·实测', cls: 'cap-ok' }
      : { text: '已适配', cls: 'cap-adapt' }
  }
  if (caps.includes('verified')) return { text: '已验证', cls: 'cap-ok' }
  if (caps.includes('browse')) return { text: '部分能力', cls: 'cap-part' }
  if (caps.includes('query')) return { text: 'SQL执行', cls: 'cap-base' }
  return { text: '仅连接', cls: 'cap-base' }
}
function capTooltip(t) {
  const caps = t.capabilities || []
  if (!caps.length) return t.name
  return caps.map((c) => CAP_LABELS[c] || c).join(' · ')
}

// 类型品牌色
function typeColor(code) {
  const map = {
    MYSQL: '#00758f', POSTGRESQL: '#336791', MARIADB: '#003545', ORACLE: '#c74634',
    SQLSERVER: '#a91d22', AZURESQL: '#0089d6', DB2: '#003b6f', DB2I: '#003b6f',
    DAMENG: '#e60012', KINGBASE: '#c43a2b', OCEANBASE: '#005eff', TIDB: '#18b8f1',
    GBASE: '#1d8f4c', HIGRES: '#0b6bd8', COCKROACHDB: '#6933ff', YUGABYTEDB: '#2d3436',
    GREENPLUM: '#68a71b', EDB: '#3a6ea5', FIREBIRD: '#e8641d', HSQLDB: '#8b5cf6',
    DERBY: '#c0392b', MONETDB: '#e67e22', INFORMIX: '#0078d7', HANA: '#1a6fa8',
    SYBASE: '#e31837', MSACCESS: '#a4373a', CUBRID: '#ffc107', EXASOL: '#0e7c86',
    MONGODB: '#47a248', REDIS: '#d82c20', NEO4J: '#4581c3', ORIENTDB: '#00a3e0',
    HIVE: '#fdb813', SPARK: '#e25a1c', PHOENIX: '#e8a33d', IGNITE: '#a0a0a0',
    IMPALA: '#c99400', CLICKHOUSE: '#ffcc01', DORIS: '#000000', STARROCKS: '#2d5bff',
    DUCKDB: '#fff000', REDSHIFT: '#dd652f', SNOWFLAKE: '#29b5e8', TERADATA: '#f36525',
    VERTICA: '#e88b1f', TRINO: '#dd00a1', PRESTO: '#06a6c5', DREMIO: '#005ea5',
    KYLIN: '#f8b400', CRATEDB: '#00a6a6', INFLUXDB: '#22adf6', TDENGINE: '#e7563a',
    TIMESCALEDB: '#074f6b', SQLITE: '#0f80cc', H2: '#4e7ab5', ELASTICSEARCH: '#005571'
  }
  return map[code] || '#6b7280'
}

// 官方品牌图标(本地 svg 资源, 见 src/assets/dbicons)
const iconModules = import.meta.glob('/src/assets/dbicons/*.svg', { eager: true, as: 'url' })
function typeIcon(code) {
  return iconModules[`/src/assets/dbicons/${String(code).toLowerCase()}.svg`] || ''
}
function hasIcon(code) {
  return !!typeIcon(code)
}
// 无官方图标时的品牌徽标缩写
function typeShort(code) {
  return String(code || '').slice(0, 2)
}

// 打开时初始化选中态并加载类型
watch(
  () => props.visible,
  (v) => {
    if (v) {
      if (props.multiple) {
        internalSelected.value = Array.isArray(props.modelValue) ? [...props.modelValue] : []
      }
      // 同步外部输入的关键字(需求: 选择框可输入过滤)
      keyword.value = props.initialKeyword || ''
      if (!types.value.length) {
        listDbTypes().then((res) => {
          types.value = res.data?.types || []
        })
      }
    }
  }
)

// 弹窗打开期间, 外部输入实时同步过滤词(选择框可输入自动筛选)
watch(
  () => props.initialKeyword,
  (v) => {
    if (props.visible) {
      keyword.value = v || ''
    }
  }
)
</script>

<style scoped>
.dtp-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: 460px;
}
.dtp-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.dtp-bulk {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--imp-text-secondary);
  padding: 0 4px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  background: #fafafa;
}
.dtp-sort {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--imp-text-secondary);
}
.dtp-selected {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 6px 10px;
  background: #f5f7fa;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  min-height: 32px;
  max-height: 64px;
  overflow-y: auto;
}
.dtp-selected-label {
  font-size: 13px;
  color: var(--imp-text-secondary);
  flex-shrink: 0;
}
.dtp-main {
  display: flex;
  flex: 1;
  min-height: 0;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  overflow: hidden;
}
.dtp-cats {
  width: 110px;
  border-right: 1px solid #ebeef5;
  background: #fafafa;
  overflow-y: auto;
  flex-shrink: 0;
}
.dtp-cat {
  padding: 10px 14px;
  font-size: 13px;
  color: var(--imp-text-secondary);
  cursor: pointer;
  border-left: 3px solid transparent;
  transition: all 0.15s;
}
.dtp-cat:hover {
  background: #f0f2f5;
  color: #409eff;
}
.dtp-cat.active {
  background: #ecf5ff;
  color: #409eff;
  border-left-color: #409eff;
  font-weight: 600;
}
.dtp-grid {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(118px, 1fr));
  gap: 6px;
  padding: 10px;
  overflow-y: auto;
  align-content: start;
}
.dtp-item {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 10px 6px;
  border: 1px solid transparent;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
  text-align: center;
}
.dtp-item:hover {
  background: #f5f7fa;
  border-color: #dcdfe6;
}
.dtp-item.selected {
  background: #ecf5ff;
  border-color: #409eff;
}
.dtp-icon {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.12);
  overflow: hidden;
}
.dtp-icon-img {
  width: 30px;
  height: 30px;
  object-fit: contain;
}
.dtp-icon-text {
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.5px;
  user-select: none;
}
.dtp-name {
  font-size: 12px;
  color: #303133;
  line-height: 1.3;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dtp-cap {
  font-size: 10px;
  line-height: 1.4;
  padding: 1px 6px;
  border-radius: 8px;
  white-space: nowrap;
}
.dtp-cap.cap-ok { color: #389e0d; background: #f6ffed; border: 1px solid #b7eb8f; }
.dtp-cap.cap-adapt { color: #1677ff; background: #e6f4ff; border: 1px solid #91caff; }
.dtp-cap.cap-part { color: #d46b08; background: #fff7e6; border: 1px solid #ffd591; }
.dtp-cap.cap-base { color: #8c8c8c; background: #fafafa; border: 1px solid #d9d9d9; }
.dtp-check {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: 1px solid #c0c4cc;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
}
.dtp-check.checked {
  background: #409eff;
  border-color: #409eff;
}
.dtp-footer {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
}
.dtp-count {
  font-size: 13px;
  color: var(--imp-text-secondary);
  margin-right: auto;
}
</style>
