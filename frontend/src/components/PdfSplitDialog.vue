<template>
  <el-dialog
      v-model="visible"
      title="拆分 PDF"
      width="520px"
      :append-to-body="true"
      class="pdf-split-dialog"
      @closed="resetForm"
  >
    <p class="split-hint">
      请选择<strong>原生 PDF 文件</strong>进行拆分（非编辑器内由 PDF 转成的 OFD 预览）。
      拆分后将分别下载两份 PDF。
    </p>

    <div class="attach-area">
      <div v-if="!selectedFile" class="attach-empty">
        <el-icon class="attach-empty-icon"><Document /></el-icon>
        <span>点击下方「选择 PDF」添加文件</span>
      </div>
      <div v-else class="attach-item">
        <div class="attach-badge pdf">PDF</div>
        <div class="attach-body">
          <div class="attach-name" :title="selectedFile.name">{{ selectedFile.name }}</div>
          <div class="attach-meta">
            <span v-if="pageCount > 0">共 {{ pageCount }} 页</span>
            <span v-else-if="readingPages">正在读取页数…</span>
            <span class="attach-size">{{ formatFileSize(selectedFile.size) }}</span>
          </div>
        </div>
        <el-button type="danger" link :icon="Close" title="移除" @click="clearFile" />
      </div>
    </div>

    <el-form v-if="selectedFile && pageCount > 1" label-width="120px" class="split-form">
      <el-form-item label="第一部分至">
        <el-input-number
            v-model="splitAfterPage"
            :min="1"
            :max="maxSplitPage"
            controls-position="right"
        />
        <span class="page-hint">页（含）</span>
      </el-form-item>
      <el-form-item label="拆分预览">
        <div class="preview">
          <div>第一部分：第 1 ～ {{ splitAfterPage }} 页</div>
          <div>第二部分：第 {{ splitAfterPage + 1 }} ～ {{ pageCount }} 页</div>
        </div>
      </el-form-item>
    </el-form>

    <input
        ref="fileInputRef"
        type="file"
        accept=".pdf,application/pdf"
        style="display: none"
        @change="onFileSelected"
    />

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="visible = false">取消</el-button>
        <div class="footer-actions">
          <el-button :icon="FolderOpened" :disabled="splitting" @click="triggerSelect">
            选择 PDF
          </el-button>
          <el-button
              type="primary"
              :loading="splitting"
              :disabled="!canSplit"
              @click="handleSplit"
          >
            拆分并保存
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Close, Document, FolderOpened } from '@element-plus/icons-vue'
import { useEditorStore } from '@/stores/editorStore'
import { ofdApi, downloadBlob } from '@/api/ofdApi'

const visible = defineModel<boolean>({ default: false })
const store = useEditorStore()
const fileInputRef = ref<HTMLInputElement>()
const selectedFile = ref<File | null>(null)
const pageCount = ref(0)
const readingPages = ref(false)
const splitting = ref(false)
const splitAfterPage = ref(1)

const maxSplitPage = computed(() => Math.max(1, pageCount.value - 1))
const canSplit = computed(
    () => !!selectedFile.value && pageCount.value > 1 && !readingPages.value && !splitting.value,
)

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}

function isPdfFile(file: File): boolean {
  const name = file.name.toLowerCase()
  return name.endsWith('.pdf') || file.type === 'application/pdf'
}

function resetForm() {
  selectedFile.value = null
  pageCount.value = 0
  splitAfterPage.value = 1
  splitting.value = false
  readingPages.value = false
}

function triggerSelect() {
  fileInputRef.value?.click()
}

function clearFile() {
  selectedFile.value = null
  pageCount.value = 0
  splitAfterPage.value = 1
}

async function onFileSelected(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!isPdfFile(file)) {
    ElMessage.warning('请选择 .pdf 格式的文件')
    return
  }

  selectedFile.value = file
  pageCount.value = 0
  readingPages.value = true
  try {
    const count = await ofdApi.pdfPageCount(file)
    pageCount.value = count
    if (count <= 1) {
      ElMessage.warning('仅 1 页的 PDF 无法拆分')
      return
    }
    splitAfterPage.value = Math.max(1, Math.floor(count / 2))
  } catch (err: any) {
    ElMessage.error(err?.message ?? '读取 PDF 页数失败')
    clearFile()
  } finally {
    readingPages.value = false
  }
}

async function handleSplit() {
  if (!selectedFile.value || pageCount.value <= 1) return
  if (splitAfterPage.value < 1 || splitAfterPage.value >= pageCount.value) {
    ElMessage.warning(`请输入 1～${pageCount.value - 1} 之间的页码`)
    return
  }

  splitting.value = true
  store.setLoading(true, '正在拆分 PDF…')
  try {
    const files = await ofdApi.splitPdf(selectedFile.value, splitAfterPage.value)
    downloadBlob(files[0].blob, files[0].filename)
    await new Promise((r) => setTimeout(r, 300))
    downloadBlob(files[1].blob, files[1].filename)
    ElMessage.success('PDF 已拆分为两份并开始下载')
    visible.value = false
  } catch (err: any) {
    ElMessage.error(err?.message ?? 'PDF 拆分失败')
  } finally {
    splitting.value = false
    store.setLoading(false)
  }
}
</script>

<style scoped>
.split-hint {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.55;
  color: var(--text-2);
}

.attach-area {
  min-height: 100px;
  padding: 12px;
  border: 1px dashed var(--line);
  border-radius: var(--radius);
  background: #fafafa;
  margin-bottom: 12px;
}

.attach-empty {
  height: 88px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--text-3);
  font-size: 13px;
}

.attach-empty-icon {
  font-size: 32px;
  color: #c0c4cc;
}

.attach-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
}

.attach-badge {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border-radius: 6px;
  color: #fff;
  font-size: 11px;
  font-weight: 800;
  display: grid;
  place-items: center;
}

.attach-badge.pdf {
  background: linear-gradient(145deg, #e74c3c, #c0392b);
}

.attach-body {
  flex: 1;
  min-width: 0;
}

.attach-name {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.attach-meta {
  margin-top: 4px;
  display: flex;
  gap: 8px;
  font-size: 11px;
  color: var(--text-3);
}

.split-form {
  margin-top: 8px;
}

.page-hint {
  margin-left: 8px;
  font-size: 13px;
  color: var(--text-3);
}

.preview {
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-2);
}

.dialog-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.footer-actions {
  display: flex;
  gap: 8px;
}
</style>
