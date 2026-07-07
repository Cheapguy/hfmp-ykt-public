<template>
  <div class="dash">
    <!-- 页头 -->
    <header class="dash-head">
      <p class="eyebrow">惠民惠农财政补贴 · 一卡通</p>
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

    <!-- 待办：部门=待更正 / 乡镇=待编制花名册 -->
    <section class="panel">
      <div class="panel-head">
        <h2 class="panel-title">{{ todoTitle }}</h2>
        <span class="panel-meta">{{ todos.length }} 项待处理</span>
      </div>
      <ul class="todo" v-loading="loading">
        <li v-for="t in todos" :key="t.id" @click="goItem(t)">
          <span class="todo-name">{{ t.batchName }}</span>
          <span class="chip" :class="tagClass(t.tag)">{{ t.tag || '新增' }}</span>
          <span class="go">{{ goText }}</span>
        </li>
        <li v-if="!todos.length && !loading" class="empty">{{ emptyText }}</li>
      </ul>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { rosterEditApi, correctionApi, auditApi } from '../api/system'

const router = useRouter()
const steps = ['批次维护', '编制花名册', '业务审核', '生成支付']
const todos = ref([]); const loading = ref(false)
const today = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })

// 首页待办按岗位分流：部门经办=待更正 / 部门审核=待审核 / 乡镇等=待编制花名册
const user = JSON.parse(localStorage.getItem('ykt_user') || '{}')
const userType = String(user.userType || '')
const isDeptOp = computed(() => userType === 'DEPT_OP')
const isAudit = computed(() => userType.endsWith('AUDIT'))   // 部门/乡镇审核岗 → 待审核
const todoTitle = computed(() => isAudit.value ? '待审核' : isDeptOp.value ? '待更正' : '待编制花名册')
const goText = computed(() => isAudit.value ? '前往审核 →' : isDeptOp.value ? '前往更正 →' : '前往编制 →')
const emptyText = computed(() => isAudit.value ? '暂无待审核批次。' : isDeptOp.value ? '暂无待更正批次。' : '暂无待编制花名册，先去主管部门下达批次。')

onMounted(async () => {
  loading.value = true
  try {
    if (isAudit.value) {
      // 待审核：审核链未终审的批次
      const res = await auditApi.page({ pageNum: 1, pageSize: 50, tab: 'pending' })
      todos.value = (res?.records || []).map(b => ({ id: b.id, batchName: b.batchName || b.batchCode, tag: b.status || '待审核' }))
    } else if (isDeptOp.value) {
      // 待更正：退回/支付失败明细，按批次去重
      const list = (await correctionApi.list({})) || []
      const seen = new Set(); const out = []
      for (const d of list) {
        if (!d.batchCode || seen.has(d.batchCode)) continue
        seen.add(d.batchCode)
        out.push({ id: d.batchCode, batchName: d.batchName || d.batchCode, tag: '更正' })
      }
      todos.value = out
    } else {
      todos.value = (await rosterEditApi.pending()) || []
    }
  } finally { loading.value = false }
})

function goItem(t) {
  if (isAudit.value) router.push({ path: '/dept/audit/review' })
  else if (isDeptOp.value) router.push({ path: '/dept/correction' })
  else router.push({ path: '/roster/edit', query: { batchId: t.id } })
}
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
