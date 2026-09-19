<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import AppIcon from '@/components/AppIcon.vue'

interface MenuItem {
  title: string
  icon: string
  to: string
  roles?: string[]
}

interface MenuGroup {
  label: string
  items: MenuItem[]
}

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const collapsed = ref(false)

const MENU: MenuGroup[] = [
  {
    label: '监测中心',
    items: [
      { title: '实时监测', icon: 'dashboard', to: '/dashboard' },
      { title: '大屏看板', icon: 'screen', to: '/screen' },
      { title: '站点地图', icon: 'map', to: '/map' },
      { title: '历史数据', icon: 'history', to: '/history' }
    ]
  },
  {
    label: '预报预警',
    items: [
      { title: '精细预报', icon: 'forecast', to: '/forecast' },
      { title: '灾害告警', icon: 'bell', to: '/alert' },
      { title: '质控审核', icon: 'shield', to: '/qc', roles: ['OPERATOR', 'FORECASTER'] }
    ]
  },
  {
    label: '服务与报表',
    items: [
      { title: '气象服务', icon: 'doc', to: '/content' },
      { title: '统计报表', icon: 'chart', to: '/report' }
    ]
  },
  {
    label: '系统管理',
    items: [
      { title: '站点设备', icon: 'station', to: '/admin/stations', roles: ['OPERATOR'] },
      { title: '告警规则', icon: 'settings', to: '/admin/rules', roles: ['FORECASTER'] },
      { title: '用户角色', icon: 'users', to: '/admin/users', roles: ['ADMIN'] },
      { title: '系统管理', icon: 'settings', to: '/admin/system', roles: ['ADMIN'] }
    ]
  }
]

const visibleMenu = computed(() =>
  MENU.map((group) => ({
    label: group.label,
    items: group.items.filter(
      (item) => !item.roles || userStore.isAdmin || userStore.hasRole(...item.roles)
    )
  })).filter((group) => group.items.length > 0)
)

const currentRoleLabel = computed(() => {
  const roles = userStore.roles
  if (roles.includes('ADMIN')) return '管理员'
  if (roles.includes('FORECASTER')) return '预报员'
  if (roles.includes('OPERATOR')) return '运维人员'
  return '普通用户'
})

function isActive(to: string): boolean {
  return route.path === to
}

async function handleLogout(): Promise<void> {
  await userStore.logout()
  router.push('/login')
}
</script>

<template>
  <div class="layout">
    <aside class="sidebar" :class="{ collapsed }">
      <div class="brand">
        <div class="brand-mark">气</div>
        <div v-show="!collapsed" class="brand-text">
          <strong>校园气象</strong>
          <span>智能服务系统</span>
        </div>
      </div>

      <nav class="nav">
        <div v-for="group in visibleMenu" :key="group.label" class="nav-group">
          <div v-show="!collapsed" class="nav-label">{{ group.label }}</div>
          <router-link
            v-for="item in group.items"
            :key="item.to"
            :to="item.to"
            class="nav-item"
            :class="{ active: isActive(item.to) }"
            :title="item.title"
          >
            <AppIcon :name="item.icon" :size="17" />
            <span v-show="!collapsed">{{ item.title }}</span>
          </router-link>
        </div>
      </nav>
    </aside>

    <div class="main">
      <header class="topbar">
        <button class="btn btn-ghost btn-sm" @click="collapsed = !collapsed">
          {{ collapsed ? '»' : '«' }}
        </button>
        <div class="crumb">{{ route.meta.title ?? '' }}</div>
        <div class="topbar-right">
          <span class="user-meta">
            {{ userStore.user?.realName || userStore.user?.username }}
            <em>{{ currentRoleLabel }}</em>
          </span>
          <button class="btn btn-ghost btn-sm" @click="handleLogout">
            <AppIcon name="logout" :size="15" />
            退出
          </button>
        </div>
      </header>

      <section class="content">
        <router-view v-slot="{ Component }">
          <component :is="Component" />
        </router-view>
      </section>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  height: 100%;
  background: var(--bg);
}

.sidebar {
  width: 220px;
  flex-shrink: 0;
  border-right: 1px solid var(--border);
  background: var(--bg-panel);
  display: flex;
  flex-direction: column;
  transition: width 0.2s ease;
  overflow-y: auto;
}

.sidebar.collapsed {
  width: 64px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 16px;
  border-bottom: 1px solid var(--border);
}

.brand-mark {
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: var(--radius-sm);
  background: var(--primary);
  color: #fff;
  display: grid;
  place-items: center;
  font-weight: 700;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
  white-space: nowrap;
}

.brand-text strong {
  font-size: 14px;
}

.brand-text span {
  font-size: 11px;
  color: var(--text-muted);
}

.nav {
  padding: 12px 10px 24px;
  flex: 1;
}

.nav-group + .nav-group {
  margin-top: 16px;
}

.nav-label {
  padding: 0 8px 6px;
  font-size: 11px;
  color: var(--text-dim);
  letter-spacing: 1px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: var(--radius-sm);
  color: var(--text-muted);
  font-size: 13px;
  margin-bottom: 2px;
  transition: all 0.15s ease;
  white-space: nowrap;
}

.nav-item:hover {
  background: var(--bg-hover);
  color: var(--text);
}

.nav-item.active {
  background: rgba(74, 126, 168, 0.16);
  color: var(--text);
  box-shadow: inset 2px 0 0 var(--primary);
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.topbar {
  height: 56px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 20px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-panel);
}

.crumb {
  font-size: 14px;
  font-weight: 500;
}

.topbar-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 14px;
}

.user-meta {
  font-size: 13px;
  color: var(--text-muted);
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-meta em {
  font-style: normal;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 20px;
  background: var(--bg-elevated);
  color: var(--text-muted);
  border: 1px solid var(--border);
}

.content {
  flex: 1;
  overflow-y: auto;
}
</style>