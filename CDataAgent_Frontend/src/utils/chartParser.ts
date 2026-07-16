/**
 * 从 AI 消息文本中提取 ECharts v5 option JSON。
 *
 * 后端 ChartGenAgent 输出格式（见 prompt）：
 *   {ECHARTS_OPTION_JSON}
 *   注意
 *   {分析结论文本}
 *
 * MainAgent 汇总后 JSON 可能被代码块或多余文本包裹。
 * 此解析器支持：代码块 → 全文扫描 → 分隔符 → 全文解析
 */

interface EChartsOption {
  series?: unknown[]
  [key: string]: unknown
}

export interface ChartParseResult {
  /** 解析后的 ECharts option JSON 对象 */
  option: EChartsOption
  /** 剩余分析文本（不包含 JSON 的部分） */
  analysis: string
}

let debugCount = 0

/**
 * 尝试从文本中提取 ECharts 图表配置。
 */
export function extractChartOption(text: string): ChartParseResult | null {
  if (!text) {
    console.log('[chartParser] empty text')
    return null
  }

  const id = ++debugCount

  // 1) 代码块 (```json ... ```) — 最高优先级，能最准确分离 JSON 和分析文本
  let result = extractFromCodeBlock(text)
  if (result) {
    console.log(`[chartParser#${id}] found from code block`)
    return result
  }

  // 2) 全文扫描 JSON 对象（平衡大括号，包含嵌套 + 字符串转义）
  result = scanJsonAnywhere(text)
  if (result) {
    console.log(`[chartParser#${id}] found from scan`)
    return result
  }

  // 3) 分隔符 (【【【【【 / 注意)
  result = extractBySeparator(text)
  if (result) {
    console.log(`[chartParser#${id}] found from separator`)
    return result
  }

  // 4) 全文尝试
  result = tryParseFullText(text)
  if (result) {
    console.log(`[chartParser#${id}] found from full text`)
    return result
  }

  console.log(
    `[chartParser#${id}] no chart found. text length=${text.length}, preview="${text.slice(0, 120)}..."`,
  )
  return null
}

/** 匹配 ```json ... ``` 代码块，提取 JSON 和剩余分析文本 */
function extractFromCodeBlock(text: string): ChartParseResult | null {
  const blockRegex = /```(\w*)\s*\n?([\s\S]*?)```/
  const match = text.match(blockRegex)
  if (!match) return null

  const jsonStr = match[2].trim()
  const option = tryParseJson(jsonStr)
  if (!option || !isValidEChartsOption(option)) return null

  const analysis = text.replace(match[0], '').trim()
  return { option, analysis }
}

/**
 * 扫描文本中任意位置的 JSON 对象（平衡大括号）。
 * 处理嵌套对象、字符串中的大括号、转义字符。
 */
function scanJsonAnywhere(text: string): ChartParseResult | null {
  for (let i = 0; i < text.length; i++) {
    if (text[i] !== '{') continue

    let depth = 0
    let inStr = false

    for (let j = i; j < text.length; j++) {
      const ch = text[j]

      if (inStr) {
        if (ch === '\\') {
          j++ // skip escaped char
          continue
        }
        if (ch === '"') inStr = false
        continue
      }

      // not in string
      if (ch === '"') {
        inStr = true
        continue
      }
      if (ch === '{') {
        depth++
        continue
      }
      if (ch === '}') {
        depth--
        if (depth === 0) {
          // 找到平衡的 JSON 对象 text[i..j]
          const candidate = text.substring(i, j + 1)
          try {
            const parsed = JSON.parse(candidate)
            if (isValidEChartsOption(parsed)) {
              const before = text.slice(0, i).trim()
              const after = text.slice(j + 1).trim()
              const analysis = [before, after].filter(Boolean).join('\n\n')
              return { option: parsed, analysis }
            }
          } catch {
            // 不是合法 JSON，继续找下一个 }
          }
        }
      }
    }
  }
  return null
}

/** 按分隔符提取：JSON + 分隔符 + 文本 */
function extractBySeparator(text: string): ChartParseResult | null {
  const separators = ['【【【【【', '注意']

  for (const sep of separators) {
    const idx = text.indexOf(sep)
    if (idx === -1) continue

    const before = text.slice(0, idx).trim()
    if (!before) continue

    const option = tryParseJson(before)
    if (!option || !isValidEChartsOption(option)) continue

    const analysis = text.slice(idx + sep.length).trim()
    return { option, analysis }
  }

  return null
}

/** 尝试将全文作为 JSON 解析 */
function tryParseFullText(text: string): ChartParseResult | null {
  const trimmed = text.trim()
  if (!trimmed.startsWith('{')) return null

  const option = tryParseJson(trimmed)
  if (!option || !isValidEChartsOption(option)) return null

  return { option, analysis: '' }
}

// ---- 辅助 ----

/** 安全解析 JSON 字符串 */
function tryParseJson(str: string): EChartsOption | null {
  try {
    // 去掉 markdown 包裹残留
    const cleaned = str
      .replace(/^```(?:json|javascript|js)?\s*/i, '')
      .replace(/```\s*$/, '')
      .trim()

    if (!cleaned.startsWith('{')) return null
    return JSON.parse(cleaned) as EChartsOption
  } catch {
    return null
  }
}

/** 验证是否为有效的 ECharts option（必须包含 series） */
function isValidEChartsOption(option: EChartsOption): boolean {
  if (!option || typeof option !== 'object') return false

  const { series } = option
  if (!Array.isArray(series) || series.length === 0) return false

  return series.every(
    (s) => s && typeof s === 'object' && typeof (s as Record<string, unknown>).type === 'string',
  )
}
