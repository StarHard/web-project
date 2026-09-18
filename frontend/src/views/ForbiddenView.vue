<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="page">
    <div class="panel state" style="max-width: 520px; margin: 80px auto">
      <div style="font-size: 44px; font-weight: 700; color: var(--warning)">403</div>
      <p>当前账号无权访问该页面</p>
      <button class="btn btn-primary" style="margin-top: 12px" @click="router.push('/dashboard')">
        返回首页
      </button>
    </div>
  </div>
</template>