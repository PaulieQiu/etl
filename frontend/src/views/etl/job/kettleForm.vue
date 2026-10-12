<template>
  <div class="app-container etl-form-page">
    <el-card shadow="never" class="form-panel">
      <template #header>
        <div class="form-heading">
          <el-button icon="Back" circle size="small" @click="goBack" />
          <div>

            <h3>{{ isEdit ? '编辑' : '新增' }} Kettle 任务</h3>
          </div>
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" class="kettle-form">
        <!-- 基本信息 -->
        <el-row :gutter="16" class="base-info">
          <el-col :span="8">
            <el-form-item label="任务名称" prop="jobName">
              <el-input v-model="form.jobName" placeholder="如: 用户表每日同步" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="任务类型">
              <el-select v-model="form.kettleType" style="width: 100%" @change="onKettleTypeChange">
                <el-option label="转换 (Transformation)" value="TRANS" />
                <el-option label="作业 (Job)" value="JOB" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="所属总任务">
              <el-select v-model="form.parentId" clearable filterable placeholder="可选: 挂载到总任务下" style="width: 100%">
                <el-option v-for="p in parentJobs" :key="p.id" :label="p.jobName" :value="p.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="状态">
              <el-switch v-model="form.status" active-value="0" inactive-value="1" active-text="正常" inactive-text="停用" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <!-- TRANS: 子任务设计(与总任务子任务界面一致: 多源 UNION/JOIN、Excel/API、清洗/质量/目标、过程图日志) -->
      <template v-if="form.kettleType === 'TRANS'">
        <SubTaskDesigner ref="designerRef" :model-value="form" :conn-list="connList" @save="submitForm" @cancel="goBack" />
      </template>

      <!-- JOB: 原作业可视化设计器 -->
      <template v-else>
        <div class="job-panel">
          <el-divider content-position="left">作业可视化设计（SQL 执行作业项）</el-divider>
          <KettleDesignerPanel :kettle-type="form.kettleType" :conn-list="connList" :form="form" :job-name="form.jobName" @apply="onApplyXml" />
          <el-divider content-position="left">XML 脚本（高级）</el-divider>
          <div class="xml-script-bar">
            <el-button size="small" type="info" plain @click="showXmlScript = !showXmlScript">{{ showXmlScript ? '收起' : '展开' }} XML 脚本</el-button>
            <span class="kettle-xml-tip">留空时, 保存/执行将按上方配置自动生成</span>
          </div>
          <el-input v-if="showXmlScript" v-model="form.kettleXml" type="textarea" :rows="8" placeholder="Kettle 作业 XML(.kjb) 内容, 留空则自动生成" />
        </div>
      </template>

      <div v-if="form.kettleType === 'JOB'" class="form-footer">
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
        <el-button @click="goBack">取消</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup name="EtlKettleForm">
import { ref, computed, onMounted, getCurrentInstance } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getEtlJob, addEtlJob, updateEtlJob, listAllConn, listEtlJob } from '@/api/etl/index'
import SubTaskDesigner from './SubTaskDesigner.vue'
import KettleDesignerPanel from './KettleDesignerPanel.vue'

const { proxy } = getCurrentInstance()
const route = useRoute()
const router = useRouter()

const isEdit = computed(() => !!route.query.id)
const saving = ref(false)
const formRef = ref()
const connList = ref([])
const designerRef = ref()
const showXmlScript = ref(false)
const parentJobs = ref([])

function loadParents() {
  listEtlJob({ pageNum: 1, pageSize: 200 }).then(res => {
    parentJobs.value = (res.rows || []).map(j => ({ id: j.id, jobName: j.jobName }))
  })
}

function newTask() {
  return {
    id: undefined,
    parentId: undefined,
    _activeMini: 0,
    jobName: undefined,
    jobType: 'KETTLE',
    kettleType: 'TRANS',
    kettleXml: undefined,
    status: '0',
    srcType: 'TABLE',
    srcConnId: undefined,
    srcSchema: undefined,
    srcTable: undefined,
    srcWhere: undefined,
    srcExcelPath: '',
    srcExcelSheet: '',
    srcExcelHeader: 0,
    srcExcelDataRow: undefined,
    srcExcelColFrom: undefined,
    srcExcelColTo: undefined,
    srcApiUrl: '',
    srcApiMethod: 'GET',
    srcApiHeaders: '',
    srcApiBody: '',
    srcApiDataPath: '',
    srcApiPageField: '',
    srcApiPageSize: 0,
    _srcAuth: { type: 'NONE', tokenUrl: '', tokenMethod: 'POST', tokenHeaders: '', tokenBody: '', tokenPath: 'token' },
    srcRows: [{ srcTable: '', srcWhere: '', joinType: 'UNION', joinField: '', joinRef: '', srcConnId: undefined, srcSchema: undefined, _srcRowDbs: [], maps: [{ srcField: '', dstField: '' }], cleanRules: [] }],
    _srcDbs: [],
    _srcTables: [],
    _srcCols: [],
    cleanConnId: undefined,
    cleanSchema: undefined,
    cleanTable: undefined,
    cleanWriteMode: 'INSERT',
    _cleanDbs: [],
    _cleanTables: [],
    _cleanCols: [],
    qualityRules: [],
    dstConnId: undefined,
    dstType: 'TABLE',
    dstSchema: undefined,
    dstTable: undefined,
    dstExcelPath: '',
    dstExcelSheet: '',
    dstApiUrl: '',
    dstApiMethod: 'POST',
    dstApiHeaders: '',
    dstApiBodyTpl: '',
    _dstAuth: { type: 'NONE', tokenUrl: '', tokenMethod: 'POST', tokenHeaders: '', tokenBody: '', tokenPath: 'token' },
    _dstDbs: [],
    _dstTables: [],
    _dstCols: [],
    dstMaps: [{ srcField: '', dstField: '' }],
    dstWriteMode: 'INSERT',
    speedChannel: 3,
    batchSize: 1024,
    incrField: '',
    incrMode: 'ID',
    incrLastValue: '',
    retryCount: 0,
    retryInterval: 30,
    errorLimitPercent: 0,
    errorLimitCount: 0,
    depIds: []
  }
}

const form = ref(newTask())

const rules = computed(() => {
  const r = {
    jobName: [{ required: true, message: '任务名称不能为空', trigger: 'blur' }]
  }
  return r
})

function goBack() { router.push('/etl/job') }

function loadConns() {
  listAllConn().then(res => { connList.value = res.data || res.rows || [] })
}

function onKettleTypeChange(type) {
  form.value.kettleXml = undefined
  proxy.$modal.msgSuccess(type === 'JOB' ? '已切换为作业类型, 在下方可视化设计器中添加 SQL 作业项' : '已切换为转换类型, 使用子任务设计器配置 源/表/清洗/质量/目标')
}

function onApplyXml(xml) {
  form.value.kettleXml = xml
}

function validSrcRows() {
  return (form.value.srcRows || []).filter(r => r.srcTable)
}
function cleanRuleCount() {
  return validSrcRows().reduce((n, r) => n + (r.cleanRules || []).filter(x => x.ruleName || x.ruleType).length, 0)
}

function loadDetail() {
  const id = route.query.id
  if (!id) return
  getEtlJob(id).then(res => {
    const d = res.data
    form.value.jobName = d.jobName
    form.value.status = d.status || '0'
    form.value.kettleType = d.kettleType || 'TRANS'
    form.value.kettleXml = d.kettleXml || undefined
    form.value.id = d.id
    form.value.parentId = d.parentId || undefined
    if (form.value.kettleType === 'JOB') {
      // JOB 回显: 旧字段
      form.value.srcConnId = d.srcConnId
      form.value.srcSchema = d.srcSchema
      form.value.srcTable = d.srcTable
      form.value.srcWhere = d.srcWhere
      form.value.dstConnId = d.dstConnId
      form.value.dstSchema = d.dstSchema
      form.value.dstTable = d.dstTable
      form.value.dstWriteMode = d.dstWriteMode || 'INSERT'
      form.value.batchSize = d.batchSize || 1024
    } else {
      // TRANS 回显: 子任务设计器
      designerRef.value.loadFrom(d)
    }
  })
}

function submitForm() {
  const sub = form.value
  if (form.value.kettleType === 'JOB') {
    // JOB: 原校验逻辑
    if (!sub.jobName) { proxy.$modal.msgWarning('请填写任务名称'); return }
    if (!sub.kettleXml) { proxy.$modal.msgWarning('作业 XML 未生成, 请在可视化设计器中「生成 XML」并「应用到表单」'); return }
    formRef.value.validate(valid => {
      if (!valid) return
      saving.value = true
      const payload = { id: sub.id, jobName: sub.jobName, jobType: 'KETTLE', kettleType: 'JOB', kettleXml: sub.kettleXml, status: sub.status }
      const done = () => {
        saving.value = false
        proxy.$modal.msgSuccess(isEdit.value ? '修改成功' : '新增成功')
        goBack()
      }
      const fail = (e) => {
        saving.value = false
        proxy.$modal.msgError('保存失败: ' + (e?.msg || e?.message || '未知错误'))
      }
      if (sub.id) updateEtlJob(payload).then(done).catch(fail)
      else addEtlJob(payload).then(done).catch(fail)
    })
    return
  }
  // TRANS: 与总任务子任务一致的校验
  if (!sub.jobName) { proxy.$modal.msgWarning('请填写任务名称'); return }
  const srcType = sub.srcType || 'TABLE'
  const dstType = sub.dstType || 'TABLE'
  if (srcType === 'TABLE') {
    if (!sub.srcConnId) { proxy.$modal.msgWarning('未选择源数据源'); return }
    if (!validSrcRows().length) { proxy.$modal.msgWarning('未配置源表'); return }
  } else if (srcType === 'EXCEL') {
    if (!sub.srcExcelPath) { proxy.$modal.msgWarning('未配置源 Excel 文件路径'); return }
  } else if (srcType === 'API') {
    if (!sub.srcApiUrl) { proxy.$modal.msgWarning('未配置源 API 地址'); return }
  }
  if (dstType === 'TABLE') {
    if (!sub.dstConnId || !sub.dstTable) { proxy.$modal.msgWarning('未配置目标数据源或目标表'); return }
  } else if (dstType === 'EXCEL') {
    if (!sub.dstExcelPath) { proxy.$modal.msgWarning('未配置目标 Excel 文件路径'); return }
  } else if (dstType === 'API') {
    if (!sub.dstApiUrl) { proxy.$modal.msgWarning('未配置目标 API 地址'); return }
    if (sub._dstAuth?.type === 'TOKEN' && !sub._dstAuth.tokenUrl) { proxy.$modal.msgWarning('TOKEN 鉴权未配置 Token 地址'); return }
  }
  if (srcType === 'API' && sub._srcAuth?.type === 'TOKEN' && !sub._srcAuth.tokenUrl) {
    proxy.$modal.msgWarning('TOKEN 鉴权未配置 Token 地址'); return
  }
  const dstNonTable = dstType !== 'TABLE'
  const hasPipelineRules = cleanRuleCount() > 0 || (sub.qualityRules || []).length > 0
  if (dstNonTable && hasPipelineRules && !(sub.cleanConnId && sub.cleanSchema && sub.cleanTable)) {
    proxy.$modal.msgWarning('目标为 Excel/API 的多段管线需配置清洗结果表作为中间承接'); return
  }
  const rows = validSrcRows()
  const multiRel = rows.length > 1 && rows.some(r => r.joinType === 'UNION' || r.joinType === 'LEFT_JOIN' || r.joinType === 'JOIN')
  const hasCleanRule = cleanRuleCount() > 0
  const hasCleanTable = !!(sub.cleanConnId && sub.cleanSchema && sub.cleanTable)
  if (multiRel && !(hasCleanRule && hasCleanTable)) {
    proxy.$modal.msgWarning('多源合并/关联场景, 清洗规则与清洗结果表必须同时配置'); return
  }
  formRef.value.validate(valid => {
    if (!valid) return
    saving.value = true
    const payload = designerRef.value.serialize(sub.parentId)
    const done = () => {
      saving.value = false
      proxy.$modal.msgSuccess(isEdit.value ? '修改成功' : '新增成功')
      goBack()
    }
    const fail = (e) => {
      saving.value = false
      proxy.$modal.msgError('保存失败: ' + (e?.msg || e?.message || '未知错误'))
    }
    if (sub.id) {
      updateEtlJob(payload).then(done).catch(fail)
    } else {
      addEtlJob(payload).then(done).catch(fail)
    }
  })
}

onMounted(() => {
  if (route.query.jobName) form.value.jobName = decodeURIComponent(String(route.query.jobName))
  loadConns()
  loadParents()
  loadDetail()
})
</script>

<style lang="scss" scoped>
.etl-form-page {
  .form-heading {
    display: flex;
    align-items: center;
    gap: 12px;
    h3 { margin: 0; font-size: 16px; }
  }
  .base-info { max-width: 1100px; margin: 0 auto 12px; }
  .job-panel { max-width: 1150px; margin: 0 auto; }
  .xml-script-bar {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 4px 0 8px;
    flex-wrap: wrap;
    .kettle-xml-tip { font-size: 12px; color: var(--el-text-color-secondary); }
  }
  .form-footer {
    display: flex;
    justify-content: flex-end;
    gap: 10px;
    margin-top: 16px;
    padding-top: 16px;
    border-top: 1px solid var(--el-border-color-lighter);
  }
}
</style>
