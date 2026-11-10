const { requireAccount, demoMode } = require('../../data/auth.js')
const { getOrder, applyAction, decideQuote, windows, reschedule } = require('../../data/demo.js')
const live = require('../../data/live.js')
const analysis = require('../../data/quote-analysis.js')

const ownerActions = {
  confirm: { title: '确认报价', content: '请核对完整服务明细和总价。确认增项后将按新的服务范围继续施工。' },
  cancel: { title: '取消预约', content: '预约取消后，当前工单将结束。' },
  close: { title: '确认验收', content: '确认后，这笔保养订单将归档。' }
}

Page({
  data: { order: null, showQuote: false, windows: demoMode ? windows : [], changingTime: false, rejecting: false, rejectReason: '', error: '',
    demoMode, busy: false, loading: false, date: live.day(), earliest: live.day(), latest: live.day(30), analysisJob: null },
  onLoad(options) { this.id = options.id || (demoMode ? 'AC-1103-018' : '') },
  onShow() { if (requireAccount('OWNER')) return this.refresh() },
  refresh() {
    if (!demoMode) return this.refreshLive()
    const order = getOrder(this.id) || null
    this.setOrder(order)
  },
  setOrder(order) {
    this.setData({ order, addedAmount: order && order.approvedQuote && order.quote
      ? ((Math.round(Number(order.quote.total) * 100) - Math.round(Number(order.approvedQuote.total) * 100)) / 100).toFixed(2) : '' })
  },
  async refreshLive() {
    if (!this.id) return this.setData({ error: '缺少订单编号。' })
    this.setData({ loading: true })
    try { this.setOrder(await live.order(this.id)) }
    catch (error) { this.setData({ error: error.message }) }
    finally { this.setData({ loading: false }) }
  },
  editRejectReason(event) { this.setData({ rejectReason: event.detail.value, error: '' }) },
  reject() {
    if (!requireAccount('OWNER')) return
    if (!demoMode) {
      const reason = this.data.rejectReason.trim()
      if (!reason) return this.setData({ error: '请填写拒绝原因。' })
      return live.mutate(this, () => live.quoteAction(this, this.data.order, 'reject', reason), async () => {
        this.setData({ rejecting: false, rejectReason: '' })
        await this.refresh()
      })
    }
    const quote = this.data.order.quote
    try {
      decideQuote(this.id, 'reject', quote.id, quote.version, this.data.rejectReason)
      this.setData({ rejecting: false, rejectReason: '', error: '' })
    } catch (error) { this.setData({ error: error.message }) }
    this.refresh()
  },
  toggleQuote() { this.setData({ showQuote: !this.data.showQuote }) },
  toggleTime() { this.setData({ changingTime: !this.data.changingTime, error: '' }) },
  chooseDate(event) { this.setData({ date: event.detail.value, windows: [] }) },
  loadSlots() {
    const { order, date } = this.data
    return live.mutate(this, () => live.slots(date, order.bookingItems, order.technicianId), windows => {
      this.setData({ windows: windows.filter(window => window.start !== order.serviceStart) })
      if (!this.data.windows.length) this.setData({ error: '所选日期没有其他可用时段。' })
    })
  },
  changeTime(event) {
    const windowId = event.currentTarget.dataset.id
    if (!demoMode) {
      const window = this.data.windows.find(item => item.id === windowId)
      if (!window) return
      return live.mutate(this, () => live.orderAction(this, this.data.order, 'reschedule', { serviceStart: window.startAt }), async () => {
        this.setData({ changingTime: false, windows: [] })
        await this.refresh()
      })
    }
    if (!reschedule(this.id, windowId)) return this.setData({ error: '该时段暂不可用，请选择其他时段。' })
    this.setData({ changingTime: false, error: '' })
    this.refresh()
  },
  act(event) {
    if (!requireAccount('OWNER')) return
    const action = event.currentTarget.dataset.action
    if (action === 'reject') return this.setData({ rejecting: !this.data.rejecting, error: '' })
    const prompt = ownerActions[action]
    if (!prompt) return
    const quote = this.data.order.quote
    return wx.showModal({
      ...prompt,
      confirmText: prompt.title,
      success: ({ confirm }) => {
        if (!confirm) return
        if (!demoMode) {
          return live.mutate(this, () => action === 'confirm'
            ? live.quoteAction(this, this.data.order, action)
            : live.orderAction(this, this.data.order, action), async () => {
            this.setData({ rejecting: false })
            await this.refresh()
          })
        }
        try {
          if (action === 'confirm') decideQuote(this.id, action, quote.id, quote.version)
          else if (!applyAction(this.id, action)) throw new Error('订单状态已更新，请刷新后重试。')
          this.setData({ error: '', rejecting: false })
        } catch (error) { this.setData({ error: error.message }) }
        this.refresh()
      }
    })
  },
  requestAnalysis() { return analysis.request(this, this.data.order.quote || this.data.order.approvedQuote) },
  refreshAnalysis() { return analysis.refresh(this) },
})


