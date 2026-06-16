<template>
  <el-dialog
      :model-value="modelValue"
      title="国密电子签章（GM/T 0099 SES v4）"
      width="460px"
      :close-on-click-modal="false"
      @update:model-value="(v: boolean) => emit('update:modelValue', v)"
      @open="onOpen"
  >
    <el-form label-width="92px" size="default">
      <el-form-item label="印章名称">
        <el-input v-model="form.name" placeholder="如：XX有限公司公章" maxlength="32" />
      </el-form-item>

      <el-form-item label="加盖页码">
        <el-input-number v-model="form.page" :min="1" :max="pageCount" />
        <span class="hint">共 {{ pageCount }} 页</span>
      </el-form-item>

      <el-form-item label="位置 (mm)">
        <div class="row">
          <el-input-number v-model="form.x" :min="0" :step="5" controls-position="right" />
          <span class="x-sep">×</span>
          <el-input-number v-model="form.y" :min="0" :step="5" controls-position="right" />
        </div>
      </el-form-item>

      <el-form-item label="尺寸 (mm)">
        <div class="row">
          <el-input-number v-model="form.width" :min="10" :max="120" :step="5" controls-position="right" />
          <span class="x-sep">×</span>
          <el-input-number v-model="form.height" :min="10" :max="120" :step="5" controls-position="right" />
        </div>
      </el-form-item>

      <el-form-item label="印章图片">
        <input ref="imgRef" type="file" accept="image/png,image/jpeg" style="display:none" @change="onPick" />
        <el-button :icon="Picture" @click="imgRef?.click()">选择图片</el-button>
        <span v-if="sealImage" class="hint">{{ sealImage.name }}</span>
        <span v-else class="hint">不选则自动生成红色圆章</span>
      </el-form-item>
    </el-form>

    <el-alert
        type="info"
        :closable="false"
        show-icon
        title="说明：使用运行时生成的 SM2 自签测试证书签章，用于演示/内部用途。如需可信签章，请接入企业 CA 颁发的国密证书。"
    />

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="signing" @click="handleSign">加盖并保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Picture } from '@element-plus/icons-vue'
import { useEditorStore } from '@/stores/editorStore'
import { ofdApi, downloadBlob } from '@/api/ofdApi'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const store = useEditorStore()
const imgRef = ref<HTMLInputElement>()
const sealImage = ref<File | null>(null)
const signing = ref(false)

const pageCount = computed(() => store.document?.pageCount ?? 1)

const form = reactive({
  name: '',
  page: 1,
  x: 120,
  y: 200,
  width: 40,
  height: 40,
})

function onOpen() {
  form.page = Math.min(store.currentPageIndex + 1, pageCount.value)
  if (!form.name) form.name = store.document?.title ? `${store.document.title} 印章` : 'OFD 测试印章'
}

function onPick(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0] ?? null
  sealImage.value = f
}

async function handleSign() {
  if (!store.document) return
  signing.value = true
  store.setLoading(true, '正在加盖国密签章…')
  try {
    // 用当前编辑后的 OFD 作为签章对象
    const ofdBlob = await ofdApi.saveOfd(store.getDocumentForSave()!)
    const signed = await ofdApi.signGm({
      file: ofdBlob,
      sealImage: sealImage.value,
      name: form.name,
      page: form.page,
      x: form.x,
      y: form.y,
      width: form.width,
      height: form.height,
    })
    const base = store.document.title ?? 'document'
    downloadBlob(signed, `${base}-signed.ofd`)
    ElMessage.success('已加盖国密签章并保存')
    emit('update:modelValue', false)
  } catch (err: any) {
    ElMessage.error(err?.message ?? '签章失败')
  } finally {
    signing.value = false
    store.setLoading(false)
  }
}
</script>

<style scoped>
.row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.x-sep {
  color: var(--text-3, #999);
}
.hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--text-3, #999);
}
:deep(.el-alert) {
  margin-top: 4px;
}
</style>
