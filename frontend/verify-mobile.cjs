const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vue = require('./admin-web/node_modules/vue/dist/vue.cjs.js')
const { renderToString } = require('./admin-web/node_modules/@vue/server-renderer')
const root = path.join(__dirname, 'mini-program')
require(path.join(root, 'data/config.js')).apiBase = ''
const { signIn, signOut } = require(path.join(root, 'data/auth.js'))

function page(name, options = {}) {
  const file = require.resolve(path.join(root, 'pages', name, 'index.js'))
  let instance
  global.Page = definition => { instance = definition }
  try {
    delete require.cache[file]
    require(file)
  } finally {
    delete global.Page
  }
  instance.data = structuredClone(instance.data)
  instance.setData = patch => Object.assign(instance.data, patch)
  instance.onLoad?.(options)
  instance.onShow?.()
  return instance
}

const tap = (key, value) => ({ currentTarget: { dataset: { [key]: value } } })
const input = value => ({ detail: { value } })

async function verifyMobile() {
  const { getOrder } = require(path.join(root, 'data/demo.js'))
  const config = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'))
  for (const route of config.pages) {
    JSON.parse(fs.readFileSync(path.join(root, route + '.json'), 'utf8'))
    assert.ok(fs.statSync(path.join(root, route + '.wxml')).size)
  }
  let orderId
  let destination
  global.wx = {
    reLaunch({ url }) { destination = url },
    redirectTo({ url }) { orderId = url.split('=')[1] },
    showModal({ success }) { success({ confirm: true }) },
    showToast() {}, navigateBack() {}
  }
  try {
    signOut()
    page('orders', { role: 'technician' })
    assert.equal(destination, '/pages/preview/index')
    const login = page('preview')
    const credentials = (loginName, password) => ({ detail: { value: { loginName, password } } })
    await login.login(credentials('demo-owner', 'wrong'))
    assert.match(login.data.error, /账号或密码/)
    await login.login(credentials('demo-owner', 'demo1234'))
    assert.equal(destination, '/pages/orders/index')
    assert.equal(page('orders', { role: 'technician' }).data.isTech, false)
    assert.equal(page('technician').data.order, null)
    const booking = page('booking', { service: 'air' })
    assert.deepEqual(booking.data.selectedServices.map(item => item.id), ['air'])
    booking.chooseService(tap('id', 'oil'))
    booking.chooseService(tap('id', 'wiper'))
    booking.changeQuantity({ currentTarget: { dataset: { id: 'wiper', delta: 1 } } })
    assert.equal(booking.data.selectedServices.length, 3)
    assert.equal(booking.data.totalMinutes, 130)
    assert.equal(booking.data.estimatedAmount, '456.00')
    booking.chooseService(tap('id', 'wiper'))
    assert.equal(booking.data.selectedServices.length, 2)
    booking.chooseService(tap('id', 'wiper'))
    booking.changeQuantity({ currentTarget: { dataset: { id: 'wiper', delta: 1 } } })
    const emptyBooking = page('booking')
    emptyBooking.next()
    emptyBooking.chooseService(tap('id', 'oil'))
    emptyBooking.next()
    assert.equal(emptyBooking.data.step, 1)
    assert.match(emptyBooking.data.error, /至少选择一个/)
    const garage = page('vehicles')
    garage.start()
    garage.allBrands()
    garage.search(input('本田'))
    assert.equal(garage.data.brands.length, 1)
    garage.chooseBrand(tap('name', '本田'))
    garage.chooseGroup(tap('index', 1))
    assert.equal(garage.data.group.name, 'SUV')
    garage.chooseGroup(tap('index', 0))
    garage.chooseModel(tap('name', '思域'))
    garage.editPlate(input('粤B·•••16'))
    garage.save()
    booking.onShow()
    assert.equal(booking.data.vehicles.at(-1).car, '本田思域')
    booking.chooseVehicle(tap('id', booking.data.vehicles.at(-1).id))
    const ownerHome = page('orders')
    assert.equal(ownerHome.data.displayedServices.length, 8)
    ownerHome.toggleServices()
    const { services } = require(path.join(root, 'data/demo.js'))
    assert.equal(ownerHome.data.displayedServices.length, services.length)
    for (const service of services) {
      assert.ok(fs.statSync(path.join(root, 'assets', service.id + '.svg')).size)
      assert.deepEqual(page('booking', { service: service.id }).data.selectedServices.map(item => item.id), [service.id])
    }
    ownerHome.toggleServices()
    assert.equal(ownerHome.data.displayedServices.length, 8)
    ownerHome.searchHome(input('胎压'))
    assert.deepEqual(ownerHome.data.displayedServices.map(service => service.id), ['pressure'])
    ownerHome.clearSearch()
    assert.equal(ownerHome.data.displayedServices.length, 8)
    ownerHome.searchHome(input('空调'))
    assert.equal(ownerHome.data.matchedServices[0].id, 'air')
    ownerHome.searchHome(input('不存在'))
    assert.equal(ownerHome.data.matchedOrders.length, 0)
    ownerHome.clearSearch()
    assert.equal(ownerHome.data.searching, false)
    ownerHome.switchTab(tap('tab', 'messages'))
    assert.equal(ownerHome.data.tab, 'messages')
    ownerHome.switchTab(tap('tab', 'mine'))
    assert.equal(ownerHome.data.tab, 'mine')
    ownerHome.selectOwnerOrders(tap('filter', 'shipping'))
    assert.match(ownerHome.data.ownerOrderEmpty, /没有待收货环节/)
    assert.equal(ownerHome.data.ownerOrderRows.length, 0)
    ownerHome.selectOwnerOrders(tap('filter', 'all'))
    assert.ok(ownerHome.data.ownerOrderRows.length)
    booking.next()
    booking.next()
    assert.equal(booking.data.step, 1)
    assert.match(booking.data.error, /上门时段/)
    booking.chooseWindow(tap('id', 'morning'))
    booking.next()
    assert.equal(booking.data.step, 2)
    booking.book()
    assert.equal(getOrder(orderId).status, 'reserved')
    assert.equal(getOrder(orderId).bookingItems.length, 3)
    assert.equal(getOrder(orderId).bookingItems.find(item => item.serviceId === 'wiper').quantity, 2)
    assert.equal(getOrder(orderId).committedEnd - getOrder(orderId).serviceStart, 130 * 60000)
    const bookedId = orderId
    booking.book()
    assert.equal(orderId, bookedId)
    const bookingDetails = page('owner', { id: orderId })
    bookingDetails.toggleTime()
    bookingDetails.changeTime(tap('id', 'afternoon'))
    assert.equal(bookingDetails.data.order.windowId, 'afternoon')
    bookingDetails.changeTime(tap('id', 'morning'))
    assert.equal(bookingDetails.data.order.windowId, 'morning')
    page('orders').logout()
    assert.equal(destination, '/pages/preview/index')
    await signIn('demo-tech', 'demo1234')
    const list = page('orders', { role: 'owner' })
    assert.equal(list.data.isTech, true)
    list.selectOwnerOrders(tap('filter', 'accept'))
    assert.ok(list.data.ownerOrderRows.some(order => order.id === orderId))
    list.selectOwnerOrders(tap('filter', 'complete'))
    assert.equal(list.data.ownerOrderRows.length, 0)
    assert.equal(page('support').data.allowed, true)
    list.searchHome(input(orderId.toLowerCase()))
    assert.deepEqual(list.data.matchedOrders.map(order => order.id), [orderId])
    assert.equal(list.data.orders[0].url, '/pages/technician/index?id=' + orderId)
    const techChat = page('chat', { id: orderId })
    techChat.edit(input('我已准备出发'))
    techChat.send()
    const tech = page('technician', { id: orderId })
    for (const action of ['accept', 'depart', 'arrive']) tech.act(tap('action', action))
    assert.equal(tech.data.order.status, 'arrived')
    const quote = page('quote', { id: orderId })
    assert.equal(quote.data.choices.filter(item => item.quantity > 0).length, 3)
    quote.publish()
    assert.match(quote.data.error, /报价说明/)
    quote.editReason(input('现场检查，按目录更换空调滤芯'))
    quote.publish()
    tech.onShow()
    assert.equal(tech.data.order.quote.total, '456.00')
    await signIn('demo-owner', 'demo1234')
    assert.equal(page('orders').data.conversations.find(item => item.id === orderId).unread, 1)
    const ownerChat = page('chat', { id: orderId })
    assert.equal(ownerChat.data.messages[0].text, '我已准备出发')
    assert.equal(page('orders').data.conversations.find(item => item.id === orderId).unread, 0)
    ownerChat.edit(input('收到，车在地库'))
    ownerChat.send()
    const owner = page('owner', { id: orderId })
    owner.toggleQuote()
    assert.equal(owner.data.showQuote, true)
    owner.act(tap('action', 'reject'))
    owner.reject()
    assert.match(owner.data.error, /拒绝原因/)
    owner.editRejectReason(input('请再次核对服务内容'))
    owner.reject()
    await signIn('demo-tech', 'demo1234')
    tech.onShow()
    assert.equal(tech.data.order.status, 'arrived')
    const revised = page('quote', { id: orderId })
    revised.editReason(input('复核后保持目录报价'))
    revised.publish()
    await signIn('demo-owner', 'demo1234')
    owner.onShow()
    assert.equal(owner.data.order.quote.total, '456.00')
    owner.act(tap('action', 'confirm'))
    await signIn('demo-tech', 'demo1234')
    tech.onShow()
    techChat.onShow()
    assert.equal(techChat.data.messages[1].role, 'OWNER')
    tech.act(tap('action', 'start'))
    const originalNow = Date.now
    const finishAt = originalNow() + 65 * 60000
    try {
      Date.now = () => finishAt
      tech.act(tap('action', 'complete'))
    } finally {
      Date.now = originalNow
    }
    assert.deepEqual(page('orders').data.stats, { count: 1, hours: 1, minutes: 5, amount: '456.00' })
    await signIn('demo-owner', 'demo1234')
    owner.onShow()
    assert.equal(owner.data.order.status, 'completed')
    owner.act(tap('action', 'close'))
    assert.equal(getOrder(orderId).status, 'closed')
    const conflict = page('booking')
    conflict.next()
    conflict.chooseWindow(tap('id', 'morning'))
    conflict.next()
    conflict.book()
    assert.match(conflict.data.error, /时段已被预约/)
    console.log('Mini program: login, role guards, multi-service booking/quantities/totals/one-order submit, vehicle, reschedule, slot conflict, combined quote, chat and full sample order flow passed')
  } finally {
    signOut()
    delete global.wx
  }
}

function templateFromWxml(wxml) {
  return wxml
    .replace(/wx:for="{{(.*?)}}"/g, 'v-for="(item, index) in $1"')
    .replace('wx:key="*this"', ':key="item"')
    .replace(/wx:key="([^"]+)"/g, ':key="item.$1"')
    .replace(/wx:(if|elif)="{{(.*?)}}"/g, (_, type, expression) =>
      (type === 'if' ? 'v-if' : 'v-else-if') + '="' + expression.replaceAll('&', '&amp;') + '"')
    .replace(/wx:else/g, 'v-else')
    .replace(/\s(?:bindtap|bindinput|data-action|data-id)="[^"]*"/g, '')
    .replace(/\b(class|value|src|placeholder)="([^"]*{{[^"]*)"/g, (_, key, value) => {
      const parts = value.split(/{{(.*?)}}/).map((part, index) => index % 2 ? '(' + part + ')' : JSON.stringify(part))
      return ':' + key + '="' + parts.join(' + ').replaceAll('&', '&amp;').replaceAll('"', '&quot;') + '"'
    })
    .replace(/<image\b([^>]*?)\/?\s*>/g, '<img$1>')
    .replace(/<(\/?)navigator\b/g, '<$1div')
    .replace(/<(\/?)view\b/g, '<$1div')
    .replace(/<(\/?)text\b/g, '<$1span')
    .replace(/<(\/?)block\b/g, '<$1template')
}

function readStyles(file) {
  return fs.readFileSync(file, 'utf8').replace(/(-?[\d.]+)rpx/g, (_, value) => (Number(value) * 390 / 750) + 'px')
}

async function renderReview(role = 'owner') {
  await signIn('demo-owner', 'demo1234')
  const booking = page('booking')
  booking.chooseService(tap('id', 'air'))
  booking.chooseService(tap('id', 'wiper'))
  booking.changeQuantity({ currentTarget: { dataset: { id: 'wiper', delta: 1 } } })
  booking.next()
  booking.chooseWindow(tap('id', 'morning'))
  const home = page('orders')
  const ownerMessages = page('orders')
  ownerMessages.switchTab(tap('tab', 'messages'))
  const mine = page('orders')
  mine.switchTab(tap('tab', 'mine'))
  const brand = page('vehicles')
  brand.start()
  const model = page('vehicles')
  model.start()
  model.chooseBrand(tap('name', '宝马'))
  signOut()
  let frames = [
    { name: 'orders', label: '首页', instance: home },
    { name: 'orders', label: '聊天', instance: ownerMessages },
    { name: 'orders', label: '我的', instance: mine },
    { name: 'vehicles', label: '选择品牌', instance: brand },
    { name: 'vehicles', label: '选择车系', instance: model },
    { name: 'booking', label: '服务与预约时段', instance: booking }
  ]
  if (role === 'technician') {
    await signIn('demo-tech', 'demo1234')
    const workbench = page('orders')
    const jobs = page('orders')
    jobs.switchTab(tap('tab', 'messages'))
    const profile = page('orders')
    profile.switchTab(tap('tab', 'mine'))
    signOut()
    frames = [
      { name: 'orders', label: '技师 · 订单', instance: workbench },
      { name: 'orders', label: '技师 · 消息', instance: jobs },
      { name: 'orders', label: '技师 · 我的', instance: profile }
    ]
  }
  if (role === 'services') {
    await signIn('demo-owner', 'demo1234')
    const expanded = page('orders')
    expanded.toggleServices()
    const search = page('orders')
    search.searchHome(input('胎压'))
    signOut()
    frames = [
      { name: 'orders', label: '首页 · 八项上门服务', instance: home },
      { name: 'orders', label: '更多服务 · 展开后', instance: expanded },
      { name: 'orders', label: '搜索 · 胎压检测', instance: search }
    ]
  }
  if (role === 'd3') {
    const { getOrder, decideQuote, applyAction } = require('./mini-program/data/demo.js')
    const id = 'AC-1103-018'
    const quote = getOrder(id).quote
    decideQuote(id, 'confirm', quote.id, quote.version)
    applyAction(id, 'start')
    await signIn('demo-tech', 'demo1234')
    const draft = page('quote', { id })
    draft.changeQuantity({ currentTarget: { dataset: { id: 'air', delta: 1 } } })
    draft.editReason(input('现场检查发现空调滤芯需要更换，征求车主确认。'))
    // Keep the unsent form as a separate visual fixture.
    const snapshot = { data: structuredClone(draft.data) }
    const previousWx = global.wx
    global.wx = { navigateBack() {} }
    try { draft.publish() } finally { global.wx = previousWx }
    const tech = page('technician', { id })
    await signIn('demo-owner', 'demo1234')
    const owner = page('owner', { id })
    owner.toggleQuote()
    signOut()
    frames = [
      { name: 'owner', label: '车主 · 确认增项', instance: owner },
      { name: 'technician', label: '技师 · 报价与延期', instance: tech },
      { name: 'quote', label: '技师 · 增项报价编辑', instance: snapshot }
    ]
  }
  const cards = []
  for (const frame of frames) {
    const dir = path.join(root, 'pages', frame.name)
    const template = templateFromWxml(fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8'))
    const view = await renderToString(vue.createSSRApp({ data: () => frame.instance.data, template }))
    cards.push('<section><h2>' + frame.label + '</h2><article>' + view + '</article></section>')
  }
  const styles = ['app.wxss', 'pages/booking/index.wxss', 'pages/orders/index.wxss', 'pages/vehicles/index.wxss', 'pages/technician/index.wxss', 'pages/quote/index.wxss']
    .map(file => readStyles(path.join(root, file))).join('\n').replace(/^page \{/m, 'article {').replace(/\bimage(?=\s*\{)/g, 'img').replace(/\bnavigator(?=\s*\{)/g, 'div').replace(/position: fixed/g, 'position: absolute')
  return '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>小程序页面预览</title><style>'
    + styles + '\nbody{margin:0;padding:24px;background:#e5eceb;font:15px/1.5 system-ui;color:#172c37}'
    + 'header{margin:0 auto 20px;width:1210px}h1,h2,p{margin:0}h1{font-size:25px}header p{color:#52666d}'
    + 'main{display:flex;gap:20px;width:1210px;margin:auto;align-items:flex-start;flex-wrap:wrap}section{width:390px}'
    + 'h2{font-size:15px;margin-bottom:8px}article{border-radius:12px;overflow:hidden;min-height:585px;position:relative}'
    + 'img{object-fit:contain}button{border:0}button,input{font-family:inherit;box-sizing:border-box}button.action,button.secondary{display:block;width:100%;border:0}'
    + 'button.secondary{border:1px solid #b9cbcc}input{width:100%;font-size:inherit}'
    + '</style><header><h1>微信小程序页面预览</h1><p>同一套小程序模板的浏览器渲染 · 示例数据</p></header><main>'
    + cards.join('') + '</main></html>'
}

module.exports = { verifyMobile, renderReview, page, tap, input }












