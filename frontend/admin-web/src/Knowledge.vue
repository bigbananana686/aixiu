<script setup>
import { onMounted, ref } from 'vue'
import { api, command, listAll } from './api.js'
import { localTime } from './live-orders.js'

const items = ref([])
const busy = ref(false)
const error = ref('')
const notice = ref('')
const job = ref(null)
const write = command()
let fileRevision = 0
const labels = { PENDING: '待处理', RUNNING: '处理中', SUCCEEDED: '已完成', FAILED: '失败', RETRY_WAIT: '等待重试' }

async function run(work) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try { await work() } catch (problem) { error.value = problem.message }
  finally { busy.value = false }
}

async function load() { items.value = await listAll('/admin/knowledge-documents') }

function upload(event) {
  const form = event.currentTarget
  const data = new FormData(form)
  for (const field of ['documentKey', 'modelTags', 'source']) {
    if (!data.get(field)?.trim()) data.delete(field)
  }
  const file = data.get('file')
  return run(async () => {
    if (!file?.size || file.size > 10 * 1024 * 1024) throw new Error('请选择 10 MB 以内的 PDF、TXT 或 Markdown 文件。')
    const signature = JSON.stringify([...data.entries()].filter(([name]) => name !== 'file')) + ':' + fileRevision
    const result = await write('/admin/knowledge-documents', 'POST', data, signature)
    form.reset()
    notice.value = '已接收资料版本 ' + result.version + '，索引任务编号 ' + result.jobId + '。'
    await load()
  })
}

function checkJob(id) {
  return run(async () => { job.value = await api('/jobs/' + id) })
}

onMounted(() => run(load))
</script>

<template>
  <section class="ledger">
    <header class="panel-heading"><h2>上传养护资料</h2><button class="quiet" type="button" :disabled="busy" @click="run(load)">刷新列表</button></header>
    <form class="tools" @submit.prevent="upload">
      <label class="search">文件<input name="file" type="file" required accept=".pdf,.txt,.md,.markdown" @change="fileRevision++"></label>
      <label class="search">标题<input name="title" required maxlength="200"></label>
      <label class="search">资料编号（新建可留空）<input name="documentKey"></label>
      <label class="search">车型标签（逗号分隔）<input name="modelTags" placeholder="GENERIC"></label>
      <label class="search">来源<input name="source"></label>
      <button class="primary" type="submit" :disabled="busy">上传并排队索引</button>
    </form>
    <p v-if="error" class="empty form-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="empty" role="status">{{ notice }}</p>
    <div v-if="job" class="tools" role="status">
      索引任务 {{ job.jobId }} · {{ labels[job.status] || job.status }} · {{ localTime(job.updatedAt) }}
      {{ job.error?.message || job.staleReason || '' }}
      <button class="text-button" type="button" :disabled="busy" @click="checkJob(job.jobId)">刷新任务</button>
    </div>
    <div class="table-scroll">
      <table>
        <thead><tr><th>资料编号</th><th>标题</th><th>版本</th><th>索引状态</th><th>当前发布版</th><th>任务</th></tr></thead>
        <tbody>
          <tr v-for="item in items" :key="item.documentKey + ':' + item.version">
            <td>{{ item.documentKey }}</td><td>{{ item.title }}</td><td>{{ item.version }}</td>
            <td>{{ labels[item.indexStatus] || item.indexStatus }}</td><td>{{ item.activeVersion || '—' }}</td>
            <td><button v-if="item.jobId" class="text-button" type="button" :disabled="busy" @click="checkJob(item.jobId)">查看 {{ item.jobId }}</button></td>
          </tr>
          <tr v-if="!items.length"><td class="empty" colspan="6">{{ busy ? '正在读取…' : '暂无资料。' }}</td></tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
