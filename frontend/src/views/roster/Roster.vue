<template>
  <div>
    <h2 class="page-title">花名册维护</h2>

    <el-card shadow="never" style="margin-bottom:12px">
      <el-form inline>
        <el-form-item label="选择批次">
          <el-select v-model="batchId" placeholder="请选择已下达批次" style="width:320px" @change="reload">
            <el-option v-for="b in batches" :key="b.id" :label="`${b.batchName}（${STATUS_LABEL[b.status]}）`" :value="b.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :disabled="!batchId" @click="fillVisible = true">批量填报</el-button>
          <el-button :disabled="!batchId" @click="openSingle">新增</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column type="index" label="#" width="55" align="center" />
        <el-table-column prop="name" label="姓名" width="100" />
        <el-table-column prop="idCard" label="身份证号" width="190" />
        <el-table-column prop="socialCard" label="社保卡号" width="150" />
        <el-table-column prop="standard" label="补贴标准" width="110" />
        <el-table-column prop="amount" label="补贴金额" width="110" />
        <el-table-column label="审核状态" width="120" align="center">
          <template #default="{ row }"><el-tag :type="AUDIT_TYPE[row.auditStatus] || 'info'">{{ AUDIT_LABEL[row.auditStatus] || row.auditStatus }}</el-tag></template>
        </el-table-column>
        <el-table-column label="支付状态" width="120" align="center">
          <template #default="{ row }"><el-tag :type="PAY_TYPE[row.payStatus] || 'info'">{{ PAY_LABEL[row.payStatus] || row.payStatus }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="220" align="center" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.auditStatus === 'DRAFT'" type="primary" link @click="openSingle(row)">编辑</el-button>
            <el-button v-if="row.auditStatus === 'DRAFT'" type="success" link @click="act('submit', row)">送审</el-button>
            <el-button v-if="canAudit(row)" type="success" link @click="act('audit', row)">审核</el-button>
            <el-button v-if="row.auditStatus !== 'DRAFT' && row.auditStatus !== 'FINAL'" type="warning" link @click="act('back', row)">退回</el-button>
            <el-button v-if="row.auditStatus === 'DRAFT'" type="danger" link @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div style="display:flex;justify-content:flex-end;margin-top:12px">
        <el-pagination v-model:current-page="page.pageNum" v-model:page-size="page.pageSize" :total="page.total"
          layout="total, prev, pager, next" background @current-change="reload" />
      </div>
    </el-card>

    <!-- 批量填报 -->
    <el-dialog v-model="fillVisible" title="批量填报（从补贴对象库）" width="520px">
      <el-form label-width="100px">
        <el-form-item label="乡镇">
          <el-select v-model="fillForm.townId" style="width:100%" @change="loadVillages">
            <el-option v-for="t in towns" :key="t.id" :label="t.orgName" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="村组">
          <el-select v-model="fillForm.villageId" clearable style="width:100%">
            <el-option v-for="v in villages" :key="v.id" :label="v.villageName" :value="v.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="补贴标准"><el-input-number v-model="fillForm.standard" :min="0" style="width:100%" /></el-form-item>
        <el-form-item label="补贴金额"><el-input-number v-model="fillForm.amount" :min="0" style="width:100%" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="fillVisible = false">取消</el-button>
        <el-button type="primary" @click="doFill">填报</el-button>
      </template>
    </el-dialog>

    <!-- 单条编辑 -->
    <el-dialog v-model="singleVisible" :title="singleForm.id ? '编辑' : '新增'" width="520px">
      <el-form :model="singleForm" label-width="100px">
        <el-form-item label="姓名"><el-input v-model="singleForm.name" /></el-form-item>
        <el-form-item label="身份证号"><el-input v-model="singleForm.idCard" /></el-form-item>
        <el-form-item label="社保卡号"><el-input v-model="singleForm.socialCard" /></el-form-item>
        <el-form-item label="补贴标准"><el-input-number v-model="singleForm.standard" :min="0" style="width:100%" /></el-form-item>
        <el-form-item label="补贴金额"><el-input-number v-model="singleForm.amount" :min="0" style="width:100%" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="singleVisible = false">取消</el-button>
        <el-button type="primary" @click="doSaveSingle">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { rosterApi, batchApi, villageApi, orgApi } from '../../api/system'

const STATUS_LABEL = { NEW: '未下达', ISSUED: '已下达', SENT: '已发送', PAID: '已支付', PAID_OUT: '已发放' }
const AUDIT_LABEL = { DRAFT: '待编制', TOWN_SUBMIT: '乡镇送审', TOWN_AUDIT: '乡镇审核', DEPT_SUBMIT: '部门送审', FINAL: '终审' }
const AUDIT_TYPE = { DRAFT: 'info', TOWN_SUBMIT: 'warning', TOWN_AUDIT: 'warning', DEPT_SUBMIT: 'warning', FINAL: 'success' }
const PAY_LABEL = { NONE: '未生成', APPLIED: '已生成申请', PAID: '已支付', REFUNDED: '已退款', RETURNED: '已退回' }
const PAY_TYPE = { NONE: 'info', APPLIED: 'warning', PAID: 'success' }

const batches = ref([]); const towns = ref([]); const villages = ref([])
const batchId = ref(null)
const rows = ref([]); const loading = ref(false)
const page = reactive({ pageNum: 1, pageSize: 10, total: 0 })

onMounted(async () => {
  const list = await batchApi.page({ pageNum: 1, pageSize: 100 })
  batches.value = (list?.records || []).filter(b => ['ISSUED', 'SENT', 'PAID', 'PAID_OUT'].includes(b.status))
  towns.value = ((await orgApi.tree()) || []).filter(o => o.orgType === 'TOWN')
})

async function reload() {
  if (!batchId.value) { rows.value = []; return }
  loading.value = true
  try {
    const res = await rosterApi.page({ pageNum: page.pageNum, pageSize: page.pageSize, batchId: batchId.value })
    rows.value = res?.records || []
    page.total = res?.total || 0
  } finally { loading.value = false }
}

function canAudit(row) { return ['TOWN_SUBMIT', 'TOWN_AUDIT', 'DEPT_SUBMIT'].includes(row.auditStatus) }

async function act(fn, row) {
  try { await rosterApi[fn](row.id); ElMessage.success('操作成功'); reload() } catch (e) {}
}
async function onDelete(row) {
  await ElMessageBox.confirm('确定删除该花名册记录？', '提示', { type: 'warning' })
  await rosterApi.delete(row.id); ElMessage.success('删除成功'); reload()
}

// 批量填报
const fillVisible = ref(false)
const fillForm = reactive({ townId: null, villageId: null, standard: 0, amount: 0 })
async function loadVillages() {
  fillForm.villageId = null
  villages.value = (await villageApi.list({ townId: fillForm.townId })) || []
}
async function doFill() {
  const res = await rosterApi.batchFill({ batchId: batchId.value, ...fillForm })
  ElMessage.success(`已填报 ${res?.filled ?? 0} 人`)
  fillVisible.value = false
  reload()
}

// 单条
const singleVisible = ref(false)
const singleForm = reactive({ id: null, name: '', idCard: '', socialCard: '', standard: 0, amount: 0 })
function openSingle(row) {
  Object.assign(singleForm, { id: null, name: '', idCard: '', socialCard: '', standard: 0, amount: 0 }, row && row.id ? row : {})
  singleVisible.value = true
}
async function doSaveSingle() {
  await rosterApi.save({ ...singleForm, batchId: batchId.value })
  ElMessage.success('保存成功')
  singleVisible.value = false
  reload()
}
</script>
