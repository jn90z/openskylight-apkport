import { afterEach, describe, expect, it, vi } from 'vitest'
import { googleRequest } from '../../src/android/google-auth'

afterEach(() => vi.unstubAllGlobals())

describe('Android Calendar API errors', () => {
  it('explains a disabled Calendar API and preserves the HTTP status', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false, status: 403,
      json: async () => ({ error: { details: [{ reason: 'SERVICE_DISABLED' }] } })
    }))
    await expect(googleRequest('https://www.googleapis.com/calendar/v3/users/me/calendarList'))
      .rejects.toMatchObject({ code: 'GOOGLE_API', status: 403, message: expect.stringContaining('Enable Google Calendar API') })
  })

  it('preserves an actionable provider error through the IPC AppError envelope', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false, status: 403,
      json: async () => ({ error: { message: 'Access denied for this account' } })
    }))
    await expect(googleRequest('https://www.googleapis.com/calendar/v3/users/me/calendarList'))
      .rejects.toMatchObject({ code: 'GOOGLE_API', message: 'Access denied for this account' })
  })
})
