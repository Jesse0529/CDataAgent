<template>
  <RichTextContent class="block-paragraph" :text="block.text" />
</template>

<script setup lang="ts">
import type { ParagraphBlock } from '@/services/types'
import RichTextContent from '../RichTextContent.vue'

defineProps<{ block: ParagraphBlock }>()
</script>

<style scoped>
.block-paragraph { margin-bottom: 8px; }
</style>
