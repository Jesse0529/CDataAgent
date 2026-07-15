/**
 * RenderDocument 运行时类型守卫 — 拒绝非法格式、未知版本、字段不完整的文档。
 * @module
 */
import type { RenderBlock, RenderDocument } from '@/services/types'

/**
 * 验证 RenderDocument 是否合法（版本 + 结构 + 区块完整性）。
 * 用于 SSE 接收和持久化恢复时的安全校验。
 */
export function isValidRenderDocument(doc: unknown): doc is RenderDocument {
  if (!doc || typeof doc !== 'object') return false
  const d = doc as Record<string, unknown>
  if (d.version !== 1) return false
  if (typeof d.runId !== 'string' || !d.runId) return false
  if (!Array.isArray(d.blocks)) return false
  return d.blocks.every(isValidBlock)
}

/**
 * 验证单个区块是否合法。
 */
function isValidBlock(block: unknown): block is RenderBlock {
  if (!block || typeof block !== 'object') return false
  const b = block as Record<string, unknown>
  if (typeof b.id !== 'string' || !b.id) return false
  if (typeof b.type !== 'string') return false

  switch (b.type) {
    case 'summary':
    case 'paragraph':
      return typeof b.text === 'string'
    case 'bullets':
      return Array.isArray(b.items) && b.items.every((i) => typeof i === 'string')
    case 'table':
      return Array.isArray(b.headers) && Array.isArray(b.rows) && typeof b.totalRows === 'number'
    case 'chart':
      return typeof b.chartIndex === 'number'
    case 'notice':
      return (b.level === 'info' || b.level === 'warning') && typeof b.text === 'string'
    default:
      return false
  }
}

/**
 * 安全解析 RenderDocument — 非法文档返回 null，调用方走 legacy 渲染。
 */
export function safeParseRenderDocument(data: unknown): RenderDocument | null {
  if (isValidRenderDocument(data)) return data
  console.warn('[renderDocument] 非法文档已拒绝:', data)
  return null
}
