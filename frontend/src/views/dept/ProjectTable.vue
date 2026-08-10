<template>
  <div>
    <!-- 工具条。三个分支写成互斥条件而不是 if/else-if/else：
         用 v-else 兜底的话，「维护页 + 所有 tab」这一格会掉进审核按钮组里，
         录入岗就凭空多出「审核/退回/核定追踪代码」三个按钮。
         维护页各 tab：待审核=还没送出去能增删改送；已审核=已送出去只能撤回；所有=纯查看，整条工具栏收起。 -->
    <el-card v-if="showBar" shadow="never" class="bar">
      <template v-if="forAudit">
        <el-button type="primary" :icon="Select" @click="doApprove">审核</el-button>
        <el-button :icon="Back" @click="doReject">退回</el-button>
        <el-button :icon="Ticket" @click="doTraceCode">核定追踪代码</el-button>
      </template>
      <template v-else-if="tab === 'pending'">
        <el-button type="primary" :icon="Plus" @click="openCreate">新增</el-button>
        <el-button :icon="Edit" @click="openEdit">修改</el-button>
        <el-button :icon="Delete" @click="doDelete">删除</el-button>
        <el-button type="success" :icon="Promotion" @click="doSubmit">送审</el-button>
      </template>
      <template v-else-if="tab === 'audited'">
        <el-button :icon="RefreshLeft" @click="doRevoke">取消送审</el-button>
      </template>
    </el-card>

    <!-- 筛选 -->
    <el-card shadow="never" class="filter">
      <el-form inline @submit.prevent>
        <el-form-item label="项目编码：">
          <el-input v-model="query.projectCode" clearable style="width:300px" @keyup.enter="reload" />
        </el-form-item>
        <el-form-item label="项目名称：">
          <el-input v-model="query.projectName" clearable style="width:300px" @keyup.enter="reload" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" round @click="reload">查询</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 列表 -->
    <el-card shadow="never">
      <el-tabs v-model="tab" @tab-change="reload">
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
        <el-table-column prop="lastResult" label="状态" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="STATUS_TYPE[row.auditStatus] || 'info'">{{ row.lastResult || STATUS_LABEL[row.auditStatus] || row.auditStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="projectCode" label="项目编码" width="160" />
        <el-table-column prop="projectName" label="项目名称" min-width="200" show-overflow-tooltip />
        <el-table-column prop="deptName" label="业务处室" width="140" show-overflow-tooltip />
        <el-table-column prop="pivotOfficeName" label="归口处室" width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.pivotOfficeName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="traceCode" label="追踪代码" width="120" align="center">
          <template #default="{ row }">{{ row.traceCode || '—' }}</template>
        </el-table-column>
        <el-table-column prop="competentDept" label="主管部门" width="160" show-overflow-tooltip />
        <el-table-column prop="grantType" label="发放类型" width="110" align="center" />
        <el-table-column prop="policyLevel" label="政策级次" width="100" align="center">
          <template #default="{ row }">{{ POLICY[row.policyLevel] || row.policyLevel }}</template>
        </el-table-column>
        <el-table-column prop="projectLevel" label="项目级次" width="140" align="center">
          <template #default="{ row }">{{ PROJLEVEL[row.projectLevel] || row.projectLevel }}</template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination v-model:current-page="page.pageNum" v-model:page-size="page.pageSize" :total="page.total"
          :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next, jumper" background
          @size-change="reload" @current-change="reload" />
      </div>
    </el-card>

    <!-- 审核历史 -->
    <el-dialog v-model="histVisible" title="审核历史 / 流程进度" width="900px">
      <el-table :data="history" border stripe size="small">
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="doneStation" label="已审岗" width="110" />
        <el-table-column prop="operator" label="操作人" width="100" />
        <el-table-column prop="opType" label="操作类型" width="90" align="center" />
        <el-table-column prop="opResult" label="操作结果" width="110" />
        <el-table-column prop="opinion" label="审核意见" min-width="140" />
        <el-table-column prop="opTime" label="操作时间" width="170" align="center">
          <template #default="{ row }">{{ fmt(row.opTime) }}</template>
        </el-table-column>
        <el-table-column prop="pendingStation" label="待审岗" width="110" />
      </el-table>
      <el-empty v-if="!history.length" description="暂无审核记录" />
    </el-dialog>

    <!-- 省财政厅农业处终审：审核意见 + 选填追踪代码（原信息处单独核定那一棒已并入此处） -->
    <el-dialog v-model="finalVisible" title="省财政厅农业处审核（终审）" width="560px">
      <el-form label-width="100px">
        <el-form-item label="审核意见">
          <el-input v-model="finalOpinion" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="追踪代码">
          <el-input v-model="finalTrace" maxlength="64"
            placeholder="选填，字母/数字/下划线/连字符；留空可事后用「核定追踪代码」补录" />
        </el-form-item>
      </el-form>
      <div class="final-tip">终审通过后自动生成项目编码，项目转入「已审核」。</div>
      <template #footer>
        <el-button @click="finalVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmFinal">确定终审</el-button>
      </template>
    </el-dialog>

    <!-- 新增 / 修改 -->
    <ProjectForm v-model:visible="formVisible" :row="formRow" @saved="reload" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, Promotion, Select, Back, Ticket, RefreshLeft } from '@element-plus/icons-vue'
import { projectApi } from '../../api/system'
import ProjectForm from './ProjectForm.vue'

const props = defineProps({ forAudit: { type: Boolean, default: false } })

const POLICY = { CENTRAL: '中央级', PROVINCE: '省级', CITY: '市级', COUNTY: '县（区）级' }
const PROJLEVEL = { PROV_SELF: '省级自建项目', PROV_CATALOG: '省级目录清单项目', CITY_SELF: '市级自建项目', COUNTY_SELF: '县级自建项目' }
const STATUS_LABEL = { DRAFT: '草稿', SUBMITTED: '已送审', APPROVED: '已终审' }
const STATUS_TYPE = { DRAFT: 'info', SUBMITTED: 'warning', APPROVED: 'success' }

const tab = ref('pending')
// 维护页的「所有」是纯查看页，没有按钮——整条工具栏收起，免得留一条空白卡片
const showBar = computed(() => props.forAudit || tab.value !== 'all')
const query = reactive({ projectCode: '', projectName: '' })
const rows = ref([]); const loading = ref(false)
const page = reactive({ pageNum: 1, pageSize: 10, total: 0 })
const selected = ref([])

onMounted(reload)

async function reload() {
  loading.value = true
  try {
    const res = await projectApi.page({
      pageNum: page.pageNum, pageSize: page.pageSize, tab: tab.value, forAudit: props.forAudit,
      projectCode: query.projectCode || undefined, projectName: query.projectName || undefined
    })
    rows.value = res?.records || []
    page.total = Number(res?.total) || 0
  } finally { loading.value = false }
}

function summary({ columns }) {
  const s = []
  columns.forEach((c, i) => { s[i] = i === 1 ? '合计' : '' })
  return s
}

function pickOne() {
  if (selected.value.length !== 1) { ElMessage.warning('请选中一条记录'); return null }
  return selected.value[0]
}
function ensureSel() {
  if (!selected.value.length) { ElMessage.warning('请先勾选项目'); return false }
  return true
}

// ---- 维护：新增/修改/删除/送审 ----
const formVisible = ref(false); const formRow = ref(null)
function openCreate() { formRow.value = null; formVisible.value = true }
function openEdit() { const r = pickOne(); if (!r) return; formRow.value = r; formVisible.value = true }

async function doDelete() {
  if (!ensureSel()) return
  await ElMessageBox.confirm(`确定删除选中的 ${selected.value.length} 个项目？此操作不可恢复`, '删除确认', { type: 'warning' })
  for (const r of selected.value) await projectApi.delete(r.id)
  ElMessage.success('删除成功'); reload()
}
async function doSubmit() {
  if (!ensureSel()) return
  await projectApi.submit(selected.value.map(r => r.id))
  ElMessage.success('送审成功'); reload()
}

/**
 * 取消送审：把已送审的项目撤回成草稿。
 * 后端只放行「还停在第一棒(省财政厅业务处室)且没人审过」的，已被审过或已终审的会明确报「撤销失败」。
 * 逐条调用而非批量：接口是单条的，且失败原因要能对上是哪个项目。
 */
async function doRevoke() {
  const r = pickOne(); if (!r) return
  await ElMessageBox.confirm(`确定取消送审「${r.projectName}」？撤回后回到草稿，可继续修改`, '取消送审',
    { type: 'warning' })
  await projectApi.revoke(r.id)
  ElMessage.success('已取消送审'); reload()
}

// ---- 审核：3 棒 ----
// 县财政局录入 --送审--> DEPT 省财政厅业务处室 --> AGRI 省财政厅农业处(终审+生成编码+可核定追踪代码)
// COUNTY/SZ 是已废弃的中间棒，仅历史在途数据还停在上面，后端按 DEPT 处理，这里跟着给同一个标题。
const STAGE_TITLE = {
  DEPT: '省财政厅业务处室审核',
  AGRI: '省财政厅农业处审核（终审）',
  COUNTY: '省财政厅业务处室审核',
  SZ: '省财政厅业务处室审核'
}
async function doApprove() {
  if (!ensureSel()) return
  const stages = [...new Set(selected.value.map(r => r.auditStage))]
  if (stages.length > 1) { ElMessage.warning('选中项目处于不同审核阶段，请分别审核'); return }
  const stage = stages[0]
  const title = STAGE_TITLE[stage]
  if (!title) { ElMessage.warning('当前阶段无法审核'); return }
  if (stage !== 'AGRI') {
    const { value } = await ElMessageBox.prompt('审核意见', title, { inputValue: '同意', confirmButtonText: '通过' })
    await projectApi.approve(selected.value.map(r => r.id), value)
    ElMessage.success('审核通过'); reload()
    return
  }
  // 终审：追踪代码并入这一棒（原先是信息处单独一岗，已退场），留空表示暂不核定，事后可用「核定追踪代码」补
  finalOpinion.value = '同意'; finalTrace.value = ''; finalVisible.value = true
}

// ---- 农业处终审弹窗（审核意见 + 选填追踪代码）----
const finalVisible = ref(false); const finalOpinion = ref('同意'); const finalTrace = ref('')
async function confirmFinal() {
  const code = finalTrace.value.trim()
  if (code && !/^[0-9A-Za-z_-]{1,64}$/.test(code)) {
    ElMessage.warning('追踪代码只能是字母/数字/下划线/连字符，且不超过 64 位'); return
  }
  await projectApi.approve(selected.value.map(r => r.id), finalOpinion.value, code || undefined)
  finalVisible.value = false
  ElMessage.success(code ? '终审成功，已生成项目编码并核定追踪代码' : '终审成功，已生成项目编码')
  reload()
}

// ---- 农业处：事后补录追踪代码（对已终审项目）----
async function doTraceCode() {
  if (!ensureSel()) return
  if (selected.value.some(r => r.auditStatus !== 'APPROVED')) {
    ElMessage.warning('仅可对已终审项目核定追踪代码'); return
  }
  const { value } = await ElMessageBox.prompt('追踪代码（字母/数字/下划线/连字符，≤64位）', '核定追踪代码',
    { inputPlaceholder: '终审时未填的可在此补录', confirmButtonText: '确定核定',
      inputPattern: /^[0-9A-Za-z_-]{1,64}$/, inputErrorMessage: '格式不合法' })
  await projectApi.traceCode(selected.value.map(r => r.id), value)
  ElMessage.success('追踪代码已核定'); reload()
}
async function doReject() {
  if (!ensureSel()) return
  const { value } = await ElMessageBox.prompt('退回原因', '退回', { inputPlaceholder: '请填写退回原因', confirmButtonText: '确定退回' })
  await projectApi.reject(selected.value.map(r => r.id), value)
  ElMessage.success('已退回'); reload()
}

// ---- 审核历史 ----
const histVisible = ref(false); const history = ref([])
async function openHistory(row) {
  history.value = (await projectApi.history(row.id)) || []
  histVisible.value = true
}

function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '' }
</script>

<style scoped>
.bar :deep(.el-card__body) { padding: 12px 16px; display: flex; flex-wrap: wrap; gap: 8px; }
.filter { margin: 12px 0; }
.filter :deep(.el-card__body) { padding: 16px 16px 0; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.final-tip { color: var(--el-text-color-secondary); font-size: 12px; padding-left: 100px; }
</style>
