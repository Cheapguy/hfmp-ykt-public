import { fetchMenus } from '../api/auth'

/**
 * 权限缓存（登录期单例）：/auth/menus 只拉一次，路由守卫、侧边栏、v-perm 共用。
 * 后端已按角色过滤且包含隐藏菜单(visible=0)与 F 按钮权限行；
 * 侧边栏渲染自行按 visible===1 且非 F 过滤。
 */
let menus = null      // 已加载的菜单行；null=未加载
let loading = null    // 进行中的加载 promise（并发去重）
const paths = new Set()
const perms = new Set()

export function ensurePerms() {
  if (menus) return Promise.resolve(menus)
  if (!loading) {
    loading = fetchMenus().then(list => {
      menus = list || []
      paths.clear(); perms.clear()
      menus.forEach(m => {
        if (m.path) paths.add(m.path)
        if (m.permission) perms.add(m.permission)
      })
      return menus
    }).catch(e => { loading = null; throw e })   // 失败允许重试，别把坏结果焊死
  }
  return loading
}

export function isAdmin() {
  try { return JSON.parse(localStorage.getItem('ykt_user') || '{}').userType === 'SYS_ADMIN' }
  catch { return false }
}

/** 页面级：是否拥有该菜单 path */
export function hasPath(p) { return isAdmin() || paths.has(p) }

/** 按钮级（v-perm）：兼容 permission 码与菜单 path 两种写法 */
export function hasPerm(code) { return isAdmin() || perms.has(code) || paths.has(code) }

export function grantedMenus() { return menus || [] }

/** 登录/登出/切换账号时调用，下次导航重新拉取 */
export function resetPerms() {
  menus = null; loading = null
  paths.clear(); perms.clear()
}
