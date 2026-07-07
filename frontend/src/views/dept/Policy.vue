<template>
  <div class="policy">
    <h2 class="page-title">政策基础录入</h2>

    <el-card shadow="never" class="bar">
      <el-button type="primary" :icon="Plus" @click="openForm('new')">新增</el-button>
      <el-button :icon="Edit" @click="openForm('edit')">修改</el-button>
      <el-button :icon="Delete" @click="doDelete">删除</el-button>
      <el-button :icon="View" @click="openForm('view')">查看详情</el-button>
      <el-button :icon="CircleClose" @click="doDiscard">废止</el-button>
    </el-card>

    <el-card shadow="never" class="filter">
      <el-form inline @submit.prevent>
        <el-form-item label="政策文号："><el-input v-model="q.policyNo" clearable style="width:260px" @keyup.enter="reload" /></el-form-item>
        <el-form-item label="政策标题："><el-input v-model="q.title" clearable style="width:300px" @keyup.enter="reload" /></el-form-item>
        <el-form-item><el-button type="primary" round @click="reload">查询</el-button></el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" border stripe size="default" @selection-change="s => selected = s">
        <el-table-column type="selection" width="44" />
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="policyNo" label="政策文号" width="180" show-overflow-tooltip />
        <el-table-column prop="title" label="政策标题" min-width="240" show-overflow-tooltip />
        <el-table-column prop="shortName" label="政策简称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="competentDept" label="政策(主管)部门" width="180" show-overflow-tooltip />
        <el-table-column label="政策级次" width="100" align="center">
          <template #default="{ row }">{{ LEVEL[row.policyLevel] || row.policyLevel }}</template>
        </el-table-column>
        <el-table-column prop="publishDate" label="发文日期" width="120" align="center" />
        <el-table-column prop="endDate" label="政策结束日期" width="130" align="center" />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === '2' ? 'info' : 'success'">{{ STATUS[row.status] || '正常' }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="page.pageNum" v-model:page-size="page.pageSize" :total="page.total"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next" background
          @size-change="reload" @current-change="reload" />
      </div>
    </el-card>

    <!-- 新增 / 修改 / 查看 -->
    <el-dialog v-model="formVisible" :title="dlgTitle" width="980px" top="5vh" destroy-on-close @closed="onClosed">
      <el-form :model="form" label-width="120px" :disabled="mode === 'view'">
        <div class="form-grid">
          <el-form-item label="政策文号" required><el-input v-model="form.policyNo" /></el-form-item>
          <el-form-item label="政策标题" required><el-input v-model="form.title" /></el-form-item>
          <el-form-item label="政策信息级次" required>
            <el-select v-model="form.policyLevel" style="width:100%">
              <el-option v-for="(v, k) in LEVEL" :key="k" :label="`${k}-${v}`" :value="k" />
            </el-select>
          </el-form-item>
          <el-form-item label="年度" required>
            <el-select v-model="form.policyYear" style="width:100%">
              <el-option v-for="y in years" :key="y" :label="y" :value="y" />
            </el-select>
          </el-form-item>
          <el-form-item label="发文日期" required>
            <el-date-picker v-model="form.publishDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
          </el-form-item>
          <el-form-item label="政策结束日期" required>
            <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
          </el-form-item>
          <el-form-item label="政策(主管)部门" required>
            <el-select v-model="form.competentDept" filterable allow-create default-first-option clearable
              placeholder="请选择主管部门" style="width:100%">
              <el-option v-for="d in depts" :key="d.guid" :label="`${d.code}-${d.name}`" :value="d.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="简称" required><el-input v-model="form.shortName" /></el-form-item>
          <el-form-item label="政策状态">
            <el-select v-model="form.status" style="width:100%">
              <el-option v-for="(v, k) in STATUS" :key="k" :label="`${k}-${v}`" :value="k" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="政策内容">
          <div class="editor-wrap">
            <Toolbar v-if="mode !== 'view'" :editor="editorRef" :defaultConfig="toolbarConfig" mode="default" />
            <Editor v-model="form.content" :defaultConfig="editorConfig" mode="default" class="editor-body" @onCreated="onEditorCreated" />
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">{{ mode === 'view' ? '关闭' : '取消' }}</el-button>
        <el-button v-if="mode !== 'view'" type="primary" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, shallowRef, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, View, CircleClose } from '@element-plus/icons-vue'
import '@wangeditor/editor/dist/css/style.css'
import { Editor, Toolbar } from '@wangeditor/editor-for-vue'
import { policyApi, agencyApi } from '../../api/system'

// 富文本编辑器
const editorRef = shallowRef()
const toolbarConfig = {}
const editorConfig = { placeholder: '请输入政策正文内容…' }
function onEditorCreated(editor) {
  editorRef.value = editor
  if (mode.value === 'view') editor.disable()
}
function onClosed() { editorRef.value && editorRef.value.destroy(); editorRef.value = null }
onBeforeUnmount(() => { editorRef.value && editorRef.value.destroy() })

const LEVEL = { '1': '中央', '2': '省级', '3': '市级', '4': '县级' }
const STATUS = { '1': '正常', '2': '废止' }
const thisYear = new Date().getFullYear()
const years = Array.from({ length: 8 }, (_, i) => String(thisYear + 1 - i))

const rows = ref([]); const loading = ref(false); const selected = ref([])
const q = reactive({ policyNo: '', title: '' })
const page = reactive({ pageNum: 1, pageSize: 10, total: 0 })
const depts = ref([])  // 主管部门下拉=机构子单位(level2，code 从101001起)

onMounted(async () => {
  depts.value = (await agencyApi.list({ level: 2 })) || []
  reload()
})

async function reload() {
  loading.value = true
  try {
    const res = await policyApi.page({ pageNum: page.pageNum, pageSize: page.pageSize, policyNo: q.policyNo || undefined, title: q.title || undefined })
    rows.value = res?.records || []
    page.total = Number(res?.total) || 0
  } finally { loading.value = false }
}

// ---- 表单 ----
const formVisible = ref(false); const mode = ref('new')
const dlgTitle = computed(() => ({ new: '政策信息新增', edit: '政策信息修改', view: '政策信息详情' }[mode.value]))
const blank = () => ({ id: null, policyNo: '', title: '', policyLevel: '1', policyYear: String(thisYear), publishDate: '', endDate: '', competentDept: '', shortName: '', status: '1', content: '' })
const form = reactive(blank())

function pickOne() {
  if (selected.value.length !== 1) { ElMessage.warning('请勾选一条政策'); return null }
  return selected.value[0]
}
function openForm(m) {
  mode.value = m
  if (m === 'new') { Object.assign(form, blank()) }
  else {
    const r = pickOne(); if (!r) return
    Object.assign(form, blank(), r)
  }
  formVisible.value = true
}
async function onSave() {
  if (!form.policyNo || !form.title) { ElMessage.warning('政策文号、标题必填'); return }
  await policyApi.save({ ...form })
  ElMessage.success('保存成功'); formVisible.value = false; reload()
}
async function doDelete() {
  if (!selected.value.length) { ElMessage.warning('请勾选要删除的政策'); return }
  await ElMessageBox.confirm(`确定删除选中的 ${selected.value.length} 条政策？`, '删除', { type: 'warning' })
  for (const r of selected.value) await policyApi.delete(r.id)
  ElMessage.success('已删除'); reload()
}
async function doDiscard() {
  const r = pickOne(); if (!r) return
  if (r.status === '2') { ElMessage.info('该政策已是废止状态'); return }
  await ElMessageBox.confirm(`确定废止政策「${r.title}」？`, '废止', { type: 'warning' })
  await policyApi.discard(r.id)
  ElMessage.success('已废止'); reload()
}
</script>

<style scoped>
.bar :deep(.el-card__body) { padding: 12px 16px; display: flex; gap: 8px; flex-wrap: wrap; }
.filter { margin: 12px 0; }
.filter :deep(.el-card__body) { padding: 16px 16px 0; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.form-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 0 16px; }
.editor-wrap { width: 100%; border: 1px solid var(--el-border-color); border-radius: 6px; overflow: hidden; }
.editor-wrap :deep(.w-e-toolbar) { border-bottom: 1px solid var(--el-border-color); }
.editor-body { height: 320px; overflow-y: auto; }
</style>
