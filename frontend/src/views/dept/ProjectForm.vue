<template>
  <el-dialog :model-value="visible" :title="editing ? '修改' : '新增'" width="980px" top="4vh"
    :close-on-click-modal="false" @update:model-value="v => $emit('update:visible', v)" @closed="onClosed">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="120px" class="proj-form">
      <el-row :gutter="16">
        <el-col :span="8"><el-form-item label="项目编码" prop="projectCode">
          <el-input v-model="form.projectCode" placeholder="终审后自动生成，可不填" />
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="项目名称" prop="projectName">
          <el-input v-model="form.projectName" maxlength="128" />
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="项目简称" prop="shortName">
          <el-input v-model="form.shortName" maxlength="7" placeholder="≤7个字" />
        </el-form-item></el-col>

        <el-col :span="8"><el-form-item label="主管部门" prop="competentDept">
          <el-select v-model="form.competentDept" filterable allow-create default-first-option
            style="width:100%" :loading="loadingDict" placeholder="请选择">
            <el-option v-for="o in agencies" :key="o.guid" :label="o.code ? `${o.code}-${o.name}` : o.name"
              :value="o.name" />
          </el-select>
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="业务处室" prop="deptName">
          <el-select v-model="form.deptName" filterable style="width:100%" :loading="loadingDict" placeholder="请选择">
            <el-option v-for="o in bizOffices" :key="o.officeCode"
              :label="`${o.officeCode}-${o.officeName}`" :value="o.officeName" />
          </el-select>
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="发放类型" prop="grantType">
          <el-select v-model="form.grantType" style="width:100%">
            <el-option v-for="o in GRANT_TYPES" :key="o" :label="o" :value="o" />
          </el-select>
        </el-form-item></el-col>

        <el-col :span="8"><el-form-item label="政策级次" prop="policyLevel">
          <el-select v-model="form.policyLevel" style="width:100%" @change="onPolicyChange">
            <el-option v-for="(l,k) in POLICY" :key="k" :label="l" :value="k" />
          </el-select>
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="项目级次" prop="projectLevel">
          <el-select v-model="form.projectLevel" style="width:100%" :disabled="!form.policyLevel"
            :placeholder="form.policyLevel ? '请选择' : '请先选政策级次'">
            <el-option v-for="k in projectLevelOptions" :key="k" :label="PROJLEVEL[k]" :value="k" />
          </el-select>
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="预算来源" prop="budgetSource">
          <el-select v-model="form.budgetSource" clearable style="width:100%" placeholder="请选择">
            <el-option v-for="o in BUDGET_SOURCES" :key="o.code" :label="`${o.code}-${o.name}`" :value="o.name" />
          </el-select>
        </el-form-item></el-col>

        <el-col :span="8"><el-form-item label="追踪代码" prop="traceCode">
          <el-input v-model="form.traceCode" disabled placeholder="由省财政厅农业处终审时核定" />
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="联系人" prop="contactName">
          <el-input v-model="form.contactName" maxlength="30" />
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="联系方式" prop="contactPhone">
          <el-input v-model="form.contactPhone" maxlength="30" />
        </el-form-item></el-col>

        <el-col :span="24"><el-form-item label="补贴范围及对象" prop="subsidyScope">
          <el-input v-model="form.subsidyScope" type="textarea" :rows="2" maxlength="600" show-word-limit />
        </el-form-item></el-col>
        <el-col :span="24"><el-form-item label="政策文件名称" prop="policyDocName">
          <el-input v-model="form.policyDocName" maxlength="200" />
        </el-form-item></el-col>
        <el-col :span="24"><el-form-item label="政策文号" prop="policyDocNo">
          <el-input v-model="form.policyDocNo" maxlength="100" />
        </el-form-item></el-col>

        <el-col :span="24"><el-form-item label="政策文件" prop="files">
          <div class="file-panel">
            <div class="file-head">
              <span class="file-head-label">政策文件</span>
              <span class="tip">本系统为非涉密平台、严禁传输国家秘密，请确保扫描、上传的文件资料不涉及国家秘密</span>
              <el-upload class="up-new" :show-file-list="false" :auto-upload="false"
                         :on-change="f => onPick(f, -1)" :accept="ACCEPT">
                <el-button size="small" :loading="uploading">上传新附件</el-button>
              </el-upload>
            </div>
            <el-table :data="form.files" border size="small" empty-text="暂无附件">
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
                  <el-button link type="success" :icon="Download" title="下载" @click="doDownload(row)" />
                </template>
              </el-table-column>
              <el-table-column label="重新上传" width="90" align="center">
                <template #default="{ $index }">
                  <el-upload :show-file-list="false" :auto-upload="false"
                             :on-change="f => onPick(f, $index)" :accept="ACCEPT">
                    <el-button link type="warning" :icon="Upload" title="重新上传" />
                  </el-upload>
                </template>
              </el-table-column>
              <el-table-column label="预览" width="70" align="center">
                <template #default="{ row }">
                  <el-button link type="primary" :icon="View" title="预览"
                    :disabled="!previewable(row)" @click="doPreview(row)" />
                </template>
              </el-table-column>
              <el-table-column label="删除" width="70" align="center">
                <template #default="{ $index }">
                  <el-button link type="danger" :icon="CircleClose" title="删除" @click="doRemove($index)" />
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-form-item></el-col>

        <el-col :span="24"><el-form-item label="补贴标准" prop="subsidyStandard">
          <el-input v-model="form.subsidyStandard" type="textarea" :rows="2" maxlength="600" show-word-limit />
        </el-form-item></el-col>
      </el-row>
    </el-form>
    <template #footer>
      <el-button @click="$emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Upload, View, CircleClose } from '@element-plus/icons-vue'
import { projectApi, agencyApi } from '../../api/system'
import { downloadFile } from '../../utils/download'

const props = defineProps({
  visible: { type: Boolean, default: false },
  row: { type: Object, default: null }
})
const emit = defineEmits(['update:visible', 'saved'])

const POLICY = { CENTRAL: '中央级', PROVINCE: '省级', CITY: '市级', COUNTY: '县（区）级' }
const PROJLEVEL = { PROV_SELF: '省级自建项目', PROV_CATALOG: '省级目录清单项目', CITY_SELF: '市级自建项目', COUNTY_SELF: '县级自建项目' }
// 区划联动：政策级次 -> 可选项目级次
const LEVEL_MAP = {
  COUNTY: ['COUNTY_SELF'],
  CITY: ['CITY_SELF'],
  PROVINCE: ['PROV_SELF', 'PROV_CATALOG'],
  CENTRAL: ['PROV_SELF', 'PROV_CATALOG']
}
// 发放类型对齐生产：只有到户/到人两种。原先那三项（一卡通发放/社会化发放/现金发放）
// 是脚手架阶段的臆造值，与批量填报的收款人绑定口径(HOUSEHOLD/PERSON)也对不上。
const GRANT_TYPES = ['到户', '到人']
// 预算来源对齐生产：只有本级财力一条
const BUDGET_SOURCES = [{ code: '001', name: '本级财力' }]
const ACCEPT = '.pdf,.doc,.docx,.wps,.xls,.xlsx,.png,.jpg,.jpeg,.ofd'
// 浏览器能直接开的才给预览，其余（doc/xls 等）只能下载——生产也是这几类没有预览按钮
const PREVIEWABLE = /\.(pdf|png|jpg|jpeg)$/i

const formRef = ref()
const saving = ref(false)
const editing = computed(() => !!(props.row && props.row.id))

const blank = () => ({
  id: null, projectCode: '', projectName: '', shortName: '',
  competentDept: '', deptName: '', grantType: '',
  policyLevel: '', projectLevel: '', budgetSource: '', traceCode: '',
  contactName: '', contactPhone: '',
  subsidyScope: '', policyDocName: '', policyDocNo: '', subsidyStandard: '',
  files: []
})
const form = reactive(blank())

const projectLevelOptions = computed(() => LEVEL_MAP[form.policyLevel] || [])

// ===== 字典：主管部门(机构) / 业务处室 / 本账号可选的政策级次 =====
const agencies = ref([])
const bizOffices = ref([])
const loadingDict = ref(false)
// 受限账号（市级）只能选市级；判据在后端（依赖 SYS_ORG.ORG_TYPE），前端只拿结果用于即时弹提示
const allowedLevels = ref(null)
const lockedMsg = ref('')
let dictLoaded = false

async function loadDict() {
  if (dictLoaded) return
  loadingDict.value = true
  try {
    const [ag, bo, pl] = await Promise.all([
      agencyApi.list({ level: 2 }),
      projectApi.bizOffices(),
      projectApi.policyLevels()
    ])
    agencies.value = ag || []
    bizOffices.value = bo || []
    allowedLevels.value = pl?.allowed || null
    lockedMsg.value = pl?.lockedMsg || ''
    dictLoaded = true
  } finally { loadingDict.value = false }
}

/**
 * 政策级次越界即弹提示并回退到上一个合法值。
 *
 * <p>不清空：清空会立刻触发「请选择政策级次」「请选择项目级次」两行必填校验红字，
 * 用户还没提交就看见一片红。受限账号本来就只有一个合法值，回退等于什么都没发生。
 */
let lastValidLevel = ''
// 打开修改弹窗时该项目是否本来就有附件——决定「政策文件必填」对它是否成立，见 rules.files
let hadFilesOnOpen = false
function onPolicyChange(v) {
  if (allowedLevels.value && v && !allowedLevels.value.includes(v)) {
    form.policyLevel = lastValidLevel
    if (!projectLevelOptions.value.includes(form.projectLevel)) form.projectLevel = ''
    ElMessageBox.alert(lockedMsg.value || '当前账号不能选择该政策级次', '提示', { confirmButtonText: '确定' })
    return
  }
  lastValidLevel = v || ''
  // 政策级次变化后，项目级次若不在允许集合则清空
  if (!projectLevelOptions.value.includes(form.projectLevel)) form.projectLevel = ''
}

/** 主管部门默认值：优先农业口（生产默认「农业科」，本地机构字典里叫「农业股」/「农业农村局」）。 */
function defaultCompetentDept() {
  const hit = agencies.value.find(a => /农业/.test(a.name || ''))
  return hit ? hit.name : ''
}

// ===== 政策文件附件 =====
const uploading = ref(false)

/** idx < 0 = 追加新附件；idx >= 0 = 替换该行（重新上传，保留首传者与原序号）。 */
async function onPick(file, idx) {
  if (!file || !file.raw) return
  if (file.raw.size > 20 * 1024 * 1024) return ElMessage.warning('附件不能超过 20MB')
  const fd = new FormData()
  fd.append('file', file.raw)
  uploading.value = true
  try {
    const d = await projectApi.upload(fd)
    const row = {
      fileName: d.fileName, fileSize: d.fileSize, fileUrl: d.url, uploadName: d.uploadName
    }
    if (idx >= 0) {
      // 重新上传：换文件，但「上传人」沿用原行——这一列记的是谁把这份材料带进来的
      const old = form.files[idx]
      form.files[idx] = { ...row, uploadBy: old.uploadBy, uploadName: old.uploadName || d.uploadName }
      ElMessage.success('已重新上传')
    } else {
      form.files.push(row)
      ElMessage.success('附件上传成功')
    }
  } finally { uploading.value = false }
}

async function doRemove(idx) {
  const f = form.files[idx]
  await ElMessageBox.confirm(`确定删除附件「${f.fileName}」？保存后生效`, '删除确认', { type: 'warning' })
  form.files.splice(idx, 1)
}

// 附件已改为需登录下载：window.open 开的新窗口不会带 Authorization 头，只能走 axios 取 blob
function doDownload(row) { return downloadFile(row.fileUrl, row.fileName) }
function previewable(row) { return PREVIEWABLE.test(row.fileName || '') }
function doPreview(row) { return downloadFile(row.fileUrl, row.fileName) }

function sizeText(n) {
  const v = Number(n)
  if (!v || v <= 0) return '—'
  if (v < 1024) return v + ' B'
  if (v < 1024 * 1024) return (v / 1024).toFixed(2) + ' KB'
  return (v / 1024 / 1024).toFixed(2) + ' MB'
}

watch(() => props.visible, async v => {
  if (!v) return
  Object.assign(form, blank())
  // 先把行数据铺上再拉字典：字典任何一个接口挂了都会让 watch 抛出，
  // 放在 await 之后的赋值就整段不执行——修改弹窗会开成一片空白，像这条记录没数据一样。
  if (props.row) {
    Object.assign(form, JSON.parse(JSON.stringify(props.row)))
    form.files = []
  }
  await loadDict()
  if (props.row) {
    if (props.row.id) form.files = (await projectApi.files(props.row.id)) || []
    hadFilesOnOpen = form.files.length > 0
    lastValidLevel = form.policyLevel || ''
  } else {
    form.competentDept = defaultCompetentDept()
    form.budgetSource = BUDGET_SOURCES[0].name
    // 只有一个可选级次（市级账号）时直接预选，省一次必然的点击
    if (allowedLevels.value?.length === 1) {
      form.policyLevel = allowedLevels.value[0]
      if (projectLevelOptions.value.length === 1) form.projectLevel = projectLevelOptions.value[0]
    }
    lastValidLevel = form.policyLevel || ''
  }
})

const required = (msg) => ({ required: true, message: msg, trigger: 'change' })
const rules = {
  projectName: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
  shortName: [{ required: true, message: '请输入项目简称', trigger: 'blur' }, { max: 7, message: '简称须≤7个字', trigger: 'blur' }],
  competentDept: [required('请选择主管部门')],
  deptName: [required('请选择业务处室')],
  grantType: [required('请选择发放类型')],
  policyLevel: [required('请选择政策级次')],
  projectLevel: [required('请选择项目级次')],
  subsidyScope: [{ required: true, message: '请输入补贴范围及对象', trigger: 'blur' }],
  policyDocName: [{ required: true, message: '请输入政策文件名称', trigger: 'blur' }],
  policyDocNo: [{ required: true, message: '请输入政策文号', trigger: 'blur' }],
  subsidyStandard: [{ required: true, message: '请输入补贴标准', trigger: 'blur' }],
  // required:true 不只是校验，还负责让 label 显示红星——只写 validator 的话校验拦得住但标签上没星，
  // 用户看不出这是必填项（生产表单上「政策文件」是带红星的）。
  //
  // 例外：打开时本来就没有附件的历史项目不强制。导入的 1092 条生产项目全部没有附件行，
  // 一律强制的话这些项目改个联系人都保存不了，而附件原件并不在我们手上。
  // 新增项目、以及本来有附件却被删光的，仍然拦。
  files: [{
    required: true,
    validator: (_r, _v, cb) =>
      (form.files.length || (editing.value && !hadFilesOnOpen)) ? cb() : cb(new Error('请上传政策文件')),
    trigger: 'change'
  }]
}

async function onSave() {
  await formRef.value.validate()
  saving.value = true
  try {
    await projectApi.save(form)
    ElMessage.success(editing.value ? '修改成功' : '新增成功')
    emit('update:visible', false)
    emit('saved')
  } finally { saving.value = false }
}
function onClosed() { formRef.value?.clearValidate?.() }
</script>

<style scoped>
.proj-form :deep(.el-form-item) { margin-bottom: 16px; }
.tip { color: #f56c6c; font-size: 12px; line-height: 1.5; }
.file-panel { width: 100%; border: 1px solid var(--el-border-color-lighter); }
.file-head {
  display: flex; align-items: center; gap: 12px;
  padding: 6px 10px; background: var(--el-fill-color-light);
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.file-head-label { font-weight: 600; white-space: nowrap; }
.file-head .up-new { margin-left: auto; }
</style>
