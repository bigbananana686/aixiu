const feedbackUrl = 'http://127.0.0.1:5173/api/preview/feedback'

function request(method, data) {
  return new Promise((resolve, reject) => {
    wx.request({
      url: feedbackUrl, method, data, timeout: 8000,
      header: { Authorization: 'Bearer demo-owner', 'Content-Type': 'application/json' },
      success(response) {
        const body = response.data
        if (response.statusCode >= 200 && response.statusCode < 300 && body?.data) {
          resolve(body.data)
        } else {
          reject(new Error(body?.message || '反馈服务暂不可用，请稍后重试。'))
        }
      },
      fail() { reject(new Error('无法连接反馈服务，请确认爱修管理端正在本机运行。')) }
    })
  })
}

module.exports = {
  listFeedback: () => request('GET'),
  submitFeedback: data => request('POST', data)
}
