<template>
  <div class="content-page">
    <el-card v-loading="loading" class="report-card">
      <template #header>
        <div class="card-header">
          <span class="section-title">{{ t('projectDetail.report.title') }}</span>
          <el-tooltip v-if="report" :content="t('projectDetail.report.download')" placement="top">
            <el-button text class="report-download-btn" @click="$emit('download')">
              <el-icon><Download /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
      </template>
      <el-empty v-if="!report" :description="t('projectDetail.report.empty')" />
      <div v-else class="report-content">
        <div class="report-summary">
          <div class="report-score">
            <div class="score-main">{{ report.score ?? 0 }}</div>
            <el-tag :type="getRatingType(report.rating)" size="small">
              {{ report.rating || '-' }}
            </el-tag>
            <span class="score-time">
              {{ t('projectDetail.report.generatedTime') }} {{ formatDate(report.generatedTime) }}
            </span>
          </div>
          <div class="report-metrics">
            <div class="report-metric">
              <span class="metric-label">{{ t('projectDetail.report.totalSql') }}</span>
              <strong>{{ report.totalSqlCount ?? 0 }}</strong>
            </div>
            <div class="report-metric">
              <span class="metric-label">{{ t('projectDetail.report.issueSql') }}</span>
              <strong>{{ report.issueSqlCount ?? 0 }}</strong>
            </div>
            <div class="report-metric">
              <span class="metric-label">{{ t('projectDetail.report.issueRate') }}</span>
              <strong>{{ formatPercent(report.issueSqlRate) }}</strong>
            </div>
            <div class="report-metric">
              <span class="metric-label">{{ t('projectDetail.report.issueCount') }}</span>
              <strong>{{ report.issueCount ?? 0 }}</strong>
            </div>
          </div>
        </div>

        <div class="report-grid">
          <div class="report-section">
            <div class="report-section-title">{{ t('projectDetail.report.riskLevel') }}</div>
            <div class="risk-list">
              <span class="risk-item critical">{{ t('severity.critical') }} {{ report.criticalCount ?? 0 }}</span>
              <span class="risk-item high">{{ t('severity.high') }} {{ report.highCount ?? 0 }}</span>
              <span class="risk-item warning">{{ t('severity.warning') }} {{ report.warningCount ?? 0 }}</span>
              <span class="risk-item info">{{ t('severity.info') }} {{ report.infoCount ?? 0 }}</span>
            </div>
          </div>
          <div class="report-section">
            <div class="report-section-title">{{ t('projectDetail.report.issueCategory') }}</div>
            <div class="risk-list">
              <span class="risk-item security">{{ t('projectDetail.report.security') }} {{ report.securityIssueSqlCount ?? 0 }}</span>
              <span class="risk-item performance">{{ t('projectDetail.report.performance') }} {{ report.performanceIssueSqlCount ?? 0 }}</span>
              <span class="risk-item quality">{{ t('projectDetail.report.quality') }} {{ report.qualityIssueSqlCount ?? 0 }}</span>
              <span class="risk-item other">{{ t('projectDetail.report.other') }} {{ report.otherIssueSqlCount ?? 0 }}</span>
            </div>
          </div>
        </div>

        <div class="report-tables">
          <el-table :data="report.topRuleJson || []" size="small" stripe>
            <el-table-column :label="t('projectDetail.report.topRule')" min-width="160">
              <template #default="{ row }">{{ displayReportField(row, ['ruleName', 'name', 'ruleCode', 'code']) }}</template>
            </el-table-column>
            <el-table-column :label="t('projectDetail.report.count')" width="90" align="center">
              <template #default="{ row }">{{ displayReportField(row, ['count', 'issueCount', 'hitCount']) }}</template>
            </el-table-column>
          </el-table>
          <el-table :data="report.topFileJson || []" size="small" stripe>
            <el-table-column :label="t('projectDetail.report.topFile')" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ displayReportField(row, ['filePath', 'path', 'file']) }}</template>
            </el-table-column>
            <el-table-column :label="t('projectDetail.report.count')" width="90" align="center">
              <template #default="{ row }">{{ displayReportField(row, ['count', 'issueCount']) }}</template>
            </el-table-column>
          </el-table>
        </div>

        <el-table :data="highRiskSqlRows" size="small" stripe class="high-risk-table">
          <el-table-column :label="t('projectDetail.report.highRiskSql')" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">{{ displayReportField(row, ['sql', 'sqlContent', 'content']) }}</template>
          </el-table-column>
          <el-table-column :label="t('projectDetail.report.location')" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ displayReportField(row, ['statementId']) }}</template>
          </el-table-column>
          <el-table-column :label="t('projectDetail.report.level')" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="getSeverityType(String(displayReportField(row, ['severity', 'riskLevel'])))" size="small">
                {{ displaySeverityLabel(displayReportField(row, ['severity', 'riskLevel'])) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('projectDetail.report.triggerRule')" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">
              <el-tooltip
                :content="displayReportField(row, ['message'])"
                :disabled="displayReportField(row, ['message']) === '-'"
                placement="top"
              >
                <span>{{ displayReportField(row, ['ruleName']) }}</span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column :label="t('projectDetail.report.suggestion')" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ displayReportField(row, ['suggestion']) }}</template>
          </el-table-column>
          <el-table-column :label="t('projectDetail.report.operation')" width="220" align="center" fixed="right">
            <template #default="{ row }">
              <span class="high-risk-actions">
                <span
                  v-if="displayReportField(row, ['scanId']) !== '-'"
                  class="action-btn"
                  @click="handleViewConstruction(row)"
                >
                  {{ t('projectDetail.report.constructionDetail') }}
                </span>
                <span
                  v-if="canIgnoreIssue(row)"
                  class="action-btn danger"
                  @click="openIgnoreDialog(row)"
                >
                  {{ t('projectDetail.report.ignoreIssue') }}
                </span>
                <span v-if="displayReportField(row, ['scanId']) === '-' && !canIgnoreIssue(row)" class="text-secondary">-</span>
              </span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>

    <el-dialog
      v-model="ignoreDialogVisible"
      :title="t('projectDetail.report.ignoreDialogTitle')"
      width="560px"
      align-center
      @closed="resetIgnoreDialog"
    >
      <div class="ignore-dialog">
        <p class="ignore-message">{{ t('projectDetail.report.ignoreDialogMessage') }}</p>
        <div class="ignore-summary">
          <div>
            <span>{{ t('projectDetail.report.triggerRule') }}</span>
            <strong>{{ ignoreRuleName }}</strong>
          </div>
          <div>
            <span>{{ t('projectDetail.report.highRiskSql') }}</span>
            <code>{{ ignoreSql }}</code>
          </div>
        </div>
        <el-input
          v-model="ignoreReason"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          :placeholder="t('projectDetail.report.ignoreReasonPlaceholder')"
        />
      </div>
      <template #footer>
        <el-button @click="ignoreDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="ignoreSubmitting" @click="confirmIgnoreIssue">
          {{ t('common.confirm') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { Download } from '@element-plus/icons-vue'
import type { ProjectReportVO } from '@/api/projectReport'
import { createSqlWhitelist } from '@/api/sqlWhitelist'

const props = defineProps<{
  loading: boolean
  report: ProjectReportVO | null
}>()

defineEmits<{
  download: []
}>()

const { t, locale } = useI18n()
const router = useRouter()
const ignoreDialogVisible = ref(false)
const ignoreSubmitting = ref(false)
const ignoreReason = ref('')
const ignoreTarget = ref<Record<string, unknown> | null>(null)
const ignoredIssueKeys = ref<Set<string>>(new Set())

const highRiskSqlRows = computed(() => {
  return (props.report?.highRiskSqlJson || []).filter(row => !ignoredIssueKeys.value.has(whitelistKey(row)))
})

const ignoreSql = computed(() => {
  return ignoreTarget.value ? displayReportField(ignoreTarget.value, ['sql', 'sqlContent', 'content']) : '-'
})

const ignoreRuleName = computed(() => {
  return ignoreTarget.value ? displayReportField(ignoreTarget.value, ['ruleName', 'ruleCode']) : '-'
})

watch(() => props.report?.id, () => {
  ignoredIssueKeys.value = new Set()
})

const getRatingType = (rating?: string) => {
  switch (rating?.toUpperCase()) {
    case 'A': return 'success'
    case 'B': return 'primary'
    case 'C': return 'warning'
    case 'D': return 'danger'
    default: return 'info'
  }
}

const getSeverityType = (severity: string) => {
  switch (getSeverityKey(severity)) {
    case 'critical': return 'danger'
    case 'high': return 'warning'
    case 'warning': return 'info'
    default: return 'info'
  }
}

const getSeverityKey = (severity: string) => {
  switch (severity?.trim().toLowerCase()) {
    case 'critical':
    case '致命':
      return 'critical'
    case 'high':
    case '高危':
      return 'high'
    case 'medium':
    case '中':
      return 'medium'
    case 'low':
    case '低':
      return 'low'
    case 'warning':
    case '警告':
      return 'warning'
    case 'info':
    case '信息':
      return 'info'
    default:
      return ''
  }
}

const displaySeverityLabel = (severity: string) => {
  const key = getSeverityKey(severity)
  return key ? t(`severity.${key}`) : severity
}

const formatPercent = (value?: number | string) => {
  if (value === undefined || value === null || value === '') {
    return '0%'
  }
  const numeric = Number(value)
  if (Number.isNaN(numeric)) {
    return `${value}%`
  }
  return `${numeric.toFixed(2)}%`
}

const displayReportField = (row: Record<string, unknown>, keys: string[]) => {
  for (const key of keys) {
    const value = row?.[key]
    if (value !== undefined && value !== null && value !== '') {
      return String(value)
    }
  }
  return '-'
}

const whitelistKey = (row: Record<string, unknown>) => {
  return `${displayReportField(row, ['ruleId'])}:${displayReportField(row, ['sql', 'sqlContent', 'content'])}`
}

const canIgnoreIssue = (row: Record<string, unknown>) => {
  const sql = displayReportField(row, ['sql', 'sqlContent', 'content'])
  const ruleId = Number(displayReportField(row, ['ruleId']))
  return sql !== '-' && Number.isFinite(ruleId) && ruleId > 0
}

const openIgnoreDialog = (row: Record<string, unknown>) => {
  if (!canIgnoreIssue(row)) {
    ElMessage.warning(t('projectDetail.report.ignoreUnavailable'))
    return
  }
  ignoreTarget.value = row
  ignoreReason.value = ''
  ignoreDialogVisible.value = true
}

const resetIgnoreDialog = () => {
  ignoreTarget.value = null
  ignoreReason.value = ''
}

const confirmIgnoreIssue = async () => {
  if (!props.report?.projectId || !ignoreTarget.value) {
    return
  }
  const sql = displayReportField(ignoreTarget.value, ['sql', 'sqlContent', 'content'])
  const ruleId = Number(displayReportField(ignoreTarget.value, ['ruleId']))
  if (sql === '-' || !Number.isFinite(ruleId) || ruleId <= 0) {
    ElMessage.warning(t('projectDetail.report.ignoreUnavailable'))
    return
  }
  ignoreSubmitting.value = true
  try {
    await createSqlWhitelist(props.report.projectId, {
      sql,
      ruleId,
      reason: ignoreReason.value.trim() || t('projectDetail.report.ignoreDefaultReason')
    })
    ignoredIssueKeys.value = new Set([...ignoredIssueKeys.value, whitelistKey(ignoreTarget.value)])
    ElMessage.success(t('projectDetail.report.ignoreSuccess'))
    ignoreDialogVisible.value = false
  } finally {
    ignoreSubmitting.value = false
  }
}

const handleViewConstruction = (row: Record<string, unknown>) => {
  const scanId = Number(displayReportField(row, ['scanId']))
  if (!scanId || Number.isNaN(scanId)) {
    return
  }
  router.push({
    path: `/workspace/sql-statements/${scanId}/construction`,
    query: props.report?.projectId
      ? {
        projectId: String(props.report.projectId),
        fromTab: 'projectSql',
        title: displayReportField(row, ['statementId'])
      }
      : {}
  })
}

const formatDate = (date?: string) => {
  if (!date) return '-'
  return new Date(date).toLocaleString(locale.value)
}
</script>

<style lang="scss" scoped>
.content-page {
  .el-card {
    min-height: 400px;

    :deep(.el-card__body) {
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 300px;
    }
  }

  .report-card {
    :deep(.el-card__body) {
      display: block;
      min-height: 300px;
      padding: 20px 24px;
    }
  }
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;

  .section-title {
    font-size: 16px;
    font-weight: 600;
    color: var(--jsp-text-primary);
  }
}

.report-download-btn {
  padding: 4px;
  color: var(--jsp-text-secondary);

  .el-icon {
    font-size: 18px;
  }

  &:hover {
    color: $primary-color;
  }
}

.report-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.report-summary {
  display: grid;
  grid-template-columns: 180px 1fr;
  gap: 16px;
  align-items: stretch;
}

.report-score,
.report-metric,
.report-section {
  border-bottom: 1px solid var(--jsp-border-color);
  border-radius: 8px;
  background: var(--jsp-bg-card);
}

.report-score {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 18px;

  .score-main {
    font-size: 42px;
    line-height: 1;
    font-weight: 700;
    color: var(--jsp-text-primary);
  }

  .score-time {
    color: var(--jsp-text-secondary);
    font-size: 12px;
    text-align: center;
  }
}

.report-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.report-metric {
  padding: 16px;
  display: flex;
  flex-direction: column;
  justify-content: center;

  .metric-label {
    color: var(--jsp-text-secondary);
    font-size: 13px;
    margin-bottom: 8px;
  }

  strong {
    color: var(--jsp-text-primary);
    font-size: 24px;
    line-height: 1;
  }
}

.report-grid,
.report-tables {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.report-section {
  padding: 16px;
}

.report-section-title {
  color: var(--jsp-text-primary);
  font-weight: 600;
  margin-bottom: 12px;
}

.risk-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.risk-item {
  padding: 5px 10px;
  border-radius: 6px;
  font-size: 13px;
  background: $border-light;
  color: $text-secondary-dark;

  &.critical,
  &.security {
    background: $danger-light;
    color: $danger-color;
  }

  &.high,
  &.performance {
    background: $warning-light;
    color: $warning-color;
  }

  &.warning,
  &.quality {
    background: $primary-light;
    color: $primary-hover;
  }

  &.info,
  &.other {
    background: $border-light;
    color: $text-secondary-dark;
  }
}

.high-risk-table {
  margin-top: 0;
}

.high-risk-actions {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  white-space: nowrap;

  .action-btn {
    cursor: pointer;
    color: $primary-hover;

    &:hover {
      color: $primary-hover;
    }

    &.danger {
      color: $danger-color;

      &:hover {
        color: $danger-color;
      }
    }
  }
}

.ignore-dialog {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.ignore-message {
  margin: 0;
  color: $text-secondary-dark;
  line-height: 1.6;
}

.ignore-summary {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border-bottom: 1px solid var(--jsp-border-color);
  border-radius: 8px;
  background: var(--jsp-bg-card);

  div {
    display: flex;
    align-items: flex-start;
    gap: 10px;
    min-width: 0;
  }

  span {
    width: 72px;
    flex-shrink: 0;
    color: var(--jsp-text-secondary);
  }

  strong,
  code {
    flex: 1;
    min-width: 0;
    color: var(--jsp-text-primary);
    word-break: break-all;
  }

  code {
    font-family: Monaco, Menlo, Consolas, monospace;
    white-space: normal;
  }
}
</style>
