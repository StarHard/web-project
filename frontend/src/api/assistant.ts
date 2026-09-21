import { request } from '@/utils/request'

/** 工具调用留痕：本轮问答中模型实际查了哪些数据、参数与返回 */
export interface ToolInvocation {
  name: string
  arguments: string
  result: string
}

export interface AssistantAnswer {
  answer: string
  /** llm 大模型 / unavailable 大模型不可用（未配置密钥或网络不通） */
  source: 'llm' | 'unavailable'
  model: string
  /** 大模型不可用时为空 */
  tools: ToolInvocation[] | null
}

/**
 * 气象决策助手对话
 *
 * 超时单独放宽到 90s：一次问答可能包含多轮工具调用，每轮都是一次独立的大模型往返，
 * 沿用全局 20s 会在数据量大时被误判为失败。
 */
export function askAssistant(question: string) {
  return request<AssistantAnswer>({
    url: '/assistant/chat',
    method: 'post',
    data: { question },
    timeout: 90000
  })
}
