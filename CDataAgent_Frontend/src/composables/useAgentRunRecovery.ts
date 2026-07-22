const STORAGE_KEY = 'aibi:active-agent-run'

export interface ActiveAgentRun {
  conversationId: string
  messageId: string
  runId: string
  resumeToken: string
  lastEventId: string
}

function isActiveAgentRun(value: unknown): value is ActiveAgentRun {
  if (!value || typeof value !== 'object') return false
  const run = value as Record<string, unknown>
  return [run.conversationId, run.messageId, run.runId, run.resumeToken, run.lastEventId].every(
    (item) => typeof item === 'string' && item.length > 0,
  )
}

/**
 * 仅保存当前标签页可恢复的运行游标。
 * 恢复凭据不进入 localStorage，关闭标签页后自然失效。
 */
export function useAgentRunRecovery() {
  function loadActiveRun(): ActiveAgentRun | null {
    try {
      const raw = sessionStorage.getItem(STORAGE_KEY)
      if (!raw) return null
      const parsed: unknown = JSON.parse(raw)
      return isActiveAgentRun(parsed) ? parsed : null
    } catch {
      return null
    }
  }

  function saveActiveRun(run: ActiveAgentRun): void {
    try {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(run))
    } catch {
      // sessionStorage 不可用时仅失去刷新恢复能力，不影响本次对话。
    }
  }

  function clearActiveRun(): void {
    try {
      sessionStorage.removeItem(STORAGE_KEY)
    } catch {
      // ignore
    }
  }

  return { loadActiveRun, saveActiveRun, clearActiveRun }
}
