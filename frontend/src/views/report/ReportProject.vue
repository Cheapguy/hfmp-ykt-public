<template>
  <div class="report">
    <h2 class="page-title">乡镇项目发放情况查询</h2>

    <el-card shadow="never" class="bar">
      <el-button type="primary" :icon="Search" @click="reload">查询</el-button>
      <el-button :icon="Download" @click="doExport">导出</el-button>
    </el-card>

    <el-card shadow="never" class="filter">
      <div class="filter-grid">
        <div class="fi"><label>项目名称：</label>
          <el-select v-model="q.projectId" filterable clearable placeholder=" " @change="onProj">
            <el-option v-for="p in projects" :key="p.id" :label="`${p.projectCode || ''} ${p.projectName}`" :value="p.id" />
          </el-select>
        </div>
        <div class="fi"><label>批次名称：</label>
          <el-select v-model="q.batchId" filterable clearable placeholder="选择项目后加载" :disabled="!q.projectId">
            <el-option v-for="b in batches" :key="b.id" :label="b.batchName || b.batchCode" :value="b.id" />
          </el-select>
        </div>
        <div class="fi fi-actions">
          <el-button type="primary" :icon="Search" @click="reload">查询</el-button>
          <el-button plain @click="expanded = !expanded">显示/隐藏</el-button>
        </div>
        <template v-if="expanded">
          <div class="fi"><label>部门名称：</label>
            <el-select v-model="q.competentDept" filterable clearable placeholder="请选择部门" fit-input-width
              popper-class="agency-pop">
              <el-option v-for="d in agencyDepts" :key="d.guid" :label="`${d.code}-${d.name}`" :value="d.name" />
            </el-select>
          </div>
          <div class="fi"><label>单位名称：</label>
            <el-select v-model="q.unit" filterable clearable placeholder="请选择单位" fit-input-width
              popper-class="agency-pop">
              <el-option v-for="a in agencyAll" :key="a.guid"
                :label="(a.levelNo === 2 ? '　├ ' : '') + a.code + '-' + a.name" :value="a.guid" />
            </el-select>
          </div>
        </template>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" border stripe size="default" show-summary :summary-method="summary">
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="competentDept" label="部门名称" width="180" show-overflow-tooltip />
        <el-table-column prop="projectName" label="项目名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="batchName" label="批次名称" min-width="200" show-overflow-tooltip />
        <el-table-column prop="month" label="月份/年" width="100" align="center" />
        <el-table-column prop="townName" label="乡镇名称" width="150" show-overflow-tooltip />
        <el-table-column prop="applyAmount" label="补贴申请金额" width="140" align="right">
          <template #default="{ row }">{{ money(row.applyAmount) }}</template>
        </el-table-column>
        <el-table-column prop="applyCount" label="申请享受人数" width="120" align="right" />
        <el-table-column prop="grantAmount" label="补贴发放金额" width="140" align="right">
          <template #default="{ row }">{{ money(row.grantAmount) }}</template>
        </el-table-column>
        <el-table-column prop="grantCount" label="发放享受人数" width="120" align="right" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Download } from '@element-plus/icons-vue'
import { reportApi, projectApi, queryApi, agencyApi } from '../../api/system'

const projects = ref([]); const batches = ref([])
const agencyDepts = ref([])  // 部门名称下拉=机构子单位(level2,101001起)
const agencyAll = ref([])    // 单位名称下拉=机构全量(局领导0100起)
const q = reactive({ projectId: null, batchId: null, competentDept: null, unit: null })
const rows = ref([]); const loading = ref(false)
const expanded = ref(false)

onMounted(async () => {
  projects.value = (await projectApi.list({ included: 1, tab: 'all' })) || []
  agencyDepts.value = (await agencyApi.list({ level: 2 })) || []
  agencyAll.value = (await agencyApi.list()) || []
  reload()
})

async function onProj() {
  q.batchId = null; batches.value = []
  if (q.projectId) batches.value = (await queryApi.batches({ projectId: q.projectId })) || []
}

async function reload() {
  loading.value = true
  try {
    rows.value = (await reportApi.project({
      projectId: q.projectId || undefined, batchId: q.batchId || undefined,
      competentDept: q.competentDept || undefined
    })) || []
  } finally { loading.value = false }
}

function money(v) { return v == null ? '0.00' : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }
function summary({ columns }) {
  const sum = (k) => rows.value.reduce((a, r) => a + (Number(r[k]) || 0), 0)
  return columns.map((c, i) => {
    if (i === 0) return '合计'
    if (c.property === 'applyAmount') return money(sum('applyAmount'))
    if (c.property === 'applyCount') return sum('applyCount')
    if (c.property === 'grantAmount') return money(sum('grantAmount'))
    if (c.property === 'grantCount') return sum('grantCount')
    return ''
  })
}

function doExport() {
  if (!rows.value.length) { ElMessage.warning('无数据可导出'); return }
  const head = ['部门名称', '项目名称', '批次名称', '月份/年', '乡镇名称', '补贴申请金额', '申请享受人数', '补贴发放金额', '发放享受人数']
  const lines = rows.value.map(r => [r.competentDept, r.projectName, r.batchName, r.month, r.townName, r.applyAmount, r.applyCount, r.grantAmount, r.grantCount]
    .map(v => `"${v == null ? '' : String(v).replace(/"/g, '""')}"`).join(','))
  const csv = '﻿' + [head.join(','), ...lines].join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob); const a = document.createElement('a')
  a.href = url; a.download = '乡镇项目发放情况查询.csv'; document.body.appendChild(a); a.click()
  document.body.removeChild(a); URL.revokeObjectURL(url)
}
</script>

<style scoped>
.bar :deep(.el-card__body) { padding: 12px 16px; display: flex; gap: 8px; }
.filter { margin: 12px 0; }
.filter :deep(.el-card__body) { padding: 18px 16px; }
.filter-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px 24px; }
.filter .fi { display: flex; align-items: center; }
.filter .fi label { width: 96px; text-align: right; padding-right: 10px; color: #57606a; font-size: 14px; flex-shrink: 0; }
.filter .fi :deep(.el-select), .filter .fi :deep(.el-input) { flex: 1; min-width: 0; }
.filter .fi-actions { gap: 10px; }
</style>
