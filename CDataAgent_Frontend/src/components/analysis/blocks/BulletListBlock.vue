<template>
  <ul class="block-bullets">
    <li v-for="(item, i) in block.items" :key="i">{{ item }}</li>
  </ul>
</template>

<script setup lang="ts">
import type { BulletListBlock } from '@/services/types'

defineProps<{ block: BulletListBlock }>()
</script>

<style scoped>
.block-bullets { color: var(--fg); padding-left: 20px; margin: 8px 0; }
.block-bullets li { line-height: 1.6; margin-bottom: 4px; }
</style>
