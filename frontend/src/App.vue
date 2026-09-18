<script setup lang="ts">
import { onMounted } from 'vue'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

onMounted(() => {
  // 刷新页面后与服务端同步一次身份，避免本地缓存角色过期
  userStore.loadProfile()
})
</script>

<template>
  <router-view />
</template>