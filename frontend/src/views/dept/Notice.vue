<template>
  <div>
    <h2 class="page-title">通知公告管理</h2>

    <el-card shadow="never" class="bar">
      <el-button type="primary" :icon="Upload" @click="openUpload">上传通知</el-button>
      <el-button type="danger" :icon="Delete" :disabled="!selected.length" @click="doDelete">删除</el-button>
      <el-button :icon="Promotion" :disabled="selected.length !== 1" @click="openDispatch">下发单位</el-button>
      <span class="tip" v-if="selected.length !== 1">（下发单位需勾选单条公告）</span>
    </el-card>

    <el-card shadow="never">
      <el-table ref="tblRef" v-loading="loading" :data="rows" border stripe :row-key="r => r.id"
        @selection-change="s => selected = s">
        <el-table-column type="selection" width="44" reserve-selection />
        <el-table-column type="index" label="序号" width="70" align="center" />
        <el-table-column prop="fileName" label="公告" min-width="360" show-overflow-tooltip>
          <template #default="{ row }">
            <a v-if="row.fileUrl" :href="row.fileUrl" target="_blank" class="file-link">{{ row.fileName || row.title }}</a>
            <span v-else>{{ row.fileName || row.title }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="140" align="center">
          <template #default="{ row }">{{ extOf(row.fileName) }}</template>
        </el-table-column>
        <el-table-column label="下发单位" width="120" align="center">
          <template #default="{ row }">
            <el-tag v-if="targetCount(row)" type="success" size="small">{{ targetCount(row) }} 个乡镇</el-tag>
            <el-tag v-else type="info" size="small">未下发</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="200" align="center">
          <template #default="{ row }">{{ fmt(row.createTime) }}</template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="page.pageNum" v-model:page-size="page.pageSize" :total="page.total"
          :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next" background
          @size-change="reload" @current-change="reload" />
      </div>
    </el-card>

    <!-- 导入选项 -->
    <el-dialog v-model="upVisible" title="导入选项" width="560px" @closed="onUpClosed">
      <div class="up-warn">请勿上传涉密和违反国家有关政策、法规的文件、图片等！</div>
      <el-upload ref="upRef" drag :auto-upload="false" :limit="1" :on-change="onFileChange"
        :on-exceed="onExceed" :on-remove="() => file = null">
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">将文件拖到此处，或<em>点击选择</em></div>
      </el-upload>
      <template #footer>
        <el-button type="primary" :loading="uploading" @click="doUpload">导 入</el-button>
        <el-button @click="upVisible = false">取 消</el-button>
      </template>
    </el-dialog>

    <!-- 通知下发单位 -->
    <el-dialog v-model="dpVisible" title="通知下发单位" width="720px" top="6vh" @open="loadTowns">
      <div class="dp-head">
        公告：<b>{{ curNotice?.fileName || curNotice?.title }}</b>
      </div>
      <el-table ref="townRef" v-loading="townLoading" :data="towns" border stripe height="460"
        :row-key="r => String(r.id)" @selection-change="s => townSel = s">
        <el-table-column type="selection" width="44" />
        <el-table-column type="index" label="序号" width="70" align="center" />
        <el-table-column prop="name" label="单位" min-width="360" show-overflow-tooltip />
      </el-table>
      <template #footer>
        <el-button type="primary" @click="doDispatch">确 定（{{ townSel.length }}）</el-button>
        <el-button @click="dpVisible = false">取 消</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Upload, Delete, Promotion, UploadFilled } from '@element-plus/icons-vue'
import { noticeApi } from '../../api/system'

// ---- 列表 ----
const tblRef = ref(null)
const rows = ref([])
const selected = ref([])
const loading = ref(false)
const page = reactive({ pageNum: 1, pageSize: 10, total: 0 })

async function reload() {
  loading.value = true
  try {
    const res = await noticeApi.page({ pageNum: page.pageNum, pageSize: page.pageSize })
    rows.value = res?.records || []
    page.total = Number(res?.total) || 0
  } finally { loading.value = false }
}

function extOf(name) {
  if (!name) return '-'
  const i = name.lastIndexOf('.')
  return i >= 0 ? name.slice(i + 1).toLowerCase() : '-'
}
function targetCount(row) {
  return row.targetOrgIds ? row.targetOrgIds.split(',').filter(Boolean).length : 0
}
function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '' }

// ---- 删除 ----
async function doDelete() {
  if (!selected.value.length) return
  await ElMessageBox.confirm(`确定删除选中的 ${selected.value.length} 条通知？`, '删除', { type: 'warning' })
  for (const r of selected.value) await noticeApi.delete(r.id)
  ElMessage.success('已删除')
  tblRef.value?.clearSelection()
  reload()
}

// ---- 上传 ----
const upVisible = ref(false)
const upRef = ref(null)
const file = ref(null)
const uploading = ref(false)

function openUpload() { file.value = null; upVisible.value = true }
function onFileChange(f) { file.value = f.raw }
function onExceed(files) {
  upRef.value?.clearFiles()
  const f = files[0]
  upRef.value?.handleStart(f)
  file.value = f
}
function onUpClosed() { upRef.value?.clearFiles(); file.value = null }

async function doUpload() {
  if (!file.value) return ElMessage.warning('请选择要导入的文件')
  const fd = new FormData()
  fd.append('file', file.value)
  uploading.value = true
  try {
    await noticeApi.upload(fd)
    ElMessage.success('上传成功')
    upVisible.value = false
    page.pageNum = 1
    reload()
  } finally { uploading.value = false }
}

// ---- 下发单位 ----
const dpVisible = ref(false)
const townRef = ref(null)
const towns = ref([])
const townSel = ref([])
const townLoading = ref(false)
const curNotice = ref(null)

function openDispatch() {
  if (selected.value.length !== 1) return
  curNotice.value = selected.value[0]
  dpVisible.value = true
}
async function loadTowns() {
  townLoading.value = true
  try {
    towns.value = (await noticeApi.towns()) || []
    // 回显该公告已下发的乡镇
    const has = new Set((curNotice.value?.targetOrgIds || '').split(',').filter(Boolean))
    await nextTick()
    towns.value.forEach(t => townRef.value?.toggleRowSelection(t, has.has(String(t.id))))
  } finally { townLoading.value = false }
}
async function doDispatch() {
  const ids = townSel.value.map(t => t.id)
  await noticeApi.dispatch(curNotice.value.id, ids)
  ElMessage.success(ids.length ? `已下发给 ${ids.length} 个乡镇` : '已收回下发')
  dpVisible.value = false
  reload()
}

onMounted(reload)
</script>

<style scoped>
.bar { margin-bottom: 12px; }
.bar :deep(.el-card__body) { padding: 12px 16px; display: flex; align-items: center; gap: 8px; }
.tip { color: #c0c4cc; font-size: 12px; }
.file-link { color: var(--el-color-primary); text-decoration: none; }
.file-link:hover { text-decoration: underline; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.up-warn { color: #f56c6c; font-size: 13px; margin-bottom: 12px; text-align: center; }
.dp-head { margin-bottom: 10px; color: #606266; }
</style>
