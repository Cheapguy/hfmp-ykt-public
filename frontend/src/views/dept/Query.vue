<template>
  <div>
    <el-card shadow="never" class="bar">
      <el-button type="primary" @click="reload">查询</el-button>
      <el-button @click="openVillageSummary">村组补贴汇总</el-button>
      <el-button @click="openRoster">花名册</el-button>
      <el-button @click="doExport" :loading="exporting">导出花名册</el-button>
      <span class="sel-hint" v-if="selected.length">已选 {{ selected.length }} 个批次</span>
    </el-card>

    <el-card shadow="never" class="filter">
      <el-form inline @submit.prevent>
        <el-form-item>
          <template #label><span style="color:#f56c6c">*</span>项目：</template>
          <el-select v-model="query.projectId" filterable clearable placeholder=" " style="width:320px" @change="onProjectChange">
            <el-option v-for="p in projects" :key="p.id" :label="`${p.projectCode || ''} ${p.projectName}`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="批次：">
          <el-select v-model="query.batchCode" clearable filterable remote :remote-method="loadBatches"
            :loading="batchLoading" placeholder="选择项目后加载批次" style="width:300px"
            :disabled="!query.projectId" @change="reload" @visible-change="onBatchOpen">
            <el-option v-for="b in batches" :key="b.batchCode"
              :label="b.batchName ? `${b.batchCode} ${b.batchName}` : b.batchCode" :value="b.batchCode" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" round @click="reload">查询</el-button></el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" border stripe size="default" row-key="id"
        show-summary :summary-method="summary" @selection-change="onSelect">
        <el-table-column type="selection" width="46" reserve-selection />
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column label="进度" width="80" align="center">
          <template #default="{ row }"><el-button type="primary" link @click="openHistory(row)">查看</el-button></template>
        </el-table-column>
        <el-table-column prop="projectName" label="项目" width="160" show-overflow-tooltip />
        <el-table-column prop="batchCode" label="批次号" width="220" show-overflow-tooltip />
        <el-table-column prop="batchName" label="批次名称" min-width="220" show-overflow-tooltip />
        <el-table-column prop="unitName" label="单位" width="180" show-overflow-tooltip />
        <el-table-column prop="auditStatus" label="审核状态" width="120" align="center" />
        <el-table-column prop="planCount" label="申请发放人数" width="120" align="right" />
        <el-table-column prop="planAmount" label="申请发放金额" width="140" align="right">
          <template #default="{ row }">{{ money(row.planAmount) }}</template>
        </el-table-column>
        <el-table-column prop="actualCount" label="实际发放人数" width="120" align="right" />
        <el-table-column prop="actualAmount" label="实际发放金额" width="140" align="right">
          <template #default="{ row }">{{ money(row.actualAmount) }}</template>
        </el-table-column>
        <el-table-column prop="refundAmount" label="退款金额" width="120" align="right">
          <template #default="{ row }">{{ money(row.refundAmount) }}</template>
        </el-table-column>
        <el-table-column prop="returnAmount" label="退回金额" width="120" align="right">
          <template #default="{ row }">{{ money(row.returnAmount) }}</template>
        </el-table-column>
        <el-table-column prop="stopAmount" label="停发金额" width="120" align="right">
          <template #default="{ row }">{{ money(row.stopAmount) }}</template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="page.pageNum" v-model:page-size="page.pageSize" :total="page.total"
          :page-sizes="[10,20,50,100,200]" layout="total, sizes, prev, pager, next, jumper" background
          @size-change="reload" @current-change="reload" />
      </div>
    </el-card>

    <!-- 流程进度 -->
    <el-dialog v-model="histVisible" title="流程进度" width="980px">
      <el-table :data="history" border stripe size="small">
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="doneStation" label="已审岗" width="130" />
        <el-table-column prop="operator" label="操作人" width="110" />
        <el-table-column prop="opType" label="操作类型" width="100" align="center" />
        <el-table-column prop="opResult" label="操作结果" width="130" />
        <el-table-column prop="opinion" label="审核意见" min-width="140" />
        <el-table-column prop="opTime" label="操作时间" width="170" align="center">
          <template #default="{ row }">{{ fmt(row.opTime) }}</template>
        </el-table-column>
        <el-table-column prop="pendingStation" label="待审岗" width="130" />
      </el-table>
    </el-dialog>

    <!-- 村组补贴汇总 -->
    <el-dialog v-model="villageVisible" title="按村组汇总数据" width="760px">
      <el-table v-loading="villageLoading" :data="villageRows" border stripe size="small"
        show-summary :summary-method="villageSummaryRow">
        <el-table-column type="index" label="序号" width="64" align="center" />
        <el-table-column prop="householdCount" label="发放户数" width="120" align="right" />
        <el-table-column prop="personCount" label="发放人数" width="120" align="right" />
        <el-table-column prop="villageName" label="村组" min-width="200" show-overflow-tooltip />
        <el-table-column prop="amount" label="发放金额" width="160" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 花名册 -->
    <el-dialog v-model="rosterVisible" title="花名册" width="92%" top="4vh">
      <div style="margin-bottom:8px">
        <el-button type="success" size="small" @click="doExport" :loading="exporting">导出 Excel</el-button>
      </div>
      <el-table v-loading="rosterLoading" :data="rosterRows" border stripe size="small" height="60vh">
        <el-table-column prop="sortNo" label="序号" width="64" align="center" />
        <el-table-column prop="payStatus" label="支付状态" width="90" align="center" />
        <el-table-column prop="holderName" label="户主姓名" width="90" />
        <el-table-column prop="holderIdCard" label="户主身份证号" width="170" />
        <el-table-column prop="payeeName" label="收款人姓名" width="90" />
        <el-table-column prop="payeeIdCard" label="收款人身份证号" width="170" />
        <el-table-column prop="bankAccount" label="银行账号" width="180" />
        <el-table-column prop="bankName" label="开户银行" width="220" show-overflow-tooltip />
        <el-table-column prop="villageName" label="村(居)委会/组" width="160" show-overflow-tooltip />
        <el-table-column prop="beneficiaryName" label="享受人" width="90" />
        <el-table-column prop="beneficiaryIdCard" label="享受人身份证号" width="170" />
        <el-table-column prop="phone" label="联系电话" width="120" />
        <el-table-column prop="age" label="年龄" width="64" align="center" />
        <el-table-column prop="amount" label="补贴金额" width="110" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="fillDate" label="填报日期" width="120" align="center" />
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="rosterPage.pageNum" v-model:page-size="rosterPage.pageSize"
          :total="rosterPage.total" :page-sizes="[50,100,200,500]"
          layout="total, sizes, prev, pager, next, jumper" background
          @size-change="loadRoster" @current-change="loadRoster" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { queryApi, projectApi } from '../../api/system'

const projects = ref([])
const query = reactive({ projectId: null, batchCode: '' })
const rows = ref([]); const loading = ref(false)
const page = reactive({ pageNum: 1, pageSize: 50, total: 0 })
const batches = ref([]); const batchLoading = ref(false)
const selected = ref([])
const selectedIds = computed(() => selected.value.map(r => r.id).join(','))

onMounted(async () => {
  projects.value = (await projectApi.list({ included: 1, tab: 'all' })) || []
  reload()
})

async function reload() {
  loading.value = true
  try {
    const res = await queryApi.page({
      pageNum: page.pageNum, pageSize: page.pageSize,
      projectId: query.projectId || undefined, batchCode: query.batchCode || undefined
    })
    rows.value = res?.records || []
    page.total = Number(res?.total) || 0
  } finally { loading.value = false }
}

function onSelect(sel) { selected.value = sel }
function ensureSelected() {
  if (!selected.value.length) { ElMessage.warning('请先在列表中勾选批次'); return false }
  return true
}

// 选项目 → 清空已选批次 + 预加载该项目批次列表 + 重新查询
async function onProjectChange() {
  query.batchCode = ''
  batches.value = []
  if (query.projectId) loadBatches('')
  reload()
}
async function loadBatches(keyword) {
  if (!query.projectId) return
  batchLoading.value = true
  try {
    batches.value = (await queryApi.batches({ projectId: query.projectId, keyword: keyword || undefined })) || []
  } finally { batchLoading.value = false }
}
function onBatchOpen(open) {
  if (open && query.projectId && !batches.value.length) loadBatches('')
}

function summary({ columns }) {
  const s = []
  const sum = (k) => rows.value.reduce((a, r) => a + (Number(r[k]) || 0), 0)
  columns.forEach((c, i) => {
    if (i === 1) s[i] = '合计'
    else if (c.property === 'planCount') s[i] = sum('planCount')
    else if (c.property === 'planAmount') s[i] = money(sum('planAmount'))
    else if (c.property === 'actualCount') s[i] = sum('actualCount')
    else if (c.property === 'actualAmount') s[i] = money(sum('actualAmount'))
    else if (c.property === 'refundAmount') s[i] = money(sum('refundAmount'))
    else if (c.property === 'returnAmount') s[i] = money(sum('returnAmount'))
    else if (c.property === 'stopAmount') s[i] = money(sum('stopAmount'))
    else s[i] = ''
  })
  return s
}

// ===== 村组补贴汇总 =====
const villageVisible = ref(false); const villageRows = ref([]); const villageLoading = ref(false)
async function openVillageSummary() {
  if (!ensureSelected()) return
  villageVisible.value = true; villageLoading.value = true
  try { villageRows.value = (await queryApi.villageSummary(selectedIds.value)) || [] }
  finally { villageLoading.value = false }
}
function villageSummaryRow({ columns }) {
  const s = []
  const hh = villageRows.value.reduce((a, r) => a + (Number(r.householdCount) || 0), 0)
  const pc = villageRows.value.reduce((a, r) => a + (Number(r.personCount) || 0), 0)
  const am = villageRows.value.reduce((a, r) => a + (Number(r.amount) || 0), 0)
  columns.forEach((c, i) => {
    if (i === 0) s[i] = '合计'
    else if (c.property === 'householdCount') s[i] = hh
    else if (c.property === 'personCount') s[i] = pc
    else if (c.property === 'amount') s[i] = money(am)
    else s[i] = ''
  })
  return s
}

// ===== 花名册 =====
const rosterVisible = ref(false); const rosterRows = ref([]); const rosterLoading = ref(false)
const rosterPage = reactive({ pageNum: 1, pageSize: 100, total: 0 })
async function openRoster() {
  if (!ensureSelected()) return
  rosterPage.pageNum = 1
  rosterVisible.value = true
  loadRoster()
}
async function loadRoster() {
  rosterLoading.value = true
  try {
    const res = await queryApi.roster({ pageNum: rosterPage.pageNum, pageSize: rosterPage.pageSize, batchIds: selectedIds.value })
    rosterRows.value = res?.records || []
    rosterPage.total = Number(res?.total) || 0
  } finally { rosterLoading.value = false }
}

// ===== 导出花名册 =====
const exporting = ref(false)
async function doExport() {
  if (!ensureSelected()) return
  exporting.value = true
  try {
    const blob = await queryApi.exportRoster(selectedIds.value)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url; a.download = '花名册.xlsx'; document.body.appendChild(a); a.click()
    document.body.removeChild(a); URL.revokeObjectURL(url)
  } finally { exporting.value = false }
}

const histVisible = ref(false); const history = ref([])
async function openHistory(row) { history.value = await queryApi.history(row.id); histVisible.value = true }

function money(v) { return v == null ? '' : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }
function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '' }
</script>

<style scoped>
.bar :deep(.el-card__body) { padding: 12px 16px; display: flex; gap: 8px; align-items: center; }
.sel-hint { color: #909399; font-size: 13px; margin-left: 8px; }
.filter { margin: 12px 0; }
.filter :deep(.el-card__body) { padding: 16px 16px 0; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
</style>
