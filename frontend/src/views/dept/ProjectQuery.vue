<template>
  <div>
    <h2 class="page-title">补贴项目查询</h2>

    <!-- 筛选 -->
    <el-card shadow="never" class="filter">
      <el-form inline @submit.prevent>
        <el-form-item label="项目编码：">
          <el-input v-model="query.projectCode" clearable style="width:280px" @keyup.enter="reload" />
        </el-form-item>
        <el-form-item label="项目名称：">
          <el-input v-model="query.projectName" clearable style="width:280px" @keyup.enter="reload" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" round @click="reload">查询</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <el-tabs v-model="tab" @tab-change="onTab">
        <el-tab-pane label="本省项目" name="gansu" />
        <el-tab-pane label="中央项目" name="central" />
      </el-tabs>

      <!-- 本省项目（本省 / 本级及管辖区划） -->
      <template v-if="tab === 'gansu'">
        <el-table v-loading="loading" :data="rows" border stripe size="default">
          <!-- 列序对齐生产：编码 / 名称(可点开详情) / 追踪代码 / 追踪名称 / 主管部门 / 业务科室 /
               发放类型 / 是否自建项目 -->
          <el-table-column type="index" label="序号" width="60" align="center" />
          <el-table-column prop="projectCode" label="项目编码" width="130" />
          <el-table-column prop="projectName" label="项目名称" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <el-button link type="primary" class="name-link" @click="openDetail(row)">{{ row.projectName }}</el-button>
            </template>
          </el-table-column>
          <el-table-column prop="traceCode" label="项目追踪代码" width="150" align="center">
            <template #default="{ row }">{{ row.traceCode || '' }}</template>
          </el-table-column>
          <el-table-column prop="trackProName" label="项目追踪名称" width="180" show-overflow-tooltip />
          <el-table-column prop="competentDept" label="主管部门" width="160" show-overflow-tooltip />
          <el-table-column prop="deptName" label="业务科室" width="140" show-overflow-tooltip />
          <el-table-column prop="grantType" label="发放类型" width="100" align="center" />
          <el-table-column prop="isSelfBuilt" label="是否自建项目" width="110" align="center">
            <template #default="{ row }">{{ row.isSelfBuilt || '' }}</template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination v-model:current-page="page.pageNum" v-model:page-size="page.pageSize" :total="page.total"
            :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next, jumper" background
            @size-change="reload" @current-change="reload" />
        </div>
      </template>

      <!-- 中央项目（全国统一清单） -->
      <template v-else>
        <el-table v-loading="loading" :data="ctlRows" border stripe size="default">
          <!-- 列序对齐生产：编码 / 名称 / 中央主管部门名称 / 项目类型，政策依据是本系统多给的一列 -->
          <el-table-column type="index" label="序号" width="60" align="center" />
          <el-table-column prop="projectCode" label="项目编码" width="100" align="center" />
          <el-table-column prop="projectName" label="项目名称" min-width="240" show-overflow-tooltip />
          <el-table-column prop="competentDept" label="中央主管部门名称" width="220" show-overflow-tooltip />
          <el-table-column prop="category" label="项目类型" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="row.category === '必纳项目' ? 'danger' : 'warning'">{{ row.category }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="policyBasis" label="政策依据" min-width="260" show-overflow-tooltip />
        </el-table>
        <el-empty v-if="!ctlRows.length && !loading" description="暂无数据" />
      </template>
    </el-card>

    <!-- 点项目名称打开的只读详情 -->
    <ProjectDetail v-model:visible="detailVisible" :row="detailRow" />
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { projectApi } from '../../api/system'
import ProjectDetail from './ProjectDetail.vue'

const tab = ref('gansu')
const detailVisible = ref(false); const detailRow = ref(null)
function openDetail(row) { detailRow.value = row; detailVisible.value = true }
const query = reactive({ projectCode: '', projectName: '' })
const rows = ref([]); const ctlRows = ref([]); const loading = ref(false)
const page = reactive({ pageNum: 1, pageSize: 10, total: 0 })

onMounted(reload)

function onTab() { page.pageNum = 1; reload() }

async function reload() {
  loading.value = true
  try {
    if (tab.value === 'gansu') {
      const res = await projectApi.page({
        pageNum: page.pageNum, pageSize: page.pageSize, tab: 'all',
        projectCode: query.projectCode || undefined, projectName: query.projectName || undefined
      })
      rows.value = res?.records || []
      page.total = Number(res?.total) || 0
    } else {
      ctlRows.value = (await projectApi.central({
        projectCode: query.projectCode || undefined, projectName: query.projectName || undefined
      })) || []
    }
  } finally { loading.value = false }
}
</script>

<style scoped>
.filter { margin-bottom: 12px; }
.filter :deep(.el-card__body) { padding: 16px 16px 0; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
/* 项目名称做成链接样式（生产是带下划线的蓝字），但保持单行省略不撑破列宽 */
.name-link { max-width: 100%; text-decoration: underline; }
.name-link :deep(span) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
