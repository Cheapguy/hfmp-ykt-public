<template>
  <div>
    <h2 class="page-title">更正发放</h2>

    <!-- 工具条 -->
    <el-card shadow="never" class="bar">
      <el-button :icon="Download" @click="doExport">导出</el-button>
      <el-button type="primary" :icon="RefreshRight" @click="doRebuild">批次重构</el-button>
      <span class="sel-hint" v-if="selected.length">已选 {{ selected.length }} 条</span>
    </el-card>

    <!-- 筛选 -->
    <el-card shadow="never" class="filter">
      <el-form inline @submit.prevent>
        <el-form-item label="项目：">
          <el-select v-model="query.projectId" filterable clearable placeholder=" " style="width:300px" @change="onProjectChange">
            <el-option v-for="p in projects" :key="p.id" :label="`${p.projectCode || ''} ${p.projectName}`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="批次：">
          <el-select v-model="query.batchCode" clearable filterable remote :remote-method="loadBatches"
            :loading="batchLoading" placeholder="选择项目后加载" style="width:280px"
            :disabled="!query.projectId" @visible-change="onBatchOpen">
            <el-option v-for="b in batches" :key="b.batchCode"
              :label="b.batchName ? `${b.batchCode} ${b.batchName}` : b.batchCode" :value="b.batchCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="乡镇：">
          <el-select v-model="query.townId" clearable filterable placeholder=" " style="width:200px">
            <el-option v-for="t in towns" :key="t.id" :label="t.orgName" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" round @click="reload">查询</el-button></el-form-item>
      </el-form>
    </el-card>

    <!-- 列表 -->
    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" border stripe size="default" row-key="id"
        show-summary :summary-method="summary" @selection-change="s => selected = s">
        <el-table-column type="selection" width="44" reserve-selection />
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="beneficiaryName" label="姓名" width="100" />
        <el-table-column prop="bankAccount" label="银行卡号" width="190" show-overflow-tooltip />
        <el-table-column prop="batchName" label="发放批次" min-width="200" show-overflow-tooltip />
        <el-table-column prop="townName" label="所在乡镇" width="160" show-overflow-tooltip />
        <el-table-column prop="villageName" label="村组" width="150" show-overflow-tooltip />
        <el-table-column prop="amount" label="申请金额" width="120" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="STATUS_TYPE[row.payStatus] || 'danger'">{{ row.payStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="failReason" label="失败原因" min-width="160" show-overflow-tooltip />
        <el-table-column label="发放次数" width="100" align="center">
          <template #default="{ row }">第 {{ (row.retryTimes || 0) + 1 }} 次</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="110" show-overflow-tooltip />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, RefreshRight } from '@element-plus/icons-vue'
import { correctionApi, projectApi, orgApi, queryApi } from '../../api/system'

const route = useRoute()

const STATUS_TYPE = { '已退款': 'warning', '已退回': 'danger', '支付失败': 'danger' }
const projects = ref([]); const towns = ref([]); const batches = ref([]); const batchLoading = ref(false)
const query = reactive({ projectId: null, batchCode: '', townId: null })
const rows = ref([]); const loading = ref(false); const selected = ref([])

// 工作台待办点入：默认选中对应项目/批次（Long 已按字符串序列化，直接用，勿 Number 丢精度）
async function applyRouteQuery() {
  if (route.query.projectId) query.projectId = String(route.query.projectId)
  if (route.query.batchCode) query.batchCode = String(route.query.batchCode)
  if (query.projectId) await loadBatches('')   // 预载批次下拉，令批次能显示名称
  reload()
}

onMounted(async () => {
  projects.value = (await projectApi.list({ included: 1, tab: 'all' })) || []
  towns.value = ((await orgApi.tree()) || []).filter(o => o.orgType === 'TOWN')
  await applyRouteQuery()
})

// 本页标签被 keep-alive 缓存且 :key 只认 path 不含 query：标签不关时再点另一条待办，
// 组件不会重新挂载，只 onMounted 回填的话筛选框仍是上一条、静默无反应。
// 两个必须条件（缺一个就串页/串筛选，同 RosterEdit 的 ownPath 范式）：
// ① 守 ownPath —— keep-alive 不暂停 watcher，route 又是全应用共享，不守就会被别的页面导航触发：
//    /dept/audit/review 也读 projectId+batchCode 这两个键，点「待审核」会把本页筛选改成审核批次。
// ② 监听具体键的值而非 route.query 对象 —— 对象每次导航都是新引用，点回本标签时 layout 会 push
//    冻结的原 fullPath，监听对象会重跑一遍回填、把用户手改的筛选盖回去。
const ownPath = route.path
watch([() => route.query.projectId, () => route.query.batchCode], () => {
  if (route.path !== ownPath) return
  applyRouteQuery()
})

async function reload() {
  loading.value = true
  try {
    rows.value = (await correctionApi.list({
      projectId: query.projectId || undefined,
      batchCode: query.batchCode || undefined,
      townId: query.townId || undefined
    })) || []
  } finally { loading.value = false }
}

async function onProjectChange() {
  query.batchCode = ''; batches.value = []
  if (query.projectId) loadBatches('')
  reload()
}
async function loadBatches(keyword) {
  if (!query.projectId) return
  batchLoading.value = true
  try { batches.value = (await queryApi.batches({ projectId: query.projectId, keyword: keyword || undefined })) || [] }
  finally { batchLoading.value = false }
}
function onBatchOpen(open) { if (open && query.projectId && !batches.value.length) loadBatches('') }

function money(v) { return v == null ? '' : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }
function summary({ columns }) {
  const s = []
  const sum = rows.value.reduce((a, r) => a + (Number(r.amount) || 0), 0)
  columns.forEach((c, i) => {
    if (i === 1) s[i] = '合计'
    else if (c.property === 'amount') s[i] = money(sum)
    else s[i] = ''
  })
  return s
}

async function doRebuild() {
  if (!selected.value.length) { ElMessage.warning('请勾选需要重构的数据'); return }
  await ElMessageBox.confirm(`确定对选中的 ${selected.value.length} 条数据进行批次重构？将按项目+乡镇生成新批次交乡镇重新填报。`, '批次重构', { type: 'warning' })
  const res = await correctionApi.rebuild(selected.value.map(r => String(r.id)))
  ElMessage.success(`重构完成：生成 ${res?.batchCount ?? 0} 个新批次，复制 ${res?.personCount ?? 0} 人`)
  reload()
}

function doExport() {
  if (!rows.value.length) { ElMessage.warning('无数据可导出'); return }
  const head = ['姓名', '银行卡号', '发放批次', '所在乡镇', '村组', '申请金额', '状态', '失败原因', '发放次数', '备注']
  const lines = rows.value.map(r => [r.beneficiaryName, r.bankAccount, r.batchName, r.townName, r.villageName, r.amount, r.payStatus, r.failReason, `第${(r.retryTimes || 0) + 1}次`, r.remark]
    .map(v => `"${v == null ? '' : String(v).replace(/"/g, '""')}"`).join(','))
  const csv = '﻿' + [head.join(','), ...lines].join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = '更正发放.csv'; document.body.appendChild(a); a.click()
  document.body.removeChild(a); URL.revokeObjectURL(url)
}
</script>

<style scoped>
.bar :deep(.el-card__body) { padding: 12px 16px; display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.sel-hint { color: #909399; font-size: 13px; margin-left: 8px; }
.filter { margin: 12px 0; }
.filter :deep(.el-card__body) { padding: 16px 16px 0; }
</style>
