<template>
  <div class="p-2 app-container">
    <div class="search-wrap">
      <el-card shadow="never" class="search-panel">
        <template #header>
          <div class="panel-heading">
            <div>

              <h3>数据库驱动管理</h3>
            </div>
          </div>
        </template>
        <el-form :model="queryParams" :inline="true" class="query-form" @submit.prevent>
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
          <el-form-item label="关键字" prop="keyword">
            <el-input v-model="queryParams.keyword" placeholder="驱动名称/版本" clearable style="width: 180px" @keyup.enter="handleQuery" />
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

            <h3>驱动列表</h3>
            <p>支持从 Maven Central 自动下载驱动, 或手动上传本地 jar 文件。</p>
          </div>
          <div class="toolbar-actions">
            <el-button v-hasPermi="['dbtool:driver:add']" type="primary" plain icon="Plus" @click="openAdd">新增驱动</el-button>
            <el-button
              v-hasPermi="['dbtool:driver:remove']"
              type="danger"
              plain
              icon="Delete"
              :disabled="multiple"
              @click="handleDelete()"
            >删除</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" border class="data-table" :data="driverList" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="55" align="center" />
        <el-table-column label="数据库类型" align="center" prop="dbType" width="120">
          <template #default="scope">
            <el-tag :type="typeTag(scope.row.dbType)" effect="light">{{ typeName(scope.row.dbType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="驱动名称" align="center" prop="driverName" min-width="180" show-overflow-tooltip />
        <el-table-column label="版本" align="center" prop="driverVersion" width="130" />
        <el-table-column label="驱动类" align="center" prop="driverClass" min-width="200" show-overflow-tooltip />
        <el-table-column label="来源" align="center" prop="source" width="90">
          <template #default="scope">
            <el-tag :type="sourceTag(scope.row.source)" size="small">{{ sourceName(scope.row.source) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="文件大小" align="center" prop="fileSize" width="100">
          <template #default="scope">
            {{ formatSize(scope.row.fileSize) }}
          </template>
        </el-table-column>
        <el-table-column label="创建时间" align="center" prop="createTime" width="160" />
        <el-table-column label="操作" align="center" width="150" fixed="right" class-name="op-col">
          <template #default="scope">
            <el-button v-hasPermi="['dbtool:driver:edit']" link type="primary" class="op-btn" @click="openEdit(scope.row)">编辑</el-button>
            <el-button v-hasPermi="['dbtool:driver:remove']" link type="danger" class="op-btn" @click="handleDelete(scope.row)">删除</el-button>
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

    <DbTypePicker
      v-model:visible="queryPickerVisible"
      :multiple="true"
      :model-value="queryDbTypes"
      :initial-keyword="queryTypeKeyword"
      title="选择数据库类型"
      @select="onQueryTypeSelect"
    />

    <!-- 新增驱动弹窗 -->
    <el-dialog v-model="dialog.visible" :title="dialog.title" width="640px" append-to-body>
      <el-tabs v-model="activeTab">
        <el-tab-pane label="自动下载" name="download">
          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="从 Maven Central 自动下载 JDBC 驱动 jar。内置驱动(MySQL/PostgreSQL等)无需下载。"
            style="margin-bottom: 14px"
          />
          <el-form :model="dlForm" label-width="100px">
            <el-form-item label="数据库类型" required>
              <el-select v-model="dlForm.dbType" placeholder="选择数据库类型" filterable style="width: 100%" @change="onDlTypeChange">
                <el-option
                  v-for="t in typeList"
                  :key="t.code"
                  :label="t.name + (t.builtin ? '（已内置）' : '')"
                  :value="t.code"
                  :disabled="t.builtin"
                />
              </el-select>
              <div class="form-tip">标记"（已内置）"的驱动已集成在应用中, 无需下载。</div>
            </el-form-item>
            <el-form-item label="驱动版本" required>
              <el-select v-model="dlForm.version" placeholder="选择版本" style="width: 100%">
                <el-option v-for="v in dlVersions" :key="v" :label="v" :value="v" />
              </el-select>
            </el-form-item>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="手动上传" name="upload">
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            title="上传本地 JDBC 驱动 jar 文件(如从数据库厂商官网下载的驱动包)。"
            style="margin-bottom: 14px"
          />
          <el-form :model="upForm" label-width="100px">
            <el-form-item label="数据库类型">
              <el-select v-model="upForm.dbType" placeholder="选择类型(可空)" clearable filterable style="width: 100%">
                <el-option v-for="t in typeList" :key="t.code" :label="t.name" :value="t.code" />
              </el-select>
            </el-form-item>
            <el-form-item label="驱动版本">
              <el-input v-model="upForm.version" placeholder="自定义版本标识(可空, 默认 custom)" />
            </el-form-item>
            <el-form-item label="驱动文件" required>
              <el-upload
                :auto-upload="false"
                :limit="1"
                accept=".jar"
                :on-change="onFileChange"
                :on-remove="() => (upForm.file = null)"
                drag
              >
                <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
                <div class="el-upload__text">拖拽或 <em>点击选择</em> jar 文件</div>
              </el-upload>
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <div class="dialog-footer">
          <el-button v-if="activeTab === 'download'" type="primary" :loading="downloading" @click="submitDownload">开始下载</el-button>
          <el-button v-else type="primary" :loading="uploading" @click="submitUpload">开始上传</el-button>
          <el-button @click="dialog.visible = false">取消</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 编辑驱动弹窗 -->
    <el-dialog v-model="editDialog.visible" :title="editDialog.title" width="560px" append-to-body>
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="100px">
        <el-alert
          v-if="editForm.source === '1'"
          type="info"
          :closable="false"
          show-icon
          title="内置驱动由系统注册表管理, 名称/版本/驱动类不可修改, 仅可调整状态与备注。"
          style="margin-bottom: 14px"
        />
        <el-form-item label="数据库类型">
          <el-input :model-value="typeName(editForm.dbType)" disabled />
        </el-form-item>
        <el-form-item label="驱动名称" prop="driverName">
          <el-input v-model="editForm.driverName" :disabled="editForm.source === '1'" placeholder="驱动名称(如 com.mysql:mysql-connector-j)" />
        </el-form-item>
        <el-form-item label="驱动版本" prop="driverVersion">
          <el-input v-model="editForm.driverVersion" :disabled="editForm.source === '1'" placeholder="驱动版本" />
        </el-form-item>
        <el-form-item label="驱动类" prop="driverClass">
          <el-input v-model="editForm.driverClass" :disabled="editForm.source === '1'" placeholder="JDBC 驱动类名" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="editForm.status">
            <el-radio value="0">正常</el-radio>
            <el-radio value="1">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="editForm.remark" type="textarea" :rows="2" placeholder="备注说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button type="primary" :loading="saving" @click="submitEdit">保存</el-button>
          <el-button @click="editDialog.visible = false">取消</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="DbDriver">
import { listDbDriver, delDbDriver, updateDbDriver, downloadDbDriver, uploadDbDriver, listDbTypes } from '@/api/dbtool/index'
import { ElMessage, ElMessageBox } from 'element-plus'
import { reactive, ref, computed } from 'vue'
import { ArrowDown, CircleClose } from '@element-plus/icons-vue'
import DbTypePicker from '../components/DbTypePicker.vue'

const loading = ref(false)
const driverList = ref([])
const total = ref(0)
const ids = ref([])
const multiple = ref(true)
const downloading = ref(false)
const uploading = ref(false)
const activeTab = ref('download')

const typeList = ref([])
const dialog = reactive({ visible: false, title: '' })

const queryParams = reactive({
  pageNum: 1,
  pageSize: 10,
  dbType: undefined,
  keyword: undefined
})

// 搜索区类型多选
const queryDbTypes = ref([])
const queryTypeKeyword = ref('')
const queryPickerVisible = ref(false)
// 类型输入框聚焦状态(聚焦时隐藏"请选择类型"提示)
const typeInputFocused = ref(false)

function openTypePicker() {
  queryPickerVisible.value = true
}

function onQueryTypeClear() {
  queryTypeKeyword.value = ''
}

// 右侧 X: 清空已选类型
function clearQueryType() {
  queryDbTypes.value = []
  queryTypeKeyword.value = ''
  queryParams.dbType = ''
  handleQuery()
}

function onQueryTypeSelect(types) {
  queryDbTypes.value = (types || []).map((t) => t.code)
  queryTypeKeyword.value = ''
  queryParams.dbType = queryDbTypes.value.join(',')
  handleQuery()
}

function removeQueryType(code) {
  queryDbTypes.value = queryDbTypes.value.filter((c) => c !== code)
  queryParams.dbType = queryDbTypes.value.join(',')
  handleQuery()
}

const dlForm = reactive({ dbType: undefined, version: undefined })
const upForm = reactive({ dbType: undefined, version: undefined, file: null })

// 编辑弹窗
const saving = ref(false)
const editFormRef = ref()
const editDialog = reactive({ visible: false, title: '编辑驱动' })
const editForm = reactive({ id: undefined, dbType: undefined, driverName: undefined, driverVersion: undefined, driverClass: undefined, source: undefined, status: '0', remark: undefined })
const editRules = {
  driverName: [{ required: true, message: '驱动名称不能为空', trigger: 'blur' }],
  driverVersion: [{ required: true, message: '驱动版本不能为空', trigger: 'blur' }]
}

const dlVersions = computed(() => {
  const t = typeList.value.find((x) => x.code === dlForm.dbType)
  return t?.versions || []
})

function typeName(type) {
  return typeList.value.find((x) => x.code === type)?.name || type || ''
}

function typeTag(type) {
  const map = { MYSQL: 'primary', POSTGRESQL: 'success', ORACLE: 'warning', SQLSERVER: 'info', MONGODB: 'success', REDIS: 'danger', SQLITE: 'info' }
  return map[type] || 'primary'
}

function sourceName(s) {
  return { 1: '内置', 2: '自动下载', 3: '手动上传' }[s] || s
}

function sourceTag(s) {
  return { 1: 'info', 2: 'primary', 3: 'warning' }[s] || 'info'
}

function formatSize(bytes) {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(2) + ' MB'
}

function onDlTypeChange(code) {
  const t = typeList.value.find((x) => x.code === code)
  dlForm.version = t?.defaultVersion || (t?.versions?.[0] || undefined)
}

function onFileChange(file) {
  upForm.file = file.raw
}

function loadTypes() {
  listDbTypes().then((res) => {
    typeList.value = res.data?.types || []
  })
}

function getList() {
  loading.value = true
  listDbDriver(queryParams).then((res) => {
    const data = res.data
    driverList.value = data.records
    total.value = data.total
    loading.value = false
  })
}

function handleQuery() {
  queryParams.pageNum = 1
  getList()
}

function resetQuery() {
  queryDbTypes.value = []
  queryTypeKeyword.value = ''
  queryParams.dbType = undefined
  queryParams.keyword = undefined
  handleQuery()
}

function handleSelectionChange(selection) {
  ids.value = selection.map((item) => item.id)
  multiple.value = !selection.length
}

function openAdd() {
  dlForm.dbType = undefined
  dlForm.version = undefined
  upForm.dbType = undefined
  upForm.version = undefined
  upForm.file = null
  dialog.title = '新增数据库驱动'
  dialog.visible = true
}

function openEdit(row) {
  editForm.id = row.id
  editForm.dbType = row.dbType
  editForm.driverName = row.driverName
  editForm.driverVersion = row.driverVersion
  editForm.driverClass = row.driverClass
  editForm.source = row.source
  editForm.status = row.status || '0'
  editForm.remark = row.remark
  editDialog.title = '编辑驱动 - ' + typeName(row.dbType)
  editDialog.visible = true
}

function submitEdit() {
  editFormRef.value.validate((valid) => {
    if (!valid) return
    saving.value = true
    updateDbDriver({ ...editForm })
      .then(() => {
        ElMessage.success('驱动信息保存成功')
        editDialog.visible = false
        getList()
      })
      .catch((err) => {
        ElMessage.error(err?.msg || '驱动信息保存失败')
      })
      .finally(() => {
        saving.value = false
      })
  })
}

function submitDownload() {
  if (!dlForm.dbType) {
    ElMessage.warning('请选择数据库类型')
    return
  }
  if (!dlForm.version) {
    ElMessage.warning('请选择驱动版本')
    return
  }
  downloading.value = true
  downloadDbDriver(dlForm.dbType, dlForm.version)
    .then(() => {
      ElMessage.success('驱动下载成功')
      dialog.visible = false
      getList()
    })
    .catch((err) => {
      ElMessage.error(err?.msg || '驱动下载失败')
    })
    .finally(() => {
      downloading.value = false
    })
}

function submitUpload() {
  if (!upForm.file) {
    ElMessage.warning('请选择驱动 jar 文件')
    return
  }
  const fd = new FormData()
  fd.append('file', upForm.file)
  if (upForm.dbType) fd.append('dbType', upForm.dbType)
  if (upForm.version) fd.append('version', upForm.version)
  uploading.value = true
  uploadDbDriver(fd)
    .then(() => {
      ElMessage.success('驱动上传成功')
      dialog.visible = false
      getList()
    })
    .catch((err) => {
      ElMessage.error(err?.msg || '驱动上传失败')
    })
    .finally(() => {
      uploading.value = false
    })
}

function handleDelete(row) {
  const delRows = row?.id ? [row] : driverList.value.filter((d) => ids.value.includes(d.id))
  const builtinRows = delRows.filter((d) => d.source === '1')
  if (builtinRows.length) {
    ElMessage.warning('内置驱动不可删除')
    return
  }
  const delIds = delRows.map((d) => d.id)
  ElMessageBox.confirm('是否确认删除选中的驱动? 删除后 jar 文件将被移除。', '系统提示', {
    type: 'warning'
  }).then(() => {
    return delDbDriver(delIds.join(','))
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
.query-type-box {
  position: relative;
  display: flex;
  align-items: center;
  gap: 2px;
  width: 240px;
  min-height: 32px;
  max-height: 64px;
  overflow-y: auto;
  padding: 2px 26px 2px 6px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #fff;
  cursor: pointer;
  box-sizing: border-box;
}
.query-type-box:hover {
  border-color: #c0c4cc;
}
.query-type-box .query-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
  flex: 1;
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
.query-type-box .query-tags .el-tag {
  margin: 0;
}
.query-type-box :deep(.el-input__wrapper) {
  box-shadow: none !important;
  padding: 0;
  background: transparent;
}
.query-type-box .query-type-input {
  flex: 1;
  min-width: 60px;
}
.query-type-box .query-type-clear {
  position: absolute;
  right: 24px;
  top: 50%;
  transform: translateY(-50%);
  color: #c0c4cc;
  cursor: pointer;
  font-size: 14px;
  z-index: 2;
}
.query-type-box .query-type-clear:hover {
  color: var(--imp-text-secondary);
}
.query-type-box .query-type-arrow {
  position: absolute;
  right: 6px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--imp-text-secondary);
  cursor: pointer;
  font-size: 14px;
  pointer-events: auto;
}
.query-type-box .query-type-arrow:hover {
  color: #409eff;
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
.form-tip {
  font-size: 12px;
  color: var(--imp-text-secondary);
  line-height: 1.6;
  margin-top: 4px;
}
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
</style>
