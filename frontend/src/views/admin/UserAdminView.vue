<script setup lang="ts">
import { onMounted, ref } from 'vue'
import DataPager from '@/components/DataPager.vue'
import EmptyState from '@/components/EmptyState.vue'
import {
  changeUserStatus,
  createUser,
  createRole,
  listPermissions,
  listRoles,
  pageUsers,
  updateRole,
  updateUser,
  type Permission,
  type SysRole,
  type SysUser
} from '@/api/system'
import { useUserStore } from '@/stores/user'
import { formatTime } from '@/utils/format'
import { toastError, toastSuccess } from '@/utils/toast'

const userStore = useUserStore()

const tab = ref<'user' | 'role'>('user')

const users = ref<SysUser[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const loading = ref(false)
const roles = ref<SysRole[]>([])
const permissions = ref<Permission[]>([])

const showUserForm = ref(false)
const editingUserId = ref<number | null>(null)
const userForm = ref({
  username: '',
  password: '',
  realName: '',
  phone: '',
  email: '',
  roleCodes: [] as string[]
})

const showRoleForm = ref(false)
const editingRoleId = ref<number | null>(null)
const roleForm = ref({ roleCode: '', roleName: '', permCodes: [] as string[] })

async function loadUsers(): Promise<void> {
  loading.value = true
  try {
    const page = await pageUsers({ pageNum: pageNum.value, pageSize: pageSize.value, keyword: keyword.value })
    users.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function loadRoles(): Promise<void> {
  roles.value = await listRoles()
}

async function loadPermissions(): Promise<void> {
  permissions.value = await listPermissions()
}

function openCreateUser(): void {
  editingUserId.value = null
  userForm.value = { username: '', password: '', realName: '', phone: '', email: '', roleCodes: [] }
  showUserForm.value = true
}

function openEditUser(item: SysUser): void {
  editingUserId.value = item.id
  userForm.value = {
    username: item.username,
    password: '',
    realName: item.realName ?? '',
    phone: item.phone ?? '',
    email: item.email ?? '',
    roleCodes: [...item.roleCodes]
  }
  showUserForm.value = true
}

async function submitUser(): Promise<void> {
  if (!editingUserId.value && (!userForm.value.username.trim() || !userForm.value.password)) {
    toastError('新增用户须填写用户名与初始密码')
    return
  }
  if (editingUserId.value) {
    await updateUser(editingUserId.value, userForm.value)
    toastSuccess('用户已更新')
  } else {
    await createUser(userForm.value)
    toastSuccess('用户已新增')
  }
  showUserForm.value = false
  await loadUsers()
}

async function handleUserStatus(item: SysUser): Promise<void> {
  await changeUserStatus(item.id, item.status !== 1)
  toastSuccess(item.status === 1 ? '账号已禁用' : '账号已启用')
  await loadUsers()
}

function openCreateRole(): void {
  editingRoleId.value = null
  roleForm.value = { roleCode: '', roleName: '', permCodes: [] }
  showRoleForm.value = true
}

function openEditRole(item: SysRole): void {
  editingRoleId.value = item.id
  roleForm.value = { roleCode: item.roleCode, roleName: item.roleName, permCodes: [...item.permCodes] }
  showRoleForm.value = true
}

async function submitRole(): Promise<void> {
  if (!roleForm.value.roleCode.trim() || !roleForm.value.roleName.trim()) {
    toastError('角色编码与名称不能为空')
    return
  }
  if (editingRoleId.value) {
    await updateRole(editingRoleId.value, roleForm.value)
    toastSuccess('角色权限已更新')
  } else {
    await createRole(roleForm.value)
    toastSuccess('角色已新增')
  }
  showRoleForm.value = false
  await Promise.all([loadRoles(), loadPermissions()])
  if (userStore.user) await userStore.loadProfile()
}

function togglePerm(code: string): void {
  const index = roleForm.value.permCodes.indexOf(code)
  if (index >= 0) {
    roleForm.value.permCodes.splice(index, 1)
  } else {
    roleForm.value.permCodes.push(code)
  }
}

function resetAndSearch(): void {
  pageNum.value = 1
  loadUsers()
}

onMounted(async () => {
  await Promise.all([loadUsers(), loadRoles(), loadPermissions()])
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">用户角色</h1>
        <p class="page-subtitle">账号管理、角色分配与权限配置（RBAC）</p>
      </div>
      <div class="row">
        <button class="btn" :class="{ 'btn-primary': tab === 'user' }" @click="tab = 'user'">用户管理</button>
        <button class="btn" :class="{ 'btn-primary': tab === 'role' }" @click="tab = 'role'">角色权限</button>
      </div>
    </div>

    <template v-if="tab === 'user'">
      <div class="row" style="margin-bottom: 14px">
        <input v-model="keyword" class="input" style="width: 200px" placeholder="用户名/姓名" @keyup.enter="resetAndSearch" />
        <button class="btn" @click="resetAndSearch">搜索</button>
        <button class="btn btn-primary" @click="openCreateUser">新增用户</button>
      </div>

      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>用户名</th>
              <th>姓名</th>
              <th>手机号</th>
              <th>邮箱</th>
              <th>角色</th>
              <th>状态</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in users" :key="item.id">
              <td>{{ item.username }}</td>
              <td>{{ item.realName || '--' }}</td>
              <td>{{ item.phone || '--' }}</td>
              <td>{{ item.email || '--' }}</td>
              <td>
                <span v-for="role in item.roleCodes" :key="role" class="tag tag-info" style="margin-right: 4px">
                  {{ role }}
                </span>
              </td>
              <td>
                <span :class="item.status === 1 ? 'tag tag-online' : 'tag tag-offline'">
                  {{ item.status === 1 ? '正常' : '禁用' }}
                </span>
              </td>
              <td>{{ formatTime(item.createTime) }}</td>
              <td>
                <button class="btn btn-sm" @click="openEditUser(item)">编辑</button>
                <button class="btn btn-sm" style="margin-left: 6px" @click="handleUserStatus(item)">
                  {{ item.status === 1 ? '禁用' : '启用' }}
                </button>
              </td>
            </tr>
            <tr v-if="users.length === 0">
              <td colspan="8" class="table-empty"><EmptyState compact icon="users" title="暂无用户" /></td>
            </tr>
          </tbody>
        </table>
      </div>

      <DataPager
        v-model:pageNum="pageNum"
        v-model:pageSize="pageSize"
        :total="total"
        @update:pageNum="loadUsers"
        @update:pageSize="resetAndSearch"
      />
    </template>

    <template v-else>
      <div class="row" style="margin-bottom: 14px">
        <button class="btn btn-primary" @click="openCreateRole">新增角色</button>
      </div>

      <div class="grid grid-2">
        <div v-for="item in roles" :key="item.id" class="panel">
          <div class="row" style="justify-content: space-between">
            <div>
              <strong>{{ item.roleName }}</strong>
              <span class="tag tag-muted" style="margin-left: 8px">{{ item.roleCode }}</span>
            </div>
            <button class="btn btn-sm" @click="openEditRole(item)">配置权限</button>
          </div>
          <div class="perm-list">
            <span v-for="code in item.permCodes" :key="code" class="tag tag-muted">{{ code }}</span>
          </div>
        </div>
      </div>
    </template>

    <div v-if="showUserForm" class="modal-mask" @click.self="showUserForm = false">
      <div class="modal">
        <h3 class="modal-title">{{ editingUserId ? '编辑用户' : '新增用户' }}</h3>
        <div class="form-grid">
          <label class="field">
            <span class="field-label">用户名</span>
            <input v-model="userForm.username" class="input" :disabled="!!editingUserId" placeholder="登录名" />
          </label>
          <label class="field">
            <span class="field-label">{{ editingUserId ? '重置密码（留空表示不修改）' : '初始密码' }}</span>
            <input v-model="userForm.password" type="password" class="input" placeholder="至少 6 位" />
          </label>
          <div class="grid grid-2" style="gap: 14px">
            <label class="field">
              <span class="field-label">姓名</span>
              <input v-model="userForm.realName" class="input" />
            </label>
            <label class="field">
              <span class="field-label">手机号</span>
              <input v-model="userForm.phone" class="input" />
            </label>
          </div>
          <label class="field">
            <span class="field-label">邮箱</span>
            <input v-model="userForm.email" class="input" />
          </label>
          <div class="field">
            <span class="field-label">角色</span>
            <div class="row">
              <label v-for="role in roles" :key="role.id" class="row" style="gap: 4px">
                <input v-model="userForm.roleCodes" type="checkbox" :value="role.roleCode" />
                {{ role.roleName }}
              </label>
            </div>
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn" @click="showUserForm = false">取消</button>
          <button class="btn btn-primary" @click="submitUser">保存</button>
        </div>
      </div>
    </div>

    <div v-if="showRoleForm" class="modal-mask" @click.self="showRoleForm = false">
      <div class="modal" style="max-width: 620px">
        <h3 class="modal-title">{{ editingRoleId ? '配置角色权限' : '新增角色' }}</h3>
        <div class="form-grid">
          <div class="grid grid-2" style="gap: 14px">
            <label class="field">
              <span class="field-label">角色编码</span>
              <input v-model="roleForm.roleCode" class="input" :disabled="!!editingRoleId" placeholder="如 FORECASTER" />
            </label>
            <label class="field">
              <span class="field-label">角色名称</span>
              <input v-model="roleForm.roleName" class="input" placeholder="如 预报员" />
            </label>
          </div>
          <div class="field">
            <span class="field-label">权限（已选 {{ roleForm.permCodes.length }} 项）</span>
            <div class="perm-list">
              <button
                v-for="perm in permissions"
                :key="perm.id"
                class="chip"
                :class="{ active: roleForm.permCodes.includes(perm.permCode) }"
                @click="togglePerm(perm.permCode)"
              >
                {{ perm.permName }}（{{ perm.permCode }}）
              </button>
            </div>
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn" @click="showRoleForm = false">取消</button>
          <button class="btn btn-primary" @click="submitRole">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.perm-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}

.chip {
  padding: 4px 12px;
  border-radius: var(--radius-pill);
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  color: var(--text-muted);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.chip.active {
  background: rgba(74, 126, 168, 0.18);
  border-color: var(--primary);
  color: var(--accent);
}
</style>