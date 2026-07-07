<template>
  <div>
    <h2 class="page-title">菜单管理</h2>
    <CrudTable :api="menuApi" :columns="columns" :form-fields="formFields" :form-rules="rules" form-width="640px">
      <template #column-menuType="{ value }">{{ ({ M:'目录', C:'菜单', F:'按钮' })[value] || value }}</template>
      <template #form-menuType="{ form }">
        <el-radio-group v-model="form.menuType">
          <el-radio value="M">目录</el-radio><el-radio value="C">菜单</el-radio><el-radio value="F">按钮</el-radio>
        </el-radio-group>
      </template>
    </CrudTable>
  </div>
</template>

<script setup>
import CrudTable from '../../components/CrudTable.vue'
import { menuApi } from '../../api/system'

const columns = [
  { prop: 'id', label: 'ID', width: 90 },
  { prop: 'parentId', label: '父ID', width: 90 },
  { prop: 'menuName', label: '菜单名称', minWidth: 160 },
  { prop: 'menuCode', label: '分组', width: 100 },
  { prop: 'menuType', label: '类型', width: 90, align: 'center' },
  { prop: 'path', label: '路由', minWidth: 160 },
  { prop: 'sortNo', label: '排序', width: 80 }
]
const formFields = [
  { prop: 'parentId', label: '父级ID', type: 'number', span: 12, default: 0 },
  { prop: 'sortNo', label: '排序', type: 'number', span: 12 },
  { prop: 'menuName', label: '菜单名称', span: 12, maxlength: 32 },
  { prop: 'menuCode', label: '顶级分组code', span: 12, maxlength: 32, tip: 'sys/setup/dept/roster/pay' },
  { prop: 'menuType', label: '类型', default: 'C' },
  { prop: 'path', label: '路由地址', maxlength: 128 },
  { prop: 'visible', label: '显示', type: 'switch', default: 1 }
]
const rules = { menuName: [{ required: true, message: '请输入菜单名称', trigger: 'blur' }] }
</script>
