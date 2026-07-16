<template>
  <div class="block-summary">
    <p class="block-text">{{ block.text }}</p>
  </div>
</template>

<script setup lang="ts">
import type { SummaryBlock } from '@/services/types'

defineProps<{ block: SummaryBlock }>()
</script>

<style scoped>
.block-summary { margin-bottom: 12px; }
.block-text { color: var(--fg); line-height: 1.6; white-space: pre-wrap; }
</style>
