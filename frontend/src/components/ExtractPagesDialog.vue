<template>
  <el-dialog
      v-model="visible"
      title="提取页面"
      width="480px"
      :append-to-body="true"
      @open="onOpen"
  >
    <p class="hint">
      从<strong>当前文档</strong>按页码提取页面，生成新的 {{ store.isPdfDocument ? 'PDF' : 'OFD' }} 文件。
      页码从 1 开始，支持逗号与范围（如 <code>1,3,5-8</code>），顺序即输出顺序。
    </p>

    <el-form label-width="88px" @submit.prevent>
      <el-form-item label="文档">
        <span>{{ docTitle }}</span>
        <span class="meta">共 {{ pageCount }} 页</span>
      </el-form-item>
      <el-form-item label="页码范围" required>
        <el-input
            v-model="pagesInput"
            placeholder="例如：1,3,5-8 或 2-5"
            @keyup.enter="handleExtract"
        />
      </el-form-item>
      <el-form-item label="快捷">
        <el-button size="small" @click="pagesInput = String(store.currentPageIndex + 1)">当前页</el-button>
        <el-button size="small" @click="pagesInput = `1-${pageCount}`">全部</el-button>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="extracting" :disabled="!pagesInput.trim()" @click="handleExtract">
        提取并下载
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useEditorStore } from '@/stores/editorStore'
import { ofdApi, downloadBlob } from '@/api/ofdApi'
import { parsePageRange } from '@/utils/pageRotate'

const visible = defineModel<boolean>({ default: false })
const store = useEditorStore()
const pagesInput = ref('')
const extracting = ref(false)

const pageCount = computed(() => store.document?.pageCount ?? 0)
const docTitle = computed(() => store.document?.title?.trim() || '文档')

function onOpen() {
  pagesInput.value = String(store.currentPageIndex + 1)
}

async function handleExtract() {
  if (!store.document) return
  extracting.value = true
  store.setLoading(true, '正在提取页面…')
  try {
    const pages = parsePageRange(pagesInput.value, pageCount.value)
    const blob = await store.extractPagesAsBlob(pages)
    const ext = store.isPdfDocument ? 'pdf' : 'ofd'
    const base = docTitle.value.replace(/\.(ofd|pdf)$/i, '')
    downloadBlob(blob, `${base}_提取.${ext}`)
    ElMessage.success(`已提取 ${pages.length} 页`)
    visible.value = false
  } catch (err: any) {
    ElMessage.error(err?.message ?? '提取失败')
  } finally {
    extracting.value = false
    store.setLoading(false)
  }
}
</script>

<style scoped>
.hint {
  font-size: 13px;
  color: var(--text-2, #666);
  line-height: 1.6;
  margin: 0 0 16px;
}
.hint code {
  background: #f4f4f5;
  padding: 1px 4px;
  border-radius: 3px;
}
.meta {
  margin-left: 8px;
  font-size: 12px;
  color: var(--text-3, #999);
}
</style>
