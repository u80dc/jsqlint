import request from './request'

export interface SqlStatementVO {
  id: number
  projectId: number
  versionId: number
  statementId: string
  statementType?: string
  sqlHash?: string
  rawSql?: string
  sourceType?: string
  sourceFile?: string
  sourceLine?: number
  mapperClass?: string
  mapperMethod?: string
  sqlNodeStructure?: Record<string, unknown>
  parameterInfo?: Record<string, unknown>
  metadata?: Record<string, unknown>
  isDynamic?: boolean
  paramCount?: number
  createTime?: string
}

export interface SqlDefinitionVO {
  statementId: string
  statementType: string
  sqlHash: string
  rawSql?: string
  sourceType?: string
  constructionType?: string
  sourceFile?: string
  sourceLine?: number
  mapperClass?: string
  mapperMethod?: string
  dynamic?: boolean
  parameterCount?: number
}

export interface ConstructionStepVO {
  id: string
  order: number
  depth: number
  nodeType: string
  title: string
  contribution?: string
  condition?: string
  dynamic?: boolean
  attributes?: Record<string, unknown>
}

export interface SqlParameterVO {
  name: string
  index?: number
  type?: string
  target?: string
  bindingType?: string
  source?: string
  possibleValues?: Array<Record<string, unknown>>
  propagationPaths?: Array<Record<string, unknown>>
  diagnostics?: string[]
}

export interface RenderedSqlVO {
  index: number
  sql: string
  parameters?: Record<string, unknown>
  diagnostics?: string[]
}

export interface SqlAuditResultVO {
  id: number
  ruleId?: number
  ruleName?: string
  severity?: string
  message?: string
  sqlContent?: string
  suggestion?: string
  filePath?: string
  lineNumber?: number
}

export interface SqlPropagationCallTraceStepVO {
  caller?: string
  callee?: string
  callSite?: string
  argumentIndex?: number
  argument?: string
  summary?: string
}

export interface SqlPropagationValueFlowStepVO {
  method?: string
  statementIndex?: number
  statement?: string
  kind?: string
  subject?: string
  value?: string
  summary?: string
}

export interface SqlPropagationPathItemVO {
  rootValue?: string
  steps?: SqlPropagationCallTraceStepVO[]
  valueFlow?: SqlPropagationValueFlowStepVO[]
}

export interface SqlParameterPropagationVO {
  name?: string
  index?: number
  type?: string
  bindingType?: string
  paths?: SqlPropagationPathItemVO[]
}

export interface SqlPropagationPathVO {
  id: number
  projectId: number
  versionId: number
  statementId?: string
  rawSql?: string
  parameters?: SqlParameterPropagationVO[]
  rawPaths?: Array<Record<string, unknown>>
  diagnostics?: string[]
}

export interface SqlConstructionDetailVO {
  id: number
  projectId: number
  versionId: number
  definition: SqlDefinitionVO
  constructionSteps: ConstructionStepVO[]
  parameters: SqlParameterVO[]
  renderedSqls: RenderedSqlVO[]
  auditResults?: SqlAuditResultVO[]
  diagnostics: string[]
  sqlNodeStructure?: Record<string, unknown>
  metadata?: Record<string, unknown>
  createTime?: string
}

export const getSqlConstructionDetail = (id: number): Promise<SqlConstructionDetailVO> => {
  return request.get<SqlConstructionDetailVO>(`/api/sql-statements/${id}/construction`)
}

export const getSqlPropagationPaths = (id: number, versionId?: number): Promise<SqlPropagationPathVO> => {
  const params = versionId ? { versionId } : undefined
  return request.get<SqlPropagationPathVO>(`/api/sql-statements/${id}/propagation-paths`, { params })
}

export const getSqlStatementsByProjectAndVersion = (projectId: number, versionId: number): Promise<SqlStatementVO[]> => {
  return request.get<SqlStatementVO[]>(`/api/sql-statements/project/${projectId}/version/${versionId}`)
}
