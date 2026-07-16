<script setup lang="ts">
import { NButton, NInput, NSelect, useDialog, useMessage } from 'naive-ui'
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { ApiError, apiDelete, apiGet, apiGetChecked, apiPost } from '@/services/api'
import type { MessageVO } from '@/services/types'
import { parseChartOptions, parseFileNames } from '@/utils/messageParser'
import LogoIcon from './LogoIcon.vue'

const ChartPreviewModal = defineAsyncComponent(() => import('./ChartPreviewModal.vue'))

const message = useMessage()
const dialog = useDialog()

const props = defineProps<{
  collapsed: boolean
}>()

const emit = defineEmits<(e: 'locate-message', msgId: string) => void>()

// ---- 页签状态 ----
const activeTab = ref<'config' | 'history'>('config')

// ---- 加载/保存状态 ----
const loading = ref(true)
const saving = ref(false)
const configured = ref(false)

// ---- 常量 ----
const DEEPSEEK_MODELS = [
  { label: 'DeepSeek v4 Flash', value: 'deepseek-v4-flash' },
  { label: 'DeepSeek v4 Pro', value: 'deepseek-v4-pro' },
]

// ---- 表单字段 ----
const provider = ref<string>('DEEPSEEK')
const apiKey = ref('')
const modelName = ref('')
const baseUrl = ref('')
const showApiKey = ref(false)
const apiKeyMasked = ref('')

// ---- 计算 ----
const showBaseUrl = computed(() => provider.value === 'CUSTOM')
const isDeepseek = computed(() => provider.value === 'DEEPSEEK')
const canSave = computed(() => provider.value && modelName.value && (apiKey.value || configured))

// ======================== 分析历史 ========================

const chartMessages = ref<MessageVO[]>([])
const fetchingCharts = ref(false)
const conversationId = ref<number | null>(null)

/** 当前选中的历史记录（用于图表预览） */
const selectedChart = ref<{
  charts: Array<{ option: Record<string, unknown> }>
  fileNames: string[]
  content: string
  /** 前端消息 ID（"db-{n}" 格式），用于定位 */
  messageId: string
} | null>(null)
const showChartModal = ref(false)

/** 解析 chartOption JSON 字符串 → 图表配置数组 */
/** 解析 fileAttachments JSON 字符串 → 文件名列表 */
/** 截取内容预览（去 JSON 残留，取前 120 字符） */
function getContentPreview(text: string): string {
  if (!text) return ''
  // 去掉可能的 chart JSON 残留
  let clean = text
    .replace(/```[\s\S]*?```/g, '')
    .replace(/\{[\s\S]*?\}/g, '')
    .replace(/#{1,6}\s*NEEDS_CHART#*/gi, '')
    .replace(/现在为您生成可视化图表[！!]?/g, '')
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .trim()
  if (!clean) clean = text
  return clean.length > 120 ? `${clean.slice(0, 120)}…` : clean
}

/** 格式化时间 */
function formatTime(t?: string): string {
  if (!t) return ''
  const d = new Date(t)
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** Token 数量格式化 */
function formatTokens(n?: number): string {
  if (n == null) return ''
  if (n >= 1000) {
    const k = n / 1000
    return k % 1 === 0 ? `${k}k` : `${k.toFixed(1)}k`
  }
  return String(n)
}

/** 获取当前对话 ID */
async function ensureConversationId(): Promise<number> {
  if (conversationId.value) return conversationId.value
  const cid = await apiGetChecked<number>('/agent/conversation')
  conversationId.value = cid
  return cid
}

/** 获取含图表的历史消息 */
async function fetchChartMessages() {
  try {
    const cid = await ensureConversationId()
    fetchingCharts.value = true
    chartMessages.value = await apiGetChecked<MessageVO[]>(
      `/agent/conversations/${cid}/chart-messages`,
    )
  } catch (err: unknown) {
    const msg = err instanceof ApiError ? err.message : '加载失败'
    message.error(msg)
  } finally {
    fetchingCharts.value = false
  }
}

/** 点击历史记录 → 打开图表预览 */
function openChartPreview(msg: MessageVO) {
  const charts = parseChartOptions(msg.chartOption)
  if (!charts || charts.length === 0) {
    message.warning('该记录没有可用的可视化图表')
    return
  }
  selectedChart.value = {
    charts: charts.map((opt) => ({ option: opt })),
    fileNames: parseFileNames(msg.fileAttachments),
    content: msg.content,
    messageId: `db-${msg.id}`,
  }
  showChartModal.value = true
}

/** 从图表弹窗定位到对话记录 */
function handleLocateMessage(msgId: string): void {
  showChartModal.value = false
  emit('locate-message', msgId)
}

/** 切换到历史页签时自动加载 */
watch(activeTab, (tab) => {
  if (tab === 'history' && chartMessages.value.length === 0) {
    fetchChartMessages()
  }
})

// ---- API ----

async function fetchConfig() {
  loading.value = true
  try {
    const res = await apiGet<Record<string, unknown>>('/model/config')
    if (res.code !== 0) return
    const data = res.data
    configured.value = !!data.configured
    if (configured.value) {
      provider.value = (data.provider as string) || 'DEEPSEEK'
      modelName.value = (data.modelName as string) || ''
      baseUrl.value = (data.baseUrl as string) || ''
      apiKey.value = ''
      apiKeyMasked.value = (data.apiKeyMasked as string) || ''
    } else {
      provider.value = 'DEEPSEEK'
      modelName.value = ''
      baseUrl.value = ''
      apiKey.value = ''
      apiKeyMasked.value = ''
    }
  } catch (err: unknown) {
    console.error('[ModelConfig] fetch failed', err)
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  if (!canSave.value) return
  // 仅当用户输入了新 Key 时才做校验
  if (apiKey.value) {
    if (apiKey.value.length < 8) {
      message.warning('API Key 长度不足，至少 8 位')
      return
    }
    // 防止将脱敏 key（含 ****）传回后端
    if (apiKey.value.includes('****')) {
      message.warning('检测到脱敏 Key，请输入完整的 API Key')
      return
    }
  }
  if (provider.value === 'CUSTOM' && !baseUrl.value) {
    message.warning('CUSTOM 模式下接口地址不能为空')
    return
  }
  saving.value = true
  try {
    const body: Record<string, unknown> = {
      provider: provider.value,
      apiKey: apiKey.value,
      modelName: modelName.value,
    }
    if (baseUrl.value) body.baseUrl = baseUrl.value
    const res = await apiPost('/model/config', body)
    if (res.code !== 0) {
      message.error(res.message || '保存失败')
      return
    }
    message.success('模型配置已保存')
    configured.value = true
    apiKey.value = ''
    await fetchConfig()
  } catch (err: unknown) {
    const msg = err instanceof ApiError ? err.message : '保存失败'
    message.error(msg)
  } finally {
    saving.value = false
  }
}

function handleReset() {
  dialog.warning({
    title: '重置模型配置',
    content: '确定要恢复系统默认模型吗？自定义的 API Key 将被清除。',
    positiveText: '确认重置',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        const res = await apiDelete('/model/config')
        if (res.code !== 0) {
          message.error(res.message || '重置失败')
          return
        }
        message.success('已恢复系统默认模型')
        await fetchConfig()
      } catch (err: unknown) {
        const msg = err instanceof ApiError ? err.message : '重置失败'
        message.error(msg)
      }
    },
  })
}

onMounted(() => {
  fetchConfig()
})
</script>

<template>
  <aside id="workspace-sidebar" class="config-panel" :class="{ 'config-panel--hidden': collapsed }">
    <div class="config-panel__inner">
    <!-- ===== 品牌区 ===== -->
    <div class="panel-brand">
      <LogoIcon :size="48" />
      <span class="panel-brand__name">CData Agent</span>
    </div>

    <!-- ===== 页签导航 ===== -->
    <div class="panel-tabs">
      <button
        class="panel-tab"
        :class="{ 'panel-tab--active': activeTab === 'config' }"
        @click="activeTab = 'config'"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
          <path d="M12 15a3 3 0 100-6 3 3 0 000 6z" stroke="currentColor" stroke-width="1.8" />
          <path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 01-2.83 2.83l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 010-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 012.83-2.83l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 014 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 010 4h-.09a1.65 1.65 0 00-1.51 1z" stroke="currentColor" stroke-width="1.8" />
        </svg>
        模型配置
      </button>
      <button
        class="panel-tab"
        :class="{ 'panel-tab--active': activeTab === 'history' }"
        @click="activeTab = 'history'"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
          <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.8" />
          <path d="M12 7v5l3 3" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
        </svg>
        分析历史
      </button>
    </div>

    <!-- ===== 内容区 ===== -->
    <div class="panel-content">
      <!-- 模型配置 -->
      <template v-if="activeTab === 'config'">
        <div v-if="loading" class="panel-loading">
          <p>加载中…</p>
        </div>

        <template v-else>
          <div class="config-form">
            <div class="form-group">
              <label class="form-label">模型提供方</label>
              <NSelect
                v-model:value="provider"
                :options="[
                  { label: 'DeepSeek', value: 'DEEPSEEK' },
                  { label: '自定义 (兼容 OpenAI)', value: 'CUSTOM' },
                ]"
                placeholder="选择提供方"
              />
            </div>
            <div class="form-group">
              <label class="form-label">API Key</label>
              <NInput
                v-model:value="apiKey"
                type="password"
                :placeholder="configured ? '输入新 Key 替换现有配置' : ''"
                size="medium"
                show-password-on="click"
              />
              <div v-if="configured && apiKeyMasked" class="key-badge">
                已配置：{{ apiKeyMasked }}
              </div>
            </div>
            <div class="form-group">
              <label class="form-label">模型名称</label>
              <NSelect
                v-if="isDeepseek"
                v-model:value="modelName"
                :options="DEEPSEEK_MODELS"
                placeholder="选择模型版本"
                size="medium"
              />
              <NInput
                v-else
                v-model:value="modelName"
                placeholder="输入模型名称"
                size="medium"
              />
            </div>
            <div v-if="showBaseUrl" class="form-group">
              <label class="form-label">接口地址</label>
              <NInput
                v-model:value="baseUrl"
                placeholder="如 https://api.openai.com"
                size="medium"
              />
            </div>
            <div v-if="!loading" class="form-actions">
              <NButton
                type="primary"
                :loading="saving"
                :disabled="!canSave"
                size="medium"
                style="flex: 1"
                @click="handleSave"
              >
                保存
              </NButton>
              <NButton
                v-if="configured"
                size="medium"
                ghost
                style="flex: 1"
                @click="handleReset"
              >
                重置默认
              </NButton>
            </div>
          </div>
        </template>
      </template>

      <!-- 分析历史 -->
      <div v-else class="chart-history">
        <div v-if="fetchingCharts" class="panel-loading">
          <p>加载中…</p>
        </div>

        <template v-else-if="chartMessages.length === 0">
          <div class="chart-history__empty">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none">
              <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.8" />
              <path d="M12 7v5l3 3" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
            </svg>
            <p>暂无图表记录</p>
            <span>完成数据分析后将自动展示</span>
          </div>
        </template>

        <template v-else>
          <div class="chart-history__list">
            <div
              v-for="msg in chartMessages"
              :key="msg.id"
              class="chart-history__item"
              @click="openChartPreview(msg)"
            >
              <!-- 关联文件 -->
              <div v-if="parseFileNames(msg.fileAttachments).length > 0" class="chart-history__files">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none">
                  <path d="M5 4a2 2 0 012-2h7l5 5v13a2 2 0 01-2 2H7a2 2 0 01-2-2V4z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>
                  <path d="M14 2v4a1 1 0 001 1h4" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>
                </svg>
                <span>{{ parseFileNames(msg.fileAttachments).join('、') }}</span>
              </div>

              <!-- 内容预览 -->
              <p class="chart-history__preview">{{ getContentPreview(msg.content) }}</p>

              <!-- 底部元信息 -->
              <div class="chart-history__meta">
                <span class="chart-history__time">{{ formatTime(msg.createTime) }}</span>
                <span v-if="msg.tokenUsage != null" class="chart-history__tokens">
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none">
                    <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                  {{ formatTokens(msg.tokenUsage) }}
                </span>
              </div>
            </div>
          </div>
        </template>
      </div>
    </div>
    </div>
  </aside>

  <!-- 图表预览弹窗 -->
  <ChartPreviewModal
    v-if="selectedChart"
    :charts="selectedChart.charts"
    :visible="showChartModal"
    :message-id="selectedChart.messageId"
    @close="showChartModal = false"
    @locate="handleLocateMessage"
  />
</template>

<style scoped>
/* ===== 面板容器 ===== */
.config-panel {
  flex-shrink: 0;
  width: var(--workspace-sidebar-width, 300px);
  background: var(--surface);
  border-right: 1px solid var(--border-soft);
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  transition: width 0.35s var(--ease-out-expo),
              border-color 0.35s var(--ease-out-expo);
}

.config-panel--hidden {
  width: 0;
  border-right-color: transparent;
}

.config-panel__inner {
  width: var(--workspace-sidebar-width, 300px);
  min-width: var(--workspace-sidebar-width, 300px);
  height: 100%;
  display: flex;
  flex-direction: column;
}

/* ===== 品牌区 ===== */
.panel-brand {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 22px 18px 18px;
  flex-shrink: 0;
}

.panel-brand__name {
  font-size: 25px;
  font-weight: 750;
  letter-spacing: -0.035em;
  line-height: 1;
  color: var(--fg);
}

/* ===== 页签导航 ===== */
.panel-tabs {
  display: flex;
  gap: 4px;
  padding: 8px 12px 4px;
  flex-shrink: 0;
}

.panel-tab {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 7px 8px;
  border-radius: 8px;
  border: none;
  background: transparent;
  color: var(--muted);
  font-size: 13px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.28s var(--ease-out-expo);
}

.panel-tab:hover {
  color: var(--fg);
  background: var(--border-inner);
}

.panel-tab--active {
  color: var(--fg);
  background: var(--accent-glow-soft);
}

.panel-tab--active svg {
  color: var(--accent);
}

/* ===== 内容区 ===== */
.panel-content {
  flex: 1;
  overflow-y: auto;
  padding: 8px 16px 16px;
  scrollbar-width: thin;
  scrollbar-color: var(--scrollbar-thumb) transparent;
}

.panel-content::-webkit-scrollbar {
  width: 4px;
}

.panel-content::-webkit-scrollbar-thumb {
  background: var(--scrollbar-thumb);
  border-radius: 2px;
}

.panel-loading {
  text-align: center;
  padding: 32px;
  color: var(--muted);
  font-size: 14px;
}

/* ===== 模型配置表单 ===== */
.config-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--fg);
  padding-left: 2px;
}

.key-badge {
  font-size: 12px;
  color: var(--muted);
  padding: 2px 8px;
  background: var(--surface-raised);
  border: 1px solid var(--border-inner);
  border-radius: 6px;
  margin-top: 4px;
  word-break: break-all;
}

.form-actions {
  display: flex;
  gap: 8px;
  padding-top: 6px;
}

/* ===== 分析历史 ===== */
.chart-history {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.chart-history__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 16px;
  color: var(--muted);
}

.chart-history__empty p {
  font-size: 15px;
  font-weight: 500;
  color: var(--fg);
  margin: 0;
}

.chart-history__empty span {
  font-size: 13px;
  color: var(--dim-text);
}

/* ===== 历史列表 ===== */
.chart-history__list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-bottom: 8px;
}

.chart-history__item {
  background: var(--surface-raised);
  border: 1px solid var(--border-inner);
  border-radius: 14px;
  padding: 10px 12px;
  cursor: pointer;
  transition: all 0.28s var(--ease-out-expo),
              transform 0.28s var(--spring);
  position: relative;
  animation: item-enter 0.3s var(--ease-out-expo) both;
}

@keyframes item-enter {
  from { opacity: 0; transform: translateY(6px); }
  to   { opacity: 1; transform: translateY(0); }
}

.chart-history__item:hover {
  border-color: var(--accent);
  background: var(--accent-glow-soft);
  transform: translateY(-1px);
}

.chart-history__files {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 4px;
  font-size: 11px;
  color: var(--accent-light);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chart-history__files svg {
  flex-shrink: 0;
  opacity: 0.7;
}

.chart-history__preview {
  font-size: 13px;
  line-height: 1.5;
  color: var(--muted);
  margin: 0 0 8px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}

.chart-history__item:hover .chart-history__preview {
  color: var(--fg);
}

.chart-history__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.chart-history__time {
  font-size: 11px;
  color: var(--dim-text);
  white-space: nowrap;
}

.chart-history__tokens {
  display: flex;
  align-items: center;
  gap: 3px;
  font-size: 11px;
  font-weight: 500;
  color: var(--accent-light);
  white-space: nowrap;
}

.chart-history__tokens svg {
  opacity: 0.7;
}

/* Naive UI 暗色覆盖 */
:deep(.n-input),
:deep(.n-select) {
  --n-border: var(--border-soft) !important;
  --n-border-hover: var(--accent) !important;
  --n-border-focus: var(--accent) !important;
  --n-color: var(--surface) !important;
  --n-text-color: var(--fg) !important;
  --n-placeholder-color: var(--muted) !important;
  --n-caret-color: var(--accent) !important;
}
</style>
