<template>
  <div class="dash">
    <!-- 页头 -->
    <header class="dash-head">
      <p class="eyebrow">甘肃省惠民惠农财政补贴 · 一卡通</p>
      <h1>工作台</h1>
      <p class="lede">补贴发放全流程一站式管理 —— 从批次下达到确认发放，让每一笔惠民资金可追溯、可核对。</p>
      <span class="date">{{ today }}</span>
    </header>

    <!-- 发放流程 -->
    <section class="panel">
      <div class="panel-head">
        <h2 class="panel-title">发放流程</h2>
        <span class="panel-meta">惠民补贴发放管理系统</span>
      </div>
      <ol class="flow">
        <li v-for="(s, i) in steps" :key="s" class="node" :class="{ first: i === 0 }">
          <span class="disc"><span class="num">{{ String(i + 1).padStart(2, '0') }}</span></span>
          <span class="name">{{ s }}</span>
        </li>
      </ol>
    </section>

    <!-- 待办：只展示「有数据」的面板（无数据自动隐藏），全空时给一句提示 -->
    <section v-if="visiblePanels.length" class="todos" :class="{ dual: visiblePanels.length > 1 }">
      <div v-for="p in visiblePanels" :key="p.key" class="panel">
        <div class="panel-head">
          <h2 class="panel-title">{{ p.title }}</h2>
          <span class="panel-meta">{{ p.error ? '加载失败' : p.items.length + ' 项待处理' }}</span>
        </div>
        <!-- 失败不伪装成空：给出可点重试的提示，否则故障和「真的没活干」看着一样 -->
        <div v-if="p.error" class="todo-error" @click="p.load()">加载失败，点击重试</div>
        <ul v-else class="todo">
          <li v-for="t in p.items" :key="t.id" @click="p.go(t)">
            <span class="todo-name">{{ t.batchName }}</span>
            <span class="chip" :class="tagClass(t.tag)">{{ t.tag || '新增' }}</span>
            <span class="go">{{ p.goText }}</span>
          </li>
        </ul>
      </div>
    </section>
    <section v-else-if="!todoLoading" class="panel empty-all">
      <div class="empty-all-body">暂无待办事项。</div>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onActivated } from 'vue'
import { useRouter } from 'vue-router'
import { rosterEditApi, correctionApi, auditApi, referReqApi, paymentApi } from '../api/system'

const router = useRouter()
const steps = ['批次维护', '编制花名册', '业务审核', '生成支付']
const today = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })

const user = JSON.parse(localStorage.getItem('ykt_user') || '{}')
const userType = String(user.userType || '')

// 待办按岗位并发拉多个接口，某个没权限(403)是正常的，不该一进首页就糊一片红 toast。
// 走静默请求，失败由面板自己标 error 显示「加载失败·点击重试」——不能默默留空，
// 否则和「真的没活干」看着一样，出问题没人发现。
const SILENT = { silent: true }

// —— 待办面板工厂：各自带 items/loading + 独立数据源 ——
// 待更正：退回/支付失败明细，按批次去重
function correctionPanel() {
  return reactive({
    key: 'correction', title: '待更正', goText: '前往更正 →', emptyText: '暂无待更正批次。',
    items: [], loading: false, error: false,
    // 点入更正发放：带项目+批次，页面默认选中
    go: (t) => router.push({ path: '/dept/correction', query: { projectId: t.projectId, batchCode: t.id } }),
    async load() {
      this.loading = true; this.error = false
      try {
        const list = (await correctionApi.list({}, SILENT)) || []
        const seen = new Set(); const out = []
        for (const d of list) {
          if (!d.batchCode || seen.has(d.batchCode)) continue
          seen.add(d.batchCode)
          out.push({ id: d.batchCode, batchName: d.batchName || d.batchCode, tag: '更正', projectId: d.projectId })
        }
        this.items = out
      } catch { this.items = []; this.error = true } finally { this.loading = false }
    }
  })
}
// 待审核：审核链未终审的批次
function auditPanel() {
  return reactive({
    key: 'audit', title: '待审核', goText: '前往审核 →', emptyText: '暂无待审核批次。',
    items: [], loading: false, error: false,
    // 点入发放数据审核：带项目+批次编码，页面默认选中
    go: (t) => router.push({ path: '/dept/audit/review', query: { projectId: t.projectId, batchCode: t.batchCode } }),
    async load() {
      this.loading = true; this.error = false
      try {
        const res = await auditApi.page({ pageNum: 1, pageSize: 50, tab: 'pending' }, SILENT)
        this.items = (res?.records || []).map(b => ({
          id: b.id, batchName: b.batchName || b.batchCode, tag: b.status || '待审核',
          projectId: b.projectId, batchCode: b.batchCode
        }))
      } catch { this.items = []; this.error = true } finally { this.loading = false }
    }
  })
}
// 待编制花名册（乡镇经办）
function rosterPanel() {
  return reactive({
    key: 'roster', title: '待编制花名册', goText: '前往编制 →',
    emptyText: '暂无待编制花名册，先去主管部门下达批次。',
    items: [], loading: false, error: false,
    go: (t) => router.push({ path: '/roster/edit', query: { batchId: t.id } }),
    async load() {
      this.loading = true; this.error = false
      try { this.items = (await rosterEditApi.pending(undefined, SILENT)) || [] }
      catch { this.items = []; this.error = true } finally { this.loading = false }
    }
  })
}

// 待审引用（被引用乡镇）：他乡镇引用本乡镇名下补贴对象的待批请求
function referPanel() {
  return reactive({
    key: 'refer', title: '待审引用', goText: '前往审核 →',
    emptyText: '暂无待审引用请求。',
    items: [], loading: false, error: false,
    go: () => router.push({ path: '/setup/beneficiary', query: { referReq: 'pending' } }),
    async load() {
      this.loading = true; this.error = false
      try {
        this.items = ((await referReqApi.pending(SILENT)) || []).map(r => ({
          id: r.id, batchName: `${r.name || ''}（${r.idCard || ''}）`, tag: '待审核'
        }))
      } catch { this.items = []; this.error = true } finally { this.loading = false }
    }
  })
}

// 引用已通过·待纳入（引用方通知）：对方乡镇审核通过后，本乡镇需点「纳入补贴对象库」才入库
function referIncludePanel() {
  return reactive({
    key: 'refer-include', title: '引用已通过·待纳入', goText: '前往纳入 →',
    emptyText: '暂无待纳入的引用。',
    items: [], loading: false, error: false,
    go: () => router.push({ path: '/setup/beneficiary', query: { referReq: 'mine' } }),
    async load() {
      this.loading = true; this.error = false
      try {
        this.items = ((await referReqApi.mine(SILENT)) || [])
          .filter(r => r.status === 'APPROVED')
          .map(r => ({ id: r.id, batchName: `${r.name || ''}（${r.idCard || ''}）`, tag: '已通过' }))
      } catch { this.items = []; this.error = true } finally { this.loading = false }
    }
  })
}

// 财政端三块（集中支付链路：发起支付 → 签章送审 → 银行代发标记）
// 待发起支付：已发送一体化(SENT)的批次等财政生成支付申请
function payPendingPanel() {
  return reactive({
    key: 'pay-pending', title: '待发起支付', goText: '前往发起 →',
    items: [], loading: false, error: false,
    go: (t) => router.push({ path: '/pay/apply', query: { projectId: t.projectId } }),
    async load() {
      this.loading = true; this.error = false
      try {
        this.items = ((await paymentApi.pending(undefined, SILENT)) || []).map(b => ({
          id: b.id, batchName: b.batchName || b.batchCode, tag: '待支付', projectId: b.projectId
        }))
      } catch { this.items = []; this.error = true } finally { this.loading = false }
    }
  })
}
// 待签章送审：支付申请已生成、等签章送审(PENDING_SUBMIT)
function paySubmitPanel() {
  return reactive({
    key: 'pay-submit', title: '待签章送审', goText: '前往送审 →',
    items: [], loading: false, error: false,
    go: () => router.push({ path: '/pay/submit', query: { tab: 'PENDING_SUBMIT' } }),
    async load() {
      this.loading = true; this.error = false
      try {
        this.items = ((await paymentApi.submitList('PENDING_SUBMIT', SILENT)) || []).map(a => ({
          id: a.id, batchName: a.batchName || a.applyNo, tag: '待送审'
        }))
      } catch { this.items = []; this.error = true } finally { this.loading = false }
    }
  })
}
// 待标记发放结果：已送审(SUBMITTED)的申请等银行代发逐笔标记成败
function payMarkPanel() {
  return reactive({
    key: 'pay-mark', title: '待标记发放结果', goText: '前往标记 →',
    items: [], loading: false, error: false,
    go: () => router.push({ path: '/pay/submit', query: { tab: 'SUBMITTED' } }),
    async load() {
      this.loading = true; this.error = false
      try {
        this.items = ((await paymentApi.submitList('SUBMITTED', SILENT)) || []).map(a => ({
          id: a.id, batchName: a.batchName || a.applyNo, tag: '待代发'
        }))
      } catch { this.items = []; this.error = true } finally { this.loading = false }
    }
  })
}

// 主管部门(经办岗)全包更正+审核 → 并排两块；审核岗看待审核；乡镇经办看待编制 + 待审引用 + 已通过待纳入(它持补贴对象维护菜单，既是审批方也是引用方)；
// 财政(含财政审核岗)走集中支付三块——原来它落 rosterPanel(待编制花名册)，那不是财政的活，工作台恒空。
function pickPanels() {
  if (userType === 'DEPT_OP') return [correctionPanel(), auditPanel()]
  if (userType === 'TOWN_OP') return [rosterPanel(), referPanel(), referIncludePanel()]
  if (userType === 'FINANCE' || userType === 'FIN_REVIEW')
    return [payPendingPanel(), paySubmitPanel(), payMarkPanel()]
  if (userType.endsWith('AUDIT')) return [auditPanel()]
  return [rosterPanel()]
}
const panels = ref(pickPanels())
// 只显示有数据的面板；加载中不算空（避免闪一下「暂无待办」）。
// 加载失败的面板也要显示——留空会和「真的没活干」混为一谈，故障就被藏起来了。
const visiblePanels = computed(() => panels.value.filter(p => p.items.length > 0 || p.error))
const todoLoading = computed(() => panels.value.some(p => p.loading))

const loadAll = () => panels.value.forEach(p => p.load())
// 首页是常驻标签、被 keep-alive 缓存，整个会话只挂载一次：
// 只挂 onMounted 的话，审完/纳入完回首页仍列着已处理项，「无数据隐藏」也不生效，得按 F5。onActivated 保证每次切回来重取。
// 但 keep-alive 组件首次挂载时 mounted 与 activated 都会触发，两个都无脑调 loadAll 会让首屏每块面板
// 请求跑两遍（乡镇/财政各 3 块 = 6 次），两个并发写 items 期间还会闪一下。故首次交给 activated，
// onMounted 只兜「本页没被 keep-alive 缓存」的场景（那时 activated 不触发）。
let loadedOnce = false
const loadOnce = () => { if (loadedOnce) return; loadedOnce = true; loadAll() }
onMounted(loadOnce)
onActivated(() => { if (!loadedOnce) { loadOnce(); return } loadAll() })

function tagClass(s) {
  if (!s) return 'tag-new'
  return s.includes('退回') || s.includes('更正') ? 'tag-back' : 'tag-new'
}
</script>

<style scoped>
/* ===== 暖人文 · Anthropic 风 设计令牌 ===== */
.dash {
  --paper: #faf9f5;      /* 暖米主背景 */
  --paper-2: #f3f1ea;    /* 稍深米：hover / 分区 */
  --card: #fffefb;       /* 暖白卡面 */
  --ink: #191915;        /* 近黑暖调 */
  --sub: #6b675e;        /* 暖灰次要 */
  --weak: #9c978b;       /* 弱化 */
  --line: #e7e2d6;       /* 暖 hairline */
  --clay: #cc785c;       /* 赤陶橙（Claude 橙）强调 */
  --clay-deep: #b15c41;
  --clay-bg: #f6ece5;    /* 赤陶浅底 */
  --serif: Georgia, 'Times New Roman', 'Songti SC', 'STSong', SimSun, serif;

  /* 铺满工作区的暖米底（抵消 layout 内边距） */
  margin: -16px;
  padding: 40px 48px 56px;
  background: var(--paper);
  min-height: calc(100vh - 60px);
  color: var(--ink);
  display: flex;
  flex-direction: column;
  gap: 32px;
}

/* 页头：衬线大标题 + 引导语 */
.dash-head { position: relative; max-width: 760px; }
.dash-head .eyebrow {
  margin: 0 0 14px; font-size: 13px; letter-spacing: 0.08em;
  color: var(--clay); text-transform: none; font-weight: 600;
}
.dash-head h1 {
  margin: 0; font-family: var(--serif); font-weight: 500;
  font-size: 44px; line-height: 1.1; letter-spacing: -0.01em; color: var(--ink);
}
.dash-head .lede { margin: 18px 0 0; font-size: 16px; line-height: 1.7; color: var(--sub); }
.dash-head .date { position: absolute; top: 4px; right: 0; font-size: 13px; color: var(--weak); }

/* 面板：暖白卡 + 柔和圆角 + 暖阴影 */
.panel {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 14px;
  box-shadow: 0 1px 2px rgba(60, 50, 30, 0.04), 0 8px 24px -16px rgba(60, 50, 30, 0.12);
  overflow: hidden;
}
.panel-head {
  display: flex; align-items: baseline; justify-content: space-between;
  padding: 22px 28px; border-bottom: 1px solid var(--line);
}
.panel-title { margin: 0; font-family: var(--serif); font-weight: 500; font-size: 20px; color: var(--ink); }
.panel-meta { font-size: 13px; color: var(--weak); }

/* 流程：柔和暖圆节点 + 连接线 */
.flow {
  position: relative; list-style: none; margin: 0;
  padding: 36px 28px 32px;
  display: grid; grid-template-columns: repeat(4, 1fr);
}
.flow::before {
  content: ''; position: absolute; left: calc(12.5% + 4px); right: calc(12.5% + 4px);
  top: 64px; height: 2px; background: var(--line);
}
.node { position: relative; z-index: 1; display: flex; flex-direction: column; align-items: center; gap: 16px; }
.node .disc {
  width: 56px; height: 56px; border-radius: 50%;
  background: var(--paper-2); border: 1px solid var(--line);
  display: flex; align-items: center; justify-content: center;
  transition: transform 0.18s ease, box-shadow 0.18s ease;
}
.node .num { font-family: var(--serif); font-size: 19px; color: var(--sub); }
.node.first .disc { background: var(--clay); border-color: var(--clay); box-shadow: 0 6px 16px -6px rgba(204, 120, 92, 0.6); }
.node.first .num { color: #fff; }
.node:hover .disc { transform: translateY(-3px); box-shadow: 0 8px 18px -8px rgba(60, 50, 30, 0.25); }
.node .name { font-size: 14px; color: var(--sub); }
.node.first .name { color: var(--ink); font-weight: 600; }

/* 全空占位 */
.empty-all-body { padding: 28px; color: var(--weak); font-size: 14px; }
.todo-error { padding: 24px 28px; color: #cc785c; font-size: 14px; cursor: pointer; }
.todo-error:hover { text-decoration: underline; }

/* 待办分栏：主管部门 待更正 + 待审核 并排 */
.todos { display: grid; grid-template-columns: 1fr; gap: 32px; }
.todos.dual { grid-template-columns: 1fr 1fr; }
@media (max-width: 900px) { .todos.dual { grid-template-columns: 1fr; } }

/* 待办列表 */
.todo { list-style: none; margin: 0; padding: 6px 0; }
.todo li {
  display: flex; align-items: center; gap: 14px;
  padding: 16px 28px; border-bottom: 1px solid var(--line);
  cursor: pointer; transition: background 0.15s ease;
}
.todo li:last-child { border-bottom: none; }
.todo li:hover { background: var(--paper-2); }
.todo-name { font-size: 15px; color: var(--ink); }
.todo .go { margin-left: auto; font-size: 13px; color: var(--clay); opacity: 0; transition: opacity 0.15s ease; }
.todo li:hover .go { opacity: 1; }
.todo .empty { color: var(--weak); cursor: default; }

/* chip：赤陶橙细边框胶囊 */
.chip { font-size: 12px; line-height: 20px; padding: 0 10px; border-radius: 999px; border: 1px solid; }
.tag-new { color: var(--clay-deep); border-color: #e8c4b4; background: var(--clay-bg); }
.tag-back { color: #a23b2c; border-color: #e8b8ad; background: #f9ebe6; }
</style>
