import { googleRequest } from './google-auth'
import { AppError } from '../main/services/errors'

type Params = Record<string, unknown>
type Options = { headers?: Record<string, string> }

export function calendar({ auth }: { auth: { getAccessToken(retry?: boolean): Promise<string> } }) {
  async function call(path: string, method: string, params: Params, options?: Options) {
    const { requestBody, calendarId: _calendarId, eventId: _eventId, ...query } = params
    const search = new URLSearchParams()
    for (const [key, value] of Object.entries(query)) {
      if (value != null) search.set(key, String(value))
    }
    const queryString = search.toString()
    const url = `https://www.googleapis.com/calendar/v3/${path}${queryString ? `?${queryString}` : ''}`
    const listing = path === 'users/me/calendarList'
    for (const retry of [false, true]) {
      let stage = 'authorization'
      try {
        const token = await auth.getAccessToken(retry)
        stage = 'HTTP request or response parsing'
        if (listing) console.warn('[google] Calendar list request started')
        const data = await googleRequest(url, {
          method,
          headers: { ...options?.headers, Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
          body: requestBody === undefined ? undefined : JSON.stringify(requestBody)
        })
        if (listing) {
          const result = data as { items?: unknown[] } | null
          if (!result || typeof result !== 'object' || (result.items !== undefined && !Array.isArray(result.items))) {
            throw new AppError('GOOGLE_API', 'Google returned an invalid calendar list response.')
          }
          console.warn(`[google] Calendar list received: ${result.items?.length ?? 0} items`)
        }
        return { data }
      } catch (err) {
        const status = (err as { status?: number })?.status
        if (listing) console.warn(`[google] Calendar list failed during ${stage}; HTTP status: ${typeof status === 'number' ? status : 'unavailable'}; error type: ${err instanceof TypeError ? 'TypeError' : err instanceof ReferenceError ? 'ReferenceError' : err instanceof AppError ? 'AppError' : 'other'}`)
        if (retry || status !== 401) {
          if (listing && !(err instanceof AppError)) {
            throw new AppError('GOOGLE_API', `Could not load Google calendars during ${stage}. Check the network and retry.`)
          }
          throw err
        }
      }
    }
    throw new Error('Google request did not complete')
  }

  const eventsPath = (params: Params) => `calendars/${encodeURIComponent(String(params.calendarId))}/events`
  const eventPath = (params: Params) => `${eventsPath(params)}/${encodeURIComponent(String(params.eventId))}`
  return {
    calendarList: { list: (params: Params) => call('users/me/calendarList', 'GET', params) },
    events: {
      list: (params: Params) => call(eventsPath(params), 'GET', params),
      insert: (params: Params) => call(eventsPath(params), 'POST', params),
      get: (params: Params) => call(eventPath(params), 'GET', params),
      patch: (params: Params, options?: Options) => call(eventPath(params), 'PATCH', params, options),
      delete: (params: Params) => call(eventPath(params), 'DELETE', params)
    }
  }
}
