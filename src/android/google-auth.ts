import { and, eq, isNull } from 'drizzle-orm'
import { DateTime } from 'luxon'
import { uuidv7 } from '@shared/uuid'
import { isoUtc } from '@shared/dates'
import type { AppDb } from '../main/db/client'
import { calendars, events, googleAccounts } from '../main/db/schema'
import { AppError, notFound } from '../main/services/errors'
import { host, request } from './host'

const SCOPE = 'https://www.googleapis.com/auth/calendar'

export async function googleRequest<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, options)
  if (!response.ok) {
    let message = `Google Calendar returned HTTP ${response.status}`
    try {
      const body = await response.json() as {
        error?: { message?: string; errors?: { reason?: string }[]; details?: { reason?: string }[] }
      }
      const reasons = [
        ...(body.error?.errors ?? []),
        ...(body.error?.details ?? [])
      ].map((entry) => entry.reason)
      if (reasons.some((reason) => ['accessNotConfigured', 'SERVICE_DISABLED'].includes(reason ?? ''))) {
        message = 'Enable Google Calendar API in the Google Cloud project containing this app’s Android OAuth client, then retry.'
      } else if (response.status === 401) {
        message = 'Google authorization expired. Reconnect this account in Settings.'
      } else if (response.status === 403 && reasons.includes('insufficientPermissions')) {
        message = 'Google Calendar permission is missing. Reconnect this account and grant Calendar access.'
      } else if (body.error?.message) {
        message = body.error.message.slice(0, 500)
      }
    } catch {
      // Keep the HTTP status when the provider response is not JSON.
    }
    throw Object.assign(new AppError('GOOGLE_API', message), { status: response.status })
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>
}

type Authorization = { email: string; token: string }

export function createAndroidGoogleAuth(db: AppDb) {
  let connecting = false
  const access = new Map<string, { token: string; expiresAt: number }>()
  const refreshing = new Map<string, Promise<string>>()
  const configured = () => host<boolean>('googleAvailable')

  function markAuthError(accountId: string, message: string | null): void {
    db.update(googleAccounts).set({ lastRefreshError: message }).where(eq(googleAccounts.id, accountId)).run()
  }

  function remember(accountId: string, token: string): string {
    // Google Play services manages its cache and retrieves fresh access tokens.
    access.set(accountId, { token, expiresAt: Date.now() + 5 * 60_000 })
    return token
  }

  async function connect(): Promise<{ email: string }> {
    if (!configured()) throw new AppError('UNAVAILABLE', 'Update or enable Google Play services to connect Calendar')
    if (connecting) throw new AppError('BUSY', 'A Google sign-in is already in progress')
    connecting = true
    try {
      let grant: Authorization
      try {
        grant = await request<Authorization>('googleConnect', { scope: SCOPE })
      } catch (error) {
        throw new AppError('AUTH_DENIED', (error as Error).message)
      }
      const { email, token } = grant
      const [existing] = db.select().from(googleAccounts).where(eq(googleAccounts.email, email)).all()
      const id = existing?.id ?? uuidv7()
      const patch = { refreshTokenEnc: null, connectedAt: isoUtc(DateTime.utc()), lastRefreshError: null }
      if (existing) db.update(googleAccounts).set(patch).where(eq(googleAccounts.id, id)).run()
      else db.insert(googleAccounts).values({ id, email, scopes: SCOPE, ...patch }).run()
      remember(id, token)
      return { email }
    } finally {
      connecting = false
    }
  }

  async function refresh(accountId: string, expiredToken?: string): Promise<string> {
    const [account] = db.select().from(googleAccounts).where(eq(googleAccounts.id, accountId)).all()
    if (!account) throw notFound('Google account')
    let token: string
    try {
      const grant = await request<Authorization>('googleToken', { scope: SCOPE, email: account.email, expiredToken })
      token = grant.token
    } catch (error) {
      const message = (error as Error).message
      markAuthError(accountId, message)
      throw Object.assign(new AppError('REAUTH_REQUIRED', message), { status: 401 })
    }
    if (!db.select({ id: googleAccounts.id }).from(googleAccounts).where(eq(googleAccounts.id, accountId)).get()) {
      throw notFound('Google account')
    }
    markAuthError(accountId, null)
    return remember(accountId, token)
  }

  async function getAccessToken(accountId: string, retry = false): Promise<string> {
    const cached = access.get(accountId)
    if (!retry && cached && cached.expiresAt > Date.now()) return cached.token
    const inflight = refreshing.get(accountId)
    if (inflight) return inflight
    const promise = refresh(accountId, retry ? cached?.token : undefined)
    refreshing.set(accountId, promise)
    try {
      return await promise
    } finally {
      refreshing.delete(accountId)
    }
  }

  function disconnect(accountId: string): void {
    const [account] = db.select().from(googleAccounts).where(eq(googleAccounts.id, accountId)).all()
    if (!account) throw notFound('Google account')
    const now = isoUtc(DateTime.utc())
    db.transaction((tx) => {
      const linked = tx.select().from(calendars)
        .where(and(eq(calendars.googleAccountId, accountId), isNull(calendars.deletedAt))).all()
      for (const cal of linked) {
        tx.update(events).set({ deletedAt: now }).where(and(eq(events.calendarId, cal.id), isNull(events.deletedAt))).run()
        tx.update(calendars).set({ deletedAt: now }).where(eq(calendars.id, cal.id)).run()
      }
      tx.update(calendars).set({ googleAccountId: null }).where(eq(calendars.googleAccountId, accountId)).run()
      tx.delete(googleAccounts).where(eq(googleAccounts.id, accountId)).run()
    })
    access.delete(accountId)
  }

  return {
    isConfigured: configured,
    getNotice: () => configured() ? null : 'Google Calendar requires current Google Play services on this tablet.',
    listAccounts: () => db.select().from(googleAccounts).all().map(({ id, email, lastRefreshError }) => ({ id, email, error: lastRefreshError })),
    setCredentials() { throw new AppError('UNAVAILABLE', 'Use Connect Google on Android') },
    connect, disconnect, markAuthError,
    getAuthedClient: (accountId: string) => ({ getAccessToken: (retry = false) => getAccessToken(accountId, retry) })
  }
}
