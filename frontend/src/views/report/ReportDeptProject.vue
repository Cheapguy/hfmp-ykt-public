<template>
  <div class="report">
    <h2 class="page-title">部门项目发放情况查询</h2>

    <el-card shadow="never" class="bar">
      <el-button type="primary" :icon="Search" @click="reload">查询</el-button>
      <el-button :icon="Download" @click="doExport">导出</el-button>
    </el-card>

    <el-card shadow="never" class="filter">
      <el-form inline @submit.prevent>
        <el-form-item label="项目名称：">
          <el-select v-model="q.projectId" filterable clearable placeholder=" " style="width:300px" @change="onProj">
            <el-option v-for="p in projects" :key="p.id" :label="`${p.projectCode || ''} ${p.projectName}`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="批次名称：">
          <el-select v-model="q.batchId" filterable clearable placeholder="选择项目后加载" style="width:280px" :disabled="!q.projectId">
            <el-option v-for="b in batches" :key="b.id" :label="b.batchName || b.batchCode" :value="b.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" round @click="reload">查询</el-button>
          <el-button plain @click="expanded = !expanded">显示/隐藏</el-button>
        </el-form-item>
        <template v-if="expanded">
          <el-form-item label="部门名称：">
            <el-select v-model="q.competentDept" filterable clearable placeholder="请选择部门/单位" style="width:340px">
              <el-option v-for="a in agencyUnits" :key="a.guid" :label="`${a.code}-${a.name}`" :value="a.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="区划：">
            <el-select v-model="q.townId" filterable clearable placeholder=" " style="width:200px">
              <el-option v-for="t in towns" :key="t.id" :label="t.orgName" :value="t.id" />
            </el-select>
          </el-form-item>
        </template>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" border size="default" :row-class-name="rowClass">
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column label="部门名称" width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.rowType === 'TOTAL'">合计</span>
            <span v-else-if="row.rowType === 'DEPT_SUBTOTAL'">部门小计</span>
            <span v-else>{{ row.deptName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="项目名称" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.rowType === 'PROJECT_SUBTOTAL'">项目小计</span>
            <span v-else-if="row.rowType === 'DETAIL'">{{ row.projectName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="批次名称" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.rowType === 'DETAIL' ? row.batchName : '' }}</template>
        </el-table-column>
        <el-table-column label="补贴发放金额" width="150" align="right">
          <template #default="{ row }">{{ money(row.grantAmount) }}</template>
        </el-table-column>
        <el-table-column label="兑付人次（人/次）" width="150" align="right">
          <template #default="{ row }">{{ row.grantCount }}</template>
        </el-table-column>
        <el-table-column label="发放时间" width="120" align="center">
          <template #default="{ row }">{{ row.rowType === 'DETAIL' ? row.grantDate : '' }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Download } from '@element-plus/icons-vue'
import { reportApi, projectApi, queryApi, orgApi, agencyApi } from '../../api/system'

const projects = ref([]); const batches = ref([]); const towns = ref([]); const agencyUnits = ref([])
const q = reactive({ projectId: null, batchId: null, competentDept: null, townId: null })
const rows = ref([]); const loading = ref(false)
const expanded = ref(false)

onMounted(async () => {
  projects.value = (await projectApi.list({ included: 1, tab: 'all' })) || []
  towns.value = ((await orgApi.tree()) || []).filter(o => o.orgType === 'TOWN')
  agencyUnits.value = (await agencyApi.list({ level: 2 })) || []  // 部门名称下拉=机构子单位
  reload()
})

async function onProj() {
  q.batchId = null; batches.value = []
  if (q.projectId) batches.value = (await queryApi.batches({ projectId: q.projectId })) || []
}

async function reload() {
  loading.value = true
  try {
    rows.value = (await reportApi.deptProject({
      projectId: q.projectId || undefined, batchId: q.batchId || undefined,
      competentDept: q.competentDept || undefined, townId: q.townId || undefined
    })) || []
  } finally { loading.value = false }
}

function rowClass({ row }) {
  return { TOTAL: 'r-total', DEPT_SUBTOTAL: 'r-dept', PROJECT_SUBTOTAL: 'r-proj' }[row.rowType] || ''
}
function money(v) { return v == null ? '0.00' : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }

function doExport() {
  if (!rows.value.length) { ElMessage.warning('无数据可导出'); return }
  const label = r => r.rowType === 'TOTAL' ? '合计' : r.rowType === 'DEPT_SUBTOTAL' ? '部门小计' : r.deptName
  const proj = r => r.rowType === 'PROJECT_SUBTOTAL' ? '项目小计' : r.rowType === 'DETAIL' ? r.projectName : ''
  const head = ['部门名称', '项目名称', '批次名称', '补贴发放金额', '兑付人次', '发放时间']
  const lines = rows.value.map(r => [label(r), proj(r), r.rowType === 'DETAIL' ? r.batchName : '', r.grantAmount, r.grantCount, r.rowType === 'DETAIL' ? r.grantDate : '']
    .map(v => `"${v == null ? '' : String(v).replace(/"/g, '""')}"`).join(','))
  const csv = '﻿' + [head.join(','), ...lines].join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob); const a = document.createElement('a')
  a.href = url; a.download = '部门项目发放情况查询.csv'; document.body.appendChild(a); a.click()
  document.body.removeChild(a); URL.revokeObjectURL(url)
}
</script>

<style scoped>
.bar :deep(.el-card__body) { padding: 12px 16px; display: flex; gap: 8px; }
.filter { margin: 12px 0; }
.filter :deep(.el-card__body) { padding: 16px 16px 0; }
:deep(.r-total) { background: var(--el-color-primary-light-9) !important; font-weight: 700; }
:deep(.r-dept) { background: #f6f3ec !important; font-weight: 600; }
:deep(.r-proj) { background: #faf8f2 !important; font-weight: 600; }
</style>
