/**
 * AI 消息气泡组件。
 *
 * 当 AI 响应包含 ECharts v5 图表配置时：
 *  - 流式过程中自动过滤 raw JSON，只展示分析文本
 *  - 完成后显示「查看可视化图表」引导按钮
 *  - 点击按钮打开全屏弹窗清晰展示图表
 *
 * 当 AI 响应包含结论（conclusion）时：
 *  - 独立渲染"分析结论"卡片，与推理文本分离
 *  - 结论区右上角提供一键复制按钮
 */

<script setup lang="ts">
import { computed, defineAsyncComponent, ref } from 'vue'
import { useMessage } from 'naive-ui'
import type { ChatMessageVO } from '@/services/types'
import { extractChartOption } from '@/utils/chartParser'
import LogoIcon from './LogoIcon.vue'

const msg = useMessage()

/** 图表弹窗异步加载（仅用户点击时触发 ECharts 加载） */
const ChartPreviewModal = defineAsyncComponent(() => import('./ChartPreviewModal.vue'))
interface ContentSegment {
  type: 'text' | 'table'
  key: number
  /** 文本段渲染后的 HTML */
  html?: string
  /** 表格段列标题 */
  headers?: string[]
  /** 表格段行数据（每行是一个字符串数组） */
  rows?: string[][]
}

const props = defineProps<{
  message: ChatMessageVO
}>()

const showChartModal = ref(false)
const copySuccess = ref(false)

/** 消息的结论文本（独立于推理过程） */
const conclusion = computed((): string | null => {
  if (props.message.role !== 'ai') return null
  if (props.message.conclusion && props.message.conclusion.trim()) {
    return props.message.conclusion.trim()
  }
  return null
})

/** 复制结论到剪贴板 */
async function handleCopyConclusion(): Promise<void> {
  if (!conclusion.value) return
  try {
    await navigator.clipboard.writeText(conclusion.value)
    copySuccess.value = true
    msg.success('分析结论已复制到剪贴板')
    setTimeout(() => { copySuccess.value = false }, 2000)
  } catch {
    msg.error('复制失败，请手动选择文本复制')
  }
}

/**
 * 从消息中提取图表配置数组（仅完成消息解析，流式消息不解析）。
 *
 * 优先级：
 *  1. message.chartOption（从 event:complete 或 DB 加载的对象数组）
 *  2. extractChartOption(content) 解析（老消息 content 中可能仍有 JSON）
 *
 * 守卫：流式消息（status !== 'done'）和错误消息不走 JSON 扫描。
 */
const chartResult = computed((): Record<string, unknown>[] | null => {
  if (props.message.role !== 'ai') return null

  // 流式消息：chart 事件可能在中途到达（Synthesizer 文本之前），期间即可显示
  if (props.message.chartOption && props.message.chartOption.length > 0) {
    return props.message.chartOption
  }

  // 已完成消息兜底：从 content 正则解析（旧消息）
  if (props.message.status === 'done') {
    const result = extractChartOption(props.message.content)
    if (result) {
      console.log('[ChatMessage] chart detected from content', {
        id: props.message.id,
        analysisLen: result.analysis.length,
      })
      return [result.option]
    }
  }

  return null
})

/**
 * 获取展示文本：过滤掉可能的图表 JSON，只保留分析文本。
 * 新消息 content 已不含 JSON 和分隔符（ChartOutputTool 存 chartOption，
 * Synthesizer 只输出分析文本），此函数仅作为旧消息兜底。
 */
function getDisplayText(text: string): string {
  if (!text) return ''

  // 旧消息兜底：content 中可能仍有 JSON+分隔符
  const result = extractChartOption(text)
  if (result) return result.analysis

  // 以 `{` 开头 → 只有 JSON
  if (text.trim().startsWith('{')) return ''

  return text
}

/**
 * 流式消息的展示文本（函数，非 computed，避免缓存导致的陈旧渲染）。
 * 流式期间使用极简渲染器，只处理行内格式，不做表格解析（不闪屏）。
 * 无真实内容时 → 显示后端推送的状态文本。
 */
function streamingDisplay(): string {
  const text = getDisplayText(props.message.content)
  if (text) return streamingMarkdown(text)
  const raw = props.message.content.trim()
  return raw || '思考中…'
}

/** 流式消息中是否有真实分析文本（非状态文本） */
const hasRealContent = computed(() => {
  if (props.message.status !== 'streaming') return true
  return getDisplayText(props.message.content).length > 0
})

/** 检测是否为表格对齐行（含多列场景 |:---:|:---:|:----:|） */
function isAlignmentRow(line: string): boolean {
  const trimmed = line.trim()
  if (!trimmed.startsWith('|') || !trimmed.endsWith('|')) return false
  // 去掉首尾 |，按列拆分，每列检查是否为对齐标记 :--- 或 :---: 或 ---
  const inner = trimmed.slice(1, -1).trim()
  if (!inner) return false
  const cells = inner.split('|').map(c => c.trim()).filter(Boolean)
  return cells.length > 0 && cells.every(c => /^:?-+:?$/.test(c))
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
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

/**
 * 渲染单元格内容：HTML 转义 + 行内 markdown 格式化（粗体、斜体、行内代码）。
 * 用于表格单元格 v-html 输出。
 */
function renderCellContent(text: string): string {
  if (!text) return ''
  return escapeHtml(text)
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/__(.+?)__/g, '<strong>$1</strong>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    .replace(/_(.+?)_/g, '<em>$1</em>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    // 清理流式中未配对的 ** 标记
    .replace(/\*{2,}/g, '')
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
  return text.split('\n').map(line => {
    if (line.trim().startsWith('|') && /\|{2,}/.test(line)) {
      return line.replace(/\|{2,}/g, '|\n|')
    }
    return line
  }).join('\n')
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
  const headerRow = rows.find(r => !isAlignmentRow(r.trim()))
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
  if (hasLeadingPipe) result = '|' + result
  if (hasTrailingPipe) result = result + '|'
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
  const headerRow = rows.find(r => !isAlignmentRow(r))
  if (!headerRow) return [{ tableRows: rows, tailText: '' }]
  const expectedCols = splitPipeRow(headerRow).length

  const result: { tableRows: string[]; tailText: string }[] = []
  let blockStart = 0

  while (blockStart < rows.length) {
    // 每个子表独立确定自己的表头
    const currentHeader = rows.slice(blockStart).find(r => !isAlignmentRow(r))
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
function parseContentSegments(text: string): ContentSegment[] {
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

/**
 * 统一内容分段：流式和完成态共用同一渲染路径。
 * 文本段始终 streamingMarkdown，表格段始终结构化数据，
 * 确保过渡零差异。
 */
const contentSegments = computed((): ContentSegment[] => {
  const raw = props.message.content
  if (!raw) return []

  // 完成态先清理旧消息中可能嵌入的 chart JSON（兼容历史数据）
  const content = (props.message.status !== 'streaming')
    ? (getDisplayText(raw) || raw)
    : raw

  // 兜底清理：移除可能残留的 ##CONCLUSION## / ##END## 标记
  const clean = content
    .replace(/##CONCLUSION##\r?\n?/gi, '')
    .replace(/\r?\n?##END##/gi, '')
    .trim()

  const segments = parseContentSegments(clean)

  // 流式状态：将光标插入最后一个文本段的末段 <p> 内，紧跟最后一个文字
  if (props.message.status === 'streaming' && segments.length > 0) {
    for (let i = segments.length - 1; i >= 0; i--) {
      const seg = segments[i]
      if (seg.type === 'text' && seg.html) {
        // 在最后一个 </p> 前插入光标，使其位于段落末尾、紧跟最后一个字
        const lastP = seg.html.lastIndexOf('</p>')
        if (lastP >= 0) {
          segments[i] = {
            ...seg,
            html: seg.html.slice(0, lastP) + '<span class="cursor-blink">▌</span>' + seg.html.slice(lastP),
          }
        } else {
          // 无 <p> 包裹时直接追加
          segments[i] = { ...seg, html: seg.html + '<span class="cursor-blink">▌</span>' }
        }
        break
      }
    }
  }

  return segments
})

/**
 * 标准化 markdown 文本：修正 LLM 输出的常见格式瑕疵，使前端渲染更稳定。
 * 作为 prompt 约束的兜底，即使 LLM 偶有偏差也不会产生裸符号。
 */
function normalizeMarkdown(text: string): string {
  if (!text) return ''
  return text
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
}

/**
 * 流式阶段的渲染器。
 * 支持标题、表格、行内格式，与完成态渲染风格一致。
 */
function streamingMarkdown(text: string): string {
  if (!text) return ''

  let raw = text
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

  // 1. 转义 HTML（必须在表格转换前，否则会转义 <table> 标签）
  const escaped = raw
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')

  // 2. 行内格式
  let html = escaped
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/__(.+?)__/g, '<strong>$1</strong>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    .replace(/_(.+?)_/g, '<em>$1</em>')
    // 清理流式中未配对的 ** 标记（避免显示裸符号）
    .replace(/\*{2,}/g, '')

  // 3. 管道表格块 → HTML（在转义后执行，| 不受转义影响）
  html = applyTableBlocks(html)

  // 4. 块级格式（逐行）
  const lines = html.split('\n')
  const processed = lines.map(line => {
    const t = line.trim()
    // 标准标题：## 文本（含空格）
    const hm = t.match(/^(#{1,6})\s+(.+)$/)
    if (hm) return `<h${hm[1].length}>${hm[2]}</h${hm[1].length}>`
    // 兜底：## 开头的行都视为标题（无空格、emoji、特殊字符等情况）
    const hm2 = t.match(/^(#{2,6})(.+)$/)
    if (hm2) return `<h${hm2[1].length}>${hm2[2]}</h${hm2[1].length}>`
    if (/^<table/i.test(t)) return t
    // 分隔线：---/*** → <hr>
    if (/^-{3,}$/.test(t) || /^\*{3,}$/.test(t)) return '<hr>'
    // 游离 # 号行（非标题内容）→ 移除
    if (/^#+$/.test(t)) return ''
    return line
  })
  html = processed.join('\n')

  // 5. 双换行分段
  const paragraphs = html.split(/\n\n+/).filter(Boolean)
  return paragraphs
    .map(p => {
      const tp = p.trim()
      if (/^<(h[1-6]|table|hr)/i.test(tp)) return tp
      return `<p>${tp.replace(/\n/g, '<br>')}</p>`
    })
    .join('')
}

/** 将管道表格行数组转为 HTML <table>（单元格内容已 HTML 转义）。 */
function pipeTableToHtml(rows: string[]): string {
  if (rows.length === 0) return ''
  if (rows.length === 1) {
    // 流式过程中仅表头到达 → 渲染为仅有表头的表格骨架，避免原始管道文本闪烁
    const cells = splitPipeRow(rows[0])
    if (cells.length >= 2) {
      let html = '<table class="msg-table-wrap">'
      html += '<thead><tr>'
      for (const c of cells) html += `<th>${escapeHtml(c) || '&nbsp;'}</th>`
      html += '</tr></thead><tbody></tbody></table>'
      return html
    }
    return rows[0] || ''
  }

  // 判断第一行是否为对齐行（无表头的情况）
  const firstIsAlign = isAlignmentRow(rows[0])

  let headerIdx = firstIsAlign ? -1 : 0       // -1 = 无表头
  let bodyStart = firstIsAlign ? 1 : 1

  // 若第一行是表头，检查第二行是否为对齐行（标准表格）
  if (!firstIsAlign && rows.length > 1 && isAlignmentRow(rows[1])) {
    bodyStart = 2
  }

  // 修复：对齐行在表头前面的非标准情况
  if (firstIsAlign && rows.length > 1 && !isAlignmentRow(rows[1])) {
    headerIdx = 1
    bodyStart = 2
  }

  // 确定列数
  const sampleRow = headerIdx >= 0 ? rows[headerIdx] : rows[bodyStart]
  const colCount = sampleRow ? splitPipeRow(sampleRow).length : 1

  let html = '<table class="msg-table-wrap">'

  // 表头
  if (colCount > 0) {
    html += '<thead><tr>'
    if (headerIdx >= 0) {
      const headers = splitPipeRow(rows[headerIdx])
      for (const h of headers) html += `<th>${escapeHtml(h) || '&nbsp;'}</th>`
    } else if (bodyStart < rows.length) {
      const headers = splitPipeRow(rows[bodyStart])
      for (const h of headers) html += `<th>${escapeHtml(h) || '&nbsp;'}</th>`
      bodyStart++
    } else {
      // 无表头行：生成占位列名
      for (let c = 0; c < colCount; c++) html += `<th>&nbsp;</th>`
    }
    html += '</tr></thead>'
  }

  // 表体
  html += '<tbody>'
  for (let i = bodyStart; i < rows.length; i++) {
    const cells = splitPipeRow(rows[i])
    // 列数归一化：确保每行与表头列数一致
    const normCells = cells.length < colCount
      ? [...cells, ...Array(colCount - cells.length).fill('')]
      : cells.slice(0, colCount)
    html += '<tr>'
    for (const c of normCells) html += `<td>${escapeHtml(c) || '&nbsp;'}</td>`
    html += '</tr>'
  }
  html += '</tbody></table>'
  return html
}

/** 拆分管道表格行。 */
function splitPipeRow(line: string): string[] {
  const t = line.trim().replace(/^\|+/, '').replace(/\|+$/, '')
  return t.split('|').map(c => c.trim())
}

/** 将连续管道行替换为 HTML <table>。 */
function applyTableBlocks(text: string): string {
  const lines = text.split('\n')
  const result: string[] = []
  let i = 0
  while (i < lines.length) {
    const t = lines[i].trim()
    if (t.startsWith('|')) {
      const rows: string[] = [t]; i++
      while (i < lines.length) { const n = lines[i].trim(); if (n.startsWith('|')) { rows.push(n); i++ } else break }
      result.push(rows.length >= 2 ? pipeTableToHtml(rows) : rows.join('\n'))
    } else { result.push(lines[i]); i++ }
  }
  return result.join('\n')
}

/**
 * 格式化表格单元格显示值。
 * - null/undefined → 短横
 * - 整数 → 带千分位分隔符
 * - 小数 → 保留两位
 * - 其他 → 转字符串
 */
function formatCellValue(val: unknown): string {
  if (val === null || val === undefined) return '—'
  if (typeof val === 'number') {
    return val % 1 === 0 ? val.toLocaleString() : val.toFixed(2)
  }
  return String(val)
}

function formatTime(ts: number): string {
  return new Date(ts).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
  })
}

/** Token 数量格式化：>=1000 显示 "1.2k"，否则原样显示 */
function formatTokens(n: number): string {
  if (n >= 1000) {
    const k = n / 1000
    return k % 1 === 0 ? `${k}k` : `${k.toFixed(1)}k`
  }
  return String(n)
}
</script>

<template>
  <!-- 用户消息 — 右侧 -->
  <div v-if="message.role === 'user'" :id="'msg-' + message.id" class="msg-row msg-row--user">
    <div class="msg-bubble msg-bubble--user">
      <div v-if="message.fileAttachments && message.fileAttachments.length > 0" class="msg-attachments">
        <div
          v-for="att in message.fileAttachments"
          :key="att.id"
          class="msg-attach-chip"
        >
          <span class="msg-attach-chip__icon">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
              <path d="M5 4a2 2 0 012-2h7l5 5v13a2 2 0 01-2 2H7a2 2 0 01-2-2V4z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>
              <path d="M14 2v4a1 1 0 001 1h4" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>
              <path d="M9.5 9l5 5M14.5 9l-5 5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
            </svg>
          </span>
          <span class="msg-attach-chip__name">{{ att.name }}</span>
        </div>
      </div>
      <div class="msg-text">{{ message.content }}</div>
    </div>
    <div class="msg-time">{{ formatTime(message.timestamp) }}</div>
  </div>

  <!-- AI 加载中 -->
  <div v-else-if="message.status === 'loading'" :id="'msg-' + message.id" class="msg-row msg-row--ai">
    <div class="msg-row__inner">
      <div class="msg-avatar">
        <LogoIcon :size="28" />
      </div>
      <div class="msg-bubble msg-bubble--loading">
        <span class="loading-dot" />
        <span class="loading-dot" />
        <span class="loading-dot" />
        <span class="loading-text">正在分析…</span>
      </div>
    </div>
  </div>

  <!-- AI 消息（流式 + 完成态统一渲染） -->
  <div v-else :id="'msg-' + message.id" class="msg-row msg-row--ai">
    <div class="msg-row__inner">
      <div class="msg-avatar">
        <LogoIcon :size="28" />
      </div>

      <!-- 无真实内容时的状态指示器（流式初期） -->
      <div v-if="message.status === 'streaming' && !hasRealContent" class="msg-bubble msg-bubble--status">
        <span class="status-spinner" />
        <span class="status-text">{{ streamingDisplay() }}</span>
      </div>

      <!-- 断线重连指示器 -->
      <div v-else-if="message.reconnecting" class="msg-bubble msg-bubble--reconnecting">
        <span class="reconnecting-spinner" />
        <span class="reconnecting-text">连接中断，正在重连…</span>
      </div>

      <!-- 真实内容气泡（流式 + 完成态共享同一段落结构） -->
      <div v-else class="msg-bubble msg-bubble--ai" :class="{ 'msg-bubble--streaming': message.status === 'streaming' }">
        <!-- 结论区（独立于推理过程，仅完成态显示） -->
        <div v-if="message.status === 'done' && conclusion" class="msg-conclusion">
          <div class="msg-conclusion__header">
            <span class="msg-conclusion__label">分析结论</span>
            <button
              class="msg-conclusion__copy"
              :class="{ 'msg-conclusion__copy--done': copySuccess }"
              @click="handleCopyConclusion"
              :title="copySuccess ? '已复制' : '复制结论'"
            >
              <svg v-if="!copySuccess" width="14" height="14" viewBox="0 0 24 24" fill="none">
                <rect x="9" y="9" width="13" height="13" rx="2" stroke="currentColor" stroke-width="1.8" />
                <path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1" stroke="currentColor" stroke-width="1.8" />
              </svg>
              <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none">
                <polyline points="20 6 9 17 4 12" stroke="currentColor" stroke-width="2.8" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>
          </div>
          <div class="msg-conclusion__text">{{ conclusion }}</div>
        </div>

        <!-- 内容段：文本段 v-html 更新，表格段 v-for 固定骨架 + 增量行 -->
        <template v-for="seg in contentSegments" :key="seg.key">
          <div v-if="seg.type === 'text'" class="msg-text" v-html="seg.html" />
          <div v-else-if="seg.type === 'table' && seg.headers" class="msg-tables">
            <div class="msg-table-wrap">
              <table>
                <thead>
                  <tr>
                    <th v-for="(h, hi) in seg.headers" :key="hi">{{ h }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(row, ri) in seg.rows" :key="ri">
                    <td v-for="(cell, ci) in row" :key="ci" v-html="renderCellContent(cell)" />
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </template>

        <!-- event:table 结构化表格（流式 + 完成态均可能） -->
        <div v-if="message.tables && message.tables.length > 0" class="msg-tables">
          <div v-for="table in message.tables" :key="table.outputKey" class="msg-table-wrap">
            <table>
              <thead>
                <tr><th v-for="h in table.headers" :key="h">{{ h }}</th></tr>
              </thead>
              <tbody>
                <tr v-for="(row, ri) in table.rows" :key="ri" class="event-row"
                    :style="{ animationDelay: `${ri * 0.05}s` }">
                  <td v-for="h in table.headers" :key="h">{{ formatCellValue(row[h]) }}</td>
                </tr>
              </tbody>
            </table>
            <div v-if="table.totalRows > table.rows.length" class="msg-table-footer">
              … 仅展示前 {{ table.rows.length }} 行，共 {{ table.totalRows }} 行
            </div>
          </div>
        </div>

        <!-- 完成态：图表引导按钮 -->
        <div
          v-if="message.status === 'done' && chartResult"
          class="chart-trigger"
          @click="showChartModal = true"
        >
          <span class="chart-trigger__icon">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
              <rect x="3" y="14" width="4" height="7" rx="1" fill="currentColor" />
              <rect x="10" y="9" width="4" height="12" rx="1" fill="currentColor" />
              <rect x="17" y="4" width="4" height="17" rx="1" fill="currentColor" />
            </svg>
          </span>
          <span class="chart-trigger__text">
            {{ chartResult.length === 1 ? '查看可视化图表' : `查看可视化图表（${chartResult.length} 张）` }}
          </span>
          <span class="chart-trigger__arrow">→</span>
        </div>

        <!-- 完成态：Token 消耗 -->
        <div v-if="message.status === 'done' && typeof message.tokenUsage === 'number'" class="msg-token-usage">
          <span class="msg-token-usage__label">Tokens</span>
          <span class="msg-token-usage__value">{{ formatTokens(message.tokenUsage) }}</span>
        </div>
      </div>
    </div>
    <div v-if="message.status === 'done'" class="msg-time">{{ formatTime(message.timestamp) }}</div>

    <!-- 图表预览弹窗（所有状态均可触发） -->
    <ChartPreviewModal
      v-if="chartResult"
      :charts="chartResult.map((opt) => ({ option: opt }))"
      :visible="showChartModal"
      @close="showChartModal = false"
    />
  </div>
</template>

<style scoped>
/* ===== 行容器 ===== */
.msg-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 20px;
}

.msg-row--user {
  align-items: flex-end;
}

.msg-row--ai {
  align-items: flex-start;
}

/* 内部布局：头像 + 气泡 */
.msg-row__inner {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

/* ===== AI 头像 / Logo ===== */
.msg-avatar {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
}

/* ===== 气泡 ===== */
.msg-bubble {
  width: fit-content;
  max-width: 85%;
  padding: 12px 16px;
  font-size: 15px;
  line-height: 1.6;
  overflow-wrap: break-word;
  word-break: break-word;
}

.msg-row--ai .msg-bubble {
  margin-left: -8px;
}

.msg-bubble--user {
  background: var(--accent);
  color: #fff;
  border-radius: 20px;
}

.msg-bubble--ai {
  background: var(--surface);
  color: var(--fg);
  border: 1px solid var(--border-soft);
  border-radius: 20px;
}

/* 流式气泡 — flex 布局让光标可同行或自然换行 */
.msg-bubble--streaming {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0;
}

/* 分段渲染：文本段自适应宽度，表格段独占整行 */
.msg-bubble--streaming > .msg-text {
  width: 100%;
  animation: stream-fade-in 0.12s ease-out;
}

@keyframes stream-fade-in {
  from { opacity: 0.88; }
  to   { opacity: 1; }
}
.msg-bubble--streaming > .msg-tables {
  width: 100%;
}

/* 状态气泡（图表生成 / 思考中） */
.msg-bubble--status {
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--surface);
  border: 1px solid var(--border-soft);
  border-radius: 20px;
  padding: 10px 18px;
}

.status-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--border-soft);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
  flex-shrink: 0;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.status-text {
  font-size: 13px;
  color: var(--muted);
}

/* 重连指示器 */
.msg-bubble--reconnecting {
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--surface);
  border: 1px dashed var(--accent);
  border-radius: 20px;
  padding: 10px 18px;
  animation: reconnect-pulse 1.5s ease-in-out infinite;
}

.reconnecting-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--border-soft);
  border-top-color: var(--accent);
  border-left-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
  flex-shrink: 0;
}

.reconnecting-text {
  font-size: 13px;
  color: var(--accent);
  font-weight: 500;
}

@keyframes reconnect-pulse {
  0%, 100% { opacity: 1; border-color: var(--accent); }
  50% { opacity: 0.7; border-color: var(--accent-light); }
}

/* ===== 加载动画 ===== */
.msg-bubble--loading {
  display: flex;
  align-items: center;
  gap: 6px;
  background: var(--surface);
  border: 1px solid var(--border-soft);
  border-radius: 20px;
  padding: 12px 20px;
}

.loading-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--muted);
  animation: dotBounce 1.4s infinite ease-in-out both;
}

.loading-dot:nth-child(1) {
  animation-delay: -0.32s;
}

.loading-dot:nth-child(2) {
  animation-delay: -0.16s;
}

.loading-dot:nth-child(3) {
  animation-delay: 0s;
}

@keyframes dotBounce {
  0%,
  80%,
  100% {
    transform: scale(0.6);
    opacity: 0.4;
  }
  40% {
    transform: scale(1);
    opacity: 1;
  }
}

.loading-text {
  font-size: 14px;
  color: var(--muted);
  margin-left: 8px;
}

/* ===== 结论区 ===== */
.msg-conclusion {
  background: var(--accent-glow-soft);
  border: 1px solid var(--accent-light);
  border-radius: 12px;
  padding: 12px 16px;
  margin-bottom: 16px;
  transition: border-color 0.2s;
}

.msg-conclusion__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.msg-conclusion__label {
  font-size: 13px;
  font-weight: 600;
  color: var(--accent);
  letter-spacing: 0.02em;
  text-transform: uppercase;
}

.msg-conclusion__copy {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  border: 1px solid var(--border-soft);
  background: var(--surface);
  color: var(--muted);
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: all 0.2s;
  flex-shrink: 0;
}

.msg-conclusion__copy:hover {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-glow-soft);
}

.msg-conclusion__copy--done {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-glow-soft);
}

.msg-conclusion__text {
  font-size: 15px;
  line-height: 1.7;
  color: var(--fg);
  font-weight: 500;
}

/* ===== 图表引导按钮 ===== */
.chart-trigger {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 18px;
  margin-top: 16px;
  margin-bottom: 12px;
  background: var(--surface-raised);
  border: 1px solid var(--border-soft);
  border-radius: 20px;
  cursor: pointer;
  transition:
    border-color 0.28s var(--ease-out-expo),
    box-shadow 0.28s var(--ease-out-expo);
  user-select: none;
}

.chart-trigger:hover {
  border-color: var(--accent);
  box-shadow: 0 2px 16px var(--accent-glow);
}

.chart-trigger__icon {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 12px;
  background: var(--accent-glow-soft);
  color: var(--accent);
  display: grid;
  place-items: center;
}

.chart-trigger__text {
  flex: 1;
  font-size: 14px;
  font-weight: 500;
  color: var(--fg);
}

.chart-trigger__arrow {
  flex-shrink: 0;
  color: var(--accent);
  font-size: 16px;
  transition: transform 0.28s var(--spring);
}

/* ===== 用户消息附件 chips ===== */
.msg-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.msg-attach-chip {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px 3px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.12);
  font-size: 12px;
  white-space: nowrap;
  max-width: 180px;
  user-select: none;
  -webkit-user-select: none;
}

.msg-attach-chip__icon {
  font-size: 12px;
  line-height: 1;
}

.msg-attach-chip__name {
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.85);
}

.chart-trigger:hover .chart-trigger__arrow {
  transform: translateX(4px);
}

/* ===== 时间 ===== */
.msg-time {
  font-size: 12px;
  color: var(--dim-text);
  padding: 0 4px;
}

/* ===== 内容渲染 ===== */
.msg-text :deep(p) {
  margin: 0 0 8px;
}

.msg-text :deep(p:last-child) {
  margin-bottom: 0;
}

.msg-text :deep(.msg-code) {
  background: var(--surface-raised);
  border: 1px solid var(--border-inner);
  border-radius: 10px;
  padding: 12px 16px;
  margin: 8px 0;
  overflow-x: auto;
  font-size: 14px;
  line-height: 1.5;
  white-space: pre-wrap;
}

.msg-text :deep(.msg-code code) {
  color: var(--fg);
  font-family: 'SF Mono', 'Fira Code', 'Cascadia Code', monospace;
}

/* ===== Markdown 元素 ===== */
.msg-text :deep(h1),
.msg-text :deep(h2),
.msg-text :deep(h3),
.msg-text :deep(h4) {
  color: var(--fg);
  font-weight: 600;
  margin: 16px 0 8px;
  letter-spacing: -0.01em;
}

.msg-text :deep(h1) {
  font-size: 18px;
  border-bottom: 1px solid var(--border-soft);
  padding-bottom: 6px;
}

.msg-text :deep(h2) {
  font-size: 17px;
  border-bottom: 1px solid var(--border-soft);
  padding-bottom: 4px;
}

.msg-text :deep(h3) {
  font-size: 16px;
}

.msg-text :deep(h4) {
  font-size: 15px;
}

.msg-text :deep(hr) {
  border: none;
  border-top: 1px solid var(--border-soft);
  margin: 16px 0;
}

.msg-text :deep(strong) {
  font-weight: 700;
  color: var(--fg);
}

.msg-text :deep(em) {
  font-style: italic;
}

.msg-text :deep(ul),
.msg-text :deep(ol) {
  margin: 6px 0;
  padding-left: 1.5em;
}

.msg-text :deep(li) {
  margin: 3px 0;
  color: var(--fg);
}

.msg-text :deep(code):not(pre code) {
  background: var(--surface-raised);
  color: var(--accent-light);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 13px;
  font-family: 'SF Mono', 'Fira Code', 'Cascadia Code', monospace;
  word-break: break-all;
}

/* ===== 表格渲染（陶土 + 白色主题） ===== */
/* 适用于 .msg-text（完成态）和 .msg-tables（流式分段） */

/* 表格外容器 */
.msg-text :deep(.msg-table-wrap),
.msg-tables .msg-table-wrap {
  overflow-x: auto;
  overflow: hidden;
  border-radius: 10px;
  border: 1px solid var(--border-soft);
  margin: 12px 0;
  background: #fff;
  animation: tableReveal 0.45s var(--ease-out-expo);
}

/* 表格自身 */
.msg-text :deep(.msg-table-wrap table),
.msg-tables .msg-table-wrap table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
  margin: 0;
}

/* 表头单元 — 陶土底色 + 白色文字 */
.msg-text :deep(.msg-table-wrap thead th),
.msg-tables .msg-table-wrap thead th {
  background: var(--accent);
  color: #fff;
  font-weight: 600;
  padding: 8px 12px;
  text-align: left;
  white-space: nowrap;
  font-size: 13px;
  letter-spacing: 0.02em;
  border: 1px solid rgba(188, 105, 74, 0.3);
}

/* 表体单元 — 网格边框清晰划分列 */
.msg-text :deep(.msg-table-wrap th),
.msg-text :deep(.msg-table-wrap td),
.msg-tables .msg-table-wrap th,
.msg-tables .msg-table-wrap td {
  padding: 8px 12px;
  text-align: left;
  vertical-align: top;
  border: 1px solid var(--border-soft);
  overflow-wrap: break-word;
  word-break: break-word;
}
.msg-text :deep(.msg-table-wrap tbody td),
.msg-tables .msg-table-wrap tbody td {
  color: var(--fg);
  font-size: 14px;
}

/* 交替行底色（暖白） */
.msg-text :deep(.msg-table-wrap tbody tr:nth-child(even)),
.msg-tables .msg-table-wrap tbody tr:nth-child(even) {
  background: var(--surface-raised);
}

/* 行悬停（陶土淡色） */
.msg-text :deep(.msg-table-wrap tbody tr:hover),
.msg-tables .msg-table-wrap tbody tr:hover {
  background: var(--accent-glow-soft);
}

/* 表格出场动画 */
@keyframes tableReveal {
  from {
    opacity: 0;
    transform: translateY(16px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* ===== event:table 行逐条滑入动画（仅在完成态稳定后触发一次） ===== */
.msg-tables .msg-table-wrap .event-row {
  opacity: 0;
  animation: rowSlideIn 0.25s var(--ease-out-expo) forwards;
}

@keyframes rowSlideIn {
  from {
    opacity: 0;
    transform: translateX(-8px);
  }
  to {
    opacity: 1;
    transform: translateX(0);
  }
}

.msg-tables .msg-table-footer {
  padding: 6px 12px;
  font-size: 13px;
  color: var(--muted);
  border-top: 1px solid var(--border-soft);
  text-align: center;
}

/* ===== Token 消耗徽章 ===== */
.msg-token-usage {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--border-inner);
  font-size: 12px;
  user-select: none;
}

.msg-token-usage__label {
  color: var(--muted);
  font-weight: 400;
  letter-spacing: 0.02em;
}

.msg-token-usage__value {
  color: var(--accent-light);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.msg-text :deep(a) {
  color: var(--accent);
  text-decoration: none;
}

.msg-text :deep(a:hover) {
  text-decoration: underline;
}

/* ===== 定位高亮闪烁 ===== */
.msg-flash-highlight {
  animation: msg-flash-pulse 1.8s var(--ease-out-expo);
}

@keyframes msg-flash-pulse {
  0%, 15% {
    background-color: var(--accent-glow-soft);
    border-color: var(--accent);
    box-shadow: 0 0 24px var(--accent-glow);
  }
  100% {
    background-color: transparent;
    border-color: var(--border-soft);
    box-shadow: none;
  }
}
</style>

<!-- 非 scoped：确保 v-html 内表格样式不受 scoped CSS 限制（与 scoped 主题一致） -->
<style>
.msg-text .msg-table-wrap {
  overflow-x: auto;
  overflow: hidden;
  border-radius: 10px;
  border: 1px solid var(--border-soft);
  margin: 12px 0;
  background: #fff;
}
.msg-text .msg-table-wrap table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
  margin: 0;
}
.msg-text .msg-table-wrap thead th {
  background: var(--accent);
  color: #fff;
  font-weight: 600;
  padding: 8px 12px;
  text-align: left;
  white-space: nowrap;
  font-size: 13px;
  letter-spacing: 0.02em;
  border: 1px solid rgba(188, 105, 74, 0.3);
}
.msg-text .msg-table-wrap th,
.msg-text .msg-table-wrap td {
  padding: 8px 12px;
  text-align: left;
  vertical-align: top;
  border: 1px solid var(--border-soft);
  overflow-wrap: break-word;
  word-break: break-word;
}
.msg-text .msg-table-wrap tbody td {
  color: var(--fg);
  font-size: 14px;
}
.msg-text .msg-table-wrap tbody tr:nth-child(even) {
  background: var(--surface-raised);
}
.msg-text .msg-table-wrap tbody tr:hover {
  background: var(--accent-glow-soft);
}

/* 流式光标 — 位于 v-html 内，故放非 scoped 块 */
.cursor-blink {
  animation: blink 0.8s step-end infinite;
  color: var(--accent);
  font-size: 15px;
}

@keyframes blink {
  50% { opacity: 0; }
}
</style>
