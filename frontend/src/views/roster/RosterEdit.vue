<template>
  <div class="roster-edit">
    <!-- 工具栏 -->
    <el-card shadow="never" class="toolbar">
      <el-button type="primary" :disabled="isCorrection || locked" @click="openSingle()">新增</el-button>
      <el-button :disabled="locked" @click="editSelected">修改</el-button>
      <el-button :disabled="isCorrection || locked" @click="openFill">批量填报</el-button>
      <el-button :disabled="locked" @click="delSelected">删除</el-button>
      <el-button :disabled="locked" @click="openBatchEdit">批量修改</el-button>
      <el-button :disabled="locked" @click="doSubmit">送审</el-button>
      <el-button :disabled="!locked" @click="doUnsubmit">取消送审</el-button>
      <el-button :disabled="isCorrection || locked" @click="openImport">导入</el-button>
      <el-button @click="doExport">导出</el-button>
      <el-button :disabled="isCorrection || locked" @click="doDeleteBatch">删除批次</el-button>
      <el-button :disabled="locked" @click="doStop">停发</el-button>
      <el-button @click="openSummary">发放金额合计及批次备注信息查看</el-button>
      <input ref="fileInput" type="file" accept=".xlsx,.xls" style="display:none" @change="onFileChange" />
    </el-card>

    <!-- 批次/筛选 -->
    <el-card shadow="never" class="filter">
      <div class="search-grid">
        <div class="sf"><label>发放批次：</label>
          <el-select v-model="batchId" filterable style="width:340px" @change="onBatchChange">
            <el-option v-for="b in batchOptions" :key="b.id"
              :label="`${b.batchCode || ''}-${b.batchName}${b.status === 'SUBMITTED' ? '（已送审）' : ''}`" :value="String(b.id)" />
          </el-select>
        </div>
        <div class="sf"><label>户主姓名：</label><el-input v-model="q.holderName" clearable @keyup.enter="reload" /></div>
        <div class="sf actions">
          <el-button type="primary" @click="reload">查询</el-button>
          <el-button type="primary" plain @click="searchExpanded = !searchExpanded">显示/隐藏</el-button>
        </div>
        <template v-if="searchExpanded">
          <div class="sf"><label>户主身份证号：</label><el-input v-model="q.holderIdCard" clearable @keyup.enter="reload" /></div>
          <div class="sf"><label>收款人姓名：</label><el-input v-model="q.payeeName" clearable @keyup.enter="reload" /></div>
          <div class="sf"><label>收款人身份证：</label><el-input v-model="q.payeeIdCard" clearable @keyup.enter="reload" /></div>
          <div class="sf"><label>银行账号：</label><el-input v-model="q.bankAccount" clearable @keyup.enter="reload" /></div>
          <div class="sf"><label>享受人：</label><el-input v-model="q.beneficiaryName" clearable @keyup.enter="reload" /></div>
          <div class="sf"><label>享受人身份证号：</label><el-input v-model="q.beneficiaryIdCard" clearable @keyup.enter="reload" /></div>
        </template>
      </div>
    </el-card>

    <!-- 数据列表 -->
    <el-card shadow="never">
      <div class="list-bar">数据列表</div>
      <!-- :key=列集合签名：模板列异步变化且新列插在中间时，el-table 表头列注册会错乱（体对头不对），整表重建最稳 -->
      <el-table :key="colsSig" v-loading="loading" :data="rows" border stripe size="small" row-key="id"
        @selection-change="onSelect" height="56vh">
        <el-table-column type="selection" width="42" reserve-selection />
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="sortNo" label="排序序号" width="90" align="center" />
        <el-table-column prop="payStatus" label="支付状态" width="90" align="center" />
        <!-- 大列组整表按发放表定义驱动：组内列顺序 = 模板排序号（自由列可插到内置列之间） -->
        <template v-for="g in gridGroups" :key="g.key">
          <el-table-column v-if="g.cols.length" :label="g.label" align="center">
            <el-table-column v-for="c in g.cols" :key="c.key" :label="c.label"
              :width="c.width" :align="c.align || 'left'" show-overflow-tooltip>
              <template #default="{ row }">{{ cellVal(row, c) }}</template>
            </el-table-column>
          </el-table-column>
        </template>
        <el-table-column label="备注信息" width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.payStatus === '已停发'" style="color:#f56c6c">停发：{{ row.stopReason }}</span>
            <span v-else>{{ row.remark }}</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="page.pageNum" v-model:page-size="page.pageSize" :total="page.total"
          :page-sizes="[50,100,200,500]" layout="total, sizes, prev, pager, next, jumper" background
          @size-change="reload" @current-change="reload" />
      </div>
    </el-card>

    <!-- 新增/修改单据 -->
    <el-dialog v-model="singleVisible" :title="form.id ? '修改单据' : '新增单据'" width="900px">
      <el-form :model="form" label-width="120px" class="single-grid">
        <el-form-item label="排序序号"><el-input v-model.number="form.sortNo" /></el-form-item>
        <el-form-item label="户主姓名" required><el-input v-model="form.holderName" /></el-form-item>
        <el-form-item label="户主身份证号" required><el-input v-model="form.holderIdCard" /></el-form-item>
        <el-form-item label="银行账号" required><el-input v-model="form.bankAccount" /></el-form-item>
        <el-form-item label="开户银行" required>
          <el-select v-model="form.bankName" filterable allow-create default-first-option style="width:100%">
            <el-option v-for="b in banks" :key="b.id" :label="b.bankName" :value="b.bankName" />
          </el-select>
        </el-form-item>
        <el-form-item label="村(居)委会" required>
          <el-select v-model="form.villageName" filterable allow-create default-first-option style="width:100%">
            <el-option v-for="v in villages" :key="v.id" :label="v.villageName" :value="v.villageName" />
          </el-select>
        </el-form-item>
        <el-form-item label="村(居)民小组" required><el-input v-model="form.groupName" /></el-form-item>
        <el-form-item label="收款人姓名" required><el-input v-model="form.payeeName" /></el-form-item>
        <el-form-item label="享受人" required><el-input v-model="form.beneficiaryName" /></el-form-item>
        <el-form-item label="收款人身份证" required><el-input v-model="form.payeeIdCard" /></el-form-item>
        <el-form-item label="享受人身份证号" required><el-input v-model="form.beneficiaryIdCard" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="补助标准" required><el-input-number v-model="form.standard" :min="0" :precision="2" controls-position="right" style="width:100%" /></el-form-item>
        <el-form-item label="补贴金额" required><el-input-number v-model="form.amount" :min="0" :precision="2" controls-position="right" style="width:100%" /></el-form-item>
        <el-form-item label="填报日期"><el-date-picker v-model="form.fillDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" @click="saveSingle(false)">保存</el-button>
        <el-button v-if="!form.id" type="primary" plain @click="saveSingle(true)">保存并继续</el-button>
        <el-button @click="singleVisible = false">取消</el-button>
      </template>
    </el-dialog>

    <!-- 批量填报 -->
    <el-dialog v-model="fillVisible" title="批量填报" width="94%" top="4vh">
      <div class="fill-search">
        <div class="sf"><label><span class="req">*</span>数据来源：</label>
          <el-select v-model="fill.source" style="width:220px" @change="fillQuery">
            <el-option label="1-家庭成员表" value="1" />
            <el-option label="2-历史发放数据" value="2" />
          </el-select>
        </div>
        <div class="sf"><label>行政村：</label>
          <el-select v-model="fill.villageId" clearable filterable style="width:220px">
            <el-option v-for="v in villages" :key="v.id" :label="v.villageName" :value="v.id" />
          </el-select>
        </div>
        <div class="sf"><label>享受人姓名：</label><el-input v-model="fill.beneficiaryName" clearable style="width:200px" @keyup.enter="fillQuery" /></div>
        <div class="sf"><el-button type="primary" @click="fillQuery">查询</el-button></div>
        <div class="sf"><label>统一补贴金额：</label>
          <el-input-number v-model="fill.bulkAmount" :min="0" :precision="2" controls-position="right" />
          <el-button size="small" style="margin-left:8px" @click="applyBulkAmount">应用到勾选</el-button>
        </div>
      </div>
      <el-table ref="candTable" v-loading="fillLoading" :data="candidates" border stripe size="small"
        height="56vh" @selection-change="onCandSelect">
        <el-table-column type="selection" width="42" />
        <el-table-column type="index" label="序号" width="56" align="center" />
        <el-table-column label="补贴金额" width="150" align="center">
          <template #default="{ row }"><el-input-number v-model="row.amount" :min="0" :precision="2" size="small" controls-position="right" /></template>
        </el-table-column>
        <el-table-column prop="holderName" label="户主姓名" width="90" />
        <el-table-column prop="holderIdCard" label="户主身份证号" width="170" />
        <el-table-column prop="beneficiaryName" label="享受人" width="90" />
        <el-table-column prop="beneficiaryIdCard" label="享受人身份证号" width="170" />
        <el-table-column prop="bankAccount" label="银行账号" width="180" />
        <el-table-column prop="bankName" label="开户银行" width="260" show-overflow-tooltip>
          <template #default="{ row }">{{ bankDisp(row.bankName) }}</template>
        </el-table-column>
        <el-table-column prop="villageName" label="村(居)委会" width="140" show-overflow-tooltip />
        <el-table-column prop="groupName" label="村(居)民小组" width="110" />
        <el-table-column prop="phone" label="联系电话" width="120" />
      </el-table>
      <template #footer>
        <span style="float:left;color:#909399">已勾选 {{ candSelected.length }} 人</span>
        <el-button type="primary" :loading="filling" @click="doFillSave">保存</el-button>
        <el-button @click="fillVisible = false">取消</el-button>
      </template>
    </el-dialog>

    <!-- 批量修改 -->
    <el-dialog v-model="batchVisible" title="批量修改" width="92%" top="5vh">
      <el-table :data="batchRows" border size="small" height="62vh">
        <el-table-column type="index" label="序号" width="56" align="center" />
        <el-table-column label="户主姓名" width="120"><template #default="{ row }"><el-input v-model="row.holderName" size="small" /></template></el-table-column>
        <el-table-column label="享受人" width="120"><template #default="{ row }"><el-input v-model="row.beneficiaryName" size="small" /></template></el-table-column>
        <el-table-column label="银行账号" width="200"><template #default="{ row }"><el-input v-model="row.bankAccount" size="small" /></template></el-table-column>
        <el-table-column label="开户银行" width="220"><template #default="{ row }"><el-input v-model="row.bankName" size="small" /></template></el-table-column>
        <el-table-column label="村(居)民小组" width="140"><template #default="{ row }"><el-input v-model="row.groupName" size="small" /></template></el-table-column>
        <el-table-column label="联系电话" width="150"><template #default="{ row }"><el-input v-model="row.phone" size="small" /></template></el-table-column>
        <el-table-column label="补贴标准" width="130"><template #default="{ row }"><el-input-number v-model="row.standard" :min="0" :precision="2" size="small" controls-position="right" /></template></el-table-column>
        <el-table-column label="补贴金额" width="130"><template #default="{ row }"><el-input-number v-model="row.amount" :min="0" :precision="2" size="small" controls-position="right" /></template></el-table-column>
        <el-table-column label="备注" min-width="160"><template #default="{ row }"><el-input v-model="row.remark" size="small" /></template></el-table-column>
      </el-table>
      <template #footer>
        <el-button type="primary" :loading="batchSaving" @click="saveBatch">保存全部</el-button>
        <el-button @click="batchVisible = false">取消</el-button>
      </template>
    </el-dialog>

    <!-- 导入选项（对齐生产：选文件 + 导出模板 / 导入 / 取消） -->
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

    <!-- 信息校验日志（导入前置校验 / 送审校验共用） -->
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

    <!-- 发放金额合计及批次备注 -->
    <el-dialog v-model="summaryVisible" title="发放金额合计及批次备注信息" width="480px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="批次名称">{{ sum.batchName }}</el-descriptions-item>
        <el-descriptions-item label="发放人数">{{ sum.personCount }}</el-descriptions-item>
        <el-descriptions-item label="发放金额合计">{{ money(sum.totalAmount) }}</el-descriptions-item>
        <el-descriptions-item label="批次状态">{{ statusLabel(sum.status) }}</el-descriptions-item>
        <el-descriptions-item label="批次备注">{{ sum.remark }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { rosterEditApi, villageApi, orgApi, bankApi } from '../../api/system'

const STATUS = { NEW: '待编制', SUBMITTED: '已送审', STOP: '已停发', ISSUED: '已下达', PAID: '已支付' }
const statusLabel = (s) => STATUS[s] || s || ''

const route = useRoute()
const batchId = ref(null)
const batchOptions = ref([])
const batchTownId = ref(null)
const searchExpanded = ref(false)
const q = reactive({ holderName: '', holderIdCard: '', payeeName: '', payeeIdCard: '', bankAccount: '', beneficiaryName: '', beneficiaryIdCard: '' })
const rows = ref([]); const loading = ref(false)
const selected = ref([])
const page = reactive({ pageNum: 1, pageSize: 100, total: 0 })
const banks = ref([]); const villages = ref([])

const currentBatch = computed(() => batchOptions.value.find(x => String(x.id) === String(batchId.value)))
// 更正发放（重构）批次：人员固定复制而来，禁止新增/批量填报/导入/删除批次
const isCorrection = computed(() => !!currentBatch.value && (currentBatch.value.batchName || '').startsWith('更正发放'))
// 已送审锁定：编辑类按钮全灰，仅「取消送审」可用（送审后花名册不可再改）
const locked = computed(() => currentBatch.value?.status === 'SUBMITTED')

onMounted(async () => {
  await refreshBatches()
  towns.value = ((await orgApi.tree()) || []).filter(o => o.orgType === 'TOWN')
  banks.value = (await bankApi.list({})) || []
  const qid = route.query.batchId
  if (qid) {
    batchId.value = String(qid)
    if (!batchOptions.value.some(b => String(b.id) === batchId.value)) {
      const info = await rosterEditApi.info(batchId.value)
      if (info && info.id) batchOptions.value.unshift(info)
    }
  } else if (batchOptions.value.length) batchId.value = String(batchOptions.value[0].id)
  await loadBatchVillages()
  reload()
})
watch(() => route.query.batchId, (v) => { if (v) { batchId.value = String(v); loadBatchVillages(); reload() } })

async function loadBatchVillages() {
  const info = await rosterEditApi.info(batchId.value)
  batchTownId.value = info?.townId || null
  villages.value = (await villageApi.list(batchTownId.value ? { townId: batchTownId.value } : {})) || []
  // 项目发放表定义的全部模板列（绑定+自由），数据列表整表按它渲染
  try {
    const cols = await rosterEditApi.tplCols(batchId.value)
    tplGridCols.value = cols && cols.length ? cols : FALLBACK_COLS
  } catch { tplGridCols.value = FALLBACK_COLS }
}

// ---- 数据列表按发放表定义整表驱动 ----
const GROUP_ORDER = [
  ['HOLDER', '户主信息'], ['PAYEE', '收款人信息'],
  ['BENEFICIARY', '补贴对象信息'], ['GRANT', '发放信息'], ['EXT', '扩展信息']
]
// 绑定列的展示参数（宽度/对齐）；自由列统一 120
const BIND_META = {
  holderName: { width: 90 }, holderIdCard: { width: 170 },
  payeeName: { width: 90 }, payeeIdCard: { width: 170 }, bankAccount: { width: 180 }, bankName: { width: 260 },
  villageName: { width: 140 }, groupName: { width: 110 }, beneficiaryName: { width: 90 },
  beneficiaryIdCard: { width: 170 }, phone: { width: 120 },
  standard: { width: 100, align: 'right' }, amount: { width: 110, align: 'right' },
  fillDate: { width: 120, align: 'center' }
}
// tpl-cols 拉不到时的兜底：默认 18 列布局（与内置默认模板一致）
const FALLBACK_COLS = [
  ['holderName', '户主姓名'], ['holderIdCard', '户主身份证号'], ['payeeName', '收款人姓名'],
  ['payeeIdCard', '收款人身份证号'], ['bankAccount', '银行账号'], ['bankName', '开户银行'],
  ['villageName', '村(居)委会'], ['groupName', '村(居)民小组'], ['beneficiaryName', '享受人'],
  ['beneficiaryIdCard', '享受人身份证号'], ['phone', '联系电话'],
  ['workPlace', '务工地点', 'EXT'], ['transport', '乘坐交通工具', 'EXT'],
  ['standard', '补贴标准'], ['amount', '补贴金额'], ['fillDate', '填报日期']
].map(([k, label, g]) => ({ key: k, label, bind: g ? '' : k, group: g || bindGroupOf(k) }))
function bindGroupOf(b) {
  if (b === 'holderName' || b === 'holderIdCard') return 'HOLDER'
  if (['payeeName', 'payeeIdCard', 'bankAccount', 'bankName'].includes(b)) return 'PAYEE'
  if (['villageName', 'groupName', 'beneficiaryName', 'beneficiaryIdCard', 'phone'].includes(b)) return 'BENEFICIARY'
  return 'GRANT'
}
const tplGridCols = ref(FALLBACK_COLS)
const colsSig = computed(() => tplGridCols.value.map(c => c.key).join('|'))
const gridGroups = computed(() => GROUP_ORDER.map(([key, label]) => {
  const cols = tplGridCols.value.filter(c => c.group === key)
    .map(c => ({ ...c, width: 120, ...(BIND_META[c.bind] || {}) }))
  if (key === 'BENEFICIARY' && cols.length) cols.push({ key: '__age', label: '年龄', bind: 'age', width: 64, align: 'center' })
  return { key, label, cols }
}))
function cellVal(row, c) {
  const b = c.bind
  if (!b) return extVal(row, c.key)
  if (b === 'bankName') return bankDisp(row.bankName)
  if (b === 'standard') return money(row.standard)
  if (b === 'amount') return row.payStatus === '已停发' ? money(0) : money(row.amount)
  return row[b] ?? ''
}
function extVal(row, key) {
  if (!row.extJson) return ''
  try { return (JSON.parse(row.extJson) || {})[key] ?? '' } catch { return '' }
}
// 开户银行按生产口径显示「联行号-名称」（库里仍只存名称，导入/编辑不受影响）
const bankNoMap = computed(() => Object.fromEntries(
  banks.value.map(b => [b.bankName, b.unionCode || b.bankCode || ''])))
function bankDisp(name) {
  if (!name) return ''
  const no = bankNoMap.value[name]
  return no ? `${no}-${name}` : name
}

function onBatchChange() { page.pageNum = 1; loadBatchVillages(); reload() }
async function reload() {
  if (!batchId.value) { rows.value = []; page.total = 0; return }
  loading.value = true
  try {
    const res = await rosterEditApi.page({ batchId: batchId.value, ...filterParams(), pageNum: page.pageNum, pageSize: page.pageSize })
    rows.value = res?.records || []
    page.total = Number(res?.total) || 0
  } finally { loading.value = false }
}
function filterParams() {
  const o = {}
  Object.keys(q).forEach(k => { if (q[k]) o[k] = q[k] })
  return o
}
function onSelect(s) { selected.value = s }
function needSelected() { if (!selected.value.length) { ElMessage.warning('请先勾选数据'); return false } return true }
function needBatch() { if (!batchId.value) { ElMessage.warning('请先选择批次'); return false } return true }

// 新增/修改
const singleVisible = ref(false)
const blank = () => ({ id: null, sortNo: null, holderName: '', holderIdCard: '', payeeName: '', payeeIdCard: '', bankAccount: '', bankName: '', villageName: '', groupName: '', beneficiaryName: '', beneficiaryIdCard: '', phone: '', age: null, standard: null, amount: null, fillDate: null, remark: '' })
const form = reactive(blank())
function openSingle(row) {
  Object.assign(form, blank(), row && row.id ? row : {})
  singleVisible.value = true
}
function editSelected() {
  if (!needSelected()) return
  if (selected.value.length > 1) return ElMessage.warning('修改只能选一条')
  openSingle(selected.value[0])
}
async function saveSingle(keep) {
  await rosterEditApi.save({ ...form, batchId: batchId.value })
  ElMessage.success('保存成功')
  if (keep) { Object.assign(form, blank()) } else { singleVisible.value = false }
  reload()
}

// 删除
async function delSelected() {
  if (!needSelected()) return
  await ElMessageBox.confirm(`确认删除选中的 ${selected.value.length} 条？`, '提示', { type: 'warning' })
  await rosterEditApi.remove(selected.value.map(r => r.id).join(','))
  ElMessage.success('已删除'); reload()
}

// 批量填报（数据来源 + 行政村 + 查询 + 勾选 + 补贴金额 + 保存）
const towns = ref([])
const fillVisible = ref(false); const filling = ref(false); const fillLoading = ref(false)
const fill = reactive({ source: '1', villageId: null, beneficiaryName: '', bulkAmount: null })
const candidates = ref([]); const candSelected = ref([]); const candTable = ref(null)
function openFill() {
  if (!needBatch()) return
  candidates.value = []; candSelected.value = []
  fill.villageId = null; fill.beneficiaryName = ''; fill.bulkAmount = null
  fillVisible.value = true
}
async function fillQuery() {
  fillLoading.value = true
  try {
    const list = await rosterEditApi.fillCandidates({ source: fill.source, villageId: fill.villageId || undefined, beneficiaryName: fill.beneficiaryName || undefined })
    candidates.value = (list || []).map(r => ({ ...r, amount: null, standard: null }))
  } finally { fillLoading.value = false }
}
function onCandSelect(s) { candSelected.value = s }
function applyBulkAmount() {
  if (fill.bulkAmount == null) return ElMessage.warning('请先填统一补贴金额')
  candSelected.value.forEach(r => { r.amount = fill.bulkAmount })
}
async function doFillSave() {
  if (!candSelected.value.length) return ElMessage.warning('请先勾选要添加的人员')
  const bad = candSelected.value.some(r => r.amount == null || r.amount === '')
  if (bad) return ElMessage.warning('勾选的人员需填写补贴金额')
  filling.value = true
  try {
    const n = await rosterEditApi.fillSave({ batchId: batchId.value, rows: candSelected.value })
    ElMessage.success(`已添加 ${n ?? 0} 人`); fillVisible.value = false; reload()
  } finally { filling.value = false }
}

// 批量修改
const batchVisible = ref(false); const batchSaving = ref(false); const batchRows = ref([])
function openBatchEdit() {
  if (!needSelected()) return
  batchRows.value = selected.value.map(r => ({ ...r }))
  batchVisible.value = true
}
async function saveBatch() {
  batchSaving.value = true
  try { await rosterEditApi.batchSave(batchRows.value); ElMessage.success('已保存'); batchVisible.value = false; reload() }
  finally { batchSaving.value = false }
}

// 送审 / 取消 / 停发 / 删除批次
async function doSubmit() {
  if (!needBatch()) return
  const res = await rosterEditApi.submit(batchId.value)
  if (res && res.ok === false) {
    // 与补贴对象基础库不一致：弹「信息校验日志」，修改一致后方可送审
    showLog(res.errors || [])
    return
  }
  ElMessage.success('已送审'); refreshBatches()
}
async function doUnsubmit() { if (!needBatch()) return; await rosterEditApi.unsubmit(batchId.value); ElMessage.success('已取消送审'); refreshBatches() }
async function doStop() {
  if (!needSelected()) return
  const { value: reason } = await ElMessageBox.prompt(
    `对选中的 ${selected.value.length} 条明细停发，请填写停发原因（必填）：`, '停发',
    { confirmButtonText: '确定', cancelButtonText: '取消', inputType: 'textarea',
      inputValidator: v => (v && v.trim()) ? true : '停发原因不能为空' })
  const res = await rosterEditApi.stopDetails(selected.value.map(r => String(r.id)), reason.trim())
  ElMessage.success(`已停发 ${res?.count ?? selected.value.length} 人`); reload()
}
async function doDeleteBatch() {
  if (!needBatch()) return
  await ElMessageBox.confirm('确认删除整个批次（含花名册）？', '警告', { type: 'warning' })
  await rosterEditApi.deleteBatch(batchId.value)
  ElMessage.success('批次已删除'); batchId.value = null; rows.value = []; refreshBatches()
}
async function refreshBatches() { batchOptions.value = (await rosterEditApi.pending()) || [] }

// 导入（导入选项弹窗：导出模板 / 选文件 / 前置校验）
const fileInput = ref(null)
const importVisible = ref(false); const importing = ref(false)
const importFile = ref(null)
function openImport() {
  if (!needBatch()) return
  importFile.value = null
  importVisible.value = true
}
function onFileChange(e) {
  importFile.value = e.target.files?.[0] || null
  e.target.value = ''
}
async function downloadTemplate() {
  if (!needBatch()) return   // 模板列按批次所属项目的发放表定义生成
  const blob = await rosterEditApi.template(batchId.value)
  saveBlob(blob, '清册导入模板.xls')
}
async function doImport() {
  if (!importFile.value) return ElMessage.warning('请先选择需要导入的文件')
  importing.value = true
  try {
    const fd = new FormData(); fd.append('file', importFile.value)
    const res = await rosterEditApi.importExcel(batchId.value, fd)
    if (res && res.errors && res.errors.length) {
      // 前置校验不通过：整体不导入，弹「信息校验日志」
      showLog(res.errors)
      return
    }
    ElMessage.success(`导入 ${res?.count ?? 0} 条`)
    importVisible.value = false; importFile.value = null
    reload()
  } finally { importing.value = false }
}

// 信息校验日志（导入 / 送审共用）
const logVisible = ref(false); const logRows = ref([])
function showLog(errors) {
  logRows.value = (errors || []).map(msg => ({ msg }))
  logVisible.value = true
}
function exportLog() {
  const text = logRows.value.map((r, i) => `${i + 1}\t${r.msg}`).join('\r\n')
  // ﻿ BOM：让 Excel/记事本按 UTF-8 识别中文
  saveBlob(new Blob(['﻿序号\t信息校验结果\r\n' + text], { type: 'text/plain;charset=utf-8' }), '信息校验日志.txt')
}

function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob); const a = document.createElement('a')
  a.href = url; a.download = filename; document.body.appendChild(a); a.click()
  document.body.removeChild(a); URL.revokeObjectURL(url)
}
async function doExport() {
  if (!needBatch()) return
  const blob = await rosterEditApi.exportUrl(String(batchId.value))
  const url = URL.createObjectURL(blob); const a = document.createElement('a')
  a.href = url; a.download = '花名册.xlsx'; document.body.appendChild(a); a.click()
  document.body.removeChild(a); URL.revokeObjectURL(url)
}

// 汇总
const summaryVisible = ref(false); const sum = reactive({})
async function openSummary() {
  if (!needBatch()) return
  Object.assign(sum, await rosterEditApi.summary(batchId.value) || {})
  summaryVisible.value = true
}

function money(v) { return v == null ? '' : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }
</script>

<style scoped>
.toolbar :deep(.el-card__body) { padding: 10px 12px; display: flex; flex-wrap: wrap; gap: 8px; }
.toolbar :deep(.el-button) { margin-left: 0; }
.filter { margin: 12px 0; }
.filter :deep(.el-card__body) { padding: 16px; }
.search-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px 24px; }
.sf { display: flex; align-items: center; }
.sf > label { width: 110px; text-align: right; color: #606266; flex: none; }
.sf .el-input, .sf .el-select { flex: 1; }
.sf.actions { justify-content: flex-start; gap: 8px; }
.list-bar { background: #f5f7fa; border-left: 3px solid var(--el-color-primary); padding: 6px 12px; font-weight: 600; margin-bottom: 10px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.single-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 0 16px; }
.fill-search { display: flex; flex-wrap: wrap; align-items: center; gap: 12px 20px; margin-bottom: 12px; }
.fill-search .sf > label { width: auto; }
.req { color: #f56c6c; margin-right: 2px; }
.import-row { display: flex; gap: 8px; align-items: center; }
.log-bar { margin-bottom: 10px; }
</style>
