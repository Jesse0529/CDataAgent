<template>
  <TransitionGroup v-if="visible" name="activity" tag="ol" class="run-activities" aria-label="执行进度">
    <li v-for="activity in activities" :key="activity.id" class="run-activities__item">
      <span class="run-activities__marker" :class="`is-${activity.state}`" aria-hidden="true" />
      <span class="run-activities__label">{{ activity.label }}</span>
    </li>
    <li v-if="pendingLabel" key="pending" class="run-activities__item run-activities__item--pending">
      <span class="run-activities__marker is-running" aria-hidden="true" />
      <span class="run-activities__label">{{ pendingLabel }}</span>
    </li>
  </TransitionGroup>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { RunActivity } from '@/services/types'

const props = defineProps<{
  activities: RunActivity[]
  pendingLabel?: string
}>()

const visible = computed(() => props.activities.length > 0 || Boolean(props.pendingLabel))
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

.run-activities__item { display: flex; align-items: center; gap: 9px; color: var(--muted); font-size: 14px; }
.run-activities__item--pending { color: var(--fg); }
.run-activities__marker { width: 7px; height: 7px; border-radius: 50%; background: var(--border-inner); flex: 0 0 auto; }
.run-activities__marker.is-running { background: var(--accent); animation: activity-pulse 1s ease-in-out infinite; }
.run-activities__marker.is-succeeded { background: #3aa675; }
.run-activities__marker.is-failed { background: #d46161; }
@keyframes activity-pulse { 50% { opacity: .38; transform: scale(.72); } }
.activity-enter-active,
.activity-leave-active { transition: opacity 180ms ease-out, transform 180ms var(--ease-out-expo); }
.activity-enter-from,
.activity-leave-to { opacity: 0; transform: translateY(5px); }

@media (prefers-reduced-motion: reduce) {
  .activity-enter-active,
  .activity-leave-active { transition: none; }
}
</style>
