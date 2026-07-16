<template>
  <section class="block-table">
    <div v-if="block.title" class="block-table__title">{{ block.title }}</div>
    <div class="block-table__scroll">
      <table>
        <thead><tr><th v-for="header in block.headers" :key="header">{{ header }}</th></tr></thead>
        <tbody>
          <tr v-for="(row, rowIndex) in visibleRows" :key="rowIndex">
            <td v-for="header in block.headers" :key="header">{{ row[header] ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <footer v-if="hasMoreRows || block.totalRows > block.rows.length" class="block-table__footer">
      <p class="table-hint">
        预览 {{ visibleRows.length }} / {{ block.rows.length }} 行<span v-if="block.totalRows > block.rows.length">，数据共 {{ block.totalRows }} 行</span>
      </p>
      <button
        v-if="hasMoreRows"
        ref="openButton"
        class="block-table__view"
        type="button"
        aria-label="查看表格"
        title="查看表格"
        @click="openTable"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M8 3H5a2 2 0 0 0-2 2v3M16 3h3a2 2 0 0 1 2 2v3M21 16v3a2 2 0 0 1-2 2h-3M3 16v3a2 2 0 0 0 2 2h3" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" />
        </svg>
      </button>
    </footer>
  </section>

  <Teleport to="body">
    <div v-if="showFullTable" class="table-modal" @click="handleBackdropClick">
      <section class="table-modal__panel" role="dialog" aria-modal="true" :aria-label="modalTitle">
        <header class="table-modal__header">
          <div>
            <h2>{{ modalTitle }}</h2>
            <p>已加载 {{ block.rows.length }} 行<span v-if="block.totalRows > block.rows.length">，数据共 {{ block.totalRows }} 行</span></p>
          </div>
          <button class="table-modal__close" type="button" aria-label="关闭表格" @click="closeTable">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M18 6 6 18M6 6l12 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
            </svg>
          </button>
        </header>
        <div class="table-modal__content">
          <table>
            <thead><tr><th v-for="header in block.headers" :key="header">{{ header }}</th></tr></thead>
            <tbody>
              <tr v-for="(row, rowIndex) in block.rows" :key="rowIndex">
                <td v-for="header in block.headers" :key="header">{{ row[header] ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { DataTableBlock } from '@/services/types'

const props = defineProps<{ block: DataTableBlock }>()

const PREVIEW_ROW_LIMIT = 10
const showFullTable = ref(false)
const openButton = ref<HTMLButtonElement | null>(null)
const hasMoreRows = computed(() => props.block.rows.length > PREVIEW_ROW_LIMIT)
const visibleRows = computed(() => props.block.rows.slice(0, PREVIEW_ROW_LIMIT))
const modalTitle = computed(() => props.block.title || '表格数据')

function openTable(): void {
  showFullTable.value = true
}

function closeTable(): void {
  showFullTable.value = false
  nextTick(() => openButton.value?.focus())
}

function handleBackdropClick(event: MouseEvent): void {
  if (event.target === event.currentTarget) closeTable()
}

function handleKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') closeTable()
}

watch(showFullTable, (visible) => {
  if (visible) document.addEventListener('keydown', handleKeydown)
  else document.removeEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => document.removeEventListener('keydown', handleKeydown))
</script>

<style scoped>
.block-table {
  margin: 14px 0;
  overflow: hidden;
  border: 1px solid var(--border-soft);
  border-radius: 14px;
  background: var(--surface);
  box-shadow: 0 8px 24px rgb(0 0 0 / 4%);
}

.block-table__title {
  padding: 12px 14px 8px;
  color: var(--fg);
  font-size: 14px;
  font-weight: 600;
}

.block-table__scroll { overflow-x: auto; }

table {
  width: 100%;
  min-width: max-content;
  border-collapse: separate;
  border-spacing: 0;
  color: var(--fg);
  font-size: 13px;
}

th,
td {
  min-width: 104px;
  padding: 7px 12px;
  text-align: left;
  vertical-align: middle;
  border-bottom: 1px solid var(--border-soft);
}

th {
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--surface-raised);
  color: var(--accent);
  font-size: 12px;
  font-weight: 650;
  letter-spacing: 0.02em;
  white-space: nowrap;
}

tbody tr { transition: background-color 160ms ease; }
tbody tr:nth-child(even) { background: var(--surface-raised); }
tbody tr:hover { background: var(--accent-glow-soft); }
tbody tr:last-child td { border-bottom: 0; }
td { max-width: 320px; overflow-wrap: anywhere; }

.block-table__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 14px;
  border-top: 1px solid var(--border-soft);
}

.table-hint {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
}

.block-table__view {
  flex-shrink: 0;
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border: 0;
  border-radius: 8px;
  padding: 0;
  background: transparent;
  color: rgb(188 105 74);
  cursor: pointer;
  transition: color 160ms ease, transform 160ms ease;
}

.block-table__view:hover {
  color: var(--accent-light);
  transform: translateY(-1px);
}

.block-table__view:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
</style>

<style>
.table-modal {
  position: fixed;
  z-index: 1100;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgb(45 40 36 / 48%);
  backdrop-filter: blur(3px);
}

.table-modal__panel {
  display: flex;
  width: min(1120px, 100%);
  max-height: min(760px, calc(100vh - 48px));
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--border-soft);
  border-radius: 20px;
  background: var(--surface);
  box-shadow: 0 24px 80px rgb(45 40 36 / 24%);
}

.table-modal__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-soft);
}

.table-modal__header h2 {
  margin: 0;
  color: var(--fg);
  font-size: 16px;
  font-weight: 650;
}

.table-modal__header p {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 12px;
}

.table-modal__close {
  display: grid;
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid var(--border-soft);
  border-radius: 9px;
  background: var(--surface);
  color: var(--muted);
  cursor: pointer;
}

.table-modal__close:hover {
  border-color: var(--accent);
  background: var(--accent-glow-soft);
  color: var(--accent);
}

.table-modal__content { overflow: auto; }
.table-modal__content table { min-width: 100%; }
.table-modal__content th {
  top: 0;
  background: var(--surface-raised);
}

@media (max-width: 640px) {
  .table-modal { padding: 12px; }
  .table-modal__panel { max-height: calc(100vh - 24px); border-radius: 16px; }
  .table-modal__header { padding: 14px 16px; }
}
</style>
