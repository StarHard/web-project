<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import { askAssistant, type AssistantAnswer, type ToolInvocation } from '@/api/assistant'

interface ChatMessage {
  role: 'user' | 'assistant'
  text: string
  source?: AssistantAnswer['source']
  model?: string
  tools?: ToolInvocation[]
}

/** 工具名 → 中文能力名，与后端 MeteoTools 的方法名一一对应 */
const TOOL_LABELS: Record<string, string> = {
  listStations: '站点清单',
  queryRealtime: '实时观测',
  queryHistory: '历史统计',
  queryForecast: '多模型预报',
  queryAlerts: '告警记录',
  getStationInfo: '站点档案',
  searchKnowledge: '知识库检索'
}

/** 工具 → 可继续深挖的页面，作为回答下方的引用卡片（把 AI 结论引回图表页面） */
const TOOL_LINKS: Record<string, { title: string; to: string }> = {
  queryRealtime: { title: '实时监测', to: '/dashboard' },
  queryHistory: { title: '历史数据', to: '/history' },
  queryForecast: { title: '精细预报', to: '/forecast' },
  queryAlerts: { title: '灾害告警', to: '/alert' },
  listStations: { title: '站点地图', to: '/map' },
  getStationInfo: { title: '站点地图', to: '/map' },
  searchKnowledge: { title: '气象服务原文', to: '/content' }
}

const SAMPLES = [
  '现在各站点的气温和风速是多少？',
  '最近 24 小时有哪些站点触发了告警？',
  '大风黄色预警的发布标准是什么？',
  '未来 24 小时气温最高会到多少度？'
]

const messages = ref<ChatMessage[]>([])
const draft = ref('')
const loading = ref(false)
/** 展开查看返回内容的工具项，键为「消息下标-工具下标」 */
const expanded = ref<Set<string>>(new Set())
const scrollRef = ref<HTMLElement | null>(null)

const canSend = computed(() => !loading.value && draft.value.trim().length > 0)

/** 顶栏展示实际使用的模型，让评委一眼看到接的是哪个大模型 */
const modelTag = computed(
  () => [...messages.value].reverse().find((item) => item.role === 'assistant' && item.model)?.model ?? ''
)

function toolLinks(tools?: ToolInvocation[]): { title: string; to: string }[] {
  const seen = new Set<string>()
  const links: { title: string; to: string }[] = []
  for (const tool of tools ?? []) {
    const link = TOOL_LINKS[tool.name]
    if (link && !seen.has(link.to)) {
      seen.add(link.to)
      links.push(link)
    }
  }
  return links
}

function toggle(key: string): void {
  const next = new Set(expanded.value)
  if (next.has(key)) {
    next.delete(key)
  } else {
    next.add(key)
  }
  expanded.value = next
}

async function scrollToBottom(): Promise<void> {
  await nextTick()
  scrollRef.value?.scrollTo({ top: scrollRef.value.scrollHeight, behavior: 'smooth' })
}

async function send(preset?: string): Promise<void> {
  const question = (preset ?? draft.value).trim()
  if (!question || loading.value) {
    return
  }
  draft.value = ''
  messages.value.push({ role: 'user', text: question })
  loading.value = true
  await scrollToBottom()
  try {
    const data = await askAssistant(question)
    messages.value.push({
      role: 'assistant',
      text: data.answer,
      source: data.source,
      model: data.model,
      tools: data.tools ?? []
    })
  } catch {
    // request 拦截器已弹出错误提示，这里只补一条对话内的失败占位，保持上下文完整
    messages.value.push({
      role: 'assistant',
      text: '本轮提问失败，请稍后重试。',
      source: 'unavailable',
      tools: []
    })
  } finally {
    loading.value = false
    await scrollToBottom()
  }
}

function clear(): void {
  messages.value = []
  expanded.value = new Set()
}
</script>

<template>
  <div class="page assistant">
    <div class="page-header">
      <div>
        <h1 class="page-title">智能助手</h1>
        <p class="page-subtitle">
          自然语言提问，由大模型规划并调用实时/历史/预报/告警数据工具后作答
          <span v-if="modelTag"> · {{ modelTag }}</span>
        </p>
      </div>
      <div class="row">
        <button class="btn btn-ghost" :disabled="messages.length === 0 || loading" @click="clear">
          清空对话
        </button>
      </div>
    </div>

    <div class="panel chat">
      <div ref="scrollRef" class="stream">
        <div v-if="messages.length === 0" class="intro">
          <div class="intro-mark"><AppIcon name="assistant" :size="22" /></div>
          <p class="intro-title">可以直接问系统里的真实数据</p>
          <p class="intro-note">
            助手不凭常识作答：每个数值都来自工具查询，回答下方会列出本轮实际调用的数据接口与返回内容。
          </p>
          <div class="samples">
            <button v-for="item in SAMPLES" :key="item" class="chip" @click="send(item)">
              {{ item }}
            </button>
          </div>
        </div>

        <div v-for="(message, index) in messages" :key="index" class="turn" :class="message.role">
          <div class="avatar">{{ message.role === 'user' ? '我' : 'AI' }}</div>
          <div class="body">
            <div class="bubble" :class="{ warn: message.source === 'unavailable' }">
              {{ message.text }}
            </div>

            <div v-if="message.tools?.length" class="tools">
              <div class="tools-head">数据工具调用 · {{ message.tools.length }} 次</div>
              <div v-for="(tool, toolIndex) in message.tools" :key="toolIndex" class="tool">
                <div class="tool-head">
                  <span class="tool-name">{{ TOOL_LABELS[tool.name] ?? tool.name }}</span>
                  <span class="tool-args">{{ tool.arguments }}</span>
                  <button
                    class="btn btn-ghost btn-sm"
                    @click="toggle(`${index}-${toolIndex}`)"
                  >
                    {{ expanded.has(`${index}-${toolIndex}`) ? '收起' : '返回内容' }}
                  </button>
                </div>
                <pre v-if="expanded.has(`${index}-${toolIndex}`)" class="tool-result">{{ tool.result }}</pre>
              </div>
            </div>

            <div v-if="message.role === 'assistant' && toolLinks(message.tools).length" class="refs">
              <router-link
                v-for="link in toolLinks(message.tools)"
                :key="link.to"
                class="ref-card"
                :to="link.to"
              >
                <span>查看{{ link.title }}</span>
                <AppIcon name="chart" :size="14" />
              </router-link>
            </div>
          </div>
        </div>

        <div v-if="loading" class="turn assistant">
          <div class="avatar">AI</div>
          <div class="body">
            <div class="bubble thinking">正在查询数据并组织回答…</div>
          </div>
        </div>
      </div>

      <div class="composer">
        <textarea
          v-model="draft"
          class="textarea"
          rows="2"
          placeholder="例如：未来 24 小时主校区站的气温最高会到多少度？（Enter 发送，Shift + Enter 换行）"
          @keydown.enter.exact.prevent="send()"
        ></textarea>
        <button class="btn btn-primary" :disabled="!canSend" @click="send()">
          {{ loading ? '回答中…' : '发送' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 对话区撑满可视高度，输入框固定在底部，消息列表独立滚动 */
.assistant {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.chat {
  flex: 1;
  min-height: 420px;
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}

.stream {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px;
}

/* ===== 空态引导 ===== */
.intro {
  padding: 22px 6px;
}

.intro-mark {
  width: 42px;
  height: 42px;
  border-radius: var(--radius);
  background: rgba(74, 126, 168, 0.16);
  border: 1px solid rgba(74, 126, 168, 0.35);
  color: var(--accent);
  display: grid;
  place-items: center;
  margin-bottom: 14px;
}

.intro-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.intro-note {
  margin: 6px 0 16px;
  max-width: 580px;
  font-size: 13px;
  color: var(--text-muted);
}

.samples {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.chip {
  padding: 6px 14px;
  border-radius: 20px;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  color: var(--text-muted);
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.15s ease;
}

.chip:hover {
  color: var(--text);
  border-color: var(--primary);
}

/* ===== 对话气泡 ===== */
.turn {
  display: flex;
  gap: 12px;
  margin-bottom: 18px;
}

.turn.user {
  flex-direction: row-reverse;
}

.avatar {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  border-radius: var(--radius-sm);
  display: grid;
  place-items: center;
  font-size: 12px;
  font-weight: 600;
}

.turn.assistant .avatar {
  background: rgba(74, 126, 168, 0.18);
  border: 1px solid rgba(74, 126, 168, 0.35);
  color: var(--accent);
}

.turn.user .avatar {
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  color: var(--text-muted);
}

.body {
  min-width: 0;
  max-width: 80%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.turn.user .body {
  align-items: flex-end;
}

.bubble {
  padding: 11px 15px;
  border-radius: var(--radius);
  font-size: 13.5px;
  line-height: 1.7;
  /* 大模型返回的换行直接生效；提示词已要求不使用 Markdown，此处不做渲染 */
  white-space: pre-wrap;
  word-break: break-word;
}

.turn.assistant .bubble {
  background: var(--bg-elevated);
  border: 1px solid var(--border);
}

.turn.user .bubble {
  background: rgba(74, 126, 168, 0.16);
  border: 1px solid rgba(74, 126, 168, 0.3);
}

/* 大模型不可用的降级回答：用告警色区分，避免被当成正常结论 */
.bubble.warn {
  background: rgba(217, 154, 43, 0.1);
  border-color: rgba(217, 154, 43, 0.4);
  color: #e0b45c;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.55;
  }
  50% {
    opacity: 1;
  }
}

.thinking {
  color: var(--text-muted);
  animation: pulse 1.4s ease-in-out infinite;
}

/* ===== 工具调用留痕 ===== */
.tools {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg);
  overflow: hidden;
}

.tools-head {
  padding: 7px 12px;
  font-size: 11px;
  letter-spacing: 0.5px;
  color: var(--text-dim);
  background: var(--bg-elevated);
  border-bottom: 1px solid var(--border);
}

.tool {
  padding: 8px 12px;
}

.tool + .tool {
  border-top: 1px solid var(--border);
}

.tool-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.tool-name {
  font-size: 12px;
  font-weight: 500;
  color: var(--accent);
}

.tool-args {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--text-muted);
}

.tool-result {
  margin: 8px 0 0;
  padding: 10px 12px;
  max-height: 240px;
  overflow-y: auto;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-family: inherit;
  font-size: 12px;
  color: var(--text-muted);
  white-space: pre-wrap;
}

/* ===== 引用卡片 ===== */
.refs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ref-card {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border-radius: 20px;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  color: var(--text-muted);
  font-size: 12px;
  transition: all 0.15s ease;
}

.ref-card:hover {
  color: var(--accent);
  border-color: var(--primary);
}

/* ===== 输入区 ===== */
.composer {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 12px 18px;
  border-top: 1px solid var(--border);
}

.composer .textarea {
  min-height: 46px;
  max-height: 140px;
  resize: none;
}
</style>
