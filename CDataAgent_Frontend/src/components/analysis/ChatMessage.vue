/**
 * AI 消息气泡组件。
 *
 * 当 AI 响应包含 ECharts v5 图表配置时：
 *  - 流式过程中自动过滤 raw JSON，只展示分析文本
 *  - 图表流程结束并持久化后显示「查看可视化图表」引导按钮
 *  - 点击按钮打开全屏弹窗清晰展示图表
 *
 * 当 AI 响应包含结论（conclusion）时：
 *  - 独立渲染"分析结论"卡片，与推理文本分离
 *  - 结论区右上角提供一键复制按钮
 */

<script setup lang="ts">
import { useMessage } from 'naive-ui'
import { type Component, computed, defineAsyncComponent, ref } from 'vue'
import type { ChatMessageVO } from '@/services/types'
import { extractChartOption } from '@/utils/chartParser'
import {
  type ContentSegment,
  getDisplayText,
  parseContentSegments,
  renderCellContent,
  streamingMarkdown,
} from '@/utils/messageRenderer'
import { isValidRenderDocument } from '@/utils/renderDocument'
import BulletListBlockVue from './blocks/BulletListBlock.vue'
import DataTableBlockVue from './blocks/DataTableBlock.vue'
import NoticeBlockVue from './blocks/NoticeBlock.vue'
import ParagraphBlockVue from './blocks/ParagraphBlock.vue'
import SummaryBlockVue from './blocks/SummaryBlock.vue'
import LogoIcon from './LogoIcon.vue'
import RichTextContent from './RichTextContent.vue'
import RunActivityTimeline from './RunActivityTimeline.vue'

// 文档区块很小，静态加载可避免最终文档首次挂载时出现空白帧。

const blockCompMap: Record<string, Component> = {
  summary: SummaryBlockVue,
  paragraph: ParagraphBlockVue,
  bullets: BulletListBlockVue,
  table: DataTableBlockVue,
  notice: NoticeBlockVue,
}
function blockComponent(type: string) {
  return blockCompMap[type] ?? NoticeBlockVue
}

const msg = useMessage()

/** 图表弹窗异步加载（仅用户点击时触发 ECharts 加载） */
const ChartPreviewModal = defineAsyncComponent(() => import('./ChartPreviewModal.vue'))
const props = defineProps<{
  message: ChatMessageVO
}>()

const renderDocument = computed(() => {
  const document = props.message.renderDocument
  return isValidRenderDocument(document) ? document : null
})

const presentationBlocks = computed(
  () => props.message.liveBlocks ?? renderDocument.value?.blocks ?? [],
)
const hasPresentationBlocks = computed(() => presentationBlocks.value.length > 0)
const presentationDegraded = computed(() => renderDocument.value?.degraded === true)
const analysisActivities = computed(() =>
  (props.message.activities ?? []).filter(
    (activity) => activity.stage !== 'chart' && activity.stage !== 'validate',
  ),
)
const chartActivities = computed(() =>
  (props.message.activities ?? []).filter(
    (activity) => activity.stage === 'chart' || activity.stage === 'validate',
  ),
)

const showChartModal = ref(false)
const copySuccess = ref(false)

/** 消息的结论文本（独立于推理过程） */
const conclusion = computed((): string | null => {
  if (props.message.role !== 'ai') return null
  if (props.message.conclusion?.trim()) {
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
    setTimeout(() => {
      copySuccess.value = false
    }, 2000)
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

/** 流式阶段仅展示统一的不可点击图表入口，持久化刷新后才开放预览。 */
const chartPending = computed(
  () =>
    props.message.status === 'streaming' &&
    (props.message.chartGenerating || Boolean(chartResult.value)),
)
const canPreviewChart = computed(
  () =>
    props.message.status === 'done' &&
    props.message.chartPreviewAvailable === true &&
    Boolean(chartResult.value),
)

function openChartPreview(): void {
  if (canPreviewChart.value) showChartModal.value = true
}

/**
 * 获取展示文本：过滤掉可能的图表 JSON，只保留分析文本。
 * 新消息 content 已不含 JSON 和分隔符（ChartOutputTool 存 chartOption，
 * Synthesizer 只输出分析文本），此函数仅作为旧消息兜底。
 */
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
const contentSegments = computed((): ContentSegment[] => {
  const raw = props.message.content
  if (!raw) return []

  // 完成态先清理旧消息中可能嵌入的 chart JSON（兼容历史数据）
  const content = props.message.status !== 'streaming' ? getDisplayText(raw) || raw : raw

  // 兜底清理：移除可能残留的 ##CONCLUSION## / ##END## 标记
  const clean = content
    .replace(/##CONCLUSION##\r?\n?/gi, '')
    .replace(/\r?\n?##END##/gi, '')
    .trim()

  const segments = parseContentSegments(clean)

  // 流式状态：将光标插入最后一个文本段的末段 <p> 内，紧跟最后一个文字
  // 图表生成阶段不显示光标，改用「正在生成图表…」骨架提示
  if (props.message.status === 'streaming' && !chartPending.value && segments.length > 0) {
    for (let i = segments.length - 1; i >= 0; i--) {
      const seg = segments[i]
      if (seg.type === 'text' && seg.html) {
        // 在最后一个 </p> 前插入光标，使其位于段落末尾、紧跟最后一个字
        const lastP = seg.html.lastIndexOf('</p>')
        if (lastP >= 0) {
          segments[i] = {
            ...seg,
            html:
              seg.html.slice(0, lastP) +
              '<span class="cursor-blink">▌</span>' +
              seg.html.slice(lastP),
          }
        } else {
          // 无 <p> 包裹时直接追加
          segments[i] = { ...seg, html: `${seg.html}<span class="cursor-blink">▌</span>` }
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
function formatCellValue(val: unknown): string {
  if (val === null || val === undefined) return '—'
  if (typeof val === 'number') {
    return val % 1 === 0 ? val.toLocaleString() : val.toFixed(2)
  }
  return String(val)
}

function formatTimestamp(ts: number): string {
  const d = new Date(ts)
  const Y = d.getFullYear()
  const M = String(d.getMonth() + 1).padStart(2, '0')
  const D = String(d.getDate()).padStart(2, '0')
  const h = String(d.getHours()).padStart(2, '0')
  const m = String(d.getMinutes()).padStart(2, '0')
  return `${Y}-${M}-${D} ${h}:${m}`
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
        <span class="loading-text">思考中…</span>
      </div>
    </div>
  </div>

  <!-- AI 消息（流式 + 完成态统一渲染） -->
  <div v-else :id="'msg-' + message.id" class="msg-row msg-row--ai">
    <div class="msg-row__inner">
      <div class="msg-avatar">
        <LogoIcon :size="28" />
      </div>

      <!-- 无真实内容时的状态指示器（图表流程改用统一入口） -->
      <div v-if="message.status === 'streaming' && !hasRealContent && !message.chartGenerating && !message.activities?.length" class="msg-bubble msg-bubble--status">
        <span class="status-spinner" />
        <span class="status-text">{{ streamingDisplay() }}</span>
      </div>

      <!-- 断线重连指示器 -->
      <div v-else-if="message.reconnecting" class="msg-bubble msg-bubble--reconnecting">
        <span class="reconnecting-spinner" />
        <span class="reconnecting-text">连接中断，正在重连…</span>
      </div>

      <!-- 真实内容气泡（流式 + 完成态共享同一段落结构） -->
      <div
        v-else
        class="msg-bubble msg-bubble--ai"
        :class="{
          'msg-bubble--streaming': message.status === 'streaming',
        }"
      >
        <RunActivityTimeline v-if="analysisActivities.length" :activities="analysisActivities" />
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

        <!-- v1 协议：RenderDocument 区块渲染 -->
        <template v-if="hasPresentationBlocks">
          <TransitionGroup name="render-block" tag="div" class="render-document">
            <component
              v-for="(block, index) in presentationBlocks.filter((item) => item.type !== 'chart')"
              :key="block.id"
              :is="blockComponent(block.type)"
              :block="block"
              :style="{ '--render-block-delay': `${index * 45}ms` }"
            />
          </TransitionGroup>
          <div v-if="presentationDegraded" class="degraded-notice">
            ⚠️ 回答已降级
          </div>
        </template>

        <!-- 内容段：文本段 v-html 更新，表格段 v-for 固定骨架 + 增量行 -->
        <template v-if="!hasPresentationBlocks" v-for="seg in contentSegments" :key="seg.key">
          <RichTextContent v-if="seg.type === 'text'" class="msg-text" :html="seg.html" />
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
        <div v-if="!hasPresentationBlocks && message.tables && message.tables.length > 0" class="msg-tables">
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

        <!-- 图表入口：流式阶段与完成后的布局、图标和样式保持一致。 -->
        <RunActivityTimeline v-if="chartActivities.length" :activities="chartActivities" />

        <div
          v-if="chartPending || canPreviewChart"
          class="chart-trigger"
          :class="{ 'chart-trigger--pending': chartPending }"
          :role="canPreviewChart ? 'button' : undefined"
          :tabindex="canPreviewChart ? 0 : undefined"
          :aria-disabled="canPreviewChart ? undefined : true"
          @click="openChartPreview"
          @keydown.enter.prevent="openChartPreview"
        >
          <span class="chart-trigger__icon">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
              <rect x="3" y="14" width="4" height="7" rx="1" fill="currentColor" />
              <rect x="10" y="9" width="4" height="12" rx="1" fill="currentColor" />
              <rect x="17" y="4" width="4" height="17" rx="1" fill="currentColor" />
            </svg>
          </span>
          <span class="chart-trigger__text">
            {{ canPreviewChart
              ? (chartResult?.length === 1 ? '查看可视化图表' : `查看可视化图表（${chartResult?.length} 张）`)
              : '正在生成图表…' }}
          </span>
          <span class="chart-trigger__arrow">→</span>
        </div>

        <!-- 完成态：时间 + Token 消耗 -->
        <div v-if="message.status === 'done'" class="msg-token-usage">
          <span class="msg-token-usage__item msg-token-usage__time">{{ formatTimestamp(message.timestamp) }}</span>
          <span v-if="typeof message.tokenUsage === 'number'" class="msg-token-usage__item">
            <span class="msg-token-usage__label">Tokens</span>
            <span class="msg-token-usage__value">{{ formatTokens(message.tokenUsage) }}</span>
          </span>
        </div>
      </div>
    </div>

    <!-- 图表预览弹窗仅在持久化完成后按需挂载。 -->
    <ChartPreviewModal
      v-if="canPreviewChart && showChartModal"
      :charts="(chartResult ?? []).map((opt) => ({ option: opt }))"
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
  width: min(100%, 860px);
  min-width: 0;
  max-width: 100%;
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
  min-width: 0;
  padding: 12px 16px;
  font-size: 15px;
  line-height: 1.6;
  overflow-wrap: break-word;
  word-break: break-word;
  overflow: hidden;
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
  flex: 1;
  width: 0;
  max-width: none;
  padding: 4px 0;
  color: var(--fg);
  overflow: visible;
}

/* 流式气泡 — flex 布局让光标可同行或自然换行 */
.msg-bubble--streaming {
  display: block;
}

/* 分段渲染：文本段自适应宽度（短文本单行，长文本自动换行），表格段独占整行 */
.msg-bubble--streaming > .msg-text {
  max-width: 100%;
  min-width: 0;
  animation: stream-fade-in 0.12s ease-out;
}

@keyframes stream-fade-in {
  from { opacity: 0.88; }
  to   { opacity: 1; }
}
.msg-bubble--streaming > .msg-tables {
  width: 100%;
}

.render-document {
  display: grid;
  gap: 2px;
  min-width: 0;
  max-width: 100%;
}

.render-block-enter-active {
  transition:
    opacity 260ms ease-out,
    transform 260ms var(--ease-out-expo);
  transition-delay: var(--render-block-delay, 0ms);
}

.render-block-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

@media (prefers-reduced-motion: reduce) {
  .render-block-enter-active {
    transition: none;
  }
}

/* 状态气泡（图表生成 / 思考中） */
.msg-bubble--status {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 0;
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
  padding: 4px 0;
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

.chart-trigger--pending {
  cursor: default;
  pointer-events: none;
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
  gap: 6px;
  padding: 4px 12px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.12);
  font-size: 13px;
  white-space: nowrap;
  user-select: none;
  -webkit-user-select: none;
  max-width: 100%;
}

.msg-attach-chip__icon {
  flex-shrink: 0;
  font-size: 13px;
  line-height: 1;
}

.msg-attach-chip__name {
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.85);
}

.chart-trigger:hover .chart-trigger__arrow {
  transform: translateX(4px);
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

.msg-text :deep(pre) {
  margin: 12px 0;
  padding: 12px 16px;
  overflow-x: auto;
  border: 1px solid var(--border-inner);
  border-radius: 10px;
  background: var(--surface-raised);
}

.msg-text :deep(pre code) {
  color: var(--fg);
  font-family: 'SF Mono', 'Fira Code', 'Cascadia Code', monospace;
  font-size: 13px;
  white-space: pre;
}

.msg-text :deep(blockquote) {
  margin: 12px 0;
  padding: 6px 12px;
  border-left: 3px solid var(--accent);
  color: var(--muted);
  background: var(--surface-raised);
}

/* ===== 表格渲染（陶土 + 白色主题） ===== */
/* 适用于 .msg-text（完成态）和 .msg-tables（流式分段） */

/* 表格外容器 */
.msg-text :deep(.msg-table-wrap),
.msg-tables .msg-table-wrap {
  overflow-x: auto;
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

/* ===== Token 消耗徽章 + 时间 ===== */
.msg-token-usage {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--border-inner);
  font-size: 12px;
  user-select: none;
}

.msg-token-usage__item {
  display: flex;
  align-items: center;
  gap: 4px;
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

.msg-token-usage__time {
  color: var(--accent-light);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.02em;
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
