<template>
  <div>
    <h2 class="page-title">补贴项目纳入及挂接</h2>

    <!-- 工具条 -->
    <el-card shadow="never" class="bar">
      <el-button type="primary" :icon="CirclePlus" @click="openInclude">纳入</el-button>
      <el-button type="success" :icon="Link" @click="openLink">挂接</el-button>
      <el-button :icon="Remove" @click="doUnlink">取消挂接</el-button>
    </el-card>

    <!-- 筛选 -->
    <el-card shadow="never" class="filter">
      <el-form inline @submit.prevent>
        <el-form-item label="项目编码：">
          <el-input v-model="query.projectCode" clearable style="width:280px" @keyup.enter="reload" />
        </el-form-item>
        <el-form-item label="项目名称：">
          <el-input v-model="query.projectName" clearable style="width:280px" @keyup.enter="reload" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" round @click="reload">查询</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 已纳入项目库列表 -->
    <el-card shadow="never">
      <el-table ref="tableRef" v-loading="loading" :data="rows" border stripe size="default"
        @selection-change="s => selected = s">
        <el-table-column type="selection" width="44" />
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="projectCode" label="项目编码" width="160" />
        <el-table-column prop="projectName" label="项目名称" min-width="200" show-overflow-tooltip />
        <el-table-column prop="competentDept" label="主管部门" width="160" show-overflow-tooltip />
        <el-table-column prop="deptName" label="业务科室" width="140" show-overflow-tooltip />
        <el-table-column prop="grantType" label="发放类型" width="110" align="center" />
        <el-table-column label="是否自建项目" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="isSelf(row) ? 'success' : 'info'">{{ isSelf(row) ? '是' : '否' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="catalogCode" label="中央项目编码" width="130" align="center">
          <template #default="{ row }">{{ row.catalogCode || '—' }}</template>
        </el-table-column>
        <el-table-column prop="catalogName" label="中央项目名称" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.catalogName || '—' }}</template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination v-model:current-page="page.pageNum" v-model:page-size="page.pageSize" :total="page.total"
          :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next, jumper" background
          @size-change="reload" @current-change="reload" />
      </div>
    </el-card>

    <!-- 纳入弹窗：待纳入（已终审未纳入）多选 -->
    <el-dialog v-model="incVisible" title="补贴项目纳入 · 待纳入清单" width="860px" @open="loadIncludable">
      <el-form inline @submit.prevent class="dlg-filter">
        <el-form-item label="项目名称：">
          <el-input v-model="incQuery" clearable style="width:240px" @keyup.enter="loadIncludable" />
        </el-form-item>
        <el-form-item><el-button type="primary" @click="loadIncludable">查询</el-button></el-form-item>
      </el-form>
      <el-table :data="incRows" v-loading="incLoading" border stripe size="small" height="380"
        @selection-change="s => incSel = s">
        <el-table-column type="selection" width="44" />
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="projectCode" label="项目编码" width="160" />
        <el-table-column prop="projectName" label="项目名称" min-width="200" show-overflow-tooltip />
        <el-table-column prop="competentDept" label="主管部门" width="160" show-overflow-tooltip />
      </el-table>
      <el-empty v-if="!incRows.length && !incLoading" description="无待纳入项目" />
      <template #footer>
        <el-button @click="incVisible = false">取消</el-button>
        <el-button type="primary" @click="doInclude">确定纳入（{{ incSel.length }}）</el-button>
      </template>
    </el-dialog>

    <!-- 挂接弹窗：中央补贴项目清单 单选 -->
    <el-dialog v-model="linkVisible" title="补贴项目挂接 · 中央补贴项目清单" width="860px" @open="loadCentral">
      <el-alert :title="`将为选中的 ${selected.length} 个项目挂接同一个中央项目`" type="info" :closable="false" class="mb8" />
      <el-form inline @submit.prevent class="dlg-filter">
        <el-form-item label="项目名称：">
          <el-input v-model="ctlQuery" clearable style="width:240px" @keyup.enter="loadCentral" />
        </el-form-item>
        <el-form-item><el-button type="primary" @click="loadCentral">查询</el-button></el-form-item>
      </el-form>
      <el-table :data="ctlRows" v-loading="ctlLoading" border stripe size="small" height="360"
        highlight-current-row @current-change="r => ctlPick = r">
        <el-table-column width="44" align="center">
          <template #default="{ row }"><el-radio :model-value="ctlPick?.id" :label="row.id"><span></span></el-radio></template>
        </el-table-column>
        <el-table-column prop="projectCode" label="中央项目编码" width="120" align="center" />
        <el-table-column prop="projectName" label="中央项目名称" min-width="200" show-overflow-tooltip />
        <el-table-column prop="category" label="分类" width="100" align="center" />
        <el-table-column prop="competentDept" label="主管部门" width="160" show-overflow-tooltip />
      </el-table>
      <template #footer>
        <el-button @click="linkVisible = false">取消</el-button>
        <el-button type="primary" @click="doLink">确定挂接</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CirclePlus, Link, Remove } from '@element-plus/icons-vue'
import { projectApi } from '../../api/system'

const query = reactive({ projectCode: '', projectName: '' })
const rows = ref([]); const loading = ref(false); const selected = ref([])
const page = reactive({ pageNum: 1, pageSize: 10, total: 0 })

const isSelf = (r) => typeof r.projectLevel === 'string' && r.projectLevel.endsWith('_SELF')

onMounted(reload)

async function reload() {
  loading.value = true
  try {
    const res = await projectApi.page({
      pageNum: page.pageNum, pageSize: page.pageSize, tab: 'all', included: 1,
      projectCode: query.projectCode || undefined, projectName: query.projectName || undefined
    })
    rows.value = res?.records || []
    page.total = Number(res?.total) || 0
  } finally { loading.value = false }
}

function ensureSel() {
  if (!selected.value.length) { ElMessage.warning('请先勾选项目'); return false }
  return true
}

// ---- 纳入 ----
const incVisible = ref(false); const incRows = ref([]); const incSel = ref([])
const incLoading = ref(false); const incQuery = ref('')
function openInclude() { incQuery.value = ''; incVisible.value = true }
async function loadIncludable() {
  incLoading.value = true
  try { incRows.value = (await projectApi.includable({ projectName: incQuery.value || undefined })) || [] }
  finally { incLoading.value = false }
}
async function doInclude() {
  if (!incSel.value.length) { ElMessage.warning('请勾选要纳入的项目'); return }
  await projectApi.includeBatch(incSel.value.map(r => r.id))
  ElMessage.success('纳入成功'); incVisible.value = false; reload()
}

// ---- 挂接 ----
const linkVisible = ref(false); const ctlRows = ref([]); const ctlPick = ref(null)
const ctlLoading = ref(false); const ctlQuery = ref('')
function openLink() {
  if (!ensureSel()) return
  ctlQuery.value = ''; ctlPick.value = null; linkVisible.value = true
}
async function loadCentral() {
  ctlLoading.value = true
  try { ctlRows.value = (await projectApi.central({ projectName: ctlQuery.value || undefined })) || [] }
  finally { ctlLoading.value = false }
}
async function doLink() {
  if (!ctlPick.value) { ElMessage.warning('请选择一个中央项目'); return }
  await projectApi.linkBatch(selected.value.map(r => r.id), ctlPick.value.projectCode, ctlPick.value.projectName)
  ElMessage.success('挂接成功'); linkVisible.value = false; reload()
}

// ---- 取消挂接 ----
async function doUnlink() {
  if (!ensureSel()) return
  const has = selected.value.filter(r => r.catalogCode)
  if (!has.length) { ElMessage.warning('选中项目均未挂接'); return }
  await ElMessageBox.confirm(`确定取消选中的 ${has.length} 个项目的挂接？`, '取消挂接', { type: 'warning' })
  await projectApi.unlinkBatch(has.map(r => r.id))
  ElMessage.success('已取消挂接'); reload()
}
</script>

<style scoped>
.bar :deep(.el-card__body) { padding: 12px 16px; display: flex; flex-wrap: wrap; gap: 8px; }
.filter { margin: 12px 0; }
.filter :deep(.el-card__body) { padding: 16px 16px 0; }
.dlg-filter { margin-bottom: 8px; }
.mb8 { margin-bottom: 8px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
</style>
