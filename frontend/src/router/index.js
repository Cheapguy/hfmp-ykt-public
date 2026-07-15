import { createRouter, createWebHashHistory } from 'vue-router'
import { ensurePerms, hasPath } from '../store/perm'

const Login = () => import('../views/Login.vue')
const Layout = () => import('../layout/index.vue')
const Dashboard = () => import('../views/Dashboard.vue')
const Placeholder = () => import('../views/Placeholder.vue')
const Forbidden = () => import('../views/Forbidden.vue')

// 发放数据审核
const Audit = () => import('../views/audit/Audit.vue')
// 系统管理-查询 / 通知下载
const Query = () => import('../views/dept/Query.vue')
const NoticeDownload = () => import('../views/setup/NoticeDownload.vue')

// 安全管理
const User = () => import('../views/sys/User.vue')
const Role = () => import('../views/sys/Role.vue')
const Menu = () => import('../views/sys/Menu.vue')
const Org  = () => import('../views/sys/Org.vue')
// 系统设置
const Bank        = () => import('../views/setup/Bank.vue')
const Village     = () => import('../views/setup/Village.vue')
const Beneficiary = () => import('../views/setup/Beneficiary.vue')
// 主管部门
const Project = () => import('../views/dept/Project.vue')
const ProjectAudit = () => import('../views/dept/ProjectAudit.vue')
const ProjectLink = () => import('../views/dept/ProjectLink.vue')
const ProjectPolicy = () => import('../views/dept/ProjectPolicy.vue')
const TplDefine = () => import('../views/dept/TplDefine.vue')
const ProjectQuery = () => import('../views/dept/ProjectQuery.vue')
const Notice  = () => import('../views/dept/Notice.vue')
const Policy  = () => import('../views/dept/Policy.vue')
const Batch   = () => import('../views/dept/Batch.vue')
const BatchSend = () => import('../views/dept/BatchSend.vue')
const Correction = () => import('../views/dept/Correction.vue')
// 花名册
const Roster  = () => import('../views/roster/Roster.vue')
const RosterEdit = () => import('../views/roster/RosterEdit.vue')
// 惠民报表
const ReportPerson  = () => import('../views/report/ReportPerson.vue')
const ReportDetail  = () => import('../views/report/ReportDetail.vue')
const ReportProject = () => import('../views/report/ReportProject.vue')
const ReportDeptProject = () => import('../views/report/ReportDeptProject.vue')
const ReportUsage   = () => import('../views/report/ReportUsage.vue')
const ReportProjectStat  = () => import('../views/report/ReportProjectStat.vue')
const ReportCountyStat   = () => import('../views/report/ReportCountyStat.vue')
const ReportStd51        = () => import('../views/report/ReportStd51.vue')
const ReportSelfProject  = () => import('../views/report/ReportSelfProject.vue')
// 集中支付
const Quota   = () => import('../views/pay/Quota.vue')
const Apply   = () => import('../views/pay/Apply.vue')
const PaySubmit = () => import('../views/pay/Submit.vue')

const routes = [
  { path: '/login', name: 'Login', component: Login, meta: { title: '登录', noAuth: true } },
  {
    path: '/',
    component: Layout,
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'Dashboard', component: Dashboard, meta: { title: '工作台', public: true } },
      { path: '403', name: 'Forbidden', component: Forbidden, meta: { title: '无权限', public: true } },
      { path: 'dept/audit/review', name: 'Audit', component: Audit, meta: { title: '发放数据审核' } },
      { path: 'sys/user', name: 'User', component: User, meta: { title: '用户管理' } },
      { path: 'sys/role', name: 'Role', component: Role, meta: { title: '角色管理' } },
      { path: 'sys/menu', name: 'Menu', component: Menu, meta: { title: '菜单管理' } },
      { path: 'sys/org',  name: 'Org',  component: Org,  meta: { title: '机构管理' } },
      { path: 'setup/bank',        name: 'Bank',        component: Bank,        meta: { title: '开户银行设置' } },
      { path: 'setup/village',     name: 'Village',     component: Village,     meta: { title: '村组维护' } },
      { path: 'setup/beneficiary', name: 'Beneficiary', component: Beneficiary, meta: { title: '补贴对象维护' } },
      { path: 'dept/query/list', name: 'Query', component: Query, meta: { title: '查询' } },
      { path: 'setup/notice-download', name: 'NoticeDownload', component: NoticeDownload, meta: { title: '通知下载' } },
      { path: 'dept/project',      name: 'Project',     component: Project,     meta: { title: '补贴项目维护' } },
      { path: 'dept/project-audit',name: 'ProjectAudit',component: ProjectAudit,meta: { title: '补贴项目审核' } },
      { path: 'dept/project-link', name: 'ProjectLink', component: ProjectLink, meta: { title: '项目纳入及挂接' } },
      { path: 'dept/project-policy', name: 'ProjectPolicy', component: ProjectPolicy, meta: { title: '政策关联项目' } },
      { path: 'dept/tpl-define',   name: 'TplDefine',   component: TplDefine,   meta: { title: '发放表定义' } },
      { path: 'dept/project-query',name: 'ProjectQuery',component: ProjectQuery,meta: { title: '补贴项目查询' } },
      { path: 'dept/notice',       name: 'Notice',      component: Notice,      meta: { title: '通知公告管理' } },
      { path: 'dept/policy',       name: 'Policy',      component: Policy,      meta: { title: '政策基础录入' } },
      { path: 'dept/batch',        name: 'Batch',       component: Batch,       meta: { title: '补贴批次维护' } },
      { path: 'dept/batch-send',   name: 'BatchSend',   component: BatchSend,   meta: { title: '批次发送' } },
      { path: 'dept/correction',   name: 'Correction',  component: Correction,  meta: { title: '更正发放' } },
      { path: 'roster/fill',       name: 'RosterFill',  component: Roster,      meta: { title: '花名册填报' } },
      { path: 'roster/edit',       name: 'RosterEdit',  component: RosterEdit,  meta: { title: '编制花名册' } },
      // 补贴录入子项（55项国标菜单 /entry/{code}）：复用编制花名册页，按项目编码过滤批次；
      // 不设 meta.title，标签页标题回落到菜单名（如「2082102-农村分散供养特困人员供养金」）
      { path: 'entry/:code(\\d+)', name: 'GrantEntry',  component: RosterEdit },
      { path: 'roster/audit',      name: 'RosterAudit', component: Roster,      meta: { title: '花名册审核' } },
      { path: 'report/dept-project',name: 'ReportDeptProject', component: ReportDeptProject, meta: { title: '部门项目发放情况查询' } },
      { path: 'report/person',     name: 'ReportPerson', component: ReportPerson,  meta: { title: '乡镇发放情况个人查询' } },
      { path: 'report/detail',     name: 'ReportDetail', component: ReportDetail,  meta: { title: '乡镇发放情况明细查询' } },
      { path: 'report/project',    name: 'ReportProject',component: ReportProject, meta: { title: '乡镇项目发放情况查询' } },
      { path: 'report/usage',      name: 'ReportUsage',  component: ReportUsage,   meta: { title: '惠民系统使用情况查询' } },
      { path: 'report/project-stat', name: 'ReportProjectStat', component: ReportProjectStat, meta: { title: '分项目发放情况统计表' } },
      { path: 'report/county-stat',  name: 'ReportCountyStat',  component: ReportCountyStat,  meta: { title: '分县区发放情况统计表' } },
      { path: 'report/std51',        name: 'ReportStd51',       component: ReportStd51,       meta: { title: '51项补贴项目发放表' } },
      { path: 'report/self-project', name: 'ReportSelfProject', component: ReportSelfProject, meta: { title: '补贴项目发放表(自设项目)' } },
      { path: 'pay/quota',         name: 'Quota',       component: Quota,       meta: { title: '项目额度挂接' } },
      { path: 'pay/apply',         name: 'Apply',       component: Apply,       meta: { title: '发起支付申请' } },
      { path: 'pay/submit',        name: 'PaySubmit',   component: PaySubmit,   meta: { title: '支付签章送审' } },
      // 未实现走占位页
      { path: ':pathMatch(.*)*', name: 'Placeholder', component: Placeholder }
    ]
  }
]

const router = createRouter({
  history: createWebHashHistory('/hfmp-ykt/'),
  routes
})

router.beforeEach(async (to) => {
  if (to.meta.noAuth) return true
  const token = localStorage.getItem('ykt_token')
  if (!token) return { path: '/login', query: { redirect: to.fullPath } }
  // 公共页（工作台/403）与未实现占位页不做菜单校验
  if (to.meta.public || to.name === 'Placeholder') return true
  // 菜单拉取失败时不能 return true 放行——那会渲染受保护页面的外壳(fail-open)。
  // 401 由 axios 拦截器兜底跳登录；其余错误回落到公共工作台，绝不带着未校验的权限进受保护页。
  try { await ensurePerms() } catch (e) { return { path: '/dashboard' } }
  // 页面级权限 = 用户菜单里有该 path（meta.perm 可显式指定所需菜单，供无独立菜单的子页继承）
  const need = to.meta.perm ? [].concat(to.meta.perm) : [to.path]
  if (need.some(p => hasPath(p))) return true
  return { path: '/403', query: { from: to.fullPath } }
})

export default router
