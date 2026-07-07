<template>
  <template v-for="m in items" :key="m.id">
    <el-sub-menu v-if="(m.children && m.children.length) || m.menuType === 'M'" :index="m.path || ('m_'+m.id)">
      <template #title>
        <el-icon v-if="depth === 0"><component :is="iconFor(m)" /></el-icon>
        <span>{{ m.menuName }}</span>
      </template>
      <SidebarMenu :items="m.children" :depth="depth + 1" />
    </el-sub-menu>
    <el-menu-item v-else :index="m.path || ('m_'+m.id)">
      <el-icon v-if="depth === 0"><component :is="iconFor(m)" /></el-icon>
      <template #title>{{ m.menuName }}</template>
    </el-menu-item>
  </template>
</template>

<script>
export default { name: 'SidebarMenu' }
</script>

<script setup>
import { Setting, Tools, OfficeBuilding, Tickets, Money, Folder, DocumentChecked, TrendCharts } from '@element-plus/icons-vue'

defineProps({
  items: { type: Array, default: () => [] },
  depth: { type: Number, default: 0 }
})

const ICON_BY_CODE = { setup: Setting, dept: OfficeBuilding, audit: DocumentChecked, report: TrendCharts, roster: Tickets, pay: Money, sys: Tools }
function iconFor(m) { return ICON_BY_CODE[m.menuCode] || Folder }
</script>
