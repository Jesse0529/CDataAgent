/**
 * 文件数据预览弹窗 — 全屏弹窗展示 Parquet 文件的前 N 行数据。
 * 点击文件卡片预览图标触发，分页浏览。
 *
 * 设计规范对齐 ChartPreviewModal：
 *   遮罩   → --bg 60% opacity + backdrop-filter
 *   面板   → --surface，--radius-lg (40px)
 *   关闭   → ESC / 遮罩点击 / X 按钮
 *   分页   → 上一页 / 下一页
 */

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { apiGetChecked } from '@/services/api'
import type { FilePreviewVO } from '@/services/types'

const props = defineProps<{
  /** 文件 ID */
  fileId: string
  /** 文件名（用于标题展示） */
  fileName: string
  visible: boolean
}>()

const emit = defineEmits<(e: 'close') => void>()

// ---- 状态 ----
const modalRef = ref<HTMLDivElement | null>(null)
const preview = ref<FilePreviewVO | null>(null)
const loading = ref(false)
const loadingDir = ref<'prev' | 'next' | null>(null) // 换页方向，用于行动画方向
const page = ref(1)
const pageSize = 30

/** 列信息（从 columnMeta 解析的类型映射） */
const columnTypes = ref<Record<string, string>>({})

/** 格式化单元格值 */
function formatCellValue(val: unknown): string {
  if (val === null || val === undefined) return '—'
  if (typeof val === 'number') {
    return val % 1 === 0 ? val.toLocaleString() : val.toFixed(4)
  }
  return String(val)
}

/** 加载预览数据（loading 仅控制按钮禁用，不替换表格 DOM） */
async function loadPreview(p: number) {
  if (!props.fileId) return
  loading.value = true
  try {
    const data = await apiGetChecked<FilePreviewVO>(
      `/file/${props.fileId}/preview?page=${p}&size=${pageSize}`,
    )
    preview.value = data
    page.value = data.page
  } catch (e) {
    console.error('[FilePreviewModal] load error:', e)
    preview.value = null
  } finally {
    loading.value = false
    loadingDir.value = null
  }
}

function prevPage(): void {
  if (preview.value && page.value > 1 && !loading.value) {
    loadingDir.value = 'prev'
    loadPreview(page.value - 1)
  }
}

function nextPage(): void {
  if (preview.value?.hasMore && !loading.value) {
    loadingDir.value = 'next'
    loadPreview(page.value + 1)
  }
}

// ---- 弹窗控制 ----
function handleKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') emit('close')
}

function handleBackdropClick(e: MouseEvent): void {
  if (e.target === e.currentTarget) emit('close')
}

watch(
  () => props.visible,
  (v) => {
    if (v) {
      page.value = 1
      nextTick(() => loadPreview(1))
      document.addEventListener('keydown', handleKeydown)
    } else {
      preview.value = null
      document.removeEventListener('keydown', handleKeydown)
    }
  },
)

onMounted(() => {
  if (props.visible) {
    nextTick(() => {
      loadPreview(1)
      document.addEventListener('keydown', handleKeydown)
    })
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <Teleport to="body">
    <div
      v-if="visible"
      ref="modalRef"
      class="preview-modal"
      @click="handleBackdropClick"
    >
      <div class="preview-modal__panel">
        <!-- 头部 -->
        <div class="preview-modal__header">
          <div class="preview-modal__header-left">
            <span class="preview-modal__title">数据预览</span>
            <span class="preview-modal__filename">{{ fileName }}</span>
          </div>
          <div class="preview-modal__header-right">
            <span v-if="preview" class="preview-modal__info">
              {{ preview.totalRows }} 行 · {{ preview.headers.length }} 列
            </span>
            <button class="preview-modal__close" @click="emit('close')" aria-label="关闭">
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

        <!-- 空状态（仅在首次打开且无数据时） -->
        <div v-if="!preview" class="preview-modal__empty">
          暂无数据
        </div>

        <!-- 数据表格（常驻，换页时保持 DOM 不销毁） -->
        <div v-else class="preview-modal__table-wrap">
          <table class="preview-modal__table">
            <thead>
              <tr>
                <th class="preview-modal__row-num">#</th>
                <th v-for="(h, hi) in preview.headers" :key="hi">
                  {{ h }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(row, ri) in preview.rows"
                :key="page * pageSize + ri"
                class="preview-modal__row"
                :style="{ animationDelay: `${ri * 0.02}s` }"
              >
                <td class="preview-modal__row-num">{{ (page - 1) * pageSize + ri + 1 }}</td>
                <td v-for="(cell, ci) in row" :key="ci">{{ formatCellValue(cell) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 底部：分页 -->
        <div v-if="preview" class="preview-modal__footer">
          <div class="preview-modal__pagination">
            <button
              class="preview-modal__page-btn"
              :disabled="page <= 1 || loading"
              @click="prevPage"
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
                <path d="M15 18l-6-6 6-6" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              上一页
            </button>
            <span class="preview-modal__page-info">{{ page }} / {{ Math.ceil(preview.totalRows / pageSize) }}</span>
            <button
              class="preview-modal__page-btn"
              :disabled="!preview.hasMore || loading"
              @click="nextPage"
            >
              下一页
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
                <path d="M9 18l6-6-6-6" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
/* 全屏遮罩 */
.preview-modal {
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
.preview-modal__panel {
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
.preview-modal__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 28px 0;
  flex-shrink: 0;
}

.preview-modal__header-left {
  display: flex;
  align-items: baseline;
  gap: 12px;
  min-width: 0;
}

.preview-modal__header-right {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
}

.preview-modal__title {
  font-size: 18px;
  font-weight: 600;
  color: var(--fg);
  letter-spacing: -0.01em;
}

.preview-modal__filename {
  font-size: 14px;
  color: var(--muted);
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-modal__info {
  font-size: 13px;
  color: var(--muted);
  white-space: nowrap;
}

.preview-modal__close {
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

.preview-modal__close:hover {
  background: var(--accent);
  color: #fff;
}

/* 空状态 */
.preview-modal__empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--muted);
  font-size: 14px;
}

/* 表格容器 */
.preview-modal__table-wrap {
  flex: 1;
  overflow: auto;
  margin: 16px 20px 12px;
  border: 1px solid var(--border-soft);
  border-radius: 12px;
}

/* 数据表格 */
.preview-modal__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.preview-modal__table thead {
  position: sticky;
  top: 0;
  z-index: 1;
}

.preview-modal__table thead th {
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

.preview-modal__table tbody td {
  padding: 6px 12px;
  color: var(--fg);
  border: 1px solid var(--border-soft);
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-modal__table tbody tr:nth-child(even) {
  background: var(--surface-raised);
}

.preview-modal__table tbody tr:hover {
  background: var(--accent-glow-soft);
}

.preview-modal__row-num {
  color: var(--dim-text) !important;
  width: 48px;
  min-width: 48px;
  text-align: center !important;
  font-size: 12px;
}

/* 行淡入动画（换页时新数据逐行滑入，不闪白） */
.preview-modal__row {
  animation: row-fade-in 0.22s ease-out both;
}

@keyframes row-fade-in {
  from {
    opacity: 0;
    transform: translateY(-3px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 底部 */
.preview-modal__footer {
  display: flex;
  justify-content: center;
  padding: 0 28px 16px;
  flex-shrink: 0;
}

.preview-modal__pagination {
  display: flex;
  align-items: center;
  gap: 16px;
}

.preview-modal__page-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 16px;
  border-radius: 999px;
  border: 1px solid var(--border-soft);
  background: var(--surface-raised);
  color: var(--fg);
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.2s, border-color 0.2s;
}

.preview-modal__page-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.preview-modal__page-btn:not(:disabled):hover {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}

.preview-modal__page-info {
  font-size: 14px;
  color: var(--muted);
  min-width: 60px;
  text-align: center;
}
</style>
