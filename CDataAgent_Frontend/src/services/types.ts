/**
 * API 响应类型 — 对应后端 VO/DTO
 */

/** 数据文件（对应后端 DataFileVO） */
export interface DataFileVO {
  id: string
  originalFilename: string
  fileSize: number
  rowCount: number
  /** JSON 字符串（由后端序列化，需在前端 parse） */
  columnMeta: string
  /** 文件状态，如 "READY" / "PROCESSING" */
  status: string
  createTime: string
}

/** 结构化表格事件（对应后端 AnalysisState.TableEvent） */
export interface TableEventData {
  outputKey: string
  headers: string[]
  rows: Record<string, unknown>[]
  totalRows: number
}

/** 对话消息 */
export interface ChatMessageVO {
  id: string
  role: 'user' | 'ai'
  content: string
  /** ECharts option 对象数组（每个元素一张图表），从后端 JSON 数组解析获得 */
  chartOption?: Record<string, unknown>[]
  /** 用户消息附带的文件列表，后端持久化后回传 */
  fileAttachments?: { id: string; name: string }[]
  /** 结构化表格数据（流式过程中实时到达） */
  tables?: TableEventData[]
  timestamp: number
  status: 'sending' | 'loading' | 'streaming' | 'done' | 'error'
  /** 页面刷新导致内容截断时为 true，UI 显示不完整提示 + 重试按钮 */
  incomplete?: boolean
  /** 本轮 AI 回复消耗的 token 数（仅 assistant 消息，来自 SSE complete 或 DB） */
  tokenUsage?: number
  /** 分析结论（独立于推理过程的精简总结，仅 assistant 消息可能有值） */
  conclusion?: string
  /** SSE 断线重连中，显示重连指示器 */
  reconnecting?: boolean
  /** 后端正在生成或校验图表中，禁用统一图表入口。 */
  chartGenerating?: boolean
  /** 已由持久化消息确认，允许打开图表预览。 */
  chartPreviewAvailable?: boolean
  /** RenderDocument v1 展示文档（新协议消息，旧消息为 null） */
  renderDocument?: RenderDocument | null
  /** 渲染协议版本（当前为 1，旧消息为 null） */
  renderVersion?: number | null
  /** 当前运行的唯一标识，仅在流式 assistant 消息中存在 */
  runId?: string
  /** 已成功应用的最后一个 SSE 事件 ID，用于断线续播 */
  lastEventId?: string | null
  /** 服务端确认的过程状态，不参与最终文档持久化 */
  progress?: { stage: string; label: string; state: 'running' | 'done' | 'failed' }
}

/** 后端持久化消息（对应后端 MessageVO） */
export interface MessageVO {
  id: string
  role: 'user' | 'assistant'
  content: string
  /** 文件附件 JSON 字符串，如 [{"id":"1","name":"data.xlsx"}] */
  fileAttachments?: string
  /** 图表配置 JSON 字符串（ECharts option），仅 assistant 消息可能有值 */
  chartOption?: string | null
  createTime: string
  /** 本轮消耗的 token 数（仅 assistant 消息可能有值） */
  tokenUsage?: number
  /** 分析结论（独立于推理过程的精简总结，仅 assistant 消息可能有值） */
  conclusion?: string
  /** RenderDocument v1 JSON（新协议消息，旧消息为 null） */
  renderDocument?: string | null
  /** 渲染协议版本（当前为 1，旧消息为 null） */
  renderVersion?: number | null
}

/** SSE event:complete 结构化事件 */
export interface StructuredEvent {
  type: 'complete' | 'error'
  /** 纯分析文本（不含 chart JSON） */
  analysis?: string
  /** ECharts option JSON 字符串，无图表时为 null */
  chartOption?: string | null
  /** 错误消息 */
  message?: string
  /** 本轮 AI 回复消耗的 token 数 */
  tokenUsage?: number
  /** 用于断线重连的后端会话令牌 */
  resumeToken?: string
  /** 本次运行的唯一标识（新协议） */
  runId?: string
  /** 文档协议版本（新协议：1） */
  documentVersion?: number
}

// ─── RenderDocument v1 ────────────────────────────────

export interface RenderDocument {
  version: 1
  runId: string
  blocks: RenderBlock[]
  degraded?: boolean
}

export type RenderBlock =
  | SummaryBlock
  | ParagraphBlock
  | BulletListBlock
  | DataTableBlock
  | ChartBlock
  | NoticeBlock

export interface SummaryBlock {
  id: string
  type: 'summary'
  title?: string
  text: string
}

export interface ParagraphBlock {
  id: string
  type: 'paragraph'
  text: string
}

export interface BulletListBlock {
  id: string
  type: 'bullets'
  items: string[]
}

export interface DataTableBlock {
  id: string
  type: 'table'
  title?: string
  headers: string[]
  rows: Array<Record<string, string | number | null>>
  totalRows: number
}

export interface ChartBlock {
  id: string
  type: 'chart'
  chartIndex: number
  title?: string
}

export interface NoticeBlock {
  id: string
  type: 'notice'
  level: 'info' | 'warning'
  text: string
}

/** SSE meta 事件数据 */
export interface MetaEvent {
  runId: string
  renderProtocol: string
  resumeToken?: string
  replaySupported: boolean
}

/** 服务端进度事件。展示文本必须是服务端受控纯文本。 */
export interface ProgressEvent {
  stage: string
  label: string
  state: 'running' | 'done' | 'failed'
}

/** 文件数据预览（对应后端 FilePreviewVO） */
export interface FilePreviewVO {
  headers: string[]
  rows: unknown[][]
  totalRows: number
  page: number
  pageSize: number
  hasMore: boolean
}
