export const demo = import.meta.env.VITE_AIXIU_MODE === 'demo'
const base = (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') + '/api/v1'
const sessionKey = 'aixiu-admin-session'
export function readSession() {
  try { return JSON.parse(sessionStorage.getItem(sessionKey) || 'null') }
  catch { return null }
}
export function storeSession(session) {
  if (session) sessionStorage.setItem(sessionKey, JSON.stringify(session))
  else sessionStorage.removeItem(sessionKey)
}

export async function api(path, { method = 'GET', data, key, token = readSession()?.accessToken } = {}) {
  const headers = new Headers()
  if (token) headers.set('Authorization', 'Bearer ' + token)
  if (key) headers.set('Idempotency-Key', key)
  if (data !== undefined && !(data instanceof FormData)) headers.set('Content-Type', 'application/json')
  let response
  try {
    response = await fetch(base + path, {
      method, headers, body: data instanceof FormData ? data : JSON.stringify(data),
      signal: AbortSignal.timeout(15000),
    })
  } catch {
    throw new Error('无法连接爱修服务，请确认后端已启动后重试。')
  }
  if (response.status === 204) return null
  const body = await response.json().catch(() => ({}))
  if (response.ok && Object.hasOwn(body, 'data')) return body.data
  if (response.status === 401 && token && readSession()?.accessToken === token) {
    storeSession(null)
    window.dispatchEvent(new Event('aixiu-session-expired'))
  }
  const error = body.error
  throw Object.assign(new Error(error?.fieldErrors?.[0]?.message || error?.message || '服务暂不可用，请重试。'), {
    status: response.status, code: error?.code, retryable: error?.retryable,
  })
}

export async function listAll(path, filters = {}) {
  const items = []
  const params = new URLSearchParams({ ...filters, pageSize: '100' })
  for (let page = 1; ; page++) {
    params.set('page', page)
    const result = await api(path + '?' + params)
    items.push(...result.items)
    if (items.length >= result.total || !result.items.length) return items
  }
}

export function command() {
  let attempt
  return async (path, method, data, signature = JSON.stringify(data)) => {
    const operation = method + path + signature
    if (attempt?.operation !== operation) {
      attempt = { operation, key: globalThis.crypto?.randomUUID?.() || Date.now() + '-' + Math.random().toString(36).slice(2) }
    }
    try {
      const result = await api(path, { method, data, key: attempt.key })
      attempt = null
      return result
    } catch (error) {
      if (error.status && !error.retryable) attempt = null
      throw error
    }
  }
}
