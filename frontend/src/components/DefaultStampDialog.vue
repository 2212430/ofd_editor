<template>
  <el-dialog
      v-model="visible"
      title="图章"
      width="680px"
      :append-to-body="true"
      class="default-stamp-dialog"
      @open="onOpen"
  >
    <section class="stamp-section">
      <h4 class="section-title">在此签名</h4>
      <div class="stamp-grid sign-grid">
        <button
            v-for="stamp in SIGN_HERE_STAMPS"
            :key="stamp.id"
            type="button"
            class="stamp-item"
            :title="stamp.text"
            @click="pickStamp(stamp)"
        >
          <img :src="previewMap[stamp.id]" :alt="stamp.text" draggable="false" />
        </button>
      </div>
    </section>

    <section class="stamp-section">
      <h4 class="section-title">标准图章</h4>
      <div class="stamp-grid standard-grid">
        <button
            v-for="stamp in STANDARD_STAMPS"
            :key="stamp.id"
            type="button"
            class="stamp-item"
            :title="stamp.text"
            @click="pickStamp(stamp)"
        >
          <img :src="previewMap[stamp.id]" :alt="stamp.text" draggable="false" />
        </button>
      </div>
    </section>

    <p class="stamp-hint">选择图章后，在页面上点击即可放置；可重复放置同一图章。</p>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { useEditorStore } from '@/stores/editorStore'
import {
    SIGN_HERE_STAMPS,
    STANDARD_STAMPS,
    getDefaultStampDataUrl,
    warmupDefaultStamps,
    type DefaultStampDef,
} from '@/utils/defaultStamps'

const visible = defineModel<boolean>({ default: false })
const emit = defineEmits<{ picked: [] }>()
const store = useEditorStore()
const previewMap = reactive<Record<string, string>>({})

function onOpen() {
    warmupDefaultStamps()
    for (const stamp of [...SIGN_HERE_STAMPS, ...STANDARD_STAMPS]) {
        previewMap[stamp.id] = getDefaultStampDataUrl(stamp)
    }
}

function pickStamp(stamp: DefaultStampDef) {
    if (!store.document) {
        ElMessage.warning('请先打开文档')
        return
    }
    store.setPendingStampImage(getDefaultStampDataUrl(stamp))
    visible.value = false
    emit('picked')
    ElMessage.success(`已选择「${stamp.text}」，请点击页面放置`)
}
</script>

<style scoped>
.stamp-section {
    margin-bottom: 20px;
}
.stamp-section:last-of-type {
    margin-bottom: 8px;
}
.section-title {
    margin: 0 0 10px;
    font-size: 13px;
    font-weight: 600;
    color: var(--text-2);
}
.stamp-grid {
    display: grid;
    gap: 10px;
}
.sign-grid {
    grid-template-columns: repeat(5, 1fr);
}
.standard-grid {
    grid-template-columns: repeat(4, 1fr);
}
.stamp-item {
    display: block;
    width: 100%;
    padding: 4px;
    border: 1px solid var(--border, #e4e7ed);
    border-radius: 4px;
    background: #fafafa;
    cursor: pointer;
    transition: border-color 0.15s, box-shadow 0.15s, transform 0.1s;
}
.stamp-item:hover {
    border-color: var(--ribbon-accent, #409eff);
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
    transform: translateY(-1px);
}
.stamp-item img {
    display: block;
    width: 100%;
    height: auto;
    pointer-events: none;
    user-select: none;
}
.stamp-hint {
    margin: 4px 0 0;
    font-size: 12px;
    color: var(--text-3, #909399);
}
@media (max-width: 640px) {
    .sign-grid { grid-template-columns: repeat(3, 1fr); }
    .standard-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
