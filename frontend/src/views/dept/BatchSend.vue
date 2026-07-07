<template>
  <div>
    <h2 class="page-title">批次发送</h2>

    <!-- 工具条 -->
    <el-card shadow="never" class="bar">
      <el-button type="primary" :icon="Promotion" @click="doSend">批次发送一体化</el-button>
      <el-button :icon="Back" @click="doCancelSend">取消发送</el-button>
      <el-button :icon="Tickets" @click="openRoster">补贴花名册</el-button>
      <span class="sel-hint" v-if="selected.length">已选 {{ selected.length }} 个批次</span>
    </el-card>

    <!-- 筛选 -->
    <el-card shadow="never" class="filter">
      <el-form inline @submit.prevent>
        <el-form-item label="批次编号：">
          <el-input v-model="query.batchCode" clearable style="width:260px" @keyup.enter="reload" />
        </el-form-item>
        <el-form-item label="批次名称：">
          <el-input v-model="query.batchName" clearable style="width:260px" @keyup.enter="reload" />
        </el-form-item>
        <el-form-item><el-button type="primary" round @click="reload">查询</el-button></el-form-item>
      </el-form>
    </el-card>

    <!-- 列表 -->
    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" border stripe size="default"
        show-summary :summary-method="summary" @selection-change="s => selected = s">
        <el-table-column type="selection" width="44" />
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column label="批次状态" width="100" align="center">
          <template #default="{ row }"><el-tag :type="sendType(row)">{{ sendText(row) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="batchCode" label="批次编号" width="230" show-overflow-tooltip />
        <el-table-column prop="batchName" label="批次名称" min-width="200" show-overflow-tooltip />
        <el-table-column label="项目名称" width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ projName(row.projectId) }}</template>
        </el-table-column>
        <el-table-column prop="planCount" label="申请发放人数(人)" width="140" align="right" />
        <el-table-column prop="planAmount" label="申请发放金额(元)" width="150" align="right">
          <template #default="{ row }">{{ money(row.planAmount) }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" width="140" show-overflow-tooltip />
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="page.pageNum" v-model:page-size="page.pageSize" :total="page.total"
          :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next, jumper" background
          @size-change="reload" @current-change="reload" />
      </div>
    </el-card>

    <!-- 补贴花名册 -->
    <el-dialog v-model="rosterVisible" title="花名册" width="92%" top="4vh">
      <el-table v-loading="rosterLoading" :data="rosterRows" border stripe size="small" height="60vh">
        <el-table-column prop="sortNo" label="序号" width="64" align="center" />
        <el-table-column prop="payStatus" label="支付状态" width="110" align="center" />
        <el-table-column prop="holderName" label="户主姓名" width="90" />
        <el-table-column prop="holderIdCard" label="户主身份证号" width="170" />
        <el-table-column prop="payeeName" label="收款人姓名" width="90" />
        <el-table-column prop="bankAccount" label="银行账号" width="180" />
        <el-table-column prop="bankName" label="开户银行" width="200" show-overflow-tooltip />
        <el-table-column prop="villageName" label="村(居)委会/组" width="160" show-overflow-tooltip />
        <el-table-column prop="beneficiaryName" label="享受人" width="90" />
        <el-table-column prop="amount" label="补贴金额" width="110" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="rosterPage.pageNum" v-model:page-size="rosterPage.pageSize"
          :total="rosterPage.total" :page-sizes="[50,100,200]"
          layout="total, sizes, prev, pager, next, jumper" background
          @size-change="loadRoster" @current-change="loadRoster" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Promotion, Back, Tickets } from '@element-plus/icons-vue'
import { batchApi, projectApi, queryApi } from '../../api/system'

// 批次发送页状态：终审=未发送，SENT=已发送，PAID=已支付，PAID_OUT=已发放
const SEND_LABEL = { SUBMITTED: '未发送', SENT: '已发送', PAID: '已支付', PAID_OUT: '已发放' }
const SEND_TYPE  = { SUBMITTED: 'info', SENT: 'primary', PAID: 'warning', PAID_OUT: 'success' }
const sendText = (row) => SEND_LABEL[row.status] || row.lastResult || row.status
const sendType = (row) => SEND_TYPE[row.status] || 'info'

const projects = ref([])
const projName = (id) => projects.value.find(p => String(p.id) === String(id))?.projectName || '-'

const query = reactive({ batchCode: '', batchName: '' })
const rows = ref([]); const loading = ref(false); const selected = ref([])
const page = reactive({ pageNum: 1, pageSize: 10, total: 0 })

onMounted(async () => {
  projects.value = (await projectApi.list({ included: 1, tab: 'all' })) || []
  reload()
})

async function reload() {
  loading.value = true
  try {
    const res = await batchApi.page({
      sendScope: 1, pageNum: page.pageNum, pageSize: page.pageSize,
      batchCode: query.batchCode || undefined, batchName: query.batchName || undefined
    })
    rows.value = res?.records || []
    page.total = Number(res?.total) || 0
  } finally { loading.value = false }
}

function money(v) { return v == null ? '' : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }
function summary({ columns, data }) {
  const s = []
  columns.forEach((c, i) => {
    if (i === 1) { s[i] = '合计'; return }
    if (c.property === 'planCount') s[i] = data.reduce((a, r) => a + (Number(r.planCount) || 0), 0)
    else if (c.property === 'planAmount') s[i] = money(data.reduce((a, r) => a + (Number(r.planAmount) || 0), 0))
    else s[i] = ''
  })
  return s
}

async function doSend() {
  if (!selected.value.length) { ElMessage.warning('请先勾选批次'); return }
  const bad = selected.value.filter(r => r.status !== 'SUBMITTED')
  if (bad.length) { ElMessage.warning('仅未发送（终审）批次可发送一体化'); return }
  await ElMessageBox.confirm(`确定将选中的 ${selected.value.length} 个批次发送至上级系统？`, '批次发送', { type: 'warning' })
  for (const r of selected.value) await batchApi.send(r.id)
  ElMessage.success('已发送一体化'); reload()
}
async function doCancelSend() {
  if (!selected.value.length) { ElMessage.warning('请先勾选批次'); return }
  const bad = selected.value.filter(r => r.status !== 'SENT')
  if (bad.length) { ElMessage.warning('仅已发送批次可取消发送'); return }
  await ElMessageBox.confirm(`确定取消选中的 ${selected.value.length} 个批次发送？`, '取消发送', { type: 'warning' })
  for (const r of selected.value) await batchApi.cancelSend(r.id)
  ElMessage.success('已取消发送'); reload()
}

// ---- 补贴花名册 ----
const rosterVisible = ref(false); const rosterRows = ref([]); const rosterLoading = ref(false)
const rosterPage = reactive({ pageNum: 1, pageSize: 100, total: 0 })
function openRoster() {
  if (selected.value.length !== 1) { ElMessage.warning('请选中一个批次查看花名册'); return }
  rosterPage.pageNum = 1; rosterVisible.value = true; loadRoster()
}
async function loadRoster() {
  rosterLoading.value = true
  try {
    const res = await queryApi.roster({ pageNum: rosterPage.pageNum, pageSize: rosterPage.pageSize, batchIds: selected.value[0].id })
    rosterRows.value = res?.records || []
    rosterPage.total = Number(res?.total) || 0
  } finally { rosterLoading.value = false }
}
</script>

<style scoped>
.bar :deep(.el-card__body) { padding: 12px 16px; display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.sel-hint { color: #909399; font-size: 13px; margin-left: 8px; }
.filter { margin: 12px 0; }
.filter :deep(.el-card__body) { padding: 16px 16px 0; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
</style>
