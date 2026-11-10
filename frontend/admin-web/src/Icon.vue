<script setup>
defineProps({ name: { type: String, required: true } })

const paths = {
  wrench: 'M14.5 6.5a5 5 0 0 0-6.2 6.2L3 18l3 3 5.3-5.3a5 5 0 0 0 6.2-6.2l-3.3 3.3-3-3 3.3-3.3Z',
  home: 'm3 10 9-7 9 7v10H3Z M9 20v-7h6v7',
  file: 'M6 3h12v18H6z M9 7h6m-6 4h6m-6 4h4',
  calendar: 'M4 6h16v14H4z M4 10h16 M8 4v4m8-4v4m-8 6h2m4 0h2',
  shield: 'm12 3 8 3v5c0 4.5-3.3 7.6-8 10-4.7-2.4-8-5.5-8-10V6Z m-3 9 2 2 4-4',
  check: 'm5 12 4 4L19 6',
  clock: 'M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16Zm0 4v4l3 2',
  alert: 'M10.6 4.8 3.3 18a1 1 0 0 0 .9 1.5h15.6a1 1 0 0 0 .9-1.5L13.4 4.8a1.6 1.6 0 0 0-2.8 0ZM12 10v3m0 3v.1',
  menu: 'M4 6h16M4 12h16M4 18h16',
  logout: 'M10 4H4v16h6m4-12 4 4-4 4m-5-4h11',
  refresh: 'M20 4v5h-5m4-1a8 8 0 1 0 1 6',
  search: 'M10.5 4a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13Zm4.6 11.1L20 20',
  chevron: 'm9 6 6 6-6 6',
  close: 'm6 6 12 12M6 18 18 6',
}
</script>

<template>
  <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"
    stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path :d="paths[name]" />
  </svg>
</template>
