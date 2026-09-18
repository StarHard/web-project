import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { toastError } from '@/utils/toast'

/**
 * meta.roles 为空表示登录即可访问；ADMIN 视为拥有全部权限（与后端 RBAC 一致）
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    component: () => import('@/layouts/DefaultLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '实时监测' }
      },
      {
        path: 'map',
        name: 'map',
        component: () => import('@/views/MapView.vue'),
        meta: { title: '站点地图' }
      },
      {
        path: 'history',
        name: 'history',
        component: () => import('@/views/HistoryView.vue'),
        meta: { title: '历史数据' }
      },
      {
        path: 'forecast',
        name: 'forecast',
        component: () => import('@/views/ForecastView.vue'),
        meta: { title: '精细预报' }
      },
      {
        path: 'alert',
        name: 'alert',
        component: () => import('@/views/AlertView.vue'),
        meta: { title: '灾害告警' }
      },
      {
        path: 'qc',
        name: 'qc',
        component: () => import('@/views/QcView.vue'),
        meta: { title: '质控审核', roles: ['OPERATOR', 'FORECASTER'] }
      },
      {
        path: 'content',
        name: 'content',
        component: () => import('@/views/ContentView.vue'),
        meta: { title: '气象服务' }
      },
      {
        path: 'report',
        name: 'report',
        component: () => import('@/views/ReportView.vue'),
        meta: { title: '统计报表' }
      },
      {
        path: 'admin/stations',
        name: 'admin-stations',
        component: () => import('@/views/admin/StationAdminView.vue'),
        meta: { title: '站点设备', roles: ['OPERATOR'] }
      },
      {
        path: 'admin/rules',
        name: 'admin-rules',
        component: () => import('@/views/admin/AlertRuleAdminView.vue'),
        meta: { title: '告警规则', roles: ['FORECASTER'] }
      },
      {
        path: 'admin/users',
        name: 'admin-users',
        component: () => import('@/views/admin/UserAdminView.vue'),
        meta: { title: '用户角色', roles: ['ADMIN'] }
      },
      {
        path: 'admin/system',
        name: 'admin-system',
        component: () => import('@/views/admin/SystemAdminView.vue'),
        meta: { title: '系统管理', roles: ['ADMIN'] }
      }
    ]
  },
  {
    path: '/screen',
    name: 'screen',
    component: () => import('@/views/ScreenView.vue'),
    meta: { title: '大屏看板' }
  },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('@/views/ForbiddenView.vue'),
    meta: { title: '无访问权限' }
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const userStore = useUserStore()

  if (to.meta.public) {
    return userStore.isLoggedIn && to.name === 'login' ? { name: 'dashboard' } : true
  }

  if (!userStore.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  const requiredRoles = (to.meta.roles as string[] | undefined) ?? []
  if (requiredRoles.length > 0 && !userStore.isAdmin && !userStore.hasRole(...requiredRoles)) {
    toastError('无访问权限')
    return { name: 'forbidden' }
  }

  return true
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · 校园智能气象服务系统` : '校园智能气象服务系统'
})

export default router