<template>
  <div class="report">
    <h2 class="page-title">惠民系统使用情况查询</h2>

    <el-card shadow="never" class="bar">
      <el-button type="primary" :icon="Search" @click="reload">查询</el-button>
      <el-button :icon="Download" @click="doExport">导出</el-button>
    </el-card>

    <el-card shadow="never" class="filter">
      <el-form inline @submit.prevent>
        <el-form-item label="开始日期：">
          <el-date-picker v-model="q.startDate" type="date" value-format="YYYY-MM-DD" style="width:200px" />
        </el-form-item>
        <el-form-item label="截止日期：">
          <el-date-picker v-model="q.endDate" type="date" value-format="YYYY-MM-DD" style="width:200px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" round @click="reload">查询</el-button>
          <el-button plain @click="expanded = !expanded">显示/隐藏</el-button>
        </el-form-item>
        <template v-if="expanded">
          <el-form-item label="部门名称：">
            <el-select v-model="q.competentDept" filterable clearable placeholder=" " style="width:300px">
              <el-option v-for="d in depts" :key="d" :label="d" :value="d" />
            </el-select>
          </el-form-item>
        </template>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" border stripe size="default" show-summary :summary-method="summary">
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="year" label="年度" width="80" align="center" />
        <el-table-column prop="deptName" label="部门名称" min-width="200" show-overflow-tooltip />
        <el-table-column prop="projectCnt" label="项目个数" width="100" align="right" />
        <el-table-column prop="batchCnt" label="批次个数" width="100" align="right" />
        <el-table-column prop="townCnt" label="乡镇个数" width="100" align="right" />
        <el-table-column prop="villageCnt" label="村组个数" width="100" align="right" />
        <el-table-column prop="holderCnt" label="户主个数" width="100" align="right" />
        <el-table-column prop="beneficiaryCnt" label="享受人个数" width="110" align="right" />
        <el-table-column prop="amount" label="发放金额" width="140" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="actualCnt" label="实际发放人个数" width="130" align="right" />
        <el-table-column prop="actualAmount" label="实际支付金额" width="140" align="right">
          <template #default="{ row }">{{ money(row.actualAmount) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Download } from '@element-plus/icons-vue'
import { reportApi, projectApi } from '../../api/system'

const projects = ref([])
const now = new Date()
const ym = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
const q = reactive({ startDate: `${ym}-01`, endDate: `${ym}-${new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate()}`, competentDept: null })
const rows = ref([]); const loading = ref(false); const expanded = ref(false)
const depts = computed(() => [...new Set(projects.value.map(p => p.competentDept).filter(Boolean))])

onMounted(async () => {
  projects.value = (await projectApi.list({ included: 1, tab: 'all' })) || []
  reload()
})

async function reload() {
  loading.value = true
  try {
    rows.value = (await reportApi.usage({
      startDate: q.startDate || undefined, endDate: q.endDate || undefined, competentDept: q.competentDept || undefined
    })) || []
  } finally { loading.value = false }
}

function money(v) { return v == null ? '0.00' : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }
const NUM_COLS = ['projectCnt', 'batchCnt', 'townCnt', 'villageCnt', 'holderCnt', 'beneficiaryCnt', 'actualCnt']
const MONEY_COLS = ['amount', 'actualAmount']
function summary({ columns }) {
  const sum = k => rows.value.reduce((a, r) => a + (Number(r[k]) || 0), 0)
  return columns.map((c, i) => {
    if (i === 0) return '合计'
    if (NUM_COLS.includes(c.property)) return sum(c.property)
    if (MONEY_COLS.includes(c.property)) return money(sum(c.property))
    return ''
  })
}

function doExport() {
  if (!rows.value.length) { ElMessage.warning('无数据可导出'); return }
  const head = ['年度', '部门名称', '项目个数', '批次个数', '乡镇个数', '村组个数', '户主个数', '享受人个数', '发放金额', '实际发放人个数', '实际支付金额']
  const lines = rows.value.map(r => [r.year, r.deptName, r.projectCnt, r.batchCnt, r.townCnt, r.villageCnt, r.holderCnt, r.beneficiaryCnt, r.amount, r.actualCnt, r.actualAmount]
    .map(v => `"${v == null ? '' : String(v).replace(/"/g, '""')}"`).join(','))
  const csv = '﻿' + [head.join(','), ...lines].join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob); const a = document.createElement('a')
  a.href = url; a.download = '惠民系统使用情况查询.csv'; document.body.appendChild(a); a.click()
  document.body.removeChild(a); URL.revokeObjectURL(url)
}
</script>

<style scoped>
.bar :deep(.el-card__body) { padding: 12px 16px; display: flex; gap: 8px; }
.filter { margin: 12px 0; }
.filter :deep(.el-card__body) { padding: 16px 16px 0; }
</style>
