<script setup lang="ts">
import { useDialog, useMessage } from 'naive-ui'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ChatInput from '@/components/analysis/ChatInput.vue'
import ChatMessage from '@/components/analysis/ChatMessage.vue'
import FileContextBar from '@/components/analysis/FileContextBar.vue'
import FilePreviewModal from '@/components/analysis/FilePreviewModal.vue'
import ModelConfigPanel from '@/components/analysis/ModelConfigPanel.vue'
import WelcomeScreen from '@/components/analysis/WelcomeScreen.vue'
import { useAgentStream } from '@/composables/useAgentStream'
import { useChatPersistence } from '@/composables/useChatPersistence'
import { useFiles } from '@/composables/useFiles'
import {
  ApiError,
  apiDeleteChecked,
  apiGetChecked,
  apiPostChecked,
  withRetry,
} from '@/services/api'
import type { ChatMessageVO, DataFileVO, MessageVO } from '@/services/types'
import { parseChartOptions, parseFileAttachments } from '@/utils/messageParser'
import { safeParseRenderDocument } from '@/utils/renderDocument'

const message = useMessage()
const dialog = useDialog()

// ---- 对话状态 ----
const activeConversationId = ref<string | null>(null)
const messages = ref<ChatMessageVO[]>([])
const configCollapsed = ref(false)
const fileContextExpanded = ref(true)
const SIDEBAR_STORAGE_KEY = 'cdata-agent.workspace.sidebar-collapsed'
const FILE_CONTEXT_STORAGE_KEY = 'cdata-agent.workspace.file-context-collapsed'

function parseRenderDocument(value: string | null | undefined) {
  if (!value) return null
  try {
    return safeParseRenderDocument(JSON.parse(value))
  } catch {
    return null
  }
}

const {
  files,
  uploading,
  deletingAll: deletingAllFiles,
  selectedFileIds,
  toggleFile,
  fetchFiles,
  uploadFiles,
  deleteFile,
  deleteAllFiles,
} = useFiles(activeConversationId)
const {
  chatting,
  flushPending: flushAgentStream,
  start: startAgentStream,
  stop: stopAgentStream,
} = useAgentStream()
const selectedFileCount = computed(
  () => files.value.filter((file) => selectedFileIds.value.has(file.id)).length,
)

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
  if (chatting.value) {
    message.warning('任务运行中，暂不能清空聊天记录')
    return
  }
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
  if (chatting.value) {
    message.warning('任务运行中，暂不能重置对话')
    return
  }
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
const messagesContentRef = ref<HTMLDivElement | null>(null)
const autoScrollEnabled = ref(true)
const AUTO_SCROLL_THRESHOLD = 48
let followScrollFrame: number | null = null
let messagesResizeObserver: ResizeObserver | null = null

function updateAutoScrollState(): void {
  const el = chatAreaRef.value
  if (!el) return
  autoScrollEnabled.value =
    el.scrollHeight - el.scrollTop - el.clientHeight <= AUTO_SCROLL_THRESHOLD
}

function scrollToBottom(instant = false, force = false) {
  if (!force && !autoScrollEnabled.value) return
  if (force) autoScrollEnabled.value = true
  nextTick(() => {
    const el = chatAreaRef.value
    if (!el) return
    el.scrollTo({ top: el.scrollHeight, behavior: instant ? 'auto' : 'smooth' })
  })
}

/**
 * 流式输出按帧跟随，避免每个 token 叠加一次 smooth 动画导致抖动。
 * 用户主动上滑后 autoScrollEnabled 会关闭，不再抢占阅读位置。
 */
function scheduleStreamFollow(): void {
  if (followScrollFrame !== null || !autoScrollEnabled.value) return
  followScrollFrame = requestAnimationFrame(() => {
    followScrollFrame = null
    const el = chatAreaRef.value
    if (!el || !autoScrollEnabled.value) return
    el.scrollTop = el.scrollHeight
  })
}

// ---- 文件管理 ----

async function handleUpload(incoming: File[]) {
  try {
    const { addedCount, skippedCount } = await uploadFiles(incoming)
    if (addedCount > 0) {
      fileContextExpanded.value = true
      message.success(
        skippedCount > 0
          ? `上传了 ${addedCount} 个文件（${skippedCount} 个重复已跳过）`
          : `${addedCount} 个文件已上传`,
      )
    } else if (skippedCount > 0) {
      message.warning('文件已存在，请勿重复上传')
    }
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : '上传失败'
    message.error(msg)
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
        await deleteFile(fileId)
        message.success('文件已删除')
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : '删除失败'
        message.error(msg)
      }
    },
  })
}

function handleDeleteAllFiles() {
  if (chatting.value) {
    message.warning('任务运行中，暂不能删除文件')
    return
  }
  if (files.value.length === 0 || deletingAllFiles.value) return

  const count = files.value.length
  dialog.warning({
    title: '清空数据文件',
    content: `将永久删除当前对话中的 ${count} 个数据文件，且无法恢复，是否继续？`,
    positiveText: '确认清空',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        const deletedCount = await deleteAllFiles()
        fileContextExpanded.value = false
        message.success(`已删除 ${deletedCount} 个数据文件`)
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : '清空文件失败'
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
      chartPreviewAvailable: m.role === 'assistant',
      fileAttachments: parseFileAttachments(m.fileAttachments),
      tokenUsage: m.tokenUsage ?? undefined,
      renderDocument: m.renderVersion === 1 ? parseRenderDocument(m.renderDocument) : null,
      renderVersion: m.renderVersion ?? null,
    }))
    scrollToBottom(true)
  } catch (err: unknown) {
    console.warn('[AnalysisPage] loadMessages failed', err)
  }
}

async function syncMessagesAfterStream(): Promise<void> {
  flushMessages()
  const conversationId = activeConversationId.value
  if (!conversationId) return

  try {
    const persistedMessages = await withRetry(() =>
      apiGetChecked<MessageVO[]>(`/agent/conversations/${conversationId}/messages`),
    )
    const persisted = [...(persistedMessages || [])]
      .reverse()
      .find((item) => item.role === 'assistant')
    const liveMessage = [...messages.value]
      .reverse()
      .find((item) => item.role === 'ai' && item.status === 'done')
    if (!persisted || !liveMessage) return

    const persistedDocument =
      persisted.renderVersion === 1 ? parseRenderDocument(persisted.renderDocument) : null
    const persistedCharts = parseChartOptions(persisted.chartOption)

    // 当前运行已展示增量产物时，不再用持久化快照替换节点，避免结束时闪屏。
    if (!liveMessage.renderDocument && !liveMessage.liveBlocks?.length && persistedDocument) {
      liveMessage.renderDocument = persistedDocument
      liveMessage.renderVersion = persisted.renderVersion ?? null
    }
    if (!liveMessage.chartOption && persistedCharts) liveMessage.chartOption = persistedCharts
    if (persistedCharts) {
      liveMessage.chartResultState = 'ready'
      liveMessage.chartPreviewAvailable = true
    } else if (liveMessage.chartExpected && !liveMessage.chartResultState) {
      liveMessage.chartResultState = 'unavailable'
      liveMessage.chartPreviewAvailable = false
    }
    if (liveMessage.tokenUsage === undefined)
      liveMessage.tokenUsage = persisted.tokenUsage ?? undefined
    if (!liveMessage.chartResultState) liveMessage.chartPreviewAvailable = true
  } catch (err: unknown) {
    console.warn('[AnalysisPage] stream message reconciliation failed', err)
  }
}

// ---- 发送消息 ----

function handleStop() {
  stopAgentStream()
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
  scrollToBottom(false, true)

  const streamMsgId = `ai-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
  const streamMsg: ChatMessageVO = {
    id: streamMsgId,
    role: 'ai',
    content: '',
    timestamp: Date.now(),
    status: 'streaming',
  }
  messages.value = [...messages.value, streamMsg]
  const activeStreamMessage = messages.value[messages.value.length - 1]
  if (!activeStreamMessage) return

  await startAgentStream({
    conversationId: activeConversationId.value ?? '0',
    text,
    fileIds: [...selectedFileIds.value],
    message: activeStreamMessage,
    onPersist: syncMessagesAfterStream,
  })
}

// ---- 持久化 ----
const { saveMessages } = useChatPersistence()

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
  flushAgentStream()
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
        chartPreviewAvailable: m.role === 'assistant',
        fileAttachments: parseFileAttachments(m.fileAttachments),
        tokenUsage: m.tokenUsage ?? undefined,
        renderDocument: m.renderVersion === 1 ? parseRenderDocument(m.renderDocument) : null,
        renderVersion: m.renderVersion ?? null,
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
watch(messages, () => {
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => {
    saveMessages(messages.value)
  }, 500)
})

// ---- 初始化 ----
const { loadMessages: loadLocalMessages, clearMessages } = useChatPersistence()

onMounted(async () => {
  try {
    configCollapsed.value = window.localStorage.getItem(SIDEBAR_STORAGE_KEY) === 'true'
    fileContextExpanded.value = window.localStorage.getItem(FILE_CONTEXT_STORAGE_KEY) !== 'true'
  } catch {
    // 本地存储不可用时保留默认展开状态。
  }
  window.addEventListener('beforeunload', handleBeforeUnload)
  chatAreaRef.value?.addEventListener('scroll', updateAutoScrollState, { passive: true })
  if (messagesContentRef.value) {
    messagesResizeObserver = new ResizeObserver(scheduleStreamFollow)
    messagesResizeObserver.observe(messagesContentRef.value)
  }

  // 1. 获取默认对话 ID
  try {
    const convId = await withRetry(() => apiGetChecked<string>('/agent/conversation'))
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

watch(configCollapsed, (value) => {
  try {
    window.localStorage.setItem(SIDEBAR_STORAGE_KEY, String(value))
  } catch {
    // 偏好保存失败不影响工作区。
  }
})

watch(fileContextExpanded, (value) => {
  try {
    window.localStorage.setItem(FILE_CONTEXT_STORAGE_KEY, String(!value))
  } catch {
    // 偏好保存失败不影响工作区。
  }
})

onBeforeUnmount(() => {
  handleStop()
  flushAgentStream()
  if (saveTimer) clearTimeout(saveTimer)
  if (followScrollFrame !== null) cancelAnimationFrame(followScrollFrame)
  messagesResizeObserver?.disconnect()
  chatAreaRef.value?.removeEventListener('scroll', updateAutoScrollState)
  window.removeEventListener('beforeunload', handleBeforeUnload)
})
</script>

<template>
  <div class="analysis-page">
    <!-- 侧栏开关始终使用同一控件，随面板边界平滑移动 -->
    <button
      class="workspace-sidebar-toggle"
      :class="{ 'workspace-sidebar-toggle--collapsed': configCollapsed }"
      type="button"
      :aria-expanded="!configCollapsed"
      aria-controls="workspace-sidebar"
      @click="configCollapsed = !configCollapsed"
      :title="configCollapsed ? '展开侧边栏' : '收起侧边栏'"
      :aria-label="configCollapsed ? '展开侧边栏' : '收起侧边栏'"
    >
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path d="M15 18l-6-6 6-6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </button>

    <!-- 左侧模型配置面板 -->
    <ModelConfigPanel
      :collapsed="configCollapsed"
      @locate-message="handleLocateMessage"
    />

    <!-- 右侧对话区 -->
    <div class="analysis-page__chat-col">
      <div class="analysis-page__chat-inner">
        <!-- 对话区域 -->
        <div ref="chatAreaRef" class="analysis-page__messages">
          <div ref="messagesContentRef" class="analysis-page__messages-content">
            <WelcomeScreen v-if="!hasMessages" :has-files="files.length > 0" />
            <template v-else>
              <ChatMessage
                v-for="msg in messages"
                :key="msg.id"
                :message="msg"
              />
            </template>
          </div>
        </div>

        <div class="analysis-page__composer">
          <FileContextBar
            :files="files"
            :selected-file-ids="selectedFileIds"
            :expanded="fileContextExpanded"
            :deleting-all="deletingAllFiles"
            @toggle-file="toggleFile"
            @preview-file="handlePreviewFile"
            @delete-file="handleDeleteFile"
            @delete-all-files="handleDeleteAllFiles"
          />

          <!-- 输入区 -->
          <ChatInput
            :has-files="files.length > 0"
            :file-count="files.length"
            :selected-file-count="selectedFileCount"
            :file-context-expanded="fileContextExpanded"
            :loading="chatting"
            :uploading="uploading"
            @send="handleSend"
            @stop="handleStop"
            @upload="handleUpload"
            @toggle-file-context="fileContextExpanded = !fileContextExpanded"
            @clear-conversation="handleClearMessages"
            @reset-conversation="handleResetConversation"
          />
        </div>
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
  --workspace-sidebar-width: 300px;
  display: flex;
  height: 100vh;
  position: relative;
}

/* ===== 侧栏控制 ===== */
.workspace-sidebar-toggle {
  position: absolute;
  top: 12px;
  left: calc(var(--workspace-sidebar-width) - 44px);
  z-index: 20;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  border: 1px solid var(--border-soft);
  background: var(--surface);
  color: var(--muted);
  display: grid;
  place-items: center;
  cursor: pointer;
  box-shadow: var(--shadow-card);
  transition: left 0.35s var(--ease-out-expo), border-color 0.2s var(--ease-out-expo),
    color 0.2s var(--ease-out-expo), background 0.2s var(--ease-out-expo);
}

.workspace-sidebar-toggle--collapsed {
  left: 12px;
}

.workspace-sidebar-toggle svg {
  transition: transform 0.24s var(--ease-out-expo);
}

.workspace-sidebar-toggle--collapsed svg {
  transform: rotate(180deg);
}

.workspace-sidebar-toggle:hover {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-glow-soft);
}

.workspace-sidebar-toggle:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

.analysis-page__chat-col {
  flex: 1;
  min-width: 0;
  padding: 0 0 0 clamp(12px, 2.5vw, 40px);
  transition: padding 0.35s var(--ease-out-expo);
}

.analysis-page__chat-inner {
  width: 100%;
  height: 100%;
  max-width: none;
  display: flex;
  flex-direction: column;
}

.analysis-page__messages {
  flex: 1;
  overflow-y: auto;
  overflow-anchor: none;
  scrollbar-width: thin;
  scrollbar-color: var(--scrollbar-thumb) transparent;
}

.analysis-page__messages-content,
.analysis-page__composer {
  width: 100%;
  max-width: min(clamp(720px, 75%, 1300px), 100%);
  margin: 0 auto;
}

.analysis-page__messages-content {
  padding: 8px 16px 16px;
}

.analysis-page__composer {
  --composer-gutter: 16px;
  position: relative;
  flex-shrink: 0;
  padding: 0 var(--composer-gutter);
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


/* ===== 响应式断点 ===== */

@media (max-width: 1024px) {
  .analysis-page__chat-col {
    padding: 0 0 0 clamp(6px, 2vw, 16px);
  }
}

@media (max-width: 768px) {
  .analysis-page__chat-col {
    padding: 0;
  }

  .analysis-page__messages-content,
  .analysis-page__composer {
    --composer-gutter: 8px;
    padding-right: 8px;
    padding-left: 8px;
  }
}
</style>
