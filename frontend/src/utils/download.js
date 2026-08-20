import request from '../api/request'
import { ElMessage } from 'element-plus'

/** 与 axios baseURL 相同的后端前缀；库里存的 fileUrl 是含这段前缀的绝对路径 */
const API_PREFIX = '/hfmp-ykt/api'

/**
 * fileUrl 白名单。必须锁死，因为 fileUrl 是**可写字段**：
 * 公告、项目附件都走 BaseCrudController 的通用 create/update，请求体里带一个
 * `fileUrl: "https://evil.example/x"` 就能存进库。axios 遇到绝对 URL 会直打那个站，
 * 而请求拦截器无条件加 Authorization —— 收件人点一下，token 就送出去了。
 * 只放行「本站 /files/preview/ + uuid 文件名（可带 ?fn=）」这一种形状。
 */
const ALLOWED_FILE_URL = /^\/hfmp-ykt\/api\/files\/preview\/[A-Za-z0-9._-]+(\?[^#]*)?$/

/**
 * 把 Blob 存成本地文件。
 */
export function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob instanceof Blob ? blob : new Blob([blob]))
  const a = document.createElement('a')
  a.href = url
  a.download = filename || '下载文件'
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/**
 * 带鉴权下载后端附件。
 *
 * 附件接口以前挂在拦截器的 excludes 里免登录开放，于是任何人拿到（或猜到）那串 uuid
 * 就能直接下载政策文件和通知附件。改成走 axios 后，Authorization 头会自动带上，
 * 但要注意：fileUrl 存的是 `/hfmp-ykt/api/files/preview/xxx` 这样的绝对路径，
 * 而 axios 的 baseURL 也是 `/hfmp-ykt/api`——不剥前缀就会拼成双份路径 404。
 */
export async function downloadFile(fileUrl, filename) {
  if (!fileUrl) return
  if (!ALLOWED_FILE_URL.test(fileUrl)) {
    ElMessage.error('附件地址不合法，已阻止下载')
    return
  }
  const path = fileUrl.slice(API_PREFIX.length)
  const blob = await request.get(path, { responseType: 'blob' })
  // 文件名优先用调用方给的；否则从 ?fn= 参数里还原原始文件名
  let name = filename
  if (!name) {
    const m = /[?&]fn=([^&]+)/.exec(fileUrl)
    if (m) { try { name = decodeURIComponent(m[1]) } catch { name = m[1] } }
  }
  saveBlob(blob, name)
}
