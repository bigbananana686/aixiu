const { requireAccount } = require('../../data/auth.js')
const { getOrder } = require('../../data/demo.js')
const { readMessages, sendMessage, markRead } = require('../../data/messages.js')

Page({
  data: { order: null, account: null, messages: [], draft: '', lastMessage: '' },
  onLoad(options) { this.id = options.id },
  onShow() {
    const account = requireAccount()
    if (!account) return
    this.setData({ account, order: getOrder(this.id) || null })
    this.refresh()
  },
  refresh() {
    const messages = readMessages(this.id)
    markRead(this.id, this.data.account.role)
    const last = messages[messages.length - 1]
    this.setData({ messages, lastMessage: last ? 'message-' + last.id : '' })
  },
  edit(event) { this.setData({ draft: event.detail.value }) },
  send() {
    const account = requireAccount()
    if (!account || !sendMessage(this.id, account.role, this.data.draft)) return
    this.setData({ draft: '' })
    this.refresh()
  }
})

