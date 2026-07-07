<template>
  <div>
    <h2 class="page-title">补贴批次维护</h2>

    <!-- 工具条 -->
    <el-card shadow="never" class="bar">
      <el-button type="primary" :icon="Plus" @click="openCreate">按主管部门新增</el-button>
      <el-button :icon="Edit" @click="openEdit">修改</el-button>
      <el-button :icon="Delete" @click="doDelete">删除</el-button>
      <el-button type="success" :icon="Promotion" @click="doIssue">批次下达</el-button>
      <el-button :icon="Back" @click="doCancelIssue">取消批次下达</el-button>
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
        <el-form-item>
          <el-button type="primary" round @click="reload">查询</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 列表 -->
    <el-card shadow="never">
      <el-table ref="tableRef" v-loading="loading" :data="rows" border stripe size="default"
        show-summary :summary-method="summary" @selection-change="s => selected = s">
        <el-table-column type="selection" width="44" />
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="status" label="批次状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="stateType(row)">{{ stateText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="batchCode" label="批次编号" width="230" show-overflow-tooltip />
        <el-table-column prop="batchName" label="批次名称" min-width="220" show-overflow-tooltip />
        <el-table-column prop="townId" label="下达单位" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ townName(row.townId) }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" width="120" show-overflow-tooltip />
        <el-table-column prop="fundTitle" label="批次标题" width="160" show-overflow-tooltip />
        <el-table-column prop="planCount" label="申请发放人数(人)" width="140" align="right" />
        <el-table-column prop="planAmount" label="申请发放金额(元)" width="150" align="right">
          <template #default="{ row }">{{ money(row.planAmount) }}</template>
        </el-table-column>
        <el-table-column prop="actualCount" label="实际发放人数(人)" width="140" align="right" />
        <el-table-column prop="actualAmount" label="实际发放金额(元)" width="150" align="right">
          <template #default="{ row }">{{ money(row.actualAmount) }}</template>
        </el-table-column>
        <el-table-column prop="refundAmount" label="退款金额(元)" width="130" align="right">
          <template #default="{ row }">{{ money(row.refundAmount) }}</template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination v-model:current-page="page.pageNum" v-model:page-size="page.pageSize" :total="page.total"
          :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next, jumper" background
          @size-change="reload" @current-change="reload" />
      </div>
    </el-card>

    <!-- 新增 / 修改 批次 -->
    <el-dialog v-model="formVisible" :title="editing ? '修改批次' : '新增批次'" width="780px" @open="onDialogOpen">
      <el-tabs v-model="tab">
        <el-tab-pane label="批次基础信息" name="base">
          <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
            <el-row :gutter="16">
              <el-col :span="12">
                <el-form-item label="发放表" prop="projectId">
                  <el-select v-model="form.projectId" filterable placeholder="选择发放表"
                    style="width:100%" @change="onProjChange">
                    <el-option v-for="p in projects" :key="p.id"
                      :label="(p.projectCode ? p.projectCode + '-' : '') + p.projectName" :value="p.id" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="批次编号">
                  <el-input v-model="form.batchCode" disabled placeholder="保存后自动生成" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="批次名称" prop="batchName">
                  <el-input v-model="form.batchName" maxlength="128" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="发放资金标题">
                  <el-input v-model="form.fundTitle" maxlength="255" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="截止日期" prop="deadline">
                  <el-date-picker v-model="form.deadline" type="date" value-format="YYYY-MM-DD"
                    placeholder="选择截止日期" style="width:100%" :disabled-date="beforeToday" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="项目简称">
                  <el-input v-model="form.shortName" disabled />
                </el-form-item>
              </el-col>
              <el-col :span="24">
                <el-form-item label="备注">
                  <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" />
                </el-form-item>
              </el-col>
            </el-row>
          </el-form>
        </el-tab-pane>

        <el-tab-pane v-if="!editing" label="下达单位" name="towns">
          <div class="towns-head">
            <el-input v-model="townFilter" placeholder="筛选乡镇" clearable style="width:220px" />
            <el-checkbox v-model="townAll" @change="toggleAllTowns" style="margin-left:12px">全选</el-checkbox>
            <span class="towns-tip">已选 {{ form.townIds.length }} 个</span>
          </div>
          <el-checkbox-group v-model="form.townIds" class="towns-grid">
            <el-checkbox v-for="t in filteredTowns" :key="t.id" :value="t.id" :label="t.id">{{ t.name }}</el-checkbox>
          </el-checkbox-group>
        </el-tab-pane>
      </el-tabs>

      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, Promotion, Back } from '@element-plus/icons-vue'
import { batchApi, projectApi, orgApi } from '../../api/system'

const STATUS_LABEL = { NEW: '未下达', ISSUED: '已下达', SENT: '已发送一体化', PAID: '已支付', PAID_OUT: '已发放', SUBMITTED: '审核中', PART_REFUND: '部分退款', PART_RETURN: '部分退回' }
const STATUS_TYPE = { NEW: 'info', ISSUED: 'warning', SENT: 'primary', PAID: 'primary', PAID_OUT: 'success' }

const projects = ref([]); const towns = ref([]); const pickTowns = ref([])
const townName = (id) => towns.value.find(t => String(t.id) === String(id))?.orgName || '-'
// 状态文本优先用流程态 lastResult（含 退回 标红），回退到 status 映射
const stateText = (row) => row.lastResult || STATUS_LABEL[row.status] || row.status
const stateType = (row) => (row.lastResult && row.lastResult.includes('退回')) ? 'danger' : (STATUS_TYPE[row.status] || 'info')

const query = reactive({ batchCode: '', batchName: '' })
const rows = ref([]); const loading = ref(false); const selected = ref([])
const page = reactive({ pageNum: 1, pageSize: 10, total: 0 })

onMounted(async () => {
  projects.value = (await projectApi.list({ included: 1, tab: 'all' })) || []
  // towns：全州乡镇字典，仅供列表 townName 显示（批次列表按租户不按县，需覆盖所有县）
  towns.value = ((await orgApi.tree()) || []).filter(o => o.orgType === 'TOWN')
  // pickTowns：本县乡镇，供「下达单位」多选（县账号只能对本县下达）
  pickTowns.value = (await batchApi.towns()) || []
  reload()
})

async function reload() {
  loading.value = true
  try {
    const res = await batchApi.page({
      pageNum: page.pageNum, pageSize: page.pageSize,
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
    if (['planCount', 'actualCount'].includes(c.property)) {
      s[i] = data.reduce((a, r) => a + (Number(r[c.property]) || 0), 0)
    } else if (['planAmount', 'actualAmount', 'refundAmount'].includes(c.property)) {
      s[i] = money(data.reduce((a, r) => a + (Number(r[c.property]) || 0), 0))
    } else s[i] = ''
  })
  return s
}

function pickOne() {
  if (selected.value.length !== 1) { ElMessage.warning('请选中一条记录'); return null }
  return selected.value[0]
}

// ---- 新增 / 修改 ----
const formVisible = ref(false); const editing = ref(false); const tab = ref('base')
const formRef = ref()
const form = reactive({ id: null, projectId: null, batchCode: '', batchName: '', fundTitle: '', deadline: '', shortName: '', remark: '', townIds: [] })
const rules = {
  projectId: [{ required: true, message: '请选择发放表', trigger: 'change' }],
  batchName: [{ required: true, message: '请填写批次名称', trigger: 'blur' }],
  deadline:  [{ required: true, message: '请选择截止日期', trigger: 'change' }]
}

function resetForm() {
  Object.assign(form, { id: null, projectId: null, batchCode: '', batchName: '', fundTitle: '', deadline: '', shortName: '', remark: '', townIds: [] })
  townFilter.value = ''; townAll.value = false
}
function openCreate() { resetForm(); editing.value = false; tab.value = 'base'; formVisible.value = true }
function openEdit() {
  const r = pickOne(); if (!r) return
  if (r.status !== 'NEW') { ElMessage.warning('仅未下达批次可修改'); return }
  resetForm(); editing.value = true; tab.value = 'base'
  Object.assign(form, { id: r.id, projectId: r.projectId, batchCode: r.batchCode, batchName: r.batchName, fundTitle: r.fundTitle, deadline: r.deadline, remark: r.remark })
  onProjChange(r.projectId)
  formVisible.value = true
}
function onDialogOpen() { formRef.value?.clearValidate() }
// 截止日期不能小于今天（禁用今天之前的日期，今天可选）
function beforeToday(d) {
  const t = new Date(); t.setHours(0, 0, 0, 0)
  return d.getTime() < t.getTime()
}
function onProjChange(pid) {
  const p = projects.value.find(x => String(x.id) === String(pid))
  form.shortName = p?.shortName || ''
  if (p && !form.batchName) form.batchName = p.projectName
}

async function onSave() {
  await formRef.value.validate()
  if (editing.value) {
    await batchApi.save({ id: form.id, projectId: form.projectId, batchName: form.batchName, fundTitle: form.fundTitle, deadline: form.deadline, remark: form.remark })
    ElMessage.success('保存成功')
  } else {
    if (!form.townIds.length) { tab.value = 'towns'; ElMessage.warning('请选择下达单位（乡镇）'); return }
    const res = await batchApi.batchCreate({ projectId: form.projectId, batchName: form.batchName, fundTitle: form.fundTitle, deadline: form.deadline, remark: form.remark, townIds: form.townIds })
    ElMessage.success(`保存成功，已生成 ${res?.count ?? form.townIds.length} 个批次`)
  }
  formVisible.value = false; reload()
}

// ---- 下达单位 多选 ----
const townFilter = ref(''); const townAll = ref(false)
const filteredTowns = computed(() => {
  const kw = townFilter.value.trim()
  return kw ? pickTowns.value.filter(t => t.name.includes(kw)) : pickTowns.value
})
function toggleAllTowns(v) {
  form.townIds = v ? filteredTowns.value.map(t => t.id) : []
}

// ---- 删除 / 下达 / 取消下达 ----
async function doDelete() {
  if (!selected.value.length) { ElMessage.warning('请先勾选批次'); return }
  const bad = selected.value.filter(r => r.status !== 'NEW')
  if (bad.length) { ElMessage.warning('只能删除未下达批次'); return }
  await ElMessageBox.confirm(`确定删除选中的 ${selected.value.length} 个批次？`, '删除确认', { type: 'warning' })
  for (const r of selected.value) await batchApi.delete(r.id)
  ElMessage.success('批次删除成功'); reload()
}
async function doIssue() {
  if (!selected.value.length) { ElMessage.warning('请先勾选批次'); return }
  const bad = selected.value.filter(r => r.status !== 'NEW')
  if (bad.length) { ElMessage.warning('仅未下达批次可下达'); return }
  for (const r of selected.value) await batchApi.issue(r.id)
  ElMessage.success('批次下达成功'); reload()
}
async function doCancelIssue() {
  if (!selected.value.length) { ElMessage.warning('请先勾选批次'); return }
  const bad = selected.value.filter(r => r.status !== 'ISSUED')
  if (bad.length) { ElMessage.warning('仅已下达批次可取消下达'); return }
  // 更正发放批次由系统重构生成，取消下达会脱出二次发放链路，禁止
  if (selected.value.some(r => (r.batchName || '').startsWith('更正发放'))) {
    ElMessage.warning('更正发放批次由系统重构生成，不可取消下达'); return
  }
  for (const r of selected.value) await batchApi.cancelIssue(r.id)
  ElMessage.success('已取消批次下达'); reload()
}
</script>

<style scoped>
.bar :deep(.el-card__body) { padding: 12px 16px; display: flex; flex-wrap: wrap; gap: 8px; }
.filter { margin: 12px 0; }
.filter :deep(.el-card__body) { padding: 16px 16px 0; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.towns-head { display: flex; align-items: center; margin-bottom: 12px; }
.towns-tip { margin-left: auto; color: #909399; font-size: 13px; }
.towns-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px 4px; max-height: 320px; overflow-y: auto; }
.towns-grid :deep(.el-checkbox) { margin-right: 0; }
</style>
