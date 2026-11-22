import { getAuthorization } from '@/utils/auth'

export type ConflictReviewType = 'superior' | 'internal' | 'replacement' | 'existing'
export type ReviewDecision = 'pending' | 'resolved' | 'ignored'

export interface ConflictIssue {
  id: string
  recordId: string
  severity: 'critical' | 'warning' | 'suggestion'
  dimension: string
  conflictType: string
  reviewScope: 'superior' | 'internal' | 'remaining_internal' | 'old_version'
  sourceClauseNumber: string
  standardId: string
  clauseId: string
  title: string
  description: string
  originalSnippet: string
  standardRef: string
  standardClause: string
  standardText: string
  suggestionText: string
  confidence?: number
  reviewComment?: string
  status: ReviewDecision
}

export interface ConflictReviewDetail {
  id: string
  documentName: string
  content: string
  reviewType: ConflictReviewType
  baselineDate: string
  clauseCount: number
  engineVersion: string
  status: string
  standardIds: string[]
  replacedStandardIds: string[]
  summary: {
    total?: number
    clauseCount?: number
    bySeverity?: Record<string, number>
    comparedStandardCount?: number
    excludedReplacedCount?: number
  }
  issues: ConflictIssue[]
  createdAt: string
  updatedAt: string
}

export interface ConflictReviewListItem {
  id: string
  documentName: string
  reviewType: ConflictReviewType
  clauseCount: number
  summary: Record<string, any> | string
  status: string
  createdAt: string
  updatedAt: string
}

const API_BASE = '/api/v1/conflict-reviews'

function headers(): HeadersInit {
  const result: HeadersInit = { 'Content-Type': 'application/json' }
  const authorization = getAuthorization()
  if (authorization) result.Authorization = authorization
  return result
}

async function parseResponse<T>(response: Response, fallback: string): Promise<T> {
  const body = await response.json().catch(() => null)
  if (response.ok && (body?.code === 200 || body?.code === 201)) return body.data as T
  throw new Error(body?.message || fallback)
}

export async function createConflictReview(data: {
  documentName: string
  content: string
  reviewType: ConflictReviewType
  baselineDate: string
  standardIds: string[]
  replacedStandardIds: string[]
}): Promise<{ id: string; status: string }> {
  const response = await fetch(API_BASE, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify(data),
  })
  return parseResponse(response, '创建审查失败')
}

export async function getConflictReview(id: string): Promise<ConflictReviewDetail> {
  const response = await fetch(`${API_BASE}/${id}`, { headers: headers() })
  return parseResponse(response, '获取审查结果失败')
}

export async function getConflictReviewList(page = 1, pageSize = 20): Promise<{
  list: ConflictReviewListItem[]
  total: number
  page: number
  pageSize: number
}> {
  const response = await fetch(`${API_BASE}?page=${page}&pageSize=${pageSize}`, { headers: headers() })
  return parseResponse(response, '获取审查列表失败')
}

export async function decideConflictIssue(
  recordId: string,
  issueId: string,
  status: ReviewDecision,
  reviewComment = '',
): Promise<ConflictIssue> {
  const response = await fetch(`${API_BASE}/${recordId}/issues/${issueId}`, {
    method: 'PUT',
    headers: headers(),
    body: JSON.stringify({ status, reviewComment }),
  })
  return parseResponse(response, '保存复核结论失败')
}
