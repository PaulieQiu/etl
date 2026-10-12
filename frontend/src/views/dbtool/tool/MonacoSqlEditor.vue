<template>
  <div class="monaco-editor-wrapper" :class="{ 'fullscreen': isFullscreen }">
    <!-- 工具栏 -->
    <div class="editor-toolbar">
      <div class="toolbar-left">
        <span class="toolbar-title">SQL 编辑器</span>
      </div>
      <div class="toolbar-right">
        <button v-if="!readOnly" class="toolbar-btn" @click="undo" title="撤销 (Ctrl+Z)">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor">
            <path d="M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z"/>
          </svg>
          撤销
        </button>
        <button v-if="!readOnly" class="toolbar-btn" @click="redo" title="重做 (Ctrl+Y)">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor">
            <path d="M18.4 10.6C16.55 8.99 14.15 8 11.5 8c-4.65 0-8.58 3.03-9.96 7.22L3.9 16c1.05-3.19 4.05-5.5 7.6-5.5 1.95 0 3.73.72 5.12 1.88L13 16h9V7l-3.6 3.6z"/>
          </svg>
          重做
        </button>
        <button v-if="!readOnly" class="toolbar-btn" @click="onFormat" title="格式化 SQL">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor">
            <path d="M3 17h18v2H3v-2zm0-7h18v2H3v-2zm0-7h18v2H3V3z"/>
          </svg>
          格式化
        </button>
        <button v-if="!readOnly" class="toolbar-btn" @click="onClear" title="清空内容">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor">
            <path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/>
          </svg>
          清空
        </button>
        <button class="toolbar-btn" @click="toggleFullscreen" :title="isFullscreen ? '退出全屏' : '全屏'">
          <svg v-if="!isFullscreen" viewBox="0 0 24 24" width="16" height="16" fill="currentColor">
            <path d="M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z"/>
          </svg>
          <svg v-else viewBox="0 0 24 24" width="16" height="16" fill="currentColor">
            <path d="M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z"/>
          </svg>
          {{ isFullscreen ? '退出全屏' : '全屏' }}
        </button>
      </div>
    </div>
    <!-- 编辑器容器 -->
    <div ref="containerRef" class="monaco-sql-editor" :style="[wrapStyle, { '--suggest-max-h': suggestMaxH + 'px' }]"></div>
  </div>
</template>

<script setup name="MonacoSqlEditor">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as monaco from 'monaco-editor'

// Monaco Worker 配置: Monaco 0.56 以 module worker 加载, 需返回 ESM worker 入口
// (旧 data-url + importScripts 方式报 "Module scripts don't support importScripts()";
//  Vite 8 rolldown 对 'monaco-editor/esm/...?worker' 子路径解析失败, 故直接用 CDN ESM 入口)
self.MonacoEnvironment = {
  getWorkerUrl: function (_moduleId, label) {
    // SQL 语言无专门 worker, 统一使用默认 editorWorker
    return 'https://cdn.jsdelivr.net/npm/monaco-editor@0.56.0/esm/vs/editor/editor.worker.js'
  }
}

const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  height: { type: String, default: '' },
  minHeight: { type: String, default: '160px' },
  maxHeight: { type: String, default: '' },
  readOnly: { type: Boolean, default: false },
  language: { type: String, default: 'sql' },
  // 动态补全数据：库名和表名
  databases: { type: Array, default: () => [] },
  tables: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue', 'keydown-ctrl-enter', 'format', 'clear'])

const containerRef = ref(null)
const isFullscreen = ref(false)
// 补全下拉最大高度(px): 随编辑器可视区高度动态调整, 保证不超出编辑器可视区
const suggestMaxH = ref(220)
let editor = null
let internalUpdate = false
let suggestResizeObserver = null

const wrapStyle = computed(() => {
  const s = {}
  if (isFullscreen.value) {
    s.height = 'calc(100vh - 120px)'
  } else {
    if (props.height) s.height = props.height
    if (props.minHeight) s.minHeight = props.minHeight
    if (props.maxHeight) s.maxHeight = props.maxHeight
  }
  return s
})

// SQL 关键字补全建议
const sqlKeywords = [
  'SELECT', 'FROM', 'WHERE', 'AND', 'OR', 'NOT', 'IN', 'LIKE', 'BETWEEN', 'IS', 'NULL',
  'ORDER BY', 'GROUP BY', 'HAVING', 'LIMIT', 'OFFSET', 'UNION', 'ALL', 'DISTINCT',
  'INSERT INTO', 'VALUES', 'UPDATE', 'SET', 'DELETE FROM', 'CREATE TABLE', 'ALTER TABLE',
  'DROP TABLE', 'TRUNCATE TABLE', 'CREATE INDEX', 'DROP INDEX', 'CREATE DATABASE',
  'DROP DATABASE', 'USE', 'SHOW', 'DESCRIBE', 'EXPLAIN', 'JOIN', 'LEFT JOIN', 'RIGHT JOIN',
  'INNER JOIN', 'OUTER JOIN', 'CROSS JOIN', 'ON', 'AS', 'CASE', 'WHEN', 'THEN', 'ELSE',
  'END', 'CAST', 'CONVERT', 'COALESCE', 'IFNULL', 'NULLIF', 'EXISTS', 'COUNT', 'SUM',
  'AVG', 'MIN', 'MAX', 'GROUP_CONCAT', 'CONCAT', 'SUBSTRING', 'TRIM', 'UPPER', 'LOWER',
  'LENGTH', 'REPLACE', 'NOW', 'CURDATE', 'CURTIME', 'DATE', 'YEAR', 'MONTH', 'DAY',
  'HOUR', 'MINUTE', 'SECOND', 'DATEDIFF', 'TIMESTAMPDIFF', 'DATE_ADD', 'DATE_SUB',
  'IF', 'PRIMARY KEY', 'FOREIGN KEY', 'UNIQUE', 'CHECK', 'DEFAULT', 'AUTO_INCREMENT',
  'COMMENT', 'ENGINE', 'CHARSET', 'COLLATE', 'INT', 'BIGINT', 'SMALLINT', 'TINYINT',
  'DECIMAL', 'FLOAT', 'DOUBLE', 'VARCHAR', 'CHAR', 'TEXT', 'LONGTEXT', 'MEDIUMTEXT',
  'TINYTEXT', 'BLOB', 'LONGBLOB', 'DATE', 'DATETIME', 'TIMESTAMP', 'TIME', 'YEAR',
  'BOOLEAN', 'BOOL', 'JSON', 'ENUM', 'SET', 'WITH', 'RECURSIVE', 'CTE', 'WINDOW',
  'OVER', 'PARTITION BY', 'ROW_NUMBER', 'RANK', 'DENSE_RANK', 'LAG', 'LEAD',
  'FIRST_VALUE', 'LAST_VALUE', 'NTILE', 'BEGIN', 'COMMIT', 'ROLLBACK', 'SAVEPOINT',
  'TRANSACTION', 'LOCK TABLES', 'UNLOCK TABLES', 'GRANT', 'REVOKE', 'VIEW',
  'CREATE VIEW', 'DROP VIEW', 'PROCEDURE', 'FUNCTION', 'TRIGGER', 'CALL', 'RETURN',
  'ASC', 'DESC', 'NULLS FIRST', 'NULLS LAST', 'USING', 'TABLESPACE', 'FILLFACTOR',
  'INDEX', 'UNIQUE INDEX', 'FULLTEXT INDEX', 'SPATIAL INDEX', 'HASH INDEX', 'BTREE INDEX',
  'CONSTRAINT', 'REFERENCES', 'ON DELETE', 'ON UPDATE', 'CASCADE', 'SET NULL',
  'SET DEFAULT', 'RESTRICT', 'NO ACTION', 'IF NOT EXISTS', 'IF EXISTS', 'OR REPLACE',
  'TEMPORARY', 'TEMP', 'FOR UPDATE', 'LOCK IN SHARE MODE', 'FOR SHARE', 'NOWAIT',
  'SKIP LOCKED', 'WITH ROLLUP', 'GROUPING SETS', 'CUBE', 'ROLLUP', 'LATERAL',
  'TABLESAMPLE', 'BERNOULLI', 'SYSTEM', 'REPEATABLE', 'MATERIALIZED', 'MATERIALIZED VIEW',
  'REFRESH', 'CONCURRENTLY', 'UNLOGGED', 'GLOBAL', 'LOCAL', 'PUBLIC', 'PRIVATE'
]

// 注册 SQL 补全提供者（包含动态库名和表名）
monaco.languages.registerCompletionItemProvider('sql', {
  triggerCharacters: ['.', ' '],
  provideCompletionItems: function (model, position) {
    const word = model.getWordUntilPosition(position)
    const range = {
      startLineNumber: position.lineNumber,
      endLineNumber: position.lineNumber,
      startColumn: word.startColumn,
      endColumn: word.endColumn
    }
    const suggestions = []

    // 计算当前输入标识符的完整范围（支持连字符等特殊字符，如 ry-vue）
    const lineText = model.getLineContent(position.lineNumber)
    const beforeCursor = lineText.substring(0, position.column - 1)
    // 当前已输入前缀（用于过滤建议, 避免一次显示全部 130+ 关键字导致列表超高）
    const inputPrefix = (word.word || '').toUpperCase()

    // 按前缀过滤 + 截断: 普通上下文总条数上限, 防止补全列表过长
    function limitByPrefix(list, max) {
      const filtered = list.filter((s) => !inputPrefix || String(s.label).toUpperCase().startsWith(inputPrefix))
      return filtered.slice(0, max || 20)
    }
    // 从光标往前找标识符开头（字母/数字/下划线/连字符/反引号）
    let idStart = beforeCursor.length
    while (idStart > 0) {
      const ch = beforeCursor[idStart - 1]
      if (/[a-zA-Z0-9_`-]/.test(ch)) {
        idStart--
      } else {
        break
      }
    }
    const identifierRange = {
      startLineNumber: position.lineNumber,
      endLineNumber: position.lineNumber,
      startColumn: idStart + 1,
      endColumn: position.column
    }
    // 如果标识符范围比 word 范围更宽（含连字符），使用更宽的范围
    if (identifierRange.startColumn < range.startColumn) {
      range.startColumn = identifierRange.startColumn
      range.endColumn = identifierRange.endColumn
    }

    // 判断标识符是否需要反引号（包含特殊字符或为关键字）
    function needBacktick(name) {
      if (!name) return false
      // 包含非字母数字下划线的字符需要反引号
      if (!/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(name)) return true
      // 是SQL关键字需要反引号
      const upperName = name.toUpperCase()
      const keywords = ['SELECT', 'FROM', 'WHERE', 'AND', 'OR', 'NOT', 'IN', 'LIKE', 'BETWEEN', 'IS', 'NULL', 'TRUE', 'FALSE', 'ORDER', 'BY', 'GROUP', 'HAVING', 'LIMIT', 'OFFSET', 'UNION', 'ALL', 'DISTINCT', 'AS', 'ON', 'JOIN', 'LEFT', 'RIGHT', 'INNER', 'OUTER', 'CROSS', 'FULL', 'INSERT', 'INTO', 'VALUES', 'UPDATE', 'SET', 'DELETE', 'CREATE', 'TABLE', 'DATABASE', 'INDEX', 'VIEW', 'TRIGGER', 'PROCEDURE', 'FUNCTION', 'ALTER', 'DROP', 'TRUNCATE', 'RENAME', 'USE', 'SHOW', 'DESCRIBE', 'EXPLAIN', 'GRANT', 'REVOKE', 'COMMIT', 'ROLLBACK', 'BEGIN', 'TRANSACTION', 'SAVEPOINT', 'LOCK', 'UNLOCK', 'CALL', 'DECLARE', 'HANDLER', 'LOOP', 'WHILE', 'REPEAT', 'UNTIL', 'IF', 'THEN', 'ELSE', 'ELSEIF', 'CASE', 'WHEN', 'END', 'LEAVE', 'ITERATE', 'RETURN', 'CURSOR', 'OPEN', 'CLOSE', 'FETCH', 'NEXT', 'PREV', 'FIRST', 'LAST', 'ABSOLUTE', 'RELATIVE', 'FOR', 'READ', 'WRITE', 'ONLY', 'TEMPORARY', 'TEMP', 'ENGINE', 'CHARSET', 'COLLATE', 'COMMENT', 'AUTO_INCREMENT', 'PRIMARY', 'KEY', 'UNIQUE', 'FOREIGN', 'REFERENCES', 'CHECK', 'DEFAULT', 'NOT NULL', 'AUTOINCREMENT', 'SERIAL', 'BIT', 'BOOL', 'BOOLEAN', 'TINYINT', 'SMALLINT', 'MEDIUMINT', 'INT', 'INTEGER', 'BIGINT', 'FLOAT', 'DOUBLE', 'DECIMAL', 'NUMERIC', 'DATE', 'TIME', 'DATETIME', 'TIMESTAMP', 'YEAR', 'CHAR', 'VARCHAR', 'BINARY', 'VARBINARY', 'TINYBLOB', 'TINYTEXT', 'BLOB', 'TEXT', 'MEDIUMBLOB', 'MEDIUMTEXT', 'LONGBLOB', 'LONGTEXT', 'ENUM', 'SET', 'JSON', 'GEOMETRY', 'POINT', 'LINESTRING', 'POLYGON', 'MULTIPOINT', 'MULTILINESTRING', 'MULTIPOLYGON', 'GEOMETRYCOLLECTION']
      return keywords.includes(upperName)
    }

    // 给标识符添加反引号（如果需要）
    function quoteIdentifier(name) {
      if (needBacktick(name)) {
        return '`' + name + '`'
      }
      return name
    }

    // 获取当前行文本，判断是否为"库名."的上下文
    const lineContent = model.getLineContent(position.lineNumber)
    const textBeforeCursor = lineContent.substring(0, position.column - 1)
    
    // 检测是否为"库名."模式（如 ry-vue. 或 `ry-vue`.）
    const dbDotMatch = textBeforeCursor.match(/([`]?[\w-]+[`]?)\.\s*$/)
    const isTableContext = !!dbDotMatch
    
    // 检测是否为"表名."模式（如 user. 或 `user`.），应该显示字段名（暂不支持）
    const tableDotMatch = textBeforeCursor.match(/([`]?[\w]+[`]?)\.\s*$/)
    const isColumnContext = !!tableDotMatch && !isTableContext

    if (isTableContext) {
      // 库名. 上下文：只显示表名（按前缀过滤, 最多 15 条）
      const dbName = dbDotMatch[1].replace(/`/g, '')
      if (props.tables && props.tables.length > 0) {
        limitByPrefix(props.tables.map((table) => ({
          label: table,
          kind: monaco.languages.CompletionItemKind.Class,
          insertText: quoteIdentifier(table),
          detail: `${dbName} 中的数据表`,
          range: range,
          sortText: '0_' + table
        })), 15).forEach((s) => suggestions.push(s))
      } else {
        // 没有表名时提示
        suggestions.push({
          label: '（当前库暂无表或未加载表列表）',
          kind: monaco.languages.CompletionItemKind.Text,
          insertText: '',
          detail: '请先选择数据库以加载表列表',
          range: range
        })
      }
    } else if (isColumnContext) {
      // 表名. 上下文：暂不支持字段名补全，显示提示
      suggestions.push({
        label: '（字段名补全功能开发中）',
        kind: monaco.languages.CompletionItemKind.Text,
        insertText: '',
        detail: '字段名智能补全将在后续版本支持',
        range: range
      })
    } else {
      // 普通上下文：显示 SQL 关键字 + 库名 + 表名
      
      // SQL 关键字补全（按前缀过滤, 最多 12 条）
      limitByPrefix(sqlKeywords.map((kw) => ({
        label: kw,
        kind: monaco.languages.CompletionItemKind.Keyword,
        insertText: kw,
        detail: 'SQL 关键字',
        range: range,
        sortText: '2_' + kw
      })), 12).forEach((s) => suggestions.push(s))

      // 库名补全（按前缀过滤, 最多 10 条）
      if (props.databases && props.databases.length > 0) {
        limitByPrefix(props.databases.map((db) => ({
          label: db,
          kind: monaco.languages.CompletionItemKind.Module,
          insertText: quoteIdentifier(db),
          detail: '数据库',
          range: range,
          sortText: '1_' + db
        })), 10).forEach((s) => suggestions.push(s))
      }

      // 表名补全（按前缀过滤, 最多 10 条）
      if (props.tables && props.tables.length > 0) {
        limitByPrefix(props.tables.map((table) => ({
          label: table,
          kind: monaco.languages.CompletionItemKind.Class,
          insertText: quoteIdentifier(table),
          detail: '数据表',
          range: range,
          sortText: '1_' + table
        })), 10).forEach((s) => suggestions.push(s))
      }
    }

    return { suggestions: suggestions }
  }
})

// 配置中文语言包
function setupChineseLocale() {
  // Monaco Editor 的中文配置
  monaco.editor.defineTheme('vs-dark-chinese', {
    base: 'vs-dark',
    inherit: true,
    rules: [],
    colors: {}
  })
}

onMounted(() => {
  if (!containerRef.value) return
  setupChineseLocale()

  editor = monaco.editor.create(containerRef.value, {
    value: props.modelValue || '',
    language: props.language,
    theme: 'vs-dark',
    automaticLayout: true,
    minimap: { enabled: false },
    fontSize: 13,
    fontFamily: "'Consolas', 'Courier New', monospace",
    lineNumbers: 'on',
    scrollBeyondLastLine: false,
    wordWrap: 'off',
    tabSize: 2,
    insertSpaces: true,
    readOnly: props.readOnly,
    placeholder: props.placeholder,
    renderLineHighlight: 'all',
    cursorBlinking: 'smooth',
    cursorSmoothCaretAnimation: 'on',
    smoothScrolling: true,
    padding: { top: 8, bottom: 8 },
    scrollbar: {
      verticalScrollbarSize: 10,
      horizontalScrollbarSize: 10,
      useShadows: false
    },
    suggest: {
      showKeywords: true,
      showSnippets: true,
      showFiles: false,
      filterGraceful: true,
      snippetsPreventQuickSuggestions: false,
      // 限制补全提示框高度与可见项数, 避免列表过长超出编辑器可视区域
      maxVisibleSuggestions: 8,
      // 初始值, 实际高度由 ResizeObserver + CSS 变量 --suggest-max-h 动态限制
      maxHeight: suggestMaxH.value
    }
  })

  // 内容变化事件
  editor.onDidChangeModelContent(() => {
    internalUpdate = true
    emit('update:modelValue', editor.getValue())
    internalUpdate = false
  })

  // 开发环境暴露到window方便调试
  if (import.meta.env.DEV) {
    window.__monacoEditor = editor
  }

  // Ctrl+Enter 快捷键
  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => {
    emit('keydown-ctrl-enter')
  })

  // 占位符支持
  if (props.placeholder) {
    const updatePlaceholder = () => {
      const model = editor.getModel()
      if (!model) return
      const decorations = editor.deltaDecorations([], [
        {
          range: new monaco.Range(1, 1, 1, 1),
          options: {
            isWholeLine: true,
            className: 'monaco-placeholder',
            hoverMessage: { value: props.placeholder }
          }
        }
      ])
      if (editor.getValue()) {
        editor.deltaDecorations(decorations, [])
      }
    }
    updatePlaceholder()
    editor.onDidChangeModelContent(updatePlaceholder)
  }

  // 注册编辑器高度监听: 动态限制补全下拉高度(含边框), 确保不超出编辑器可视区
  updateSuggestMaxH()
  if (typeof ResizeObserver !== 'undefined') {
    suggestResizeObserver = new ResizeObserver(updateSuggestMaxH)
    suggestResizeObserver.observe(containerRef.value)
  }
})

// 监听外部值变化
watch(() => props.modelValue, (newVal) => {
  if (editor && !internalUpdate) {
    const current = editor.getValue()
    if (current !== newVal) {
      editor.setValue(newVal || '')
    }
  }
})

// 监听只读变化
watch(() => props.readOnly, (newVal) => {
  if (editor) {
    editor.updateOptions({ readOnly: newVal })
  }
})

// 监听语言变化
watch(() => props.language, (newVal) => {
  if (editor) {
    monaco.editor.setModelLanguage(editor.getModel(), newVal)
  }
})

// 监听动态补全数据变化
watch(() => [props.databases, props.tables], () => {
  // 补全提供者已注册，数据通过 props 动态读取
}, { deep: true })

// 监听编辑器可视区高度, 动态限制补全下拉最大高度(含边框), 确保不超出编辑器可视区
// 注册时机在 onMounted 内(编辑器创建后), 此处仅定义函数
const updateSuggestMaxH = () => {
  const h = containerRef.value ? containerRef.value.clientHeight : 0
  // 不超过编辑器可视区 80%, 下限 120px, 上限 220px
  suggestMaxH.value = Math.max(120, Math.min(220, Math.round(h * 0.8)))
}

onBeforeUnmount(() => {
  if (suggestResizeObserver) {
    suggestResizeObserver.disconnect()
    suggestResizeObserver = null
  }
  if (editor) {
    editor.dispose()
    editor = null
  }
})

// 方法
function undo() {
  if (editor) {
    editor.trigger('keyboard', 'undo', null)
  }
}

function redo() {
  if (editor) {
    editor.trigger('keyboard', 'redo', null)
  }
}

function onFormat() {
  // 通知父组件执行格式化（父组件使用 sql-formatter，更强大）
  emit('format')
}

function onClear() {
  // 通知父组件执行清空
  emit('clear')
}

function formatSql() {
  if (editor) {
    editor.getAction('editor.action.formatDocument').run()
  }
}

function toggleFullscreen() {
  isFullscreen.value = !isFullscreen.value
  // 全屏时添加 body 类名
  if (isFullscreen.value) {
    document.body.classList.add('monaco-fullscreen-open')
  } else {
    document.body.classList.remove('monaco-fullscreen-open')
  }
  // 延迟更新布局
  setTimeout(() => {
    if (editor) {
      editor.layout()
    }
  }, 100)
}

// 暴露给父组件
defineExpose({
  getSelectionInfo() {
    if (!editor) return { hasSelection: false, start: 0, end: 0, selectedText: '' }
    const selection = editor.getSelection()
    const model = editor.getModel()
    if (!selection || !model) return { hasSelection: false, start: 0, end: 0, selectedText: '' }
    const start = model.getOffsetAt(selection.getStartPosition())
    const end = model.getOffsetAt(selection.getEndPosition())
    const selectedText = model.getValueInRange(selection)
    return {
      hasSelection: !selection.isEmpty(),
      start,
      end,
      selectedText
    }
  },
  focus() {
    editor?.focus()
  },
  getEditor() {
    return editor
  },
  getValue() {
    return editor?.getValue() || ''
  },
  setValue(val) {
    editor?.setValue(val || '')
  },
  format() {
    formatSql()
  },
  undo,
  redo,
  toggleFullscreen
})
</script>

<style scoped>
.monaco-editor-wrapper {
  position: relative;
  width: 100%;
  border: 1px solid #3e3e3e;
  border-radius: 4px;
  overflow: hidden;
  background: #1e1e1e;
}

.monaco-editor-wrapper.fullscreen {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 9999;
  border-radius: 0;
  border: none;
}

.editor-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 12px;
  background: #2d2d2d;
  border-bottom: 1px solid #3e3e3e;
}

.toolbar-left {
  display: flex;
  align-items: center;
}

.toolbar-title {
  color: #cccccc;
  font-size: 12px;
  font-weight: 500;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.toolbar-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  background: transparent;
  border: 1px solid #555;
  border-radius: 4px;
  color: #cccccc;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.toolbar-btn:hover {
  background: #3e3e3e;
  border-color: #007acc;
  color: #ffffff;
}

.toolbar-btn:active {
  background: #007acc;
}

.monaco-sql-editor {
  position: relative;
  width: 100%;
  overflow: hidden;
  background: #1e1e1e;
  box-sizing: border-box;
}

.monaco-sql-editor :deep(.monaco-editor) {
  background: #1e1e1e !important;
}

.monaco-sql-editor :deep(.monaco-editor .margin) {
  background: #1e1e1e !important;
}

.monaco-sql-editor :deep(.monaco-editor .monaco-editor-background) {
  background: #1e1e1e !important;
}

/* 占位符样式 */
:deep(.monaco-placeholder) {
  position: relative;
}

:deep(.monaco-placeholder::after) {
  content: attr(data-placeholder);
  position: absolute;
  left: 0;
  top: 0;
  color: #6a737d;
  pointer-events: none;
  white-space: pre;
}

/* 右键菜单中文化 - 通过 CSS 覆盖 */
:deep(.monaco-menu .action-label) {
  font-family: 'Microsoft YaHei', 'PingFang SC', sans-serif !important;
}

/* 限制 SQL 补全下拉高度, 防止列表超出编辑器可视区(高度随编辑器动态调整) */
:deep(.monaco-editor .suggest-widget) {
  max-height: var(--suggest-max-h, 220px) !important;
}

:deep(.monaco-editor .suggest-widget .monaco-list) {
  max-height: var(--suggest-max-h, 220px) !important;
}

:deep(.monaco-editor .suggest-widget .monaco-list .monaco-scrollable-element) {
  max-height: var(--suggest-max-h, 220px) !important;
}
</style>

<style>
/* 全局样式：全屏时 body 禁止滚动 */
body.monaco-fullscreen-open {
  overflow: hidden !important;
}

/* Monaco 右键菜单中文化 */
.monaco-menu .action-label {
  font-family: 'Microsoft YaHei', 'PingFang SC', sans-serif !important;
}

/* Monaco 编辑器中文化 - 常见菜单项 */
.monaco-menu-container .menu-item .action-label[aria-label="Undo"]::before {
  content: "撤销";
  visibility: visible;
}
.monaco-menu-container .menu-item .action-label[aria-label="Undo"] {
  visibility: hidden;
  position: relative;
}
.monaco-menu-container .menu-item .action-label[aria-label="Undo"]::before {
  position: absolute;
  left: 0;
  top: 0;
}
</style>
