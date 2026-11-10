const { api, listAll, command, query } = require('./api.js')
const stateKeys = {
  RESERVED: 'reserved', ACCEPTED: 'accepted', EN_ROUTE: 'departed', ARRIVED: 'arrived',
  QUOTE_PENDING: 'quoted', READY_FOR_SERVICE: 'ready', SERVICING: 'working', COMPLETED: 'completed',
  CLOSED: 'closed', CANCELLED: 'cancelled',
}
const labels = {
  reserved: '待接单', accepted: '已接单', departed: '技师已出发', arrived: '技师已到达', quoted: '待确认报价',
  extraQuoted: '待确认增项', ready: '待开工', working: '保养中', completed: '待车主验收', closed: '已归档', cancelled: '已取消',
}
const quoteLabels = { PENDING: '待确认', APPROVED: '已确认', REJECTED: '已拒绝', EXPIRED: '已过期', SUPERSEDED: '已替换', WITHDRAWN: '已撤回' }
const icons = { OIL_FILTER: 'oil', AIR_FILTER: 'air', CABIN_FILTER: 'cabin', WIPER: 'wiper', BATTERY_CHECK: 'battery', TIRE_PRESSURE: 'pressure' }
const epoch = value => value ? new Date(value).getTime() : 0
const china = value => new Date((typeof value === 'number' ? value : epoch(value)) + 28800000).toISOString()
const formatTime = value => value ? china(value).slice(0, 16).replace('T', ' ') : ''
const day = (days = 0) => china(Date.now() + days * 86400000).slice(0, 10)
const vehicleRow = item => ({ id: item.vehicleId, car: item.modelCode, plate: item.plateMasked, mileage: item.mileage, version: item.rowVersion })

async function vehicles() { return (await listAll('/vehicles')).map(vehicleRow) }
async function catalog() {
  return (await listAll('/service-items')).map(item => ({
    id: item.serviceItemId, label: item.name, name: item.name, price: item.unitPrice, minutes: item.durationMinutes, duration: item.durationMinutes + ' 分钟',
    version: item.catalogVersion, icon: icons[item.code] || 'diagnostics', tone: 'green',
  }))
}
const bookingLine = item => ({
  serviceId: item.serviceItemId, name: item.name || item.nameSnapshot, label: item.name || item.nameSnapshot, quantity: item.quantity,
  unitMinutes: item.durationMinutes,
  catalogVersion: item.catalogVersion, minutes: item.lineMinutes ?? item.quantity * item.durationMinutes,
  amount: (Math.round(Number(item.unitPrice) * 100) * item.quantity / 100).toFixed(2),
  lineKey: item.lineKey || item.serviceItemId, isExtra: item.isExtra,
})
const quoteRow = quote => ({
  id: quote.quoteId, version: quote.version, purpose: quote.purpose, status: quote.status,
  purposeLabel: quote.purpose === 'EXTRA' ? '增项报价' : '初始报价', decisionReason: quote.terminalReason,
  statusLabel: quoteLabels[quote.status] || quote.status, total: quote.totalAmount, totalMinutes: quote.totalMinutes, minutes: quote.totalMinutes,
  deadline: formatTime(quote.expiresAt), expiresAt: epoch(quote.expiresAt), contentHash: quote.contentHash,
  items: quote.items.map(bookingLine), analysis: quote.analysis,
})
function orderRow(item, knownVehicle, quotes = []) {
  const status = item.state === 'QUOTE_PENDING' && item.approvedQuoteId ? 'extraQuoted' : stateKeys[item.state]
  const bookingItems = item.bookingItems?.map(bookingLine) || []
  return {
    id: item.orderId, orderNo: item.orderNo, rowVersion: item.rowVersion, status, statusLabel: labels[status],
    car: item.modelCode || knownVehicle?.car || '车辆 #' + item.vehicleId,
    plate: item.plateMasked || knownVehicle?.plate || '',
    technicianId: item.technicianId, tech: '技师 #' + item.technicianId,
    address: item.address, appointment: formatTime(item.serviceStart), serviceStart: epoch(item.serviceStart),
    committedEnd: epoch(item.committedEnd), committedEndLabel: formatTime(item.committedEnd),
    effectiveEnd: epoch(item.effectiveEnd), effectiveEndLabel: formatTime(item.effectiveEnd), acceptDeadlineText: formatTime(item.acceptDeadline),
    service: bookingItems.map(line => line.label).join('、') || '上门保养',
    bookingItems, quoteSequence: Math.max(0, ...quotes.map(quote => Number(quote.version))),
    approvedQuote: quotes.find(quote => quote.id === item.approvedQuoteId) || null,
    quote: quotes.find(quote => quote.id === item.pendingQuoteId) || null,
    quotes, completedNote: item.serviceResult,
    exceptions: item.exceptions?.map(entry => ({
      id: entry.exceptionId, label: entry.type === 'OVER_PLAN_COMPLETION' ? '超计划完工' : '延期时段冲突',
      detail: entry.type === 'OVER_PLAN_COMPLETION' ? '超出承诺时间 ' + entry.overPlanMinutes + ' 分钟' : '申请延期至 ' + formatTime(entry.requestedTargetEnd),
    })) || [],
    progress: item.stateLog?.map((entry, index) => ({
      id: index, title: labels[stateKeys[entry.toState]] || entry.action, time: formatTime(entry.createdAt),
    })) || [],
  }
}

async function orders(knownVehicles = []) {
  const byId = Object.fromEntries(knownVehicles.map(vehicle => [vehicle.id, vehicle]))
  return (await listAll('/orders')).map(item => orderRow(item, byId[item.vehicleId]))
}

async function order(id) {
  const path = '/orders/' + id
  const [item, quotes] = await Promise.all([api(path), listAll(path + '/quotes')])
  return orderRow(item, null, quotes.map(quoteRow))
}

async function slots(date, items, technicianId) {
  const result = await api('/technicians/availability?' + query({
    date, serviceItemIds: items.map(item => item.id || item.serviceId).join(','),
    ...(technicianId ? { technicianId } : {}),
  }))
  const extra = items.reduce((total, item) => total + (item.quantity - 1) * (item.unitMinutes || item.minutes), 0)
  const choices = []
  for (const tech of result.technicians) {
    const seen = new Set()
    for (const range of tech.serviceStartRanges) {
      const earliest = epoch(range.earliest)
      const latest = epoch(range.latest) - extra * 60000
      const starts = [earliest]
      const interval = 30 * 60000
      for (let start = Math.floor(earliest / interval) * interval + interval; start <= latest; start += interval) starts.push(start)
      if (latest > earliest) starts.push(latest)
      for (const start of starts) {
        if (start > latest || start <= Date.now() || seen.has(start)) continue
        seen.add(start)
        const startAt = new Date(start).toISOString()
        choices.push({ id: tech.technicianId + '-' + start, technicianId: tech.technicianId, tech: tech.displayName,
          time: formatTime(start), startAt, start })
      }
    }
  }
  return choices.sort((a, b) => a.start - b.start)
}

function orderAction(page, order, action, extra = {}) {
  return command(page, '/orders/' + order.id + '/' + action, { expectedRowVersion: order.rowVersion, ...extra })
}
function quoteAction(page, order, action, reason) {
  const quote = order.quote
  return command(page, '/orders/' + order.id + '/quotes/' + quote.id + '/' + action,
    { version: quote.version, ...(reason !== undefined ? { reason } : {}) })
}

async function mutate(page, action, succeeded) {
  if (page.data.busy) return
  page.setData({ busy: true, error: '' })
  try {
    const result = await action()
    await succeeded?.(result)
  } catch (error) {
    if (error.status === 409) await Promise.resolve(page.refresh?.()).catch(() => {})
    page.setData({ error: error.message })
  } finally {
    page.setData({ busy: false })
  }
}

module.exports = { api, command, listAll, formatTime, day, vehicles, catalog, orders, order, slots, orderAction, quoteAction, mutate, vehicleRow }
