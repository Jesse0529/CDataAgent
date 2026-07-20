import { ref } from 'vue'
import { apiPostStream } from '@/services/api'
import type {
  ArtifactEvent,
  ChartResultEvent,
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

function activitySourceIds(activity: RunActivity): string[] {
  return activity.sourceIds?.length ? activity.sourceIds : [activity.id]
}

function activityToolKey(activity: RunActivity): string {
  // 旧服务端事件缺少 toolKey 时不做跨事件合并，避免误把不同工具归为一行。
  return activity.toolKey || `legacy:${activity.id}`
}

function mergeActivity(activities: RunActivity[], incoming: RunActivity): RunActivity[] {
  const next = activities.map((activity) => ({
    ...activity,
    sourceIds: [...activitySourceIds(activity)],
  }))
  const sourceIndex = next.findIndex((activity) =>
    activitySourceIds(activity).includes(incoming.id),
  )

  if (sourceIndex >= 0) {
    const source = next[sourceIndex]
    const sourceIds = activitySourceIds(source).filter((id) => id !== incoming.id)
    if (sourceIds.length === 0) next.splice(sourceIndex, 1)
    else {
      next[sourceIndex] = { ...source, count: sourceIds.length, sourceIds }
    }
  }

  const toolKey = activityToolKey(incoming)
  const targetIndex = next.findIndex(
    (activity) => activityToolKey(activity) === toolKey && activity.state === incoming.state,
  )
  if (targetIndex < 0) {
    return [...next, { ...incoming, toolKey, count: 1, sourceIds: [incoming.id] }]
  }

  const target = next[targetIndex]
  const sourceIds = [...new Set([...activitySourceIds(target), incoming.id])]
  next[targetIndex] = {
    ...target,
    label: incoming.label,
    count: sourceIds.length,
    sourceIds,
  }
  return next
}

export interface StartAgentStreamOptions {
  conversationId: string
  text: string
  fileIds: string[]
  message: ChatMessageVO
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
    const { conversationId, text, fileIds, message, onPersist } = options
    let streamUrl = `/agent/chat/stream?message=${encodeURIComponent(text)}&conversationId=${conversationId}&renderProtocol=render-document.v1`
    if (fileIds.length > 0) streamUrl += `&fileIds=${fileIds.join(',')}`

    let finalAnalysis = ''
    let pendingContent = ''
    let rafToken: number | null = null
    let activityFlushTimer: ReturnType<typeof setTimeout> | null = null
    let pendingActivities: RunActivity[] = []
    let artifactFlushTimer: ReturnType<typeof setTimeout> | null = null
    const pendingArtifactBlocks: RenderDocument['blocks'] = []
    const queuedArtifactIds = new Set((message.liveBlocks ?? []).map((block) => block.id))
    let resolveArtifactDrain: (() => void) | null = null
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
    }

    function flushActivities(): void {
      activityFlushTimer = null
      if (pendingActivities.length === 0) return

      let activities = message.activities ?? []
      let chartGenerating = message.chartGenerating ?? false
      for (const activity of pendingActivities) {
        activities = mergeActivity(activities, activity)
        if (activity.stage === 'chart' || activity.stage === 'validate') {
          chartGenerating = activity.state === 'running'
        }
      }
      pendingActivities = []
      message.activities = activities
      message.chartGenerating = chartGenerating
    }

    function queueActivity(activity: RunActivity): void {
      pendingActivities.push(activity)
      if (activityFlushTimer === null) {
        activityFlushTimer = setTimeout(flushActivities, 32)
      }
    }

    function flushArtifactBlock(): void {
      artifactFlushTimer = null
      const block = pendingArtifactBlocks.shift()
      if (!block) {
        resolveArtifactDrain?.()
        resolveArtifactDrain = null
        return
      }

      message.liveBlocks = [...(message.liveBlocks ?? []), block]
      if (pendingArtifactBlocks.length > 0) {
        artifactFlushTimer = setTimeout(flushArtifactBlock, 96)
      } else {
        resolveArtifactDrain?.()
        resolveArtifactDrain = null
      }
    }

    function queueArtifactBlocks(blocks: RenderDocument['blocks']): void {
      for (const block of blocks) {
        if (queuedArtifactIds.has(block.id)) continue
        queuedArtifactIds.add(block.id)
        pendingArtifactBlocks.push(block)
      }
      if (pendingArtifactBlocks.length > 0 && artifactFlushTimer === null) {
        flushArtifactBlock()
      }
    }

    function waitForArtifactDrain(): Promise<void> {
      if (pendingArtifactBlocks.length === 0 && artifactFlushTimer === null)
        return Promise.resolve()
      return new Promise((resolve) => {
        resolveArtifactDrain = resolve
      })
    }

    function flushAll(): void {
      if (rafToken !== null) {
        cancelAnimationFrame(rafToken)
        rafToken = null
      }
      flushBuffer()
      if (activityFlushTimer !== null) {
        clearTimeout(activityFlushTimer)
        activityFlushTimer = null
      }
      flushActivities()
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
        },
        (activity: RunActivity, eventId: string | null) => {
          if (currentRunId && eventId && seenEventIds.has(`${currentRunId}:${eventId}`)) return
          if (currentRunId && eventId) seenEventIds.add(`${currentRunId}:${eventId}`)
          message.lastEventId = eventId
          if (eventId) lastEventId = eventId
          queueActivity(activity)
        },
        (artifact: ArtifactEvent, eventId: string | null) => {
          if (currentRunId && artifact.runId !== currentRunId) return
          if (currentRunId && eventId && seenEventIds.has(`${currentRunId}:${eventId}`)) return
          if (currentRunId && eventId) seenEventIds.add(`${currentRunId}:${eventId}`)
          if (artifact.chartExpected === true) message.chartExpected = true
          queueArtifactBlocks(artifact.blocks)
          message.lastEventId = eventId
          if (eventId) lastEventId = eventId
        },
        (result: ChartResultEvent, eventId: string | null) => {
          if (currentRunId && result.runId !== currentRunId) return
          if (currentRunId && eventId && seenEventIds.has(`${currentRunId}:${eventId}`)) return
          if (currentRunId && eventId) seenEventIds.add(`${currentRunId}:${eventId}`)
          message.chartExpected = result.plannedChartCount > 0
          message.chartResultState = result.state
          message.chartGenerating = false
          message.chartPreviewAvailable = result.state === 'ready'
          const chartOptions = parseChartOptions(result.chartOption)
          if (chartOptions) message.chartOption = chartOptions
          message.lastEventId = eventId
          if (eventId) lastEventId = eventId
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
      await waitForArtifactDrain()
      if (message.status === 'streaming') {
        const hasStreamedContent =
          message.content.trim().length > 0 && !STATUS_TEXTS.has(message.content)
        if (!hasStreamedContent && finalAnalysis) message.content = finalAnalysis
        message.status = 'done'
        message.timestamp = Date.now()
        message.chartGenerating = false
        message.chartPreviewAvailable = message.chartResultState
          ? message.chartResultState === 'ready' && Boolean(message.chartOption?.length)
          : true
        message.reconnecting = false
        onPersist()
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
    } finally {
      cancelActiveStream()
      chatting.value = false
      cancelCurrent = null
      flushCurrent = null
    }
  }

  return { chatting, flushPending, start, stop }
}
