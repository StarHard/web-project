<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'

/**
 * 统一空态。此前的空态是散落在 20 多处的一行灰色文字，
 * 既没有可看的东西，也不区分语义（真空中 / 筛选无匹配 / 需先执行动作）。
 *
 * 语义分型：
 * - default  数据本身为空，说明将来会有什么
 * - positive 空即正常（例如无告警），颜色承担编码而非装饰
 * - error    读取失败
 */
withDefaults(
  defineProps<{
    /** AppIcon 中的图标名 */
    icon: string
    title: string
    /** 「为什么值得有」的一句话，省略则只显示标题 */
    hint?: string
    /** 传入即渲染默认行动按钮；也可用 #actions 插槽自定义 */
    actionText?: string
    /** 请求进行中时禁用默认按钮，避免重复提交 */
    actionDisabled?: boolean
    variant?: 'default' | 'positive' | 'error'
    /** 表格行内等窄容器：退化为单行内联，不占整块高度 */
    compact?: boolean
  }>(),
  { variant: 'default', compact: false, actionDisabled: false }
)

defineEmits<{ action: [] }>()
</script>

<template>
  <span v-if="compact" class="empty-compact">
    <AppIcon :name="icon" :size="14" />
    <span>{{ title }}</span>
  </span>

  <div v-else class="empty-state" :class="`is-${variant}`">
    <span class="empty-mark"><AppIcon :name="icon" :size="24" /></span>
    <p class="empty-title">{{ title }}</p>
    <p v-if="hint" class="empty-hint">{{ hint }}</p>
    <div v-if="actionText || $slots.actions" class="empty-actions">
      <slot name="actions">
        <button
          type="button"
          class="btn btn-primary btn-sm"
          :disabled="actionDisabled"
          @click="$emit('action')"
        >
          {{ actionText }}
        </button>
      </slot>
    </div>
  </div>
</template>

<style scoped>
.empty-compact {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-dim);
}

/* 左对齐而非居中：空态在这里是「面板的一项读数」，不是欢迎页插图 */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 30px 4px;
  max-width: 620px;
}

.empty-mark {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  margin-bottom: 8px;
  border-radius: var(--radius-surface);
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  color: var(--text-muted);
}

.empty-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}

.empty-hint {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-muted);
}

.empty-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.is-positive .empty-mark {
  background: rgba(47, 168, 92, 0.12);
  border-color: rgba(47, 168, 92, 0.32);
  color: #5cc17f;
}

.is-error .empty-mark {
  background: rgba(209, 87, 79, 0.12);
  border-color: rgba(209, 87, 79, 0.34);
  color: #e08b86;
}
</style>