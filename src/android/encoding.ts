import { Buffer } from 'buffer'

// QuickJS provides the ECMAScript built-ins but leaves Web platform APIs to its host.
Object.assign(globalThis, {
  Buffer,
  TextEncoder: class {
    encode(text = '') { return Uint8Array.from(Buffer.from(text, 'utf8')) }
  },
  TextDecoder: class {
    decode(bytes: Uint8Array) { return Buffer.from(bytes).toString('utf8') }
  }
})
