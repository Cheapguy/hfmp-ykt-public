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
// 登录态相关的本地缓存一次清干净：只删 token 会留下上一个账号的 userType 和标签页，
// 换人登录时旧标签直接带着别人的查询条件复活。
function clearSession() {
  localStorage.removeItem('ykt_token')
  localStorage.removeItem('ykt_user')
  localStorage.removeItem('ykt_tabs')
}

function onUnauthorized(msg) {
  if (msg) ElMessage.error(msg)
  clearSession()
  router.push('/login')
}

request.interceptors.response.use(
  resp => {
    const r = resp.data
    const silent = resp.config?.silent
    // 导出接口用 responseType:'blob'，但后端出错时同样是 HTTP 200 + JSON 体（R.code 约定）。
    // 不在这里拆开，浏览器就会老老实实把 {"code":403,"msg":"无权限"} 存成一个 .xlsx，
    // 用户双击才发现文件打不开，且完全看不到真正的错误原因。
    if (r instanceof Blob && (r.type || '').includes('json')) {
      return r.text().then(text => {
        let body
        try { body = JSON.parse(text) } catch { return r }   // 真是 JSON 数据流就原样放行
        if (!body || typeof body !== 'object' || !('code' in body)) return r
        if (body.code === 0 || body.code === 200) return body.data
        if (body.code === 401) { onUnauthorized('登录已过期，请重新登录'); return Promise.reject(body) }
        if (!silent) ElMessage.error(body.msg || '导出失败')
        return Promise.reject(body)
      })
    }
    if (r && typeof r === 'object' && 'code' in r) {
      if (r.code === 0 || r.code === 200) return r.data
      if (r.code === 401) {
        onUnauthorized('登录已过期，请重新登录')
        return Promise.reject(r)
      }
      if (!silent) ElMessage.error(r.msg || '请求失败')
      return Promise.reject(r)
    }
    return r
  },
  err => {
    if (err.response?.status === 401) {
      onUnauthorized()
    } else if (!err.config?.silent) {
      ElMessage.error(err.response?.data?.msg || err.message || '网络异常')
    }
    return Promise.reject(err)
  }
)

export default request
