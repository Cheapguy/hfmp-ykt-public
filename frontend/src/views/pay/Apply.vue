<template>
  <div class="apply-wrap">
    <h2 class="page-title">一卡通发放申请</h2>
    <div class="layout">
      <!-- 左：项目 -->
      <el-card shadow="never" class="left">
        <el-input v-model="projKw" placeholder="请输入" clearable size="default" class="proj-search" />
        <div class="proj-root">全部</div>
        <ul class="proj-list">
          <li v-for="p in filteredProjects" :key="p.id"
              :class="{ active: String(p.id) === String(curProjectId) }" @click="selectProject(p)">
            [{{ p.projectCode }}]{{ p.projectName }}
          </li>
          <li v-if="!filteredProjects.length" class="empty">无授权项目</li>
        </ul>
      </el-card>

      <!-- 右：待支付 / 已支付 -->
      <el-card shadow="never" class="right">
        <el-tabs v-model="tab" @tab-change="reload">
          <el-tab-pane label="待支付" name="pending" />
          <el-tab-pane label="已支付" name="paid" />
        </el-tabs>
        <div class="bar">
          <span class="tip" v-if="curProjectName">当前项目：{{ curProjectName }}</span>
        </div>
        <el-table v-loading="loading" :data="rows" border stripe size="default">
          <el-table-column type="index" label="#" width="50" align="center" />
          <el-table-column prop="batchCode" label="批次编号" width="180" show-overflow-tooltip />
          <el-table-column prop="batchName" label="批次名称" min-width="180" show-overflow-tooltip />
          <el-table-column prop="planCount" label="发放人数" width="100" align="right" />
          <el-table-column prop="planAmount" label="发放金额" width="140" align="right">
            <template #default="{ row }">{{ money(row.planAmount) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="STATUS_TYPE[row.status] || 'info'">{{ STATUS_LABEL[row.status] || row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" align="center" fixed="right">
            <template #default="{ row }">
              <el-button v-if="tab === 'pending'" type="primary" link @click="openGen(row)">发起支付</el-button>
              <template v-else>
                <el-button type="primary" link @click="openDetail(row)">查看详情</el-button>
                <el-button v-if="row.status === 'PAID'" type="danger" link @click="doRevoke(row)">撤销支付</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <!-- 发起支付 -->
    <el-dialog v-model="genVisible" title="发起支付申请" width="92%" top="5vh">
      <el-form :model="genForm" label-width="120px" class="gen-form">
        <div class="form-grid">
          <el-form-item label="付款账户类型"><el-input v-model="genForm.acctType" disabled /></el-form-item>
          <el-form-item label="业务类型"><el-input v-model="genForm.bizType" disabled /></el-form-item>
          <el-form-item label="结算方式"><el-input v-model="genForm.settleType" disabled /></el-form-item>
          <el-form-item label="单位内部机构"><el-input v-model="genForm.innerOrg" disabled /></el-form-item>
          <el-form-item label="资金往来对象">
            <el-select v-model="genForm.payee" style="width:100%">
              <el-option v-for="o in payees" :key="o" :label="o" :value="o" />
            </el-select>
          </el-form-item>
          <el-form-item label="申请金额"><el-input :model-value="money(preview.amount)" disabled /></el-form-item>
        </div>
        <el-form-item label="用途"><el-input v-model="genForm.usage" type="textarea" :rows="2" /></el-form-item>
      </el-form>

      <div class="sub-title">指标信息（本次预扣减）</div>
      <el-table v-loading="previewLoading" :data="preview.indicators" border stripe size="small" height="38vh" show-summary
        :summary-method="deductSummary">
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="indicatorNo" label="指标文号" width="180" show-overflow-tooltip />
        <el-table-column label="政府经济分类" width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.govEconCode }} {{ row.govEconName }}</template>
        </el-table-column>
        <el-table-column label="部门经济分类" width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.deptEconCode }} {{ row.deptEconName }}</template>
        </el-table-column>
        <el-table-column prop="budgetProject" label="预算项目" min-width="160" show-overflow-tooltip />
        <el-table-column prop="availableAmount" label="可支付余额" width="140" align="right">
          <template #default="{ row }">{{ money(row.availableAmount) }}</template>
        </el-table-column>
        <el-table-column prop="deductAmount" label="本次预扣减金额" width="150" align="right">
          <template #default="{ row }">{{ money(row.deductAmount) }}</template>
        </el-table-column>
      </el-table>

      <template #footer>
        <span class="dlg-tip" v-if="preview.shortage">额度不足，无法发起</span>
        <el-button @click="genVisible = false">返回</el-button>
        <el-button type="primary" :disabled="previewLoading || preview.shortage" @click="doGen">保存</el-button>
      </template>
    </el-dialog>

    <!-- 查看详情 -->
    <el-dialog v-model="detailVisible" title="支付申请详情" width="86%" top="6vh">
      <el-descriptions v-if="detail.apply" :column="3" border size="small" class="d-desc">
        <el-descriptions-item label="申请编号">{{ detail.apply.applyNo }}</el-descriptions-item>
        <el-descriptions-item label="申请金额">{{ money(detail.apply.amount) }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ APPLY_LABEL[detail.apply.status] || detail.apply.status }}</el-descriptions-item>
        <el-descriptions-item label="资金往来对象">{{ detail.apply.payee }}</el-descriptions-item>
        <el-descriptions-item label="用途" :span="2">{{ detail.apply.usage }}</el-descriptions-item>
      </el-descriptions>

      <template v-if="detail.okCount || detail.failCount">
        <div class="sub-title">发放结果</div>
        <el-descriptions :column="4" border size="small" class="d-desc">
          <el-descriptions-item label="成功人数">{{ detail.okCount }}</el-descriptions-item>
          <el-descriptions-item label="成功金额">{{ money(detail.okAmount) }}</el-descriptions-item>
          <el-descriptions-item label="失败人数">
            <span :style="{ color: detail.failCount ? '#f56c6c' : '#67c23a' }">{{ detail.failCount }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="退回金额">
            <span :style="{ color: detail.failCount ? '#f56c6c' : '' }">{{ money(detail.failAmount) }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <template v-if="detail.fails && detail.fails.length">
          <el-table :data="detail.fails" border stripe size="small" max-height="200" style="margin-bottom:12px">
            <el-table-column type="index" label="#" width="50" align="center" />
            <el-table-column prop="name" label="姓名" width="100" />
            <el-table-column prop="bankAccount" label="银行账号" width="180" show-overflow-tooltip />
            <el-table-column prop="amount" label="金额" width="110" align="right">
              <template #default="{ row }">{{ money(row.amount) }}</template>
            </el-table-column>
            <el-table-column prop="reason" label="失败原因(退回)" min-width="180" />
          </el-table>
        </template>
      </template>

      <div class="sub-title">指标扣减明细</div>
      <el-table :data="detail.indicators" border stripe size="small" height="34vh" show-summary :summary-method="deductSummary">
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="indicatorNo" label="指标文号" width="180" show-overflow-tooltip />
        <el-table-column label="政府经济分类" width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.govEconCode }} {{ row.govEconName }}</template>
        </el-table-column>
        <el-table-column prop="budgetProject" label="预算项目" min-width="160" show-overflow-tooltip />
        <el-table-column prop="availableAmount" label="可支付余额" width="140" align="right">
          <template #default="{ row }">{{ money(row.availableAmount) }}</template>
        </el-table-column>
        <el-table-column prop="deductAmount" label="扣减金额" width="140" align="right">
          <template #default="{ row }">{{ money(row.deductAmount) }}</template>
        </el-table-column>
      </el-table>
      <template #footer><el-button @click="detailVisible = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { paymentApi, projectApi } from '../../api/system'

const APPLY_LABEL = { PENDING_SUBMIT: '待送审', SUBMITTED: '已送审', PAID: '已支付' }
const STATUS_LABEL = { SENT: '待支付', PAID: '已支付', PAID_OUT: '已发放' }
const STATUS_TYPE = { SENT: 'warning', PAID: 'success', PAID_OUT: 'success' }
const payees = ['19 与部门外其他', '01 本部门', '09 其他']

const projects = ref([]); const projKw = ref('')
const curProjectId = ref(null); const curProjectName = ref('')
const tab = ref('pending')
const rows = ref([]); const loading = ref(false)

const filteredProjects = computed(() => {
  const kw = projKw.value.trim()
  return kw ? projects.value.filter(p => (`[${p.projectCode}]${p.projectName}`).includes(kw)) : projects.value
})

onMounted(async () => {
  projects.value = (await projectApi.list({ included: 1, tab: 'all' })) || []
})

function money(v) { return v == null ? '' : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }

async function selectProject(p) {
  curProjectId.value = p.id; curProjectName.value = `[${p.projectCode}]${p.projectName}`
  await reload()
}
async function reload() {
  if (!curProjectId.value) { rows.value = []; return }
  loading.value = true
  try {
    const api = tab.value === 'pending' ? paymentApi.pending : paymentApi.paid
    rows.value = (await api(curProjectId.value)) || []
  } finally { loading.value = false }
}

// ---- 发起支付 ----
const genVisible = ref(false); const previewLoading = ref(false)
const genForm = reactive({
  batchId: null, acctType: '财政零余额账户', bizType: '其他批量业务',
  settleType: '电子转账支付', innerOrg: '其他', payee: payees[0], usage: ''
})
const preview = reactive({ amount: 0, indicators: [], shortage: false })

async function openGen(row) {
  Object.assign(genForm, { batchId: row.id, payee: payees[0], usage: row.batchName || '' })
  Object.assign(preview, { amount: 0, indicators: [], shortage: false })
  genVisible.value = true
  previewLoading.value = true
  try {
    const res = await paymentApi.preview(row.id)
    Object.assign(preview, { amount: res?.amount || 0, indicators: res?.indicators || [], shortage: false })
  } catch (e) {
    preview.shortage = true   // 后端额度不足会抛异常
  } finally { previewLoading.value = false }
}
async function doGen() {
  await paymentApi.gen({ batchId: String(genForm.batchId), payee: genForm.payee, usage: genForm.usage })
  ElMessage.success('支付申请已生成，批次转入已支付')
  genVisible.value = false; reload()
}

// ---- 撤销支付 ----
async function doRevoke(row) {
  await ElMessageBox.confirm(`确定撤销批次「${row.batchName}」的支付？将回滚指标额度并退回待支付。`, '撤销支付', { type: 'warning' })
  await paymentApi.revoke(String(row.id))
  ElMessage.success('已撤销支付'); reload()
}

// ---- 查看详情 ----
const detailVisible = ref(false)
const detail = reactive({ apply: null, indicators: [], okCount: 0, okAmount: 0, failCount: 0, failAmount: 0, fails: [] })
async function openDetail(row) {
  const res = await paymentApi.detail(String(row.id)) || {}
  detail.apply = res.apply || null
  detail.indicators = res.indicators || []
  detail.okCount = res.okCount || 0; detail.okAmount = res.okAmount || 0
  detail.failCount = res.failCount || 0; detail.failAmount = res.failAmount || 0
  detail.fails = res.fails || []
  detailVisible.value = true
}

// 合计行：只对扣减金额列求和
function deductSummary({ columns, data }) {
  return columns.map((col, i) => {
    if (i === 0) return '合计'
    if (col.property === 'deductAmount') {
      const sum = data.reduce((s, r) => s + (Number(r.deductAmount) || 0), 0)
      return money(sum)
    }
    return ''
  })
}
</script>

<style scoped>
.layout { display: flex; gap: 12px; }
.left { width: 300px; flex-shrink: 0; }
.left :deep(.el-card__body) { padding: 12px; }
.proj-search { margin-bottom: 10px; }
.proj-root { color: #606266; font-weight: 600; padding: 4px 6px; }
.proj-list { list-style: none; margin: 4px 0 0; padding: 0; }
.proj-list li { padding: 8px 10px; cursor: pointer; border-radius: 4px; font-size: 13px; color: #303133; }
.proj-list li:hover { background: #f5f7fa; }
.proj-list li.active { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.proj-list li.empty { color: #c0c4cc; cursor: default; }
.right { flex: 1; min-width: 0; }
.bar { display: flex; align-items: center; margin-bottom: 12px; }
.bar .tip { margin-left: auto; color: #909399; font-size: 13px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.sub-title { font-weight: 600; color: #303133; margin: 6px 0 10px; padding-left: 8px; border-left: 3px solid var(--el-color-primary); }
.d-desc { margin-bottom: 12px; }
.dlg-tip { color: #f56c6c; font-size: 13px; margin-right: auto; }
:deep(.el-dialog__footer) { display: flex; align-items: center; gap: 8px; }
</style>
