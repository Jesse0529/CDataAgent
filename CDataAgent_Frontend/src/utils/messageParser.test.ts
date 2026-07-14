import { describe, expect, it } from 'vitest'
import {
  parseChartOptions,
  parseFileAttachments,
  parseFileNames,
  tryParseJson,
} from './messageParser'

describe('messageParser', () => {
  it('parses persisted chart options and rejects empty or malformed values', () => {
    expect(parseChartOptions('[{"series": []}]')).toEqual([{ series: [] }])
    expect(parseChartOptions('[]')).toBeUndefined()
    expect(parseChartOptions('{invalid')).toBeUndefined()
  })

  it('parses attachments once for both message and history views', () => {
    const raw = '[{"id":"file-1","name":"sales.csv"},{"id":"file-2","name":42}]'

    expect(parseFileAttachments(raw)).toEqual([
      { id: 'file-1', name: 'sales.csv' },
      { id: 'file-2', name: 42 },
    ])
    expect(parseFileNames(raw)).toEqual(['sales.csv', '42'])
    expect(tryParseJson('invalid')).toBeNull()
  })
})
