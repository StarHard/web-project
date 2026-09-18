<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { toastError } from '@/utils/toast'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const username = ref('admin')
const password = ref('Admin@123')
const loading = ref(false)

async function submit(): Promise<void> {
  if (!username.value || !password.value) {
    toastError('请输入用户名与密码')
    return
  }
  loading.value = true
  try {
    await userStore.login(username.value, password.value)
    const redirect = (route.query.redirect as string) || '/dashboard'
    router.replace(redirect)
  } catch {
    // 错误提示已由请求拦截器统一处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-visual">
      <h1>校园智能气象服务系统</h1>
      <p>观测采集 · 智能质控 · 精细预报 · 灾害告警 · 决策服务</p>
      <ul>
        <li>五大智能体协同，全链路自动化运行</li>
        <li>分钟级观测入湖，秒级实时监测</li>
        <li>0–72h 逐小时精细化预报与准确率检验</li>
        <li>阈值告警自动升级与多渠道触达</li>
      </ul>
    </div>

    <form class="login-card" @submit.prevent="submit">
      <h2>账号登录</h2>
      <p class="hint">演示账号：admin / Admin@123</p>

      <label class="field">
        <span class="field-label">用户名</span>
        <input v-model="username" class="input" autocomplete="username" placeholder="请输入用户名" />
      </label>

      <label class="field">
        <span class="field-label">密码</span>
        <input
          v-model="password"
          type="password"
          class="input"
          autocomplete="current-password"
          placeholder="请输入密码"
        />
      </label>

      <button class="btn btn-primary submit" type="submit" :disabled="loading">
        {{ loading ? '登录中…' : '登 录' }}
      </button>
    </form>
  </div>
</template>

<style scoped>
.login-page {
  height: 100%;
  display: grid;
  grid-template-columns: 1.15fr 0.85fr;
  align-items: center;
  gap: 40px;
  padding: 0 8vw;
  background: radial-gradient(900px 500px at 15% 10%, rgba(59, 130, 246, 0.18), transparent),
    radial-gradient(700px 400px at 85% 85%, rgba(34, 211, 238, 0.12), transparent), var(--bg);
}

.login-visual h1 {
  font-size: 34px;
  font-weight: 600;
  margin: 0 0 14px;
  letter-spacing: 1px;
  color: var(--text);
}

.login-visual p {
  color: var(--text-muted);
  margin: 0 0 26px;
  letter-spacing: 2px;
}

.login-visual ul {
  list-style: none;
  padding: 0;
  margin: 0;
  color: var(--text-muted);
  font-size: 14px;
  line-height: 2.1;
}

.login-visual li::before {
  content: '◆';
  color: var(--cyan);
  margin-right: 10px;
  font-size: 10px;
}

.login-card {
  background: var(--bg-panel);
  border: 1px solid var(--border-light);
  border-radius: 14px;
  padding: 34px 32px;
  box-shadow: var(--shadow);
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.login-card h2 {
  margin: 0;
  font-size: 20px;
}

.hint {
  margin: -8px 0 6px;
  font-size: 12px;
  color: var(--text-muted);
}

.submit {
  margin-top: 6px;
  padding: 11px;
  font-size: 14px;
}

@media (max-width: 900px) {
  .login-page {
    grid-template-columns: 1fr;
    padding: 0 6vw;
  }

  .login-visual {
    display: none;
  }
}
</style>