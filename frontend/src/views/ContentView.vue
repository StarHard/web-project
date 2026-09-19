<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import DataPager from '@/components/DataPager.vue'
import {
  articleDetail,
  changeArticlePublishStatus,
  createArticle,
  pageArticles,
  updateArticle,
  type ServiceArticle
} from '@/api/content'
import { useUserStore } from '@/stores/user'
import { formatTime } from '@/utils/format'
import { toastError, toastSuccess } from '@/utils/toast'

const userStore = useUserStore()

const CATEGORY_LABELS: Record<number, string> = {
  1: '农业气象',
  2: '旅游气象',
  3: '出行指数',
  4: '科普'
}

const articles = ref<ServiceArticle[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const category = ref<number | undefined>(undefined)
const loading = ref(false)

const detail = ref<ServiceArticle | null>(null)
const editing = ref<ServiceArticle | null>(null)
const form = ref<{ category: number; title: string; content: string }>({
  category: 1,
  title: '',
  content: ''
})

const canManage = computed(() => userStore.isAdmin)

async function loadArticles(): Promise<void> {
  loading.value = true
  try {
    // 公开列表默认只返回已发布内容；管理员可切换查看草稿
    const page = await pageArticles({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      category: category.value,
      publishStatus: 1
    })
    articles.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function openDetail(id: number): Promise<void> {
  detail.value = await articleDetail(id)
}

function openCreate(): void {
  editing.value = null
  form.value = { category: 1, title: '', content: '' }
  detail.value = { id: 0, category: 1, title: '', content: '', publishStatus: 0 }
}

async function submitArticle(): Promise<void> {
  if (!form.value.title.trim() || !form.value.content.trim()) {
    toastError('标题与内容不能为空')
    return
  }
  if (detail.value?.id) {
    await updateArticle(detail.value.id, form.value)
    toastSuccess('已保存')
  } else {
    await createArticle(form.value)
    toastSuccess('已新增，可在管理端发布')
  }
  detail.value = null
  await loadArticles()
}

async function togglePublish(item: ServiceArticle): Promise<void> {
  const next = item.publishStatus === 1 ? 2 : 1
  await changeArticlePublishStatus(item.id, next)
  toastSuccess(next === 1 ? '已上架' : '已下架')
  await loadArticles()
}

function categoryLabel(value: number): string {
  return CATEGORY_LABELS[value] ?? '其他'
}

onMounted(loadArticles)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">气象服务</h1>
        <p class="page-subtitle">面向校园的农业气象、旅游气象、出行指数与科普内容</p>
      </div>
      <div class="row">
        <select v-model.number="category" class="select" style="width: 150px" @change="loadArticles">
          <option :value="undefined">全部分类</option>
          <option v-for="(label, key) in CATEGORY_LABELS" :key="key" :value="Number(key)">{{ label }}</option>
        </select>
        <button v-if="canManage" class="btn btn-primary" @click="openCreate">新增内容</button>
      </div>
    </div>

    <div v-if="articles.length" class="grid grid-2">
      <div v-for="item in articles" :key="item.id" class="panel article-card">
        <div class="row" style="justify-content: space-between">
          <span class="tag tag-info">{{ categoryLabel(item.category) }}</span>
          <span class="article-time">{{ formatTime(item.publishTime) }}</span>
        </div>
        <h3 class="article-title" @click="openDetail(item.id)">{{ item.title }}</h3>
        <div class="article-actions">
          <button class="btn btn-sm" @click="openDetail(item.id)">查看详情</button>
          <button v-if="canManage" class="btn btn-sm" @click="togglePublish(item)">下架</button>
        </div>
      </div>
    </div>
    <div v-else-if="!loading" class="panel state">暂无已发布的气象服务内容</div>

    <DataPager
      v-model:pageNum="pageNum"
      v-model:pageSize="pageSize"
      :total="total"
      @update:pageNum="loadArticles"
      @update:pageSize="loadArticles"
    />

    <div v-if="detail" class="modal-mask" @click.self="detail = null">
      <div class="modal" style="max-width: 680px">
        <template v-if="detail.id">
          <h3 class="modal-title">{{ detail.title }}</h3>
          <span class="tag tag-muted">{{ categoryLabel(detail.category) }}</span>
          <div class="article-content">{{ detail.content }}</div>
          <div class="modal-actions">
            <button class="btn" @click="detail = null">关闭</button>
          </div>
        </template>

        <template v-else>
          <h3 class="modal-title">新增气象服务内容</h3>
          <div class="form-grid">
            <label class="field">
              <span class="field-label">分类</span>
              <select v-model.number="form.category" class="select">
                <option v-for="(label, key) in CATEGORY_LABELS" :key="key" :value="Number(key)">{{ label }}</option>
              </select>
            </label>
            <label class="field">
              <span class="field-label">标题</span>
              <input v-model="form.title" class="input" placeholder="请输入标题" />
            </label>
            <label class="field">
              <span class="field-label">内容</span>
              <textarea v-model="form.content" class="textarea" placeholder="请输入正文内容" />
            </label>
          </div>
          <div class="modal-actions">
            <button class="btn" @click="detail = null">取消</button>
            <button class="btn btn-primary" @click="submitArticle">提交</button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.article-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.article-title {
  margin: 0;
  font-size: 15px;
  cursor: pointer;
  transition: color 0.15s ease;
}

.article-title:hover {
  color: var(--accent);
}

.article-time {
  font-size: 12px;
  color: var(--text-muted);
}

.article-actions {
  display: flex;
  gap: 8px;
}

.article-content {
  margin-top: 14px;
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1.9;
  white-space: pre-wrap;
}
</style>