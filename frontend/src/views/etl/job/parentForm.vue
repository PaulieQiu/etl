<template>
  <div class="p-2 app-container etl-parent-form">
    <el-card shadow="never">
      <template #header>
        <div class="panel-heading">
          <el-button icon="Back" circle size="small" class="back-btn" @click="goBack" />
          <div>

            <h3>{{ isEdit ? '编辑总任务' : '新增总任务' }}</h3>
          </div>
        </div>
      </template>

      <!-- 步骤条 -->
      <el-steps :active="activeStep" align-center finish-status="success" class="form-steps" @click.stop>
        <el-step title="任务信息" description="总任务基本信息" @click="goStep(0)" />
        <el-step title="子任务设计" description="源/表/映射→清洗→质量→完成" @click="goStep(1)" />
        <el-step title="调度配置" description="cron 表达式" @click="goStep(2)" />
        <el-step title="完成" description="确认并保存" @click="goStep(3)" />
      </el-steps>

      <!-- ① 任务信息 -->
      <div v-show="activeStep === 0" class="step-panel">
        <el-form ref="baseRef" :model="form" :rules="baseRules" label-width="110px" style="max-width: 640px">
          <el-form-item label="任务名称" prop="jobName">
            <el-input v-model="form.jobName" placeholder="请输入总任务名称, 如: 每日主数据同步" />
          </el-form-item>
          <el-form-item label="任务描述" prop="remark">
            <el-input v-model="form.remark" type="textarea" :rows="3" placeholder="任务用途说明(选填)" />
          </el-form-item>
          <el-form-item label="所属目录">
            <el-select v-model="form.parentId" clearable placeholder="根目录(不选=顶层)" style="width: 100%">
              <el-option label="根目录" :value="'0'" />
              <el-option v-for="d in dirOptions" :key="d.id" :label="d.jobName" :value="d.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="并发方式">
            <el-radio-group v-model="form.runMode">
              <el-radio value="SEQUENTIAL">顺序执行子任务</el-radio>
              <el-radio value="PARALLEL">并行执行子任务</el-radio>
            </el-radio-group>
            <div class="form-tip">并行执行: 无前置依赖的子任务同时执行; 配置了前置依赖(depIds)的子任务按依赖自动分层, 依赖层先完成后才执行依赖它的子任务。</div>
          </el-form-item>
        </el-form>
        <div class="step-actions">
          <el-button type="primary" @click="nextStep(0)">下一步：子任务设计</el-button>
        </div>
      </div>

      <!-- ② 子任务设计 -->
      <div v-show="activeStep === 1" class="step-panel">
        <div class="subtask-toolbar">
          <el-button type="primary" plain icon="Plus" @click="addSubtask">新增子任务</el-button>
          <span class="form-tip">点击上方节点切换配置步骤, 仅当前步骤可编辑, 前置配置只读展示; 子任务卡片可折叠</span>
        </div>

        <div v-for="(sub, idx) in form.children" :key="sub._key" class="subtask-card">
          <div class="subtask-head" @click="sub._collapsed = !sub._collapsed">
            <div class="subtask-title">
              <el-icon class="collapse-icon">
                <ArrowDown v-if="!sub._collapsed" />
                <ArrowRight v-else />
              </el-icon>
              子任务 {{ idx + 1 }}
              <el-tag size="small" :type="sub.jobType === 'KETTLE' ? 'success' : 'primary'" style="margin-left: 8px">{{ sub.jobType }}</el-tag>
              <el-tag v-if="sub.jobName" size="small" type="info" style="margin-left: 6px">{{ sub.jobName }}</el-tag>
              <el-select
                v-if="form.children.length > 1"
                v-model="sub.depIds"
                multiple
                size="small"
                placeholder="前置依赖"
                clearable
                style="margin-left: 8px; width: 190px"
                @click.stop
              >
                <el-option
                  v-for="o in otherSubtasks(idx)"
                  :key="o.id"
                  :label="o.jobName || ('子任务 ' + (o._idx + 1))"
                  :value="String(o.id)"
                />
              </el-select>
            </div>
            <div class="subtask-actions" @click.stop>
              <el-button size="small" type="primary" plain icon="CopyDocument" @click="copySubtask(idx)">复制</el-button>
              <el-button size="small" type="danger" plain icon="Delete" @click="removeSubtask(idx)">删除</el-button>
            </div>
          </div>

          <!-- 子任务内可点击步骤条 -->
          <div class="mini-steps">
            <div
              v-for="(st, si) in miniSteps"
              :key="si"
              class="mini-step"
              :class="{ active: sub._activeMini === si, done: sub._activeMini > si }"
              @click="sub._activeMini = si"
            >
              <span class="mini-step-idx">{{ si + 1 }}</span>
              <span class="mini-step-title">{{ st }}</span>
              <span v-if="[1, 2].includes(si)" class="mini-step-opt">可选</span>
            </div>
          </div>

          <div v-show="!sub._collapsed">
          <el-form :model="sub" label-width="110px" class="subtask-form">
            <!-- 通用: 子任务名称/引擎 -->
            <el-row :gutter="16">
              <el-col :span="8">
                <el-form-item label="子任务名称">
                  <el-input v-model="sub.jobName" placeholder="如: 用户主数据同步" />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="引擎类型">
                  <el-select v-model="sub.jobType" style="width: 100%">
                    <el-option label="Kettle" value="KETTLE" />
                    <el-option label="DataX" value="DATAX" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>

            <!-- ============ 面板0: 数据源/源表/映射(合并) ============ -->
            <div v-show="sub._activeMini === 0">
              <el-row :gutter="16">
                <el-col :span="8">
                  <el-form-item label="源类型">
                    <el-select v-model="sub.srcType" style="width: 100%">
                      <el-option label="数据库表" value="TABLE" />
                      <el-option label="Excel 文件" value="EXCEL" />
                      <el-option label="API 接口" value="API" />
                    </el-select>
                  </el-form-item>
                </el-col>
                <el-col :span="16">
                  <div class="form-tip" style="line-height: 32px">表源支持多表 UNION ALL 合并 / JOIN 关联；Excel/API 源为单源（后端解析为 CSV 后由 Kettle 读入）；Excel/API 源或目标均可参与清洗/质量多段管线（目标非表时需配置清洗结果表承接）。</div>
                </el-col>
              </el-row>

              <template v-if="sub.srcType === 'TABLE'">
              <el-divider content-position="left">数据源（源）</el-divider>
              <el-row :gutter="16">
                <el-col :span="8">
                  <el-form-item label="源数据源">
                    <el-select v-model="sub.srcConnId" filterable placeholder="选择源数据源" style="width: 100%" @change="onConnChange(sub, 'src')">
                      <el-option v-for="c in connList" :key="c.id" :label="c.connName" :value="c.id" />
                    </el-select>
                  </el-form-item>
                </el-col>
                <el-col :span="8">
                  <el-form-item label="源库">
                    <el-select v-model="sub.srcSchema" filterable placeholder="选择库" style="width: 100%" @change="onSchemaChange(sub, 'src')">
                      <el-option v-for="s in sub._srcDbs" :key="s" :label="s" :value="s" />
                    </el-select>
                  </el-form-item>
                </el-col>
              </el-row>

              <el-divider content-position="left">源表（可多个: UNION ALL 合并 / JOIN 关联组合）</el-divider>
              <div v-for="(row, ri) in sub.srcRows" :key="ri" class="src-row">
                <el-row :gutter="8" class="src-conn-row">
                  <el-col :span="7">
                    <el-select v-model="row.srcConnId" size="small" filterable clearable placeholder="源数据源(默认任务级)" style="width: 100%" @change="onRowConnChange(sub, row)">
                      <el-option v-for="c in connList" :key="c.id" :label="c.connName" :value="c.id" />
                    </el-select>
                  </el-col>
                  <el-col :span="7">
                    <el-select v-model="row.srcSchema" size="small" filterable clearable placeholder="源库(默认任务级)" style="width: 100%" @change="onRowSchemaChange(sub, row)">
                      <el-option v-for="s2 in row._srcRowDbs || []" :key="s2" :label="s2" :value="s2" />
                    </el-select>
                  </el-col>
                  <el-col :span="10"><span class="conn-tip">不选则使用任务级「源数据源 / 源库」；跨数据源合并/关联时逐行指定</span></el-col>
                </el-row>
                <el-row :gutter="8">
                  <el-col :span="7">
                    <el-select v-model="row.srcTable" filterable placeholder="源表" style="width: 100%" @change="onSrcTableChange(sub, row)">
                      <el-option v-for="t in sub._srcTables" :key="t.tableName" :label="t.tableName" :value="t.tableName" />
                    </el-select>
                  </el-col>
                  <el-col :span="12">
                    <el-input v-model="row.srcWhere" placeholder="过滤条件(where, 可选), 如 status = '1'" />
                  </el-col>
                  <el-col :span="5" style="text-align: right">
                    <el-button size="small" type="primary" link icon="Plus" @click="addSrcRow(sub)">新增源表</el-button>
                    <el-button v-if="sub.srcRows.length > 1" size="small" type="danger" link icon="Delete" @click="removeSrcRow(sub, ri)">删除</el-button>
                  </el-col>
                </el-row>
                <!-- 关联方式: 首行主表 / 其余行 UNION/JOIN -->
                <el-row v-if="ri > 0" :gutter="8" class="join-row">
                  <el-col :span="7">
                    <el-select v-model="row.joinType" size="small" style="width: 100%">
                      <el-option label="UNION ALL 合并（字段相同场景）" value="UNION" />
                      <el-option label="INNER JOIN 关联组合" value="JOIN" />
                      <el-option label="LEFT JOIN 关联组合" value="LEFT_JOIN" />
                    </el-select>
                  </el-col>
                  <el-col :span="7">
                    <el-input v-model="row.joinField" size="small" placeholder="本表关联字段, 如 dept_id" />
                  </el-col>
                  <el-col :span="8">
                    <el-input v-model="row.joinRef" size="small" placeholder="主表关联字段, 如 id" />
                  </el-col>
                  <el-col :span="2" class="join-ref-label">ON 主表=本表</el-col>
                </el-row>
                <el-row v-else :gutter="8" class="join-row">
                  <el-col :span="24">
                    <span class="join-main-tip">✓ 主表（第一张源表；其余源表可选 UNION ALL 合并或 JOIN 关联组合）</span>
                  </el-col>
                </el-row>
              </div>
              </template>

              <!-- 源: Excel 文件 -->
              <template v-else-if="sub.srcType === 'EXCEL'">
                <el-divider content-position="left">源 Excel 文件（统一目录管理）</el-divider>
                <el-row :gutter="16">
                  <el-col :span="12">
                    <el-form-item label="已上传文件">
                      <el-select v-model="sub.srcExcelPath" filterable clearable placeholder="从统一目录选择(可输入相对路径)" style="width: 100%" allow-create @change="onSrcExcelPathChange(sub)">
                        <el-option v-for="f in fileList" :key="f" :label="f" :value="f" />
                      </el-select>
                    </el-form-item>
                  </el-col>
                  <el-col :span="6">
                    <el-form-item label="工作表">
                      <el-select v-model="sub.srcExcelSheet" filterable clearable placeholder="选择工作表" style="width: 100%" @change="onSrcSheetChange(sub)">
                        <el-option v-for="sh in sub._srcSheets" :key="sh.name" :label="sh.name + '（' + sh.rows + '行×' + sh.cols + '列）'" :value="sh.name" />
                      </el-select>
                    </el-form-item>
                  </el-col>
                  <el-col :span="6">
                    <el-form-item label="表头行">
                      <el-input-number v-model="sub.srcExcelHeader" :min="0" :max="100" style="width: 100%" @change="validateExcelConfig(sub, 'src')" />
                    </el-form-item>
                  </el-col>
                </el-row>
                <el-row :gutter="16">
                  <el-col :span="8">
                    <el-form-item label="数据起始行">
                      <el-input-number v-model="sub.srcExcelDataRow" :min="0" :max="1000000" :controls="true" style="width: 100%" placeholder="0=首行" @change="validateExcelConfig(sub, 'src')" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="8">
                    <el-form-item label="起始列">
                      <el-input-number v-model="sub.srcExcelColFrom" :min="1" :max="1000" style="width: 100%" placeholder="如 C=3" @change="validateExcelConfig(sub, 'src')" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="8">
                    <el-form-item label="结束列">
                      <el-input-number v-model="sub.srcExcelColTo" :min="1" :max="1000" style="width: 100%" placeholder="如 AL=38" @change="validateExcelConfig(sub, 'src')" />
                    </el-form-item>
                  </el-col>
                </el-row>
                <el-row :gutter="16">
                  <el-col :span="12">
                    <el-form-item label="文件路径(高级)">
                      <el-input v-model="sub.srcExcelPath" placeholder="绝对路径或统一目录下相对路径" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="12">
                    <el-form-item label="上传文件">
                      <el-upload :http-request="o => uploadEtlFile(o, sub, 'src')" :show-file-list="false" accept=".xlsx,.xls,.csv">
                        <el-button size="small" type="primary" plain icon="Upload">上传到统一目录</el-button>
                      </el-upload>
                    </el-form-item>
                  </el-col>
                </el-row>
                <div class="form-tip">相对路径基于服务端统一目录（默认 D:/project/etl-engine/files，可用系统属性 etl.file.dir 覆盖）；首行为列名（表头行, 0=首行）；每行一条记录，执行时自动解析为 CSV 供 Kettle 读入。</div>
              </template>

              <!-- 源: API 接口 -->
              <template v-else-if="sub.srcType === 'API'">
                <el-divider content-position="left">源 API 接口</el-divider>
                <el-row :gutter="16">
                  <el-col :span="16">
                    <el-form-item label="接口地址">
                      <el-input v-model="sub.srcApiUrl" placeholder="https://host/api/list" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="8">
                    <el-form-item label="请求方法">
                      <el-select v-model="sub.srcApiMethod" style="width: 100%">
                        <el-option label="GET" value="GET" />
                        <el-option label="POST" value="POST" />
                      </el-select>
                    </el-form-item>
                  </el-col>
                </el-row>
                <el-row :gutter="16">
                  <el-col :span="12">
                    <el-form-item label="请求头 JSON">
                      <el-input v-model="sub.srcApiHeaders" placeholder='{"Authorization":"Bearer xxxx"}' />
                    </el-form-item>
                  </el-col>
                  <el-col :span="12">
                    <el-form-item label="请求体(仅POST)">
                      <el-input v-model="sub.srcApiBody" placeholder='{"keyword":"诗歌"}' />
                    </el-form-item>
                  </el-col>
                </el-row>
                <el-row :gutter="16">
                  <el-col :span="8">
                    <el-form-item label="数据路径">
                      <el-input v-model="sub.srcApiDataPath" placeholder="JSON数组路径, 如 data.list, 空=根数组" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="8">
                    <el-form-item label="分页参数名">
                      <el-input v-model="sub.srcApiPageField" placeholder="如 page, 空=不分页" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="8">
                    <el-form-item label="每页条数">
                      <el-input-number v-model="sub.srcApiPageSize" :min="0" :max="100000" style="width: 100%" />
                    </el-form-item>
                  </el-col>
                </el-row>
                <el-divider content-position="left">API 鉴权</el-divider>
                <el-row :gutter="16">
                  <el-col :span="8">
                    <el-form-item label="鉴权方式">
                      <el-select v-model="sub._srcAuth.type" style="width: 100%">
                        <el-option label="无鉴权 (使用请求头 JSON)" value="NONE" />
                        <el-option label="Token 动态获取" value="TOKEN" />
                      </el-select>
                    </el-form-item>
                  </el-col>
                  <el-col :span="16">
                    <div class="form-tip" style="line-height: 32px">TOKEN: 先从 tokenUrl 获取令牌（5 分钟缓存），自动注入请求头与 URL 的 ${token} 占位。</div>
                  </el-col>
                </el-row>
                <template v-if="sub._srcAuth.type === 'TOKEN'">
                  <el-row :gutter="16">
                    <el-col :span="14">
                      <el-form-item label="Token 地址">
                        <el-input v-model="sub._srcAuth.tokenUrl" placeholder="https://host/api/token" />
                      </el-form-item>
                    </el-col>
                    <el-col :span="5">
                      <el-form-item label="方法">
                        <el-select v-model="sub._srcAuth.tokenMethod" style="width: 100%">
                          <el-option label="POST" value="POST" />
                          <el-option label="GET" value="GET" />
                        </el-select>
                      </el-form-item>
                    </el-col>
                    <el-col :span="5">
                      <el-form-item label="Token 路径">
                        <el-input v-model="sub._srcAuth.tokenPath" placeholder="如 data.token" />
                      </el-form-item>
                    </el-col>
                  </el-row>
                  <el-row :gutter="16">
                    <el-col :span="12">
                      <el-form-item label="Token 请求头 JSON">
                        <el-input v-model="sub._srcAuth.tokenHeaders" placeholder='{"Content-Type":"application/json"}' />
                      </el-form-item>
                    </el-col>
                    <el-col :span="12">
                      <el-form-item label="Token 请求体">
                        <el-input v-model="sub._srcAuth.tokenBody" placeholder='{"appId":"xx","secret":"yy"}' />
                      </el-form-item>
                    </el-col>
                  </el-row>
                </template>
                <div class="form-tip">返回需为 JSON 数组（或 dataPath 指向的数组）；分页按 页码递增 循环拉取直到不足一页。字段名取自首条记录。</div>
              </template>

            </div>

            <!-- ============ 面板1: 清洗规则(含清洗结果表/映射, 三部分折叠) ============ -->
            <div v-show="sub._activeMini === 1">
              <div class="ro-summary">
                <span class="ro-label">源</span>
                <el-tag size="small" type="info">{{ connName(sub.srcConnId) }}.{{ sub.srcSchema }}.{{ validSrcRows(sub).map(r => r.srcTable).join(' / ') }}</el-tag>
                <span class="ro-label">清洗映射</span>
                <el-tag size="small" type="info">{{ mapCount(sub) }} 条</el-tag>
                <span class="ro-label">清洗规则</span>
                <el-tag size="small" type="info">{{ cleanRuleCount(sub) }} 条</el-tag>
                <span class="ro-label">清洗结果</span>
                <el-tag size="small" type="info">{{ sub.cleanTable || '未配置' }}</el-tag>
              </div>
              <el-collapse v-model="sub._cleanCollapse" class="clean-collapse">
                <el-collapse-item title="清洗规则明细（按源表绑定, 可多个）" name="rules">
              <el-divider content-position="left">数据清洗规则（按源表绑定, 可多个）</el-divider>
              <div v-if="(sub.srcType || 'TABLE') !== 'TABLE'" class="form-tip" style="margin-bottom: 8px">Excel/API 源为单源：清洗规则为任务级规则，作用于解析后的全部记录。</div>
              <div v-for="(row, ri) in sub.srcRows" :key="'c' + ri" class="map-group">
                <div class="map-group-title">
                  {{ (sub.srcType || 'TABLE') !== 'TABLE' ? 'Excel/API 源（任务级规则）' : (row.srcTable || ('源表' + (ri + 1) + '(未选择)')) }}
                  <el-button size="small" type="primary" link icon="Plus" @click="row.cleanRules.push({ ruleName: '', ruleType: '', ruleConfig: '', _cfg: {} })">新增规则</el-button>
                </div>
                <div v-for="(r, cri) in row.cleanRules" :key="cri" class="rule-row">
                  <el-row :gutter="8">
                    <el-col :span="4">
                      <el-input v-model="r.ruleName" size="small" placeholder="规则名称" />
                    </el-col>
                    <el-col :span="5">
                      <el-select v-model="r.ruleType" size="small" placeholder="规则类型" style="width: 100%" @change="onRuleTypeChange(r)">
                        <el-option v-for="t in cleanRuleTypes" :key="t" :label="t" :value="t" />
                      </el-select>
                    </el-col>
                    <el-col :span="13">
                      <div v-if="r.ruleType === '默认值填充'" class="rule-cfg-inline">
                        <el-select v-model="r._cfg.field" size="small" filterable allow-create placeholder="字段" class="cfg-w1" @change="syncRuleConfig(r)">
                          <el-option v-for="c in (sub.cleanTable ? sub._cleanCols : sub._srcCols)" :key="c" :label="c" :value="c" />
                        </el-select>
                        <el-input v-model="r._cfg.defaultValue" size="small" placeholder="默认值" class="cfg-w1" @input="syncRuleConfig(r)" />
                        <span class="cfg-tip">条件:</span>
                        <el-select v-model="r._cfg.whenField" size="small" filterable allow-create clearable placeholder="条件字段(可选)" class="cfg-w1" @change="syncRuleConfig(r)">
                          <el-option v-for="c in (sub.cleanTable ? sub._cleanCols : sub._srcCols)" :key="c" :label="c" :value="c" />
                        </el-select>
                        <el-input v-model="r._cfg.whenValue" size="small" placeholder="条件值" class="cfg-w1" @input="syncRuleConfig(r)" />
                      </div>
                      <div v-else-if="r.ruleType === '叶子标记'" class="rule-cfg-inline">
                        <el-input v-model="r._cfg.field" size="small" placeholder="标记字段(F_MX)" class="cfg-w1" @input="syncRuleConfig(r)" />
                        <el-input v-model="r._cfg.codeField" size="small" placeholder="编码字段(F_CODE)" class="cfg-w1" @input="syncRuleConfig(r)" />
                        <el-input v-model="r._cfg.parentField" size="small" placeholder="上级字段(F_NSXXSJJD)" class="cfg-w1" @input="syncRuleConfig(r)" />
                        <el-input v-model="r._cfg.leafValue" size="small" placeholder="叶子值1" class="cfg-w1" @input="syncRuleConfig(r)" />
                        <el-input v-model="r._cfg.nonLeafValue" size="small" placeholder="非叶值0" class="cfg-w1" @input="syncRuleConfig(r)" />
                      </div>
                      <div v-else-if="r.ruleType === '当前时间填充' || r.ruleType === 'UUID填充'" class="rule-cfg-inline">
                        <el-select v-model="r._cfg.field" size="small" filterable allow-create placeholder="字段" class="cfg-w2" @change="syncRuleConfig(r)">
                          <el-option v-for="c in (sub.cleanTable ? sub._cleanCols : sub._srcCols)" :key="c" :label="c" :value="c" />
                        </el-select>
                      </div>
                      <div v-else-if="r.ruleType === '格式规范化'" class="rule-cfg-inline">
                        <el-select v-model="r._cfg.field" size="small" filterable allow-create placeholder="字段" class="cfg-w1" @change="syncRuleConfig(r)">
                          <el-option v-for="c in (sub.cleanTable ? sub._cleanCols : sub._srcCols)" :key="c" :label="c" :value="c" />
                        </el-select>
                        <el-select v-model="r._cfg.action" size="small" placeholder="操作" class="cfg-w1" @change="syncRuleConfig(r)">
                          <el-option label="去空格 trim" value="trim" />
                          <el-option label="转大写 upper" value="upper" />
                          <el-option label="转小写 lower" value="lower" />
                        </el-select>
                      </div>
                      <div v-else-if="r.ruleType === '单位换算'" class="rule-cfg-inline">
                        <el-select v-model="r._cfg.field" size="small" filterable allow-create placeholder="字段" class="cfg-w1" @change="syncRuleConfig(r)">
                          <el-option v-for="c in (sub.cleanTable ? sub._cleanCols : sub._srcCols)" :key="c" :label="c" :value="c" />
                        </el-select>
                        <el-input-number v-model="r._cfg.factor" size="small" placeholder="系数" class="cfg-w1 num" :controls="false" @change="syncRuleConfig(r)" />
                        <el-input-number v-model="r._cfg.offset" size="small" placeholder="偏移" class="cfg-w1 num" :controls="false" @change="syncRuleConfig(r)" />
                      </div>
                      <div v-else-if="r.ruleType === '范围校验'" class="rule-cfg-inline">
                        <el-select v-model="r._cfg.field" size="small" filterable allow-create placeholder="字段" class="cfg-w1" @change="syncRuleConfig(r)">
                          <el-option v-for="c in (sub.cleanTable ? sub._cleanCols : sub._srcCols)" :key="c" :label="c" :value="c" />
                        </el-select>
                        <el-input-number v-model="r._cfg.min" size="small" placeholder="最小值" class="cfg-w1 num" :controls="false" @change="syncRuleConfig(r)" />
                        <el-input-number v-model="r._cfg.max" size="small" placeholder="最大值" class="cfg-w1 num" :controls="false" @change="syncRuleConfig(r)" />
                      </div>
                      <div v-else-if="r.ruleType === '枚举校验'" class="rule-cfg-inline">
                        <el-select v-model="r._cfg.field" size="small" filterable allow-create placeholder="字段" class="cfg-w1" @change="syncRuleConfig(r)">
                          <el-option v-for="c in (sub.cleanTable ? sub._cleanCols : sub._srcCols)" :key="c" :label="c" :value="c" />
                        </el-select>
                        <el-input v-model="r._cfg.values" size="small" placeholder="枚举值,逗号分隔" class="cfg-w2" @input="syncRuleConfig(r)" />
                      </div>
                      <div v-else-if="r.ruleType === '格式校验'" class="rule-cfg-inline">
                        <el-select v-model="r._cfg.field" size="small" filterable allow-create placeholder="字段" class="cfg-w1" @change="syncRuleConfig(r)">
                          <el-option v-for="c in (sub.cleanTable ? sub._cleanCols : sub._srcCols)" :key="c" :label="c" :value="c" />
                        </el-select>
                        <el-input v-model="r._cfg.pattern" size="small" placeholder="正则表达式" class="cfg-w2" @input="syncRuleConfig(r)" />
                      </div>
                      <div v-else-if="r.ruleType === '字段映射'" class="rule-cfg-inline">
                        <el-select v-model="r._cfg.from" size="small" filterable allow-create placeholder="源字段" class="cfg-w1" @change="syncRuleConfig(r)">
                          <el-option v-for="c in (sub.cleanTable ? sub._cleanCols : sub._srcCols)" :key="c" :label="c" :value="c" />
                        </el-select>
                        <el-input v-model="r._cfg.to" size="small" placeholder="目标字段" class="cfg-w1" @input="syncRuleConfig(r)" />
                      </div>
                      <div v-else-if="r.ruleType === 'SQL 清洗'" class="rule-cfg-inline">
                        <el-input v-model="r._cfg.sql" size="small" type="textarea" :rows="1" placeholder="SQL 语句(仅数据库表源生效)" class="cfg-w3" @input="syncRuleConfig(r)" />
                      </div>
                      <div v-else-if="r.ruleType === '去空值' || r.ruleType === '去重复' || r.ruleType === '非空校验' || r.ruleType === '唯一校验' || r.ruleType === '关联完整性'" class="rule-cfg-inline">
                        <el-select v-model="r._cfg.field" size="small" filterable allow-create placeholder="字段" class="cfg-w2" @change="syncRuleConfig(r)">
                          <el-option v-for="c in (sub.cleanTable ? sub._cleanCols : sub._srcCols)" :key="c" :label="c" :value="c" />
                        </el-select>
                      </div>
                      <el-input v-else v-model="r.ruleConfig" size="small" placeholder="规则配置(JSON)" />
                    </el-col>
                    <el-col :span="2" style="text-align: right">
                      <el-button size="small" type="danger" link icon="Delete" @click="row.cleanRules.splice(cri, 1)" />
                    </el-col>
                  </el-row>
                </div>
                <el-empty v-if="!row.cleanRules.length" description="暂无规则, 点击「新增规则」" :image-size="30" />
              </div>
                </el-collapse-item>
                <el-collapse-item title="清洗结果表（源表经清洗规则后写入）" name="table">
              <el-row :gutter="16">
                <el-col :span="8">
                  <el-form-item label="结果数据源">
                    <el-select v-model="sub.cleanConnId" filterable clearable placeholder="清洗结果数据源(可复用源)" style="width: 100%" @change="onConnChange(sub, 'clean')">
                      <el-option v-for="c in connList" :key="c.id" :label="c.connName" :value="c.id" />
                    </el-select>
                  </el-form-item>
                </el-col>
                <el-col :span="8">
                  <el-form-item label="结果库">
                    <el-select v-model="sub.cleanSchema" filterable clearable placeholder="结果库" style="width: 100%" @change="onSchemaChange(sub, 'clean')">
                      <el-option v-for="s in sub._cleanDbs" :key="s" :label="s" :value="s" />
                    </el-select>
                  </el-form-item>
                </el-col>
                <el-col :span="8">
                  <el-form-item label="结果表">
                    <el-select v-model="sub.cleanTable" filterable clearable allow-create placeholder="清洗结果表(留空=不落结果表)" style="width: 100%" @change="onCleanTableChange(sub)">
                      <el-option v-for="t in sub._cleanTables" :key="t.tableName" :label="t.tableName" :value="t.tableName" />
                    </el-select>
                  </el-form-item>
                </el-col>
              </el-row>
              <el-row :gutter="16">
                <el-col :span="8">
                  <el-form-item label="写入模式">
                    <el-select v-model="sub.cleanWriteMode" style="width: 100%">
                      <el-option label="INSERT (追加)" value="INSERT" />
                      <el-option label="REPLACE (覆盖写)" value="REPLACE" />
                      <el-option label="TRUNCATE (清空后写入)" value="TRUNCATE" />
                    </el-select>
                  </el-form-item>
                </el-col>
                <el-col :span="16">
                  <div class="form-tip" style="line-height: 32px">结果表不存在时执行将自动建表（按清洗结果映射列生成）；多源表清洗结果汇总到同一张结果表时在此配置各源表字段对应关系。</div>
                </el-col>
              </el-row>


                </el-collapse-item>
                <el-collapse-item title="清洗结果映射（各源表字段 → 结果表字段）" name="maps">
              <div v-for="(row, ri) in sub.srcRows" :key="'cm' + ri" class="map-group">
                <div class="map-group-title">
                  {{ row.srcTable || ('源表' + (ri + 1) + '(未选择)') }}
                  <el-button size="small" type="primary" link icon="Plus" @click="row.maps.push({ srcField: '', dstField: '' })">新增映射</el-button>
                </div>
                <div v-for="(m, mi) in row.maps" :key="mi" class="rule-row">
                  <el-row :gutter="8">
                    <el-col :span="8">
                      <el-select v-model="m.srcField" filterable placeholder="源字段" style="width: 100%">
                        <el-option v-for="c in sub._srcCols" :key="c" :label="c" :value="c" />
                      </el-select>
                    </el-col>
                    <el-col :span="2" style="text-align: center; line-height: 32px; color: var(--el-text-color-secondary)">→</el-col>
                    <el-col :span="8">
                      <el-select v-model="m.dstField" filterable allow-create placeholder="结果表字段" style="width: 100%">
                        <el-option v-for="c in sub._cleanCols" :key="c" :label="c" :value="c" />
                      </el-select>
                    </el-col>
                    <el-col :span="6" style="text-align: right">
                      <el-button v-if="row.maps.length > 1" size="small" type="danger" link icon="Delete" @click="row.maps.splice(mi, 1)">删除</el-button>
                    </el-col>
                  </el-row>
                </div>
                <el-empty v-if="!row.maps.length" description="暂无映射, 点击「新增映射」(留空按同名自动映射)" :image-size="30" />
              </div>
                </el-collapse-item>
              </el-collapse>
            </div>

            <!-- ============ 面板3: 质量规则(前置: 源/映射/清洗/结果只读) ============ -->
            <div v-show="sub._activeMini === 2">
              <div class="ro-summary">
                <span class="ro-label">源</span>
                <el-tag size="small" type="info">{{ connName(sub.srcConnId) }}.{{ sub.srcSchema }}.{{ validSrcRows(sub).map(r => r.srcTable).join(' / ') }}</el-tag>
                <span class="ro-label">清洗映射</span>
                <el-tag size="small" type="info">{{ mapCount(sub) }} 条</el-tag>
                <span class="ro-label">清洗规则</span>
                <el-tag size="small" type="info">{{ cleanRuleCount(sub) }} 条</el-tag>
                <span class="ro-label">清洗结果</span>
                <el-tag size="small" type="info">{{ sub.cleanTable || '未配置' }}</el-tag>
              </div>
              <el-divider content-position="left">清洗规则明细（只读）</el-divider>
              <div v-for="(row, ri) in sub.srcRows" :key="'qr' + ri" class="ro-block">
                <div class="ro-block-title">{{ row.srcTable || ('源表' + (ri + 1)) }}</div>
                <el-tag v-for="(r, cri) in row.cleanRules" :key="cri" size="small" style="margin-right: 6px" type="warning">{{ r.ruleName || r.ruleType || '未命名规则' }}</el-tag>
                <span v-if="!row.cleanRules.length" class="ro-none">无</span>
              </div>
              <el-divider content-position="left">数据质量规则（可多个）</el-divider>
              <div v-for="(r, ri) in sub.qualityRules" :key="ri" class="rule-row">
                <el-row :gutter="8">
                  <el-col :span="6">
                    <el-input v-model="r.ruleName" placeholder="规则名称, 如: 非空校验" />
                  </el-col>
                  <el-col :span="6">
                    <el-select v-model="r.ruleType" placeholder="规则类型" style="width: 100%">
                      <el-option v-for="t in qualityRuleTypes" :key="t" :label="t" :value="t" />
                    </el-select>
                  </el-col>
                  <el-col :span="10">
                    <el-input v-model="r.ruleConfig" placeholder="规则配置, 如: {field: 'id', notNull: true}" />
                  </el-col>
                  <el-col :span="2" style="text-align: right">
                    <el-button size="small" type="danger" link icon="Delete" @click="sub.qualityRules.splice(ri, 1)" />
                  </el-col>
                </el-row>
              </div>
              <el-button size="small" type="primary" plain icon="Plus" @click="sub.qualityRules.push({ ruleName: '', ruleType: '', ruleConfig: '' })">新增质量规则</el-button>
            </div>

            <!-- ============ 面板4: 目标(目标类型/数据源/库/表/写入模式) ============ -->
            <div v-show="sub._activeMini === 3">
              <div class="ro-summary">
                <span class="ro-label">源</span>
                <el-tag size="small" type="info">{{ srcDesc(sub) }}</el-tag>
                <span class="ro-label">清洗结果</span>
                <el-tag size="small" type="info">{{ sub.cleanTable || '未配置(直接写目标)' }}</el-tag>
                <span class="ro-label">质量</span>
                <el-tag size="small" type="info">{{ sub.qualityRules.length }} 条</el-tag>
              </div>
              <el-row :gutter="16">
                <el-col :span="8">
                  <el-form-item label="目标类型" label-width="100px" label-position="right">
                    <el-select v-model="sub.dstType" style="width: 100%">
                      <el-option label="数据库表" value="TABLE" />
                      <el-option label="Excel 文件" value="EXCEL" />
                      <el-option label="API 接口" value="API" />
                    </el-select>
                  </el-form-item>
                </el-col>
                <el-col :span="16">
                  <div class="form-tip" style="line-height: 32px">表目标支持 INSERT/REPLACE/TRUNCATE；Excel 目标导出为 .xlsx；API 目标逐行 POST/PUT 推送。</div>
                </el-col>
              </el-row>

              <template v-if="sub.dstType === 'TABLE'">
              <el-divider content-position="left">目标数据源与目标表</el-divider>
              <el-row :gutter="16">
                <el-col :span="8">
                  <el-form-item label="目标数据源" label-width="100px" label-position="right">
                    <el-select v-model="sub.dstConnId" filterable placeholder="目标数据源" style="width: 100%" @change="onConnChange(sub, 'dst')">
                      <el-option v-for="c in connList" :key="c.id" :label="c.connName" :value="c.id" />
                    </el-select>
                  </el-form-item>
                </el-col>
                <el-col :span="8">
                  <el-form-item label="目标库" label-width="100px" label-position="right">
                    <el-select v-model="sub.dstSchema" filterable placeholder="目标库" style="width: 100%" @change="onSchemaChange(sub, 'dst')">
                      <el-option v-for="s in sub._dstDbs" :key="s" :label="s" :value="s" />
                    </el-select>
                  </el-form-item>
                </el-col>
                <el-col :span="8">
                  <el-form-item label="目标表" label-width="100px" label-position="right">
                    <el-select v-model="sub.dstTable" filterable allow-create placeholder="目标表" style="width: 100%" @change="onDstTableChange(sub)">
                      <el-option v-for="t in sub._dstTables" :key="t.tableName" :label="t.tableName" :value="t.tableName" />
                    </el-select>
                  </el-form-item>
                </el-col>
              </el-row>
              <el-row :gutter="16">
                <el-col :span="8">
                  <el-form-item label="写入模式" label-width="100px" label-position="right">
                    <el-select v-model="sub.dstWriteMode" style="width: 100%">
                      <el-option label="INSERT (追加)" value="INSERT" />
                      <el-option label="REPLACE (覆盖写)" value="REPLACE" />
                      <el-option label="TRUNCATE (清空后写入)" value="TRUNCATE" />
                      <el-option label="按字段替换 (DELETE+INSERT)" value="DELETE_INSERT" />
                    </el-select>
                  </el-form-item>
                </el-col>
                <el-col v-if="sub.dstWriteMode === 'DELETE_INSERT'" :span="8">
                  <el-form-item label="替换字段" label-width="100px" label-position="right">
                    <el-input v-model="sub.dstReplaceKey" clearable placeholder="如 f_code(按该字段匹配删除后插入)" style="width: 100%" />
                  </el-form-item>
                </el-col>
                <el-col :span="sub.dstWriteMode === 'DELETE_INSERT' ? 8 : 16">
                  <div class="form-tip" style="line-height: 32px">目标表不存在时执行将自动建表（按字段映射生成）；留空映射行按同名自动映射。</div>
                </el-col>
              </el-row>

                            <el-divider content-position="left" class="sec-div">
                              <span class="sec-title">增量同步 / 失败重试 / 脏数据容忍</span>
                              <span class="form-tip sec-tip">执行成功后将自动回写源表该字段最大值作为下次增量起点。</span>
                            </el-divider>
              <el-row :gutter="16">
                <el-col :span="6">
                  <el-form-item label="增量字段" label-width="100px" label-position="right">
                    <el-select v-model="sub.incrField" filterable allow-create clearable placeholder="如 category_id/update_time" style="width: 100%">
                      <el-option v-for="c in sub._srcCols" :key="c" :label="c" :value="c" />
                    </el-select>
                  </el-form-item>
                </el-col>
                <el-col :span="6">
                  <el-form-item label="增量模式" label-width="100px" label-position="right">
                    <el-select v-model="sub.incrMode" style="width: 100%">
                      <el-option label="不启用" value="NONE" />
                      <el-option label="ID 递增" value="ID" />
                      <el-option label="时间戳" value="TIME" />
                    </el-select>
                  </el-form-item>
                </el-col>
                <el-col :span="6">
                  <el-form-item label="起始值" label-width="100px" label-position="right">
                    <el-input v-model="sub.incrLastValue" placeholder="上次最大值, 留空则全量" style="width: 100%" />
                  </el-form-item>
                </el-col>
                </el-row>
              <el-row :gutter="16">
                <el-col :span="6">
                  <el-form-item label="失败重试次数" label-width="100px" label-position="right">
                    <el-input-number v-model="sub.retryCount" :min="0" :max="10" style="width: 100%" />
                  </el-form-item>
                </el-col>
                <el-col :span="6">
                  <el-form-item label="重试间隔(秒)" label-width="100px" label-position="right">
                    <el-input-number v-model="sub.retryInterval" :min="1" :max="3600" style="width: 100%" />
                  </el-form-item>
                </el-col>
                <el-col :span="6">
                  <el-form-item label="容忍脏数据%" label-width="100px" label-position="right">
                    <el-input-number v-model="sub.errorLimitPercent" :min="0" :max="100" :step="0.1" style="width: 100%" />
                  </el-form-item>
                </el-col>
                <el-col :span="6">
                  <el-form-item label="容忍条数" label-width="100px" label-position="right">
                    <el-input-number v-model="sub.errorLimitCount" :min="0" :max="100000" style="width: 100%" />
                  </el-form-item>
                </el-col>
              </el-row>

<div class="fm-head">
                  <span class="fm-title" @click="fmCollapsed = !fmCollapsed">
                    <span class="fm-arrow" :class="{ open: !fmCollapsed }">＞</span>字段映射（{{ sub.cleanTable ? '清洗结果表字段' : '源表字段' }} → 目标字段）
                  </span>
                  <span class="fm-btns">
                    <el-button size="small" type="primary" link icon="Plus" @click="sub.dstMaps.push({ srcField: '', dstField: '', dstDefault: '' })">新增映射</el-button>
                    <el-button size="small" type="primary" @click="submitForm">保存</el-button>
                    <el-button size="small" @click="goBack">取消</el-button>
                  </span>
                </div>
              <div v-show="!fmCollapsed" class="map-group">
                <div v-for="(m, mi) in sub.dstMaps" :key="'dm' + mi" class="rule-row">
                  <el-row :gutter="8">
                    <el-col :span="7">
                      <el-select v-model="m.srcField" filterable clearable placeholder="源字段(结果表/源表)" style="width: 100%">
                        <el-option v-for="c in (sub.cleanTable ? sub._cleanCols : sub._srcCols)" :key="c" :label="c" :value="c" />
                      </el-select>
                    </el-col>
                    <el-col :span="2" style="text-align: center; line-height: 32px; color: var(--el-text-color-secondary)">→</el-col>
                    <el-col :span="7">
                      <el-select v-model="m.dstField" filterable allow-create placeholder="目标字段" style="width: 100%">
                        <el-option v-for="c in sub._dstCols" :key="c" :label="c" :value="c" />
                      </el-select>
                    </el-col>
                    <el-col :span="7">
                      <el-input v-model="m.dstDefault" clearable placeholder="默认值: UUID/NOW/固定值" style="width: 100%" />
                    </el-col>
                    <el-col :span="1" style="text-align: right">
                      <el-button v-if="sub.dstMaps.length > 1" size="small" type="danger" link icon="Delete" @click="sub.dstMaps.splice(mi, 1)" />
                    </el-col>
                  </el-row>
                </div>
                <el-empty v-if="!sub.dstMaps.length" description="暂无映射, 点击「新增映射」(留空按同名自动映射)" :image-size="30" />
              </div>
              </template>

              <!-- 目标: Excel 文件 -->
              <template v-else-if="sub.dstType === 'EXCEL'">
                <el-divider content-position="left">目标 Excel 文件（统一目录管理）</el-divider>
                <el-row :gutter="16">
                  <el-col :span="10">
                    <el-form-item label="文件路径">
                      <el-select v-model="sub.dstExcelPath" filterable clearable allow-create placeholder="选择/输入目标文件" style="width: 100%" @change="onDstExcelPathChange(sub)">
                        <el-option v-for="f in fileList" :key="f" :label="f" :value="f" />
                      </el-select>
                    </el-form-item>
                  </el-col>
                  <el-col :span="7">
                    <el-form-item label="工作表">
                      <el-select v-model="sub.dstExcelSheet" filterable clearable placeholder="选择工作表" style="width: 100%" @change="onDstSheetChange(sub)">
                        <el-option v-for="sh in sub._dstSheets" :key="sh.name" :label="sh.name" :value="sh.name" />
                      </el-select>
                    </el-form-item>
                  </el-col>
                  <el-col :span="7">
                    <el-form-item label="表头行">
                      <el-input-number v-model="sub.dstExcelHeader" :min="0" :max="100" style="width: 100%" @change="rebuildDstExcelCols(sub)" />
                    </el-form-item>
                  </el-col>
                </el-row>
                <el-row :gutter="16">
                  <el-col :span="8">
                    <el-form-item label="起始列">
                      <el-input-number v-model="sub.dstExcelColFrom" :min="1" :max="1000" style="width: 100%" @change="rebuildDstExcelCols(sub)" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="8">
                    <el-form-item label="结束列">
                      <el-input-number v-model="sub.dstExcelColTo" :min="1" :max="1000" style="width: 100%" @change="rebuildDstExcelCols(sub)" />
                    </el-form-item>
                  </el-col>
                </el-row>
                <div class="form-tip">执行时将结果数据写为 xlsx（表头行为列名，取起始列-结束列）；目标文件已存在则覆盖。相对路径基于服务端统一目录（可用系统属性 etl.file.dir 覆盖）。</div>
              </template>

              <!-- 目标: API 接口 -->
              <template v-else-if="sub.dstType === 'API'">
                <el-divider content-position="left">目标 API 接口（逐行推送）</el-divider>
                <el-row :gutter="16">
                  <el-col :span="14">
                    <el-form-item label="接口地址">
                      <el-input v-model="sub.dstApiUrl" placeholder="https://host/api/save, 支持 ${字段名} 占位" />
                    </el-form-item>
                  </el-col>
                  <el-col :span="10">
                    <el-form-item label="请求方法">
                      <el-select v-model="sub.dstApiMethod" style="width: 100%">
                        <el-option label="POST" value="POST" />
                        <el-option label="PUT" value="PUT" />
                      </el-select>
                    </el-form-item>
                  </el-col>
                </el-row>
                <el-row :gutter="16">
                  <el-col :span="12">
                    <el-form-item label="请求头 JSON">
                      <el-input v-model="sub.dstApiHeaders" placeholder='{"Authorization":"Bearer xxxx"}' />
                    </el-form-item>
                  </el-col>
                  <el-col :span="12">
                    <el-form-item label="请求体模板">
                      <el-input v-model="sub.dstApiBodyTpl" placeholder='{"id":"${categoryId}","name":"${categoryName}"}' />
                    </el-form-item>
                  </el-col>
                </el-row>
                <el-divider content-position="left">API 鉴权</el-divider>
                <el-row :gutter="16">
                  <el-col :span="8">
                    <el-form-item label="鉴权方式">
                      <el-select v-model="sub._dstAuth.type" style="width: 100%">
                        <el-option label="无鉴权 (使用请求头 JSON)" value="NONE" />
                        <el-option label="Token 动态获取" value="TOKEN" />
                      </el-select>
                    </el-form-item>
                  </el-col>
                  <el-col :span="16">
                    <div class="form-tip" style="line-height: 32px">TOKEN: 先从 tokenUrl 获取令牌（5 分钟缓存），自动注入请求头与 URL 的 ${token} 占位。</div>
                  </el-col>
                </el-row>
                <template v-if="sub._dstAuth.type === 'TOKEN'">
                  <el-row :gutter="16">
                    <el-col :span="14">
                      <el-form-item label="Token 地址">
                        <el-input v-model="sub._dstAuth.tokenUrl" placeholder="https://host/api/token" />
                      </el-form-item>
                    </el-col>
                    <el-col :span="5">
                      <el-form-item label="方法">
                        <el-select v-model="sub._dstAuth.tokenMethod" style="width: 100%">
                          <el-option label="POST" value="POST" />
                          <el-option label="GET" value="GET" />
                        </el-select>
                      </el-form-item>
                    </el-col>
                    <el-col :span="5">
                      <el-form-item label="Token 路径">
                        <el-input v-model="sub._dstAuth.tokenPath" placeholder="如 data.token" />
                      </el-form-item>
                    </el-col>
                  </el-row>
                  <el-row :gutter="16">
                    <el-col :span="12">
                      <el-form-item label="Token 请求头 JSON">
                        <el-input v-model="sub._dstAuth.tokenHeaders" placeholder='{"Content-Type":"application/json"}' />
                      </el-form-item>
                    </el-col>
                    <el-col :span="12">
                      <el-form-item label="Token 请求体">
                        <el-input v-model="sub._dstAuth.tokenBody" placeholder='{"appId":"xx","secret":"yy"}' />
                      </el-form-item>
                    </el-col>
                  </el-row>
                </template>
                <div class="form-tip">每条记录触发一次请求；URL 与请求体中的 ${字段名} 由该行字段值替换；响应体不落库。</div>
              </template>
            </div>

            <!-- ============ 面板5: 完成预览(全部配置 + 过程图, 点击节点查看日志) ============ -->
            <div v-show="sub._activeMini === 4">
              <el-divider content-position="left">子任务配置汇总</el-divider>
              <el-descriptions :column="2" border size="small" class="preview-desc">
                <el-descriptions-item label="子任务名称">{{ sub.jobName }}</el-descriptions-item>
                <el-descriptions-item label="引擎">{{ sub.jobType }}</el-descriptions-item>
                <el-descriptions-item label="源">{{ srcDesc(sub) }}</el-descriptions-item>
                <el-descriptions-item label="目标">{{ dstDesc(sub) }}</el-descriptions-item>
                <el-descriptions-item label="清洗结果">{{ sub.cleanTable || '未配置' }}（{{ sub.cleanWriteMode || 'INSERT' }}）</el-descriptions-item>
                <el-descriptions-item label="清洗规则">{{ cleanRuleCount(sub) }} 条（按表）</el-descriptions-item>
                <el-descriptions-item label="质量规则">{{ sub.qualityRules.length }} 条</el-descriptions-item>
                <el-descriptions-item label="清洗/目标映射">{{ mapCount(sub) }} / {{ dstMapCount(sub) }} 条</el-descriptions-item>
                <el-descriptions-item label="参数">并发 {{ sub.speedChannel }} / 批 {{ sub.batchSize }}</el-descriptions-item>
              </el-descriptions>

              <el-divider content-position="left">执行过程图（点击节点查看日志）</el-divider>
              <div class="flow-canvas">
                <svg :viewBox="'0 0 ' + fg(sub).width + ' ' + fg(sub).height" class="flow-svg">
                  <defs>
                    <marker id="flowArrow" markerWidth="10" markerHeight="8" refX="9" refY="4" orient="auto">
                      <path d="M0,0 L10,4 L0,8 z" fill="#8a94a6"></path>
                    </marker>
                  </defs>
                  <path v-for="(l, li) in fg(sub).lines" :key="'fl' + li" :d="l.d" fill="none" stroke="#8a94a6" stroke-width="1.6" marker-end="url(#flowArrow)"></path>
                  <circle v-for="(l, li) in fg(sub).lines" :key="'fc' + li" r="3.6" fill="#409eff" opacity="0.85">
                    <animateMotion :dur="l.dur" repeatCount="indefinite" :path="l.d"></animateMotion>
                  </circle>
                  <g v-for="(n, ni) in fg(sub).nodes" :key="'fn' + ni" @click="n.click" class="flow-svg-node">
                    <title>{{ n.tip }}</title>
                    <rect :x="n.x" :y="n.y" :width="n.w" :height="n.h" :rx="n.rx" :fill="n.fill" :stroke="n.stroke" stroke-width="1"></rect>
                    <text v-for="(t, ti) in n.texts" :key="ti" :x="n.x + n.w / 2" :y="n.y + t.y" text-anchor="middle" :font-size="t.size" :fill="t.color" :font-weight="t.bold || 600">{{ t.v }}</text>
                  </g>
                </svg>
              </div>
            </div>
          </el-form>
          </div>
        </div>

        <div v-if="!form.children.length" class="empty-subtask">
          <el-empty description="尚未添加子任务, 点击「新增子任务」开始设计" />
        </div>

        <div class="step-actions">
          <el-button @click="activeStep = 0">上一步</el-button>
          <el-button type="primary" :disabled="!form.children.length" @click="activeStep = 2">下一步：调度配置</el-button>
        </div>
      </div>

      <!-- ③ 调度配置 -->
      <div v-show="activeStep === 2" class="step-panel">
        <el-form label-width="110px" style="max-width: 640px">
          <el-form-item label="子任务执行">
            <el-radio-group v-model="form.runMode">
              <el-radio-button value="SEQUENTIAL">顺序执行</el-radio-button>
              <el-radio-button value="PARALLEL">并行执行</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="执行告警">
            <el-select v-model="form.notifyType" style="width: 200px">
              <el-option label="不告警" value="NONE" />
              <el-option label="邮件" value="MAIL" />
            </el-select>
            <span style="margin: 0 8px" />
            <el-select v-model="form.notifyOn" style="width: 140px">
              <el-option label="仅失败时" value="FAILED" />
              <el-option label="仅成功时" value="SUCCESS" />
              <el-option label="成功与失败" value="ALL" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="form.notifyType === 'MAIL'" label="收件邮箱">
            <el-input v-model="form.notifyMails" placeholder="多个邮箱用英文逗号分隔" style="width: 360px" />
          </el-form-item>
          <el-form-item label="调度启用">
            <el-switch v-model="form.scheduleEnabled" active-value="1" inactive-value="0" />
          </el-form-item>
          <el-form-item label="Cron 表达式">
            <div class="cron-row">
              <el-input v-model="form.scheduleCron" placeholder="如: 0 0 2 * * ?  每天凌晨2点" style="width: 340px" @blur="checkCron" @input="onCronTextInput">
                <template #append>
                  <el-button icon="MagicStick" @click="openCron">可视化</el-button>
                </template>
              </el-input>
              <span class="cron-desc" :class="{ invalid: cronDescInvalid }">{{ cronDescText }}</span>
            </div>
          </el-form-item>
          <el-form-item label="下次执行">
            <span class="next-time">{{ form._nextTime || '—' }}</span>
          </el-form-item>
        </el-form>
        <div class="step-actions">
          <el-button @click="activeStep = 1">上一步</el-button>
          <el-button type="primary" @click="activeStep = 3">下一步：确认保存</el-button>
        </div>
      </div>

      <!-- ④ 完成 -->
      <div v-show="activeStep === 3" class="step-panel">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="任务名称">{{ form.jobName }}</el-descriptions-item>
          <el-descriptions-item label="子任务数">{{ form.children.length }}</el-descriptions-item>
          <el-descriptions-item label="调度">{{ form.scheduleEnabled === '1' ? form.scheduleCron : '未启用' }}</el-descriptions-item>
          <el-descriptions-item label="执行方式">{{ form.runMode === 'PARALLEL' ? '并行(按依赖分层)' : '顺序执行' }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="form.children" style="margin-top: 16px" size="small">
          <el-table-column type="index" label="#" width="50" />
          <el-table-column prop="jobName" label="子任务" />
          <el-table-column label="引擎" width="90">
            <template #default="{ row }">{{ row.jobType }}</template>
          </el-table-column>
          <el-table-column label="源 → 目标" min-width="220">
            <template #default="{ row }">
              {{ connName(row.srcConnId) }}.{{ row.srcSchema }}.{{ firstSrcTable(row) }} → {{ connName(row.dstConnId) }}.{{ row.dstSchema }}.{{ row.dstTable }}
            </template>
          </el-table-column>
          <el-table-column label="清洗/质量规则" width="130">
            <template #default="{ row }">{{ cleanRuleCount(row) }} / {{ (row.qualityRules || []).length }}</template>
          </el-table-column>
          <el-table-column label="字段映射" width="100">
            <template #default="{ row }">{{ mapCount(row) }} 条</template>
          </el-table-column>
        </el-table>
        <div class="step-actions">
          <el-button @click="activeStep = 2">上一步</el-button>
          <el-button type="primary" :loading="submitting" @click="submitForm(false)">保存</el-button>
          <el-button type="success" :loading="submitting" @click="submitForm(true)">保存并执行</el-button>
        </div>
      </div>
    </el-card>

    <!-- 节点日志弹窗 -->
    <el-dialog v-model="logDialog.open" :title="logDialog.title" width="780px" top="8vh">
      <el-table v-loading="logDialog.loading" :data="logDialog.rows" size="small" border>
        <el-table-column prop="nodeType" label="类型" width="110" />
        <el-table-column prop="nodeName" label="节点" min-width="160" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.runStatus === 'SUCCESS' ? 'success' : row.runStatus === 'FAILED' ? 'danger' : 'warning'" size="small">{{ row.runStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="totalRecords" label="记录数" width="80" align="right" />
        <el-table-column prop="startTime" label="开始" width="175" />
        <el-table-column prop="endTime" label="结束" width="175" />
        <el-table-column label="操作" width="70" align="center">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="viewLogDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!logDialog.rows.length && !logDialog.loading" description="该节点暂无执行日志（执行后产生）" />
      <!-- 日志详情 -->
      <el-dialog v-model="logDetailOpen" title="日志详情" width="720px" append-to-body top="10vh">
        <pre class="log-detail-pre">{{ logDetailText }}</pre>
      </el-dialog>
    </el-dialog>

    <!-- Cron 可视化 -->
    <el-dialog v-if="cronOpen" :model-value="cronOpen" title="Cron 表达式可视化" width="680px" append-to-body @close="cronOpen = false">
      <CronGen :model-value="form.scheduleCron" @update:model-value="onCronInput" @change="onCronGenChange" />
      <template #footer>
        <el-button @click="cronCancel">取消</el-button>
        <el-button type="primary" @click="cronConfirm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="EtlParentForm">
import { ref, reactive, onMounted, watch, computed, getCurrentInstance } from 'vue'
import { describeCron, cronInvalidReason } from './components/CronDesc'
import { useRoute, useRouter } from 'vue-router'
import { addEtlJob, updateEtlJob, getEtlJob, listEtlJob, listAllConn, listDatabases, listTables, listColumns, listEtlJobLog, cronNext, runEtlJob } from '@/api/etl/index'
import request from '@/utils/request'
import CronGen from './components/CronGen.vue'

const { proxy } = getCurrentInstance()
const route = useRoute()
const router = useRouter()
const isEdit = ref(false)
// 字段映射折叠状态
const fmCollapsed = ref(false)
const activeStep = ref(0)
const submitting = ref(false)
const cronOpen = ref(false)
const cronBackup = ref('')
const connList = ref([])
// 所属目录选项(目录树扁平化, 层级缩进)
const dirOptions = ref([])
function loadDirOptions() {
  listEtlJob({ pageNum: 1, pageSize: 999 }).then(res => {
    const walk = (list, depth) => list.flatMap(j => {
      if (j.nodeType !== 'DIR') return []
      return [{ id: j.id, jobName: (depth ? '　'.repeat(depth) : '') + j.jobName }, ...walk(j.children || [], depth + 1)]
    })
    dirOptions.value = walk((res.data?.rows) || [], 0)
  }).catch(() => { dirOptions.value = [] })
}

const miniSteps = ['源/表', '清洗规则', '质量规则', '目标', '完成预览']
const cleanRuleTypes = ['去空值', '去重复', '格式规范化', '字段映射', '单位换算', '默认值填充', '当前时间填充', 'UUID填充', '叶子标记', '范围校验', '枚举校验', '格式校验', '关联完整性', '非空校验', '唯一校验', 'SQL 清洗']
const qualityRuleTypes = ['非空校验', '唯一校验', '范围校验', '枚举校验', '格式校验', '关联完整性', '自定义 SQL']

// ===== Excel 工作表/列工具 =====
function colLetter(n) {
  let s = ''
  let v = Number(n) || 1
  while (v > 0) {
    const m = (v - 1) % 26
    s = String.fromCharCode(65 + m) + s
    v = Math.floor((v - 1) / 26)
  }
  return s
}
function excelColsFromSheet(subObj, role, headerRow, colFrom, colTo) {
  const info = role === 'src' ? subObj._srcSheetInfo : subObj._dstSheetInfo
  const out = []
  if (!info || !info.preview) return out
  const h = Number(headerRow) || 0
  const from = Number(colFrom) || 1
  const to = Number(colTo) || from
  const row = info.preview[h] || []
  for (let c = from; c <= to; c++) {
    const v = row[c - 1]
    out.push((v !== undefined && String(v).trim() !== '') ? String(v).trim() : colLetter(c))
  }
  return out
}
function loadSheets(subObj, role) {
  const path = role === 'src' ? subObj.srcExcelPath : subObj.dstExcelPath
  if (!path) return
  if (role === 'src') subObj._srcSheets = []
  else subObj._dstSheets = []
  request({ url: '/etl/file/sheets', method: 'get', params: { path: String(path).replace(/\\/g, '/') } }).then(res => {
    const list = res.data || []
    if (role === 'src') {
      subObj._srcSheets = list
      if (list.length && !subObj.srcExcelSheet) subObj.srcExcelSheet = list[0].name
      const sh = list.find(x => x.name === subObj.srcExcelSheet) || list[0]
      subObj._srcSheetInfo = sh || null
      rebuildSrcExcelCols(subObj)
    } else {
      subObj._dstSheets = list
      if (list.length && !subObj.dstExcelSheet) subObj.dstExcelSheet = list[0].name
      const sh = list.find(x => x.name === subObj.dstExcelSheet) || list[0]
      subObj._dstSheetInfo = sh || null
      rebuildDstExcelCols(subObj)
    }
  }).catch(() => {
    if (role === 'src') subObj._srcSheetInfo = null
    else subObj._dstSheetInfo = null
  })
}
function onSrcExcelPathChange(subObj) {
  loadSheets(subObj, 'src')
  validateExcelConfig(subObj, 'src')
}
function onDstExcelPathChange(subObj) {
  loadSheets(subObj, 'dst')
}
function onSrcSheetChange(subObj) {
  const sh = (subObj._srcSheets || []).find(x => x.name === subObj.srcExcelSheet)
  subObj._srcSheetInfo = sh || null
  rebuildSrcExcelCols(subObj)
  validateExcelConfig(subObj, 'src')
}
function onDstSheetChange(subObj) {
  const sh = (subObj._dstSheets || []).find(x => x.name === subObj.dstExcelSheet)
  subObj._dstSheetInfo = sh || null
  rebuildDstExcelCols(subObj)
}
function rebuildSrcExcelCols(subObj) {
  if ((subObj.srcType || 'TABLE') !== 'EXCEL') return
  subObj._srcCols = excelColsFromSheet(subObj, 'src', subObj.srcExcelHeader, subObj.srcExcelColFrom, subObj.srcExcelColTo)
}
function rebuildDstExcelCols(subObj) {
  if ((subObj.dstType || 'TABLE') !== 'EXCEL') return
  subObj._dstCols = excelColsFromSheet(subObj, 'dst', subObj.dstExcelHeader, subObj.dstExcelColFrom, subObj.dstExcelColTo)
}
function srcHeaderPreview(subObj) {
  const info = subObj._srcSheetInfo
  if (!info || !info.preview) return ''
  const h = Number(subObj.srcExcelHeader) || 0
  const from = Number(subObj.srcExcelColFrom) || 1
  const to = Number(subObj.srcExcelColTo) || from
  const row = info.preview[h] || []
  const cells = []
  for (let c = from; c <= to; c++) {
    const v = row[c - 1]
    cells.push(colLetter(c) + '=' + ((v !== undefined && String(v).trim() !== '') ? String(v).trim() : '空'))
  }
  return cells.join('  ')
}
function dstHeaderPreview(subObj) {
  const info = subObj._dstSheetInfo
  if (!info || !info.preview) return ''
  const h = Number(subObj.dstExcelHeader) || 0
  const from = Number(subObj.dstExcelColFrom) || 1
  const to = Number(subObj.dstExcelColTo) || from
  const row = info.preview[h] || []
  const cells = []
  for (let c = from; c <= to; c++) {
    const v = row[c - 1]
    cells.push(colLetter(c) + '=' + ((v !== undefined && String(v).trim() !== '') ? String(v).trim() : '空'))
  }
  return cells.join('  ')
}
function validateExcelConfig(subObj, role) {
  const isSrc = role === 'src'
  const from = Number(isSrc ? subObj.srcExcelColFrom : subObj.dstExcelColFrom) || 0
  const to = Number(isSrc ? subObj.srcExcelColTo : subObj.dstExcelColTo) || 0
  const header = Number(isSrc ? subObj.srcExcelHeader : subObj.dstExcelHeader)
  const dataRow = Number(isSrc ? subObj.srcExcelDataRow : subObj.dstExcelDataRow)
  const info = isSrc ? subObj._srcSheetInfo : subObj._dstSheetInfo
  if (from && to && from > to) {
    proxy.$modal.msgError((isSrc ? '源' : '目标') + '起始列不能大于结束列')
    return false
  }
  if (header != null && dataRow != null && dataRow < header) {
    proxy.$modal.msgError('数据起始行不能小于表头行')
    return false
  }
  if (info) {
    if (to && info.cols && to > info.cols) {
      proxy.$modal.msgError('结束列(' + to + ')超出工作表最大列数(' + info.cols + ')')
      return false
    }
    if (dataRow != null && info.rows && dataRow >= info.rows) {
      proxy.$modal.msgError('数据起始行(' + dataRow + ')超出工作表最大行数(' + info.rows + ')')
      return false
    }
  }
  if (isSrc) rebuildSrcExcelCols(subObj)
  return true
}

// ===== 清洗规则可视化配置 =====
function onRuleTypeChange(r) {
  if (!r._cfg) r._cfg = {}
  try {
    const prev = JSON.parse(r.ruleConfig || '{}')
    Object.assign(r._cfg, prev)
  } catch (e) { /* ignore */ }
  syncRuleConfig(r)
}
function syncRuleConfig(r) {
  const cfg = r._cfg || {}
  const map = {
    '去空值': ['field'],
    '去重复': ['field'],
    '非空校验': ['field'],
    '唯一校验': ['field'],
    '关联完整性': ['field'],
    '格式规范化': ['field', 'action'],
    '单位换算': ['field', 'factor', 'offset'],
    '默认值填充': ['field', 'defaultValue', 'whenField', 'whenValue'],
    '当前时间填充': ['field'],
    'UUID填充': ['field'],
    '叶子标记': ['field', 'codeField', 'parentField', 'leafValue', 'nonLeafValue'],
    '范围校验': ['field', 'min', 'max'],
    '枚举校验': ['field', 'values'],
    '格式校验': ['field', 'pattern'],
    '字段映射': ['from', 'to'],
    'SQL 清洗': ['sql']
  }
  const keys = map[r.ruleType] || ['field']
  const keep = {}
  keys.forEach(k => {
    const v = cfg[k]
    if (v !== undefined && v !== null && String(v).trim() !== '') keep[k] = v
  })
  r.ruleConfig = JSON.stringify(keep)
}

let keySeed = 1
function newSubtask() {
  return {
    _key: 's' + keySeed++,
    _activeMini: 0,
    _cleanCollapse: ['rules'],
    _collapsed: false,
    _designerOpen: ['d'],
    _xmlPreview: '',
    _xmlLoading: false,
    jobName: '',
    jobType: 'KETTLE',
    srcType: 'TABLE',
    srcConnId: undefined,
    srcSchema: undefined,
    srcExcelPath: '',
    srcExcelSheet: '',
    srcExcelHeader: 0,
    srcExcelDataRow: undefined,
    srcExcelColFrom: undefined,
    srcExcelColTo: undefined,
    _srcSheets: [],
    _srcSheetInfo: null,
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
    dstExcelHeader: 0,
    dstExcelColFrom: undefined,
    dstExcelColTo: undefined,
    _dstSheets: [],
    _dstSheetInfo: null,
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
    status: '0',
    depIds: []
  }
}

const form = reactive({
  id: undefined,
  jobName: route.query.jobName ? decodeURIComponent(String(route.query.jobName)) : '',
  remark: '',
  runMode: 'SEQUENTIAL',
  scheduleEnabled: '0',
  scheduleCron: '',
  _nextTime: '',
  notifyType: 'NONE',
  notifyMails: '',
  notifyOn: 'FAILED',
  parentId: '0',
  children: []
})

function addSubtask() {
  form.children.push(newSubtask())
}

/** 返回任务列表 */
function goBack() { router.push('/etl/job') }

function removeSubtask(idx) {
  form.children.splice(idx, 1)
}

/** 复制子任务: 深拷贝全部配置(清 id 按新增保存), 仅父调用方可改名/改目标 */
function copySubtask(idx) {
  const src = form.children[idx]
  if (!src) return
  const copy = JSON.parse(JSON.stringify(src))
  copy._key = 'st_' + Date.now() + '_' + Math.floor(Math.random() * 10000)
  copy._collapsed = false
  copy._activeMini = 0
  copy.id = undefined
  copy.depIds = []
  copy.jobName = (src.jobName || '子任务') + '(复制)'
  form.children.splice(idx + 1, 0, copy)
}

// 可选前置依赖: 同父下已保存(有 id)的其他子任务
function otherSubtasks(idx) {
  return form.children
    .map((s, i) => ({ ...s, _idx: i }))
    .filter((s, i) => i !== idx && s.id)
}

function addSrcRow(sub) {
  sub.srcRows.push({ srcTable: '', srcWhere: '', joinType: 'UNION', joinField: '', joinRef: '', srcConnId: undefined, srcSchema: undefined, _srcRowDbs: [], maps: [{ srcField: '', dstField: '' }], cleanRules: [] })
}

function removeSrcRow(sub, ri) {
  sub.srcRows.splice(ri, 1)
}

function connName(id) {
  const s = String(id == null ? '' : id)
  const c = connList.value.find(x => {
    const xi = String(x.id)
    return xi === s || (s.length > 16 && xi.startsWith(s.slice(0, 16))) || (xi.length > 16 && s.startsWith(xi.slice(0, 16)))
  })
  return c ? c.connName : (id ? '?' : '')
}

function srcDesc(sub) {
  const t = sub.srcType || 'TABLE'
  if (t === 'EXCEL') return 'Excel: ' + (sub.srcExcelPath || '未配置')
  if (t === 'API') return 'API: ' + (sub.srcApiUrl || '未配置')
  return connName(sub.srcConnId) + '.' + (sub.srcSchema || '') + '.' + validSrcRows(sub).map(r => r.srcTable).join(' / ')
}

function dstDesc(sub) {
  const t = sub.dstType || 'TABLE'
  if (t === 'EXCEL') return 'Excel: ' + (sub.dstExcelPath || '未配置')
  if (t === 'API') return 'API: ' + (sub.dstApiUrl || '未配置') + '（' + (sub.dstApiMethod || 'POST') + '）'
  return connName(sub.dstConnId) + '.' + (sub.dstSchema || '') + '.' + (sub.dstTable || '未配置') + '（' + (sub.dstWriteMode || 'INSERT') + '）'
}

function srcBrief(sub) {
  const t = sub.srcType || 'TABLE'
  if (t === 'EXCEL') return sub.srcExcelPath || '未配置'
  if (t === 'API') return sub.srcApiUrl || '未配置'
  return connName(sub.srcConnId) || '未配置'
}

function dstBrief(sub) {
  const t = sub.dstType || 'TABLE'
  if (t === 'EXCEL') return sub.dstExcelPath || '未配置'
  if (t === 'API') return sub.dstApiUrl || '未配置'
  return connName(sub.dstConnId) || '未配置'
}

function firstSrcTable(sub) {
  const row = (sub.srcRows || []).find(r => r.srcTable)
  return row ? row.srcTable : ''
}

function validSrcRows(sub) {
  return (sub.srcRows || []).filter(r => r.srcTable)
}

function mapCount(sub) {
  return validSrcRows(sub).reduce((n, r) => n + (r.maps || []).filter(m => m.srcField || m.dstField).length, 0)
}

function cleanRuleCount(sub) {
  return validSrcRows(sub).reduce((n, r) => n + (r.cleanRules || []).filter(x => x.ruleName || x.ruleType).length, 0)
}

function dstMapCount(sub) {
  return (sub.dstMaps || []).filter(m => m.srcField || m.dstField).length
}

function onConnChange(sub, role) {
  const connId = sub[role === 'src' ? 'srcConnId' : role === 'dst' ? 'dstConnId' : 'cleanConnId']
  const dbKey = role === 'src' ? '_srcDbs' : role === 'dst' ? '_dstDbs' : '_cleanDbs'
  sub[dbKey] = []
  sub[role === 'src' ? 'srcSchema' : role === 'dst' ? 'dstSchema' : 'cleanSchema'] = undefined
  if (!connId) return
  listDatabases(connId).then(res => {
    sub[dbKey] = res.data || res.rows || []
  })
}

function onSchemaChange(sub, role) {
  const connId = sub[role === 'src' ? 'srcConnId' : role === 'dst' ? 'dstConnId' : 'cleanConnId']
  const schema = sub[role === 'src' ? 'srcSchema' : role === 'dst' ? 'dstSchema' : 'cleanSchema']
  const tableKey = role === 'src' ? '_srcTables' : role === 'dst' ? '_dstTables' : '_cleanTables'
  sub[tableKey] = []
  if (!connId || !schema) return
  listTables(connId, schema).then(res => {
    sub[tableKey] = res.data || res.rows || []
  })
}

function onRowConnChange(sub, row) {
  row._srcRowDbs = []
  row.srcSchema = undefined
  if (!row.srcConnId) return
  listDatabases(row.srcConnId).then(res => {
    row._srcRowDbs = res.data || res.rows || []
  })
}

function onRowSchemaChange(sub, row) {
  const cid = row.srcConnId || sub.srcConnId
  const sch = row.srcSchema || sub.srcSchema
  row._srcRowTables = []
  if (!cid || !sch) return
  listTables(cid, sch).then(res => {
    row._srcRowTables = res.data || res.rows || []
  })
}

function onSrcTableChange(sub, row) {
  const cid = row.srcConnId || sub.srcConnId
  const sch = row.srcSchema || sub.srcSchema
  if (cid && sch && row.srcTable) {
    listColumns(cid, sch, row.srcTable).then(res => {
      const cols = (res.data || res.rows || [])
      sub._srcCols = cols.map(c => c.columnName || c.name || c)
    })
  }
}

function onDstTableChange(sub) {
  if (sub.dstConnId && sub.dstSchema && sub.dstTable) {
    listColumns(sub.dstConnId, sub.dstSchema, sub.dstTable).then(res => {
      const cols = (res.data || res.rows || [])
      sub._dstCols = cols.map(c => c.columnName || c.name || c)
    })
  }
}

function onCleanTableChange(sub) {
  sub._cleanCols = []
  if (sub.cleanConnId && sub.cleanSchema && sub.cleanTable) {
    listColumns(sub.cleanConnId, sub.cleanSchema, sub.cleanTable).then(res => {
      const cols = (res.data || res.rows || [])
      sub._cleanCols = cols.map(c => c.columnName || c.name || c)
    })
  }
}

// ===== 完成预览: 过程图点击节点查看日志 =====
const logDialog = reactive({
  open: false,
  title: '',
  rows: [],
  loading: false
})
const logDetailOpen = ref(false)
const logDetailText = ref('')

function viewLogDetail(row) {
  logDetailText.value = (row.logText || '暂无日志内容') + (row.errorMsg ? '\n\n[错误] ' + row.errorMsg : '')
  logDetailOpen.value = true
}

function clipText(t, n) {
  if (!t) return '-'
  t = String(t)
  return t.length > n ? t.slice(0, n - 1) + '…' : t
}

function fgNode(x, y, w, h, fill, stroke, texts, click, tip) {
  return { x, y, w, h, rx: 8, fill, stroke, texts, click, tip }
}

function flowGraph(sub) {
  const rows = validSrcRows(sub)
  const N = Math.max(rows.length, 1)
  const NW = 150, NH = 58, STEP = 200, GAP = 150
  const Y0 = 70
  const X_START = 20
  const X_DS = 210
  const X_BR = 430
  const LX = 470
  const hasClean = !!(sub.cleanTable)
  const hasLane = rows.length > 0
  // 最后节点(目标表)的右边缘: 源表→清洗→[清洗结果]→质量→目标数据源→目标表
  const laneEnd = hasLane ? (LX + 5 * STEP + NW + (hasClean ? STEP : 0)) : LX
  const END_X = laneEnd + 70          // 汇聚母线/结束节点衔接处
  const yc = Y0 + (N - 1) * GAP / 2
  const nodes = [], lines = []
  const jtLabel = r => r.joinType === 'JOIN' ? ' INNER JOIN' : (r.joinType === 'LEFT_JOIN' ? ' LEFT JOIN' : '')
  const laneY = i => Y0 + i * GAP
  const dur = '2.2s'
  // 开始
  nodes.push(fgNode(X_START, yc - 26, 96, 52, '#e1f3d8', '#95d475',
    [{ v: '开始', y: 19, size: 14, color: '#529b2e' }],
    () => viewNodeLog(sub, { type: 'SUBTASK', nodeId: sub.id, name: '开始' }), '开始'))
  // 选择数据源
  nodes.push(fgNode(X_DS, yc - 29, 190, 58, '#ecf5ff', '#a0cfff',
    [{ v: '选择数据源', y: 18, size: 12, color: '#79bbff' },
     { v: clipText(srcBrief(sub), 13), y: 41, size: 13, color: '#303133' }],
    () => viewNodeLog(sub, { type: 'DATA_SOURCE', nodeId: sub.srcConnId, name: '选择数据源' }), '选择数据源'))
  lines.push({ d: 'M' + (X_START + 96) + ',' + yc + ' L' + X_DS + ',' + yc, dur: '2.0s' })
  // 分支母线
  if (N > 1) {
    lines.push({ d: 'M' + (X_DS + 190) + ',' + yc + ' L' + X_BR + ',' + yc, dur: '2.3s' })
    lines.push({ d: 'M' + X_BR + ',' + (Y0 - 24) + ' L' + X_BR + ',' + (Y0 + (N - 1) * GAP + 24), dur: '3.0s' })
  } else {
    // 单源: 直接连到 lane 起点(有 lane)或结束节点左侧(Excel/API 无 lane)
    lines.push({ d: 'M' + (X_DS + 190) + ',' + yc + ' L' + (hasLane ? LX : END_X - 20) + ',' + yc, dur: '2.3s' })
  }
  // 各 lane
  rows.forEach((row, i) => {
    const y = laneY(i)
    if (N > 1) lines.push({ d: 'M' + X_BR + ',' + y + ' L' + (LX - 20) + ',' + y, dur: '2.2s' })
    let cx = LX
    // 源表
    nodes.push(fgNode(cx, y - 29, NW, NH, '#fdf6ec', '#f5dab1',
      [{ v: '源表' + (i + 1) + jtLabel(row), y: 18, size: 12, color: '#e6a23c' },
       { v: clipText(row.srcTable + '@' + connName(row.srcConnId || sub.srcConnId), 32), y: 41, size: 12, color: '#303133' }],
      () => viewNodeLog(sub, { type: 'TABLE', nodeId: row.srcTable, name: '源表' + (i + 1) }), '源表' + (i + 1) + ': ' + row.srcTable))
    cx += STEP
    lines.push({ d: 'M' + (cx - STEP + NW) + ',' + y + ' L' + cx + ',' + y, dur: '2.2s' })
    const clean = row.cleanRules || []
    nodes.push(fgNode(cx, y - 29, NW, NH, '#f0f9eb', '#e1f3d8',
      [{ v: '清洗规则', y: 18, size: 12, color: '#85ce61' },
       { v: clean.length ? clipText(clean.map(c => c.ruleName || c.ruleType).join('、'), 15) : '无', y: 41, size: 13, color: '#303133' }],
      () => viewNodeLog(sub, { type: 'CLEAN_RULE', nodeId: clean.length ? (clean[0].ruleName || clean[0].ruleType) : '', name: '清洗规则' }), '清洗规则'))
    if (hasClean) {
      cx += STEP
      lines.push({ d: 'M' + (cx - STEP + NW) + ',' + y + ' L' + cx + ',' + y, dur: '2.2s' })
      nodes.push(fgNode(cx, y - 29, NW, NH, '#f5f0ff', '#d3c6f0',
        [{ v: '清洗结果表', y: 18, size: 12, color: '#8e44ad' },
         { v: clipText(connName(sub.cleanConnId) + '.' + sub.cleanTable, 22), y: 41, size: 12, color: '#303133' }],
        () => viewNodeLog(sub, { type: 'TABLE', nodeId: sub.cleanTable || '', name: '清洗结果表' }), '清洗结果表'))
    }
    cx += STEP
    lines.push({ d: 'M' + (cx - STEP + NW) + ',' + y + ' L' + cx + ',' + y, dur: '2.2s' })
    const quals = sub.qualityRules || []
    nodes.push(fgNode(cx, y - 29, NW, NH, '#fef0f0', '#fbc4c4',
      [{ v: '质量检查', y: 18, size: 12, color: '#f56c6c' },
       { v: quals.length ? clipText(quals.map(q => q.ruleName || q.ruleType).join('、'), 15) : '无', y: 41, size: 13, color: '#303133' }],
      () => viewNodeLog(sub, { type: 'QUALITY_RULE', nodeId: quals.length ? (quals[0].ruleName || quals[0].ruleType) : '', name: '质量规则' }), '质量检查'))
    cx += STEP
    lines.push({ d: 'M' + (cx - STEP + NW) + ',' + y + ' L' + cx + ',' + y, dur: '2.2s' })
    nodes.push(fgNode(cx, y - 29, NW, NH, '#ecf5ff', '#a0cfff',
      [{ v: '目标数据源', y: 18, size: 12, color: '#79bbff' },
       { v: clipText(dstBrief(sub), 15), y: 41, size: 13, color: '#303133' }],
      () => viewNodeLog(sub, { type: 'TABLE', nodeId: sub.dstTable || '', name: '目标数据源' }), '目标数据源'))
    cx += STEP
    lines.push({ d: 'M' + (cx - STEP + NW) + ',' + y + ' L' + cx + ',' + y, dur: '2.2s' })
    nodes.push(fgNode(cx, y - 29, NW, NH, '#ecf5ff', '#a0cfff',
      [{ v: '目标表', y: 18, size: 12, color: '#79bbff' },
       { v: clipText(sub.dstTable || '-', 15), y: 41, size: 13, color: '#303133' }],
      () => viewNodeLog(sub, { type: 'TABLE', nodeId: sub.dstTable || '', name: '目标表' }), '目标表'))
    // lane 末尾线
    if (N > 1) {
      lines.push({ d: 'M' + (cx + NW) + ',' + y + ' L' + END_X + ',' + y, dur: '2.4s' })
    } else {
      // 单源: 直接连到结束节点左侧(不留多余母线空段)
      lines.push({ d: 'M' + (cx + NW) + ',' + y + ' L' + (END_X - 20) + ',' + y, dur: '2.4s' })
    }
  })
  // 汇聚母线 + 结束
  let ex
  if (N > 1) {
    lines.push({ d: 'M' + END_X + ',' + (Y0 - 24) + ' L' + END_X + ',' + (Y0 + (N - 1) * GAP + 24), dur: '3.0s' })
    lines.push({ d: 'M' + END_X + ',' + yc + ' L' + (END_X + 40) + ',' + yc, dur: '2.0s' })
    ex = END_X + 40
  } else {
    ex = END_X - 20
  }
  nodes.push(fgNode(ex, yc - 26, 96, 52, '#fef0f0', '#fbc4c4',
    [{ v: '结束', y: 19, size: 14, color: '#f56c6c' }],
    () => viewNodeLog(sub, { type: 'SUBTASK', nodeId: sub.id, name: '结束' }), '结束'))
  return { nodes, lines, width: ex + 96 + 40, height: Y0 + (N - 1) * GAP + 58 + 60 }
}
const fg = (sub) => flowGraph(sub)

function viewNodeLog(sub, node) {
  if (logDialog.loading) return
  if (!sub || !sub.id) {
    logDialog.open = true
    logDialog.title = (sub ? sub.jobName : '子任务') + ' · ' + node.name
    logDialog.rows = []
    logDialog.loading = false
    return
  }
  // 子任务节点日志的 job_id 即子任务 id, 故按 subtaskId 过滤
  const params = {
    jobId: sub.id,
    pageNum: 1,
    pageSize: 10
  }
  // SUBTASK 类型日志 subtask_id 为 NULL(只记 job_id), 传 subtaskId 会过滤掉, 故跳过
  if (node.type !== 'SUBTASK') params.subtaskId = sub.id
  if (node.type) params.nodeType = node.type
  if (node.nodeId) params.nodeId = node.nodeId
  logDialog.open = true
  logDialog.title = (sub.jobName || '子任务') + ' · ' + node.name
  logDialog.rows = []
  logDialog.loading = true
  listEtlJobLog(params).then(res => {
    const rows = (res.data && (res.data.rows || res.data.list)) || res.rows || []
    logDialog.rows = rows
    logDialog.loading = false
  }).catch(() => {
    logDialog.loading = false
  })
}

let lastCronCheck = { v: null, t: 0 }
function checkCron() {
  if (!form.scheduleCron) { form._nextTime = ''; return }
  if (cronInvalidReason(form.scheduleCron)) { form._nextTime = ''; return }
  const now = Date.now()
  if (lastCronCheck.v === form.scheduleCron && now - lastCronCheck.t < 600) return
  lastCronCheck = { v: form.scheduleCron, t: now }
  cronNext(form.scheduleCron).then(res => {
    form._nextTime = res.data || ''
  }).catch(() => {
    form._nextTime = ''
  })
}

let cronTimer = null
function onCronGenChange() {
  clearTimeout(cronTimer)
  cronTimer = setTimeout(() => checkCron(), 400)
}

const cronDescText = computed(() => {
  if (!form.scheduleCron) return '请输入或点击"可视化"配置 cron 表达式'
  const reason = cronInvalidReason(form.scheduleCron)
  if (reason) return reason
  return describeCron(form.scheduleCron)
})
const cronDescInvalid = computed(() => !!form.scheduleCron && cronInvalidReason(form.scheduleCron) !== null)

let cronInputTimer = null
function onCronTextInput() {
  clearTimeout(cronInputTimer)
  cronInputTimer = setTimeout(() => checkCron(), 500)
}

function onCronInput(val) {
  if (typeof val !== 'string') return
  form.scheduleCron = val
  clearTimeout(cronTimer)
  cronTimer = setTimeout(() => checkCron(), 400)
}

function openCron() {
  cronBackup.value = form.scheduleCron || ''
  cronOpen.value = true
}

function cronConfirm() {
  cronOpen.value = false
  checkCron()
}

function cronCancel() {
  form.scheduleCron = cronBackup.value
  cronOpen.value = false
  checkCron()
}

function nextStep(cur) {
  if (cur === 0) {
    if (!form.jobName) { proxy.$modal.msgWarning('请填写任务名称'); return }
    if (!form.children.length) addSubtask()
    activeStep.value = 1
  }
}

function collectSubtask(sub) {
  const rows = validSrcRows(sub)
  const nonTableSrc = (sub.srcType || 'TABLE') !== 'TABLE'
  // 清洗结果映射(按源表分组): 源表字段 → 结果表字段 (fieldMapping)
  const maps = rows
    .map(r => ({ srcTable: r.srcTable, maps: (r.maps || []).filter(m => m.srcField || m.dstField) }))
    .filter(g => g.maps.length)
  // 目标映射(平铺): 结果表/源表字段 → 目标字段 (dstMapping)
  const dstMaps = (sub.dstMaps || []).filter(m => m.srcField || m.dstField)
  // 清洗规则: 表源按表分组; 非表源(Excel/API)为任务级规则(归属虚拟源行)
  const stripCfg = arr => (arr || []).map(x => ({ ruleName: x.ruleName, ruleType: x.ruleType, ruleConfig: x.ruleConfig })).filter(x => x.ruleName || x.ruleType)
  const cleanRules = nonTableSrc
      ? (sub.srcRows?.[0]?.cleanRules || []).filter(x => x.ruleName || x.ruleType).length
      ? [{ srcTable: '', rules: stripCfg(sub.srcRows[0].cleanRules) }]
      : []
    : rows
        .map(r => ({ srcTable: r.srcTable, rules: stripCfg(r.cleanRules) }))
        .filter(g => g.rules.length)
  const hasClean = !!(sub.cleanConnId && sub.cleanSchema && sub.cleanTable)
  // 无结果表时: 目标映射直接作为 源→目标 字段映射(fieldMapping)
  const effectiveMaps = hasClean ? maps : (dstMaps.length ? [{ srcTable: rows[0]?.srcTable || '', maps: dstMaps }] : [])
  const srcAuth = sub._srcAuth || { type: 'NONE' }
  const dstAuth = sub._dstAuth || { type: 'NONE' }
  return {
    id: sub.id,
    jobName: sub.jobName,
    jobType: sub.jobType,
    kettleType: 'TRANS',
    parentId: form.id,
    srcType: sub.srcType || 'TABLE',
    srcConnId: sub.srcType === 'TABLE' ? sub.srcConnId : null,
    srcSchema: sub.srcSchema,
    srcTable: rows?.[0]?.srcTable,
    srcWhere: rows?.[0]?.srcWhere,
    srcExcelPath: sub.srcExcelPath || null,
    srcExcelSheet: sub.srcExcelSheet || null,
    srcExcelHeader: sub.srcExcelHeader == null ? 0 : sub.srcExcelHeader,
    srcExcelDataRow: sub.srcExcelDataRow ?? null,
    srcExcelColFrom: sub.srcExcelColFrom ?? null,
    srcExcelColTo: sub.srcExcelColTo ?? null,
    srcApiUrl: sub.srcApiUrl || null,
    srcApiMethod: sub.srcApiMethod || 'GET',
    srcApiHeaders: sub.srcApiHeaders || null,
    srcApiBody: sub.srcApiBody || null,
    srcApiDataPath: sub.srcApiDataPath || null,
    srcApiPageField: sub.srcApiPageField || null,
    srcApiPageSize: sub.srcApiPageSize || 0,
    srcApiAuth: srcAuth.type === 'TOKEN' ? JSON.stringify(srcAuth) : null,
    srcList: rows.length ? JSON.stringify(rows.map(r => ({ srcTable: r.srcTable, srcWhere: r.srcWhere, joinType: r.joinType || 'UNION', joinField: r.joinField || '', joinRef: r.joinRef || '', srcConnId: r.srcConnId || null, srcSchema: r.srcSchema || null }))) : null,
    fieldMapping: effectiveMaps.length ? JSON.stringify(effectiveMaps) : null,
    cleanRules: cleanRules.length ? JSON.stringify(cleanRules) : null,
    qualityRules: sub.qualityRules?.length ? JSON.stringify(sub.qualityRules) : null,
    cleanConnId: sub.cleanConnId,
    cleanSchema: sub.cleanSchema,
    cleanTable: sub.cleanTable,
    cleanWriteMode: sub.cleanWriteMode || 'INSERT',
    dstConnId: sub.dstType === 'TABLE' ? sub.dstConnId : null,
    dstType: sub.dstType || 'TABLE',
    dstSchema: sub.dstSchema,
    dstTable: sub.dstTable,
    dstExcelPath: sub.dstExcelPath || null,
    dstExcelSheet: sub.dstExcelSheet || null,
    dstApiUrl: sub.dstApiUrl || null,
    dstApiMethod: sub.dstApiMethod || 'POST',
    dstApiHeaders: sub.dstApiHeaders || null,
    dstApiBodyTpl: sub.dstApiBodyTpl || null,
    dstApiAuth: dstAuth.type === 'TOKEN' ? JSON.stringify(dstAuth) : null,
    dstMapping: dstMaps.length ? JSON.stringify(dstMaps) : null,
    dstWriteMode: sub.dstWriteMode,
    dstReplaceKey: sub.dstReplaceKey || null,
    speedChannel: sub.speedChannel,
    batchSize: sub.batchSize,
    incrField: sub.incrField || null,
    incrMode: sub.incrMode || 'ID',
    incrLastValue: sub.incrLastValue || null,
    retryCount: sub.retryCount == null ? 0 : sub.retryCount,
    retryInterval: sub.retryInterval == null ? 30 : sub.retryInterval,
    errorLimitPercent: sub.errorLimitPercent == null ? 0 : sub.errorLimitPercent,
    errorLimitCount: sub.errorLimitCount == null ? 0 : sub.errorLimitCount,
    status: '0',
    depIds: (sub.depIds || []).join(',') || null
  }
}

// 步骤条点击跳转: 向后跳转需前置步骤满足, 向前跳转直接允许
function goStep(i) {
  if (i === activeStep.value) return
  if (i > activeStep.value) {
    if (i >= 1 && !form.jobName) { proxy.$modal.msgWarning('请先填写任务名称'); activeStep.value = 0; return }
    if (i >= 2 && !form.children.length) { proxy.$modal.msgWarning('请先添加子任务'); activeStep.value = 1; return }
    if (i >= 3) {
      if (!form.children.length) { proxy.$modal.msgWarning('请先添加子任务'); activeStep.value = 1; return }
      if (form.scheduleEnabled === '1' && cronInvalidReason(form.scheduleCron)) {
        proxy.$modal.msgWarning('调度 Cron 表达式无效: ' + cronInvalidReason(form.scheduleCron)); activeStep.value = 2; return
      }
    }
  }
  activeStep.value = i
}

function submitForm(saveAndRun = false) {
  if (!form.jobName) { proxy.$modal.msgWarning('请填写任务名称'); activeStep.value = 0; return }
  if (!form.children.length) { proxy.$modal.msgWarning('请至少添加一个子任务'); activeStep.value = 1; return }
  for (const sub of form.children) {
    if (!sub.jobName) { proxy.$modal.msgWarning('存在未命名的子任务'); activeStep.value = 1; return }
    const srcType = sub.srcType || 'TABLE'
    const dstType = sub.dstType || 'TABLE'
    if (srcType === 'TABLE') {
      if (!sub.srcConnId) { proxy.$modal.msgWarning('子任务「' + sub.jobName + '」未选择源数据源'); activeStep.value = 1; return }
      const hasTable = validSrcRows(sub).length
      if (!hasTable) { proxy.$modal.msgWarning('子任务「' + sub.jobName + '」未配置源表'); activeStep.value = 1; return }
    } else if (srcType === 'EXCEL') {
      if (!sub.srcExcelPath) { proxy.$modal.msgWarning('子任务「' + sub.jobName + '」未配置源Excel文件路径'); activeStep.value = 1; return }
    } else if (srcType === 'API') {
      if (!sub.srcApiUrl) { proxy.$modal.msgWarning('子任务「' + sub.jobName + '」未配置源API地址'); activeStep.value = 1; return }
    }
    if (dstType === 'TABLE') {
      if (!sub.dstConnId || !sub.dstTable) { proxy.$modal.msgWarning('子任务「' + sub.jobName + '」未配置目标数据源或目标表'); activeStep.value = 1; return }
    } else if (dstType === 'EXCEL') {
      if (!sub.dstExcelPath) { proxy.$modal.msgWarning('子任务「' + sub.jobName + '」未配置目标Excel文件路径'); activeStep.value = 1; return }
    } else if (dstType === 'API') {
      if (!sub.dstApiUrl) { proxy.$modal.msgWarning('子任务「' + sub.jobName + '」未配置目标API地址'); activeStep.value = 1; return }
      if (sub._dstAuth?.type === 'TOKEN' && !sub._dstAuth.tokenUrl) { proxy.$modal.msgWarning('子任务「' + sub.jobName + '」TOKEN 鉴权未配置 Token 地址'); activeStep.value = 1; return }
    }
    if (srcType === 'API' && sub._srcAuth?.type === 'TOKEN' && !sub._srcAuth.tokenUrl) {
      proxy.$modal.msgWarning('子任务「' + sub.jobName + '」TOKEN 鉴权未配置 Token 地址'); activeStep.value = 1; return
    }
    // 目标非表(Excel/API)且配置了清洗/质量规则(多段管线): 需要清洗结果表作为中间承接
    const dstNonTable = dstType !== 'TABLE'
    const hasPipelineRules = cleanRuleCount(sub) > 0 || (sub.qualityRules || []).length > 0
    if (dstNonTable && hasPipelineRules && !(sub.cleanConnId && sub.cleanSchema && sub.cleanTable)) {
      proxy.$modal.msgWarning('子任务「' + sub.jobName + '」目标为Excel/API 的多段管线需配置清洗结果表作为中间承接'); activeStep.value = 1; return
    }
    // 六点重构约束: 多源(LEFT JOIN / UNION ALL)时 清洗规则与清洗结果必须同时配置
    const rows = validSrcRows(sub)
    const multiRel = rows.length > 1 && rows.some(r => r.joinType === 'UNION' || r.joinType === 'LEFT_JOIN' || r.joinType === 'JOIN')
    const hasCleanRule = cleanRuleCount(sub) > 0
    const hasCleanTable = !!(sub.cleanConnId && sub.cleanSchema && sub.cleanTable)
    if (multiRel && !(hasCleanRule && hasCleanTable)) {
      proxy.$modal.msgWarning('子任务「' + sub.jobName + '」为多源合并/关联场景, 清洗规则与清洗结果表必须同时配置'); activeStep.value = 1; return
    }
    // 清洗结果映射(多源汇总)时需配置结果表字段
    const cleanMaps = rows.reduce((n, r) => n + (r.maps || []).filter(m => m.srcField).length, 0)
    if (hasCleanTable && cleanMaps > 0 && !sub.cleanSchema) { proxy.$modal.msgWarning('子任务「' + sub.jobName + '」清洗结果库未选择'); activeStep.value = 1; return }
  }
  submitting.value = true
  const parentPayload = {
    id: form.id,
    jobName: form.jobName,
    remark: form.remark,
    jobType: 'KETTLE',
    parentId: form.parentId || '0',
    scheduleEnabled: form.scheduleEnabled,
    scheduleCron: form.scheduleCron,
    status: '0'
  }
  const doSave = (parentId) => {
    const subs = form.children.map(s => collectSubtask({ ...s, parentId }))
    const saves = subs.map(s => s.id ? updateEtlJob(s) : addEtlJob(s))
    return Promise.all(saves)
  }
  const req = form.id ? updateEtlJob(parentPayload).then(() => form.id) : addEtlJob(parentPayload)
  req.then(res => {
    const id = res && typeof res === 'object' ? res.data : res
    form.id = id
    return doSave(id)
  }).then(() => {
    submitting.value = false
    proxy.$modal.msgSuccess('保存成功')
    if (saveAndRun) {
      runEtlJob(form.id).then(r => {
        proxy.$modal.msgSuccess('任务已提交执行, 可到任务列表查看执行日志')
        router.push('/etl/job')
      }).catch(e => {
        proxy.$modal.msgError('提交执行失败: ' + (e?.msg || e?.message || '未知错误'))
        router.push('/etl/job')
      })
    } else {
      router.push('/etl/job')
    }
  }).catch(e => {
    submitting.value = false
    proxy.$modal.msgError('保存失败: ' + (e?.msg || e?.message || '未知错误'))
  })
}

function loadDetail(id) {
  getEtlJob(id).then(res => {
    const p = res.data
    form.id = p.id
    form.jobName = p.jobName
    form.remark = p.remark
    form.scheduleEnabled = p.scheduleEnabled || '0'
    form.scheduleCron = p.scheduleCron || ''
    form.runMode = p.runMode || 'SEQUENTIAL'
    form.notifyType = p.notifyType || 'NONE'
    form.notifyMails = p.notifyMails || ''
    form.notifyOn = p.notifyOn || 'FAILED'
    form.parentId = p.parentId ? String(p.parentId) : '0'
    checkCron()
    const children = p.children || []
    children.forEach(c => {
      const sub = newSubtask()
      sub.id = c.id
      sub.jobName = c.jobName
      sub.jobType = c.jobType || 'KETTLE'
      sub.srcType = c.srcType || 'TABLE'
      sub.srcConnId = c.srcConnId
      sub.srcSchema = c.srcSchema
      sub.srcExcelPath = c.srcExcelPath || ''
      sub.srcExcelSheet = c.srcExcelSheet || ''
      sub.srcExcelHeader = c.srcExcelHeader == null ? 0 : c.srcExcelHeader
      sub.srcExcelDataRow = c.srcExcelDataRow ?? undefined
      sub.srcExcelColFrom = c.srcExcelColFrom ?? undefined
      sub.srcExcelColTo = c.srcExcelColTo ?? undefined
      sub.srcApiUrl = c.srcApiUrl || ''
      sub.srcApiMethod = c.srcApiMethod || 'GET'
      sub.srcApiHeaders = c.srcApiHeaders || ''
      sub.srcApiBody = c.srcApiBody || ''
      sub.srcApiDataPath = c.srcApiDataPath || ''
      sub.srcApiPageField = c.srcApiPageField || ''
      sub.srcApiPageSize = c.srcApiPageSize || 0
      if (c.srcApiAuth) {
        try { sub._srcAuth = { type: 'NONE', tokenUrl: '', tokenMethod: 'POST', tokenHeaders: '', tokenBody: '', tokenPath: 'token', ...JSON.parse(c.srcApiAuth) } } catch (e) { /* ignore */ }
      }
      sub.dstType = c.dstType || 'TABLE'
      sub.dstConnId = c.dstConnId
      sub.dstSchema = c.dstSchema
      sub.dstTable = c.dstTable
      sub.dstExcelPath = c.dstExcelPath || ''
      sub.dstExcelSheet = c.dstExcelSheet || ''
      sub.dstApiUrl = c.dstApiUrl || ''
      sub.dstApiMethod = c.dstApiMethod || 'POST'
      sub.dstApiHeaders = c.dstApiHeaders || ''
      sub.dstApiBodyTpl = c.dstApiBodyTpl || ''
      if (c.dstApiAuth) {
        try { sub._dstAuth = { type: 'NONE', tokenUrl: '', tokenMethod: 'POST', tokenHeaders: '', tokenBody: '', tokenPath: 'token', ...JSON.parse(c.dstApiAuth) } } catch (e) { /* ignore */ }
      }
      sub.dstWriteMode = c.dstWriteMode || 'INSERT'
      sub.speedChannel = c.speedChannel || 3
      sub.batchSize = c.batchSize || 1024
      sub.cleanConnId = c.cleanConnId
      sub.cleanSchema = c.cleanSchema
      sub.cleanTable = c.cleanTable
      sub.cleanWriteMode = c.cleanWriteMode || 'INSERT'
      sub.incrField = c.incrField || ''
      sub.incrMode = c.incrMode || 'ID'
      sub.incrLastValue = c.incrLastValue || ''
      sub.retryCount = c.retryCount == null ? 0 : c.retryCount
      sub.retryInterval = c.retryInterval == null ? 30 : c.retryInterval
      sub.errorLimitPercent = c.errorLimitPercent == null ? 0 : c.errorLimitPercent
      sub.errorLimitCount = c.errorLimitCount == null ? 0 : c.errorLimitCount
      sub.depIds = c.depIds ? String(c.depIds).split(',').filter(Boolean) : []
      sub.qualityRules = c.qualityRules ? JSON.parse(c.qualityRules) : []
      if (c.dstMapping) {
        try {
          const dm = JSON.parse(c.dstMapping)
          sub.dstMaps = Array.isArray(dm) ? dm.filter(m => m.srcField || m.dstField) : [{ srcField: '', dstField: '' }]
        } catch (e) { sub.dstMaps = [{ srcField: '', dstField: '' }] }
      }
      let rows = []
      if (c.srcList) {
        try { rows = JSON.parse(c.srcList) } catch (e) { rows = [] }
      }
      if (!rows.length && c.srcTable) rows = [{ srcTable: c.srcTable, srcWhere: c.srcWhere }]
      if (!rows.length) rows = [{ srcTable: '', srcWhere: '' }]
      rows = rows.map(r => ({ srcTable: r.srcTable || '', srcWhere: r.srcWhere || '', joinType: r.joinType || 'UNION', joinField: r.joinField || '', joinRef: r.joinRef || '', srcConnId: r.srcConnId || undefined, srcSchema: r.srcSchema || undefined, _srcRowDbs: [], maps: [{ srcField: '', dstField: '' }], cleanRules: [] }))
      // 字段映射回显: 分组格式(新) / 平铺(旧, 归属第一个源表)
      let mapsByTable = null
      if (c.fieldMapping) {
        try {
          const fm = JSON.parse(c.fieldMapping)
          if (Array.isArray(fm) && fm.length && fm[0].srcTable !== undefined) {
            mapsByTable = {}
            fm.forEach(g => { if (g.srcTable !== undefined && g.srcTable !== null) mapsByTable[g.srcTable] = g.maps || [] })
          } else {
            mapsByTable = {}
            if (rows[0]) mapsByTable[rows[0].srcTable] = fm.filter(m => m.srcField || m.dstField)
          }
        } catch (e) { mapsByTable = null }
      }
      if (mapsByTable) {
        rows.forEach(r => {
          if (mapsByTable[r.srcTable] && mapsByTable[r.srcTable].length) {
            r.maps = mapsByTable[r.srcTable]
          }
        })
      }
      // 关联方式回显(joinType/joinField/joinRef 已在 srcList 中)
      rows.forEach(r => {
        if (r.joinType === undefined) r.joinType = 'UNION'
        if (r.joinField === undefined) r.joinField = ''
        if (r.joinRef === undefined) r.joinRef = ''
      })
      // 清洗规则回显: 分组格式(新) / 平铺(旧, 归属第一个源表)
      if (c.cleanRules) {
        try {
          const cr = JSON.parse(c.cleanRules)
          if (Array.isArray(cr) && cr.length && cr[0].srcTable !== undefined) {
            const crByTable = {}
            cr.forEach(g => { if (g.srcTable !== undefined && g.srcTable !== null) crByTable[g.srcTable] = g.rules || [] })
            rows.forEach(r => {
              if (crByTable[r.srcTable] !== undefined) r.cleanRules = crByTable[r.srcTable]
            })
          } else if (Array.isArray(cr)) {
            if (rows[0]) rows[0].cleanRules = cr.filter(x => x.ruleName || x.ruleType)
          }
        } catch (e) { /* ignore */ }
      }
      // 清洗规则可视化配置模型初始化(在 rows 回显后)
      rows.forEach(rows2 => {
        ;(rows2.cleanRules || []).forEach(rl => {
          if (!rl._cfg) {
            try { rl._cfg = JSON.parse(rl.ruleConfig || '{}') } catch (e) { rl._cfg = {} }
          }
        })
      })
      sub.srcRows = rows
      form.children.push(sub)
    })
    // 空总任务编辑时补一个空子任务
    if (!form.children.length) addSubtask()
    // 回显后按依赖顺序触发 数据源→库→表 联动
    setTimeout(() => {
      form.children.forEach(sub => {
        if ((sub.srcType || 'TABLE') === 'EXCEL' && sub.srcExcelPath) {
          loadSheets(sub, 'src')
        }
        if ((sub.dstType || 'TABLE') === 'EXCEL' && sub.dstExcelPath) {
          loadSheets(sub, 'dst')
        }
        const srcSchema = sub.srcSchema
        if (sub.srcConnId) {
          onConnChange(sub, 'src')
          if (srcSchema) {
            setTimeout(() => {
              sub.srcSchema = srcSchema
              onSchemaChange(sub, 'src')
            }, 500)
          }
        }
        const dstSchema = sub.dstSchema
        if (sub.dstConnId) {
          onConnChange(sub, 'dst')
          if (dstSchema) {
            setTimeout(() => {
              sub.dstSchema = dstSchema
              onSchemaChange(sub, 'dst')
            }, 900)
          }
        }
        const cleanSchema = sub.cleanSchema
        if (sub.cleanConnId) {
          onConnChange(sub, 'clean')
          if (cleanSchema) {
            setTimeout(() => {
              sub.cleanSchema = cleanSchema
              onSchemaChange(sub, 'clean')
              if (sub.cleanTable) onCleanTableChange(sub)
            }, 1300)
          }
        }
      })
    }, 100)
  })
}

onMounted(() => {
  listAllConn().then(res => { connList.value = res.data || res.rows || [] })
  loadEtlFiles()
  loadDirOptions()
  if (route.query.id) {
    isEdit.value = true
    loadDetail(route.query.id)
  } else {
    form.parentId = route.query.parentId ? String(route.query.parentId) : '0'
    addSubtask()
  }
})

/** 统一目录文件列表(Excel/API 桥接, 相对路径) */
const fileList = ref([])
function loadEtlFiles() {
  request({ url: '/etl/file/list', method: 'get' }).then(res => {
    fileList.value = (res.data || []).map(f => ((f && f.name) || '').replace(/\\/g, '/'))
  }).catch(() => { fileList.value = [] })
}

/** 上传文件到服务端统一目录, 成功后回填相对路径 */
function uploadEtlFile(opt, sub, role) {
  const fd = new FormData()
  fd.append('file', opt.file)
  request({ url: '/etl/file/upload', method: 'post', data: fd }).then(res => {
    proxy.$modal.msgSuccess('上传成功')
    const name = ((res.data && res.data.name) || opt.file.name || '').replace(/\\/g, '/')
    if (role === 'src') {
      sub.srcExcelPath = name
    } else {
      sub.dstExcelPath = name
    }
    loadEtlFiles()
  }).catch(e => {
    proxy.$modal.msgError('上传失败: ' + (e?.msg || e?.message || '未知错误'))
  })
}
</script>

<style lang="scss" scoped>
.form-steps { .el-step { cursor: pointer; } }
.rule-cfg-inline {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  width: 100%;
  .cfg-w1 { width: 110px; }
  .cfg-w2 { width: 200px; }
  .cfg-w3 { width: 100%; }
  .cfg-w1.num, .cfg-w2.num { width: 90px; }
  .cfg-tip {
    color: var(--imp-text-secondary);
    font-size: 12px;
    white-space: nowrap;
    flex-shrink: 0;
  }
}
.excel-cell-preview {
  font-size: 12px;
  color: #67c23a;
  line-height: 20px;
  word-break: break-all;
  margin-top: 2px;
  &.dim { color: var(--imp-text-secondary); }
}
.etl-parent-form {
  .cron-row {
    display: flex;
    align-items: center;
    gap: 12px;
    .cron-desc {
      font-size: 13px;
      color: #529b2e;
      background: #f0f9eb;
      border: 1px solid #e1f3d8;
      border-radius: 6px;
      padding: 6px 12px;
      white-space: nowrap;
      flex-shrink: 0;
      line-height: 1.5;
      &.invalid { color: #f56c6c; background: #fef0f0; border-color: #fbc4c4; }
    }
  }
  .panel-heading { display: flex; align-items: center; gap: 12px; }
  .form-steps { max-width: 900px; margin: 8px auto 28px; }
  .step-panel { max-width: 1150px; margin: 0 auto; }
  .step-actions { margin-top: 24px; text-align: center; }
  .form-tip { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6; }
  .subtask-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
  .subtask-card {
    border: 1px solid var(--el-border-color-light);
    border-radius: 8px;
    padding: 14px 18px 8px;
    margin-bottom: 18px;
    background: #fafbfc;
    .subtask-head {
      display: flex; justify-content: space-between; align-items: center;
      margin-bottom: 10px; cursor: pointer;
      .subtask-title { font-weight: 600; display: flex; align-items: center; gap: 6px; }
      .collapse-icon { cursor: pointer; color: var(--el-text-color-secondary); }
    }
    :deep(.el-divider__text) { font-size: 13px; color: var(--el-color-primary); font-weight: 500; }
  }
  .mini-steps {
    display: flex; gap: 6px; margin: 4px 0 14px; flex-wrap: wrap;
    .mini-step {
      display: flex; align-items: center; gap: 6px;
      padding: 5px 12px; border-radius: 16px; cursor: pointer;
      border: 1px solid var(--el-border-color);
      background: #fff; color: var(--el-text-color-regular);
      font-size: 13px; user-select: none; transition: all .2s;
      .mini-step-idx {
        width: 18px; height: 18px; border-radius: 50%;
        background: var(--el-border-color); color: #fff;
        font-size: 11px; display: inline-flex; align-items: center; justify-content: center;
      }
      .mini-step-opt {
        font-size: 10px; color: var(--el-text-color-secondary);
        border: 1px dashed var(--el-border-color);
        border-radius: 8px; padding: 0 5px; line-height: 16px;
        background: #fff; white-space: nowrap;
      }
      &:hover { border-color: var(--el-color-primary); color: var(--el-color-primary); }
      &.active {
        border-color: var(--el-color-primary); background: var(--el-color-primary); color: #fff;
        .mini-step-idx { background: rgba(255,255,255,.3); }
      }
      &.done {
        border-color: var(--el-color-success); color: var(--el-color-success);
        .mini-step-idx { background: var(--el-color-success); }
      }
    }
  }
  .ro-summary {
    display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
    padding: 10px 12px; margin-bottom: 10px;
    background: #f5f7fa; border: 1px dashed var(--el-border-color); border-radius: 6px;
    .ro-label { font-size: 12px; color: var(--el-text-color-secondary); }
  }
  /* ===== 清洗面板折叠 ===== */
  .clean-collapse {
    border: 1px solid var(--el-border-color-lighter); border-radius: 8px; overflow: hidden;
    :deep(.el-collapse-item__header) {
      font-size: 13px; font-weight: 600; color: var(--el-color-primary); height: 42px;
      background: #fafcff; padding-left: 12px;
    }
    :deep(.el-collapse-item__content) { padding: 10px 14px; }
    .map-group { margin-bottom: 10px; }
  }
  .ro-block {
    padding: 6px 10px; margin-bottom: 6px;
    background: #fafafa; border-radius: 4px;
    .ro-block-title { font-size: 12px; font-weight: 600; color: var(--el-text-color-regular); margin-bottom: 4px; }
    .ro-none { font-size: 12px; color: var(--el-text-color-placeholder); }
  }
  .src-row, .rule-row { margin-bottom: 8px; }
  .map-group {
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 6px; padding: 8px 10px; margin-bottom: 10px; background: #fff;
    .map-group-title {
      font-size: 13px; font-weight: 600; color: var(--el-color-primary);
      display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px;
    }
  }
  .xml-preview { font-family: Consolas, Monaco, monospace; font-size: 12px; }
  .xml-loading { color: var(--el-text-color-secondary); font-size: 13px; padding: 8px 0; }
  .designer-collapse { margin-top: 12px; }
  .preview-desc { margin-bottom: 4px; }
  .empty-subtask { padding: 30px 0; }
  .next-time { color: var(--el-color-success); font-weight: 500; }
  .log-detail-pre {
    white-space: pre-wrap; word-break: break-all; max-height: 480px; overflow: auto;
    background: #f5f7fa; border: 1px solid var(--el-border-color); border-radius: 6px;
    padding: 12px; font-size: 12px; line-height: 1.6; font-family: Consolas, Monaco, monospace;
  }

  /* ===== 执行过程图 ===== */
  .flow-canvas { overflow-x: auto; }
.flow-svg { width: 100%; min-width: 1180px; display: block; height: 196px; }
.flow-svg-node { cursor: pointer; }
.flow-svg-node rect { transition: filter .15s; }
.flow-svg-node:hover rect { filter: brightness(.96); }
.flow-canvas {
    background: #fff; border: 1px solid var(--el-border-color-lighter); border-radius: 8px;
    padding: 16px 14px 10px; overflow-x: auto;
  }
  .flow-top {
    display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 4px 0 10px;
  }
  .flow-lanes {
    display: flex; flex-direction: column; gap: 12px; padding: 6px 0;
    border-top: 1px dashed var(--el-border-color-lighter);
  }
  .flow-lane {
    display: flex; align-items: center; gap: 8px; flex-wrap: nowrap; min-width: max-content;
    padding: 8px 0;
  }
  .flow-end-row {
    display: flex; justify-content: center; padding: 10px 0 2px;
    border-top: 1px dashed var(--el-border-color-lighter);
  }
  .flow-node {
    border: 1.5px solid var(--el-border-color); border-radius: 8px;
    background: #fff; text-align: center; cursor: pointer; transition: all .15s;
    display: flex; flex-direction: column; justify-content: center; align-items: center;
    &:hover { border-color: var(--el-color-primary); box-shadow: 0 2px 8px rgba(64,158,255,.18); transform: translateY(-1px); }
  }
  .flow-start {
    width: 56px; height: 56px; border-radius: 50%;
    border-color: var(--el-color-success); color: var(--el-color-success);
    font-weight: 600; font-size: 13px; flex-shrink: 0;
  }
  .flow-box {
    width: 132px; min-height: 54px; padding: 6px 4px; flex-shrink: 0;
    .fn-title { font-size: 13px; font-weight: 600; color: var(--el-text-color-primary); }
    .fn-sub { font-size: 11px; color: var(--el-text-color-secondary); margin-top: 2px; max-width: 126px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  }
  .flow-stack {
    display: flex; flex-direction: column; gap: 4px; cursor: pointer; flex-shrink: 0;
    padding: 3px 4px; border: 1px dashed transparent; border-radius: 6px;
    &:hover { border-color: var(--el-color-warning); background: #fdf6ec; }
  }
  .flow-mini {
    border-color: var(--el-color-warning); background: #fdf6ec;
    min-width: 116px; padding: 4px 6px; border-radius: 6px;
    .fn-mini-title { font-size: 11px; font-weight: 600; color: #b88230; }
    .fn-mini-sub { font-size: 11px; color: var(--el-text-color-secondary); max-width: 110px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  }
  .flow-mini.flow-none { border-style: dashed; background: #fafafa; .fn-mini-sub { color: var(--el-text-color-placeholder); } }
  .join-row { margin-top: 6px; background: #fafcff; border-radius: 6px; padding: 4px 6px; }
  .join-ref-label { line-height: 28px; font-size: 12px; color: var(--el-text-color-secondary); }
  .join-main-tip { font-size: 12px; color: var(--el-color-success); line-height: 28px; }
  .flow-arrow {
    color: var(--el-text-color-secondary); font-size: 13px; flex-shrink: 0; user-select: none;
  }
}

.fm-divider { cursor: pointer; user-select: none; }
.fm-arrow { margin-right: 6px; color: var(--el-text-color-secondary); font-size: 12px; }

.sec-div { width: 100%; }
.sec-tip { margin: 0 0 0 12px !important; }
.sec-head {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  padding: 4px 0; margin: 16px 0 12px;
  .sec-title { font-size: 14px; font-weight: 600; color: var(--el-text-color-primary); }
  .form-tip { margin: 0; }
}
.fm-head {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  padding: 4px 0; margin: 16px 0 12px;
  .fm-title { font-size: 14px; font-weight: 600; color: var(--el-text-color-primary); cursor: pointer; user-select: none; }
  .fm-arrow { display: inline-block; margin-right: 6px; color: var(--el-text-color-secondary); font-size: 12px; transition: transform .2s; }
  .fm-arrow.open { transform: rotate(90deg); }
  .fm-btns { display: flex; align-items: center; gap: 8px; }
}
.map-group { max-height: 320px; overflow-y: auto; }
</style>
