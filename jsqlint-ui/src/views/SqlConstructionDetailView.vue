<template>
  <div class="sql-construction-page">
    <el-skeleton v-if="loading && !detail" :rows="8" animated />
    <el-empty v-else-if="!detail" description="未找到 SQL 构造详情" />
    <div v-else class="detail-body">
      <section class="summary-band">
        <div class="summary-item">
          <span>SQL 类型</span>
          <strong>{{ detail.definition.statementType || '-' }}</strong>
        </div>
        <div class="summary-item">
          <span>构造方式</span>
          <strong>{{ detail.definition.constructionType || '-' }}</strong>
        </div>
        <div class="summary-item">
          <span>来源</span>
          <strong>{{ detail.definition.sourceType || '-' }}</strong>
        </div>
        <div class="summary-item">
          <span>参数数量</span>
          <strong>{{ detail.definition.parameterCount ?? 0 }}</strong>
        </div>
        <div class="summary-item">
          <span>动态 SQL</span>
          <strong>{{ detail.definition.dynamic ? '是' : '否' }}</strong>
        </div>
      </section>

      <section class="definition-section">
        <div class="section-heading">
          <h2>定义位置</h2>
        </div>
        <div class="definition-grid">
          <div>
            <span>Mapper 类</span>
            <strong>{{ detail.definition.mapperClass || '-' }}</strong>
          </div>
          <div>
            <span>方法</span>
            <strong>{{ detail.definition.mapperMethod || '-' }}</strong>
          </div>
          <div>
            <span>文件</span>
            <strong>{{ sourceLocation }}</strong>
          </div>
          <div>
            <span>Hash</span>
            <strong>{{ detail.definition.sqlHash || '-' }}</strong>
          </div>
        </div>
        <pre class="sql-block">{{ detail.definition.rawSql || '暂无原始 SQL' }}</pre>
      </section>

      <div class="workbench">
        <section class="timeline-section">
          <div class="section-heading">
            <h2>构造过程</h2>
            <span>{{ detail.constructionSteps.length }} 个节点</span>
          </div>
          <el-empty v-if="detail.constructionSteps.length === 0" description="暂无 SqlNode 构造信息" />
          <div v-else class="construction-timeline">
            <div
              v-for="step in detail.constructionSteps"
              :key="step.id"
              class="timeline-row"
              :style="{ '--depth': step.depth }"
            >
              <div class="timeline-marker">{{ step.order }}</div>
              <div class="timeline-content">
                <div class="step-header">
                  <div>
                    <el-tag size="small" effect="plain">{{ step.nodeType || 'NODE' }}</el-tag>
                    <strong>{{ step.title }}</strong>
                  </div>
                  <span v-if="step.dynamic" class="dynamic-flag">动态</span>
                </div>
                <pre v-if="step.contribution" class="step-sql">{{ step.contribution }}</pre>
                <div v-if="step.condition" class="step-condition">
                  条件: <code>{{ step.condition }}</code>
                </div>
                <div v-if="step.attributes && Object.keys(step.attributes).length" class="step-attrs">
                  <span v-for="(value, key) in step.attributes" :key="key">
                    {{ key }}={{ formatValue(value) }}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </section>

        <section class="parameter-section">
          <div class="section-heading">
            <h2>关联参数</h2>
            <span>{{ detail.parameters.length }} 个参数</span>
          </div>
          <el-empty v-if="detail.parameters.length === 0" description="暂无参数推断信息" />
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
                <span>目标</span>
                <strong>{{ selectedParameter.target || '-' }}</strong>
              </div>
              <div>
                <span>类型</span>
                <strong>{{ selectedParameter.type || '-' }}</strong>
              </div>
            </div>

            <div class="subsection-title">可能值</div>
            <div v-if="selectedParameter.possibleValues?.length" class="value-list">
              <div v-for="(value, index) in selectedParameter.possibleValues" :key="index" class="value-item">
                <strong>{{ formatValue(value.displayValue) }}</strong>
                <span>{{ formatValue(value.summary) }}</span>
              </div>
            </div>
            <el-empty v-else description="暂无可能值" :image-size="70" />

            <div class="subsection-title">传播路径</div>
            <div v-if="selectedParameter.propagationPaths?.length" class="path-list">
              <div v-for="(path, pathIndex) in selectedParameter.propagationPaths" :key="pathIndex" class="path-item">
                <div class="path-root">根值: {{ formatValue(path.rootValue) }}</div>
                <ol>
                  <li v-for="(step, stepIndex) in pathSteps(path)" :key="stepIndex">
                    {{ formatValue(step.summary || step.callSite || step.statement) }}
                  </li>
                </ol>
              </div>
            </div>
            <el-empty v-else description="当前记录未保存完整传播路径" :image-size="70" />
          </div>
        </section>
      </div>

      <section class="flow-section">
        <div class="section-heading">
          <h2>参数传播路径</h2>
          <span v-if="!propagation && propagationLoading">加载中...</span>
          <span v-else-if="propagation && hasPropagationSteps">{{ propagationStats.pathCount }} 条路径 / {{ propagationStats.stepCount }} 个节点</span>
        </div>
        <el-empty v-if="propagation && !hasPropagationSteps" description="暂无参数传播路径" />
        <el-empty v-else-if="propagationLoadError" description="参数传播路径加载失败" />
        <div v-show="hasPropagationSteps" ref="flowChartRef" class="flow-chart" :style="{ height: flowChartHeight }"></div>
      </section>

      <section class="rendered-section">
        <div class="section-heading">
          <h2>渲染 SQL</h2>
          <span>{{ detail.renderedSqls.length }} 条样例</span>
        </div>
        <el-empty v-if="detail.renderedSqls.length === 0" description="暂无渲染 SQL 样例" />
        <el-collapse v-else>
          <el-collapse-item
            v-for="item in detail.renderedSqls"
            :key="item.index"
            :name="String(item.index)"
            :title="`样例 ${item.index + 1}`"
          >
            <pre class="sql-block">{{ item.sql }}</pre>
            <div v-if="item.parameters && Object.keys(item.parameters).length" class="render-params">
              <span v-for="(value, key) in item.parameters" :key="key">
                {{ key }}={{ formatValue(value) }}
              </span>
            </div>
          </el-collapse-item>
        </el-collapse>
      </section>

      <section class="audit-section">
        <div class="section-heading">
          <h2>审核问题</h2>
          <span>{{ visibleAuditResults.length }} 条问题</span>
        </div>
        <el-empty v-if="visibleAuditResults.length === 0" description="暂无审核问题" />
        <el-table v-else :data="visibleAuditResults" size="small" stripe>
          <el-table-column label="级别" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="severityTagType(row.severity)" size="small">
                {{ severityText(row.severity) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="触发规则" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.ruleName || row.ruleId || '-' }}</template>
          </el-table-column>
          <el-table-column label="问题信息" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ row.message || '-' }}</template>
          </el-table-column>
          <el-table-column label="问题 SQL" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">{{ row.sqlContent || fallbackWhitelistSql }}</template>
          </el-table-column>
          <el-table-column label="修改建议" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.suggestion || '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="130" align="center" fixed="right">
            <template #default="{ row }">
              <span
                v-if="canIgnoreAuditResult(row)"
                class="action-btn action-btn--danger"
                @click="handleIgnoreAuditResult(row)"
              >
                忽略此问题
              </span>
              <span v-else class="text-secondary">-</span>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section v-if="detail.diagnostics.length" class="diagnostic-section">
        <div class="section-heading">
          <h2>诊断信息</h2>
        </div>
        <ul>
          <li v-for="item in detail.diagnostics" :key="item">{{ item }}</li>
        </ul>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
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

const loading = ref(false)
const detail = ref<SqlConstructionDetailVO | null>(null)
const selectedParameterKey = ref('')
const ignoredAuditKeys = ref<Set<string>>(new Set())

// 参数传播路径（来源于 propagation-paths 接口）
const propagation = ref<SqlPropagationPathVO | null>(null)
const propagationLoading = ref(false)
const propagationLoadError = ref(false)
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

// 传播路径统计：以 parameters[].paths[].steps 中的 step 为节点依据
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
  const rows = propagationStats.value.pathCount || 1
  return `${Math.min(760, Math.max(360, rows * 132 + 96))}px`
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
    ElMessage.error('SQL 记录 ID 不合法')
    return
  }
  loading.value = true
  try {
    detail.value = await getSqlConstructionDetail(sqlId.value)
    ignoredAuditKeys.value = new Set()
    selectedParameterKey.value = detail.value.parameters[0] ? parameterKey(detail.value.parameters[0]) : ''
    loadPropagationPaths()
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
    ElMessage.warning('该问题缺少 SQL 或规则信息，无法加入白名单')
    return
  }
  try {
    await ElMessageBox.confirm(
      '确认将此类 SQL 问题加入白名单吗？加入后，后续审核中相同 SQL 指纹和规则命中的问题将被过滤。',
      '加入白名单',
      {
        type: 'warning',
        confirmButtonText: '确认',
        cancelButtonText: '取消'
      }
    )
    await createSqlWhitelist(detail.value.projectId, {
      sql: auditResultSql(result),
      ruleId: result.ruleId,
      reason: '从 SQL 构造详情页加入白名单'
    })
    ignoredAuditKeys.value = new Set([...ignoredAuditKeys.value, auditResultKey(result)])
    ElMessage.success('已加入白名单')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error('加入白名单失败')
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
    case 'critical': return '致命'
    case 'high': return '高危'
    case 'warning': return '警告'
    case 'info': return '信息'
    default: return severity || '-'
  }
}

const parameterKey = (parameter: SqlParameterVO) => {
  return `${parameter.name || 'param'}-${parameter.index ?? 'x'}`
}

const bindingText = (bindingType?: string) => {
  switch (bindingType) {
    case 'PARAMETER_BINDING': return '#{} 绑定'
    case 'RAW_SUBSTITUTION': return '${} 拼接'
    case 'SQL_TEXT_REFERENCE': return '文本引用'
    default: return '未知'
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

// 流程图只使用 parameters[].paths[].steps。每个 step 是一个节点，节点内展示 caller -> callee。
const buildFlowOption = (data: SqlPropagationPathVO): echarts.EChartsOption => {
  type FlowNode = {
    id: string
    name: string
    caller?: string
    callee?: string
    parameter?: string
    rootValue?: string
    stepNumber: number
    detail?: string
    itemStyle?: Record<string, unknown>
    x: number
    y: number
  }
  type FlowLink = { source: string; target: string }

  const nodes: FlowNode[] = []
  const links: FlowLink[] = []
  let rowIndex = 0

  const pushLink = (source: string, target: string) => {
    if (source && target && !links.some(l => l.source === source && l.target === target)) {
      links.push({ source, target })
    }
  }

  ;(data.parameters || []).forEach((parameter, paramIndex) => {
    const paramLabel = parameter.name || (parameter.index != null ? `param${parameter.index}` : `参数${paramIndex + 1}`)
    ;(parameter.paths || []).forEach((path, pathIndex) => {
      const steps = path.steps || []
      if (steps.length === 0) {
        return
      }

      const y = rowIndex * 132 + 84
      let previousNodeId = ''
      steps.forEach((step, stepIndex) => {
        const callerName = shortMethod(step.caller) || '调用者'
        const calleeName = shortMethod(step.callee) || '被调方法'
        const nodeId = `param-${paramIndex}-path-${pathIndex}-step-${stepIndex}`
        const isFirst = stepIndex === 0
        const isLast = stepIndex === steps.length - 1

        nodes.push({
          id: nodeId,
          name: `${callerName} -> ${calleeName}`,
          caller: callerName,
          callee: calleeName,
          parameter: paramLabel,
          rootValue: path.rootValue,
          stepNumber: stepIndex + 1,
          detail: [
            step.callSite ? `调用点: ${step.callSite}` : '',
            step.argumentIndex != null ? `实参下标: ${step.argumentIndex}` : '',
            step.argument ? `实参: ${step.argument}` : '',
            step.summary
          ].filter(Boolean).join('\n'),
          itemStyle: flowStepNodeStyle(isFirst, isLast),
          x: stepIndex * 292 + 150,
          y
        })
        pushLink(previousNodeId, nodeId)
        previousNodeId = nodeId
      })
      rowIndex += 1
    })
  })

  return {
    tooltip: {
      trigger: 'item',
      formatter: (param: any) => {
        const data = param?.data || {}
        const lines = [`<strong>${data.name || ''}</strong>`]
        if (data.parameter) lines.push(`参数: ${data.parameter}`)
        if (data.rootValue) lines.push(`根值: ${data.rootValue}`)
        if (data.caller) lines.push(`caller: ${data.caller}`)
        if (data.callee) lines.push(`callee: ${data.callee}`)
        if (data.detail) lines.push(`<span style="color:#64748b">${String(data.detail).replace(/\n/g, '<br/>')}</span>`)
        return lines.join('<br/>')
      }
    },
    // graph 系列 TS 类型未收纳部分运行时字段，整体按 any 传入。
    series: [
      {
        type: 'graph',
        layout: 'none',
        roam: true,
        draggable: true,
        symbol: 'roundRect',
        symbolSize: [246, 84],
        edgeSymbol: ['none', 'arrow'],
        edgeSymbolSize: [0, 10],
        label: {
          show: true,
          position: 'inside',
          lineHeight: 18,
          formatter: (params: any) => {
            const node = params.data || {}
            return [
              `{step|STEP ${node.stepNumber || ''}}`,
              `{caller|${truncate(node.caller || '调用者', 30)}}`
            ].join('\n')
          },
          rich: {
            step: {
              color: '#64748b',
              fontSize: 10,
              fontWeight: 700,
              lineHeight: 14
            },
            caller: {
              color: '#0f172a',
              fontSize: 12,
              fontWeight: 700,
              lineHeight: 18
            }
          }
        },
        lineStyle: {
          color: '#64748b',
          width: 1.4,
          curveness: 0.04,
          opacity: 0.75
        },
        emphasis: {
          focus: 'adjacency',
          lineStyle: { width: 2.4, color: '#2563eb', opacity: 1 },
          label: { color: '#1e293b' }
        },
        data: nodes.map(node => ({
          id: node.id,
          name: node.name,
          caller: node.caller,
          callee: node.callee,
          parameter: node.parameter,
          rootValue: node.rootValue,
          stepNumber: node.stepNumber,
          detail: node.detail,
          itemStyle: node.itemStyle,
          x: node.x,
          y: node.y
        })),
        links
      } as any
    ]
  }
}

const flowStepNodeStyle = (isFirst: boolean, isLast: boolean) => {
  if (isFirst) {
    return {
      color: '#ecfdf5',
      borderColor: '#10b981',
      borderWidth: 2,
      shadowColor: 'rgba(16, 185, 129, 0.18)',
      shadowBlur: 12,
      shadowOffsetY: 4
    }
  }
  if (isLast) {
    return {
      color: '#eef2ff',
      borderColor: '#6366f1',
      borderWidth: 2,
      shadowColor: 'rgba(99, 102, 241, 0.16)',
      shadowBlur: 12,
      shadowOffsetY: 4
    }
  }
  return {
    color: '#f8fafc',
    borderColor: '#93c5fd',
    borderWidth: 1.6,
    shadowColor: 'rgba(37, 99, 235, 0.12)',
    shadowBlur: 10,
    shadowOffsetY: 3
  }
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
  padding: 0;
  color: #1e293b;
}

.detail-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.summary-band,
.definition-section,
.timeline-section,
.parameter-section,
.flow-section,
.rendered-section,
.audit-section,
.diagnostic-section {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}

.summary-band {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
}

.summary-item {
  padding: 16px;
  border-right: 1px solid #e2e8f0;

  &:last-child {
    border-right: 0;
  }

  span {
    display: block;
    color: #64748b;
    font-size: 12px;
    margin-bottom: 8px;
  }

  strong {
    display: block;
    font-size: 18px;
    word-break: break-word;
  }
}

.definition-section,
.timeline-section,
.parameter-section,
.flow-section,
.rendered-section,
.audit-section,
.diagnostic-section {
  padding: 18px;
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
    color: #64748b;
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
    color: #64748b;
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

.sql-block,
.step-sql {
  margin: 0;
  padding: 12px;
  background: #0f172a;
  color: #e2e8f0;
  border-radius: 6px;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.6;
}

.workbench {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(360px, 0.9fr);
  gap: 16px;
  align-items: start;
}

.flow-chart {
  width: 100%;
  height: 320px;
}

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

.timeline-marker {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #dbeafe;
  color: #2563eb;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
}

.timeline-content {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 12px;
  min-width: 0;
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
  color: #d97706;
  font-size: 12px;
}

.step-condition {
  margin-top: 8px;
  color: #475569;
  font-size: 13px;

  code {
    color: #1e293b;
  }
}

.step-attrs,
.render-params {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;

  span {
    background: #f1f5f9;
    border-radius: 6px;
    color: #475569;
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
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
  color: inherit;
  cursor: pointer;
  padding: 10px 12px;
  text-align: left;

  &.active {
    border-color: #3b82f6;
    background: #eff6ff;
  }
}

.param-name {
  font-weight: 700;
  min-width: 0;
  word-break: break-word;
}

.param-source {
  grid-column: 1 / -1;
  color: #64748b;
  font-size: 12px;
  word-break: break-word;
}

.parameter-detail {
  margin-top: 16px;
  border-top: 1px solid #e2e8f0;
  padding-top: 16px;
}

.param-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;

  span {
    display: block;
    color: #64748b;
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

.value-list,
.path-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.value-item,
.path-item {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 10px;

  strong,
  span {
    display: block;
    word-break: break-word;
  }

  span {
    color: #64748b;
    font-size: 12px;
    margin-top: 4px;
  }
}

.path-root {
  color: #475569;
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
    color: #475569;
  }
}

.action-btn {
  cursor: pointer;
  color: #2563eb;
  white-space: nowrap;

  &:hover {
    color: #1d4ed8;
  }

  &.action-btn--danger {
    color: #dc2626;

    &:hover {
      color: #b91c1c;
    }
  }
}

.text-secondary {
  color: #94a3b8;
}

@media (max-width: 1100px) {
  .summary-band,
  .workbench,
  .definition-grid {
    grid-template-columns: 1fr;
  }

  .summary-item {
    border-right: 0;
    border-bottom: 1px solid #e2e8f0;

    &:last-child {
      border-bottom: 0;
    }
  }

  .timeline-row {
    margin-left: 0;
  }
}
</style>
