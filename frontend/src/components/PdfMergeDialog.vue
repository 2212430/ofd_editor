<template>
  <el-dialog
      v-model="visible"
      title="合并 PDF"
      width="520px"
      :append-to-body="true"
      class="pdf-merge-dialog"
      @closed="resetList"
  >
    <p class="merge-hint">
      最多选择 2 个 PDF。先在浏览器下载<strong>原生 PDF</strong>合并结果，再自动导入编辑器（转为 OFD 以便批注与编辑）。
      列表从上到下为合并顺序：上方在前，下方在后。
    </p>

    <div class="attach-area">
      <div v-if="attachments.length === 0" class="attach-empty">
        <el-icon class="attach-empty-icon"><Document /></el-icon>
        <span>点击下方「选择 PDF」添加文件</span>
      </div>
      <ul v-else class="attach-list">
        <li
            v-for="(item, index) in attachments"
            :key="item.id"
            class="attach-item"
        >
          <div class="attach-badge pdf">PDF</div>
          <div class="attach-body">
            <div class="attach-name" :title="item.file.name">{{ item.file.name }}</div>
            <div class="attach-meta">
              <span class="attach-order">第 {{ index + 1 }} 个（{{ index === 0 ? '合并在前' : '合并在后' }}）</span>
              <span class="attach-size">{{ formatFileSize(item.file.size) }}</span>
            </div>
          </div>
          <el-button
              type="danger"
              link
              :icon="Close"
              title="移除"
              @click="removeAttachment(index)"
          />
        </li>
      </ul>
    </div>

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
          <el-button
              :icon="FolderOpened"
              :disabled="attachments.length >= MAX_FILES || merging"
              @click="triggerSelect"
          >
            选择 PDF
          </el-button>
          <el-button
              type="primary"
              :icon="Files"
              :disabled="attachments.length !== MAX_FILES"
              :loading="merging"
              @click="handleMerge"
          >
            合并并打开
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Close, Document, Files, FolderOpened } from '@element-plus/icons-vue'
import { useEditorStore } from '@/stores/editorStore'
import { buildMergedPdfFilename, ofdApi, promptDownloadBlob } from '@/api/ofdApi'

const MAX_FILES = 2

interface MergeAttachment {
  id: string
  file: File
}

const visible = defineModel<boolean>({ default: false })
const store = useEditorStore()
const fileInputRef = ref<HTMLInputElement>()
const attachments = ref<MergeAttachment[]>([])
const merging = ref(false)
let attachSeq = 0

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}

function isPdfFile(file: File): boolean {
  const name = file.name.toLowerCase()
  return name.endsWith('.pdf') || file.type === 'application/pdf'
}

function resetList() {
  attachments.value = []
  merging.value = false
}

function triggerSelect() {
  if (attachments.value.length >= MAX_FILES) {
    ElMessage.warning(`最多选择 ${MAX_FILES} 个 PDF 文件`)
    return
  }
  fileInputRef.value?.click()
}

function onFileSelected(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!isPdfFile(file)) {
    ElMessage.warning('请选择 .pdf 格式的文件')
    return
  }
  if (attachments.value.length >= MAX_FILES) {
    ElMessage.warning(`最多选择 ${MAX_FILES} 个 PDF 文件`)
    return
  }
  const duplicate = attachments.value.some(
      (a) => a.file.name === file.name && a.file.size === file.size && a.file.lastModified === file.lastModified,
  )
  if (duplicate) {
    ElMessage.warning('该文件已在列表中')
    return
  }
  attachments.value.push({ id: `att-${++attachSeq}`, file })
}

function removeAttachment(index: number) {
  attachments.value.splice(index, 1)
}

async function handleMerge() {
  if (attachments.value.length !== MAX_FILES) return
  const [first, second] = attachments.value.map((a) => a.file)
  const pdfFilename = buildMergedPdfFilename(first.name, second.name)

  merging.value = true
  store.setLoading(true, '正在合并 PDF…')

  try {
    const pdfBlob = await ofdApi.mergePdf(first, second)

    store.setLoading(true, '合并完成，请下载 PDF 文件…')
    await promptDownloadBlob(pdfBlob, pdfFilename)

    store.setLoading(true, '正在导入编辑器（PDF→OFD）…')
    const pdfFile = new File([pdfBlob], pdfFilename, { type: 'application/pdf' })
    const ofdBlob = await ofdApi.fromPdf(pdfFile)
    const ofdName = pdfFilename.replace(/\.pdf$/i, '.ofd')
    const ofdFile = new File([ofdBlob], ofdName, { type: 'application/ofd' })
    const doc = await ofdApi.parseOfd(ofdFile)
    store.setDocument(doc)
    store.setCurrentFile(ofdFile, 'pdf')
    await store.loadAllAnnotations()

    ElMessage.success(
        `已下载合并 PDF，并在编辑器中打开（共 ${doc.pageCount} 页）。` +
        '编辑区为 OFD 预览；需要原生 PDF 请使用刚下载的文件。',
    )
    visible.value = false
  } catch (err: any) {
    ElMessage.error(err?.message ?? 'PDF 合并失败')
  } finally {
    merging.value = false
    store.setLoading(false)
  }
}
</script>

<style scoped>
.merge-hint {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.55;
  color: var(--text-2);
}

.attach-area {
  min-height: 160px;
  padding: 12px;
  border: 1px dashed var(--line);
  border-radius: var(--radius);
  background: #fafafa;
}

.attach-empty {
  height: 136px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--text-3);
  font-size: 13px;
}

.attach-empty-icon {
  font-size: 36px;
  color: #c0c4cc;
}

.attach-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.attach-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
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
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.attach-meta {
  margin-top: 4px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 11px;
  color: var(--text-3);
}

.attach-order {
  color: #c0392b;
  font-weight: 600;
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
