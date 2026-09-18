import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { fetchProfile, login as loginApi, logout as logoutApi, type UserInfo } from '@/api/auth'
import { ACCESS_TOKEN_KEY, REFRESH_TOKEN_KEY } from '@/utils/request'

const USER_KEY = 'meteo_user'

function readStoredUser(): UserInfo | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as UserInfo
  } catch {
    return null
  }
}

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem(ACCESS_TOKEN_KEY) || '')
  const user = ref<UserInfo | null>(readStoredUser())

  const isLoggedIn = computed(() => Boolean(token.value))
  const roles = computed(() => user.value?.roles ?? [])

  function hasRole(...codes: string[]): boolean {
    return codes.some((code) => roles.value.includes(code))
  }

  /** ADMIN 拥有全部权限，无需逐项判断 */
  const isAdmin = computed(() => roles.value.includes('ADMIN'))

  function applyAuth(accessToken: string, refreshTokenValue: string, info: UserInfo): void {
    token.value = accessToken
    user.value = info
    localStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
    localStorage.setItem(REFRESH_TOKEN_KEY, refreshTokenValue)
    localStorage.setItem(USER_KEY, JSON.stringify(info))
  }

  async function login(username: string, password: string): Promise<void> {
    const resp = await loginApi({ username, password })
    applyAuth(resp.accessToken, resp.refreshToken, resp.user)
  }

  /** 刷新页面后同步一次服务端身份，避免本地角色信息过期 */
  async function loadProfile(): Promise<void> {
    if (!token.value) return
    try {
      const profile = await fetchProfile()
      user.value = {
        id: profile.id,
        username: profile.username,
        realName: user.value?.realName,
        roles: profile.roles
      }
      localStorage.setItem(USER_KEY, JSON.stringify(user.value))
    } catch {
      // 令牌失效由请求层统一处理，此处忽略
    }
  }

  async function logout(): Promise<void> {
    try {
      await logoutApi()
    } catch {
      // 服务端登出失败不阻塞本地清理
    }
    token.value = ''
    user.value = null
    localStorage.removeItem(ACCESS_TOKEN_KEY)
    localStorage.removeItem(REFRESH_TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  return { token, user, isLoggedIn, roles, isAdmin, hasRole, login, loadProfile, logout }
})