<script setup>
import { ref } from 'vue'
import Brand from './Brand.vue'
import Icon from './Icon.vue'
import { api, demo } from './api.js'

const emit = defineEmits(['login'])
const account = 'demo-admin'
const demoPassword = 'demo1234'
const username = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)

async function login() {
  if (busy.value) return
  if (!demo) {
    busy.value = true
    try {
      const session = await api('/auth/login', { method: 'POST', token: '', data: { loginName: username.value.trim(), password: password.value } })
      const profile = await api('/me', { token: session.accessToken })
      if (profile.role !== 'ADMIN' || !profile.enabled) throw new Error('请使用已启用的管理员账号。')
      emit('login', profile.loginName, { ...profile, accessToken: session.accessToken })
    } catch (problem) {
      error.value = problem.message
    } finally {
      busy.value = false
    }
    return
  }
  if (username.value.trim() !== account || password.value !== demoPassword) {
    error.value = '账号或密码不正确，请核对下方的演示账号。'
    return
  }
  emit('login', account)
}
</script>

<template>
  <main class="login">
    <section class="login-intro" aria-label="爱修运营管理台">
      <Brand />
      <div class="login-copy">
        <p class="login-kicker">汽车养护运营工作区</p>
        <p class="login-message">让养护有序，<br>让交付安心。</p>
        <p class="login-description">从车主预约到技师上门，<br>在这里跟进每一笔工单。</p>
        <ul class="login-features">
          <li><Icon name="file" /> 工单跟进</li>
          <li><Icon name="calendar" /> 技师排班</li>
          <li><Icon name="shield" /> 异常核查</li>
        </ul>
      </div>
    </section>
    <section class="login-entry" aria-labelledby="login-title">
      <div class="login-box">
        <span class="login-symbol"><Icon name="wrench" /></span>
        <h1 id="login-title" tabindex="-1">登录管理工作区</h1>
        <p class="login-subtitle">{{ demo ? '使用演示账号体验爱修运营管理台。' : '请使用平台分配的管理员账号登录。' }}</p>
        <form class="login-form" @submit.prevent="login" @input="error = ''">
          <label for="login-account">登录账号</label>
          <input id="login-account" v-model="username" autocomplete="username" placeholder="请输入账号" required>
          <label for="login-password">密码</label>
          <input id="login-password" v-model="password" type="password" autocomplete="current-password"
            placeholder="请输入密码" required>
          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <button class="primary login-submit" type="submit" :disabled="busy">{{ busy ? '正在登录' : '登录工作区' }} <Icon name="chevron" /></button>
        </form>
        <p v-if="demo" class="login-help">演示账号 <code>{{ account }}</code><span aria-hidden="true"> / </span>密码 <code>{{ demoPassword }}</code></p>
      </div>
    </section>
  </main>
</template>
