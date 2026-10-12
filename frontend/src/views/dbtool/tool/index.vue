<template>
  <div class="app-container dbtool-workbench">
    <div class="wb-header">
      <div class="wb-toolbar">
        <div class="wb-left">
          <span class="wb-group">连接</span>
          <el-button type="primary" plain size="small" icon="DocumentAdd" @click="openSqlTab()">新建 SQL 脚本</el-button>
          <el-button plain size="small" icon="Refresh" @click="refreshTree">刷新</el-button>
          <span class="wb-sep"></span>
          <span class="wb-group">结构</span>
          <el-button v-hasPermi="['dbtool:meta:add']" plain size="small" icon="Plus" @click="openCreateTable">新建表</el-button>
          <span class="wb-sep"></span>
          <span class="wb-group">对比 / 迁移</span>
          <el-button plain size="small" icon="Sort" @click="openSchemaDiffDialog">结构对比</el-button>
          <el-button plain size="small" icon="RefreshRight" @click="dataCompareVisible = true">数据对比</el-button>
          <el-button plain size="small" icon="Switch" @click="openMigrateDialog">数据迁移</el-button>
          <el-button plain size="small" icon="Upload" @click="openExcelImportDialog">Excel 导入</el-button>
          <span class="wb-sep"></span>
          <span class="wb-group">辅助</span>
          <el-button plain size="small" icon="MagicStick" @click="openAiAssistant">AI 助手</el-button>
          <span class="wb-hint">双击连接 → 打开 SQL 脚本 / 双击表 → 查看表结构</span>
        </div>
      </div>
    </div>

    <div class="wb-body">
      <!-- 左侧: 数据源集合目录树 -->
      <div class="wb-side" :style="{ width: sideWidth + 'px' }">
        <div class="side-title">
          <span><el-icon><Connection /></el-icon>&nbsp;数据源集合</span>
          <span class="side-count">{{ connList.length }}</span>
        </div>
        <div class="side-search">
          <el-input
            v-model="treeKeyword"
            size="small"
            placeholder="搜索连接 / 库 / 表"
            clearable
            :prefix-icon="Search"
            @clear="handleTreeSearch"
            @input="handleTreeSearch"
          />
        </div>
        <el-tree
          ref="treeRef"
          :key="treeVersion"
          :props="{ label: 'label', children: 'children' }"
          node-key="id"
          lazy
          :load="loadTreeNode"
          :filter-node-method="treeFilterNode"
          highlight-current
          :expand-on-click-node="false"
          @node-click="handleTreeNodeClick"
        >
          <template #default="{ data }">
            <span class="tree-node" @dblclick.stop="handleTreeNodeDblClick(data)" @contextmenu.prevent.stop="showTreeMenu($event, data)">
              <el-icon v-if="data.isConn" class="icon-conn"><Coin /></el-icon>
              <el-icon v-else-if="data.isDb" class="icon-db"><FolderOpened /></el-icon>
              <el-icon v-else-if="data.isTable" class="icon-table"><Document /></el-icon>
              <el-icon v-else-if="data.isColumn" class="icon-col"><Key /></el-icon>
              <el-icon v-else><InfoFilled /></el-icon>
              <span class="tree-label">{{ data.label }}</span>
              <el-tag v-if="data.tableType === 'VIEW'" size="small" type="warning" class="tree-tag">视图</el-tag>
            </span>
          </template>
        </el-tree>
      </div>

      <!-- 拖动手柄 -->
      <div class="wb-resizer" @mousedown="startResize" />

      <!-- 右侧: 多标签工作区 -->
      <div class="wb-main">
        <el-tabs
          v-if="tabs.length"
          v-model="activeTab"
          type="border-card"
          class="wb-tabs"
          closable
          @tab-remove="closeTab"
          @tab-click="onTabClick"
        >
          <el-tab-pane v-for="tab in tabs" :key="tab.key" :name="tab.key" :label="tab.title" :closable="tab.closable !== false">
            <!-- SQL 脚本 tab -->
            <div v-if="tab.type === 'sql'" class="sql-tab">
              <div class="sql-tab-toolbar">
                <el-select v-model="tab.connId" placeholder="选择连接" size="small" style="width: 170px" @change="(v) => onSqlConnChange(tab, v)">
                  <el-option v-for="c in connList" :key="c.id" :label="c.connName + (c.accessMode === 'READ_ONLY' ? ' (只读)' : '')" :value="c.id" />
                </el-select>
                <el-select v-model="tab.schema" placeholder="选择数据库" size="small" style="width: 140px" clearable @change="(v) => onSqlSchemaChange(tab, v)">
                  <el-option v-for="d in tab.dbList" :key="d" :label="d" :value="d" />
                </el-select>
                <el-tag v-if="isConnReadOnly(tab.connId)" type="warning" size="small">只读连接</el-tag>
                <el-button type="primary" size="small" icon="CaretRight" :disabled="tab.executing" @click="runSqlTab(tab)">执行</el-button>
                <el-button v-if="tab.executing" type="danger" size="small" icon="CircleClose" @click="cancelSqlTab(tab)">取消查询</el-button>
                <el-button size="small" icon="DataAnalysis" @click="explainSql(tab)">执行计划</el-button>
                <el-button v-if="!tab.txnSession" size="small" icon="CircleCheck" :disabled="!tab.connId || tab.executing" @click="beginTxn(tab)">开始事务</el-button>
                <template v-if="tab.txnSession">
                  <el-tag size="small" type="success">事务中</el-tag>
                  <el-button size="small" icon="Select" :disabled="tab.executing" @click="commitTxn(tab)">提交</el-button>
                  <el-button size="small" icon="RefreshLeft" :disabled="tab.executing" @click="rollbackTxn(tab)">回滚</el-button>
                  <el-button size="small" icon="CircleClose" :disabled="tab.executing" @click="closeTxn(tab)">关闭</el-button>
                </template>
                <el-button size="small" icon="Clock" @click="openSqlLibrary(tab)">SQL 历史/脚本</el-button>
                <span class="sql-shortcut">选中执行选区 / 未选中执行当前语句 · Ctrl+Enter</span>
              </div>
              <MonacoSqlEditor
                :ref="(el) => registerSqlEditor(tab, el)"
                v-model="tab.sqlText"
                class="sql-editor-box"
                placeholder="输入 SQL 语句, 如: SELECT * FROM `table` LIMIT 100"
                :min-height="'140px'"
                :height="(tab.editorHeight || 200) + 'px'"
                :databases="tab.dbList"
                :tables="tab.tableList"
                @keydown-ctrl-enter="runSqlTab(tab)"
                @format="() => formatSql(tab)"
                @clear="() => resetSql(tab)"
              />
              <div class="sql-resizer" title="拖动调整编辑区高度" @mousedown.prevent="startDragResize(tab, $event)"></div>
              <div class="result-tabs">
                <el-tabs v-model="tab.activeResultTab" type="border-card" class="sub-tabs result-sub-tabs">
                  <el-tab-pane label="查询结果" name="result">
                    <div class="result-title">
                      <span class="rt-label">查询结果</span>
                      <el-input
                        v-model="tab.filterExpr"
                        class="result-filter"
                        size="small"
                        placeholder="输入 SQL 表达式过滤结果, 如 id &gt; 10 AND f_name like '%中%'"
                        clearable
                        @keyup.enter="applyResultFilter(tab)"
                        @clear="applyResultFilter(tab)"
                      />
                      <el-button
                        size="small"
                        :type="tab.gridServer ? 'primary' : 'default'"
                        plain
                        :disabled="!tab.lastSql"
                        @click="toggleServerGrid(tab)"
                      >{{ tab.gridServer ? '服务端模式' : '本地模式' }}</el-button>
                      <span v-if="tab.error" class="sql-error">⚠ {{ tab.error }}</span>
                    </div>
                    <div class="result-table-wrap">
                      <AgGridResult
                        :ref="(el) => registerAgGrid(tab, el)"
                        :columns="tab.columns"
                        :column-types="tab.columnTypes"
                        :rows="tab.pageRows"
                        :height="'100%'"
                        :pagination="false"
                        :editable="!isConnReadOnly(tab.connId) && !tab.gridServer"
                        :primary-keys="getPrimaryKeys(tab)"
                        @cell-edit="(params) => handleAgGridCellEdit(tab, params)"
                        @cell-editing-started="(params) => handleAgGridEditingStarted(tab, params)"
                        @cell-editing-stopped="(params) => handleAgGridEditingStopped(tab, params)"
                        @selection-change="(rows) => (tab.selectedRows = rows)"
                        @cell-context-menu="(params) => openResultCtxMenu(tab, params)"
                      />
                      <!-- 查询结果右键菜单 -->
                      <div v-if="resultCtxMenu.visible" class="db-result-ctx-menu" :style="{ left: resultCtxMenu.x + 'px', top: resultCtxMenu.y + 'px' }" @click.stop>
                        <div class="ctx-menu-item ctx-has-sub">
                          <span class="ctx-label">生成 SQL</span>
                          <span class="ctx-arrow">▶</span>
                          <div class="ctx-submenu">
                            <div class="ctx-menu-item" @click="genRowSql(resultCtxMenu.tab, 'select_eq')">SELECT .. WHERE .. =</div>
                            <div class="ctx-menu-item" @click="genRowSql(resultCtxMenu.tab, 'select_in')">SELECT .. WHERE .. IN</div>
                            <div class="ctx-menu-item" @click="genRowSql(resultCtxMenu.tab, 'insert')">INSERT</div>
                            <div class="ctx-menu-item" @click="genRowSql(resultCtxMenu.tab, 'update')">UPDATE</div>
                            <div class="ctx-menu-item" @click="genRowSql(resultCtxMenu.tab, 'delete')">DELETE by Unique Key</div>
                            <div class="ctx-menu-item" @click="genRowSql(resultCtxMenu.tab, 'create')">CREATE</div>
                          </div>
                        </div>
                        <div class="ctx-menu-item" @click="ctxMenuRefresh(resultCtxMenu.tab)"><span class="ctx-label">Refresh</span><span class="ctx-key">F5</span></div>
                        <div class="ctx-menu-item" @click="ctxMenuSwitchPanel(resultCtxMenu.tab)"><span class="ctx-label">切换结果面板</span><span class="ctx-key">F7</span></div>
                      </div>
                    </div>
                  </el-tab-pane>
                  <el-tab-pane :label="'执行明细' + (tab.statements.length ? ' (' + tab.statements.length + ')' : '')" name="statements">
                    <div class="stmt-list-pane">
                      <div class="stmt-list-title">
                        执行明细 ({{ tab.statements.length }} 条)
                        <el-button link type="primary" size="small" @click="tab.statements = []">清除</el-button>
                      </div>
                      <div v-if="!tab.statements.length" class="stmt-empty">
                        <div class="stmt-empty-icon">▦</div>
                        <div class="stmt-empty-title">暂无执行记录</div>
                        <div class="stmt-empty-desc">执行 SQL 后，每条语句的执行状态、耗时与错误信息将显示在这里。</div>
                      </div>
                      <div v-for="(s, i) in tab.statements" :key="i" class="stmt-item">
                        <span :class="['stmt-status', s.status]">{{ s.status === 'ok' ? '✓' : s.status === 'fail' ? '✗' : '…' }}</span>
                        <span class="stmt-sql">{{ s.sql }}</span>
                        <span :class="['stmt-msg', s.status === 'fail' ? 'fail' : '']">{{ s.msg }}</span>
                        <span v-if="s.costMs != null" class="stmt-cost">耗时 {{ s.costMs }} ms</span>
                      </div>
                    </div>
                  </el-tab-pane>
                </el-tabs>
                <div class="result-footer">
                  <div class="rf-left">
                    <el-button size="small" icon="Refresh" @click="refreshResult(tab)">刷新</el-button>
                    <el-button size="small" type="success" icon="CircleCheck" :disabled="isConnReadOnly(tab.connId) || !resultHasChanges(tab)" @click="saveResult(tab)">保存</el-button>
                    <el-button size="small" type="danger" plain icon="Close" :disabled="isConnReadOnly(tab.connId) || !resultHasChanges(tab)" @click="cancelResult(tab)">取消</el-button>
                    <el-button size="small" type="danger" plain icon="Delete" :disabled="isConnReadOnly(tab.connId)" @click="deleteSelectedRows(tab)">删除</el-button>
                    <el-button size="small" type="primary" plain icon="Plus" :disabled="isConnReadOnly(tab.connId)" @click="addResultRow(tab)">新增</el-button>
                    <el-button size="small" icon="CopyDocument" :disabled="isConnReadOnly(tab.connId)" @click="copySelectedRows(tab)">复制</el-button>
                    <el-dropdown trigger="click" @command="(f) => exportResult(tab, f)">
                      <el-button size="small" icon="Download" :disabled="!tab.lastSql">导出<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
                      <template #dropdown>
                        <el-dropdown-menu>
                          <el-dropdown-item command="csv">CSV (Excel 可直接打开)</el-dropdown-item>
                          <el-dropdown-item command="json">JSON</el-dropdown-item>
                          <el-dropdown-item command="sql">INSERT 语句</el-dropdown-item>
                        </el-dropdown-menu>
                      </template>
                    </el-dropdown>
                  </div>
                  <div class="rf-right">
                    <el-pagination
                      size="small"
                      background
                      layout="total, sizes, prev, pager, next, jumper"
                      :total="tab.gridServer ? tab.gridTotal : tab.filteredTotal"
                      v-model:current-page="tab.pageNum"
                      v-model:page-size="tab.pageSize"
                      :page-sizes="[50, 100, 200, 500]"
                      @current-change="updatePageRows(tab)"
                      @size-change="updatePageRows(tab)"
                    />
                    <span class="rf-info" v-if="tab.costMs != null">{{ tab.rows.length }} 行已获取 - {{ tab.costMs }} ms</span>
                  </div>
                </div>
              </div>
            </div>

            <!-- Redis 管理 tab -->
            <div v-else-if="tab.type === 'redis'" class="redis-tab">
              <RedisManager :conn-id="tab.connId" :conn-name="tab.connName" :read-only="isConnReadOnly(tab.connId)" />
            </div>

            <!-- Elasticsearch 管理 tab -->
            <div v-else-if="tab.type === 'es'" class="es-tab">
              <EsManager :conn-id="tab.connId" :conn-name="tab.connName" :read-only="isConnReadOnly(tab.connId)" />
            </div>

            <!-- 表结构 tab -->
            <div v-else-if="tab.type === 'table'" class="table-tab">
              <div class="table-tab-header">
                <el-icon class="icon-table"><Document /></el-icon>
                <span class="table-tab-title">{{ tab.table }}</span>
                <span v-if="tab.comment" class="table-tab-comment">{{ tab.comment }}</span>
                <span class="table-tab-loc">{{ tab.connName }} / {{ tab.schema }}</span>
                <el-button v-hasPermi="['dbtool:meta:remove']" type="danger" plain size="small" icon="Delete" class="table-drop-btn" :disabled="isConnReadOnly(tab.connId)" @click="handleDropTableFromTab(tab)">删除表</el-button>
              </div>
              <el-tabs v-model="tab.activeSub" type="border-card" class="sub-tabs table-main-tabs">
                <!-- 属性 tab -->
                <el-tab-pane label="属性" name="attr">
                  <div class="attr-layout">
                    <!-- 表基本信息 -->
                    <div class="attr-basic">
                      <el-descriptions :column="4" border size="small" class="attr-desc">
                        <el-descriptions-item label="表名">{{ tab.table }}</el-descriptions-item>
                        <el-descriptions-item label="注释">{{ tab.comment || '-' }}</el-descriptions-item>
                        <el-descriptions-item label="引擎">{{ tab.tableInfo.engine || '-' }}</el-descriptions-item>
                        <el-descriptions-item label="字符集">{{ tab.tableInfo.charset || '-' }}</el-descriptions-item>
                        <el-descriptions-item label="行数">{{ tab.tableInfo.rows || '-' }}</el-descriptions-item>
                        <el-descriptions-item label="数据大小">{{ tab.tableInfo.dataSize || '-' }}</el-descriptions-item>
                        <el-descriptions-item label="创建时间">{{ tab.tableInfo.createTime || '-' }}</el-descriptions-item>
                        <el-descriptions-item label="更新时间">{{ tab.tableInfo.updateTime || '-' }}</el-descriptions-item>
                      </el-descriptions>
                    </div>
                    <!-- 左侧菜单 + 右侧内容 -->
                    <div class="attr-body">
                      <div class="attr-side">
                        <div
                          v-for="m in attrMenus"
                          :key="m.key"
                          :class="['attr-menu-item', { active: tab.attrMenu === m.key }]"
                          @click="tab.attrMenu = m.key"
                        >
                          <el-icon><component :is="m.icon" /></el-icon>
                          <span>{{ m.label }}</span>
                        </div>
                      </div>
                      <div class="attr-content">
                        <!-- 列 -->
                        <div v-if="tab.attrMenu === 'columns'" class="pane-col">
                          <div class="tab-toolbar">
                            <el-button v-hasPermi="['dbtool:meta:edit']" type="primary" plain size="small" icon="Plus" :disabled="isConnReadOnly(tab.connId)" @click="openColumnDialog(tab, null)">新增列</el-button>
                            <el-button v-hasPermi="['dbtool:meta:edit']" type="success" plain size="small" icon="Edit" :disabled="isConnReadOnly(tab.connId) || !tab.currentColumn" @click="openColumnDialog(tab, tab.currentColumn)">编辑列</el-button>
                            <el-button v-hasPermi="['dbtool:meta:edit']" type="danger" plain size="small" icon="Delete" :disabled="isConnReadOnly(tab.connId) || !tab.currentColumn" @click="handleDropColumn(tab)">删除列</el-button>
                          </div>
                          <div class="sub-table-wrap">
                            <AgGridTable
                              :columns="agtColumns.columns"
                              :rows="tab.columns"
                              :current-key="tab.currentColumn ? tab.currentColumn.columnName : null"
                              @row-click="(row) => (tab.currentColumn = row)"
                            />
                          </div>
                        </div>
                        <!-- 约束 -->
                        <div v-else-if="tab.attrMenu === 'constraints'" class="pane-col">
                          <div class="sub-table-wrap">
                            <AgGridTable :columns="agtColumns.constraints" :rows="tab.constraints" />
                          </div>
                        </div>
                        <!-- 外键 -->
                        <div v-else-if="tab.attrMenu === 'foreignKeys'" class="pane-col">
                          <div class="sub-table-wrap">
                            <AgGridTable :columns="agtColumns.foreignKeys" :rows="tab.foreignKeys" />
                          </div>
                        </div>
                        <!-- 引用 -->
                        <div v-else-if="tab.attrMenu === 'references'" class="pane-col">
                          <div class="sub-table-wrap">
                            <AgGridTable :columns="agtColumns.references" :rows="tab.references" />
                          </div>
                        </div>
                        <!-- 触发器 -->
                        <div v-else-if="tab.attrMenu === 'triggers'" class="pane-col">
                          <div class="sub-table-wrap">
                            <AgGridTable :columns="agtColumns.triggers" :rows="tab.triggers" />
                          </div>
                        </div>
                        <!-- 索引 -->
                        <div v-else-if="tab.attrMenu === 'indexes'" class="pane-col">
                          <div class="sub-table-wrap">
                            <AgGridTable :columns="agtColumns.indexes" :rows="tab.indexes" />
                          </div>
                        </div>
                        <!-- 统计 -->
                        <div v-else-if="tab.attrMenu === 'stats'" class="pane-col">
                          <div class="stats-grid">
                            <div class="stat-card">
                              <div class="stat-label">列数</div>
                              <div class="stat-value">{{ tab.columns.length }}</div>
                            </div>
                            <div class="stat-card">
                              <div class="stat-label">索引数</div>
                              <div class="stat-value">{{ tab.indexes.length }}</div>
                            </div>
                            <div class="stat-card">
                              <div class="stat-label">主键数</div>
                              <div class="stat-value">{{ tab.columns.filter(c => c.columnKey === 'PRI').length }}</div>
                            </div>
                            <div class="stat-card">
                              <div class="stat-label">可空列</div>
                              <div class="stat-value">{{ tab.columns.filter(c => c.nullable === 'YES').length }}</div>
                            </div>
                            <div class="stat-card">
                              <div class="stat-label">外键数</div>
                              <div class="stat-value">{{ tab.foreignKeys.length }}</div>
                            </div>
                            <div class="stat-card">
                              <div class="stat-label">触发器数</div>
                              <div class="stat-value">{{ tab.triggers.length }}</div>
                            </div>
                          </div>
                        </div>
                        <!-- DDL -->
                        <div v-else-if="tab.attrMenu === 'ddl'" class="pane-col">
                          <div class="ddl-wrap">
                            <MonacoSqlEditor
                              v-model="tab.ddl"
                              class="ddl-editor-box"
                              read-only
                              :height="'420px'"
                              :min-height="'260px'"
                              :databases="tab.dbList || []"
                              :tables="tab.tableList || []"
                            />
                            <el-button type="primary" size="small" class="copy-btn" @click="copyDdl(tab)">复制 DDL</el-button>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </el-tab-pane>

                <!-- 数据 tab -->
                <el-tab-pane label="数据" name="data">
                  <div class="table-data-pane">
                    <div class="sql-tab-toolbar">
                      <el-select v-model="tab.dataConnId" placeholder="选择连接" size="small" style="width: 170px" @change="(v) => onDataConnChange(tab, v)">
                        <el-option v-for="c in connList" :key="c.id" :label="c.connName + (c.accessMode === 'READ_ONLY' ? ' (只读)' : '')" :value="c.id" />
                      </el-select>
                      <el-select v-model="tab.dataSchema" placeholder="选择数据库" size="small" style="width: 140px" clearable>
                        <el-option v-for="d in tab.dataDbList" :key="d" :label="d" :value="d" />
                      </el-select>
                      <el-tag v-if="isConnReadOnly(tab.dataConnId || tab.connId)" type="warning" size="small">只读连接</el-tag>
                      <el-button type="primary" size="small" icon="CaretRight" @click="runTableDataSql(tab)">执行</el-button>
                      <el-button size="small" icon="Refresh" @click="loadTableData(tab)">刷新数据</el-button>
                      <span class="sql-shortcut">Ctrl+Enter 执行</span>
                    </div>
                    <MonacoSqlEditor
                      :ref="(el) => registerSqlEditor(tab, el)"
                      v-model="tab.dataSql"
                      class="sql-editor-box"
                      placeholder="输入 SQL, 如 SELECT * FROM table LIMIT 200"
                      :min-height="'100px'"
                      :height="(tab.dataEditorHeight || 140) + 'px'"
                      :databases="tab.dbList || []"
                      :tables="tab.tableList || []"
                      @keydown-ctrl-enter="runTableDataSql(tab)"
                      @format="() => formatSql(tab, 'dataSql')"
                      @clear="() => resetSql(tab, 'dataSql')"
                    />
                    <div class="sql-resizer" title="拖动调整编辑区高度" @mousedown.prevent="startDragResize(tab, $event)"></div>
                    <div class="result-tabs">
                      <el-tabs v-model="tab.dataResultTab" type="border-card" class="sub-tabs result-sub-tabs">
                        <el-tab-pane label="查询结果" name="result">
                          <div class="result-title">
                            <span class="rt-label">查询结果</span>
                            <el-input
                              v-model="tab.dataFilterExpr"
                              class="result-filter"
                              size="small"
                              placeholder="输入 SQL 表达式过滤结果"
                              clearable
                              @keyup.enter="applyDataFilter(tab)"
                              @clear="applyDataFilter(tab)"
                            />
                            <span v-if="tab.dataError" class="sql-error">⚠ {{ tab.dataError }}</span>
                          </div>
                          <div class="result-table-wrap">
                            <AgGridResult
                              :columns="tab.dataColumns"
                              :column-types="tab.dataColumnTypes || []"
                              :rows="tab.dataPageRows"
                              :height="'100%'"
                              :editable="false"
                              :pagination="false"
                              :primary-keys="getDataPrimaryKeys(tab)"
                            />
                          </div>
                        </el-tab-pane>
                        <el-tab-pane :label="'执行明细' + (tab.dataStatements.length ? ' (' + tab.dataStatements.length + ')' : '')" name="statements">
                          <div class="stmt-list-pane">
                            <div class="stmt-list-title">
                              执行明细 ({{ tab.dataStatements.length }} 条)
                              <el-button link type="primary" size="small" @click="tab.dataStatements = []">清除</el-button>
                            </div>
                            <div v-if="!tab.dataStatements.length" class="stmt-empty">暂无执行记录</div>
                            <div v-for="(s, i) in tab.dataStatements" :key="i" class="stmt-item">
                              <span :class="['stmt-status', s.status]">{{ s.status === 'ok' ? '✓' : s.status === 'fail' ? '✗' : '…' }}</span>
                              <span class="stmt-sql">{{ s.sql }}</span>
                              <span :class="['stmt-msg', s.status === 'fail' ? 'fail' : '']">{{ s.msg }}</span>
                              <span v-if="s.costMs != null" class="stmt-cost">耗时 {{ s.costMs }} ms</span>
                            </div>
                          </div>
                        </el-tab-pane>
                      </el-tabs>
                      <div class="result-footer">
                        <div class="rf-left">
                          <el-tooltip content="刷新" placement="top">
                            <el-button size="small" circle icon="Refresh" @click="loadTableData(tab)" />
                          </el-tooltip>
                          <el-tooltip content="导出 CSV" placement="top">
                            <el-button size="small" circle icon="Download" @click="exportTableData(tab)" />
                          </el-tooltip>
                        </div>
                        <div class="rf-right">
                          <el-pagination
                            size="small"
                            background
                            layout="total, sizes, prev, pager, next, jumper"
                            :total="tab.dataFilteredTotal"
                            v-model:current-page="tab.dataPageNum"
                            v-model:page-size="tab.dataPageSize"
                            :page-sizes="[50, 100, 200, 500]"
                            @current-change="updateDataPageRows(tab)"
                            @size-change="updateDataPageRows(tab)"
                          />
                          <span class="rf-info" v-if="tab.dataCostMs != null">{{ tab.dataAllRows.length }} 行已获取 - {{ tab.dataCostMs }} ms</span>
                        </div>
                      </div>
                    </div>
                  </div>
                </el-tab-pane>

                <!-- ER图 tab -->
                <el-tab-pane label="ER图" name="er">
                  <ErDesigner :tables="tab.erTables" :columns-map="tab.erColumnsMap" :conn-id="tab.connId" :schema="tab.schema" :conn-list="connList" :fk-relations="tab.erFkRels" />
                </el-tab-pane>
              </el-tabs>
            </div>
          </el-tab-pane>
        </el-tabs>
        <div v-else class="wb-empty-wrap">
          <el-empty description="左侧数据源树: 双击连接打开 SQL 脚本, 双击表查看表结构" />
        </div>
      </div>
    </div>

    <!-- 数据迁移 / Excel 导入弹窗 -->
    <DataMigrateDialog v-if="migrateDialogVisible" @close="migrateDialogVisible = false" />
    <SchemaDiffDialog v-if="schemaDiffDialogVisible" @close="schemaDiffDialogVisible = false" />
    <DataCompareDialog v-if="dataCompareVisible" @close="dataCompareVisible = false" />
    <ExcelImportDialog v-if="excelImportDialogVisible" @close="excelImportDialogVisible = false" />
    <SqlLibraryDialog
      ref="sqlLibraryRef"
      v-model="sqlLibraryVisible"
      :connections="connList"
      :current-sql="sqlLibrarySql"
      :conn-id="sqlLibraryConnId"
      :schema="sqlLibrarySchema"
      @apply="applySqlLibraryEntry"
    />

    <el-drawer v-model="aiVisible" title="AI SQL 助手 (P5)" size="480px" destroy-on-close>
      <AiAssistant
        :current-sql="aiSql"
        :conn-list="connList"
        @close="aiVisible = false"
        @insert-sql="applyAiSql"
      />
    </el-drawer>

    <!-- 新建表 DDL 弹窗 -->
    <el-dialog v-model="ddlDialog.visible" title="新建表 (DDL)" width="700px" append-to-body>
      <el-form label-width="80px">
        <el-form-item label="连接">
          <el-select v-model="ddlDialog.connId" style="width: 100%" @change="onDdlConnChange">
            <el-option v-for="c in connList" :key="c.id" :label="c.connName" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据库">
          <el-select v-model="ddlDialog.schema" style="width: 100%">
            <el-option v-for="d in ddlDialog.dbList" :key="d" :label="d" :value="d" />
          </el-select>
        </el-form-item>
        <el-form-item label="DDL">
          <MonacoSqlEditor
            v-model="ddlDialog.sql"
            class="ddl-editor-box"
            :height="'280px'"
            :min-height="'220px'"
            :databases="ddlDialog.dbList || []"
            placeholder="CREATE TABLE `t_demo` (&#10;  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',&#10;  `name` varchar(64) DEFAULT NULL COMMENT '名称',&#10;  PRIMARY KEY (`id`)&#10;) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='示例表';"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" @click="submitCreateTable">执行创建</el-button>
        <el-button @click="ddlDialog.visible = false">取消</el-button>
      </template>
    </el-dialog>

    <!-- 列新增/编辑弹窗 -->
    <el-dialog v-model="colDialog.visible" :title="colDialog.title" width="560px" append-to-body>
      <el-form ref="colFormRef" :model="colForm" :rules="colRules" label-width="100px">
        <el-form-item label="列名" prop="columnName">
          <el-input v-model="colForm.columnName" placeholder="请输入列名" />
        </el-form-item>
        <el-form-item label="数据类型" prop="columnType">
          <el-input v-model="colForm.columnType" placeholder="如 varchar(64) / int / bigint" />
        </el-form-item>
        <el-form-item label="是否可空" prop="nullable">
          <el-radio-group v-model="colForm.nullable">
            <el-radio value="YES">可空</el-radio>
            <el-radio value="NO">非空</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="默认值" prop="defaultValue">
          <el-input v-model="colForm.defaultValue" placeholder="如 NULL / 0 / ''" />
        </el-form-item>
        <el-form-item label="自增" prop="extra">
          <el-switch v-model="colForm.autoIncrement" />
        </el-form-item>
        <el-form-item label="注释" prop="columnComment">
          <el-input v-model="colForm.columnComment" type="textarea" :rows="2" placeholder="请输入列注释" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" @click="submitColumn">确定</el-button>
        <el-button @click="colDialog.visible = false">取消</el-button>
      </template>
    </el-dialog>

    <!-- 树节点右键菜单 -->
    <div v-if="treeMenu.visible" class="tree-context-menu" :style="{ left: treeMenu.x + 'px', top: treeMenu.y + 'px' }" @contextmenu.prevent.stop>
      <template v-if="treeMenu.data && treeMenu.data.isTable">
        <div class="tcm-item" @click="menuViewTable">查看表<span class="tcm-kbd">F4</span></div>
        <div class="tcm-item" @click="menuEditData">编辑数据</div>
        <div class="tcm-item" @click="menuSqlConsole">在 SQL 控制台中读该数据</div>
        <div class="tcm-item has-sub" @mouseenter="genSqlOpen = true" @mouseleave="genSqlOpen = false">
          生成 SQL<span class="tcm-arrow">▶</span>
          <div v-show="genSqlOpen" class="tcm-sub" @mouseenter="genSqlOpen = true" @mouseleave="genSqlOpen = false">
            <div class="tcm-item" @click="menuGenSql('SELECT')">SELECT</div>
            <div class="tcm-item" @click="menuGenSql('INSERT')">INSERT</div>
            <div class="tcm-item" @click="menuGenSql('UPDATE')">UPDATE</div>
            <div class="tcm-item" @click="menuGenSql('DELETE')">DELETE</div>
            <div class="tcm-item" @click="menuGenSql('MERGE')">MERGE</div>
            <div class="tcm-item" @click="menuGenSql('DDL')">DDL</div>
          </div>
        </div>
        <div class="tcm-divider" />
        <div class="tcm-item" @click="menuCopyName">复制表名<span class="tcm-kbd">Ctrl+C</span></div>
        <div class="tcm-item" @click="menuRename">重命名<span class="tcm-kbd">F2</span></div>
        <div class="tcm-item danger" @click="menuDelete">删除<span class="tcm-kbd">Delete</span></div>
        <div class="tcm-item" @click="menuRefreshNode">刷新<span class="tcm-kbd">F5</span></div>
        <div class="tcm-divider" />
        <div class="tcm-item" @click="menuExportData">导出数据</div>
        <div class="tcm-item" @click="menuImportData">导入数据</div>
        <div class="tcm-item" @click="menuErView">查看 ER 图</div>
      </template>
      <template v-else-if="treeMenu.data && (treeMenu.data.isConn || treeMenu.data.isDb)">
        <div class="tcm-item" @click="menuNewSql">新建 SQL 脚本</div>
        <div class="tcm-item" @click="menuRefreshNode">刷新<span class="tcm-kbd">F5</span></div>
      </template>
      <template v-else-if="treeMenu.data && treeMenu.data.isColumn">
        <div class="tcm-item" @click="menuCopyColumn">复制列名</div>
      </template>
    </div>
  </div>
</template>

<script setup name="DbTool">
import {
  listAllDbConn,
  listDatabases,
  listTables,
  listColumns,
  listIndexes,
  listConstraints,
  listForeignKeys,
  listReferences,
  listTriggers,
  getTableDdl,
  executeDdl,
  dropTable,
  renameTable,
  addColumn,
  dropColumn,
  modifyColumn,
  executeSql,
  cancelSqlExecution,
  txnBegin,
  txnExecute,
  txnCommit,
  txnRollback,
  txnClose,
  exportDownload,
  queryGrid,
  queryDbData,
  previewGridChanges,
  commitGridChanges,
  issueStructureChangeConfirmation
} from '@/api/dbtool/index'
import SchemaDiffDialog from './SchemaDiffDialog.vue'
import DataCompareDialog from './DataCompareDialog.vue'
import MonacoSqlEditor from './MonacoSqlEditor.vue'
import RedisManager from './RedisManager.vue'
import EsManager from './EsManager.vue'
import DataMigrateDialog from './DataMigrateDialog.vue'
import ExcelImportDialog from './ExcelImportDialog.vue'
import SqlLibraryDialog from './SqlLibraryDialog.vue'
import AiAssistant from './AiAssistant.vue'
import AgGridResult from './AgGridResult.vue'
import AgGridTable from './AgGridTable.vue'
import ErDesigner from './ErDesigner.vue'
import { format as formatSqlText } from 'sql-formatter'
import { ElMessage, ElMessageBox } from 'element-plus'
import { nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import {
  Connection,  Coin,
  FolderOpened,
  Document,
  Key,
  InfoFilled,
  CaretRight,
  DocumentAdd,
  Menu,
  Lock,
  Connection as FkIcon,
  Share,
  Bell,
  Sort,
  DataAnalysis,
  Search,
  Document as DdlIcon
} from '@element-plus/icons-vue'

// 属性页左侧菜单
const attrMenus = [
  { key: 'columns', label: '列', icon: Menu },
  { key: 'constraints', label: '约束', icon: Lock },
  { key: 'foreignKeys', label: '外键', icon: FkIcon },
  { key: 'references', label: '引用', icon: Share },
  { key: 'triggers', label: '触发器', icon: Bell },
  { key: 'indexes', label: '索引', icon: Sort },
  { key: 'stats', label: '统计', icon: DataAnalysis },
  { key: 'ddl', label: 'DDL', icon: DdlIcon }
]

// 台账表格列配置 (AgGridTable, 样式复刻原 el-table)
const agtColumns = {
  columns: [
    { label: '列名', field: 'columnName', minWidth: 130 },
    { label: '类型', field: 'columnType', minWidth: 130 },
    { label: '可空', field: 'nullable', width: 80, align: 'center', render: 'tag', tagType: (v) => (v === 'NO' ? { type: 'danger', label: '否' } : { type: 'info', label: '是' }) },
    { label: '默认值', field: 'defaultValue', minWidth: 100 },
    { label: '键', field: 'columnKey', width: 80, align: 'center', render: 'tag', tagType: (v) => (v === 'PRI' ? { type: 'warning', label: '主键' } : v ? { type: 'info', label: v } : { type: 'info', label: '-' }) },
    { label: '自增', field: 'extra', width: 80, align: 'center', render: 'tag', tagType: (v) => (v && String(v).includes('auto_increment') ? { type: 'success', label: '是' } : { type: 'info', label: '-' }) },
    { label: '注释', field: 'columnComment', minWidth: 160 }
  ],
  constraints: [
    { label: '约束名', field: 'constraintName', minWidth: 180 },
    { label: '类型', field: 'constraintType', width: 140, align: 'center', render: 'tag', tagType: (v) => (v === 'PRIMARY KEY' ? { type: 'warning', label: v } : v === 'UNIQUE' ? { type: 'success', label: v } : { type: 'info', label: v }) },
    { label: '列名', field: 'columnName', minWidth: 150 },
    { label: '序号', field: 'ordinalPosition', width: 80, align: 'center' }
  ],
  foreignKeys: [
    { label: '外键名', field: 'fkName', minWidth: 180 },
    { label: '本表列', field: 'columnName', minWidth: 140 },
    { label: '引用表', field: 'referencedTableName', minWidth: 160 },
    { label: '引用列', field: 'referencedColumnName', minWidth: 160 },
    { label: '更新规则', field: 'updateRule', width: 110, align: 'center' },
    { label: '删除规则', field: 'deleteRule', width: 110, align: 'center' }
  ],
  references: [
    { label: '外键名', field: 'fkName', minWidth: 180 },
    { label: '引用表', field: 'tableName', minWidth: 160 },
    { label: '引用列', field: 'columnName', minWidth: 140 },
    { label: '被引用列', field: 'referencedColumnName', minWidth: 160 },
    { label: '更新规则', field: 'updateRule', width: 110, align: 'center' },
    { label: '删除规则', field: 'deleteRule', width: 110, align: 'center' }
  ],
  triggers: [
    { label: '触发器名', field: 'triggerName', minWidth: 160 },
    { label: '触发时机', field: 'timing', width: 100, align: 'center' },
    { label: '事件', field: 'event', width: 100, align: 'center' },
    { label: '语句', field: 'statement', minWidth: 300 },
    { label: '创建时间', field: 'created', width: 160 }
  ],
  indexes: [
    { label: '索引名', field: 'indexName', minWidth: 140 },
    { label: '列名', field: 'columnName', minWidth: 140 },
    { label: '序号', field: 'seqInIndex', width: 70, align: 'center' },
    { label: '唯一', field: 'nonUnique', width: 90, align: 'center', render: 'tag', tagType: (v) => (v === 0 ? { type: 'success', label: '唯一' } : { type: 'info', label: '非唯一' }) },
    { label: '类型', field: 'indexType', width: 100, align: 'center' },
    { label: '注释', field: 'indexComment', minWidth: 140 }
  ]
}

const connList = ref([])
const tabs = ref([])
const activeTab = ref('')
const sideWidth = ref(280)
const treeVersion = ref(0)
const treeRef = ref()
const currentTreeData = ref(null)
const treeKeyword = ref('')
let treeSearchTimer = null
const sqlLibraryRef = ref()
const sqlLibraryVisible = ref(false)
const sqlLibraryTargetKey = ref('')
const sqlLibrarySql = ref('')
const sqlLibraryConnId = ref()
const sqlLibrarySchema = ref('')
const aiVisible = ref(false)
const aiTargetKey = ref('')
const aiSql = ref('')

function openAiAssistant() {
  const active = tabs.value.find((entry) => entry.key === activeTab.value)
  if (active && active.type === 'sql') {
    aiTargetKey.value = active.key
    aiSql.value = active.sqlText || ''
  } else {
    const tab = openSqlTab()
    aiTargetKey.value = tab.key
    aiSql.value = ''
  }
  aiVisible.value = true
}

function applyAiSql(sql) {
  const tab = tabs.value.find((entry) => entry.key === aiTargetKey.value)
  if (!tab) {
    ElMessage.warning('当前 SQL 标签页已关闭')
    return
  }
  tab.sqlText = tab.sqlText ? tab.sqlText + '\n' + sql : sql
  activeTab.value = tab.key
  ElMessage.success('SQL 已插入编辑器')
}


function isConnReadOnly(connId) {
  return connList.value.some((conn) => String(conn.id) === String(connId) && conn.accessMode === 'READ_ONLY')
}

// 树节点过滤: 按 连接/库/表/列 名称匹配
function treeFilterNode(value, data) {
  if (!value) return true
  const kw = String(value).toLowerCase()
  return String(data.label || '').toLowerCase().includes(kw)
}

// 搜索输入处理: debounce 后重建树(库节点按最新关键词重新懒加载过滤表, 清除旧过滤状态), 再展开匹配节点并过滤
function handleTreeSearch() {
  if (treeSearchTimer) clearTimeout(treeSearchTimer)
  treeSearchTimer = setTimeout(async () => {
    const kw = (treeKeyword.value || '').trim()
    const kwl = kw.toLowerCase()
    // 重建树: 恢复初始折叠状态并让库节点重新按最新关键词懒加载(表加载时过滤, 避免渲染全量表)
    treeVersion.value++
    await nextTick()
    const tree = treeRef.value
    if (!tree) return
    if (!kw) return
    try {
      await expandTreeForSearch(kwl)
    } catch (e) {
      return
    }
    tree.filter(kwl)
  }, 350)
}

async function expandTreeForSearch(kwl) {
  const tree = treeRef.value
  if (!tree || !tree.store) return
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  const root = tree.store.root
  if (!root) return
  // 等待树根懒加载完成(连接列表)
  let tRoot = Date.now()
  while ((!root.childNodes || !root.childNodes.length) && Date.now() - tRoot < 15000) await sleep(60)
  if (!root.childNodes || !root.childNodes.length) return
  // 展开单个节点并等待懒加载完成(表节点不展开)
  const ensureLevel = async (node) => {
    if (!node || node.isLeaf) return
    if (node.data && node.data.isTable) return
    const t0 = Date.now()
    node.expand()
    if (!node.loaded) {
      while (!node.loaded && Date.now() - t0 < 15000) await sleep(60)
    }
  }
  // 第一层: 并行展开连接(加载库列表)
  await Promise.all(Array.from(root.childNodes).map((c) => ensureLevel(c)))
  // 第二层: 收集库, 只展开"库名匹配"的库; 若无库名匹配(表名搜索)则全部展开(库加载时已按关键词过滤表)
  const allDbs = []
  for (const c of Array.from(root.childNodes)) {
    if (c.childNodes) {
      for (const d of Array.from(c.childNodes)) {
        if (d.data && d.data.isDb) allDbs.push(d)
      }
    }
  }
  const matching = allDbs.filter((d) => String(d.data.label).toLowerCase().includes(kwl))
  const toExpand = matching.length ? matching : allDbs
  await Promise.all(toExpand.map((d) => ensureLevel(d)))
}

// SQL 编辑器组件实例注册表 (key -> SqlEditor exposed)
const sqlEditorRefs = new Map()
function registerSqlEditor(tab, el) {
  if (el) {
    sqlEditorRefs.set(tab.key, el)
  } else {
    sqlEditorRefs.delete(tab.key)
  }
}

// AG-Grid 组件实例注册表
const agGridRefs = new Map()
function registerAgGrid(tab, el) {
  if (el) {
    agGridRefs.set(tab.key, el)
  } else {
    agGridRefs.delete(tab.key)
  }
}

// 获取主键列
function getPrimaryKeys(tab) {
  if (!tab.columns) return []
  // 常见主键列名
  const pkNames = ['id', 'ID', 'user_id', 'role_id', 'dept_id', 'menu_id', 'post_id', 'dict_id', 'config_id', 'notice_id', 'oper_id', 'log_id', 'job_id', 'job_log_id']
  return tab.columns.filter(c => pkNames.includes(c) || c.toLowerCase().endsWith('_id'))
}

// 数据 tab 主键: 优先从表结构列(对象数组)取 PRI, 兜底常见主键名
function getDataPrimaryKeys(tab) {
  const colObjs = Array.isArray(tab.columns) && tab.columns.length && typeof tab.columns[0] === 'object' ? tab.columns : []
  const pri = colObjs.filter((c) => c.columnKey === 'PRI').map((c) => c.columnName)
  if (pri.length) return pri
  const pkNames = ['id', 'ID', 'user_id', 'role_id', 'dept_id', 'menu_id', 'post_id', 'dict_id', 'config_id', 'notice_id', 'oper_id', 'log_id', 'job_id', 'job_log_id']
  return (tab.dataColumns || []).filter((c) => pkNames.includes(c))
}

// 处理AG-Grid单元格编辑
function handleAgGridCellEdit(tab, params) {
  const { data, colId, oldValue, newValue } = params
  if (oldValue === newValue) return
  // 标记行为dirty
  if (!data.dirty) {
    data.dirty = true
    data.dirtyCols = {}
  }
  data.dirtyCols[colId] = true
  // 添加到editedRows
  if (!tab.editedRows.includes(data)) {
    tab.editedRows.push(data)
  }
}

// 处理AG-Grid单元格编辑开始
function handleAgGridEditingStarted(tab, params) {
  tab.editingCell = {
    rowIndex: params.rowIndex,
    colId: params.colId,
    value: params.value,
    data: params.data
  }
}

// 处理AG-Grid单元格编辑结束
function handleAgGridEditingStopped(tab, params) {
  tab.editingCell = null
}

// 右键菜单
const treeMenu = reactive({ visible: false, x: 0, y: 0, data: null })
const genSqlOpen = ref(false)

// 弹窗
const ddlDialog = reactive({ visible: false, connId: undefined, schema: undefined, dbList: [], sql: '' })
const migrateDialogVisible = ref(false)
const schemaDiffDialogVisible = ref(false)
const dataCompareVisible = ref(false)
const excelImportDialogVisible = ref(false)

function openMigrateDialog() {
  migrateDialogVisible.value = true
}

function openSchemaDiffDialog() {
  schemaDiffDialogVisible.value = true
}

function openExcelImportDialog() {
  excelImportDialogVisible.value = true
}

const colDialog = reactive({ visible: false, title: '' })
const colForm = ref({})
const colFormRef = ref()
const colRules = {
  columnName: [{ required: true, message: '列名不能为空', trigger: 'blur' }],
  columnType: [{ required: true, message: '数据类型不能为空', trigger: 'blur' }]
}

// ============ 左侧目录树(懒加载) ============
function loadTreeNode(node, resolve) {
  if (node.level === 0) {
    listAllDbConn()
      .then((res) => {
        connList.value = res.data || []
        resolve(
          connList.value.map((c) => ({
            id: 'conn_' + c.id,
            label: c.connName,
            isConn: true,
            connId: c.id,
            dbType: c.dbType,
            databaseName: c.databaseName,
            leaf: false
          }))
        )
      })
      .catch(() => resolve([]))
  } else if (node.data.isConn) {
    if (node.data.dbType === 'REDIS') {
      resolve([{ id: 'note_' + node.data.id, label: '双击连接打开 Redis 可视化管理', isNote: true, isRedisNote: true, connId: node.data.connId, leaf: true }])
      return
    }
    if (node.data.dbType === 'ELASTICSEARCH') {
      resolve([{ id: 'note_' + node.data.id, label: '双击连接打开 Elasticsearch 可视化管理', isNote: true, isEsNote: true, connId: node.data.connId, leaf: true }])
      return
    }
    listDatabases(node.data.connId)
      .then((res) => {
        resolve(
          (res.data || []).map((d) => ({
            id: node.data.connId + '_db_' + d,
            label: d,
            isDb: true,
            connId: node.data.connId,
            schema: d,
            leaf: false
          }))
        )
      })
      .catch(() => resolve([]))
  } else if (node.data.isDb) {
    listTables(node.data.connId, node.data.schema)
      .then((res) => {
        let list = res.data || []
        // 搜索激活时: 按关键词过滤表, 避免渲染全量表节点导致卡顿/崩溃
        const kw = (treeKeyword.value || '').trim().toLowerCase()
        if (kw) {
          list = list.filter((t) => (t.tableName + ' ' + (t.tableComment || '')).toLowerCase().includes(kw))
        }
        resolve(
          list.map((t) => ({
            id: node.data.connId + '_tbl_' + node.data.schema + '_' + t.tableName,
            label: t.tableComment ? `${t.tableName} (${t.tableComment})` : t.tableName,
            isTable: true,
            connId: node.data.connId,
            schema: node.data.schema,
            tableName: t.tableName,
            tableType: t.tableType,
            comment: t.tableComment,
            leaf: false
          }))
        )
      })
      .catch(() => resolve([]))
  } else if (node.data.isTable) {
    listColumns(node.data.connId, node.data.schema, node.data.tableName)
      .then((res) => {
        resolve(
          (res.data || []).map((col, i) => ({
            id: node.data.id + '_col_' + i,
            label: col.columnName,
            isColumn: true,
            leaf: true
          }))
        )
      })
      .catch(() => resolve([]))
  } else {
    resolve([])
  }
}

function handleTreeNodeClick(data) {
  // 记录选中节点, 新建 SQL 脚本时带出连接/库
  currentTreeData.value = data
}

function handleTreeNodeDblClick(data) {
  currentTreeData.value = data
  if (data.isConn) {
    const conn = connList.value.find((c) => c.id === data.connId)
    if (conn && conn.dbType === 'REDIS') {
      // 双击 Redis 连接: 打开 Redis 可视化管理
      openRedisTab(data.connId, conn.connName)
      return
    }
    if (conn && conn.dbType === 'ELASTICSEARCH') {
      // 双击 ES 连接: 打开 Elasticsearch 可视化管理
      openEsTab(data.connId, conn.connName)
      return
    }
    // 双击连接: 新建 SQL 脚本并绑定该连接
    openSqlTab(data.connId)
  } else if (data.isTable) {
    openTableTab(data.connId, data.schema, data.tableName, data.comment, data.tableType)
  } else if (data.isRedisNote) {
    const conn = connList.value.find((c) => c.id === data.connId)
    openRedisTab(data.connId, conn ? conn.connName : 'Redis')
  } else if (data.isEsNote) {
    const conn = connList.value.find((c) => c.id === data.connId)
    openEsTab(data.connId, conn ? conn.connName : 'Elasticsearch')
  }
}

// ============ Elasticsearch 可视化管理 ============
function openEsTab(connId, connName) {
  const exist = tabs.value.find((t) => t.type === 'es' && t.connId === connId)
  if (exist) {
    activeTab.value = exist.key
    return
  }
  const conn = connList.value.find((c) => c.id === connId)
  const tab = reactive({
    key: 'es_' + connId,
    type: 'es',
    title: (connName || conn.connName || 'Elasticsearch') + ' (ES)',
    connId,
    connName: connName || (conn ? conn.connName : ''),
    closable: true
  })
  tabs.value.push(tab)
  activeTab.value = tab.key
}

// ============ Redis 可视化管理 ============
function openRedisTab(connId, connName) {
  const exist = tabs.value.find((t) => t.type === 'redis' && t.connId === connId)
  if (exist) {
    activeTab.value = exist.key
    return
  }
  const conn = connList.value.find((c) => c.id === connId)
  const tab = reactive({
    key: 'redis_' + connId,
    type: 'redis',
    title: (connName || conn.connName || 'Redis') + ' (Redis)',
    connId,
    connName: connName || (conn ? conn.connName : ''),
    closable: true
  })
  tabs.value.push(tab)
  activeTab.value = tab.key
}

function refreshTree() {
  treeVersion.value++
}

// ============ 右侧多标签 ============
function openTableTab(connId, schema, table, comment, tableType) {
  const exist = tabs.value.find((t) => t.type === 'table' && t.connId === connId && t.schema === schema && t.table === table)
  if (exist) {
    activeTab.value = exist.key
    return
  }
  const conn = connList.value.find((c) => c.id === connId)
  const tab = reactive({
    key: 'table_' + Date.now(),
    type: 'table',
    title: table,
    connId,
    schema,
    table,
    comment,
    tableType,
    connName: conn ? conn.connName : '',
    // 属性页
    tableInfo: {},
    columns: [],
    indexes: [],
    constraints: [],
    foreignKeys: [],
    references: [],
    triggers: [],
    ddl: '',
    attrMenu: 'columns',
    currentColumn: undefined,
    // 数据页
    dataConnId: connId,
    dataSchema: schema,
    dataDbList: [],
    dataSql: '',
    dataEditorHeight: 140,
    dataColumns: [],
    dataColumnTypes: [],
    dataAllRows: [],
    dataPageRows: [],
    dataPageNum: 1,
    dataPageSize: 100,
    dataFilteredTotal: 0,
    dataFilterExpr: '',
    dataEmptyText: '暂无数据',
    dataStatements: [],
    dataCostMs: null,
    dataError: '',
    dataResultTab: 'result',
    // ER图
    erTables: [],
    erColumnsMap: {},
    erFkRels: [],
    activeSub: 'attr',
    closable: true
  })
  tabs.value.push(tab)
  activeTab.value = tab.key
  loadTableDetail(tab)
}

function loadTableDetail(tab) {
  listColumns(tab.connId, tab.schema, tab.table).then((res) => {
    tab.columns = res.data || []
  })
  listIndexes(tab.connId, tab.schema, tab.table).then((res) => {
    tab.indexes = res.data || []
  })
  listConstraints(tab.connId, tab.schema, tab.table).then((res) => {
    tab.constraints = res.data || []
  })
  listForeignKeys(tab.connId, tab.schema, tab.table).then((res) => {
    tab.foreignKeys = res.data || []
  })
  listReferences(tab.connId, tab.schema, tab.table).then((res) => {
    tab.references = res.data || []
  })
  listTriggers(tab.connId, tab.schema, tab.table).then((res) => {
    tab.triggers = res.data || []
  })
  getTableDdl(tab.connId, tab.schema, tab.table).then((res) => {
    tab.ddl = res.data || ''
    // 从DDL解析表基本信息
    parseTableInfo(tab)
  })
  // 加载ER图数据: 以当前表为中心, 按真实外键/引用关系自动扩展相关表(非全库表)
  loadErByFk(tab)
  loadTableData(tab)
}

// 按外键关系自动扩展 ER 图: 本表 + 本表外键引用的表 + 引用本表的表, 并收集真实字段级外键关系
function loadErByFk(tab) {
  Promise.all([
    listForeignKeys(tab.connId, tab.schema, tab.table).catch(() => []),
    listReferences(tab.connId, tab.schema, tab.table).catch(() => [])
  ]).then(([fksRaw, refsRaw]) => {
    const fks = (fksRaw && fksRaw.data) || []
    const refs = (refsRaw && refsRaw.data) || []
    const related = new Set([tab.table])
    const fkRels = []
    ;(fks || []).forEach((f) => {
      if (f.referencedTableName) {
        fkRels.push({
          srcTable: f.tableName, srcColumn: f.columnName,
          dstTable: f.referencedTableName, dstColumn: f.referencedColumnName
        })
        related.add(f.referencedTableName)
      }
    })
    ;(refs || []).forEach((r) => {
      if (r.tableName) {
        fkRels.push({
          srcTable: r.tableName, srcColumn: r.columnName,
          dstTable: r.referencedTableName || tab.table, dstColumn: r.referencedColumnName
        })
        related.add(r.tableName)
      }
    })
    const arr = [...related]
    tab.erTables = arr.map((tn) => ({ tableName: tn }))
    const map = {}
    let loaded = 0
    arr.forEach((tn) => {
      listColumns(tab.connId, tab.schema, tn).then((r) => {
        map[tn] = r.data || []
        loaded++
        if (loaded >= arr.length) {
          tab.erColumnsMap = map
          tab.erFkRels = fkRels
        }
      }).catch(() => {
        map[tn] = []
        loaded++
        if (loaded >= arr.length) {
          tab.erColumnsMap = map
          tab.erFkRels = fkRels
        }
      })
    })
  })
}

function parseTableInfo(tab) {  const ddl = tab.ddl || ''
  const info = {}
  const engineMatch = ddl.match(/ENGINE=(\w+)/i)
  if (engineMatch) info.engine = engineMatch[1]
  const charsetMatch = ddl.match(/DEFAULT\s+CHARSET=(\w+)/i)
  if (charsetMatch) info.charset = charsetMatch[1]
  const commentMatch = ddl.match(/COMMENT='([^']*)'/i)
  if (commentMatch && !tab.comment) tab.comment = commentMatch[1]
  tab.tableInfo = info
}

function loadTableData(tab) {
  const conn = connList.value.find((c) => c.id === tab.connId)
  if (conn && conn.dbType === 'REDIS') {
    tab.dataColumns = []
    tab.dataAllRows = []
    tab.dataPageRows = []
    tab.dataSql = '-- Redis 为 NoSQL, 不支持 SQL 查询'
    return
  }
  tab.dataSql = `SELECT * FROM \`${tab.schema}\`.\`${tab.table}\` LIMIT 200`
  if (tab.connId) {
    listDatabases(tab.connId).then((res) => {
      tab.dataDbList = res.data || []
    })
  }
  runTableDataSql(tab)
}

function onDataConnChange(tab, v) {
  tab.dataSchema = undefined
  tab.dataDbList = []
  if (v) {
    listDatabases(v).then((res) => {
      tab.dataDbList = res.data || []
    })
  }
}

function runTableDataSql(tab) {
  if (!tab.dataSql.trim()) {
    ElMessage.warning('请输入 SQL')
    return
  }
  tab.dataError = ''
  tab.dataStatements = []
  tab.dataColumns = []
  tab.dataAllRows = []
  tab.dataPageRows = []
  tab.dataEmptyText = '执行中...'
  tab.dataCostMs = null
  const t0 = Date.now()
  const stmts = splitSqlStatements(tab.dataSql)
  if (!stmts.length) {
    ElMessage.warning('未找到可执行的 SQL 语句')
    return
  }
  runDataStatements(tab, stmts, 0, t0)
}

function runDataStatements(tab, stmts, idx, t0) {
  if (idx >= stmts.length) {
    finishDataRun(tab, t0)
    return
  }
  const sql = stmts[idx]
  const item = reactive({ sql, status: 'running', msg: '' })
  tab.dataStatements.push(item)
  executeSql({ connId: tab.dataConnId || tab.connId, schema: tab.schema || undefined, sql })
    .then((res) => {
      const r = res.data || {}
      if (r.columns && r.columns.length) {
        item.status = 'ok'
        item.msg = `查询 ${(r.rows || []).length} 行`
        item.costMs = r.costMs ?? 0
        tab.dataColumns = r.columns
        tab.dataColumnTypes = r.columnTypes || []
        loadDataResultRows(tab, r.rows || [])
        tab.dataEmptyText = r.rows && r.rows.length ? '' : '查询完成, 无结果'
      } else {
        item.status = 'ok'
        item.msg = `影响 ${r.affectedRows ?? 0} 行`
        item.costMs = r.costMs ?? 0
      }
      runDataStatements(tab, stmts, idx + 1, t0)
    })
    .catch((err) => {
      item.status = 'fail'
      item.msg = err?.msg || err?.message || '执行失败'
      tab.dataError = `${sql}\n→ ${item.msg}`
      // 拦截器已弹过中文提示(isHandled=true), 避免重复弹窗(中英双弹/双中文)
      if (!err?.isHandled) ElMessage.error(item.msg)
      finishDataRun(tab, t0)
    })
}

function finishDataRun(tab, t0) {
  tab.dataCostMs = Date.now() - t0
}

function loadDataResultRows(tab, rows) {
  const withKey = (rows || []).map((r, i) =>
    Object.assign({}, r, { rowKey: 'dr_' + tab.key + '_' + i + '_' + Date.now() })
  )
  tab.dataAllRows = withKey
  tab.dataFilterExpr = ''
  tab.dataPageNum = 1
  updateDataPageRows(tab)
}

function applyDataFilter(tab) {
  tab.dataPageNum = 1
  updateDataPageRows(tab)
}

function updateDataPageRows(tab) {
  const filtered = tab.dataAllRows.filter((r) => evalFilter(tab.dataFilterExpr, r))
  tab.dataFilteredTotal = filtered.length
  const start = (tab.dataPageNum - 1) * tab.dataPageSize
  tab.dataPageRows = filtered.slice(start, start + tab.dataPageSize)
}

function dataColTypeTag(tab, idx) {
  const t = (tab.dataColumnTypes && tab.dataColumnTypes[idx]) || ''
  const lower = t.toLowerCase()
  if (/int|decimal|float|double|numeric|number|bit/.test(lower)) {
    return { type: 'warning', label: t }
  }
  if (/date|time|timestamp|year/.test(lower)) {
    return { type: 'success', label: t }
  }
  if (/char|text|varchar|enum|json|blob/.test(lower)) {
    return { type: 'info', label: t }
  }
  return { type: 'info', label: t || '—' }
}

function exportTableData(tab) {
  if (!tab.dataAllRows.length) {
    ElMessage.warning('没有可导出的数据')
    return
  }
  exportCsv(tab.table || 'result', tab.dataColumns, tab.dataAllRows)
}

// SQL 脚本 tab
function openSqlTab(preConnId, preSchema) {
  const sel = currentTreeData.value
  let connId
  let schema = preSchema
  if (preConnId !== undefined) {
    connId = preConnId
  } else if (sel && sel.isConn) {
    connId = sel.connId
  } else if (sel && sel.isDb) {
    connId = sel.connId
    schema = sel.schema
  } else {
    connId = connList.value.find((c) => c.dbType !== 'REDIS')?.id || connList.value[0]?.id
  }
  const tab = reactive({
    key: 'sql_' + Date.now(),
    type: 'sql',
    title: 'SQL 脚本',
    connId,
    schema: schema || undefined,
    dbList: [],
    tableList: [],
    sqlText: '',
    editorHeight: 200,
    columns: [],
    columnTypes: [],
    rows: [],
    emptyText: '暂无数据',
    statements: [],
    costMs: null,
    affectedRows: null,
    error: '',
    // 结果网格
    activeResultTab: 'result',
    allRows: [],
    pageRows: [],
    pageNum: 1,
    pageSize: 100,
    filteredTotal: 0,
    filterExpr: '',
    selectedRows: [],
    deletedRows: [],
    editedRows: [],
    lastSql: '',
    currentRow: null,
    activeCell: null,
    editingCell: null,
    sortState: null,
    txnSession: null,
    gridServer: false,
    gridTotal: 0,
    closable: true
  })
  tabs.value.push(tab)
  activeTab.value = tab.key
  if (tab.connId) {
    listDatabases(tab.connId).then((res) => {
      tab.dbList = res.data || []
      if (tab.schema && !tab.dbList.includes(tab.schema)) {
        tab.schema = undefined
      }
      // 默认选择第一个数据库并加载表名
      if (!tab.schema && tab.dbList.length > 0) {
        tab.schema = tab.dbList[0]
        onSqlSchemaChange(tab, tab.schema)
      }
    })
  }
}

function onSqlConnChange(tab, v) {
  tab.schema = undefined
  tab.dbList = []
  tab.tableList = []
  tab.txnSession = null
  if (v) {
    listDatabases(v).then((res) => {
      tab.dbList = res.data || []
    })
  }
}

// 选择数据库时获取表名列表
function onSqlSchemaChange(tab, v) {
  tab.tableList = []
  if (v && tab.connId) {
    listTables(tab.connId, v).then((res) => {
      tab.tableList = (res.data || []).map((t) => t.tableName || t.name || t)
    })
  }
}

function openSqlLibrary(tab) {
  sqlLibraryTargetKey.value = tab.key
  sqlLibrarySql.value = tab.sqlText || ''
  sqlLibraryConnId.value = tab.connId
  sqlLibrarySchema.value = tab.schema || ''
  sqlLibraryVisible.value = true
}

function applySqlLibraryEntry(item) {
  const tab = tabs.value.find((entry) => entry.key === sqlLibraryTargetKey.value)
  if (!tab) {
    ElMessage.warning('当前 SQL 标签页已关闭')
    return
  }
  if (item.connId && !connList.value.some((conn) => String(conn.id) === String(item.connId))) {
    ElMessage.warning('脚本关联的数据源已不存在，请重新选择数据源')
    return
  }
  const applySql = () => {
    tab.sqlText = item.sql || ''
    tab.schema = undefined
    tab.tableList = []
    if (item.schema && tab.dbList.includes(item.schema)) {
      tab.schema = item.schema
      onSqlSchemaChange(tab, item.schema)
    } else if (item.schema) {
      ElMessage.warning('脚本关联的数据库当前不可用，已载入 SQL 内容')
    }
    activeTab.value = tab.key
  }
  if (item.connId && String(tab.connId) !== String(item.connId)) {
    tab.connId = item.connId
    tab.schema = undefined
    tab.dbList = []
    tab.tableList = []
    listDatabases(item.connId)
      .then((res) => {
        tab.dbList = res.data || []
        applySql()
      })
      .catch((error) => { if (!error?.isHandled) ElMessage.error(error?.msg || error?.message || '加载脚本数据源失败') })
    return
  }
  applySql()
}

// SQL格式化: 使用sql-formatter库, 根据数据库类型选择方言
function formatSql(tab, field = 'sqlText') {
  const sql = tab[field] || ''
  if (!sql.trim()) {
    ElMessage.warning('请输入 SQL')
    return
  }
  if (typeof formatSqlText !== 'function') {
    ElMessage.error('sql-formatter 未正确加载')
    return
  }
  // 根据连接获取数据库类型, 映射到sql-formatter方言
  const conn = connList.value.find((c) => c.id === tab.connId)
  const dbType = conn?.dbType || 'MYSQL'
  const dialectMap = {
    MYSQL: 'mysql',
    MARIADB: 'mariadb',
    POSTGRESQL: 'postgresql',
    ORACLE: 'plsql',
    SQLSERVER: 'tsql',
    MSSQL: 'tsql',
    SQLITE: 'sqlite',
    HIVE: 'hive',
    SPARK: 'spark',
    REDSHIFT: 'redshift',
    DB2: 'db2',
    TRINO: 'trino',
    PRESTO: 'trino'
  }
  const language = dialectMap[dbType?.toUpperCase()] || 'sql'
  try {
    const formatted = formatSqlText(sql, {
      language: language,
      tabWidth: 2,
      useTabs: false,
      keywordCase: 'upper',
      linesBetweenQueries: 2
    })
    tab[field] = formatted
    ElMessage.success('SQL 已格式化')
  } catch (e) {
    ElMessage.error('格式化失败: ' + (e?.message || e))
  }
}

// 执行计划: 给SQL加EXPLAIN前缀后执行
function explainSql(tab) {
  if (!tab.sqlText.trim()) {
    ElMessage.warning('请输入 SQL')
    return
  }
  let execText = ''
  const editor = sqlEditorRefs.get(tab.key)
  if (editor) {
    const sel = editor.getSelectionInfo()
    if (sel && sel.hasSelection && sel.selectedText.trim()) {
      execText = sel.selectedText.trim()
    } else {
      execText = getStatementAtCursor(tab.sqlText, sel ? sel.start : 0).trim()
    }
  } else {
    execText = tab.sqlText.trim()
  }
  if (execText.endsWith(';')) {
    execText = execText.slice(0, -1).trim()
  }
  if (!/^SELECT\b/i.test(execText)) {
    ElMessage.warning('执行计划仅支持 SELECT 语句')
    return
  }
  const explainSqlText = 'EXPLAIN ' + execText
  tab.lastExecText = explainSqlText
  tab.error = ''
  tab.statements = []
  tab.columns = []
  tab.rows = []
  tab.emptyText = '执行中...'
  tab.costMs = null
  tab.affectedRows = null
  const item = reactive({ sql: explainSqlText, status: 'running', msg: '' })
  tab.statements.push(item)
  const t0 = Date.now()
  executeSql({ connId: tab.connId, schema: tab.schema || undefined, sql: explainSqlText })
    .then((res) => {
      const r = res.data || {}
      item.status = 'ok'
      item.msg = r.rows ? `${r.rows.length} 行` : '完成'
      item.costMs = r.costMs ?? 0
      if (r.columns && r.columns.length) {
        item.query = { columns: r.columns, rows: r.rows || [], columnTypes: r.columnTypes || [] }
        tab.columns = r.columns
        tab.columnTypes = r.columnTypes || []
        tab.lastSql = explainSqlText
        loadResultRows(tab, r.rows || [])
        tab.emptyText = r.rows && r.rows.length ? '' : '无结果'
      } else {
        tab.emptyText = '无结果'
      }
      tab.costMs = Date.now() - t0
      tab.activeResultTab = 'result'
      sqlLibraryRef.value?.record(tab.lastExecText, tab.connId, tab.schema, {
        status: 'SUCCESS',
        durationMs: tab.costMs,
        rowsAffected: tab.affectedRows ?? undefined
      })
    })
    .catch((err) => {
      item.status = 'fail'
      item.msg = err?.msg || err?.message || '执行失败'
      tab.error = `${explainSqlText}\n→ ${item.msg}`
      if (!err?.isHandled) ElMessage.error(item.msg)
      tab.costMs = Date.now() - t0
      sqlLibraryRef.value?.record(tab.lastExecText, tab.connId, tab.schema, {
        status: 'FAILED',
        durationMs: tab.costMs,
        errorMsg: item.msg
      })
    })
}

function runSqlTab(tab) {
  if (tab.executing) {
    ElMessage.warning('已有查询正在执行, 请等待完成或先点击取消')
    return
  }
  if (!tab.sqlText.trim()) {
    ElMessage.warning('请输入 SQL')
    return
  }
  tab.error = ''
  // 1. 确定执行文本: 有选区执行选区, 无选区执行光标所在语句
  let execText = ''
  const editor = sqlEditorRefs.get(tab.key)
  if (editor) {
    const sel = editor.getSelectionInfo()
    if (sel && sel.hasSelection && sel.selectedText.trim()) {
      execText = sel.selectedText
    } else {
      execText = getStatementAtCursor(tab.sqlText, sel ? sel.start : 0)
    }
  } else {
    execText = tab.sqlText
  }
  // 1.5 自动给包含特殊字符的库名添加反引号
  execText = autoQuoteIdentifiers(execText, tab.dbList || [])
  // 2. 拆分为多条语句
  const stmts = splitSqlStatements(execText)
  if (!stmts.length) {
    ElMessage.warning('未找到可执行的 SQL 语句')
    return
  }
  tab.lastExecText = execText
  // 3. 逐条执行
  tab.statements = []
  tab.columns = []
  tab.rows = []
  tab.emptyText = '执行中...'
  tab.costMs = null
  tab.affectedRows = null
  tab.executing = true
  tab.cancelRequested = false
  const t0 = Date.now()
  let lastQuery = null
  let affectedTotal = 0
  runStatements(tab, stmts, 0, t0, (idx, item, lastQueryRef, affectedRef) => {
    if (item.status === 'ok') {
      if (item.query) {
        lastQueryRef.value = item.query
      }
      affectedRef.value += item.affectedRows || 0
    }
  })
}

// 自动给包含特殊字符的标识符（库名、表名）添加反引号
function autoQuoteIdentifiers(sql, dbList) {
  if (!sql || !dbList || dbList.length === 0) return sql
  let result = sql
  dbList.forEach((dbName) => {
    // 只处理包含特殊字符的库名
    if (!/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(dbName)) {
      const quotedDbName = '`' + dbName + '`'
      // 匹配未被反引号括起来的库名. 模式（如 ry-vue.）
      // 使用负向断言确保前面不是反引号
      const regex = new RegExp('(?<!`)' + dbName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\.', 'g')
      result = result.replace(regex, quotedDbName + '.')
    }
  })
  return result
}

// 递归串行执行, 避免 async 循环在组件销毁后更新状态
function runStatements(tab, stmts, idx, t0, onOk) {
  if (idx >= stmts.length) {
    finishSqlRun(tab, t0)
    return
  }
  const sql = stmts[idx]
  const item = reactive({ sql, status: 'running', msg: '' })
  tab.statements.push(item)
  const executionId = createExecutionId()
  tab.currentExecutionId = executionId
  tab.cancelRequested = false
  const lastQuery = { value: null }
  const affectedRef = { value: 0 }
  runStatementApi(tab, sql, executionId)
    .then((res) => {
      const r = res.data || {}
      // 高危SQL: 弹出确认框, 用户确认后重新执行
      if (r.highRisk) {
        item.status = 'pending'
        item.msg = '高危SQL待确认'
        ElMessageBox.confirm(r.warning || '检测到高危操作, 是否继续执行?', '高危SQL确认', {
          confirmButtonText: '确认执行',
          cancelButtonText: '取消',
          type: 'warning'
        }).then(() => {
          runStatementApi(tab, sql, executionId, r.confirmationToken)
            .then((res2) => {
              const r2 = res2.data || {}
              if (r2.columns && r2.columns.length) {
                item.status = 'ok'
                item.msg = `查询 ${(r2.rows || []).length} 行`
                item.costMs = r2.costMs ?? 0
                item.query = { columns: r2.columns, rows: r2.rows || [], columnTypes: r2.columnTypes || [] }
              } else {
                item.status = 'ok'
                item.msg = `影响 ${r2.affectedRows ?? 0} 行`
                item.costMs = r2.costMs ?? 0
                item.affectedRows = r2.affectedRows ?? 0
              }
              onOk && onOk(idx, item, lastQuery, affectedRef)
              runStatements(tab, stmts, idx + 1, t0, onOk)
            })
            .catch((err2) => {
              if (tab.cancelRequested) {
                item.status = 'cancel'
                item.msg = '查询已取消'
              } else {
                item.status = 'fail'
                item.msg = err2?.msg || err2?.message || '执行失败'
                tab.error = `${sql}\n→ ${item.msg}`
                if (!err2?.isHandled) ElMessage.error(item.msg)
              }
              finishSqlRun(tab, t0)
            })
        }).catch(() => {
          item.status = 'cancel'
          item.msg = '用户取消'
          runStatements(tab, stmts, idx + 1, t0, onOk)
        })
        return
      }
      if (r.columns && r.columns.length) {
        item.status = 'ok'
        item.msg = `查询 ${(r.rows || []).length} 行`
        item.costMs = r.costMs ?? 0
        item.query = { columns: r.columns, rows: r.rows || [], columnTypes: r.columnTypes || [] }
      } else {
        item.status = 'ok'
        item.msg = `影响 ${r.affectedRows ?? 0} 行`
        item.costMs = r.costMs ?? 0
        item.affectedRows = r.affectedRows ?? 0
      }
      onOk && onOk(idx, item, lastQuery, affectedRef)
      runStatements(tab, stmts, idx + 1, t0, onOk)
    })
    .catch((err) => {
      if (tab.cancelRequested) {
        item.status = 'cancel'
        item.msg = '查询已取消'
      } else {
        item.status = 'fail'
        item.msg = err?.msg || err?.message || '执行失败'
        tab.error = `${sql}\n→ ${item.msg}`
        if (!err?.isHandled) ElMessage.error(item.msg)
      }
      finishSqlRun(tab, t0)
    })
}

function finishSqlRun(tab, t0) {
  tab.executing = false
  tab.currentExecutionId = null
  tab.cancelRequested = false
  tab.costMs = Date.now() - t0
  const stmts = tab.statements || []
  const last = [...stmts].toReversed().find((s) => s.status === 'ok' && s.query)
  if (last && last.query) {
    tab.columns = last.query.columns
    tab.columnTypes = last.query.columnTypes || []
    tab.lastSql = last.sql || tab.sqlText
    tab.gridServer = false
    tab.gridTotal = 0
    loadResultRows(tab, last.query.rows)
    tab.emptyText = last.query.rows.length ? '' : '查询完成, 无结果'
  } else {
    const hasQuery = stmts.some((s) => s.status === 'ok' && s.query)
    if (!hasQuery) {
      tab.columns = []
      tab.rows = []
      tab.allRows = []
      tab.pageRows = []
      tab.filteredTotal = 0
      tab.emptyText = stmts.length ? '全部为写操作, 无查询结果' : '暂无数据'
    }


  }

  const okCount = stmts.filter((s) => s.status === 'ok').length
  const failCount = stmts.filter((s) => s.status === 'fail').length
  if (failCount) {
    tab.affectedRows = null
  } else if (okCount) {
    tab.affectedRows = okCount
  }
  // 执行历史: 按整组语句最终状态记录(成功/失败/取消)
  const hasCancel = stmts.some((s) => s.status === 'cancel')
  const historyStatus = failCount ? 'FAILED' : hasCancel ? 'CANCELLED' : okCount ? 'SUCCESS' : 'FAILED'
  const errLines = String(tab.error || '').split('\n')
  const historyError = errLines.length > 1 ? errLines.slice(1).join('\n').slice(0, 500) : (tab.error ? String(tab.error).slice(0, 500) : undefined)
  sqlLibraryRef.value?.record(tab.lastExecText || tab.sqlText, tab.connId, tab.schema, {
    status: historyStatus,
    durationMs: tab.costMs,
    rowsAffected: tab.affectedRows ?? undefined,
    errorMsg: historyError
  })
}

// 事务内执行: 有活动事务会话时走事务接口, 否则走普通执行接口
function runStatementApi(tab, sql, executionId, confirmationToken) {
  if (tab.txnSession && tab.txnSession.sessionId) {
    return txnExecute({ sessionId: tab.txnSession.sessionId, sql, confirmationToken })
  }
  return executeSql({ connId: tab.connId, schema: tab.schema || undefined, sql, executionId, confirmationToken })
}

async function beginTxn(tab) {
  if (!tab.connId) {
    ElMessage.warning('请先选择连接')
    return
  }
  try {
    const res = await txnBegin({ connId: tab.connId, schema: tab.schema || undefined })
    tab.txnSession = res.data
    ElMessage.success('事务已开启: ' + (tab.txnSession?.connName || ''))
  } catch (e) {
    ElMessage.error(e?.msg || e?.message || '开启事务失败')
  }
}

async function commitTxn(tab) {
  const sessionId = tab.txnSession?.sessionId
  if (!sessionId) return
  try {
    await txnCommit({ sessionId })
    tab.txnSession = null
    ElMessage.success('事务已提交')
  } catch (e) {
    ElMessage.error(e?.msg || e?.message || '事务提交失败')
  }
}

async function rollbackTxn(tab) {
  const sessionId = tab.txnSession?.sessionId
  if (!sessionId) return
  try {
    await txnRollback({ sessionId })
    tab.txnSession = null
    ElMessage.success('事务已回滚')
  } catch (e) {
    ElMessage.error(e?.msg || e?.message || '事务回滚失败')
  }
}

async function closeTxn(tab) {
  const sessionId = tab.txnSession?.sessionId
  if (!sessionId) return
  try {
    await txnClose(sessionId)
    tab.txnSession = null
    ElMessage.success('事务会话已关闭')
  } catch (e) {
    ElMessage.error(e?.msg || e?.message || '关闭事务会话失败')
  }
}

// 顶层函数: 生成执行ID / 取消查询（必须在 setup 作用域, 供 runStatements 与模板使用）
function createExecutionId() {
  return globalThis.crypto?.randomUUID?.() || `dbtool-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

async function cancelSqlTab(tab) {
  if (!tab.currentExecutionId) return
  const executionId = tab.currentExecutionId
  // 立即复位执行状态, 让按钮恢复可用; 后端最终会结束请求(有界超时)
  tab.cancelRequested = true
  tab.executing = false
  try {
    const response = await cancelSqlExecution(executionId)
    if (response.data) {
      if (tab.currentExecutionId === executionId) {
        ElMessage.info('已发送取消请求')
      }
    } else {
      ElMessage.warning('查询已结束或无法取消')
    }
  } catch (error) {
    ElMessage.error(error?.msg || error?.message || '取消查询失败')
  }
}


// 加载查询结果到结果网格
function loadResultRows(tab, rows) {
  const withKey = (rows || []).map((r, i) =>
    Object.assign({}, r, {
      rowKey: 'r_' + tab.key + '_' + i + '_' + Date.now(),
      originalValues: Object.assign({}, r)
    })
  )
  tab.allRows = withKey
  tab.rows = withKey.slice()
  tab.filterExpr = ''
  tab.pageNum = 1
  tab.deletedRows = []
  tab.editedRows = []
  tab.selectedRows = []
  tab.currentRow = null
  tab.activeCell = null
  tab.editingCell = null
  tab.sortState = null
  updatePageRows(tab)
}

// 按过滤表达式 + 分页更新 tab.pageRows
function updatePageRows(tab) {
  if (tab.gridServer) {
    loadServerGrid(tab)
    return
  }
  const filtered = tab.rows.filter((r) => evalFilter(tab.filterExpr, r))
  tab.filteredTotal = filtered.length
  const start = (tab.pageNum - 1) * tab.pageSize
  tab.pageRows = filtered.slice(start, start + tab.pageSize)
}

// ============ 服务端网格模式: 过滤/排序/分页走后端(P2) ============
function toggleServerGrid(tab) {
  tab.gridServer = !tab.gridServer
  tab.pageNum = 1
  updatePageRows(tab)
}

async function loadServerGrid(tab) {
  if (!tab.lastSql || !tab.connId) return
  let filters = []
  try {
    filters = parseServerFilters(tab.filterExpr)
  } catch (e) {
    ElMessage.error(e.message)
    tab.gridServer = false
    return
  }
  const sort = tab.sortState && tab.sortState.column
    ? { column: tab.sortState.column, order: tab.sortState.order || 'asc' }
    : null
  try {
    const res = await queryGrid({
      connId: tab.connId,
      sql: tab.lastSql,
      pageNum: tab.pageNum,
      pageSize: tab.pageSize,
      filters,
      sort
    })
    tab.columns = res.data?.columns || []
    tab.columnTypes = res.data?.columnTypes || []
    tab.rows = res.data?.rows || []
    tab.pageRows = tab.rows
    tab.gridTotal = res.data?.total || 0
    tab.error = ''
  } catch (e) {
    ElMessage.error(e?.msg || e?.message || '服务端查询失败')
  }
}

// 轻量服务端过滤表达式解析: col op value, AND 连接
// 支持: = != <> > >= < <= like / is null / is not null / in (a,b)
function parseServerFilters(expr) {
  if (!expr || !expr.trim()) return []
  const parts = expr.split(/\s+AND\s+/i)
  const filters = []
  for (const p of parts) {
    const s = p.trim()
    if (!s) continue
    let m
    if ((m = s.match(/^(\w+)\s+is\s+not\s+null$/i))) {
      filters.push({ column: m[1], op: 'notnull' })
      continue
    }
    if ((m = s.match(/^(\w+)\s+is\s+null$/i))) {
      filters.push({ column: m[1], op: 'isnull' })
      continue
    }
    if ((m = s.match(/^(\w+)\s+in\s*\(([^)]+)\)$/i))) {
      const vals = m[2].split(',').map((v) => unquote(v.trim()))
      filters.push({ column: m[1], op: 'in', value: vals })
      continue
    }
    if ((m = s.match(/^(\w+)\s*(=|!=|<>|>=|<=|>|<|like)\s*(.+)$/i))) {
      const opMap = { '=': 'eq', '!=': 'ne', '<>': 'ne', '>': 'gt', '>=': 'ge', '<': 'lt', '<=': 'le', like: 'like' }
      filters.push({ column: m[1], op: opMap[m[2].toLowerCase()], value: unquote(m[3].trim()) })
      continue
    }
    throw new Error('服务端过滤暂不支持该表达式: ' + s)
  }
  return filters
}

function unquote(v) {
  if (v && ((v.startsWith("'") && v.endsWith("'")) || (v.startsWith('"') && v.endsWith('"')))) {
    return v.slice(1, -1)
  }
  return v
}

function applyResultFilter(tab) {
  tab.pageNum = 1
  updatePageRows(tab)
}

// 行编辑标记
function markCellDirty(tab, row, col) {
  if (!row || row.isNew) return
  if (!row.dirty) row.dirty = true
  if (!row.dirtyCols) row.dirtyCols = {}
  row.dirtyCols[col] = true
  if (!tab.editedRows.includes(row)) tab.editedRows.push(row)
}

// ============ 结果网格: 双击编辑 / 排序 / 单元格交互 ============
function isEditingCell(tab, row, col) {
  return !!(tab.editingCell && tab.editingCell.rowKey === row.rowKey && tab.editingCell.col === col)
}
function isActiveCell(tab, row, col) {
  return !!(tab.activeCell && tab.activeCell.rowKey === row.rowKey && tab.activeCell.col === col)
}
function handleCellClick(tab, row, col) {
  tab.activeCell = { rowKey: row.rowKey, col }
}
function handleCurrentRowChange(tab, row) {
  tab.currentRow = row || null
}
function cellDblClick(tab, row, col) {
  tab.activeCell = { rowKey: row.rowKey, col }
  tab.editingCell = { rowKey: row.rowKey, col, value: row[col] }
}
function commitCellEdit(tab, row, col) {
  if (!tab.editingCell) return
  const prev = tab.editingCell.value
  tab.editingCell = null
  if (prev !== row[col]) {
    markCellDirty(tab, row, col)
  }
}
function cancelCellEdit(tab, row, col) {
  if (!tab.editingCell) return
  const rk = tab.editingCell.rowKey
  const c = tab.editingCell.col
  const r = tab.rows.find((x) => x.rowKey === rk)
  if (r) r[c] = tab.editingCell.value
  tab.editingCell = null
}
function cellInputKeydown(tab, e, row, col) {
  if (e.key === 'Enter') {
    commitCellEdit(tab, row, col)
  } else if (e.key === 'Escape') {
    cancelCellEdit(tab, row, col)
  }
}
function onResultSort(tab, { prop, order }) {
  if (tab.gridServer) {
    tab.sortState = order === 'ascending' || order === 'descending'
      ? { column: prop, order: order === 'descending' ? 'desc' : 'asc' }
      : null
    updatePageRows(tab)
    return
  }
  tab.sortState = { prop, order }
  const cols = tab.columns
  const types = tab.columnTypes || []
  const idx = cols.indexOf(prop)
  const type = idx > -1 ? (types[idx] || '').toLowerCase() : ''
  const isNum = /int|decimal|float|double|number|numeric|tinyint|bigint|smallint|mediumint/.test(type)
  const isDate = /date|time|timestamp/.test(type)
  // 基于 allRows 原始顺序排序; order=null 时取消排序恢复原序
  const arr = tab.allRows.slice()
  if (order === 'ascending' || order === 'descending') {
    arr.sort((a, b) => {
      const va = a[prop]
      const vb = b[prop]
      let cmp = 0
      if (va === null || va === undefined || va === '') {
        cmp = vb === null || vb === undefined || vb === '' ? 0 : -1
      } else if (vb === null || vb === undefined || vb === '') {
        cmp = 1
      } else if (isNum) {
        // 数值比较: 超安全整数范围的 BIGINT 用 BigInt 精确比较, 避免精度丢失
        const sa = String(va).trim()
        const sb = String(vb).trim()
        const isIntA = /^-?\d+$/.test(sa)
        const isIntB = /^-?\d+$/.test(sb)
        if (isIntA && isIntB) {
          let ba, bb
          try {
            ba = BigInt(sa)
            bb = BigInt(sb)
          } catch (e) {
            ba = null
            bb = null
          }
          if (ba !== null && bb !== null) {
            cmp = ba < bb ? -1 : ba > bb ? 1 : 0
          } else {
            cmp = Number(va) - Number(vb)
          }
        } else {
          cmp = Number(va) - Number(vb)
        }
      } else if (isDate) {
        cmp = new Date(va).getTime() - new Date(vb).getTime()
      } else {
        cmp = String(va).localeCompare(String(vb), 'zh-Hans-CN')
      }
      return order === 'ascending' ? cmp : -cmp
    })
  }
  tab.rows = arr
  updatePageRows(tab)
}
function colTypeTag(tab, idx) {
  const t = (tab.columnTypes && tab.columnTypes[idx]) || ''
  const lower = t.toLowerCase()
  if (/int|decimal|float|double|numeric|number|bit/.test(lower)) {
    return { type: 'warning', label: t }
  }
  if (/date|time|timestamp|year/.test(lower)) {
    return { type: 'success', label: t }
  }
  if (/char|text|varchar|enum|json|blob/.test(lower)) {
    return { type: 'info', label: t }
  }
  return { type: 'info', label: t || '—' }
}

// 是否有待保存/可取消的变更(编辑中或已有 dirty/deleted)
function resultHasChanges(tab) {
  return !!(
    tab.editingCell ||
    (tab.editedRows && tab.editedRows.length) ||
    (tab.deletedRows && tab.deletedRows.length)
  )
}

// 双击字段名复制字段名
function copyColumnName(tab, col) {
  copyText(col, () => ElMessage.success(`已复制字段名: ${col}`))
}

// ============ 拖动调整 SQL 编辑器高度 ============
let dragResizeState = null
function startDragResize(tab, e) {
  e.preventDefault()
  dragResizeState = { tab, startY: e.clientY, startH: tab.editorHeight || 200 }
  document.body.style.cursor = 'ns-resize'
  document.body.style.userSelect = 'none'
  document.addEventListener('mousemove', onDragResizeMove)
  document.addEventListener('mouseup', stopDragResize)
}
function onDragResizeMove(e) {
  if (!dragResizeState) return
  const { tab, startY, startH } = dragResizeState
  const delta = e.clientY - startY
  const h = Math.min(Math.max(startH + delta, 80), 640)
  if (tab.editorHeight !== h) tab.editorHeight = Math.round(h)
}
function stopDragResize() {
  dragResizeState = null
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  document.removeEventListener('mousemove', onDragResizeMove)
  document.removeEventListener('mouseup', stopDragResize)
}

// 新增行
function addResultRow(tab) {
  if (!tab.columns.length) {
    ElMessage.warning('请先执行查询')
    return
  }
  const row = { isNew: true, rowKey: 'new_' + Date.now() }
  tab.columns.forEach((c) => {
    row[c] = null
  })
  tab.editedRows.push(row)
  tab.allRows = [...tab.allRows, row]
  tab.rows = [...tab.rows, row]
  tab.pageNum = Math.max(1, Math.ceil(tab.rows.length / tab.pageSize))
  updatePageRows(tab)
  // 自动选中新增行，确保复制/删除等操作能包含新增行
  const gridComp = agGridRefs.get(tab.key)
  if (gridComp && typeof gridComp.selectRows === 'function') {
    setTimeout(() => {
      gridComp.selectRows([row])
    }, 300)
  } else {
    // 降级：直接更新 selectedRows
    tab.selectedRows = [...(tab.selectedRows || []), row]
  }
}

// 删除选中行
function deleteSelectedRows(tab) {
  const sel = tab.selectedRows || []
  if (!sel.length) {
    ElMessage.warning('请先勾选要删除的行')
    return
  }
  ElMessageBox.confirm(`确定删除选中的 ${sel.length} 行? 点击「保存」后写回数据库`, '删除行', { type: 'warning' })
    .then(() => {
      sel.forEach((row) => {
        if (!row.isNew) {
          const ei = tab.editedRows.indexOf(row)
          if (ei > -1) tab.editedRows.splice(ei, 1)
          tab.deletedRows.push(row)
        } else {
          const ei = tab.editedRows.indexOf(row)
          if (ei > -1) tab.editedRows.splice(ei, 1)
        }
      })
      const selKeys = new Set(sel.map(r => r.rowKey))
      tab.rows = tab.rows.filter(r => !selKeys.has(r.rowKey))
      tab.allRows = tab.allRows.filter(r => !selKeys.has(r.rowKey))
      tab.selectedRows = []
      updatePageRows(tab)
    })
    .catch(() => {})
}

// 查询结果右键菜单状态
const resultCtxMenu = reactive({ visible: false, x: 0, y: 0, tab: null })

// 打开查询结果右键菜单
function openResultCtxMenu(tab, params) {
  // 关闭上一个菜单
  resultCtxMenu.visible = false
  // 限制菜单不超出视口
  const menuW = 240
  const menuH = 200
  const x = Math.min(params.x, window.innerWidth - menuW - 10)
  const y = Math.min(params.y, window.innerHeight - menuH - 10)
  resultCtxMenu.x = x
  resultCtxMenu.y = y
  resultCtxMenu.tab = tab
  resultCtxMenu.visible = true
  // 右键所在行也加入选中(如果未选中)
  if (params.data) {
    const sel = tab.selectedRows || []
    if (!sel.includes(params.data)) {
      const gridComp = agGridRefs.get(tab.key)
      if (gridComp && typeof gridComp.selectRows === 'function') {
        gridComp.selectRows([params.data])
      } else {
        tab.selectedRows = [...sel, params.data]
      }
    }
  }
  // 全局点击关闭菜单
  setTimeout(() => {
    document.addEventListener('click', closeResultCtxMenu, { once: true })
  }, 0)
}

function closeResultCtxMenu() {
  resultCtxMenu.visible = false
}

// 右键菜单: 刷新
function ctxMenuRefresh(tab) {
  closeResultCtxMenu()
  refreshResult(tab)
}

// 右键菜单: 切换结果面板 (查询结果 <-> 执行明细)
function ctxMenuSwitchPanel(tab) {
  closeResultCtxMenu()
  tab.activeResultTab = tab.activeResultTab === 'result' ? 'statements' : 'result'
}

// 右键菜单: 生成 SQL
function genRowSql(tab, mode) {
  closeResultCtxMenu()
  const rows = tab.selectedRows || []
  if (!rows.length) {
    ElMessage.warning('请先选择记录(右键选择或点击行选中)')
    return
  }
  const table = extractTableName(tab.lastSql || tab.sqlText)
  if (!table) {
    ElMessage.warning('无法从 SQL 识别表名')
    return
  }
  const cols = tab.columns || []
  if (!cols.length) {
    ElMessage.warning('无列信息')
    return
  }
  const pks = getPrimaryKeys(tab)
  const pk = pks.length ? pks[0] : cols[0]
  const t = (name) => '`' + name + '`'
  let sql = ''
  if (mode === 'select_eq') {
    const r = rows[0]
    sql = `SELECT * FROM ${t(table)} WHERE ${t(pk)} = ${quoteSqlVal(r[pk])}`
  } else if (mode === 'select_in') {
    const vals = rows.map((r) => quoteSqlVal(r[pk])).join(', ')
    sql = `SELECT * FROM ${t(table)} WHERE ${t(pk)} IN (${vals})`
  } else if (mode === 'insert') {
    // 选中 N 行 -> 一条 INSERT, N 个 VALUES 元组 (列为各选中行非空列的并集, 保持表列顺序)
    const unionCols = cols.filter((c) => rows.some((r) => r[c] !== null && r[c] !== undefined && r[c] !== ''))
    if (!unionCols.length) {
      ElMessage.warning('选中行均为空, 无法生成 INSERT')
      return
    }
    const values = rows.map((r) => `(${unionCols.map((c) => quoteSqlVal(r[c])).join(', ')})`)
    sql = `INSERT INTO ${t(table)} (${unionCols.map(t).join(', ')}) VALUES ${values.join(', ')}`
  } else if (mode === 'update') {
    const r = rows[0]
    const setCols = cols.filter((c) => c !== pk && r[c] !== null && r[c] !== undefined && r[c] !== '')
    sql = `UPDATE ${t(table)} SET ${setCols.map((c) => `${t(c)} = ${quoteSqlVal(r[c])}`).join(', ')} WHERE ${t(pk)} = ${quoteSqlVal(r[pk])}`
  } else if (mode === 'delete') {
    // 选中 N 行 -> N 条 DELETE (每条按主键定位)
    sql = rows.map((r) => `DELETE FROM ${t(table)} WHERE ${t(pk)} = ${quoteSqlVal(r[pk])}`).join(';\n') + ';'
  } else if (mode === 'create') {
    const typeMap = {
      INT: 'INT', BIGINT: 'BIGINT', TINYINT: 'TINYINT', SMALLINT: 'SMALLINT', MEDIUMINT: 'MEDIUMINT',
      DECIMAL: 'DECIMAL(10,2)', NUMERIC: 'NUMERIC(10,2)', FLOAT: 'FLOAT', DOUBLE: 'DOUBLE', REAL: 'DOUBLE',
      VARCHAR: 'VARCHAR(255)', CHAR: 'CHAR(50)', TEXT: 'TEXT', TINYTEXT: 'TEXT', MEDIUMTEXT: 'MEDIUMTEXT', LONGTEXT: 'LONGTEXT',
      DATE: 'DATE', TIME: 'TIME', DATETIME: 'DATETIME', TIMESTAMP: 'TIMESTAMP', YEAR: 'YEAR',
      BLOB: 'BLOB', TINYBLOB: 'TINYBLOB', MEDIUMBLOB: 'MEDIUMBLOB', LONGBLOB: 'LONGBLOB',
      JSON: 'JSON', BIT: 'BIT(1)', BOOL: 'TINYINT(1)', BOOLEAN: 'TINYINT(1)'
    }
    const lines = []
    lines.push(`CREATE TABLE ${t(table)} (`)
    cols.forEach((c, i) => {
      const rawType = ((tab.columnTypes || [])[i] || 'VARCHAR').toUpperCase()
      const dbType = typeMap[rawType] || `VARCHAR(255)`
      const pkFlag = c === pk ? ' PRIMARY KEY' : ''
      const comma = i < cols.length - 1 ? ',' : ''
      lines.push(`  ${t(c)} ${dbType}${pkFlag}${comma}`)
    })
    lines.push(')')
    sql = lines.join('\n')
  }
  if (!sql) return
  // 写入 SQL 编辑器
  const editor = sqlEditorRefs.get(tab.key)
  if (editor && typeof editor.setValue === 'function') {
    editor.setValue(sql)
    if (typeof editor.focus === 'function') editor.focus()
  } else {
    tab.sqlText = sql
  }
  ElMessage.success('SQL 已生成并写入编辑器, 请确认后执行')
}

// 复制选中行: 在结果集中新增行, 各单元格自动填充源行对应内容(可保存为 INSERT)
function copySelectedRows(tab) {
  const sel = tab.selectedRows || []
  if (!sel.length) {
    ElMessage.warning('请先勾选要复制的行')
    return
  }
  if (!tab.columns || !tab.columns.length) {
    ElMessage.warning('请先执行查询')
    return
  }
  const cols = tab.columns
  const newRows = sel.map((src) => {
    const row = { isNew: true, rowKey: 'copy_' + Date.now() + '_' + Math.random().toString(36).slice(2, 8) }
    cols.forEach((c) => {
      // 深拷贝源行值, 避免新行与源行共享引用(如 JSON 对象)
      row[c] = cloneValue(src[c])
    })
    return row
  })
  tab.editedRows.push(...newRows)
  tab.allRows = [...tab.allRows, ...newRows]
  tab.rows = [...tab.rows, ...newRows]
  tab.pageNum = Math.max(1, Math.ceil(tab.rows.length / tab.pageSize))
  updatePageRows(tab)
  // 自动选中复制出的新行
  const gridComp = agGridRefs.get(tab.key)
  if (gridComp && typeof gridComp.selectRows === 'function') {
    setTimeout(() => gridComp.selectRows(newRows), 300)
  } else {
    tab.selectedRows = [...(tab.selectedRows || []), ...newRows]
  }
  ElMessage.success(`已复制 ${newRows.length} 行到结果集底部, 点击「保存」写回数据库`)
}

// 深拷贝单元格值
function cloneValue(v) {
  if (v === null || v === undefined || typeof v !== 'object') return v
  try {
    return JSON.parse(JSON.stringify(v))
  } catch (e) {
    return Array.isArray(v) ? [...v] : { ...v }
  }
}

function copyText(text, done) {
  // 降级方案1：使用 document.execCommand('copy')，兼容 http/https
  const execCommandCopy = () => {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.left = '-9999px'
    ta.style.top = '0'
    ta.style.width = '1px'
    ta.style.height = '1px'
    ta.style.padding = '0'
    ta.style.border = 'none'
    ta.style.outline = 'none'
    ta.style.boxShadow = 'none'
    ta.style.background = 'transparent'
    ta.style.opacity = '0'
    ta.setAttribute('readonly', 'readonly')
    ta.tabIndex = -1
    document.body.appendChild(ta)
    ta.focus()
    ta.select()
    ta.setSelectionRange(0, text.length)
    let ok = false
    try {
      ok = document.execCommand('copy')
      if (!ok) {
        ta.focus()
        ta.select()
        ok = document.execCommand('copy')
      }
    } catch (e) {
      ok = false
    }
    if (window.getSelection) {
      const sel = window.getSelection()
      if (sel) sel.removeAllRanges()
    }
    document.body.removeChild(ta)
    return ok
  }

  // 降级方案2：使用 contenteditable div
  const contentEditableCopy = () => {
    const div = document.createElement('div')
    div.contentEditable = 'true'
    div.innerText = text
    div.style.position = 'fixed'
    div.style.left = '-9999px'
    div.style.top = '0'
    div.style.width = '1px'
    div.style.height = '1px'
    div.style.overflow = 'hidden'
    div.style.opacity = '0'
    document.body.appendChild(div)
    const range = document.createRange()
    range.selectNodeContents(div)
    const sel = window.getSelection()
    sel.removeAllRanges()
    sel.addRange(range)
    let ok = false
    try {
      ok = document.execCommand('copy')
    } catch (e) {
      ok = false
    }
    sel.removeAllRanges()
    document.body.removeChild(div)
    return ok
  }

  const fail = () => {
    ElMessage.warning('复制失败, 请手动框选单元格后 Ctrl+C')
  }

  // 确保文档获得焦点
  const ensureFocus = () => {
    try {
      if (typeof window.focus === 'function') window.focus()
      if (document.activeElement && typeof document.activeElement.blur === 'function') {
        document.activeElement.blur()
      }
      if (document.body && typeof document.body.focus === 'function') document.body.focus()
    } catch (e) { /* ignore */ }
  }

  // 方案1：navigator.clipboard.writeText（https 环境）
  if (navigator.clipboard && window.isSecureContext && typeof navigator.clipboard.writeText === 'function') {
    ensureFocus()
    navigator.clipboard.writeText(text).then(() => {
      done()
    }).catch(() => {
      // 方案2：ClipboardItem API
      if (typeof ClipboardItem !== 'undefined' && navigator.clipboard && typeof navigator.clipboard.write === 'function') {
        try {
          const item = new ClipboardItem({ 'text/plain': new Blob([text], { type: 'text/plain' }) })
          navigator.clipboard.write([item]).then(() => {
            done()
          }).catch(() => {
            // 方案3：execCommand
            ensureFocus()
            if (execCommandCopy() || contentEditableCopy()) {
              done()
            } else {
              fail()
            }
          })
        } catch (e) {
          ensureFocus()
          if (execCommandCopy() || contentEditableCopy()) {
            done()
          } else {
            fail()
          }
        }
      } else {
        // 方案3：execCommand
        ensureFocus()
        if (execCommandCopy() || contentEditableCopy()) {
          done()
        } else {
          fail()
        }
      }
    })
  } else {
    // http 环境直接使用 execCommand
    ensureFocus()
    if (execCommandCopy() || contentEditableCopy()) {
      done()
    } else {
      fail()
    }
  }
}

// 刷新结果: 重新执行上次 SQL
function refreshResult(tab) {
  if (!tab.lastSql) {
    ElMessage.warning('没有可刷新的 SQL, 请先执行查询')
    return
  }
  executeAndLoad(tab, tab.lastSql)
}

// 取消未保存变更: 重新加载
function cancelResult(tab) {
  if (!tab.lastSql) {
    ElMessage.warning('没有可取消的变更')
    return
  }
  executeAndLoad(tab, tab.lastSql)
}

function executeAndLoad(tab, sql) {
  tab.error = ''
  tab.statements = []
  executeSql({ connId: tab.connId, schema: tab.schema || undefined, sql })
    .then((res) => {
      const r = res.data || {}
      if (r.columns && r.columns.length) {
        tab.columns = r.columns
        tab.columnTypes = r.columnTypes || []
        tab.lastSql = sql
        loadResultRows(tab, r.rows || [])
        tab.emptyText = r.rows && r.rows.length ? '' : '查询完成, 无结果'
      } else {
        tab.columns = []
        tab.columnTypes = []
        tab.rows = []
        tab.allRows = []
        tab.pageRows = []
        tab.filteredTotal = 0
        tab.emptyText = '无查询结果'
      }
      tab.costMs = r.costMs
    })
    .catch((err) => {
      tab.error = err?.msg || err?.message || '执行失败'
      ElMessage.error(tab.error)
    })
}

// 预览后由后端在单一事务中安全写回数据库
async function saveResult(tab) {
  if (!tab.editedRows.length && !tab.deletedRows.length) {
    ElMessage.info('没有需要保存的变更')
    return
  }
  const table = extractTableName(tab.lastSql || tab.sqlText)
  if (!table) {
    ElMessage.warning('无法从 SQL 识别表名；仅支持 SELECT * FROM 单表结果')
    return
  }
  const changes = []
  tab.deletedRows.forEach((row) => {
    changes.push({
      operation: 'DELETE',
      values: {},
      originalValues: row.originalValues || {}
    })
  })
  tab.editedRows.forEach((row) => {
    const dirtyColumns = Object.keys(row['dirtyCols'] || {})
    if (row['isNew']) {
      if (!dirtyColumns.length) return
      const values = {}
      dirtyColumns.forEach((column) => {
        values[column] = row[column]
      })
      changes.push({ operation: 'INSERT', values })
    } else if (row['dirty'] && dirtyColumns.length) {
      const values = {}
      dirtyColumns.forEach((column) => {
        values[column] = row[column]
      })
      changes.push({
        operation: 'UPDATE',
        values,
        originalValues: row.originalValues || {}
      })
    }
  })
  if (!changes.length) {
    ElMessage.info('没有可保存的变更')
    return
  }
  const request = {
    connId: tab.connId,
    schema: tab.schema,
    table,
    sourceSql: tab.lastSql || tab.sqlText,
    changes
  }
  try {
    const preview = await previewGridChanges(request)
    const statements = preview.data?.statements || []
    await ElMessageBox.confirm(
      `共 ${preview.data?.changeCount || changes.length} 行，将在一个事务中提交以下参数化语句（实际数据值不会拼入 SQL）：\n\n${statements.join('\n')}`,
      '确认保存数据变更',
      { type: 'warning', confirmButtonText: '确认提交', cancelButtonText: '取消', distinguishCancelAndClose: true }
    )
    await commitGridChanges({ changes: request, confirmationToken: preview.data.confirmationToken })
    ElMessage.success('数据变更已在事务中提交')
    executeAndLoad(tab, tab.lastSql)
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error?.msg || error?.message || '数据变更失败')
  }
}

function extractTableName(sql) {
  if (!sql) return null
  // 支持 `db-name`.table、db.table、`table`、db-table 等形式
  // 先匹配反引号包裹的库名段 + 可选点 + 表名段
  const m = sql.match(/(?:FROM|INTO|UPDATE|JOIN)\s+((?:`[^`]+`\.)?`?[A-Za-z0-9_\u4e00-\u9fa5.-]+`?)/i)
  if (!m) return null
  let full = m[1]
  // 去掉反引号
  full = full.replace(/`/g, '')
  // 取最后一段(表名), 支持 db.table 形式
  const parts = full.split('.')
  return parts[parts.length - 1]
}

function quoteSqlVal(v) {
  if (v === null || v === undefined) return 'NULL'
  if (typeof v === 'number') return String(v)
  return "'" + String(v).replace(/'/g, "''") + "'"
}

async function exportResult(tab, format = 'csv') {
  const sql = tab.lastSql
  if (!sql) {
    ElMessage.warning('无可导出的查询 SQL, 请先执行查询')
    return
  }
  if (!tab.connId) {
    ElMessage.warning('连接无效')
    return
  }
  try {
    const blob = await exportDownload({ connId: tab.connId, sql, format })
    // 服务端异常时返回 JSON 错误而非文件
    if (blob && blob.type && blob.type.includes('json')) {
      const text = await blob.text()
      let msg = '导出失败'
      try {
        const j = JSON.parse(text)
        msg = j?.msg || j?.message || msg
      } catch (e) {
        /* ignore */
      }
      ElMessage.error(msg)
      return
    }
    const ext = format === 'json' ? 'json' : format === 'sql' ? 'sql' : 'csv'
    downloadBlob(blob, 'export_' + Date.now() + '.' + ext)
    ElMessage.success('导出完成')
  } catch (e) {
    ElMessage.error(e?.msg || e?.message || '导出失败')
  }
}

function downloadBlob(blob, filename) {
  if (!blob || !blob.size) return
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

// ============ 结果过滤表达式求值 (前端) ============
function evalFilter(expr, row) {
  if (!expr || !expr.trim()) return true
  try {
    const p = new FilterParser(expr, row)
    return !!p.parse()
  } catch (e) {
    return true
  }
}

class FilterParser {
  constructor(expr, row) {
    this.row = row
    this.tokens = this.tokenize(expr)
    this.pos = 0
  }
  tokenize(s) {
    const tokens = []
    let i = 0
    while (i < s.length) {
      const c = s[i]
      if (/\s/.test(c)) {
        i++
        continue
      }
      if (c === '(' || c === ')') {
        tokens.push({ t: c })
        i++
        continue
      }
      if (c === '>' || c === '<' || c === '=' || c === '!') {
        let op = c
        i++
        if ((c === '>' || c === '<' || c === '!') && s[i] === '=') {
          op += '='
          i++
        }
        if (c === '<' && s[i] === '>') {
          op = '<>'
          i++
        }
        tokens.push({ t: op })
        continue
      }
      if (c === "'") {
        let j = i + 1
        let str = ''
        while (j < s.length && s[j] !== "'") {
          str += s[j]
          j++
        }
        tokens.push({ t: 'STRING', v: str })
        i = j + 1
        continue
      }
      if (/[0-9]/.test(c) || (c === '-' && /[0-9]/.test(s[i + 1] || ''))) {
        let j = i
        let num = ''
        while (j < s.length && /[0-9.-]/.test(s[j])) {
          num += s[j]
          j++
        }
        tokens.push({ t: 'NUMBER', v: Number(num) })
        i = j
        continue
      }
      let j = i
      let w = ''
      while (j < s.length && /[A-Za-z0-9_\u4e00-\u9fa5]/.test(s[j])) {
        w += s[j]
        j++
      }
      const lw = w.toLowerCase()
      if (['and', 'or', 'not', 'like', 'is', 'null'].includes(lw)) {
        tokens.push({ t: 'KW', v: lw })
      } else {
        tokens.push({ t: 'IDENT', v: w })
      }
      i = j
    }
    return tokens
  }
  peek() {
    return this.tokens[this.pos]
  }
  next() {
    return this.tokens[this.pos++]
  }
  expect(t) {
    const tk = this.next()
    if (!tk || tk.t !== t) throw new Error('unexpected token')
    return tk
  }
  parse() {
    if (!this.tokens.length) return true
    const v = this.parseOr()
    if (this.pos < this.tokens.length) throw new Error('trailing tokens')
    return v
  }
  parseOr() {
    let v = this.parseAnd()
    while (this.peek() && this.peek().t === 'KW' && this.peek().v === 'or') {
      this.next()
      v = v || this.parseAnd()
    }
    return v
  }
  parseAnd() {
    let v = this.parseNot()
    while (this.peek() && this.peek().t === 'KW' && this.peek().v === 'and') {
      this.next()
      v = v && this.parseNot()
    }
    return v
  }
  parseNot() {
    if (this.peek() && this.peek().t === 'KW' && this.peek().v === 'not') {
      this.next()
      return !this.parseNot()
    }
    return this.parseComparison()
  }
  parseComparison() {
    const left = this.parseOperand()
    const tk = this.peek()
    if (!tk) return !!left
    if (tk.t === 'KW' && tk.v === 'is') {
      this.next()
      let neg = false
      if (this.peek() && this.peek().t === 'KW' && this.peek().v === 'not') {
        this.next()
        neg = true
      }
      this.expect({ t: 'KW', v: 'null' })
      const isNull = left === null || left === undefined || left === ''
      return neg ? !isNull : isNull
    }
    if (tk.t === 'KW' && tk.v === 'like') {
      this.next()
      const rv = this.parseOperand()
      const pat = String(rv)
        .replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
        .replace(/%/g, '.*')
        .replace(/_/g, '.')
      const re = new RegExp('^' + pat + '$', 'i')
      return re.test(left === null || left === undefined ? '' : String(left))
    }
    if (['>', '<', '>=', '<=', '=', '!=', '<>'].includes(tk.t)) {
      this.next()
      const rv = this.parseOperand()
      if (left === null || left === undefined || rv === null || rv === undefined) {
        return tk.t === '!=' || tk.t === '<>'
      }
      switch (tk.t) {
        case '>':
          return left > rv
        case '<':
          return left < rv
        case '>=':
          return left >= rv
        case '<=':
          return left <= rv
        case '=':
          return String(left) === String(rv)
        case '!=':
        case '<>':
          return String(left) !== String(rv)
      }
    }
    return !!left
  }
  parseOperand() {
    const tk = this.next()
    if (!tk) throw new Error('unexpected end')
    if (tk.t === 'IDENT') return this.row[tk.v]
    if (tk.t === 'NUMBER') return tk.v
    if (tk.t === 'STRING') return tk.v
    if (tk.t === 'KW' && tk.v === 'null') return null
    if (tk.t === '(') {
      const v = this.parseOr()
      this.expect({ t: ')' })
      return v
    }
    throw new Error('unexpected token')
  }
}

// 拆分 SQL: 按分号切分为多条非空语句
function splitSqlStatements(sql) {
  return (sql || '')
    .split(';')
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}

// 取光标所在语句 (按分号分段, 光标落在分号后归入下一条, 末尾以分号结尾时归入最后一条完整语句)
function getStatementAtCursor(text, cursor) {
  const pos = Math.max(0, Math.min(cursor || 0, text.length))
  const semis = []
  let idx = text.indexOf(';')
  while (idx !== -1) {
    semis.push(idx)
    idx = text.indexOf(';', idx + 1)
  }
  let start = 0
  for (let k = 0; k < semis.length; k++) {
    const s = semis[k]
    if (pos <= s) {
      return text.substring(start, s + 1).trim()
    }
    start = s + 1
  }
  // 光标在所有分号之后
  if (start >= text.length && semis.length) {
    const lastSemi = semis[semis.length - 1]
    const prevStart = semis.length > 1 ? semis[semis.length - 2] + 1 : 0
    return text.substring(prevStart, lastSemi + 1).trim()
  }
  return text.substring(start, text.length).trim()
}

function resetSql(tab, field = 'sqlText') {
  // 使用编辑器的 executeEdits 清空内容，以便支持撤销
  const editorInst = sqlEditorRefs.get(tab.key)
  if (editorInst && editorInst.getEditor) {
    const monacoEditor = editorInst.getEditor()
    if (monacoEditor) {
      const model = monacoEditor.getModel()
      if (model) {
        const fullRange = model.getFullModelRange()
        monacoEditor.executeEdits('clear', [{ range: fullRange, text: '' }])
      }
    }
  } else {
    tab[field] = ''
  }
  if (field === 'sqlText') {
    tab.columns = []
    tab.rows = []
    tab.costMs = null
    tab.affectedRows = null
    tab.error = ''
    tab.statements = []
  }
}

function closeTab(key) {
  const idx = tabs.value.findIndex((t) => t.key === key)
  if (idx > -1) {
    tabs.value.splice(idx, 1)
  }
  if (activeTab.value === key) {
    activeTab.value = tabs.value.length ? tabs.value[tabs.value.length - 1].key : ''
  }
}

function onTabClick() {
  // 预留: 切换 tab 时可同步侧栏高亮
}

// ============ 新建表 ============
function openCreateTable() {
  const connId = activeSqlConnId()
  if (!connId) {
    ElMessage.warning('请先在左侧选择数据源')
    return
  }
  ddlDialog.connId = connId
  ddlDialog.schema = undefined
  ddlDialog.dbList = []
  ddlDialog.sql = ''
  ddlDialog.visible = true
  listDatabases(connId).then((res) => {
    ddlDialog.dbList = res.data || []
  })
}

function onDdlConnChange(v) {
  ddlDialog.schema = undefined
  ddlDialog.dbList = []
  if (v) {
    listDatabases(v).then((res) => {
      ddlDialog.dbList = res.data || []
    })
  }
}

function activeSqlConnId() {
  const active = tabs.value.find((t) => t.key === activeTab.value)
  if (active && active.connId) return active.connId
  return connList.value[0]?.id
}

function submitCreateTable() {
  if (!ddlDialog.sql.trim()) {
    ElMessage.warning('请输入 DDL')
    return
  }
  executeDdl({ connId: ddlDialog.connId, sql: ddlDialog.sql }).then(() => {
    ElMessage.success('创建成功')
    ddlDialog.visible = false
    refreshTree()
  })
}

// ============ 删除表 ============
function handleDropTableFromTab(tab) {
  ElMessageBox.confirm(`是否确认删除表 ${tab.table}? 该操作不可恢复!`, '系统提示', { type: 'warning' })
    .then(() => {
      const operation = { connId: tab.connId, operation: 'DROP_TABLE', schema: tab.schema, table: tab.table }
      return issueStructureChangeConfirmation(operation).then((res) =>
        dropTable({ ...operation, confirmationToken: res.data })
      )
    })
    .then(() => {
      ElMessage.success('删除成功')
      closeTab(tab.key)
      refreshTree()
    })
    .catch(() => {})
}

// ============ 列维护(作用于表 tab) ============
function resetColForm() {
  colForm.value = {
    columnName: undefined,
    columnType: undefined,
    nullable: 'YES',
    defaultValue: undefined,
    autoIncrement: false,
    columnComment: undefined
  }
  colFormRef.value?.resetFields()
}

function openColumnDialog(tab, row) {
  resetColForm()
  colDialog.currentTab = tab
  if (row) {
    colDialog.title = '编辑列'
    colForm.value = {
      columnName: row.columnName,
      columnType: row.columnType,
      nullable: row.nullable || 'YES',
      defaultValue: row.defaultValue,
      autoIncrement: row.extra ? row.extra.includes('auto_increment') : false,
      columnComment: row.columnComment
    }
    colDialog.oldColumnName = row.columnName
  } else {
    colDialog.title = '新增列'
    colDialog.oldColumnName = undefined
  }
  colDialog.visible = true
}

function submitColumn() {
  colFormRef.value.validate((valid) => {
    if (!valid) return
    const tab = colDialog.currentTab
    if (!tab) return
    const column = {
      columnName: colForm.value.columnName,
      columnType: colForm.value.columnType,
      nullable: colForm.value.nullable,
      defaultValue: colForm.value.defaultValue || undefined,
      extra: colForm.value.autoIncrement ? 'auto_increment' : undefined,
      columnComment: colForm.value.columnComment
    }
    const done = () => {
      ElMessage.success('操作成功')
      colDialog.visible = false
      listColumns(tab.connId, tab.schema, tab.table).then((res) => {
        tab.columns = res.data || []
      })
      getTableDdl(tab.connId, tab.schema, tab.table).then((res) => {
        tab.ddl = res.data || ''
      })
    }
    if (colDialog.oldColumnName) {
      modifyColumn({ connId: tab.connId, schema: tab.schema, table: tab.table, oldColumnName: colDialog.oldColumnName, column }).then(done)
    } else {
      addColumn({ connId: tab.connId, schema: tab.schema, table: tab.table, column }).then(done)
    }
  })
}

function handleDropColumn(tab) {
  if (!tab.currentColumn) return
  const columnName = tab.currentColumn.columnName
  ElMessageBox.confirm(`是否确认删除列 ${columnName}?`, '系统提示', { type: 'warning' })
    .then(() => {
      const operation = {
        connId: tab.connId,
        operation: 'DROP_COLUMN',
        schema: tab.schema,
        table: tab.table,
        columnName
      }
      return issueStructureChangeConfirmation(operation).then((res) =>
        dropColumn({ ...operation, confirmationToken: res.data })
      )
    })
    .then(() => {
      ElMessage.success('删除成功')
      listColumns(tab.connId, tab.schema, tab.table).then((res) => {
        tab.columns = res.data || []
      })
      getTableDdl(tab.connId, tab.schema, tab.table).then((res) => {
        tab.ddl = res.data || ''
      })
    })
    .catch(() => {})
}

function copyDdl(tab) {
  navigator.clipboard.writeText(tab.ddl).then(() => ElMessage.success('已复制'))
}

// ============ 侧栏拖动 ============
function startResize(e) {
  e.preventDefault()
  const startX = e.clientX
  const startW = sideWidth.value
  const move = (ev) => {
    const w = startW + (ev.clientX - startX)
    sideWidth.value = Math.min(480, Math.max(200, w))
  }
  const up = () => {
    document.removeEventListener('mousemove', move)
    document.removeEventListener('mouseup', up)
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
  }
  document.addEventListener('mousemove', move)
  document.addEventListener('mouseup', up)
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
}

// ============ 树右键菜单 ============
function showTreeMenu(event, data) {
  treeMenu.data = data
  treeMenu.x = event.clientX
  treeMenu.y = event.clientY
  treeMenu.visible = true
  genSqlOpen.value = false
}

function closeTreeMenu() {
  treeMenu.visible = false
  treeMenu.data = null
  genSqlOpen.value = false
}

function onDocPointerDown() {
  closeTreeMenu()
}

onMounted(() => {
  document.addEventListener('click', onDocPointerDown)
  document.addEventListener('contextmenu', onDocPointerDown)
})
onUnmounted(() => {
  document.removeEventListener('click', onDocPointerDown)
  document.removeEventListener('contextmenu', onDocPointerDown)
  stopDragResize()
  if (treeSearchTimer) clearTimeout(treeSearchTimer)
})

function menuViewTable() {
  const d = treeMenu.data
  if (!d) return
  closeTreeMenu()
  openTableTab(d.connId, d.schema, d.tableName, d.comment, d.tableType)
}

function menuEditData() {
  const d = treeMenu.data
  if (!d) return
  closeTreeMenu()
  openTableTab(d.connId, d.schema, d.tableName, d.comment, d.tableType)
  const tab = tabs.value.find((t) => t.type === 'table' && t.connId === d.connId && t.schema === d.schema && t.table === d.tableName)
  if (tab) tab.activeSub = 'data'
}

function menuSqlConsole() {
  const d = treeMenu.data
  if (!d) return
  closeTreeMenu()
  openSqlTab(d.connId, d.schema)
  const tab = tabs.value[tabs.value.length - 1]
  if (tab && tab.type === 'sql') {
    tab.sqlText = `SELECT * FROM \`${d.tableName}\` LIMIT 200`
    tab.title = d.tableName
  }
}

function pushSqlToEditor(connId, schema, sql) {
  let tab = tabs.value.find((t) => t.type === 'sql')
  if (!tab) {
    openSqlTab(connId, schema)
    tab = tabs.value[tabs.value.length - 1]
  }
  tab.sqlText = sql
  activeTab.value = tab.key
}

function menuGenSql(kind) {
  const d = treeMenu.data
  if (!d) return
  genSqlOpen.value = false
  closeTreeMenu()
  if (kind === 'DDL') {
    getTableDdl(d.connId, d.schema, d.tableName).then((res) => {
      pushSqlToEditor(d.connId, d.schema, res.data || '')
    })
    return
  }
  if (kind === 'SELECT') {
    pushSqlToEditor(d.connId, d.schema, `SELECT * FROM \`${d.tableName}\`;`)
    return
  }
  listColumns(d.connId, d.schema, d.tableName).then((res) => {
    const cols = res.data || []
    const colNames = cols.map((c) => c.columnName)
    const colList = colNames.map((c) => `\`${c}\``).join(', ')
    const colQ = colNames.map(() => '?').join(', ')
    const pk = cols.find((c) => c.columnKey === 'PRI') || cols[0]
    const pkName = pk ? pk.columnName : colNames[0]
    let sql = ''
    if (kind === 'INSERT') {
      sql = `INSERT INTO \`${d.tableName}\` (${colList}) VALUES (${colQ});`
    } else if (kind === 'UPDATE') {
      const set = colNames.slice(0, 5).map((c) => `\`${c}\` = ?`).join(',\n  ')
      sql = `UPDATE \`${d.tableName}\`\nSET ${set}\nWHERE \`${pkName}\` = ?;`
    } else if (kind === 'DELETE') {
      sql = `DELETE FROM \`${d.tableName}\` WHERE \`${pkName}\` = ?;`
    } else if (kind === 'MERGE') {
      const c0 = colNames[0] || 'id'
      const c1 = colNames[1] || c0
      sql = `MERGE INTO \`${d.tableName}\` AS T\nUSING (SELECT 1 AS \`${c0}\`) AS S\nON (T.\`${c0}\` = S.\`${c0}\`)\nWHEN MATCHED THEN UPDATE SET \`${c1}\` = S.\`${c1}\`\nWHEN NOT MATCHED THEN INSERT (${colList}) VALUES (${colQ});`
    }
    pushSqlToEditor(d.connId, d.schema, sql)
  })
}

function menuCopyName() {
  const d = treeMenu.data
  if (!d) return
  closeTreeMenu()
  const copy = () => {
    const ta = document.createElement('textarea')
    ta.value = d.tableName
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    let ok = false
    try {
      ok = document.execCommand('copy')
    } catch (e) {
      ok = false
    }
    document.body.removeChild(ta)
    return ok
  }
  const done = () => ElMessage.success('已复制表名: ' + d.tableName)
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(d.tableName).then(done).catch(() => {
      copy() && done()
    })
  } else {
    copy() && done()
  }
}

function menuCopyColumn() {
  const d = treeMenu.data
  if (!d) return
  closeTreeMenu()
  const name = d.label || d.columnName || ''
  const copy = () => {
    const ta = document.createElement('textarea')
    ta.value = name
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    let ok = false
    try {
      ok = document.execCommand('copy')
    } catch (e) {
      ok = false
    }
    document.body.removeChild(ta)
    return ok
  }
  const done = () => ElMessage.success('已复制列名: ' + name)
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(name).then(done).catch(() => {
      copy() && done()
    })
  } else {
    copy() && done()
  }
}

function menuRename() {
  const d = treeMenu.data
  if (!d) return
  closeTreeMenu()
  ElMessageBox.prompt(`请输入表 ${d.tableName} 的新名称`, '重命名表', {
    inputValue: d.tableName,
    inputValidator: (v) => (v && v.trim() ? true : '表名不能为空')
  })
    .then(({ value }) => {
      return renameTable({ connId: d.connId, schema: d.schema, oldName: d.tableName, newName: value.trim() })
    })
    .then(() => {
      ElMessage.success('重命名成功')
      refreshTree()
    })
    .catch(() => {})
}

function menuDelete() {
  const d = treeMenu.data
  if (!d) return
  closeTreeMenu()
  ElMessageBox.confirm(`是否确认删除表 ${d.tableName}? 该操作不可恢复!`, '系统提示', { type: 'warning' })
    .then(() => {
      const operation = { connId: d.connId, operation: 'DROP_TABLE', schema: d.schema, table: d.tableName }
      return issueStructureChangeConfirmation(operation).then((res) =>
        dropTable({ ...operation, confirmationToken: res.data })
      )
    })
    .then(() => {
      ElMessage.success('删除成功')
      refreshTree()
    })
    .catch(() => {})
}

function menuRefreshNode() {
  closeTreeMenu()
  refreshTree()
}

function menuNewSql() {
  const d = treeMenu.data
  if (!d) return
  closeTreeMenu()
  currentTreeData.value = d
  openSqlTab(d.connId, d.isDb ? d.schema : undefined)
}

function menuExportData() {
  const d = treeMenu.data
  if (!d) return
  closeTreeMenu()
  queryDbData({ connId: d.connId, sql: `SELECT * FROM \`${d.tableName}\``, pageNum: 1, pageSize: 5000 })
    .then((res) => {
      const cols = res.data.columns || []
      const rows = res.data.rows || []
      exportCsv(d.tableName, cols, rows)
    })
    .catch((err) => ElMessage.error(err?.msg || '导出失败'))
}

function exportCsv(tableName, cols, rows) {
  const esc = (v) => {
    const s = v === null || v === undefined ? '' : String(v)
    return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
  }
  const header = cols.map((c) => esc(c)).join(',')
  const lines = rows.map((r) => cols.map((c) => esc(r[c])).join(','))
  const csv = '\uFEFF' + [header, ...lines].join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `${tableName}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
  ElMessage.success(`导出成功 ${rows.length} 行`)
}

function menuImportData() {
  closeTreeMenu()
  ElMessage.info('导入数据功能规划中, 可通过 SQL 控制台执行 INSERT 语句')
}

function menuErView() {
  closeTreeMenu()
  ElMessage.info('ER 图功能规划中')
}

// 初始化: 加载连接树(懒加载根节点由 el-tree 自动触发)
</script>

<style scoped>
.dbtool-workbench {
  height: calc(100vh - 100px);
  display: flex;
  flex-direction: column;
  padding: 8px;
}
.wb-header {
  margin-bottom: 8px;
  flex-shrink: 0;
}
.wb-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.wb-left {
  display: flex;
  gap: 8px;
  align-items: center;
}
.wb-group {
  font-size: 12px;
  color: var(--imp-info);
  font-weight: 500;
  letter-spacing: 0.5px;
  margin-left: 2px;
}
.wb-sep {
  width: 1px;
  height: 18px;
  background: var(--imp-border);
  margin: 0 2px;
  flex-shrink: 0;
}
.wb-hint {
  color: var(--imp-text-secondary);
  font-size: 12px;
  margin-left: 8px;
}
.wb-body {
  flex: 1;
  display: flex;
  gap: 0;
  min-height: 0;
}
.wb-side {
  flex-shrink: 0;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  background: #fff;
  overflow: auto;
  padding: 8px;
}
.side-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  padding: 4px 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  margin-bottom: 6px;
}
.side-count {
  color: var(--imp-text-secondary);
  font-weight: normal;
  font-size: 12px;
}
.side-search {
  padding: 0 4px 8px;
}
.side-search .el-input__wrapper {
  box-shadow: 0 0 0 1px var(--el-border-color) inset;
}
.side-search .el-input__wrapper.is-focus {
  box-shadow: 0 0 0 1px var(--el-color-primary) inset;
}
.tree-node {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.icon-conn {
  color: #409eff;
}
.icon-db {
  color: #e6a23c;
}
.icon-table {
  color: #67c23a;
}
.icon-col {
  color: var(--imp-text-secondary);
}
.tree-tag {
  margin-left: 4px;
}
.wb-resizer {
  width: 6px;
  cursor: col-resize;
  flex-shrink: 0;
  margin: 0 2px;
  border-radius: 3px;
}
.wb-resizer:hover {
  background: var(--el-color-primary-light-7);
}
.wb-main {
  flex: 1;
  min-width: 0;
  position: relative;
}
.wb-empty-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}
.wb-tabs {
  height: 100%;
}
.wb-tabs :deep(.el-tabs__content) {
  height: calc(100% - 40px);
  padding: 8px;
}
.wb-tabs :deep(.el-tab-pane) {
  height: 100%;
}

/* SQL 脚本 tab */
.sql-tab {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 6px;
}

/* Redis 管理 tab */
.redis-tab {
  height: 100%;
  min-height: 480px;
}
/* Elasticsearch 管理 tab */
.es-tab {
  height: 100%;
  min-height: 480px;
}
.sql-tab-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.sql-shortcut {
  margin-left: auto;
  color: #c0c4cc;
  font-size: 12px;
}

/* 执行明细 */
.stmt-list {
  flex-shrink: 0;
  max-height: 120px;
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  margin-bottom: 6px;
  background: #fafafa;
}
.stmt-list-title {
  padding: 4px 8px;
  font-size: 12px;
  color: var(--imp-text-secondary);
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: #f5f7fa;
}
.stmt-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 8px;
  font-size: 12px;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.stmt-item:last-child {
  border-bottom: none;
}
.stmt-status {
  flex-shrink: 0;
  width: 16px;
  text-align: center;
  font-weight: 700;
}
.stmt-status.ok {
  color: #52c41a;
}
.stmt-status.fail {
  color: #f56c6c;
}
.stmt-status.running {
  color: #409eff;
}
.stmt-sql {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #303133;
}
.stmt-msg {
  flex-shrink: 0;
  color: var(--imp-text-secondary);
}
.stmt-msg.fail {
  color: #f56c6c;
}
.stmt-cost {
  flex-shrink: 0;
  color: var(--imp-text-secondary);
  font-size: 12px;
  font-family: 'Consolas', 'Courier New', monospace;
}
.sql-editor-box {
  flex-shrink: 0;
}
.sql-area {
  flex-shrink: 0;
}
.sql-editor {
  flex-shrink: 0;
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: 12px;
}
/* 结果区 tabs: 查询结果 / 执行明细 并列 */
.result-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.result-sub-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.result-sub-tabs :deep(.el-tabs__header) {
  margin: 0;
}
.result-sub-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  padding: 6px 8px 2px;
  overflow: hidden;
}
.result-sub-tabs :deep(.el-tab-pane) {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.stmt-list-pane {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
.stmt-empty {
  padding: 28px 16px;
  text-align: center;
  color: var(--imp-text-secondary);
}
.stmt-empty-icon {
  font-size: 30px;
  line-height: 1;
  color: var(--imp-text-tertiary);
  margin-bottom: var(--imp-space-1);
}
.stmt-empty-title {
  font-size: 13px;
  color: var(--imp-text);
  font-weight: 500;
  margin-bottom: 4px;
}
.stmt-empty-desc {
  font-size: 12px;
  color: #c0c4cc;
  font-size: 12px;
}

/* 结果列头: 字段类型 + 字段名 (排序按钮由 el-table 固定在列右侧边框) */
.result-th {
  display: flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
  min-width: 0;
  padding-right: 4px;
}
.th-name {
  font-weight: 600;
  font-size: 12px;
  color: #303133;
  cursor: copy;
  overflow: hidden;
  text-overflow: ellipsis;
}
.th-type {
  flex-shrink: 0;
  font-family: 'Consolas', 'Courier New', monospace;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 单元格文本与交互 */
.cell-text {
  display: block;
  width: 100%;
  min-height: 20px;
  padding: 1px 4px;
  font-size: 12px;
  color: #303133;
  cursor: cell;
  border-radius: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cell-text.cell-null {
  color: #c0c4cc;
  font-style: italic;
}
.cell-text.cell-active {
  background: rgba(64, 158, 255, 0.12);
  box-shadow: inset 0 0 0 1px var(--el-color-primary);
}
.result-table-wrap :deep(.el-table__body tr.current-row > td.el-table__cell) {
  background: #ecf5ff !important;
}
.result-table-wrap :deep(.el-table__body tr.current-row > td.el-table__cell .cell-text) {
  color: #1d4b8f;
}

.result-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  flex-shrink: 0;
  padding: 2px 0;
  display: flex;
  align-items: center;
  gap: 8px;
}
.rt-label {
  flex-shrink: 0;
}
.result-filter {
  width: 360px;
  flex-shrink: 0;
}
.sql-error {
  color: #f56c6c;
  font-weight: normal;
  font-size: 12px;
  margin-left: 8px;
}
.result-table-wrap {
  flex: 1;
  min-height: 0;
  position: relative;
}

/* 查询结果右键菜单 */
.db-result-ctx-menu {
  position: fixed;
  z-index: 9999;
  min-width: 200px;
  background: #ffffff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.15);
  padding: 5px 0;
  user-select: none;
}
.ctx-menu-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 7px 16px;
  font-size: 13px;
  color: #303133;
  cursor: pointer;
  white-space: nowrap;
  position: relative;
}
.ctx-menu-item:hover {
  background: #ecf5ff;
  color: #409eff;
}
.ctx-key {
  font-size: 11px;
  color: #a8abb2;
  margin-left: 24px;
}
.ctx-arrow {
  font-size: 10px;
  color: #a8abb2;
}
.ctx-has-sub .ctx-submenu {
  display: none;
  position: absolute;
  left: 100%;
  top: -6px;
  background: #ffffff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.15);
  padding: 5px 0;
  min-width: 190px;
}
.ctx-has-sub:hover .ctx-submenu {
  display: block;
}
.result-table-wrap :deep(.el-table) {
  width: 100%;
}
.cell-edit {
  --el-input-border-color: transparent;
  --el-input-hover-border-color: transparent;
  --el-input-focus-border-color: var(--el-color-primary);
  --el-input-bg-color: transparent;
}
.cell-edit :deep(.el-input__wrapper) {
  box-shadow: none;
  padding: 0 2px;
}
.cell-edit :deep(.el-input__inner) {
  font-size: 12px;
  color: #303133;
}
.result-footer {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 2px 0;
  border-top: 1px solid var(--el-border-color-lighter);
  margin-top: 4px;
  flex-wrap: nowrap;
}
.rf-left {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: nowrap;
}
.rf-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: nowrap;
  min-width: 0;
  overflow: auto;
}
.rf-right :deep(.el-pagination) {
  white-space: nowrap;
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  min-width: 0;
}
.rf-right :deep(.el-pagination .el-pagination__sizes),
.rf-right :deep(.el-pagination .el-pager),
.rf-right :deep(.el-pagination .el-pagination__jump),
.rf-right :deep(.el-pagination .el-pagination__total) {
  flex-shrink: 0;
}
.rf-right :deep(.el-pagination .el-pager) {
  flex-wrap: nowrap;
}
.rf-info {
  font-size: 12px;
  color: var(--imp-text-secondary);
  flex-shrink: 0;
  white-space: nowrap;
  margin-left: auto;
}

/* SQL 编辑器与结果区之间的拖动条 */
.sql-resizer {
  flex-shrink: 0;
  height: 7px;
  cursor: ns-resize;
  position: relative;
  margin: -1px 0;
  z-index: 5;
}
.sql-resizer::after {
  content: '';
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  width: 48px;
  height: 3px;
  border-radius: 2px;
  background: #dcdfe6;
  transition: background 0.15s;
}
.sql-resizer:hover::after,
.sql-resizer.dragging::after {
  background: var(--el-color-primary);
}
.sql-resizer:hover {
  background: rgba(64, 158, 255, 0.08);
}

/* 表结构 tab */
.table-tab {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.table-tab-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  flex-shrink: 0;
}
.table-tab-title {
  font-size: 15px;
  font-weight: 600;
}
.table-tab-comment {
  color: var(--imp-text-secondary);
  font-size: 13px;
}
.table-tab-loc {
  margin-left: auto;
  color: var(--imp-text-secondary);
  font-size: 12px;
}
.table-drop-btn {
  margin-left: 8px;
}
.sub-tabs {
  flex: 1;
  min-height: 0;
  margin-top: 6px;
}
.sub-tabs :deep(.el-tabs__content) {
  height: calc(100% - 40px);
  padding: 8px;
}
.sub-tabs :deep(.el-tab-pane) {
  height: 100%;
}
.pane-col {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.tab-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  flex-shrink: 0;
}
.sub-table-wrap {
  flex: 1;
  min-height: 0;
}
.sub-table-wrap :deep(.el-table) {
  width: 100%;
}
.ddl-wrap {
  position: relative;
  height: 100%;
  display: flex;
  flex-direction: column;
}
.ddl-editor-box {
  flex: 1;
  min-height: 0;
}
.copy-btn {
  position: absolute;
  top: 4px;
  right: 4px;
  z-index: 20;
}
.sql-toolbar {
  margin-bottom: 8px;
}
.sql-area {
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: 12px;
}
.sql-actions {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.sql-cost {
  color: var(--imp-text-secondary);
  font-size: 12px;
}

/* ============ 树右键菜单 ============ */
.tree-context-menu {
  position: fixed;
  z-index: 3000;
  min-width: 200px;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.12);
  padding: 4px;
}
.tcm-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 6px 10px;
  font-size: 13px;
  color: #303133;
  border-radius: 4px;
  cursor: pointer;
  white-space: nowrap;
}
.tcm-item:hover {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.tcm-item.danger {
  color: #f56c6c;
}
.tcm-item.danger:hover {
  background: #fef0f0;
  color: #f56c6c;
}
.tcm-item.has-sub {
  position: relative;
}
.tcm-arrow {
  font-size: 10px;
  color: var(--imp-text-secondary);
}
.tcm-kbd {
  color: var(--imp-text-secondary);
  font-size: 12px;
  border: 1px solid #e4e7ed;
  border-radius: 3px;
  padding: 0 4px;
  background: #fafafa;
}
.tcm-divider {
  height: 1px;
  background: var(--el-border-color-lighter);
  margin: 4px 2px;
}
.tcm-sub {
  position: absolute;
  left: calc(100% - 2px);
  top: -4px;
  min-width: 120px;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.12);
  padding: 4px;
}

/* ============ 表结构详情: 属性页布局 ============ */
.table-main-tabs {
  flex: 1;
  min-height: 0;
  margin-top: 6px;
}
.table-main-tabs :deep(.el-tabs__content) {
  height: calc(100% - 40px);
  padding: 8px;
}
.table-main-tabs :deep(.el-tab-pane) {
  height: 100%;
}

/* 属性页整体布局 */
.attr-layout {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 8px;
}
.attr-basic {
  flex-shrink: 0;
}
.attr-desc {
  margin: 0;
}
.attr-body {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 0;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  overflow: hidden;
}
.attr-side {
  width: 140px;
  flex-shrink: 0;
  background: #fafafa;
  border-right: 1px solid var(--el-border-color-lighter);
  overflow-y: auto;
  padding: 4px 0;
}
.attr-menu-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 14px;
  font-size: 13px;
  color: var(--imp-text-secondary);
  cursor: pointer;
  transition: all 0.15s;
  border-left: 3px solid transparent;
}
.attr-menu-item:hover {
  background: #f0f2f5;
  color: #303133;
}
.attr-menu-item.active {
  background: #ecf5ff;
  color: #409eff;
  border-left-color: #409eff;
  font-weight: 600;
}
.attr-content {
  flex: 1;
  min-width: 0;
  padding: 12px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

/* 统计卡片 */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  padding: 16px;
}
.stat-card {
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 20px;
  text-align: center;
}
.stat-label {
  font-size: 13px;
  color: var(--imp-text-secondary);
  margin-bottom: 8px;
}
.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: #409eff;
}

/* 数据页 */
.table-data-pane {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 6px;
}
</style>
