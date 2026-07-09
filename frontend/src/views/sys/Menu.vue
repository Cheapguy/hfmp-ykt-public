<template>
  <div>
    <h2 class="page-title">菜单管理</h2>
    <el-card shadow="never">
      <div class="toolbar">
        <el-button type="primary" @click="openForm(null, 0)">新增顶级目录</el-button>
        <el-button @click="load">刷新</el-button>
      </div>
      <el-table :data="tree" row-key="id" border default-expand-all
        :tree-props="{ children: 'children' }" v-loading="loading">
        <el-table-column prop="menuName" label="菜单名称" min-width="220">
          <template #default="{ row }">
            <span>{{ row.menuName }}</span>
            <el-tag v-if="row.visible === 0" size="small" type="info" class="row-tag">隐藏</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="menuType" label="类型" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="TYPE_TAG[row.menuType] || 'info'" size="small">{{ TYPE[row.menuType] || row.menuType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="path" label="路由地址" min-width="180" />
        <el-table-column prop="permission" label="权限码" min-width="140" />
        <el-table-column prop="sortNo" label="排序" width="80" align="center" />
        <el-table-column label="操作" width="200" align="center">
          <template #default="{ row }">
            <el-button v-if="row.menuType !== 'F'" type="primary" link @click="openForm(null, row.id)">新增子级</el-button>
            <el-button type="primary" link @click="openForm(row)">编辑</el-button>
            <el-button type="danger" link @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="formVisible" :title="form.id ? '编辑菜单' : '新增菜单'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
        <el-form-item label="父级菜单">
          <el-tree-select v-model="form.parentId" :data="parentOptions" node-key="id" check-strictly
            :props="{ label: 'menuName', children: 'children' }" style="width:100%" clearable
            placeholder="留空为顶级" />
        </el-form-item>
        <el-form-item label="菜单名称" prop="menuName">
          <el-input v-model="form.menuName" maxlength="32" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="form.menuType">
            <el-radio value="M">目录</el-radio><el-radio value="C">菜单</el-radio><el-radio value="F">按钮</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="顶级分组" prop="menuCode">
          <el-input v-model="form.menuCode" maxlength="32" placeholder="sys / setup / dept / roster / pay / audit / report" />
        </el-form-item>
        <el-form-item v-if="form.menuType === 'C'" label="路由地址">
          <el-input v-model="form.path" maxlength="128" placeholder="/dept/xxx（须与前端路由一致）" />
        </el-form-item>
        <el-form-item v-if="form.menuType === 'F'" label="权限码">
          <el-input v-model="form.permission" maxlength="64" placeholder="如 sys:user:reset（配合 v-perm 控制按钮）" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortNo" :min="0" style="width:160px" />
        </el-form-item>
        <el-form-item label="侧边栏显示">
          <el-switch v-model="form.visible" :active-value="1" :inactive-value="0" />
          <span class="hint">隐藏后不出现在侧边栏，但仍可作为接口/子页权限挂载点</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { menuApi } from '../../api/system'

const TYPE = { M: '目录', C: '菜单', F: '按钮' }
const TYPE_TAG = { M: 'warning', C: 'success', F: 'info' }

const loading = ref(false)
const flat = ref([])
const tree = computed(() => buildTree(flat.value))
// 父级下拉只给 目录/菜单（按钮不能再挂子级）
const parentOptions = computed(() => buildTree(flat.value.filter(m => m.menuType !== 'F')))

function buildTree(rows) {
  const map = {}; rows.forEach(m => map[m.id] = { ...m, children: [] })
  const out = []
  rows.forEach(m => { const p = m.parentId; if (p && map[p]) map[p].children.push(map[m.id]); else out.push(map[m.id]) })
  const sortDeep = ns => { ns.sort((a, b) => (a.sortNo || 0) - (b.sortNo || 0)); ns.forEach(n => n.children.length && sortDeep(n.children)) }
  sortDeep(out)
  return out
}

async function load() {
  loading.value = true
  try { flat.value = (await menuApi.tree()) || [] }
  finally { loading.value = false }
}
onMounted(load)

const formVisible = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive({})
const rules = { menuName: [{ required: true, message: '请输入菜单名称', trigger: 'blur' }] }

function openForm(row, parentId) {
  const init = row
    ? { ...row, children: undefined }
    : { id: null, parentId: parentId || 0, menuName: '', menuCode: '', menuType: parentId ? 'C' : 'M', path: '', permission: '', sortNo: 0, visible: 1 }
  Object.keys(form).forEach(k => delete form[k])
  Object.assign(form, init)
  if (form.parentId === 0) form.parentId = null   // tree-select 里 0 无对应节点，置空=顶级
  formVisible.value = true
}

async function onSave() {
  await formRef.value.validate()
  saving.value = true
  try {
    await menuApi.save({ ...form, parentId: form.parentId || 0 })
    ElMessage.success('已保存')
    formVisible.value = false
    load()
  } finally { saving.value = false }
}

async function onDelete(row) {
  await ElMessageBox.confirm(`删除菜单「${row.menuName}」？已授权该菜单的角色将失去对应权限。`, '提示', { type: 'warning' })
  await menuApi.delete(row.id)
  ElMessage.success('已删除')
  load()
}
</script>

<style scoped>
.toolbar { margin-bottom: 12px; display: flex; gap: 8px; }
.row-tag { margin-left: 6px; }
.hint { margin-left: 10px; font-size: 12px; color: #909399; }
</style>
