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
}
