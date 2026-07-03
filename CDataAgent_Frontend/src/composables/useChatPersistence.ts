/**
 * 对话持久化 — localStorage 封装
 *
 * 单对话模式，全局存储消息用于刷新恢复。
 * key: aibi:chat
 */

import type { ChatMessageVO } from '@/services/types'

const STORAGE_KEY = 'aibi:chat'

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
      const toSave = messages.filter((m) => m.status !== 'loading')
      if (toSave.length === 0) {
        localStorage.removeItem(STORAGE_KEY)
        return
      }
      localStorage.setItem(STORAGE_KEY, JSON.stringify(toSave))
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
