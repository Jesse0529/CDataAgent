<script setup lang="ts">
import { useMessage } from 'naive-ui'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const message = useMessage()

const props = defineProps<{
  hasFiles: boolean
  loading: boolean
  uploading: boolean
}>()

const emit = defineEmits<{
  (e: 'send', text: string): void
  (e: 'stop'): void
  (e: 'upload', files: File[]): void
  (e: 'clear-conversation'): void
  (e: 'reset-conversation'): void
}>()

const text = ref('')
const textareaRef = ref<HTMLTextAreaElement | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)
const attachWrapRef = ref<HTMLDivElement | null>(null)
const showMenu = ref(false)
/** true → 显示对话管理子菜单，false → 显示主菜单 */
const manageSubmenu = ref(false)

function handleSend() {
  const trimmed = text.value.trim()
  if (!trimmed || props.loading) return
  emit('send', trimmed)
  text.value = ''
  nextTick(() => {
    if (textareaRef.value) {
      textareaRef.value.style.height = ''
    }
  })
}

watch(
  () => props.loading,
  (loading) => {
    if (!loading) {
      nextTick(() => {
        textareaRef.value?.focus()
      })
    }
  },
)

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

function toggleMenu() {
  if (props.uploading) return
  showMenu.value = !showMenu.value
  if (!showMenu.value) {
    manageSubmenu.value = false // 关闭菜单时重置子菜单状态
  }
}

function handleMenuUpload() {
  showMenu.value = false
  manageSubmenu.value = false
  fileInputRef.value?.click()
}

function handleManageClick(e: MouseEvent) {
  e.stopPropagation()
  manageSubmenu.value = true
}

function handleBackClick(e: MouseEvent) {
  e.stopPropagation()
  manageSubmenu.value = false
}

function handleClickOutside(e: MouseEvent) {
  if (!showMenu.value) return
  const el = attachWrapRef.value
  if (el && !el.contains(e.target as Node)) {
    showMenu.value = false
  }
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', handleClickOutside)
})

function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const fileList = input.files
  if (!fileList || fileList.length === 0) return

  const allowed = [
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    'application/vnd.ms-excel',
    'text/csv',
  ]
  const validFiles: File[] = []

  for (const file of Array.from(fileList)) {
    const ext = file.name.split('.').pop()?.toLowerCase()
    const extOk = ext === 'xlsx' || ext === 'xls' || ext === 'csv'

    if (!allowed.includes(file.type) && !extOk) {
      message.warning(`「${file.name}」格式不支持，仅支持 .xlsx / .xls / .csv`)
      continue
    }

    if (file.size > 60 * 1024 * 1024) {
      message.warning(`「${file.name}」超过 60MB 上限，已跳过`)
      continue
    }

    validFiles.push(file)
  }

  if (validFiles.length === 0) return

  emit('upload', validFiles)

  if (fileInputRef.value) {
    fileInputRef.value.value = ''
  }
}

function autoResize() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  const maxH = 24 * 3 + 12
  el.style.height = `${Math.min(el.scrollHeight, maxH)}px`
}

watch(text, () => {
  nextTick(autoResize)
})
</script>

<template>
  <div class="chat-input">
    <div class="chat-input__inner">
      <div ref="attachWrapRef" class="chat-input__attach-wrap">
        <button
          class="chat-input__attach"
          :disabled="uploading"
          :title="uploading ? '上传中…' : '更多操作'"
          :aria-label="uploading ? '上传中' : '更多操作'"
          @click="toggleMenu"
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
          </svg>
        </button>

        <!-- 弹出菜单 -->
        <div v-if="showMenu" class="chat-input__menu">
          <!-- 主菜单（v-show 避免 DOM 重建导致点击检测时序问题） -->
          <div v-show="!manageSubmenu">
            <button class="chat-input__menu-item" @click="handleMenuUpload">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
                <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8l-6-6z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" />
                <path d="M14 2v4a2 2 0 002 2h4" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" />
                <path d="M12 18v-6M9 15h6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
              <span>上传 Excel 文件</span>
            </button>
            <div class="chat-input__menu-divider" />
            <button class="chat-input__menu-item" @click="handleManageClick">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
                <path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2v10z" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
                <path d="M12 8v4M12 16h.01" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
              <span>对话管理</span>
              <svg class="menu-item-arrow" width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M9 6l6 6-6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>
          </div>
          <!-- 管理子菜单（v-show 避免 DOM 重建） -->
          <div v-show="manageSubmenu">
            <button class="chat-input__menu-item" @click="handleBackClick">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M15 6l-6 6 6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              <span>返回</span>
            </button>
            <div class="chat-input__menu-divider" />
            <button class="chat-input__menu-item" @click="showMenu = false; manageSubmenu = false; emit('clear-conversation')">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M3 6h18M8 6V4a1 1 0 011-1h6a1 1 0 011 1v2M19 6l-1 14a2 2 0 01-2 2H8a2 2 0 01-2-2L5 6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
                <path d="M10 11v6M14 11v6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
              <span>清空聊天记录</span>
            </button>
            <button class="chat-input__menu-item" @click="showMenu = false; manageSubmenu = false; emit('reset-conversation')">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M1 4v6h6M23 20v-6h-6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
                <path d="M20.49 9A9 9 0 005.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 013.51 15" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              <span>重置对话</span>
            </button>
          </div>
        </div>
      </div>

      <input
        ref="fileInputRef"
        type="file"
        multiple
        accept=".xlsx,.xls,.csv"
        class="chat-input__file-input"
        @change="handleFileChange"
      />

      <textarea
        ref="textareaRef"
        v-model="text"
        class="chat-input__textarea"
        :placeholder="hasFiles ? '输入你的分析需求...' : '输入你的问题，或上传文件开始数据分析...'"
        :disabled="loading"
        rows="1"
        @keydown="handleKeydown"
        @input="autoResize"
      />
      <button
        class="chat-input__send"
        :disabled="!loading && !text.trim()"
        :aria-label="loading ? '停止生成' : '发送'"
        @click="loading ? emit('stop') : handleSend()"
      >
        <svg v-if="!loading" width="20" height="20" viewBox="0 0 24 24" fill="none">
          <path d="M5 12h14M12 5l7 7-7 7" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="none">
          <rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" stroke="none" />
        </svg>
      </button>
    </div>
    <div class="chat-input__hint">
      Shift + Enter 换行
    </div>
  </div>
</template>

<style scoped>
.chat-input {
  flex-shrink: 0;
  padding: 0 0 8px;
}

.chat-input__inner {
  background: var(--surface);
  border-radius: 20px;
  border: 1px solid var(--border-soft);
  display: flex;
  align-items: flex-end;
  gap: 6px;
  padding: 5px 6px 5px 10px;
  transition: border-color 0.28s var(--ease-out-expo);
}

.chat-input__inner:focus-within {
  border-color: var(--accent);
}

.chat-input__file-input {
  display: none;
}

.chat-input__attach {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 1px solid var(--border-soft);
  background: var(--surface-raised);
  color: var(--muted);
  display: grid;
  place-items: center;
  cursor: pointer;
  transition: all 0.28s var(--ease-out-expo), transform 0.28s var(--spring);
}

.chat-input__attach:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-glow-soft);
  transform: scale(1.08);
}

.chat-input__attach:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

/* ===== 附件按钮包装（菜单定位锚点） ===== */
.chat-input__attach-wrap {
  position: relative;
}

/* ===== 弹出菜单 ===== */
.chat-input__menu {
  position: absolute;
  bottom: calc(100% + 8px);
  left: 0;
  min-width: 200px;
  background: var(--surface-raised);
  border: 1px solid var(--border-soft);
  border-radius: 16px;
  padding: 6px;
  box-shadow:
    0 4px 24px rgba(0, 0, 0, 0.45),
    0 12px 48px rgba(0, 0, 0, 0.3);
  z-index: 200;
  animation: menu-enter 0.2s var(--ease-out-expo);
  transform-origin: bottom left;
}

@keyframes menu-enter {
  from {
    opacity: 0;
    transform: scale(0.92) translateY(4px);
  }
  to {
    opacity: 1;
    transform: scale(1) translateY(0);
  }
}

.chat-input__menu-divider {
  height: 1px;
  background: var(--border-inner);
  margin: 4px 8px;
}

.chat-input__menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 14px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--fg);
  font-size: 14px;
  font-weight: 500;
  font-family: inherit;
  line-height: 1.4;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.2s var(--ease-out-expo);
}

.chat-input__menu-item:hover {
  background: var(--accent-glow-soft);
  color: var(--accent);
}

.chat-input__menu-item svg {
  flex-shrink: 0;
  color: var(--muted);
  transition: color 0.2s var(--ease-out-expo);
}

.chat-input__menu-item:hover svg {
  color: var(--accent);
}

.menu-item-arrow {
  margin-left: auto;
  opacity: 0.4;
}

.chat-input__textarea {
  flex: 1;
  background: transparent;
  border: none;
  outline: none;
  resize: none;
  overflow-y: auto;
  color: var(--fg);
  font-size: 15px;
  font-family: inherit;
  line-height: 24px;
  min-height: 36px;
  max-height: 84px;
  padding: 6px 0;
}

.chat-input__textarea::placeholder {
  color: var(--muted);
}

.chat-input__textarea:disabled {
  cursor: not-allowed;
}

.chat-input__send {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: none;
  background: var(--accent);
  color: #fff;
  display: grid;
  place-items: center;
  cursor: pointer;
  transition: transform 0.28s var(--spring), box-shadow 0.28s var(--ease-out-expo);
}

.chat-input__send:hover:not(:disabled) {
  transform: scale(1.1);
  box-shadow: 0 4px 20px var(--accent-glow);
}

.chat-input__send:active:not(:disabled) {
  transform: scale(0.95);
}

.chat-input__send:disabled {
  background: var(--surface-raised);
  color: var(--muted);
  cursor: not-allowed;
}

.chat-input__hint {
  font-size: 12px;
  color: var(--dim-text);
  margin-top: 4px;
  padding-right: 4px;
}
</style>
