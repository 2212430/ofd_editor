<template>
  <div
      class="canvas-wrapper"
      :class="{ 'cursor-crosshair': store.isAnnotationTool }"
  >
    <v-stage
        ref="stageRef"
        :config="stageConfig"
        @click="handleStageClick"
        @mousedown="handleMouseDown"
        @mousemove="handleMouseMove"
        @mouseup="handleMouseUp"
        @mouseleave="handleMouseLeave"
    >
      <!-- ============================================================
           Layer 1：OFD 原生元素
           ============================================================ -->
      <v-layer>
        <v-rect :config="bgConfig" />

        <template v-for="element in page.elements" :key="element.id">
          <v-text
              v-if="element.type === 'TEXT'"
              :config="getTextConfig(element)"
              @click="handleElementClick($event, element.id)"
              @dragend="(e: any) => handleDragEnd(e, element.id)"
              @transformend="(e: any) => handleTransformEnd(e, element.id)"
          />
          <v-path
              v-else-if="element.type === 'PATH' && getPathData(element)"
              :config="getPathConfig(element)"
              @click="handleElementClick($event, element.id)"
              @dragend="(e: any) => handleDragEnd(e, element.id)"
              @transformend="(e: any) => handleTransformEnd(e, element.id)"
          />
          <v-image
              v-else-if="element.type === 'IMAGE' && !!imageMap[element.id]"
              :config="getImageConfig(element)"
              @click="handleElementClick($event, element.id)"
              @dragend="(e: any) => handleDragEnd(e, element.id)"
              @transformend="(e: any) => handleTransformEnd(e, element.id)"
          />
          <v-rect
              v-else-if="element.type === 'IMAGE' && getImageSrc(element) && !imageErrorMap[element.id]"
              :config="getImagePlaceholderConfig(element)"
              @click="handleElementClick($event, element.id)"
          />
          <v-rect
              v-else-if="element.type === 'IMAGE' && imageErrorMap[element.id]"
              :config="getImageFailedConfig(element)"
              @click="handleElementClick($event, element.id)"
          />
          <v-rect
              v-else-if="element.type === 'IMAGE'"
              :config="getImageNoSrcPlaceholderConfig(element)"
              @click="handleElementClick($event, element.id)"
          />
          <v-rect
              v-else
              :config="getFallbackConfig(element)"
              @click="handleElementClick($event, element.id)"
          />
        </template>

        <v-transformer
            v-if="!store.isAnnotationTool"
            ref="transformerRef"
            :config="transformerConfig"
        />
      </v-layer>

      <!-- ============================================================
           Layer 2：已保存的注释层
           ============================================================ -->
      <v-layer ref="annotationLayerRef">
        <template v-for="item in annotationConfigs" :key="item.ann.id">

          <v-rect
              v-if="item.ann.type === 'HIGHLIGHT'"
              :config="item.highlightCfg"
              @click="handleAnnotationClick($event, item.ann.id)"
              @dragend="(e: any) => handleAnnotationDragEnd(e, item.ann)"
              @transformend="(e: any) => handleAnnotationTransformEnd(e, item.ann)"
          />
          <v-line
              v-else-if="item.ann.type === 'UNDERLINE'"
              :config="item.underlineCfg"
              @click="handleAnnotationClick($event, item.ann.id)"
              @dragend="(e: any) => handleAnnotationDragEnd(e, item.ann)"
              @transformend="(e: any) => handleAnnotationTransformEnd(e, item.ann)"
          />
          <v-line
              v-else-if="item.ann.type === 'STRIKEOUT'"
              :config="item.strikeoutCfg"
              @click="handleAnnotationClick($event, item.ann.id)"
              @dragend="(e: any) => handleAnnotationDragEnd(e, item.ann)"
              @transformend="(e: any) => handleAnnotationTransformEnd(e, item.ann)"
          />
          <v-rect
              v-else-if="item.ann.type === 'RECTANGLE'"
              :config="item.rectangleCfg"
              @click="handleAnnotationClick($event, item.ann.id)"
              @dragend="(e: any) => handleAnnotationDragEnd(e, item.ann)"
              @transformend="(e: any) => handleAnnotationTransformEnd(e, item.ann)"
          />
          <v-ellipse
              v-else-if="item.ann.type === 'CIRCLE'"
              :config="item.circleCfg"
              @click="handleAnnotationClick($event, item.ann.id)"
              @dragend="(e: any) => handleAnnotationDragEnd(e, item.ann)"
              @transformend="(e: any) => handleAnnotationTransformEnd(e, item.ann)"
          />
          <v-arrow
              v-else-if="item.ann.type === 'ARROW'"
              :config="item.arrowCfg"
              @click="handleAnnotationClick($event, item.ann.id)"
              @dragend="(e: any) => handleAnnotationDragEnd(e, item.ann)"
              @transformend="(e: any) => handleAnnotationTransformEnd(e, item.ann)"
          />
          <v-line
              v-else-if="item.ann.type === 'FREEHAND'"
              :config="item.freehandCfg"
              @click="handleAnnotationClick($event, item.ann.id)"
              @dragend="(e: any) => handleAnnotationDragEnd(e, item.ann)"
              @transformend="(e: any) => handleAnnotationTransformEnd(e, item.ann)"
          />
          <v-group
              v-else-if="item.ann.type === 'TEXTBOX'"
              :config="item.groupCfg"
              @click="handleAnnotationClick($event, item.ann.id)"
              @dblclick="handleAnnotationDblClick($event, item.ann.id)"
              @dragend="(e: any) => handleAnnotationDragEnd(e, item.ann)"
              @transformend="(e: any) => handleAnnotationTransformEnd(e, item.ann)"
          >
            <v-rect :config="item.textBoxBgCfg" />
            <v-text :config="item.textBoxTxtCfg" />
          </v-group>
          <v-group
              v-else-if="item.ann.type === 'STICKYNOTE'"
              :config="item.groupCfg"
              @click="handleAnnotationClick($event, item.ann.id)"
              @dblclick="handleAnnotationDblClick($event, item.ann.id)"
              @dragend="(e: any) => handleAnnotationDragEnd(e, item.ann)"
              @transformend="(e: any) => handleAnnotationTransformEnd(e, item.ann)"
          >
            <v-rect :config="item.stickyBgCfg" />
            <v-text :config="item.stickyTxtCfg" />
          </v-group>
          <v-image
              v-else-if="item.ann.type === 'STAMP' && stampImageMap[item.ann.id]"
              :config="item.stampCfg"
              @click="handleAnnotationClick($event, item.ann.id)"
              @dragend="(e: any) => handleAnnotationDragEnd(e, item.ann)"
              @transformend="(e: any) => handleAnnotationTransformEnd(e, item.ann)"
          />

        </template>

        <v-transformer
            ref="annotationTransformerRef"
            :config="annotationTransformerConfig"
        />
      </v-layer>

      <!-- ============================================================
           Layer 3：临时绘制层
           ============================================================ -->
      <v-layer v-if="isDrawing">
        <v-rect
            v-if="['RECTANGLE', 'HIGHLIGHT'].includes(drawTool)"
            :config="previewRectConfig"
        />
        <v-ellipse
            v-else-if="drawTool === 'CIRCLE'"
            :config="previewEllipseConfig"
        />
        <v-line
            v-else-if="drawTool === 'FREEHAND'"
            :config="previewFreehandConfig"
        />
        <v-arrow
            v-else-if="drawTool === 'ARROW'"
            :config="previewArrowConfig"
        />
        <v-line
            v-else-if="drawTool === 'UNDERLINE'"
            :config="previewUnderlineConfig"
        />
        <v-line
            v-else-if="drawTool === 'STRIKEOUT'"
            :config="previewStrikeoutConfig"
        />
      </v-layer>
    </v-stage>

    <!-- 文本注释编辑弹窗 -->
    <el-dialog
        v-model="textEditVisible"
        title="编辑注释文本"
        width="400px"
        :append-to-body="true"
        @close="cancelTextEdit"
    >
      <el-input
          v-model="textEditContent"
          type="textarea"
          :rows="5"
          placeholder="请输入注释内容..."
      />
      <template #footer>
        <el-button @click="cancelTextEdit">取消</el-button>
        <el-button type="primary" @click="confirmTextEdit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useEditorStore } from '@/stores/editorStore'
import type { PageData, ElementData, AnnotationData } from '@/types'

// ─────────────────────────────────────────────
// Props / Store
// ─────────────────────────────────────────────
const props = defineProps<{ page: PageData; pageIndex: number }>()
const store = useEditorStore()

// ─────────────────────────────────────────────
// Konva 引用
// ─────────────────────────────────────────────
const stageRef                 = ref()
const transformerRef           = ref()
const annotationLayerRef       = ref()
const annotationTransformerRef = ref()

// ─────────────────────────────────────────────
// 常量
// ─────────────────────────────────────────────
const MM_TO_PX = 3.7795275591

/**
 * 支持通过锚点缩放的注释类型。
 * HIGHLIGHT / UNDERLINE / STRIKEOUT / ARROW / FREEHAND
 * 不在此列，选中后只显示边框，不显示缩放锚点。
 */
const RESIZABLE_TYPES = ['RECTANGLE', 'CIRCLE', 'TEXTBOX', 'STICKYNOTE', 'STAMP']

// ─────────────────────────────────────────────
// Stage 基础配置
// ─────────────────────────────────────────────
const canvasWidth  = computed(() => props.page.width  * MM_TO_PX * store.scale)
const canvasHeight = computed(() => props.page.height * MM_TO_PX * store.scale)

const stageConfig = computed(() => ({
  width:  canvasWidth.value,
  height: canvasHeight.value,
}))

const bgConfig = computed(() => ({
  x: 0, y: 0,
  width:  canvasWidth.value,
  height: canvasHeight.value,
  fill:   'white',
  name:   'page-bg',
}))

const transformerConfig = {
  rotateEnabled: true,
  boundBoxFunc: (oldBox: any, newBox: any) =>
      (newBox.width < 10 || newBox.height < 10) ? oldBox : newBox,
}

// ─────────────────────────────────────────────
// 注释 Transformer 配置
// ─────────────────────────────────────────────
const annotationTransformerConfig = computed(() => ({
  rotateEnabled:      false,
  keepRatio:          false,
  visible:            !!(store.currentTool === 'SELECT' && store.selectedAnnotationId),
  borderStroke:       '#1a73e8',
  borderStrokeWidth:  2,
  anchorFill:         '#ffffff',
  anchorStroke:       '#1a73e8',
  anchorSize:         8,
  anchorCornerRadius: 2,
  enabledAnchors: [
    'top-left',    'top-center',    'top-right',
    'middle-left',                  'middle-right',
    'bottom-left', 'bottom-center', 'bottom-right',
  ],
  boundBoxFunc: (oldBox: any, newBox: any) =>
      (newBox.width < 10 || newBox.height < 10) ? oldBox : newBox,
}))

// ─────────────────────────────────────────────
// 单位换算
// ─────────────────────────────────────────────
function s(v: number)     { return v * MM_TO_PX * store.scale }
function px2mm(v: number) { return v / MM_TO_PX / store.scale }

// ─────────────────────────────────────────────
// OFD 元素图片缓存
// ─────────────────────────────────────────────
const imageMap      = reactive<Record<string, HTMLImageElement>>({})
const imageErrorMap = reactive<Record<string, boolean>>({})

watch(
    () => props.page.elements
        .filter(el => el.type === 'IMAGE')
        .map(el => ({ id: el.id, src: getImageSrc(el) })),
    (items) => {
      for (const item of items) {
        if (!item.src || imageMap[item.id]) continue
        imageErrorMap[item.id] = false
        const img = new window.Image()
        if (!item.src.startsWith('data:')) img.crossOrigin = 'anonymous'
        img.onload  = () => { imageMap[item.id] = img }
        img.onerror = () => {
          imageErrorMap[item.id] = true
          console.warn('[IMG 加载失败]', item.id, item.src.slice(0, 80))
        }
        img.src = item.src
      }
    },
    { immediate: true, deep: false }
)

// ─────────────────────────────────────────────
// 注释数据
// ─────────────────────────────────────────────
const currentPageAnnotations = computed<AnnotationData[]>(() => store.currentPageAnnotations)
const annotationConfigs = computed(() =>
    currentPageAnnotations.value.map(ann => ({
      ann,
      highlightCfg:   ann.type === 'HIGHLIGHT'   ? getHighlightConfig(ann)   : null,
      underlineCfg:   ann.type === 'UNDERLINE'   ? getUnderlineConfig(ann)   : null,
      strikeoutCfg:   ann.type === 'STRIKEOUT'   ? getStrikeoutConfig(ann)   : null,
      rectangleCfg:   ann.type === 'RECTANGLE'   ? getRectangleConfig(ann)   : null,
      circleCfg:      ann.type === 'CIRCLE'      ? getCircleConfig(ann)      : null,
      arrowCfg:       ann.type === 'ARROW'       ? getArrowConfig(ann)       : null,
      freehandCfg:    ann.type === 'FREEHAND'    ? getFreehandConfig(ann)    : null,
      groupCfg:       ['TEXTBOX','STICKYNOTE'].includes(ann.type) ? getAnnotationGroupConfig(ann) : null,
      textBoxBgCfg:   ann.type === 'TEXTBOX'     ? getTextBoxBgConfig(ann)   : null,
      textBoxTxtCfg:  ann.type === 'TEXTBOX'     ? getTextBoxTextConfig(ann) : null,
      stickyBgCfg:    ann.type === 'STICKYNOTE'  ? getStickyNoteBgConfig(ann): null,
      stickyTxtCfg:   ann.type === 'STICKYNOTE'  ? getStickyNoteTextConfig(ann): null,
      stampCfg:       ann.type === 'STAMP'       ? getStampConfig(ann)       : null,
    }))
)
// ─────────────────────────────────────────────
// 图章图片缓存
// ─────────────────────────────────────────────
const stampImageMap = reactive<Record<string, HTMLImageElement>>({})

watch(
    currentPageAnnotations,
    (anns) => {
      for (const ann of anns) {
        if (ann.type !== 'STAMP' || !ann.stampBase64 || stampImageMap[ann.id]) continue
        const img = new window.Image()
        img.onload = () => { stampImageMap[ann.id] = img }
        img.src = `data:image/png;base64,${ann.stampBase64}`
      }
    },
    { immediate: true, deep: false }
)

// ─────────────────────────────────────────────
// OFD 元素 Transformer 跟踪
// ─────────────────────────────────────────────
watch(() => store.selectedElementId, async (id) => {
  await nextTick()
  const transformer = transformerRef.value?.getNode()
  const stage       = stageRef.value?.getNode()
  if (!transformer || !stage) return
  if (id) {
    const node = stage.findOne(`#${id}`)
    if (node) {
      transformer.nodes([node])
      transformer.getLayer()?.batchDraw()
    }
  } else {
    transformer.nodes([])
    transformer.getLayer()?.batchDraw()
  }
})

// ─────────────────────────────────────────────
// 注释 Transformer 跟踪
// ─────────────────────────────────────────────
watch(() => store.selectedAnnotationId, async (id) => {
  await nextTick()
  const transformer = annotationTransformerRef.value?.getNode()
  if (!transformer) return

  if (id && store.currentTool === 'SELECT') {
    const layer = annotationLayerRef.value?.getNode()
    layer?.getChildren().forEach((n: any) => {
      console.log('layer子节点 name:', n.name(), 'type:', n.getType(), 'className:', n.getClassName())
    })
    if (!layer) {
      transformer.nodes([])
      transformer.getLayer()?.batchDraw()
      return
    }

    const node = layer.findOne((n: any) => n.name() === id)
    const ann  = store.currentPageAnnotations.find(a => a.id === id)

    // ✅ 加这几行
    console.log('=== Transformer 诊断 ===')
    console.log('id:', id)
    console.log('ann:', ann)
    console.log('ann.type:', ann?.type)
    console.log('canResize:', ann ? RESIZABLE_TYPES.includes(ann.type) : false)
    console.log('node:', node)
    console.log('node.name():', node?.name?.())
    console.log('transformer:', transformer)
    console.log('transformer.enabledAnchors 方法存在:', typeof transformer.enabledAnchors)

    if (node) {
      transformer.nodes([node])
      const canResize = ann ? RESIZABLE_TYPES.includes(ann.type) : false
      const anchors = canResize
          ? ['top-left','top-center','top-right','middle-left','middle-right','bottom-left','bottom-center','bottom-right']
          : []
      console.log('设置 enabledAnchors:', anchors)
      transformer.enabledAnchors(anchors)
      console.log('设置后 enabledAnchors():', transformer.enabledAnchors())
    } else {
      transformer.nodes([])
    }
  } else {
    transformer.nodes([])
  }

  transformer.getLayer()?.batchDraw()
})

// ─────────────────────────────────────────────
// 绘制状态 & 预览配置
// ─────────────────────────────────────────────
const isDrawing     = ref(false)
const drawTool      = ref('')
const drawStartX    = ref(0)
const drawStartY    = ref(0)
const drawCurX      = ref(0)
const drawCurY      = ref(0)
const drawingPoints = ref<number[]>([])

const previewRectConfig = computed(() => ({
  x:           s(Math.min(drawStartX.value, drawCurX.value)),
  y:           s(Math.min(drawStartY.value, drawCurY.value)),
  width:       s(Math.abs(drawCurX.value - drawStartX.value)),
  height:      s(Math.abs(drawCurY.value - drawStartY.value)),
  fill:        store.currentTool === 'HIGHLIGHT' ? store.annotationColor : 'transparent',
  opacity:     store.annotationOpacity,
  stroke:      store.currentTool === 'HIGHLIGHT' ? 'transparent' : store.annotationColor,
  strokeWidth: store.annotationLineWidth,
  dash:        [4, 3],
}))

const previewEllipseConfig = computed(() => {
  const rx = s(Math.abs(drawCurX.value - drawStartX.value)) / 2
  const ry = s(Math.abs(drawCurY.value - drawStartY.value)) / 2
  return {
    x:           s(Math.min(drawStartX.value, drawCurX.value)) + rx,
    y:           s(Math.min(drawStartY.value, drawCurY.value)) + ry,
    radiusX:     rx,
    radiusY:     ry,
    fill:        'transparent',
    stroke:      store.annotationColor,
    strokeWidth: store.annotationLineWidth,
    dash:        [4, 3],
  }
})

const previewFreehandConfig = computed(() => ({
  points:      drawingPoints.value,
  stroke:      store.annotationColor,
  strokeWidth: store.annotationLineWidth,
  lineCap:     'round' as const,
  lineJoin:    'round' as const,
  opacity:     store.annotationOpacity,
  tension:     0.4,
}))

const previewArrowConfig = computed(() => ({
  points: [
    s(drawStartX.value), s(drawStartY.value),
    s(drawCurX.value),   s(drawCurY.value),
  ],
  stroke:        store.annotationColor,
  strokeWidth:   store.annotationLineWidth,
  fill:          store.annotationColor,
  opacity:       store.annotationOpacity,
  pointerLength: 12,
  pointerWidth:  8,
  lineJoin:      'round' as const,
}))

const previewUnderlineConfig = computed(() => ({
  points: [
    s(drawStartX.value), s(drawCurY.value),
    s(drawCurX.value),   s(drawCurY.value),
  ],
  stroke:      store.annotationColor,
  strokeWidth: store.annotationLineWidth,
}))

const previewStrikeoutConfig = computed(() => {
  const midY = (drawStartY.value + drawCurY.value) / 2
  return {
    points: [
      s(drawStartX.value), s(midY),
      s(drawCurX.value),   s(midY),
    ],
    stroke:      store.annotationColor,
    strokeWidth: store.annotationLineWidth,
  }
})

// ─────────────────────────────────────────────
// 文本注释编辑弹窗
// ─────────────────────────────────────────────
const textEditVisible  = ref(false)
const textEditContent  = ref('')
const textEditTargetId = ref<string | null>(null)
let pendingTextAnn: Omit<AnnotationData, 'id' | 'createdAt' | 'updatedAt'> | null = null

function openTextEdit(ann?: AnnotationData) {
  if (ann) {
    textEditTargetId.value = ann.id
    textEditContent.value  = ann.content ?? ''
  } else {
    textEditTargetId.value = null
    textEditContent.value  = ''
  }
  textEditVisible.value = true
}

async function confirmTextEdit() {
  const text = textEditContent.value.trim()
  if (!text) { ElMessage.warning('注释内容不能为空'); return }
  if (textEditTargetId.value) {
    await store.updateAnnotation(textEditTargetId.value, { content: text })
  } else if (pendingTextAnn) {
    await store.addAnnotation({ ...pendingTextAnn, content: text })
    pendingTextAnn = null
  }
  textEditVisible.value = false
}

function cancelTextEdit() {
  pendingTextAnn        = null
  textEditVisible.value = false
}

// ─────────────────────────────────────────────
// 获取 Stage 鼠标坐标（mm）
// ─────────────────────────────────────────────
function getStagePos(): { x: number; y: number } | null {
  const stage = stageRef.value?.getNode()
  if (!stage) return null
  const pos = stage.getPointerPosition()
  if (!pos) return null
  return { x: px2mm(pos.x), y: px2mm(pos.y) }
}

// ─────────────────────────────────────────────
// 鼠标事件
// ─────────────────────────────────────────────
function handleMouseDown(e: any) {
  if (!store.isAnnotationTool) return
  if (e.evt?.button !== 0) return
  if (['STAMP', 'TEXTBOX', 'STICKYNOTE'].includes(store.currentTool)) return
  const pos = getStagePos()
  if (!pos) return
  isDrawing.value     = true
  drawTool.value      = store.currentTool
  drawStartX.value    = pos.x
  drawStartY.value    = pos.y
  drawCurX.value      = pos.x
  drawCurY.value      = pos.y
  drawingPoints.value = [s(pos.x), s(pos.y)]
}

function handleMouseMove(e: any) {
  if (!isDrawing.value) return
  const pos = getStagePos()
  if (!pos) return
  drawCurX.value = pos.x
  drawCurY.value = pos.y
  if (drawTool.value === 'FREEHAND') {
    drawingPoints.value.push(s(pos.x), s(pos.y))
  }
}

async function handleMouseUp() {
  if (!isDrawing.value) return
  isDrawing.value = false
  const pos = getStagePos()
  if (!pos) return
  const x      = Math.min(drawStartX.value, pos.x)
  const y      = Math.min(drawStartY.value, pos.y)
  const width  = Math.abs(pos.x - drawStartX.value)
  const height = Math.abs(pos.y - drawStartY.value)
  if (drawTool.value !== 'FREEHAND' && width < 1 && height < 1) return
  if (drawTool.value === 'FREEHAND' && drawingPoints.value.length < 6) return
  await commitAnnotation(drawTool.value, x, y, width, height)
}

function handleMouseLeave() {
  if (isDrawing.value) isDrawing.value = false
}

function handleStageClick(e: any) {
  const name         = typeof e.target?.name === 'function' ? e.target.name() : ''
  const isBackground = e.target === e.target.getStage() || name === 'page-bg'
  if (!store.isAnnotationTool) {
    if (isBackground) store.selectElement(null)
    return
  }
  if (isBackground) store.selectAnnotation(null)
  if (!isBackground) return
  const pos = getStagePos()
  if (!pos) return
  if (['TEXTBOX', 'STICKYNOTE'].includes(store.currentTool)) {
    pendingTextAnn = {
      type:        store.currentTool as any,
      pageIndex:   props.pageIndex,
      x:           pos.x,
      y:           pos.y,
      width:       60,
      height:      30,
      opacity:     store.annotationOpacity,
      fontSize:    12,
      fontColor:   '#000000',
      color:       store.currentTool === 'STICKYNOTE' ? '#FFFACD' : 'transparent',
      strokeColor: '#999999',
      lineWidth:   1,
    }
    openTextEdit()
  }
}

function handleElementClick(e: any, elementId: string) {
  if (store.isAnnotationTool) return
  e.cancelBubble = true
  store.selectElement(elementId)
}

function handleAnnotationClick(e: any, id: string) {
  e.cancelBubble = true
  if (store.currentTool === 'SELECT') store.selectAnnotation(id)
}

function handleAnnotationDblClick(e: any, id: string) {
  e.cancelBubble = true
  const ann = currentPageAnnotations.value.find(a => a.id === id)
  if (ann && ['TEXTBOX', 'STICKYNOTE'].includes(ann.type)) openTextEdit(ann)
}

// ─────────────────────────────────────────────
// ✅ 注释拖拽结束
//
// 核心原则：
//   Konva draggable 节点在 dragend 时，node.x() / node.y()
//   返回的是节点当前在 layer 内的【绝对像素坐标】。
//
//   对于 RECT 类（HIGHLIGHT / RECTANGLE / TEXTBOX / STICKYNOTE / STAMP）：
//     渲染时 x = s(ann.x)，拖动后 node.x() = 新的绝对 px
//     → newX(mm) = px2mm(node.x())
//     → 重置 node.x(s(newX)) 让 Konva 不再持有位移
//
//   对于 ELLIPSE（CIRCLE）：
//     渲染时 x = s(ann.x) + rx（圆心），拖动后 node.x() = 新圆心 px
//     → newCx(mm) = px2mm(node.x())
//     → newX(mm)  = newCx - ann.width/2
//     → 重置 node.x(s(newX) + rx)
//
//   对于 LINE 类（UNDERLINE / STRIKEOUT / ARROW / FREEHAND）：
//     这些节点本身 x=0，y=0，points 存绝对坐标；
//     拖动后 node.x() = 位移 px（因为起始 x=0）
//     → dx(mm) = px2mm(node.x())
//     → newX = ann.x + dx
//     → 重置 node.x(0) / node.y(0)
// ─────────────────────────────────────────────
async function handleAnnotationDragEnd(e: any, ann: AnnotationData) {
  const node = e.target

  let newX: number
  let newY: number

  if (ann.type === 'CIRCLE') {
    // 圆心坐标 → 左上角
    const newCx = px2mm(node.x())
    const newCy = px2mm(node.y())
    newX = newCx - (ann.width  ?? 0) / 2
    newY = newCy - (ann.height ?? 0) / 2
    // 重置回圆心坐标
    node.x(s(newX) + s((ann.width  ?? 0) / 2))
    node.y(s(newY) + s((ann.height ?? 0) / 2))
  } else if (['UNDERLINE', 'STRIKEOUT', 'ARROW', 'FREEHAND'].includes(ann.type)) {
    // 这些节点渲染时 x=0/y=0，拖动后的 x/y 就是位移
    const dx = px2mm(node.x())
    const dy = px2mm(node.y())
    newX = ann.x + dx
    newY = ann.y + dy
    node.x(0)
    node.y(0)
  } else {
    // HIGHLIGHT / RECTANGLE / TEXTBOX / STICKYNOTE / STAMP / GROUP
    // 渲染时 x = s(ann.x)，拖动后 node.x() 是新的绝对 px
    newX = px2mm(node.x())
    newY = px2mm(node.y())
    node.x(s(newX))
    node.y(s(newY))
  }

  await store.updateAnnotation(ann.id, { x: newX, y: newY })
  await nextTick()
  node.getLayer()?.batchDraw()
}

// ─────────────────────────────────────────────
// 注释变换结束（锚点缩放）
//
// 只有 RESIZABLE_TYPES 会进入此逻辑（其余类型 enabledAnchors=[]）。
//
// RECT 类（RECTANGLE / TEXTBOX / STICKYNOTE / STAMP）：
//   Transformer 缩放时会修改 scaleX/scaleY，
//   node.x/y 是左上角绝对 px（Transformer 会移动它），
//   node.width/height 是原始尺寸，需乘以 scale 才是新尺寸。
//   → 清空 scale，将新尺寸固化到 width/height。
//
// CIRCLE（v-ellipse）：
//   node.x/y 是圆心绝对 px，
//   node.radiusX/Y 是原始半径，需乘以 scale。
//   → 清空 scale，将新半径固化到 radiusX/Y，
//   → store 存左上角 (x,y) + width/height。
// ─────────────────────────────────────────────
async function handleAnnotationTransformEnd(e: any, ann: AnnotationData) {
  const node   = e.target
  const scaleX = node.scaleX()
  const scaleY = node.scaleY()

  node.scaleX(1)
  node.scaleY(1)

  let newX: number
  let newY: number
  let newWidth: number
  let newHeight: number

  if (ann.type === 'CIRCLE') {
    const newRx = node.radiusX() * scaleX
    const newRy = node.radiusY() * scaleY
    const newCx = px2mm(node.x())
    const newCy = px2mm(node.y())
    newWidth  = px2mm(newRx) * 2
    newHeight = px2mm(newRy) * 2
    newX      = newCx - newWidth  / 2
    newY      = newCy - newHeight / 2
    node.radiusX(newRx)
    node.radiusY(newRy)
    node.x(s(newX) + newRx)
    node.y(s(newY) + newRy)

  } else if (['TEXTBOX', 'STICKYNOTE'].includes(ann.type)) {
    newX      = px2mm(node.x())
    newY      = px2mm(node.y())
    newWidth  = (ann.width  ?? 60) * scaleX
    newHeight = (ann.height ?? 30) * scaleY

    // 固化：更新子节点的实际尺寸（bg rect 和 text）
    const bg   = node.findOne('Rect')
    const text = node.findOne('Text')
    if (bg) {
      bg.width(s(newWidth))
      bg.height(s(newHeight))
    }
    if (text) {
      text.width(s(newWidth)  - (ann.type === 'STICKYNOTE' ? 12 : 8))
      text.height(s(newHeight) - (ann.type === 'STICKYNOTE' ? 12 : 8))
    }

  } else {
    // RECTANGLE / STAMP
    newX      = px2mm(node.x())
    newY      = px2mm(node.y())
    newWidth  = px2mm(node.width()  * scaleX)
    newHeight = px2mm(node.height() * scaleY)
    node.width(s(newWidth))
    node.height(s(newHeight))
    node.x(s(newX))
    node.y(s(newY))
  }

  await store.updateAnnotation(ann.id, {
    x: newX, y: newY,
    width: newWidth, height: newHeight,
  })

  await nextTick()
  const transformer = annotationTransformerRef.value?.getNode()
  if (transformer) {
    transformer.nodes([node])
    transformer.getLayer()?.batchDraw()
  }
}

// ─────────────────────────────────────────────
// 提交注释
// ─────────────────────────────────────────────
async function commitAnnotation(
    tool: string,
    x: number, y: number,
    width: number, height: number,
) {
  const base: Partial<AnnotationData> = {
    type:        tool as any,
    pageIndex:   props.pageIndex,
    x, y, width, height,
    opacity:     store.annotationOpacity,
    color:       store.annotationColor,
    strokeColor: store.annotationColor,
    lineWidth:   store.annotationLineWidth,
  }

  switch (tool) {
    case 'HIGHLIGHT':
      base.strokeColor = 'transparent'
      break
    case 'UNDERLINE':
    case 'STRIKEOUT':
      base.height = 0
      break
    case 'ARROW':
      base.pathPoints = [
        [0, 0],
        [width, height],
      ]
      break
    case 'FREEHAND':
      base.pathPoints = []
      for (let i = 0; i < drawingPoints.value.length; i += 2) {
        const ptX = px2mm(drawingPoints.value[i])
        const ptY = px2mm(drawingPoints.value[i + 1])
        base.pathPoints.push([ptX - x, ptY - y])
      }
      break
  }

  const result = await store.addAnnotation(base as any)
  if (result) {
    ElMessage.success({ message: '注释已添加', duration: 1200, showClose: false })
  } else {
    ElMessage.error('注释保存失败，请检查后端连接')
  }
}

// ─────────────────────────────────────────────
// OFD 元素拖拽 / 变换
// ─────────────────────────────────────────────
function handleDragEnd(e: any, elementId: string) {
  store.updateElement(props.pageIndex, elementId, {
    x: px2mm(e.target.x()),
    y: px2mm(e.target.y()),
  })
}

function handleTransformEnd(e: any, elementId: string) {
  const node = e.target
  store.updateElement(props.pageIndex, elementId, {
    x:        px2mm(node.x()),
    y:        px2mm(node.y()),
    scaleX:   node.scaleX(),
    scaleY:   node.scaleY(),
    rotation: node.rotation(),
  })
}

// ─────────────────────────────────────────────
// OFD 元素 Config
// ─────────────────────────────────────────────
function getImageSrc(element: ElementData): string {
  const e = element as any
  if (typeof e.imageBase64 === 'string' && e.imageBase64.startsWith('data:')) return e.imageBase64
  if (typeof e.imageData  === 'string' && e.imageData.startsWith('data:'))   return e.imageData
  if (typeof e.imageUrl   === 'string' && e.imageUrl.trim())                  return e.imageUrl.trim()
  return ''
}

function getPathData(element: ElementData): string {
  const e = element as any
  return (typeof e.pathData === 'string' && e.pathData.trim()) ? e.pathData.trim() : ''
}

function getTextConfig(element: ElementData) {
  const isSelected = store.selectedElementId === element.id
  return {
    id:          element.id,
    x:           s(element.x),
    y:           s(element.y),
    width:       s(element.width),
    height:      s(element.height),
    rotation:    element.rotation ?? 0,
    draggable:   !store.isAnnotationTool,
    text:        element.content  ?? '',
    fontSize:    (element.fontSize ?? 3) * MM_TO_PX * store.scale,
    fontFamily:  element.fontFamily ?? 'sans-serif',
    fontStyle:   `${element.bold ? 'bold' : 'normal'} ${element.italic ? 'italic' : ''}`.trim(),
    fill:        element.color    ?? '#000000',
    stroke:      isSelected ? '#1a73e8' : undefined,
    strokeWidth: isSelected ? 0.5 : 0,
  }
}

function getPathConfig(element: ElementData) {
  const e          = element as any
  const isSelected = store.selectedElementId === element.id
  const sc         = MM_TO_PX * store.scale
  return {
    id:          element.id,
    x: 0, y: 0,
    scaleX: sc, scaleY: sc,
    rotation:    element.rotation ?? 0,
    draggable:   !store.isAnnotationTool,
    data:        getPathData(element),
    fill:        e.fillColor   ?? 'transparent',
    stroke:      isSelected ? '#1a73e8' : (e.strokeColor ?? '#222222'),
    strokeWidth: isSelected ? (2 / sc) : ((e.lineWidth ?? 0.3) / sc * 0.5),
    strokeScaleEnabled: false,
  }
}

function getImageConfig(element: ElementData) {
  const isSelected = store.selectedElementId === element.id
  return {
    id:          element.id,
    x:           s(element.x),
    y:           s(element.y),
    width:       s(element.width),
    height:      s(element.height),
    rotation:    element.rotation ?? 0,
    draggable:   !store.isAnnotationTool,
    image:       imageMap[element.id],
    stroke:      isSelected ? '#1a73e8' : undefined,
    strokeWidth: isSelected ? 1 : 0,
  }
}

function getImagePlaceholderConfig(element: ElementData) {
  return {
    id: element.id, x: s(element.x), y: s(element.y),
    width: s(element.width), height: s(element.height),
    fill: 'rgba(120,120,120,0.08)', stroke: 'rgba(120,120,120,0.35)',
    strokeWidth: 1, draggable: !store.isAnnotationTool,
  }
}

function getImageFailedConfig(element: ElementData) {
  return {
    id: element.id, x: s(element.x), y: s(element.y),
    width: s(element.width), height: s(element.height),
    fill: 'rgba(255,77,79,0.12)', stroke: '#ff4d4f',
    strokeWidth: 1, draggable: !store.isAnnotationTool,
  }
}

function getImageNoSrcPlaceholderConfig(element: ElementData) {
  return {
    id: element.id, x: s(element.x), y: s(element.y),
    width: s(element.width), height: s(element.height),
    fill: 'rgba(255,0,0,0.05)', stroke: '#ffaaaa',
    strokeWidth: 1, draggable: !store.isAnnotationTool,
  }
}

function getFallbackConfig(element: ElementData) {
  const isSelected = store.selectedElementId === element.id
  return {
    id: element.id, x: s(element.x), y: s(element.y),
    width: s(element.width), height: s(element.height),
    fill: 'transparent',
    stroke: isSelected ? '#1a73e8' : 'transparent',
    strokeWidth: isSelected ? 1 : 0,
    draggable: !store.isAnnotationTool,
  }
}

// ─────────────────────────────────────────────
// 注释 Config
// ─────────────────────────────────────────────
function annDraggable() { return store.currentTool === 'SELECT' }

function getAnnotationGroupConfig(ann: AnnotationData) {
  return {
    name:      ann.id,
    x:         s(ann.x),
    y:         s(ann.y),
    draggable: annDraggable(),
    listening: true,
  }
}

function getHighlightConfig(ann: AnnotationData) {
  return {
    name:        ann.id,
    x:           s(ann.x),
    y:           s(ann.y),
    width:       s(ann.width),
    height:      s(ann.height),
    fill:        ann.color   ?? '#000000',
    opacity:     ann.opacity ?? 0.45,
    stroke:      'transparent',
    strokeWidth: 0,
    listening:   true,
    draggable:   annDraggable(),
  }
}

function getRectangleConfig(ann: AnnotationData) {
  return {
    name:        ann.id,
    x:           s(ann.x),
    y:           s(ann.y),
    width:       s(ann.width),
    height:      s(ann.height),
    fill:        'transparent',
    stroke:      ann.strokeColor ?? ann.color ?? '#000000',
    strokeWidth: ann.lineWidth   ?? 2,
    opacity:     ann.opacity     ?? 1,
    listening:   true,
    draggable:   annDraggable(),
  }
}

// CIRCLE：store 存左上角(x,y) + width/height，渲染时换算圆心
function getCircleConfig(ann: AnnotationData) {
  const rx = s((ann.width  ?? 0) / 2)
  const ry = s((ann.height ?? 0) / 2)
  return {
    name:        ann.id,
    x:           s(ann.x) + rx,
    y:           s(ann.y) + ry,
    radiusX:     rx,
    radiusY:     ry,
    fill:        'transparent',
    stroke:      ann.strokeColor ?? ann.color ?? '#000000',
    strokeWidth: ann.lineWidth   ?? 2,
    opacity:     ann.opacity     ?? 1,
    listening:   true,
    draggable:   annDraggable(),
  }
}

// UNDERLINE / STRIKEOUT：节点自身 x/y 不设（默认0），points 存绝对 px
function getUnderlineConfig(ann: AnnotationData) {
  const baseY = s(ann.y + ann.height)
  return {
    name:        ann.id,
    points:      [s(ann.x), baseY, s(ann.x + ann.width), baseY],
    stroke:      ann.strokeColor ?? ann.color ?? '#000000',
    strokeWidth: ann.lineWidth   ?? 2,
    opacity:     ann.opacity     ?? 1,
    listening:   true,
    draggable:   annDraggable(),
  }
}

function getStrikeoutConfig(ann: AnnotationData) {
  const midY = s(ann.y + ann.height / 2)
  return {
    name:        ann.id,
    points:      [s(ann.x), midY, s(ann.x + ann.width), midY],
    stroke:      ann.strokeColor ?? ann.color ?? '#FF0000',
    strokeWidth: ann.lineWidth   ?? 2,
    opacity:     ann.opacity     ?? 1,
    listening:   true,
    draggable:   annDraggable(),
  }
}

// ARROW：pathPoints 存相对于锚点(x,y) 的 mm 偏移，渲染时转绝对 px
function getArrowConfig(ann: AnnotationData) {
  const pts = ann.pathPoints ?? [[0, 0], [ann.width ?? 0, ann.height ?? 0]]
  const absPts: number[] = []
  for (const pt of pts) {
    absPts.push(s(ann.x + pt[0]))
    absPts.push(s(ann.y + pt[1]))
  }
  return {
    name:          ann.id,
    points:        absPts,
    stroke:        ann.strokeColor ?? ann.color ?? '#000000',
    strokeWidth:   ann.lineWidth   ?? 2,
    fill:          ann.strokeColor ?? ann.color ?? '#000000',
    opacity:       ann.opacity     ?? 1,
    pointerLength: 12,
    pointerWidth:  8,
    lineJoin:      'round' as const,
    listening:     true,
    draggable:     annDraggable(),
  }
}

// FREEHAND：pathPoints 存相对于锚点(x,y) 的 mm 偏移，渲染时转绝对 px
function getFreehandConfig(ann: AnnotationData) {
  const pts = ann.pathPoints ?? []
  const absPts: number[] = []
  for (const pt of pts) {
    absPts.push(s(ann.x + pt[0]))
    absPts.push(s(ann.y + pt[1]))
  }
  return {
    name:        ann.id,
    points:      absPts,
    stroke:      ann.strokeColor ?? ann.color ?? '#000000',
    strokeWidth: ann.lineWidth   ?? 2,
    lineCap:     'round' as const,
    lineJoin:    'round' as const,
    tension:     0.4,
    opacity:     ann.opacity ?? 1,
    listening:   true,
    draggable:   annDraggable(),
  }
}

function getTextBoxBgConfig(ann: AnnotationData) {
  return {
    x: 0, y: 0,
    width:        s(ann.width),
    height:       s(ann.height),
    fill:         ann.color       ?? '#FFFFFF',
    stroke:       ann.strokeColor ?? '#AAAAAA',
    strokeWidth:  ann.lineWidth   ?? 1,
    opacity:      ann.opacity     ?? 1,
    cornerRadius: 2,
    listening:    true,
  }
}

function getTextBoxTextConfig(ann: AnnotationData) {
  return {
    x: 4, y: 4,
    width:         s(ann.width)  - 8,
    height:        s(ann.height) - 8,
    text:          ann.content   ?? '',
    fontSize:      (ann.fontSize ?? 12) * MM_TO_PX * store.scale,
    fontFamily:    'Microsoft YaHei, sans-serif',
    fill:          ann.fontColor ?? '#000000',
    align:         'left'  as const,
    verticalAlign: 'top'   as const,
    wrap:          'word'  as const,
    listening:     true,
  }
}

function getStickyNoteBgConfig(ann: AnnotationData) {
  return {
    x: 0, y: 0,
    width:        s(ann.width),
    height:       s(ann.height),
    fill:         ann.color       ?? '#FFFACD',
    stroke:       ann.strokeColor ?? '#E6C619',
    strokeWidth:  ann.lineWidth   ?? 1,
    cornerRadius: 4,
    opacity:      ann.opacity     ?? 1,
    listening:    true,
  }
}

function getStickyNoteTextConfig(ann: AnnotationData) {
  return {
    x: 6, y: 6,
    width:         s(ann.width)  - 12,
    height:        s(ann.height) - 12,
    text:          ann.content   ?? '',
    fontSize:      (ann.fontSize ?? 12) * MM_TO_PX * store.scale,
    fontFamily:    'Microsoft YaHei, sans-serif',
    fill:          ann.fontColor ?? '#333333',
    align:         'left'  as const,
    verticalAlign: 'top'   as const,
    wrap:          'word'  as const,
    listening:     true,
  }
}

function getStampConfig(ann: AnnotationData) {
  return {
    name:      ann.id,
    image:     stampImageMap[ann.id],
    x:         s(ann.x),
    y:         s(ann.y),
    width:     s(ann.width),
    height:    s(ann.height),
    opacity:   ann.opacity ?? 1,
    listening: true,
    draggable: annDraggable(),
  }
}
</script>