const { order } = require('./order.js')

const services = [
  { id: 'oil', price: '199.00', label: '做保养', name: '机油和机滤更换', duration: '约 60 分钟', minutes: 60, tone: 'amber' },
  { id: 'air', price: '119.00', label: '空调滤芯', name: '更换空调滤芯', duration: '约 30 分钟', minutes: 30, tone: 'blue' },
  { id: 'enginefilter', price: '89.00', label: '空气滤芯', name: '更换发动机空气滤芯', duration: '约 20 分钟', minutes: 20, tone: 'green' },
  { id: 'battery', price: '159.00', label: '电瓶服务', name: '电瓶检测与更换', duration: '约 30 分钟', minutes: 30, tone: 'coral' },
  { id: 'wiper', price: '69.00', label: '更换雨刷', name: '雨刷检查与更换', duration: '约 20 分钟', minutes: 20, tone: 'blue' },
  { id: 'tire', price: '99.00', label: '轮胎检修', name: '轮胎检查与维修', duration: '约 60 分钟', minutes: 60, tone: 'violet' },
  { id: 'brake', price: '399.00', label: '刹车养护', name: '刹车检查与养护', duration: '约 60 分钟', minutes: 60, tone: 'coral' },
  { id: 'diagnostics', price: '99.00', label: '故障检测', name: '车辆故障诊断', duration: '约 30 分钟', minutes: 30, tone: 'green' },
  { id: 'acclean', price: '129.00', label: '空调清洁', name: '空调系统清洁', duration: '约 45 分钟', minutes: 45, tone: 'blue' },
  { id: 'coolant', price: '169.00', label: '防冻液', name: '防冻液检查与更换', duration: '约 45 分钟', minutes: 45, tone: 'green' },
  { id: 'sparkplug', price: '199.00', label: '更换火花塞', name: '火花塞检查与更换', duration: '约 45 分钟', minutes: 45, tone: 'amber' },
  { id: 'pressure', price: '29.00', label: '胎压检测', name: '胎压检测与调整', duration: '约 20 分钟', minutes: 20, tone: 'violet' }
]
const windows = [
  { id: 'morning', hour: 10, time: '10:30–11:30' },
  { id: 'afternoon', hour: 14, time: '14:00–15:00' }
].map(window => {
  const start = new Date()
  start.setDate(start.getDate() + 1)
  start.setHours(window.hour, window.hour === 10 ? 30 : 0, 0, 0)
  return { ...window, start: start.getTime(), end: start.getTime() + 60 * 60000,
    time: (start.getMonth() + 1) + ' 月 ' + start.getDate() + ' 日 ' + window.time }
})
const vehicles = [
  { id: '1', car: '大众朗逸', plate: '粤B·•••38' },
  { id: '2', car: '丰田卡罗拉', plate: '粤B·•••62' }
]
const labels = {
  reserved: '待接单', accepted: '已接单', departed: '技师已出发', arrived: '技师已到达',
  quoted: '待确认报价', extraQuoted: '待确认增项', ready: '待开工', working: '保养中',
  completed: '待车主验收', closed: '已归档', cancelled: '已取消'
}
const quoteStatusLabels = {
  PENDING: '待确认', CONFIRMED: '已确认', REJECTED: '已拒绝',
  SUPERSEDED: '已被新版替换', WITHDRAWN: '技师已撤回', EXPIRED: '已到期'
}
const actions = {
  accept: [['reserved'], 'accepted', '技师已接单'],
  decline: [['reserved'], 'cancelled', '技师拒绝接单'],
  cancel: [['reserved', 'accepted'], 'cancelled', '车主取消预约'],
  depart: [['accepted'], 'departed', '技师已出发'],
  arrive: [['departed'], 'arrived', '技师已到达'],
  start: [['ready'], 'working', '技师开始保养'],
  complete: [['working'], 'completed', '技师完成保养'],
  close: [['completed'], 'closed', '车主验收归档']
}
let nextEventId = 1
let nextQuoteId = 2
let nextExceptionId = 1
const now = Date.now()
const seedQuote = {
  ...order.quote, id: '1', version: 1, purpose: 'INITIAL', status: 'PENDING',
  expiresAt: now + 10 * 60000, totalMinutes: 60, addedMinutes: 0, targetEnd: now + 60 * 60000,
  items: order.quote.items.map((item, index) => ({
    ...item, lineKey: 'seed-' + index, quantity: 1, unitPrice: item.amount, minutes: index === 0 ? 60 : 0
  }))
}
const orders = [{
  ...order, status: 'quoted', rowVersion: 1, quoteSequence: 1,
  serviceStart: now, committedEnd: now + 60 * 60000, effectiveEnd: now + 60 * 60000,
  quote: seedQuote, quotes: [seedQuote], approvedQuote: null, exceptions: [],
  progress: order.progress.map(event => ({ ...event, id: nextEventId++ }))
}]

function copy(value) { return JSON.parse(JSON.stringify(value)) }

function record(target, title) {
  target.rowVersion++
  target.progress = [{ id: nextEventId++, time: '刚刚', title }, ...target.progress]
}

function fail(message) { throw new Error(message) }

function formatTime(value) {
  const date = new Date(value)
  return [date.getHours(), date.getMinutes()].map(part => String(part).padStart(2, '0')).join(':')
}

function finishPending(target, status, title) {
  const pending = target.quote
  pending.status = status
  target.quote = target.approvedQuote
  target.status = pending.purpose === 'EXTRA' ? 'working' : 'arrived'
  target.effectiveEnd = target.committedEnd
  record(target, title)
}

function expirePending(target) {
  if (target.quote && target.quote.status === 'PENDING' && Date.now() >= target.quote.expiresAt) {
    finishPending(target, 'EXPIRED', '报价到期，恢复原服务范围')
  }
}

function findOrder(id) {
  const target = orders.find(entry => entry.id === id)
  if (!target) fail('订单不存在，请返回列表刷新。')
  expirePending(target)
  return target
}

function listOrders() {
  return orders.map(target => {
    expirePending(target)
    return copy({
      ...target, statusLabel: labels[target.status],
      committedEndLabel: formatTime(target.committedEnd),
      effectiveEndLabel: formatTime(target.effectiveEnd),
      quote: target.quote ? { ...target.quote, deadline: formatTime(target.quote.expiresAt) } : null,
      quotes: target.quotes.map(quote => ({
        ...quote, deadline: formatTime(quote.expiresAt), statusLabel: quoteStatusLabels[quote.status],
        purposeLabel: quote.purpose === 'EXTRA' ? '增项报价' : '初始报价'
      }))
    })
  })
}

function getOrder(id) { return listOrders().find(entry => entry.id === id) }

function applyAction(id, action) {
  const target = orders.find(entry => entry.id === id)
  if (!target) return false
  expirePending(target)
  const transition = actions[action]
  if (!transition || !transition[0].includes(target.status)) return false
  if (action === 'start') target.startedAt = Date.now()
  if (action === 'complete') {
    target.workMinutes = Math.max(0, Math.round((Date.now() - target.startedAt) / 60000))
    if (Date.now() > target.committedEnd) {
      target.exceptions.push({
        id: String(nextExceptionId++), type: 'OVER_PLAN_COMPLETION', label: '超计划完工',
        minutes: Math.min(10080, Math.ceil((Date.now() - target.committedEnd) / 60000)),
        detail: '完工时间超过已承诺结束时间。'
      })
    }
  }
  if (action === 'cancel' && target.quote && target.quote.status === 'PENDING') {
    target.quote.status = 'EXPIRED'
    target.quote = target.approvedQuote
    target.effectiveEnd = target.committedEnd
  }
  target.status = transition[1]
  record(target, transition[2])
  return true
}

function validateVersion(target, expected) {
  if (target.rowVersion !== expected) fail('订单已更新，请刷新后重试。')
}

function submitQuote(id, input) {
  const target = findOrder(id)
  validateVersion(target, input.rowVersion)
  const pending = target.quote && target.quote.status === 'PENDING' ? target.quote : null
  const purpose = pending ? pending.purpose : target.status === 'working' ? 'EXTRA' : 'INITIAL'
  if (!['arrived', 'working', 'quoted', 'extraQuoted'].includes(target.status)) fail('当前状态不能发布报价。')
  if ((pending ? pending.id : null) !== input.pendingQuoteId) fail('待确认报价已更新，请刷新。')
  if ((target.approvedQuote ? target.approvedQuote.id : null) !== input.approvedQuoteId) fail('已批准范围已更新，请刷新。')
  if (!Array.isArray(input.items) || !input.items.length || input.items.length > 50) fail('请选择 1–50 个服务条目。')
  const approvedItems = target.approvedQuote ? target.approvedQuote.items : []
  const inherited = new Map(approvedItems.map(item => [item.lineKey, item]))
  const seen = new Set()
  let amountCents = 0
  let totalMinutes = 0
  let addedMinutes = 0
  const items = input.items.map(item => {
    const key = String(item.lineKey || '')
    if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(key) || seen.has(key.toLowerCase())) fail('条目标识无效或重复。')
    seen.add(key.toLowerCase())
    const old = purpose === 'EXTRA' ? inherited.get(key) : null
    let snapshot
    if (old) {
      if (old.serviceId !== item.serviceId || old.quantity !== item.quantity) fail('已批准条目不能修改。')
      snapshot = copy(old)
    } else {
      const service = services.find(service => service.id === item.serviceId)
      if (!service || !Number.isInteger(item.quantity) || item.quantity < 1 || item.quantity > 99) fail('请选择有效服务及数量。')
      snapshot = {
        lineKey: key, serviceId: service.id, name: service.name, quantity: item.quantity,
        unitPrice: service.price, amount: (Math.round(Number(service.price) * 100) * item.quantity / 100).toFixed(2),
        minutes: service.minutes * item.quantity
      }
      if (purpose === 'EXTRA') addedMinutes += snapshot.minutes
    }
    amountCents += Math.round(Number(snapshot.amount) * 100)
    totalMinutes += snapshot.minutes
    return snapshot
  })
  if (purpose === 'EXTRA' && approvedItems.some(item => !seen.has(item.lineKey.toLowerCase()))) fail('增项必须保留全部已批准条目。')
  if (purpose === 'EXTRA' && !addedMinutes) fail('请选择至少一项新增服务。')
  if (amountCents > 999999999 || totalMinutes > 10080) fail('总金额或工时超过支持范围。')
  const reason = String(input.reason || '').trim()
  if (!reason || reason.length > 500) fail('请填写不超过 500 字的报价说明。')
  const targetEnd = purpose === 'EXTRA'
    ? target.committedEnd + addedMinutes * 60000 : target.serviceStart + totalMinutes * 60000
  const effectiveEnd = Math.max(target.committedEnd, targetEnd)
  checkWorkWindow(target, effectiveEnd)
  if (hasConflict(target, effectiveEnd)) fail('报价工时与其他预约冲突，请调整新增项目或联系平台。')
  if (pending) pending.status = 'SUPERSEDED'
  const quote = {
    id: String(nextQuoteId++), version: ++target.quoteSequence, purpose, status: 'PENDING',
    total: (amountCents / 100).toFixed(2), totalMinutes, addedMinutes,
    items, reason, targetEnd, expiresAt: pending ? pending.expiresAt : Date.now() + 10 * 60000
  }
  target.quote = quote
  target.quotes.unshift(quote)
  target.effectiveEnd = effectiveEnd
  target.status = purpose === 'EXTRA' ? 'extraQuoted' : 'quoted'
  record(target, pending ? '技师替换待确认报价' : purpose === 'EXTRA' ? '技师发布增项报价' : '技师发布初始报价')
  return getOrder(id)
}

function decideQuote(id, action, quoteId, version, reason = '') {
  const target = findOrder(id)
  const quote = target.quote
  if (!quote || quote.id !== quoteId || quote.version !== version || quote.status !== 'PENDING') {
    fail('这份报价已更新、到期或撤回，请刷新订单。')
  }
  if (action === 'confirm') {
    quote.status = 'CONFIRMED'
    target.approvedQuote = quote
    target.committedEnd = Math.max(target.committedEnd, quote.targetEnd)
    target.effectiveEnd = target.committedEnd
    target.status = quote.purpose === 'EXTRA' ? 'working' : 'ready'
    record(target, quote.purpose === 'EXTRA' ? '车主确认增项报价' : '车主确认报价')
  } else if (action === 'reject' || action === 'withdraw') {
    if (action === 'reject' && (!reason.trim() || reason.trim().length > 500)) fail('请填写不超过 500 字的拒绝原因。')
    if (action === 'reject') quote.decisionReason = reason.trim()
    finishPending(target, action === 'reject' ? 'REJECTED' : 'WITHDRAWN',
      action === 'reject' ? '车主拒绝报价，恢复原服务范围' : '技师主动撤回报价')
  } else fail('无效的报价操作。')
  return getOrder(id)
}

function extendRuntime(id, rowVersion, targetEnd, reason) {
  const target = findOrder(id)
  validateVersion(target, rowVersion)
  if (!['ready', 'working'].includes(target.status)) fail('请先处理待确认报价，再申请延期。')
  if (!reason.trim() || reason.length > 500 || !Number.isFinite(targetEnd) || targetEnd <= target.committedEnd) fail('请填写有效延期时间和原因。')
  checkWorkWindow(target, targetEnd)
  if (hasConflict(target, targetEnd)) {
    target.exceptions.push({
      id: String(nextExceptionId++), type: 'DEFERRAL_SLOT_CONFLICT', label: '延期时段冲突',
      detail: '申请的延期占用了其他有效预约，需人工协调。', targetEnd: formatTime(targetEnd)
    })
    fail('延期与其他预约冲突，已记录异常，请联系平台协调。')
  }
  target.committedEnd = targetEnd
  target.effectiveEnd = targetEnd
  record(target, '技师延期至 ' + formatTime(targetEnd) + '，' + reason.trim())
  return getOrder(id)
}

function hasConflict(target, end) {
  for (const other of orders) expirePending(other)
  return orders.some(other => other.id !== target.id && other.status !== 'cancelled'
    && other.serviceStart < end && other.effectiveEnd > target.serviceStart)
}

function checkWorkWindow(target, end) {
  const window = windows.find(window => window.id === target.windowId)
  if (!window) return
  const workEnd = new Date(window.start)
  workEnd.setHours(18, 0, 0, 0)
  if (end > workEnd.getTime()) fail('超出当日工作窗口，请联系平台协调。')
}

function createBooking(vehicleId, selections, windowId, address) {
  const vehicle = vehicles.find(entry => entry.id === vehicleId)
  const window = windows.find(entry => entry.id === windowId)
  if (!vehicle || !window || !address.trim()) return null
  const requested = typeof selections === 'string' ? [{ serviceId: selections, quantity: 1 }] : selections
  if (!Array.isArray(requested) || !requested.length || requested.length > 50) fail('请选择 1–50 个服务项目。')
  const unique = new Set()
  const bookingItems = requested.map(item => {
    const service = services.find(entry => entry.id === item.serviceId)
    if (!service || unique.has(item.serviceId) || !Number.isInteger(item.quantity) || item.quantity < 1 || item.quantity > 99) fail('服务项目或数量无效，请重新选择。')
    unique.add(service.id)
    return { serviceId: service.id, name: service.name, quantity: item.quantity, price: service.price, minutes: service.minutes }
  })
  const totalMinutes = bookingItems.reduce((sum, item) => sum + item.minutes * item.quantity, 0)
  if (totalMinutes > 10080) fail('组合服务总工时过长，请调整项目或数量。')
  const committedEnd = window.start + totalMinutes * 60000
  const planned = { windowId, serviceStart: window.start }
  checkWorkWindow(planned, committedEnd)
  if (hasConflict(planned, committedEnd)) return null
  const id = 'AC-' + String(window.start) + '-' + String(orders.length + 1).padStart(3, '0')
  orders.unshift({
    id, car: vehicle.car, plate: vehicle.plate,
    service: bookingItems.map(item => item.name + (item.quantity > 1 ? ' × ' + item.quantity : '')).join('、'),
    serviceId: bookingItems[0].serviceId, bookingItems, windowId,
    appointment: window.time.split('–')[0] + '–' + formatTime(committedEnd), address: address.trim(), tech: '陈师傅', status: 'reserved',
    rowVersion: 1, quoteSequence: 0, serviceStart: window.start,
    committedEnd, effectiveEnd: committedEnd,
    quote: null, quotes: [], approvedQuote: null, exceptions: [],
    progress: [{ id: nextEventId++, time: '刚刚', title: '车主提交预约' }]
  })
  return id
}

function reschedule(id, windowId) {
  const target = orders.find(entry => entry.id === id)
  const window = windows.find(entry => entry.id === windowId)
  if (!target || !window || !['reserved', 'accepted'].includes(target.status)) return false
  const duration = target.committedEnd - target.serviceStart
  const end = window.start + duration
  try { checkWorkWindow({ windowId }, end) } catch (_) { return false }
  if (hasConflict({ id, serviceStart: window.start }, end)) return false
  target.windowId = windowId
  target.appointment = window.time.split('–')[0] + '–' + formatTime(end)
  target.serviceStart = window.start
  target.committedEnd = end
  target.effectiveEnd = end
  record(target, '车主修改预约时段')
  return true
}

function addVehicle(car, plate) {
  if (!car.trim() || !plate.trim()) return null
  const vehicle = { id: String(vehicles.length + 1), car: car.trim(), plate: plate.trim() }
  vehicles.push(vehicle)
  return vehicle.id
}

module.exports = {
  services, windows, vehicles, listOrders, getOrder, applyAction, createBooking, addVehicle, reschedule,
  submitQuote, decideQuote, extendRuntime
}

