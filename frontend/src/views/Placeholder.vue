<template>
  <el-empty :description="`「${$route.meta.title || '该功能'}」开发中`">
    <template #image><el-icon style="font-size:64px;color:#c0c4cc"><Tools /></el-icon></template>
    <div style="color:#909399;font-size:13px">路由：{{ $route.path }}</div>
  </el-empty>
</template>

<script setup>
import { Tools } from '@element-plus/icons-vue'
</script>
