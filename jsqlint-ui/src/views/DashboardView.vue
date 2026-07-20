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

    <!-- 当前任务 -->
    <el-card class="table-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">{{ t('dashboard.currentTasks') }}</span>
        </div>
      </template>
      <el-table :data="currentTasks" v-loading="tasksLoading" style="width: 100%" stripe>
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
            <el-tag :type="getTaskStatusType(row.status)" effect="light">
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
    </el-card>

    <!-- 最近分析项目列表 -->
    <el-card class="table-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">{{ t('dashboard.recentProjects') }}</span>
          <span class="text-action" @click="handleViewAllProjects">
            {{ t('dashboard.viewAll') }}
            <el-icon style="margin-left: 4px"><ArrowRight /></el-icon>
          </span>
        </div>
      </template>
      <el-table :data="recentProjects" v-loading="recentLoading" style="width: 100%" stripe>
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
            <el-tag :type="getProjectStatusType(row.status)" effect="light">
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
        <el-table-column :label="t('dashboard.actions')" width="120" align="center">
          <template #default="{ row }">
            <span class="action-btn action-btn--info" @click="handleView(row)">{{ t('dashboard.view') }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="t('dashboard.noAnalysisRecords')" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { Document, SuccessFilled, WarningFilled, CircleCloseFilled, ArrowRight } from '@element-plus/icons-vue'
import {
  getOverviewAnalysisTrend,
  getOverviewCurrentTasks,
  getOverviewProjectSummary,
  getOverviewRecentProjects,
  type OverviewCurrentTaskVO,
  type OverviewProjectSummaryVO,
  type OverviewRecentProjectVO,
  type OverviewTrendVO
} from '@/api/overview'

const router = useRouter()
const { t, locale } = useI18n()
const trendChartRef = ref<HTMLElement>()
const pieChartRef = ref<HTMLElement>()
const trendPeriod = ref<'week' | 'month' | 'year'>('week')
const recentLoading = ref(false)
const tasksLoading = ref(false)

let trendChart: echarts.ECharts | null = null
let pieChart: echarts.ECharts | null = null

const stats = ref<OverviewProjectSummaryVO>({
  totalProjects: 0,
  safeSqlCount: 0,
  warningSqlCount: 0,
  dangerSqlCount: 0
})

const recentProjects = ref<OverviewRecentProjectVO[]>([])
const currentTasks = ref<OverviewCurrentTaskVO[]>([])

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

const handleViewAllProjects = () => {
  router.push('/workspace/projects')
}

const renderTrendChart = (data?: OverviewTrendVO) => {
  if (!trendChart) return
  const option = {
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#1e293b',
      borderColor: '#334155',
      textStyle: { color: '#f8fafc' }
    },
    legend: {
      data: [t('dashboard.legend.safe'), t('dashboard.legend.warning'), t('dashboard.legend.danger')],
      bottom: 0,
      textStyle: { color: '#64748b' }
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
      axisLine: { lineStyle: { color: '#e2e8f0' } },
      axisLabel: { color: '#64748b' }
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      splitLine: { lineStyle: { color: '#f1f5f9' } },
      axisLabel: { color: '#64748b' }
    },
    series: [
      {
        name: t('dashboard.legend.safe'),
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 8,
        lineStyle: { color: '#10b981', width: 3 },
        itemStyle: { color: '#10b981' },
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
        lineStyle: { color: '#f59e0b', width: 3 },
        itemStyle: { color: '#f59e0b' },
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
        lineStyle: { color: '#ef4444', width: 3 },
        itemStyle: { color: '#ef4444' },
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
    { value: summaryStats.value.safeSqlCount, name: t('dashboard.safeSql'), itemStyle: { color: '#10b981' } },
    { value: summaryStats.value.warningSqlCount, name: t('dashboard.warningSql'), itemStyle: { color: '#f59e0b' } },
    { value: summaryStats.value.dangerSqlCount, name: t('dashboard.dangerSql'), itemStyle: { color: '#ef4444' } }
  ]
  const totalDistribution = distribution.reduce((sum, item) => sum + item.value, 0)
  const data = totalDistribution > 0
    ? distribution
    : [{ value: summaryStats.value.totalProjects, name: t('dashboard.uncategorized'), itemStyle: { color: '#94a3b8' } }]

  const option = {
    tooltip: {
      trigger: 'item',
      backgroundColor: '#1e293b',
      borderColor: '#334155',
      textStyle: { color: '#f8fafc' }
    },
    legend: {
      orient: 'vertical',
      right: '5%',
      top: 'center',
      textStyle: { color: '#64748b' }
    },
    series: [{
      type: 'pie',
      radius: ['50%', '75%'],
      center: ['35%', '50%'],
      avoidLabelOverlap: false,
      itemStyle: {
        borderRadius: 8,
        borderColor: '#ffffff',
        borderWidth: 3
      },
      label: { show: false },
      emphasis: {
        label: { show: true, fontSize: 18, fontWeight: 'bold', color: '#1e293b' }
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

const loadDashboard = async () => {
  try {
    await Promise.all([
      loadSummary(),
      loadTrend(),
      loadRecentProjects(),
      loadCurrentTasks()
    ])
  } catch {
    ElMessage.error(t('dashboard.loadError'))
  }
}

const handleResize = () => {
  trendChart?.resize()
  pieChart?.resize()
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
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  trendChart?.dispose()
  pieChart?.dispose()
})
</script>

<style lang="scss" scoped>
.dashboard {
  height: 100%;
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
  background: #ffffff;
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
    color: #1e293b;
    line-height: 1.2;
  }

  &__label {
    font-size: 14px;
    color: #64748b;
    margin-top: 4px;
  }

  // 颜色变体
  &--blue .stat-card__icon {
    background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%);
    color: #fff;
  }

  &--green .stat-card__icon {
    background: linear-gradient(135deg, #10b981 0%, #059669 100%);
    color: #fff;
  }

  &--yellow .stat-card__icon {
    background: linear-gradient(135deg, #f59e0b 0%, #d97706 100%);
    color: #fff;
  }

  &--red .stat-card__icon {
    background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%);
    color: #fff;
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
    color: #1e293b;
  }

  .chart {
    height: 280px;
    width: 100%;
  }
}

// ============================================
// 表格卡片
// ============================================
.table-card {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;

  :deep(.el-card__body) {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding-top: 16px;
  }

  :deep(.el-table) {
    flex: 1;
  }
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;

  .card-title {
    font-size: 16px;
    font-weight: 600;
    color: #1e293b;
  }

  .text-action {
    display: inline-flex;
    align-items: center;
    color: #3b82f6;
    font-size: 14px;
    cursor: pointer;

    &:hover {
      color: #2563eb;
    }
  }
}
</style>
