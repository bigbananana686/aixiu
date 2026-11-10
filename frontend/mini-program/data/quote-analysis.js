const { api, command, mutate } = require('./live.js')

function request(page, quote) {
  if (!quote) return
  return mutate(page, () => command(page, '/orders/' + page.data.order.id + '/quote-analysis', {
    quoteId: quote.id, version: quote.version, contentHash: quote.contentHash,
  }), async result => {
    page.setData({ analysisJob: { jobId: result.jobId, status: result.status || 'PENDING' } })
    await load(page)
  })
}

async function load(page) {
  const job = await api('/jobs/' + page.data.analysisJob.jobId)
  const result = job.result?.analysis || job.result
  const statuses = { PENDING: '待处理', RUNNING: '分析中', SUCCEEDED: '已完成', FAILED: '分析失败', RETRY_WAIT: '等待重试' }
  const text = job.resultStale ? '报价已变化，请重新请求分析。' : job.error?.message ||
    [result?.summary, ...(result?.limitations || [])].filter(Boolean).join('\n')
  page.setData({ analysisJob: job, analysisStatus: statuses[job.status] || job.status, analysisText: text })
}

function refresh(page) {
  if (page.data.analysisJob) return mutate(page, () => load(page))
}

module.exports = { request, refresh }
