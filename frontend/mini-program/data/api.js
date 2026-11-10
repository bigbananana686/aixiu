const { apiBase } = require('./config.js')
let token = ''
let onExpired

function setSession(accessToken, expire) {
  token = accessToken
  onExpired = expire
}

function api(path, { method = 'GET', data, key, accessToken = token } = {}) {
  const header = {}
  if (accessToken) header.Authorization = 'Bearer ' + accessToken
  if (key) header['Idempotency-Key'] = key
  return new Promise((resolve, reject) => {
    wx.request({
      url: apiBase.replace(/\/$/, '') + '/api/v1' + path, method, data, header, timeout: 30000,
      success(response) {
        const { statusCode, data: body } = response
        if (statusCode === 204) return resolve(null)
        if (statusCode >= 200 && statusCode < 300 && body && Object.prototype.hasOwnProperty.call(body, 'data')) return resolve(body.data)
        if (statusCode === 401 && token && token === accessToken) onExpired?.()
        const error = body && body.error
        reject(Object.assign(new Error(error?.fieldErrors?.[0]?.message || error?.message || '服务暂不可用，请稍后重试。'), {
          status: statusCode, code: error?.code, retryable: error?.retryable,
        }))
      },
      fail() { reject(new Error('无法连接爱修服务，请检查网络后重试。')) },
    })
  })
}

function query(filters) {
  return Object.entries(filters).map(([name, value]) => encodeURIComponent(name) + '=' + encodeURIComponent(value)).join('&')
}

async function listAll(path, filters = {}) {
  const items = []
  for (let page = 1; ; page++) {
    const result = await api(path + '?' + query({ ...filters, page, pageSize: 100 }))
    items.push(...result.items)
    if (items.length >= result.total || !result.items.length) return items
  }
}

function command(page, path, data, method = 'POST') {
  const identity = method + path + JSON.stringify(data)
  if (page.commandAttempt?.identity !== identity) {
    page.commandAttempt = { identity, key: Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2) }
  }
  return api(path, { method, data, key: page.commandAttempt.key }).then(result => {
    page.commandAttempt = null
    return result
  }, error => {
    if (error.status && !error.retryable) page.commandAttempt = null
    throw error
  })
}

module.exports = { api, listAll, command, query, setSession }
