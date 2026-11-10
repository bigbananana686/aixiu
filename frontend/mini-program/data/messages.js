const { getOrder } = require('./demo.js')
const threads = {
  'AC-1103-018': [{ id: 1, role: 'OWNER', time: '10:20', text: '您好，车辆停在订单里填写的位置，到了可以发消息给我。' }]
}
let nextId = 2
const readThrough = { OWNER: {}, TECH: {} }

function readMessages(orderId) {
  if (!getOrder(orderId)) return []
  return (threads[orderId] || []).slice()
}

function sendMessage(orderId, role, text) {
  if (!getOrder(orderId) || !['OWNER', 'TECH'].includes(role) || typeof text !== 'string') return false
  const content = text.trim()
  if (!content || content.length > 300) return false
  const thread = threads[orderId] || (threads[orderId] = [])
  const now = new Date()
  const time = [now.getHours(), now.getMinutes()].map(part => String(part).padStart(2, '0')).join(':')
  thread.push({ id: nextId++, role, text: content, time })
  return true
}

function markRead(orderId, role) {
  const messages = readMessages(orderId)
  if (messages.length) readThrough[role][orderId] = messages[messages.length - 1].id
}

function unreadCount(orderId, role, messages) {
  const cursor = readThrough[role][orderId] || 0
  return messages.filter(message => message.role !== role && message.id > cursor).length
}

module.exports = { readMessages, sendMessage, markRead, unreadCount }


