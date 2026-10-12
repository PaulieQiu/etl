<template>
  <div class="cron-gen">
    <!-- 常用预设 -->
    <div class="preset-block">
      <div class="preset-label">常用预设</div>
      <div class="preset-grid">
        <el-button
          v-for="p in presets"
          :key="p.value"
          size="small"
          :type="p.value === modelValue ? 'primary' : 'default'"
          plain
          class="preset-btn"
          @click="applyPreset(p.value)"
        >{{ p.label }}</el-button>
      </div>
    </div>

    <!-- 每段可视化配置（参考 cron-online：不指定/周期/步长/指定） -->
    <div class="seg-config">
      <div v-for="(seg, i) in segs" :key="i" class="seg-line">
        <div class="seg-name">{{ seg.label }}</div>
        <div class="seg-modes">
          <el-radio-group v-model="seg.mode" size="small" @change="applyMode(i)">
            <el-radio-button value="any">不指定</el-radio-button>
            <el-radio-button value="range">周期</el-radio-button>
            <el-radio-button value="step">步长</el-radio-button>
            <el-radio-button value="spec">指定</el-radio-button>
          </el-radio-group>
        </div>
        <div class="seg-control">
          <template v-if="seg.mode === 'any'">
            <span class="any-value">{{ i === 5 ? '?' : '*' }}</span>
          </template>
          <template v-else-if="seg.mode === 'range'">
            <el-input-number v-model="seg.rangeA" :min="seg.min" :max="seg.max" size="small" class="range-input" @change="applyRange(i)" />
            <span class="dash">-</span>
            <el-input-number v-model="seg.rangeB" :min="seg.min" :max="seg.max" size="small" class="range-input" @change="applyRange(i)" />
            <template v-if="i === 4">
              <span class="quarter-hint">季度</span>
              <button
                v-for="q in quarterPresets"
                :key="q.label"
                type="button"
                class="quarter-btn"
                :class="[q.cls, { on: seg.value === q.value }]"
                @click="applyQuarter(i, q)"
              >{{ q.label }}</button>
            </template>
          </template>
          <template v-else-if="seg.mode === 'step'">
            <span class="step-prefix">每</span>
            <el-input-number v-model="seg.step" :min="1" :max="seg.max" size="small" class="step-input" @change="applyStep(i)" />
            <span class="step-suffix">{{ seg.unit }}</span>
          </template>
          <template v-else>
            <span class="spec-value">{{ seg.value || (i === 5 ? '?' : '*') }}</span>
          </template>
        </div>
        <!-- 指定：数字/星期点选 -->
        <div v-if="seg.mode === 'spec'" class="seg-picker">
          <button
            v-for="n in seg.nums"
            :key="n"
            type="button"
            class="pick-btn"
            :class="{ on: isPicked(i, n) }"
            @click="togglePick(i, n)"
          >{{ n }}</button>
          <button
            v-for="s in seg.specials"
            :key="s"
            type="button"
            class="pick-btn special"
            :class="{ on: isPicked(i, s) }"
            @click="togglePick(i, s)"
          >{{ s }}</button>
        </div>
      </div>
    </div>

    <!-- 表达式 7 字段 + 可编辑反解析 -->
    <div class="expr-block">
      <div class="expr-fields">
        <div v-for="(seg, i) in segs" :key="i" class="expr-field">
          <div class="expr-label">{{ seg.label }}</div>
          <div class="expr-value">{{ seg.value || (i === 5 ? '?' : '*') }}</div>
        </div>
      </div>
      <div class="expr-row">
        <span class="cron-label">Cron 表达式</span>
        <el-input v-model="exprInput" class="expr-input" placeholder="输入 7 段表达式后点反解析，如: 0 0 2 * * ? *" @keyup.enter="parseFromInput" />
        <el-button size="small" class="parse-btn" @click="parseFromInput">反解析到UI</el-button>
      </div>
    </div>

    <!-- 效果说明 -->
    <div class="desc-block">
      <div class="desc-label">效果说明</div>
      <div class="desc-box" :class="{ invalid: isInvalid }">{{ currentDesc }}</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { describeCron } from './CronDesc'

const props = defineProps({
  modelValue: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue', 'confirm', 'cancel', 'change'])

const quarterPresets = [
  { label: 'Q1', value: '1-3', a: 1, b: 3, cls: 'q1' },
  { label: 'Q2', value: '4-6', a: 4, b: 6, cls: 'q2' },
  { label: 'Q3', value: '7-9', a: 7, b: 9, cls: 'q3' },
  { label: 'Q4', value: '10-12', a: 10, b: 12, cls: 'q4' }
]

const presets = [
  { label: '每5分钟', value: '0 */5 * * * ? *' },
  { label: '每30分钟', value: '0 0/30 * * * ? *' },
  { label: '每小时', value: '0 0 * * * ? *' },
  { label: '每天凌晨2点', value: '0 0 2 * * ? *' },
  { label: '每天8点30分', value: '0 30 8 * * ? *' },
  { label: '每周一9点', value: '0 0 9 ? * MON *' },
  { label: '每月1号1点', value: '0 0 1 1 * ? *' },
  { label: '每年1月1日', value: '0 0 0 1 1 ? *' }
]

const range = (a, b) => Array.from({ length: b - a + 1 }, (_, k) => String(a + k))
const WEEKDAYS = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT']

const segs = ref([
  { label: '秒', min: 0, max: 59, unit: '秒', mode: 'spec', value: '0', rangeA: 0, rangeB: 59, step: 5, picked: ['0'], nums: range(0, 59), specials: [] },
  { label: '分', min: 0, max: 59, unit: '分钟', mode: 'spec', value: '0', rangeA: 0, rangeB: 59, step: 30, picked: ['0'], nums: range(0, 59), specials: [] },
  { label: '时', min: 0, max: 23, unit: '小时', mode: 'spec', value: '2', rangeA: 0, rangeB: 23, step: 1, picked: ['2'], nums: range(0, 23), specials: [] },
  { label: '日', min: 1, max: 31, unit: '天', mode: 'any', value: '*', rangeA: 1, rangeB: 31, step: 1, picked: [], nums: range(1, 31), specials: ['L', 'W'] },
  { label: '月', min: 1, max: 12, unit: '月', mode: 'any', value: '*', rangeA: 1, rangeB: 12, step: 1, picked: [], nums: range(1, 12), specials: [] },
  { label: '周', min: 1, max: 7, unit: '周', mode: 'any', value: '?', rangeA: 1, rangeB: 7, step: 1, picked: [], nums: WEEKDAYS, specials: ['L'] },
  { label: '年', min: 2024, max: 2035, unit: '年', mode: 'any', value: '*', rangeA: 2024, rangeB: 2035, step: 1, picked: [], nums: range(2024, 2035), specials: [] }
])

let skipParse = false
function emitValue(v) {
  skipParse = true
  emit('update:modelValue', v)
  nextTick(() => { skipParse = false })
}

const isInvalid = ref(false)
const exprInput = ref('')

const currentDesc = computed(() => {
  const v = props.modelValue
  if (!v) return '请选择预设或输入 cron 表达式，将自动识别执行效果'
  const d = describeCron(v)
  isInvalid.value = d === null
  if (d === null) return '表达式格式无效，请检查（7 段：秒 分 时 日 月 周 年）'
  return d
})

/* ---------- 段编辑 ---------- */
function applyMode(i) {
  const seg = segs.value[i]
  const cur = String(seg.value || '').trim()
  if (seg.mode === 'any') {
    const want = i === 5 ? '?' : '*'
    if (cur === want) return
    seg.value = want
    seg.picked = []
  } else if (seg.mode === 'range') {
    applyRange(i)
  } else if (seg.mode === 'step') {
    // 已是步长形态且步长一致时跳过, 避免 radio change 与 parse 竞争重写
    const m = cur.match(/^\*?\/(\d+)$/)
    if (m && parseInt(m[1], 10) === (seg.step >= 1 ? seg.step : 1)) return
    applyStep(i)
  } else {
    syncSpec(i)
  }
  compose()
}

function applyQuarter(i, q) {
  const seg = segs.value[i]
  seg.mode = 'range'
  seg.rangeA = q.a
  seg.rangeB = q.b
  seg.picked = []
  seg.value = q.value
  compose()
}

function applyRange(i) {
  const seg = segs.value[i]
  let a = seg.rangeA, b = seg.rangeB
  if (a > b) { const t = a; a = b; b = t; seg.rangeA = a; seg.rangeB = b }
  seg.picked = []
  seg.value = `${a}-${b}`
  compose()
}

function applyStep(i) {
  const seg = segs.value[i]
  const n = seg.step >= 1 ? seg.step : 1
  seg.picked = []
  seg.value = `*/${n}`
  compose()
}

function syncSpec(i) {
  const seg = segs.value[i]
  const cur = String(seg.value || '').trim()
  if (cur === '*' || cur === '?' || cur === '') {
    seg.picked = []
    seg.value = ''
  } else {
    seg.picked = cur.split(',').map(s => s.trim()).filter(s => seg.nums.includes(s) || seg.specials.includes(s))
    seg.value = seg.picked.join(',')
  }
}

function isPicked(i, n) {
  return segs.value[i].picked.includes(String(n))
}

function togglePick(i, n) {
  const seg = segs.value[i]
  const k = String(n)
  const idx = seg.picked.indexOf(k)
  if (idx >= 0) {
    seg.picked.splice(idx, 1)
  } else {
    seg.picked.push(k)
  }
  if (i === 5) {
    seg.picked.sort((a, b) => WEEKDAYS.indexOf(a) - WEEKDAYS.indexOf(b))
  } else {
    seg.picked.sort((a, b) => {
      const na = parseInt(a, 10), nb = parseInt(b, 10)
      if (!Number.isNaN(na) && !Number.isNaN(nb)) return na - nb
      return a.localeCompare(b)
    })
  }
  seg.value = seg.picked.join(',')
  compose()
}

/* ---------- 组合与反解析 ---------- */
function compose() {
  const v = segs.value.map((s, i) => {
    const val = String(s.value || '').trim()
    if (val === '') return i === 5 ? '?' : '*'
    return val
  }).join(' ')
  emitValue(v)
  emit('change', v)
}

function setSeg(i, val) {
  const seg = segs.value[i]
  seg.value = val
  if (val === '*' || val === '?') {
    seg.mode = 'any'
    seg.picked = []
  } else if (/^\d+-\d+$/.test(val)) {
    const [a, b] = val.split('-').map(Number)
    seg.mode = 'range'
    seg.rangeA = a
    seg.rangeB = b
    seg.picked = []
  } else if (/^(\d+|\*)\/\d+$/.test(val)) {
    seg.mode = 'step'
    seg.step = parseInt(val.split('/')[1], 10)
    seg.picked = []
  } else {
    seg.mode = 'spec'
    seg.picked = val.split(',').map(s => s.trim()).filter(s => seg.nums.includes(s) || seg.specials.includes(s))
    seg.value = seg.picked.join(',')
  }
}

function parseFrom(v) {
  const parts = String(v || '').trim().split(/\s+/)
  if (parts.length === 7) {
    parts.forEach((p, i) => setSeg(i, p))
  } else if (parts.length === 6) {
    setSeg(0, '0')
    parts.forEach((p, i) => setSeg(i + 1, p))
    setSeg(6, '*')
  }
}

function parse() {
  parseFrom(props.modelValue)
}

function parseFromInput() {
  const v = String(exprInput.value || '').trim()
  if (!v) return
  const parts = v.split(/\s+/)
  if (parts.length !== 7 && parts.length !== 6) {
    isInvalid.value = true
    return
  }
  emitValue(v)
  parseFrom(v)
}

function applyPreset(val) {
  if (typeof val !== 'string' || !val) return
  emitValue(val)
  parseFrom(val)
}

watch(() => props.modelValue, (nv) => {
  exprInput.value = nv || ''
  if (skipParse) return
  parse()
})

parse()
</script>

<style lang="scss" scoped>
.cron-gen {
  .preset-block {
    margin-bottom: 12px;
    .preset-label { font-size: 13px; font-weight: 600; color: var(--el-text-color-primary); margin-bottom: 6px; }
    .preset-grid {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 6px;
      .preset-btn { width: 100%; margin: 0; }
    }
  }

  .seg-config {
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
    padding: 10px 12px;
    margin-bottom: 12px;
    .seg-line {
      padding: 6px 0;
      border-bottom: 1px dashed var(--el-border-color-extra-light);
      &:last-child { border-bottom: none; }
      display: flex;
      align-items: center;
      gap: 10px;
      flex-wrap: wrap;
      .seg-name {
        font-size: 13px;
        font-weight: 600;
        color: var(--el-text-color-primary);
        width: 28px;
        flex-shrink: 0;
      }
      .seg-modes {
        flex-shrink: 0;
        :deep(.el-radio-button__inner) { padding: 6px 10px; font-size: 12px; }
      }
      .seg-control {
        flex-shrink: 0;
        display: flex;
        align-items: center;
        gap: 6px;
        .any-value {
          font-family: Consolas, monospace;
          font-size: 14px;
          color: var(--el-text-color-regular);
          background: var(--el-fill-color-light);
          border-radius: 4px;
          padding: 3px 14px;
        }
        .spec-value {
          font-family: Consolas, monospace;
          font-size: 13px;
          color: #1d4ed8;
          font-weight: 600;
          background: #f0f5ff;
          border-radius: 4px;
          padding: 3px 10px;
          max-width: 260px;
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
        }
        .range-input { width: 88px; }
        .dash { color: var(--el-text-color-secondary); }
        .step-prefix, .step-suffix { font-size: 12px; color: var(--el-text-color-secondary); }
        .step-input { width: 88px; }
        .quarter-hint { font-size: 12px; color: var(--el-text-color-secondary); margin-left: 6px; }
        .quarter-btn {
          min-width: 34px;
          height: 26px;
          padding: 0 9px;
          border-radius: 13px;
          border: 1px solid;
          font-size: 12px;
          font-weight: 600;
          cursor: pointer;
          transition: all .15s ease;
          line-height: 24px;
          &.q1 { color: #1d4ed8; border-color: #bfdbfe; background: #eff6ff; &:hover { box-shadow: 0 2px 6px rgba(29,78,216,.25); transform: translateY(-1px); } &.on { background: #1d4ed8; color: #fff; border-color: #1d4ed8; } }
          &.q2 { color: #047857; border-color: #a7f3d0; background: #ecfdf5; &:hover { box-shadow: 0 2px 6px rgba(4,120,87,.25); transform: translateY(-1px); } &.on { background: #047857; color: #fff; border-color: #047857; } }
          &.q3 { color: #d97706; border-color: #fde68a; background: #fffbeb; &:hover { box-shadow: 0 2px 6px rgba(217,119,6,.25); transform: translateY(-1px); } &.on { background: #d97706; color: #fff; border-color: #d97706; } }
          &.q4 { color: #7c3aed; border-color: #ddd6fe; background: #f5f3ff; &:hover { box-shadow: 0 2px 6px rgba(124,58,237,.25); transform: translateY(-1px); } &.on { background: #7c3aed; color: #fff; border-color: #7c3aed; } }
        }
      }
      .seg-picker {
        flex-basis: 100%;
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
        padding-top: 2px;
        .pick-btn {
          min-width: 26px;
          height: 24px;
          padding: 0 5px;
          border: 1px solid var(--el-border-color);
          border-radius: 4px;
          background: #fff;
          font-size: 11px;
          color: var(--el-text-color-regular);
          cursor: pointer;
          &:hover { border-color: var(--el-color-primary); color: var(--el-color-primary); }
          &.on {
            background: var(--el-color-primary);
            border-color: var(--el-color-primary);
            color: #fff;
          }
          &.special {
            background: #fdf6ec;
            border-color: #f5dab1;
            color: #e6a23c;
            font-weight: 600;
            &.on { background: #e6a23c; border-color: #e6a23c; color: #fff; }
          }
        }
      }
    }
  }

  .expr-block {
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 8px;
    padding: 10px 12px;
    margin-bottom: 12px;
    .expr-fields {
      display: flex;
      gap: 4px;
      margin-bottom: 8px;
      .expr-field {
        flex: 1;
        min-width: 0;
        text-align: center;
        .expr-label { font-size: 11px; color: var(--el-text-color-placeholder); margin-bottom: 3px; }
        .expr-value {
          font-family: Consolas, monospace;
          font-size: 13px;
          color: #1d4ed8;
          font-weight: 600;
          background: #f0f5ff;
          border-radius: 4px;
          padding: 3px 0;
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
        }
      }
    }
    .expr-row {
      display: flex;
      align-items: center;
      gap: 8px;
      .cron-label { font-size: 12px; color: var(--el-text-color-secondary); flex-shrink: 0; }
      .expr-input { flex: 1; min-width: 0; }
      .parse-btn { flex-shrink: 0; }
    }
  }

  .desc-block {
    display: flex;
    align-items: flex-start;
    gap: 12px;
    .desc-label { font-size: 13px; color: var(--el-text-color-primary); flex-shrink: 0; width: 68px; padding-top: 8px; }
    .desc-box {
      flex: 1;
      background: #f0f9eb;
      border: 1px solid #e1f3d8;
      border-radius: 6px;
      padding: 8px 12px;
      color: #529b2e;
      font-size: 13px;
      min-height: 36px;
      line-height: 1.6;
      &.invalid {
        background: #fef0f0;
        border-color: #fbc4c4;
        color: #f56c6c;
      }
    }
  }
}
</style>
