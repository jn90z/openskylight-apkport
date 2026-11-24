import './encoding'
import '@formatjs/intl-getcanonicallocales/polyfill.js'
import '@formatjs/intl-locale/polyfill.js'
import '@formatjs/intl-pluralrules/polyfill-force.js'
import '@formatjs/intl-pluralrules/locale-data/en.js'
import '@formatjs/intl-numberformat/polyfill-force.js'
import '@formatjs/intl-numberformat/locale-data/en.js'
import '@formatjs/intl-datetimeformat/polyfill-force.js'
import '@formatjs/intl-datetimeformat/locale-data/en.js'
import '@formatjs/intl-datetimeformat/add-all-tz.js'
import { URL, URLSearchParams } from 'whatwg-url'
import { randomBytes } from './crypto'
import { host, request } from './host'

const zone = host<string>('timezone')
const formatter = Intl.DateTimeFormat as typeof Intl.DateTimeFormat & { __setDefaultTimeZone(zone: string): void }
try {
  formatter.__setDefaultTimeZone(zone)
} catch {
  formatter.__setDefaultTimeZone('UTC')
}

type NetworkResult = { status: number; body: string; headers: Record<string, string> }
async function fetchNative(url: string | URL, options?: RequestInit) {
  const { status, body, headers } = await request<NetworkResult>('fetch', {
    url: String(url), method: options?.method, headers: options?.headers, body: options?.body,
    timeout: (options?.signal as unknown as { timeout?: number })?.timeout
  })
  return {
    status, ok: status >= 200 && status < 300, text: async () => body, json: async () => JSON.parse(body),
    headers: { get: (key: string) => headers[key.toLowerCase()] ?? null }
  }
}

const callbacks = new Map<number, () => void>()
function schedule(callback: () => void, delay: number, repeat: boolean): number {
  const id = host<number>('timer', [delay, repeat])
  callbacks.set(id, repeat ? callback : () => {
    callbacks.delete(id)
    callback()
  })
  return id
}

function cancel(id: number): void {
  callbacks.delete(id)
  host('cancelTimer', id)
}

export function fireTimer(id: number): void {
  callbacks.get(id)?.()
}

Object.assign(globalThis, {
  URL, URLSearchParams,
  fetch: fetchNative,
  AbortSignal: { timeout: (timeout: number) => ({ timeout }) },
  crypto: { getRandomValues: (bytes: Uint8Array) => { bytes.set(randomBytes(bytes.length)); return bytes } },
  setTimeout: (callback: () => void, delay: number) => schedule(callback, delay, false),
  setInterval: (callback: () => void, delay: number) => schedule(callback, delay, true),
  clearTimeout: cancel,
  clearInterval: cancel,
  console: {
    // Provider errors may carry URLs or authorization details.
    error: (message: unknown) => host('log', String(message)),
    warn: (message: unknown) => host('log', String(message))
  }
})
