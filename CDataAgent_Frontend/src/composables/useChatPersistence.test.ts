import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessageVO } from '@/services/types'
import { useChatPersistence } from './useChatPersistence'

function message(id: number, content = `message-${id}`): ChatMessageVO {
  return {
    id: String(id),
    role: 'ai',
    content,
    timestamp: id,
    status: 'done',
  }
}

function installStorage(): void {
  const values = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('useChatPersistence', () => {
  it('只保留最近 100 条可恢复消息', () => {
    installStorage()
    const persistence = useChatPersistence()

    persistence.saveMessages(Array.from({ length: 101 }, (_, index) => message(index)))

    const saved = persistence.loadMessages()
    expect(saved).toHaveLength(100)
    expect(saved[0]?.id).toBe('1')
    expect(saved[saved.length - 1]?.id).toBe('100')
  })

  it('超过容量上限时优先淘汰最早消息', () => {
    installStorage()
    const persistence = useChatPersistence()
    const largeContent = 'x'.repeat(800_000)

    persistence.saveMessages([message(1, largeContent), message(2, largeContent)])

    const saved = persistence.loadMessages()
    expect(saved).toHaveLength(1)
    expect(saved[0]?.id).toBe('2')
  })
})
