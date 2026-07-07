<template>
  <div>
    <h2 class="page-title">开户银行设置维护</h2>
    <CrudTable
      ref="crud"
      :api="bankApi"
      :columns="columns"
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
      </template>
    </CrudTable>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Edit } from '@element-plus/icons-vue'
import CrudTable from '../../components/CrudTable.vue'
import { bankApi } from '../../api/system'

const crud = ref()
const sel = ref([])

function onEdit() {
  if (sel.value.length !== 1) return ElMessage.warning('请选中一条记录进行修改')
  crud.value.openForm(sel.value[0])
}

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
