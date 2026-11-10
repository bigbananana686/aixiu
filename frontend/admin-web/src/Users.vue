<script setup>
import { onMounted, ref } from 'vue'
import { api, command } from './api.js'
import { localTime } from './live-orders.js'

const labels = { OWNER: '车主', TECH: '技师', ADMIN: '管理员' }
const keyword = ref('')
const role = ref('')
const enabled = ref('')
const list = ref({ items: [], page: 1, total: 0, pageSize: 20 })
const busy = ref(false)
const error = ref('')
const notice = ref('')
const draft = ref(null)
const write = command()

async function load(page = 1) {
  const query = new URLSearchParams({ page, pageSize: 20, keyword: keyword.value.trim(), role: role.value })
  if (enabled.value) query.set('enabled', enabled.value)
  list.value = await api('/admin/users?' + query)
}

async function run(action) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try { await action() } catch (problem) { error.value = problem.message }
  finally { busy.value = false }
}

function save() {
  return run(async () => {
    const { userId, version, role, enabled, technicianName } = draft.value
    await write('/admin/users/' + userId, 'PATCH', {
      expectedVersion: version, role, enabled, ...(technicianName.trim() ? { technicianName: technicianName.trim() } : {}),
    })
    draft.value = null
    notice.value = '用户权限已更新。'
    await load(list.value.page)
  })
}

onMounted(() => run(load))
</script>

<template>
  <section class="ledger">
    <form class="tools" @submit.prevent="run(load)">
      <label class="search">登录账号<input v-model="keyword" type="search" maxlength="64" placeholder="搜索账号"></label>
      <label class="search">角色<select v-model="role"><option value="">全部</option><option v-for="(label, key) in labels" :key="key" :value="key">{{ label }}</option></select></label>
      <label class="search">账号状态<select v-model="enabled"><option value="">全部</option><option value="true">已启用</option><option value="false">已停用</option></select></label>
      <button class="primary" type="submit" :disabled="busy">查询用户</button>
    </form>
    <p v-if="error" class="empty form-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="empty" role="status">{{ notice }}</p>
    <div class="table-scroll">
      <table>
        <thead><tr><th>账号</th><th>角色</th><th>账号状态</th><th>最近更新</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="item in list.items" :key="item.userId">
            <td>{{ item.loginName }}<small>#{{ item.userId }}</small></td><td>{{ labels[item.role] }}</td>
            <td><span class="status" :class="{ done: item.enabled }">{{ item.enabled ? '已启用' : '已停用' }}</span></td>
            <td>{{ localTime(item.updatedAt) }}</td>
            <td><button v-if="item.role !== 'ADMIN'" class="text-button" type="button" :disabled="busy"
              :aria-label="'管理 ' + item.loginName" @click="draft = { ...item, originalRole: item.role, technicianName: '' }; notice = ''">管理账号</button><span v-else>—</span></td>
          </tr>
          <tr v-if="!list.items.length"><td colspan="5" class="empty">{{ busy ? '正在读取…' : '暂无匹配账号。' }}</td></tr>
        </tbody>
      </table>
    </div>
    <footer class="tools">
      <span>共 {{ list.total }} 个账号 · 第 {{ list.page }} 页</span>
      <button class="quiet" type="button" :disabled="busy || list.page <= 1" @click="run(() => load(list.page - 1))">上一页</button>
      <button class="quiet" type="button" :disabled="busy || list.page * list.pageSize >= list.total" @click="run(() => load(list.page + 1))">下一页</button>
    </footer>
    <form v-if="draft" class="tools" @submit.prevent="save">
      <strong>{{ draft.loginName }}</strong>
      <label class="search">调整为<select v-model="draft.role"><option value="OWNER">车主</option><option value="TECH">技师</option></select></label>
      <label v-if="draft.originalRole === 'OWNER' && draft.role === 'TECH'" class="search">技师对外显示名<input v-model="draft.technicianName" maxlength="64" placeholder="首次转为技师时必填"></label>
      <label><input v-model="draft.enabled" type="checkbox"> 启用账号</label>
      <button class="primary" type="submit" :disabled="busy">保存权限</button>
      <button class="quiet" type="button" :disabled="busy" @click="draft = null">取消</button>
    </form>
  </section>
</template>
