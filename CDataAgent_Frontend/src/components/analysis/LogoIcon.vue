<script setup lang="ts">
defineProps<{
  size?: number
}>()
</script>

<template>
  <img
    class="logo-icon"
    src="/brand/cdata-agent-logo.png"
    :style="{ width: `${size ?? 32}px` }"
    alt=""
    aria-hidden="true"
  />
</template>

<style scoped>
.logo-icon {
  display: block;
  width: auto;
  max-width: 100%;
  height: auto;
  object-fit: contain;
}
</style>
