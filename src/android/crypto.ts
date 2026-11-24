import { Buffer } from 'buffer'
import { host } from './host'

export function randomBytes(size: number): Buffer {
  return Buffer.from(host<string>('random', size), 'base64')
}

export function scryptSync(value: string, salt: Uint8Array, length: number): Buffer {
  return Buffer.from(host<string>('scrypt', [value, Buffer.from(salt).toString('base64'), length]), 'base64')
}

export function createHash(algorithm: string) {
  if (algorithm !== 'sha256') throw new Error('Unsupported hash')
  let input = ''
  return {
    update(value: string) {
      input += value
      return this
    },
    digest(encoding?: 'hex') {
      const bytes = Buffer.from(host<string>('sha256', input), 'base64')
      return encoding ? bytes.toString(encoding) : bytes
    }
  }
}

export function timingSafeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false
  let difference = 0
  for (let i = 0; i < a.length; i++) difference |= a[i] ^ b[i]
  return difference === 0
}

export function base64url(value: Uint8Array): string {
  return Buffer.from(value).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
