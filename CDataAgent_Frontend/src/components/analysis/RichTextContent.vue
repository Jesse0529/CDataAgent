<template>
  <div class="rich-text-content">
    <template v-for="segment in segments" :key="segment.key">
      <div v-if="segment.type === 'text'" v-html="segment.html" />
      <div v-else-if="segment.headers" class="rich-table-wrap">
        <table>
          <thead>
            <tr><th v-for="(header, index) in segment.headers" :key="index">{{ header }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="(row, rowIndex) in segment.rows" :key="rowIndex">
              <td v-for="(cell, cellIndex) in row" :key="cellIndex" v-html="renderCellContent(cell)" />
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  type ContentSegment,
  parseContentSegments,
  renderCellContent,
} from '@/utils/messageRenderer'

const props = defineProps<{
  text?: string
  html?: string
}>()

const segments = computed<ContentSegment[]>(() => {
  if (props.html !== undefined) return [{ type: 'text', key: 0, html: props.html }]
  return parseContentSegments(props.text ?? '')
})
</script>

<style scoped>
.rich-text-content { min-width: 0; max-width: 100%; color: var(--fg); line-height: 1.6; overflow-wrap: anywhere; }
.rich-text-content :deep(p) { margin: 0 0 8px; }
.rich-text-content :deep(p:last-child) { margin-bottom: 0; }
.rich-text-content :deep(h1),
.rich-text-content :deep(h2),
.rich-text-content :deep(h3),
.rich-text-content :deep(h4) { margin: 16px 0 8px; color: var(--fg); font-weight: 600; letter-spacing: -0.01em; }
.rich-text-content :deep(h1) { font-size: 18px; border-bottom: 1px solid var(--border-soft); padding-bottom: 6px; }
.rich-text-content :deep(h2) { font-size: 17px; border-bottom: 1px solid var(--border-soft); padding-bottom: 4px; }
.rich-text-content :deep(h3) { font-size: 16px; }
.rich-text-content :deep(h4) { font-size: 15px; }
.rich-text-content :deep(hr) { margin: 16px 0; border: 0; border-top: 1px solid var(--border-soft); }
.rich-text-content :deep(ul),
.rich-text-content :deep(ol) { margin: 6px 0; padding-left: 1.5em; }
.rich-text-content :deep(li) { margin: 3px 0; }
.rich-text-content :deep(code) { padding: 2px 6px; border-radius: 4px; background: var(--surface-raised); color: var(--accent-light); font-family: 'SF Mono', 'Fira Code', 'Cascadia Code', monospace; overflow-wrap: anywhere; }
.rich-text-content :deep(pre) { max-width: 100%; margin: 12px 0; padding: 12px 16px; overflow-x: auto; border: 1px solid var(--border-inner); border-radius: 10px; background: var(--surface-raised); }
.rich-text-content :deep(pre code) { padding: 0; background: transparent; color: var(--fg); white-space: pre-wrap; }
.rich-text-content :deep(blockquote) { margin: 12px 0; padding: 6px 12px; border-left: 3px solid var(--accent); background: var(--surface-raised); color: var(--muted); }
.rich-text-content :deep(a) { color: var(--accent); text-decoration: none; }
.rich-text-content :deep(a:hover) { text-decoration: underline; }
.rich-table-wrap { max-width: 100%; margin: 12px 0; overflow-x: auto; border: 1px solid var(--border-soft); border-radius: 10px; background: var(--surface); }
.rich-table-wrap table { width: max-content; min-width: 100%; border-collapse: collapse; }
.rich-table-wrap th,
.rich-table-wrap td { padding: 8px 12px; border: 1px solid var(--border-soft); text-align: left; vertical-align: top; overflow-wrap: anywhere; }
.rich-table-wrap th { background: var(--accent); color: #fff; white-space: nowrap; }
.rich-table-wrap tr:nth-child(even) { background: var(--surface-raised); }
</style>
