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
            <!--
              用 v-model 绑本地 ref + @change 提交：
              1) v-model 双向同步，撤销/重做后 store 改变能立刻反映在 textarea；
              2) @change 失焦时一次性提交，整段编辑算作一条 history，避免每键一次撤销
            -->
            <el-input
                v-model="editContent"
                type="textarea" :rows="3" size="small"
                @change="(v: string) => update({ content: v })"
            />
          </div>
          <div class="form-item">
            <label>字体大小</label>
            <el-input-number
                v-model="editFontSize"
                size="small" :min="6" :max="200" :step="1"
                @change="(v: number) => update({ fontSize: v })"
            />
          </div>
          <div class="form-item">
            <label>字体颜色</label>
            <el-color-picker
                v-model="editElementColor"
                size="small"
                @change="(v: string) => update({ color: v })"
            />
          </div>
        </template>

        <template v-if="store.selectedElement.type === 'IMAGE'">
          <el-divider style="margin: 8px 0" />
          <el-button
              type="primary"
              plain
              size="small"
              style="width:100%"
              :icon="Crop"
              :disabled="!store.canCropSelectedImage()"
              @click="handleOpenImageCrop"
          >
            裁剪图片
          </el-button>
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

        <el-button
            v-if="store.canDeleteSelectedElement"
            type="danger" plain size="small" style="width:100%; margin: 8px 0 0 0"
            :icon="Delete"
            @click="handleDeleteElement"
        >
          删除元素
        </el-button>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Pointer, RefreshLeft, Delete, Crop } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useEditorStore } from '@/stores/editorStore'
import type { ElementData, AnnotationData } from '@/types'
import { ANNOTATION_TYPE_LABEL as annTypeLabel } from '@/utils/annotationLabels'

const store = useEditorStore()

const selectedAnnotation = computed(() => store.selectedAnnotation)
const selectedElement    = computed(() => store.selectedElement)

// ─── 本地缓存：注释颜色 / 透明度 / 字体颜色 ──────────────────────────────
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

// ─── 本地缓存：OFD 文本元素 内容/字号/颜色 ───────────────────────────────
// 同样用独立 ref 驱动 v-model；store.selectedElement 变化（包括 undo / redo
// 替换 document 后产生的新对象）通过下方 watch 同步回这三个 ref，确保 textarea
// 和 ColorPicker 内部状态在撤销后立刻刷成历史快照里的值。
const editContent      = ref<string>('')
const editFontSize     = ref<number>(12)
const editElementColor = ref<string>('#000000')

watch(
    // 监听 id + 关键字段，撤销/重做后 selectedElement 是新对象但 id 一致
    () => {
      const el = selectedElement.value
      return el
          ? { id: el.id, content: el.content, fontSize: el.fontSize, color: el.color }
          : null
    },
    (snap) => {
      if (!snap) return
      editContent.value      = snap.content ?? ''
      editFontSize.value     = snap.fontSize ?? 12
      editElementColor.value = snap.color ?? '#000000'
    },
    { immediate: true }
)

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
  await store.deleteAnnotation(store.selectedAnnotationId)
  ElMessage.success('注释已删除')
}

// ─── OFD 元素 ─────────────────────────────────────────────────────────────
const typeLabel: Record<string, string> = {
  TEXT: '文本', IMAGE: '图像', PATH: '矢量', SEAL: '电子签章', OTHER: '其他',
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

function handleOpenImageCrop() {
  if (!store.openImageCropDialog()) {
    ElMessage.warning('当前图片无法裁剪，请确认已加载图片数据')
  }
}

async function handleDeleteElement() {
  if (!store.canDeleteSelectedElement) return
  try {
    await ElMessageBox.confirm('确定删除选中的元素吗？删除后保存即从文档中移除。', '删除元素', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  if (store.deleteSelectedElement()) {
    ElMessage.success('元素已删除，保存后生效')
  }
}
</script>