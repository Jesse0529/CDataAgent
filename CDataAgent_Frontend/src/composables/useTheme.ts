import type { GlobalThemeOverrides } from 'naive-ui'
import { computed } from 'vue'
import { tasteSoftThemeOverrides } from '@/styles/naive-theme'

/** Naive UI theme（浅色 = null，即不启用暗色主题） */
const naiveTheme = computed(() => null)

/** Naive UI 覆盖（始终使用 Taste Soft 浅色） */
const naiveThemeOverrides = computed<GlobalThemeOverrides>(() => tasteSoftThemeOverrides)

export function useTheme() {
  return {
    naiveTheme,
    naiveThemeOverrides,
  }
}
