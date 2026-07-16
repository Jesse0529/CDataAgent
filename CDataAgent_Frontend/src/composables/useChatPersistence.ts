/**
 * 对话持久化 — localStorage 封装
 *
 * 单对话模式，全局存储消息用于刷新恢复。
 * key: aibi:chat
 */

import type { ChatMessageVO } from '@/services/types'

const STORAGE_KEY = 'aibi:chat'
const MAX_MESSAGES = 100
const MAX_STORAGE_BYTES = 1_500_000

function getStorageBytes(value: string): number {
  return new TextEncoder().encode(value).byteLength
}

export function useChatPersistence() {
  function loadMessages(): ChatMessageVO[] {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      if (!raw) return []
      const parsed = JSON.parse(raw) as ChatMessageVO[]
      return parsed.filter((m) => m.status !== 'loading')
    } catch {
      return []
    }
  }

  function saveMessages(messages: ChatMessageVO[]): void {
    try {
      const toSave = messages.filter((m) => m.status !== 'loading').slice(-MAX_MESSAGES)
      if (toSave.length === 0) {
        localStorage.removeItem(STORAGE_KEY)
        return
      }

      while (toSave.length > 1) {
        const serialized = JSON.stringify(toSave)
        if (getStorageBytes(serialized) <= MAX_STORAGE_BYTES) {
          localStorage.setItem(STORAGE_KEY, serialized)
          return
        }
        toSave.shift()
      }

      const serialized = JSON.stringify(toSave)
      if (getStorageBytes(serialized) <= MAX_STORAGE_BYTES) {
        localStorage.setItem(STORAGE_KEY, serialized)
      } else {
        localStorage.removeItem(STORAGE_KEY)
      }
    } catch {
      // localStorage 满或不可用，静默忽略
    }
  }

  function clearMessages(): void {
    localStorage.removeItem(STORAGE_KEY)
  }

  return {
    loadMessages,
    saveMessages,
    clearMessages,
  }
}
