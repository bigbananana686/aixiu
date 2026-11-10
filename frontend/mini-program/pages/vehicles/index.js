const { requireAccount, demoMode } = require('../../data/auth.js')
const live = require('../../data/live.js')
const { vehicles, addVehicle } = require('../../data/demo.js')
const { brands } = require('../../data/brands.js')

Page({
  data: { vehicles: [], brands, step: 'garage', brand: null, groupIndex: 0, group: null, model: '', plate: '', error: '', query: '', showAll: false, demoMode, busy: false, mileage: '', editingId: '' },
  onLoad(options) { this.fromBooking = options.from === 'booking' },
  onShow() {
    if (!requireAccount('OWNER')) return
    if (!demoMode) return this.refresh()
    this.setData({ vehicles: [...vehicles] })
  },
  async refresh() {
    try { this.setData({ vehicles: await live.vehicles() }) }
    catch (error) { this.setData({ error: error.message }) }
  },
  start() { this.setData({ step: 'brand', showAll: false, query: '', error: '', editingId: '', mileage: '' }) },
  editVehicle(event) {
    const vehicle = this.data.vehicles.find(item => item.id === event.currentTarget.dataset.id)
    this.setData({ step: 'edit', editingId: vehicle.id, version: vehicle.version, modelCode: vehicle.car, plate: '', mileage: String(vehicle.mileage), error: '' })
  },
  editModelCode(event) { this.setData({ modelCode: event.detail.value }) },
  editMileage(event) { this.setData({ mileage: event.detail.value }) },
  allBrands() { this.setData({ showAll: true }) },
  search(event) {
    const query = event.detail.value.trim()
    this.setData({ query, brands: brands.filter(brand => brand.name.includes(query) || brand.mark.toLowerCase().includes(query.toLowerCase())) })
  },
  chooseBrand(event) {
    const brand = brands.find(item => item.name === event.currentTarget.dataset.name)
    if (brand) this.setData({ step: 'model', brand, groupIndex: 0, group: brand.groups[0] })
  },
  chooseGroup(event) {
    const groupIndex = Number(event.currentTarget.dataset.index)
    this.setData({ groupIndex, group: this.data.brand.groups[groupIndex] })
  },
  chooseModel(event) { this.setData({ model: event.currentTarget.dataset.name, step: 'plate', plate: '', error: '' }) },
  editPlate(event) { this.setData({ plate: event.detail.value, error: '' }) },
  back(event) { this.setData({ step: event.currentTarget.dataset.step, error: '', brands, query: '' }) },
  save() {
    if (!demoMode) return this.saveLive()
    const { brand, model, plate } = this.data
    const id = addVehicle(brand.name + model, plate.toUpperCase())
    if (!id) return this.setData({ error: '请填写车牌。' })
    if (this.fromBooking) {
      this.getOpenerEventChannel().emit('vehicleAdded', id)
      wx.navigateBack()
      return
    }
    this.setData({ vehicles: [...vehicles], step: 'garage', error: '' })
  },
  saveLive() {
    if (!requireAccount('OWNER')) return
    const { brand, model, plate, mileage, editingId, version, modelCode } = this.data
    if (!/^\d{1,7}$/.test(mileage)) return this.setData({ error: '请输入有效的车辆里程（公里）。' })
    const data = {
      modelCode: editingId ? modelCode.trim() : brand.name + model, mileage: Number(mileage),
      ...(plate.trim() ? { plateNumber: plate.trim().toUpperCase() } : {}),
      ...(editingId ? { expectedRowVersion: version } : {}),
    }
    return live.mutate(this, () => live.command(this, '/vehicles' + (editingId ? '/' + editingId : ''), data, editingId ? 'PATCH' : 'POST'), async item => {
      if (this.fromBooking && !editingId) {
        this.getOpenerEventChannel().emit('vehicleAdded', item.vehicleId)
        wx.navigateBack()
      } else {
        await this.refresh()
        this.setData({ step: 'garage' })
      }
    })
  },
})

