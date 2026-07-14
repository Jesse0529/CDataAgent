import { type Ref, ref } from 'vue'
import { apiDeleteChecked, apiGetChecked, apiUpload, withRetry } from '@/services/api'
import type { DataFileVO } from '@/services/types'

const MAX_FILES = 8
const SELECTED_FILES_KEY = 'aibi:selectedFiles'

export interface UploadFilesResult {
  addedCount: number
  skippedCount: number
}

function loadSelectedFiles(): Set<string> {
  try {
    const raw = localStorage.getItem(SELECTED_FILES_KEY)
    if (!raw) return new Set()
    const ids = JSON.parse(raw)
    return Array.isArray(ids) ? new Set(ids) : new Set()
  } catch {
    return new Set()
  }
}

export function useFiles(conversationId: Ref<string | null>) {
  const files = ref<DataFileVO[]>([])
  const fetchingFiles = ref(true)
  const uploading = ref(false)
  const selectedFileIds = ref<Set<string>>(loadSelectedFiles())

  function saveSelectedFiles(): void {
    try {
      localStorage.setItem(SELECTED_FILES_KEY, JSON.stringify([...selectedFileIds.value]))
    } catch {
      // Ignore unavailable or full browser storage.
    }
  }

  function toggleFile(fileId: string): void {
    const next = new Set(selectedFileIds.value)
    next.has(fileId) ? next.delete(fileId) : next.add(fileId)
    selectedFileIds.value = next
    saveSelectedFiles()
  }

  async function fetchFiles(): Promise<void> {
    const id = conversationId.value
    if (!id) {
      files.value = []
      fetchingFiles.value = false
      return
    }

    fetchingFiles.value = true
    try {
      files.value =
        (await withRetry(() => apiGetChecked<DataFileVO[]>(`/file/list?conversationId=${id}`))) ||
        []
    } catch (error: unknown) {
      console.warn('[useFiles] fetchFiles failed', error)
    } finally {
      fetchingFiles.value = false
    }
  }

  async function uploadFiles(incoming: File[]): Promise<UploadFilesResult> {
    const id = conversationId.value
    if (!id) throw new Error('请等待对话初始化完成')
    if (files.value.length + incoming.length > MAX_FILES) {
      throw new Error(`最多同时上传 ${MAX_FILES} 个文件，当前已 ${files.value.length} 个`)
    }

    uploading.value = true
    try {
      const formData = new FormData()
      for (const file of incoming) formData.append('files', file)

      const response = await apiUpload<DataFileVO[]>(
        `/file/upload?conversationId=${id}&replaceIfExists=false`,
        formData,
      )
      if (response.code !== 0) throw new Error(response.message || '上传失败')

      const existingNames = new Set(files.value.map((file) => file.originalFilename))
      const uploaded = response.data || []
      const added = uploaded.filter((file) => !existingNames.has(file.originalFilename))
      files.value = [...files.value, ...added]

      const nextSelected = new Set(selectedFileIds.value)
      for (const file of added) nextSelected.add(file.id)
      selectedFileIds.value = nextSelected
      saveSelectedFiles()

      return { addedCount: added.length, skippedCount: uploaded.length - added.length }
    } finally {
      uploading.value = false
    }
  }

  async function deleteFile(fileId: string): Promise<void> {
    await withRetry(() => apiDeleteChecked<boolean>(`/file/${fileId}`))
    files.value = files.value.filter((file) => file.id !== fileId)

    const nextSelected = new Set(selectedFileIds.value)
    nextSelected.delete(fileId)
    selectedFileIds.value = nextSelected
    saveSelectedFiles()
  }

  return {
    files,
    fetchingFiles,
    uploading,
    selectedFileIds,
    toggleFile,
    fetchFiles,
    uploadFiles,
    deleteFile,
  }
}
