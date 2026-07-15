<template>
  <el-container class="app-shell">
    <!-- 顶部：纯品牌栏（无横向菜单，对齐生产） -->
    <el-header class="app-header" height="56px">
      <div class="brand">
        <div class="logo-mark">惠</div>
        <div class="brand-text">甘肃省惠民惠农财政补贴"一卡通"管理模块</div>
      </div>
      <div class="header-warn">本系统为非涉密平台，严禁处理、传输国家秘密</div>
      <div class="header-right">
        <span class="year">年度 {{ year }}</span>
        <el-dropdown @command="onUserCmd">
          <span class="user-trigger">
            <el-icon><UserFilled /></el-icon>
            <span class="username">{{ user.realName || user.username || '未登录' }}</span>
            <el-tag v-if="user.userType" size="small" class="role-tag">{{ userTypeLabel }}</el-tag>
            <el-icon><CaretBottom /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="changePwd">修改密码</el-dropdown-item>
              <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>

    <el-container class="app-body">
      <!-- 左侧：完整菜单树（对齐生产） -->
      <el-aside width="236px" class="app-aside">
        <el-menu :default-active="$route.path" :unique-opened="true" class="side-menu" router>
          <el-menu-item index="/dashboard" class="home-item">
            <el-icon><HomeFilled /></el-icon>
            <template #title>工作台</template>
          </el-menu-item>
          <SidebarMenu :items="menuTree" :depth="0" />
        </el-menu>
      </el-aside>

      <div class="main-col">
        <!-- 打开过的菜单标签页（对齐生产多页签，点击切换 / × 关闭；首页常驻） -->
        <div class="tabs-bar">
          <div v-for="t in tabs" :key="t.path" class="page-tab" :class="{ active: t.path === $route.path }"
            @click="onTabClick(t)">
            <span>{{ t.title }}</span>
            <el-icon v-if="t.path !== '/dashboard'" class="tab-close" @click.stop="closeTab(t)"><Close /></el-icon>
          </div>
        </div>
        <el-main class="app-main">
          <router-view v-slot="{ Component, route }">
            <transition name="fade" mode="out-in">
              <keep-alive :include="cacheNames">
                <component :is="wrapTab(route, Component)" :key="route.path" />
              </keep-alive>
            </transition>
          </router-view>
        </el-main>
      </div>
    </el-container>

    <el-dialog v-model="pwdVisible" title="修改密码" width="420px">
      <el-form ref="pwdRef" :model="pwdForm" :rules="pwdRules" label-width="90px">
        <el-form-item label="原密码" prop="oldPassword"><el-input v-model="pwdForm.oldPassword" type="password" show-password /></el-form-item>
        <el-form-item label="新密码" prop="newPassword"><el-input v-model="pwdForm.newPassword" type="password" show-password /></el-form-item>
        <el-form-item label="确认密码" prop="confirmPwd"><el-input v-model="pwdForm.confirmPwd" type="password" show-password /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdLoading" @click="onPwdSubmit">提交</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch, h } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UserFilled, CaretBottom, HomeFilled, Close } from '@element-plus/icons-vue'
import { logout, changePassword } from '../api/auth'
import { ensurePerms, resetPerms } from '../store/perm'
import SidebarMenu from './SidebarMenu.vue'

const router = useRouter()
const route = useRoute()
const menuFlat = ref([])
const user = ref(JSON.parse(localStorage.getItem('ykt_user') || '{}'))
const year = new Date().getFullYear()

const userTypeLabel = computed(() => ({
  SYS_ADMIN: '系统管理员', TOWN_OP: '乡镇经办', TOWN_AUDIT: '乡镇审核',
  DEPT_OP: '部门经办', DEPT_AUDIT: '部门审核', FINANCE: '财政'
}[user.value.userType] || ''))

const menuTree = computed(() => {
  // /auth/menus 现在会带回隐藏菜单(visible=0)与 F 按钮权限行（供路由守卫/v-perm 用），
  // 侧边栏只渲染可见的目录与菜单
  const rows = menuFlat.value.filter(m => m.visible === 1 && m.menuType !== 'F')
  const map = {}
  rows.forEach(m => map[m.id] = { ...m, children: [] })
  const tree = []
  rows.forEach(m => {
    const pid = m.parentId
    if (pid && pid !== '0' && map[pid]) map[pid].children.push(map[m.id])
    else tree.push(map[m.id])
  })
  const sortFn = (a, b) => (a.sortNo || 0) - (b.sortNo || 0)
  const sortDeep = (nodes) => { nodes.sort(sortFn); nodes.forEach(n => { if (n.children?.length) sortDeep(n.children) }) }
  sortDeep(tree)
  // 目录下子项全被过滤(如只剩隐藏菜单)时别留空组
  const prune = (nodes) => nodes.filter(n => {
    n.children = prune(n.children)
    return n.menuType !== 'M' || n.children.length > 0
  })
  return prune(tree)
})

onMounted(async () => {
  try { menuFlat.value = await ensurePerms() }   // 与路由守卫共享同一次拉取
  catch (e) { console.error('菜单加载失败', e) }
  // 深链/刷新时 tab 的 title watch 先于菜单加载跑完，动态路由(/entry/*)标题会落成「页面」——菜单到位后回填
  tabs.value.forEach(t => {
    if (t.title === '页面') {
      const m = menuFlat.value.find(x => x.path === t.path)
      if (m && m.menuName) t.title = m.menuName
    }
  })
})

/* ============ 菜单多开标签页 ============ */
const HOME_TAB = { path: '/dashboard', title: '首页' }
const tabs = ref(JSON.parse(sessionStorage.getItem('ykt_tabs') || 'null') || [{ ...HOME_TAB }])
watch(tabs, v => sessionStorage.setItem('ykt_tabs', JSON.stringify(v)), { deep: true })

// keep-alive 按标签管缓存：include 只认组件名，页面组件都是无名 <script setup>，
// 且同一组件复用于多个路由（花名册填报/审核），所以按 route.path 包一层动态命名的壳。
// 关标签 → cacheNames 移除 → keep-alive 淘汰该页状态，重开是全新页。
const tabName = p => 'tab:' + p
const cacheNames = computed(() => tabs.value.map(t => tabName(t.path)))
const wrapMap = new Map()
function wrapTab(r, component) {
  if (!component) return null
  let w = wrapMap.get(r.path)
  if (!w) { w = { name: tabName(r.path), render: () => h(component) }; wrapMap.set(r.path, w) }
  else w.render = () => h(component)   // 换成本次导航的最新 vnode，旧 vnode 不能二次渲染
  return w
}

watch(() => route.path, p => {
  if (p === '/login' || p === '/403') return
  if (!tabs.value.some(t => t.path === p)) {
    const title = route.meta?.title || menuFlat.value.find(m => m.path === p)?.menuName || '页面'
    tabs.value.push({ path: p, fullPath: route.fullPath, title })
  }
}, { immediate: true })

function onTabClick(t) { if (t.path !== route.path) router.push(t.fullPath || t.path) }
function closeTab(t) {
  const i = tabs.value.findIndex(x => x.path === t.path)
  if (i < 0) return
  tabs.value.splice(i, 1)
  wrapMap.delete(t.path)
  if (route.path === t.path) {
    const next = tabs.value[Math.min(i, tabs.value.length - 1)] || HOME_TAB
    router.push(next.fullPath || next.path)
  }
}

async function onUserCmd(cmd) {
  if (cmd === 'logout') {
    await ElMessageBox.confirm('确定退出当前账号？', '提示', { type: 'warning' })
    try { await logout() } catch (e) {}
    localStorage.removeItem('ykt_token'); localStorage.removeItem('ykt_user')
    sessionStorage.removeItem('ykt_tabs')
    resetPerms()
    router.replace('/login')
  } else if (cmd === 'changePwd') { pwdVisible.value = true }
}

const pwdVisible = ref(false)
const pwdLoading = ref(false)
const pwdRef = ref()
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPwd: '' })
const pwdRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [{ required: true, min: 6, message: '新密码至少6位', trigger: 'blur' }],
  confirmPwd: [{ required: true, trigger: 'blur', validator: (_, v, cb) => v === pwdForm.newPassword ? cb() : cb(new Error('两次密码不一致')) }]
}
async function onPwdSubmit() {
  await pwdRef.value.validate()
  pwdLoading.value = true
  try {
    await changePassword({ oldPassword: pwdForm.oldPassword, newPassword: pwdForm.newPassword })
    ElMessage.success('密码已修改，请重新登录')
    pwdVisible.value = false
    localStorage.removeItem('ykt_token')
    sessionStorage.removeItem('ykt_tabs')
    resetPerms()
    router.replace('/login')
  } finally { pwdLoading.value = false }
}
</script>

<style scoped>
/* ===== 暖人文 · Anthropic 风 设计令牌 ===== */
.app-shell {
  --paper: #faf9f5; --card: #fffefb; --ink: #191915; --sub: #6b675e;
  --weak: #9c978b; --line: #e7e2d6; --clay: #cc785c; --clay-deep: #b15c41; --clay-bg: #f6ece5;
  height: 100vh; display: flex; flex-direction: column;
}

/* 顶栏：暖白底 + 深字 + hairline，去掉政务绿渐变 */
.app-header {
  background: var(--card); color: var(--ink); padding: 0 24px;
  display: flex; align-items: center; flex-shrink: 0;
  border-bottom: 1px solid var(--line);
}
.brand { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }
.logo-mark {
  width: 34px; height: 34px; border-radius: 9px; background: var(--clay); color: #fff;
  font-size: 18px; font-weight: 700; display: flex; align-items: center; justify-content: center;
  box-shadow: 0 4px 10px -4px rgba(204, 120, 92, 0.6);
}
.brand-text { font-size: 16px; font-weight: 700; letter-spacing: 0.5px; color: var(--ink); white-space: nowrap; }
.header-warn { flex: 1; text-align: center; font-size: 13px; color: #c0392b; letter-spacing: 0.5px; }
.header-right { display: flex; align-items: center; gap: 16px; flex-shrink: 0; }
.year { font-size: 13px; color: var(--weak); }
.user-trigger { display: flex; align-items: center; gap: 8px; color: var(--ink); cursor: pointer; padding: 6px 10px; border-radius: 8px; transition: background 0.15s; }
.user-trigger:hover { background: var(--paper); }
.username { font-size: 14px; }
.role-tag { background: var(--clay-bg) !important; border: 1px solid #e8c4b4 !important; color: var(--clay-deep) !important; }

.app-body { flex: 1; overflow: hidden; }

/* 左侧：暖白底完整菜单树 */
.app-aside { background: var(--card); border-right: 1px solid var(--line); overflow-y: auto; }
.side-menu { border-right: none !important; background: transparent; padding: 8px; }
.side-menu :deep(.el-menu-item), .side-menu :deep(.el-sub-menu__title) {
  height: 44px; border-radius: 8px; margin-bottom: 2px; color: var(--sub); font-size: 14px;
}
.side-menu :deep(.el-menu-item:hover), .side-menu :deep(.el-sub-menu__title:hover) {
  background: var(--paper); color: var(--ink);
}
.side-menu :deep(.el-menu-item.is-active) {
  background: var(--clay-bg) !important; color: var(--clay-deep) !important; font-weight: 600;
}
.side-menu :deep(.el-menu-item .el-icon), .side-menu :deep(.el-sub-menu__title .el-icon) { color: var(--clay); }
.side-menu :deep(.home-item) { color: var(--ink); font-weight: 600; }

.main-col { flex: 1; display: flex; flex-direction: column; overflow: hidden; min-width: 0; }
.tabs-bar {
  display: flex; gap: 6px; padding: 8px 16px 0; background: var(--paper);
  border-bottom: 1px solid var(--line); overflow-x: auto; flex-shrink: 0;
}
.page-tab {
  display: flex; align-items: center; gap: 6px; padding: 5px 12px; font-size: 13px;
  color: var(--sub); background: var(--card); border: 1px solid var(--line); border-bottom: none;
  border-radius: 8px 8px 0 0; cursor: pointer; white-space: nowrap; user-select: none;
}
.page-tab:hover { color: var(--ink); }
.page-tab.active { color: var(--clay-deep); background: var(--clay-bg); border-color: #e8c4b4; font-weight: 600; }
.tab-close { font-size: 12px; border-radius: 50%; }
.tab-close:hover { background: var(--clay); color: #fff; }

.app-main { padding: 16px; background: var(--paper); overflow-y: auto; }
.fade-enter-active, .fade-leave-active { transition: opacity 0.15s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
