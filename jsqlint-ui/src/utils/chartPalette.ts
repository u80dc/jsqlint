/**
 * echarts 图表统一色板。
 *
 * <p>所有图表配色从这里取值，与 _tokens.scss 的语义令牌保持一致；
 * 后续若引入深色模式，只需在此扩展 dark 变体。</p>
 *
 * @author zhangcs
 */

/** 语义色：与设计令牌同值 */
export const chartColors = {
  primary: '#3b82f6',
  success: '#10b981',
  warning: '#f59e0b',
  danger: '#ef4444',
  info: '#3b82f6'
} as const

/** 轴线/网格/文字等中性色 */
export const chartNeutral = {
  axisLine: '#e2e8f0',
  splitLine: '#f1f5f9',
  label: '#64748b',
  labelDark: '#334155',
  tooltipBg: '#1e293b',
  tooltipBorder: '#334155',
  tooltipText: '#f8fafc',
  empty: '#e2e8f0'
} as const

/** 严重级别（critical/high/warning/info）语义色 */
export const severityColors: Record<string, string> = {
  critical: chartColors.danger,
  high: '#f97316',
  warning: chartColors.warning,
  info: chartColors.primary
}

/** 多系列分类色（顺序即系列顺序） */
export const seriesPalette = [
  chartColors.primary,
  chartColors.success,
  chartColors.warning,
  chartColors.danger,
  '#8b5cf6',
  '#94a3b8'
] as const

/** 深色 tooltip 通用配置 */
export const darkTooltip = {
  backgroundColor: chartNeutral.tooltipBg,
  borderColor: chartNeutral.tooltipBorder,
  textStyle: { color: chartNeutral.tooltipText }
} as const
