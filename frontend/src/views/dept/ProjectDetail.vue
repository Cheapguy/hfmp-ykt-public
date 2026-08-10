<template>
  <el-dialog :model-value="visible" title="项目信息" width="980px" top="4vh"
    @update:model-value="v => $emit('update:visible', v)">
    <el-form v-loading="loading" label-width="120px" class="proj-detail">
      <el-row :gutter="16">
        <el-col :span="8"><el-form-item label="项目编码">
          <el-input :model-value="d.projectCode" disabled />
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="项目名称">
          <el-input :model-value="d.projectName" disabled />
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="项目简称">
          <el-input :model-value="d.shortName" disabled />
        </el-form-item></el-col>

        <el-col :span="8"><el-form-item label="主管部门">
          <el-input :model-value="d.competentDept" disabled />
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="业务处室">
          <el-input :model-value="d.deptName" disabled />
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="发放类型">
          <el-input :model-value="d.grantType" disabled />
        </el-form-item></el-col>

        <el-col :span="8"><el-form-item label="政策级次">
          <el-input :model-value="POLICY[d.policyLevel] || d.policyLevel" disabled />
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="项目级次">
          <el-input :model-value="PROJLEVEL[d.projectLevel] || d.projectLevel" disabled />
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="预算来源">
          <el-input :model-value="d.budgetSource" disabled />
        </el-form-item></el-col>

        <el-col :span="8"><el-form-item label="追踪代码">
          <el-input :model-value="d.traceCode" disabled />
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="联系人">
          <el-input :model-value="d.contactName" disabled />
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="联系方式">
          <el-input :model-value="d.contactPhone" disabled />
        </el-form-item></el-col>

        <el-col :span="24"><el-form-item label="补贴范围及对象">
          <el-input :model-value="d.subsidyScope" type="textarea" :rows="2" disabled />
        </el-form-item></el-col>
        <el-col :span="24"><el-form-item label="政策文件名称">
          <el-input :model-value="d.policyDocName" type="textarea" :rows="2" disabled />
        </el-form-item></el-col>
        <el-col :span="24"><el-form-item label="政策文号">
          <el-input :model-value="d.policyDocNo" disabled />
        </el-form-item></el-col>

        <el-col :span="24"><el-form-item label="政策文件">
          <div class="file-panel">
            <div class="file-head">
              <span class="file-head-label">政策文件</span>
              <span class="tip">本系统为非涉密平台、严禁传输国家秘密，请确保扫描、上传的文件资料不涉及国家秘密</span>
            </div>
            <!-- 只读视图：只留下载与预览，不给重新上传/删除 -->
            <el-table :data="files" border size="small" empty-text="暂无附件">
              <el-table-column type="index" label="序号" width="60" align="center" />
              <el-table-column prop="fileName" label="文件名称" min-width="240" show-overflow-tooltip />
              <el-table-column label="文件大小" width="110" align="right">
                <template #default="{ row }">{{ sizeText(row.fileSize) }}</template>
              </el-table-column>
              <el-table-column prop="uploadName" label="上传人" width="100" align="center">
                <template #default="{ row }">{{ row.uploadName || '—' }}</template>
              </el-table-column>
              <el-table-column label="下载" width="70" align="center">
                <template #default="{ row }">
                  <el-button link type="success" :icon="Download" title="下载" @click="open(row)" />
                </template>
              </el-table-column>
              <el-table-column label="预览" width="70" align="center">
                <template #default="{ row }">
                  <el-button link type="primary" :icon="View" title="预览"
                    :disabled="!previewable(row)" @click="open(row)" />
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-form-item></el-col>

        <el-col :span="24"><el-form-item label="补贴标准">
          <el-input :model-value="d.subsidyStandard" type="textarea" :rows="2" disabled />
        </el-form-item></el-col>
      </el-row>
    </el-form>
    <template #footer>
      <el-button type="primary" @click="$emit('update:visible', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { Download, View } from '@element-plus/icons-vue'
import { projectApi } from '../../api/system'

const props = defineProps({
  visible: { type: Boolean, default: false },
  row: { type: Object, default: null }
})
defineEmits(['update:visible'])

const POLICY = { CENTRAL: '中央级', PROVINCE: '省级', CITY: '市级', COUNTY: '县（区）级' }
const PROJLEVEL = { PROV_SELF: '省级自建项目', PROV_CATALOG: '省级目录清单项目', CITY_SELF: '市级自建项目', COUNTY_SELF: '县级自建项目' }
const PREVIEWABLE = /\.(pdf|png|jpg|jpeg)$/i

const d = ref({})
const files = ref([])
const loading = ref(false)

watch(() => props.visible, async v => {
  if (!v || !props.row) return
  // 列表行只有列表用到的那几列，详情要展示补贴范围/标准/政策文号等，得单独拉一次
  d.value = props.row
  files.value = []
  loading.value = true
  try {
    d.value = (await projectApi.detail(props.row.id)) || props.row
    files.value = (await projectApi.files(props.row.id)) || []
  } finally { loading.value = false }
})

function open(row) { window.open(row.fileUrl, '_blank') }
function previewable(row) { return PREVIEWABLE.test(row.fileName || '') }
function sizeText(n) {
  const v = Number(n)
  if (!v || v <= 0) return '—'
  if (v < 1024) return v + ' B'
  if (v < 1024 * 1024) return (v / 1024).toFixed(2) + ' KB'
  return (v / 1024 / 1024).toFixed(2) + ' MB'
}
</script>

<style scoped>
.proj-detail :deep(.el-form-item) { margin-bottom: 14px; }
.tip { color: #f56c6c; font-size: 12px; line-height: 1.5; }
.file-panel { width: 100%; border: 1px solid var(--el-border-color-lighter); }
.file-head {
  display: flex; align-items: center; gap: 12px;
  padding: 6px 10px; background: var(--el-fill-color-light);
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.file-head-label { font-weight: 600; white-space: nowrap; }
</style>
