/**
 * Cron 表达式中文效果说明（CronGen 弹窗与 parentForm 调度配置页共用）
 */
const WEEK_CN = { SUN: '周日', MON: '周一', TUE: '周二', WED: '周三', THU: '周四', FRI: '周五', SAT: '周六' }
const WEEK_NUM = { 1: '周日', 2: '周一', 3: '周二', 4: '周三', 5: '周四', 6: '周五', 7: '周六' }
function weekName(x) {
  const up = String(x).toUpperCase()
  if (WEEK_CN[up]) return WEEK_CN[up]
  if (WEEK_NUM[up]) return WEEK_NUM[up]
  return null
}
const QUARTER_MAP = { '1-3': '一季度', '4-6': '二季度', '7-9': '三季度', '10-12': '四季度' }
const pad = (n) => String(n).padStart(2, '0')

function describeWeek(w) {
  const up = String(w).toUpperCase()
  if (/^[A-Z]{3}$/.test(up)) return WEEK_CN[up] || null
  if (/^[1-7]$/.test(up)) return WEEK_NUM[up] || null
  const m = up.match(/^([A-Z]{3}|[1-7])-([A-Z]{3}|[1-7])$/)
  if (m) {
    const a = weekName(m[1]), b = weekName(m[2])
    return (a && b) ? `${a}至${b}` : null
  }
  const list = up.split(',')
  if (list.length > 1 && list.every(x => /^([A-Z]{3}|[1-7])$/.test(x))) {
    return list.map(x => weekName(x) || x).join('、')
  }
  return null
}

function describeMonth(m) {
  if (/^\d+$/.test(m)) return `${m} 月`
  const mm = m.match(/^(\d+)-(\d+)$/)
  if (mm) return `${mm[1]}-${mm[2]} 月`
  if (/^\d+(,\d+)+$/.test(m)) return `${m} 月`
  return null
}

/** 字段值的中文摘要（兜底用） */
function fieldDesc(x, isWeek) {
  if (x === '*' || x === '?') return '任意'
  if (isWeek) {
    const wd = describeWeek(x)
    if (wd) return wd
  }
  return x
}

const SEG_NAMES = ['秒', '分', '时', '日', '月', '周', '年']
const SEG_OK = /^[0-9a-z,?*/\-#]+$/i

/**
 * 返回表达式的具体无效原因（段数不足/过多/字段非法）；合法返回 null
 */
export function cronInvalidReason(v) {
  const str = String(v || '').trim()
  if (!str) return '表达式为空'
  const parts = str.split(/\s+/)
  if (parts.length !== 7) {
    if (parts.length < 7) {
      return `表达式段数不足：当前 ${parts.length} 段（需 7 段：秒 分 时 日 月 周 年），缺少 ${7 - parts.length} 段`
    }
    return `表达式段数过多：当前 ${parts.length} 段（需 7 段：秒 分 时 日 月 周 年），请删除多余段`
  }
  for (let i = 0; i < 7; i++) {
    const seg = parts[i]
    if (!SEG_OK.test(seg)) {
      return `第 ${i + 1} 段（${SEG_NAMES[i]}）包含非法字符：${seg}`
    }
    // 纯字母段只允许 3 字母缩写（SUN..SAT）或 L/W；单字母 x 等视为非法
    if (/^[a-z]+$/i.test(seg) && !/^[a-z]{3}$/i.test(seg) && !/^[lw]+$/i.test(seg)) {
      return `第 ${i + 1} 段（${SEG_NAMES[i]}）值不合法：${seg}（周段可用 SUN/MON/TUE/WED/THU/FRI/SAT）`
    }
  }
  return null
}

/** 返回效果说明；表达式非法返回 null */
export function describeCron(v) {
  const parts = String(v).trim().split(/\s+/)
  if (parts.length !== 7) return null
  const [sec, min, hour, day, month, week, year] = parts
  // 宽松格式校验: 允许 * ? 数字 逗号列表 区间 步长 英文缩写 L #; 具体语义由后端解析
  const okSeg = (s) => SEG_OK.test(s)
  if (!okSeg(sec) || !okSeg(min) || !okSeg(hour) || !okSeg(day) || !okSeg(month) || !okSeg(week) || !okSeg(year)) return null
  const any = (s) => s === '*' || s === '?'
  const y = any(year) || year === ''
  const yFix = /^\d{4}$/.test(year)
  const yRange = /^\d{4}-\d{4}$/.test(year)
  const yOk = y || yFix || yRange
  const yDesc = yFix ? `${year} 年 ` : (yRange ? `${year} 年 ` : '')
  const weekAny = week === '?' || week === '*'
  // ---- 预设精确匹配 ----
  if (sec === '0' && min === '0' && hour === '0' && day === '1' && month === '1' && week === '?' && y) return '每年 1 月 1 日 00:00:00 执行'
  if (sec === '0' && min === '0' && hour === '1' && day === '1' && month === '*' && week === '?' && y) return '每月 1 号 01:00:00 执行'
  if (sec === '0' && min === '0' && hour === '9' && day === '?' && month === '*' && /^MON$/i.test(week) && y) return '每周一 09:00:00 执行'
  if (sec === '0' && min === '30' && hour === '8' && day === '*' && month === '*' && week === '?' && y) return '每天 08:30:00 执行'
  if (sec === '0' && min === '0' && hour === '2' && day === '*' && month === '*' && week === '?' && y) return '每天 02:00:00 执行'
  if (sec === '0' && min === '0' && hour === '*' && day === '*' && month === '*' && week === '?' && y) return '每小时整点执行'
  if (sec === '0' && /^0\/30$/.test(min) && hour === '*' && day === '*' && month === '*' && week === '?' && y) return '每 30 分钟执行'
  if (sec === '0' && /^\*\/5$/.test(min) && hour === '*' && day === '*' && month === '*' && week === '?' && y) return '每 5 分钟执行'
  // ---- 通用智能描述 ----
  const secStep = sec.match(/^\*?\/(\d+)$/)
  if (secStep && any(min) && any(hour) && any(day) && any(month) && weekAny && yOk) return `${yDesc}每 ${secStep[1]} 秒执行`
  const minStep = min.match(/^\*?\/(\d+)$/)
  if (sec === '0' && minStep && any(hour) && any(day) && any(month) && weekAny && yOk) return `${yDesc}每 ${minStep[1]} 分钟执行`
  const hourStep = hour.match(/^\*?\/(\d+)$/)
  if (sec === '0' && min === '0' && hourStep && any(day) && any(month) && weekAny && yOk) return `${yDesc}每 ${hourStep[1]} 小时执行`
  const monthStep = month.match(/^(\*|1)\/(\d+)$/)
  if (sec === '0' && min === '0' && hour === '0' && day === '1' && monthStep && week === '?' && yOk) {
    const mstep = parseInt(monthStep[2], 10)
    if (mstep === 3) return `${yDesc}每季度 1 号 00:00:00 执行`
    if (mstep === 6) return `${yDesc}每半年 1 号 00:00:00 执行`
  }
  const secN = parseInt(sec, 10), minN = parseInt(min, 10), hourN = parseInt(hour, 10)
  // 每月最后一个周几 / 每月第N个周几
  if (sec === '0' && !Number.isNaN(minN) && !Number.isNaN(hourN) && (day === '?' || day === '*') && any(month) && yOk) {
    const wl = week.match(/^([A-Z]{3}|[1-7])L$/i)
    if (wl) {
      const wd = weekName(wl[1])
      if (wd) return `${yDesc}每月最后一个${wd} ${pad(hourN)}:${pad(minN)} 执行`
    }
    const wh = week.match(/^([A-Z]{3}|[1-7])#(\d+)$/i)
    if (wh) {
      const wd = weekName(wh[1])
      if (wd) return `${yDesc}每月第 ${wh[2]} 个${wd} ${pad(hourN)}:${pad(minN)} 执行`
    }
  }
  // 每周固定时刻（周段 MON / MON-FRI / 列表）
  if (sec === '0' && !Number.isNaN(minN) && !Number.isNaN(hourN) && (day === '?' || day === '*') && any(month) && yOk) {
    const wd = describeWeek(week)
    if (wd) return `${yDesc}${wd} ${pad(hourN)}:${pad(minN)} 执行`
  }
  // 每天固定时刻
  if (!Number.isNaN(secN) && !Number.isNaN(minN) && !Number.isNaN(hourN) && any(day) && any(month) && weekAny && yOk) {
    return `${yDesc}每天 ${pad(hourN)}:${pad(minN)}:${pad(secN)} 执行`
  }
  // 每月最后一天（日段 L）
  if (sec === '0' && !Number.isNaN(minN) && !Number.isNaN(hourN) && /^L$/i.test(day) && any(month) && weekAny && yOk) {
    return `${yDesc}每月最后一天 ${pad(hourN)}:${pad(minN)} 执行`
  }
  // 每月固定日
  if (sec === '0' && !Number.isNaN(minN) && !Number.isNaN(hourN) && /^\d+$/.test(day) && any(month) && weekAny && yOk) {
    return `${yDesc}每月 ${day} 号 ${pad(hourN)}:${pad(minN)} 执行`
  }
  // 季度（月段 1-3/4-6/7-9/10-12）
  if (sec === '0' && !Number.isNaN(minN) && !Number.isNaN(hourN) && weekAny && yOk) {
    const q = QUARTER_MAP[month]
    if (q) {
      const dayDesc = /^\d+$/.test(day) ? `第 ${day} 号 ` : (any(day) ? '每天 ' : `${day} `)
      return `每季度${yDesc}${dayDesc}${pad(hourN)}:${pad(minN)} 执行`
    }
  }
  // 月份列表/区间
  if (sec === '0' && !Number.isNaN(minN) && !Number.isNaN(hourN) && /^\d+$/.test(day) && weekAny && yOk) {
    const monthDesc = describeMonth(month)
    if (monthDesc) return `${yDesc}${monthDesc} ${day} 号 ${pad(hourN)}:${pad(minN)} 执行`
  }
  // ---- 兜底: 字段级中文摘要 ----
  return `按表达式执行（秒 ${fieldDesc(sec)}、分 ${fieldDesc(min)}、时 ${fieldDesc(hour)}、日 ${fieldDesc(day)}、月 ${fieldDesc(month)}、周 ${fieldDesc(week, true)}、年 ${fieldDesc(year)}）`
}
