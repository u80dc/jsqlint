<template>
  <div class="project-detail">
    <!-- 顶部菜单栏 -->
    <div class="top-menu">
      <el-tabs v-model="activeTab" class="main-tabs" @tab-change="handleTabChange">
        <el-tab-pane :label="t('projectDetail.tabs.overview')" name="overview" />
        <el-tab-pane :label="t('projectDetail.tabs.projectSql')" name="projectSql" />
        <el-tab-pane :label="t('projectDetail.tabs.report')" name="sql" />
        <el-tab-pane :label="t('projectDetail.tabs.rules')" name="rules" />
        <el-tab-pane :label="t('projectDetail.tabs.whitelist')" name="whitelist" />
        <el-tab-pane :label="t('projectDetail.tabs.members')" name="members" />
        <el-tab-pane :label="t('projectDetail.tabs.logs')" name="logs" />
      </el-tabs>
    </div>

    <!-- 概览页 -->
    <div v-show="activeTab === 'overview'" class="overview-layout">
      <!-- 左侧：项目信息 + 版本操作 + 版本列表 -->
      <div class="left-panel">
        <!-- 项目信息卡片 -->
        <el-card v-loading="loading" class="detail-card">
          <template #header>
            <div class="card-header">
              <div class="project-title-group">
                <span class="project-name">{{ project?.name }}</span>
                <el-tag :type="project?.status === 1 ? 'success' : 'info'" effect="light" size="small">
                  {{ project?.status === 1 ? t('projectDetail.status.ready') : t('projectDetail.status.notReady') }}
                </el-tag>
              </div>
              <el-tooltip :content="t('common.edit')" placement="top">
                <el-button text class="header-edit-btn" @click="openEditProject">
                  <el-icon><EditPen /></el-icon>
                </el-button>
              </el-tooltip>
            </div>
          </template>

          <el-descriptions :column="1" border class="project-desc">
            <el-descriptions-item :label="t('projectDetail.overview.gitUrl')">
              <div class="git-url-row">
                <span class="info-value git-url">{{ project?.gitUrl || '-' }}</span>
                <el-tooltip v-if="project?.gitUrl" :content="t('common.copy')" placement="top">
                  <el-icon class="copy-btn" @click="copyGitUrl"><CopyDocument /></el-icon>
                </el-tooltip>
              </div>
            </el-descriptions-item>

            <el-descriptions-item :label="t('projectDetail.overview.ref')">
              {{ project?.ref || '-' }}
            </el-descriptions-item>

            <el-descriptions-item :label="t('projectDetail.overview.description')">
              {{ project?.description || '-' }}
            </el-descriptions-item>

            <el-descriptions-item :label="t('projectDetail.overview.credential')">
              <template v-if="project?.credentialName">
                <el-tooltip
                  :content="project.credentialCreatorName
                    ? t('projectDetail.credentials.providedBy', { name: project.credentialCreatorName })
                    : t('projectDetail.credentials.changeCredential')"
                  placement="top"
                >
                  <span class="credential-name" @click="showCredentialDialog">
                    <el-tooltip v-if="project.credentialVisibility !== 0" :content="t('projectDetail.credentials.private')" placement="top">
                      <el-icon><Lock /></el-icon>
                    </el-tooltip>
                    <el-tooltip v-else :content="t('projectDetail.credentials.public')" placement="top">
                      <svg class="globe-icon" viewBox="0 0 1024 1024" xmlns="http://www.w3.org/2000/svg">
                        <path fill="none" stroke="currentColor" stroke-width="24" d="M512 64C282.667 64 96 282.667 96 512s186.667 448 416 448 416-186.667 416-448S741.333 64 512 64zm0 64c194.667 0 352 157.333 352 352s-157.333 352-352 352-352-157.333-352-352 157.333-352 352-352zM144 512h736M512 144v736"/>
                      </svg>
                    </el-tooltip>
                    {{ project.credentialName }}
                  </span>
                </el-tooltip>
              </template>
              <span v-else class="no-credential" @click="showCredentialDialog">
                <el-icon><Plus /></el-icon>
                {{ t('projectDetail.credentials.unset') }}
              </span>
            </el-descriptions-item>

            <el-descriptions-item :label="t('projectDetail.overview.creator')">
              {{ project?.creatorName || '-' }}
            </el-descriptions-item>

            <el-descriptions-item :label="t('projectDetail.overview.createTime')">
              <el-tooltip v-if="project?.createTime" :content="formatDate(project.createTime)" placement="top">
                {{ relativeTime(project.createTime) }}
              </el-tooltip>
              <template v-else>-</template>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>

        <!-- 项目状态步骤条 -->
        <el-card v-loading="statusLoading" class="status-card">
          <div class="status-card-content">
            <el-tooltip :content="executionRunning ? '停止' : '运行'" placement="top">
              <button
                class="execution-toggle"
                :class="{ 'is-running': executionRunning }"
                :disabled="executionSubmitting"
                type="button"
                @click="toggleExecution"
              >
                <el-icon v-if="executionRunning"><Close /></el-icon>
                <el-icon v-else><VideoPlay /></el-icon>
              </button>
            </el-tooltip>
            <ExecutionSteps :steps="projectSteps" />
          </div>
        </el-card>

        <!-- 版本列表 -->
        <el-card class="version-list-card">
          <template #header>
            <div class="version-list-header">
              <span class="section-title">{{ t('projectDetail.overview.versionList') }}</span>
              <el-tooltip :content="t('common.refresh')" placement="top">
                <el-button text @click="loadVersions" class="refresh-btn">
                  <el-icon><Refresh /></el-icon>
                </el-button>
              </el-tooltip>
            </div>
          </template>
            <el-table :data="displayVersions" class="version-table">
            <el-table-column prop="ref" :label="t('projectDetail.overview.ref')" width="120" />
            <el-table-column prop="commitId" label="COMMIT ID" width="130" show-overflow-tooltip>
              <template #default="{ row }">
                {{ row.commitId || '-' }}
              </template>
            </el-table-column>
            <el-table-column prop="commitMessage" :label="t('projectDetail.overview.commitMessage')" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                {{ row.commitMessage || '-' }}
              </template>
            </el-table-column>
            <el-table-column prop="operationTime" :label="t('projectDetail.overview.createTime')" width="160">
              <template #default="{ row }">
                {{ formatDate(row.operationTime) }}
              </template>
            </el-table-column>
            <el-table-column prop="overallStatusName" :label="t('projectDetail.overview.pullStatus')" width="90">
              <template #default="{ row }">
                <el-tooltip :content="row.message" :disabled="!row.message" placement="top">
                  <el-tag
                    :type="getStatusTagType(row.overallStatus)"
                    size="small"
                  >
                    {{ row.overallStatusName || '-' }}
                  </el-tag>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column prop="operatorName" :label="t('projectDetail.overview.operator')" width="100" />
            <el-table-column :label="t('projectDetail.overview.operation')" width="190" align="center" fixed="right">
              <template #default="{ row, $index }">
                <span class="version-actions">
                  <el-tooltip v-if="canViewVersionSql(row)" :content="t('projectDetail.overview.viewSqlTip')" placement="top">
                    <span class="version-action-text" @click="handleViewVersionSql(row)">
                      {{ t('projectDetail.overview.viewSql') }}
                    </span>
                  </el-tooltip>
                  <el-tooltip v-if="canViewVersionReport(row)" :content="t('projectDetail.overview.viewReportTip')" placement="top">
                    <span class="version-action-text" @click="handleViewVersionReport(row)">
                      {{ t('projectDetail.overview.viewReport') }}
                    </span>
                  </el-tooltip>
                  <el-tooltip
                    v-if="canDiffVersion($index)"
                    :content="t('projectDetail.overview.diffTip')"
                    placement="top"
                  >
                    <span class="version-action-text" @click="openVersionDiff(row, $index)">
                      {{ t('projectDetail.overview.diff') }}
                    </span>
                  </el-tooltip>
                  <span v-if="!canViewVersionSql(row) && !canViewVersionReport(row) && !canDiffVersion($index)" class="version-action-empty">-</span>
                </span>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="versions.length > VERSION_PAGE_SIZE" class="version-load-more">
            <el-button
              v-if="displayVersions.length < versions.length"
              text
              type="primary"
              :icon="ArrowDown"
              @click="showMoreVersions"
            >
              {{ t('projectDetail.overview.showMoreVersions', { count: versions.length - displayVersions.length }) }}
            </el-button>
            <el-button
              v-if="displayVersions.length > VERSION_PAGE_SIZE"
              text
              :icon="ArrowUp"
              @click="collapseVersions"
            >
              {{ t('projectDetail.overview.collapseVersions') }}
            </el-button>
          </div>
        </el-card>
      </div>

      <!-- 右侧：统计图表 -->
      <div v-loading="overviewLoading" class="right-panel">
        <!-- 评级 & 关键指标卡片 -->
        <div class="metrics-cards">
          <el-card class="metric-card rating-card">
            <div class="metric-content">
              <div class="metric-label">{{ t('projectDetail.overview.projectScore') }}</div>
              <div class="metric-value score-value">{{ projectScore.score }}</div>
              <div class="rating-badge" :class="'rating-' + projectScore.rating.toLowerCase()">
                {{ projectScore.rating }}
              </div>
            </div>
          </el-card>
          <el-card class="metric-card">
            <div class="metric-content">
              <div class="metric-label">{{ t('projectDetail.overview.sqlHealthRate') }}</div>
              <div class="metric-value">{{ projectScore.complianceRate }}<span class="metric-unit">%</span></div>
              <div class="metric-trend" :class="projectScore.complianceTrend >= 0 ? 'up' : 'down'">
                <el-icon><Top v-if="projectScore.complianceTrend >= 0" /><Bottom v-else /></el-icon>
                {{ Math.abs(projectScore.complianceTrend) }}%
              </div>
            </div>
          </el-card>
          <el-card class="metric-card">
            <div class="metric-content">
              <div class="metric-label">{{ t('projectDetail.overview.issueSqlRate') }}</div>
              <div class="metric-value warning">{{ projectScore.slowSqlRate }}<span class="metric-unit">%</span></div>
              <div class="metric-trend" :class="projectScore.slowSqlTrend <= 0 ? 'up' : 'down'">
                <el-icon><Bottom v-if="projectScore.slowSqlTrend <= 0" /><Top v-else /></el-icon>
                {{ Math.abs(projectScore.slowSqlTrend) }}%
              </div>
            </div>
          </el-card>
          <el-card class="metric-card">
            <div class="metric-content">
              <div class="metric-label">{{ t('projectDetail.overview.fixRate') }}</div>
              <div class="metric-value success">{{ projectScore.fixRate }}<span class="metric-unit">%</span></div>
              <div class="metric-trend" :class="projectScore.fixTrend >= 0 ? 'up' : 'down'">
                <el-icon><Top v-if="projectScore.fixTrend >= 0" /><Bottom v-else /></el-icon>
                {{ Math.abs(projectScore.fixTrend) }}%
              </div>
            </div>
          </el-card>
        </div>

        <!-- SQL 占比 + 失分构成 -->
        <div class="charts-row">
          <el-card class="chart-card pie-card">
            <template #header>
              <span class="section-title">{{ t('projectDetail.overview.sqlRatio') }}</span>
            </template>
            <div ref="pieChartRef" class="chart"></div>
          </el-card>

          <el-card class="chart-card lose-card">
            <template #header>
              <span class="section-title">{{ t('projectDetail.overview.loseScore') }}</span>
            </template>
            <div ref="loseScoreChartRef" class="chart"></div>
          </el-card>
        </div>

        <!-- 趋势分析 -->
        <div class="charts-row trend-row">
          <el-card class="chart-card trend-card">
            <template #header>
              <span class="section-title">{{ t('projectDetail.overview.trend') }}</span>
            </template>
            <div ref="lineChartRef" class="chart"></div>
          </el-card>
        </div>
      </div>
    </div>

    <ProjectReportPanel
      v-show="activeTab === 'sql'"
      :loading="reportLoading"
      :report="projectReport"
      @download="handleDownloadReport"
    />

    <div v-show="activeTab === 'projectSql'" class="content-page project-sql-page">
      <el-card>
        <div class="project-sql-toolbar">
          <div class="project-sql-summary">
            <span>{{ t('projectDetail.projectSql.latestVersion') }}</span>
            <strong>{{ projectSqlVersionLabel }}</strong>
            <span class="project-sql-count">{{ t('projectDetail.projectSql.total', { count: projectSqlList.length }) }}</span>
          </div>
          <el-tooltip :content="t('common.refresh')" placement="top">
            <el-button text class="toolbar-refresh-btn" :loading="projectSqlLoading" @click="loadProjectSql()">
              <el-icon><Refresh /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
        <el-table
          :data="projectSqlList"
          v-loading="projectSqlLoading"
          class="project-sql-table"
          stripe
          row-class-name="clickable-row"
          @row-dblclick="handleViewSqlConstruction"
        >
          <el-table-column prop="statementId" :label="t('projectDetail.projectSql.statementId')" min-width="220">
            <template #default="{ row }">
              <el-tooltip :content="row.statementId || '-'" :disabled="!row.statementId" placement="top">
                <span class="copyable-cell" @click="copyText(row.statementId)">
                  {{ shortenStatementId(row.statementId) }}
                </span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column prop="statementType" :label="t('projectDetail.projectSql.statementType')" width="100" align="center">
            <template #default="{ row }">{{ row.statementType || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('projectDetail.projectSql.sql')" min-width="440">
            <template #default="{ row }">
              <el-tooltip :content="row.rawSql || '-'" :disabled="!row.rawSql" placement="top">
                <span class="copyable-cell copyable-cell--sql" @click="copyText(row.rawSql)">
                  {{ row.rawSql || '-' }}
                </span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column prop="sourceType" :label="t('projectDetail.projectSql.sourceType')" width="130" align="center">
            <template #default="{ row }">{{ row.sourceType || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('projectDetail.projectSql.location')" min-width="180">
            <template #default="{ row }">
              <el-tooltip :content="formatSqlLocation(row) || '-'" :disabled="!formatSqlLocation(row)" placement="top">
                <span class="copyable-cell" @click="copyText(formatSqlLocation(row))">
                  {{ formatSqlLocation(row) || '-' }}
                </span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column :label="t('projectDetail.projectSql.dynamic')" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.isDynamic ? 'warning' : 'info'" size="small">
                {{ row.isDynamic ? t('common.yes') : t('common.no') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="paramCount" :label="t('projectDetail.projectSql.paramCount')" width="90" align="center">
            <template #default="{ row }">{{ row.paramCount ?? 0 }}</template>
          </el-table-column>
          <el-table-column :label="t('projectDetail.issues.title')" width="90" align="center">
            <template #default="{ row }">
              <el-badge
                v-if="issueCountsByScan[row.id] > 0"
                :value="issueCountsByScan[row.id]"
                :type="issueOpenCountsByScan[row.id] > 0 ? 'danger' : 'info'"
                class="issue-badge"
              >
                <el-button text size="small" @click.stop="toggleIssuePanel(row)">
                  {{ t('projectDetail.issues.view') }}
                </el-button>
              </el-badge>
              <span v-else class="issue-none">0</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('projectDetail.projectSql.operation')" width="80" align="center" fixed="right">
            <template #default="{ row }">
              <div class="action-icons-row">
                <el-tooltip :content="t('projectDetail.report.constructionDetail')" placement="top">
                  <el-icon class="action-icon" @click="handleViewSqlConstruction(row)"><View /></el-icon>
                </el-tooltip>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <!-- 审核规则页 -->
    <div v-show="activeTab === 'rules'" class="content-page">
      <el-card>
        <div class="filter-toolbar">
          <div class="rule-filters">
            <el-input
              v-model="ruleKeyword"
              :placeholder="t('projectDetail.rules.searchPlaceholder')"
              clearable
              @keyup.enter="handleSearchRules"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
            <el-select
              v-model="ruleCategory"
              :placeholder="t('projectDetail.rules.category')"
              clearable
              class="rule-filter-select"
              @change="handleSearchRules"
            >
              <el-option :label="t('projectDetail.rules.categoryQuality')" value="SQL_QUALITY" />
              <el-option :label="t('projectDetail.rules.categorySecurity')" value="SECURITY" />
              <el-option :label="t('projectDetail.rules.categoryPerformance')" value="PERFORMANCE" />
            </el-select>
            <el-select
              v-model="ruleEnabled"
              :placeholder="t('projectDetail.rules.status')"
              clearable
              class="rule-filter-select"
              @change="handleSearchRules"
            >
              <el-option :label="t('common.enable')" :value="true" />
              <el-option :label="t('common.disable')" :value="false" />
            </el-select>
            <el-button type="primary" @click="handleSearchRules">{{ t('common.search') }}</el-button>
            <el-button @click="handleResetRules">{{ t('common.reset') }}</el-button>
          </div>
          <div class="filter-toolbar__actions">
            <el-button type="primary" @click="handleAddProjectRule">
              <el-icon><Plus /></el-icon>
              {{ t('projectDetail.rules.addProjectRule') }}
            </el-button>
          </div>
        </div>
        <el-table :data="ruleList" v-loading="ruleLoading" class="rules-table" stripe>
          <el-table-column prop="name" :label="t('projectDetail.rules.ruleName')" min-width="150" show-overflow-tooltip />
          <el-table-column prop="code" :label="t('projectDetail.rules.ruleCode')" min-width="150" show-overflow-tooltip />
          <el-table-column prop="categoryDescription" :label="t('projectDetail.rules.category')" width="110" align="center" />
          <el-table-column prop="severityDescription" :label="t('projectDetail.rules.severity')" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="getSeverityType(row.severity)" size="small">
                {{ row.severityDescription || row.severity }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="description" :label="t('projectDetail.rules.description')" min-width="200" show-overflow-tooltip />
          <el-table-column prop="suggestion" :label="t('projectDetail.rules.suggestion')" min-width="180" show-overflow-tooltip />
          <el-table-column prop="enabled" :label="t('projectDetail.rules.status')" width="90" align="center">
            <template #default="{ row }">
              <el-switch v-model="row.enabledFlag" @change="(val: boolean) => handleToggleRule(row, val)" />
            </template>
          </el-table-column>
          <el-table-column prop="createUserName" :label="t('projectDetail.rules.creator')" width="100" align="center" />
          <el-table-column prop="updateTime" :label="t('projectDetail.rules.updateTime')" width="170" align="center">
            <template #default="{ row }">
              {{ formatDate(row.updateTime) }}
            </template>
          </el-table-column>
          <el-table-column :label="t('projectDetail.rules.operation')" width="100" align="center" fixed="right">
            <template #default="{ row }">
              <div class="action-icons-row">
                <el-tooltip :content="t('common.edit')" placement="top">
                  <el-icon class="action-icon" @click="handleEditProjectRule(row)"><Edit /></el-icon>
                </el-tooltip>
                <el-tooltip :content="t('common.delete')" placement="top">
                  <el-icon class="action-icon action-icon--danger" @click="handleDeleteProjectRule(row)"><Delete /></el-icon>
                </el-tooltip>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="ruleTotal > 0" class="rules-pagination">
          <el-pagination
            v-model:current-page="ruleCurrentPage"
            v-model:page-size="rulePageSize"
            :page-sizes="[10, 20, 50]"
            :total="ruleTotal"
            layout="total, sizes, prev, pager, next"
            background
            @current-change="loadRules"
            @size-change="handleRulePageSizeChange"
          />
        </div>
      </el-card>
    </div>

    <div v-show="activeTab === 'whitelist'" class="content-page whitelist-page">
      <el-card>
        <div class="whitelist-toolbar">
          <div class="whitelist-summary">
            <span class="section-title">{{ t('projectDetail.whitelist.title') }}</span>
            <span class="whitelist-count">{{ t('projectDetail.whitelist.total', { count: whitelistTotal }) }}</span>
          </div>
          <el-button :loading="whitelistLoading" @click="loadWhitelist">
            <el-icon><Refresh /></el-icon>
            {{ t('common.refresh') }}
          </el-button>
        </div>
        <el-table :data="whitelistList" v-loading="whitelistLoading" class="whitelist-table" stripe>
          <el-table-column prop="sqlSample" :label="t('projectDetail.whitelist.sql')" min-width="280" show-overflow-tooltip />
          <el-table-column prop="ruleName" :label="t('projectDetail.whitelist.rule')" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.ruleName || row.ruleId || '-' }}</template>
          </el-table-column>
          <el-table-column prop="sqlFingerprint" :label="t('projectDetail.whitelist.fingerprint')" min-width="220" show-overflow-tooltip />
          <el-table-column prop="reason" :label="t('projectDetail.whitelist.reason')" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.reason || '-' }}</template>
          </el-table-column>
          <el-table-column prop="createUserName" :label="t('projectDetail.whitelist.creator')" width="120" align="center">
            <template #default="{ row }">{{ row.createUserName || '-' }}</template>
          </el-table-column>
          <el-table-column prop="createTime" :label="t('projectDetail.whitelist.createTime')" width="170" align="center">
            <template #default="{ row }">{{ formatDate(row.createTime) }}</template>
          </el-table-column>
          <el-table-column :label="t('projectDetail.whitelist.operation')" width="80" align="center" fixed="right">
            <template #default="{ row }">
              <div class="action-icons-row">
                <el-tooltip :content="t('projectDetail.whitelist.remove')" placement="top">
                  <el-icon class="action-icon action-icon--danger" @click="handleRemoveWhitelist(row)"><Delete /></el-icon>
                </el-tooltip>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="whitelistTotal > 0" class="whitelist-pagination">
          <el-pagination
            v-model:current-page="whitelistCurrentPage"
            v-model:page-size="whitelistPageSize"
            :page-sizes="[10, 20, 50]"
            :total="whitelistTotal"
            layout="total, sizes, prev, pager, next"
            background
            @current-change="loadWhitelist"
            @size-change="handleWhitelistPageSizeChange"
          />
        </div>
      </el-card>
    </div>

    <el-dialog
      v-model="ruleDialogVisible"
      :title="ruleDialogTitle"
      width="720px"
      align-center
      @close="handleRuleDialogClose"
    >
      <el-form ref="ruleFormRef" :model="ruleForm" :rules="ruleFormRules" label-width="100px">
        <el-form-item :label="t('projectDetail.rules.ruleName')" prop="name">
          <el-input v-model="ruleForm.name" :placeholder="t('projectDetail.rules.namePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('projectDetail.rules.ruleCode')" prop="code">
          <el-input v-model="ruleForm.code" :placeholder="t('projectDetail.rules.codePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('projectDetail.rules.category')" prop="category">
          <el-select v-model="ruleForm.category" :placeholder="t('projectDetail.rules.categoryPlaceholder')" style="width: 100%">
            <el-option :label="t('projectDetail.rules.categoryQuality')" value="SQL_QUALITY" />
            <el-option :label="t('projectDetail.rules.categorySecurity')" value="SECURITY" />
            <el-option :label="t('projectDetail.rules.categoryPerformance')" value="PERFORMANCE" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('projectDetail.rules.severity')" prop="severity">
          <el-select v-model="ruleForm.severity" :placeholder="t('projectDetail.rules.severityPlaceholder')" style="width: 100%">
            <el-option :label="t('severity.critical')" value="critical" />
            <el-option :label="t('severity.high')" value="high" />
            <el-option :label="t('severity.warning')" value="warning" />
            <el-option :label="t('severity.info')" value="info" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('projectDetail.rules.status')" prop="enabled">
          <el-switch v-model="ruleForm.enabled" />
        </el-form-item>
        <el-form-item :label="t('projectDetail.rules.description')" prop="description">
          <el-input v-model="ruleForm.description" type="textarea" :rows="2" :placeholder="t('projectDetail.rules.descriptionPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('projectDetail.rules.suggestion')" prop="suggestion">
          <el-input v-model="ruleForm.suggestion" type="textarea" :rows="2" :placeholder="t('projectDetail.rules.suggestionPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('projectDetail.rules.droolsContent')" prop="droolsContent">
          <el-input
            v-model="ruleForm.droolsContent"
            type="textarea"
            :rows="9"
            :placeholder="t('projectDetail.rules.droolsPlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="ruleSubmitLoading" @click="handleSubmitProjectRule">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    <ProjectMembersPanel
      v-show="activeTab === 'members'"
      :active="activeTab === 'members'"
      :is-project-admin="isProjectAdmin"
      :project-id="projectId"
    />

    <ProjectLogsPanel
      v-show="activeTab === 'logs'"
      :active="activeTab === 'logs'"
      :project-id="projectId"
    />

    <!-- 编辑项目对话框 -->
    <el-dialog v-model="editProjectVisible" :title="t('projects.editProject')" width="520px" align-center @close="resetEditProjectForm">
      <el-form ref="editProjectFormRef" :model="editProjectForm" :rules="editProjectRules" label-width="100px">
        <el-form-item :label="t('projects.projectName')" prop="name">
          <el-input v-model="editProjectForm.name" :placeholder="t('projects.projectNamePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('projects.description')" prop="description">
          <el-input
            v-model="editProjectForm.description"
            type="textarea"
            :rows="3"
            maxlength="128"
            show-word-limit
            :placeholder="t('projects.descriptionPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('projects.gitUrl')" prop="gitUrl">
          <el-input v-model="editProjectForm.gitUrl" :placeholder="t('projects.gitUrlPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('projects.ref')" prop="ref">
          <el-input v-model="editProjectForm.ref" :placeholder="t('projects.refPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('projects.dialect')" prop="dialect">
          <el-select v-model="editProjectForm.dialect" style="width: 100%">
            <el-option label="MySQL" value="mysql" />
            <el-option label="PostgreSQL" value="postgresql" />
            <el-option label="Oracle" value="oracle" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('projects.credential')" prop="credentialId">
          <el-select v-model="editProjectForm.credentialId" :placeholder="t('projects.credentialPlaceholder')" style="width: 100%">
            <el-option v-for="cred in editCredentials" :key="cred.id" :label="cred.name" :value="cred.id" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('projects.workspace')" prop="workspaceId">
          <el-select v-model="editProjectForm.workspaceId" :placeholder="t('projects.workspacePlaceholder')" style="width: 100%">
            <el-option :label="t('projects.none')" :value="0" />
            <el-option v-for="ws in editWorkspaces" :key="ws.id" :label="ws.name" :value="ws.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editProjectVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="editProjectSaving" @click="submitEditProject">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    <!-- 凭证选择对话框 -->
    <el-dialog v-model="credentialDialogVisible" :title="t('projectDetail.credentials.selectTitle')" width="500px">
      <el-select
        v-model="selectedCredentialId"
        filterable
        :placeholder="t('projectDetail.credentials.selectPlaceholder')"
        style="width: 100%"
      >
        <el-option
          :value="-1"
          :label="t('projectDetail.credentials.none')"
        />
        <el-option
          v-for="cred in availableCredentials"
          :key="cred.id"
          :label="cred.name"
          :value="cred.id"
        >
          <div class="credential-option">
            <span class="credential-option-name">
              <el-tooltip v-if="cred.visibility !== 0" :content="t('projectDetail.credentials.private')" placement="top">
                <el-icon><Lock /></el-icon>
              </el-tooltip>
              <el-tooltip v-else :content="t('projectDetail.credentials.public')" placement="top">
                <svg class="globe-icon" viewBox="0 0 1024 1024" xmlns="http://www.w3.org/2000/svg">
                  <path fill="none" stroke="currentColor" stroke-width="24" d="M512 64C282.667 64 96 282.667 96 512s186.667 448 416 448 416-186.667 416-448S741.333 64 512 64zm0 64c194.667 0 352 157.333 352 352s-157.333 352-352 352-352-157.333-352-352 157.333-352 352-352zM144 512h736M512 144v736"/>
                </svg>
              </el-tooltip>
              {{ cred.name }}
            </span>
            <span class="credential-option-detail">{{ t('projectDetail.overview.creator') }}: {{ cred.createUserName }}</span>
          </div>
        </el-option>
      </el-select>
      <template #footer>
        <el-button @click="credentialDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="savingCredential" @click="handleSaveCredential">{{ t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    <!-- 问题处理弹窗：单条 SQL 的审核问题列表 + 状态流转 -->
    <el-dialog
      v-model="issueDialogVisible"
      :title="t('projectDetail.issues.dialogTitle')"
      width="860px"
      align-center
    >
      <div v-if="issueStatementId" class="issue-dialog-subtitle">{{ issueStatementId }}</div>
      <el-table :data="issueList" v-loading="issueLoading" size="small">
        <el-table-column prop="severity" :label="t('projectDetail.issues.severity')" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="getIssueSeverityType(row.severity)" size="small">{{ row.severity }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ruleName" :label="t('projectDetail.issues.rule')" min-width="140" show-overflow-tooltip />
        <el-table-column prop="message" :label="t('projectDetail.issues.message')" min-width="200" show-overflow-tooltip />
        <el-table-column prop="status" :label="t('projectDetail.issues.status')" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getIssueStatusType(row.status)" size="small">{{ row.statusDescription }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('projectDetail.issues.handler')" width="100" align="center">
          <template #default="{ row }">{{ row.handleUserName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="handleRemark" :label="t('projectDetail.issues.remark')" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.handleRemark || '-' }}</template>
        </el-table-column>
        <el-table-column :label="t('common.operation')" width="80" align="center" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="openTransit(row)">
              {{ t('projectDetail.issues.handle') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 状态流转内嵌面板 -->
      <div v-if="transitTarget" class="issue-transit-panel">
        <el-form label-width="90px" size="small">
          <el-form-item :label="t('projectDetail.issues.targetStatus')">
            <el-radio-group v-model="transitForm.status">
              <el-radio-button
                v-for="opt in availableTransitions(transitTarget.status)"
                :key="opt.value"
                :value="opt.value"
              >{{ opt.label }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item :label="t('projectDetail.issues.remark')">
            <el-input
              v-model="transitForm.remark"
              type="textarea"
              :rows="2"
              maxlength="500"
              :placeholder="t('projectDetail.issues.remarkPlaceholder')"
            />
          </el-form-item>
          <el-form-item v-if="transitForm.status === 'DISMISSED'">
            <el-checkbox v-model="transitForm.addToWhitelist">
              {{ t('projectDetail.issues.addToWhitelist') }}
            </el-checkbox>
          </el-form-item>
          <el-form-item>
            <el-button size="small" @click="transitTarget = null">{{ t('common.cancel') }}</el-button>
            <el-button type="primary" size="small" :loading="transitSaving" @click="submitTransit">
              {{ t('common.confirm') }}
            </el-button>
          </el-form-item>
        </el-form>
      </div>
    </el-dialog>

    <!-- 版本差异弹窗：SQL 变更明细 + 风险增量 -->
    <el-dialog
      v-model="diffDialogVisible"
      :title="t('projectDetail.diff.title')"
      width="860px"
      align-center
    >
      <div v-if="diffData" v-loading="diffLoading" class="diff-body">
        <div class="diff-summary">
          <span class="diff-summary-item diff-added">
            {{ t('projectDetail.diff.added') }}: {{ diffData.addedCount }}
          </span>
          <span class="diff-summary-item diff-modified">
            {{ t('projectDetail.diff.modified') }}: {{ diffData.modifiedCount }}
          </span>
          <span class="diff-summary-item diff-deleted">
            {{ t('projectDetail.diff.deleted') }}: {{ diffData.deletedCount }}
          </span>
        </div>
        <div v-if="diffData.newIssueCount > 0" class="diff-risk">
          <span class="diff-risk-label">{{ t('projectDetail.diff.risk') }}:</span>
          <el-tag v-if="diffData.newCriticalCount" type="danger" size="small">
            {{ t('severity.critical') }} × {{ diffData.newCriticalCount }}
          </el-tag>
          <el-tag v-if="diffData.newHighCount" type="danger" effect="plain" size="small">
            {{ t('severity.high') }} × {{ diffData.newHighCount }}
          </el-tag>
          <el-tag v-if="diffData.newWarningCount" type="warning" size="small">
            {{ t('severity.warning') }} × {{ diffData.newWarningCount }}
          </el-tag>
          <el-tag v-if="diffData.newInfoCount" type="info" size="small">
            {{ t('severity.info') }} × {{ diffData.newInfoCount }}
          </el-tag>
        </div>
        <el-table v-if="diffData.changes.length > 0" :data="diffData.changes" size="small" max-height="420">
          <el-table-column prop="changeType" :label="t('projectDetail.diff.changeType')" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="getChangeTypeTag(row.changeType)" size="small">
                {{ getChangeTypeLabel(row.changeType) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="statementId" :label="t('projectDetail.diff.statementId')" min-width="300" show-overflow-tooltip />
        </el-table>
        <el-empty
          v-else
          :description="t('projectDetail.diff.empty')"
          :image-size="60"
        />
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { getWorkspaces, addProjectToWorkspace, removeProjectFromWorkspace, type WorkspaceVO } from '@/api/workspace'
import { Lock, Search, Refresh, Plus, Top, Bottom, VideoPlay, Close, EditPen, CopyDocument, View, Edit, Delete, ArrowDown, ArrowUp } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { chartColors, chartNeutral, seriesPalette } from '@/utils/chartPalette'
import { useTheme } from '@/composables/useTheme'
import ProjectLogsPanel from '@/components/project/ProjectLogsPanel.vue'
import ProjectMembersPanel from '@/components/project/ProjectMembersPanel.vue'
import ProjectReportPanel from '@/components/project/ProjectReportPanel.vue'
import ExecutionSteps, { type ExecutionStep, type ExecutionStepStatus } from '@/components/project/ExecutionSteps.vue'
import { executeProject, getProject, getProjectCredentials, getProjectStatus, getProjectVersions, getVersionDiff, updateProject, updateProjectCredential, type CredentialVO, type ProjectVO, type ProjectVersionStepVO, type ProjectVersionVO, type VersionDiffVO } from '@/api/project'
import type { AuditRuleVO } from '@/api/auditRule'
import { getAuditIssues, transitIssueStatus, type AuditIssueVO } from '@/api/auditIssue'
import { checkIsProjectAdmin } from '@/api/projectMember'
import { createProjectAuditRule, deleteProjectAuditRule, getProjectAuditRules, toggleProjectAuditRule, updateProjectAuditRule } from '@/api/projectAuditRule'
import { downloadProjectReportPdf, getProjectReport, type ProjectReportVO } from '@/api/projectReport'
import {
  getProjectOverviewIssueCategories,
  getProjectOverviewIssueRectificationRate,
  getProjectOverviewIssueSqlRate,
  getProjectOverviewScore,
  getProjectOverviewSql,
  getProjectOverviewSqlQualityTrend
} from '@/api/projectOverview'
import { getSqlStatementsByProjectAndVersion, type SqlStatementVO } from '@/api/sqlStatement'
import { deleteSqlWhitelist, getSqlWhitelistList, type SqlWhitelistVO } from '@/api/sqlWhitelist'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { t, locale } = useI18n()
const { onThemeChange } = useTheme()

const loading = ref(false)
const statusLoading = ref(false)
const reportLoading = ref(false)
const projectSqlLoading = ref(false)
const overviewLoading = ref(false)
const project = ref<ProjectVO | null>(null)
const versions = ref<ProjectVersionVO[]>([])
const projectReport = ref<ProjectReportVO | null>(null)
const projectSqlList = ref<SqlStatementVO[]>([])
const projectSqlVersion = ref<ProjectVersionVO | null>(null)
const isProjectAdmin = ref(false)
const executionRunning = ref(false)
const executionSubmitting = ref(false)
const STATUS_REFRESH_INTERVAL = 10000
let statusRefreshTimer: ReturnType<typeof setInterval> | null = null
let statusRefreshing = false

// 原地编辑项目
const editProjectVisible = ref(false)
const editProjectSaving = ref(false)
const editProjectFormRef = ref<FormInstance>()
const editCredentials = ref<CredentialVO[]>([])
const editWorkspaces = ref<WorkspaceVO[]>([])
const editProjectForm = reactive({
  name: '',
  description: '',
  gitUrl: '',
  ref: '',
  dialect: 'mysql',
  credentialId: undefined as number | undefined,
  workspaceId: 0
})
const editProjectRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('projects.projectNameRequired'), trigger: 'blur' }],
  gitUrl: [{ required: true, message: t('projects.gitUrlRequired'), trigger: 'blur' }],
  ref: [{ required: true, message: t('projects.refRequired'), trigger: 'blur' }],
  credentialId: [{ required: true, message: t('projects.credentialRequired'), trigger: 'change' }],
  workspaceId: [{ required: true, message: t('projects.workspaceRequired'), trigger: 'change' }]
}))

const projectDetailTabs = ['overview', 'projectSql', 'sql', 'rules', 'whitelist', 'members', 'logs']

const getRouteTab = () => {
  const tab = typeof route.query.tab === 'string' ? route.query.tab : ''
  return projectDetailTabs.includes(tab) ? tab : 'overview'
}

const getRouteVersionId = () => {
  const versionId = typeof route.query.versionId === 'string' ? Number(route.query.versionId) : 0
  return Number.isFinite(versionId) && versionId > 0 ? versionId : undefined
}

// 顶部菜单当前激活的标签
const activeTab = ref(getRouteTab())

// 图表引用
const pieChartRef = ref<HTMLElement>()
const lineChartRef = ref<HTMLElement>()
const loseScoreChartRef = ref<HTMLElement>()
let pieChart: echarts.ECharts | null = null
let lineChart: echarts.ECharts | null = null
let loseScoreChart: echarts.ECharts | null = null

// 项目状态步骤条
const createDefaultProjectSteps = (): ExecutionStep[] => [
  { key: 'pull', label: t('projectDetail.steps.pull'), status: 'pending' },
  { key: 'compile', label: t('projectDetail.steps.compile'), status: 'pending' },
  { key: 'scan', label: t('projectDetail.steps.scan'), status: 'pending' },
  { key: 'audit', label: t('projectDetail.steps.audit'), status: 'pending' },
  { key: 'report', label: t('projectDetail.steps.report'), status: 'pending' }
]

const projectSteps = ref<ExecutionStep[]>(createDefaultProjectSteps())

// SQL 统计数据（环形图）
const sqlTypeStats = ref<Array<{ name: string, value: number }>>([])

// 项目评分数据
const projectScore = ref({
  score: 0,
  rating: 'D',
  complianceRate: 0,
  complianceTrend: 0,
  slowSqlRate: 0,
  slowSqlTrend: 0,
  fixRate: 0,
  fixTrend: 0
})

// 失分饼图数据
const loseScoreData = ref({
  securityDeduction: 0,
  performanceDeduction: 0,
  qualityDeduction: 0,
  otherDeduction: 0
})

// 趋势数据（折线图）
const trendData = ref({
  versions: [] as string[],
  total: [] as number[],
  critical: [] as number[],
  high: [] as number[],
  warning: [] as number[]
})

// 审核规则相关
const ruleKeyword = ref('')
const ruleCategory = ref('')
const ruleEnabled = ref<boolean | null | ''>(null)
const ruleLoading = ref(false)
const ruleSubmitLoading = ref(false)
const ruleDialogVisible = ref(false)
const ruleIsEdit = ref(false)
const currentRuleId = ref(0)
const ruleCurrentPage = ref(1)
const rulePageSize = ref(10)
const ruleTotal = ref(0)
const ruleList = ref<AuditRuleVO[]>([])
const ruleFormRef = ref<FormInstance>()

interface ProjectRuleForm {
  name: string
  code: string
  description: string
  suggestion: string
  severity: string
  category: string
  droolsContent: string
  enabled: boolean
}

const ruleForm = reactive<ProjectRuleForm>({
  name: '',
  code: '',
  description: '',
  suggestion: '',
  severity: 'warning',
  category: 'SQL_QUALITY',
  droolsContent: '',
  enabled: true
})

const ruleFormRules = computed<FormRules<ProjectRuleForm>>(() => ({
  name: [{ required: true, message: t('projectDetail.rules.nameRequired'), trigger: 'blur' }],
  code: [
    { required: true, message: t('projectDetail.rules.codeRequired'), trigger: 'blur' },
    { pattern: /^[A-Z_][A-Z0-9_]*$/, message: t('projectDetail.rules.codePattern'), trigger: 'blur' }
  ],
  severity: [{ required: true, message: t('projectDetail.rules.severityRequired'), trigger: 'change' }],
  category: [{ required: true, message: t('projectDetail.rules.categoryRequired'), trigger: 'change' }],
  droolsContent: [{ required: true, message: t('projectDetail.rules.droolsRequired'), trigger: 'blur' }]
}))

const ruleDialogTitle = computed(() => ruleIsEdit.value
  ? t('projectDetail.rules.editProjectRule')
  : t('projectDetail.rules.addProjectRule'))

// SQL 白名单相关
const whitelistLoading = ref(false)
const whitelistList = ref<SqlWhitelistVO[]>([])
const whitelistCurrentPage = ref(1)
const whitelistPageSize = ref(10)
const whitelistTotal = ref(0)

const loadWhitelist = async () => {
  whitelistLoading.value = true
  try {
    const result = await getSqlWhitelistList(projectId, {
      page: whitelistCurrentPage.value,
      pageSize: whitelistPageSize.value
    })
    whitelistList.value = result.list || []
    whitelistTotal.value = Number(result.total || 0)
  } catch {
    whitelistList.value = []
    whitelistTotal.value = 0
    ElMessage.error(t('projectDetail.messages.loadWhitelistError'))
  } finally {
    whitelistLoading.value = false
  }
}

const handleWhitelistPageSizeChange = () => {
  whitelistCurrentPage.value = 1
  loadWhitelist()
}

const handleRemoveWhitelist = async (row: SqlWhitelistVO) => {
  try {
    await ElMessageBox.confirm(
      t('projectDetail.messages.removeWhitelistConfirm'),
      t('projectDetail.messages.removeWhitelistTitle'),
      {
        type: 'warning',
        confirmButtonText: t('projectDetail.whitelist.remove'),
        cancelButtonText: t('common.cancel')
      }
    )
    await deleteSqlWhitelist(projectId, row.id)
    ElMessage.success(t('projectDetail.messages.removeWhitelistSuccess'))
    if (whitelistList.value.length === 1 && whitelistCurrentPage.value > 1) {
      whitelistCurrentPage.value -= 1
    }
    await loadWhitelist()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(t('projectDetail.messages.removeWhitelistError'))
    }
  }
}

const getSeverityType = (severity: string) => {
  switch (severity?.toLowerCase()) {
    case 'critical': return 'danger'
    case 'high': return 'warning'
    case 'warning': return 'warning'
    default: return 'info'
  }
}

const handleAddProjectRule = () => {
  ruleIsEdit.value = false
  resetRuleForm()
  ruleDialogVisible.value = true
}

const handleEditProjectRule = (row: AuditRuleVO) => {
  ruleIsEdit.value = true
  currentRuleId.value = row.id
  ruleForm.name = row.name
  ruleForm.code = row.code
  ruleForm.description = row.description || ''
  ruleForm.suggestion = row.suggestion || ''
  ruleForm.severity = row.severity
  ruleForm.category = row.category
  ruleForm.droolsContent = row.droolsContent
  ruleForm.enabled = row.enabled === 1
  ruleDialogVisible.value = true
}

const handleSearchRules = () => {
  ruleCurrentPage.value = 1
  loadRules()
}

const handleResetRules = () => {
  ruleKeyword.value = ''
  ruleCategory.value = ''
  ruleEnabled.value = null
  handleSearchRules()
}

const handleRulePageSizeChange = () => {
  ruleCurrentPage.value = 1
  loadRules()
}

const handleToggleRule = async (row: AuditRuleVO, enabled: boolean) => {
  try {
    await toggleProjectAuditRule(projectId, row.id, enabled)
    row.enabled = enabled ? 1 : 0
    row.enabledFlag = enabled
    ElMessage.success(t('projectDetail.messages.toggleRuleSuccess', {
      action: enabled ? t('common.enable') : t('common.disable'),
      name: row.name
    }))
  } catch {
    row.enabledFlag = !enabled
  }
}

const loadRules = async () => {
  ruleLoading.value = true
  try {
    const params: {
      page: number
      pageSize: number
      name?: string
      category?: string
      enabled?: boolean
    } = {
      page: ruleCurrentPage.value,
      pageSize: rulePageSize.value
    }
    if (ruleKeyword.value) {
      params.name = ruleKeyword.value
    }
    if (ruleCategory.value) {
      params.category = ruleCategory.value
    }
    if (typeof ruleEnabled.value === 'boolean') {
      params.enabled = ruleEnabled.value
    }

    const data = await getProjectAuditRules(projectId, params)
    ruleList.value = data.list.map(rule => ({
      ...rule,
      enabledFlag: rule.enabled === 1
    }))
    ruleTotal.value = data.total
  } catch {
    ElMessage.error(t('projectDetail.messages.loadRulesError'))
  } finally {
    ruleLoading.value = false
  }
}

const handleRuleDialogClose = () => {
  resetRuleForm()
}

const resetRuleForm = () => {
  ruleForm.name = ''
  ruleForm.code = ''
  ruleForm.description = ''
  ruleForm.suggestion = ''
  ruleForm.severity = 'warning'
  ruleForm.category = 'SQL_QUALITY'
  ruleForm.droolsContent = ''
  ruleForm.enabled = true
  currentRuleId.value = 0
  ruleFormRef.value?.clearValidate()
}

const handleSubmitProjectRule = async () => {
  const valid = await ruleFormRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }

  try {
    ruleSubmitLoading.value = true
    if (ruleIsEdit.value) {
      await updateProjectAuditRule(projectId, currentRuleId.value, {
        name: ruleForm.name,
        code: ruleForm.code,
        description: ruleForm.description,
        suggestion: ruleForm.suggestion,
        severity: ruleForm.severity,
        category: ruleForm.category,
        droolsContent: ruleForm.droolsContent,
        enabled: ruleForm.enabled
      })
      ElMessage.success(t('projectDetail.messages.updateRuleSuccess'))
    } else {
      await createProjectAuditRule(projectId, {
        name: ruleForm.name,
        code: ruleForm.code,
        description: ruleForm.description,
        suggestion: ruleForm.suggestion,
        severity: ruleForm.severity,
        category: ruleForm.category,
        droolsContent: ruleForm.droolsContent,
        enabled: ruleForm.enabled
      })
      ElMessage.success(t('projectDetail.messages.createRuleSuccess'))
    }
    ruleDialogVisible.value = false
    loadRules()
  } catch (error) {
    if (error !== false) {
      ElMessage.error(ruleIsEdit.value
        ? t('projectDetail.messages.updateRuleError')
        : t('projectDetail.messages.createRuleError'))
    }
  } finally {
    ruleSubmitLoading.value = false
  }
}

const handleDeleteProjectRule = async (row: AuditRuleVO) => {
  try {
    await ElMessageBox.confirm(
      t('projectDetail.messages.deleteRuleConfirm', { name: row.name }),
      t('projectDetail.messages.deleteRuleTitle'),
      {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel')
      }
    )
    await deleteProjectAuditRule(projectId, row.id)
    ElMessage.success(t('projectDetail.messages.deleteRuleSuccess'))
    if (ruleList.value.length === 1 && ruleCurrentPage.value > 1) {
      ruleCurrentPage.value -= 1
    }
    loadRules()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(t('projectDetail.messages.deleteRuleError'))
    }
  }
}

// 标签页切换处理
const handleTabChange = (tabName: string) => {
  activeTab.value = tabName
  router.replace({
    path: route.path,
    query: tabName === 'overview' ? {} : { tab: tabName }
  })
  if (tabName === 'whitelist') {
    loadWhitelist()
  }
}

const formatReportFileTime = (date?: string) => {
  if (!date) {
    return t('projectDetail.messages.unknownTime')
  }
  const parsed = new Date(date)
  if (Number.isNaN(parsed.getTime())) {
    return date.replace(/[\\/:*?"<>|\s]+/g, '-')
  }
  const pad = (value: number) => String(value).padStart(2, '0')
  return [
    parsed.getFullYear(),
    pad(parsed.getMonth() + 1),
    pad(parsed.getDate())
  ].join('-') + '_' + [
    pad(parsed.getHours()),
    pad(parsed.getMinutes()),
    pad(parsed.getSeconds())
  ].join('-')
}

// 版本列表显示逻辑：默认 5 条，每次"更多"追加 5 条，可收起回 5 条
const VERSION_PAGE_SIZE = 5
const VERSION_STEP = 5
const versionVisibleCount = ref(VERSION_PAGE_SIZE)
const displayVersions = computed(() => {
  return versions.value.slice(0, versionVisibleCount.value)
})

const showMoreVersions = () => {
  versionVisibleCount.value = Math.min(versionVisibleCount.value + VERSION_STEP, versions.value.length)
}

const collapseVersions = () => {
  versionVisibleCount.value = VERSION_PAGE_SIZE
}

const projectSqlVersionLabel = computed(() => {
  if (!projectSqlVersion.value) {
    return t('projectDetail.projectSql.noAnalyzedVersion')
  }
  const version = projectSqlVersion.value
  const ref = version.ref || '-'
  const commit = version.commitId ? version.commitId.slice(0, 8) : '-'
  return `${ref} / ${commit}`
})

/** 复制 Git 地址到剪贴板 */
const copyGitUrl = async () => {
  if (!project.value?.gitUrl) {
    return
  }
  try {
    await navigator.clipboard.writeText(project.value.gitUrl)
    ElMessage.success(t('common.copySuccess'))
  } catch {
    ElMessage.error(t('common.copyFailed'))
  }
}

/** 相对时间：x 天前 / x 小时前，完整时间由 tooltip 展示 */
const relativeTime = (value: string) => {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return '-'
  }
  const diff = Date.now() - date.getTime()
  const days = Math.floor(diff / 86400000)
  if (days >= 1) {
    return t('time.daysAgo', { count: days })
  }
  const hours = Math.floor(diff / 3600000)
  if (hours >= 1) {
    return t('time.hoursAgo', { count: hours })
  }
  const minutes = Math.floor(diff / 60000)
  if (minutes >= 1) {
    return t('time.minutesAgo', { count: minutes })
  }
  return t('time.justNow')
}

/** 原地编辑项目：弹窗复用列表页表单结构，保存后刷新信息卡 */
const openEditProject = async () => {
  if (!project.value) {
    return
  }
  editProjectForm.name = project.value.name
  editProjectForm.description = project.value.description || ''
  editProjectForm.gitUrl = project.value.gitUrl
  editProjectForm.ref = project.value.ref || ''
  editProjectForm.dialect = project.value.dialect || 'mysql'
  editProjectForm.credentialId = project.value.credentialId
  editProjectForm.workspaceId = project.value.workspaceId ?? 0
  // 凭证与工作空间选项并行加载（失败不阻塞弹窗打开）
  try {
    const [creds, spaces] = await Promise.all([getProjectCredentials(), getWorkspaces()])
    editCredentials.value = creds
    editWorkspaces.value = spaces
  } catch {
    editCredentials.value = []
    editWorkspaces.value = []
  }
  editProjectVisible.value = true
}

const resetEditProjectForm = () => {
  editProjectFormRef.value?.clearValidate()
}

const submitEditProject = async () => {
  await editProjectFormRef.value?.validate()
  editProjectSaving.value = true
  try {
    const workspaceId = editProjectForm.workspaceId || 0
    await updateProject(projectId, {
      name: editProjectForm.name.trim(),
      description: editProjectForm.description?.trim() || undefined,
      gitUrl: editProjectForm.gitUrl.trim(),
      ref: editProjectForm.ref.trim(),
      dialect: editProjectForm.dialect,
      credentialId: editProjectForm.credentialId,
      workspaceId: workspaceId === 0 ? undefined : workspaceId
    })
    // 工作空间归属变化需单独同步（与列表页创建逻辑同构）
    const targetWorkspace = workspaceId === 0 ? null : workspaceId
    if (targetWorkspace !== (project.value?.workspaceId ?? null)) {
      try {
        if (targetWorkspace === null) {
          await removeProjectFromWorkspace((project.value?.workspaceId)!, projectId)
        } else {
          await addProjectToWorkspace(targetWorkspace, projectId)
        }
      } catch {
        // 工作空间同步失败不影响主流程，提示后继续
        ElMessage.warning(t('projects.moveError'))
      }
    }
    ElMessage.success(t('projects.updateSuccess'))
    editProjectVisible.value = false
    await loadProject()
  } finally {
    editProjectSaving.value = false
  }
}

// 凭证相关
const credentialDialogVisible = ref(false)
const selectedCredentialId = ref<number | null>(null)
const availableCredentials = ref<CredentialVO[]>([])
const savingCredential = ref(false)

const projectId = Number(route.params.id)

const loadProject = async () => {
  loading.value = true
  try {
    project.value = await getProject(projectId)
    await loadVersions()
    await loadProjectStatus()
    if (activeTab.value === 'projectSql') {
      await loadProjectSql()
    } else if (activeTab.value === 'sql') {
      await loadProjectReport()
    } else if (activeTab.value === 'whitelist') {
      await loadWhitelist()
    }
  } catch {
    ElMessage.error(t('projectDetail.messages.loadProjectError'))
  } finally {
    loading.value = false
  }
}

const loadVersions = async () => {
  try {
    versions.value = await getProjectVersions(projectId)
  } catch {
    versions.value = []
  } finally {
    // 数据刷新后回到默认展示条数
    versionVisibleCount.value = VERSION_PAGE_SIZE
  }
}

const toggleExecution = async () => {
  if (executionRunning.value) {
    executionRunning.value = false
    return
  }

  executionSubmitting.value = true
  try {
    const submitted = await executeProject(projectId)
    if (submitted) {
      await loadVersions()
      await loadProjectStatus()
      executionRunning.value = true
    } else {
      ElMessage.error(t('projectDetail.messages.executeError'))
    }
  } catch {
    ElMessage.error(t('projectDetail.messages.executeError'))
  } finally {
    executionSubmitting.value = false
  }
}

const getAnalyzedVersionCandidates = () => {
  const reachedSqlScan = versions.value.filter(version => (version.currentStep ?? -1) >= 2)
  return reachedSqlScan.length > 0 ? reachedSqlScan : versions.value
}

const findVersion = (versionId?: number) => {
  if (!versionId) {
    return null
  }
  return versions.value.find(version => version.id === versionId) || null
}

const switchProjectDetailTab = (tabName: string, versionId?: number) => {
  activeTab.value = tabName
  router.replace({
    path: route.path,
    query: {
      tab: tabName,
      ...(versionId ? { versionId: String(versionId) } : {})
    }
  })
}

const canViewVersionReport = (row: ProjectVersionVO) => {
  return (row.currentStep ?? -1) === 4 && row.overallStatus === 2
}

const canViewVersionSql = (row: ProjectVersionVO) => {
  const currentStep = row.currentStep ?? -1
  return currentStep > 2 || (currentStep === 2 && row.overallStatus === 2)
}

const handleViewVersionSql = (row: ProjectVersionVO) => {
  switchProjectDetailTab('projectSql', row.id)
}

const handleViewVersionReport = (row: ProjectVersionVO) => {
  switchProjectDetailTab('sql', row.id)
}

const loadProjectSql = async (versionId = getRouteVersionId()) => {
  projectSqlLoading.value = true
  try {
    if (versions.value.length === 0) {
      await loadVersions()
    }

    if (versionId) {
      projectSqlVersion.value = findVersion(versionId)
      projectSqlList.value = await getSqlStatementsByProjectAndVersion(projectId, versionId)
      return
    }

    for (const version of getAnalyzedVersionCandidates()) {
      const list = await getSqlStatementsByProjectAndVersion(projectId, version.id)
      if (list.length > 0) {
        projectSqlVersion.value = version
        projectSqlList.value = list
        loadIssueCounts()
        return
      }
    }

    projectSqlList.value = []
    projectSqlVersion.value = null
  } catch {
    projectSqlList.value = []
    projectSqlVersion.value = null
    ElMessage.error(t('projectDetail.projectSql.loadError'))
  } finally {
    projectSqlLoading.value = false
  }
}

const formatSqlLocation = (row: SqlStatementVO) => {
  if (!row.sourceFile) {
    return '-'
  }
  return row.sourceLine ? `${row.sourceFile}:${row.sourceLine}` : row.sourceFile
}

// ============================================
// 审核问题处理（行内入口 + 弹窗处理）
// ============================================
const issueDialogVisible = ref(false)
const issueLoading = ref(false)
const issueList = ref<AuditIssueVO[]>([])
const issueStatementId = ref('')
const issueScanId = ref<number | null>(null)
/** 每条 SQL 的问题计数（总数/待处理数），行内徽标用 */
const issueCountsByScan = reactive<Record<number, number>>({})
const issueOpenCountsByScan = reactive<Record<number, number>>({})
const transitTarget = ref<AuditIssueVO | null>(null)
const transitSaving = ref(false)
const transitForm = reactive({
  status: 'CONFIRMED',
  remark: '',
  addToWhitelist: false
})

const getIssueSeverityType = (severity?: string) => {
  const types: Record<string, string> = {
    critical: 'danger',
    high: 'danger',
    warning: 'warning',
    info: 'info'
  }
  return types[severity || ''] || 'info'
}

const getIssueStatusType = (status?: string) => {
  const types: Record<string, string> = {
    OPEN: 'danger',
    CONFIRMED: 'warning',
    RESOLVED: 'success',
    DISMISSED: 'info'
  }
  return types[status || 'OPEN'] || 'danger'
}

/** 状态机与后端 IssueStatus.canTransit 保持一致 */
const availableTransitions = (current: string) => {
  const options: { value: string; label: string }[] = []
  if (current === 'OPEN') {
    options.push({ value: 'CONFIRMED', label: t('projectDetail.issues.statusOptions.confirmed') })
    options.push({ value: 'DISMISSED', label: t('projectDetail.issues.statusOptions.dismissed') })
  } else if (current === 'CONFIRMED') {
    options.push({ value: 'RESOLVED', label: t('projectDetail.issues.statusOptions.resolved') })
    options.push({ value: 'DISMISSED', label: t('projectDetail.issues.statusOptions.dismissed') })
    options.push({ value: 'OPEN', label: t('projectDetail.issues.statusOptions.reopen') })
  } else {
    options.push({ value: 'OPEN', label: t('projectDetail.issues.statusOptions.reopen') })
  }
  return options
}

/** 加载全部问题的计数，按 scanId 分组到行内徽标 */
const loadIssueCounts = async () => {
  if (!projectId || projectSqlList.value.length === 0) {
    return
  }
  try {
    const result = await getAuditIssues(projectId, { pageSize: 500 })
    const total: Record<number, number> = {}
    const open: Record<number, number> = {}
    for (const issue of result.list) {
      if (issue.scanId == null) {
        continue
      }
      total[issue.scanId] = (total[issue.scanId] || 0) + 1
      if (issue.status === 'OPEN') {
        open[issue.scanId] = (open[issue.scanId] || 0) + 1
      }
    }
    Object.keys(issueCountsByScan).forEach(k => delete issueCountsByScan[Number(k)])
    Object.assign(issueCountsByScan, total)
    Object.keys(issueOpenCountsByScan).forEach(k => delete issueOpenCountsByScan[Number(k)])
    Object.assign(issueOpenCountsByScan, open)
  } catch {
    // 计数加载失败不阻塞列表展示
  }
}

const toggleIssuePanel = async (row: SqlStatementVO) => {
  issueScanId.value = row.id
  issueStatementId.value = row.statementId || ''
  issueDialogVisible.value = true
  transitTarget.value = null
  issueLoading.value = true
  try {
    const result = await getAuditIssues(projectId, { scanId: row.id, pageSize: 100 })
    issueList.value = result.list
  } catch {
    issueList.value = []
    ElMessage.error(t('projectDetail.issues.loadError'))
  } finally {
    issueLoading.value = false
  }
}

const openTransit = (row: AuditIssueVO) => {
  transitTarget.value = row
  const first = availableTransitions(row.status)[0]
  transitForm.status = first ? first.value : 'CONFIRMED'
  transitForm.remark = ''
  transitForm.addToWhitelist = false
}

const submitTransit = async () => {
  if (!transitTarget.value) {
    return
  }
  transitSaving.value = true
  try {
    await transitIssueStatus(projectId, transitTarget.value.id, {
      status: transitForm.status,
      remark: transitForm.remark || undefined,
      addToWhitelist: transitForm.status === 'DISMISSED' ? transitForm.addToWhitelist : undefined
    })
    ElMessage.success(t('projectDetail.issues.transitSuccess'))
    transitTarget.value = null
    // 刷新弹窗列表与行内徽标
    if (issueScanId.value != null) {
      const result = await getAuditIssues(projectId, { scanId: issueScanId.value, pageSize: 100 })
      issueList.value = result.list
    }
    await loadIssueCounts()
  } catch {
    ElMessage.error(t('projectDetail.issues.transitError'))
  } finally {
    transitSaving.value = false
  }
}

// ============================================
// 版本差异（diff）
// ============================================
const diffDialogVisible = ref(false)
const diffLoading = ref(false)
const diffData = ref<VersionDiffVO | null>(null)

/** 版本列表按时间倒序，行 index 的下一行即上一版本 */
const canDiffVersion = (rowIndex: number) => {
  return rowIndex >= 0 && rowIndex + 1 < versions.value.length
}

const openVersionDiff = async (row: ProjectVersionVO, rowIndex: number) => {
  const previous = versions.value[rowIndex + 1]
  if (!previous || !row.id || !previous.id) {
    return
  }
  diffDialogVisible.value = true
  diffLoading.value = true
  diffData.value = null
  try {
    diffData.value = await getVersionDiff(projectId, previous.id, row.id)
  } catch {
    ElMessage.error(t('projectDetail.diff.loadError'))
  } finally {
    diffLoading.value = false
  }
}

const getChangeTypeTag = (changeType?: string) => {
  const types: Record<string, string> = {
    ADDED: 'success',
    MODIFIED: 'warning',
    DELETED: 'danger'
  }
  return types[changeType || ''] || 'info'
}

const getChangeTypeLabel = (changeType?: string) => {
  const labels: Record<string, string> = {
    ADDED: t('projectDetail.diff.added'),
    MODIFIED: t('projectDetail.diff.modified'),
    DELETED: t('projectDetail.diff.deleted')
  }
  return labels[changeType || ''] || changeType || '-'
}

/**
 * statementId 缩短展示：去掉方法名前的包名前缀，保留"类.方法(参数)"。
 * 例：cn.jnu.mapper.BookMapper.count(cn.jnu.entity.Book) → BookMapper.count(cn.jnu.entity.Book)
 */
const shortenStatementId = (statementId?: string) => {
  if (!statementId) {
    return '-'
  }
  const openParen = statementId.indexOf('(')
  if (openParen < 0) {
    return statementId
  }
  const beforeParen = statementId.slice(0, openParen)
  // 从括号前往前找第二个点，保留最后两段（类名.方法名）
  const lastDot = beforeParen.lastIndexOf('.')
  const secondLastDot = lastDot > 0 ? beforeParen.lastIndexOf('.', lastDot - 1) : -1
  const methodPart = secondLastDot > 0 ? beforeParen.slice(secondLastDot + 1) : beforeParen
  return `${methodPart}${statementId.slice(openParen)}`
}

/** 单击复制完整 statementId */
/** 单击复制文本到剪贴板，空值忽略 */
const copyText = async (text?: string | null) => {
  if (!text) {
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(t('common.copySuccess'))
  } catch {
    ElMessage.error(t('common.copyFailed'))
  }
}

const formatSqlMapper = (row: SqlStatementVO) => {
  if (!row.mapperClass && !row.mapperMethod) {
    return '-'
  }
  if (!row.mapperClass) {
    return row.mapperMethod || '-'
  }
  return row.mapperMethod ? `${row.mapperClass}.${row.mapperMethod}` : row.mapperClass
}

const handleViewSqlConstruction = (row: SqlStatementVO) => {
  router.push({
    path: `/workspace/sql-statements/${row.id}/construction`,
    query: {
      projectId: String(projectId),
      fromTab: 'projectSql',
      title: formatSqlMapper(row) !== '-' ? formatSqlMapper(row) : row.statementId
    }
  })
}

const getProjectStepLabel = (step: ProjectVersionStepVO) => {
  switch (step.stepCode) {
    case 'SYNC_CODE':
      return t('projectDetail.steps.pull')
    case 'COMPILE_CHECK':
      return t('projectDetail.steps.compile')
    case 'SQL_SCAN':
      return t('projectDetail.steps.scan')
    case 'RULE_AUDIT':
      return t('projectDetail.steps.audit')
    case 'GENERATE_REPORT':
      return t('projectDetail.steps.report')
    default:
      return step.stepName || '-'
  }
}

const getProjectStepStatus = (status?: number): ExecutionStepStatus => {
  switch (status) {
    case 1:
      return 'running'
    case 2:
      return 'success'
    case 3:
      return 'failed'
    default:
      return 'pending'
  }
}

const loadProjectStatus = async (silent = false) => {
  if (statusRefreshing) {
    return
  }
  statusRefreshing = true
  if (!silent) {
    statusLoading.value = true
  }
  try {
    const data = await getProjectStatus(projectId)
    const steps = data.steps || []
    projectSteps.value = steps.length > 0
      ? [...steps]
        .sort((a, b) => a.stepOrder - b.stepOrder)
        .map(step => ({
          key: step.stepCode || String(step.stepType),
          label: getProjectStepLabel(step),
          status: getProjectStepStatus(step.status)
        }))
      : createDefaultProjectSteps()
    executionRunning.value = steps.some(step => step.status === 1)
  } catch {
    projectSteps.value = createDefaultProjectSteps()
  } finally {
    if (!silent) {
      statusLoading.value = false
    }
    statusRefreshing = false
  }
}

// 版本列表操作按钮处理函数
const loadProjectReport = async (versionId = getRouteVersionId()) => {
  reportLoading.value = true
  try {
    const report = await getProjectReport(projectId, versionId)
    projectReport.value = report || null
  } catch {
    projectReport.value = null
  } finally {
    reportLoading.value = false
  }
}

const handleDownloadReport = async () => {
  if (!projectReport.value) {
    return
  }
  try {
    const blob = await downloadProjectReportPdf(projectId, projectReport.value.versionId)
    const projectName = (project.value?.name || 'project').replace(/[\\/:*?"<>|]/g, '_')
    const fileName = `${projectName}-${formatReportFileTime(projectReport.value.generatedTime)}.pdf`
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
  } catch {
    ElMessage.error(t('projectDetail.messages.downloadReportError'))
  }
}

const loadCredentials = async () => {
  try {
    availableCredentials.value = await getProjectCredentials()
  } catch {
    availableCredentials.value = []
  }
}

const showCredentialDialog = async () => {
  await loadCredentials()
  selectedCredentialId.value = project.value?.credentialId ?? -1
  credentialDialogVisible.value = true
}

const handleSaveCredential = async () => {
  savingCredential.value = true
  try {
    const credId = selectedCredentialId.value === -1 ? null : selectedCredentialId.value
    project.value = await updateProjectCredential(projectId, credId)
    credentialDialogVisible.value = false
    ElMessage.success(t('projectDetail.messages.credentialUpdateSuccess'))
  } catch {
    ElMessage.error(t('projectDetail.messages.credentialUpdateError'))
  } finally {
    savingCredential.value = false
  }
}

const checkAdmin = async () => {
  if (userStore.userId === 0) return
  try {
    isProjectAdmin.value = await checkIsProjectAdmin(projectId, userStore.userId)
  } catch {
    isProjectAdmin.value = false
  }
}

const formatDate = (date?: string) => {
  if (!date) return '-'
  return new Date(date).toLocaleString(locale.value)
}

const getStatusTagType = (status?: number) => {
  switch (status) {
    case 0: return 'info'
    case 1: return 'warning'
    case 2: return 'success'
    case 3: return 'danger'
    default: return 'info'
  }
}

const startStatusRefresh = () => {
  if (statusRefreshTimer) {
    return
  }
  statusRefreshTimer = setInterval(() => {
    loadProjectStatus(true)
  }, STATUS_REFRESH_INTERVAL)
}

const stopStatusRefresh = () => {
  if (!statusRefreshTimer) {
    return
  }
  clearInterval(statusRefreshTimer)
  statusRefreshTimer = null
}

watch(() => [route.query.tab, route.query.versionId], () => {
  const nextTab = getRouteTab()
  const versionId = getRouteVersionId()
  activeTab.value = nextTab
  if (nextTab === 'sql') {
    loadProjectReport(versionId)
  } else if (nextTab === 'projectSql') {
    loadProjectSql(versionId)
  } else if (nextTab === 'whitelist') {
    loadWhitelist()
  }
})

onMounted(() => {
  loadProject()
  checkAdmin()
  initCharts()
  loadProjectOverview()
  loadRules()
  startStatusRefresh()
  // 主题切换时重绘图表适配深浅色
  onThemeChange(() => {
    updateOverviewCharts()
  })
})

onUnmounted(() => {
  stopStatusRefresh()
  pieChart?.dispose()
  lineChart?.dispose()
  loseScoreChart?.dispose()
})

// 初始化图表
const initCharts = () => {
  nextTick(() => {
    if (pieChartRef.value) {
      pieChart = echarts.init(pieChartRef.value)
      updatePieChart()
    }
    if (lineChartRef.value) {
      lineChart = echarts.init(lineChartRef.value)
      updateLineChart()
    }
    if (loseScoreChartRef.value) {
      loseScoreChart = echarts.init(loseScoreChartRef.value)
      updateLoseScoreChart()
    }
  })
}

// 更新环形图
const updatePieChart = () => {
  if (!pieChart) return
  const option: echarts.EChartsOption = {
    tooltip: {
      trigger: 'item',
      formatter: '{b}: {c} ({d}%)',
      extraCssText: 'max-width: 200px; word-wrap: break-word; white-space: normal;'
    },
    legend: {
      orient: 'horizontal',
      bottom: 0,
      left: 'center',
      itemWidth: 10,
      itemHeight: 10,
      textStyle: {
        color: chartNeutral.label,
        fontSize: 12
      },
      itemGap: 16
    },
    color: [...seriesPalette],
    series: [
      {
        name: t('projectDetail.chart.sqlRatio'),
        type: 'pie',
        radius: ['48%', '76%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: false,
        itemStyle: {
          borderRadius: 6,
          borderColor: '#fff',
          borderWidth: 2
        },
        label: {
          show: false
        },
        emphasis: {
          label: {
            show: true,
            fontSize: 14,
            fontWeight: 'bold'
          }
        },
        data: sqlTypeStats.value
      }
    ]
  }
  pieChart.setOption(option)
}

// 更新折线图
const updateLineChart = () => {
  if (!lineChart) return
  const option: echarts.EChartsOption = {
    tooltip: {
      trigger: 'axis'
    },
    legend: {
      bottom: 0,
      itemWidth: 14,
      itemHeight: 8,
      textStyle: {
        color: chartNeutral.label,
        fontSize: 12
      },
      itemGap: 16
    },
    color: [chartColors.primary, chartColors.danger, chartColors.warning, seriesPalette[4]],
    grid: {
      left: 60,
      right: 30,
      top: 20,
      bottom: 70
    },
    xAxis: {
      type: 'category',
      data: trendData.value.versions,
      axisLine: {
        lineStyle: { color: chartNeutral.axisLine }
      },
      axisLabel: {
        color: chartNeutral.label,
        fontSize: 11,
        rotate: 0,
        formatter: (value: string) => value.replace(/\s+/, '\n')
      }
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      splitLine: { lineStyle: { color: chartNeutral.splitLine } },
      axisLabel: { color: chartNeutral.label, fontSize: 11 }
    },
    series: [
      {
        name: t('projectDetail.chart.totalSql'),
        type: 'line',
        smooth: true,
        data: trendData.value.total,
        lineStyle: { width: 2 },
        itemStyle: { color: chartColors.primary }
      },
      {
        name: t('severity.critical'),
        type: 'line',
        smooth: true,
        data: trendData.value.critical,
        lineStyle: { width: 2 },
        itemStyle: { color: chartColors.danger }
      },
      {
        name: t('severity.high'),
        type: 'line',
        smooth: true,
        data: trendData.value.high,
        lineStyle: { width: 2 },
        itemStyle: { color: chartColors.warning }
      },
      {
        name: t('projectDetail.chart.warningSql'),
        type: 'line',
        smooth: true,
        data: trendData.value.warning,
        lineStyle: { width: 2 },
        itemStyle: { color: seriesPalette[4] }
      }
    ]
  }
  lineChart.setOption(option)
}

// 更新失分饼图
const updateLoseScoreChart = () => {
  if (!loseScoreChart) return
  const option: echarts.EChartsOption = {
    tooltip: {
      trigger: 'item',
      formatter: `{b}: {c}${t('projectDetail.chart.pointUnit')} ({d}%)`
    },
    legend: {
      orient: 'horizontal',
      bottom: 0,
      left: 'center',
      itemWidth: 10,
      itemHeight: 10,
      textStyle: {
        color: chartNeutral.label,
        fontSize: 12
      },
      itemGap: 16
    },
    color: [chartColors.warning, chartColors.danger, seriesPalette[4], '#ec4899'],
    series: [
      {
        name: t('projectDetail.chart.loseScore'),
        type: 'pie',
        radius: ['48%', '76%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: false,
        itemStyle: {
          borderRadius: 4,
          borderColor: '#fff',
          borderWidth: 2
        },
        label: {
          show: false
        },
        emphasis: {
          label: {
            show: true,
            fontSize: 12,
            fontWeight: 'bold'
          }
        },
        data: [
          { value: loseScoreData.value.securityDeduction, name: t('projectDetail.chart.securityDeduction') },
          { value: loseScoreData.value.performanceDeduction, name: t('projectDetail.chart.performanceDeduction') },
          { value: loseScoreData.value.qualityDeduction, name: t('projectDetail.chart.qualityDeduction') },
          { value: loseScoreData.value.otherDeduction, name: t('projectDetail.chart.otherDeduction') }
        ]
      }
    ]
  }
  loseScoreChart.setOption(option)
}

const toNumber = (value?: number | string | null) => {
  const numeric = Number(value ?? 0)
  return Number.isFinite(numeric) ? numeric : 0
}

const toPercent = (value?: number | string | null) => {
  return Math.max(0, Math.min(100, Number(toNumber(value).toFixed(2))))
}

const getSqlTypeLabel = (sqlType?: string) => {
  switch ((sqlType || '').toUpperCase()) {
    case 'SELECT':
      return t('projectDetail.chart.selectSql')
    case 'INSERT':
      return t('projectDetail.chart.insertSql')
    case 'UPDATE':
      return t('projectDetail.chart.updateSql')
    case 'DELETE':
      return t('projectDetail.chart.deleteSql')
    default:
      return sqlType || t('projectDetail.chart.otherSql')
  }
}

const formatTrendVersionLabel = (item: { operationTime?: string, ref?: string, commitId?: string, versionId?: number }) => {
  if (item.operationTime) {
    const parsed = new Date(item.operationTime)
    if (!Number.isNaN(parsed.getTime())) {
      return parsed.toLocaleString(locale.value)
    }
    return item.operationTime
  }
  return item.versionId ? `#${item.versionId}` : '-'
}

const updateOverviewCharts = async () => {
  await nextTick()
  // 容器布局可能晚于 echarts.init（样式/加载态），先同步尺寸再重绘，避免按初始小尺寸裁剪
  pieChart?.resize()
  lineChart?.resize()
  loseScoreChart?.resize()
  updatePieChart()
  updateLineChart()
  updateLoseScoreChart()
}

const loadProjectOverview = async () => {
  overviewLoading.value = true
  try {
    const [sqlOverview, issueCategories, rectificationRate, issueSqlRate, scoreOverview, sqlQualityTrend] = await Promise.all([
      getProjectOverviewSql(projectId),
      getProjectOverviewIssueCategories(projectId),
      getProjectOverviewIssueRectificationRate(projectId),
      getProjectOverviewIssueSqlRate(projectId),
      getProjectOverviewScore(projectId),
      getProjectOverviewSqlQualityTrend(projectId)
    ])

    const issueRate = toPercent(issueSqlRate.issueSqlRate)
    const healthRate = toPercent(100 - issueRate)
    const issueRateTrend = issueSqlRate.trend === 'UP'
      ? toPercent(issueSqlRate.increaseRate)
      : issueSqlRate.trend === 'DOWN'
        ? -toPercent(issueSqlRate.decreaseRate)
        : 0
    const healthTrend = issueSqlRate.trend === 'DOWN'
      ? toPercent(issueSqlRate.decreaseRate)
      : issueSqlRate.trend === 'UP'
        ? -toPercent(issueSqlRate.increaseRate)
        : 0
    const fixTrend = rectificationRate.trend === 'DOWN'
      ? toPercent(rectificationRate.decreaseRate)
      : rectificationRate.trend === 'UP'
        ? -toPercent(rectificationRate.increaseRate)
        : 0

    projectScore.value = {
      score: scoreOverview.score ?? 0,
      rating: scoreOverview.rating || 'D',
      complianceRate: healthRate,
      complianceTrend: healthTrend,
      slowSqlRate: issueRate,
      slowSqlTrend: issueRateTrend,
      fixRate: toPercent(rectificationRate.rectificationRate),
      fixTrend
    }

    loseScoreData.value = {
      securityDeduction: (issueCategories.securitySqlCount || 0) * 4,
      performanceDeduction: (issueCategories.performanceSqlCount || 0) * 3,
      qualityDeduction: (issueCategories.qualitySqlCount || 0) * 2,
      otherDeduction: issueCategories.otherSqlCount || 0
    }

    sqlTypeStats.value = (sqlOverview.typeCounts || []).map(item => ({
      name: getSqlTypeLabel(item.sqlType),
      value: item.count || 0
    }))

    const trendItems = sqlQualityTrend.items || []
    trendData.value = {
      versions: trendItems.map(formatTrendVersionLabel),
      total: trendItems.map(item => item.totalSqlCount || 0),
      critical: trendItems.map(item => item.criticalSqlCount || 0),
      high: trendItems.map(item => item.highSqlCount || 0),
      warning: trendItems.map(item => item.warningSqlCount || 0)
    }

    await updateOverviewCharts()
  } catch {
    ElMessage.error(t('dashboard.loadError'))
  } finally {
    overviewLoading.value = false
  }
}
</script>

<style lang="scss" scoped>
.project-detail {
  padding: 0px;
}

.top-menu {
  background: var(--jsp-bg-card);
  border-bottom: 1px solid var(--jsp-border-color);
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-right: 16px;

  .main-tabs {
    :deep(.el-tabs__header) {
      margin: 0;
      padding: 0 16px;
    }

    :deep(.el-tabs__nav-wrap::after) {
      display: none;
    }

    :deep(.el-tabs__item) {
      font-size: 14px;
      height: 48px;
      line-height: 48px;
    }
  }

  .top-actions {
    display: flex;
    align-items: center;
    gap: 8px;

    .top-action-btn {
      padding: 8px;
      color: var(--jsp-text-secondary);

      .el-icon {
        font-size: 18px;
      }

      &:hover {
        color: $primary-color;
      }
    }
  }
}

.overview-layout {
  display: flex;
  gap: 16px;
}

.left-panel {
  flex: 1;
  min-width: 0;

  .detail-card {
    margin-bottom: 16px;
  }

  .status-card {
    margin-bottom: 16px;

    :deep(.el-card__body) {
      padding: 20px 16px;
    }

    .status-card-content {
      display: flex;
      align-items: flex-start;
      gap: 16px;
      min-width: 0;
    }

    .execution-toggle {
      width: 36px;
      height: 36px;
      border: 0;
      border-radius: 50%;
      background: $success-lighter;
      color: $success-color;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
      cursor: pointer;
      transition: all 0.18s ease;

      .el-icon {
        font-size: 18px;
      }

      &:hover {
        background: $success-light;
        color: $success-color;
      }

      &:disabled {
        cursor: not-allowed;
        opacity: 0.6;
      }

      &.is-running {
        background: $danger-lighter;
        color: $danger-color;

        &:hover {
          background: $danger-light;
          color: $danger-color;
        }
      }
    }

    :deep(.execution-steps) {
      flex: 1;
      min-width: 0;
    }
  }

  .version-list-card {
    :deep(.el-card__body) {
      padding: 16px;
    }

    .version-load-more {
      display: flex;
      justify-content: center;
      gap: 8px;
      padding-top: 12px;
    }

    .version-list-header {
      display: flex;
      align-items: center;
      justify-content: space-between;

      .refresh-btn {
        padding: 4px;
        color: var(--jsp-text-secondary);

        &:hover {
          color: $primary-color;
        }

        .el-icon {
          font-size: 16px;
        }
      }
    }

    .version-actions {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 12px;
      white-space: nowrap;
    }

    .version-action-text {
      color: inherit;
      cursor: pointer;
      font: inherit;
      font-weight: inherit;
      line-height: inherit;
    }
  }
}

.right-panel {
  width: 860px;
  flex-shrink: 0;

  .metrics-cards {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 10px;
    margin-bottom: 16px;

    .metric-card {
      .metric-content {
        display: flex;
        flex-direction: column;
        align-items: center;
        padding: 4px 0;

        .metric-label {
          font-size: 12px;
          color: var(--jsp-text-secondary);
          margin-bottom: 4px;
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
          max-width: 100%;
        }

        .metric-value {
          font-size: 20px;
          font-weight: 700;
          color: var(--jsp-text-primary);
          line-height: 1.2;

          .metric-unit {
            font-size: 12px;
            font-weight: 500;
            margin-left: 2px;
          }

          &.warning {
            color: $warning-color;
          }

          &.success {
            color: $success-color;
          }
        }

        .metric-trend {
          display: flex;
          align-items: center;
          gap: 2px;
          font-size: 12px;
          margin-top: 2px;

          .el-icon {
            font-size: 12px;
          }

          &.up {
            color: $success-color;
          }

          &.down {
            color: $danger-color;
          }
        }
      }

      :deep(.el-card__body) {
        padding: 8px 10px;
      }
    }

    .rating-card {
      .metric-content {
        position: relative;

        .score-value {
          font-size: 24px;
        }

        .rating-badge {
          position: absolute;
          top: -2px;
          right: -2px;
          width: 22px;
          height: 22px;
          border-radius: 5px;
          display: flex;
          align-items: center;
          justify-content: center;
          font-size: 12px;
          font-weight: 700;
          color: $text-inverse;

          &.rating-s {
            background: linear-gradient(135deg, $success-color, $success-color);
          }

          &.rating-a {
            background: linear-gradient(135deg, $success-color, $success-color);
          }

          &.rating-b {
            background: linear-gradient(135deg, $primary-color, $primary-hover);
          }

          &.rating-c {
            background: linear-gradient(135deg, $warning-color, $warning-color);
          }

          &.rating-d {
            background: linear-gradient(135deg, $danger-color, $danger-color);
          }
        }
      }
    }
  }

  .charts-row {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 12px;
    margin-bottom: 16px;

    &.single-chart-row,
    &.trend-row {
      grid-template-columns: 1fr;
    }

    &:last-child {
      margin-bottom: 0;
    }
  }

  .chart-card {
    margin-bottom: 0;

    // SQL 占比与失分构成同行，等高避免 grid 拉齐后矮卡留白
    &.lose-card,
    &.pie-card {
      .chart {
        height: 280px;
      }
    }

    &.trend-card {
      .chart {
        height: 320px;
      }
    }

    :deep(.el-card__body) {
      padding: 16px;
    }

    .chart {
      width: 100%;
      height: 200px;
    }
  }
}

.content-page {
  .el-card {
    min-height: 400px;

    :deep(.el-card__body) {
      min-height: 300px;
      padding: 16px;
    }
  }

  .rule-filters {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 12px;

    :deep(.el-input) {
      width: 220px;
    }

    :deep(.el-icon) {
      color: $text-placeholder;
    }
  }

  .rule-filter-select {
    width: 150px;
  }

  .rules-table {
    width: 100%;
  }

  .rules-pagination,
  .whitelist-pagination {
    display: flex;
    justify-content: flex-end;
    padding-top: 16px;
  }

  @media (max-width: 900px) {
    // 窄屏下 4 列指标卡退回 2 列，避免过度压缩
    .metrics-cards {
      grid-template-columns: repeat(2, 1fr);
    }

    .rule-filters {
      align-items: stretch;
      flex-direction: column;

      :deep(.el-input),
      .rule-filter-select,
      .el-button {
        width: 100%;
      }
    }
  }
}

.whitelist-page {
  .whitelist-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 14px;
  }

  .whitelist-summary {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 10px;
    min-width: 0;
  }

  .whitelist-count {
    color: $text-placeholder;
    font-size: 13px;
  }

  .whitelist-table {
    width: 100%;
  }
}

.project-sql-page {
  .project-sql-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 14px;

    // 纯图标刷新按钮，悬浮 tooltip 提示
    .toolbar-refresh-btn {
      padding: 4px;
      color: var(--jsp-text-secondary);

      &:hover {
        color: $primary-color;
      }

      .el-icon {
        font-size: 16px;
      }
    }
  }

  .project-sql-summary {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px;
    min-width: 0;
    color: var(--jsp-text-secondary);
    font-size: 13px;

    strong {
      color: var(--jsp-text-primary);
      font-weight: 600;
      word-break: break-all;
    }
  }

  .project-sql-count {
    color: $text-placeholder;
  }

  .project-sql-table {
    width: 100%;

    // 行双击进入构造详情，手型提示可交互
    :deep(.clickable-row) {
      cursor: pointer;
    }
  }

  // 问题徽标：待处理>0 红色，全部处理完灰色
  .issue-badge {
    :deep(.el-badge__content) {
      transform: translate(80%, -30%);
    }
  }

  .issue-none {
    color: $text-placeholder;
  }

  // 问题弹窗：语句副标题与流转面板
  .issue-dialog-subtitle {
    margin-bottom: 10px;
    font-size: 12px;
    color: var(--jsp-text-secondary);
    word-break: break-all;
  }

  .issue-transit-panel {
    margin-top: 14px;
    padding: 12px 14px;
    border: 1px solid var(--jsp-border-color, #e4e7ed);
    border-radius: 8px;
    background: var(--jsp-bg-page);
  }

  // 版本差异弹窗
  .diff-body {
    min-height: 160px;
  }

  .diff-summary {
    display: flex;
    gap: 16px;
    margin-bottom: 12px;

    .diff-summary-item {
      font-size: 14px;
      font-weight: 600;
    }

    .diff-added { color: var(--el-color-success); }
    .diff-modified { color: var(--el-color-warning); }
    .diff-deleted { color: var(--el-color-danger); }
  }

  .diff-risk {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 12px;

    .diff-risk-label {
      font-size: 13px;
      font-weight: 600;
      color: var(--jsp-text-secondary);
    }
  }

  // statementId 缩短展示：默认只显示"类.方法(参数)"，悬浮完整、单击复制
  // 可单击复制的单元格：单击复制完整内容（非显示的缩短文本），悬浮 tooltip 展示全文
  .copyable-cell {
    display: inline-block;
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    vertical-align: middle;
    cursor: copy;
    color: var(--jsp-text-primary);

    // SQL 列保留换行语义的单行省略展示
    &--sql {
      font-family: 'Monaco', 'Menlo', monospace;
      font-size: 13px;
    }
  }
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;

  .project-name {
    flex: 1;
    font-size: 18px;
    font-weight: 600;
    color: var(--jsp-text-primary);
  }

  .pull-btn {
    padding: 4px;
    color: var(--jsp-text-secondary);

    .el-icon {
      font-size: 18px;
    }

    &:hover {
      color: $primary-color;
    }
  }

  .section-title {
    font-size: 16px;
    font-weight: 600;
    color: var(--jsp-text-primary);
  }

}

// 项目信息 descriptions（el-descriptions 与个人信息页同形态）
.project-desc {
  :deep(.el-descriptions__label) {
    width: 110px;
    font-size: 13px;
    color: var(--jsp-text-secondary);
  }

  :deep(.el-descriptions__content) {
    font-size: 14px;
    color: var(--jsp-text-primary);
  }
}

.project-title-group {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.header-edit-btn {
  padding: 4px;
  color: var(--jsp-text-secondary);

  .el-icon {
    font-size: 16px;
  }

  &:hover {
    color: $primary-color;
  }
}

.git-url-row {
  display: flex;
  align-items: center;
  gap: 8px;

  .info-value.git-url {
    font-family: 'Monaco', 'Menlo', monospace;
    font-size: 13px;
    color: $primary-color;
    background: $border-light;
    padding: 4px 10px;
    border-radius: 6px;
    word-break: break-all;
  }

  .copy-btn {
    flex-shrink: 0;
    font-size: 15px;
    color: var(--jsp-text-secondary);
    cursor: pointer;

    &:hover {
      color: $primary-color;
    }
  }
}

// 凭证展示（el-descriptions-item 直接子元素，不嵌套容器）
.credential-name {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: $primary-color;
  cursor: pointer;

  &:hover {
    color: $primary-hover;

    .credential-action {
      text-decoration: underline;
    }
  }

  .el-icon {
    font-size: 13px;
    color: var(--jsp-text-secondary);
  }
}

// 未设置凭证：虚线引导按钮样式，强调可点击配置
.no-credential {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 10px;
  border: 1px dashed var(--jsp-border-color);
  border-radius: 4px;
  color: var(--jsp-text-secondary);
  font-size: 13px;
  cursor: pointer;

  &:hover {
    color: $primary-color;
    border-color: $primary-color;
  }
}

.credential-action {
  color: $text-placeholder;
  font-size: 13px;
  margin-left: 2px;
}

.credential-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 12px;

  // 图标与名称同行居中对齐（与信息卡的 credential-name 同构）
  .credential-option-name {
    display: inline-flex;
    align-items: center;
    gap: 4px;
  }

  .credential-option-detail {
    color: $text-placeholder;
    font-size: 12px;
  }
}

.globe-icon {
  width: 13px;
  height: 13px;
  flex-shrink: 0;
  // 该 svg 的绘制重心偏上，下移 7px 与文字视觉中线对齐
  margin-top: 7px;
  color: var(--jsp-text-secondary);
}
</style>
