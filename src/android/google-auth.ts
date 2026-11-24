import { Buffer } from 'buffer'
import { and, eq, isNull } from 'drizzle-orm'
import { DateTime } from 'luxon'
import { uuidv7 } from '@shared/uuid'
import { isoUtc } from '@shared/dates'
import type { AppDb } from '../main/db/client'
import { calendars, events, googleAccounts } from '../main/db/schema'
import { AppError, notFound } from '../main/services/errors'
import type { SettingsService } from '../main/services/settingsService'
import { base64url, createHash, randomBytes } from './crypto'
import { host, request } from './host'

const SCOPES = 'https://www.googleapis.com/auth/calendar openid email'
const CLIENT_ID = 'google.clientId'
const CLIENT_SECRET = 'google.clientSecretEnc'
const NOTICE = 'google.secretNotice'

interface TokenResponse { access_token: string; refresh_token?: string; expires_in: number }

export async function googleRequest<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, options)
  if (!response.ok) throw Object.assign(new Error(`Google returned HTTP ${response.status}`), { status: response.status })
  return response.status === 204 ? undefined as T : response.json() as Promise<T>
}

function exchange(values: Record<string, string>): Promise<TokenResponse> {
  return googleRequest('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(values).toString()
  })
}

export function createAndroidGoogleAuth(db: AppDb, settings: SettingsService) {
  let connecting = false
  const access = new Map<string, { token: string; expiresAt: number }>()
  const refreshing = new Map<string, Promise<string>>()

  function credentials() {
    const clientId = settings.getRaw(CLIENT_ID)
    const secret = settings.getRaw(CLIENT_SECRET)
    if (!clientId || !secret) return null
    try {
      return { client_id: clientId, client_secret: host<string>('decrypt', secret) }
    } catch {
      settings.deleteRaw(CLIENT_SECRET)
      settings.setRaw(NOTICE, 'Saved Google credentials could not be read. Enter them again.')
      return null
    }
  }

  function setCredentials(clientId: string, clientSecret: string): void {
    const encrypted = host<string>('encrypt', clientSecret.trim())
    db.transaction(() => {
      settings.setRaw(CLIENT_ID, clientId.trim())
      settings.setRaw(CLIENT_SECRET, encrypted)
      settings.deleteRaw(NOTICE)
    })
    access.clear()
  }

  function markAuthError(accountId: string, message: string | null): void {
    db.update(googleAccounts).set({ lastRefreshError: message }).where(eq(googleAccounts.id, accountId)).run()
  }

  function remember(accountId: string, token: TokenResponse): string {
    access.set(accountId, { token: token.access_token, expiresAt: Date.now() + token.expires_in * 1000 })
    return token.access_token
  }

  async function connect(): Promise<{ email: string }> {
    const creds = credentials()
    if (!creds) throw new AppError('NOT_CONFIGURED', 'Enter your Google OAuth client ID and secret first')
    if (connecting) throw new AppError('BUSY', 'A Google sign-in is already in progress')
    connecting = true
    try {
      const verifier = base64url(randomBytes(32))
      const state = base64url(randomBytes(32))
      const challenge = base64url(createHash('sha256').update(verifier).digest() as Uint8Array)
      const { code, redirectUri } = await request<{ code: string; redirectUri: string }>('oauth', {
        clientId: creds.client_id, state, challenge, scope: SCOPES
      })
      const token = await exchange({
        ...creds, code, redirect_uri: redirectUri, code_verifier: verifier, grant_type: 'authorization_code'
      })
      if (!token.refresh_token) throw new AppError('NO_REFRESH_TOKEN', 'Google did not return a refresh token')
      const { email } = await googleRequest<{ email: string }>('https://openidconnect.googleapis.com/v1/userinfo', {
        headers: { Authorization: `Bearer ${token.access_token}` }
      })
      if (!email) throw new AppError('AUTH_DENIED', 'Google did not return an email address')
      const refreshTokenEnc = Buffer.from(host<string>('encrypt', token.refresh_token), 'base64')
      const [existing] = db.select().from(googleAccounts).where(eq(googleAccounts.email, email)).all()
      const id = existing?.id ?? uuidv7()
      const patch = { refreshTokenEnc, connectedAt: isoUtc(DateTime.utc()), lastRefreshError: null }
      if (existing) db.update(googleAccounts).set(patch).where(eq(googleAccounts.id, id)).run()
      else db.insert(googleAccounts).values({ id, email, scopes: SCOPES, ...patch }).run()
      remember(id, token)
      return { email }
    } finally {
      connecting = false
    }
  }

  async function refresh(accountId: string): Promise<string> {
    const creds = credentials()
    if (!creds) throw new AppError('NOT_CONFIGURED', 'Google credentials missing')
    const [account] = db.select().from(googleAccounts).where(eq(googleAccounts.id, accountId)).all()
    if (!account) throw notFound('Google account')
    let token: string
    try {
      if (!account.refreshTokenEnc) throw new Error('Token unavailable')
      token = host<string>('decrypt', Buffer.from(account.refreshTokenEnc).toString('base64'))
    } catch {
      const message = 'Saved Google sign-in could not be read. Reconnect this account.'
      markAuthError(accountId, message)
      throw new AppError('REAUTH_REQUIRED', message)
    }
    try {
      const result = await exchange({ ...creds, refresh_token: token, grant_type: 'refresh_token' })
      if (!db.select({ id: googleAccounts.id }).from(googleAccounts).where(eq(googleAccounts.id, accountId)).get()) {
        throw notFound('Google account')
      }
      markAuthError(accountId, null)
      return remember(accountId, result)
    } catch (err) {
      const status = (err as { status?: number }).status
      if (status === 400 || status === 401) markAuthError(accountId, 'Sign-in expired — reconnect this account')
      throw err
    }
  }

  async function getAccessToken(accountId: string, retry = false): Promise<string> {
    const cached = access.get(accountId)
    if (!retry && cached && cached.expiresAt - Date.now() > 60_000) return cached.token
    const inflight = refreshing.get(accountId)
    if (inflight) return inflight
    const promise = refresh(accountId)
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
        tx.update(calendars).set({ deletedAt: now, googleAccountId: null }).where(eq(calendars.id, cal.id)).run()
      }
      tx.delete(googleAccounts).where(eq(googleAccounts.id, accountId)).run()
    })
    access.delete(accountId)
  }

  return {
    isConfigured: () => credentials() !== null,
    getNotice: () => settings.getRaw(NOTICE),
    listAccounts: () => db.select().from(googleAccounts).all().map(({ id, email, lastRefreshError }) => ({ id, email, error: lastRefreshError })),
    setCredentials, connect, disconnect, markAuthError,
    getAuthedClient: (accountId: string) => ({ getAccessToken: (retry = false) => getAccessToken(accountId, retry) })
  }
}
