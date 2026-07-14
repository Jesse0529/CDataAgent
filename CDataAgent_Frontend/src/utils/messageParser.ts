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

/** Parse persisted ECharts option arrays while rejecting empty or invalid values. */
export function parseChartOptions(raw?: string | null): Record<string, unknown>[] | undefined {
  if (!raw) return undefined

  const parsed = tryParseJson(raw)
  return Array.isArray(parsed) && parsed.length > 0
    ? (parsed as Record<string, unknown>[])
    : undefined
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
