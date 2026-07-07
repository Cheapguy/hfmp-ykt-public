import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const request = axios.create({
  baseURL: '/hfmp-ykt/api',
  timeout: 30000
})

request.interceptors.request.use(config => {
  const token = localStorage.getItem('ykt_token')
  if (token) config.headers['Authorization'] = 'Bearer ' + token
  return config
})

request.interceptors.response.use(
  resp => {
    const r = resp.data
    if (r && typeof r === 'object' && 'code' in r) {
      if (r.code === 0 || r.code === 200) return r.data
      if (r.code === 401) {
        ElMessage.error('登录已过期，请重新登录')
        localStorage.removeItem('ykt_token')
        router.push('/login')
        return Promise.reject(r)
      }
      ElMessage.error(r.msg || '请求失败')
      return Promise.reject(r)
    }
    return r
  },
  err => {
    if (err.response?.status === 401) {
      localStorage.removeItem('ykt_token')
      router.push('/login')
    } else {
      ElMessage.error(err.response?.data?.msg || err.message || '网络异常')
    }
    return Promise.reject(err)
  }
)

export default request
