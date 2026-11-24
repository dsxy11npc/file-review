<template>
  <div class="result-page">
    <div v-if="loading" class="page-state"><el-icon class="is-loading"><Loading /></el-icon>正在加载审查结果</div>
    <div v-else-if="error" class="page-state"><h2>无法打开审查结果</h2><p>{{ error }}</p><el-button @click="router.push('/review')">返回列表</el-button></div>
    <template v-else-if="record">
      <header class="toolbar">
        <div>
          <el-button :icon="ArrowLeft" @click="router.push('/review')">返回列表</el-button>
          <span class="document-name">{{ record.documentName }}</span>
          <el-tag size="small">{{ reviewTypeLabel }}</el-tag>
        </div>
        <el-button type="primary" :icon="Download" @click="exportReport">导出审查报告</el-button>
      </header>

      <section class="summary-bar">
        <div><strong>{{ record.clauseCount }}</strong><span>待审条款</span></div>
        <div><strong>{{ record.summary?.comparedStandardCount ?? record.standardIds?.length ?? 0 }}</strong><span>实际对照制度</span></div>
        <div><strong>{{ issues.length }}</strong><span>疑似问题</span></div>
        <div><strong>{{ pendingCount }}</strong><span>待复核</span></div>
        <p>基准日期：{{ record.baselineDate }} · 引擎：{{ record.engineVersion }} · 结果仅供人工复核</p>
      </section>

      <main class="workbench">
        <section class="source-panel">
          <div class="panel-title">待审制度原文</div>
          <div class="source-content">{{ record.content }}</div>
        </section>

        <section class="issues-panel">
          <div class="panel-title issue-title">
            <span>疑似问题（{{ filteredIssues.length }}）</span>
            <div class="issue-filters">
              <el-select v-model="statusFilter" size="small" clearable placeholder="全部状态" style="width: 115px">
                <el-option label="待复核" value="pending" />
                <el-option label="已确认" value="resolved" />
                <el-option label="已排除" value="ignored" />
              </el-select>
              <el-select v-model="scopeFilter" size="small" clearable placeholder="全部范围" style="width: 140px">
              <el-option label="上级文件" value="superior" />
              <el-option label="内部制度" value="internal" />
              <el-option label="替换后体系" value="remaining_internal" />
              <el-option label="旧版本对照" value="old_version" />
              </el-select>
            </div>
          </div>
          <div v-if="filteredIssues.length === 0" class="empty-issues">
            <el-empty description="当前范围内未发现规则可识别的疑似冲突" :image-size="80" />
            <p>这不代表制度已经通过合法性审查，请结合业务范围人工检查。</p>
          </div>
          <button
            v-for="issue in filteredIssues"
            :key="issue.id"
            class="issue-row"
            :class="{ active: activeIssue?.id === issue.id, handled: issue.status !== 'pending' }"
            @click="selectIssue(issue)"
          >
            <span class="severity" :class="issue.severity">{{ severityLabel(issue.severity) }}</span>
            <span><strong>{{ issue.title }}</strong><small>{{ issue.sourceClauseNumber }} · {{ scopeLabel(issue.reviewScope) }}</small></span>
            <el-tag size="small" :type="statusType(issue.status)">{{ statusLabel(issue.status) }}</el-tag>
          </button>
        </section>
      </main>

      <section v-if="activeIssue" class="detail-panel">
        <div class="detail-heading">
          <div><h3>{{ activeIssue.title }}</h3><p>{{ conflictTypeLabel(activeIssue.conflictType) }} · 系统匹配置信度 {{ confidenceLabel(activeIssue.confidence) }}</p></div>
          <el-button link @click="activeIssue = null">收起</el-button>
        </div>
        <div class="evidence-grid">
          <article><h4>待审制度 · {{ activeIssue.sourceClauseNumber }}</h4><p>{{ activeIssue.originalSnippet }}</p></article>
          <article><h4>{{ activeIssue.standardRef }} · {{ activeIssue.standardClause }}</h4><p>{{ activeIssue.standardText }}</p></article>
        </div>
        <div class="analysis"><strong>分析理由</strong><p>{{ activeIssue.description }}</p></div>
        <div class="suggestion"><strong>修改建议</strong><p>{{ activeIssue.suggestionText }}</p></div>
        <el-input v-model="reviewComment" type="textarea" :rows="2" maxlength="2000" show-word-limit placeholder="填写人工复核意见（可选）" />
        <div class="decision-actions">
          <el-button type="success" :loading="saving" @click="decide('resolved')">确认问题</el-button>
          <el-button :loading="saving" @click="decide('ignored')">排除问题</el-button>
          <el-button text :loading="saving" @click="decide('pending')">恢复待复核</el-button>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Download, Loading } from '@element-plus/icons-vue'
import {
  decideConflictIssue,
  getConflictReview,
  type ConflictIssue,
  type ConflictReviewDetail,
  type ReviewDecision,
} from '../service/conflictReview'
import { downloadFile } from '../utils/document'

const route = useRoute()
const router = useRouter()
const record = ref<ConflictReviewDetail | null>(null)
const issues = ref<ConflictIssue[]>([])
const activeIssue = ref<ConflictIssue | null>(null)
const reviewComment = ref('')
const scopeFilter = ref('')
const statusFilter = ref('')
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const pendingCount = computed(() => issues.value.filter(issue => issue.status === 'pending').length)
const filteredIssues = computed(() => issues.value.filter(issue =>
  (!scopeFilter.value || issue.reviewScope === scopeFilter.value)
  && (!statusFilter.value || issue.status === statusFilter.value)
))
const reviewTypeLabel = computed(() => ({ superior: '上级符合性', internal: '内部一致性', replacement: '新旧替换', existing: '存量复核' }[record.value?.reviewType || ''] || '制度审查'))

onMounted(async () => {
  try {
    record.value = await getConflictReview(route.params.id as string)
    issues.value = record.value.issues
    if (issues.value.length) selectIssue(issues.value[0])
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载失败'
  } finally {
    loading.value = false
  }
})

function selectIssue(issue: ConflictIssue) {
  activeIssue.value = issue
  reviewComment.value = issue.reviewComment || ''
}

async function decide(status: ReviewDecision) {
  if (!record.value || !activeIssue.value) return
  saving.value = true
  try {
    const updated = await decideConflictIssue(record.value.id, activeIssue.value.id, status, reviewComment.value)
    const index = issues.value.findIndex(issue => issue.id === updated.id)
    if (index >= 0) issues.value[index] = updated
    activeIssue.value = updated
    ElMessage.success('人工复核结论已保存')
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '保存失败')
  } finally {
    saving.value = false
  }
}

function severityLabel(value: string) {
  return { critical: '重点', warning: '警告', suggestion: '提示' }[value] || value
}
function statusLabel(value: string) {
  return { pending: '待复核', resolved: '已确认', ignored: '已排除' }[value] || value
}
function statusType(value: string) {
  return value === 'resolved' ? 'success' : value === 'ignored' ? 'info' : 'warning'
}
function scopeLabel(value: string) {
  return { superior: '上级文件', internal: '内部制度', remaining_internal: '替换后体系', old_version: '旧版本对照' }[value] || value
}
function conflictTypeLabel(value: string) {
  return { numeric_standard: '数量或时限标准', approval_authority: '审批权限', invalid_reference: '失效文件引用' }[value] || value
}
function confidenceLabel(value?: number) {
  return value == null ? '未提供' : `${Math.round(value * 100)}%（仅表示规则匹配程度）`
}

function exportReport() {
  if (!record.value) return
  const confirmed = issues.value.filter(issue => issue.status === 'resolved')
  const pending = issues.value.filter(issue => issue.status === 'pending')
  const lines = [
    '# 规章制度冲突审查报告', '',
    `- **审查文件**：${record.value.documentName}`,
    `- **审查类型**：${reviewTypeLabel.value}`,
    `- **审查基准日期**：${record.value.baselineDate}`,
    `- **待审条款数**：${record.value.clauseCount}`,
    `- **审查引擎**：${record.value.engineVersion}`,
    `- **人工确认问题**：${confirmed.length}`,
    `- **尚未复核问题**：${pending.length}`, '',
    '> 本报告由本地规则辅助生成，不构成最终合法性结论。', '',
    '## 人工确认的问题', '',
  ]
  appendIssues(lines, confirmed)
  lines.push('## 待复核的疑似问题', '')
  appendIssues(lines, pending)
  lines.push('## 已排除的问题', '')
  appendIssues(lines, issues.value.filter(issue => issue.status === 'ignored'))
  downloadFile(lines.join('\n'), `制度冲突审查报告_${record.value.baselineDate}.md`, 'text/markdown')
}

function appendIssues(lines: string[], items: ConflictIssue[]) {
  if (!items.length) { lines.push('无。', ''); return }
  items.forEach((issue, index) => {
    lines.push(`### ${index + 1}. ${issue.title}`, '')
    lines.push(`- **待审条款**：${issue.sourceClauseNumber}`)
    lines.push(`- **对照依据**：${issue.standardRef} ${issue.standardClause}`)
    lines.push(`- **审查范围**：${scopeLabel(issue.reviewScope)}`)
    lines.push(`- **分析**：${issue.description}`)
    lines.push(`- **建议**：${issue.suggestionText}`)
    if (issue.reviewComment) lines.push(`- **人工意见**：${issue.reviewComment}`)
    lines.push('', '**待审原文**', '', `> ${issue.originalSnippet}`, '', '**对照原文**', '', `> ${issue.standardText}`, '')
  })
}
</script>

<style scoped>
.result-page { min-height: 100vh; background: #f5f7fa; padding-bottom: 24px; }
.page-state { min-height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 14px; color: #6b7280; }
.toolbar { display: flex; justify-content: space-between; align-items: center; padding: 12px 24px; background: #fff; border-bottom: 1px solid #e5e7eb; }
.toolbar > div { display: flex; align-items: center; gap: 14px; }
.document-name { font-weight: 600; }
.summary-bar { display: flex; align-items: center; gap: 36px; padding: 14px 28px; background: #eff6ff; border-bottom: 1px solid #dbeafe; }
.summary-bar div { display: flex; flex-direction: column; }
.summary-bar strong { font-size: 20px; color: #1d4ed8; }
.summary-bar span, .summary-bar p { font-size: 12px; color: #64748b; }
.summary-bar p { margin-left: auto; }
.workbench { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(360px, .85fr); gap: 16px; margin: 18px 24px; min-height: 440px; }
.source-panel, .issues-panel, .detail-panel { background: #fff; border: 1px solid #e5e7eb; border-radius: 10px; overflow: hidden; }
.panel-title { padding: 13px 16px; border-bottom: 1px solid #e5e7eb; font-weight: 600; font-size: 14px; }
.source-content { padding: 24px; white-space: pre-wrap; line-height: 1.9; color: #374151; font-size: 14px; }
.issue-title { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.issue-filters { display: flex; gap: 8px; }
.issue-row { display: flex; align-items: flex-start; gap: 10px; width: 100%; padding: 13px 14px; background: #fff; border: 0; border-bottom: 1px solid #f0f2f5; text-align: left; cursor: pointer; }
.issue-row:hover, .issue-row.active { background: #eff6ff; }
.issue-row.handled { opacity: .65; }
.issue-row > span:nth-child(2) { flex: 1; display: flex; flex-direction: column; gap: 5px; }
.issue-row strong { font-size: 13px; }
.issue-row small { color: #6b7280; }
.severity { padding: 2px 6px; border-radius: 4px; font-size: 11px; white-space: nowrap; }
.severity.critical { background: #fef2f2; color: #dc2626; }
.severity.warning { background: #fffbeb; color: #d97706; }
.severity.suggestion { background: #eff6ff; color: #2563eb; }
.empty-issues { padding: 24px; text-align: center; }
.empty-issues p { color: #6b7280; font-size: 12px; }
.detail-panel { margin: 0 24px; padding: 20px; }
.detail-heading { display: flex; justify-content: space-between; align-items: flex-start; }
.detail-heading h3 { margin: 0 0 4px; }
.detail-heading p { margin: 0; color: #6b7280; font-size: 12px; }
.evidence-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin: 16px 0; }
.evidence-grid article { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 14px; }
.evidence-grid h4 { margin: 0 0 8px; font-size: 13px; color: #475569; }
.evidence-grid p, .analysis p, .suggestion p { margin: 5px 0 0; line-height: 1.7; white-space: pre-wrap; }
.analysis, .suggestion { margin: 12px 0; font-size: 13px; }
.suggestion { color: #1e40af; }
.decision-actions { display: flex; margin-top: 12px; }
@media (max-width: 900px) { .workbench, .evidence-grid { grid-template-columns: 1fr; } .summary-bar { flex-wrap: wrap; gap: 18px; } .summary-bar p { width: 100%; margin-left: 0; } }
</style>
