<template>
  <div class="review-page">
    <header class="review-header">
      <el-button :icon="ArrowLeft" @click="router.push('/home')">返回主页</el-button>
      <div>
        <h2>新建制度冲突审查</h2>
        <p>上传制度文本，选择对照范围，生成带双方原文的疑似问题清单</p>
      </div>
    </header>

    <main class="review-layout">
      <section class="form-card">
        <div class="step-title"><span>1</span>上传待审制度</div>
        <el-upload
          ref="uploadRef"
          drag
          accept=".md,.txt"
          :auto-upload="false"
          :show-file-list="false"
          :limit="1"
          :on-change="handleFile"
          :on-exceed="handleExceed"
        >
          <el-icon class="upload-icon"><UploadFilled /></el-icon>
          <div>拖拽或点击选择 .md / .txt 文件</div>
          <div class="upload-tip">文件在浏览器中读取，正文随审查任务提交，不依赖 OSS</div>
        </el-upload>
        <div v-if="fileName" class="file-row">
          <el-icon><Document /></el-icon>
          <span>{{ fileName }}</span>
          <small>{{ sourceClauses }} 个条款或段落</small>
          <el-button link type="danger" @click="clearFile">移除</el-button>
        </div>
        <el-input
          v-model="content"
          type="textarea"
          :rows="10"
          placeholder="也可以直接粘贴制度正文。建议使用“第一条 ……”“第二条 ……”格式。"
          @input="countClauses"
        />
      </section>

      <section class="form-card">
        <div class="step-title"><span>2</span>设置审查范围</div>
        <el-form label-position="top">
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="审查类型">
                <el-select v-model="reviewType" style="width: 100%">
                  <el-option label="新制度对上级文件" value="superior" />
                  <el-option label="新制度对内部制度" value="internal" />
                  <el-option label="新旧替换及整体审查" value="replacement" />
                  <el-option label="存量制度对上级复核" value="existing" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="审查基准日期">
                <el-date-picker v-model="baselineDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>

        <div class="scope-toolbar">
          <span>选择对照制度（{{ selectedStandardIds.length }}）</span>
          <el-input v-model="keyword" size="small" clearable placeholder="搜索编号或名称" :prefix-icon="Search" />
        </div>
        <div v-loading="loadingStandards" class="standard-list">
          <label v-for="standard in filteredStandards" :key="standard.id" class="standard-item">
            <input v-model="selectedStandardIds" type="checkbox" :value="standard.id" />
            <div>
              <strong>{{ standard.number }} {{ standard.name }}</strong>
              <small>
                {{ standard.category === 'superior' ? '上级文件' : '内部制度' }} ·
                {{ standard.status === 'active' ? '现行' : '已废止' }} ·
                {{ standard.effectiveDate || '未填生效日期' }}
              </small>
            </div>
          </label>
          <el-empty v-if="!loadingStandards && filteredStandards.length === 0" description="没有匹配的制度" :image-size="70" />
        </div>

        <div v-if="reviewType === 'replacement'" class="replacement-box">
          <div class="replacement-title">选择本次被替换的旧制度</div>
          <p>旧制度用于新旧对照，同时会从“替换后的现行体系”中排除。</p>
          <el-select v-model="replacedStandardIds" multiple style="width: 100%" placeholder="从已选制度中选择">
            <el-option
              v-for="standard in selectedStandards"
              :key="standard.id"
              :label="`${standard.number} ${standard.name}`"
              :value="standard.id"
            />
          </el-select>
        </div>

        <el-alert
          title="第一版使用本地规则识别数量标准和审批主体差异，所有结果均需人工复核。"
          type="info"
          :closable="false"
          show-icon
        />
        <el-button class="submit-button" type="primary" size="large" :loading="creating" :disabled="!canSubmit" @click="submit">
          开始审查
        </el-button>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { UploadInstance, UploadRawFile } from 'element-plus'
import { ArrowLeft, Document, Search, UploadFilled } from '@element-plus/icons-vue'
import { getStandardTreeApi, type Standard } from '../service/standardClauses'
import { createConflictReview, type ConflictReviewType } from '../service/conflictReview'

const router = useRouter()
const uploadRef = ref<UploadInstance>()
const fileName = ref('')
const content = ref('')
const sourceClauses = ref(0)
const reviewType = ref<ConflictReviewType>('superior')
const baselineDate = ref(new Date().toISOString().slice(0, 10))
const standards = ref<Standard[]>([])
const selectedStandardIds = ref<string[]>([])
const replacedStandardIds = ref<string[]>([])
const loadingStandards = ref(false)
const creating = ref(false)
const keyword = ref('')

const availableStandards = computed(() => {
  if (reviewType.value === 'superior' || reviewType.value === 'existing') {
    return standards.value.filter(item => item.category === 'superior')
  }
  if (reviewType.value === 'internal' || reviewType.value === 'replacement') {
    return standards.value.filter(item => item.category !== 'superior')
  }
  return standards.value
})
const filteredStandards = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  return availableStandards.value.filter(item => !value || `${item.number}${item.name}`.toLowerCase().includes(value))
})
const selectedStandards = computed(() => standards.value.filter(item => selectedStandardIds.value.includes(item.id)))
const canSubmit = computed(() => content.value.trim().length > 0 && selectedStandardIds.value.length > 0 && !creating.value)

watch(reviewType, () => {
  const allowed = new Set(availableStandards.value.map(item => item.id))
  selectedStandardIds.value = selectedStandardIds.value.filter(id => allowed.has(id))
  replacedStandardIds.value = []
})
watch(selectedStandardIds, () => {
  replacedStandardIds.value = replacedStandardIds.value.filter(id => selectedStandardIds.value.includes(id))
})

onMounted(async () => {
  loadingStandards.value = true
  try {
    standards.value = (await getStandardTreeApi()).map(node => node.standard)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '制度库加载失败')
  } finally {
    loadingStandards.value = false
  }
})

async function handleFile(uploadFile: { raw?: UploadRawFile; name: string }) {
  const file = uploadFile.raw
  if (!file) return
  if (!/\.(md|txt)$/i.test(file.name) || file.size > 1024 * 1024) {
    ElMessage.warning('仅支持不超过 1MB 的 .md、.txt 文件')
    uploadRef.value?.clearFiles()
    return
  }
  content.value = await file.text()
  fileName.value = file.name
  countClauses()
}

function handleExceed(files: File[]) {
  if (files[0]) void handleFile({ raw: files[0] as UploadRawFile, name: files[0].name })
}

function clearFile() {
  fileName.value = ''
  content.value = ''
  sourceClauses.value = 0
  uploadRef.value?.clearFiles()
}

function countClauses() {
  const matches = content.value.match(/^\s*第[零〇一二三四五六七八九十百千万两0-9]+条/gm)
  sourceClauses.value = matches?.length || content.value.split(/\n\s*\n|\n/).filter(line => line.trim()).length
}

async function submit() {
  creating.value = true
  try {
    const result = await createConflictReview({
      documentName: fileName.value || '粘贴的制度文本.md',
      content: content.value,
      reviewType: reviewType.value,
      baselineDate: baselineDate.value,
      standardIds: selectedStandardIds.value,
      replacedStandardIds: replacedStandardIds.value,
    })
    ElMessage.success('审查完成，请复核疑似问题')
    await router.push(`/review/${result.id}`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '创建审查失败')
  } finally {
    creating.value = false
  }
}
</script>

<style scoped>
.review-page { min-height: 100vh; background: #f5f7fa; }
.review-header { display: flex; align-items: center; gap: 20px; padding: 18px 32px; background: #fff; border-bottom: 1px solid #e5e7eb; }
.review-header h2 { margin: 0 0 4px; font-size: 21px; }
.review-header p { margin: 0; color: #6b7280; font-size: 13px; }
.review-layout { max-width: 1180px; margin: 24px auto; padding: 0 20px; display: grid; grid-template-columns: 1fr 1fr; gap: 20px; align-items: start; }
.form-card { background: #fff; border: 1px solid #e5e7eb; border-radius: 12px; padding: 22px; }
.step-title { display: flex; align-items: center; gap: 10px; font-size: 16px; font-weight: 600; margin-bottom: 18px; }
.step-title span { display: inline-flex; align-items: center; justify-content: center; width: 26px; height: 26px; border-radius: 50%; color: #fff; background: #2563eb; font-size: 13px; }
.upload-icon { font-size: 38px; color: #2563eb; margin-bottom: 8px; }
.upload-tip { color: #9ca3af; font-size: 12px; margin-top: 6px; }
.file-row { display: flex; align-items: center; gap: 8px; margin: 12px 0; color: #374151; }
.file-row span { flex: 1; }
.file-row small { color: #6b7280; }
.form-card :deep(.el-textarea) { margin-top: 12px; }
.scope-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; font-size: 13px; }
.scope-toolbar .el-input { width: 210px; }
.standard-list { max-height: 340px; min-height: 180px; overflow-y: auto; border: 1px solid #e5e7eb; border-radius: 8px; margin-bottom: 16px; }
.standard-item { display: flex; gap: 10px; padding: 12px; border-bottom: 1px solid #f0f2f5; cursor: pointer; }
.standard-item:hover { background: #f8fafc; }
.standard-item input { margin-top: 4px; }
.standard-item div { display: flex; flex-direction: column; gap: 5px; }
.standard-item strong { font-size: 13px; font-weight: 500; }
.standard-item small { color: #8a94a3; }
.replacement-box { margin-bottom: 16px; padding: 12px; background: #fffbeb; border-radius: 8px; }
.replacement-title { font-size: 13px; font-weight: 600; }
.replacement-box p { color: #92400e; font-size: 12px; margin: 5px 0 10px; }
.submit-button { width: 100%; margin-top: 18px; }
@media (max-width: 900px) { .review-layout { grid-template-columns: 1fr; } .review-header { padding: 14px 18px; } }
</style>
