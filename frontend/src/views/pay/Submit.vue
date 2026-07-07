<template>
  <div class="submit-wrap">
    <h2 class="page-title">支付申请录入（签章送审）</h2>

    <el-card shadow="never">
      <el-tabs v-model="tab" @tab-change="reload">
        <el-tab-pane label="待送审" name="PENDING_SUBMIT" />
        <el-tab-pane label="已送审" name="SUBMITTED" />
      </el-tabs>

      <div class="bar" v-if="tab === 'PENDING_SUBMIT'">
        <el-button type="success" :icon="Stamp" :disabled="!selected.length" @click="doSubmit">批量签章送审</el-button>
        <el-button type="danger" :icon="Delete" :disabled="!selected.length" @click="doRemove">删除</el-button>
        <span class="tip">已选 {{ selected.length }} 条</span>
      </div>
      <div class="bar" v-else>
        <el-button type="primary" :icon="CreditCard" :disabled="!selected.length" @click="doBankPay">批量银行代发</el-button>
        <span class="tip">已选 {{ selected.length }} 条 · 代发后失败明细可由主管在「更正发放」重构二次发放</span>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe size="default"
        @selection-change="s => selected = s">
        <el-table-column type="selection" width="44" />
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="applyNo" label="申请编号" width="180" show-overflow-tooltip />
        <el-table-column prop="projectName" label="补贴项目" min-width="170" show-overflow-tooltip />
        <el-table-column prop="batchName" label="批次名称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="amount" label="申请金额" width="140" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="payee" label="资金往来对象" width="160" show-overflow-tooltip />
        <el-table-column prop="usage" label="用途" min-width="140" show-overflow-tooltip />
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'SUBMITTED' ? 'success' : 'warning'">{{ LABEL[row.status] || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" align="center" fixed="right">
          <template #default="{ row }">
            <template v-if="tab === 'PENDING_SUBMIT'">
              <el-button type="success" link @click="doSubmit(row)">签章送审</el-button>
              <el-button type="danger" link @click="doRemove(row)">删除</el-button>
            </template>
            <el-button v-else type="primary" link @click="doBankPay(row)">银行代发</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 银行代发：人工标记成败 -->
    <el-dialog v-model="markVisible" title="银行代发 - 标记发放结果" width="900px" top="6vh">
      <div class="mark-tip">
        默认全部「成功」，请将银行退单/核对失败的人员标记为「失败」并选择原因。
        当前批次：{{ markApply?.batchName }} · 共 {{ markRows.length }} 人 · 已标记失败 {{ markFailCount() }} 人
      </div>
      <el-table v-loading="markLoading" :data="markRows" border stripe size="small" height="50vh">
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="name" label="姓名" width="100" />
        <el-table-column prop="idCard" label="身份证号" width="180" show-overflow-tooltip />
        <el-table-column prop="bankAccount" label="银行账号" width="170" show-overflow-tooltip />
        <el-table-column prop="amount" label="金额" width="110" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="发放结果" width="150" align="center">
          <template #default="{ row }">
            <el-radio-group v-model="row.result" size="small">
              <el-radio-button value="OK">成功</el-radio-button>
              <el-radio-button value="FAIL">失败</el-radio-button>
            </el-radio-group>
          </template>
        </el-table-column>
        <el-table-column label="失败原因" min-width="200">
          <template #default="{ row }">
            <el-select v-if="row.result === 'FAIL'" v-model="row.reason" size="small" style="width:100%">
              <el-option v-for="r in FAIL_REASONS" :key="r" :label="r" :value="r" />
            </el-select>
            <span v-else style="color:#c0c4cc">—</span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="markVisible = false">取消</el-button>
        <el-button type="primary" @click="doConfirmMark">提交代发结果</el-button>
      </template>
    </el-dialog>

    <!-- 银行代发结果 -->
    <el-dialog v-model="resultVisible" title="银行代发结果" width="720px">
      <el-descriptions :column="2" border size="small" class="r-sum">
        <el-descriptions-item label="成功笔数（实发）">{{ result.okCount }}</el-descriptions-item>
        <el-descriptions-item label="成功金额">{{ money(result.okAmount) }}</el-descriptions-item>
        <el-descriptions-item label="失败笔数（退回）">
          <span :style="{ color: result.failCount ? '#f56c6c' : '#67c23a' }">{{ result.failCount }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="退回金额">{{ money(result.failAmount) }}</el-descriptions-item>
        <el-descriptions-item label="停发笔数（退款）">
          <span :style="{ color: result.stopCount ? '#e6a23c' : '#67c23a' }">{{ result.stopCount }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="退款金额">{{ money(result.stopAmount) }}</el-descriptions-item>
      </el-descriptions>
      <template v-if="result.fails && result.fails.length">
        <div class="sub-title">失败明细（需主管在「更正发放」重构二次发放）</div>
        <el-table :data="result.fails" border stripe size="small" max-height="320">
          <el-table-column type="index" label="#" width="50" align="center" />
          <el-table-column prop="name" label="姓名" width="110" />
          <el-table-column prop="bankAccount" label="银行账号" width="180" show-overflow-tooltip />
          <el-table-column prop="amount" label="金额" width="110" align="right">
            <template #default="{ row }">{{ money(row.amount) }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="失败原因" min-width="180" />
        </el-table>
      </template>
      <el-result v-else icon="success" title="全部代发成功" sub-title="该批次已全部发放到账" />
      <template #footer><el-button type="primary" @click="resultVisible = false">知道了</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Stamp, Delete, CreditCard } from '@element-plus/icons-vue'
import { paymentApi } from '../../api/system'

const LABEL = { PENDING_SUBMIT: '待送审', SUBMITTED: '已送审', PAID: '已支付' }

const tab = ref('PENDING_SUBMIT')
const rows = ref([]); const loading = ref(false); const selected = ref([])

onMounted(reload)

function money(v) { return v == null ? '' : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }

async function reload() {
  loading.value = true
  try { rows.value = (await paymentApi.submitList(tab.value)) || [] }
  finally { loading.value = false }
}

// 单行(row 为对象) 或 批量(无参，用 selected)
function targets(row) {
  return row && row.id ? [row] : selected.value
}

async function doSubmit(row) {
  const list = targets(row)
  if (!list.length) return ElMessage.warning('请选择申请')
  await ElMessageBox.confirm(`确定对选中的 ${list.length} 条申请签章送审？`, '签章送审', { type: 'warning' })
  for (const a of list) await paymentApi.submit(String(a.id))
  ElMessage.success('已送审'); selected.value = []; reload()
}

async function doRemove(row) {
  const list = targets(row)
  if (!list.length) return ElMessage.warning('请选择申请')
  await ElMessageBox.confirm(
    `确定删除选中的 ${list.length} 条申请？将回滚指标额度，批次退回终审，需一卡通重新发送一体化。`,
    '删除支付申请', { type: 'warning' })
  for (const a of list) await paymentApi.remove(String(a.id))
  ElMessage.success('已删除'); selected.value = []; reload()
}

// ---- 银行代发（集中支付收尾，逐笔人工标记成败）----
const FAIL_REASONS = ['卡号已注销', '卡已达限额（单笔/日累计超限）', '卡号与户名不一致', '账户冻结', '其他']
const markVisible = ref(false); const markLoading = ref(false)
const markApply = ref(null)        // 当前代发的申请
const markRows = ref([])           // 花名册明细 + result/reason
const resultVisible = ref(false)
const result = reactive({ okCount: 0, okAmount: 0, failCount: 0, failAmount: 0, stopCount: 0, stopAmount: 0, fails: [] })

async function doBankPay(row) {
  // 银行代发需逐笔人工标记，只能针对单个申请
  let apply = row
  if (!apply || !apply.id) {
    if (selected.value.length !== 1) return ElMessage.warning('银行代发需逐笔标记成败，请只勾选一个申请')
    apply = selected.value[0]
  }
  markApply.value = apply
  markVisible.value = true
  markLoading.value = true
  try {
    const list = (await paymentApi.grantList(String(apply.id))) || []
    markRows.value = list.map(r => ({ ...r, result: 'OK', reason: FAIL_REASONS[0] }))
  } finally { markLoading.value = false }
}

const markFailCount = () => markRows.value.filter(r => r.result === 'FAIL').length

async function doConfirmMark() {
  const fails = markRows.value.filter(r => r.result === 'FAIL')
    .map(r => ({ detailId: String(r.id), reason: r.reason }))
  await ElMessageBox.confirm(
    `本次代发 ${markRows.value.length} 人：成功 ${markRows.value.length - fails.length} 人，失败 ${fails.length} 人。确认提交？`,
    '确认银行代发结果', { type: 'warning' })
  const r = (await paymentApi.bankPay(String(markApply.value.id), fails)) || {}
  Object.assign(result, {
    okCount: r.okCount || 0, okAmount: Number(r.okAmount || 0),
    failCount: r.failCount || 0, failAmount: Number(r.failAmount || 0),
    stopCount: r.stopCount || 0, stopAmount: Number(r.stopAmount || 0), fails: r.fails || []
  })
  markVisible.value = false
  resultVisible.value = true
  selected.value = []; reload()
}
</script>

<style scoped>
.bar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.bar .tip { margin-left: auto; color: #909399; font-size: 13px; }
.r-sum { margin-bottom: 12px; }
.sub-title { font-weight: 600; color: #303133; margin: 6px 0 10px; padding-left: 8px; border-left: 3px solid var(--el-color-primary); }
.mark-tip { color: #909399; font-size: 13px; margin-bottom: 10px; }
</style>
