<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useMessage, useDialog } from 'naive-ui'
import {
  apiGetChecked,
  apiPostChecked,
  apiPostStream,
  apiUpload,
  apiDeleteChecked,
  ApiError,
  withRetry,
} from '@/services/api'
import type {
  ChatMessageVO,
  DataFileVO,
  MessageVO,
  StructuredEvent,
  TableEventData,
} from '@/services/types'
import WelcomeScreen from '@/components/analysis/WelcomeScreen.vue'
import ChatMessage from '@/components/analysis/ChatMessage.vue'
import ChatInput from '@/components/analysis/ChatInput.vue'
import ModelConfigPanel from '@/components/analysis/ModelConfigPanel.vue'
import FilePreviewModal from '@/components/analysis/FilePreviewModal.vue'
import { useChatPersistence } from '@/composables/useChatPersistence'

const message = useMessage()
const dialog = useDialog()

// ---- 常量 ----
const MAX_FILES = 8

// ---- 对话状态 ----
const activeConversationId = ref<string | null>(null)
const messages = ref<ChatMessageVO[]>([])
const chatting = ref(false)
const stopStream = ref<(() => void) | null>(null)
const configCollapsed = ref(false)

// ---- 文件状态 ----
const files = ref<DataFileVO[]>([])
const fetchingFiles = ref(true)
const uploading = ref(false)
/** 用户选中的文件 ID 集合，传给后端分析用 */
const selectedFileIds = ref<Set<string>>(loadSelectedFiles())

function toggleFile(fileId: string) {
  const s = new Set(selectedFileIds.value)
  s.has(fileId) ? s.delete(fileId) : s.add(fileId)
  selectedFileIds.value = s
  saveSelectedFiles()
}

// ---- 文件选择持久化 ----
const SELECTED_FILES_KEY = 'aibi:selectedFiles'

function saveSelectedFiles(): void {
  try {
    const ids = [...selectedFileIds.value]
    localStorage.setItem(SELECTED_FILES_KEY, JSON.stringify(ids))
  } catch { /* 静默 */ }
}

function loadSelectedFiles(): Set<string> {
  try {
    const raw = localStorage.getItem(SELECTED_FILES_KEY)
    if (!raw) return new Set()
    const ids = JSON.parse(raw)
    return Array.isArray(ids) ? new Set(ids) : new Set()
  } catch {
    return new Set()
  }
}

// ---- 文件预览 ----
const previewFileId = ref<string | null>(null)
const previewFileName = ref('')
const showPreview = ref(false)

function handlePreviewFile(file: DataFileVO) {
  previewFileId.value = file.id
  previewFileName.value = file.originalFilename
  showPreview.value = true
}

const clearing = ref(false)
const resetting = ref(false)

async function handleClearMessages() {
  dialog.warning({
    title: '清空聊天记录',
    content: '仅删除聊天记录但不可恢复，是否继续？',
    positiveText: '确认清空',
    negativeText: '取消',
    onPositiveClick: async () => {
      const cid = activeConversationId.value
      if (!cid) return
      clearing.value = true
      try {
        await apiDeleteChecked(`/agent/conversations/${cid}/messages`)
        messages.value = []
        clearMessages()
        message.success('聊天记录已清空')
      } catch (err: unknown) {
        const msg = err instanceof ApiError ? err.message : '清空失败'
        message.error(msg)
      } finally {
        clearing.value = false
      }
    },
  })
}

async function handleResetConversation() {
  dialog.warning({
    title: '重置对话',
    content: '该选项会清空聊天记录并重置 Agent 的记忆，是否继续？',
    positiveText: '确认重置',
    negativeText: '取消',
    onPositiveClick: async () => {
      const cid = activeConversationId.value
      if (!cid) return
      resetting.value = true
      try {
        await apiPostChecked(`/agent/conversations/${cid}/reset`, {})
        messages.value = []
        clearMessages()
        message.success('对话已重置')
      } catch (err: unknown) {
        const msg = err instanceof ApiError ? err.message : '重置失败'
        message.error(msg)
      } finally {
        resetting.value = false
      }
    },
  })
}

// ---- 计算 ----
const hasMessages = computed(() => messages.value.length > 0)

// ---- 滚动 ----
const chatAreaRef = ref<HTMLDivElement | null>(null)
const scrollAnchorRef = ref<HTMLDivElement | null>(null)

function scrollToBottom(instant = false) {
  nextTick(() => {
    scrollAnchorRef.value?.scrollIntoView({ behavior: instant ? 'auto' : 'smooth' })
  })
}

/** 将后端返回的 fileAttachments JSON 字符串解析为对象数组 */
function parseFileAttachments(raw?: string): { id: string; name: string }[] | undefined {
  if (!raw) return undefined
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) && parsed.length > 0 ? parsed : undefined
  } catch {
    return undefined
  }
}

/** 安全 JSON 解析，失败返回 null */
function tryParseJson(raw: string): unknown | null {
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

/** 将后端返回的 chartOption JSON 数组字符串解析为对象数组 */
function parseChartOptions(raw?: string | null): Record<string, unknown>[] | undefined {
  if (!raw) return undefined
  const parsed = tryParseJson(raw)
  if (Array.isArray(parsed) && parsed.length > 0) {
    return parsed as Record<string, unknown>[]
  }
  return undefined
}

// ---- 文件管理 ----

async function fetchFiles() {
  if (!activeConversationId.value) {
    files.value = []
    fetchingFiles.value = false
    return
  }

  fetchingFiles.value = true
  try {
    const newList = await withRetry(() =>
      apiGetChecked<DataFileVO[]>(`/file/list?conversationId=${activeConversationId.value}`),
    )
    files.value = newList || []
    // 不再自动选中文件，由用户手动选择
  } catch (err: unknown) {
    console.warn('[AnalysisPage] fetchFiles failed', err)
  } finally {
    fetchingFiles.value = false
  }
}

async function handleUpload(incoming: File[]) {
  if (!activeConversationId.value) {
    message.error('请等待对话初始化完成')
    return
  }

  if (files.value.length + incoming.length > MAX_FILES) {
    message.warning(`最多同时上传 ${MAX_FILES} 个文件，当前已 ${files.value.length} 个`)
    return
  }

  uploading.value = true
  try {
    const formData = new FormData()
    for (const file of incoming) {
      formData.append('files', file)
    }
    const res = await apiUpload<DataFileVO[]>(
      `/file/upload?conversationId=${activeConversationId.value}&replaceIfExists=false`,
      formData,
    )

    if (res.code !== 0) {
      message.error(res.message || '上传失败')
      return
    }

    const uploaded = res.data || []
    // 按文件名去重：已存在的文件不再追加
    const existingNames = new Set(files.value.map(f => f.originalFilename))
    const deduped = uploaded.filter(f => !existingNames.has(f.originalFilename))
    files.value = [...files.value, ...deduped]

    // 新文件自动勾选，且已有勾选不清除
    const s = new Set(selectedFileIds.value)
    for (const file of deduped) s.add(file.id)
    selectedFileIds.value = s
    saveSelectedFiles()

    const addedCount = deduped.length
    const skippedCount = uploaded.length - deduped.length
    if (addedCount > 0) {
      message.success(skippedCount > 0
        ? `上传了 ${addedCount} 个文件（${skippedCount} 个重复已跳过）`
        : `${addedCount} 个文件已上传`)
    } else if (skippedCount > 0) {
      message.warning('文件已存在，请勿重复上传')
    }
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : '上传失败'
    message.error(msg)
  } finally {
    uploading.value = false
  }
}

async function handleDeleteFile(fileId: string) {
  const file = files.value.find((f) => f.id === fileId)
  dialog.warning({
    title: '删除文件',
    content: `确定要删除「${file?.originalFilename || '文件'}」吗？`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await withRetry(() => apiDeleteChecked<boolean>(`/file/${fileId}`))
        files.value = files.value.filter((f) => f.id !== fileId)
        const s = new Set(selectedFileIds.value)
        s.delete(fileId)
        selectedFileIds.value = s
        saveSelectedFiles()
        message.success('文件已删除')
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : '删除失败'
        message.error(msg)
      }
    },
  })
}

// ---- 加载消息 ----

async function loadMessages() {
  if (!activeConversationId.value) {
    messages.value = []
    return
  }

  try {
    const msgList = await withRetry(() =>
      apiGetChecked<MessageVO[]>(`/agent/conversations/${activeConversationId.value}/messages`),
    )
    messages.value = (msgList || []).map((m) => ({
      id: `db-${m.id}`,
      role: m.role === 'assistant' ? 'ai' : 'user',
      content: m.content,
      timestamp: new Date(m.createTime).getTime(),
      status: 'done' as const,
      chartOption: parseChartOptions(m.chartOption),
      fileAttachments: parseFileAttachments(m.fileAttachments),
      tokenUsage: m.tokenUsage ?? undefined,
    }))
    scrollToBottom(true)
  } catch (err: unknown) {
    console.warn('[AnalysisPage] loadMessages failed', err)
  }
}

// ---- 发送消息 ----

function handleStop() {
  if (stopStream.value) {
    stopStream.value()
    stopStream.value = null
  }
  chatting.value = false
}

async function handleSend(text: string) {
  // 发送前刷新文件列表
  await fetchFiles()

  const userMsg: ChatMessageVO = {
    id: `user-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
    role: 'user',
    content: text,
    timestamp: Date.now(),
    status: 'done',
    fileAttachments: files.value
      .filter((f) => selectedFileIds.value.has(f.id))
      .map((f) => ({ id: f.id, name: f.originalFilename })),
  }
  messages.value = [...messages.value, userMsg]
  scrollToBottom()

  const streamMsgId = `ai-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
  const streamMsg: ChatMessageVO = {
    id: streamMsgId,
    role: 'ai',
    content: '',
    timestamp: Date.now(),
    status: 'streaming',
  }
  messages.value = [...messages.value, streamMsg]

  chatting.value = true
  const cid = activeConversationId.value ?? '0'

  // 构建流式 URL，附带用户选中的文件 ID
  let streamUrl = `/agent/chat/stream?message=${encodeURIComponent(text)}&conversationId=${cid}`
  const selFiles = [...selectedFileIds.value]
  if (selFiles.length > 0) {
    streamUrl += `&fileIds=${selFiles.join(',')}`
  }

  let finalAnalysis = ''
  const STATUS_TEXTS = new Set(['正在分析数据…', '正在生成图表…'])

  // 本轮的表格事件累加器
  const streamTables: TableEventData[] = []

  // ---- rAF 批处理：合并逐 token 更新为一帧一次 ----
  let pendingContent = ''
  let rafToken: number | null = null

  function flushBuffer() {
    rafToken = null
    if (!pendingContent) return

    messages.value = messages.value.map((m) => {
      if (m.id !== streamMsgId) return m
      const prev = STATUS_TEXTS.has(m.content) ? '' : m.content
      return { ...m, content: prev + pendingContent }
    })
    pendingContent = ''
    scrollToBottom(true) // auto 模式，无 smooth 动画
  }

  // 暴露给 handleBeforeUnload：刷新前先把缓冲区刷入 messages.value
  flushPendingStream.value = () => {
    if (rafToken !== null) {
      cancelAnimationFrame(rafToken)
      rafToken = null
    }
    flushBuffer()
  }

  // ---- SSE 重连状态 ----
  let resumeToken: string | undefined
  let eventCount = 0
  const MAX_RETRIES = 3
  let retryCount = 0
  let reconnecting = false

  function doStream(url: string) {
    return apiPostStream(
      url,
      {},
      (token: string) => {
        eventCount++
        pendingContent += token
        if (!rafToken) {
          rafToken = requestAnimationFrame(flushBuffer)
        }
      },
      () => {},
      (err: Error) => {
        // 错误回调 — 尝试重连
        if (retryCount < MAX_RETRIES && resumeToken) {
          retryCount++
          reconnecting = true
          const delay = 1000 * Math.pow(2, retryCount - 1)
          // 显示重连状态
          messages.value = messages.value.map((m) =>
            m.id === streamMsgId
              ? { ...m, content: '连接中断，正在重连…', reconnecting: true }
              : m,
          )
          setTimeout(() => {
            // 走 resume 路径：接回活跃流
            const resumeUrl = `/agent/chat/resume?conversationId=${cid}&resumeToken=${resumeToken}&resumeSeq=${eventCount}`
            const r = doStream(resumeUrl)
            stopStream.value = r.abort
            r.promise.then(() => {
              // resume 完成处理（下方 await promise 外的逻辑处理）
            }).catch(() => {
              messages.value = messages.value.map((m) =>
                m.id === streamMsgId
                  ? { ...m, content: `❌ ${err.message}`, status: 'error', timestamp: Date.now(), reconnecting: false }
                  : m,
              )
            })
          }, delay)
          return
        }
        // 重连耗尽 → 显示错误
        messages.value = messages.value.map((m) =>
          m.id === streamMsgId
            ? { ...m, content: m.content || `❌ ${err.message}`, status: 'error', timestamp: Date.now(), reconnecting: false }
            : m,
        )
        scrollToBottom()
      },
      (data: StructuredEvent) => {
        const parsedOptions = parseChartOptions(data.chartOption)
        if (parsedOptions) {
          messages.value = messages.value.map((m) =>
            m.id === streamMsgId ? { ...m, chartOption: parsedOptions, chartGenerating: false } : m,
          )
        }
        if (data.analysis) {
          finalAnalysis = data.analysis
        }
        if (typeof data.tokenUsage === 'number') {
          messages.value = messages.value.map((m) =>
            m.id === streamMsgId ? { ...m, tokenUsage: data.tokenUsage } : m,
          )
        }
        // 保存 resumeToken
        if ((data as unknown as Record<string, unknown>).resumeToken) {
          resumeToken = (data as unknown as Record<string, unknown>).resumeToken as string
        }
        // 重置重连状态
        reconnecting = false
      },
      (status: string) => {
        // 从 status 事件中提取 resumeToken（后端在流启动时即发送）
        if (status.startsWith('resumeToken:')) {
          resumeToken = status.slice('resumeToken:'.length).trim()
          return
        }
        const chartGenStatus = '正在生成图表…'
        messages.value = messages.value.map((m) => {
          if (m.id !== streamMsgId) return m
          // 图表生成阶段：设置 chartGenerating 标记，保持已有内容不变
          if (status === chartGenStatus) {
            return { ...m, chartGenerating: true }
          }
          const isStatus = m.content === '' || STATUS_TEXTS.has(m.content)
          return isStatus ? { ...m, content: status } : m
        })
      },
      (chartJson: string) => {
        const parsedOptions = parseChartOptions(chartJson)
        if (parsedOptions) {
          messages.value = messages.value.map((m) =>
            m.id === streamMsgId ? { ...m, chartOption: parsedOptions } : m,
          )
        }
      },
      (tableData: TableEventData) => {
        streamTables.push(tableData)
        messages.value = messages.value.map((m) =>
          m.id === streamMsgId
            ? { ...m, tables: [...(m.tables || []), tableData] }
            : m,
        )
        scrollToBottom(true)
      },
    )
  }

  try {
    const { promise, abort: abortStream } = doStream(streamUrl)

    stopStream.value = abortStream

    await promise

    // 流完成前 flush 剩余缓冲区，防止丢失最后一批 token
    if (rafToken !== null) {
      cancelAnimationFrame(rafToken)
      rafToken = null
    }
    flushBuffer()

    const streamMsg = messages.value.find((m) => m.id === streamMsgId)
    if (streamMsg?.status === 'streaming') {
      const finalContent = finalAnalysis || streamMsg.content
      messages.value = messages.value.map((m) =>
        m.id === streamMsgId
          ? { ...m, content: finalContent, status: 'done' as const, timestamp: Date.now(), chartGenerating: false }
          : m,
      )
      flushMessages()
      scrollToBottom()
    }
  } catch (err) {
    const errorText = err instanceof Error ? err.message : '分析请求失败'
    messages.value = messages.value.map((m) =>
      m.id === streamMsgId
        ? { ...m, content: `❌ ${errorText}`, status: 'error', timestamp: Date.now() }
        : m,
    )
    flushMessages()
    scrollToBottom()
  } finally {
    chatting.value = false
    stopStream.value = null
  }
}

// ---- 持久化 ----
const { saveMessages } = useChatPersistence()

/** 暴露给 handleBeforeUnload：把 rAF 缓冲区积压的 token 刷入 messages.value */
const flushPendingStream = ref<(() => void) | null>(null)

function flushMessages(): void {
  if (saveTimer) {
    clearTimeout(saveTimer)
    saveTimer = null
  }
  if (messages.value.length === 0) return
  saveMessages(messages.value)
}

function handleBeforeUnload(): void {
  // 先把流式缓冲区刷进 messages.value，否则最新 token 会丢失
  flushPendingStream.value?.()
  if (messages.value.length === 0) return
  saveMessages(messages.value)
}

/**
 * 定位到指定消息 — 从分析历史跳转到对话视图中的对应消息。
 * msgId 格式为 "db-{n}"，与 ChatMessage 元素 id 匹配。
 *
 * 注意：当前会话产生的消息 ID 是 user-{ts}/{ai-{ts} 客户端格式，
 * 而历史面板传过来的是 db-{n} 格式。因此每次定位前都从后端重新加载
 * 消息列表，确保 messages.value 中所有消息都带有正确的 db-{n} ID。
 */
async function handleLocateMessage(msgId: string): Promise<void> {
  const targetId = `msg-${msgId}`

  // 从后端重新加载消息，确保 ID 为 db-{n} 格式（vs 客户端 user-/ai- 格式）
  if (activeConversationId.value) {
    try {
      const msgList = await withRetry(() =>
        apiGetChecked<MessageVO[]>(`/agent/conversations/${activeConversationId.value}/messages`),
      )
      messages.value = (msgList || []).map((m) => ({
        id: `db-${m.id}`,
        role: m.role === 'assistant' ? 'ai' : 'user',
        content: m.content,
        timestamp: new Date(m.createTime).getTime(),
        status: 'done' as const,
        chartOption: parseChartOptions(m.chartOption),
        fileAttachments: parseFileAttachments(m.fileAttachments),
        tokenUsage: m.tokenUsage ?? undefined,
      }))
    } catch (err: unknown) {
      console.warn('[AnalysisPage] reload for locate failed', err)
    }
  }

  // 消息列表仍为空
  if (messages.value.length === 0) {
    message.warning('对话记录为空，无法定位')
    return
  }

  // 检查目标消息是否存在
  const exists = messages.value.some((m) => m.id === msgId)
  if (!exists) {
    message.warning('未找到对应的对话记录，可能已被删除')
    return
  }

  // 等待 DOM 更新（重渲染后的元素 id 才会出现在文档中）
  await nextTick()

  const el = document.getElementById(targetId)
  if (!el) {
    message.warning('未找到对应的对话记录')
    return
  }

  el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  el.classList.add('msg-flash-highlight')
  setTimeout(() => el.classList.remove('msg-flash-highlight'), 2000)
}

let saveTimer: ReturnType<typeof setTimeout> | null = null
watch(
  messages,
  () => {
    if (saveTimer) clearTimeout(saveTimer)
    saveTimer = setTimeout(() => {
      saveMessages(messages.value)
    }, 500)
  },
)

// ---- 初始化 ----
const { loadMessages: loadLocalMessages, clearMessages } = useChatPersistence()

onMounted(async () => {
  window.addEventListener('beforeunload', handleBeforeUnload)

  // 1. 获取默认对话 ID
  try {
    const convId = await withRetry(() =>
      apiGetChecked<string>('/agent/conversation'),
    )
    activeConversationId.value = convId

    // 2. 优先从 localStorage 恢复消息（含表格、图表等完整前端状态）
    const localMsgs = loadLocalMessages()
    if (localMsgs.length > 0) {
      // 将流式残留标记为完成（加 incomplete 标记）
      messages.value = localMsgs.map((m) =>
        m.status === 'streaming'
          ? { ...m, status: 'done' as const, timestamp: Date.now(), incomplete: true }
          : m,
      )
      if (localMsgs.some((m) => m.status === 'streaming')) {
        clearMessages()
      }
      scrollToBottom(true)
    } else {
      // 无本地缓存时从后端加载（不含 tables，但文本内容可正常渲染）
      await loadMessages()
    }

    // 3. 加载文件列表
    fetchFiles()
  } catch (err: unknown) {
    console.error('[AnalysisPage] init failed', err)
    message.error('对话初始化失败，请检查后端是否正常运行')
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload)
})
</script>

<template>
  <div class="analysis-page">
    <!-- 展开按钮（折叠时显示在左上角） -->
    <button
      v-if="configCollapsed"
      class="config-expand-btn"
      @click="configCollapsed = false"
      title="侧边栏"
      aria-label="展开侧边栏"
    >
      <svg width="28" height="18" viewBox="0 0 28 18" fill="none">
        <rect x="0.5" y="0.5" width="27" height="17" rx="3" stroke="currentColor" stroke-width="1" />
        <line x1="6.5" y1="0.5" x2="6.5" y2="17.5" stroke="#BC694A" stroke-width="1" opacity="0.7" />
        <rect x="1.5" y="2" width="4" height="2.5" rx="0.8" fill="currentColor" opacity="0.12" />
        <rect x="8.5" y="2.5" width="12" height="1.5" rx="0.8" fill="currentColor" opacity="0.1" />
        <rect x="8.5" y="6" width="18" height="1.5" rx="0.8" fill="currentColor" opacity="0.07" />
        <rect x="8.5" y="9.5" width="14" height="1.5" rx="0.8" fill="currentColor" opacity="0.07" />
        <rect x="8.5" y="13" width="16" height="1.5" rx="0.8" fill="currentColor" opacity="0.07" />
      </svg>
    </button>

    <!-- 左侧模型配置面板 -->
    <ModelConfigPanel
      :collapsed="configCollapsed"
      @toggle="configCollapsed = !configCollapsed"
      @locate-message="handleLocateMessage"
    />

    <!-- 右侧对话区 -->
    <div class="analysis-page__chat-col">
      <div class="analysis-page__chat-inner">
        <!-- 对话区域 -->
        <div ref="chatAreaRef" class="analysis-page__messages">
          <WelcomeScreen v-if="!hasMessages" :has-files="files.length > 0" />
          <template v-else>
            <ChatMessage
              v-for="msg in messages"
              :key="`${msg.id}-${msg.status}`"
              :message="msg"
            />
          </template>
          <div ref="scrollAnchorRef" />
        </div>

        <!-- 文件区域（带框网格布局） -->
        <div v-if="files.length > 0" class="file-box">
          <div class="file-box__grid">
            <div
              v-for="file in files"
              :key="file.id"
              class="file-item"
              :class="{ 'file-item--on': selectedFileIds.has(file.id) }"
              @click="toggleFile(file.id)"
            >
              <span class="file-item__check">
                <svg v-if="selectedFileIds.has(file.id)" width="18" height="18" viewBox="0 0 24 24" fill="none">
                  <circle cx="12" cy="12" r="10" fill="var(--accent)" stroke="none" />
                  <path d="M16 8l-6.5 7.5L6 12" stroke="#fff" stroke-width="2.8" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none">
                  <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.8" />
                </svg>
              </span>
              <span class="file-item__icon">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                  <path d="M6 4a2 2 0 012-2h7l4 4v14a2 2 0 01-2 2H8a2 2 0 01-2-2V4z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>
                  <path d="M15 2v4a1 1 0 001 1h4" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>
                </svg>
              </span>
              <span class="file-item__name">{{ file.originalFilename }}</span>
              <button
                class="file-item__preview"
                title="预览数据"
                @click.stop="handlePreviewFile(file)"
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                  <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" stroke="currentColor" stroke-width="1.8" />
                  <circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="1.8" />
                </svg>
              </button>
              <button
                class="file-item__delete"
                :aria-label="`删除 ${file.originalFilename}`"
                @click.stop="handleDeleteFile(file.id)"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
                  <path d="M18 6L6 18M6 6l12 12" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" />
                </svg>
              </button>
            </div>
          </div>
        </div>

        <!-- 输入区 -->
        <ChatInput
          :has-files="files.length > 0"
          :loading="chatting"
          :uploading="uploading"
          @send="handleSend"
          @stop="handleStop"
          @upload="handleUpload"
          @clear-conversation="handleClearMessages"
          @reset-conversation="handleResetConversation"
        />
      </div>
    </div>
  </div>

  <!-- 文件数据预览弹窗 -->
  <FilePreviewModal
    :file-id="previewFileId ?? ''"
    :file-name="previewFileName"
    :visible="showPreview"
    @close="showPreview = false"
  />
</template>

<style scoped>
.analysis-page {
  display: flex;
  height: 100vh;
  position: relative;
}

/* ===== 折叠时的展开按钮（左上角） ===== */
.config-expand-btn {
  position: absolute;
  top: 12px;
  left: 12px;
  z-index: 10;
  width: 44px;
  height: 44px;
  border-radius: 10px;
  border: 1px solid var(--border-soft);
  background: var(--surface);
  color: var(--muted);
  display: grid;
  place-items: center;
  cursor: pointer;
  transition: all 0.28s var(--ease-out-expo), transform 0.28s var(--spring);
}

.config-expand-btn:hover {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--surface-raised);
  transform: scale(1.06);
}

.analysis-page__chat-col {
  flex: 1;
  display: flex;
  justify-content: center;
  min-width: 0;
  padding: 0 8px 0 clamp(12px, 2.5vw, 40px);
  transition: padding 0.35s var(--ease-out-expo);
}

.analysis-page__chat-inner {
  width: 100%;
  max-width: min(clamp(720px, 75%, 1300px), 100%);
  display: flex;
  flex-direction: column;
}

.analysis-page__messages {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0 16px 0;
  scrollbar-width: thin;
  scrollbar-color: var(--scrollbar-thumb) transparent;
}

.analysis-page__messages::-webkit-scrollbar {
  width: 10px;
}

.analysis-page__messages::-webkit-scrollbar-track {
  background: transparent;
}

.analysis-page__messages::-webkit-scrollbar-thumb {
  background: var(--scrollbar-thumb);
  border-radius: 5px;
  min-height: 60px;
}

.analysis-page__messages::-webkit-scrollbar-thumb:hover {
  background: var(--muted);
}


/* ===== 文件区域（带框网格） ===== */
.file-box {
  flex-shrink: 0;
  background: var(--surface);
  border: 1px solid var(--border-soft);
  border-radius: 20px;
  padding: 12px;
  margin-bottom: 8px;
  position: relative;
  animation: file-box-enter 0.28s var(--ease-out-expo);
}

@keyframes file-box-enter {
  from {
    opacity: 0;
    transform: translateY(-8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 内边框 — 双边框效果 */
.file-box::before {
  content: '';
  position: absolute;
  inset: 5px;
  border-radius: 15px;
  border: 1px solid var(--border-inner);
  pointer-events: none;
}

.file-box__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(130px, 1fr));
  gap: 8px;
  position: relative;
  z-index: 1;
}

@media (max-width: 480px) {
  .file-box__grid {
    grid-template-columns: 1fr;
  }
}

/* ===== 单个文件气泡 ===== */
.file-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px 8px 8px;
  border-radius: 14px;
  background: var(--surface-raised);
  border: 1px solid var(--border-inner);
  font-size: 13px;
  color: var(--fg);
  cursor: pointer;
  user-select: none;
  -webkit-user-select: none;
  min-width: 0;
  transition: border-color 0.28s var(--ease-out-expo),
              background 0.28s var(--ease-out-expo);
}

.file-item:hover {
  border-color: var(--accent);
}

.file-item--on {
  border-color: var(--accent);
  background: var(--accent-glow-soft);
}

/* 选中态复选框 */
.file-item__check {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  color: var(--muted);
}

.file-item--on .file-item__check {
  color: var(--accent);
}

/* 文件图标 */
.file-item__icon {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  color: var(--muted);
}

.file-item--on .file-item__icon {
  color: var(--accent);
}

/* 文件名 */
.file-item__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 1.3;
}

/* 预览按钮 */
.file-item__preview {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: none;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  flex-shrink: 0;
  padding: 0;
  opacity: 0;
  transition: all 0.2s var(--ease-out-expo);
}

.file-item:hover .file-item__preview {
  opacity: 1;
}

.file-item__preview:hover {
  color: var(--accent);
  background: var(--accent-glow-soft);
  opacity: 1;
}

/* 删除按钮 */
.file-item__delete {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: none;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  flex-shrink: 0;
  padding: 0;
  opacity: 0;
  transition: all 0.2s var(--ease-out-expo);
}

.file-item:hover .file-item__delete {
  opacity: 1;
}

.file-item__delete:hover {
  color: #e05555;
  background: rgba(224, 85, 85, 0.12);
  opacity: 1;
}

/* 当文件少于一整行时，让卡片不用撑满 */
.file-box__grid:only-child {
  justify-content: flex-start;
}

/* ===== 响应式断点 ===== */

@media (max-width: 1024px) {
  .analysis-page__chat-col {
    padding: 0 clamp(6px, 2vw, 16px);
  }

  /* 文件网格在小屏减列 */
  .file-box__grid {
    grid-template-columns: repeat(auto-fill, minmax(110px, 1fr));
  }
}

@media (max-width: 768px) {
  .analysis-page__chat-col {
    padding: 0 6px;
  }

  .file-box__grid {
    grid-template-columns: repeat(auto-fill, minmax(100px, 1fr));
    gap: 6px;
  }

  .file-item {
    padding: 6px 8px 6px 6px;
    font-size: 12px;
  }

  .analysis-page__messages {
    padding: 4px 0 12px;
  }
}
</style>
