import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiPost, apiPostStream, apiUpload } from './api'

const encoder = new TextEncoder()

function sseResponse(...chunks: string[]): Response {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
      controller.close()
    },
  })

  return new Response(stream, {
    headers: { 'content-type': 'text/event-stream' },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('apiPostStream', () => {
  it('支持被分片传输的 CRLF SSE 事件，并在流结束时完成', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          sseResponse('event: status\r\ndata: analyzing\r\n\r', '\ndata: hello\r\n\r\n'),
        ),
    )
    const tokens: string[] = []
    const statuses: string[] = []

    const { promise } = apiPostStream(
      '/agent/chat/stream',
      {},
      (token) => tokens.push(token),
      undefined,
      (status) => statuses.push(status),
    )

    await expect(promise).resolves.toEqual({ status: 'completed' })
    expect(statuses).toEqual(['analyzing'])
    expect(tokens).toEqual(['hello'])
  })

  it('将后端 error 事件作为失败抛出，而不是伪装为完成', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(sseResponse('event: error\ndata: upstream failed\n\n')),
    )

    const { promise } = apiPostStream('/agent/chat/stream', {}, () => {})

    await expect(promise).rejects.toThrow('upstream failed')
  })

  it('用户主动取消时返回 aborted', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (_input: RequestInfo | URL, init?: RequestInit) =>
          new Promise<Response>((_resolve, reject) => {
            init?.signal?.addEventListener('abort', () => {
              reject(new DOMException('aborted', 'AbortError'))
            })
          }),
      ),
    )

    const stream = apiPostStream('/agent/chat/stream', {}, () => {})
    stream.abort()

    await expect(stream.promise).resolves.toEqual({ status: 'aborted' })
  })
})

describe('HTTP request client', () => {
  it('以 JSON 请求体发送常规 POST 并返回统一响应', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ code: 0, data: { id: '1' }, message: '' }), {
        headers: { 'content-type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiPost<{ id: string }>('/example', { name: 'demo' })).resolves.toEqual({
      code: 0,
      data: { id: '1' },
      message: '',
    })
    expect(fetchMock).toHaveBeenCalledWith(
      '/apis/example',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ name: 'demo' }),
        headers: { 'Content-Type': 'application/json' },
      }),
    )
  })

  it('上传 FormData 时不覆盖浏览器生成的 multipart 边界', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ code: 0, data: true, message: '' }), {
        headers: { 'content-type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const formData = new FormData()
    formData.append('file', 'content')

    await apiUpload<boolean>('/upload', formData)

    expect(fetchMock).toHaveBeenCalledWith(
      '/apis/upload',
      expect.objectContaining({ method: 'POST', body: formData, headers: undefined }),
    )
  })
})
