import { ref } from 'vue'
import { apiPostStream } from '@/services/api'
import type {
  ArtifactEvent,
  ChatMessageVO,
  MetaEvent,
  ProgressEvent,
  RenderDocument,
  RunActivity,
  StructuredEvent,
  TableEventData,
} from '@/services/types'
import { parseChartOptions } from '@/utils/messageParser'

const MAX_RETRIES = 3
const STATUS_TEXTS = new Set(['正在分析数据…', '正在生成图表…'])

export interface StartAgentStreamOptions {
  conversationId: string
  text: string
  fileIds: string[]
  message: ChatMessageVO
  onScrollToBottom: (instant?: boolean) => void
  onPersist: () => void
}

export function useAgentStream() {
  const chatting = ref(false)
  let cancelCurrent: (() => void) | null = null
  let flushCurrent: (() => void) | null = null

  function stop(): void {
    cancelCurrent?.()
  }

  function flushPending(): void {
    flushCurrent?.()
  }

  async function start(options: StartAgentStreamOptions): Promise<void> {
    const { conversationId, text, fileIds, message, onScrollToBottom, onPersist } = options
    let streamUrl = `/agent/chat/stream?message=${encodeURIComponent(text)}&conversationId=${conversationId}&renderProtocol=render-document.v1`
    if (fileIds.length > 0) streamUrl += `&fileIds=${fileIds.join(',')}`

    let finalAnalysis = ''
    let pendingContent = ''
    let rafToken: number | null = null
    let resumeToken: string | undefined
    let retryCount = 0
    let retryTimer: ReturnType<typeof setTimeout> | null = null
    let resolveRetryWait: ((shouldRetry: boolean) => void) | null = null
    let currentAbort: (() => void) | null = null
    let cancelled = false

    // v1 协议状态
    let currentRunId: string | null = null
    let lastEventId: string | null = null
    const seenEventIds = new Set<string>()
    let lastDocumentRevision = message.renderDocument?.revision ?? 0

    function flushBuffer(): void {
      rafToken = null
      if (!pendingContent) return

      const previous = STATUS_TEXTS.has(message.content) ? '' : message.content
      message.content = previous + pendingContent
      message.reconnecting = false
      pendingContent = ''
      onScrollToBottom(true)
    }

    function flushAll(): void {
      if (rafToken !== null) {
        cancelAnimationFrame(rafToken)
        rafToken = null
      }
      flushBuffer()
    }

    function waitForRetry(delay: number): Promise<boolean> {
      return new Promise((resolve) => {
        resolveRetryWait = resolve
        retryTimer = setTimeout(() => {
          retryTimer = null
          resolveRetryWait = null
          resolve(!cancelled)
        }, delay)
      })
    }

    function cancelActiveStream(): void {
      cancelled = true
      currentAbort?.()
      currentAbort = null
      if (retryTimer) {
        clearTimeout(retryTimer)
        retryTimer = null
      }
      resolveRetryWait?.(false)
      resolveRetryWait = null
    }

    function openStream(url: string) {
      return apiPostStream(
        url,
        {},
        (token) => {
          pendingContent += token
          if (!rafToken) rafToken = requestAnimationFrame(flushBuffer)
        },
        (data: StructuredEvent) => {
          const chartOptions = parseChartOptions(data.chartOption)
          if (chartOptions) {
            message.chartOption = chartOptions
          }
          if (data.analysis) finalAnalysis = data.analysis
          if (typeof data.tokenUsage === 'number') message.tokenUsage = data.tokenUsage
          if (data.resumeToken) resumeToken = data.resumeToken
        },
        (status) => {
          if (status.startsWith('resumeToken:')) {
            resumeToken = status.slice('resumeToken:'.length).trim()
            return
          }
          if (status === '正在生成图表…') {
            message.chartGenerating = true
            return
          }
          if (message.content === '' || STATUS_TEXTS.has(message.content)) message.content = status
        },
        (chartJson) => {
          const chartOptions = parseChartOptions(chartJson)
          if (chartOptions) {
            message.chartOption = chartOptions
          }
        },
        (tableData: TableEventData) => {
          message.tables = [...(message.tables || []), tableData]
          onScrollToBottom(true)
        },
        (meta: MetaEvent, eventId: string | null) => {
          currentRunId = meta.runId
          lastEventId = eventId
          resumeToken = meta.resumeToken
          message.runId = meta.runId
          message.lastEventId = eventId
        },
        (doc: RenderDocument, eventId: string | null) => {
          if (currentRunId && eventId && seenEventIds.has(`${currentRunId}:${eventId}`)) return
          if (currentRunId && eventId) seenEventIds.add(`${currentRunId}:${eventId}`)
          if (currentRunId && doc.runId !== currentRunId) return
          const revision = doc.revision ?? lastDocumentRevision + 1
          if (revision <= lastDocumentRevision) return
          lastDocumentRevision = revision
          message.renderDocument = doc
          message.renderVersion = 1
          message.lastEventId = eventId
          if (eventId) lastEventId = eventId
          onScrollToBottom(true)
        },
        (progress: ProgressEvent, eventId: string | null) => {
          if (currentRunId && eventId && seenEventIds.has(`${currentRunId}:${eventId}`)) return
          if (currentRunId && eventId) seenEventIds.add(`${currentRunId}:${eventId}`)
          message.progress = progress
          message.lastEventId = eventId
          if (eventId) lastEventId = eventId
          if (progress.stage === 'chart') {
            message.chartGenerating = progress.state === 'running'
          }
          if (progress.stage !== 'analysis' && !pendingContent && !message.renderDocument) {
            message.content = progress.label
          }
          onScrollToBottom(true)
        },
        (activity: RunActivity, eventId: string | null) => {
          if (currentRunId && eventId && seenEventIds.has(`${currentRunId}:${eventId}`)) return
          if (currentRunId && eventId) seenEventIds.add(`${currentRunId}:${eventId}`)
          const activities = message.activities ? [...message.activities] : []
          const index = activities.findIndex((item) => item.id === activity.id)
          if (index >= 0) activities[index] = activity
          else activities.push(activity)
          message.activities = activities
          message.lastEventId = eventId
          if (eventId) lastEventId = eventId
          if (activity.stage === 'chart' || activity.stage === 'validate') {
            message.chartGenerating = activity.state === 'running'
          }
          onScrollToBottom(true)
        },
        (artifact: ArtifactEvent, eventId: string | null) => {
          if (currentRunId && artifact.runId !== currentRunId) return
          if (currentRunId && eventId && seenEventIds.has(`${currentRunId}:${eventId}`)) return
          if (currentRunId && eventId) seenEventIds.add(`${currentRunId}:${eventId}`)
          const existing = message.liveBlocks ? [...message.liveBlocks] : []
          const existingIds = new Set(existing.map((block) => block.id))
          message.liveBlocks = [
            ...existing,
            ...artifact.blocks.filter((block) => !existingIds.has(block.id)),
          ]
          message.lastEventId = eventId
          if (eventId) lastEventId = eventId
          onScrollToBottom(true)
        },
      )
    }

    async function streamWithReconnect(): Promise<void> {
      let url = streamUrl
      while (!cancelled) {
        const request = openStream(url)
        currentAbort = request.abort
        try {
          const result = await request.promise
          if (result.status === 'aborted' || cancelled) return
          return
        } catch (error) {
          if (cancelled) return
          if (retryCount >= MAX_RETRIES || !resumeToken) throw error

          retryCount++
          message.reconnecting = true
          if (!(await waitForRetry(1000 * 2 ** (retryCount - 1)))) return
          if (!currentRunId || !lastEventId) throw error
          url = `/agent/chat/resume?runId=${encodeURIComponent(currentRunId)}&resumeToken=${encodeURIComponent(resumeToken)}&lastEventId=${encodeURIComponent(lastEventId)}`
        } finally {
          currentAbort = null
        }
      }
    }

    chatting.value = true
    cancelCurrent = cancelActiveStream
    flushCurrent = flushAll
    try {
      await streamWithReconnect()
      flushAll()
      if (message.status === 'streaming') {
        message.content = finalAnalysis || message.content
        message.status = 'done'
        message.timestamp = Date.now()
        message.chartGenerating = false
        message.chartPreviewAvailable = true
        message.reconnecting = false
        onPersist()
        onScrollToBottom()
      }
    } catch (error) {
      const errorText = error instanceof Error ? error.message : '分析请求失败'
      message.content = message.content || `❌ ${errorText}`
      message.status = 'error'
      message.timestamp = Date.now()
      message.chartGenerating = false
      message.chartPreviewAvailable = false
      message.reconnecting = false
      onPersist()
      onScrollToBottom()
    } finally {
      cancelActiveStream()
      chatting.value = false
      cancelCurrent = null
      flushCurrent = null
    }
  }

  return { chatting, flushPending, start, stop }
}
