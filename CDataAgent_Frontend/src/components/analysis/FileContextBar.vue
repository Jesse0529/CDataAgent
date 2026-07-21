<script setup lang="ts">
import type { DataFileVO } from '@/services/types'

const props = defineProps<{
  files: DataFileVO[]
  selectedFileIds: ReadonlySet<string>
  expanded: boolean
  deletingAll?: boolean
}>()

const emit = defineEmits<{
  (e: 'toggle-file', fileId: string): void
  (e: 'preview-file', file: DataFileVO): void
  (e: 'delete-file', fileId: string): void
  (e: 'delete-all-files'): void
}>()

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
</script>

<template>
  <Transition appear name="file-context-panel">
    <section v-if="files.length > 0" v-show="expanded" class="file-context">
      <div id="file-context-list" class="file-context__body">
        <div class="file-context__grid">
          <article
            v-for="file in files"
            :key="file.id"
            class="file-context__item"
            :class="{ 'file-context__item--selected': selectedFileIds.has(file.id) }"
          >
            <button
              class="file-context__select"
              type="button"
              role="checkbox"
              :aria-checked="selectedFileIds.has(file.id)"
              :title="`${file.originalFilename}，${formatSize(file.fileSize)}，${file.rowCount.toLocaleString()} 行`"
              @click="emit('toggle-file', file.id)"
            >
              <span class="file-context__check" aria-hidden="true">
                <svg v-if="selectedFileIds.has(file.id)" width="16" height="16" viewBox="0 0 24 24" fill="none">
                  <circle cx="12" cy="12" r="10" fill="var(--accent)" />
                  <path d="M16 8l-6.5 7.5L6 12" stroke="#fff" stroke-width="2.8" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none">
                  <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.8" />
                </svg>
              </span>
              <span class="file-context__name">{{ file.originalFilename }}</span>
            </button>
            <div class="file-context__actions">
              <button type="button" title="预览数据" aria-label="预览数据" @click="emit('preview-file', file)">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                  <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" stroke="currentColor" stroke-width="1.8" />
                  <circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="1.8" />
                </svg>
              </button>
              <button type="button" :aria-label="`删除 ${file.originalFilename}`" title="删除文件" @click="emit('delete-file', file.id)">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
                  <path d="M18 6L6 18M6 6l12 12" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" />
                </svg>
              </button>
            </div>
          </article>
        </div>
        <button
          class="file-context__delete-all"
          type="button"
          :disabled="deletingAll"
          aria-label="清空文件"
          title="清空"
          @click="emit('delete-all-files')"
        >
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
      </div>
    </section>
  </Transition>
</template>

<style scoped>
.file-context {
  min-width: 0;
  max-height: 184px;
  margin-bottom: 6px;
  overflow: hidden;
  border-radius: 13px;
  background: transparent;
  transform-origin: bottom center;
  will-change: opacity, transform;
}

.file-context__body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 34px;
  max-height: 184px;
  overflow: auto;
  gap: 8px;
  padding: 6px;
  scrollbar-width: thin;
  scrollbar-color: var(--scrollbar-thumb) transparent;
}

.file-context__delete-all {
  display: grid;
  width: 34px;
  height: 34px;
  align-self: end;
  place-items: center;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  color: #b65b5b;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  transition: background-color 160ms ease, color 160ms ease;
}

.file-context__delete-all:hover:not(:disabled) {
  border-color: currentColor;
  color: #c94f4f;
}

.file-context__delete-all:focus-visible {
  outline: 2px solid #c94f4f;
  outline-offset: 2px;
}

.file-context__delete-all:disabled {
  cursor: wait;
  opacity: 0.62;
}

.file-context__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.file-context__item {
  display: flex;
  min-width: 0;
  overflow: hidden;
  border: 1px solid transparent;
  border-radius: 12px;
  background: transparent;
  transition: border-color 0.2s var(--ease-out-expo);
}

.file-context__item--selected { border-color: transparent; background: transparent; }

.file-context__select {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: center;
  gap: 7px;
  padding: 8px 5px 8px 9px;
  border: 0;
  background: transparent;
  color: var(--fg);
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.file-context__select:hover { box-shadow: inset 0 0 0 1px var(--border-hover); border-radius: 9px; }
.file-context__select:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; border-radius: 9px; }

.file-context__check { display: grid; flex-shrink: 0; color: var(--muted); }
.file-context__name { overflow: hidden; flex: 1; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.file-context__actions { display: flex; align-items: center; padding-right: 5px; }
.file-context__actions button {
  display: grid;
  width: 26px;
  height: 26px;
  place-items: center;
  border: 1px solid transparent;
  border-radius: 7px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}
.file-context__actions button:hover { border-color: currentColor; color: var(--accent); }
.file-context__actions button:last-child:hover { color: #c94f4f; }

.file-context-panel-enter-active,
.file-context-panel-leave-active {
  transition: max-height 0.28s var(--ease-out-expo), opacity 0.2s ease,
    transform 0.28s var(--ease-out-expo), margin 0.28s var(--ease-out-expo);
}

.file-context-panel-enter-from,
.file-context-panel-leave-to {
  max-height: 0;
  margin-bottom: 0;
  opacity: 0;
  transform: translateY(4px);
}

@media (max-width: 640px) {
  .file-context { max-height: 148px; }
  .file-context__body { max-height: 148px; }
}

@media (prefers-reduced-motion: reduce) {
  .file-context-panel-enter-active,
  .file-context-panel-leave-active { transition: none; }
}
</style>
