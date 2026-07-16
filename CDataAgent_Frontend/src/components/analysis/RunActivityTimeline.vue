<template>
  <ol v-if="visible" class="run-activities" aria-label="执行进度">
    <li v-for="activity in completedActivities" :key="activity.id" class="run-activities__item">
      <span class="run-activities__marker" :class="`is-${activity.state}`" aria-hidden="true" />
      <span class="run-activities__label">
        {{ activity.label }}
        <span v-if="activity.count && activity.count > 1" class="run-activities__count">×{{ activity.count }}</span>
      </span>
    </li>
    <li v-if="activeLabel" class="run-activities__item run-activities__item--active">
      <span class="run-activities__marker is-running" aria-hidden="true" />
      <span class="run-activities__label">{{ activeLabel }}</span>
    </li>
  </ol>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { RunActivity } from '@/services/types'

const props = defineProps<{
  activities: RunActivity[]
  pendingLabel?: string
  streaming?: boolean
}>()

const completedActivities = computed(() =>
  props.activities.filter((activity) => activity.state !== 'running'),
)
const runningActivities = computed(() =>
  props.activities.filter((activity) => activity.state === 'running'),
)
const activeLabel = computed(() => {
  if (props.pendingLabel) return props.pendingLabel
  if (runningActivities.value.length === 1) return runningActivities.value[0].label
  if (runningActivities.value.length > 1) {
    const count = runningActivities.value.reduce((sum, activity) => sum + (activity.count ?? 1), 0)
    return `正在并行调用 ${count} 个工具`
  }
  return props.streaming ? '等待下一步' : undefined
})
const visible = computed(() => completedActivities.value.length > 0 || Boolean(activeLabel.value))
</script>

<style scoped>
.run-activities {
  display: grid;
  gap: 8px;
  margin: 0 0 14px;
  padding: 10px 12px;
  list-style: none;
  border: 1px solid var(--border-soft);
  border-radius: 12px;
  background: var(--surface-raised);
}

.run-activities__item { display: flex; min-height: 22px; align-items: center; gap: 9px; color: var(--muted); font-size: 14px; }
.run-activities__item--active { color: var(--fg); }
.run-activities__count { margin-left: 5px; color: var(--accent); font-size: 12px; font-weight: 700; font-variant-numeric: tabular-nums; }
.run-activities__marker { width: 7px; height: 7px; border-radius: 50%; background: var(--border-inner); flex: 0 0 auto; }
.run-activities__marker.is-running { background: var(--accent); animation: activity-pulse 1s ease-in-out infinite; }
.run-activities__marker.is-succeeded { background: #3aa675; }
.run-activities__marker.is-failed { background: #d46161; }
@keyframes activity-pulse { 50% { opacity: .38; transform: scale(.72); } }

@media (prefers-reduced-motion: reduce) {
  .run-activities__marker.is-running { animation: none; }
}
</style>
