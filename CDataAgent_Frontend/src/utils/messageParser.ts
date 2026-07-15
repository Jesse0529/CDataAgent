export interface FileAttachment {
  id: string
  name: string
}

/** Safely parse JSON received from persisted message fields. */
export function tryParseJson(raw: string): unknown | null {
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

/**
 * 解析图表 option。
 * 持久化字段使用数组；SSE `chart` 事件使用单个 option 对象，统一规范为数组供视图消费。
 */
export function parseChartOptions(raw?: string | null): Record<string, unknown>[] | undefined {
  if (!raw) return undefined

  const parsed = tryParseJson(raw)
  if (Array.isArray(parsed)) {
    return parsed.length > 0 ? (parsed as Record<string, unknown>[]) : undefined
  }
  if (parsed && typeof parsed === 'object') {
    return [parsed as Record<string, unknown>]
  }
  return undefined
}

/** Parse persisted user file attachments without throwing on malformed cache data. */
export function parseFileAttachments(raw?: string | null): FileAttachment[] | undefined {
  if (!raw) return undefined

  const parsed = tryParseJson(raw)
  return Array.isArray(parsed) && parsed.length > 0 ? (parsed as FileAttachment[]) : undefined
}

/** Get displayable attachment names for history views. */
export function parseFileNames(raw?: string | null): string[] {
  const attachments = parseFileAttachments(raw)
  if (!attachments) return []

  return attachments.map((file) => String(file.name || '')).filter(Boolean)
}
