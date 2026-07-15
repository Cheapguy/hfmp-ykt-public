<template>
  <div class="report">
    <h2 class="page-title">补贴项目发放表(自设项目)</h2>

    <el-card shadow="never" class="bar">
      <el-button type="primary" :icon="Search" @click="reload">查询</el-button>
      <el-button :icon="Download" @click="doExport">导出</el-button>
    </el-card>

    <el-card shadow="never" class="filter">
      <el-form inline @submit.prevent>
        <el-form-item label="项目名称：">
          <el-select v-model="q.projectId" filterable clearable placeholder=" " style="width:360px">
            <el-option v-for="p in selfProjects" :key="p.id" :label="`${p.projectCode}-${p.projectName}`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="区划：">
          <el-select v-model="q.countyCode" filterable clearable placeholder=" " style="width:240px">
            <el-option v-for="c in counties" :key="c.code" :label="`${c.code}000-${c.name}`" :value="c.code" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" round @click="reload">查询</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" border size="default" :row-class-name="rowClass">
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="itemName" label="补贴发放项目" min-width="300" show-overflow-tooltip />
        <el-table-column prop="regionName" label="行政区划" width="220" show-overflow-tooltip />
        <el-table-column prop="personCnt" label="兑付人次（人/次）" width="170" align="right" />
        <el-table-column prop="amount" label="兑付金额(元)" width="180" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
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

const projects = ref([]); const counties = ref([])
const q = reactive({ projectId: null, countyCode: null })
const rows = ref([]); const loading = ref(false)
const selfProjects = computed(() => projects.value.filter(p => (p.projectCode || '').startsWith('9')))

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
    rows.value = (await reportApi.selfProject({
      projectId: q.projectId || undefined, countyCode: q.countyCode || undefined
    })) || []
  } finally { loading.value = false }
}

function rowClass({ row }) { return { TOTAL: 'r-total', PROJECT_SUBTOTAL: 'r-sub' }[row.rowType] || '' }
function money(v) { return v == null ? '0.00' : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }

function doExport() {
  if (!rows.value.length) { ElMessage.warning('无数据可导出'); return }
  const head = ['补贴发放项目', '行政区划', '兑付人次（人/次）', '兑付金额(元)']
  const lines = rows.value.map(r => [r.itemName, r.regionName, r.personCnt, r.amount]
    .map(v => `"${v == null ? '' : String(v).replace(/"/g, '""')}"`).join(','))
  const csv = '﻿' + [head.join(','), ...lines].join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob); const a = document.createElement('a')
  a.href = url; a.download = '补贴项目发放表(自设项目).csv'; document.body.appendChild(a); a.click()
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
