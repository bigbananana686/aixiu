import { linkSync, mkdirSync, readFileSync, readdirSync, unlinkSync, writeFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import { join } from 'node:path'

const loopback = ['127.0.0.1', '::1', '::ffff:127.0.0.1']
const actors = { 'Bearer demo-owner': 'demo-owner', 'Bearer demo-admin': 'demo-admin' }
const types = ['FEEDBACK', 'COMPLAINT']

function respond(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
  res.end(JSON.stringify(body))
}

async function parseBody(req) {
  if (!req.headers['content-type']?.startsWith('application/json')) return null
  const chunks = []
  let size = 0
  for await (const chunk of req) {
    size += chunk.length
    if (size <= 4096) chunks.push(chunk)
  }
  if (size > 4096) return null
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')) } catch { return null }
}

function listRecords(dir) {
  try {
    return readdirSync(dir).filter(name => name.endsWith('.json'))
      .map(name => JSON.parse(readFileSync(join(dir, name), 'utf8')))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  } catch (error) {
    if (error.code === 'ENOENT') return []
    throw error
  }
}

function saveRecord(dir, entry) {
  mkdirSync(dir, { recursive: true })
  const file = join(dir, entry.id + '.json')
  try {
    return JSON.parse(readFileSync(file, 'utf8'))
  } catch (error) {
    if (error.code !== 'ENOENT') throw error
  }
  const pending = join(dir, randomUUID() + '.tmp')
  writeFileSync(pending, JSON.stringify(entry), { encoding: 'utf8', flag: 'wx' })
  try {
    linkSync(pending, file)
    return entry
  } catch (error) {
    if (error.code !== 'EEXIST') throw error
    return JSON.parse(readFileSync(file, 'utf8'))
  } finally {
    unlinkSync(pending)
  }
}

export function previewFeedback(dir) {
  // The named demo identities only grant access to records on the loopback preview server.
  return async (req, res, next) => {
    if (req.url?.split('?')[0] !== '/api/preview/feedback') return next()
    if (!loopback.includes(req.socket.remoteAddress)) return respond(res, 403, { message: '请从本机访问预览服务。' })
    const actor = Object.hasOwn(actors, req.headers.authorization) ? actors[req.headers.authorization] : ''
    if (!actor) return respond(res, 401, { message: '请先登录演示账号。' })
    try {
      if (req.method === 'GET') {
        const items = listRecords(dir)
        return respond(res, 200, { data: actor === 'demo-admin' ? items : items.filter(item => item.owner === actor) })
      }
      if (req.method !== 'POST' || actor !== 'demo-owner') {
        return respond(res, 405, { message: '此账号无法提交反馈。' })
      }
      const body = await parseBody(req)
      const id = typeof body?.id === 'string' ? body.id : ''
      const detail = typeof body?.detail === 'string' ? body.detail.trim() : ''
      const orderId = typeof body?.orderId === 'string' ? body.orderId.trim() : ''
      const type = body?.type
      if (!/^[a-z0-9-]{12,64}$/.test(id) || !types.includes(type) || !detail || detail.length > 300
        || (orderId && !/^[a-z0-9-]{1,64}$/i.test(orderId)) || (type === 'COMPLAINT' && !orderId)) {
        return respond(res, 400, { message: '请核对反馈类型、关联订单和问题说明。' })
      }
      const entry = { id, owner: actor, type, orderId, detail, createdAt: new Date().toISOString() }
      const saved = saveRecord(dir, entry)
      if (saved.type !== type || saved.orderId !== orderId || saved.detail !== detail || saved.owner !== actor) {
        return respond(res, 409, { message: '这笔提交的内容已变更，请重新发起反馈。' })
      }
      return respond(res, 200, { data: saved })
    } catch {
      return respond(res, 500, { message: '反馈服务暂不可用，请稍后重试。' })
    }
  }
}
