<template>
  <div class="forbidden">
    <div class="code">403</div>
    <div class="title">无权限访问该页面</div>
    <div class="desc">
      当前账号未被授予此功能的菜单权限。<br />
      如果你的权限刚被管理员调整过，请退出后重新登录；确属需要请联系系统管理员开通。
    </div>
    <div class="ops">
      <el-button type="primary" @click="$router.replace('/dashboard')">返回工作台</el-button>
      <el-button @click="$router.back()">返回上一页</el-button>
    </div>
  </div>
</template>

<style scoped>
.forbidden {
  min-height: 60vh; display: flex; flex-direction: column;
  align-items: center; justify-content: center; text-align: center;
}
.code { font-size: 72px; font-weight: 700; color: #cc785c; font-family: Georgia, serif; line-height: 1; }
.title { font-size: 20px; font-weight: 600; color: #191915; margin: 16px 0 10px; }
.desc { font-size: 14px; color: #6b675e; line-height: 1.8; }
.ops { margin-top: 24px; display: flex; gap: 12px; }
</style>
