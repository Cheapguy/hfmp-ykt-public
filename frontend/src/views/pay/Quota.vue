<template>
  <div class="quota-wrap">
    <h2 class="page-title">项目额度规则设置</h2>
    <div class="layout">
      <!-- 左：项目（一卡通授权显示） -->
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

      <!-- 右：挂接列表 -->
      <el-card shadow="never" class="right">
        <div class="bar">
          <el-button type="primary" :icon="Plus" :disabled="!curProjectId" @click="openLink">新增挂接</el-button>
          <el-button :icon="Delete" @click="doUnlink">撤销挂接</el-button>
          <el-button :icon="Setting" :disabled="!curProjectId" @click="openRule">使用规则设置</el-button>
          <el-button :icon="Download" @click="doExport">导出</el-button>
          <span class="tip" v-if="curProjectName">当前项目：{{ curProjectName }}</span>
        </div>
        <el-table v-loading="loading" :data="rows" border stripe size="default" @selection-change="s => selected = s">
          <el-table-column type="selection" width="44" />
          <el-table-column type="index" label="#" width="50" align="center" />
          <el-table-column prop="projectCode" label="补贴项目编码" width="120" />
          <el-table-column prop="projectName" label="补贴项目名称" min-width="170" show-overflow-tooltip />
          <el-table-column prop="useRuleLabel" label="使用规则" width="130" align="center" />
          <el-table-column prop="indicatorNo" label="指标文号" width="180" show-overflow-tooltip />
          <el-table-column prop="budgetProject" label="预算项目" width="170" show-overflow-tooltip />
          <el-table-column prop="fundNature" label="资金性质" width="170" show-overflow-tooltip />
          <el-table-column prop="availableAmount" label="可支付余额" width="140" align="right">
            <template #default="{ row }">{{ money(row.availableAmount) }}</template>
          </el-table-column>
          <el-table-column prop="priority" label="优先级" width="80" align="center" />
        </el-table>
      </el-card>
    </div>

    <!-- 新增挂接 -->
    <el-dialog v-model="linkVisible" title="额度挂接 - 选择指标（仅显示热点分类“一卡通”）" width="92%" top="5vh">
      <el-form inline class="ind-filter" @submit.prevent>
        <el-form-item label="指标文号"><el-input v-model="indQ.indicatorNo" clearable style="width:200px" /></el-form-item>
        <el-form-item label="政府经济分类"><el-input v-model="indQ.govEcon" clearable style="width:180px" /></el-form-item>
        <el-form-item label="部门经济分类"><el-input v-model="indQ.deptEcon" clearable style="width:180px" /></el-form-item>
        <el-form-item label="预算项目"><el-input v-model="indQ.budgetProject" clearable style="width:180px" /></el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadIndicators">查询</el-button>
          <el-button @click="resetIndFilter">重置</el-button>
        </el-form-item>
      </el-form>
      <el-table ref="indTable" v-loading="indLoading" :data="indicators" border stripe size="small" height="52vh"
        @selection-change="s => indSelected = s">
        <el-table-column type="selection" width="44" />
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="indicatorNo" label="指标文号" width="180" show-overflow-tooltip />
        <el-table-column label="政府经济分类" width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.govEconCode }} {{ row.govEconName }}</template>
        </el-table-column>
        <el-table-column prop="budgetProject" label="预算项目" width="160" show-overflow-tooltip />
        <el-table-column prop="issuedAmount" label="指标下达数" width="130" align="right">
          <template #default="{ row }">{{ money(row.issuedAmount) }}</template>
        </el-table-column>
        <el-table-column prop="paidAmount" label="已支付数" width="130" align="right">
          <template #default="{ row }">{{ money(row.paidAmount) }}</template>
        </el-table-column>
        <el-table-column prop="availableAmount" label="可支付余额" width="130" align="right">
          <template #default="{ row }">{{ money(row.availableAmount) }}</template>
        </el-table-column>
        <el-table-column prop="budgetUnit" label="预算单位" width="180" show-overflow-tooltip />
        <el-table-column label="热点分类" width="100" align="center">
          <template #default="{ row }"><el-tag size="small" type="success">{{ row.hotClassName }}</el-tag></template>
        </el-table-column>
      </el-table>
      <template #footer>
        <span class="dlg-tip">已选 {{ indSelected.length }} 个指标</span>
        <el-button @click="linkVisible = false">返回</el-button>
        <el-button type="primary" @click="doLink">保存</el-button>
      </template>
    </el-dialog>

    <!-- 使用规则设置 -->
    <el-dialog v-model="ruleVisible" title="额度使用规则设置" width="86%" top="6vh">
      <div class="rule-type">
        <span class="lbl">额度使用类型：</span>
        <el-radio-group v-model="ruleType">
          <el-radio value="ASC">按可用金额从小到大使用</el-radio>
          <el-radio value="DESC">按可用金额从大到小使用</el-radio>
          <el-radio value="PRIORITY">按优先级使用</el-radio>
        </el-radio-group>
      </div>
      <el-table :data="ruleRows" border stripe size="small" height="44vh">
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="indicatorNo" label="指标文号" width="180" show-overflow-tooltip />
        <el-table-column prop="availableAmount" label="可支付余额" width="130" align="right">
          <template #default="{ row }">{{ money(row.availableAmount) }}</template>
        </el-table-column>
        <el-table-column label="优先级" width="110" align="center">
          <template #default="{ row }">
            <el-input-number v-model="row.priority" :min="1" :controls="false" size="small" style="width:80px"
              :disabled="ruleType !== 'PRIORITY'" />
          </template>
        </el-table-column>
        <el-table-column prop="budgetProject" label="项目" width="170" show-overflow-tooltip />
        <el-table-column prop="issuedAmount" label="指标下达数" width="130" align="right">
          <template #default="{ row }">{{ money(row.issuedAmount) }}</template>
        </el-table-column>
        <el-table-column prop="paidAmount" label="已支付数" width="120" align="right">
          <template #default="{ row }">{{ money(row.paidAmount) }}</template>
        </el-table-column>
        <el-table-column prop="indicatorDesc" label="指标说明" min-width="160" show-overflow-tooltip />
        <el-table-column prop="budgetUnit" label="预算单位" width="170" show-overflow-tooltip />
      </el-table>
      <div class="rule-hint">优先级就是支付过程中指标的使用顺序（先用优先级 1，用完顺延）</div>
      <template #footer>
        <el-button @click="ruleVisible = false">返回</el-button>
        <el-button type="primary" @click="doSaveRule">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Delete, Setting, Download } from '@element-plus/icons-vue'
import { quotaApi, projectApi } from '../../api/system'

const projects = ref([]); const projKw = ref('')
const curProjectId = ref(null); const curProjectName = ref('')
const rows = ref([]); const loading = ref(false); const selected = ref([])

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
  try { rows.value = (await quotaApi.list(curProjectId.value)) || [] }
  finally { loading.value = false }
}

// ---- 新增挂接 ----
const linkVisible = ref(false); const indicators = ref([]); const indLoading = ref(false); const indSelected = ref([])
const indQ = reactive({ indicatorNo: '', govEcon: '', deptEcon: '', budgetProject: '' })
function resetIndFilter() { Object.assign(indQ, { indicatorNo: '', govEcon: '', deptEcon: '', budgetProject: '' }); loadIndicators() }
function openLink() { linkVisible.value = true; resetIndFilter() }
async function loadIndicators() {
  indLoading.value = true
  try { indicators.value = (await quotaApi.indicators({ projectId: curProjectId.value, ...indQ })) || [] }
  finally { indLoading.value = false }
}
async function doLink() {
  if (!indSelected.value.length) { ElMessage.warning('请选择指标'); return }
  const res = await quotaApi.link(curProjectId.value, indSelected.value.map(i => String(i.id)))
  ElMessage.success(`挂接成功 ${res?.count ?? indSelected.value.length} 个指标`)
  linkVisible.value = false; reload()
}

// ---- 撤销挂接 ----
async function doUnlink() {
  if (!selected.value.length) { ElMessage.warning('请勾选要撤销的挂接'); return }
  await ElMessageBox.confirm(`确定撤销选中的 ${selected.value.length} 条挂接？`, '撤销挂接', { type: 'warning' })
  await quotaApi.unlink(selected.value.map(r => String(r.id)))
  ElMessage.success('已撤销挂接'); reload()
}

// ---- 使用规则设置 ----
const ruleVisible = ref(false); const ruleType = ref('PRIORITY'); const ruleRows = ref([])
function openRule() {
  if (!rows.value.length) { ElMessage.warning('该项目暂无挂接指标'); return }
  ruleType.value = rows.value[0]?.useRule || 'PRIORITY'
  ruleRows.value = rows.value.map(r => ({ ...r }))
  ruleVisible.value = true
}
async function doSaveRule() {
  await quotaApi.saveRule({
    projectId: curProjectId.value, useRule: ruleType.value,
    items: ruleRows.value.map(r => ({ id: String(r.id), priority: r.priority }))
  })
  ElMessage.success('使用规则已保存'); ruleVisible.value = false; reload()
}

// ---- 导出 ----
function doExport() {
  if (!rows.value.length) { ElMessage.warning('无数据可导出'); return }
  const head = ['补贴项目编码', '补贴项目名称', '使用规则', '指标文号', '预算项目', '资金性质', '可支付余额', '优先级']
  const lines = rows.value.map(r => [r.projectCode, r.projectName, r.useRuleLabel, r.indicatorNo, r.budgetProject, r.fundNature, r.availableAmount, r.priority]
    .map(v => `"${v == null ? '' : String(v).replace(/"/g, '""')}"`).join(','))
  const csv = '﻿' + [head.join(','), ...lines].join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob); const a = document.createElement('a')
  a.href = url; a.download = '项目额度挂接.csv'; document.body.appendChild(a); a.click()
  document.body.removeChild(a); URL.revokeObjectURL(url)
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
.bar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 12px; }
.bar .tip { margin-left: auto; color: #909399; font-size: 13px; }
.ind-filter { margin-bottom: 8px; }
.dlg-tip { color: #909399; font-size: 13px; margin-right: auto; }
.rule-type { margin-bottom: 12px; }
.rule-type .lbl { font-weight: 600; margin-right: 8px; }
.rule-hint { color: #e6a23c; font-size: 13px; margin-top: 10px; }
:deep(.el-dialog__footer) { display: flex; align-items: center; gap: 8px; }
</style>
