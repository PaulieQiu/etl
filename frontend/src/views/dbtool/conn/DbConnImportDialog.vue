<template>
  <el-dialog v-model="visible" title="导入数据库连接" width="900px" append-to-body destroy-on-close @closed="emit('close')">
    <el-alert
      title="支持 DBeaver connections.xml 与通用 JSON 连接配置。出于安全考虑, 检测到的密码默认不导入, 请导入后在连接编辑页手动填写。"
      type="info"
      :closable="false"
      show-icon
      class="import-tip"
    />
    <div class="import-file">
      <el-upload
        :auto-upload="false"
        :show-file-list="false"
        accept=".xml,.json,.txt"
        :on-change="onFileChange"
      >
        <el-button icon="Upload">选择文件</el-button>
      </el-upload>
      <span v-if="fileName" class="import-file-name">{{ fileName }}</span>
      <el-button type="primary" :loading="loading" :disabled="!content" @click="doPreview">解析预览</el-button>
      <el-button type="success" :disabled="!checkedCandidates.length" :loading="applying" @click="doApply">
        导入选中 ({{ checkedCandidates.length }})
      </el-button>
    </div>

    <template v-if="candidates.length">
      <el-table :data="candidates" height="380" border @selection-change="onSelectionChange">
        <el-table-column type="selection" width="45" />
        <el-table-column prop="connName" label="连接名称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="dbType" label="类型" width="110" />
        <el-table-column prop="host" label="主机" min-width="130" />
        <el-table-column prop="port" label="端口" width="70" />
        <el-table-column prop="databaseName" label="数据库" min-width="110" show-overflow-tooltip />
        <el-table-column prop="username" label="用户" min-width="90" show-overflow-tooltip />
        <el-table-column label="密码" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.passwordPresent" type="warning">已检测(不导入)</el-tag>
            <el-tag v-else type="info">未提供</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提示" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag v-for="issue in row.issues" :key="issue" type="danger" size="small" class="issue-tag">{{ issue }}</el-tag>
            <span v-if="!row.issues?.length">—</span>
          </template>
        </el-table-column>
      </el-table>
      <el-alert
        v-if="skipped.length"
        :title="`跳过 ${skipped.length} 项: ` + skipped.map((s) => s.connName + '(' + s.reason + ')').join('; ')"
        type="error"
        :closable="false"
        class="import-skipped"
      />
    </template>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { connImportPreview, connImportApply } from '@/api/dbtool/index'

const visible = ref(true)
const emit = defineEmits(['close', 'imported'])
const content = ref('')
const fileName = ref('')
const candidates = ref([])
const checkedCandidates = ref([])
const skipped = ref([])
const loading = ref(false)
const applying = ref(false)

function onFileChange(file) {
  fileName.value = file.name || ''
  const reader = new FileReader()
  reader.onload = () => {
    content.value = String(reader.result || '')
  }
  reader.readAsText(file.raw)
}

async function doPreview() {
  if (!content.value) return
  loading.value = true
  candidates.value = []
  checkedCandidates.value = []
  skipped.value = []
  try {
    const response = await connImportPreview({ content: content.value, fileName: fileName.value })
    candidates.value = response.data?.candidates || []
    if (!candidates.value.length) ElMessage.warning('未解析到任何连接配置')
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '解析失败')
  } finally {
    loading.value = false
  }
}

function onSelectionChange(selection) {
  checkedCandidates.value = selection
}

async function doApply() {
  if (!checkedCandidates.value.length) return
  applying.value = true
  skipped.value = []
  try {
    const response = await connImportApply({ candidates: checkedCandidates.value })
    const data = response.data || {}
    const imported = data.imported || []
    skipped.value = data.skipped || []
    if (imported.length) {
      ElMessage.success(`成功导入 ${imported.length} 个连接`)
      emit('imported')
    }
    if (skipped.value.length) {
      ElMessage.warning(`${skipped.value.length} 项未导入, 详见下方跳过列表`)
    }
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '导入失败')
  } finally {
    applying.value = false
  }
}
</script>

<style scoped>
.import-tip {
  margin-bottom: 12px;
}

.import-file {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.import-file-name {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.issue-tag {
  margin-right: 6px;
  margin-bottom: 4px;
}

.import-skipped {
  margin-top: 12px;
}
</style>
