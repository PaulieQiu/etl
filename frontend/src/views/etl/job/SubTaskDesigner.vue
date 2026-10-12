<template>
  <div class="subtask-designer">
    <!-- 步骤条: 与总任务子任务界面一致 -->
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
        <el-divider content-position="left">源表（可多个: UNION ALL 合并 / JOIN 关联组合, 支持折叠）</el-divider>
        <div v-for="(row, ri) in sub.srcRows" :key="ri" class="src-card" :class="{ folded: row._folded }">
          <div class="src-card-head">
            <div class="src-card-title">
              <span class="fold-ico" @click.stop="row._folded = !row._folded">
                <svg width="12" height="12" viewBox="0 0 12 12" :class="{ 'is-folded': row._folded }">
                  <path d="M2.5 4.5L6 8l3.5-3.5" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </span>
              <span class="src-idx">源表 {{ ri + 1 }}</span>
              <span v-if="ri === 0" class="main-badge">主表</span>
              <span class="src-card-summary">{{ row.srcTable || '未选择表' }} @ {{ connName(row.srcConnId) }}.{{ row.srcSchema || '库' }}</span>
            </div>
            <el-button v-if="sub.srcRows.length > 1" size="small" type="danger" link icon="Delete" @click="removeSrcRow(sub, ri)">删除</el-button>
          </div>
          <div v-show="!row._folded" class="src-card-body">
            <el-row :gutter="8">
              <el-col :span="6">
                <el-select v-model="row.srcConnId" filterable clearable placeholder="源数据源" style="width: 100%" @change="onRowConnChange(sub, row)">
                  <el-option v-for="c in connList" :key="c.id" :label="c.connName" :value="c.id" />
                </el-select>
              </el-col>
              <el-col :span="6">
                <el-select v-model="row.srcSchema" filterable clearable placeholder="源库" style="width: 100%" @change="onRowSchemaChange(sub, row)">
                  <el-option v-for="s2 in row._srcRowDbs || []" :key="s2" :label="s2" :value="s2" />
                </el-select>
              </el-col>
              <el-col :span="6">
                <el-select v-model="row.srcTable" filterable placeholder="源表" style="width: 100%" @change="onSrcTableChange(sub, row)">
                  <el-option v-for="t in row._srcRowTables || []" :key="t.tableName" :label="t.tableName" :value="t.tableName" />
                </el-select>
              </el-col>
              <el-col :span="6">
                <el-input v-model="row.srcWhere" placeholder="过滤条件, 如 status = '1'" />
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
        </div>
        <div class="src-add-bar">
          <el-button size="small" type="primary" plain icon="Plus" @click="addSrcRow(sub)">新增源表</el-button>
          <span class="form-tip">新增源表默认继承主表的数据源 / 数据库；跨数据源合并/关联时逐行修改。</span>
        </div>
      </template>

      <!-- 源: Excel 文件 -->
      <template v-else-if="sub.srcType === 'EXCEL'">
        <el-divider content-position="left">源 Excel 文件（统一目录管理）</el-divider>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="已上传文件">
              <el-select v-model="sub.srcExcelPath" filterable clearable placeholder="从统一目录选择(可输入相对路径)" style="width: 100%" allow-create @change="onSrcExcelPathChange">
                <el-option v-for="f in fileList" :key="f" :label="f" :value="f" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="工作表">
              <el-select v-model="sub.srcExcelSheet" filterable clearable placeholder="选择工作表" style="width: 100%" @change="onSrcSheetChange">
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

    <!-- ============ 面板1: 清洗规则 (规则明细 + 结果表 + 结果映射, 可折叠) ============ -->
    <div v-show="sub._activeMini === 1">
      <div class="ro-summary">
        <span class="ro-label">源</span>
        <el-tag size="small" type="info">{{ srcDesc(sub) }}</el-tag>
        <span class="ro-label">清洗映射</span>
        <el-tag size="small" type="info">{{ mapCount(sub) }} 条</el-tag>
        <span class="ro-label">清洗规则</span>
        <el-tag size="small" type="info">{{ cleanRuleCount(sub) }} 条</el-tag>
      </div>
      <el-collapse v-model="cleanCollapse" class="clean-collapse">
        <el-collapse-item title="清洗规则明细（按源表绑定, 可多个）" name="rules">
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

    <!-- ============ 面板2: 质量规则 ============ -->
    <div v-show="sub._activeMini === 2">
      <div class="ro-summary">
        <span class="ro-label">源</span>
        <el-tag size="small" type="info">{{ srcDesc(sub) }}</el-tag>
        <span class="ro-label">清洗映射</span>
        <el-tag size="small" type="info">{{ mapCount(sub) }} 条</el-tag>
        <span class="ro-label">清洗规则</span>
        <el-tag size="small" type="info">{{ cleanRuleCount(sub) }} 条</el-tag>
        <span class="ro-label">清洗结果</span>
        <el-tag size="small" type="info">{{ sub.cleanTable || '未配置' }}</el-tag>
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

    <!-- ============ 面板3: 目标 ============ -->
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
            <el-button size="small" type="primary" @click="emit('save')">保存</el-button>
            <el-button size="small" @click="emit('cancel')">取消</el-button>
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
              <el-select v-model="sub.dstExcelPath" filterable clearable allow-create placeholder="选择/输入目标文件" style="width: 100%" @change="onDstExcelPathChange">
                <el-option v-for="f in fileList" :key="f" :label="f" :value="f" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="7">
            <el-form-item label="工作表">
              <el-select v-model="sub.dstExcelSheet" filterable clearable placeholder="选择工作表" style="width: 100%" @change="onDstSheetChange">
                <el-option v-for="sh in sub._dstSheets" :key="sh.name" :label="sh.name" :value="sh.name" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="7">
            <el-form-item label="表头行">
              <el-input-number v-model="sub.dstExcelHeader" :min="0" :max="100" style="width: 100%" @change="rebuildDstExcelCols" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="起始列">
              <el-input-number v-model="sub.dstExcelColFrom" :min="1" :max="1000" style="width: 100%" @change="rebuildDstExcelCols" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="结束列">
              <el-input-number v-model="sub.dstExcelColTo" :min="1" :max="1000" style="width: 100%" @change="rebuildDstExcelCols" />
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

    <!-- ============ 面板4: 完成预览 ============ -->
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
            <marker id="sdFlowArrow" markerWidth="10" markerHeight="8" refX="9" refY="4" orient="auto">
              <path d="M0,0 L10,4 L0,8 z" fill="#8a94a6"></path>
            </marker>
          </defs>
          <path v-for="(l, li) in fg(sub).lines" :key="'fl' + li" :d="l.d" fill="none" stroke="#8a94a6" stroke-width="1.6" marker-end="url(#sdFlowArrow)"></path>
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
  </div>
</template>

<script setup name="SubTaskDesigner">
import { ref, computed, onMounted, getCurrentInstance } from 'vue'
import { listDatabases, listTables, listColumns, listEtlJobLog } from '@/api/etl/index'
import request from '@/utils/request'

const emit = defineEmits(['save', 'cancel'])
const props = defineProps({
  modelValue: { type: Object, required: true },
  connList: { type: Array, default: () => [] }
})

const { proxy } = getCurrentInstance()
const sub = computed(() => props.modelValue)

const miniSteps = ['源/表', '清洗规则', '质量规则', '目标', '完成预览']
const cleanRuleTypes = ['去空值', '去重复', '格式规范化', '字段映射', '单位换算', '默认值填充', '当前时间填充', 'UUID填充', '叶子标记', '范围校验', '枚举校验', '格式校验', '关联完整性', '非空校验', '唯一校验', 'SQL 清洗']
const qualityRuleTypes = ['非空校验', '唯一校验', '范围校验', '枚举校验', '格式校验', '关联完整性', '自定义 SQL']

// 清洗面板折叠状态（默认全部展开）
const cleanCollapse = ref(['rules', 'table', 'maps'])
// 字段映射折叠状态
const fmCollapsed = ref(false)

// ===== 统一目录文件 =====
const fileList = ref([])
function loadEtlFiles() {
  request({ url: '/etl/file/list', method: 'get' }).then(res => {
    fileList.value = (res.data || []).map(f => ((f && f.name) || '').replace(/\\/g, '/'))
  }).catch(() => { fileList.value = [] })
}
function uploadEtlFile(opt, subObj, role) {
  const fd = new FormData()
  fd.append('file', opt.file)
  request({ url: '/etl/file/upload', method: 'post', data: fd }).then(res => {
    proxy.$modal.msgSuccess('上传成功')
    const name = ((res.data && res.data.name) || opt.file.name || '').replace(/\\/g, '/')
    if (role === 'src') {
      subObj.srcExcelPath = name
    } else {
      subObj.dstExcelPath = name
    }
    loadEtlFiles()
  }).catch(e => {
    proxy.$modal.msgError('上传失败: ' + (e?.msg || e?.message || '未知错误'))
  })
}

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
      rebuildSrcExcelCols()
    } else {
      subObj._dstSheets = list
      if (list.length && !subObj.dstExcelSheet) subObj.dstExcelSheet = list[0].name
      const sh = list.find(x => x.name === subObj.dstExcelSheet) || list[0]
      subObj._dstSheetInfo = sh || null
      rebuildDstExcelCols()
    }
  }).catch(() => {
    if (role === 'src') subObj._srcSheetInfo = null
    else subObj._dstSheetInfo = null
  })
}
function onSrcExcelPathChange() {
  loadSheets(sub.value, 'src')
  validateExcelConfig(sub.value, 'src')
}
function onDstExcelPathChange() {
  loadSheets(sub.value, 'dst')
}
function onSrcSheetChange() {
  const sh = (sub.value._srcSheets || []).find(x => x.name === sub.value.srcExcelSheet)
  sub.value._srcSheetInfo = sh || null
  rebuildSrcExcelCols()
  validateExcelConfig(sub.value, 'src')
}
function onDstSheetChange() {
  const sh = (sub.value._dstSheets || []).find(x => x.name === sub.value.dstExcelSheet)
  sub.value._dstSheetInfo = sh || null
  rebuildDstExcelCols()
}
function rebuildSrcExcelCols() {
  if ((sub.value.srcType || 'TABLE') !== 'EXCEL') return
  sub.value._srcCols = excelColsFromSheet(sub.value, 'src', sub.value.srcExcelHeader, sub.value.srcExcelColFrom, sub.value.srcExcelColTo)
}
function rebuildDstExcelCols() {
  if ((sub.value.dstType || 'TABLE') !== 'EXCEL') return
  sub.value._dstCols = excelColsFromSheet(sub.value, 'dst', sub.value.dstExcelHeader, sub.value.dstExcelColFrom, sub.value.dstExcelColTo)
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
  if (isSrc) rebuildSrcExcelCols()
  return true
}

// ===== 清洗规则可视化配置 =====
function initRuleCfg(r) {
  if (!r) return
  if (!r._cfg || typeof r._cfg !== 'object') r._cfg = {}
  if (r.ruleConfig) {
    try {
      const parsed = JSON.parse(r.ruleConfig)
      if (parsed && typeof parsed === 'object') r._cfg = { ...r._cfg, ...parsed }
    } catch (e) { /* 非法 JSON 保留原样 */ }
  }
}
function onRuleTypeChange(r) {
  if (!r._cfg) r._cfg = {}
  // 切换类型时保留已填写的公共字段, 其余按新类型重建
  const kept = {}
  try {
    const prev = JSON.parse(r.ruleConfig || '{}')
    Object.assign(kept, prev)
  } catch (e) { /* ignore */ }
  r._cfg = { ...r._cfg, ...kept }
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

// ===== 源表行操作 =====
function addSrcRow(s) {
  const first = s.srcRows[0]
  s.srcRows.push({
    srcTable: '',
    srcWhere: '',
    joinType: 'UNION',
    joinField: '',
    joinRef: '',
    // 新增源表默认继承第一行的数据源/数据库
    srcConnId: first ? first.srcConnId : undefined,
    srcSchema: first ? first.srcSchema : undefined,
    _srcRowDbs: first && first._srcRowDbs ? [...first._srcRowDbs] : [],
    _srcRowTables: [],
    _folded: false,
    maps: [{ srcField: '', dstField: '' }],
    cleanRules: []
  })
}
function removeSrcRow(s, ri) {
  s.srcRows.splice(ri, 1)
}

// ===== 工具函数 =====
function connName(id) {
  const s = String(id == null ? '' : id)
  const c = props.connList.find(x => {
    const xi = String(x.id)
    return xi === s || (s.length > 16 && xi.startsWith(s.slice(0, 16))) || (xi.length > 16 && s.startsWith(xi.slice(0, 16)))
  })
  return c ? c.connName : (id ? '?' : '')
}
function validSrcRows(s) {
  return (s.srcRows || []).filter(r => r.srcTable)
}
function mapCount(s) {
  return validSrcRows(s).reduce((n, r) => n + (r.maps || []).filter(m => m.srcField || m.dstField).length, 0)
}
function cleanRuleCount(s) {
  return validSrcRows(s).reduce((n, r) => n + (r.cleanRules || []).filter(x => x.ruleName || x.ruleType).length, 0)
}
function dstMapCount(s) {
  return (s.dstMaps || []).filter(m => m.srcField || m.dstField).length
}
function srcDesc(s) {
  const t = s.srcType || 'TABLE'
  if (t === 'EXCEL') return 'Excel: ' + (s.srcExcelPath || '未配置')
  if (t === 'API') return 'API: ' + (s.srcApiUrl || '未配置')
  return connName(s.srcConnId) + '.' + (s.srcSchema || '') + '.' + validSrcRows(s).map(r => r.srcTable).join(' / ')
}
function dstDesc(s) {
  const t = s.dstType || 'TABLE'
  if (t === 'EXCEL') return 'Excel: ' + (s.dstExcelPath || '未配置')
  if (t === 'API') return 'API: ' + (s.dstApiUrl || '未配置') + '（' + (s.dstApiMethod || 'POST') + '）'
  return connName(s.dstConnId) + '.' + (s.dstSchema || '') + '.' + (s.dstTable || '未配置') + '（' + (s.dstWriteMode || 'INSERT') + '）'
}
function srcBrief(s) {
  const t = s.srcType || 'TABLE'
  if (t === 'EXCEL') return s.srcExcelPath || '未配置'
  if (t === 'API') return s.srcApiUrl || '未配置'
  return connName(s.srcConnId) || '未配置'
}
function dstBrief(s) {
  const t = s.dstType || 'TABLE'
  if (t === 'EXCEL') return s.dstExcelPath || '未配置'
  if (t === 'API') return s.dstApiUrl || '未配置'
  return connName(s.dstConnId) || '未配置'
}

// ===== 联动加载 =====
function onConnChange(s, role) {
  const connId = s[role === 'src' ? 'srcConnId' : role === 'dst' ? 'dstConnId' : 'cleanConnId']
  const dbKey = role === 'src' ? '_srcDbs' : role === 'dst' ? '_dstDbs' : '_cleanDbs'
  s[dbKey] = []
  s[role === 'src' ? 'srcSchema' : role === 'dst' ? 'dstSchema' : 'cleanSchema'] = undefined
  if (!connId) return
  listDatabases(connId).then(res => {
    s[dbKey] = res.data || res.rows || []
  })
}
function onSchemaChange(s, role) {
  const connId = s[role === 'src' ? 'srcConnId' : role === 'dst' ? 'dstConnId' : 'cleanConnId']
  const schema = s[role === 'src' ? 'srcSchema' : role === 'dst' ? 'dstSchema' : 'cleanSchema']
  const tableKey = role === 'src' ? '_srcTables' : role === 'dst' ? '_dstTables' : '_cleanTables'
  s[tableKey] = []
  if (!connId || !schema) return
  listTables(connId, schema).then(res => {
    s[tableKey] = res.data || res.rows || []
  })
}
function onRowConnChange(s, row) {
  row._srcRowDbs = []
  row.srcSchema = undefined
  if (!row.srcConnId) return
  listDatabases(row.srcConnId).then(res => {
    row._srcRowDbs = res.data || res.rows || []
  })
}
function onRowSchemaChange(s, row) {
  const cid = row.srcConnId || s.srcConnId
  const sch = row.srcSchema || s.srcSchema
  row._srcRowTables = []
  if (!cid || !sch) return
  listTables(cid, sch).then(res => {
    row._srcRowTables = res.data || res.rows || []
  })
}
function onSrcTableChange(s, row) {
  const cid = row.srcConnId || s.srcConnId
  const sch = row.srcSchema || s.srcSchema
  if (cid && sch && row.srcTable) {
    listColumns(cid, sch, row.srcTable).then(res => {
      const cols = (res.data || res.rows || [])
      s._srcCols = cols.map(c => c.columnName || c.name || c)
    })
  }
}
function onDstTableChange(s) {
  if (s.dstConnId && s.dstSchema && s.dstTable) {
    listColumns(s.dstConnId, s.dstSchema, s.dstTable).then(res => {
      const cols = (res.data || res.rows || [])
      s._dstCols = cols.map(c => c.columnName || c.name || c)
    })
  }
}
function onCleanTableChange(s) {
  s._cleanCols = []
  if (s.cleanConnId && s.cleanSchema && s.cleanTable) {
    listColumns(s.cleanConnId, s.cleanSchema, s.cleanTable).then(res => {
      const cols = (res.data || res.rows || [])
      s._cleanCols = cols.map(c => c.columnName || c.name || c)
    })
  }
}

// ===== 完成预览: 过程图 =====
const logDialog = ref({ open: false, title: '', rows: [], loading: false })
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
function flowGraph(s) {
  const rows = validSrcRows(s)
  const N = Math.max(rows.length, 1)
  const NW = 150, NH = 58, STEP = 200, GAP = 150
  const Y0 = 70
  const X_START = 20
  const X_DS = 210
  const X_BR = 430
  const LX = 470
  const END_X = LX + 6 * STEP + NW + 90
  const yc = Y0 + (N - 1) * GAP / 2
  const nodes = [], lines = []
  const jtLabel = r => r.joinType === 'JOIN' ? ' INNER JOIN' : (r.joinType === 'LEFT_JOIN' ? ' LEFT JOIN' : '')
  const laneY = i => Y0 + i * GAP
  const dur = '2.2s'
  nodes.push(fgNode(X_START, yc - 26, 96, 52, '#e1f3d8', '#95d475',
    [{ v: '开始', y: 19, size: 14, color: '#529b2e' }],
    () => viewNodeLog(s, { type: 'SUBTASK', nodeId: s.id, name: '开始' }), '开始'))
  nodes.push(fgNode(X_DS, yc - 29, 190, 58, '#ecf5ff', '#a0cfff',
    [{ v: '选择数据源', y: 18, size: 12, color: '#79bbff' },
     { v: clipText(srcBrief(s), 13), y: 41, size: 13, color: '#303133' }],
    () => viewNodeLog(s, { type: 'DATA_SOURCE', nodeId: s.srcConnId, name: '选择数据源' }), '选择数据源'))
  lines.push({ d: 'M' + (X_START + 96) + ',' + yc + ' L' + X_DS + ',' + yc, dur: '2.0s' })
  lines.push({ d: 'M' + (X_DS + 190) + ',' + yc + ' L' + X_BR + ',' + yc, dur: '2.3s' })
  if (N > 1) lines.push({ d: 'M' + X_BR + ',' + (Y0 - 24) + ' L' + X_BR + ',' + (Y0 + (N - 1) * GAP + 24), dur: '3.0s' })
  rows.forEach((row, i) => {
    const y = laneY(i)
    lines.push({ d: 'M' + X_BR + ',' + y + ' L' + (LX - 20) + ',' + y, dur: '2.2s' })
    let cx = LX
    nodes.push(fgNode(cx, y - 29, NW, NH, '#fdf6ec', '#f5dab1',
      [{ v: '源表' + (i + 1) + jtLabel(row), y: 18, size: 12, color: '#e6a23c' },
       { v: clipText(row.srcTable + '@' + connName(row.srcConnId || s.srcConnId), 32), y: 41, size: 12, color: '#303133' }],
      () => viewNodeLog(s, { type: 'TABLE', nodeId: row.srcTable, name: '源表' + (i + 1) }), '源表' + (i + 1) + ': ' + row.srcTable))
    cx += STEP
    lines.push({ d: 'M' + (cx - STEP + NW) + ',' + y + ' L' + cx + ',' + y, dur: '2.2s' })
    const clean = row.cleanRules || []
    nodes.push(fgNode(cx, y - 29, NW, NH, '#f0f9eb', '#e1f3d8',
      [{ v: '清洗规则', y: 18, size: 12, color: '#85ce61' },
       { v: clean.length ? clipText(clean.map(c => c.ruleName || c.ruleType).join('、'), 15) : '无', y: 41, size: 13, color: '#303133' }],
      () => viewNodeLog(s, { type: 'CLEAN_RULE', nodeId: clean.length ? (clean[0].ruleName || clean[0].ruleType) : '', name: '清洗规则' }), '清洗规则'))
    const hasClean = !!(s.cleanTable)
    if (hasClean) {
      cx += STEP
      lines.push({ d: 'M' + (cx - STEP + NW) + ',' + y + ' L' + cx + ',' + y, dur: '2.2s' })
      nodes.push(fgNode(cx, y - 29, NW, NH, '#f5f0ff', '#d3c6f0',
        [{ v: '清洗结果表', y: 18, size: 12, color: '#8e44ad' },
         { v: clipText(connName(s.cleanConnId) + '.' + s.cleanTable, 22), y: 41, size: 12, color: '#303133' }],
        () => viewNodeLog(s, { type: 'TABLE', nodeId: s.cleanTable || '', name: '清洗结果表' }), '清洗结果表'))
    }
    cx += STEP
    lines.push({ d: 'M' + (cx - STEP + NW) + ',' + y + ' L' + cx + ',' + y, dur: '2.2s' })
    const quals = s.qualityRules || []
    nodes.push(fgNode(cx, y - 29, NW, NH, '#fef0f0', '#fbc4c4',
      [{ v: '质量检查', y: 18, size: 12, color: '#f56c6c' },
       { v: quals.length ? clipText(quals.map(q => q.ruleName || q.ruleType).join('、'), 15) : '无', y: 41, size: 13, color: '#303133' }],
      () => viewNodeLog(s, { type: 'QUALITY_RULE', nodeId: quals.length ? (quals[0].ruleName || quals[0].ruleType) : '', name: '质量规则' }), '质量检查'))
    cx += STEP
    lines.push({ d: 'M' + (cx - STEP + NW) + ',' + y + ' L' + cx + ',' + y, dur: '2.2s' })
    nodes.push(fgNode(cx, y - 29, NW, NH, '#ecf5ff', '#a0cfff',
      [{ v: '目标数据源', y: 18, size: 12, color: '#79bbff' },
       { v: clipText(dstBrief(s), 15), y: 41, size: 13, color: '#303133' }],
      () => viewNodeLog(s, { type: 'TABLE', nodeId: s.dstTable || '', name: '目标数据源' }), '目标数据源'))
    cx += STEP
    lines.push({ d: 'M' + (cx - STEP + NW) + ',' + y + ' L' + cx + ',' + y, dur: '2.2s' })
    nodes.push(fgNode(cx, y - 29, NW, NH, '#ecf5ff', '#a0cfff',
      [{ v: '目标表', y: 18, size: 12, color: '#79bbff' },
       { v: clipText(s.dstTable || '-', 15), y: 41, size: 13, color: '#303133' }],
      () => viewNodeLog(s, { type: 'TABLE', nodeId: s.dstTable || '', name: '目标表' }), '目标表'))
    lines.push({ d: 'M' + (cx + NW) + ',' + y + ' L' + END_X + ',' + y, dur: '2.4s' })
  })
  if (N > 1) lines.push({ d: 'M' + END_X + ',' + (Y0 - 24) + ' L' + END_X + ',' + (Y0 + (N - 1) * GAP + 24), dur: '3.0s' })
  lines.push({ d: 'M' + END_X + ',' + yc + ' L' + (END_X + 90) + ',' + yc, dur: '2.0s' })
  nodes.push(fgNode(END_X + 90, yc - 26, 96, 52, '#fef0f0', '#fbc4c4',
    [{ v: '结束', y: 19, size: 14, color: '#f56c6c' }],
    () => viewNodeLog(s, { type: 'SUBTASK', nodeId: s.id, name: '结束' }), '结束'))
  return { nodes, lines, width: END_X + 96 + 90 + 40, height: Y0 + (N - 1) * GAP + 58 + 60 }
}
const fg = (s) => flowGraph(s)

function viewNodeLog(s, node) {
  const params = {
    jobId: s.id,
    subtaskId: s.id,
    pageNum: 1,
    pageSize: 10
  }
  if (node.type) params.nodeType = node.type
  if (node.nodeId) params.nodeId = node.nodeId
  logDialog.value.open = true
  logDialog.value.title = (s.jobName || '子任务') + ' · ' + node.name
  logDialog.value.rows = []
  logDialog.value.loading = true
  listEtlJobLog(params).then(res => {
    const rows = (res.data && (res.data.rows || res.data.list)) || res.rows || []
    logDialog.value.rows = rows
    logDialog.value.loading = false
  }).catch(() => {
    logDialog.value.loading = false
  })
}

// ===== 序列化: 提交给后端 EtlJob 的字段结构 (与总任务子任务一致) =====
function serialize(parentId) {
  const s = sub.value
  const rows = validSrcRows(s)
  const nonTableSrc = (s.srcType || 'TABLE') !== 'TABLE'
  const maps = rows
    .map(r => ({ srcTable: r.srcTable, maps: (r.maps || []).filter(m => m.srcField || m.dstField) }))
    .filter(g => g.maps.length)
  const dstMaps = (s.dstMaps || []).filter(m => m.srcField || m.dstField)
  const stripCfg = arr => (arr || []).map(x => ({ ruleName: x.ruleName, ruleType: x.ruleType, ruleConfig: x.ruleConfig })).filter(x => x.ruleName || x.ruleType)
  const cleanRules = nonTableSrc
    ? (s.srcRows?.[0]?.cleanRules || []).filter(x => x.ruleName || x.ruleType).length
      ? [{ srcTable: '', rules: stripCfg(s.srcRows[0].cleanRules) }]
      : []
    : rows
        .map(r => ({ srcTable: r.srcTable, rules: stripCfg(r.cleanRules) }))
        .filter(g => g.rules.length)
  const hasClean = !!(s.cleanConnId && s.cleanSchema && s.cleanTable)
  const effectiveMaps = hasClean ? maps : (dstMaps.length ? [{ srcTable: rows[0]?.srcTable || '', maps: dstMaps }] : [])
  const srcAuth = s._srcAuth || { type: 'NONE' }
  const dstAuth = s._dstAuth || { type: 'NONE' }
  return {
    id: s.id,
    jobName: s.jobName,
    jobType: s.jobType,
    kettleType: 'TRANS',
    parentId: parentId || undefined,
    srcType: s.srcType || 'TABLE',
    srcConnId: s.srcType === 'TABLE' ? s.srcConnId : null,
    srcSchema: s.srcSchema,
    srcTable: rows?.[0]?.srcTable,
    srcWhere: rows?.[0]?.srcWhere,
    srcExcelPath: s.srcExcelPath || null,
    srcExcelSheet: s.srcExcelSheet || null,
    srcExcelHeader: s.srcExcelHeader == null ? 0 : s.srcExcelHeader,
    srcExcelDataRow: s.srcExcelDataRow ?? null,
    srcExcelColFrom: s.srcExcelColFrom ?? null,
    srcExcelColTo: s.srcExcelColTo ?? null,
    srcApiUrl: s.srcApiUrl || null,
    srcApiMethod: s.srcApiMethod || 'GET',
    srcApiHeaders: s.srcApiHeaders || null,
    srcApiBody: s.srcApiBody || null,
    srcApiDataPath: s.srcApiDataPath || null,
    srcApiPageField: s.srcApiPageField || null,
    srcApiPageSize: s.srcApiPageSize || 0,
    srcApiAuth: srcAuth.type === 'TOKEN' ? JSON.stringify(srcAuth) : null,
    srcList: rows.length ? JSON.stringify(rows.map(r => ({ srcTable: r.srcTable, srcWhere: r.srcWhere, joinType: r.joinType || 'UNION', joinField: r.joinField || '', joinRef: r.joinRef || '', srcConnId: r.srcConnId || null, srcSchema: r.srcSchema || null }))) : null,
    fieldMapping: effectiveMaps.length ? JSON.stringify(effectiveMaps) : null,
    cleanRules: cleanRules.length ? JSON.stringify(cleanRules) : null,
    qualityRules: s.qualityRules?.length ? JSON.stringify(s.qualityRules) : null,
    cleanConnId: s.cleanConnId,
    cleanSchema: s.cleanSchema,
    cleanTable: s.cleanTable,
    cleanWriteMode: s.cleanWriteMode || 'INSERT',
    dstConnId: s.dstType === 'TABLE' ? s.dstConnId : null,
    dstType: s.dstType || 'TABLE',
    dstSchema: s.dstSchema,
    dstTable: s.dstTable,
    dstExcelPath: s.dstExcelPath || null,
    dstExcelSheet: s.dstExcelSheet || null,
    dstApiUrl: s.dstApiUrl || null,
    dstApiMethod: s.dstApiMethod || 'POST',
    dstApiHeaders: s.dstApiHeaders || null,
    dstApiBodyTpl: s.dstApiBodyTpl || null,
    dstApiAuth: dstAuth.type === 'TOKEN' ? JSON.stringify(dstAuth) : null,
    dstMapping: dstMaps.length ? JSON.stringify(dstMaps) : null,
    dstWriteMode: s.dstWriteMode,
    dstReplaceKey: s.dstReplaceKey || null,
    speedChannel: s.speedChannel,
    batchSize: s.batchSize,
    incrField: s.incrField || null,
    incrMode: s.incrMode || 'ID',
    incrLastValue: s.incrLastValue || null,
    retryCount: s.retryCount == null ? 0 : s.retryCount,
    retryInterval: s.retryInterval == null ? 30 : s.retryInterval,
    errorLimitPercent: s.errorLimitPercent == null ? 0 : s.errorLimitPercent,
    errorLimitCount: s.errorLimitCount == null ? 0 : s.errorLimitCount,
    status: '0',
    depIds: (s.depIds || []).join(',') || null
  }
}

// ===== 回显 =====
function loadFrom(c) {
  const s = sub.value
  s.id = c.id
  s.jobName = c.jobName
  s.jobType = c.jobType || 'KETTLE'
  s.srcType = c.srcType || 'TABLE'
  s.srcConnId = c.srcConnId
  s.srcSchema = c.srcSchema
  s.srcExcelPath = c.srcExcelPath || ''
  s.srcExcelSheet = c.srcExcelSheet || ''
  s.srcExcelHeader = c.srcExcelHeader == null ? 0 : c.srcExcelHeader
  s.srcExcelDataRow = c.srcExcelDataRow ?? undefined
  s.srcExcelColFrom = c.srcExcelColFrom ?? undefined
  s.srcExcelColTo = c.srcExcelColTo ?? undefined
  s.srcApiUrl = c.srcApiUrl || ''
  s.srcApiMethod = c.srcApiMethod || 'GET'
  s.srcApiHeaders = c.srcApiHeaders || ''
  s.srcApiBody = c.srcApiBody || ''
  s.srcApiDataPath = c.srcApiDataPath || ''
  s.srcApiPageField = c.srcApiPageField || ''
  s.srcApiPageSize = c.srcApiPageSize || 0
  if (c.srcApiAuth) {
    try { s._srcAuth = { type: 'NONE', tokenUrl: '', tokenMethod: 'POST', tokenHeaders: '', tokenBody: '', tokenPath: 'token', ...JSON.parse(c.srcApiAuth) } } catch (e) { /* ignore */ }
  }
  s.dstType = c.dstType || 'TABLE'
  s.dstConnId = c.dstConnId
  s.dstSchema = c.dstSchema
  s.dstTable = c.dstTable
  s.dstExcelPath = c.dstExcelPath || ''
  s.dstExcelSheet = c.dstExcelSheet || ''
  s.dstApiUrl = c.dstApiUrl || ''
  s.dstApiMethod = c.dstApiMethod || 'POST'
  s.dstApiHeaders = c.dstApiHeaders || ''
  s.dstApiBodyTpl = c.dstApiBodyTpl || ''
  if (c.dstApiAuth) {
    try { s._dstAuth = { type: 'NONE', tokenUrl: '', tokenMethod: 'POST', tokenHeaders: '', tokenBody: '', tokenPath: 'token', ...JSON.parse(c.dstApiAuth) } } catch (e) { /* ignore */ }
  }
  s.dstWriteMode = c.dstWriteMode || 'INSERT'
  s.speedChannel = c.speedChannel || 3
  s.batchSize = c.batchSize || 1024
  s.cleanConnId = c.cleanConnId
  s.cleanSchema = c.cleanSchema
  s.cleanTable = c.cleanTable
  s.cleanWriteMode = c.cleanWriteMode || 'INSERT'
  s.incrField = c.incrField || ''
  s.incrMode = c.incrMode || 'ID'
  s.incrLastValue = c.incrLastValue || ''
  s.retryCount = c.retryCount == null ? 0 : c.retryCount
  s.retryInterval = c.retryInterval == null ? 30 : c.retryInterval
  s.errorLimitPercent = c.errorLimitPercent == null ? 0 : c.errorLimitPercent
  s.errorLimitCount = c.errorLimitCount == null ? 0 : c.errorLimitCount
  s.depIds = c.depIds ? String(c.depIds).split(',').filter(Boolean) : []
  s.qualityRules = c.qualityRules ? JSON.parse(c.qualityRules) : []
  if (c.dstMapping) {
    try {
      const dm = JSON.parse(c.dstMapping)
      s.dstMaps = Array.isArray(dm) ? dm.filter(m => m.srcField || m.dstField) : [{ srcField: '', dstField: '' }]
    } catch (e) { s.dstMaps = [{ srcField: '', dstField: '' }] }
  }
  let rows = []
  if (c.srcList) {
    try { rows = JSON.parse(c.srcList) } catch (e) { rows = [] }
  }
  if (!rows.length && c.srcTable) rows = [{ srcTable: c.srcTable, srcWhere: c.srcWhere }]
  if (!rows.length) rows = [{ srcTable: '', srcWhere: '' }]
  rows = rows.map(r => ({ srcTable: r.srcTable || '', srcWhere: r.srcWhere || '', joinType: r.joinType || 'UNION', joinField: r.joinField || '', joinRef: r.joinRef || '', srcConnId: r.srcConnId || c.srcConnId || undefined, srcSchema: r.srcSchema || c.srcSchema || undefined, _srcRowDbs: [], _srcRowTables: [], _folded: false, maps: [{ srcField: '', dstField: '' }], cleanRules: [] }))
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
  s.srcRows = rows
  // 回显清洗规则可视化配置模型
  rows.forEach(r => {
    (r.cleanRules || []).forEach(rl => {
      if (!rl._cfg) {
        try {
          rl._cfg = JSON.parse(rl.ruleConfig || '{}')
        } catch (e) { rl._cfg = {} }
      }
    })
  })
  // 回显后联动加载 库/表/列
  setTimeout(() => {
    // 行级: 为每行加载 数据源->库->表->列
    rows.forEach((row, ri) => {
      if (row.srcConnId) {
        onRowConnChange(s, row)
        if (row.srcSchema) {
          setTimeout(() => {
            onRowSchemaChange(s, row)
            if (row.srcTable) onSrcTableChange(s, row)
          }, 400 + ri * 200)
        }
      }
    })
    const srcSchema = s.srcSchema
    if (s.srcConnId) {
      onConnChange(s, 'src')
      if (srcSchema) {
        setTimeout(() => {
          s.srcSchema = srcSchema
          onSchemaChange(s, 'src')
        }, 500)
      }
    }
    const dstSchema = s.dstSchema
    if (s.dstConnId) {
      onConnChange(s, 'dst')
      if (dstSchema) {
        setTimeout(() => {
          s.dstSchema = dstSchema
          onSchemaChange(s, 'dst')
        }, 900)
      }
    }
    const cleanSchema = s.cleanSchema
    if (s.cleanConnId) {
      onConnChange(s, 'clean')
      if (cleanSchema) {
        setTimeout(() => {
          s.cleanSchema = cleanSchema
          onSchemaChange(s, 'clean')
          if (s.cleanTable) onCleanTableChange(s)
        }, 1300)
      }
    }
  }, 100)
  // Excel 源/目标: 回显后加载工作表信息(下拉/校验/列标题)
  if ((s.srcType || 'TABLE') === 'EXCEL' && s.srcExcelPath) loadSheets(s, 'src')
  if ((s.dstType || 'TABLE') === 'EXCEL' && s.dstExcelPath) loadSheets(s, 'dst')
}

defineExpose({ serialize, loadFrom })

onMounted(() => {
  loadEtlFiles()
})
</script>

<style lang="scss" scoped>
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
.subtask-designer {
  .form-tip { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6; }
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
        .mini-step-opt { border-color: rgba(255,255,255,.5); color: #fff; }
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
  .ro-block {
    padding: 6px 10px; margin-bottom: 6px;
    background: #fafafa; border-radius: 4px;
    .ro-block-title { font-size: 12px; font-weight: 600; color: var(--el-text-color-regular); margin-bottom: 4px; }
    .ro-none { font-size: 12px; color: var(--el-text-color-placeholder); }
  }
  .rule-row { margin-bottom: 8px; }
  /* ===== 源表卡片(合并 数据源/库/表/过滤条件, 可折叠) ===== */
  .src-card {
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px; margin-bottom: 8px; background: #fff;
    &.folded .src-card-body { display: none; }
    .src-card-head {
      display: flex; align-items: center; justify-content: space-between;
      padding: 8px 12px; cursor: pointer; user-select: none;
      &:hover { background: #f5f7fa; }
      .src-card-title {
        display: flex; align-items: center; gap: 8px; min-width: 0; flex: 1;
        .fold-ico {
          cursor: pointer; color: var(--el-text-color-secondary); flex-shrink: 0;
          display: inline-flex; align-items: center;
          svg { transition: transform .18s; }
          svg.is-folded { transform: rotate(-90deg); }
        }
        .src-idx { font-size: 13px; font-weight: 600; color: var(--el-text-color-regular); flex-shrink: 0; }
        .main-badge {
          font-size: 10px; color: #fff; background: var(--el-color-success);
          border-radius: 4px; padding: 1px 6px; flex-shrink: 0;
        }
        .src-card-summary {
          font-size: 12px; color: var(--el-text-color-secondary);
          white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
        }
      }
    }
    .src-card-body { padding: 10px 12px 8px; border-top: 1px dashed var(--el-border-color-lighter); }
  }
  .src-add-bar {
    display: flex; align-items: center; gap: 10px; margin-top: 4px; flex-wrap: wrap;
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
  .map-group {
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 6px; padding: 8px 10px; margin-bottom: 10px; background: #fff;
    .map-group-title {
      font-size: 13px; font-weight: 600; color: var(--el-color-primary);
      display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px;
    }
  }
  .preview-desc { margin-bottom: 4px; }
  .log-detail-pre {
    white-space: pre-wrap; word-break: break-all; max-height: 480px; overflow: auto;
    background: #f5f7fa; border: 1px solid var(--el-border-color); border-radius: 6px;
    padding: 12px; font-size: 12px; line-height: 1.6; font-family: Consolas, Monaco, monospace;
  }
  .conn-tip { font-size: 12px; color: var(--el-text-color-secondary); line-height: 28px; }
  .join-row { margin-top: 6px; background: #fafcff; border-radius: 6px; padding: 4px 6px; }
  .join-ref-label { line-height: 28px; font-size: 12px; color: var(--el-text-color-secondary); }
  .join-main-tip { font-size: 12px; color: var(--el-color-success); line-height: 28px; }
  :deep(.el-divider__text) { font-size: 13px; color: var(--el-color-primary); font-weight: 500; }

  /* ===== 执行过程图 ===== */
  .flow-canvas { overflow-x: auto; }
  .flow-svg { width: 100%; min-width: 1180px; display: block; }
  .flow-svg-node { cursor: pointer; }
  .flow-svg-node rect { transition: filter .15s; }
  .flow-svg-node:hover rect { filter: brightness(.96); }
  .flow-canvas {
    background: #fff; border: 1px solid var(--el-border-color-lighter); border-radius: 8px;
    padding: 16px 14px 10px; overflow-x: auto;
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
.fm-divider { cursor: pointer; user-select: none; }
.fm-arrow { display: inline-block; margin-right: 6px; color: var(--el-text-color-secondary); font-size: 12px; transition: transform .2s; }
.fm-arrow.open { transform: rotate(90deg); }
</style>
