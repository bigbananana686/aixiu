const { requireAccount, demoMode } = require('../../data/auth.js')
const live = require('../../data/live.js')

const topics = {
  OWNER: [
    { title: '怎么预约上门保养？', keywords: /预约|上门|改期/, answer: '在首页选择保养项目，选好车辆和上门时段，再填写停车位置。已预约的订单可以在详情查看是否支持改期。' },
    { title: '怎么查看和确认报价？', keywords: /报价|金额|费用/, answer: '在订单详情点「查看报价」，核对项目和金额后再确认。有疑问可以点「联系技师」。' },
    { title: '到哪里看保养进度？', keywords: /进度|订单|验收/, answer: '在首页的「服务进度」或「我的订单」打开对应订单。技师记录完工后，可以在订单详情确认验收。' }
  ],
  TECH: [
    { title: '如何更新工单进度？', keywords: /工单|接单|进度|完工/, answer: '打开当前工单，按页面提示确认出发、到达并报价。车主确认报价后，再开工并记录完工。' },
    { title: '怎么发报价给车主？', keywords: /报价|金额|费用/, answer: '确认到达后，在工单详情填写初始报价并发送。需要说明项目和金额时，可先联系车主。' },
    { title: '怎么联系车主？', keywords: /联系|沟通|协商|停车/, answer: '在「消息」里打开对应订单，或在工单详情点「联系车主」，可以沟通停车位置、到达时间和服务项目。' }
  ]
}

Page({
  data: { account: null, suggestions: [], draft: '', messages: [], lastMessage: '', demoMode, conversations: [], current: null, busy: false, error: '' },
  onShow() {
    const account = requireAccount()
    if (!account) return
    this.setData({ account, suggestions: topics[account.role].map(topic => topic.title) })
    if (!demoMode) return live.mutate(this, () => this.loadConversations())
  },
  edit(event) { this.setData({ draft: event.detail.value }) },
  choose(event) { this.reply(event.currentTarget.dataset.question) },
  send() { this.reply(this.data.draft) },
  reply(input) {
    const account = requireAccount()
    const question = input.trim()
    if (!account || !question) return
    if (!demoMode) return this.sendLive(question)
    const available = topics[account.role]
    const topic = available.find(item => item.title === question)
      || available.find(item => item.keywords.test(question))
    const answer = topic ? topic.answer : '这条问题暂时没有对应指引，可以试试上方的常见问题。'
    const messages = this.data.messages
    const first = messages.length + 1
    this.setData({
      draft: '',
      messages: [...messages, { id: first, mine: true, text: question }, { id: first + 1, mine: false, text: answer }],
      lastMessage: 'assistant-' + (first + 1)
    })
  },
  async loadConversations() {
    const conversations = (await live.listAll('/ai/conversations')).map(item => ({
      ...item, label: live.formatTime(item.updatedAt),
    }))
    this.setData({ conversations })
  },
  async openConversation(id) {
    const data = await live.api('/ai/conversations/' + id)
    const messages = data.turns.flatMap((turn, index) => [
      { id: 2 * index + 1, mine: true, text: turn.message }, { id: 2 * index + 2, mine: false, text: turn.reply },
    ])
    this.setData({ current: { id: data.conversationId, revision: data.revision }, messages,
      lastMessage: messages.length ? 'assistant-' + messages.at(-1).id : '' })
  },
  pickConversation(event) {
    const item = this.data.conversations[Number(event.detail.value)]
    if (item) return live.mutate(this, () => this.openConversation(item.conversationId))
  },
  newConversation() { this.setData({ current: null, messages: [], lastMessage: '', draft: '', error: '' }) },
  removeConversation() {
    const current = this.data.current
    if (!current) return
    wx.showModal({ title: '删除会话', content: '删除这段会话记录？', success: ({ confirm }) => {
      if (confirm) return live.mutate(this, () => live.api('/ai/conversations/' + current.id, { method: 'DELETE' }), async () => {
        this.newConversation()
        await this.loadConversations()
      })
    } })
  },
  refresh() { if (this.data.current) return this.openConversation(this.data.current.id) },
  sendLive(question) {
    return live.mutate(this, async () => {
      let current = this.data.current
      if (!current) {
        const created = await live.command(this, '/ai/conversations', {})
        current = { id: created.conversationId, revision: created.revision }
        this.setData({ current })
      }
      const turn = await live.command(this, '/ai/conversations/' + current.id + '/messages', { expectedRevision: current.revision, message: question })
      const messages = this.data.messages
      const id = messages.length + 1
      this.setData({ current: { id: current.id, revision: turn.revision },
        messages: [...messages, { id, mine: true, text: question }, { id: id + 1, mine: false, text: turn.reply }],
        draft: '', lastMessage: 'assistant-' + (id + 1) })
      await this.loadConversations()
    })
  },
})
