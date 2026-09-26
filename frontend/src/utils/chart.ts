/**
 * 稀疏序列的数据点兜底。
 *
 * ECharts 折线在 showSymbol:false 下只画线不画点，而 1 个点连不成线、
 * 2 个点在很多缩放下也几乎不可见——结果是数据明明存在，图上却是一片空白，
 * 看起来和「无数据」没有区别（此前趋势图「空图」的表象之一）。
 *
 * 阈值取 2：3 个点已能连成折线，再多显示圆点只会变成噪声。
 */
const SPARSE_POINT_LIMIT = 2

/** 点数过少时显示数据点；点数足够时保持隐点，避免密集序列变成一串珠子 */
export function sparseSymbolStyle(count: number): { showSymbol: boolean; symbolSize?: number } {
  return count <= SPARSE_POINT_LIMIT ? { showSymbol: true, symbolSize: 6 } : { showSymbol: false }
}

/**
 * 多序列曲线配色：同明度、不同色相。
 *
 * 刻意避开高饱和霓虹色——这套色用于「区分序列」，不用于编码语义，
 * 饱和度低才不至于和告警等级色抢读。深色底上仍可辨识。
 */
export const SERIES_COLORS = ['#6f9dc4', '#c98b4b', '#5fae8a', '#a880c0', '#c96b6b', '#8a9ab5']

/** ECharts 走 canvas 渲染，无法解析 CSS 变量，轴与文字色在这里以字面量对齐 main.css 的 token */
export const AXIS_COLORS = {
  line: '#292e36',
  split: '#21252c',
  label: '#98a0ab'
} as const