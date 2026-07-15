/**
 * API 请求封装 — 基于 fetch，对应后端 BaseResponse<T> 格式
 *
 * 后端统一响应体：{ code: number, data: T, message: string }
 * - code === 0 表示成功，非 0 为业务错误
 * - 所有响应 HTTP 200（包括错误），错误通过 code 字段传递
 */

const configuredBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim()
const BASE_URL = (configuredBaseUrl || '/apis').replace(/\/$/, '')

import { safeParseRenderDocument } from '@/utils/renderDocument'
import type {
  MetaEvent,
  ProgressEvent,
  RenderDocument,
  StructuredEvent,
  TableEventData,
} from './types'

// ---- 错误码常量 ----

/** 后端业务错误码 */
export const ErrCode = {
  SUCCESS: 0,
  PARAMS_ERROR: 40000,
  NOT_FOUND: 40400,
  TOO_MANY_REQUEST: 42900,
  SYSTEM_ERROR: 50000,
  OPERATION_ERROR: 50001,
} as const

// ---- 响应类型 ----

export interface ApiResponse<T = unknown> {
  code: number
  data: T
  message: string
}

// ---- 错误类型 ----

export class ApiError extends Error {
  code: number

  constructor(code: number, message: string) {
    super(message)
    this.code = code
    this.name = 'ApiError'
  }

  /** 数据不存在 — 对话/文件已被删除 */
  get isNotFound(): boolean {
    return this.code === ErrCode.NOT_FOUND
  }

  /** 服务端内部错误 */
  get isServerError(): boolean {
    return this.code >= 50000
  }

  /** 参数错误 */
  get isBadRequest(): boolean {
    return this.code === ErrCode.PARAMS_ERROR
  }

  /** 是否为网络层错误（fetch 失败，无业务 code） */
  static isNetworkError(err: unknown): boolean {
    return err instanceof TypeError || (err instanceof Error && err.name === 'TypeError')
  }
}

// ---- 请求超时 ----

function createTimeout(ms: number): {
  controller: AbortController
  timer: ReturnType<typeof setTimeout>
} {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), ms)
  return { controller, timer }
}

const DEFAULT_TIMEOUT = 30_000 // 30 秒常规超时
const STREAM_TIMEOUT = 5 * 60_000 // 5 分钟流式超时（对齐后端 SseEmitter 超时）

type HttpMethod = 'GET' | 'POST' | 'DELETE'

interface RequestOptions {
  method: HttpMethod
  body?: Record<string, unknown> | FormData
  timeout?: number
  timeoutMessage?: string
}

async function request<T>(url: string, options: RequestOptions): Promise<ApiResponse<T>> {
  const {
    method,
    body,
    timeout = DEFAULT_TIMEOUT,
    timeoutMessage = '请求超时，请检查网络后重试',
  } = options
  const { controller, timer } = createTimeout(timeout)
  const isFormData = body instanceof FormData

  try {
    const response = await fetch(`${BASE_URL}${url}`, {
      method,
      headers: body && !isFormData ? { 'Content-Type': 'application/json' } : undefined,
      credentials: 'include',
      body: body ? (isFormData ? body : JSON.stringify(body)) : undefined,
      signal: controller.signal,
    })

    if (!response.ok) {
      throw new ApiError(response.status, `HTTP ${response.status}`)
    }

    return (await response.json()) as ApiResponse<T>
  } catch (err: unknown) {
    if (err instanceof DOMException && err.name === 'AbortError') {
      throw new ApiError(50000, timeoutMessage)
    }
    throw err
  } finally {
    clearTimeout(timer)
  }
}

// ---- 重试 ----

export interface RetryOptions {
  /** 最大重试次数，默认 2（总共 3 次尝试） */
  maxRetries?: number
  /** 基础延迟 ms，默认 800 */
  baseDelay?: number
  /** 判断是否应重试，默认网络错误和服务端错误可重试 */
  shouldRetry?: (err: unknown) => boolean
}

export type StreamResult = { status: 'completed' } | { status: 'aborted' }

/**
 * 对异步操作进行自动重试。
 * 仅网络错误和 5xxxx 服务端错误可重试；业务错误（4xxxx）不重试。
 */
export async function withRetry<T>(fn: () => Promise<T>, options: RetryOptions = {}): Promise<T> {
  const { maxRetries = 2, baseDelay = 800 } = options
  const shouldRetry =
    options.shouldRetry ??
    ((err: unknown): boolean => {
      if (ApiError.isNetworkError(err)) return true
      if (err instanceof ApiError && err.isServerError) return true
      return false
    })

  let lastErr: unknown

  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    try {
      return await fn()
    } catch (err: unknown) {
      lastErr = err
      if (attempt >= maxRetries || !shouldRetry(err)) throw err
      // 指数退避
      const delay = baseDelay * 2 ** attempt
      await new Promise((resolve) => setTimeout(resolve, delay))
    }
  }

  throw lastErr
}

// ---- GET ----

/**
 * 发送 GET 请求
 */
export async function apiGet<T = unknown>(url: string): Promise<ApiResponse<T>> {
  return request<T>(url, { method: 'GET' })
}

/**
 * 发送 GET 请求并自动处理业务错误
 */
export async function apiGetChecked<T = unknown>(url: string): Promise<T> {
  const res = await apiGet<T>(url)
  if (res.code !== 0) {
    throw new ApiError(res.code, res.message || '操作失败')
  }
  return res.data
}

// ---- POST ----

/**
 * 发送 POST 请求
 */
export async function apiPost<T = unknown>(
  url: string,
  body: Record<string, unknown>,
): Promise<ApiResponse<T>> {
  return request<T>(url, { method: 'POST', body })
}

/**
 * 发送 POST 请求并自动处理业务错误
 */
export async function apiPostChecked<T = unknown>(
  url: string,
  body: Record<string, unknown>,
): Promise<T> {
  const res = await apiPost<T>(url, body)
  if (res.code !== 0) {
    throw new ApiError(res.code, res.message || '操作失败')
  }
  return res.data
}

// ---- 文件上传 ----

/**
 * 上传文件，返回完整 ApiResponse
 */
export async function apiUpload<T = unknown>(
  url: string,
  formData: FormData,
): Promise<ApiResponse<T>> {
  return request<T>(url, {
    method: 'POST',
    body: formData,
    timeout: 60_000,
    timeoutMessage: '上传超时，请检查网络后重试',
  })
}

// ---- DELETE ----

/**
 * 发送 DELETE 请求
 */
export async function apiDelete<T = unknown>(url: string): Promise<ApiResponse<T>> {
  return request<T>(url, { method: 'DELETE' })
}

/**
 * 发送 DELETE 请求并自动处理业务错误
 */
export async function apiDeleteChecked<T = unknown>(url: string): Promise<T> {
  const res = await apiDelete<T>(url)
  if (res.code !== 0) {
    throw new ApiError(res.code, res.message || '操作失败')
  }
  return res.data
}

// ---- 流式 POST (SSE) ----

/**
 * 流式 POST — 使用 fetch + ReadableStream 消费 SSE（text/event-stream）。
 *
 * SSE 协议要点：
 * - 每个事件由 `\n\n`（双换行）分隔
 * - 一个事件内可有多条 `data:` 行（当内容含换行时）
 * - `event:` 字段标识事件类型
 *
 * @param onToken 每个 data 字段的回调（多行 data 用 \n 连接后传入）
 * @param onStructured 结构化完成事件回调（event:complete）
 * @param onStatus 状态事件回调（event:status），如 "正在分析数据…" "正在生成图表…"
 * @param onChart 图表配置事件回调（event:chart），data 为 JSON 数组字符串
 * @returns { promise, abort } — promise 对请求错误 reject，用户主动 abort 时返回 aborted
 */
export function apiPostStream(
  url: string,
  body: Record<string, unknown>,
  onToken: (token: string) => void,
  onStructured?: (data: StructuredEvent) => void,
  onStatus?: (text: string) => void,
  onChart?: (chartJson: string) => void,
  onTable?: (tableData: TableEventData) => void,
  onMeta?: (meta: MetaEvent, eventId: string | null) => void,
  onDocument?: (doc: RenderDocument, eventId: string | null) => void,
  onProgress?: (progress: ProgressEvent, eventId: string | null) => void,
): { promise: Promise<StreamResult>; abort: () => void } {
  const { controller: abortController, timer } = createTimeout(STREAM_TIMEOUT)
  let userAborted = false

  const promise = (async (): Promise<StreamResult> => {
    try {
      const response = await fetch(`${BASE_URL}${url}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body),
        signal: abortController.signal,
      })

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`)
      }

      // 检测 JSON 错误响应（后端以 JSON 返回业务错误）
      const ct = (response.headers.get('content-type') || '').toLowerCase()
      if (ct.includes('application/json')) {
        const text = await response.text()
        try {
          const json = JSON.parse(text)
          throw new ApiError(json.code ?? 50000, json.message || '请求失败')
        } catch (e) {
          if (e instanceof ApiError) throw e
          throw new Error(text.slice(0, 200))
        }
      }

      if (!response.body) {
        throw new Error('流式响应缺少响应体')
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { value, done } = await reader.read()

        if (done) {
          buffer += decoder.decode()
          // 处理缓冲区中可能残留的最后一个事件
          processBuffer(
            buffer,
            onToken,
            onStructured,
            onStatus,
            onChart,
            onTable,
            onMeta,
            onDocument,
            onProgress,
          )
          return { status: 'completed' }
        }

        buffer += decoder.decode(value, { stream: true })

        // 按 \n\n 分隔事件（SSE 协议的事件边界）
        while (true) {
          const separator = /\r?\n\r?\n/.exec(buffer)
          if (!separator || separator.index === undefined) break

          const rawEvent = buffer.slice(0, separator.index)
          buffer = buffer.slice(separator.index + separator[0].length)

          if (rawEvent.length === 0) continue

          processSSEEvent(
            rawEvent,
            onToken,
            onStructured,
            onStatus,
            onChart,
            onTable,
            onMeta,
            onDocument,
            onProgress,
          )
        }
      }
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === 'AbortError') {
        if (userAborted) {
          return { status: 'aborted' }
        }
        throw new ApiError(50000, 'AI 响应超时，请重试')
      }
      throw err instanceof Error ? err : new Error(String(err))
    } finally {
      clearTimeout(timer)
    }
  })()

  return {
    promise,
    abort: () => {
      if (!abortController.signal.aborted) {
        userAborted = true
        clearTimeout(timer)
        try {
          abortController.abort()
        } catch {
          /* ignore */
        }
      }
    },
  }
}

/**
 * 处理残留缓冲区内容（不完整的最后一个事件）
 */
function processBuffer(
  buffer: string,
  onToken: (token: string) => void,
  onStructured?: (data: StructuredEvent) => void,
  onStatus?: (text: string) => void,
  onChart?: (chartJson: string) => void,
  onTable?: (tableData: TableEventData) => void,
  onMeta?: (meta: MetaEvent, eventId: string | null) => void,
  onDocument?: (doc: RenderDocument, eventId: string | null) => void,
  onProgress?: (progress: ProgressEvent, eventId: string | null) => void,
): void {
  const trimmed = buffer.trim()
  if (trimmed.length === 0) return
  processSSEEvent(
    trimmed,
    onToken,
    onStructured,
    onStatus,
    onChart,
    onTable,
    onMeta,
    onDocument,
    onProgress,
  )
}

/**
 * 解析单个 SSE 事件（`\n\n` 之间的内容）
 */
function processSSEEvent(
  rawEvent: string,
  onToken: (token: string) => void,
  onStructured?: (data: StructuredEvent) => void,
  onStatus?: (text: string) => void,
  onChart?: (chartJson: string) => void,
  onTable?: (tableData: TableEventData) => void,
  onMeta?: (meta: MetaEvent, eventId: string | null) => void,
  onDocument?: (doc: RenderDocument, eventId: string | null) => void,
  onProgress?: (progress: ProgressEvent, eventId: string | null) => void,
): void {
  const dataLines: string[] = []
  let eventType = ''
  let eventId: string | null = null

  for (const line of rawEvent.split(/\r?\n/)) {
    if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''))
    } else if (line.startsWith('event:')) {
      eventType = line.slice(6).trim()
    } else if (line.startsWith('id:')) {
      eventId = line.slice(3).trim()
    }
  }

  if (dataLines.length === 0) {
    if (eventType === 'error') {
      throw new Error('后端流式处理异常')
    }
    return
  }

  const text = dataLines.join('\n')

  // ── 新事件：meta ──
  if (eventType === 'meta') {
    try {
      const meta = JSON.parse(text) as MetaEvent
      onMeta?.(meta, eventId)
    } catch {
      /* ignore parse errors */
    }
    return
  }

  // ── 新事件：document ──
  if (eventType === 'document') {
    try {
      const doc = JSON.parse(text)
      const parsed = safeParseRenderDocument(doc)
      if (parsed) onDocument?.(parsed, eventId)
    } catch {
      /* ignore parse errors */
    }
    return
  }

  if (eventType === 'progress') {
    try {
      const progress = JSON.parse(text) as ProgressEvent
      if (progress.stage && progress.label && progress.state) onProgress?.(progress, eventId)
    } catch {
      /* ignore parse errors */
    }
    return
  }

  if (eventType === 'preview') {
    onToken(text)
    return
  }

  if (eventType === 'status') {
    if (onStatus) onStatus(text)
    return
  }

  // event:ping — 心跳保活，静默消费
  if (eventType === 'ping') {
    return
  }

  // event:chart — 图表配置事件（来自 Synthesizer 阶段）
  if (eventType === 'chart') {
    if (onChart) onChart(text)
    return
  }

  // event:table — 结构化表格事件（来自 DuckDbQueryTool）
  if (eventType === 'table') {
    if (onTable) {
      try {
        const tableData = JSON.parse(text) as TableEventData
        onTable(tableData)
      } catch {
        /* 解析失败静默忽略 */
      }
    }
    return
  }

  // event:complete — 流完成结构化事件
  if (eventType === 'complete') {
    try {
      const parsed = JSON.parse(text) as StructuredEvent
      if (parsed.type === 'error') {
        throw new Error(parsed.message || '服务处理出错')
      } else if (onStructured) {
        onStructured(parsed)
      }
    } catch (error) {
      throw error instanceof Error ? error : new Error('无效的完成事件')
    }
    return
  }

  if (eventType === 'error') {
    throw new Error(text || '流式响应出错')
  }

  // event:message / 无 event 字段 → 文本 token
  if ((eventType === 'message' || eventType === '') && text) {
    onToken(text)
  }
}
