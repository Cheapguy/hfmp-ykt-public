<template>
  <div ref="rootEl" class="crud-table">
    <el-card v-if="searchFields.length" class="crud-search" shadow="never">
      <el-form :model="searchForm" :inline="searchCols === 0" :label-width="searchCols > 0 ? '130px' : ''"
        :class="{ 'search-grid': searchCols > 0 }" :style="searchCols > 0 ? { '--search-cols': searchCols } : null" @submit.prevent>
        <el-form-item v-for="f in visibleSearchFields" :key="f.prop" :label="f.label">
          <el-input v-if="!f.type || f.type === 'input'" v-model="searchForm[f.prop]" :placeholder="f.placeholder || `请输入${f.label}`" clearable style="width: 180px" @keyup.enter="handleSearch" />
          <el-select v-else-if="f.type === 'select'" v-model="searchForm[f.prop]" :placeholder="f.placeholder || `请选择${f.label}`" clearable filterable style="width: 180px">
            <el-option v-for="o in (f.options || [])" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item class="search-actions" label=" ">
          <el-button type="primary" :icon="SearchIcon" @click="handleSearch">查询</el-button>
          <el-button :icon="RefreshIcon" @click="handleReset">重置</el-button>
          <el-button v-if="collapsibleSearch && hasAdvanced" link type="primary" @click="searchCollapsed = !searchCollapsed">
            显示/隐藏<el-icon class="el-icon--right"><component :is="searchCollapsed ? ArrowDownIcon : ArrowUpIcon" /></el-icon>
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="crud-body" shadow="never">
      <div class="crud-toolbar">
        <div class="left">
          <slot name="toolbar-left">
            <el-button v-if="enableCreate" type="primary" :icon="PlusIcon" @click="openForm()">新增</el-button>
          </slot>
        </div>
        <div class="right">
          <slot name="toolbar-right" />
          <el-tooltip content="刷新"><el-button :icon="RefreshIcon" circle @click="loadData" /></el-tooltip>
        </div>
      </div>

      <!-- 数据列表 标题栏（含 列表内部检索放大镜 + 表单/列表切换）-->
      <div v-if="listTitle" class="crud-listbar">
        <span class="lb-title"><el-icon><TicketsIcon /></el-icon>{{ listTitle }}</span>
        <span class="lb-tools">
          <el-popover :visible="inlineVisible" placement="bottom-end" :width="260" trigger="manual" popper-class="inline-search-pop">
            <template #reference>
              <el-icon class="lb-ico" title="列表内部检索" @click="inlineVisible = !inlineVisible"><SearchIcon /></el-icon>
            </template>
            <div class="inline-search">
              <div class="is-head"><span>列表内部检索</span><el-icon class="is-close" @click="inlineVisible = false"><CloseIcon /></el-icon></div>
              <el-input v-model="inlineKeyword" size="small" @keyup.enter="inlineFind(1)" />
              <div class="is-btns">
                <el-button size="small" type="primary" @click="inlineFind(0)">查找</el-button>
                <el-button size="small" @click="inlineFind(-1)">上一个</el-button>
                <el-button size="small" @click="inlineFind(1)">下一个</el-button>
              </div>
              <div v-if="inlineHint" class="is-hint">{{ inlineHint }}</div>
            </div>
          </el-popover>
          <el-icon class="lb-ico" :title="viewMode === 'table' ? '切换为表单视图' : '切换为列表视图'" @click="toggleView">
            <component :is="viewMode === 'table' ? GridIcon : MenuIcon" />
          </el-icon>
        </span>
      </div>

      <!-- 表单（卡片）视图 -->
      <div v-if="viewMode === 'card'" v-loading="loading" class="crud-cards">
        <div v-for="(row, i) in rows" :key="row[idKey]" class="card-item" :class="{ 'card-sel': isSelected(row) }">
          <div class="card-head">
            <span>序号:{{ (page.pageNum - 1) * page.pageSize + i + 1 }}</span>
            <span v-if="selectable">复选:<el-checkbox :model-value="isSelected(row)" @change="v => toggleRow(row, v)" /></span>
          </div>
          <div class="card-grid">
            <div v-for="f in cardFields" :key="f.prop" class="card-cell">
              <span class="cc-lab">{{ f.label }}:</span>
              <span class="cc-val">{{ f.fmt ? f.fmt(row[f.prop], row) : (row[f.prop] ?? '') }}</span>
            </div>
          </div>
        </div>
        <el-empty v-if="!loading && !rows.length" description="暂无数据" />
      </div>

      <el-table v-show="viewMode === 'table'" ref="tableRef" v-loading="loading" :data="rows" border stripe size="default" :row-key="idKey" :row-class-name="rowClassName" @row-click="r => $emit('row-click', r)" @selection-change="handleSelectionChange">
        <el-table-column v-if="selectable" type="selection" width="48" align="center" :reserve-selection="true" />
        <el-table-column v-if="showIndex" type="index" label="#" width="55" align="center" />
        <el-table-column v-for="c in columns" :key="c.prop" :prop="c.prop" :label="c.label" :width="c.width" :min-width="c.minWidth" :align="c.align || 'left'" :show-overflow-tooltip="c.tooltip !== false">
          <template #default="{ row }">
            <slot :name="`column-${c.prop}`" :row="row" :value="row[c.prop]">
              <span v-if="c.formatter">{{ c.formatter(row[c.prop], row) }}</span>
              <span v-else>{{ row[c.prop] ?? '-' }}</span>
            </slot>
          </template>
        </el-table-column>
        <el-table-column v-if="enableEdit || enableDelete || $slots['row-actions']" label="操作" :width="actionWidth" align="center" fixed="right">
          <template #default="{ row }">
            <slot name="row-actions" :row="row">
              <el-button v-if="enableEdit" type="primary" link @click.stop="openForm(row)">编辑</el-button>
              <el-button v-if="enableDelete" type="danger" link @click.stop="handleDelete(row)">删除</el-button>
            </slot>
          </template>
        </el-table-column>
      </el-table>

      <div class="crud-pager">
        <el-pagination v-model:current-page="page.pageNum" v-model:page-size="page.pageSize" :total="page.total" :page-sizes="pageSizes" layout="total, sizes, prev, pager, next, jumper" background @size-change="loadData" @current-change="loadData" />
      </div>
    </el-card>

    <el-dialog v-model="formVisible" :title="formTitle" :width="formWidth" :close-on-click-modal="false" @closed="handleFormClosed">
      <el-form ref="formRef" :model="formData" :rules="formRules" :label-width="labelWidth">
        <el-row :gutter="16">
          <el-col v-for="f in formFields" :key="f.prop" :span="f.span || 24">
            <el-form-item :label="f.label" :prop="f.prop">
              <slot :name="`form-${f.prop}`" :form="formData" :field="f">
                <el-input v-if="!f.type || f.type === 'input'" v-model="formData[f.prop]" :placeholder="f.placeholder || `请输入${f.label}`" :maxlength="f.maxlength" :disabled="readonly(f)" />
                <el-input v-else-if="f.type === 'textarea'" v-model="formData[f.prop]" type="textarea" :rows="f.rows || 3" :maxlength="f.maxlength || 500" :disabled="readonly(f)" />
                <el-input-number v-else-if="f.type === 'number'" v-model="formData[f.prop]" :min="f.min ?? 0" :max="f.max ?? 99999999" :step="f.step || 1" :disabled="readonly(f)" style="width: 100%" />
                <el-select v-else-if="f.type === 'select'" v-model="formData[f.prop]" :placeholder="f.placeholder || `请选择${f.label}`" clearable :disabled="readonly(f)" style="width: 100%">
                  <el-option v-for="o in (f.options || [])" :key="o.value" :label="o.label" :value="o.value" />
                </el-select>
                <el-switch v-else-if="f.type === 'switch'" v-model="formData[f.prop]" :active-value="f.activeValue ?? 1" :inactive-value="f.inactiveValue ?? 0" :disabled="readonly(f)" />
              </slot>
              <div v-if="f.tip" class="form-tip">{{ f.tip }}</div>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, nextTick, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search as SearchIcon, Refresh as RefreshIcon, Plus as PlusIcon,
  ArrowDown as ArrowDownIcon, ArrowUp as ArrowUpIcon, Tickets as TicketsIcon,
  Grid as GridIcon, Menu as MenuIcon, Close as CloseIcon } from '@element-plus/icons-vue'

const props = defineProps({
  api: { type: Object, required: true },
  columns: { type: Array, default: () => [] },
  searchFields: { type: Array, default: () => [] },
  formFields: { type: Array, default: () => [] },
  formRules: { type: Object, default: () => ({}) },
  extraQuery: { type: Object, default: () => ({}) },
  defaultForm: { type: Object, default: () => ({}) },
  idKey: { type: String, default: 'id' },
  formTitle: { type: String, default: '' },
  formTitleNoun: { type: String, default: '' },   // 设了则标题为「新增{noun}」/「修改{noun}」
  formWidth: { type: String, default: '600px' },
  labelWidth: { type: String, default: '120px' },
  actionWidth: { type: [String, Number], default: 150 },
  showIndex: { type: Boolean, default: true },
  enableCreate: { type: Boolean, default: true },
  enableEdit: { type: Boolean, default: true },
  enableDelete: { type: Boolean, default: true },
  readonlyOnEdit: { type: Array, default: () => [] },
  autoLoad: { type: Boolean, default: true },
  selectable: { type: Boolean, default: false },
  pageSize: { type: Number, default: 10 },
  pageSizes: { type: Array, default: () => [10, 20, 50, 100] },
  collapsibleSearch: { type: Boolean, default: false },
  searchCols: { type: Number, default: 0 },
  listTitle: { type: String, default: '' },
  enableViewToggle: { type: Boolean, default: false },
  cardFields: { type: Array, default: () => [] }
})

const emit = defineEmits(['row-click', 'after-save', 'after-delete', 'loaded', 'selection-change'])

const loading = ref(false)
const rows = ref([])
const rootEl = ref()
const tableRef = ref()
const selection = ref([])
const page = reactive({ pageNum: 1, pageSize: props.pageSize, total: 0 })
const searchForm = reactive({})
props.searchFields.forEach(f => { searchForm[f.prop] = '' })

/* ===== 折叠搜索 ===== */
const searchCollapsed = ref(true)
const hasAdvanced = computed(() => props.searchFields.some(f => f.advanced))
const visibleSearchFields = computed(() =>
  props.collapsibleSearch && searchCollapsed.value ? props.searchFields.filter(f => !f.advanced) : props.searchFields)

/* ===== 表单/列表视图切换 ===== */
const viewMode = ref('table')
function toggleView() {
  viewMode.value = viewMode.value === 'table' ? 'card' : 'table'
  if (viewMode.value === 'table') nextTick(syncTableSelection)
}

/* ===== 列表内部检索 ===== */
const inlineVisible = ref(false)
const inlineKeyword = ref('')
const inlineHint = ref('')
let inlineMatches = []
let inlineCursor = -1
function inlineFind(dir) {
  const kw = inlineKeyword.value.trim()
  const root = rootEl.value
  if (!root) return
  root.querySelectorAll('.inline-hit, .inline-current').forEach(n => n.classList.remove('inline-hit', 'inline-current'))
  if (!kw) { inlineHint.value = '请输入检索内容'; inlineMatches = []; inlineCursor = -1; return }
  // 单元格级匹配：命中单元格标黄，当前定位单元格标蓝
  const sel = viewMode.value === 'table' ? '.el-table__body-wrapper tbody td' : '.crud-cards .card-cell'
  const cells = Array.from(root.querySelectorAll(sel))
  inlineMatches = cells.filter(c => (c.innerText || '').includes(kw))
  if (!inlineMatches.length) { inlineHint.value = '未找到匹配项'; inlineCursor = -1; return }
  inlineMatches.forEach(c => c.classList.add('inline-hit'))
  if (dir === 0 || inlineCursor < 0) inlineCursor = 0
  else inlineCursor = (inlineCursor + dir + inlineMatches.length) % inlineMatches.length
  const cur = inlineMatches[inlineCursor]
  cur.classList.remove('inline-hit'); cur.classList.add('inline-current')
  cur.scrollIntoView({ block: 'center', inline: 'center', behavior: 'smooth' })
  inlineHint.value = `第 ${inlineCursor + 1} / ${inlineMatches.length} 处`
}
function inlineClear() {
  rootEl.value?.querySelectorAll('.inline-hit, .inline-current').forEach(n => n.classList.remove('inline-hit', 'inline-current'))
  inlineMatches = []; inlineCursor = -1; inlineHint.value = ''
}
// 关闭检索弹窗时清掉高亮效果
watch(inlineVisible, v => { if (!v) inlineClear() })

const formVisible = ref(false)
const formData = ref({})
const editing = ref(false)
const saving = ref(false)
const formRef = ref()
const formTitle = computed(() => props.formTitle
  || (props.formTitleNoun ? (editing.value ? '修改' : '新增') + props.formTitleNoun : (editing.value ? '编辑' : '新增')))

function readonly(f) {
  if (f.disabled) return true
  return editing.value && props.readonlyOnEdit.includes(f.prop)
}

async function loadData() {
  loading.value = true
  try {
    const params = { pageNum: page.pageNum, pageSize: page.pageSize, ...props.extraQuery, ...trimParams(searchForm) }
    const res = await props.api.page(params)
    rows.value = res?.records || []
    page.total = Number(res?.total) || 0  // 后端 Long→String 全局序列化, el-pagination 的 total 必须是 number 否则整个分页器不渲染
    emit('loaded', { rows: rows.value, total: page.total })
  } catch (e) { /* 拦截器已弹错 */ } finally { loading.value = false }
}
function trimParams(obj) {
  const r = {}
  Object.keys(obj).forEach(k => { const v = obj[k]; if (v !== '' && v !== null && v !== undefined) r[k] = v })
  return r
}
function handleSearch() { page.pageNum = 1; loadData() }
function handleReset() { Object.keys(searchForm).forEach(k => { searchForm[k] = '' }); page.pageNum = 1; loadData() }

function openForm(row) {
  editing.value = !!row
  if (row) formData.value = JSON.parse(JSON.stringify(row))
  else {
    const init = {}
    props.formFields.forEach(f => { init[f.prop] = f.default ?? null })
    formData.value = { ...init, ...props.defaultForm }
  }
  formVisible.value = true
}
function handleFormClosed() { formRef.value?.resetFields?.(); formData.value = {} }

async function handleSave() {
  await formRef.value.validate()
  saving.value = true
  try {
    await props.api.save(formData.value)
    ElMessage.success(editing.value ? '更新成功' : '新增成功')
    formVisible.value = false
    emit('after-save', formData.value)
    loadData()
  } finally { saving.value = false }
}
async function handleDelete(row) {
  await ElMessageBox.confirm('确定删除该记录？此操作不可恢复', '删除确认', { type: 'warning' })
  await props.api.delete(row[props.idKey])
  ElMessage.success('删除成功')
  emit('after-delete', row)
  if (rows.value.length === 1 && page.pageNum > 1) page.pageNum -= 1
  loadData()
}

/* ===== 选中（表格 / 卡片两视图共用一套 id 集合）===== */
const selectedIds = ref(new Set())
const idStr = (r) => String(r?.[props.idKey])
const isSelected = (r) => selectedIds.value.has(idStr(r))
function emitSelection() {
  selection.value = rows.value.filter(isSelected)
  emit('selection-change', selection.value)
}
function handleSelectionChange(picked) {            // el-table 触发：表格视图为准
  selectedIds.value = new Set(picked.map(idStr))
  emitSelection()
}
function toggleRow(row, val) {                       // 卡片视图勾选
  const s = new Set(selectedIds.value)
  if (val) s.add(idStr(row)); else s.delete(idStr(row))
  selectedIds.value = s
  emitSelection()
}
function syncTableSelection() {                      // 卡片→表格切换时回填勾选
  const t = tableRef.value; if (!t) return
  rows.value.forEach(r => t.toggleRowSelection(r, isSelected(r)))
}
function clearSelection() {
  selectedIds.value = new Set()
  tableRef.value?.clearSelection?.()
  emitSelection()
}
function rowClassName({ row }) { return isSelected(row) ? 'crud-row-selected' : '' }

watch(() => props.extraQuery, () => { page.pageNum = 1; loadData() }, { deep: true })
onMounted(() => { if (props.autoLoad) loadData() })
defineExpose({ reload: loadData, openForm, handleDelete, getSelection: () => selection.value, clearSelection })
</script>

<style scoped>
.crud-table { display: flex; flex-direction: column; gap: 12px; }
.crud-search :deep(.el-card__body) { padding: 16px 20px 0; }
.crud-search :deep(.el-form-item) { margin-bottom: 16px; }

/* 网格对齐搜索区：固定标签宽 + 等宽列，输入框纵向对齐 */
.crud-search :deep(.el-form.search-grid) { display: grid; grid-template-columns: repeat(var(--search-cols), minmax(0, 1fr)); gap: 0 16px; }
.crud-search :deep(.search-grid .el-form-item) { margin-right: 0; }
.crud-search :deep(.search-grid .el-form-item__content) { width: auto; }
.crud-search :deep(.search-grid .el-input),
.crud-search :deep(.search-grid .el-select) { width: 100% !important; }
/* 操作按钮整行横排 */
.crud-search :deep(.search-grid .search-actions) { grid-column: 1 / -1; }
.crud-search :deep(.search-grid .search-actions .el-form-item__content) { gap: 8px; flex-wrap: nowrap; }
.crud-toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.crud-toolbar .right { display: flex; gap: 8px; }
.crud-pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.form-tip { color: #909399; font-size: 12px; line-height: 1.4; margin-top: 2px; }

/* 数据列表 标题栏 */
.crud-listbar { display: flex; justify-content: space-between; align-items: center;
  background: #eef5fe; border: 1px solid #d4e4fb; border-bottom: none; padding: 6px 12px; }
.crud-listbar .lb-title { display: flex; align-items: center; gap: 6px; font-weight: 600; color: #1f5fbf; font-size: 14px; }
.crud-listbar .lb-tools { display: flex; align-items: center; gap: 14px; }
.crud-listbar .lb-ico { cursor: pointer; color: #1f5fbf; font-size: 18px; }
.crud-listbar .lb-ico:hover { color: #0a3d8f; }

/* 列表内部检索弹窗 */
.inline-search .is-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; color: #303133; font-size: 13px; }
.inline-search .is-close { cursor: pointer; color: #909399; }
.inline-search .is-btns { display: flex; gap: 6px; margin-top: 8px; }
.inline-search .is-hint { margin-top: 6px; color: #909399; font-size: 12px; }

/* 行内检索命中高亮：当前单元格标蓝、其余相同命中标黄 */
:deep(.el-table__body td.inline-hit) { background: #fff34d !important; }
:deep(.el-table__body td.inline-current) { background: var(--el-color-primary-light-5) !important; box-shadow: inset 0 0 0 1px var(--el-color-primary); }
.crud-cards .card-cell.inline-hit { background: #fff34d; }
.crud-cards .card-cell.inline-current { background: var(--el-color-primary-light-5); }

/* 命中选中行高亮 */
:deep(.el-table__body tr.crud-row-selected > td) { background: var(--el-color-primary-light-9); }

/* 卡片（表单）视图 */
.crud-cards { display: flex; flex-direction: column; gap: 10px; }
.card-item { border: 1px solid #e4e7ed; border-radius: 4px; }
.card-item.card-sel { background: var(--el-color-primary-light-9); border-color: var(--el-color-primary-light-7); }
.card-head { display: flex; gap: 24px; align-items: center; padding: 6px 12px; background: #f5f7fa; border-bottom: 1px solid #e4e7ed; font-size: 13px; }
.card-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px 24px; padding: 12px 16px; }
.card-cell { display: flex; font-size: 13px; line-height: 1.8; }
.card-cell .cc-lab { color: #606266; white-space: nowrap; }
.card-cell .cc-val { color: #303133; word-break: break-all; }
</style>
