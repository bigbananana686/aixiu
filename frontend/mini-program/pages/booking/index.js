const { requireAccount, demoMode } = require('../../data/auth.js')
const { services, windows, vehicles, createBooking } = require('../../data/demo.js')
const live = require('../../data/live.js')

Page({
  data: {
    step: 0, vehicles: demoMode ? vehicles : [], services: [], selectedServices: [], windows: demoMode ? windows : [], vehicleId: demoMode ? vehicles[0].id : '',
    totalMinutes: 0, estimatedAmount: '0.00', windowId: '', address: demoMode ? '云栖花园 2 栋地面车位' : '', error: '',
    submitting: false, loading: false, loadingSlots: false, busy: false, searchedSlots: false,
    demoMode, date: live.day(), earliest: live.day(), latest: live.day(30), draftText: '', aiDraft: null
  },
  onLoad(options) {
    this.preselected = options.service
    if (!demoMode) return
    const selected = services.find(service => service.id === options.service) || services[0]
    this.setData({ services: services.map(service => ({ ...service, quantity: service.id === selected.id ? 1 : 0 })) })
    this.recalculate()
  },
  onShow() {
    if (!requireAccount('OWNER')) return
    if (!demoMode) return this.loadLive()
    this.setData({ vehicles: [...vehicles] })
  },
  async loadLive() {
    this.setData({ loading: true, error: '' })
    try {
      const [cars, catalog] = await Promise.all([live.vehicles(), this.loadedCatalog ? Promise.resolve(null) : live.catalog()])
      const vehicleId = cars.some(item => item.id === this.data.vehicleId) ? this.data.vehicleId : cars[0]?.id || ''
      this.setData({ vehicles: cars, vehicleId })
      if (catalog) {
        const selected = new Map(this.data.services.map(item => [item.id, item.quantity]))
        this.setData({ services: catalog.map(item => ({ ...item,
          quantity: selected.get(item.id) ?? (item.id === this.preselected ? 1 : 0) })) })
        this.loadedCatalog = true
        this.recalculate()
      }
    } catch (error) {
      this.setData({ error: error.message })
    } finally {
      this.setData({ loading: false })
    }
  },
  chooseVehicle(event) { this.setData({ vehicleId: event.currentTarget.dataset.id }) },
  chooseService(event) {
    const id = event.currentTarget.dataset.id
    this.setData({ services: this.data.services.map(item => item.id === id ? { ...item, quantity: item.quantity ? 0 : 1 } : item), error: '' })
    this.recalculate()
  },
  changeQuantity(event) {
    const { id, delta } = event.currentTarget.dataset
    this.setData({ services: this.data.services.map(item => item.id === id
      ? { ...item, quantity: Math.max(1, Math.min(99, item.quantity + Number(delta))) } : item), error: '' })
    this.recalculate()
  },
  recalculate() {
    const selectedServices = this.data.services.filter(item => item.quantity > 0)
    const totalMinutes = selectedServices.reduce((total, item) => total + item.minutes * item.quantity, 0)
    const cents = selectedServices.reduce((total, item) => total + Math.round(Number(item.price) * 100) * item.quantity, 0)
    const slots = (demoMode ? windows : []).map(slot => {
      const date = new Date(slot.start)
      const end = new Date(slot.start + totalMinutes * 60000)
      const time = value => [value.getHours(), value.getMinutes()].map(part => String(part).padStart(2, '0')).join(':')
      return { ...slot, time: (date.getMonth() + 1) + ' 月 ' + date.getDate() + ' 日 ' + time(date) + '–' + time(end) }
    })
    if (!demoMode) this.invalidateSlots()
    this.setData({ selectedServices, totalMinutes, estimatedAmount: (cents / 100).toFixed(2), windows: slots })
  },
  invalidateSlots() {
    this.slotRequest = (this.slotRequest || 0) + 1
    this.setData({ windows: [], windowId: '', searchedSlots: false, loadingSlots: false })
  },
  chooseDate(event) {
    this.invalidateSlots()
    this.setData({ date: event.detail.value, error: '' })
  },
  async loadSlots() {
    if (!this.data.selectedServices.length) return this.setData({ error: '请先选择服务项目。' })
    if (this.data.totalMinutes > 10080) return this.setData({ error: '组合服务总工时过长。' })
    const request = this.slotRequest = (this.slotRequest || 0) + 1
    this.setData({ loadingSlots: true, error: '', windowId: '' })
    try {
      const windows = await live.slots(this.data.date, this.data.selectedServices)
      if (this.slotRequest === request) this.setData({ windows, searchedSlots: true })
    } catch (error) {
      if (this.slotRequest === request) this.setData({ error: error.message, windows: [] })
    } finally {
      if (this.slotRequest === request) this.setData({ loadingSlots: false })
    }
  },
  editDraft(event) { this.setData({ draftText: event.detail.value, aiDraft: null }) },
  parseDraft() {
    const text = this.data.draftText.trim()
    if (!text) return this.setData({ error: '请先描述需要的服务。' })
    return live.mutate(this, () => live.api('/ai/booking-drafts', { method: 'POST', data: { text, timeZone: 'Asia/Shanghai' } }),
      aiDraft => this.setData({ aiDraft }))
  },
  chooseWindow(event) { this.setData({ windowId: event.currentTarget.dataset.id, error: '' }) },
  editAddress(event) { this.setData({ address: event.detail.value, error: '' }) },
  openGarage() {
    wx.navigateTo({
      url: '/pages/vehicles/index?from=booking',
      events: { vehicleAdded: vehicleId => this.setData({ vehicleId }) }
    })
  },
  next() {
    if (!this.data.vehicleId) return this.setData({ error: '请先添加并选择一辆车。' })
    if (this.data.step === 1 && !this.data.selectedServices.length) {
      return this.setData({ error: '请至少选择一个服务项目，可以多选组合预约。' })
    }
    if (this.data.totalMinutes > 10080) return this.setData({ error: '组合服务总工时过长，请调整项目或数量。' })
    if (this.data.step === 1 && (!this.data.windowId || !this.data.address.trim())) {
      return this.setData({ error: '请选择上门时段，并填写停车位置。' })
    }
    this.setData({
      step: this.data.step + 1, error: '',
      vehicle: this.data.vehicles.find(vehicle => vehicle.id === this.data.vehicleId),
      window: this.data.windows.find(window => window.id === this.data.windowId)
    })
  },
  back() { this.setData({ step: this.data.step - 1, error: '' }) },
  book() {
    if (!requireAccount('OWNER') || this.data.submitting) return
    if (!demoMode) return this.bookLive()
    const { vehicleId, selectedServices, windowId, address } = this.data
    this.setData({ submitting: true })
    try {
      const items = selectedServices.map(item => ({ serviceId: item.id, quantity: item.quantity }))
      const id = createBooking(vehicleId, items, windowId, address)
      if (!id) {
        this.setData({ step: 1, windowId: '', error: '该时段已被预约或无法容纳组合服务，请选其他时段。', submitting: false })
        return
      }
      wx.redirectTo({ url: '/pages/owner/index?id=' + id })
    } catch (error) {
      this.setData({ error: error.message, submitting: false })
    }
  },
  async bookLive() {
    const { vehicleId, selectedServices, window, address, aiDraft } = this.data
    if (!window || !vehicleId || !selectedServices.length || !address.trim()) return
    this.setData({ submitting: true, error: '' })
    try {
      const order = await live.command(this, '/orders', {
        vehicleId, technicianId: window.technicianId, serviceStart: window.startAt, address: address.trim(),
        items: selectedServices.map(item => ({ serviceItemId: item.id, quantity: item.quantity, catalogVersion: item.version })),
        ...(aiDraft?.aiParseId ? { aiParseId: aiDraft.aiParseId } : {}),
      })
      wx.redirectTo({ url: '/pages/owner/index?id=' + order.orderId })
    } catch (error) {
      if (error.status === 409) {
        this.invalidateSlots()
        this.loadedCatalog = false
        this.setData({ step: 1 })
        await this.loadLive()
      }
      this.setData({ error: error.message })
    } finally {
      this.setData({ submitting: false })
    }
  }
})


