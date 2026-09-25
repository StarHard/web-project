<script setup lang="ts">
import { useRouter } from 'vue-router'
import EmptyState from '@/components/EmptyState.vue'

const router = useRouter()
</script>

<template>
  <div class="page">
    <div class="panel" style="max-width: 520px; margin: 80px auto">
      <EmptyState
        variant="error"
        icon="alert"
        title="当前账号无权访问该页面"
        hint="该页面需要更高权限的角色。如需访问，请联系系统管理员为你的角色分配对应权限。"
      >
        <template #actions>
          <button class="btn btn-primary" @click="router.push('/dashboard')">返回首页</button>
        </template>
      </EmptyState>
    </div>
  </div>
</template>