<template>
  <div>
    <h2 class="page-title">补贴项目审核</h2>
    <ProjectTable :for-audit="true" />
  </div>
</template>

<script setup>
import ProjectTable from './ProjectTable.vue'
</script>
