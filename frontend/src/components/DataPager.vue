<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{ total: number; pageNum: number; pageSize: number; pageSizes?: number[] }>(),
  { pageSizes: () => [10, 20, 50] }
)

const emit = defineEmits<{
  (e: 'update:pageNum', value: number): void
  (e: 'update:pageSize', value: number): void
}>()

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))

function go(page: number): void {
  const next = Math.min(Math.max(page, 1), totalPages.value)
  if (next !== props.pageNum) {
    emit('update:pageNum', next)
  }
}

function changeSize(event: Event): void {
  emit('update:pageSize', Number((event.target as HTMLSelectElement).value))
}
</script>

<template>
  <div class="pager">
    <span>共 {{ total }} 条</span>
    <select class="select" style="width: auto; padding: 4px 8px" :value="pageSize" @change="changeSize">
      <option v-for="size in pageSizes" :key="size" :value="size">{{ size }} 条/页</option>
    </select>
    <button class="btn btn-sm" :disabled="pageNum <= 1" @click="go(pageNum - 1)">上一页</button>
    <span>{{ pageNum }} / {{ totalPages }}</span>
    <button class="btn btn-sm" :disabled="pageNum >= totalPages" @click="go(pageNum + 1)">下一页</button>
  </div>
</template>