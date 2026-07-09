import { hasPerm } from '../store/perm'

/**
 * 按钮级权限指令：v-perm="'sys:user:reset'" 或 v-perm="'/sys/user'"
 * 值可以是 SYS_MENU.PERMISSION 码（F 按钮行）或菜单 path，不具备则移除元素。
 * 权限数据在路由守卫 ensurePerms() 后已就绪，页面渲染时同步可查。
 * 传数组表示任一命中即可：v-perm="['/sys/user','/sys/role']"
 */
export default {
  mounted(el, binding) {
    const need = [].concat(binding.value || [])
    if (need.length && !need.some(hasPerm)) {
      el.parentNode && el.parentNode.removeChild(el)
    }
  }
}
