/**
 * 极简全局提示（避免为此引入完整 UI 组件库）
 */
export type ToastType = 'success' | 'error' | 'info'

const COLORS: Record<ToastType, string> = {
  success: '#16a34a',
  error: '#dc2626',
  info: '#2563eb'
}

function ensureContainer(): HTMLElement {
  let container = document.getElementById('meteo-toast-container')
  if (!container) {
    container = document.createElement('div')
    container.id = 'meteo-toast-container'
    container.style.cssText =
      'position:fixed;top:24px;left:50%;transform:translateX(-50%);z-index:9999;display:flex;flex-direction:column;gap:8px;align-items:center'
    document.body.appendChild(container)
  }
  return container
}

export function toast(message: string, type: ToastType = 'info', duration = 3000): void {
  const el = document.createElement('div')
  el.textContent = message
  el.style.cssText = [
    'padding:10px 18px',
    'border-radius:6px',
    'font-size:14px',
    'color:#fff',
    `background:${COLORS[type]}`,
    'box-shadow:0 4px 12px rgba(0,0,0,.15)',
    'opacity:0',
    'transition:opacity .2s ease'
  ].join(';')
  ensureContainer().appendChild(el)
  requestAnimationFrame(() => (el.style.opacity = '1'))
  setTimeout(() => {
    el.style.opacity = '0'
    setTimeout(() => el.remove(), 200)
  }, duration)
}

export const toastSuccess = (m: string) => toast(m, 'success')
export const toastError = (m: string) => toast(m, 'error')