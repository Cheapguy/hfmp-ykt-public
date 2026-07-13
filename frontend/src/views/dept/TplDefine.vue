<template>
  <div>
    <h2 class="page-title">发放表定义</h2>

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

      <!-- 右：模板列定义 -->
      <el-card shadow="never" class="main-card">
        <div class="bar">
          <el-button v-if="!isCustom" type="primary" :icon="MagicStick" :disabled="!current" @click="doInit">初始化自定义</el-button>
          <el-button v-if="isCustom" type="primary" :icon="Plus" @click="openForm()">新增列</el-button>
          <el-button :icon="CopyDocument" :disabled="!current" @click="openCopy">从其他项目复制</el-button>
          <el-button v-if="isCustom" :icon="RefreshLeft" @click="doReset">恢复默认</el-button>
          <span class="cur-tip">
            当前项目：<b v-if="current">{{ current.projectCode }} - {{ current.projectName }}</b>
            <span v-else class="muted">请在左侧选择一个项目</span>
            <el-tag v-if="current" :type="isCustom ? 'success' : 'info'" size="small" class="ml8">
              {{ isCustom ? '自定义模板' : '默认模板（只读，初始化后可编辑）' }}
            </el-tag>
          </span>
        </div>

        <el-table v-loading="loading" :data="rows" border stripe size="default" height="calc(100vh - 280px)">
          <el-table-column prop="sortNo" label="排序" width="70" align="center" />
          <el-table-column prop="itemLabel" label="列名（Excel 表头）" min-width="170" show-overflow-tooltip />
          <el-table-column prop="itemKey" label="标识（存储字段）" width="160" show-overflow-tooltip />
          <el-table-column label="数据类型" width="150" align="center">
            <template #default="{ row }">
              <el-tooltip v-if="row.colType === 'enum'" :content="'允许值：' + (row.enumValues || '')" placement="top">
                <el-tag size="small" type="warning">枚举</el-tag>
              </el-tooltip>
              <el-tag v-else size="small" type="info">{{ typeText(row.colType) }}</el-tag>
              <el-tooltip v-if="row.formula" :content="'公式：' + row.formula" placement="top">
                <el-tag size="small" type="success" class="ml4">公式</el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="必填" width="70" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.requiredFlag === 1" size="small" type="danger">必填</el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="绑定明细字段" width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.bindField">{{ bindLabel(row.bindField) }}</span>
              <el-tag v-else size="small" type="warning">自由列 · {{ groupText(row.colGroup) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="固定" width="70" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.fixedFlag === 1" size="small">固定</el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
          <el-table-column label="操作" width="200" align="center" fixed="right">
            <template #default="{ row, $index }">
              <el-button link :icon="Top" :disabled="!isCustom || $index === 0" title="上移" @click="doMove(row, 'up')" />
              <el-button link :icon="Bottom" :disabled="!isCustom || $index === rows.length - 1" title="下移" @click="doMove(row, 'down')" />
              <el-button link type="primary" :disabled="!isCustom" @click="openForm(row)">编辑</el-button>
              <el-button link type="danger" :disabled="!isCustom || row.fixedFlag === 1" @click="doDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <!-- 新增/编辑列弹窗 -->
    <el-dialog v-model="dlgVisible" :title="form.id ? '编辑列' : '新增列'" width="560px">
      <el-alert v-if="form.fixedFlag === 1" type="info" :closable="false" class="mb8"
        title="固定列为送审/发放链路依赖字段：标识、数据类型、绑定、必填不可修改" />
      <el-form ref="formRef" :model="form" :rules="rules" label-width="130px">
        <el-form-item label="列名" prop="itemLabel">
          <el-input v-model="form.itemLabel" maxlength="60" placeholder="Excel 模板表头，如：务工地点" />
        </el-form-item>
        <el-form-item label="标识" prop="itemKey">
          <el-input v-model="form.itemKey" maxlength="20" :disabled="form.fixedFlag === 1"
            placeholder="小写字母开头，英数下划线，如 work_place" />
        </el-form-item>
        <el-form-item label="绑定明细字段">
          <el-select v-model="form.bindField" clearable :disabled="form.fixedFlag === 1"
            placeholder="不绑定（自由列，值存扩展字段）" style="width:100%">
            <el-option v-for="o in bindChoices" :key="o.field" :value="o.field"
              :label="o.label" :disabled="o.taken" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据类型" prop="colType">
          <el-select v-model="form.colType" :disabled="form.fixedFlag === 1 || !!form.bindField" style="width:100%">
            <el-option value="text" label="文本" />
            <el-option value="int" label="整数" />
            <el-option value="decimal" label="金额（两位小数）" />
            <el-option value="date" label="日期" />
            <el-option value="idcard" label="身份证号（18位）" />
            <el-option value="enum" label="枚举（限定允许值）" />
          </el-select>
          <div v-if="form.bindField" class="tip">绑定字段后数据类型由系统字段决定</div>
        </el-form-item>
        <el-form-item v-if="form.colType === 'enum'" label="枚举允许值" prop="enumValues">
          <el-input v-model="form.enumValues" maxlength="400" placeholder="逗号分隔，如：火车,汽车,飞机" />
          <div class="tip">导入时该列的值必须是其中之一，否则整体拒绝导入</div>
        </el-form-item>
        <el-form-item v-if="form.colType === 'int' || form.colType === 'decimal'" label="计算公式">
          <el-input v-model="form.formula" maxlength="200" placeholder="如：standard*months（留空则由导入文件填写）" />
          <div class="tip">
            支持 + - × ÷ 和括号，引用其他数值列的标识；设置后导入时该列由系统按行计算，单元格填写的内容会被忽略。<br />
            可引用：{{ formulaVars || '（无其他数值列，可先新增如“月数”整数列）' }}
          </div>
        </el-form-item>
        <el-form-item v-if="!form.bindField" label="清册显示分组">
          <el-select v-model="form.colGroup" style="width:100%">
            <el-option v-for="(t, k) in GROUPS" :key="k" :value="k" :label="t" />
          </el-select>
          <div class="tip">该列在清册页面挂在哪个大列组下展示（不影响 Excel 模板的列顺序）</div>
        </el-form-item>
        <el-form-item label="导入时必填">
          <el-switch v-model="form.requiredFlag" :active-value="1" :inactive-value="0" :disabled="form.fixedFlag === 1" />
        </el-form-item>
        <el-form-item label="排序号">
          <el-input-number v-model="form.sortNo" :min="1" :max="999" />
          <div class="tip">填已有序号即插入到该位置，原该位置及之后的列自动后移；留空排到最后</div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlgVisible = false">取消</el-button>
        <el-button type="primary" @click="doSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 从其他项目复制 -->
    <el-dialog v-model="copyVisible" title="从其他项目复制模板定义" width="520px">
      <el-form label-width="100px">
        <el-form-item label="源项目">
          <el-select v-model="copyFrom" filterable placeholder="选择已定义模板的项目" style="width:100%">
            <el-option v-for="p in copySources" :key="p.id" :value="String(p.id)"
              :label="`${p.projectCode || '未编码'} - ${p.projectName}`" />
          </el-select>
          <div class="tip">复制其全部列定义（含类型/绑定/枚举允许值）到当前项目「{{ current?.projectName }}」</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="copyVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!copyFrom" @click="doCopy">复 制</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Document, Folder, Plus, MagicStick, RefreshLeft, Top, Bottom, CopyDocument } from '@element-plus/icons-vue'
import { projectPolicyApi, tplApi } from '../../api/system'

// ---- 项目树（复用政策关联项目的项目源，已按县域隔离过滤）----
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
  if (!data.raw) return
  current.value = data.raw
  loadItems()
}
async function loadProjects() {
  projects.value = (await projectPolicyApi.projects()) || []
}

// ---- 列定义 ----
const rows = ref([])
const isCustom = ref(false)
const loading = ref(false)
const bindOptions = ref([])

async function loadItems() {
  if (!current.value) return
  loading.value = true
  try {
    const res = await tplApi.items(current.value.id)
    rows.value = res?.items || []
    isCustom.value = !!res?.custom
  } finally { loading.value = false }
}

const bindMap = computed(() => Object.fromEntries(bindOptions.value.map(o => [o.field, o])))
const bindLabel = f => bindMap.value[f]?.label || f
const typeText = t => ({ text: '文本', int: '整数', decimal: '金额', date: '日期', idcard: '身份证' }[t] || t)
const GROUPS = { HOLDER: '户主信息', PAYEE: '收款人信息', BENEFICIARY: '补贴对象信息', GRANT: '发放信息', EXT: '扩展信息' }
const groupText = g => GROUPS[g] || '扩展信息'

// 弹窗里可选的绑定项：被其他列占用的置灰
const bindChoices = computed(() => bindOptions.value.map(o => ({
  ...o,
  taken: rows.value.some(r => r.bindField === o.field && r.id !== form.id)
})))

async function doInit() {
  if (!current.value) return
  await ElMessageBox.confirm('将以默认 18 列初始化该项目的模板定义，之后可自由增删改列。确认？', '初始化自定义', { type: 'info' })
  await tplApi.init(current.value.id)
  ElMessage.success('已初始化，可开始编辑')
  loadItems()
}
async function doReset() {
  await ElMessageBox.confirm('将删除该项目全部自定义列，恢复为默认 18 列模板。确认？', '恢复默认', { type: 'warning' })
  await tplApi.reset(current.value.id)
  ElMessage.success('已恢复默认模板')
  loadItems()
}
async function doMove(row, dir) {
  await tplApi.move(row.id, dir)
  loadItems()
}
async function doDelete(row) {
  await ElMessageBox.confirm(`确定删除列「${row.itemLabel}」？删除后新导出的模板将不再包含此列。`, '删除列', { type: 'warning' })
  await tplApi.deleteItem(row.id)
  ElMessage.success('已删除')
  loadItems()
}

// ---- 表单 ----
const dlgVisible = ref(false)
const formRef = ref(null)
const form = reactive({ id: null, projectId: null, itemLabel: '', itemKey: '', colType: 'text',
  enumValues: '', formula: '', requiredFlag: 0, bindField: '', fixedFlag: 0, colGroup: 'EXT', sortNo: null, remark: '' })

// 公式可引用的标识：本项目其他数值列（公式列不能再被引用，防循环）
const formulaVars = computed(() => rows.value
  .filter(r => (r.colType === 'int' || r.colType === 'decimal') && !r.formula && r.itemKey !== form.itemKey)
  .map(r => `${r.itemKey}（${r.itemLabel}）`).join('、'))
const rules = {
  itemLabel: [{ required: true, message: '请输入列名', trigger: 'blur' }],
  itemKey: [
    { required: true, message: '请输入标识', trigger: 'blur' },
    { pattern: /^[a-z][a-z0-9_]{0,19}$/, message: '小写字母开头，只能含小写英文/数字/下划线，≤20位', trigger: 'blur' }
  ],
  colType: [{ required: true, message: '请选择数据类型', trigger: 'change' }],
  enumValues: [{ validator: (r, v, cb) => form.colType === 'enum' && !String(v || '').trim()
    ? cb(new Error('枚举类型必须填写允许值')) : cb(), trigger: 'blur' }]
}

// 绑定字段变化 → 类型强制跟随（对齐生产 setTypeDefault）
watch(() => form.bindField, f => {
  if (f && bindMap.value[f]) form.colType = bindMap.value[f].type
})
// 类型改离数值 → 清残留公式（输入框已隐藏，防把旧值提交给后端被拒）
watch(() => form.colType, t => {
  if (t !== 'int' && t !== 'decimal') form.formula = ''
})

function openForm(row) {
  Object.assign(form, row ? { ...row, bindField: row.bindField || '', enumValues: row.enumValues || '',
    formula: row.formula || '', colGroup: row.colGroup || 'EXT' } : {
    id: null, projectId: current.value.id, itemLabel: '', itemKey: '', colType: 'text',
    enumValues: '', formula: '', requiredFlag: 0, bindField: '', fixedFlag: 0, colGroup: 'EXT', sortNo: null, remark: ''
  })
  form.projectId = current.value.id
  dlgVisible.value = true
}

// ---- 从其他项目复制 ----
const copyVisible = ref(false)
const copyFrom = ref('')
const copySources = ref([])
async function openCopy() {
  copyFrom.value = ''
  // 只列出已定义模板的项目（逐个探测太重，直接列全部，后端会拦“源项目未定义模板”）
  copySources.value = projects.value.filter(p => String(p.id) !== String(current.value.id))
  copyVisible.value = true
}
async function doCopy() {
  if (isCustom.value) {
    await ElMessageBox.confirm('当前项目已有模板定义，复制将整体覆盖，确认？', '覆盖确认', { type: 'warning' })
  }
  await tplApi.copy(copyFrom.value, current.value.id, isCustom.value)
  ElMessage.success('已复制')
  copyVisible.value = false
  loadItems()
}
async function doSave() {
  await formRef.value.validate()
  await tplApi.saveItem({ ...form, bindField: form.bindField || null })
  ElMessage.success('已保存')
  dlgVisible.value = false
  loadItems()
}

onMounted(async () => {
  loadProjects()
  bindOptions.value = (await tplApi.bindOptions()) || []
})
</script>

<style scoped>
.layout { display: flex; gap: 12px; align-items: stretch; }
.tree-card { width: 320px; flex: 0 0 320px; }
.tree-card :deep(.el-card__body) { padding: 12px; }
.main-card { flex: 1; min-width: 0; }
.mb8 { margin-bottom: 8px; }
.ml4 { margin-left: 4px; }
.ml8 { margin-left: 8px; }
.bar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.cur-tip { margin-left: 12px; color: #606266; }
.muted { color: #c0c4cc; }
.is-project { display: inline-flex; align-items: center; gap: 4px; }
.tip { font-size: 12px; color: #909399; line-height: 1.6; }
</style>
