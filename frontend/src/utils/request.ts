import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: '/',
  timeout: 120000
})

request.interceptors.response.use(
  (res) => {
    // Blob 下载直接返回
    if (res.config.responseType === 'blob') {
      return res.data
    }
    const data = res.data
    if (data && typeof data === 'object' && 'code' in data) {
      if (data.code === 200) {
        return data
      }
      ElMessage.error(data.msg || '请求失败')
      return Promise.reject(new Error(data.msg || 'Error'))
    }
    return data
  },
  (err) => {
    ElMessage.error(err.message || '网络错误')
    return Promise.reject(err)
  }
)

export default request
