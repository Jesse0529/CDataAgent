<template>
  <div class="block-notice" :class="`notice-${block.level}`">
    {{ block.text }}
  </div>
</template>

<script setup lang="ts">
import type { NoticeBlock } from '@/services/types'

defineProps<{ block: NoticeBlock }>()
</script>

<style scoped>
.block-notice { padding: 8px 16px; border-radius: 20px; font-size: 13px; margin: 8px 0; }
.notice-info { background: rgba(255,255,255,0.04); color: var(--muted); border: 1px solid var(--border-soft); }
.notice-warning { background: var(--accent-glow-soft); color: var(--accent-light); border: 1px solid var(--accent-glow); }
</style>
