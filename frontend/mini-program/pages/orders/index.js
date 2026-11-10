const { listOrders, services, vehicles } = require('../../data/demo.js')
const { requireAccount, refreshAccount, signOut, demoMode } = require('../../data/auth.js')
const live = require('../../data/live.js')

const { readMessages, unreadCount } = require('../../data/messages.js')
const { guidance } = require('../../data/technician.js')
const ownerTabs = [{ id: 'home', label: '首页' }, { id: 'messages', label: '聊天' }, { id: 'mine', label: '我的' }]
const techTabs = [{ id: 'home', label: '订单' }, { id: 'messages', label: '消息' }, { id: 'mine', label: '我的' }]
const ownerOrderFilters = [
  { id: 'unpaid', label: '待付款', empty: '当前没有待付款订单。' },
  { id: 'shipping', label: '待收货', empty: '上门保养订单没有待收货环节。' },
  { id: 'install', label: '待安装', status: 'ready', empty: '当前没有已确认报价、等待开工的订单。' },
  { id: 'review', label: '待评价', empty: '当前没有待评价订单。' },
  { id: 'aftersales', label: '退款/售后', empty: '当前没有退款或售后记录。' }
]
const techOrderFilters = [
  { id: 'accept', icon: 'unpaid', label: '待接单', statuses: ['reserved'], empty: '当前没有待接单工单。' },
  { id: 'visit', icon: 'shipping', label: '待上门', statuses: ['accepted', 'departed'], empty: '当前没有待上门工单。' },
  { id: 'quote', icon: 'review', label: '报价中', statuses: ['arrived', 'quoted', 'extraQuoted'], empty: '当前没有报价中的工单。' },
  { id: 'service', icon: 'install', label: '服务中', statuses: ['ready', 'working'], empty: '当前没有待开工或服务中的工单。' },
  { id: 'complete', icon: 'aftersales', label: '已完工', statuses: ['completed', 'closed'], empty: '当前没有已完工工单。' }
]
const actionableStates = ['reserved', 'accepted', 'departed', 'arrived', 'ready', 'working']
const liveOwnerFilters = [
  { id: 'shipping', label: '待上门', statuses: ['reserved', 'accepted', 'departed'], empty: '暂无待上门订单。' },
  { id: 'unpaid', label: '待确认', statuses: ['quoted', 'extraQuoted'], empty: '暂无待确认报价。' },
  { id: 'install', label: '保养中', statuses: ['ready', 'working'], empty: '暂无保养中的订单。' },
  { id: 'review', label: '待验收', statuses: ['completed'], empty: '暂无待验收订单。' },
  { id: 'aftersales', label: '已归档', statuses: ['closed'], empty: '暂无已归档订单。' },
]

Page({
  data: { account: null, isTech: false, orders: [], displayedServices: demoMode ? services.slice(0, 8) : [], servicesExpanded: false, vehicle: null, tab: 'home', tabs: ownerTabs, currentJob: null, actionableCount: 0, waitingCount: 0, conversations: [], unreadTotal: 0, stats: null, query: '', searching: false, matchedOrders: [], matchedServices: [], orderFilters: ownerOrderFilters, ownerOrderFilter: 'all', ownerOrderRows: [], ownerOrderEmpty: '暂无订单。', demoMode, error: '', loading: false },
  onShow() {
    const account = requireAccount()
    if (!account) return
    if (!demoMode) return this.loadLive(account)
    const isTech = account.role === 'TECH'
    const page = isTech ? 'technician' : 'owner'
    const orders = listOrders().map(order => ({
      ...order, url: '/pages/' + page + '/index?id=' + order.id
    }))
    const actionable = orders.filter(order => actionableStates.includes(order.status))
    const waiting = orders.filter(order => ['quoted', 'extraQuoted', 'completed'].includes(order.status))
    const currentJob = actionable[0] || waiting[0]
    const completed = orders.filter(order => ['completed', 'closed'].includes(order.status))
    const workMinutes = completed.reduce((total, order) => total + (order.workMinutes || 0), 0)
    const amountCents = completed.reduce((total, order) => total + Math.round(Number(order.quote ? order.quote.total : 0) * 100), 0)
    const conversations = orders.map(order => {
      const messages = readMessages(order.id)
      const last = messages[messages.length - 1]
      return {
        id: order.id, car: order.car, service: order.service,
        name: isTech ? order.car + '车主' : order.tech,
        avatar: isTech ? '车' : '技',
        preview: last ? (last.role === account.role ? '我：' : '') + last.text : '点击开始沟通',
        time: last ? last.time : '',
        lastId: last ? last.id : 0,
        unread: unreadCount(order.id, account.role, messages),
        url: '/pages/chat/index?id=' + order.id
      }
    }).sort((left, right) => right.lastId - left.lastId)
    this.setData({
      account, isTech, orders, orderFilters: isTech ? techOrderFilters : ownerOrderFilters, tabs: isTech ? techTabs : ownerTabs,
      vehicle: isTech ? null : vehicles[vehicles.length - 1] || null,
      currentJob: isTech && currentJob ? { ...currentJob, guidance: guidance[currentJob.status] } : null,
      actionableCount: actionable.length, waitingCount: waiting.length, conversations,
      unreadTotal: conversations.reduce((total, thread) => total + thread.unread, 0),
      stats: { count: completed.length, hours: Math.floor(workMinutes / 60), minutes: workMinutes % 60, amount: (amountCents / 100).toFixed(2) }
    })
    this.filterHome(this.data.query)
    this.filterOwnerOrders(this.data.ownerOrderFilter)
  },
  async loadLive(account) {
    if (this.data.loading) return
    this.setData({ account, loading: true, error: '' })
    try {
      const profile = await refreshAccount()
      if (!profile) return
      const isTech = profile.role === 'TECH'
      this.setData({ account: profile, isTech, tabs: (isTech ? techTabs : ownerTabs).filter(tab => tab.id !== 'messages'),
        orderFilters: isTech ? techOrderFilters : liveOwnerFilters })
      const [cars, catalog] = isTech ? [[], []] : await Promise.all([live.vehicles(), live.catalog()])
      const items = await live.orders(cars)
      const orders = items.map(order => ({ ...order, url: '/pages/' + (isTech ? 'technician' : 'owner') + '/index?id=' + order.id }))
      const actionable = orders.filter(order => actionableStates.includes(order.status))
      const waiting = orders.filter(order => ['quoted', 'extraQuoted', 'completed'].includes(order.status))
      const current = actionable[0] || waiting[0]
      this.catalog = catalog
      this.setData({ orders, vehicle: cars.at(-1) || null, actionableCount: actionable.length, waitingCount: waiting.length,
        currentJob: isTech && current ? { ...current, guidance: guidance[current.status] } : null })
      this.filterHome(this.data.query)
      this.filterOwnerOrders(this.data.ownerOrderFilter)
    } catch (error) {
      this.setData({ error: error.message })
    } finally {
      this.setData({ loading: false })
    }
  },
  searchHome(event) { this.filterHome(event.detail.value) },
  clearSearch() { this.filterHome('') },
  toggleServices() {
    this.setData({ servicesExpanded: !this.data.servicesExpanded })
    this.filterHome(this.data.query)
  },
  filterHome(query) {
    const keyword = query.trim().toLowerCase()
    const matches = value => String(value || '').toLowerCase().includes(keyword)
    const catalog = demoMode ? services : this.catalog || []
    const matchedServices = catalog.filter(service => matches(service.name) || matches(service.label))
    this.setData({
      query, searching: Boolean(keyword),
      matchedOrders: this.data.orders.filter(order =>
        [order.id, order.orderNo, order.car, order.plate, order.service, order.statusLabel].some(matches)),
      matchedServices,
      displayedServices: keyword ? matchedServices : catalog.slice(0, this.data.servicesExpanded ? catalog.length : 8)
    })
  },
  selectOwnerOrders(event) { this.filterOwnerOrders(event.currentTarget.dataset.filter) },
  filterOwnerOrders(id) {
    const filter = this.data.orderFilters.find(item => item.id === id)
    this.setData({
      ownerOrderFilter: filter ? filter.id : 'all',
      ownerOrderRows: filter ? this.data.orders.filter(order => filter.statuses ? filter.statuses.includes(order.status) : order.status === filter.status) : this.data.orders,
      ownerOrderEmpty: filter ? filter.empty : '暂无订单。'
    })
  },
  switchTab(event) {
    const tab = event.currentTarget.dataset.tab
    if (this.data.tabs.some(item => item.id === tab)) this.setData({ tab })
  },
  logout() {
    signOut()
    wx.reLaunch({ url: '/pages/preview/index' })
  }
})








