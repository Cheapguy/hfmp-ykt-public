<template>
  <div>
    <h2 class="page-title">补贴项目维护</h2>
    <ProjectTable :for-audit="false" />
  </div>
</template>

<script setup>
import ProjectTable from './ProjectTable.vue'
</script>
