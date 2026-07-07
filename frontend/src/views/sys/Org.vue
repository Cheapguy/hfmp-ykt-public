<template>
  <div>
    <h2 class="page-title">机构管理</h2>
    <CrudTable :api="orgApi" :columns="columns" :search-fields="searchFields" :form-fields="formFields" :form-rules="rules">
      <template #column-orgType="{ value }">{{ TYPE[value] || value }}</template>
      <template #form-orgType="{ form }">
        <el-select v-model="form.orgType" style="width:100%">
          <el-option v-for="(l,k) in TYPE" :key="k" :label="l" :value="k" />
        </el-select>
      </template>
      <template #form-parentId="{ form }">
        <el-input-number v-model="form.parentId" :min="0" style="width:100%" />
      </template>
    </CrudTable>
  </div>
</template>

<script setup>
import CrudTable from '../../components/CrudTable.vue'
import { orgApi } from '../../api/system'

const TYPE = { COUNTY: '县级', DEPT: '主管部门', TOWN: '乡镇' }
const columns = [
  { prop: 'orgCode', label: '机构编码', width: 140 },
  { prop: 'orgName', label: '机构名称', minWidth: 200 },
  { prop: 'orgType', label: '类型', width: 120 },
  { prop: 'sortNo', label: '排序', width: 80 }
]
const searchFields = [{ prop: 'orgName', label: '机构名称' }]
const formFields = [
  { prop: 'parentId', label: '上级机构ID', default: 0 },
  { prop: 'orgCode', label: '机构编码', maxlength: 32 },
  { prop: 'orgName', label: '机构名称', maxlength: 64 },
  { prop: 'orgType', label: '机构类型' },
  { prop: 'sortNo', label: '排序', type: 'number' }
]
const rules = { orgName: [{ required: true, message: '请输入机构名称', trigger: 'blur' }] }
</script>
