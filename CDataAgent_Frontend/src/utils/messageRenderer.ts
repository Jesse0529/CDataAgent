import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { extractChartOption } from './chartParser'

export interface ContentSegment {
  type: 'text' | 'table'
  key: number
  html?: string
  headers?: string[]
  rows?: string[][]
}

const UNSUPPORTED_DIAGRAM_FENCE = /```\s*(?:mermaid|plantuml|puml|dot|graphviz)\b/gi
const UNSUPPORTED_DIAGRAM_DIRECTIVE =
  /^\s*(?:(?:graph|flowchart)\s+(?:TD|TB|BT|RL|LR)\b|sequenceDiagram\b|classDiagram\b|stateDiagram(?:-v2)?\b|erDiagram\b|mindmap\b|journey\b|gantt\b).*?(?=\n\s*\n|$)/gims
const DIAGRAM_PLACEHOLDER = '（关系图谱和流程图不支持直接展示，请使用表格或要点描述。）'

/**
 * 图谱 DSL 不属于当前受控展示能力。流式时先隐藏未闭合代码块，避免撑开布局；
 * 最终结果由后端同一策略转换为文本说明。
 */
function removeUnsupportedDiagramBlocks(text: string): string {
  let result = ''
  let cursor = 0
  UNSUPPORTED_DIAGRAM_FENCE.lastIndex = 0
  let match = UNSUPPORTED_DIAGRAM_FENCE.exec(text)
  while (match) {
    result += text.slice(cursor, match.index)
    const bodyStart = UNSUPPORTED_DIAGRAM_FENCE.lastIndex
    const closing = text.indexOf('```', bodyStart)
    result += DIAGRAM_PLACEHOLDER
    if (closing < 0) return result
    cursor = closing + 3
    UNSUPPORTED_DIAGRAM_FENCE.lastIndex = cursor
    match = UNSUPPORTED_DIAGRAM_FENCE.exec(text)
  }
  return (result + text.slice(cursor)).replace(UNSUPPORTED_DIAGRAM_DIRECTIVE, DIAGRAM_PLACEHOLDER)
}
export function getDisplayText(text: string): string {
  if (!text) return ''

  // 旧消息兜底：content 中可能仍有 JSON+分隔符
  const result = extractChartOption(text)
  if (result) return result.analysis

  // 以 `{` 开头 → 只有 JSON
  if (text.trim().startsWith('{')) return ''

  return text
}
/** 检测是否为表格对齐行（含多列场景 |:---:|:---:|:----:|） */
function isAlignmentRow(line: string): boolean {
  const trimmed = line.trim()
  if (!trimmed.startsWith('|') || !trimmed.endsWith('|')) return false
  // 去掉首尾 |，按列拆分，每列检查是否为对齐标记 :--- 或 :---: 或 ---
  const inner = trimmed.slice(1, -1).trim()
  if (!inner) return false
  const cells = inner
    .split('|')
    .map((c) => c.trim())
    .filter(Boolean)
  return cells.length > 0 && cells.every((c) => /^:?-+:?$/.test(c))
}

/**
 * 合并断行表格行：LLM 可能将一行数据拆成多行输出，
 * 如 |2020\n| 2.463 | → |2020| 2.463 |
 */
function mergeBrokenTableRows(text: string): string {
  const lines = text.split('\n')
  for (let i = 0; i < lines.length - 1; i++) {
    const curr = lines[i].trim()
    const next = lines[i + 1].trim()
    if (curr.startsWith('|') && next.startsWith('|')) {
      const cPipes = (curr.match(/\|/g) || []).length
      const nPipes = (next.match(/\|/g) || []).length
      if (cPipes <= 2 && nPipes >= 3) {
        lines[i] = curr + next
        lines.splice(i + 1, 1)
        i--
      }
    }
  }
  return lines.join('\n')
}

/** HTML 转义（防止 XSS 和格式破坏） */
function escapeHtml(str: string): string {
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/**
 * 渲染单元格内容：HTML 转义 + 行内 markdown 格式化（粗体、斜体、行内代码）。
 * 用于表格单元格 v-html 输出。
 */
export function renderCellContent(text: string): string {
  if (!text) return ''
  return (
    escapeHtml(text)
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/__(.+?)__/g, '<strong>$1</strong>')
      .replace(/\*(.+?)\*/g, '<em>$1</em>')
      .replace(/_(.+?)_/g, '<em>$1</em>')
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      // 清理流式中未配对的 ** 标记
      .replace(/\*{2,}/g, '')
  )
}

/**
 * 解压 LLM 压缩表格格式：将 `||`（行分隔符）替换为 `|\n|`。
 *
 * LLM 有时将表格所有行写到同一行中用 `||` 做行分隔符。
 * 注意：`||` 可能在 token 拼接时产生（前端看到完整内容），
 * 所以必须在完整内容上操作，逐 token 处理后端不够可靠。
 * 仅对以 `|` 开头的行生效，不影响普通文本。
 */
function uncompressTableRows(text: string): string {
  return text
    .split('\n')
    .map((line) => {
      if (line.trim().startsWith('|') && /\|{2,}/.test(line)) {
        return line.replace(/\|{2,}/g, '|\n|')
      }
      return line
    })
    .join('\n')
}

/**
 * 从表格行块中分离尾部残留文本。
 *
 * LLM 输出的压缩表格有时在末尾缺少 `||` 行分隔符，导致表格最后一行的
 * 单元格数超出表头列数（尾部附带了本应另起一行的总结文本）。
 * 此函数检测并切除超出的列，将其作为独立文本段返回。
 *
 * 例如表头 4 列，最后一行有 8 列：
 *   |19|娇兰|26,859|0.1%|>💰总销售额：38,555,705|品牌数：19|...
 * → 清理后：|19|娇兰|26,859|0.1%|
 * → 尾部文本：>💰总销售额：38,555,705|品牌数：19|...
 *
 * 正常表格（列数一致）不受影响。
 */
function splitTableTail(rows: string[]): { cleanRows: string[]; tailText: string } {
  if (rows.length < 2) return { cleanRows: rows, tailText: '' }

  // 用第一行非对齐行作为表头，确定列数
  const headerRow = rows.find((r) => !isAlignmentRow(r.trim()))
  if (!headerRow) return { cleanRows: rows, tailText: '' }
  const expectedCols = splitPipeRow(headerRow).length
  const lastRow = rows[rows.length - 1]
  const lastCols = splitPipeRow(lastRow).length

  // 无多余列 → 正常表格
  if (lastCols <= expectedCols) return { cleanRows: rows, tailText: '' }

  // 有多余列 → 切出尾部文本
  const trimmed = lastRow.trim()
  // 找到第 expectedCols 个 | 的位置（将其作为最后一列的终止符）
  let pipeCount = 0
  let pipeIdx = -1
  for (let j = 0; j < trimmed.length; j++) {
    if (trimmed[j] === '|') {
      pipeCount++
      if (pipeCount === expectedCols) {
        pipeIdx = j
        break
      }
    }
  }
  let endIdx = -1
  if (pipeIdx >= 0) {
    for (let j = pipeIdx + 1; j < trimmed.length; j++) {
      if (trimmed[j] === '|') {
        endIdx = j
        break
      }
    }
  }

  if (endIdx <= pipeIdx) return { cleanRows: rows, tailText: '' }

  const cleanRow = trimmed.slice(0, endIdx + 1)
  const tailRaw = trimmed.slice(endIdx + 1).trim()

  return {
    cleanRows: [...rows.slice(0, -1), cleanRow],
    tailText: tailRaw,
  }
}

/**
 * 将管道行截断到指定列数，保留原始首尾管道风格。
 */
function truncateRowToCols(row: string, colCount: number): string {
  const trimmed = row.trim()
  const hasLeadingPipe = trimmed.startsWith('|')
  const hasTrailingPipe = trimmed.endsWith('|')
  const cells = splitPipeRow(trimmed)
  if (cells.length <= colCount) return row
  const truncated = cells.slice(0, colCount)
  let result = truncated.join('|')
  if (hasLeadingPipe) result = `|${result}`
  if (hasTrailingPipe) result = `${result}|`
  return result
}

/**
 * 从超出预期列数的行中提取尾部文本。
 */
function extractOverflowText(row: string, colCount: number): string {
  const cells = splitPipeRow(row)
  if (cells.length <= colCount) return ''
  return cells.slice(colCount).join(' ').trim()
}

/**
 * 拆分表格块中中间行列数溢出的拼接表。
 *
 * LLM 有时将两个独立的管道表连在一起输出（无空行分隔），
 * 偶尔还在中间行末尾直接附加文字而不换行。此函数检测
 * 每一行的列数是否超过表头列数，在溢出处切割，返回子表数组。
 *
 * 每个子表 { tableRows, tailText } 中 tableRows 在溢出行已被截断，
 * tailText 是从该行切出的多余文字（作文本段渲染）。
 */
function splitTableBlock(rows: string[]): { tableRows: string[]; tailText: string }[] {
  if (rows.length < 2) return [{ tableRows: rows, tailText: '' }]

  // 找到第一个非对齐行作为表头，确定预期列数
  const headerRow = rows.find((r) => !isAlignmentRow(r))
  if (!headerRow) return [{ tableRows: rows, tailText: '' }]
  const result: { tableRows: string[]; tailText: string }[] = []
  let blockStart = 0

  while (blockStart < rows.length) {
    // 每个子表独立确定自己的表头
    const currentHeader = rows.slice(blockStart).find((r) => !isAlignmentRow(r))
    if (!currentHeader) {
      result.push({ tableRows: rows.slice(blockStart), tailText: '' })
      break
    }
    const expected = splitPipeRow(currentHeader).length

    // 从表头下一行往后扫描溢出（跳过对齐行）
    let foundOverflow = false
    for (let j = blockStart + 1; j < rows.length; j++) {
      if (isAlignmentRow(rows[j])) continue
      const cells = splitPipeRow(rows[j])
      if (cells.length > expected) {
        // 第 j 行溢出 → 截断后作为当前子表的末行
        const truncated = truncateRowToCols(rows[j], expected)
        const tail = extractOverflowText(rows[j], expected)
        result.push({
          tableRows: [...rows.slice(blockStart, j), truncated],
          tailText: tail,
        })
        blockStart = j + 1
        foundOverflow = true
        break
      }
    }

    if (!foundOverflow) {
      result.push({ tableRows: rows.slice(blockStart), tailText: '' })
      break
    }
  }

  return result
}

/**
 * 将管道行数组解析为结构化的表头 + 行数据。
 */
function parseTableRows(rows: string[]): { headers: string[]; dataRows: string[][] } {
  if (rows.length < 2) return { headers: [], dataRows: [] }

  const firstIsAlign = isAlignmentRow(rows[0])
  let headerIdx = firstIsAlign ? -1 : 0
  let bodyStart = firstIsAlign ? 1 : 1

  if (!firstIsAlign && rows.length > 1 && isAlignmentRow(rows[1])) {
    bodyStart = 2
  }

  // 修复：对齐行在表头前面的非标准情况（如 | :--- | :--- |\n| 区域 | 房源数量 |）
  if (firstIsAlign && rows.length > 1 && !isAlignmentRow(rows[1])) {
    headerIdx = 1
    bodyStart = 2
  }

  // 表头
  let headers: string[] = []
  if (headerIdx >= 0) {
    headers = splitPipeRow(rows[headerIdx])
  } else if (bodyStart < rows.length) {
    // 即使无显式表头行，也使用实际行内容而非 "列1"/"列2" 占位
    headers = splitPipeRow(rows[bodyStart])
    bodyStart++ // 跳过已用作表头的行，避免数据行重复
  }

  // 数据行
  const dataRows: string[][] = []
  for (let i = bodyStart; i < rows.length; i++) {
    const cells = splitPipeRow(rows[i])
    // 列数归一化：确保每行与表头列数一致（补齐或截断）
    if (headers.length > 0 && cells.length !== headers.length) {
      if (cells.length < headers.length) {
        while (cells.length < headers.length) cells.push('')
      } else {
        cells.splice(headers.length)
      }
    }
    dataRows.push(cells)
  }

  return { headers, dataRows }
}

/**
 * 将流式原始内容拆分为「文本段」和「表格段」。
 *
 * 表格段使用结构化数据（headers + rows），模板中以 v-for 方式逐行渲染，
 * 表头骨架固定不动，新增行自动追加——不存在 v-html 整体替换导致的闪烁。
 *
 * 文本段使用 streamingMarkdown，流式和完成态完全一致。
 */
export function parseContentSegments(text: string): ContentSegment[] {
  if (!text) return []

  // 1. 标准化 + 内部标记兜底清理（后端可能跨 token 遗漏）
  let raw = normalizeMarkdown(text)
    .replace(/#+\s*NEEDS_CHART#*/gi, '')
    .replace(/【{4,}/g, '')
    .replace(/】{4,}/g, '')
    .replace(/现在为您生成可视化图表[！!]?/g, '')
    .replace(/✅图表已生成并校验通过[！!]?/g, '')
    .replace(/###柱状图\s*✅?\s*图表已生成并校验通过[！!]?/g, '')
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/([^\n])(#{1,6}\s)/g, '$1\n\n$2')
    .replace(/(#{1,6}.+?)(\|[^|]+\|)/g, '$1\n$2')
    .replace(/(#{1,6}.+?)\n(\|)/g, '$1\n\n$2')

  // 解压 || 行分隔符（token 拼接产生的压缩格式，必须在完整内容上处理）
  raw = uncompressTableRows(raw)
  raw = mergeBrokenTableRows(raw)

  // 2. 按管道行边界拆分段落
  const lines = raw.split('\n')
  const segments: ContentSegment[] = []
  let keyCounter = 0

  let i = 0
  while (i < lines.length) {
    if (lines[i].trim().startsWith('|')) {
      // 收集连续管道行 → 表格段
      const tableRows: string[] = []
      while (i < lines.length && lines[i].trim().startsWith('|')) {
        tableRows.push(lines[i])
        i++
      }

      // 拆分中间行溢出的拼接表格（LLM 常将两个表连在一起且无换行）
      const subTables = splitTableBlock(tableRows)
      for (const sub of subTables) {
        // 子表内继续检测末行溢出
        const { cleanRows, tailText } = splitTableTail(sub.tableRows)
        if (cleanRows.length >= 2) {
          // → 结构化表格段
          const { headers, dataRows } = parseTableRows(cleanRows)
          segments.push({
            type: 'table',
            key: keyCounter++,
            headers,
            rows: dataRows,
          })
        } else if (cleanRows.length === 1) {
          // 流式初期仅表头 → 也渲染为结构化表格（骨架）
          const cells = splitPipeRow(cleanRows[0])
          if (cells.length >= 2) {
            segments.push({
              type: 'table',
              key: keyCounter++,
              headers: cells,
              rows: [],
            })
          }
        }
        // 子表末尾溢出文本
        if (tailText) {
          segments.push({
            type: 'text',
            key: keyCounter++,
            html: streamingMarkdown(tailText),
          })
        }
        // 子表间溢出文本（中间行多出的列内容）
        if (sub.tailText) {
          segments.push({
            type: 'text',
            key: keyCounter++,
            html: streamingMarkdown(sub.tailText),
          })
        }
      }
    } else {
      // 收集连续非管道行 → 文本段
      const textLines: string[] = []
      while (i < lines.length && !lines[i].trim().startsWith('|')) {
        textLines.push(lines[i])
        i++
      }
      const joined = textLines.join('\n')
      if (joined.trim()) {
        segments.push({
          type: 'text',
          key: keyCounter++,
          html: streamingMarkdown(joined),
        })
      }
    }
  }

  return segments
}

function normalizeMarkdown(text: string): string {
  if (!text) return ''
  return (
    text
      // ##text → ## text（行首标题后补空格）
      .replace(/^(#{1,6})(?!\s|$)/gm, '$1 ')
      // 行中标题：text##heading → text\n\n## heading（无空格、emoji 等情况）
      .replace(/([^\n])(#{2,6})([^#\s].*)$/gm, '$1\n\n$2 $3')
      // 独立 --- 行（装饰分隔线）→ 移除（含前后空白）
      .replace(/^[ \t]*---+[ \t]*$/gm, '')
      // ---## → ##（去掉 --- 前缀，用零宽预查不消费 #）
      .replace(/---+[ \t]*(?=#)/g, '')
      // **text 前补空格：防止 ** 紧贴前文字符
      .replace(/([^\s(])\*\*/g, '$1 **')
      // 清理 LLM 内部标记
      .replace(/#+\s*NEEDS_CHART#*/gi, '')
      .replace(/##CONCLUSION##\r?\n?/gi, '')
      .replace(/\r?\n?##END##/gi, '')
      // 清理 Markdown 图片语法 ![alt](url) → 仅保留 alt 文本
      .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
  )
}

/**
 * 流式阶段的渲染器。
 * 支持标题、表格、行内格式，与完成态渲染风格一致。
 */
export function streamingMarkdown(text: string): string {
  if (!text) return ''

  let raw = removeUnsupportedDiagramBlocks(text)
    // 过滤图表确认文本（已有独立图表按钮，不应在文本中重复）
    .replace(/现在为您生成可视化图表[！!]?/g, '')
    .replace(/✅图表已生成并校验通过[！!]?/g, '')
    .replace(/###柱状图\s*✅?\s*图表已生成并校验通过[！!]?/g, '')
    // 清理 LLM 内部标记
    .replace(/#+\s*NEEDS_CHART#*/gi, '')
    .replace(/##CONCLUSION##\r?\n?/gi, '')
    .replace(/\r?\n?##END##/gi, '')
    // 清理 Markdown 图片语法 ![alt](url) → 仅保留 alt 文本
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    // 归一化标题：##标题 → ## 标题
    .replace(/^(#{1,6})(?!\s|$)/gm, '$1 ')
    // 标题前文字无换行时补换行
    .replace(/([^\n])(#{1,6}\s)/g, '$1\n\n$2')
    // 标题后紧跟表格行时补空行（含同行情景 ###表格|排名 → ###表格\n|排名）
    .replace(/(#{1,6}.+?)(\|[^|]+\|)/g, '$1\n$2')
    // 标题后紧跟表格行有换行时补空行
    .replace(/(#{1,6}.+?)\n(\|)/g, '$1\n\n$2')

  // 合并断行表格：|2020\n| 2.463 | → |2020| 2.463 |
  raw = mergeBrokenTableRows(raw)

  return DOMPurify.sanitize(marked.parse(raw, { async: false, breaks: true, gfm: true }), {
    ALLOWED_ATTR: ['href', 'title'],
    ALLOWED_TAGS: [
      'a',
      'blockquote',
      'br',
      'code',
      'del',
      'em',
      'h1',
      'h2',
      'h3',
      'h4',
      'h5',
      'h6',
      'hr',
      'li',
      'ol',
      'p',
      'pre',
      'strong',
      'table',
      'tbody',
      'td',
      'th',
      'thead',
      'tr',
      'ul',
    ],
  })
}

/** 拆分管道表格行。 */
function splitPipeRow(line: string): string[] {
  const t = line.trim().replace(/^\|+/, '').replace(/\|+$/, '')
  return t.split('|').map((c) => c.trim())
}
