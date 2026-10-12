<template>
  <div class="sql-editor" :style="wrapStyle">
    <pre ref="preRef" class="sql-editor__pre" aria-hidden="true"><code v-html="highlighted"></code></pre>
    <textarea
      ref="taRef"
      class="sql-editor__ta"
      :value="modelValue"
      :placeholder="placeholder"
      spellcheck="false"
      autocomplete="off"
      autocorrect="off"
      autocapitalize="off"
      @input="onInput"
      @scroll="syncScroll"
      @keydown="onKeydown"
      @keyup="syncScroll"
    ></textarea>
  </div>
</template>

<script setup name="SqlEditor">
import { computed, ref } from 'vue'
import hljs from 'highlight.js/lib/core'
import sql from 'highlight.js/lib/languages/sql'
import 'highlight.js/styles/atom-one-dark.css'

hljs.registerLanguage('sql', sql)

const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  height: { type: String, default: '' },
  minHeight: { type: String, default: '160px' },
  maxHeight: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue', 'keydown-ctrl-enter'])

const preRef = ref()
const taRef = ref()

const wrapStyle = computed(() => {
  const s = {}
  if (props.height) s.height = props.height
  if (props.minHeight) s.minHeight = props.minHeight
  if (props.maxHeight) s.maxHeight = props.maxHeight
  return s
})

const highlighted = computed(() => {
  const v = props.modelValue || ''
  if (!v.trim()) return ''
  try {
    return hljs.highlight(v, { language: 'sql' }).value
  } catch (e) {
    return v
  }
})

function onInput(e) {
  emit('update:modelValue', e.target.value)
  syncScroll()
}

function syncScroll() {
  if (preRef.value && taRef.value) {
    preRef.value.scrollTop = taRef.value.scrollTop
    preRef.value.scrollLeft = taRef.value.scrollLeft
  }
}

function onKeydown(e) {
  if (e.ctrlKey && e.key === 'Enter') {
    e.preventDefault()
    emit('keydown-ctrl-enter')
  }
  // Tab 键插入空格而不是跳焦点
  if (e.key === 'Tab') {
    e.preventDefault()
    const ta = taRef.value
    const start = ta.selectionStart
    const end = ta.selectionEnd
    const val = ta.value
    const next = val.slice(0, start) + '  ' + val.slice(end)
    emit('update:modelValue', next)
    requestAnimationFrame(() => {
      ta.selectionStart = ta.selectionEnd = start + 2
    })
  }
}

// 暴露给父组件: 读取当前选区信息
defineExpose({
  getSelectionInfo() {
    const ta = taRef.value
    if (!ta) return { hasSelection: false, start: 0, end: 0, selectedText: '' }
    const start = ta.selectionStart
    const end = ta.selectionEnd
    const hasSelection = start !== end
    return {
      hasSelection,
      start,
      end,
      selectedText: hasSelection ? ta.value.substring(start, end) : ''
    }
  },
  focus() {
    taRef.value?.focus()
  }
})
</script>

<style scoped>
.sql-editor {
  position: relative;
  width: 100%;
  overflow: hidden;
  border: 1px solid #3e3e3e;
  border-radius: 4px;
  background: #1e1e1e;
  box-sizing: border-box;
}
.sql-editor__pre,
.sql-editor__ta {
  margin: 0;
  padding: 8px 10px;
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.5;
  tab-size: 4;
  white-space: pre;
  word-wrap: normal;
  word-break: normal;
  overflow-wrap: normal;
  box-sizing: border-box;
}
.sql-editor__pre {
  position: absolute;
  inset: 0;
  margin: 0;
  overflow: hidden;
  pointer-events: none;
  z-index: 0;
  color: #d4d4d4;
  background: transparent;
}
.sql-editor__pre code {
  font-family: inherit;
  background: transparent;
}
.sql-editor__ta {
  position: relative;
  z-index: 1;
  display: block;
  width: 100%;
  height: 100%;
  min-height: inherit;
  resize: none;
  color: transparent;
  caret-color: #fff;
  background: transparent;
  border: none;
  outline: none;
  overflow: auto;
  font-family: inherit;
}
.sql-editor__ta::placeholder {
  color: #6a737d;
}
.sql-editor__ta::selection {
  color: transparent;
  background: rgba(38, 79, 120, 0.9);
}
</style>
