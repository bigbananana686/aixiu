const { requireAccount, demoMode } = require('../../data/auth.js')
const { services, getOrder, submitQuote } = require('../../data/demo.js')
const live = require('../../data/live.js')

Page({
  data: { order: null, purpose: '', replacing: false, inherited: [], choices: [], reason: '', total: '0.00', minutes: 0, error: '', busy: false },
  onLoad(options) { this.id = options.id },
  onShow() {
    if (!requireAccount('TECH') || this.loaded) return
    if (!demoMode) return this.loadLive()
    this.prepare(getOrder(this.id), services)
  },
  async loadLive() {
    this.setData({ busy: true, error: '' })
    try {
      const [order, catalog] = await Promise.all([live.order(this.id), live.catalog()])
      this.prepare(order, catalog)
    } catch (error) {
      this.setData({ error: error.message })
    } finally {
      this.setData({ busy: false })
    }
  },
  prepare(order, catalog) {
    if (!order || !['arrived', 'working', 'quoted', 'extraQuoted'].includes(order.status)) {
      this.setData({ error: '订单状态已变化，请返回工单重新打开。' })
      return
    }
    const pending = order.quote && order.quote.status === 'PENDING' ? order.quote : null
    const extra = pending ? pending.purpose === 'EXTRA' : order.status === 'working'
    const inherited = extra ? order.approvedQuote.items : []
    const choices = catalog.map(service => {
      const previous = pending ? pending.items.find(item => item.serviceId === service.id && !inherited.some(old => old.lineKey === item.lineKey)) : null
      const booked = (order.bookingItems || []).find(item => item.serviceId === service.id)
      let quantity = 0
      if (previous) quantity = previous.quantity
      else if (!extra && !pending) {
        if (booked) quantity = booked.quantity
        else if (!order.bookingItems && service.id === (order.serviceId || 'oil')) quantity = 1
      }
      return {
        ...service, quantity,
        lineKey: previous ? previous.lineKey : (demoMode ? 'new-' + (order.quoteSequence + 1) + '-' + service.id : 'l' + (order.quoteSequence + 1) + '-' + service.id)
      }
    })
    this.loaded = true
    this.setData({
      order, replacing: Boolean(pending), purpose: extra ? '增项报价' : '初始报价',
      inherited, choices, reason: pending ? pending.reason || '' : ''
    })
    this.recalculate()
  },
  changeQuantity(event) {
    const { id, delta } = event.currentTarget.dataset
    const choices = this.data.choices.map(item => item.id === id
      ? { ...item, quantity: Math.max(0, Math.min(99, item.quantity + Number(delta))) } : item)
    this.setData({ choices, error: '' })
    this.recalculate()
  },
  recalculate() {
    const inheritedCents = this.data.inherited.reduce((total, item) => total + Math.round(Number(item.amount) * 100), 0)
    const newCents = this.data.choices.reduce((total, item) => total + Math.round(Number(item.price) * 100) * item.quantity, 0)
    const minutes = this.data.inherited.reduce((total, item) => total + item.minutes, 0)
      + this.data.choices.reduce((total, item) => total + item.minutes * item.quantity, 0)
    this.setData({ total: ((inheritedCents + newCents) / 100).toFixed(2), minutes })
  },
  editReason(event) { this.setData({ reason: event.detail.value, error: '' }) },
  publish() {
    if (!requireAccount('TECH') || !this.data.order || this.data.busy) return
    if (!demoMode) return this.publishLive()
    const order = this.data.order
    this.setData({ busy: true, error: '' })
    try {
      submitQuote(this.id, {
        rowVersion: order.rowVersion,
        approvedQuoteId: order.approvedQuote ? order.approvedQuote.id : null,
        pendingQuoteId: order.quote && order.quote.status === 'PENDING' ? order.quote.id : null,
        items: [
          ...this.data.inherited,
          ...this.data.choices.filter(item => item.quantity > 0)
            .map(item => ({ lineKey: item.lineKey, serviceId: item.id, quantity: item.quantity }))
        ],
        reason: this.data.reason
      })
      wx.navigateBack()
    } catch (error) {
      this.setData({ error: error.message })
    } finally {
      this.setData({ busy: false })
    }
  },
  publishLive() {
    const { order, purpose, reason, choices, inherited } = this.data
    const selected = choices.filter(item => item.quantity > 0)
    if (!selected.length) return this.setData({ error: '请选择至少一项报价项目。' })
    return live.mutate(this, () => live.command(this, '/orders/' + this.id + '/quotes', {
      purpose: purpose === '增项报价' ? 'EXTRA' : 'INITIAL', expectedRowVersion: order.rowVersion,
      expectedApprovedQuoteId: order.approvedQuote?.id || null, expectedPendingQuoteId: order.quote?.id || null,
      items: [
        // 后端要求保留旧行的身份和数量，已批准行的价格与工时沿用原快照。
        ...inherited.map(item => ({ lineKey: item.lineKey, serviceItemId: item.serviceId, quantity: item.quantity,
          catalogVersion: choices.find(service => service.id === item.serviceId)?.version || '1' })),
        ...selected.map(item => ({ lineKey: item.lineKey, serviceItemId: item.id, quantity: item.quantity, catalogVersion: item.version })),
      ],
      ...(reason.trim() ? { reason: reason.trim() } : {}),
    }), () => wx.navigateBack())
  },
})
