<template>
  <el-dialog
      v-model="visible"
      title="文本水印"
      width="460px"
      :append-to-body="true"
      @open="onOpen"
  >
    <el-form label-width="92px" size="default">
      <el-form-item label="水印文字" required>
        <el-input v-model="form.text" placeholder="如：内部资料 请勿外传" maxlength="64" />
      </el-form-item>
      <el-form-item label="字号 (pt)">
        <el-input-number v-model="form.fontSize" :min="12" :max="120" :step="2" />
      </el-form-item>
      <el-form-item label="颜色">
        <el-color-picker v-model="form.color" />
      </el-form-item>
      <el-form-item label="透明度">
        <el-slider v-model="form.opacity" :min="0.05" :max="0.6" :step="0.01" show-input />
      </el-form-item>
      <el-form-item label="角度 (°)">
        <el-input-number v-model="form.angle" :min="-90" :max="90" :step="15" />
      </el-form-item>
      <el-form-item label="平铺">
        <el-switch v-model="form.tile" />
        <span class="hint">关闭则每页居中单个水印</span>
      </el-form-item>
      <el-form-item label="加粗">
        <el-switch v-model="form.bold" />
      </el-form-item>
    </el-form>

    <el-alert type="info" :closable="false" show-icon title="水印将在保存/导出时烘焙进文档（PDF 与 OFD 均支持）。" />

    <template #footer>
      <el-button v-if="store.watermarkConfig" @click="handleClear">清除水印</el-button>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="!form.text.trim()" @click="handleApply">应用</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { useEditorStore } from '@/stores/editorStore'
import type { WatermarkConfig } from '@/types'

const visible = defineModel<boolean>({ default: false })
const store = useEditorStore()

const form = reactive({
  text: '',
  fontSize: 36,
  color: '#999999',
  opacity: 0.18,
  angle: 45,
  tile: true,
  bold: false,
})

function onOpen() {
  const wm = store.watermarkConfig
  if (wm) {
    form.text = wm.text
    form.fontSize = wm.fontSize ?? 36
    form.color = wm.color ?? '#999999'
    form.opacity = wm.opacity ?? 0.18
    form.angle = wm.angle ?? 45
    form.tile = wm.tile !== false
    form.bold = !!wm.bold
  } else {
    form.text = store.document?.title ? `${store.document.title}` : '水印'
  }
}

function buildConfig(): WatermarkConfig {
  return {
    text: form.text.trim(),
    fontSize: form.fontSize,
    color: form.color,
    opacity: form.opacity,
    angle: form.angle,
    tile: form.tile,
    bold: form.bold,
  }
}

function handleApply() {
  if (!form.text.trim()) {
    ElMessage.warning('请输入水印文字')
    return
  }
  store.setWatermarkConfig(buildConfig())
  ElMessage.success('水印已设置，保存或导出时生效')
  visible.value = false
}

function handleClear() {
  store.setWatermarkConfig(null)
  ElMessage.success('已清除水印设置')
  visible.value = false
}
</script>

<style scoped>
.hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--text-3, #999);
}
:deep(.el-alert) {
  margin-top: 8px;
}
</style>
