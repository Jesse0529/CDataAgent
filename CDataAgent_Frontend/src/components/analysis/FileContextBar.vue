<script setup lang="ts">
import type { DataFileVO } from '@/services/types'

const props = defineProps<{
  files: DataFileVO[]
  selectedFileIds: ReadonlySet<string>
  expanded: boolean
}>()

const emit = defineEmits<{
  (e: 'toggle-file', fileId: string): void
  (e: 'preview-file', file: DataFileVO): void
  (e: 'delete-file', fileId: string): void
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
      </div>
    </section>
  </Transition>
</template>

<style scoped>
.file-context {
  position: absolute;
  right: var(--composer-gutter, 0px);
  bottom: calc(100% + 8px);
  left: var(--composer-gutter, 0px);
  z-index: 4;
  flex-shrink: 0;
  max-height: 270px;
  overflow: hidden;
  border: 1px solid var(--border-soft);
  border-radius: 16px;
  background: var(--surface);
  box-shadow: var(--shadow-card-hover);
  contain: layout paint;
  clip-path: inset(0 0 0 0);
  transform-origin: bottom center;
  will-change: clip-path, opacity, transform;
}

.file-context__body {
  max-height: 270px;
  overflow: auto;
  padding: 10px 12px;
  scrollbar-width: thin;
  scrollbar-color: var(--scrollbar-thumb) transparent;
}

.file-context__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 8px;
}

.file-context__item {
  display: flex;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--border-inner);
  border-radius: 12px;
  background: var(--surface-raised);
  transition: border-color 0.2s var(--ease-out-expo), background 0.2s var(--ease-out-expo);
}

.file-context__item:hover { border-color: var(--border-hover); }
.file-context__item--selected { border-color: var(--accent); background: var(--accent-glow-soft); }

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

.file-context__check { display: grid; flex-shrink: 0; color: var(--muted); }
.file-context__name { overflow: hidden; flex: 1; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.file-context__actions { display: flex; align-items: center; padding-right: 5px; }
.file-context__actions button {
  display: grid;
  width: 26px;
  height: 26px;
  place-items: center;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}
.file-context__actions button:hover { background: var(--surface); color: var(--accent); }
.file-context__actions button:last-child:hover { color: #c94f4f; }

.file-context-panel-enter-active,
.file-context-panel-leave-active {
  transition: clip-path 0.28s var(--ease-out-expo), opacity 0.2s ease,
    transform 0.28s var(--ease-out-expo);
}

.file-context-panel-enter-from,
.file-context-panel-leave-to {
  clip-path: inset(100% 0 0 0);
  opacity: 0;
  transform: translateY(8px);
}

@media (max-width: 640px) {
  .file-context { max-height: 224px; }
  .file-context__body { max-height: 224px; }
  .file-context__grid { grid-template-columns: 1fr; }
}

@media (prefers-reduced-motion: reduce) {
  .file-context-panel-enter-active,
  .file-context-panel-leave-active { transition: none; }
}
</style>
