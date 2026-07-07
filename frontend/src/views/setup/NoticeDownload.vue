<template>
  <div>
    <el-card shadow="never" class="bar">
      <el-button type="primary" :icon="Download" @click="download">下载</el-button>
    </el-card>
    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" border stripe @selection-change="s => selected = s">
        <el-table-column type="selection" width="44" />
        <el-table-column type="index" label="序号" width="70" align="center" />
        <el-table-column prop="fileName" label="通知" min-width="360" show-overflow-tooltip>
          <template #default="{ row }">{{ row.fileName || row.title }}</template>
        </el-table-column>
        <el-table-column label="类型" width="120" align="center">
          <template #default="{ row }">{{ extOf(row.fileName) }}</template>
        </el-table-column>
        <el-table-column prop="createTime" label="下发时间" width="200" align="center">
          <template #default="{ row }">{{ fmt(row.createTime) }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!rows.length && !loading" description="暂无下发给本单位的通知" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import { noticeApi } from '../../api/system'

const rows = ref([]); const loading = ref(false); const selected = ref([])

onMounted(reload)
async function reload() {
  loading.value = true
  try {
    rows.value = (await noticeApi.mine()) || []
  } finally { loading.value = false }
}

function download() {
  if (!selected.value.length) return ElMessage.warning('请勾选要下载的通知')
  const urls = selected.value.filter(r => r.fileUrl)
  if (!urls.length) return ElMessage.warning('所选通知无附件')
  urls.forEach(r => window.open(r.fileUrl, '_blank'))
}
function extOf(name) {
  if (!name) return '-'
  const i = name.lastIndexOf('.')
  return i >= 0 ? name.slice(i + 1).toLowerCase() : '-'
}
function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '' }
</script>

<style scoped>
.bar :deep(.el-card__body) { padding: 12px 16px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
</style>
