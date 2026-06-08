<template>
  <el-dialog
      v-model="visible"
      title="拆分 OFD"
      width="480px"
      :append-to-body="true"
      class="ofd-split-dialog"
      @open="onOpen"
      @closed="resetForm"
  >
    <p class="split-hint">
      将<strong>当前打开的 OFD</strong>按指定页码拆成两份并分别保存。
      拆分依据为服务端缓存的 OFD 包（若尚未保存编辑，请先保存后再拆分）。
    </p>

    <el-form label-width="120px" @submit.prevent>
      <el-form-item label="文档">
        <span class="doc-name">{{ docTitle }}</span>
        <span class="doc-meta">共 {{ pageCount }} 页</span>
      </el-form-item>
      <el-form-item label="第一部分至">
        <el-input-number
            v-model="splitAfterPage"
            :min="1"
            :max="maxSplitPage"
            :disabled="pageCount <= 1"
            controls-position="right"
        />
        <span class="page-hint">页（含）</span>
      </el-form-item>
      <el-form-item label="拆分预览">
        <div class="preview">
          <div>第一部分：第 1 ～ {{ splitAfterPage }} 页（{{ splitAfterPage }} 页）</div>
          <div>第二部分：第 {{ splitAfterPage + 1 }} ～ {{ pageCount }} 页（{{ pageCount - splitAfterPage }} 页）</div>
        </div>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
          type="primary"
          :loading="splitting"
          :disabled="pageCount <= 1 || !store.fileId"
          @click="handleSplit"
      >
        拆分并保存
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useEditorStore } from '@/stores/editorStore'
import { ofdApi, downloadBlob } from '@/api/ofdApi'

const visible = defineModel<boolean>({ default: false })
const store = useEditorStore()
const splitting = ref(false)
const splitAfterPage = ref(1)

const pageCount = computed(() => store.document?.pageCount ?? 0)
const maxSplitPage = computed(() => Math.max(1, pageCount.value - 1))
const docTitle = computed(() => {
  const t = store.document?.title?.trim()
  if (t) return t
  const name = store.currentFile?.name ?? ''
  return name.replace(/\.ofd$/i, '') || '文档'
})

function onOpen() {
  const total = pageCount.value
  if (total <= 1 || !store.fileId) {
    ElMessage.warning(total <= 1 ? '仅 1 页的文件无法拆分' : '请先打开 OFD 文件')
    visible.value = false
    return
  }
  splitAfterPage.value = Math.max(1, Math.floor(total / 2))
}

function resetForm() {
  splitting.value = false
  splitAfterPage.value = 1
}

async function handleSplit() {
  if (!store.fileId || !store.document) {
    ElMessage.warning('请先打开 OFD 文件')
    return
  }
  if (pageCount.value <= 1) {
    ElMessage.warning('仅 1 页的文件无法拆分')
    return
  }
  if (splitAfterPage.value < 1 || splitAfterPage.value >= pageCount.value) {
    ElMessage.warning(`请输入 1～${pageCount.value - 1} 之间的页码`)
    return
  }

  splitting.value = true
  store.setLoading(true, '正在拆分 OFD…')
  try {
    const files = await ofdApi.splitOfd({
      fileId: store.fileId,
      splitAfterPage: splitAfterPage.value,
      title: docTitle.value,
    })
    downloadBlob(files[0].blob, files[0].filename)
    await new Promise((r) => setTimeout(r, 300))
    downloadBlob(files[1].blob, files[1].filename)
    ElMessage.success('OFD 已拆分为两份并开始下载')
    visible.value = false
  } catch (err: any) {
    ElMessage.error(err?.message ?? 'OFD 拆分失败')
  } finally {
    splitting.value = false
    store.setLoading(false)
  }
}
</script>

<style scoped>
.split-hint {
  margin: 0 0 16px;
  font-size: 13px;
  line-height: 1.55;
  color: var(--text-2);
}

.doc-name {
  font-weight: 600;
  color: var(--text-1);
  margin-right: 8px;
}

.doc-meta {
  font-size: 12px;
  color: var(--text-3);
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
</style>
