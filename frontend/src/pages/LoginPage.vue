<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, LockKeyhole, UserRound, Waves } from '@lucide/vue'
import type { FormInstance, FormRules } from 'element-plus'
import { session } from '../services/session'

const router = useRouter()
const route = useRoute()
const formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' })
const loading = ref(false)
const error = ref('')
const rules: FormRules = {
  username: [{ required: true, whitespace: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function login() {
  if (loading.value) return
  error.value = ''
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await session.login(form.username.trim(), form.password)
    const redirect = route.query.redirect
    const destination = typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//') && !redirect.includes('\\')
      ? redirect
      : '/dashboard'
    await router.replace(destination)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '登录失败，请重试。'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-story" aria-label="灌区水情智能监测平台">
      <div class="story-grain" aria-hidden="true" />
      <div class="login-brand" aria-label="灌区水情监测">
        <span class="brand-symbol"><Waves :size="25" :stroke-width="1.9" /></span>
        <span class="brand-name">灌区水情</span>
      </div>

      <div class="story-content">
        <h1>灌区水情智能监测平台</h1>
      </div>

      <svg class="water-contours" viewBox="0 0 850 720" fill="none" aria-hidden="true">
        <defs>
          <linearGradient id="contour-gradient" x1="65" y1="570" x2="740" y2="260" gradientUnits="userSpaceOnUse"><stop stop-color="#8df1d9" stop-opacity=".02" /><stop offset=".48" stop-color="#91efde" stop-opacity=".47" /><stop offset="1" stop-color="#beffef" stop-opacity=".1" /></linearGradient>
          <linearGradient id="river-gradient" x1="360" y1="630" x2="700" y2="150" gradientUnits="userSpaceOnUse"><stop stop-color="#65d8ba" stop-opacity="0" /><stop offset=".65" stop-color="#65d8ba" stop-opacity=".12" /><stop offset="1" stop-color="#65d8ba" stop-opacity="0" /></linearGradient>
        </defs>
        <path d="M190 740C100 542 471 656 478 473C485 290 701 325 695 133C689-59 931-78 951-147L1084 28C1000 152 898 155 880 332C862 509 638 441 623 608C608 775 488 791 493 855Z" fill="url(#river-gradient)" />
        <g stroke="url(#contour-gradient)" stroke-width="1">
          <path v-for="line in 25" :key="line" :d="`M${-160 + line * 14} 795 C${-135 + line * 15} ${529 + line * 3} ${499 + line * 3} ${656 + line * 3} ${450 + line * 7} ${417 + line * 4} S${670 + line * 10} ${283 + line * 5} ${640 + line * 9} ${112 + line * 2} S${879 + line * 8} -42 910 -95`" />
        </g>
        <circle cx="551" cy="483" r="34" stroke="#a4f8e4" stroke-opacity=".1" /><circle cx="551" cy="483" r="20" stroke="#a4f8e4" stroke-opacity=".17" /><circle cx="551" cy="483" r="6" fill="#b8ffdf" /><circle cx="551" cy="483" r="3" fill="white" />
        <circle cx="717" cy="259" r="23" stroke="#a4f8e4" stroke-opacity=".1" /><circle cx="717" cy="259" r="12" stroke="#a4f8e4" stroke-opacity=".17" /><circle cx="717" cy="259" r="4" fill="#b8ffdf" />
      </svg>
    </section>

    <section class="login-entry" aria-label="登录">
      <div class="login-form-wrap">
        <h2>系统登录</h2>
        <p class="login-subtitle">请输入账号和密码</p>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="login-form" @submit.prevent="login">
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" placeholder="请输入用户名" size="large" autocomplete="username" aria-label="用户名" :disabled="loading" @input="error = ''"><template #prefix><UserRound :size="17" /></template></el-input>
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" placeholder="请输入密码" type="password" show-password size="large" autocomplete="current-password" aria-label="密码" :disabled="loading" @input="error = ''"><template #prefix><LockKeyhole :size="17" /></template></el-input>
          </el-form-item>
          <p v-if="error" class="login-error" role="alert">{{ error }}</p>
          <el-button type="primary" native-type="submit" size="large" class="login-submit" :loading="loading"><span>{{ loading ? '正在登录' : '登录' }}</span><ArrowRight v-if="!loading" :size="17" /></el-button>
        </el-form>
      </div>
    </section>
  </main>
</template>

<style scoped>
.login-page { min-height: 100svh; display: grid; grid-template-columns: 53% 47%; background: #f3f5f9; color: #303d55; font-family: Inter, "PingFang SC", "Microsoft YaHei", sans-serif; }
.login-story { position: relative; min-height: 100svh; overflow: hidden; background: radial-gradient(ellipse at 95% 83%, #3766a7 0%, transparent 55%), linear-gradient(143deg, #25324c 0%, #29466f 51%, #315b94 100%); color: #fff; isolation: isolate; }
.story-grain { position: absolute; inset: 0; opacity: .15; z-index: -1; background-image: radial-gradient(#cbdfff .55px, transparent .55px); background-size: 8px 8px; mask-image: linear-gradient(145deg, transparent 20%, black); }
.login-brand { position: relative; display: flex; width: fit-content; align-items: center; gap: 13px; margin: 48px 9.2%; text-decoration: none; color: inherit; }
.brand-symbol { width: 44px; height: 44px; border: 1px solid #91b8f43b; background: #4d83e826; border-radius: 8px; display: grid; place-items: center; color: #c2d9ff; }
.brand-name { font-size: 23px; font-weight: 600; letter-spacing: 5px; line-height: 1; }
.story-content { position: relative; z-index: 1; margin: clamp(82px, 13vh, 148px) 9.2% 0; }
.story-content h1 { font-size: clamp(28px, 3.05vw, 46px); font-weight: 550; letter-spacing: 1px; line-height: 1.58; margin: 0; }
.water-contours { position: absolute; z-index: -1; width: 125%; height: 83%; bottom: -16%; right: -21%; opacity: .95; }
.water-contours #contour-gradient stop { stop-color: #a6ccff; }
.water-contours #river-gradient stop { stop-color: #76a6f1; }
.water-contours circle[stroke] { stroke: #b6d2ff; }
.water-contours circle[fill="#b8ffdf"] { fill: #c5ddff; }
.login-entry { position: relative; display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 100svh; padding: 100px 48px; box-sizing: border-box; }
.login-form-wrap { width: 100%; max-width: 438px; box-sizing: border-box; padding: 32px; margin: -6px 0 0; background: #fff; border: 1px solid #e8ecf3; border-radius: 8px; box-shadow: 0 5px 24px #25324c05; }
.login-form-wrap h2 { font-size: 28px; line-height: 1.4; font-weight: 600; letter-spacing: .5px; margin: 0; }
.login-subtitle { font-size: 13px; color: #8490a4; margin-top: 10px; }
.login-form { margin-top: 28px; }
.login-form :deep(.el-form-item) { margin-bottom: 22px; }
.login-form :deep(.el-form-item__label) { color: #53647e; font-size: 13px; margin-bottom: 8px; }
.login-form :deep(.el-input__wrapper) { min-height: 44px; padding: 0 14px; border-radius: 6px; box-shadow: 0 0 0 1px #dfe5ef inset; background: #fff; }
.login-form :deep(.el-input__wrapper.is-focus) { box-shadow: 0 0 0 1px #4d83e8 inset, 0 0 0 3px #4d83e80d; }
.login-form :deep(.el-input__prefix) { color: #91a2bc; margin-right: 8px; }
.login-form :deep(.el-input__inner) { font-size: 13px; }
.login-form :deep(.el-input__inner::placeholder) { color: #a0abbd; }
.login-form :deep(.el-form-item.is-error .el-input__wrapper) { box-shadow: 0 0 0 1px var(--el-color-danger) inset; }
.login-submit { width: 100%; height: 44px; margin-top: 4px; border-radius: 6px; font-size: 13px; letter-spacing: 1px; background: #4d83e8; border-color: #4d83e8; box-shadow: 0 4px 12px #4d83e81a; }
.login-submit:hover { background: #3d69ba; border-color: #3d69ba; }
.login-submit :deep(> span) { width: 100%; display: flex; align-items: center; justify-content: center; gap: 15px; }
.login-error { font-size: 12px; color: #bb5148; background: #fff2f0; border: 1px solid #f4d9d4; border-radius: 5px; padding: 10px 12px; line-height: 1.6; margin: -3px 0 12px; }
@media (min-width: 1700px) { .story-content { margin-top: 16vh; } .login-form-wrap { max-width: 474px; } }
@media (max-width: 1100px) {
  .login-page { grid-template-columns: 48% 52%; }
  .login-entry { padding: 100px 34px; }
  .story-content h1 { font-size: 29px; }
}
@media (max-width: 760px) {
  .login-page { display: block; }
  .login-story { min-height: 0; height: 230px; }
  .login-brand { margin: 24px 28px; gap: 10px; }
  .brand-symbol { width: 37px; height: 37px; border-radius: 8px; }
  .brand-name { font-size: 20px; }
  .story-content { margin: 31px 28px 0; }
  .story-content h1 { font-size: 24px; line-height: 1.5; }
  .water-contours { width: 85%; height: 165%; bottom: -70%; right: -10%; opacity: .75; }
  .login-entry { min-height: calc(100svh - 230px); padding: 45px 20px 76px; justify-content: flex-start; }
  .login-form-wrap { max-width: 400px; margin: 0; padding: 24px 20px; }
  .login-form-wrap h2 { font-size: 25px; }
  .login-form { margin-top: 24px; }
}
@media (prefers-reduced-motion: reduce) { *, *::before, *::after { transition: none !important; } }
</style>
