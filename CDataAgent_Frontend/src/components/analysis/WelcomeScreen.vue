<script setup lang="ts">
import LogoIcon from './LogoIcon.vue'

defineProps<{
  hasFiles: boolean
}>()
</script>

<template>
  <div class="welcome">
    <div class="welcome__icon">
      <LogoIcon :size="56" />
    </div>
    <h2 class="welcome__title">
      <span class="eye-c">
        C<span class="pupil" />
      </span>
      <span class="eye-d">
        D<span class="pupil" />
      </span>
      ata Agent
    </h2>
    <p v-if="!hasFiles" class="welcome__sub">
      上传 excel 文件，开始对话~
    </p>
    <p v-else class="welcome__sub">
      数据已就绪，输入你的分析需求开始对话
    </p>
  </div>
</template>

<style scoped>
.welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 60px 20px;
  text-align: center;
}

.welcome__icon {
  width: 80px;
  height: 80px;
  display: grid;
  place-items: center;
}

.welcome__title {
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--fg);
  margin: 0;
  display: flex;
  gap: 0;
}

/* ===== C / D 眼位容器 ===== */
.eye-c,
.eye-d {
  position: relative;
  display: inline-block;
}

/* ===== 眼珠 ===== */
.pupil {
  position: absolute;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--accent);
  animation: look-around 5s ease-in-out infinite;
}

.eye-c .pupil {
  /* C 的开口在右侧，眼珠偏右 */
  top: 43%;
  left: 58%;
}

.eye-d .pupil {
  /* D 的空腔在 stem 与 curve 之间，眼珠居中偏右 */
  top: 43%;
  left: 44%;
}

/* ===== 眼球动画 ===== */
@keyframes look-around {
  0%, 100% { transform: translate(0, 0); }
  25%      { transform: translate(2px, 0); }
  50%      { transform: translate(0, 0); }
  75%      { transform: translate(-2px, 0); }
}

@keyframes blink {
  0%, 44%, 56%, 100% { opacity: 1; }
  46%, 54%            { opacity: 0.08; }
}

.welcome__sub {
  font-size: 16px;
  color: var(--muted);
  margin: 0;
  max-width: 320px;
  line-height: 1.6;
}
</style>
