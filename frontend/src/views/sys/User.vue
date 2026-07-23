<template>
  <div>
    <h2 class="page-title">用户管理</h2>
    <CrudTable ref="crud" :api="userApi" :columns="columns" :search-fields="searchFields"
      :form-fields="formFields" :form-rules="rules" form-width="640px" action-width="220" :detail-before-edit="true">
      <template #column-userType="{ value }">{{ UTYPE[value] || value }}</template>
      <template #column-status="{ value }">
        <el-tag :type="value === 1 ? 'success' : 'info'">{{ value === 1 ? '启用' : '禁用' }}</el-tag>
      </template>
      <template #form-userType="{ form }">
        <el-select v-model="form.userType" style="width:100%">
          <el-option v-for="(l,k) in UTYPE" :key="k" :label="l" :value="k" />
        </el-select>
      </template>
      <template #form-roleIds="{ form }">
        <el-select v-model="form.roleIds" multiple style="width:100%" placeholder="选择角色">
          <el-option v-for="r in roles" :key="r.id" :label="r.roleName" :value="r.id" />
        </el-select>
      </template>
      <template #row-actions="{ row }">
        <el-button type="primary" link @click="crud.editRow(row)">编辑</el-button>
        <el-button type="success" link @click="openData(row)">分配数据</el-button>
        <el-button type="warning" link @click="onReset(row)">重置密码</el-button>
        <el-button type="danger" link @click="crud.handleDelete(row)">删除</el-button>
      </template>
    </CrudTable>

    <!-- 分配数据：区域(机构=orgId) + 项目权限 -->
    <el-dialog v-model="dataVisible" title="分配数据" width="820px">
      <el-form label-width="96px">
        <el-form-item label="所属机构">
          <el-tree-select v-model="dataForm.orgId" :data="orgTree" node-key="id" check-strictly
            :props="{ label: 'orgName', children: 'children' }" placeholder="选择县 / 乡镇 / 部门"
            style="width:100%" filterable clearable />
          <div class="form-hint">部门/财政岗选到「县」或该县民政局/财政局(定本县)，乡镇岗选到具体「乡镇」(定本乡镇)。清空保存=移除机构，该账号将无任何县域数据可见。</div>
        </el-form-item>
        <el-form-item label="项目权限">
          <el-transfer v-model="dataForm.projectIds" :data="transferData" filterable
            filter-placeholder="搜索项目名称" :titles="['可选项目', '已授权项目']"
            :button-texts="['移除', '授权']" class="proj-transfer" />
          <div class="form-hint">右侧=该用户已授权项目。留空即按角色数据范围自动给(本县自建+省级公有)；一旦右侧有项目，则该用户仅能看右侧这些。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dataVisible = false">取消</el-button>
        <el-button type="primary" :loading="dataSaving" @click="saveData">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import CrudTable from '../../components/CrudTable.vue'
import { userApi, userRoleApi, roleApi, orgApi, projectApi } from '../../api/system'

const crud = ref()
const roles = ref([])
const orgTree = ref([])
const projects = ref([])
onMounted(async () => {
  roles.value = (await roleApi.list({})) || []
  orgTree.value = buildOrgTree((await orgApi.tree()) || [])
  projects.value = (await projectApi.list({ tab: 'all' })) || []
})
// el-transfer 需要 {key,label} 结构；label 带县级/省级标记便于区分同名项目
const transferData = computed(() => projects.value.map(p => ({
  key: p.id,
  label: p.projectName + (isCounty(p.projectCode) ? '' : '〔省级〕')
})))
function isCounty(code) { return typeof code === 'string' && /^9622\d{3}/.test(code) }

// /sys/org/tree 返回扁平表，按 parentId 组树供 el-tree-select
function buildOrgTree(flat) {
  const map = {}; flat.forEach(o => map[o.id] = { ...o, children: [] })
  const tree = []
  flat.forEach(o => { const p = map[o.parentId]; if (p) p.children.push(map[o.id]); else tree.push(map[o.id]) })
  return tree
}

const UTYPE = { SYS_ADMIN: '系统管理员', TOWN_OP: '乡镇经办', TOWN_AUDIT: '乡镇审核', DEPT_OP: '部门经办', DEPT_AUDIT: '部门审核', FINANCE: '财政' }
const columns = [
  { prop: 'username', label: '账号', width: 130 },
  { prop: 'realName', label: '姓名', width: 110 },
  { prop: 'userType', label: '类型', width: 110 },
  { prop: 'orgName', label: '所属机构', minWidth: 160 },
  { prop: 'roleNames', label: '角色', minWidth: 140 },
  { prop: 'phone', label: '手机号', width: 130 },
  { prop: 'status', label: '状态', width: 80, align: 'center' }
]
const searchFields = [{ prop: 'username', label: '账号' }, { prop: 'realName', label: '姓名' }]
const formFields = [
  { prop: 'username', label: '账号', span: 12, maxlength: 32 },
  { prop: 'realName', label: '姓名', span: 12, maxlength: 32 },
  { prop: 'userType', label: '用户类型', span: 12 },
  { prop: 'phone', label: '手机号', span: 12, maxlength: 11 },
  { prop: 'roleIds', label: '角色' },
  { prop: 'status', label: '启用', type: 'switch', default: 1 }
]
const rules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }]
}

// 编辑前拉详情带出 roleIds 的逻辑已内建到 CrudTable（:detail-before-edit + crud.editRow）：
// /page 不返回 roleIds，直接用列表行会让角色选择器空白、保存又以空值覆盖 → 静默清角色。
async function onReset(row) {
  await ElMessageBox.confirm(`将 ${row.username} 的密码重置为 123456？`, '提示', { type: 'warning' })
  await userRoleApi.resetPwd(row.id)
  ElMessage.success('已重置为 123456')
}

// ---- 分配数据（区域 orgId + 项目权限）----
const dataVisible = ref(false)
const dataSaving = ref(false)
const dataUserId = ref(null)
const dataForm = reactive({ orgId: null, projectIds: [] })
async function openData(row) {
  dataUserId.value = row.id
  const d = (await userApi.getDataScope(row.id)) || {}
  dataForm.orgId = d.orgId ?? null
  dataForm.projectIds = d.projectIds || []
  dataVisible.value = true
}
async function saveData() {
  dataSaving.value = true
  try {
    await userApi.saveDataScope(dataUserId.value, { orgId: dataForm.orgId, projectIds: dataForm.projectIds })
    ElMessage.success('分配成功')
    dataVisible.value = false
  } finally { dataSaving.value = false }
}
</script>

<style scoped>
.form-hint { color: #909399; font-size: 12px; line-height: 1.5; margin-top: 4px; }
.proj-transfer :deep(.el-transfer) { display: flex; align-items: center; }
.proj-transfer :deep(.el-transfer-panel) { width: 250px; }
.proj-transfer :deep(.el-transfer-panel__body) { height: 300px; }
.proj-transfer :deep(.el-transfer-panel__list.is-filterable) { height: 246px; }
.proj-transfer :deep(.el-transfer__buttons) { padding: 0 12px; }
</style>
