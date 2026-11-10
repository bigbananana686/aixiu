export const states = {
  RESERVED: '待接单', ACCEPTED: '已接单', EN_ROUTE: '技师已出发', ARRIVED: '技师已到达',
  QUOTE_PENDING: '待确认报价', READY_FOR_SERVICE: '待开工', SERVICING: '保养中',
  COMPLETED: '已完工', CLOSED: '已归档', CANCELLED: '已取消',
}
export const exceptionLabels = {
  DEFERRAL_SLOT_CONFLICT: '延期时段冲突', OVER_PLAN_COMPLETION: '超计划完工',
}
const clock = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'short',
})
export const localTime = value => value ? clock.format(new Date(value)) : '—'

export function orderRow(item, technicians) {
  const start = localTime(item.serviceStart)
  return {
    id: item.orderId, number: item.orderNo, state: item.state,
    car: item.modelCode || '车辆 #' + item.vehicleId, plate: item.plateMasked || '',
    service: item.bookingItems?.map(service => service.name).join('、') || '',
    status: item.state === 'QUOTE_PENDING' && item.approvedQuoteId ? '待确认增项' : states[item.state],
    tone: ['COMPLETED', 'CLOSED'].includes(item.state) ? 'done' : item.state === 'QUOTE_PENDING' ? 'pending' : '',
    date: start.slice(0, 10), window: start.slice(11) + '–' + localTime(item.committedEnd).slice(11),
    tech: technicians[item.technicianId] || '技师 #' + item.technicianId,
    address: item.address,
    events: item.stateLog?.map((entry, index) => ({
      id: index, time: localTime(entry.createdAt), title: states[entry.toState] || entry.toState,
    })) || [],
  }
}

export function exceptionRow(item, technicians) {
  const label = exceptionLabels[item.type] || item.type
  const detail = item.type === 'OVER_PLAN_COMPLETION'
    ? '实际完工 ' + localTime(item.actualFinish) + '，超时 ' + item.overPlanMinutes + ' 分钟'
    : '申请延期至 ' + localTime(item.requestedTargetEnd)
  return {
    id: item.exceptionId, orderId: item.orderId, type: item.type, label, detail,
    tech: technicians[item.technicianId] || '—',
    time: localTime(item.recordedAt), resolved: item.resolved,
  }
}
