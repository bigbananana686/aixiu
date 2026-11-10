const { requireAccount, demoMode } = require('../../data/auth.js')
const { getOrder, applyAction, decideQuote, extendRuntime } = require('../../data/demo.js')
const { guidance } = require('../../data/technician.js')
const live = require('../../data/live.js')
const analysis = require('../../data/quote-analysis.js')

const nextActions = {
  reserved: ['accept', '接单'], accepted: ['depart', '确认出发'], departed: ['arrive', '确认到达'],
  ready: ['start', '开始服务'], working: ['complete', '记录完工']
}

Page({
  data: { order: null, action: null, guidance: '', error: '', showExtension: false, extensionMinutes: '', extensionReason: '',
    demoMode, busy: false, loading: false, completeOpen: false, serviceResult: '', mileage: '', analysisJob: null },
  onLoad(options) { this.id = options.id || (demoMode ? 'AC-1103-018' : '') },
  onShow() { if (requireAccount('TECH')) return this.refresh() },
  refresh() {
    if (!demoMode) return this.refreshLive()
    this.setOrder(getOrder(this.id))
  },
  setOrder(order) {
    const action = order ? nextActions[order.status] : null
    this.setData({ order: order || null, guidance: order ? guidance[order.status] : '', action: action ? { key: action[0], label: action[1] } : null })
  },
  async refreshLive() {
    if (!this.id) return this.setData({ error: '缺少工单编号。' })
    this.setData({ loading: true })
    try { this.setOrder(await live.order(this.id)) }
    catch (error) { this.setData({ error: error.message }) }
    finally { this.setData({ loading: false }) }
  },
  act(event) {
    if (!requireAccount('TECH')) return
    const action = event.currentTarget.dataset.action
    if (!demoMode && action === 'complete') return this.setData({ completeOpen: !this.data.completeOpen })
    if (action === 'decline') {
      return wx.showModal({
        title: '拒绝接单', content: '拒绝后，这笔工单将结束。',
        ...(demoMode ? {} : { editable: true, placeholderText: '请填写拒绝原因' }),
        success: ({ confirm, content }) => {
          if (!confirm) return
          if (!demoMode && !content?.trim()) return this.setData({ error: '请填写拒绝原因。' })
          return this.update('decline', demoMode ? {} : { reason: content.trim() })
        }
      })
    }
    this.update(action)
  },
  update(action, extra = {}) {
    if (!demoMode) return live.mutate(this, () => live.orderAction(this, this.data.order, action, extra), () => this.refresh())
    if (!applyAction(this.id, action)) this.setData({ error: '订单状态已更新，请按当前提示操作。' })
    this.refresh()
  },
  withdraw() {
    if (!requireAccount('TECH')) return
    const quote = this.data.order.quote
    return wx.showModal({
      title: '撤回报价', content: '当前报价将失效，保留原订单和已批准的服务范围。',
      success: ({ confirm }) => {
        if (!confirm) return
        if (!demoMode) return live.mutate(this, () => live.quoteAction(this, this.data.order, 'withdraw'), () => this.refresh())
        try {
          decideQuote(this.id, 'withdraw', quote.id, quote.version)
          this.setData({ error: '' })
        } catch (error) {
          this.setData({ error: error.message })
        }
        this.refresh()
      }
    })
  },
  toggleExtension() { this.setData({ showExtension: !this.data.showExtension, error: '' }) },
  editMinutes(event) { this.setData({ extensionMinutes: event.detail.value, error: '' }) },
  editExtensionReason(event) { this.setData({ extensionReason: event.detail.value, error: '' }) },
  editServiceResult(event) { this.setData({ serviceResult: event.detail.value }) },
  editMileage(event) { this.setData({ mileage: event.detail.value }) },
  complete() {
    const { serviceResult, mileage, order } = this.data
    if (!serviceResult.trim()) return this.setData({ error: '请填写完工结果。' })
    if (mileage && !/^\d{1,7}$/.test(mileage)) return this.setData({ error: '请输入有效里程。' })
    const actualFinish = this.data.finishAttempt || new Date().toISOString()
    this.setData({ finishAttempt: actualFinish })
    return live.mutate(this, () => live.orderAction(this, order, 'complete', {
      actualFinish, serviceResult: serviceResult.trim(), ...(mileage ? { mileage: Number(mileage) } : {}),
    }), async () => {
      this.setData({ completeOpen: false, finishAttempt: '' })
      await this.refresh()
    })
  },
  extend() {
    if (!requireAccount('TECH')) return
    const minutes = Number(this.data.extensionMinutes)
    if (!/^\d+$/.test(this.data.extensionMinutes) || minutes < 1 || minutes > 1440) {
      this.setData({ error: '请输入 1–1440 分钟的延期时长。' })
      return
    }
    if (!demoMode) {
      const reason = this.data.extensionReason.trim()
      if (!reason) return this.setData({ error: '请填写延期原因。' })
      const { order } = this.data
      return live.mutate(this, () => live.orderAction(this, order, 'extension', {
        targetEnd: new Date(order.committedEnd + minutes * 60000).toISOString(), reason,
      }), async () => {
        this.setData({ showExtension: false, extensionMinutes: '', extensionReason: '' })
        await this.refresh()
      })
    }
    try {
      extendRuntime(this.id, this.data.order.rowVersion, this.data.order.committedEnd + minutes * 60000, this.data.extensionReason)
      this.setData({ showExtension: false, extensionMinutes: '', extensionReason: '', error: '' })
    } catch (error) {
      this.setData({ error: error.message })
    }
    this.refresh()
  },
  requestAnalysis() { return analysis.request(this, this.data.order.quote || this.data.order.approvedQuote) },
  refreshAnalysis() { return analysis.refresh(this) },
})
