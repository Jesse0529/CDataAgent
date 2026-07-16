<script setup lang="ts">
import LogoIcon from './LogoIcon.vue'

defineProps<{
  hasFiles: boolean
}>()
</script>

<template>
  <div class="welcome">
    <div class="welcome__mark" aria-hidden="true">
      <LogoIcon :size="100" />
    </div>
    <h1 class="welcome__title">CData <span>Agent</span></h1>
    <p v-if="!hasFiles" class="welcome__sub">
      上传 Excel 或 CSV，然后用自然语言探索你的数据。
    </p>
  </div>
</template>

<style scoped>
.welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 100%;
  padding: 72px 20px 112px;
  text-align: center;
}

.welcome__mark {
  width: 140px;
  height: 90px;
  display: grid;
  place-items: center;
  margin-bottom: 4px;
  filter: drop-shadow(0 10px 20px rgba(188, 105, 74, 0.14));
}

.welcome__title {
  color: var(--fg);
  font-size: clamp(30px, 3vw, 38px);
  font-weight: 750;
  letter-spacing: -0.045em;
  line-height: 1.15;
  margin: 0;
}

.welcome__title span {
  color: var(--accent);
  font-weight: 600;
}

.welcome__sub {
  max-width: 360px;
  color: var(--muted);
  font-size: 15px;
  line-height: 1.7;
  margin: 2px 0 0;
}

@media (max-width: 640px) {
  .welcome {
    padding-top: 56px;
  }

  .welcome__mark {
    width: 110px;
    height: 72px;
  }

  .welcome__sub {
    max-width: 300px;
    font-size: 14px;
  }
}
</style>
