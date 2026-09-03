<template>
  <div class="sql-construction-page">
    <el-skeleton v-if="loading && !detail" :rows="8" animated />
    <el-empty v-else-if="!detail" :description="t('sqlConstruction.notFound')" />
    <div v-else class="detail-body">
      <section class="summary-band">
        <div class="summary-item">
          <span>{{ t('sqlConstruction.statementType') }}</span>
          <strong>{{ detail.definition.statementType || '-' }}</strong>
        </div>
        <div class="summary-item">
          <span>{{ t('sqlConstruction.constructionType') }}</span>
          <strong>{{ detail.definition.constructionType || '-' }}</strong>
        </div>
        <div class="summary-item">
          <span>{{ t('sqlConstruction.source') }}</span>
          <strong>{{ detail.definition.sourceType || '-' }}</strong>
        </div>
        <div class="summary-item">
          <span>{{ t('sqlConstruction.parameterCount') }}</span>
          <strong>{{ detail.definition.parameterCount ?? 0 }}</strong>
        </div>
        <div class="summary-item">
          <span>{{ t('sqlConstruction.dynamicSql') }}</span>
          <strong>{{ detail.definition.dynamic ? t('common.yes') : t('common.no') }}</strong>
        </div>
      </section>

      <el-card class="definition-section">
        <div class="section-heading">
          <h2>{{ t('sqlConstruction.definitionLocation') }}</h2>
        </div>
        <div class="definition-grid">
          <div>
            <span>{{ t('sqlConstruction.mapperClass') }}</span>
            <strong>{{ detail.definition.mapperClass || '-' }}</strong>
          </div>
          <div>
            <span>{{ t('sqlConstruction.method') }}</span>
            <strong>{{ detail.definition.mapperMethod || '-' }}</strong>
          </div>
          <div>
            <span>{{ t('sqlConstruction.file') }}</span>
            <strong>{{ sourceLocation }}</strong>
          </div>
          <div>
            <span>Hash</span>
            <strong>{{ detail.definition.sqlHash || '-' }}</strong>
          </div>
        </div>
        <pre class="sql-block">{{ detail.definition.rawSql || t('sqlConstruction.noRawSql') }}</pre>
      </el-card>

      <div class="workbench">
        <el-card class="timeline-section">
          <div class="section-heading">
            <h2>{{ t('sqlConstruction.constructionProcess') }}</h2>
            <span>{{ t('sqlConstruction.nodeCount', { count: detail.constructionSteps.length }) }}</span>
          </div>
          <el-empty v-if="detail.constructionSteps.length === 0" :description="t('sqlConstruction.noConstructionSteps')" />
          <div v-else class="construction-timeline">
            <div
              v-for="step in detail.constructionSteps"
              :key="step.id"
              class="timeline-row"
              :style="{ '--depth': step.depth }"
            >
              <div class="timeline-rail">
                <div class="timeline-marker">{{ step.order }}</div>
              </div>
              <div class="timeline-content">
                <div class="step-header">
                  <div>
                    <el-tag size="small" effect="plain">{{ step.nodeType || 'NODE' }}</el-tag>
                    <strong>{{ step.title }}</strong>
                  </div>
                  <span v-if="step.dynamic" class="dynamic-flag">{{ t('sqlConstruction.dynamic') }}</span>
                </div>
                <pre v-if="step.contribution" class="step-sql">{{ step.contribution }}</pre>
                <div v-if="step.condition" class="step-condition">
                  {{ t('sqlConstruction.conditionLabel') }} <code>{{ step.condition }}</code>
                </div>
                <div v-if="step.attributes && Object.keys(step.attributes).length" class="step-attrs">
                  <span v-for="(value, key) in step.attributes" :key="key">
                    {{ key }}={{ formatValue(value) }}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </el-card>

        <el-card class="parameter-section">
          <div class="section-heading">
            <h2>{{ t('sqlConstruction.parameters') }}</h2>
            <span>{{ t('sqlConstruction.parameterCountLabel', { count: detail.parameters.length }) }}</span>
          </div>
          <el-empty v-if="detail.parameters.length === 0" :description="t('sqlConstruction.noParameters')" />
          <div v-else class="parameter-list">
            <button
              v-for="parameter in detail.parameters"
              :key="parameterKey(parameter)"
              class="parameter-item"
              :class="{ active: selectedParameterKey === parameterKey(parameter) }"
              @click="selectedParameterKey = parameterKey(parameter)"
            >
              <span class="param-name">{{ parameter.name }}</span>
              <el-tag :type="bindingTagType(parameter.bindingType)" size="small">
                {{ bindingText(parameter.bindingType) }}
              </el-tag>
              <span class="param-source">{{ parameter.source || 'UNKNOWN' }}</span>
            </button>
          </div>

          <div v-if="selectedParameter" class="parameter-detail">
            <div class="param-meta">
              <div>
                <span>{{ t('sqlConstruction.target') }}</span>
                <strong>{{ selectedParameter.target || '-' }}</strong>
              </div>
              <div>
                <span>{{ t('sqlConstruction.type') }}</span>
                <strong>{{ selectedParameter.type || '-' }}</strong>
              </div>
            </div>

            <div class="subsection-title">{{ t('sqlConstruction.possibleValues') }}</div>
            <div v-if="selectedParameter.possibleValues?.length" class="value-list">
              <div v-for="(value, index) in selectedParameter.possibleValues" :key="index" class="value-item">
                <strong>{{ formatValue(value.displayValue) }}</strong>
                <span>{{ formatValue(value.summary) }}</span>
              </div>
            </div>
            <el-empty v-else :description="t('sqlConstruction.noPossibleValues')" :image-size="70" />

            <div class="subsection-title trace-title-row">
              <span>{{ t('sqlConstruction.propagationPaths') }}</span>
              <el-button
                text
                type="primary"
                size="small"
                :icon="DataAnalysis"
                @click="showTracePanel"
              >
                {{ t('sqlConstruction.viewTrace') }}
              </el-button>
            </div>
            <div v-if="selectedParameter.propagationPaths?.length" class="path-list">
              <div v-for="(path, pathIndex) in selectedParameter.propagationPaths" :key="pathIndex" class="path-item">
                <div class="path-root">{{ t('sqlConstruction.rootValueLabel', { value: formatValue(path.rootValue) }) }}</div>
                <ol>
                  <li v-for="(step, stepIndex) in pathSteps(path)" :key="stepIndex">
                    {{ formatValue(step.summary || step.callSite || step.statement) }}
                  </li>
                </ol>
              </div>
            </div>
            <el-empty v-else :description="t('sqlConstruction.noFullPropagation')" :image-size="70" />
          </div>
        </el-card>
      </div>

      <el-card v-if="traceVisible" class="flow-section">
        <div class="section-heading">
          <h2>{{ t('sqlConstruction.propagationSection') }}</h2>
          <span v-if="!propagation && propagationLoading">{{ t('common.loading') }}</span>
          <span v-else-if="propagation && hasPropagationSteps">{{ t('sqlConstruction.propagationStats', { pathCount: propagationStats.pathCount, stepCount: propagationStats.stepCount }) }}</span>
          <el-button text size="small" :icon="Close" @click="closeTracePanel">
            {{ t('common.close') }}
          </el-button>
        </div>
        <el-empty v-if="propagation && !hasPropagationSteps" :description="t('sqlConstruction.noPropagation')" />
        <el-empty v-else-if="propagationLoadError" :description="t('sqlConstruction.propagationLoadError')" />
        <div v-show="hasPropagationSteps" ref="flowChartRef" class="flow-chart" :style="{ height: flowChartHeight }"></div>
      </el-card>

      <el-card class="rendered-section">
        <div class="section-heading">
          <h2>{{ t('sqlConstruction.renderedSql') }}</h2>
          <span>{{ t('sqlConstruction.sampleCount', { count: detail.renderedSqls.length }) }}</span>
        </div>
        <el-empty v-if="detail.renderedSqls.length === 0" :description="t('sqlConstruction.noRenderedSql')" />
        <el-collapse v-else>
          <el-collapse-item
            v-for="item in detail.renderedSqls"
            :key="item.index"
            :name="String(item.index)"
            :title="t('sqlConstruction.sampleTitle', { index: item.index + 1 })"
          >
            <pre class="sql-block">{{ item.sql }}</pre>
            <div v-if="item.parameters && Object.keys(item.parameters).length" class="render-params">
              <span v-for="(value, key) in item.parameters" :key="key">
                {{ key }}={{ formatValue(value) }}
              </span>
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-card>

      <el-card class="audit-section">
        <div class="section-heading">
          <h2>{{ t('sqlConstruction.auditIssues') }}</h2>
          <span>{{ t('sqlConstruction.issueCount', { count: visibleAuditResults.length }) }}</span>
        </div>
        <el-empty v-if="visibleAuditResults.length === 0" :description="t('sqlConstruction.noAuditIssues')" />
        <el-table v-else :data="visibleAuditResults" stripe>
          <el-table-column :label="t('sqlConstruction.level')" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="severityTagType(row.severity)" size="small">
                {{ severityText(row.severity) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('sqlConstruction.triggerRule')" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.ruleName || row.ruleId || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('sqlConstruction.issueMessage')" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ row.message || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('sqlConstruction.issueSql')" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">{{ row.sqlContent || fallbackWhitelistSql }}</template>
          </el-table-column>
          <el-table-column :label="t('sqlConstruction.suggestion')" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.suggestion || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('sqlConstruction.operation')" width="80" align="center" fixed="right">
            <template #default="{ row }">
              <div v-if="canIgnoreAuditResult(row)" class="action-icons-row">
                <el-tooltip :content="t('sqlConstruction.ignoreIssue')" placement="top">
                  <el-icon class="action-icon action-icon--danger" @click="handleIgnoreAuditResult(row)"><Delete /></el-icon>
                </el-tooltip>
              </div>
              <span v-else class="text-secondary">-</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card v-if="detail.diagnostics.length" class="diagnostic-section">
        <div class="section-heading">
          <h2>{{ t('sqlConstruction.diagnostics') }}</h2>
        </div>
        <ul>
          <li v-for="item in detail.diagnostics" :key="item">{{ item }}</li>
        </ul>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Close, DataAnalysis } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import {
  getSqlConstructionDetail,
  getSqlPropagationPaths,
  type SqlAuditResultVO,
  type SqlConstructionDetailVO,
  type SqlParameterVO,
  type SqlPropagationCallTraceStepVO,
  type SqlPropagationPathVO
} from '@/api/sqlStatement'
import { createSqlWhitelist } from '@/api/sqlWhitelist'

const route = useRoute()
const { t } = useI18n()

const loading = ref(false)
const detail = ref<SqlConstructionDetailVO | null>(null)
const selectedParameterKey = ref('')
const ignoredAuditKeys = ref<Set<string>>(new Set())

// 参数轨迹（来源于 propagation-paths 接口）；面板默认隐藏，由参数面板的"查看"按钮展开
const propagation = ref<SqlPropagationPathVO | null>(null)
const propagationLoading = ref(false)
const propagationLoadError = ref(false)
const traceVisible = ref(false)

const showTracePanel = () => {
  traceVisible.value = true
  // 数据未加载（或之前加载失败）时先请求，再等渲染完成初始化图表
  if (!propagation.value && !propagationLoading.value) {
    loadPropagationPaths()
  } else {
    // 面板由隐藏变可见后容器尺寸才有效，需等待渲染完成再重绘
    initFlowChart()
  }
}

/** 收起面板：v-if 会销毁图表容器 DOM，须同步 dispose echarts 实例，否则下次打开渲染空白 */
const closeTracePanel = () => {
  traceVisible.value = false
  flowChart?.dispose()
  flowChart = null
}
const flowChartRef = ref<HTMLElement>()
let flowChart: echarts.ECharts | null = null

const sqlId = computed(() => Number(route.params.id))

const sourceLocation = computed(() => {
  const definition = detail.value?.definition
  if (!definition?.sourceFile) {
    return '-'
  }
  return definition.sourceLine ? `${definition.sourceFile}:${definition.sourceLine}` : definition.sourceFile
})

const selectedParameter = computed(() => {
  return detail.value?.parameters.find(parameter => parameterKey(parameter) === selectedParameterKey.value) || null
})

const fallbackWhitelistSql = computed(() => {
  return detail.value?.renderedSqls?.find(item => item.sql)?.sql || detail.value?.definition.rawSql || ''
})

const visibleAuditResults = computed(() => {
  return (detail.value?.auditResults || []).filter(result => !ignoredAuditKeys.value.has(auditResultKey(result)))
})

// 参数轨迹统计：以 parameters[].paths[].steps 中的 step 为节点依据
const propagationSteps = computed(() => {
  const result: SqlPropagationCallTraceStepVO[] = []
  for (const parameter of propagation.value?.parameters || []) {
    for (const path of parameter.paths || []) {
      for (const step of path.steps || []) {
        result.push(step)
      }
    }
  }
  return result
})
const hasPropagationSteps = computed(() => propagationSteps.value.length > 0)
const propagationStats = computed(() => {
  const methods = new Set<string>()
  let pathCount = 0
  for (const step of propagationSteps.value) {
    if (step.caller) methods.add(step.caller)
    if (step.callee) methods.add(step.callee)
  }
  for (const parameter of propagation.value?.parameters || []) {
    for (const path of parameter.paths || []) {
      if (path.steps?.length) {
        pathCount += 1
      }
    }
  }
  return {
    stepCount: propagationSteps.value.length,
    methodCount: methods.size,
    pathCount
  }
})

const flowChartHeight = computed(() => {
  // DAG 布局按列排布，高度取决于最宽列的节点数（收敛在合理区间）
  const rows = Math.max(propagationStats.value.methodCount, propagationStats.value.pathCount, 3)
  return `${Math.min(860, Math.max(380, rows * 88 + 96))}px`
})

const loadPropagationPaths = async () => {
  if (!sqlId.value || Number.isNaN(sqlId.value)) {
    return
  }
  propagationLoading.value = true
  propagationLoadError.value = false
  try {
    propagation.value = await getSqlPropagationPaths(sqlId.value, detail.value?.versionId)
    initFlowChart()
  } catch {
    propagation.value = null
    propagationLoadError.value = true
  } finally {
    propagationLoading.value = false
  }
}

const loadDetail = async () => {
  if (!sqlId.value || Number.isNaN(sqlId.value)) {
    ElMessage.error(t('sqlConstruction.invalidSqlId'))
    return
  }
  loading.value = true
  try {
    detail.value = await getSqlConstructionDetail(sqlId.value)
    ignoredAuditKeys.value = new Set()
    selectedParameterKey.value = detail.value.parameters[0] ? parameterKey(detail.value.parameters[0]) : ''
    // 参数轨迹面板默认隐藏，数据延迟到用户点击"查看"时再请求
  } catch {
    detail.value = null
  } finally {
    loading.value = false
  }
}

const auditResultSql = (result: SqlAuditResultVO) => {
  return result.sqlContent || fallbackWhitelistSql.value
}

const auditResultKey = (result: SqlAuditResultVO) => {
  return `${result.ruleId || 0}:${auditResultSql(result)}`
}

const canIgnoreAuditResult = (result: SqlAuditResultVO) => {
  return !!detail.value?.projectId && !!result.ruleId && !!auditResultSql(result)
}

const handleIgnoreAuditResult = async (result: SqlAuditResultVO) => {
  if (!detail.value?.projectId || !result.ruleId || !auditResultSql(result)) {
    ElMessage.warning(t('sqlConstruction.ignoreUnavailable'))
    return
  }
  try {
    await ElMessageBox.confirm(
      t('sqlConstruction.ignoreConfirm'),
      t('sqlConstruction.ignoreTitle'),
      {
        type: 'warning',
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel')
      }
    )
    await createSqlWhitelist(detail.value.projectId, {
      sql: auditResultSql(result),
      ruleId: result.ruleId,
      reason: t('sqlConstruction.ignoreReason')
    })
    ignoredAuditKeys.value = new Set([...ignoredAuditKeys.value, auditResultKey(result)])
    ElMessage.success(t('sqlConstruction.ignoreSuccess'))
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(t('sqlConstruction.ignoreError'))
    }
  }
}

const severityTagType = (severity?: string) => {
  switch ((severity || '').toLowerCase()) {
    case 'critical': return 'danger'
    case 'high': return 'warning'
    case 'warning': return 'info'
    default: return 'info'
  }
}

const severityText = (severity?: string) => {
  switch ((severity || '').toLowerCase()) {
    case 'critical': return t('severity.critical')
    case 'high': return t('severity.high')
    case 'warning': return t('severity.warning')
    case 'info': return t('severity.info')
    default: return severity || '-'
  }
}

const parameterKey = (parameter: SqlParameterVO) => {
  return `${parameter.name || 'param'}-${parameter.index ?? 'x'}`
}

const bindingText = (bindingType?: string) => {
  switch (bindingType) {
    // #{} / ${} 前缀保留在代码中，避免放入词条触发 vue-i18n 插值语法
    case 'PARAMETER_BINDING': return '#{} ' + t('sqlConstruction.binding.parameter')
    case 'RAW_SUBSTITUTION': return '${} ' + t('sqlConstruction.binding.raw')
    case 'SQL_TEXT_REFERENCE': return t('sqlConstruction.binding.text')
    default: return t('sqlConstruction.binding.unknown')
  }
}

const bindingTagType = (bindingType?: string) => {
  if (bindingType === 'RAW_SUBSTITUTION') {
    return 'danger'
  }
  if (bindingType === 'PARAMETER_BINDING') {
    return 'success'
  }
  return 'info'
}

const formatValue = (value: unknown) => {
  if (value === undefined || value === null || value === '') {
    return '-'
  }
  if (typeof value === 'object') {
    return JSON.stringify(value)
  }
  return String(value)
}

const pathSteps = (path: Record<string, unknown>) => {
  const steps = Array.isArray(path.steps) ? path.steps : []
  const valueFlow = Array.isArray(path.valueFlow) ? path.valueFlow : []
  return steps.length > 0 ? steps as Array<Record<string, unknown>> : valueFlow as Array<Record<string, unknown>>
}

// DAG 调用图：节点 = 去重后的方法（根值入口 + 中间方法 + SQL 终点），边 = 调用关系（多路径合并）。
// 同一方法被多条路径复用时自然汇合/分叉，参数名作为边的悬浮明细展示。
const buildFlowOption = (data: SqlPropagationPathVO): echarts.EChartsOption => {
  type FlowNode = {
    id: string
    name: string
    method: string
    kind: 'root' | 'method' | 'sql'
    depth: number
    detail: string[]
    calls: number
    x: number
    y: number
    itemStyle: Record<string, unknown>
  }
  type FlowLink = {
    source: string
    target: string
    params: Set<string>
    detail: string[]
  }

  const NODE_W = 210
  const NODE_H = 62
  // 列间距加大到 150：为箭头留出卡片边缘之外的可见空隙
  const COL_GAP = 150
  const ROW_GAP = 26

  const nodeMap = new Map<string, FlowNode>()
  const linkMap = new Map<string, FlowLink>()
  // 每列（depth）内已占行数，用于纵向错开
  const columnRows = new Map<number, number>()

  const ensureNode = (
    id: string,
    method: string,
    kind: FlowNode['kind'],
    depth: number,
    detail: string[]
  ): FlowNode => {
    const existing = nodeMap.get(id)
    if (existing) {
      // 深度取更浅的（离根更近的层次），明细合并去重
      existing.depth = Math.min(existing.depth, depth)
      detail.forEach(line => {
        if (!existing.detail.includes(line)) {
          existing.detail.push(line)
        }
      })
      return existing
    }
    const row = columnRows.get(depth) || 0
    columnRows.set(depth, row + 1)
    const node: FlowNode = {
      id,
      name: method,
      method,
      kind,
      depth,
      detail: [...detail],
      calls: 0,
      x: depth * (NODE_W + COL_GAP) + NODE_W / 2 + 24,
      y: row * (NODE_H + ROW_GAP) + NODE_H / 2 + 24,
      itemStyle: {}
    }
    nodeMap.set(id, node)
    return node
  }

  const ensureLink = (source: string, target: string, param: string, detail: string[]) => {
    const key = `${source}->${target}`
    const existing = linkMap.get(key)
    if (existing) {
      existing.params.add(param)
      detail.forEach(line => {
        if (!existing.detail.includes(line)) {
          existing.detail.push(line)
        }
      })
      return
    }
    linkMap.set(key, { source, target, params: new Set([param]), detail: [...detail] })
    const targetNode = nodeMap.get(target)
    if (targetNode) {
      targetNode.calls += 1
    }
  }

  const stepDetailLines = (step: Record<string, unknown>): string[] => {
    return [
      step.callSite ? t('sqlConstruction.flow.callSite', { value: String(step.callSite) }) : '',
      step.argumentIndex != null ? t('sqlConstruction.flow.argumentIndex', { value: String(step.argumentIndex) }) : '',
      step.argument ? t('sqlConstruction.flow.argument', { value: String(step.argument) }) : '',
      step.summary ? String(step.summary) : ''
    ].filter(Boolean)
  }

  ;(data.parameters || []).forEach((parameter, paramIndex) => {
    const paramLabel = parameter.name || (parameter.index != null ? `param${parameter.index}` : t('sqlConstruction.flow.parameterFallback', { index: paramIndex + 1 }))
    ;(parameter.paths || []).forEach(path => {
      const steps = pathSteps(path as unknown as Record<string, unknown>)
      if (steps.length === 0) {
        return
      }
      const rootLabel = `'${truncate(formatValue(path.rootValue), 12)}'`
      const rootId = `root:${paramLabel}:${rootLabel}`
      ensureNode(rootId, rootLabel, 'root', 0, [
        t('sqlConstruction.flow.parameter', { name: paramLabel }),
        t('sqlConstruction.flow.rootValue', { value: formatValue(path.rootValue) })
      ])

      let previousId = rootId
      // steps 为"调用点 → 入口"的反序（追踪算法的展开顺序），
      // 倒序消费使图中链路按 入口 → ... → SQL调用点 的值流向展示
      const orderedSteps = [...steps].reverse()
      orderedSteps.forEach((step, stepIndex) => {
        const callerName = shortMethod(step.caller as string | undefined) || t('sqlConstruction.flow.caller')
        const calleeName = shortMethod(step.callee as string | undefined) || t('sqlConstruction.flow.callee')
        const isLast = stepIndex === orderedSteps.length - 1
        const calleeId = `method:${callerName}->${calleeName}`
        // 悬浮明细保留完整签名（含参数包名），节点显示名由 stripParamPackages 单独缩短
        ensureNode(calleeId, calleeName, isLast ? 'sql' : 'method', stepIndex + 1, [
          `callee: ${calleeName}`,
          ...stepDetailLines(step as unknown as Record<string, unknown>)
        ])
        ensureLink(previousId, calleeId, paramLabel, stepDetailLines(step as unknown as Record<string, unknown>))
        previousId = calleeId
      })
    })
  })

  const nodes = [...nodeMap.values()].map(node => ({
    id: node.id,
    name: stripParamPackages(node.name),
    method: node.method,
    kind: node.kind,
    detail: node.detail,
    calls: node.calls,
    itemStyle: dagNodeStyle(node.kind, node.calls),
    symbolSize: node.kind === 'root' ? [92, 40] : [NODE_W, NODE_H],
    x: node.x,
    y: node.y
  }))

  const links = [...linkMap.values()].map(link => ({
    source: link.source,
    target: link.target,
    params: [...link.params].join(', '),
    detail: link.detail,
    lineStyle: {
      color: '#64748b',
      width: 1.6,
      curveness: 0.08,
      opacity: 0.8
    }
  }))

  return {
    tooltip: {
      trigger: 'item',
      formatter: (param: any) => {
        const data = param?.data || {}
        if (param.dataType === 'edge') {
          const lines = [`<strong>${t('sqlConstruction.flow.edgeTitle')}</strong>`]
          if (data.params) lines.push(t('sqlConstruction.flow.parameter', { name: data.params }))
          if (data.detail) lines.push(`<span style="color:#64748b">${(data.detail as string[]).join('<br/>')}</span>`)
          return lines.join('<br/>')
        }
        const lines = [`<strong>${data.name || ''}</strong>`]
        if (data.kind === 'root') {
          lines.push(String(data.detail?.[0] || ''))
        } else if (data.calls > 1) {
          lines.push(t('sqlConstruction.flow.callCount', { count: data.calls }))
        }
        if (data.detail) lines.push(`<span style="color:#64748b">${(data.detail as string[]).join('<br/>')}</span>`)
        return lines.join('<br/>')
      }
    },
    series: [
      {
        type: 'graph',
        layout: 'none',
        roam: true,
        draggable: true,
        symbol: 'roundRect',
        // 不用箭头：链路默认从左到右为执行顺序，连线仅表示相邻关系
        edgeSymbol: ['none', 'none'],
        label: {
          show: true,
          position: 'inside',
          overflow: 'truncate',
          formatter: (params: any) => truncate(params.data?.name || '', 26),
          color: '#1e293b',
          fontSize: 12,
          fontWeight: 700
        },
        emphasis: {
          focus: 'adjacency',
          lineStyle: { width: 2.4, color: '#2563eb', opacity: 1 }
        },
        data: nodes,
        links
      } as any
    ]
  }
}

const dagNodeStyle = (kind: 'root' | 'method' | 'sql', calls: number) => {
  if (kind === 'root') {
    return {
      color: '#ecfdf5',
      borderColor: '#10b981',
      borderWidth: 2,
      shadowColor: 'rgba(16, 185, 129, 0.18)',
      shadowBlur: 10,
      shadowOffsetY: 3
    }
  }
  if (kind === 'sql') {
    return {
      color: '#eef2ff',
      borderColor: '#6366f1',
      borderWidth: 2,
      shadowColor: 'rgba(99, 102, 241, 0.16)',
      shadowBlur: 10,
      shadowOffsetY: 3
    }
  }
  // 中间方法：被多个调用引用时描边加粗提示热点
  const hotspot = calls > 1
  return {
    color: '#f8fafc',
    borderColor: hotspot ? '#2563eb' : '#93c5fd',
    borderWidth: hotspot ? 2 : 1.6,
    shadowColor: 'rgba(37, 99, 235, 0.12)',
    shadowBlur: hotspot ? 12 : 8,
    shadowOffsetY: 3
  }
}

/**
 * 节点显示名的方法参数只保留类名：
 * selectById(java.lang.Long) → selectById(Long)。完整签名仍在悬浮明细中。
 */
const stripParamPackages = (name: string) => {
  return String(name).replace(/\(([^)]*)\)/, (_match, args: string) => {
    if (!args.trim()) {
      return '()'
    }
    const shortArgs = args.split(',').map(arg => arg.trim().split('.').pop()).join(', ')
    return `(${shortArgs})`
  })
}

const shortMethod = (signature?: string) => {
  if (!signature) return ''
  const trimmed = signature.trim()
  // 取尖括号内最后一个 token，兼容 SootUp 的 <pkg.Class: R method(args)> 签名
  const match = /<([^>]+)>/.exec(trimmed)
  const body = match ? match[1] : trimmed
  return body.split(/[,:]/).map(s => s.trim()).filter(Boolean).pop() || body
}

const truncate = (text: unknown, max: number) => {
  const str = text == null ? '' : String(text)
  return str.length > max ? str.slice(0, max) + '…' : str
}

const updateFlowChart = () => {
  if (!flowChart || !propagation.value || !hasPropagationSteps.value) return
  flowChart.resize()
  flowChart.setOption(buildFlowOption(propagation.value), true)
}

const initFlowChart = () => {
  nextTick(() => {
    if (!flowChartRef.value) return
    // v-if 卸载会销毁容器 DOM，旧实例握着失效 DOM 无法复用，须重建
    if (flowChart && flowChart.getHeight() === 0) {
      flowChart.dispose()
      flowChart = null
    }
    if (!flowChart) {
      flowChart = echarts.init(flowChartRef.value)
    }
    updateFlowChart()
  })
}

const handleResize = () => {
  flowChart?.resize()
}

watch(() => route.params.id, loadDetail)

onMounted(() => {
  loadDetail()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  flowChart?.dispose()
  flowChart = null
})
</script>

<style lang="scss" scoped>
.sql-construction-page {
  color: var(--jsp-text-primary);
}

.detail-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

// 摘要带：指标卡行（与总览页统计卡同语义的紧凑版）
.summary-band {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}

.summary-item {
  background: var(--jsp-bg-card);
  border: 1px solid var(--jsp-border-color);
  border-radius: 8px;
  padding: 12px 16px;

  span {
    display: block;
    color: var(--jsp-text-secondary);
    font-size: 12px;
    margin-bottom: 6px;
  }

  strong {
    display: block;
    font-size: 17px;
    word-break: break-word;
  }
}

// 各内容区块统一走 el-card，仅需覆盖内边距节奏
.definition-section,
.timeline-section,
.parameter-section,
.flow-section,
.rendered-section,
.audit-section,
.diagnostic-section {
  :deep(.el-card__body) {
    padding: 18px 20px;
  }
}

.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;

  h2 {
    margin: 0;
    font-size: 16px;
  }

  span {
    color: var(--jsp-text-secondary);
    font-size: 13px;
  }
}

.definition-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 16px;
  margin-bottom: 14px;

  span {
    display: block;
    color: var(--jsp-text-secondary);
    font-size: 12px;
    margin-bottom: 4px;
  }

  strong {
    display: block;
    font-size: 13px;
    font-weight: 600;
    word-break: break-all;
  }
}

// SQL 代码块：浅底深字，深色模式经 --jsp 变量自动适配
.sql-block,
.step-sql {
  margin: 0;
  padding: 12px;
  background: var(--jsp-bg-page);
  border: 1px solid var(--jsp-border-light);
  color: var(--jsp-text-primary);
  border-radius: 6px;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.6;
  font-family: 'Monaco', 'Menlo', monospace;
}

.workbench {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(360px, 0.9fr);
  gap: 16px;
  // 两面板等高：以较矮面板为基准（grid 默认拉伸），内容超出者内部滚动
  align-items: stretch;
}

.workbench > .el-card {
  display: flex;
  flex-direction: column;
  min-height: 0;

  :deep(.el-card__body) {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
  }
}

.flow-chart {
  width: 100%;
  height: 320px;
}

// 构造步骤时间线：rail 列提供纵向连接线
.construction-timeline {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.timeline-row {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  gap: 10px;
  margin-left: calc(var(--depth) * 18px);
}

.timeline-rail {
  display: flex;
  flex-direction: column;
  align-items: center;

  // 步骤圆点下方的连接线，最后一行无线
  &::after {
    content: '';
    flex: 1;
    width: 2px;
    margin-top: 4px;
    background: var(--jsp-border-color);
  }
}

.timeline-row:last-child .timeline-rail::after {
  display: none;
}

.timeline-marker {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: $primary-light;
  color: $primary-hover;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  flex-shrink: 0;
}

.timeline-content {
  background: var(--jsp-bg-page);
  border-radius: 8px;
  padding: 12px;
  min-width: 0;
  padding-bottom: 12px;
}

.step-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;

  > div {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
  }
}

.dynamic-flag {
  color: $warning-color;
  font-size: 12px;
}

.step-condition {
  margin-top: 8px;
  color: $text-secondary-dark;
  font-size: 13px;

  code {
    color: var(--jsp-text-primary);
  }
}

.step-attrs,
.render-params {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;

  span {
    background: var(--jsp-bg-card);
    border: 1px solid var(--jsp-border-light);
    border-radius: 6px;
    color: $text-secondary-dark;
    font-size: 12px;
    padding: 4px 8px;
    word-break: break-all;
  }
}

.parameter-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.parameter-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  border: 1px solid var(--jsp-border-light);
  border-radius: 8px;
  background: var(--jsp-bg-page);
  color: inherit;
  cursor: pointer;
  padding: 10px 12px;
  text-align: left;
  transition: border-color 0.15s ease, background-color 0.15s ease;

  &:hover {
    border-color: $primary-color;
  }

  &.active {
    border-color: $primary-color;
    background: $primary-lighter;
  }
}

.param-name {
  font-weight: 700;
  min-width: 0;
  word-break: break-word;
}

.param-source {
  grid-column: 1 / -1;
  color: var(--jsp-text-secondary);
  font-size: 12px;
  word-break: break-word;
}

.parameter-detail {
  margin-top: 16px;
  border-top: 1px solid var(--jsp-border-light);
  padding-top: 16px;
}

.param-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;

  span {
    display: block;
    color: var(--jsp-text-secondary);
    font-size: 12px;
    margin-bottom: 4px;
  }

  strong {
    display: block;
    word-break: break-word;
    font-size: 13px;
  }
}

.subsection-title {
  margin: 16px 0 8px;
  font-size: 14px;
  font-weight: 700;
}

// "参数轨迹"小节标题 + 查看按钮同行
.trace-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.value-list,
.path-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.value-item,
.path-item {
  background: var(--jsp-bg-page);
  border: 1px solid var(--jsp-border-light);
  border-radius: 8px;
  padding: 10px;

  strong,
  span {
    display: block;
    word-break: break-word;
  }

  span {
    color: var(--jsp-text-secondary);
    font-size: 12px;
    margin-top: 4px;
  }
}

.path-root {
  color: $text-secondary-dark;
  font-size: 13px;
  margin-bottom: 8px;
  word-break: break-word;
}

.path-item ol {
  padding-left: 20px;
  margin: 0;

  li {
    margin-bottom: 6px;
    word-break: break-word;
    font-size: 13px;
  }
}

.diagnostic-section ul {
  margin: 0;
  padding-left: 20px;

  li {
    margin-bottom: 6px;
    color: $text-secondary-dark;
  }
}

.text-secondary {
  color: $text-placeholder;
}

@media (max-width: 1100px) {
  .summary-band,
  .workbench,
  .definition-grid {
    grid-template-columns: 1fr;
  }

  .timeline-row {
    margin-left: 0;
  }
}
</style>
