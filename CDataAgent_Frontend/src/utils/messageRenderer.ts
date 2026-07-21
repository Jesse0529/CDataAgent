import DOMPurify from 'dompurify'
import { marked, type Token, type Tokens } from 'marked'
import { extractChartOption } from './chartParser'

export interface ContentSegment {
  type: 'text' | 'table'
  key: number
  html?: string
  headers?: string[]
  rows?: string[][]
}

interface RenderOptions {
  streaming?: boolean
}

const MARKDOWN_OPTIONS = { async: false, breaks: true, gfm: true } as const
const SANITIZE_OPTIONS = {
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
}

const DIAGRAM_FENCE = /```\s*(?:mermaid|plantuml|puml|dot|graphviz)\b/gi
const DIAGRAM_DIRECTIVE =
  /^\s*(?:(?:graph|flowchart)\s+(?:TD|TB|BT|RL|LR)|(?:classDiagram|sequenceDiagram|stateDiagram|erDiagram|mindmap|journey|gantt)\b).*?(?=\n\s*\n|$)/gims
const DIAGRAM_PLACEHOLDER = '（关系图谱和流程图暂不支持直接展示，请使用表格或要点描述。）'
const DECORATIVE_TABLE_PREFIX =
  /^[\t ]*(?:\p{Extended_Pictographic}[\uFE0F\u200D]*)+[\t ]*(?=\|)/gmu

export function getDisplayText(text: string): string {
  if (!text) return ''
  const result = extractChartOption(text)
  if (result) return result.analysis
  return text.trim().startsWith('{') ? '' : text
}

/**
 * 将模型文本收敛为受支持的 GFM 子集。这里不猜测语义：无法确认的表格退化为普通文本。
 */
function normalizeMarkdown(text: string, { streaming = false }: RenderOptions = {}): string {
  let source = text.replace(/\r\n?/g, '\n')
  source = removeUnsupportedDiagrams(source)
    .replace(/#+\s*NEEDS_CHART#*/gi, '')
    .replace(/##CONCLUSION##\s*/gi, '')
    .replace(/\s*##END##/gi, '')
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(DECORATIVE_TABLE_PREFIX, '')
    .replace(/\*\*([^\n*]+?)\s+\*\*/g, '**$1** ')
    .replace(/__([^\n_]+?)\s+__/g, '__$1__ ')
    .replace(/^(#{1,6})(?!\s|$)/gm, '$1 ')

  source = flattenIncompleteTables(source)
  return hideUnclosedDelimiters(source, streaming)
}

function removeUnsupportedDiagrams(source: string): string {
  let result = ''
  let cursor = 0
  DIAGRAM_FENCE.lastIndex = 0
  let match = DIAGRAM_FENCE.exec(source)
  while (match) {
    result += source.slice(cursor, match.index)
    const bodyStart = DIAGRAM_FENCE.lastIndex
    const closing = source.indexOf('```', bodyStart)
    result += DIAGRAM_PLACEHOLDER
    if (closing < 0) return result
    cursor = closing + 3
    DIAGRAM_FENCE.lastIndex = cursor
    match = DIAGRAM_FENCE.exec(source)
  }
  return (result + source.slice(cursor)).replace(DIAGRAM_DIRECTIVE, DIAGRAM_PLACEHOLDER)
}

function isPipeLine(line: string): boolean {
  return line.trim().startsWith('|')
}

function isAlignmentLine(line: string): boolean {
  const trimmed = line.trim().replace(/^\|/, '').replace(/\|$/, '')
  const cells = trimmed.split('|').map((cell) => cell.trim())
  return cells.length > 1 && cells.every((cell) => /^:?-{3,}:?$/.test(cell))
}

function flattenIncompleteTables(source: string): string {
  const lines = source.split('\n')
  const result: string[] = []

  for (let index = 0; index < lines.length; index++) {
    const line = lines[index]
    if (!isPipeLine(line)) {
      result.push(line)
      continue
    }

    if (isAlignmentLine(lines[index + 1] ?? '')) {
      result.push(line, lines[++index])
      while (isPipeLine(lines[index + 1] ?? '')) result.push(lines[++index])
      continue
    }

    const cells = line
      .trim()
      .replace(/^\|+|\|+$/g, '')
      .split('|')
    result.push(
      cells
        .map((cell) => cell.trim())
        .filter(Boolean)
        .join(' · '),
    )
  }

  return result.join('\n')
}

function hideUnclosedDelimiters(source: string, streaming: boolean): string {
  const markers = ['```', '**', '__', '`']
  let result = source
  for (const marker of markers) {
    const count = result.split(marker).length - 1
    if (count % 2 !== 0) {
      const position = result.lastIndexOf(marker)
      result = result.slice(0, position) + result.slice(position + marker.length)
    }
  }

  return streaming ? result.replace(/\n{3,}$/g, '\n\n') : result
}

function sanitizeHtml(html: string): string {
  return DOMPurify.sanitize(html, SANITIZE_OPTIONS)
}

function renderMarkdown(text: string, options?: RenderOptions): string {
  const source = normalizeMarkdown(text, options)
  return sanitizeHtml(marked.parse(source, MARKDOWN_OPTIONS))
}

function renderInlineMarkdown(text: string): string {
  return sanitizeHtml(marked.parseInline(normalizeMarkdown(text), MARKDOWN_OPTIONS))
}

function isTableToken(token: Token): token is Tokens.Table {
  return token.type === 'table'
}

/** 使用 marked 的 GFM token，而不是手写表格语法解析。 */
export function parseContentSegments(text: string, options?: RenderOptions): ContentSegment[] {
  if (!text) return []

  const tokens = marked.lexer(normalizeMarkdown(text, options), MARKDOWN_OPTIONS)
  const segments: ContentSegment[] = []
  let key = 0
  let markdown = ''

  const flushMarkdown = () => {
    if (!markdown.trim()) {
      markdown = ''
      return
    }
    segments.push({
      type: 'text',
      key: key++,
      html: sanitizeHtml(marked.parse(markdown, MARKDOWN_OPTIONS)),
    })
    markdown = ''
  }

  for (const token of tokens) {
    if (!isTableToken(token)) {
      markdown += 'raw' in token ? token.raw : ''
      continue
    }

    flushMarkdown()
    segments.push({
      type: 'table',
      key: key++,
      headers: token.header.map((cell) => cell.text),
      rows: token.rows.map((row) => row.map((cell) => cell.text)),
    })
  }
  flushMarkdown()
  return segments
}

export function renderCellContent(text: string): string {
  return text ? renderInlineMarkdown(text) : ''
}

export function streamingMarkdown(text: string): string {
  return text ? renderMarkdown(text, { streaming: true }) : ''
}
