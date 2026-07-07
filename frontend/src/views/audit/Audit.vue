<template>
  <div>
    <!-- 工具条：按 tab 区分（待审核=审核/退回；已审核=取消审核；所有=只读，无审核类按钮） -->
    <el-card shadow="never" class="bar">
      <el-button v-if="tab === 'pending'" type="primary" :icon="Select" @click="doAudit">审核</el-button>
      <el-button v-if="tab === 'audited'" :icon="RefreshLeft" @click="doCancelAudit">取消审核</el-button>
      <el-button v-if="tab === 'pending'" :icon="Back" @click="doReject">退回</el-button>
      <el-button @click="openVillageSummary">村组补贴汇总</el-button>
      <el-button @click="openRoster">补贴花名册</el-button>
      <el-button @click="doExport" :loading="exporting">导出花名册</el-button>
      <el-button @click="showSum">合计</el-button>
      <el-button @click="checkBank">校验银行账号</el-button>
      <el-button @click="openPolicy">项目关联政策</el-button>
    </el-card>

    <!-- 筛选 -->
    <el-card shadow="never" class="filter">
      <el-form inline @submit.prevent>
        <el-form-item label="项目：">
          <el-select v-model="query.projectId" clearable filterable placeholder=" " style="width:320px">
            <el-option v-for="p in projects" :key="p.id" :label="p.projectName" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="批次：">
          <el-input v-model="query.batchCode" clearable placeholder="批次编码" style="width:320px" @keyup.enter="reload" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" round @click="reload">查询</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 列表 -->
    <el-card shadow="never">
      <el-tabs v-model="tab" @tab-change="onTabChange">
        <el-tab-pane label="待审核" name="pending" />
        <el-tab-pane label="已审核" name="audited" />
        <el-tab-pane label="所有" name="all" />
      </el-tabs>

      <el-table ref="tableRef" v-loading="loading" :data="rows" border stripe size="default"
        show-summary :summary-method="summary" @selection-change="s => selected = s">
        <el-table-column type="selection" width="44" />
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column label="审核历史" width="90" align="center">
          <template #default="{ row }"><el-button type="primary" link @click="openHistory(row)">查看</el-button></template>
        </el-table-column>
        <el-table-column prop="batchCode" label="批次编码" width="240" />
        <el-table-column prop="batchName" label="批次名称" min-width="220" show-overflow-tooltip />
        <el-table-column prop="unitName" label="单位" width="180" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="130" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.status" :type="statusType(row.status)">{{ row.status }}</el-tag>
            <span v-else>{{ row.stageLabel }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="grantTime" label="发放时间" width="160" align="center">
          <template #default="{ row }">{{ fmt(row.grantTime) }}</template>
        </el-table-column>
        <el-table-column prop="amount" label="发放金额" width="140" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination v-model:current-page="page.pageNum" v-model:page-size="page.pageSize" :total="page.total"
          :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next, jumper" background
          @size-change="reload" @current-change="reload" />
      </div>
    </el-card>

    <!-- 流程进度（查看） -->
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

    <!-- 补贴花名册 -->
    <el-dialog v-model="rosterVisible" title="补贴花名册" width="92%" top="4vh">
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

    <!-- 项目关联政策 -->
    <el-dialog v-model="policyVisible" title="项目关联政策" width="560px">
      <el-descriptions :column="1" border v-if="policy">
        <el-descriptions-item label="项目编码">{{ policy.projectCode }}</el-descriptions-item>
        <el-descriptions-item label="项目名称">{{ policy.projectName }}</el-descriptions-item>
        <el-descriptions-item label="政策级次">{{ policy.policyLevel }}</el-descriptions-item>
        <el-descriptions-item label="归口处室">{{ policy.deptName }}</el-descriptions-item>
        <el-descriptions-item label="挂接目录">{{ policy.catalogCode || '未挂接' }}</el-descriptions-item>
      </el-descriptions>
      <el-empty v-else description="请先选中一条批次" />
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Select, Back, RefreshLeft } from '@element-plus/icons-vue'
import { auditApi, projectApi, queryApi } from '../../api/system'

const tab = ref('pending')
const tableRef = ref()
const query = reactive({ projectId: null, batchCode: '' })
const rows = ref([]); const loading = ref(false)
const page = reactive({ pageNum: 1, pageSize: 10, total: 0 })
const selected = ref([])
const selectedIds = computed(() => selected.value.map(r => r.id).join(','))
const projects = ref([])

function ensureSelected() {
  if (!selected.value.length) { ElMessage.warning('请先在列表中勾选批次'); return false }
  return true
}

onMounted(async () => {
  // tab:'all' 去掉项目审核状态过滤——否则默认 tab=pending 会 ne APPROVED，与 included=1(已纳入即已终审)冲突→无数据
  projects.value = (await projectApi.list({ included: 1, tab: 'all' })) || []
  reload()
})

async function reload() {
  loading.value = true
  try {
    const res = await auditApi.page({
      pageNum: page.pageNum, pageSize: page.pageSize, tab: tab.value,
      projectId: query.projectId || undefined, batchCode: query.batchCode || undefined
    })
    rows.value = res?.records || []
    page.total = res?.total || 0
  } finally { loading.value = false }
}

// 切 tab：重置分页 + 清勾选（三个 tab 数据集/可操作项不同，避免选中态错位）
function onTabChange() {
  page.pageNum = 1
  selected.value = []
  tableRef.value?.clearSelection()
  reload()
}

function summary({ columns }) {
  const sums = []
  const total = rows.value.reduce((s, r) => s + (Number(r.amount) || 0), 0)
  columns.forEach((c, i) => {
    if (i === 0) sums[i] = '合计'
    else if (c.property === 'amount') sums[i] = money(total)
    else sums[i] = ''
  })
  return sums
}

function pickOne() {
  if (selected.value.length !== 1) { ElMessage.warning('请选中一条批次'); return null }
  return selected.value[0]
}

async function doAudit() {
  if (!selected.value.length) return ElMessage.warning('请选择要审核的批次')
  const { value } = await ElMessageBox.prompt('审核意见', '审核', { inputValue: '同意', confirmButtonText: '确定审核' })
  await auditApi.audit(selected.value.map(r => r.id), value)
  ElMessage.success('审核成功'); reload()
}
async function doCancelAudit() {
  if (!selected.value.length) return ElMessage.warning('请选择要取消审核的批次')
  await ElMessageBox.confirm('确定取消选中批次的审核？将撤回到上一环节（乡镇经办撤回=退回录入界面重编）', '取消审核', { type: 'warning' })
  await auditApi.cancelAudit(selected.value.map(r => r.id))
  ElMessage.success('已取消审核'); reload()
}
async function doReject() {
  if (!selected.value.length) return ElMessage.warning('请选择要退回的批次')
  const { value } = await ElMessageBox.prompt('退回原因', '退回', { inputPlaceholder: '请填写退回原因', confirmButtonText: '确定退回' })
  await auditApi.reject(selected.value.map(r => r.id), value)
  ElMessage.success('已退回，已退回乡镇经办岗'); reload()
}

const histVisible = ref(false); const history = ref([])
async function openHistory(row) {
  history.value = await auditApi.history(row.id)
  histVisible.value = true
}

// ===== 村组补贴汇总（口径同「系统管理→查询」）=====
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

// ===== 补贴花名册（分页，口径同查询界面）=====
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

async function checkBank() {
  const r = pickOne(); if (!r) return
  const res = await auditApi.checkBank(r.id)
  if (res.invalid === 0) ElMessage.success(`校验通过，共 ${res.total} 人，账号齐全`)
  else ElMessageBox.alert((res.details || []).join('\n') || '存在异常', `校验：${res.invalid}/${res.total} 条异常`, { type: 'warning' })
}

async function showSum() {
  const res = await auditApi.sum({ tab: tab.value, projectId: query.projectId || undefined })
  ElMessage.info(`当前条件合计发放金额：${money(res.totalAmount)} 元`)
}

const policyVisible = ref(false); const policy = ref(null)
async function openPolicy() {
  const r = pickOne(); if (!r) return
  // 列表已带 projectName，这里取项目详情
  policy.value = projects.value.find(p => p.projectName === r.projectName) || null
  policyVisible.value = true
}

// ---- 格式化 ----
function money(v) { return v == null ? '' : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }
function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '' }
function statusType(s) {
  if (s.includes('退回')) return 'danger'
  if (s.includes('终审')) return 'success'
  return 'warning'
}
</script>

<style scoped>
.bar :deep(.el-card__body) { padding: 12px 16px; display: flex; flex-wrap: wrap; gap: 8px; }
.filter { margin: 12px 0; }
.filter :deep(.el-card__body) { padding: 16px 16px 0; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
</style>
