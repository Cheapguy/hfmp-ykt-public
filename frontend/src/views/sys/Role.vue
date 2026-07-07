<template>
  <div>
    <h2 class="page-title">角色管理</h2>
    <CrudTable ref="crud" :api="roleApi" :columns="columns" :form-fields="formFields" :form-rules="rules">
      <template #column-dataScope="{ value }">
        <el-tag :type="SCOPE_TYPE[value] || 'info'">{{ SCOPE[value] || '全部' }}</el-tag>
      </template>
      <template #form-dataScope="{ form }">
        <el-select v-model="form.dataScope" style="width:100%" placeholder="选择数据范围">
          <el-option v-for="(l,k) in SCOPE" :key="k" :label="l" :value="k" />
        </el-select>
      </template>
      <template #row-actions="{ row }">
        <el-button type="primary" link @click="crud.openForm(row)">编辑</el-button>
        <el-button type="success" link @click="openAssign(row)">分配菜单</el-button>
        <el-button type="danger" link @click="crud.handleDelete(row)">删除</el-button>
      </template>
    </CrudTable>

    <el-dialog v-model="assignVisible" title="分配菜单权限" width="420px">
      <el-tree ref="treeRef" :data="menuTree" show-checkbox node-key="id" default-expand-all
        :props="{ label: 'menuName', children: 'children' }" />
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" @click="doAssign">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import CrudTable from '../../components/CrudTable.vue'
import { roleApi, roleMenuApi, menuApi } from '../../api/system'

const crud = ref()
// 数据范围（县域隔离）：值→中文；具体县/乡镇由用户绑定机构自动推出
const SCOPE = { ALL: '全部', COUNTY: '本县', OWN_ORG: '本机构' }
const SCOPE_TYPE = { ALL: 'danger', COUNTY: 'warning', OWN_ORG: 'success' }
const columns = [
  { prop: 'roleCode', label: '角色编码', width: 200 },
  { prop: 'roleName', label: '角色名称', minWidth: 180 },
  { prop: 'dataScope', label: '数据范围', width: 110, align: 'center' },
  { prop: 'remark', label: '备注', minWidth: 180 }
]
const formFields = [
  { prop: 'roleCode', label: '角色编码', maxlength: 64 },
  { prop: 'roleName', label: '角色名称', maxlength: 64 },
  { prop: 'dataScope', label: '数据范围', default: 'ALL' },
  { prop: 'remark', label: '备注', type: 'textarea' }
]
const rules = { roleName: [{ required: true, message: '请输入角色名称', trigger: 'blur' }] }

const menuTree = ref([])
onMounted(async () => {
  const flat = await menuApi.tree()
  menuTree.value = buildTree(flat || [])
})
function buildTree(flat) {
  const map = {}; flat.forEach(m => map[m.id] = { ...m, children: [] })
  const tree = []
  flat.forEach(m => { const p = m.parentId; if (p && map[p]) map[p].children.push(map[m.id]); else tree.push(map[m.id]) })
  return tree
}

const assignVisible = ref(false)
const treeRef = ref()
const curRole = ref(null)
async function openAssign(row) {
  curRole.value = row
  assignVisible.value = true
  const ids = await roleMenuApi.getMenuIds(row.id)
  await new Promise(r => setTimeout(r, 0))
  // 仅勾选叶子，父节点交给 el-tree 半选逻辑
  treeRef.value.setCheckedKeys(ids || [])
}
async function doAssign() {
  const checked = treeRef.value.getCheckedKeys()
  const half = treeRef.value.getHalfCheckedKeys()
  await roleMenuApi.assign(curRole.value.id, [...checked, ...half])
  ElMessage.success('分配成功')
  assignVisible.value = false
}
</script>
