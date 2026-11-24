declare function androidHost(operation: string, json: string): string

export function host<T>(operation: string, payload: unknown = null): T {
  const json = JSON.stringify(payload).replace(/[^\x00-\x7f]/g, (char) =>
    `\\u${char.charCodeAt(0).toString(16).padStart(4, '0')}`)
  const result = JSON.parse(androidHost(operation, json))
  if (result.error) throw new Error(result.error)
  return result.data as T
}

const pending = new Map<number, { resolve: (data: unknown) => void; reject: (err: Error) => void }>()
export function request<T>(operation: string, payload: unknown): Promise<T> {
  return new Promise((resolve, reject) => {
    const id = host<number>(operation, payload)
    pending.set(id, { resolve: (data) => resolve(data as T), reject })
  })
}

export function completeRequest(id: number, data: unknown, error: string | null): void {
  const job = pending.get(id)
  if (!job) return
  pending.delete(id)
  if (error) job.reject(new Error(error))
  else job.resolve(data)
}
