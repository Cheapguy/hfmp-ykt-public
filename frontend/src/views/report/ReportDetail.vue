<template>
  <div class="report">
    <h2 class="page-title">乡镇发放情况明细查询</h2>

    <el-card shadow="never" class="bar">
      <el-button type="primary" :icon="Search" @click="reload">查询</el-button>
      <el-button :icon="Download" @click="doExport">导出</el-button>
    </el-card>

    <el-card shadow="never" class="filter">
      <div class="filter-grid">
        <div class="fi"><label>乡镇：</label>
          <el-select v-model="q.agency" filterable clearable placeholder="请选择机构/乡镇" fit-input-width
            popper-class="agency-pop" @change="onAgency">
            <el-option v-for="a in agencies" :key="a.guid"
              :label="(a.levelNo === 2 ? '　├ ' : '') + a.code + '-' + a.name" :value="a.guid" />
          </el-select>
        </div>
        <div class="fi"><label>村组：</label>
          <el-select v-model="q.village" filterable clearable fit-input-width :disabled="!villages.length"
            :placeholder="villages.length ? '请选择村组' : '请先选择乡镇'">
            <el-option v-for="v in villages" :key="v.code" :label="v.code + '-' + v.name" :value="v.name" />
          </el-select>
        </div>
        <div class="fi fi-actions">
          <el-button type="primary" :icon="Search" @click="reload">查询</el-button>
          <el-button plain @click="expanded = !expanded">显示/隐藏</el-button>
        </div>
        <template v-if="expanded">
          <div class="fi"><label>批次：</label>
            <el-select v-model="q.batchId" filterable clearable placeholder="选择项目后加载" :disabled="!q.projectId">
              <el-option v-for="b in batches" :key="b.id" :label="b.batchName || b.batchCode" :value="b.id" />
            </el-select>
          </div>
          <div class="fi"><label>项目：</label>
            <el-select v-model="q.projectId" filterable clearable placeholder="请选择项目" @change="onProj">
              <el-option v-for="p in projects" :key="p.id" :label="`${p.projectCode || ''} ${p.projectName}`" :value="p.id" />
            </el-select>
          </div>
        </template>
      </div>
    </el-card>

    <el-card shadow="never">
      <div class="sum-bar">合计发放金额 <b>{{ money(totalAmount) }}</b> 元 · 共 {{ total }} 条</div>
      <el-table v-loading="loading" :data="rows" border stripe size="default">
        <el-table-column type="index" label="序号" width="60" align="center" :index="indexFn" />
        <el-table-column prop="projectName" label="项目" width="140" show-overflow-tooltip />
        <el-table-column prop="batchName" label="批次" min-width="200" show-overflow-tooltip />
        <el-table-column prop="issueTime" label="批次下达时间" width="120" align="center">
          <template #default="{ row }">{{ fmtDate(row.issueTime) }}</template>
        </el-table-column>
        <el-table-column prop="townName" label="乡镇" width="160" show-overflow-tooltip />
        <el-table-column prop="villageName" label="村组" width="150" show-overflow-tooltip />
        <el-table-column prop="holderIdCard" label="户主身份证" width="180" />
        <el-table-column prop="holderName" label="户主姓名" width="100" />
        <el-table-column prop="beneficiaryIdCard" label="享受人身份证" width="180" />
        <el-table-column prop="beneficiaryName" label="享受人姓名" width="100" />
        <el-table-column prop="amount" label="发放金额" width="120" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="grantTime" label="发放时间" width="120" align="center">
          <template #default="{ row }">{{ fmtDate(row.grantTime) }}</template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="page.pageNum" v-model:page-size="page.pageSize" :total="total"
          :page-sizes="[20, 50, 100]" layout="total, sizes, prev, pager, next, jumper" background
          @size-change="reload" @current-change="reload" />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Download } from '@element-plus/icons-vue'
import { reportApi, projectApi, queryApi, agencyApi } from '../../api/system'

const projects = ref([]); const batches = ref([]); const agencies = ref([]); const villages = ref([])
const q = reactive({ projectId: null, batchId: null, agency: null, village: null })
const rows = ref([]); const total = ref(0); const totalAmount = ref(0); const loading = ref(false)
const expanded = ref(false)
const page = reactive({ pageNum: 1, pageSize: 20 })

onMounted(async () => {
  projects.value = (await projectApi.list({ included: 1, tab: 'all' })) || []
  agencies.value = (await agencyApi.list()) || []
  reload()
})

async function onProj() {
  q.batchId = null; batches.value = []
  if (q.projectId) batches.value = (await queryApi.batches({ projectId: q.projectId })) || []
}

// 选乡镇 → 动态加载村组；只有乡镇政府(isSubsidy=1 且已链 townId)才有村组
async function onAgency() {
  q.village = null; villages.value = []
  const a = agencies.value.find(x => x.guid === q.agency)
  if (a && a.isSubsidy === '1' && a.townId) {
    villages.value = (await agencyApi.villages(a.townId)) || []
  }
}

function selectedTownId() {
  const a = agencies.value.find(x => x.guid === q.agency)
  return a && a.isSubsidy === '1' && a.townId ? a.townId : undefined
}

async function reload() {
  loading.value = true
  try {
    const res = await reportApi.detail({
      pageNum: page.pageNum, pageSize: page.pageSize,
      projectId: q.projectId || undefined, batchId: q.batchId || undefined,
      townId: selectedTownId(), village: q.village || undefined
    }) || {}
    rows.value = res.records || []
    total.value = Number(res.total) || 0
    totalAmount.value = res.totalAmount || 0
  } finally { loading.value = false }
}

function indexFn(i) { return (page.pageNum - 1) * page.pageSize + i + 1 }
function money(v) { return v == null ? '0.00' : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }
function fmtDate(v) { return v ? String(v).replace('T', ' ').slice(0, 10) : '' }

function doExport() {
  if (!rows.value.length) { ElMessage.warning('无数据可导出'); return }
  const head = ['项目', '批次', '批次下达时间', '乡镇', '村组', '户主身份证', '户主姓名', '享受人身份证', '享受人姓名', '发放金额', '发放时间']
  const lines = rows.value.map(r => [r.projectName, r.batchName, fmtDate(r.issueTime), r.townName, r.villageName, r.holderIdCard, r.holderName, r.beneficiaryIdCard, r.beneficiaryName, r.amount, fmtDate(r.grantTime)]
    .map(v => `"${v == null ? '' : String(v).replace(/"/g, '""')}"`).join(','))
  const csv = '﻿' + [head.join(','), ...lines].join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob); const a = document.createElement('a')
  a.href = url; a.download = '乡镇发放情况明细查询.csv'; document.body.appendChild(a); a.click()
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
.sum-bar { padding: 8px 12px; margin-bottom: 12px; background: var(--el-color-primary-light-9); border: 1px solid var(--el-color-primary-light-7); border-radius: 7px; font-size: 13px; color: #6b675e; }
.sum-bar b { color: var(--el-color-primary-dark-2); font-size: 16px; margin: 0 4px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
</style>
