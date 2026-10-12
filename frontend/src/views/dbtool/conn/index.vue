<template>
  <div class="p-2 app-container dbtool-conn-page">
    <div class="search-wrap">
      <el-card shadow="never" class="search-panel">
        <template #header>
          <div class="panel-heading">
            <div>

              <h3>数据源管理</h3>
            </div>
          </div>
        </template>
        <el-form :model="queryParams" :inline="true" class="query-form" @submit.prevent>
          <el-form-item label="数据源名称" prop="connName">
            <el-input v-model="queryParams.connName" placeholder="数据源名称" clearable @keyup.enter="handleQuery" />
          </el-form-item>
          <el-form-item label="数据库类型" prop="dbType">
            <div
              class="query-type-box"
              :class="{ 'has-value': queryDbTypes.length }"
              title="双击或点击右侧箭头打开类型选择"
              @dblclick="openTypePicker"
            >
              <div v-if="queryDbTypes.length" class="query-tags">
                <el-tag
                  v-for="c in queryDbTypes"
                  :key="c"
                  size="small"
                  closable
                  @close.stop="removeQueryType(c)"
                >{{ typeName(c) }}</el-tag>
              </div>
              <el-input
                v-model="queryTypeKeyword"
                class="query-type-input"
                :placeholder="typeInputFocused ? '' : '请选择类型'"
                clearable
                @keyup.enter="openTypePicker"
                @dblclick.stop="openTypePicker"
                @clear="onQueryTypeClear"
                @focus="typeInputFocused = true"
                @blur="typeInputFocused = false"
              />
              <el-icon v-if="queryDbTypes.length" class="query-type-clear" title="清空已选类型" @click.stop="clearQueryType">
                <CircleClose />
              </el-icon>
              <el-icon class="query-type-arrow" title="打开类型选择" @click="openTypePicker"><ArrowDown /></el-icon>
            </div>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
            <el-button icon="Refresh" @click="resetQuery">重置</el-button>
          </el-form-item>
        </el-form>
      </el-card>
    </div>

    <el-card shadow="never" class="table-panel">
      <template #header>
        <div class="toolbar-shell">
          <div class="table-heading">

            <h3>数据源列表</h3>
            <p>维护数据源连接信息, 供数据库工作台使用。</p>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['dbtool:conn:add']" type="primary" plain icon="Plus" @click="handleAdd">新增</el-button>
            <el-button v-hasPermi="['dbtool:conn:add']" icon="Download" @click="importVisible = true">导入</el-button>
            <el-button icon="FolderOpened" @click="backupVisible = true">备份/恢复</el-button>
            <el-button
              v-hasPermi="['dbtool:conn:remove']"
              type="danger"
              plain
              icon="Delete"
              :disabled="multiple"
              @click="handleDelete()"
            >删除</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border class="data-table" :data="connList" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="55" align="center" />
        <el-table-column label="数据源名称" align="center" prop="connName" min-width="130" />
        <el-table-column label="数据库类型" align="center" prop="dbType" width="130">
          <template #default="scope">
            <el-tag :type="typeTag(scope.row.dbType)" effect="light">{{ typeName(scope.row.dbType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="版本" align="center" prop="dbVersion" width="90">
          <template #default="scope">
            <span>{{ scope.row.dbVersion || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="主机地址" align="center" prop="host" width="130" />
        <el-table-column label="端口" align="center" prop="port" width="70" />
        <el-table-column label="数据库" align="center" prop="databaseName" min-width="110" show-overflow-tooltip />
        <el-table-column label="访问模式" align="center" prop="accessMode" width="100">
          <template #default="scope">
            <el-tag :type="scope.row.accessMode === 'READ_ONLY' ? 'warning' : 'success'">
              {{ scope.row.accessMode === 'READ_ONLY' ? '只读' : '读写' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" align="center" prop="status" width="70">
          <template #default="scope">
            <el-tag :type="scope.row.status === '0' ? 'success' : 'danger'">
              {{ scope.row.status === '0' ? '正常' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" align="center" prop="createTime" width="160" />
        <el-table-column label="操作" align="center" width="200" fixed="right" class-name="op-col">
          <template #default="scope">
            <el-button link type="primary" class="op-btn" @click="handleTest(scope.row)">测试</el-button>
            <el-button v-hasPermi="['dbtool:conn:edit']" link type="primary" class="op-btn" @click="handleUpdate(scope.row)">编辑</el-button>
            <el-button v-hasPermi="['dbtool:conn:remove']" link type="danger" class="op-btn" @click="handleDelete(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <pagination
        v-show="total > 0"
        v-model:page="queryParams.pageNum"
        v-model:limit="queryParams.pageSize"
        :total="total"
        @pagination="getList"
      />
    </el-card>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialog.visible" :title="dialog.title" width="600px" append-to-body>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="数据源名称" prop="connName">
              <el-input v-model="form.connName" placeholder="请输入数据源名称" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="数据库类型" prop="dbType">
              <el-input
                v-model="typeDisplay"
                placeholder="输入或点击选择数据库类型"
                clearable
                style="width: 100%"
                @input="onTypeInput"
                @focus="onTypeFocus"
                @clear="onTypeClear"
              >
                <template #prefix>
                  <span v-if="form.dbType" class="type-dot" :style="{ background: typeColor(form.dbType) }"></span>
                </template>
              </el-input>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="数据库版本" prop="dbVersion">
              <el-select v-model="form.dbVersion" placeholder="选择版本(可空)" clearable style="width: 100%">
                <el-option v-for="v in currentVersions" :key="v" :label="v" :value="v" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="主机地址" prop="host">
              <el-input v-model="form.host" placeholder="如 127.0.0.1" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="端口" prop="port">
              <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="数据库名" prop="databaseName">
              <el-input v-model="form.databaseName" placeholder="默认数据库(可空)" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="form.username" placeholder="请输入用户名" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="密码" prop="password">
              <el-input v-model="form.password" type="password" show-password placeholder="请输入密码" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态" prop="status">
              <el-radio-group v-model="form.status">
                <el-radio value="0">正常</el-radio>
                <el-radio value="1">停用</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="访问模式" prop="accessMode">
              <el-select v-model="form.accessMode" style="width: 100%">
                <el-option label="读写" value="READ_WRITE" />
                <el-option label="只读(禁止所有写入操作)" value="READ_ONLY" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-alert
              title="只读模式会限制工作台写入并设置连接池只读标记；生产环境仍建议使用数据库只读账号作为最终权限边界。"
              type="info"
              :closable="false"
              show-icon
            />
          </el-col>
          <el-col :span="24">
            <el-form-item label="扩展参数" prop="params">
              <el-input v-model="form.params" placeholder="如 useSSL=false&serverTimezone=Asia/Shanghai" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-divider content-position="left">SSH 隧道(跳板机访问)</el-divider>
          </el-col>
          <el-col :span="24">
            <el-form-item label="启用隧道" prop="sshUseTunnel">
              <el-switch v-model="form.sshUseTunnel" active-value="1" inactive-value="0" />
            </el-form-item>
          </el-col>
          <template v-if="form.sshUseTunnel === '1'">
            <el-col :span="12">
              <el-form-item label="SSH 主机" prop="sshHost">
                <el-input v-model="form.sshHost" placeholder="跳板机地址, 如 10.0.0.5" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="SSH 端口" prop="sshPort">
                <el-input-number v-model="form.sshPort" :min="1" :max="65535" controls-position="right" style="width: 100%" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="SSH 用户" prop="sshUser">
                <el-input v-model="form.sshUser" placeholder="跳板机用户名" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="SSH 密码" prop="sshPassword">
                <el-input v-model="form.sshPassword" type="password" show-password placeholder="跳板机密码" />
              </el-form-item>
            </el-col>
            <el-col :span="24">
              <el-alert
                title="启用后, 工作台/测试/Redis/ES 均通过本地端口转发访问数据库真实地址(host:port)。连接编辑或删除时隧道自动释放。"
                type="info"
                :closable="false"
                show-icon
              />
            </el-col>
          </template>
          <el-col :span="24">
            <el-form-item label="备注" prop="remark">
              <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="请输入备注" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button :loading="testing" @click="handleTest(form)">测试连接</el-button>
          <el-button type="primary" @click="submitForm">确定</el-button>
          <el-button @click="handleCancel">取消</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 测试结果弹窗 -->
    <el-dialog v-model="testDialog.visible" title="连接测试结果" width="420px" append-to-body>
      <el-result :icon="testDialog.success ? 'success' : 'error'" :title="testDialog.success ? '连接成功' : '连接失败'" :sub-title="testDialog.message">
        <template #extra>
          <el-button type="primary" @click="testDialog.visible = false">关闭</el-button>
        </template>
      </el-result>
    </el-dialog>

    <!-- 数据库类型选择器(仿DBeaver) -->
    <DbTypePicker v-model="form.dbType" v-model:visible="pickerVisible" :initial-keyword="typeKeyword" @select="onTypeSelect" />
    <!-- 搜索区类型多选选择器(单向绑定, 确定时通过 @select 回传) -->
    <DbTypePicker :model-value="queryDbTypes" v-model:visible="queryPickerVisible" multiple :initial-keyword="queryTypeKeyword" @select="onQueryTypeSelect" />

    <DbConnImportDialog v-if="importVisible" @close="importVisible = false" @imported="handleQuery" />
    <DbBackupDialog v-if="backupVisible" @close="backupVisible = false" />
  </div>
</template>

<script setup name="DbConn">
import { listDbConn, getDbConn, addDbConn, updateDbConn, delDbConn, testDbConn, listDbTypes } from '@/api/dbtool/index'
import DbTypePicker from '@/views/dbtool/components/DbTypePicker.vue'
import DbConnImportDialog from './DbConnImportDialog.vue'
import DbBackupDialog from './DbBackupDialog.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { reactive, ref, computed } from 'vue'
import { ArrowDown, CircleClose } from '@element-plus/icons-vue'

const loading = ref(false)
const importVisible = ref(false)
const backupVisible = ref(false)
const connList = ref([])
const total = ref(0)
const ids = ref([])
const multiple = ref(true)
const testing = ref(false)
const formRef = ref()

// 数据库类型注册信息
const typeList = ref([])
const pickerVisible = ref(false)
// 搜索区多选类型
const queryDbTypes = ref([])
// 类型输入框聚焦状态(聚焦时隐藏"请选择类型"提示)
const typeInputFocused = ref(false)
const queryPickerVisible = ref(false)
const queryTypeKeyword = ref('')
// 表单类型选择框输入关键字
const typeKeyword = ref('')
// 类型选择框显示值: 输入时显示关键字, 否则显示已选类型名
const typeDisplay = computed({
  get: () => typeKeyword.value || (form.value.dbType ? typeName(form.value.dbType) : ''),
  set: (v) => {
    typeKeyword.value = v
  }
})

const queryParams = reactive({
  pageNum: 1,
  pageSize: 10,
  connName: undefined,
  dbType: undefined
})

const dialog = reactive({
  visible: false,
  title: ''
})

const testDialog = reactive({
  visible: false,
  success: true,
  message: ''
})

const form = ref({})
// 无需用户名/密码的数据库类型(NoSQL / REST)
const noAuthTypes = ['REDIS', 'MONGODB', 'ELASTICSEARCH']
const rules = {
  connName: [{ required: true, message: '连接名称不能为空', trigger: 'blur' }],
  dbType: [{ required: true, message: '数据库类型不能为空', trigger: 'change' }],
  host: [{ required: true, message: '主机地址不能为空', trigger: 'blur' }],
  port: [{ required: true, message: '端口不能为空', trigger: 'blur' }],
  username: [{
    validator: (rule, value, callback) => {
      if (!value && !noAuthTypes.includes(form.value.dbType)) {
        callback(new Error('用户名不能为空'))
      } else {
        callback()
      }
    },
    trigger: 'blur'
  }]
}

// 当前类型可选的版本列表
const currentVersions = computed(() => {
  const t = typeList.value.find((x) => x.code === form.value.dbType)
  return t?.versions || []
})

function typeInfo(type) {
  return typeList.value.find((x) => x.code === type)
}

function typeName(type) {
  return typeInfo(type)?.name || type || ''
}

function typeColor(type) {
  const map = {
    MYSQL: '#00758f', POSTGRESQL: '#336791', MARIADB: '#002b5c', ORACLE: '#c74634',
    SQLSERVER: '#a91d22', DB2: '#003b6f', DAMENG: '#e60012', KINGBASE: '#c43a2b',
    OCEANBASE: '#005eff', TIDB: '#18b8f1', GBASE: '#1d8f4c', HIGRES: '#0b6bd8',
    MONGODB: '#47a248', REDIS: '#d82c20', HIVE: '#fdb813', CLICKHOUSE: '#ffcc01',
    DORIS: '#000000', STARROCKS: '#2d5bff', DUCKDB: '#7f7f7f', INFLUXDB: '#22adf6',
    SQLITE: '#0f80cc', H2: '#4e7ab5', ELASTICSEARCH: '#005571'
  }
  return map[type] || '#6b7280'
}

function typeTag(type) {
  const map = { MYSQL: 'primary', POSTGRESQL: 'success', ORACLE: 'warning', SQLSERVER: 'info', MONGODB: 'success', REDIS: 'danger', SQLITE: 'info' }
  return map[type] || 'primary'
}

// 选择器选中类型后, 联动端口与版本
function onTypeSelect(type) {
  form.value.dbType = type.code
  form.value.dbVersion = type.defaultVersion || ''
  if (type.defaultPort) {
    form.value.port = type.defaultPort
  }
  typeKeyword.value = ''
  pickerVisible.value = false
  // 输入框失焦, 避免残留焦点在后续点击主弹窗按钮时再次弹出选择器遮罩
  if (document.activeElement && typeof document.activeElement.blur === 'function') {
    document.activeElement.blur()
  }
}

// 类型输入框: 仅输入非空关键字时打开弹窗并联动过滤(避免占位/清空等误触发弹窗遮罩)
function onTypeInput() {
  if (typeKeyword.value) {
    pickerVisible.value = true
  }
}

function onTypeFocus() {
  pickerVisible.value = true
}

function onTypeClear() {
  form.value.dbType = ''
  form.value.dbVersion = ''
  typeKeyword.value = ''
}

function loadTypes() {
  listDbTypes().then((res) => {
    typeList.value = res.data?.types || []
  })
}

// 搜索区多选类型联动(确定时回传完整选中类型, 选择后触发搜索)
function onQueryTypeSelect(selected) {
  queryDbTypes.value = (selected || []).map((t) => t.code)
  queryTypeKeyword.value = ''
  queryPickerVisible.value = false
  handleQuery()
}

// 搜索区类型输入: 输入时打开多选弹窗并联动过滤
function openTypePicker() {
  queryPickerVisible.value = true
}

// el-input clearable 清空输入关键字
function onQueryTypeClear() {
  queryTypeKeyword.value = ''
}

// 右侧 X: 清空已选类型
function clearQueryType() {
  queryDbTypes.value = []
  queryTypeKeyword.value = ''
  handleQuery()
}

function removeQueryType(code) {
  queryDbTypes.value = queryDbTypes.value.filter((c) => c !== code)
  handleQuery()
}

function getList() {
  loading.value = true
  const params = {
    ...queryParams,
    dbType: queryDbTypes.value.length ? queryDbTypes.value.join(',') : undefined
  }
  listDbConn(params).then((res) => {
    const data = res.data
    connList.value = data.records
    total.value = data.total
    loading.value = false
  })
}

function handleQuery() {
  queryParams.pageNum = 1
  getList()
}

function resetQuery() {
  queryParams.connName = undefined
  queryDbTypes.value = []
  queryTypeKeyword.value = ''
  handleQuery()
}

function handleSelectionChange(selection) {
  ids.value = selection.map((item) => item.id)
  multiple.value = !selection.length
}

function reset() {
  form.value = {
    id: undefined,
    connName: undefined,
    dbType: 'MYSQL',
    dbVersion: undefined,
    accessMode: 'READ_WRITE',
    host: '127.0.0.1',
    port: 3306,
    databaseName: undefined,
    username: undefined,
    password: undefined,
    params: undefined,
    sshUseTunnel: '0',
    sshHost: undefined,
    sshPort: 22,
    sshUser: undefined,
    sshPassword: undefined,
    status: '0',
    remark: undefined
  }
  formRef.value?.resetFields()
}

function handleAdd() {
  reset()
  dialog.title = '新增数据库连接'
  dialog.visible = true
}

function handleUpdate(row) {
  reset()
  getDbConn(row.id).then((res) => {
    form.value = { ...res.data }
    form.value.accessMode = form.value.accessMode || 'READ_WRITE'
    form.value.password = undefined
    dialog.title = '编辑数据库连接'
    dialog.visible = true
  })
}

function handleTest(row) {
  testing.value = true
  const data = { ...row }
  // 编辑态下密码为空时后端会用已存密码
  testDbConn(data)
    .then((res) => {
      const r = res.data
      testDialog.success = r.success
      testDialog.message = r.message
      testDialog.visible = true
    })
    .finally(() => {
      testing.value = false
    })
}

function submitForm() {
  formRef.value.validate((valid) => {
    if (valid) {
      // 兜底关闭类型选择器, 避免残留遮罩挡住弹窗
      pickerVisible.value = false
      if (form.value.id) {
        updateDbConn(form.value).then(() => {
          ElMessage.success('修改成功')
          dialog.visible = false
          getList()
        })
      } else {
        addDbConn(form.value).then(() => {
          ElMessage.success('新增成功')
          dialog.visible = false
          getList()
        })
      }
    }
  })
}

// 取消: 同时关闭类型选择器, 确保返回列表页
function handleCancel() {
  pickerVisible.value = false
  dialog.visible = false
}

function handleDelete(row) {
  const delIds = row?.id ? [row.id] : ids.value
  ElMessageBox.confirm('是否确认删除选中的数据库连接?', '系统提示', {
    type: 'warning'
  }).then(() => {
    return delDbConn(delIds.join(','))
  }).then(() => {
    getList()
    ElMessage.success('删除成功')
  }).catch(() => {})
}

getList()
loadTypes()
</script>

<style scoped>
.search-wrap {
  margin-bottom: 12px;
}
.panel-heading h3 {
  margin: 0;
  font-size: 16px;
}
.table-heading h3 {
  margin: 0;
  font-size: 16px;
}
.table-heading p {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--imp-text-secondary);
}
.toolbar-shell {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
/* 操作列按钮：紧凑、不换行、不重叠 */
.data-table :deep(.op-col .cell) {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  white-space: nowrap;
}
.data-table :deep(.op-col .op-btn) {
  padding: 0 4px;
  margin: 0;
}
.type-dot {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: middle;
}
/* 搜索区多选类型选择框(仿 el-select) */
.query-type-box {
  display: flex;
  align-items: center;
  gap: 4px;
  min-height: 32px;
  max-height: 64px;
  padding: 2px 10px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #fff;
  cursor: pointer;
  transition: border-color 0.2s;
  width: 280px;
  box-sizing: border-box;
  overflow-y: auto;
}
.query-type-box:hover {
  border-color: #c0c4cc;
}
.query-tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  flex: 1;
  overflow: hidden;
}
/* 已填充值(选中类型)与输入框比例 2:1 */
.query-type-box.has-value .query-tags {
  flex: 2;
}
.query-type-box.has-value .query-type-input {
  flex: 1;
}
/* 未填充值时输入框占满 100% */
.query-type-box:not(.has-value) .query-type-input {
  flex: 1;
  width: 100%;
}
.query-type-input {
  flex: 1;
  min-width: 60px;
}
.query-type-input :deep(.el-input__wrapper) {
  box-shadow: none !important;
  background: transparent;
  padding: 0 2px;
}
.query-placeholder {
  color: #a8abb2;
  font-size: 14px;
  flex: 1;
}
.query-type-clear {
  color: #c0c4cc;
  font-size: 14px;
  flex-shrink: 0;
  cursor: pointer;
}
.query-type-clear:hover {
  color: var(--imp-text-secondary);
}
.query-type-arrow {
  color: #c0c4cc;
  font-size: 14px;
  flex-shrink: 0;
  cursor: pointer;
}
.query-type-arrow:hover {
  color: var(--imp-text-secondary);
}
</style>
