<template>
  <p class="block-paragraph">{{ block.text }}</p>
</template>

<script setup lang="ts">
import type { ParagraphBlock } from '@/services/types'

defineProps<{ block: ParagraphBlock }>()
</script>

<style scoped>
.block-paragraph { color: var(--fg); line-height: 1.6; margin-bottom: 8px; white-space: pre-wrap; }
</style>
