<template>
  <div>
    <h2 class="page-title">政策关联项目</h2>

    <div class="layout">
      <!-- 左：项目树 -->
      <el-card shadow="never" class="tree-card">
        <el-input v-model="treeFilter" placeholder="搜索项目名称" clearable :prefix-icon="Search" class="mb8" />
        <el-tree
          ref="treeRef"
          :data="treeData"
          node-key="key"
          :props="{ label: 'label', children: 'children' }"
          :filter-node-method="filterNode"
          :expand-on-click-node="false"
          default-expand-all
          highlight-current
          @node-click="onNodeClick"
        >
          <template #default="{ data }">
            <span :class="{ 'is-project': data.raw }">
              <el-icon v-if="data.raw"><Document /></el-icon>
              <el-icon v-else><Folder /></el-icon>
              {{ data.label }}
            </span>
          </template>
        </el-tree>
      </el-card>

      <!-- 右：已关联政策 -->
      <el-card shadow="never" class="main-card">
        <div class="bar">
          <el-button type="primary" :icon="Link" :disabled="!current" @click="openDialog">关联</el-button>
          <el-button :icon="Remove" :disabled="!current" @click="doUnlink">取消关联</el-button>
          <span class="cur-tip">
            当前项目：<b v-if="current">{{ current.projectCode }} - {{ current.projectName }}</b>
            <span v-else class="muted">请在左侧选择一个项目</span>
          </span>
        </div>

        <el-table ref="linkedRef" v-loading="linkedLoading" :data="linkedRows" border stripe size="default"
          height="calc(100vh - 280px)" @selection-change="s => linkedSel = s">
          <el-table-column type="selection" width="44" />
          <el-table-column type="index" label="序号" width="64" align="center" />
          <el-table-column prop="policyNo" label="政策文号" width="220" show-overflow-tooltip />
          <el-table-column prop="title" label="政策标题" min-width="320" show-overflow-tooltip />
          <el-table-column label="政策状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === '2' ? 'info' : 'success'">{{ statusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="current && !linkedRows.length && !linkedLoading" description="该项目暂未关联政策" />
      </el-card>
    </div>

    <!-- 关联弹窗：全部政策，勾选未关联的保存 -->
    <el-dialog v-model="dlgVisible" title="政策对应项目 · 选择政策" width="1100px" top="6vh" @open="loadCandidates">
      <el-form inline @submit.prevent class="dlg-filter">
        <el-form-item label="政策文号：">
          <el-input v-model="dq.policyNo" clearable style="width:260px" @keyup.enter="loadCandidates" />
        </el-form-item>
        <el-form-item label="政策标题：">
          <el-input v-model="dq.title" clearable style="width:300px" @keyup.enter="loadCandidates" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" round @click="loadCandidates">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table ref="candRef" v-loading="candLoading" :data="candRows" border stripe size="small" height="480"
        :row-key="r => r.id" @selection-change="s => candSel = s">
        <el-table-column type="selection" width="44" :selectable="row => !linkedSet.has(String(row.id))" />
        <el-table-column type="index" label="序号" width="56" align="center" />
        <el-table-column prop="policyNo" label="政策文号" width="170" show-overflow-tooltip />
        <el-table-column prop="title" label="政策标题" min-width="240" show-overflow-tooltip />
        <el-table-column prop="shortName" label="政策简称" width="140" show-overflow-tooltip />
        <el-table-column prop="competentDept" label="政策(主管)部门" width="150" show-overflow-tooltip />
        <el-table-column prop="publishDate" label="发文日期" width="110" align="center" />
        <el-table-column prop="endDate" label="结束日期" width="110" align="center" />
        <el-table-column prop="policyYear" label="年度" width="72" align="center" />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="linkedSet.has(String(row.id))" type="warning" size="small">已关联</el-tag>
            <el-tag v-else :type="row.status === '2' ? 'info' : 'success'" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination v-model:current-page="dpage.pageNum" v-model:page-size="dpage.pageSize" :total="dpage.total"
          :page-sizes="[50,100,200,999]" layout="total, sizes, prev, pager, next, jumper" background
          @size-change="loadCandidates" @current-change="loadCandidates" />
      </div>

      <template #footer>
        <el-button @click="dlgVisible = false">取消</el-button>
        <el-button type="primary" @click="doLink">保存关联（{{ candSel.length }}）</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Link, Remove, Search, Document, Folder } from '@element-plus/icons-vue'
import { projectPolicyApi } from '../../api/system'

// ---- 项目树 ----
const treeRef = ref(null)
const treeFilter = ref('')
const projects = ref([])
const current = ref(null)

const treeData = computed(() => [{
  key: 'root', label: '补贴项目', children: projects.value.map(p => ({
    key: 'p' + p.id, label: `${p.projectCode || '未编码'} - ${p.projectName}`, raw: p
  }))
}])

watch(treeFilter, v => treeRef.value?.filter(v))
function filterNode(value, data) {
  if (!value) return true
  return data.label.includes(value)
}
function onNodeClick(data) {
  if (!data.raw) return            // 点根节点不动作
  current.value = data.raw
  loadLinked()
}

async function loadProjects() {
  projects.value = (await projectPolicyApi.projects()) || []
}

// ---- 已关联政策 ----
const linkedRef = ref(null)
const linkedRows = ref([])
const linkedSel = ref([])
const linkedLoading = ref(false)
const linkedSet = computed(() => new Set(linkedRows.value.map(r => String(r.id))))

async function loadLinked() {
  if (!current.value) return
  linkedLoading.value = true
  try { linkedRows.value = (await projectPolicyApi.linked(current.value.id)) || [] }
  finally { linkedLoading.value = false }
}

async function doUnlink() {
  if (!current.value) return
  if (!linkedSel.value.length) { ElMessage.warning('请勾选要取消关联的政策'); return }
  await ElMessageBox.confirm(`确定取消选中的 ${linkedSel.value.length} 条政策关联？`, '取消关联', { type: 'warning' })
  await projectPolicyApi.unlink(current.value.id, linkedSel.value.map(r => r.id))
  ElMessage.success('已取消关联'); loadLinked()
}

// ---- 关联弹窗 ----
const dlgVisible = ref(false)
const candRef = ref(null)
const candRows = ref([])
const candSel = ref([])
const candLoading = ref(false)
const dq = reactive({ policyNo: '', title: '' })
const dpage = reactive({ pageNum: 1, pageSize: 999, total: 0 })

function openDialog() {
  if (!current.value) return
  dq.policyNo = ''; dq.title = ''; dpage.pageNum = 1
  dlgVisible.value = true
}
async function loadCandidates() {
  candLoading.value = true
  try {
    const res = await projectPolicyApi.candidates({
      pageNum: dpage.pageNum, pageSize: dpage.pageSize,
      policyNo: dq.policyNo || undefined, title: dq.title || undefined
    })
    candRows.value = res?.records || []
    dpage.total = Number(res?.total) || 0
    await nextTick()
    candRef.value?.clearSelection()      // 翻页/查询后清掉旧勾选，避免跨页误提交
  } finally { candLoading.value = false }
}
async function doLink() {
  if (!candSel.value.length) { ElMessage.warning('请勾选要关联的政策'); return }
  const ids = candSel.value.map(r => r.id)
  const res = await projectPolicyApi.link(current.value.id, ids)
  ElMessage.success(`已关联 ${res?.added ?? ids.length} 条政策`)
  dlgVisible.value = false; loadLinked()
}

const statusText = (s) => s === '2' ? '废止' : '正常'

onMounted(loadProjects)
</script>

<style scoped>
.layout { display: flex; gap: 12px; align-items: stretch; }
.tree-card { width: 320px; flex: 0 0 320px; }
.tree-card :deep(.el-card__body) { padding: 12px; }
.main-card { flex: 1; min-width: 0; }
.mb8 { margin-bottom: 8px; }
.bar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.cur-tip { margin-left: 12px; color: #606266; }
.muted { color: #c0c4cc; }
.is-project { display: inline-flex; align-items: center; gap: 4px; }
.dlg-filter { margin-bottom: 8px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
</style>
