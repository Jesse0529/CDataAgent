import type { GlobalThemeOverrides } from 'naive-ui'

/**
 * Taste Soft 浅色主题 → Naive UI 组件映射
 * 暖奶油色背景 + 暖深棕文字 + 橙陶土强调色
 */
export const tasteSoftThemeOverrides: GlobalThemeOverrides = {
  common: {
    // 背景
    bodyColor: '#FAF7F2',
    cardColor: '#FFFFFF',
    modalColor: '#FFFFFF',
    popoverColor: '#F5F0EA',
    tableColor: '#FFFFFF',
    inputColor: '#FFFFFF',
    actionColor: '#F5F0EA',

    // 文字
    textColor1: '#2D2824',
    textColor2: '#7A7268',
    textColor3: 'rgba(0,0,0,0.18)',

    // 主色
    primaryColor: '#BC694A',
    primaryColorHover: '#D4895E',
    primaryColorPressed: '#A05038',
    primaryColorSuppl: '#D4895E',

    // 边框
    borderColor: 'rgba(0,0,0,0.08)',
    dividerColor: 'rgba(0,0,0,0.04)',

    // 圆角
    borderRadius: '32px',
    borderRadiusSmall: '20px',

    // 投影
    boxShadow1: '0 2px 12px rgba(0,0,0,0.06)',
    boxShadow2: '0 4px 24px rgba(0,0,0,0.1)',
    boxShadow3: '0 8px 32px rgba(0,0,0,0.04)',

    // 字体
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Segoe UI', system-ui, sans-serif",
    fontSize: '15px',
    fontSizeSmall: '14px',
    fontSizeLarge: '18px',
    fontSizeHuge: '22px',

    // 基础色
    baseColor: '#FAF7F2',
    tableHeaderColor: '#F5F0EA',
    hoverColor: 'rgba(188,105,74,0.08)',
  },
  Button: {
    borderRadiusMedium: '999px',
    borderRadiusLarge: '999px',
  },
  Input: {
    borderRadius: '20px',
    color: '#FFFFFF',
    colorFocus: '#FFFFFF',
    border: 'rgba(0,0,0,0.08)',
    borderFocus: '#BC694A',
    borderHover: 'rgba(0,0,0,0.12)',
    textColor: '#2D2824',
    placeholderColor: '#7A7268',
  },
  Form: {
    labelTextColor: '#2D2824',
    feedbackTextColorError: '#BC694A',
  },
  Card: {
    borderRadius: '32px',
    borderColor: 'rgba(0,0,0,0.08)',
  },
}
