import { afterEach, describe, expect, it, vi } from 'vitest'
import { useAgentRunRecovery } from './useAgentRunRecovery'

function installStorage(): Map<string, string> {
  const values = new Map<string, string>()
  vi.stubGlobal('sessionStorage', {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
  })
  return values
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('useAgentRunRecovery', () => {
  it('仅恢复完整的当前标签页运行游标', () => {
    installStorage()
    const recovery = useAgentRunRecovery()

    recovery.saveActiveRun({
      conversationId: '1',
      messageId: 'ai-1',
      runId: 'run-1',
      resumeToken: 'token-1',
      lastEventId: '12',
    })

    expect(recovery.loadActiveRun()).toEqual({
      conversationId: '1',
      messageId: 'ai-1',
      runId: 'run-1',
      resumeToken: 'token-1',
      lastEventId: '12',
    })
  })

  it('忽略损坏的恢复记录', () => {
    const storage = installStorage()
    storage.set('aibi:active-agent-run', '{bad-json')

    expect(useAgentRunRecovery().loadActiveRun()).toBeNull()
  })
})
