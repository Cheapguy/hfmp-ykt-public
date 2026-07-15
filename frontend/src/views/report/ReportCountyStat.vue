<template>
  <div class="report">
    <h2 class="page-title">分县区发放情况统计表</h2>

    <el-card shadow="never" class="bar">
      <el-button type="primary" :icon="Search" @click="reload">查询</el-button>
      <el-button :icon="Download" @click="doExport">导出</el-button>
    </el-card>

    <el-card shadow="never" class="filter">
      <el-form inline @submit.prevent>
        <el-form-item label="项目：">
          <el-select v-model="q.projectId" filterable clearable placeholder=" " style="width:320px">
            <el-option v-for="p in projects" :key="p.id" :label="`${p.projectCode || ''}-${p.projectName}`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始日期：">
          <el-date-picker v-model="q.startDate" type="date" value-format="YYYY-MM-DD" style="width:180px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" round @click="reload">查询</el-button>
          <el-button plain @click="expanded = !expanded">显示/隐藏</el-button>
        </el-form-item>
        <template v-if="expanded">
          <el-form-item label="截止日期：">
            <el-date-picker v-model="q.endDate" type="date" value-format="YYYY-MM-DD" style="width:180px" />
          </el-form-item>
          <el-form-item label="区划：">
            <el-select v-model="q.countyCode" filterable clearable placeholder=" " style="width:240px">
              <el-option v-for="c in counties" :key="c.code" :label="`${c.code}000-${c.name}`" :value="c.code" />
            </el-select>
          </el-form-item>
        </template>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" border size="default" :row-class-name="rowClass">
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="province" label="省、市（州）" width="140" show-overflow-tooltip />
        <el-table-column prop="regionCode" label="区划编码" width="120" align="center" />
        <el-table-column prop="regionName" label="区划名称" width="140" show-overflow-tooltip />
        <el-table-column prop="projectCode" label="补贴项目编码" width="140" align="center" />
        <el-table-column prop="projectName" label="补贴项目名称" min-width="240" show-overflow-tooltip />
        <el-table-column prop="batchCnt" label="下达批次数" width="110" align="right" />
        <el-table-column prop="personCnt" label="兑付人次（人/次）" width="150" align="right" />
        <el-table-column prop="amount" label="兑付金额（元）" width="170" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Download } from '@element-plus/icons-vue'
import { reportApi, projectApi } from '../../api/system'

const projects = ref([]); const counties = ref([])
const now = new Date()
const q = reactive({ projectId: null, countyCode: null, startDate: `${now.getFullYear() - 1}-01-01`, endDate: null })
const rows = ref([]); const loading = ref(false); const expanded = ref(false)

onMounted(async () => {
  const [ps, cs] = await Promise.all([
    projectApi.list({ included: 1, tab: 'all' }), reportApi.counties()
  ])
  projects.value = ps || []; counties.value = cs || []
  reload()
})

async function reload() {
  loading.value = true
  try {
    rows.value = (await reportApi.countyStat({
      projectId: q.projectId || undefined, countyCode: q.countyCode || undefined,
      startDate: q.startDate || undefined, endDate: q.endDate || undefined
    })) || []
  } finally { loading.value = false }
}

function rowClass({ row }) { return { TOTAL: 'r-total', SUBTOTAL: 'r-sub' }[row.rowType] || '' }
function money(v) { return v == null ? '0.00' : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }

function doExport() {
  if (!rows.value.length) { ElMessage.warning('无数据可导出'); return }
  const head = ['省、市（州）', '区划编码', '区划名称', '补贴项目编码', '补贴项目名称', '下达批次数', '兑付人次（人/次）', '兑付金额（元）']
  const lines = rows.value.map(r => [r.province, r.regionCode, r.regionName, r.projectCode, r.projectName, r.batchCnt, r.personCnt, r.amount]
    .map(v => `"${v == null ? '' : String(v).replace(/"/g, '""')}"`).join(','))
  const csv = '﻿' + [head.join(','), ...lines].join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob); const a = document.createElement('a')
  a.href = url; a.download = '分县区发放情况统计表.csv'; document.body.appendChild(a); a.click()
  document.body.removeChild(a); URL.revokeObjectURL(url)
}
</script>

<style scoped>
.bar :deep(.el-card__body) { padding: 12px 16px; display: flex; gap: 8px; }
.filter { margin: 12px 0; }
.filter :deep(.el-card__body) { padding: 16px 16px 0; }
:deep(.r-total) { font-weight: 700; background: var(--el-fill-color-light); }
:deep(.r-sub) { font-weight: 600; background: var(--el-fill-color-lighter); }
</style>
