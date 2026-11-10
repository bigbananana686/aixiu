const { signIn, currentAccount, registerOwner, demoMode } = require('../../data/auth.js')

Page({
  data: { loginName: '', password: '', busy: false, error: '', demoMode, registering: false },
  toggleRegister() { this.setData({ registering: !this.data.registering, error: '' }) },
  onShow() {
    if (currentAccount()) wx.reLaunch({ url: '/pages/orders/index' })
  },
  async login(event) {
    if (this.data.busy) return
    const { loginName, password } = event.detail.value
    if (!loginName.trim() || !password) {
      this.setData({ error: '请输入账号和密码。' })
      return
    }
    this.setData({ busy: true, error: '' })
    try {
      if (this.data.registering) {
        await registerOwner(loginName.trim(), password)
        this.setData({ registering: false, loginName: loginName.trim() })
      }
      await signIn(loginName, password)
      this.setData({ password: '' })
      wx.reLaunch({ url: '/pages/orders/index' })
    } catch (error) {
      this.setData({ error: error.message })
    } finally {
      this.setData({ busy: false })
    }
  }
})
