<template>
  <div class="block-chart" @click="$emit('openChart', block.chartIndex)">
    <button class="chart-btn">📊 查看图表</button>
  </div>
</template>

<script setup lang="ts">
import type { ChartBlock } from '@/services/types'

defineProps<{ block: ChartBlock }>()
defineEmits<{ openChart: [index: number] }>()
</script>

<style scoped>
.block-chart { margin: 12px 0; }
.chart-btn {
  background: var(--surface-raised); color: var(--accent);
  border: 1px solid var(--accent-glow); border-radius: 999px;
  padding: 8px 20px; cursor: pointer; font-size: 14px;
  transition: background 0.28s var(--ease-out-expo);
}
.chart-btn:hover { background: var(--accent-glow); }
</style>
