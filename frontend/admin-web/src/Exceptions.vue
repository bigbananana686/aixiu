<script setup>
import { computed } from 'vue'
import { demo } from './api.js'

const props = defineProps({ items: { type: Array, required: true } })
const emit = defineEmits(['open-order'])
const query = defineModel('query', { type: String, required: true })
const type = defineModel('type', { type: String, required: true })
const rows = computed(() => props.items.filter(item =>
  (!type.value || type.value === item.type)
  && [item.orderId, item.tech, item.detail].some(value => value.toLowerCase().includes(query.value.trim().toLowerCase()))))
</script>

<template>
  <section class="ledger exception-ledger" aria-label="服务异常记录">
    <div class="tools">
      <label class="search">异常类型
        <select v-model="type" aria-label="异常类型">
          <option value="">全部异常</option>
          <option value="DEFERRAL_SLOT_CONFLICT">延期时段冲突</option>
          <option value="OVER_PLAN_COMPLETION">超计划完工</option>
        </select>
      </label>
      <label class="search">搜索异常<input v-model="query" type="search" placeholder="订单号、技师或异常说明"></label>
    </div>
    <p class="exception-note">异常记录供运营核查。延期冲突时原承诺时间保留，可联系技师协调后续预约。</p>
    <div class="table-scroll" tabindex="0" role="region" aria-label="异常表格">
    <table>
      <thead><tr><th scope="col">发生时间 / 订单</th><th scope="col">异常类型</th><th scope="col">技师</th><th scope="col">异常说明</th></tr></thead>
      <tbody>
        <tr v-for="item in rows" :key="item.id">
          <td><time>{{ item.time }}</time><button class="vehicle" @click="emit('open-order', item.orderId)">{{ item.orderId }}</button></td>
          <td><span class="status">{{ item.label }}</span></td><td>{{ item.tech }}</td>
          <td class="exception-description">{{ item.detail }}</td>
        </tr>
        <tr v-if="!rows.length"><td colspan="4" class="empty">没有符合条件的异常记录。</td></tr>
      </tbody>
    </table>
    </div>
    <footer aria-live="polite">{{ rows.length }} 条异常{{ demo ? ' · 示例数据' : '' }}</footer>
  </section>
</template>
