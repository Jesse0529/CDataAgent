/**
 * 图表预览弹窗 — 全屏暗色展示 ECharts 可视化图表。
 * 从对话框中点击「查看可视化图表」按钮触发。
 *
 * 设计规范：
 *   遮罩   → --bg 60% opacity
 *   面板   → --surface
 *   圆角   → --radius-lg (40px)
 *   关闭   → 右上角 X 按钮
 *   关闭方式: ESC / 点击遮罩 / 点击 X
 */

<script setup lang="ts">
import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps<{
  /** 图表配置数组（支持多图表切换） */
  charts: Array<{ option: Record<string, unknown> }>
  visible: boolean
  /** 前端消息 ID（"db-{n}" 格式），从历史面板打开时传入以支持定位 */
  messageId?: string
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'locate', msgId: string): void
}>()

function handleLocate(): void {
  if (props.messageId) {
    emit('locate', props.messageId)
  }
}

/** 下载当前图表为 PNG */
function downloadChart(): void {
  if (!chartInstance) return
  try {
    const url = chartInstance.getDataURL({
      type: 'png',
      pixelRatio: 2,
      backgroundColor: '#fff',
    })

    // 从 ECharts option 中提取标题作为文件名
    const option = props.charts[activeIndex.value]?.option
    let title = ''
    if (option && typeof option === 'object') {
      const t = (option as Record<string, unknown>).title
      if (t && typeof t === 'object') {
        title = ((t as Record<string, unknown>).text as string) ?? ''
      }
    }
    const safeName = title
      ? title.replace(/[<>:"/\\|?*]+/g, '_').trim().slice(0, 60)
      : ''
    const fileName = safeName ? `${safeName}.png` : `图表-${activeIndex.value + 1}.png`

    const link = document.createElement('a')
    link.download = fileName
    link.href = url
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
  } catch (e) {
    console.error('[ChartPreviewModal] download error:', e)
  }
}

// ---- 状态 ----
const modalRef = ref<HTMLDivElement | null>(null)
const chartContainer = ref<HTMLDivElement | null>(null)
const activeIndex = ref(0)

let chartInstance: echarts.ECharts | null = null
let resizeTimer: ReturnType<typeof setTimeout> | null = null

// ===== "Taste Soft" 自定义主题 =====
const TASTE_SOFT_THEME = {
  backgroundColor: 'transparent',
  color: [
    '#BC694A', '#E8A87C', '#D4895E', '#A85D3E',
    '#CF7A55', '#9C4E32', '#C4704D', '#B0603C',
  ],
  title: {
    textStyle: { color: '#2D2824', fontSize: 17, fontWeight: 600 },
    subtextStyle: { color: '#7A7268', fontSize: 13 },
    left: 'center',
  },
  legend: {
    textStyle: { color: '#7A7268', fontSize: 13 },
    pageTextStyle: { color: '#2D2824' },
  },
  tooltip: {
    backgroundColor: 'rgba(245, 240, 234, 0.96)',
    borderColor: 'rgba(0, 0, 0, 0.08)',
    borderWidth: 1,
    textStyle: { color: '#2D2824', fontSize: 13 },
    confine: true,
  },
  grid: { containLabel: true, backgroundColor: 'transparent', borderWidth: 0 },
  xAxis: {
    axisLine: { lineStyle: { color: 'rgba(0, 0, 0, 0.12)', width: 1 } },
    axisTick: { lineStyle: { color: 'rgba(0, 0, 0, 0.06)' } },
    axisLabel: { color: '#2D2824', fontSize: 12 },
    splitLine: { lineStyle: { color: 'rgba(0, 0, 0, 0.06)', type: 'dashed' as const } },
  },
  yAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: '#2D2824', fontSize: 12 },
    splitLine: { lineStyle: { color: 'rgba(0, 0, 0, 0.06)', type: 'dashed' as const } },
  },
  series: {
    label: { color: '#2D2824', fontSize: 12 },
    bar: { itemStyle: { borderRadius: [3, 3, 0, 0] } },
  },
  radar: {
    axisName: { color: '#7A7268', fontSize: 12 },
    splitArea: { areaStyle: { color: ['rgba(0,0,0,0.02)', 'rgba(0,0,0,0.01)'] } },
    splitLine: { lineStyle: { color: 'rgba(0,0,0,0.08)' } },
    axisLine: { lineStyle: { color: 'rgba(0,0,0,0.12)' } },
  },
  pie: {
    label: { color: '#2D2824', fontSize: 13 },
    labelLine: { lineStyle: { color: 'rgba(0,0,0,0.15)' } },
  },
  visualMap: { textStyle: { color: '#7A7268', fontSize: 11 } },
}

// ---- 渲染图表 ----
function renderChart(): void {
  if (!chartContainer.value || !props.charts.length) return

  const option = props.charts[activeIndex.value]?.option
  if (!option) return

  try {
    chartInstance = echarts.getInstanceByDom(chartContainer.value) ?? null
    if (!chartInstance) {
      chartInstance = echarts.init(chartContainer.value, TASTE_SOFT_THEME, { renderer: 'canvas' })
    }
    chartInstance.setOption(option as EChartsOption, { notMerge: true })
    chartInstance.resize()
  } catch (e) {
    console.error('[ChartPreviewModal] render error:', e)
  }
}

function destroyChart(): void {
  if (resizeTimer) {
    clearTimeout(resizeTimer)
    resizeTimer = null
  }
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
}

// ---- 弹窗控制 ----
function handleKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') emit('close')
}

function handleBackdropClick(e: MouseEvent): void {
  if (e.target === e.currentTarget) emit('close')
}

function prevChart(): void {
  if (activeIndex.value > 0) activeIndex.value--
}

function nextChart(): void {
  if (activeIndex.value < props.charts.length - 1) activeIndex.value++
}

// 监听 visible → 打开时渲染，关闭时销毁
watch(
  () => props.visible,
  (v) => {
    if (v) {
      activeIndex.value = 0
      nextTick(renderChart)
      document.addEventListener('keydown', handleKeydown)
    } else {
      destroyChart()
      document.removeEventListener('keydown', handleKeydown)
    }
  },
)

// 切换图表
watch(activeIndex, () => {
  nextTick(renderChart)
})

onMounted(() => {
  if (props.visible) {
    nextTick(() => {
      renderChart()
      document.addEventListener('keydown', handleKeydown)
    })
  }
})

onBeforeUnmount(() => {
  destroyChart()
  document.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <Teleport to="body">
    <div
      v-if="visible"
      ref="modalRef"
      class="chart-modal"
      @click="handleBackdropClick"
    >
      <div class="chart-modal__panel">
        <!-- 头部 -->
        <div class="chart-modal__header">
          <span class="chart-modal__title">数据可视化</span>
          <div class="chart-modal__header-actions">
            <button
              class="chart-modal__download"
              :title="'下载为 PNG'"
              @click="downloadChart"
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
                <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
                <polyline points="7 10 12 15 17 10" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
                <line x1="12" y1="15" x2="12" y2="3" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>
            <button class="chart-modal__close" @click="emit('close')" aria-label="关闭">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
              <path
                d="M18 6L6 18M6 6l12 12"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
              />
            </svg>
          </button>
        </div>
        </div>

        <!-- 图表容器 -->
        <div ref="chartContainer" class="chart-modal__chart" />

        <!-- 多图表分页 -->
        <div v-if="charts.length > 1" class="chart-modal__pagination">
          <button
            class="chart-modal__page-btn"
            :disabled="activeIndex === 0"
            @click="prevChart"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
              <path d="M15 18l-6-6 6-6" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </button>
          <span class="chart-modal__page-info">{{ activeIndex + 1 }} / {{ charts.length }}</span>
          <button
            class="chart-modal__page-btn"
            :disabled="activeIndex === charts.length - 1"
            @click="nextChart"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
              <path d="M9 18l6-6-6-6" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </button>
        </div>
        <div class="chart-modal__footer">
          <button
            v-if="messageId"
            class="chart-modal__locate"
            @click="handleLocate"
            title="定位到对话记录"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
              <circle cx="12" cy="12" r="8" stroke="currentColor" stroke-width="2" />
              <path d="M12 2v4M12 18v4M2 12h4M18 12h4" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
            </svg>
            定位到对话记录
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
/* 全屏遮罩 */
.chart-modal {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: grid;
  place-items: center;
  background: rgba(0, 0, 0, 0.3);
  backdrop-filter: blur(4px);
  padding: 24px;
}

/* 面板 */
.chart-modal__panel {
  width: min(92vw, 1100px);
  max-height: 90vh;
  background: var(--surface);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-lg, 40px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow:
    0 8px 60px rgba(0, 0, 0, 0.12),
    0 2px 20px rgba(0, 0, 0, 0.06);
}

/* 头部 */
.chart-modal__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 28px 0;
  flex-shrink: 0;
}

.chart-modal__footer {
  display: flex;
  justify-content: flex-end;
  padding: 0 28px 16px;
  flex-shrink: 0;
}

.chart-modal__locate {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 5px 14px;
  border-radius: 999px;
  border: 1px solid var(--border-soft);
  background: var(--surface-raised);
  color: var(--muted);
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.28s var(--ease-out-expo);
}

.chart-modal__locate:hover {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-glow-soft);
}

.chart-modal__locate svg {
  flex-shrink: 0;
}

.chart-modal__header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.chart-modal__download {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 1px solid var(--border-soft);
  background: var(--surface-raised);
  color: var(--muted);
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: background 0.2s, color 0.2s, border-color 0.2s;
}

.chart-modal__download:hover {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}

.chart-modal__title {
  font-size: 18px;
  font-weight: 600;
  color: var(--fg);
  letter-spacing: -0.01em;
}

.chart-modal__close {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 1px solid var(--border-soft);
  background: var(--surface-raised);
  color: var(--muted);
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: background 0.2s, color 0.2s;
}

.chart-modal__close:hover {
  background: var(--accent);
  color: #fff;
}

/* 图表容器 */
.chart-modal__chart {
  flex: 1;
  min-height: 400px;
  max-height: 68vh;
  margin: 16px 20px 12px;
  border-radius: 16px;
  overflow: hidden;
}

/* 分页 */
.chart-modal__pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 0 28px 20px;
  flex-shrink: 0;
}

.chart-modal__page-btn {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: 1px solid var(--border-soft);
  background: var(--surface-raised);
  color: var(--fg);
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: background 0.2s, border-color 0.2s;
}

.chart-modal__page-btn svg {
  display: block;
}

.chart-modal__page-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.chart-modal__page-btn:not(:disabled):hover {
  background: var(--accent);
  border-color: var(--accent);
}

.chart-modal__page-info {
  font-size: 14px;
  color: var(--muted);
  min-width: 48px;
  text-align: center;
}
</style>
