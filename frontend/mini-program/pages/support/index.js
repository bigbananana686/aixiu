const { demoMode, requireAccount } = require('../../data/auth.js')
const { listOrders } = require('../../data/demo.js')
const { listFeedback, submitFeedback } = require('../../data/feedback.js')

const labels = { FEEDBACK: '问题反馈', COMPLAINT: '服务投诉' }

Page({
  data: {
    allowed: false, type: '', title: '联系客服', available: demoMode, orders: [],
    orderIndex: 0, detail: '', error: '', busy: false, saved: false, history: [], historyError: ''
  },
  onLoad(options) {
    const type = options.type === 'complaint' ? 'COMPLAINT' : options.type === 'feedback' ? 'FEEDBACK' : ''
    this.setData({ type, title: labels[type] || '联系客服' })
  },
  async onShow() {
    const account = requireAccount(this.data.type ? 'OWNER' : undefined)
    if (!account) return
    const orders = [{ id: '', label: this.data.type === 'COMPLAINT' ? '请选择订单 ›' : '不关联订单' }]
      .concat(listOrders().map(order => ({ id: order.id, label: order.car + ' · ' + order.id })))
    this.setData({ allowed: true, isOwner: account.role === 'OWNER', orders })
    if (this.data.type && demoMode) await this.loadHistory()
  },
  async loadHistory() {
    try {
      const records = await listFeedback()
      this.setData({ history: records.filter(item => item.type === this.data.type), historyError: '' })
    } catch (error) {
      this.setData({ historyError: error.message })
    }
  },
  chooseOrder(event) {
    this.requestId = null
    this.setData({ orderIndex: Number(event.detail.value), error: '' })
  },
  edit(event) {
    this.requestId = null
    this.setData({ detail: event.detail.value, error: '' })
  },
  async save() {
    const account = requireAccount('OWNER')
    if (!account || !demoMode || !this.data.type || this.data.busy || this.data.saved) return
    const orderId = this.data.orders[this.data.orderIndex]?.id || ''
    const detail = this.data.detail.trim()
    if (!detail || (this.data.type === 'COMPLAINT' && !orderId)) {
      this.setData({ error: this.data.type === 'COMPLAINT' ? '请选择订单并填写问题说明。' : '请填写问题说明。' })
      return
    }
    this.requestId ||= Date.now().toString(36) + '-' + Math.random().toString(36).slice(2)
    this.setData({ busy: true, error: '' })
    try {
      await submitFeedback({ id: this.requestId, type: this.data.type, orderId, detail })
      this.setData({ saved: true, detail: '' })
      await this.loadHistory()
    } catch (error) {
      this.setData({ error: error.message })
    } finally {
      this.setData({ busy: false })
    }
  },
  startAgain() {
    this.requestId = null
    this.setData({ saved: false, orderIndex: 0, detail: '', error: '' })
  }
})
