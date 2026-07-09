<template>
  <div class="login">
    <div class="bg-arc bg-arc-top"></div>
    <div class="bg-arc bg-arc-bottom"></div>

    <div class="login-card">
      <div class="login-head">
        <div class="logo-mark">惠</div>
        <h1>惠民惠农财政补贴"一卡通"管理模块</h1>
        <p>预算管理一体化 · 2026</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="onSubmit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="请输入账号">
            <template #prefix><el-icon><UserIcon /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" show-password>
            <template #prefix><el-icon><LockIcon /></el-icon></template>
          </el-input>
        </el-form-item>
        <!-- 渐进验证码：连续失败 2 次后后端要求（code=4281） -->
        <el-form-item v-if="captcha.required" prop="captcha">
          <div class="captcha-row">
            <el-input v-model="captcha.code" placeholder="请输入验证码" maxlength="4" style="flex:1">
              <template #prefix><el-icon><KeyIcon /></el-icon></template>
            </el-input>
            <img v-if="captcha.img" :src="captcha.img" class="captcha-img" title="点击刷新" @click="refreshCaptcha" />
          </div>
        </el-form-item>
        <el-button type="primary" size="large" class="btn-login" :loading="loading" @click="onSubmit">登 录</el-button>
        <div class="login-tip">默认账号 admin / admin123</div>
      </el-form>
    </div>
    <div class="login-footer">© 一卡通管理模块 · 脚手架 Demo</div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User as UserIcon, Lock as LockIcon, Key as KeyIcon } from '@element-plus/icons-vue'
import { login, fetchCaptcha } from '../api/auth'
import { resetPerms } from '../store/perm'

const router = useRouter()
const route = useRoute()
const formRef = ref()
const loading = ref(false)
const form = reactive({ username: 'admin', password: 'admin123' })
const captcha = reactive({ required: false, key: '', img: '', code: '' })
const rules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function refreshCaptcha() {
  const d = await fetchCaptcha()
  captcha.key = d.key
  captcha.img = d.img
  captcha.code = ''
}

async function onSubmit() {
  await formRef.value.validate()
  loading.value = true
  try {
    const data = await login({
      username: form.username, password: form.password,
      captcha: captcha.required ? captcha.code : undefined,
      captchaKey: captcha.required ? captcha.key : undefined
    })
    localStorage.setItem('ykt_token', data.token)
    localStorage.setItem('ykt_user', JSON.stringify({
      userId: data.userId, username: data.username, realName: data.realName, userType: data.userType
    }))
    resetPerms()   // 换账号后菜单权限重新拉取
    ElMessage.success('登录成功')
    router.replace(route.query.redirect || '/dashboard')
  } catch (e) {
    // 4281 = 后端要求验证码（首次触发或输错）：展示/刷新验证码
    if (e && e.code === 4281) { captcha.required = true; refreshCaptcha() }
    else if (captcha.required) refreshCaptcha()   // 密码又错：验证码一次性，需换新
  } finally { loading.value = false }
}
</script>

<style scoped>
/* 暖人文 · Anthropic 风 */
.login { min-height: 100vh; display: flex; align-items: center; justify-content: center; position: relative; overflow: hidden; background: linear-gradient(180deg, #faf9f5 0%, #f3f1ea 100%); }
.bg-arc { position: absolute; border-radius: 50%; pointer-events: none; }
.bg-arc-top { top: -200px; left: -120px; width: 540px; height: 540px; background: linear-gradient(135deg, #e0a48d 0%, #cc785c 100%); opacity: .8; }
.bg-arc-bottom { bottom: -180px; right: 8%; width: 280px; height: 280px; background: linear-gradient(135deg, #f6ece5 0%, #e6bcae 100%); opacity: .85; }
.login-card { position: relative; z-index: 1; width: min(420px, 92vw); background: #fffefb; border: 1px solid #e7e2d6; border-radius: 16px; box-shadow: 0 24px 60px -20px rgba(120,80,50,.22); padding: 40px 36px; }
.login-head { text-align: center; margin-bottom: 24px; }
.logo-mark { width: 56px; height: 56px; border-radius: 14px; background: linear-gradient(135deg, #d68a6e, #b15c41); color: #fff; font-size: 28px; font-weight: 700; display: flex; align-items: center; justify-content: center; margin: 0 auto 14px; box-shadow: 0 8px 18px -8px rgba(204,120,92,.6); }
.login-head h1 { font-family: Georgia, 'Songti SC', 'STSong', SimSun, serif; font-weight: 500; font-size: 19px; color: #191915; margin: 0 0 6px; line-height: 1.4; }
.login-head p { font-size: 12px; color: #9c978b; margin: 0; }
.btn-login { width: 100%; height: 46px; font-size: 16px; letter-spacing: 4px; border-radius: 8px; margin-top: 6px; }
.login-tip { margin-top: 14px; font-size: 12px; color: #9c978b; text-align: center; }
.captcha-row { display: flex; gap: 10px; width: 100%; align-items: center; }
.captcha-img { height: 44px; border-radius: 6px; border: 1px solid #e7e2d6; cursor: pointer; }
.login-footer { position: absolute; bottom: 16px; font-size: 12px; color: #b3ab9c; }
</style>
