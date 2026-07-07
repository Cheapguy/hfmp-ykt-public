<template>
  <div class="village">
    <!-- 左：乡镇树 -->
    <el-card shadow="never" class="tree-pane">
      <el-input v-model="treeFilter" placeholder=" " :suffix-icon="Search" size="small" style="margin-bottom:8px" />
      <el-tree :data="treeData" :props="{ label: 'orgName', children: 'children' }" node-key="id"
        highlight-current :default-expanded-keys="expandedKeys" :filter-node-method="filterNode"
        ref="treeRef" @node-click="onPick" />
    </el-card>

    <!-- 右：村组表 -->
    <el-card shadow="never" class="grid-pane">
      <div class="toolbar">
        <el-button type="primary" :icon="Plus" :disabled="!townId" @click="openForm()">新增</el-button>
        <el-button :icon="Edit" :disabled="!current" @click="openForm(current)">修改</el-button>
      </div>
      <el-table v-loading="loading" :data="rows" border stripe highlight-current-row
        @current-change="r => current = r">
        <el-table-column type="index" label="序号" width="70" align="center" />
        <el-table-column prop="villageCode" label="编码" width="220" />
        <el-table-column prop="villageName" label="村名称" min-width="240" />
      </el-table>
    </el-card>

    <el-dialog v-model="formVisible" :title="form.id ? '修改村组' : '新增村组'" width="460px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="编码"><el-input v-model="form.villageCode" placeholder="标准化编码" /></el-form-item>
        <el-form-item label="村名称"><el-input v-model="form.villageName" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Plus, Edit } from '@element-plus/icons-vue'
import { villageApi, orgApi } from '../../api/system'

const treeData = ref([]); const townId = ref(null); const expandedKeys = ref([])
const rows = ref([]); const current = ref(null); const loading = ref(false)
const treeRef = ref(); const treeFilter = ref('')

onMounted(async () => {
  const all = (await orgApi.tree()) || []
  const counties = all.filter(o => o.orgType === 'COUNTY')
  const towns = all.filter(o => o.orgType === 'TOWN')
  treeData.value = counties.map(c => ({
    ...c, children: towns.filter(t => String(t.parentId) === String(c.id))
  }))
  // 默认展开并选中第一个有乡镇的县下首个乡镇
  const firstCounty = treeData.value.find(c => c.children.length)
  if (firstCounty) {
    expandedKeys.value = [firstCounty.id]
    townId.value = firstCounty.children[0].id; load()
  }
})

watch(treeFilter, v => treeRef.value?.filter(v))
function filterNode(v, data) { return !v || data.orgName.includes(v) }

function onPick(node) {
  if (node.orgType !== 'TOWN') return   // 点击县只展开, 不查村
  townId.value = node.id; current.value = null; load()
}

async function load() {
  loading.value = true
  try { rows.value = (await villageApi.list({ townId: townId.value })) || [] }
  finally { loading.value = false }
}

const formVisible = ref(false)
const form = ref({})
function openForm(row) {
  form.value = row ? { ...row } : { townId: townId.value, villageCode: '', villageName: '' }
  formVisible.value = true
}
async function save() {
  await villageApi.save({ ...form.value, townId: townId.value })
  ElMessage.success('保存成功')
  formVisible.value = false
  load()
}
</script>

<style scoped>
.village { display: flex; gap: 12px; align-items: flex-start; }
.tree-pane { width: 280px; flex-shrink: 0; }
.grid-pane { flex: 1; min-width: 0; }
.toolbar { margin-bottom: 12px; display: flex; gap: 8px; }
</style>
