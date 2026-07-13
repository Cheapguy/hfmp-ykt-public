<template>
  <div>
    <h2 class="page-title">开户银行设置维护</h2>
    <CrudTable
      ref="crud"
      :api="bankApi"
      :columns="columns"
      :search-fields="searchFields"
      :form-fields="formFields"
      :form-rules="rules"
      form-title-noun="开户银行"
      selectable
      :enable-edit="false"
      :enable-delete="false"
      @selection-change="s => sel = s"
    >
      <template #toolbar-left>
        <el-button type="primary" :icon="Plus" @click="crud.openForm()">新增</el-button>
        <el-button :icon="Edit" @click="onEdit">修改</el-button>
        <el-button type="danger" :icon="Delete" @click="onDelete">删除</el-button>
      </template>
    </CrudTable>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete } from '@element-plus/icons-vue'
import CrudTable from '../../components/CrudTable.vue'
import { bankApi } from '../../api/system'

const crud = ref()
const sel = ref([])

function onEdit() {
  if (sel.value.length !== 1) return ElMessage.warning('请选中一条记录进行修改')
  crud.value.openForm(sel.value[0])
}

async function onDelete() {
  if (!sel.value.length) return ElMessage.warning('请选中要删除的记录')
  await ElMessageBox.confirm(`确定删除选中的 ${sel.value.length} 家银行？此操作不可恢复`, '删除确认', { type: 'warning' })
  let ok = 0
  for (const row of sel.value) {
    try { await bankApi.delete(row.id); ok++ } catch { /* 被引用等失败已由拦截器弹出提示 */ }
  }
  if (ok) ElMessage.success(`已删除 ${ok} 条`)
  crud.value.clearSelection()
  crud.value.reload()
}

const searchFields = [
  { prop: 'bankCode', label: '银行编码' },
  { prop: 'bankName', label: '银行名称' },
  { prop: 'unionCode', label: '银行行号' }
]
const columns = [
  { prop: 'bankCode', label: '银行编码', width: 200 },
  { prop: 'bankName', label: '银行名称', minWidth: 280 },
  { prop: 'unionCode', label: '银行行号', width: 200 }
]
const formFields = [
  { prop: 'bankCode', label: '银行编码', maxlength: 32 },
  { prop: 'bankName', label: '银行名称', maxlength: 64 },
  { prop: 'unionCode', label: '银行行号', maxlength: 32 }
]
const rules = {
  bankCode: [{ required: true, message: '请输入银行编码', trigger: 'blur' }],
  bankName: [{ required: true, message: '请输入银行名称', trigger: 'blur' }]
}
</script>
