import { afterEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { useFiles } from './useFiles'

function installStorage(): Map<string, string> {
  const values = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
  })
  return values
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('useFiles', () => {
  it('persists selected file IDs without mutating the existing set', () => {
    const storage = installStorage()
    const { selectedFileIds, toggleFile } = useFiles(ref('conversation-1'))
    const initialSet = selectedFileIds.value

    toggleFile('file-1')

    expect(selectedFileIds.value).not.toBe(initialSet)
    expect([...selectedFileIds.value]).toEqual(['file-1'])
    expect(storage.get('aibi:selectedFiles')).toBe('["file-1"]')

    toggleFile('file-1')
    expect([...selectedFileIds.value]).toEqual([])
  })

  it('starts with an empty selection when persisted data is malformed', () => {
    const storage = installStorage()
    storage.set('aibi:selectedFiles', '{invalid')

    const { selectedFileIds } = useFiles(ref('conversation-1'))

    expect([...selectedFileIds.value]).toEqual([])
  })
})
