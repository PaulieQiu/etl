<template>
  <div class="redis-manager">
    <!-- 顶部工具栏 -->
    <div class="redis-toolbar">
      <el-select v-model="dbIndex" size="small" style="width: 90px" @change="onDbChange">
        <el-option v-for="i in 16" :key="i - 1" :label="'db' + (i - 1)" :value="i - 1" />
      </el-select>
      <el-input
        v-model="patternKw"
        size="small"
        class="redis-pattern"
        placeholder="搜索键名, 支持 * 通配符"
        clearable
        @keyup.enter="loadGroups(true)"
        @clear="loadGroups(true)"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button size="small" icon="Search" @click="loadGroups(true)">搜索</el-button>
      <el-button size="small" icon="Refresh" @click="loadGroups(true)">刷新</el-button>
      <el-tag v-if="readOnly" type="warning" size="small">只读连接</el-tag>
      <el-button type="primary" size="small" icon="Plus" :disabled="readOnly" @click="openAddDialog">新增键</el-button>
      <el-button type="danger" plain size="small" icon="Delete" :disabled="readOnly || !selectedKeys.length" @click="handleDeleteSelected">删除</el-button>
      <span class="redis-toolbar-right">
        <el-button size="small" :type="view === 'keys' ? 'primary' : 'default'" plain @click="view = 'keys'">键浏览</el-button>
        <el-button size="small" :type="view === 'cli' ? 'primary' : 'default'" plain @click="switchView('cli')">命令行</el-button>
        <el-button size="small" :type="view === 'info' ? 'primary' : 'default'" plain @click="switchView('info')">服务器信息</el-button>
      </span>
    </div>

    <!-- 主体: 键浏览 -->
    <div v-if="view === 'keys'" class="redis-body">
      <!-- 左侧键树(按前缀分组, 懒加载) -->
      <div class="redis-keys-pane">
        <div class="redis-keys-head">
          <span class="rk-total">db{{ dbIndex }} · 已加载 {{ totalKeys }} / 共 {{ dbSize }} 键</span>
          <span class="rk-actions">
            <el-button size="small" text type="primary" :disabled="!treeData.length" @click="expandAll">全部展开</el-button>
            <el-button size="small" text @click="collapseAll">全部折叠</el-button>
          </span>
        </div>
        <el-tree
          ref="keyTreeRef"
          :key="treeVersion"
          :data="treeData"
          node-key="id"
          show-checkbox
          :props="{ label: 'label', children: 'children' }"
          :expand-on-click-node="false"
          :default-expanded-keys="expandedKeys"
          class="redis-tree"
          empty-text="该库无匹配键"
          @node-click="onTreeNodeClick"
          @check="onTreeCheck"
          @node-expand="onNodeExpand"
        >
          <template #default="{ data }">
            <span v-if="data.isGroup" class="tree-group-node">
              <el-icon class="tg-icon"><FolderOpened /></el-icon>
              <span class="tg-name" :title="data.label">{{ data.label }}</span>
              <span class="tg-count">{{ data.count }}</span>
            </span>
            <span v-else class="tree-key-node">
              <span :class="['rk-type', 'rk-type-' + (data.type || '')]">{{ typeIcon(data.type) }}</span>
              <span class="rk-name" :title="data.key">{{ data.label }}</span>
              <span class="rk-size">{{ data.size }}</span>
            </span>
          </template>
        </el-tree>
        <div class="redis-keys-foot">
          <span class="rk-cursor" v-if="scanLimited">已达扫描上限 5000 键, 建议使用搜索缩小范围</span>
          <span class="rk-cursor" v-else>{{ treeData.length }} 个分组</span>
        </div>
      </div>

      <!-- 右侧键详情 -->
      <div class="redis-detail-pane">
        <template v-if="currentKey">
          <div class="redis-detail-head">
            <span class="rd-key" :title="currentKey">{{ currentKey }}</span>
            <el-tag :class="'rk-type-' + currentInfo.type" size="small" effect="dark">{{ currentInfo.type }}</el-tag>
            <span class="rd-ttl">TTL: <el-input-number v-model="ttlInput" size="small" :min="-1" :controls="false" :disabled="readOnly" style="width: 90px" /></span>
            <span class="rd-toolbar">
              <el-button size="small" icon="Refresh" @click="reloadDetail">重载</el-button>
              <el-button size="small" type="primary" icon="Check" :disabled="readOnly || !dirty" @click="saveDetail">保存</el-button>
              <el-button size="small" type="danger" plain icon="Delete" :disabled="readOnly" @click="handleDeleteKey(currentKey)">删除键</el-button>
            </span>
          </div>
          <div class="redis-detail-body">
            <!-- STRING -->
            <div v-if="currentInfo.type === 'string'" class="rd-value-pane">
              <div class="rd-string-meta" v-if="binaryMode">
                <el-tag type="warning" size="small" effect="plain">二进制内容</el-tag>
                <span class="rd-hint">已按 Hex(十六进制) 显示, 保存时原样写回</span>
                <el-button size="small" text type="primary" @click="toggleStringView">切换到文本视图</el-button>
              </div>
              <div class="rd-string-meta" v-else-if="currentBinary">
                <el-tag type="info" size="small" effect="plain">文本视图(不可保存)</el-tag>
                <span class="rd-hint">内容为二进制, 请切换到 Hex 视图编辑</span>
                <el-button size="small" text type="primary" @click="toggleStringView">切换到 Hex 视图</el-button>
              </div>
              <el-input
                v-model="stringValue"
                type="textarea"
                class="rd-string-editor"
                :disabled="readOnly"
                :placeholder="binaryMode ? 'Hex 内容, 每字节两个十六进制字符' : '键值内容'"
                @input="dirty = true"
              />
              <div class="rd-json-actions">
                <el-button size="small" :disabled="binaryMode" @click="formatJson">格式化 JSON</el-button>
                <el-button size="small" :disabled="binaryMode" @click="compressJson">压缩 JSON</el-button>
                <span class="rd-hint" v-if="!binaryMode && !currentBinary">支持 JSON 格式化; 二进制内容自动以 Hex 显示</span>
              </div>
            </div>

            <!-- HASH / LIST / SET / ZSET: 表格编辑 -->
            <div v-else-if="['hash', 'list', 'set', 'zset'].includes(currentInfo.type)" class="rd-table-pane">
              <div class="rd-table-toolbar">
                <el-button size="small" icon="Plus" :disabled="readOnly" @click="addRow">插入行</el-button>
                <el-button size="small" icon="Delete" :disabled="readOnly || !selectedRows.length" @click="removeRows">删除选中</el-button>
              </div>
              <el-table :data="tableRows" size="small" border height="100%" class="rd-table" @selection-change="(r) => (selectedRows = r)">
                <el-table-column type="selection" width="38" />
                <el-table-column label="索引" width="70" v-if="currentInfo.type === 'list'">
                  <template #default="{ row }"><span class="rd-idx">{{ row.__index }}</span></template>
                </el-table-column>
                <el-table-column label="成员" min-width="200" v-if="currentInfo.type === 'set' || currentInfo.type === 'zset'">
                  <template #default="{ row }">
                    <el-input v-model="row.member" size="small" :disabled="readOnly" @input="dirty = true" />
                  </template>
                </el-table-column>
                <el-table-column label="Field" min-width="180" v-if="currentInfo.type === 'hash'">
                  <template #default="{ row }">
                    <el-input v-model="row.field" size="small" :disabled="readOnly" @input="dirty = true" />
                  </template>
                </el-table-column>
                <el-table-column label="Value" min-width="240">
                  <template #default="{ row }">
                    <el-input v-model="row.value" size="small" :disabled="readOnly" @input="dirty = true" />
                  </template>
                </el-table-column>
                <el-table-column label="Score" width="120" v-if="currentInfo.type === 'zset'">
                  <template #default="{ row }">
                    <el-input v-model="row.score" size="small" :disabled="readOnly" @input="dirty = true" />
                  </template>
                </el-table-column>
              </el-table>
            </div>

            <el-empty v-else description="该类型暂不支持在线编辑, 请使用命令行" :image-size="60" />
          </div>
        </template>
        <el-empty v-else description="点击左侧键查看/编辑内容" :image-size="100" class="rd-empty" />
      </div>
    </div>

    <!-- CLI 面板 -->
    <div v-else-if="view === 'cli'" class="redis-cli-pane">
      <div class="redis-cli-output" ref="cliOutputRef">
        <div v-for="(line, i) in cliLines" :key="i" :class="['cli-line', { err: line.err }]">
          <span class="cli-prompt">{{ line.prompt }}</span>
          <pre class="cli-text">{{ line.text }}</pre>
        </div>
      </div>
      <div class="redis-cli-input">
        <span class="cli-cursor">db{{ dbIndex }}&gt;</span>
        <el-input
          v-model="cliCmd"
          size="small"
          placeholder="输入 Redis 命令, 如 GET key / DBSIZE / HGETALL key"
          @keyup.enter="runCli"
        >
          <template #append><el-button @click="runCli">执行</el-button></template>
        </el-input>
        <el-button size="small" @click="helpDialog = true">命令帮助</el-button>
      </div>
      <div class="redis-cli-hint">提示: 命令逐条执行; KEYS 已限量执行(最多 1000 条), FLUSHALL/FLUSHDB/SHUTDOWN/DEBUG/MONITOR/CONFIG SET 等危险命令仍拦截</div>
    </div>

    <!-- Info 面板 -->
    <div v-else-if="view === 'info'" class="redis-info-pane">
      <div v-for="(section, sname) in infoSections" :key="sname" class="redis-info-card">
        <div class="ri-card-title">{{ sname }}</div>
        <el-table :data="sectionRows(section)" size="small" border>
          <el-table-column prop="key" label="属性" width="220" />
          <el-table-column prop="value" label="值" />
        </el-table>
      </div>
    </div>

    <!-- 新增键弹窗 -->
    <el-dialog v-model="addDialog" title="新增键" width="560px" append-to-body>
      <el-form label-width="90px" size="small">
        <el-form-item label="键名" required>
          <el-input v-model="addForm.key" placeholder="请输入键名" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="addForm.type" style="width: 100%">
            <el-option v-for="t in ['string', 'hash', 'list', 'set', 'zset']" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="TTL(秒)">
          <el-input-number v-model="addForm.ttl" :min="-1" size="small" style="width: 140px" />
          <span class="rd-hint" style="margin-left: 8px">-1 = 永不过期</span>
        </el-form-item>
        <el-form-item v-if="addForm.type === 'string'" label="值" required>
          <el-input v-model="addForm.value" type="textarea" :rows="4" placeholder="键值内容(二进制内容请先以 Hex 格式: __HEX__ + 十六进制)" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="addDialog = false">取消</el-button>
        <el-button size="small" type="primary" :disabled="readOnly" @click="confirmAdd">确定</el-button>
      </template>
    </el-dialog>

    <!-- 命令帮助弹窗 -->
    <el-dialog v-model="helpDialog" title="Redis 命令帮助" width="760px" append-to-body>
      <div class="redis-help">
        <div v-for="g in cmdHelpGroups" :key="g.name" class="rh-group">
          <div class="rh-group-title">{{ g.name }}</div>
          <table class="rh-table">
            <tbody>
              <tr v-for="c in g.cmds" :key="c.cmd">
                <td class="rh-cmd">{{ c.cmd }}</td>
                <td class="rh-example">{{ c.example }}</td>
                <td class="rh-desc">{{ c.desc }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <template #footer>
        <el-button size="small" type="primary" @click="helpDialog = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, nextTick, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { redisKeys, redisKeyInfo, redisValue, redisSet, redisDel, redisExpire, redisInfo, redisCli } from '@/api/dbtool'

const props = defineProps({
  connId: { type: [Number, String], required: true },
  connName: { type: String, default: '' },
  readOnly: { type: Boolean, default: false }
})

const dbIndex = ref(0)
const patternKw = ref('')
const treeData = ref([])
const treeVersion = ref(0)
const treeLoading = ref(false)
const totalKeys = ref(0)
const dbSize = ref(0)
const scanLimited = ref(false)
const expandedKeys = ref([])
const keyTreeRef = ref()
const selectedKeys = ref([])

const currentKey = ref('')
const currentInfo = reactive({ type: '', ttl: -1, size: '' })
const ttlInput = ref(-1)
const stringValue = ref('')
const binaryMode = ref(false)
const currentBinary = ref(false)
const tableRows = ref([])
const selectedRows = ref([])
const dirty = ref(false)

const view = ref('keys')
const cliCmd = ref('')
const cliLines = ref([])
const cliOutputRef = ref()
const infoSections = ref({})
const helpDialog = ref(false)

const addDialog = ref(false)
const addForm = reactive({ key: '', type: 'string', ttl: -1, value: '' })

const typeMap = { string: 'STRING', hash: 'HASH', list: 'LIST', set: 'SET', zset: 'ZSET' }

const cmdHelpGroups = [
  {
    name: '通用',
    cmds: [
      { cmd: 'PING', example: 'PING', desc: '连通性测试' },
      { cmd: 'SELECT', example: 'SELECT 1', desc: '切换逻辑库(0-15)' },
      { cmd: 'DBSIZE', example: 'DBSIZE', desc: '当前库键总数' },
      { cmd: 'INFO', example: 'INFO server', desc: '服务器信息(section 可选)' }
    ]
  },
  {
    name: '键操作',
    cmds: [
      { cmd: 'GET', example: 'GET mykey', desc: '读取字符串键' },
      { cmd: 'SET', example: 'SET mykey value', desc: '写入字符串键' },
      { cmd: 'DEL', example: 'DEL key1 key2', desc: '删除一个或多个键' },
      { cmd: 'EXISTS', example: 'EXISTS mykey', desc: '键是否存在(1/0)' },
      { cmd: 'TYPE', example: 'TYPE mykey', desc: '键类型' },
      { cmd: 'TTL', example: 'TTL mykey', desc: '剩余过期秒(-1 永久/-2 不存在)' },
      { cmd: 'EXPIRE', example: 'EXPIRE mykey 60', desc: '设置过期秒数' },
      { cmd: 'PERSIST', example: 'PERSIST mykey', desc: '移除过期, 永久保存' },
      { cmd: 'RENAME', example: 'RENAME key newkey', desc: '重命名键' }
    ]
  },
  {
    name: '字符串',
    cmds: [
      { cmd: 'GETRANGE', example: 'GETRANGE key 0 10', desc: '截取子串' },
      { cmd: 'STRLEN', example: 'STRLEN key', desc: '字符串长度' }
    ]
  },
  {
    name: '哈希(Hash)',
    cmds: [
      { cmd: 'HSET', example: 'HSET user name Tom', desc: '设置字段值' },
      { cmd: 'HGET', example: 'HGET user name', desc: '读取字段值' },
      { cmd: 'HGETALL', example: 'HGETALL user', desc: '读取全部字段' },
      { cmd: 'HDEL', example: 'HDEL user name', desc: '删除字段' },
      { cmd: 'HLEN', example: 'HLEN user', desc: '字段数量' },
      { cmd: 'HKEYS', example: 'HKEYS user', desc: '列出全部字段名' }
    ]
  },
  {
    name: '列表(List)',
    cmds: [
      { cmd: 'LPUSH', example: 'LPUSH list a', desc: '左侧入队' },
      { cmd: 'RPUSH', example: 'RPUSH list b', desc: '右侧入队' },
      { cmd: 'LRANGE', example: 'LRANGE list 0 -1', desc: '区间读取(0 -1 全部)' },
      { cmd: 'LLEN', example: 'LLEN list', desc: '列表长度' },
      { cmd: 'LPOP', example: 'LPOP list', desc: '左侧出队' },
      { cmd: 'RPOP', example: 'RPOP list', desc: '右侧出队' }
    ]
  },
  {
    name: '集合(Set)',
    cmds: [
      { cmd: 'SADD', example: 'SADD set m1 m2', desc: '添加成员' },
      { cmd: 'SMEMBERS', example: 'SMEMBERS set', desc: '全部成员' },
      { cmd: 'SCARD', example: 'SCARD set', desc: '成员数量' },
      { cmd: 'SREM', example: 'SREM set m1', desc: '移除成员' }
    ]
  },
  {
    name: '有序集合(ZSet)',
    cmds: [
      { cmd: 'ZADD', example: 'ZADD zs 1 m1', desc: '添加成员及分值' },
      { cmd: 'ZRANGE', example: 'ZRANGE zs 0 -1 WITHSCORES', desc: '区间读取' },
      { cmd: 'ZCARD', example: 'ZCARD zs', desc: '成员数量' },
      { cmd: 'ZREM', example: 'ZREM zs m1', desc: '移除成员' }
    ]
  },
  {
    name: '扫描(替代 KEYS)',
    cmds: [
      { cmd: 'SCAN', example: 'SCAN 0 MATCH user:* COUNT 200', desc: '增量扫描键(KEYS 已拦截)' }
    ]
  }
]

function typeIcon(t) {
  return typeMap[t] || '?'
}

function fmtSize(s) {
  if (s === null || s === undefined || s === '-' || s === '') return ''
  const n = Number(s)
  if (isNaN(n)) return ''
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(2) + ' MB'
}

function buildPattern() {
  const kw = (patternKw.value || '').trim()
  if (!kw) return '*'
  return kw.includes('*') ? kw : '*' + kw + '*'
}

function groupOf(key) {
  const m = key.match(/^([^:{}]+)/)
  return m ? m[1] : key
}

// ============ 树形分组 + 懒加载 ============

async function loadGroups(reset) {
  if (reset) {
    treeData.value = []
    totalKeys.value = 0
    selectedKeys.value = []
    scanLimited.value = false
    loadDbSize()
  }
  treeLoading.value = true
  try {
    // 全量 SCAN 收集键名(上限 5000), 按前缀分组
    const all = []
    const seen = new Set()
    let cur = '0'
    let rounds = 0
    while (true) {
      const res = await redisKeys(props.connId, dbIndex.value, buildPattern(), cur, 500)
      const data = res.data || {}
      const batch = data.keys || []
      for (const k of batch) {
        if (!seen.has(k)) {
          seen.add(k)
          all.push(k)
        }
      }
      cur = data.cursor || '0'
      rounds++
      if (cur === '0' || rounds > 60 || all.length >= 5000) {
        if (cur !== '0') scanLimited.value = true
        break
      }
    }
    const groups = {}
    for (const k of all) {
      const g = groupOf(k)
      if (!groups[g]) groups[g] = []
      groups[g].push(k)
    }
    treeData.value = Object.entries(groups)
      .sort((a, b) => a[0].localeCompare(b[0]))
      .map(([name, ks]) => ({
        id: 'g_' + name,
        label: name,
        isGroup: true,
        count: ks.length,
        children: ks.map((k) => ({ id: 'k_' + k, label: k, key: k, isKey: true, type: '', size: '' }))
      }))
    totalKeys.value = all.length
    treeVersion.value++
  } catch (e) {
    ElMessage.error('键列表加载失败: ' + (e.msg || e.message || ''))
  } finally {
    treeLoading.value = false
  }
}

async function loadDbSize() {
  try {
    const res = await redisCli({ connId: props.connId, db: dbIndex.value, command: 'DBSIZE' })
    const data = res.data
    const raw = Array.isArray(data) ? data[0] : data
    const n = Number(String(raw == null ? '' : raw).replace(/^[+:-]/, ''))
    dbSize.value = isNaN(n) ? 0 : n
  } catch (e) {
    dbSize.value = 0
  }
}

async function fillKeyTypes(keys) {
  for (let i = 0; i < keys.length; i += 20) {
    const batch = keys.slice(i, i + 20)
    const results = await Promise.allSettled(batch.map((k) => redisKeyInfo(props.connId, dbIndex.value, k)))
    results.forEach((res, idx) => {
      if (res.status === 'fulfilled') {
        const info = res.value.data || {}
        const k = batch[idx]
        const node = findKeyNode(k)
        if (node) {
          node.type = info.type || ''
          node.size = fmtSize(info.size)
        }
      }
    })
  }
}

function findKeyNode(key) {
  for (const g of treeData.value) {
    const node = (g.children || []).find((c) => c.key === key)
    if (node) return node
  }
  return null
}

function onTreeNodeClick(data) {
  if (data.isKey) {
    selectKey(data.key)
  }
}

function onNodeExpand(data) {
  if (data.isGroup) {
    // 懒加载: 展开分组时才填充该组键的类型/大小
    fillKeyTypes(data.children.map((c) => c.key))
  }
}

function onTreeCheck() {
  if (!keyTreeRef.value) return
  const checked = keyTreeRef.value.getCheckedKeys(true) || []
  selectedKeys.value = checked.filter((id) => String(id).startsWith('k_')).map((id) => String(id).slice(2))
}

function expandAll() {
  expandedKeys.value = treeData.value.map((g) => g.id)
}

function collapseAll() {
  expandedKeys.value = []
}

// ============ 键详情 ============

function selectKey(key) {
  currentKey.value = key
  dirty.value = false
  binaryMode.value = false
  currentBinary.value = false
  selectedRows.value = []
  loadKeyDetail(key)
}

async function loadKeyDetail(key) {
  try {
    const [infoRes, valueRes] = await Promise.all([
      redisKeyInfo(props.connId, dbIndex.value, key),
      redisValue(props.connId, dbIndex.value, key)
    ])
    const info = infoRes.data || {}
    const val = valueRes.data || {}
    currentInfo.type = info.type || val.type || 'string'
    currentInfo.ttl = info.ttl === undefined ? -1 : Number(info.ttl)
    currentInfo.size = info.size || ''
    ttlInput.value = currentInfo.ttl
    const node = findKeyNode(key)
    if (node) {
      node.type = currentInfo.type
      node.size = fmtSize(info.size)
    }
    renderValue(val)
  } catch (e) {
    ElMessage.error('键详情加载失败: ' + (e.msg || e.message || ''))
  }
}

function renderValue(val) {
  const t = currentInfo.type
  if (t === 'string') {
    if (val.binary) {
      currentBinary.value = true
      // 默认文本视图(提取可读字符串), 编辑需切换 Hex
      binaryMode.value = false
      stringValue.value = extractStrings(hexToText(val.hex || ''))
    } else {
      currentBinary.value = false
      binaryMode.value = false
      stringValue.value = val.value ?? ''
    }
  } else if (t === 'hash') {
    tableRows.value = (val.fields || []).map((f, i) => ({ __index: i, field: f.field, value: f.value }))
  } else if (t === 'list') {
    tableRows.value = (val.items || []).map((it, i) => ({ __index: it.index ?? i, value: it.value }))
  } else if (t === 'set') {
    tableRows.value = (val.members || []).map((m, i) => ({ __index: i, member: m, value: m }))
  } else if (t === 'zset') {
    tableRows.value = (val.items || []).map((it, i) => ({ __index: i, member: it.member, score: it.score }))
  }
}

function toggleStringView() {
  if (!currentBinary.value) return
  if (binaryMode.value) {
    // Hex → 文本视图: 提取可读字符串(类似 strings 命令), 仅查看不可保存
    stringValue.value = extractStrings(hexToText(stringValue.value))
    binaryMode.value = false
  } else {
    // 文本 → Hex(重新从原始字节转换)
    binaryMode.value = true
    loadKeyDetail(currentKey.value)
  }
}

// 从二进制内容中提取可读字符串(连续 3 个及以上 ASCII 可打印字符)
function extractStrings(latin1Text) {
  const matches = latin1Text.match(/[\x20-\x7E]{3,}/g)
  if (!matches || !matches.length) return '(无可读文本, 该值为纯二进制内容)'
  return matches.join('\n')
}

function hexToText(hex) {
  let out = ''
  for (let i = 0; i + 1 < hex.length; i += 2) {
    out += String.fromCharCode(parseInt(hex.substring(i, i + 2), 16))
  }
  return out
}

function reloadDetail() {
  if (currentKey.value) {
    currentBinary.value = false
    binaryMode.value = false
    loadKeyDetail(currentKey.value)
  }
}

function addRow() {
  const t = currentInfo.type
  if (t === 'hash') tableRows.value.push({ field: '', value: '' })
  else if (t === 'list') tableRows.value.push({ value: '' })
  else if (t === 'set') tableRows.value.push({ member: '', value: '' })
  else if (t === 'zset') tableRows.value.push({ member: '', score: '0', value: '' })
  dirty.value = true
}

function removeRows() {
  const rm = new Set(selectedRows.value.map((r) => r.__index))
  if (rm.size) {
    tableRows.value = tableRows.value.filter((r) => !rm.has(r.__index))
    dirty.value = true
  }
}

function formatJson() {
  try {
    stringValue.value = JSON.stringify(JSON.parse(stringValue.value), null, 2)
    dirty.value = true
  } catch (e) {
    ElMessage.warning('不是合法的 JSON 内容')
  }
}

function compressJson() {
  try {
    stringValue.value = JSON.stringify(JSON.parse(stringValue.value))
    dirty.value = true
  } catch (e) {
    ElMessage.warning('不是合法的 JSON 内容')
  }
}

async function saveDetail() {
  if (!currentKey.value) return
  const t = currentInfo.type
  const payload = {
    connId: props.connId,
    db: dbIndex.value,
    key: currentKey.value,
    type: t,
    ttl: ttlInput.value,
    value: null
  }
  if (t === 'string') {
    // 二进制键: 文本视图仅查看, 必须切到 Hex 才能编辑保存
    if (currentBinary.value && !binaryMode.value) {
      ElMessage.warning('该键为二进制内容，文本视图仅可查看，请切换到 Hex 视图后编辑保存')
      return
    }
    // 二进制模式: 校验 Hex 并加 __HEX__ 前缀由后端原样写回
    if (binaryMode.value) {
      const hex = (stringValue.value || '').replace(/\s+/g, '').toUpperCase()
      if (!/^[0-9A-F]*$/.test(hex) || hex.length % 2 !== 0) {
        ElMessage.warning('Hex 内容必须为偶数个十六进制字符(如 00ACED)')
        return
      }
      payload.value = '__HEX__' + hex
    } else {
      payload.value = stringValue.value
    }
  }
  if (['hash', 'list', 'set', 'zset'].includes(t)) {
    payload.fields = tableRows.value.map((r) => {
      if (t === 'hash') return { field: r.field, value: r.value }
      if (t === 'list') return { value: r.value }
      if (t === 'set') return { value: r.value }
      return { score: r.score, value: r.value }
    })
  }
  try {
    await redisSet(payload)
    ElMessage.success('已保存')
    dirty.value = false
    reloadDetail()
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.msg || e.message || ''))
  }
}

async function handleDeleteKey(key) {
  try {
    await ElMessageBox.confirm(`确认删除键「${key}」?`, '删除确认', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await redisDel({ connId: props.connId, db: dbIndex.value, keys: [key] })
    ElMessage.success('已删除')
    currentKey.value = ''
    totalKeys.value = Math.max(0, totalKeys.value - 1)
    loadGroups(true)
  } catch (e) {
    ElMessage.error('删除失败: ' + (e.msg || e.message || ''))
  }
}

async function handleDeleteSelected() {
  if (!selectedKeys.value.length) return
  try {
    await ElMessageBox.confirm(`确认删除选中的 ${selectedKeys.value.length} 个键?`, '删除确认', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await redisDel({ connId: props.connId, db: dbIndex.value, keys: selectedKeys.value })
    ElMessage.success('已删除 ' + selectedKeys.value.length + ' 个键')
    selectedKeys.value = []
    loadGroups(true)
  } catch (e) {
    ElMessage.error('删除失败: ' + (e.msg || e.message || ''))
  }
}

function onDbChange() {
  currentKey.value = ''
  loadGroups(true)
}

function openAddDialog() {
  addForm.key = ''
  addForm.type = 'string'
  addForm.ttl = -1
  addForm.value = ''
  addDialog.value = true
}

async function confirmAdd() {
  const key = (addForm.key || '').trim()
  if (!key) {
    ElMessage.warning('请输入键名')
    return
  }
  let value = addForm.value
  if (addForm.type === 'string' && /^__HEX__/.test(value || '')) {
    // 用户输入 __HEX__ 前缀时透传
  } else if (addForm.type === 'string' && /^HEX:/.test(value || '')) {
    value = '__HEX__' + value.substring(4).replace(/\s+/g, '').toUpperCase()
  }
  try {
    await redisSet({
      connId: props.connId,
      db: dbIndex.value,
      key,
      type: addForm.type,
      ttl: addForm.ttl,
      value: addForm.type === 'string' ? value : null,
      fields: addForm.type === 'string' ? null : []
    })
    ElMessage.success('已创建')
    addDialog.value = false
    loadGroups(true)
    // 定位新键
    const g = groupOf(key)
    expandedKeys.value = ['g_' + g]
    selectKey(key)
  } catch (e) {
    ElMessage.error('创建失败: ' + (e.msg || e.message || ''))
  }
}

// ============ CLI ============

async function runCli() {
  const cmd = (cliCmd.value || '').trim()
  if (!cmd) return
  cliLines.value.push({ prompt: `db${dbIndex.value}>`, text: cmd, err: false })
  cliCmd.value = ''
  try {
    const res = await redisCli({ connId: props.connId, db: dbIndex.value, command: cmd })
    const data = res.data
    let text
    if (Array.isArray(data)) {
      text = data.length ? data.join('\n') : '(empty array)'
    } else if (data === null || data === undefined) {
      text = '(nil)'
    } else {
      text = String(data)
    }
    cliLines.value.push({ prompt: '(result)', text, err: false })
  } catch (e) {
    cliLines.value.push({ prompt: '(error)', text: e.msg || e.message || '命令执行失败', err: true })
  }
  await nextTick()
  if (cliOutputRef.value) {
    cliOutputRef.value.scrollTop = cliOutputRef.value.scrollHeight
  }
}

function switchView(v) {
  view.value = v
  if (v === 'info') loadInfo()
  if (v === 'cli' && cliOutputRef.value) {
    cliOutputRef.value.scrollTop = cliOutputRef.value.scrollHeight
  }
}

async function loadInfo() {
  try {
    const res = await redisInfo(props.connId, dbIndex.value)
    infoSections.value = res.data || {}
  } catch (e) {
    ElMessage.error('INFO 加载失败: ' + (e.msg || e.message || ''))
  }
}

function sectionRows(section) {
  return Object.entries(section || {}).map(([k, v]) => ({ key: k, value: String(v) }))
}

onMounted(() => {
  loadGroups(true)
})
</script>

<style scoped>
.redis-manager {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 480px;
  border: 1px solid var(--el-border-color-light, #ebeef5);
  border-radius: 4px;
  overflow: hidden;
  background: #fff;
}
.redis-toolbar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--el-border-color-lighter, #f0f0f0);
  background: #fafafa;
  flex-wrap: nowrap;
  overflow-x: auto;
}
.redis-pattern {
  width: 240px;
}
.redis-toolbar-right {
  margin-left: auto;
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}
.redis-body {
  display: flex;
  flex: 1;
  min-height: 0;
}
/* 左侧键树 */
.redis-keys-pane {
  width: 380px;
  min-width: 300px;
  border-right: 1px solid var(--el-border-color-light, #ebeef5);
  display: flex;
  flex-direction: column;
}
.redis-keys-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 10px;
  background: #f5f7fa;
  border-bottom: 1px solid var(--el-border-color-lighter, #f0f0f0);
}
.rk-total {
  font-size: 12px;
  color: var(--imp-text-secondary);
}
.rk-actions {
  display: flex;
  gap: 2px;
}
.redis-tree {
  flex: 1;
  overflow: auto;
  padding: 4px 0;
}
.redis-tree :deep(.el-tree-node__content) {
  height: 30px;
}
.tree-group-node {
  display: flex;
  align-items: center;
  gap: 5px;
  flex: 1;
  min-width: 0;
  font-weight: 600;
  font-size: 13px;
  color: #303133;
}
.tg-icon {
  color: #e6a23c;
}
.tg-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tg-count {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--imp-text-secondary);
  background: #f0f2f5;
  border-radius: 8px;
  padding: 0 7px;
  line-height: 16px;
}
.tree-key-node {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
}
.rk-type {
  flex-shrink: 0;
  font-size: 10px;
  font-weight: 600;
  padding: 1px 5px;
  border-radius: 3px;
  color: #fff;
  min-width: 46px;
  text-align: center;
}
.rk-type-string { background: #f56c6c; }
.rk-type-hash { background: #67c23a; }
.rk-type-list { background: #409eff; }
.rk-type-set { background: #e6a23c; }
.rk-type-zset { background: #8e44ad; }
.rk-name {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rk-size {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--imp-text-secondary);
}
.redis-keys-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 10px;
  border-top: 1px solid var(--el-border-color-lighter, #f0f0f0);
  background: #fafafa;
}
.rk-cursor {
  font-size: 11px;
  color: var(--imp-text-secondary);
}
/* 右侧详情 */
.redis-detail-pane {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.redis-detail-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter, #f0f0f0);
  background: #fff;
  flex-wrap: wrap;
}
.rd-key {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rd-ttl {
  font-size: 12px;
  color: var(--imp-text-secondary);
  display: flex;
  align-items: center;
  gap: 4px;
}
.rd-toolbar {
  margin-left: auto;
  display: flex;
  gap: 4px;
}
.redis-detail-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 10px 12px;
}
.rd-value-pane {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.rd-string-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  flex-shrink: 0;
  flex-wrap: nowrap;
  white-space: nowrap;
  overflow: hidden;
}
.rd-string-editor {
  flex: 1;
  min-height: 0;
  position: relative;
}
.rd-string-editor :deep(.el-textarea) {
  position: absolute;
  inset: 0;
}
.rd-string-editor :deep(.el-textarea__inner) {
  height: 100% !important;
  font-family: 'JetBrains Mono', Consolas, Menlo, monospace;
  font-size: 13px;
  line-height: 1.6;
  resize: none;
}
.rd-json-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
}
.rd-hint {
  font-size: 11px;
  color: var(--imp-text-secondary);
  margin-left: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.rd-table-pane {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.rd-table-toolbar {
  display: flex;
  gap: 6px;
  margin-bottom: 8px;
}
.rd-table {
  flex: 1;
  min-height: 200px;
}
.rd-idx {
  color: var(--imp-text-secondary);
  font-family: Consolas, monospace;
  font-size: 12px;
}
.rd-empty {
  margin: auto;
}
/* CLI */
.redis-cli-pane {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 400px;
  background: #1e1e1e;
  color: #d4d4d4;
}
.redis-cli-output {
  flex: 1;
  overflow: auto;
  padding: 10px 14px;
  font-family: 'JetBrains Mono', Consolas, Menlo, monospace;
  font-size: 12.5px;
}
.cli-line {
  margin-bottom: 4px;
}
.cli-line.err .cli-text {
  color: #f56c6c;
}
.cli-prompt {
  color: #6a9955;
  margin-right: 6px;
}
.cli-text {
  display: inline;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
  font-family: inherit;
}
.redis-cli-input {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-top: 1px solid #333;
}
.cli-cursor {
  color: #6a9955;
  font-family: Consolas, monospace;
  font-size: 13px;
  white-space: nowrap;
}
.redis-cli-input :deep(.el-input__wrapper) {
  background: #252526;
  box-shadow: none;
  color: #d4d4d4;
}
.redis-cli-input :deep(.el-input__inner) {
  color: #d4d4d4;
  font-family: Consolas, monospace;
}
.redis-cli-hint {
  padding: 4px 14px;
  font-size: 11px;
  color: #888;
  border-top: 1px solid #333;
}
/* Info */
.redis-info-pane {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 10px;
  padding: 12px;
  overflow: auto;
  height: 100%;
}
.redis-info-card {
  border: 1px solid var(--el-border-color-light, #ebeef5);
  border-radius: 4px;
  overflow: hidden;
}
.ri-card-title {
  padding: 6px 10px;
  font-size: 13px;
  font-weight: 600;
  background: #f5f7fa;
  border-bottom: 1px solid var(--el-border-color-lighter, #f0f0f0);
}
/* 命令帮助 */
.redis-help {
  max-height: 520px;
  overflow: auto;
}
.rh-group {
  margin-bottom: 12px;
}
.rh-group-title {
  font-size: 13px;
  font-weight: 600;
  color: #409eff;
  margin-bottom: 4px;
}
.rh-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.rh-table td {
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  padding: 4px 8px;
}
.rh-cmd {
  width: 90px;
  font-family: Consolas, monospace;
  color: #303133;
  font-weight: 600;
  white-space: nowrap;
}
.rh-example {
  width: 280px;
  font-family: Consolas, monospace;
  color: #409eff;
  white-space: nowrap;
}
.rh-desc {
  color: var(--imp-text-secondary);
}
</style>
