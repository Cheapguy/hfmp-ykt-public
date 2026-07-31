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

// 静默请求：调用方传 { silent: true } 时不弹全局 toast，错误交给调用方自己处理。
// 用于工作台待办这类「按岗位并发拉多个接口、某个没权限是正常的」场景——
// 否则一进首页就是一片红色 toast，且面板 catch 在拦截器下游根本挡不住。
// 401 例外：登录过期必须让用户看见，不受 silent 影响。
request.interceptors.response.use(
  resp => {
    const r = resp.data
    const silent = resp.config?.silent
    if (r && typeof r === 'object' && 'code' in r) {
      if (r.code === 0 || r.code === 200) return r.data
      if (r.code === 401) {
        ElMessage.error('登录已过期，请重新登录')
        localStorage.removeItem('ykt_token')
        router.push('/login')
        return Promise.reject(r)
      }
      if (!silent) ElMessage.error(r.msg || '请求失败')
      return Promise.reject(r)
    }
    return r
  },
  err => {
    if (err.response?.status === 401) {
      localStorage.removeItem('ykt_token')
      router.push('/login')
    } else if (!err.config?.silent) {
      ElMessage.error(err.response?.data?.msg || err.message || '网络异常')
    }
    return Promise.reject(err)
  }
)

export default request
