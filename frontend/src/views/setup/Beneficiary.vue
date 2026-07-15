<template>
  <div>
    <CrudTable ref="crud" :api="beneficiaryApi" :columns="columns" :search-fields="searchFields"
      :form-fields="formFields" :form-rules="rules" form-width="1060px" label-width="110px"
      selectable :page-size="200" :page-sizes="[100, 200, 500, 1000]"
      collapsible-search :search-cols="4" list-title="数据列表" enable-view-toggle :card-fields="cardFields"
      :enable-create="false" :enable-edit="false" :enable-delete="false"
      @selection-change="sel = $event" @loaded="onListLoaded">
      <!-- 工具条对齐生产 -->
      <template #toolbar-left>
        <el-button type="primary" :icon="Plus" @click="crud.openForm()">新增</el-button>
        <el-button :icon="Edit" @click="editSel">修改</el-button>
        <el-button :icon="Delete" @click="delSel">删除</el-button>
        <el-button @click="openImport">导入</el-button>
        <el-button :loading="exporting" @click="doExport">导出</el-button>
        <el-button @click="cancelSel">注销</el-button>
        <el-button @click="uncancelSel">取消注销</el-button>
        <el-button @click="referVisible = true">引用</el-button>
        <el-button @click="openBatch">批量修改</el-button>
      </template>

      <!-- 列表映射回中文 -->
      <template #column-status="{ value }">
        <el-tag :type="statusTag(value)">{{ labelOf(STATUS_OPTS, value) }}</el-tag>
      </template>
      <template #column-referred="{ value }">{{ value === 1 || value === '1' ? '是' : '否' }}</template>
      <template #column-gender="{ value }">{{ labelOf(GENDER_OPTS, value) }}</template>
      <template #column-villageId="{ value }">{{ villageName(value) }}</template>
      <template #column-bankId="{ value }">{{ bankNameById(value) }}</template>
      <template #column-publicBankId="{ value }">{{ bankNameById(value) }}</template>
      <template #column-isDibao="{ value }">{{ labelOf(YESNO_OPTS, value) }}</template>
      <template #column-isFiling="{ value }">{{ labelOf(YESNO_OPTS, value) }}</template>
      <template #column-isTekun="{ value }">{{ labelOf(YESNO_OPTS, value) }}</template>
      <template #column-isRural="{ value }">{{ labelOf(RURAL_OPTS, value) }}</template>
      <template #column-createBy="{ value }">{{ value || '一卡通' }}</template>

      <!-- 表单：村(居)委会 / 开户银行 / 公共账户开户行 用 filterable 下拉 -->
      <template #form-villageId="{ form }">
        <el-select v-model="form.villageId" filterable placeholder="选择村(居)委会(当前权限范围)" style="width:100%">
          <el-option v-for="v in villages" :key="v.id" :label="`${v.villageCode}-${v.villageName}`" :value="v.id" />
        </el-select>
      </template>
      <template #form-bankId="{ form }">
        <el-select v-model="form.bankId" filterable remote clearable :remote-method="searchBanks" :loading="bankLoading"
          placeholder="输入行号/名称搜索开户银行" style="width:100%">
          <el-option v-for="o in bankOpts" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </template>
      <template #form-publicBankId="{ form }">
        <el-select v-model="form.publicBankId" filterable remote clearable :remote-method="searchBanks" :loading="bankLoading"
          placeholder="输入行号/名称搜索公共账户开户行" style="width:100%">
          <el-option v-for="o in bankOpts" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </template>
    </CrudTable>

    <!-- 导入选项（对齐生产：选文件 + 导出模板 / 导入 / 取消） -->
    <input ref="fileInput" type="file" accept=".xlsx,.xls" style="display:none" @change="onFileChange" />
    <el-dialog v-model="importVisible" title="导入选项" width="520px">
      <div class="import-row">
        <el-input :model-value="importFile ? importFile.name : ''" placeholder="请选择需要导入的文件..." readonly />
        <el-button @click="fileInput?.click()">浏览...</el-button>
      </div>
      <template #footer>
        <el-button @click="downloadTemplate">导出模板</el-button>
        <el-button type="primary" :loading="importing" @click="doImport">导 入</el-button>
        <el-button @click="importVisible = false">取 消</el-button>
      </template>
    </el-dialog>

    <!-- 信息校验日志（导入前置校验） -->
    <el-dialog v-model="logVisible" title="信息校验日志" width="72%" top="6vh">
      <div class="log-bar">
        <el-button size="small" round @click="exportLog">导出日志信息</el-button>
        <el-button size="small" round @click="logVisible = false">关闭</el-button>
      </div>
      <el-table :data="logRows" border stripe size="small" height="56vh">
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="msg" label="信息校验结果" min-width="500" show-overflow-tooltip />
      </el-table>
    </el-dialog>

    <!-- 引用 -->
    <el-dialog v-model="referVisible" title="引用补贴对象" width="480px">
      <el-input v-model="referId" placeholder="输入身份证号筛选" />
      <div v-if="referResult" style="margin-top:12px;line-height:2">
        <div>户主：{{ referResult.headName }}</div>
        <div>家庭成员：{{ referResult.name }}</div>
        <div>身份证：{{ referResult.idCard }}</div>
      </div>
      <div v-else-if="referSearched" style="margin-top:12px;color:#f56c6c">未查询到数据</div>
      <template #footer>
        <el-button @click="referVisible = false">关闭</el-button>
        <el-button @click="doRefer">筛选</el-button>
        <el-button type="primary" :disabled="!referResult" @click="confirmRefer">确定引用</el-button>
      </template>
    </el-dialog>

    <!-- 批量修改：选中数据铺成可编辑表格 -->
    <el-dialog v-model="batchVisible" title="批量修改" fullscreen :close-on-click-modal="false" class="batch-dialog">
      <div class="batch-toolbar">
        <el-button type="primary" :loading="batchSaving" @click="batchSave">保存</el-button>
        <el-button @click="batchVisible = false">取消</el-button>
      </div>
      <div class="batch-listbar"><el-icon><Tickets /></el-icon> 数据列表</div>
      <el-table ref="batchTableRef" :data="batchRows" border stripe size="small" height="calc(100vh - 200px)"
        row-key="id" @selection-change="batchSel = $event">
        <el-table-column type="selection" width="46" align="center" />
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column v-for="col in batchColumns" :key="col.prop" :label="col.label" :width="col.width">
          <template #default="{ row }">
            <el-select v-if="col.prop === 'bankId' || col.prop === 'publicBankId'" v-model="row[col.prop]"
              filterable remote clearable :remote-method="searchBanks" :loading="bankLoading" size="small" style="width:100%">
              <el-option v-for="o in bankOpts" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-select v-else-if="col.type === 'select'" v-model="row[col.prop]" filterable clearable size="small" style="width:100%">
              <el-option v-for="o in optsFor(col.prop)" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-input-number v-else-if="col.type === 'number'" v-model="row[col.prop]" :min="0" :max="130" :controls="false" size="small" style="width:100%" />
            <el-input v-else v-model="row[col.prop]" size="small" />
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, nextTick, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, Tickets } from '@element-plus/icons-vue'
import CrudTable from '../../components/CrudTable.vue'
import { beneficiaryApi, villageApi, orgApi, bankApi } from '../../api/system'

/* ============ 数据字典 ============ */
const STATUS_OPTS = [{ label: '0-作废', value: '0' }, { label: '1-正常', value: '1' }, { label: '2-停用', value: '2' }]
const YESNO_OPTS  = [{ label: '0-否', value: '0' }, { label: '1-是', value: '1' }]
const GENDER_OPTS = [{ label: '1-男', value: '1' }, { label: '2-女', value: '2' }]
const RURAL_OPTS  = [{ label: '1-乡村', value: '1' }, { label: '2-城镇', value: '2' }]
// 与户主关系 34 项：value 存纯文本（与库/导入导出/生产口径一致；此前存数字码与库内文本对不上）
const RELATION_OPTS = ['01-本人','02-妻子','03-兄弟','04-姐妹','05-父亲','06-母亲','07-外婆','08-外公',
  '09-儿子','10-女儿','11-孙子','12-孙女','13-丈夫','14-祖父','15-祖母','16-儿媳',
  '17-女婿','18-嫂子','19-弟媳','20-舅舅','21-外甥','22-姑嫂','23-伯叔','24-伯母',
  '25-侄子','26-侄女','27-孙女婿','28-孙媳','29-重孙','30-婆婆','31-公公','32-爸爸',
  '33-奶奶','34-其他'].map(s => ({ label: s, value: s.slice(3) }))
const labelOf = (opts, v) => opts.find(o => o.value === String(v))?.label || v || '-'
const statusTag = (v) => ({ '0': 'info', '1': 'success', '2': 'warning' }[String(v)] || 'info')

/* ============ 下拉数据源 ============ */
const towns = ref([]); const villages = ref([])
// 对齐生产口径「联行号-名称」（402836505018-xx联社）；无联行号回落 bankCode，再没有只显名称
const bankLabel = (b) => { const no = b.unionCode || b.bankCode; return no ? `${no}-${b.bankName}` : b.bankName }
const villageName = (id) => { const v = villages.value.find(x => String(x.id) === String(id)); return v ? `${v.villageCode}-${v.villageName}` : '-' }

/* 全国联行号库 1.7 万行：不整表下发。bankCache 缓存已知银行（列表回显 + 搜索结果），
   bankOpts 由缓存派生（体量=屏上引用+最近搜索，几十到几百条），下拉走远程搜索 searchBanks。 */
const bankCache = reactive({})
const bankLoading = ref(false)
const mergeBanks = (arr) => (arr || []).forEach(b => { if (b && b.id != null) bankCache[String(b.id)] = b })
const bankNameById = (id) => { const b = bankCache[String(id)]; return b ? bankLabel(b) : (id ? String(id) : '') }
const bankOpts = computed(() => Object.values(bankCache).map(b => ({ label: bankLabel(b), value: b.id })))
async function searchBanks(kw) {
  if (!kw || !kw.trim()) return
  bankLoading.value = true
  try { mergeBanks((await bankApi.search(kw.trim()))?.records) } finally { bankLoading.value = false }
}
// 列表加载后：把本页引用到的开户行/公共账户开户行批量解析进缓存，供回显
async function resolveListBanks(rows) {
  const ids = [...new Set(rows.flatMap(r => [r.bankId, r.publicBankId])
    .filter(v => v != null && !bankCache[String(v)]).map(String))]
  if (ids.length) mergeBanks(await bankApi.resolve({ ids: ids.join(',') }))
}
function onListLoaded({ rows }) { resolveListBanks(rows || []) }
const villageOpts = computed(() => villages.value.map(v => ({ label: `${v.villageCode}-${v.villageName}`, value: v.id })))
const setOpt = (prop, opts) => { const f = searchFields.find(x => x.prop === prop); if (f) f.options = opts }
onMounted(async () => {
  const all = (await orgApi.tree()) || []
  const cMap = Object.fromEntries(all.filter(o => o.orgType === 'COUNTY').map(c => [String(c.id), c.orgName]))
  towns.value = all.filter(o => o.orgType === 'TOWN')
  villages.value = (await villageApi.list({})) || []
  setOpt('townId', towns.value.map(t => ({ label: `${cMap[String(t.parentId)] || ''} ${t.orgName}`, value: t.id })))
  setOpt('villageId', villages.value.map(v => ({ label: `${v.villageCode}-${v.villageName}`, value: v.id })))
})

/* ============ 列表列（对齐生产，含全部富字段）============ */
const columns = [
  { prop: 'status', label: '状态', width: 90, align: 'center' },
  { prop: 'referred', label: '是否引用', width: 90, align: 'center' },
  { prop: 'householdCode', label: '农户编码', width: 140 },
  { prop: 'headName', label: '户主姓名', width: 100 },
  { prop: 'headIdCard', label: '户主身份证号', width: 180 },
  { prop: 'name', label: '家庭成员姓名', width: 110 },
  { prop: 'idCard', label: '身份证号', width: 180 },
  { prop: 'villageId', label: '村(居)委会', width: 200 },
  { prop: 'groupName', label: '村(居)民小组', width: 110 },
  { prop: 'gender', label: '性别', width: 70, align: 'center' },
  { prop: 'phone', label: '联系电话', width: 130 },
  { prop: 'age', label: '年龄', width: 70, align: 'center' },
  { prop: 'relation', label: '与户主关系', width: 120 },
  { prop: 'accountName', label: '账户名称', width: 110 },
  { prop: 'bankAccount', label: '银行账号', width: 180 },
  { prop: 'bankId', label: '开户银行', width: 200 },
  { prop: 'publicAccountName', label: '公共账户名称', width: 120 },
  { prop: 'publicAccountNo', label: '公共账户账号', width: 160 },
  { prop: 'publicBankId', label: '公共账户开户行', width: 200 },
  { prop: 'isDibao', label: '是否低保户', width: 90, align: 'center' },
  { prop: 'isRural', label: '是否乡村人口', width: 100, align: 'center' },
  { prop: 'isFiling', label: '是否建档立卡户', width: 110, align: 'center' },
  { prop: 'isTekun', label: '是否特困人员', width: 100, align: 'center' },
  { prop: 'farmlandInfo', label: '耕地情况', width: 110 },
  { prop: 'houseInfo', label: '房屋情况', width: 110 },
  { prop: 'remark', label: '备注', width: 140 },
  { prop: 'createBy', label: '创建人', width: 100, align: 'center' }
]
const searchFields = reactive([
  { prop: 'townId', label: '乡镇', type: 'select', options: [] },
  { prop: 'villageId', label: '村(居)委会', type: 'select', options: [] },
  { prop: 'headName', label: '户主姓名', advanced: true },
  { prop: 'headIdCard', label: '户主身份证号', advanced: true },
  { prop: 'bankAccount', label: '银行账号', advanced: true },
  { prop: 'name', label: '家庭成员姓名', advanced: true },
  { prop: 'idCard', label: '家庭成员身份证号', advanced: true },
  { prop: 'isRural', label: '是否乡村人口', type: 'select', options: RURAL_OPTS, advanced: true }
])

/* ============ 表单（卡片）视图字段：映射回中文 ============ */
const cardFields = [
  { prop: 'status', label: '状态', fmt: v => labelOf(STATUS_OPTS, v) },
  { prop: 'referred', label: '是否引用', fmt: v => (v === 1 || v === '1' ? '是' : '否') },
  { prop: 'householdCode', label: '农户编码' },
  { prop: 'headName', label: '户主姓名' },
  { prop: 'headIdCard', label: '户主身份证号' },
  { prop: 'name', label: '家庭成员姓名' },
  { prop: 'idCard', label: '身份证号' },
  { prop: 'villageId', label: '村(居)委会', fmt: v => villageName(v) },
  { prop: 'groupName', label: '村(居)民小组' },
  { prop: 'gender', label: '性别', fmt: v => labelOf(GENDER_OPTS, v) },
  { prop: 'phone', label: '联系电话' },
  { prop: 'age', label: '年龄' },
  { prop: 'relation', label: '与户主关系' },
  { prop: 'accountName', label: '账户名称' },
  { prop: 'bankAccount', label: '银行账号' },
  { prop: 'bankId', label: '开户银行', fmt: v => bankNameById(v) },
  { prop: 'publicAccountName', label: '公共账户名称' },
  { prop: 'publicAccountNo', label: '公共账户账号' },
  { prop: 'publicBankId', label: '公共账户开户行', fmt: v => bankNameById(v) },
  { prop: 'isDibao', label: '是否低保户', fmt: v => labelOf(YESNO_OPTS, v) },
  { prop: 'isRural', label: '是否乡村人口', fmt: v => labelOf(RURAL_OPTS, v) },
  { prop: 'isFiling', label: '是否建档立卡户', fmt: v => labelOf(YESNO_OPTS, v) },
  { prop: 'isTekun', label: '是否特困人员', fmt: v => labelOf(YESNO_OPTS, v) },
  { prop: 'farmlandInfo', label: '耕地情况' },
  { prop: 'houseInfo', label: '房屋情况' },
  { prop: 'remark', label: '备注' },
  { prop: 'createBy', label: '创建人', fmt: v => v || '一卡通' }
]

/* ============ 修改单据表单（对齐生产 3 列布局）============ */
const formFields = [
  { prop: 'status',        label: '状态',          span: 8, type: 'select', options: STATUS_OPTS, default: '1' },
  { prop: 'referred',      label: '是否引用',      span: 8, type: 'select', options: [{ label: '0-否', value: 0 }, { label: '1-是', value: 1 }] },
  { prop: 'householdCode', label: '农户编码',      span: 8, maxlength: 32 },
  { prop: 'villageId',     label: '村(居)委会',    span: 8 },
  { prop: 'groupName',     label: '村(居)民小组',  span: 8, maxlength: 16 },
  { prop: 'headName',      label: '户主姓名',      span: 8, maxlength: 32 },
  { prop: 'headIdCard',    label: '户主身份证号',  span: 8, maxlength: 18 },
  { prop: 'name',          label: '家庭成员姓名',  span: 8, maxlength: 32 },
  { prop: 'idCard',        label: '身份证号',      span: 8, maxlength: 18 },
  { prop: 'gender',        label: '性别',          span: 8, type: 'select', options: GENDER_OPTS },
  { prop: 'phone',         label: '联系电话',      span: 8, maxlength: 11 },
  { prop: 'age',           label: '年龄',          span: 8, type: 'number', min: 0, max: 130 },
  { prop: 'relation',      label: '与户主关系',    span: 8, type: 'select', options: RELATION_OPTS },
  { prop: 'accountName',   label: '账户名称',      span: 8, maxlength: 32 },
  { prop: 'bankAccount',   label: '银行账号',      span: 8, maxlength: 32 },
  { prop: 'bankId',        label: '开户银行',      span: 8 },
  { prop: 'publicAccountName', label: '公共账户名称', span: 8, maxlength: 32 },
  { prop: 'publicAccountNo',   label: '公共账户账号', span: 8, maxlength: 32 },
  { prop: 'publicBankId',  label: '公共账户开户行', span: 8 },
  { prop: 'isDibao',       label: '是否低保户',    span: 8, type: 'select', options: YESNO_OPTS, default: '0' },
  { prop: 'isFiling',      label: '是否建档立卡户', span: 8, type: 'select', options: YESNO_OPTS, default: '0' },
  { prop: 'isTekun',       label: '是否特困人员',  span: 8, type: 'select', options: YESNO_OPTS, default: '0' },
  { prop: 'houseInfo',     label: '房屋情况',      span: 8, maxlength: 100 },
  { prop: 'farmlandInfo',  label: '耕地情况',      span: 8, maxlength: 100 },
  { prop: 'isRural',       label: '是否乡村人口',  span: 8, type: 'select', options: RURAL_OPTS, default: '1' },
  { prop: 'remark',        label: '备注',          span: 16, type: 'textarea', rows: 2, maxlength: 255 }
]
const req = (msg, trigger = 'blur') => [{ required: true, message: msg, trigger }]
const rules = {
  status:        req('请选择状态', 'change'),
  householdCode: req('请输入农户编码'),
  villageId:     req('请选择村(居)委会', 'change'),
  groupName:     req('请输入村(居)民小组'),
  headName:      req('请输入户主姓名'),
  headIdCard:    req('请输入户主身份证号'),
  name:          req('请输入家庭成员姓名'),
  idCard:        req('请输入身份证号'),
  relation:      req('请选择与户主关系', 'change'),
  accountName:   req('请输入账户名称'),
  bankAccount:   req('请输入银行账号'),
  isDibao:       req('请选择是否低保户', 'change'),
  isFiling:      req('请选择是否建档立卡户', 'change'),
  isTekun:       req('请选择是否特困人员', 'change'),
  isRural:       req('请选择是否乡村人口', 'change')
}

/* ============ 工具条动作（基于上方 checkbox 选中行）============ */
const sel = ref([])
function needOne() {
  if (sel.value.length === 0) { ElMessage.warning('请先勾选一条记录'); return null }
  if (sel.value.length > 1) { ElMessage.warning('修改只能勾选一条记录'); return null }
  return sel.value[0]
}
function needSome() {
  if (sel.value.length === 0) { ElMessage.warning('请先勾选记录'); return false }
  return true
}
function editSel() { const r = needOne(); if (r) crud.value.openForm(r) }

async function delSel() {
  if (!needSome()) return
  await ElMessageBox.confirm(`确定删除选中的 ${sel.value.length} 条记录？此操作不可恢复`, '删除确认', { type: 'warning' })
  for (const r of sel.value) await beneficiaryApi.delete(r.id)
  ElMessage.success('删除成功'); afterBatch()
}
async function cancelSel() {
  if (!needSome()) return
  await ElMessageBox.confirm(`确定注销选中的 ${sel.value.length} 条记录？`, '提示', { type: 'warning' })
  for (const r of sel.value) await beneficiaryApi.cancel(r.id)
  ElMessage.success('已注销'); afterBatch()
}
async function uncancelSel() {
  if (!needSome()) return
  for (const r of sel.value) await beneficiaryApi.uncancel(r.id)
  ElMessage.success('已取消注销'); afterBatch()
}
function afterBatch() { crud.value.clearSelection(); sel.value = []; crud.value.reload() }

/* ============ 批量修改：选中数据整列铺开行内编辑 ============ */
const batchVisible = ref(false)
const batchRows = ref([])
const batchSel = ref([])
const batchTableRef = ref()
const batchSaving = ref(false)
// 列定义：text=输入框 / number=数字 / select=下拉(下拉内容跟新增表单一致)
const batchColumns = [
  { prop: 'status', label: '状态', width: 110, type: 'select' },
  { prop: 'householdCode', label: '农户编码', width: 150 },
  { prop: 'villageId', label: '村(居)委会', width: 210, type: 'select' },
  { prop: 'groupName', label: '村(居)民小组', width: 120 },
  { prop: 'headName', label: '户主姓名', width: 110 },
  { prop: 'headIdCard', label: '户主身份证号', width: 180 },
  { prop: 'name', label: '家庭成员姓名', width: 110 },
  { prop: 'idCard', label: '身份证号', width: 180 },
  { prop: 'gender', label: '性别', width: 100, type: 'select' },
  { prop: 'phone', label: '联系电话', width: 140 },
  { prop: 'age', label: '年龄', width: 100, type: 'number' },
  { prop: 'relation', label: '与户主关系', width: 150, type: 'select' },
  { prop: 'accountName', label: '账户名称', width: 120 },
  { prop: 'bankAccount', label: '银行账号', width: 190 },
  { prop: 'bankId', label: '开户银行', width: 220, type: 'select' },
  { prop: 'publicAccountName', label: '公共账户名称', width: 140 },
  { prop: 'publicAccountNo', label: '公共账户账号', width: 170 },
  { prop: 'publicBankId', label: '公共账户开户行', width: 220, type: 'select' },
  { prop: 'isDibao', label: '是否低保户', width: 110, type: 'select' },
  { prop: 'isFiling', label: '是否建档立卡户', width: 120, type: 'select' },
  { prop: 'isTekun', label: '是否特困人员', width: 110, type: 'select' },
  { prop: 'houseInfo', label: '房屋情况', width: 130 },
  { prop: 'farmlandInfo', label: '耕地情况', width: 130 },
  { prop: 'isRural', label: '是否乡村人口', width: 120, type: 'select' },
  { prop: 'remark', label: '备注', width: 160 }
]
function optsFor(prop) {
  if (prop === 'status') return STATUS_OPTS
  if (prop === 'gender') return GENDER_OPTS
  if (prop === 'relation') return RELATION_OPTS
  if (prop === 'isRural') return RURAL_OPTS
  if (prop === 'villageId') return villageOpts.value
  if (prop === 'bankId' || prop === 'publicBankId') return bankOpts.value
  return YESNO_OPTS  // isDibao / isFiling / isTekun
}
function openBatch() {
  if (!needSome()) return
  batchRows.value = sel.value.map(r => JSON.parse(JSON.stringify(r)))  // 深拷贝，保存前不动原列表
  batchVisible.value = true
  nextTick(() => batchRows.value.forEach(r => batchTableRef.value?.toggleRowSelection(r, true)))  // 默认全勾
}
async function batchSave() {
  const target = batchSel.value.length ? batchSel.value : batchRows.value
  if (!target.length) { ElMessage.warning('没有要保存的记录'); return }
  const props = batchColumns.map(c => c.prop)
  batchSaving.value = true
  try {
    for (const r of target) {
      const payload = { id: r.id }
      props.forEach(p => { payload[p] = r[p] })
      await beneficiaryApi.save(payload)   // 带 id 走 PUT updateById
    }
    ElMessage.success(`已保存 ${target.length} 条`)
    batchVisible.value = false
    afterBatch()
  } finally { batchSaving.value = false }
}

const crud = ref()
const referVisible = ref(false); const referId = ref(''); const referResult = ref(null); const referSearched = ref(false)
async function doRefer() { referSearched.value = true; referResult.value = await beneficiaryApi.refer(referId.value) }
function confirmRefer() { crud.value.openForm({ ...referResult.value, id: null, referred: 1 }); referVisible.value = false }

/* ============ 导入 / 导出（对齐生产「导入选项」）============ */
const fileInput = ref(null)
const importVisible = ref(false); const importing = ref(false); const importFile = ref(null)
const exporting = ref(false)
function openImport() { importFile.value = null; importVisible.value = true }
function onFileChange(e) { importFile.value = e.target.files?.[0] || null; e.target.value = '' }
async function downloadTemplate() {
  saveBlob(await beneficiaryApi.importTemplate(), '补贴对象导入模板.xls')
}
async function doImport() {
  if (!importFile.value) return ElMessage.warning('请先选择需要导入的文件')
  importing.value = true
  try {
    const fd = new FormData(); fd.append('file', importFile.value)
    const res = await beneficiaryApi.importFile(fd)
    if (res && res.errors && res.errors.length) { showLog(res.errors); return }   // 校验不过整体不导入
    ElMessage.success(`导入 ${res?.count ?? 0} 条`)
    importVisible.value = false; importFile.value = null
    crud.value.reload()
  } finally { importing.value = false }
}
async function doExport() {
  exporting.value = true
  try { saveBlob(await beneficiaryApi.exportAll(), '补贴对象花名册.xls') }
  finally { exporting.value = false }
}

/* 信息校验日志 */
const logVisible = ref(false); const logRows = ref([])
function showLog(errors) { logRows.value = (errors || []).map(msg => ({ msg })); logVisible.value = true }
function exportLog() {
  const text = logRows.value.map((r, i) => `${i + 1}\t${r.msg}`).join('\r\n')
  saveBlob(new Blob(['﻿序号\t信息校验结果\r\n' + text], { type: 'text/plain;charset=utf-8' }), '信息校验日志.txt')
}
function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob); const a = document.createElement('a')
  a.href = url; a.download = filename; document.body.appendChild(a); a.click()
  document.body.removeChild(a); URL.revokeObjectURL(url)
}
</script>

<style scoped>
.import-row { display: flex; gap: 8px; align-items: center; }
.log-bar { margin-bottom: 10px; }
.batch-toolbar { margin-bottom: 10px; }
.batch-listbar { display: flex; align-items: center; gap: 6px; font-weight: 600; color: #1f5fbf;
  background: #eef5fe; border: 1px solid #d4e4fb; border-bottom: none; padding: 6px 12px; }
.batch-dialog :deep(.el-dialog__body) { padding-top: 10px; }
</style>
