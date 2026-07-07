<template>
  <div>
    <!-- 工具条 -->
    <el-card shadow="never" class="bar">
      <template v-if="!forAudit">
        <el-button type="primary" :icon="Plus" @click="openCreate">新增</el-button>
        <el-button :icon="Edit" @click="openEdit">修改</el-button>
        <el-button :icon="Delete" @click="doDelete">删除</el-button>
        <el-button type="success" :icon="Promotion" @click="doSubmit">送审</el-button>
      </template>
      <template v-else>
        <el-button type="primary" :icon="Select" @click="doApprove">审核</el-button>
        <el-button :icon="Back" @click="doReject">退回</el-button>
        <el-button :icon="Edit" @click="openEdit">修改</el-button>
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

    <!-- 市州综合岗：省级处室信息（选定归口处室） -->
    <el-dialog v-model="officeVisible" title="省级处室信息 · 选定归口处室" width="560px">
      <el-table :data="offices" border stripe size="small" height="360"
        highlight-current-row @current-change="r => officePick = r">
        <el-table-column width="44" align="center">
          <template #default="{ row }"><el-radio :model-value="officePick?.officeCode" :label="row.officeCode"><span></span></el-radio></template>
        </el-table-column>
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="officeCode" label="处室编码" width="100" align="center" />
        <el-table-column prop="officeName" label="处室名称" min-width="180" />
      </el-table>
      <el-form label-width="80px" style="margin-top:12px">
        <el-form-item label="审核意见">
          <el-input v-model="officeOpinion" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="officeVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmOffice">确定</el-button>
      </template>
    </el-dialog>

    <!-- 新增 / 修改 -->
    <ProjectForm v-model:visible="formVisible" :row="formRow" @saved="reload" />
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, Promotion, Select, Back } from '@element-plus/icons-vue'
import { projectApi } from '../../api/system'
import ProjectForm from './ProjectForm.vue'

const props = defineProps({ forAudit: { type: Boolean, default: false } })

const POLICY = { CENTRAL: '中央级', PROVINCE: '省级', CITY: '市级', COUNTY: '县级' }
const PROJLEVEL = { PROV_SELF: '省级自建项目', PROV_CATALOG: '省级目录清单项目', CITY_SELF: '市级自建项目', COUNTY_SELF: '县级自建项目' }
const STATUS_LABEL = { DRAFT: '草稿', SUBMITTED: '已送审', APPROVED: '已终审' }
const STATUS_TYPE = { DRAFT: 'info', SUBMITTED: 'warning', APPROVED: 'success' }

const tab = ref('pending')
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

// ---- 审核：审核/退回（两段制：市州综合岗选归口处室 → 归口处室终审） ----
async function doApprove() {
  if (!ensureSel()) return
  const stages = [...new Set(selected.value.map(r => r.auditStage))]
  if (stages.length > 1) { ElMessage.warning('选中项目处于不同审核阶段，请分别审核'); return }
  const stage = stages[0]
  if (stage === 'SZ') {
    await openOfficeDialog()
  } else if (stage === 'DEPT') {
    const { value } = await ElMessageBox.prompt('审核意见', '归口处室审核（终审）', { inputValue: '同意', confirmButtonText: '确定终审' })
    await projectApi.approve(selected.value.map(r => r.id), value)
    ElMessage.success('终审成功，已生成项目编码'); reload()
  } else {
    ElMessage.warning('当前阶段无法审核')
  }
}

// ---- 市州综合岗：选定归口处室 ----
const officeVisible = ref(false); const offices = ref([]); const officePick = ref(null); const officeOpinion = ref('同意')
async function openOfficeDialog() {
  if (!offices.value.length) offices.value = (await projectApi.offices()) || []
  officePick.value = null; officeOpinion.value = '同意'; officeVisible.value = true
}
async function confirmOffice() {
  if (!officePick.value) { ElMessage.warning('请选择归口处室'); return }
  await projectApi.approve(selected.value.map(r => r.id), officeOpinion.value,
    officePick.value.officeCode, officePick.value.officeName)
  ElMessage.success('审核通过，已转交归口处室'); officeVisible.value = false; reload()
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
</style>
