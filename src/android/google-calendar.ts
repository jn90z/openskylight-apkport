import { googleRequest } from './google-auth'

type Params = Record<string, unknown>
type Options = { headers?: Record<string, string> }

export function calendar({ auth }: { auth: { getAccessToken(retry?: boolean): Promise<string> } }) {
  async function call(path: string, method: string, params: Params, options?: Options) {
    const { requestBody, calendarId: _calendarId, eventId: _eventId, ...query } = params
    const search = new URLSearchParams()
    for (const [key, value] of Object.entries(query)) {
      if (value != null) search.set(key, String(value))
    }
    const url = `https://www.googleapis.com/calendar/v3/${path}${search.size ? `?${search}` : ''}`
    for (const retry of [false, true]) {
      try {
        const token = await auth.getAccessToken(retry)
        const data = await googleRequest(url, {
          method,
          headers: { ...options?.headers, Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
          body: requestBody === undefined ? undefined : JSON.stringify(requestBody)
        })
        return { data }
      } catch (err) {
        if (retry || (err as { status?: number }).status !== 401) throw err
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
