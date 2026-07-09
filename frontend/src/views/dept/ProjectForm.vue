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
          <el-select v-model="form.competentDept" filterable allow-create default-first-option style="width:100%">
            <el-option v-for="o in COMPETENT_DEPTS" :key="o" :label="o" :value="o" />
          </el-select>
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="业务处室" prop="deptName">
          <el-select v-model="form.deptName" filterable allow-create default-first-option style="width:100%">
            <el-option v-for="o in BIZ_DEPTS" :key="o" :label="o" :value="o" />
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
          <el-select v-model="form.budgetSource" filterable allow-create default-first-option clearable style="width:100%">
            <el-option v-for="o in BUDGET_SOURCES" :key="o" :label="o" :value="o" />
          </el-select>
        </el-form-item></el-col>

        <el-col :span="8"><el-form-item label="追踪代码" prop="traceCode">
          <el-input v-model="form.traceCode" maxlength="64" />
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
        <el-col :span="24"><el-form-item label="政策文件" prop="policyFile">
          <div class="policy-file">
            <template v-if="form.policyFile">
              <a v-if="policyFileUrl" :href="policyFileUrl" target="_blank" class="file-link">{{ policyFileName }}</a>
              <span v-else class="file-plain">{{ form.policyFile }}</span>
              <el-button link type="danger" @click="form.policyFile = ''">移除</el-button>
            </template>
            <el-upload :show-file-list="false" :auto-upload="false" :on-change="onPolicyFilePick"
                       accept=".pdf,.doc,.docx,.wps,.png,.jpg,.jpeg,.ofd">
              <el-button :loading="uploadingFile">{{ form.policyFile ? '重新上传' : '上传附件' }}</el-button>
            </el-upload>
          </div>
          <div class="tip">本系统为非涉密平台，严禁传输国家秘密，请确保扫描、上传的文件资料不涉及国家秘密</div>
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
import { ElMessage } from 'element-plus'
import { projectApi } from '../../api/system'

const props = defineProps({
  visible: { type: Boolean, default: false },
  row: { type: Object, default: null }
})
const emit = defineEmits(['update:visible', 'saved'])

const POLICY = { CENTRAL: '中央级', PROVINCE: '省级', CITY: '市级', COUNTY: '县级' }
const PROJLEVEL = { PROV_SELF: '省级自建项目', PROV_CATALOG: '省级目录清单项目', CITY_SELF: '市级自建项目', COUNTY_SELF: '县级自建项目' }
// 区划联动：政策级次 -> 可选项目级次
const LEVEL_MAP = {
  COUNTY: ['COUNTY_SELF'],
  CITY: ['CITY_SELF'],
  PROVINCE: ['PROV_SELF', 'PROV_CATALOG'],
  CENTRAL: ['PROV_SELF', 'PROV_CATALOG']
}
const GRANT_TYPES = ['一卡通发放', '社会化发放', '现金发放']
const BUDGET_SOURCES = ['中央财政', '省级财政', '市级财政', '县级财政']
const COMPETENT_DEPTS = ['财政局', '农业农村局', '人力资源和社会保障局', '民政局', '林业和草原局', '教育局']
const BIZ_DEPTS = ['农业农村处', '社会保障处', '综合处', '社会救助科', '教育科', '林业站']

const formRef = ref()
const saving = ref(false)
const editing = computed(() => !!(props.row && props.row.id))

const blank = () => ({
  id: null, projectCode: '', projectName: '', shortName: '',
  competentDept: '', deptName: '', grantType: '',
  policyLevel: '', projectLevel: '', budgetSource: '', traceCode: '',
  subsidyScope: '', policyDocName: '', policyDocNo: '', policyFile: '', subsidyStandard: ''
})
const form = reactive(blank())

const projectLevelOptions = computed(() => LEVEL_MAP[form.policyLevel] || [])
function onPolicyChange() {
  // 政策级次变化后，项目级次若不在允许集合则清空
  if (!projectLevelOptions.value.includes(form.projectLevel)) form.projectLevel = ''
}

// ===== 政策文件附件：policyFile 存 /files/preview 下载地址；旧数据可能是纯文本文件名 =====
const uploadingFile = ref(false)
const policyFileUrl = computed(() =>
  form.policyFile && form.policyFile.startsWith('/hfmp-ykt/api/files/preview/') ? form.policyFile : '')
const policyFileName = computed(() => {
  const m = /[?&]fn=([^&]+)/.exec(form.policyFile || '')
  try { return m ? decodeURIComponent(m[1]) : '政策文件附件' } catch { return '政策文件附件' }
})
async function onPolicyFilePick(file) {
  if (!file || !file.raw) return
  if (file.raw.size > 20 * 1024 * 1024) return ElMessage.warning('附件不能超过 20MB')
  const fd = new FormData()
  fd.append('file', file.raw)
  uploadingFile.value = true
  try {
    const d = await projectApi.upload(fd)
    form.policyFile = d.url
    ElMessage.success('附件上传成功')
  } finally { uploadingFile.value = false }
}

watch(() => props.visible, v => {
  if (!v) return
  Object.assign(form, blank())
  if (props.row) Object.assign(form, JSON.parse(JSON.stringify(props.row)))
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
  subsidyStandard: [{ required: true, message: '请输入补贴标准', trigger: 'blur' }]
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
.tip { color: #f56c6c; font-size: 12px; line-height: 1.5; margin-top: 4px; }
.policy-file { display: flex; align-items: center; gap: 12px; }
.policy-file .file-link { color: var(--el-color-primary); text-decoration: none; }
.policy-file .file-link:hover { text-decoration: underline; }
.policy-file .file-plain { color: var(--el-text-color-regular); }
</style>
