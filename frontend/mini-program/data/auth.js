const { apiBase } = require('./config.js')
const { api, setSession } = require('./api.js')
const demoRoles = { 'demo-owner': 'OWNER', 'demo-tech': 'TECH' }
let account = null

async function signIn(loginName, password) {
  const name = loginName.trim().toLowerCase()
  let profile
  let token
  if (apiBase) {
    const login = await api('/auth/login', { method: 'POST', accessToken: '', data: { loginName: name, password } })
    if (!login.accessToken) throw new Error('登录服务未返回有效凭证。')
    token = login.accessToken
    profile = await api('/me', { accessToken: token })
  } else {
    const role = Object.prototype.hasOwnProperty.call(demoRoles, name) ? demoRoles[name] : null
    if (!role || password !== 'demo1234') throw new Error('演示账号或密码不正确。')
    profile = { loginName: name, role, enabled: true }
  }
  if (profile.enabled !== true || !['OWNER', 'TECH'].includes(profile.role)) {
    throw new Error('此账号无法登录小程序，请使用已启用的车主或技师账号。')
  }
  account = { userId: profile.userId, loginName: profile.loginName, role: profile.role }
  if (token) setSession(token, () => {
    signOut()
    wx.reLaunch({ url: '/pages/preview/index' })
  })
  return account
}

function currentAccount() { return account }
function signOut() { account = null; setSession('') }

function requireAccount(role) {
  if (!account) {
    wx.reLaunch({ url: '/pages/preview/index' })
    return null
  }
  if (role && account.role !== role) {
    wx.reLaunch({ url: '/pages/orders/index' })
    return null
  }
  return account
}

const registerOwner = (loginName, password) => api('/auth/register', { method: 'POST', accessToken: '', data: { loginName, password } })
async function refreshAccount() {
  if (!apiBase || !account) return account
  const profile = await api('/me')
  if (!profile.enabled || !['OWNER', 'TECH'].includes(profile.role)) {
    signOut()
    wx.reLaunch({ url: '/pages/preview/index' })
    return null
  }
  account = { userId: profile.userId, loginName: profile.loginName, role: profile.role }
  return account
}

module.exports = { signIn, signOut, currentAccount, requireAccount, registerOwner, refreshAccount, demoMode: !apiBase }
