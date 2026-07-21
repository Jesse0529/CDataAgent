<script setup lang="ts">
import { useMessage } from 'naive-ui'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import FileContextBar from '@/components/analysis/FileContextBar.vue'
import type { DataFileVO } from '@/services/types'

const message = useMessage()

const props = defineProps<{
  hasFiles: boolean
  fileCount: number
  selectedFileCount: number
  fileContextExpanded: boolean
  files: DataFileVO[]
  selectedFileIds: ReadonlySet<string>
  deletingAll?: boolean
  loading: boolean
  uploading: boolean
}>()

const emit = defineEmits<{
  (e: 'send', text: string): void
  (e: 'stop'): void
  (e: 'upload', files: File[]): void
  (e: 'toggle-file-context'): void
  (e: 'toggle-file', fileId: string): void
  (e: 'preview-file', file: DataFileVO): void
  (e: 'delete-file', fileId: string): void
  (e: 'delete-all-files'): void
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
const MAX_UPLOAD_FILE_SIZE = 100 * 1024 * 1024

function handleSend() {
  const trimmed = text.value.trim()
  if (!trimmed || props.loading) return
  emit('send', trimmed)
  text.value = ''
  nextTick(() => {
    if (textareaRef.value) {
      textareaRef.value.style.height = ''
      textareaRef.value.focus()
    }
  })
}

function focusTextarea() {
  textareaRef.value?.focus()
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

function toggleMenu() {
  if (props.uploading) return
  showMenu.value = !showMenu.value
  if (!showMenu.value) manageSubmenu.value = false
}

function closeMenu() {
  showMenu.value = false
  manageSubmenu.value = false
}

function handleMenuUpload() {
  closeMenu()
  fileInputRef.value?.click()
}

function openManageSubmenu() {
  if (props.loading) return
  manageSubmenu.value = true
}

function closeManageSubmenu() {
  manageSubmenu.value = false
}

function handleDeleteAllFiles(): void {
  emit('delete-all-files')
}

function handleClickOutside(e: MouseEvent) {
  if (!showMenu.value) return
  const el = attachWrapRef.value
  if (el && !el.contains(e.target as Node)) {
    closeMenu()
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

    if (file.size > MAX_UPLOAD_FILE_SIZE) {
      message.warning(`「${file.name}」超过 100MB 上限，已跳过`)
      continue
    }

    validFiles.push(file)
  }

  if (validFiles.length === 0) return

  emit('upload', validFiles)

  if (fileInputRef.value) {
    fileInputRef.value.value = ''
  }
  nextTick(focusTextarea)
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

watch(
  () => props.loading,
  (loading) => {
    if (loading && manageSubmenu.value) {
      manageSubmenu.value = false
    }
  },
)

defineExpose({ focusTextarea })
</script>

<template>
  <div class="chat-input">
    <div class="chat-input__inner">
      <div v-if="hasFiles" class="chat-input__file-panel">
        <FileContextBar
          :files="files"
          :selected-file-ids="selectedFileIds"
          :expanded="fileContextExpanded"
          :deleting-all="deletingAll"
          @toggle-file="emit('toggle-file', $event)"
          @preview-file="emit('preview-file', $event)"
          @delete-file="emit('delete-file', $event)"
          @delete-all-files="handleDeleteAllFiles"
        />
      </div>

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
        <div v-if="showMenu" class="chat-input__menu" @mouseleave="closeManageSubmenu">
          <!-- 主菜单（v-show 避免 DOM 重建导致点击检测时序问题） -->
          <div>
            <button class="chat-input__menu-item" @click="handleMenuUpload">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
                <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8l-6-6z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" />
                <path d="M14 2v4a2 2 0 002 2h4" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" />
                <path d="M12 18v-6M9 15h6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
              <span>上传 Excel 文件</span>
            </button>
            <div class="chat-input__menu-divider" />
            <button
              class="chat-input__menu-item"
              :disabled="loading"
              @mouseenter="openManageSubmenu"
              @focus="openManageSubmenu"
              @click="openManageSubmenu"
            >
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
          <!-- 管理子菜单 -->
          <div v-show="manageSubmenu" class="chat-input__submenu">
            <button
              class="chat-input__menu-item"
              :disabled="loading"
              @click="closeMenu(); emit('clear-conversation')"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M3 6h18M8 6V4a1 1 0 011-1h6a1 1 0 011 1v2M19 6l-1 14a2 2 0 01-2 2H8a2 2 0 01-2-2L5 6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
                <path d="M10 11v6M14 11v6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
              <span>清空聊天记录</span>
            </button>
            <button
              class="chat-input__menu-item"
              :disabled="loading"
              @click="closeMenu(); emit('reset-conversation')"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                <path d="M1 4v6h6M23 20v-6h-6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
                <path d="M20.49 9A9 9 0 005.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 013.51 15" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              <span>重置对话</span>
            </button>
          </div>
        </div>
      </div>

      <button
        v-if="hasFiles"
        class="chat-input__file-toggle"
        :class="{
          'chat-input__file-toggle--selected': selectedFileCount > 0,
          'chat-input__file-toggle--expanded': fileContextExpanded,
        }"
        type="button"
        :aria-expanded="fileContextExpanded"
        aria-controls="file-context-list"
        :title="fileContextExpanded ? '隐藏数据文件' : `展示数据文件：已加载 ${fileCount} 个，已选 ${selectedFileCount} 个`"
        :aria-label="fileContextExpanded ? '隐藏数据文件' : `展示数据文件：已加载 ${fileCount} 个，已选 ${selectedFileCount} 个`"
        @click="emit('toggle-file-context')"
      >
        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path v-if="fileContextExpanded" d="M3 3l18 18M10.6 6.1A10.5 10.5 0 0112 6c6.5 0 10 6 10 6a18.2 18.2 0 01-3.3 3.8M6.4 6.4C3.9 8.1 2 12 2 12s3.5 6 10 6c1.4 0 2.7-.3 3.8-.8M9.9 9.9a3 3 0 004.2 4.2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
          <path v-else d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
          <circle v-if="!fileContextExpanded" cx="12" cy="12" r="3" stroke="currentColor" stroke-width="1.8" />
        </svg>
      </button>

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
        rows="1"
        @keydown="handleKeydown"
        @input="autoResize"
      />
      <div class="chat-input__actions">
        <button
          v-if="loading"
          class="chat-input__stop"
          type="button"
          aria-label="停止生成"
          title="停止生成"
          @click="emit('stop')"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
            <rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" stroke="none" />
          </svg>
        </button>
        <button
          class="chat-input__send"
          type="button"
          :disabled="!text.trim() || loading"
          :aria-label="loading ? '当前回复结束后可发送' : '发送'"
          :title="loading ? '可继续输入，当前回复结束后再发送' : '发送'"
          @click="handleSend"
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <path d="M12 19V5M5 12l7-7 7 7" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-input {
  flex-shrink: 0;
  padding: 0 0 12px;
  margin-top: 12px;
}

.chat-input__inner {
  background: var(--surface);
  border-radius: 20px;
  border: 1px solid var(--border-soft);
  display: grid;
  grid-template-columns: auto auto minmax(0, 1fr) auto;
  grid-template-areas:
    "files-panel files-panel files-panel files-panel"
    "textarea textarea textarea textarea"
    "attach files . actions";
  align-items: center;
  column-gap: 6px;
  row-gap: 0;
  padding: 8px;
  transition: border-color 0.28s var(--ease-out-expo);
}

.chat-input__inner:focus-within {
  border-color: var(--accent);
}

.chat-input__file-input {
  display: none;
}

.chat-input__file-panel {
  grid-area: files-panel;
  min-width: 0;
}

.chat-input__attach {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 1px solid transparent;
  background: transparent;
  color: var(--muted);
  display: grid;
  place-items: center;
  cursor: pointer;
  transition: all 0.28s var(--ease-out-expo), transform 0.28s var(--spring);
}

.chat-input__attach:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
  background: transparent;
  transform: none;
}

.chat-input__attach:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

/* ===== 附件按钮包装（菜单定位锚点） ===== */
.chat-input__attach-wrap {
  grid-area: attach;
  position: relative;
}

.chat-input__file-toggle {
  grid-area: files;
  display: inline-grid;
  height: 36px;
  width: 36px;
  align-items: center;
  place-items: center;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 18px;
  background: transparent;
  color: var(--muted);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.2s var(--ease-out-expo), color 0.2s var(--ease-out-expo),
    background 0.2s var(--ease-out-expo);
}

.chat-input__file-toggle:hover {
  border-color: var(--accent);
  color: var(--accent);
  background: transparent;
}

.chat-input__file-toggle--selected {
  color: var(--accent);
}

.chat-input__file-toggle--expanded {
  background: transparent;
}

/* ===== 弹出菜单 ===== */
.chat-input__menu {
  position: absolute;
  bottom: calc(100% + 8px);
  left: 0;
  min-width: 176px;
  background: var(--surface-raised);
  border: 1px solid var(--border-soft);
  border-radius: 12px;
  padding: 4px;
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
  margin: 3px 6px;
}

.chat-input__submenu {
  position: absolute;
  right: auto;
  bottom: 0;
  left: calc(100% + 6px);
  min-width: 158px;
  padding: 4px;
  border: 1px solid var(--border-soft);
  border-radius: 12px;
  background: var(--surface-raised);
  box-shadow: 0 8px 28px rgb(0 0 0 / 18%);
  animation: submenu-enter 0.16s var(--ease-out-expo);
}

.chat-input__submenu::before {
  position: absolute;
  top: 0;
  right: 100%;
  bottom: 0;
  width: 6px;
  content: '';
}

@keyframes submenu-enter {
  from { opacity: 0; transform: translateX(-4px); }
  to { opacity: 1; transform: translateX(0); }
}

.chat-input__menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--fg);
  font-size: 13px;
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
  grid-area: textarea;
  width: 100%;
  background: transparent;
  border: none;
  outline: none;
  resize: none;
  overflow-y: auto;
  color: var(--fg);
  font-size: 15px;
  font-family: inherit;
  line-height: 24px;
  min-height: 46px;
  max-height: 96px;
  padding: 8px 6px;
}

.chat-input__textarea::placeholder {
  color: var(--muted);
}

.chat-input__actions {
  grid-area: actions;
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 4px;
}

.chat-input__send {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 1px solid transparent;
  background: transparent;
  color: var(--accent);
  display: grid;
  place-items: center;
  cursor: pointer;
  transition: transform 0.28s var(--spring), box-shadow 0.28s var(--ease-out-expo);
}

.chat-input__send:hover:not(:disabled) {
  border-color: var(--accent);
  transform: none;
  box-shadow: none;
}

.chat-input__send:active:not(:disabled) {
  transform: none;
}

.chat-input__send:disabled {
  background: transparent;
  color: var(--muted);
  cursor: not-allowed;
}

.chat-input__stop {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 1px solid transparent;
  border-radius: 50%;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  transition: border-color 0.2s var(--ease-out-expo), color 0.2s var(--ease-out-expo),
    background 0.2s var(--ease-out-expo);
}

.chat-input__stop:hover {
  border-color: var(--accent);
  background: transparent;
  color: var(--accent);
}

</style>
