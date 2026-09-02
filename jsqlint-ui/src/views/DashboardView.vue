<template>
  <div class="dashboard">
    <!-- 统计卡片 -->
    <el-row :gutter="20" class="stats-row">
      <el-col :xs="24" :sm="12" :md="6">
        <div class="stat-card stat-card--blue">
          <div class="stat-card__icon">
            <el-icon :size="28"><Document /></el-icon>
          </div>
          <div class="stat-card__content">
            <div class="stat-card__value">{{ summaryStats.totalProjects }}</div>
            <div class="stat-card__label">{{ t('dashboard.totalProjects') }}</div>
          </div>
        </div>
      </el-col>

      <el-col :xs="24" :sm="12" :md="6">
        <div class="stat-card stat-card--green">
          <div class="stat-card__icon">
            <el-icon :size="28"><SuccessFilled /></el-icon>
          </div>
          <div class="stat-card__content">
            <div class="stat-card__value">{{ summaryStats.safeSqlCount }}</div>
            <div class="stat-card__label">{{ t('dashboard.safeSql') }}</div>
          </div>
        </div>
      </el-col>

      <el-col :xs="24" :sm="12" :md="6">
        <div class="stat-card stat-card--yellow">
          <div class="stat-card__icon">
            <el-icon :size="28"><WarningFilled /></el-icon>
          </div>
          <div class="stat-card__content">
            <div class="stat-card__value">{{ summaryStats.warningSqlCount }}</div>
            <div class="stat-card__label">{{ t('dashboard.warningSql') }}</div>
          </div>
        </div>
      </el-col>

      <el-col :xs="24" :sm="12" :md="6">
        <div class="stat-card stat-card--red">
          <div class="stat-card__icon">
            <el-icon :size="28"><CircleCloseFilled /></el-icon>
          </div>
          <div class="stat-card__content">
            <div class="stat-card__value">{{ summaryStats.dangerSqlCount }}</div>
            <div class="stat-card__label">{{ t('dashboard.dangerSql') }}</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 图表区域 -->
    <el-row :gutter="20" class="charts-row">
      <el-col :xs="24" :lg="16">
        <el-card class="chart-card">
          <template #header>
            <div class="card-header">
              <span class="card-title">{{ t('dashboard.analysisTrend') }}</span>
              <div class="card-actions">
                <el-radio-group v-model="trendPeriod" size="small">
                  <el-radio-button label="week">{{ t('dashboard.period.week') }}</el-radio-button>
                  <el-radio-button label="month">{{ t('dashboard.period.month') }}</el-radio-button>
                  <el-radio-button label="year">{{ t('dashboard.period.year') }}</el-radio-button>
                </el-radio-group>
              </div>
            </div>
          </template>
          <div ref="trendChartRef" class="chart"></div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="8">
        <el-card class="chart-card">
          <template #header>
            <div class="card-header">
              <span class="card-title">{{ t('dashboard.projectDistribution') }}</span>
            </div>
          </template>
          <div ref="pieChartRef" class="chart"></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 图表区域二：规则命中 / 严重级别分布 / 项目健康度 -->
    <el-row :gutter="20" class="charts-row">
      <el-col :xs="24" :lg="10">
        <el-card class="chart-card">
          <template #header>
            <div class="card-header">
              <span class="card-title">{{ t('dashboard.ruleHitTop') }}</span>
            </div>
          </template>
          <div ref="ruleHitChartRef" class="chart"></div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="7">
        <el-card class="chart-card">
          <template #header>
            <div class="card-header">
              <span class="card-title">{{ t('dashboard.severityDistribution') }}</span>
            </div>
          </template>
          <div ref="severityChartRef" class="chart"></div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="7">
        <el-card class="chart-card">
          <template #header>
            <div class="card-header">
              <span class="card-title">{{ t('dashboard.projectScoreRanking') }}</span>
            </div>
          </template>
          <div ref="scoreChartRef" class="chart"></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 当前任务 -->
    <el-card class="table-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">{{ t('dashboard.currentTasks') }}</span>
        </div>
      </template>
      <el-table :data="visibleTasks" v-loading="tasksLoading" style="width: 100%" stripe>
        <el-table-column prop="projectName" :label="t('dashboard.projectName')" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.projectName || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="typeDescription" :label="t('dashboard.taskType')" width="120">
          <template #default="{ row }">
            {{ row.typeDescription || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="statusDescription" :label="t('dashboard.status')" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="getTaskStatusType(row.status)" effect="light" size="small">
              {{ row.statusDescription || getTaskStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="nextExecTime" :label="t('dashboard.nextExecTime')" width="170" align="center">
          <template #default="{ row }">
            {{ formatTimestamp(row.nextExecTime) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('dashboard.retry')" width="90" align="center">
          <template #default="{ row }">
            {{ row.retryCount ?? 0 }}/{{ row.maxRetries ?? 0 }}
          </template>
        </el-table-column>
        <el-table-column prop="errorMessage" :label="t('dashboard.executionInfo')" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.errorMessage || '-' }}
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="t('dashboard.noCurrentTasks')" />
        </template>
      </el-table>
      <div v-if="currentTasks.length > TASK_PREVIEW_COUNT" class="load-more">
        <el-button
          text
          type="primary"
          :icon="tasksExpanded ? ArrowUp : ArrowDown"
          @click="tasksExpanded = !tasksExpanded"
        >
          {{ tasksExpanded
            ? t('dashboard.collapseTasks')
            : t('dashboard.showMoreTasks', { count: currentTasks.length - TASK_PREVIEW_COUNT }) }}
        </el-button>
      </div>
    </el-card>

    <!-- 最近分析项目列表 -->
    <el-card class="table-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">{{ t('dashboard.recentProjects') }}</span>
        </div>
      </template>
      <el-table :data="visibleRecentProjects" v-loading="recentLoading" style="width: 100%" stripe>
        <el-table-column prop="projectName" :label="t('dashboard.projectName')" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.projectName || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="ref" :label="t('dashboard.branch')" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.ref || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="statusDescription" :label="t('dashboard.status')" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="getProjectStatusType(row.status)" effect="light" size="small">
              {{ row.statusDescription || getProjectStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="commitMessage" :label="t('dashboard.commitMessage')" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.commitMessage || row.commitId || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="analysisTime" :label="t('dashboard.analysisTime')" width="180" align="center">
          <template #default="{ row }">
            {{ formatDate(row.analysisTime) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('dashboard.actions')" width="80" align="center">
          <template #default="{ row }">
            <div class="action-icons-row">
              <el-tooltip :content="t('dashboard.view')" placement="top">
                <el-icon class="action-icon" @click="handleView(row)"><View /></el-icon>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="t('dashboard.noAnalysisRecords')" />
        </template>
      </el-table>
      <div v-if="recentProjects.length > RECENT_PREVIEW_COUNT" class="load-more">
        <el-button
          text
          type="primary"
          :icon="recentExpanded ? ArrowUp : ArrowDown"
          @click="recentExpanded = !recentExpanded"
        >
          {{ recentExpanded
            ? t('dashboard.collapseTasks')
            : t('dashboard.showMoreTasks', { count: recentProjects.length - RECENT_PREVIEW_COUNT }) }}
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import {
  chartColors,
  chartNeutral,
  severityColors,
  seriesPalette,
  darkTooltip
} from '@/utils/chartPalette'
import { ElMessage } from 'element-plus'
import { useTheme } from '@/composables/useTheme'
import { Document, SuccessFilled, WarningFilled, CircleCloseFilled, ArrowDown, ArrowUp, View } from '@element-plus/icons-vue'
import {
  getOverviewAnalysisTrend,
  getOverviewCurrentTasks,
  getOverviewProjectScores,
  getOverviewProjectSummary,
  getOverviewRecentProjects,
  getOverviewRuleHits,
  getOverviewSeverityDistribution,
  type OverviewCurrentTaskVO,
  type OverviewProjectScoreVO,
  type OverviewProjectSummaryVO,
  type OverviewRecentProjectVO,
  type OverviewRuleHitVO,
  type OverviewSeverityDistributionVO,
  type OverviewTrendVO
} from '@/api/overview'

const router = useRouter()
const { t, locale } = useI18n()
const { onThemeChange } = useTheme()
const trendChartRef = ref<HTMLElement>()
const pieChartRef = ref<HTMLElement>()
const ruleHitChartRef = ref<HTMLElement>()
const severityChartRef = ref<HTMLElement>()
const scoreChartRef = ref<HTMLElement>()
const trendPeriod = ref<'week' | 'month' | 'year'>('week')
const recentLoading = ref(false)
const tasksLoading = ref(false)

let trendChart: echarts.ECharts | null = null
let pieChart: echarts.ECharts | null = null
let ruleHitChart: echarts.ECharts | null = null
let severityChart: echarts.ECharts | null = null
let scoreChart: echarts.ECharts | null = null

const stats = ref<OverviewProjectSummaryVO>({
  totalProjects: 0,
  safeSqlCount: 0,
  warningSqlCount: 0,
  dangerSqlCount: 0
})

const recentProjects = ref<OverviewRecentProjectVO[]>([])
// 最近分析列表同样默认展示 5 条，可展开
const RECENT_PREVIEW_COUNT = 5
const recentExpanded = ref(false)
const visibleRecentProjects = computed(() =>
  recentExpanded.value ? recentProjects.value : recentProjects.value.slice(0, RECENT_PREVIEW_COUNT)
)
const currentTasks = ref<OverviewCurrentTaskVO[]>([])
// 当前任务列表默认只展示前 5 条，点击"展示更多"展开全部
const TASK_PREVIEW_COUNT = 5
const tasksExpanded = ref(false)
const visibleTasks = computed(() =>
  tasksExpanded.value ? currentTasks.value : currentTasks.value.slice(0, TASK_PREVIEW_COUNT)
)

const toNumber = (value: unknown) => {
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : 0
}

const summaryStats = computed(() => ({
  totalProjects: toNumber(stats.value.totalProjects),
  safeSqlCount: toNumber(stats.value.safeSqlCount),
  warningSqlCount: toNumber(stats.value.warningSqlCount),
  dangerSqlCount: toNumber(stats.value.dangerSqlCount)
}))

const getProjectStatusType = (status?: number) => {
  if (status === 10 || status === 8 || status === 6 || status === 4 || status === 2) return 'success'
  if (status === 11) return 'danger'
  if (status === 0 || status === undefined || status === null) return 'info'
  return 'warning'
}

const getProjectStatusText = (status?: number) => {
  const texts: Record<number, string> = {
    0: t('dashboard.projectStatus.notExecuted'),
    1: t('dashboard.projectStatus.syncing'),
    2: t('dashboard.projectStatus.synced'),
    3: t('dashboard.projectStatus.compiling'),
    4: t('dashboard.projectStatus.checked'),
    5: t('dashboard.projectStatus.scanning'),
    6: t('dashboard.projectStatus.scanned'),
    7: t('dashboard.projectStatus.auditing'),
    8: t('dashboard.projectStatus.audited'),
    9: t('dashboard.projectStatus.reporting'),
    10: t('dashboard.projectStatus.success'),
    11: t('dashboard.projectStatus.failed')
  }
  return status === undefined || status === null ? t('dashboard.unknown') : texts[status] || t('dashboard.unknown')
}

const getTaskStatusType = (status?: number) => {
  if (status === 1 || status === 5) return 'warning'
  if (status === 3) return 'danger'
  return 'info'
}

const getTaskStatusText = (status?: number) => {
  const texts: Record<number, string> = {
    0: t('dashboard.taskStatus.pending'),
    1: t('dashboard.taskStatus.running'),
    5: t('dashboard.taskStatus.retrying')
  }
  return status === undefined || status === null ? t('dashboard.unknown') : texts[status] || t('dashboard.unknown')
}

const formatDate = (value?: string) => {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString(locale.value)
}

const formatTimestamp = (value?: number) => {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString(locale.value)
}

const handleView = (row: OverviewRecentProjectVO) => {
  if (row.projectId) {
    router.push(`/workspace/projects/${row.projectId}`)
  }
}

const renderTrendChart = (data?: OverviewTrendVO) => {
  if (!trendChart) return
  const option = {
    tooltip: {
      trigger: 'axis',
      backgroundColor: darkTooltip.backgroundColor,
      borderColor: darkTooltip.borderColor,
      textStyle: { color: darkTooltip.textStyle.color }
    },
    legend: {
      data: [t('dashboard.legend.safe'), t('dashboard.legend.warning'), t('dashboard.legend.danger')],
      bottom: 0,
      textStyle: { color: chartNeutral.label }
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '15%',
      top: '10%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: data?.labels || [],
      axisLine: { lineStyle: { color: chartNeutral.axisLine } },
      axisLabel: { color: chartNeutral.label }
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      splitLine: { lineStyle: { color: chartNeutral.splitLine } },
      axisLabel: { color: chartNeutral.label }
    },
    series: [
      {
        name: t('dashboard.legend.safe'),
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 8,
        lineStyle: { color: chartColors.success, width: 3 },
        itemStyle: { color: chartColors.success },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(16, 185, 129, 0.2)' },
            { offset: 1, color: 'rgba(16, 185, 129, 0)' }
          ])
        },
        data: data?.safe || []
      },
      {
        name: t('dashboard.legend.warning'),
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 8,
        lineStyle: { color: chartColors.warning, width: 3 },
        itemStyle: { color: chartColors.warning },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(245, 158, 11, 0.2)' },
            { offset: 1, color: 'rgba(245, 158, 11, 0)' }
          ])
        },
        data: data?.warning || []
      },
      {
        name: t('dashboard.legend.danger'),
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 8,
        lineStyle: { color: chartColors.danger, width: 3 },
        itemStyle: { color: chartColors.danger },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(239, 68, 68, 0.2)' },
            { offset: 1, color: 'rgba(239, 68, 68, 0)' }
          ])
        },
        data: data?.danger || []
      }
    ]
  }

  trendChart.setOption(option)
}

const renderPieChart = () => {
  if (!pieChart) return
  const distribution = [
    { value: summaryStats.value.safeSqlCount, name: t('dashboard.safeSql'), itemStyle: { color: chartColors.success } },
    { value: summaryStats.value.warningSqlCount, name: t('dashboard.warningSql'), itemStyle: { color: chartColors.warning } },
    { value: summaryStats.value.dangerSqlCount, name: t('dashboard.dangerSql'), itemStyle: { color: chartColors.danger } }
  ]
  const totalDistribution = distribution.reduce((sum, item) => sum + item.value, 0)
  // 无扫描数据时展示灰色空态占位，不再用项目总数冒充分布
  const data = totalDistribution > 0
    ? distribution
    : [{ value: 1, name: t('dashboard.noScanData'), itemStyle: { color: chartNeutral.empty } }]

  const option = {
    tooltip: {
      trigger: 'item',
      backgroundColor: darkTooltip.backgroundColor,
      borderColor: darkTooltip.borderColor,
      textStyle: { color: darkTooltip.textStyle.color }
    },
    legend: {
      orient: 'vertical',
      right: '5%',
      top: 'center',
      textStyle: { color: chartNeutral.label }
    },
    series: [{
      type: 'pie',
      radius: ['50%', '75%'],
      center: ['35%', '50%'],
      avoidLabelOverlap: false,
      itemStyle: {
        borderRadius: 8,
        borderColor: '#ffffff', // 环形图分瓣白描边
        borderWidth: 3
      },
      label: { show: false },
      emphasis: {
        label: { show: true, fontSize: 18, fontWeight: 'bold', color: chartNeutral.tooltipBg }
      },
      data
    }]
  }

  pieChart.setOption(option)
}

const initCharts = () => {
  if (trendChartRef.value) {
    trendChart = echarts.init(trendChartRef.value)
    renderTrendChart()
  }
  if (pieChartRef.value) {
    pieChart = echarts.init(pieChartRef.value)
    renderPieChart()
  }
  if (ruleHitChartRef.value) {
    ruleHitChart = echarts.init(ruleHitChartRef.value)
  }
  if (severityChartRef.value) {
    severityChart = echarts.init(severityChartRef.value)
  }
  if (scoreChartRef.value) {
    scoreChart = echarts.init(scoreChartRef.value)
  }
}

/**
 * 规则命中 TOP N：横向柱状图，按规则严重级别着色
 */
const renderRuleHitChart = (data?: OverviewRuleHitVO) => {
  if (!ruleHitChart) return
  // echarts 横向柱状图自下而上绘制，倒序传入使命中最多的在最上方
  const rules = [...(data?.topRules || [])].reverse()
  const option = {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: darkTooltip.backgroundColor,
      borderColor: darkTooltip.borderColor,
      textStyle: { color: darkTooltip.textStyle.color }
    },
    grid: {
      left: '3%',
      right: '8%',
      top: '5%',
      bottom: '5%',
      containLabel: true
    },
    xAxis: {
      type: 'value',
      axisLine: { show: false },
      splitLine: { lineStyle: { color: chartNeutral.splitLine } },
      axisLabel: { color: chartNeutral.label }
    },
    yAxis: {
      type: 'category',
      data: rules.map(rule => rule.ruleName),
      axisLine: { lineStyle: { color: chartNeutral.axisLine } },
      axisLabel: { color: chartNeutral.label, width: 120, overflow: 'truncate' }
    },
    series: [{
      type: 'bar',
      barMaxWidth: 18,
      data: rules.map(rule => ({
        value: rule.hitCount,
        itemStyle: { color: severityColors[rule.severity || ''] || seriesPalette[0], borderRadius: [0, 4, 4, 0] }
      })),
      label: {
        show: true,
        position: 'right',
        color: chartNeutral.label
      }
    }]
  }
  ruleHitChart.setOption(option)
}

/**
 * 问题严重级别分布：环形图，中心显示总问题数
 */
const renderSeverityChart = (data?: OverviewSeverityDistributionVO) => {
  if (!severityChart) return
  const entries = [
    { value: data?.criticalCount || 0, name: t('dashboard.severity.critical'), key: 'critical' },
    { value: data?.highCount || 0, name: t('dashboard.severity.high'), key: 'high' },
    { value: data?.warningCount || 0, name: t('dashboard.severity.warning'), key: 'warning' },
    { value: data?.infoCount || 0, name: t('dashboard.severity.info'), key: 'info' }
  ]
  const total = entries.reduce((sum, item) => sum + item.value, 0)
  const seriesData = total > 0
    ? entries.map(item => ({ ...item, itemStyle: { color: severityColors[item.key] } }))
    : [{ value: 1, name: t('dashboard.noIssues'), itemStyle: { color: chartNeutral.empty } }]

  const option = {
    tooltip: {
      trigger: 'item',
      backgroundColor: darkTooltip.backgroundColor,
      borderColor: darkTooltip.borderColor,
      textStyle: { color: darkTooltip.textStyle.color }
    },
    series: [{
      type: 'pie',
      radius: ['55%', '78%'],
      center: ['50%', '50%'],
      avoidLabelOverlap: false,
      itemStyle: {
        borderRadius: 6,
        borderColor: '#ffffff', // 环形图分瓣白描边
        borderWidth: 2
      },
      label: {
        show: true,
        position: 'center',
        formatter: total > 0 ? `{total|${total}}\n{label|${t('dashboard.totalIssues')}}` : `{label|${t('dashboard.noIssues')}}`,
        rich: {
          total: { fontSize: 22, fontWeight: 'bold', color: chartNeutral.tooltipBg },
          label: { fontSize: 12, color: chartNeutral.label, padding: [4, 0, 0, 0] }
        }
      },
      data: seriesData
    }]
  }
  severityChart.setOption(option)
}

/**
 * 项目健康度排行：横向条形图，分数映射红→绿色带，最差项目在上
 */
const renderScoreChart = (data?: OverviewProjectScoreVO) => {
  if (!scoreChart) return
  // 评分升序返回（最差在前），倒序绘制使最差在顶部
  const projects = [...(data?.projects || [])].reverse()
  const scoreColor = (score: number) => {
    if (score >= 90) return chartColors.success
    if (score >= 75) return '#84cc16' // 黄绿过渡档，仅此处使用
    if (score >= 60) return chartColors.warning
    return chartColors.danger
  }
  const option = {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: darkTooltip.backgroundColor,
      borderColor: darkTooltip.borderColor,
      textStyle: { color: darkTooltip.textStyle.color },
      formatter: (params: unknown) => {
        const item = Array.isArray(params) ? params[0] : params
        const project = projects[item.dataIndex]
        if (!project) return ''
        return `${project.projectName}<br/>${t('dashboard.score')}: ${project.score} (${project.rating})<br/>${t('dashboard.issueSqlCount')}: ${project.issueSqlCount}`
      }
    },
    grid: {
      left: '3%',
      right: '12%',
      top: '5%',
      bottom: '5%',
      containLabel: true
    },
    xAxis: {
      type: 'value',
      max: 100,
      axisLine: { show: false },
      splitLine: { lineStyle: { color: chartNeutral.splitLine } },
      axisLabel: { color: chartNeutral.label }
    },
    yAxis: {
      type: 'category',
      data: projects.map(project => project.projectName),
      axisLine: { lineStyle: { color: chartNeutral.axisLine } },
      axisLabel: { color: chartNeutral.label, width: 100, overflow: 'truncate' }
    },
    series: [{
      type: 'bar',
      barMaxWidth: 16,
      data: projects.map(project => ({
        value: project.score,
        itemStyle: { color: scoreColor(project.score), borderRadius: [0, 4, 4, 0] }
      })),
      label: {
        show: true,
        position: 'right',
        color: chartNeutral.label,
        formatter: '{c}'
      }
    }]
  }
  scoreChart.setOption(option)
}

const loadSummary = async () => {
  stats.value = await getOverviewProjectSummary()
  renderPieChart()
}

const loadTrend = async () => {
  const trend = await getOverviewAnalysisTrend(trendPeriod.value)
  renderTrendChart(trend)
}

const loadRecentProjects = async () => {
  recentLoading.value = true
  try {
    recentProjects.value = await getOverviewRecentProjects()
  } finally {
    recentLoading.value = false
  }
}

const loadCurrentTasks = async () => {
  tasksLoading.value = true
  try {
    currentTasks.value = await getOverviewCurrentTasks()
  } finally {
    tasksLoading.value = false
  }
}

const loadOverviewCharts = async () => {
  const [ruleHits, severity, scores] = await Promise.all([
    getOverviewRuleHits(),
    getOverviewSeverityDistribution(),
    getOverviewProjectScores()
  ])
  renderRuleHitChart(ruleHits)
  renderSeverityChart(severity)
  renderScoreChart(scores)
}

const loadDashboard = async () => {
  try {
    await Promise.all([
      loadSummary(),
      loadTrend(),
      loadRecentProjects(),
      loadCurrentTasks(),
      loadOverviewCharts()
    ])
  } catch {
    ElMessage.error(t('dashboard.loadError'))
  }
}

const handleResize = () => {
  trendChart?.resize()
  pieChart?.resize()
  ruleHitChart?.resize()
  severityChart?.resize()
  scoreChart?.resize()
}

watch(trendPeriod, async () => {
  try {
    await loadTrend()
  } catch {
    ElMessage.error(t('dashboard.trendLoadError'))
  }
})

onMounted(async () => {
  await nextTick()
  initCharts()
  loadDashboard()
  window.addEventListener('resize', handleResize)
  // 主题切换时重绘图表，使中性色（轴线/文字）适配深浅色
  onThemeChange(() => {
    renderPieChart()
    loadOverviewCharts()
  })
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  trendChart?.dispose()
  pieChart?.dispose()
  ruleHitChart?.dispose()
  severityChart?.dispose()
  scoreChart?.dispose()
})
</script>

<style lang="scss" scoped>
.dashboard {
  // 内容自然撑高，由 MainLayout 的 .main-content 滚动；
  // 不能用 height:100% + 子项 flex:1，否则内容被压进一屏、表格内出现嵌套滚动
  min-height: 100%;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

// ============================================
// 统计卡片
// ============================================
.stats-row {
  .el-col {
    margin-bottom: 16px;
  }
}

.stat-card {
  background: var(--jsp-bg-card);
  border-radius: 12px;
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  transition: all 0.2s ease;
  position: relative;
  overflow: hidden;

  &:hover {
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
    transform: translateY(-2px);
  }

  &__icon {
    width: 56px;
    height: 56px;
    border-radius: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }

  &__content {
    flex: 1;
    min-width: 0;
  }

  &__value {
    font-size: 28px;
    font-weight: 700;
    color: var(--jsp-text-primary);
    line-height: 1.2;
  }

  &__label {
    font-size: 14px;
    color: var(--jsp-text-secondary);
    margin-top: 4px;
  }

  // 颜色变体
  &--blue .stat-card__icon {
    background: linear-gradient(135deg, $primary-color 0%, $primary-hover 100%);
    color: $text-inverse;
  }

  &--green .stat-card__icon {
    background: linear-gradient(135deg, $success-color 0%, $success-color 100%);
    color: $text-inverse;
  }

  &--yellow .stat-card__icon {
    background: linear-gradient(135deg, $warning-color 0%, $warning-color 100%);
    color: $text-inverse;
  }

  &--red .stat-card__icon {
    background: linear-gradient(135deg, $danger-color 0%, $danger-color 100%);
    color: $text-inverse;
  }
}

// ============================================
// 图表卡片
// ============================================
.charts-row {
  .el-col {
    margin-bottom: 16px;
  }
}

.chart-card {
  height: 100%;

  :deep(.el-card__body) {
    height: calc(100% - 60px);
  }

  .card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .card-title {
    font-size: 16px;
    font-weight: 600;
    color: var(--jsp-text-primary);
  }

  .chart {
    height: 280px;
    width: 100%;
  }
}

// ============================================
// 表格卡片
// ============================================
.load-more {
  display: flex;
  justify-content: center;
  padding-top: 12px;
  border-top: 1px solid var(--jsp-border-light);
}

.table-card {
  // 随内容自然撑高，不再与兄弟元素分食一屏高度
  display: flex;
  flex-direction: column;

  :deep(.el-card__body) {
    display: flex;
    flex-direction: column;
    padding-top: 16px;
  }

  :deep(.el-table) {
    // 空数据时 el-table 高度塌缩只剩表头，保住空态面板高度
    min-height: 280px;
  }

  :deep(.el-table__empty-block) {
    min-height: 200px;
  }
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;

  .card-title {
    font-size: 16px;
    font-weight: 600;
    color: var(--jsp-text-primary);
  }

  .text-action {
    display: inline-flex;
    align-items: center;
    color: $primary-color;
    font-size: 14px;
    cursor: pointer;

    &:hover {
      color: $primary-hover;
    }
  }
}
</style>
