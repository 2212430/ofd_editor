<template>
  <div class="property-panel">

    <!-- ===== 注释属性面板 ===== -->
    <template v-if="store.selectedAnnotationId && selectedAnnotation">
      <div class="panel-header">
        注释属性
        <el-tag size="small" type="info" style="margin-left:6px">
          {{ annTypeLabel[selectedAnnotation.type] ?? selectedAnnotation.type }}
        </el-tag>
      </div>

      <div class="property-form">
        <!-- 坐标 -->
        <div class="form-row">
          <div class="form-item">
            <label>X 位置</label>
            <el-input-number
                :model-value="round(selectedAnnotation?.x ?? 0)"
                size="small" :precision="1" :step="1"
                @change="(v: number) => updateAnnotation({ x: v })"
            />
          </div>
          <div class="form-item">
            <label>Y 位置</label>
            <el-input-number
                :model-value="round(selectedAnnotation?.y ?? 0)"
                size="small" :precision="1" :step="1"
                @change="(v: number) => updateAnnotation({ y: v })"
            />
          </div>
        </div>
        <div class="form-row">
          <div class="form-item">
            <label>宽度</label>
            <el-input-number
                :model-value="round(selectedAnnotation?.width ?? 1)"
                size="small" :precision="1" :step="1" :min="1"
                @change="(v: number) => updateAnnotation({ width: v })"
            />
          </div>
          <div class="form-item">
            <label>高度</label>
            <el-input-number
                :model-value="round(selectedAnnotation?.height ?? 1)"
                size="small" :precision="1" :step="1" :min="1"
                @change="(v: number) => updateAnnotation({ height: v })"
            />
          </div>
        </div>

        <el-divider style="margin: 8px 0" />

        <!-- 颜色 -->
        <div class="form-item">
          <label>颜色</label>
          <el-color-picker
              v-model="editColor"
              size="small"
              @change="(v: string) => updateAnnotation({ color: v })"
          />
        </div>

        <!-- 透明度 -->
        <div class="form-item">
          <label>透明度</label>
          <el-slider
              v-model="editOpacity"
              :min="0.1" :max="1" :step="0.1"
              :format-tooltip="(v: number) => `${Math.round(v * 100)}%`"
              @change="(v: number) => updateAnnotation({ opacity: v })"
          />
        </div>

        <!-- 线宽 -->
        <div
            v-if="!['HIGHLIGHT','TEXTBOX','STICKYNOTE','STAMP'].includes(selectedAnnotation.type)"
            class="form-item"
        >
          <label>线条粗细</label>
          <el-input-number
              :model-value="selectedAnnotation?.lineWidth ?? 2"
              size="small" :min="1" :max="20" :step="1"
              @change="(v: number) => updateAnnotation({ lineWidth: v })"
          />
        </div>

        <!-- 文本内容 -->
        <template v-if="['TEXTBOX','STICKYNOTE'].includes(selectedAnnotation.type)">
          <el-divider style="margin: 8px 0" />
          <div class="form-item">
            <label>文本内容</label>
            <el-input
                :model-value="selectedAnnotation?.content ?? ''"
                type="textarea"
                :rows="4"
                size="small"
                @change="(v: string) => updateAnnotation({ content: v })"
            />
          </div>
          <div class="form-item">
            <label>字体大小</label>
            <el-input-number
                :model-value="selectedAnnotation?.fontSize ?? 12"
                size="small" :min="6" :max="72" :step="1"
                @change="(v: number) => updateAnnotation({ fontSize: v })"
            />
          </div>
          <div class="form-item">
            <label>字体颜色</label>
            <el-color-picker
                v-model="editFontColor"
                size="small"
                @change="(v: string) => updateAnnotation({ fontColor: v })"
            />
          </div>
        </template>

        <el-divider style="margin: 8px 0" />

        <el-button
            type="danger" plain size="small"
            style="width:100%"
            :icon="Delete"
            @click="handleDeleteAnnotation"
        >
          删除该注释
        </el-button>
      </div>
    </template>

    <!-- ===== OFD 元素属性面板 ===== -->
    <template v-else>
      <div class="panel-header">元素属性</div>

      <div v-if="!store.selectedElement" class="empty-tip">
        <el-icon><Pointer /></el-icon>
        <span>请点击选择元素或注释</span>
      </div>

      <div v-else class="property-form">
        <el-tag v-if="store.selectedElement.isDirty"
                type="warning" size="small" style="margin-bottom:12px">
          已修改
        </el-tag>

        <div class="form-item">
          <label>类型</label>
          <el-tag size="small">
            {{ typeLabel[store.selectedElement.type] ?? store.selectedElement.type }}
          </el-tag>
        </div>

        <el-divider style="margin: 8px 0" />

        <div class="form-row">
          <div class="form-item">
            <label>X 位置</label>
            <el-input-number
                :model-value="round(store.selectedElement.x)"
                size="small" :precision="1" :step="1"
                @change="(v: number) => update({ x: v })"
            />
          </div>
          <div class="form-item">
            <label>Y 位置</label>
            <el-input-number
                :model-value="round(store.selectedElement.y)"
                size="small" :precision="1" :step="1"
                @change="(v: number) => update({ y: v })"
            />
          </div>
        </div>

        <div class="form-row">
          <div class="form-item">
            <label>宽度</label>
            <el-input-number
                :model-value="round(store.selectedElement.width)"
                size="small" :precision="1" :step="1" :min="1"
                @change="(v: number) => update({ width: v })"
            />
          </div>
          <div class="form-item">
            <label>高度</label>
            <el-input-number
                :model-value="round(store.selectedElement.height)"
                size="small" :precision="1" :step="1" :min="1"
                @change="(v: number) => update({ height: v })"
            />
          </div>
        </div>

        <div class="form-item">
          <label>旋转角度</label>
          <el-input-number
              :model-value="round(store.selectedElement.rotation ?? 0)"
              size="small" :precision="1" :step="5" :min="-360" :max="360"
              @change="(v: number) => update({ rotation: v })"
          />
        </div>

        <template v-if="store.selectedElement.type === 'TEXT'">
          <el-divider style="margin: 8px 0" />
          <div class="form-item">
            <label>文本内容</label>
            <el-input
                :model-value="store.selectedElement.content"
                type="textarea" :rows="3" size="small"
                @input="(v: string) => update({ content: v })"
            />
          </div>
          <div class="form-item">
            <label>字体大小</label>
            <el-input-number
                :model-value="store.selectedElement.fontSize ?? 12"
                size="small" :min="6" :max="200" :step="1"
                @change="(v: number) => update({ fontSize: v })"
            />
          </div>
          <div class="form-item">
            <label>字体颜色</label>
            <el-color-picker
                :model-value="store.selectedElement.color ?? '#000000'"
                size="small"
                @change="(v: string) => update({ color: v })"
            />
          </div>
        </template>

        <el-divider style="margin: 8px 0" />

        <el-button
            v-if="store.selectedElement.isDirty"
            type="warning" plain size="small" style="width:100%"
            :icon="RefreshLeft"
            @click="handleReset"
        >
          重置到原始状态
        </el-button>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Pointer, RefreshLeft, Delete } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useEditorStore } from '@/stores/editorStore'
import type { ElementData, AnnotationData } from '@/types'

const store = useEditorStore()

const selectedAnnotation = computed(() => store.selectedAnnotation)

// ─── 本地缓存：颜色 / 透明度 / 字体颜色 ───────────────────────────────────
// 用独立 ref 驱动 v-model，避免 computed plain object 不触发响应式的问题
const editColor     = ref<string>('#000000')
const editOpacity   = ref<number>(1)
const editFontColor = ref<string>('#000000')

// 每次选中注释变化时，同步本地缓存
watch(
    selectedAnnotation,
    (ann) => {
      if (ann) {
        editColor.value     = ann.color     ?? '#FFFF00'
        editOpacity.value   = ann.opacity   ?? 1
        editFontColor.value = ann.fontColor ?? '#000000'
      }
    },
    { immediate: true }
)

// ─── 注释类型标签 ─────────────────────────────────────────────────────────
const annTypeLabel: Record<string, string> = {
  HIGHLIGHT:  '高亮',
  UNDERLINE:  '下划线',
  STRIKEOUT:  '删除线',
  RECTANGLE:  '矩形',
  CIRCLE:     '椭圆',
  ARROW:      '箭头',
  FREEHAND:   '手绘',
  TEXTBOX:    '文本框',
  STICKYNOTE: '便利贴',
  STAMP:      '图章',
}

// ─── 更新注释（同步本地缓存 + 提交 store）────────────────────────────────
function updateAnnotation(changes: Partial<AnnotationData>) {
  if (!store.selectedAnnotationId) return
  if (changes.color !== undefined) editColor.value = changes.color
  if (changes.opacity !== undefined) editOpacity.value = changes.opacity
  if (changes.fontColor !== undefined) editFontColor.value = changes.fontColor
  const finalChanges = { ...changes }
  if (changes.color !== undefined) {
    finalChanges.strokeColor = changes.color
  }

  store.updateAnnotation(store.selectedAnnotationId, finalChanges)
}

async function handleDeleteAnnotation() {
  if (!store.selectedAnnotationId) return
  try {
    await ElMessageBox.confirm('确定删除该注释吗？', '确认删除', { type: 'warning' })
    await store.deleteAnnotation(store.selectedAnnotationId)
    ElMessage.success('注释已删除')
  } catch { /* 取消 */ }
}

// ─── OFD 元素 ─────────────────────────────────────────────────────────────
const typeLabel: Record<string, string> = {
  TEXT: '文本', IMAGE: '图像', PATH: '矢量', OTHER: '其他',
}

function round(val: number) {
  return Math.round(val * 10) / 10
}

function update(changes: Partial<ElementData>) {
  if (!store.selectedElementId) return
  store.updateElement(store.currentPageIndex, store.selectedElementId, changes)
}

function handleReset() {
  if (!store.selectedElementId) return
  store.resetElement(store.currentPageIndex, store.selectedElementId)
  ElMessage.success('已重置到原始状态')
}
</script>