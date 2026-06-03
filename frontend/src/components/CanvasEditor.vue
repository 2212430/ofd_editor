<template>
  <div
      ref="wrapperRef"
      class="canvas-wrapper"
      :class="{
        'canvas-wrapper--offscreen': offscreen,
        'cursor-crosshair': !offscreen && store.isAnnotationTool,
        'cursor-hand': !offscreen && store.isHandTool,
        'cursor-grabbing': !offscreen && store.isHandTool && isPanning,
      }"
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
          <v-group
              v-if="element.type === 'TEXT' && isCurrencySplitText(element)"
              :config="getCurrencyGroupConfig(element)"
              @click="handleElementClick($event, element.id)"
              @dragend="(e: any) => handleDragEnd(e, element.id)"
              @transformend="(e: any) => handleTransformEnd(e, element.id)"
          >
            <v-text :config="getCurrencyHeadConfig(element)" />
            <v-text :config="getCurrencyTailConfig(element)" />
          </v-group>
          <v-text
              v-else-if="element.type === 'TEXT'"
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
              v-else-if="element.type === 'SEAL' && !!imageMap[element.id]"
              :config="getSealConfig(element)"
          />
          <v-rect
              v-else-if="element.type === 'SEAL' && getImageSrc(element) && !imageErrorMap[element.id]"
              :config="getSealPlaceholderConfig(element)"
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
            v-if="!offscreen && store.isSelectTool"
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
import { computed, nextTick, onUnmounted, reactive, ref, watch, withDefaults } from 'vue'
import { ElMessage } from 'element-plus'
import { useEditorStore } from '@/stores/editorStore'
import type { PageData, ElementData, AnnotationData } from '@/types'
import { konvaStageRotationConfig, normalizeViewRotation } from '@/utils/viewRotation'

// ─────────────────────────────────────────────
// Props / Store
// ─────────────────────────────────────────────
const props = withDefaults(
    defineProps<{
      page: PageData
      pageIndex: number
      /** 离屏渲染：仅用于缩略图截图，不响应交互 */
      offscreen?: boolean
      /** 固定缩放（离屏截图时用 1，避免影响主画布 store.scale） */
      fixedScale?: number
    }>(),
    { offscreen: false },
)
const store = useEditorStore()

const renderScale = computed(() => props.fixedScale ?? store.scale)

// ─────────────────────────────────────────────
// Konva 引用
// ─────────────────────────────────────────────
const stageRef                 = ref()
const wrapperRef               = ref<HTMLElement>()
const transformerRef           = ref()
const annotationLayerRef       = ref()
const annotationTransformerRef = ref()

/**
 * 撤销 / 重做兜底刷新：
 * store.renderVersion 在 undo / redo 后 +1。
 *
 * 之前发现 vue-konva 的 `watch(() => props.config, …, { deep:true })`
 * 在 applyInPlace 之后并不会重新触发（props.config 由父组件每次 render 时新建，
 * 但父组件的 render effect 在某些 element 属性变化下不一定调度执行，
 * 导致 Konva 节点的 text / fontSize / fill 停留在撤销前的值）。
 *
 * 这里走两条路兜底：
 *   1) 等下一个 tick（Vue 把可能的 patch 都跑完），从 stage 上把所有 Konva.Text 节点找出来；
 *      根据当前 store 里的 element 数据手动写一遍 text / fontSize / fill 等关键字段，
 *      并清掉 Konva 内部的 measure cache。
 *   2) 最后再来一次 stage.draw() 强制重绘。
 */
watch(
    () => store.renderVersion,
    () => {
      nextTick(() => {
        const stage = stageRef.value?.getNode?.()
        if (!stage) return

        const page = props.page
        if (page) {
          // Konva 节点用 element.id 作为内部 id 属性；按 id 找回节点，
          // 直接把最新 store 数据写到节点上，绕开 vue-konva deep watcher 漏发的情况
          for (const el of page.elements) {
            const node: any = stage.findOne('#' + el.id)
            if (!node) continue
            if (el.type === 'TEXT') {
              if (isCurrencySplitText(el)) {
                const grp = stage.findOne('#' + el.id)
                grp?.setAttrs(getCurrencyGroupConfig(el))
                stage.findOne('#' + el.id + '-cur-h')?.setAttrs(getCurrencyHeadConfig(el))
                stage.findOne('#' + el.id + '-cur-t')?.setAttrs(getCurrencyTailConfig(el))
              } else {
                node.setAttrs(getTextConfig(el))
              }
            } else if (el.type === 'PATH') {
              node.setAttrs(getPathConfig(el))
            } else if (el.type === 'IMAGE' && imageMap[el.id]) {
              node.setAttrs(getImageConfig(el))
            }
            node.clearCache?.()
          }
        }
        stage.draw()
      })
    },
)

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
const canvasWidth  = computed(() => props.page.width  * MM_TO_PX * renderScale.value)
const canvasHeight = computed(() => props.page.height * MM_TO_PX * renderScale.value)

const stageRotation = computed(() =>
    konvaStageRotationConfig(
        props.page.width,
        props.page.height,
        renderScale.value,
        props.offscreen ? 0 : store.viewRotation,
    ),
)

const stageConfig = computed(() => {
  const rot = stageRotation.value
  return {
    width:     rot.stageWidth,
    height:    rot.stageHeight,
    rotation:  rot.rotation,
    offsetX:   rot.offsetX,
    offsetY:   rot.offsetY,
    x:         rot.x,
    y:         rot.y,
    listening: !props.offscreen,
  }
})

watch(
    () => store.viewRotation,
    () => {
      nextTick(() => stageRef.value?.getNode?.()?.batchDraw?.())
    },
)

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
function s(v: number)     { return v * MM_TO_PX * renderScale.value }
function px2mm(v: number) { return v / MM_TO_PX / renderScale.value }

// ─────────────────────────────────────────────
// OFD 元素图片缓存
// ─────────────────────────────────────────────
const imageMap      = reactive<Record<string, HTMLImageElement>>({})
const imageErrorMap = reactive<Record<string, boolean>>({})

watch(
    () => props.page.elements
        .filter(el => el.type === 'IMAGE' || el.type === 'SEAL')
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
const pageAnnotations = computed<AnnotationData[]>(() =>
    store.annotationsMap[props.pageIndex] ?? [],
)

function ensureActivePageForInteraction() {
  if (props.offscreen) return
  if (store.currentPageIndex !== props.pageIndex) {
    store.setCurrentPage(props.pageIndex, { preserveSelection: true })
  }
}
const annotationConfigs = computed(() =>
    pageAnnotations.value.map(ann => ({
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

function stampImageSrc(stampBase64?: string): string {
  if (!stampBase64) return ''
  if (stampBase64.startsWith('data:')) return stampBase64
  return `data:image/png;base64,${stampBase64}`
}

function toRawBase64(dataUrl: string): string {
  const comma = dataUrl.indexOf(',')
  return comma >= 0 ? dataUrl.slice(comma + 1) : dataUrl
}

watch(
    pageAnnotations,
    (anns) => {
      for (const ann of anns) {
        if (ann.type !== 'STAMP' || !ann.stampBase64 || stampImageMap[ann.id]) continue
        const img = new window.Image()
        img.onload = () => { stampImageMap[ann.id] = img }
        img.src = stampImageSrc(ann.stampBase64)
      }
    },
    { immediate: true, deep: false }
)

// ─────────────────────────────────────────────
// OFD 元素 Transformer 跟踪
// ─────────────────────────────────────────────
async function refreshElementTransformer(elementId: string) {
  await nextTick()
  const transformer = transformerRef.value?.getNode()
  const stage       = stageRef.value?.getNode()
  if (!transformer || !stage || store.selectedElementId !== elementId) return
  const node = stage.findOne('#' + elementId)
  if (node) {
    transformer.nodes([node])
    transformer.getLayer()?.batchDraw()
  }
}

watch(() => store.selectedElementId, async (id) => {
  if (props.offscreen) return
  if (id) await refreshElementTransformer(id)
  else {
    await nextTick()
    const transformer = transformerRef.value?.getNode()
    if (!transformer) return
    transformer.nodes([])
    transformer.getLayer()?.batchDraw()
  }
})

// ─────────────────────────────────────────────
// 注释 Transformer 跟踪
// ─────────────────────────────────────────────
watch(() => store.selectedAnnotationId, async (id) => {
  if (props.offscreen) return
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
  const rot = props.offscreen ? 0 : normalizeViewRotation(store.viewRotation)
  if (rot === 0) {
    return { x: px2mm(pos.x), y: px2mm(pos.y) }
  }
  const local = stage.getAbsoluteTransform().copy().invert().point(pos)
  return { x: px2mm(local.x), y: px2mm(local.y) }
}

// ─────────────────────────────────────────────
// 手型工具：拖拽平移编辑区滚动条
// ─────────────────────────────────────────────
const isPanning = ref(false)
const suppressClick = ref(false)
const panStart = { clientX: 0, clientY: 0, scrollLeft: 0, scrollTop: 0, moved: false }

function getScrollContainer(): HTMLElement | null {
  return wrapperRef.value?.closest('.editor-area') as HTMLElement | null
}

function onPanMove(e: MouseEvent) {
  if (!isPanning.value) return
  const sc = getScrollContainer()
  if (!sc) return
  const dx = e.clientX - panStart.clientX
  const dy = e.clientY - panStart.clientY
  if (Math.abs(dx) > 2 || Math.abs(dy) > 2) panStart.moved = true
  sc.scrollLeft = panStart.scrollLeft - dx
  sc.scrollTop = panStart.scrollTop - dy
}

function onPanEnd() {
  window.removeEventListener('mousemove', onPanMove)
  window.removeEventListener('mouseup', onPanEnd)
  if (isPanning.value && panStart.moved) suppressClick.value = true
  isPanning.value = false
}

onUnmounted(() => {
  window.removeEventListener('mousemove', onPanMove)
  window.removeEventListener('mouseup', onPanEnd)
})

// ─────────────────────────────────────────────
// 鼠标事件
// ─────────────────────────────────────────────
function handleMouseDown(e: any) {
  if (props.offscreen) return
  if (store.isAnnotationTool) ensureActivePageForInteraction()
  if (store.isHandTool) {
    if (e.evt?.button !== 0) return
    const sc = getScrollContainer()
    if (!sc) return
    isPanning.value = true
    panStart.clientX = e.evt.clientX
    panStart.clientY = e.evt.clientY
    panStart.scrollLeft = sc.scrollLeft
    panStart.scrollTop = sc.scrollTop
    panStart.moved = false
    e.evt.preventDefault()
    window.addEventListener('mousemove', onPanMove)
    window.addEventListener('mouseup', onPanEnd)
    return
  }
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
  if (props.offscreen) return
  if (suppressClick.value) {
    suppressClick.value = false
    return
  }
  if (store.isHandTool) return

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
    return
  }
  if (store.currentTool === 'STAMP') {
    const stampSrc = store.pendingStampImage
    if (!stampSrc) {
      ElMessage.warning('请先在注释栏点击「导入图章」选择图片')
      return
    }
    void placeStampAt(pos.x, pos.y, stampSrc)
  }
}

// ─────────────────────────────────────────────
// 图章放置
// ─────────────────────────────────────────────
async function placeStampAt(clickX: number, clickY: number, dataUrl: string) {
  try {
    const { width: pxW, height: pxH } = await loadImageDimensions(dataUrl)
    let wMm = pxW / MM_TO_PX
    let hMm = pxH / MM_TO_PX
    const maxSize = 40
    if (wMm > maxSize || hMm > maxSize) {
      const scale = maxSize / Math.max(wMm, hMm)
      wMm *= scale
      hMm *= scale
    }
    const x = Math.max(0, clickX - wMm / 2)
    const y = Math.max(0, clickY - hMm / 2)

    const result = await store.addAnnotation({
      type: 'STAMP',
      pageIndex: props.pageIndex,
      x, y, width: wMm, height: hMm,
      opacity: store.annotationOpacity,
      stampBase64: toRawBase64(dataUrl),
    })
    if (result) {
      ElMessage.success({ message: '图章已添加', duration: 1200, showClose: false })
    } else {
      ElMessage.error('图章保存失败，请检查后端连接')
    }
  } catch (err: any) {
    ElMessage.error(err.message || '放置图章失败')
  }
}

function loadImageDimensions(src: string): Promise<{ width: number; height: number }> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve({ width: img.naturalWidth, height: img.naturalHeight })
    img.onerror = () => reject(new Error('无法解析图章图片'))
    img.src = src
  })
}

function handleElementClick(e: any, elementId: string) {
  if (suppressClick.value) return
  if (!store.isSelectTool) return
  ensureActivePageForInteraction()
  const el = props.page.elements.find(item => item.id === elementId)
  if (el?.type === 'SEAL') return
  e.cancelBubble = true
  store.selectElement(elementId)
}

function handleAnnotationClick(e: any, id: string) {
  e.cancelBubble = true
  if (suppressClick.value) return
  ensureActivePageForInteraction()
  if (store.currentTool === 'SELECT') store.selectAnnotation(id)
}

function handleAnnotationDblClick(e: any, id: string) {
  e.cancelBubble = true
  const ann = pageAnnotations.value.find(a => a.id === id)
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
      // 保留真实起点和终点，避免把起点固定成包围盒左上角导致方向失真
      // x/y 仍使用包围盒左上角，pathPoints 存相对该锚点的两端点
      base.pathPoints = [
        [drawStartX.value - x, drawStartY.value - y],
        [drawCurX.value - x, drawCurY.value - y],
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
async function handleDragEnd(e: any, elementId: string) {
  store.updateElement(props.pageIndex, elementId, {
    x: px2mm(e.target.x()),
    y: px2mm(e.target.y()),
  })
  await refreshElementTransformer(elementId)
}

async function handleTransformEnd(e: any, elementId: string) {
  const node = e.target
  const element = props.page.elements.find(el => el.id === elementId)

  if (element?.type === 'IMAGE') {
    const scaleX = node.scaleX()
    const scaleY = node.scaleY()
    const newWidth  = px2mm(node.width() * Math.abs(scaleX))
    const newHeight = px2mm(node.height() * Math.abs(scaleY))
    node.scaleX(1)
    node.scaleY(1)
    store.updateElement(props.pageIndex, elementId, {
      x:        px2mm(node.x()),
      y:        px2mm(node.y()),
      width:    newWidth,
      height:   newHeight,
      scaleX:   1,
      scaleY:   1,
      rotation: node.rotation(),
    })
  } else {
    store.updateElement(props.pageIndex, elementId, {
      x:        px2mm(node.x()),
      y:        px2mm(node.y()),
      scaleX:   node.scaleX(),
      scaleY:   node.scaleY(),
      rotation: node.rotation(),
    })
  }
  await refreshElementTransformer(elementId)
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

/**
 * 按字符大致猜测系统字体下的水平 advance（单位：em，即与 fontSize 同量纲）
 * OFD 原文常用嵌入字体的 Glyph 宽度排版；替换为系统字体后宽度会偏窄，因此需要估算
 * 自然渲染宽度，再用 letterSpacing 补差到 Boundary.w，避免片段间产生可见间隙。
 */
function approxAdvanceEm(ch: string): number {
  if (!ch || ch === '\n') return 0
  const code = ch.charCodeAt(0)
  // CJK 统一汉字 / 兼容汉字 / 全角符号 / 假名 / 谚文 → 全角字符
  if (
      (code >= 0x2e80 && code <= 0x9fff) ||
      (code >= 0xa000 && code <= 0xa4cf) ||
      (code >= 0xac00 && code <= 0xd7af) ||
      (code >= 0xf900 && code <= 0xfaff) ||
      (code >= 0xff00 && code <= 0xffef) ||
      (code >= 0x3000 && code <= 0x303f)
  ) return 1.0
  if (ch === ' ') return 0.32
  if (ch === '¥' || ch === '￥' || ch === '\u00a5' || ch === '\uffe5') return 0.72
  if (/[il.,;:'!|`]/.test(ch)) return 0.30
  if (/[A-Z0-9#&%@$]/.test(ch)) return 0.62
  return 0.55
}

function estimateNaturalWidthMm(text: string, fsMm: number): number {
  let sum = 0
  for (const ch of text) sum += approxAdvanceEm(ch)
  return sum * fsMm
}

function formatCurrencyDisplayText(text: string): string {
  return text.replace(/([¥￥])(?=\d)/g, '$1\u2002')
}

function normalizeCurrencyContent(raw: string): string {
  return raw.replace(/\s*\n\s*/g, '').trim()
}

function isCurrencySplitText(element: ElementData): boolean {
  if (element.verticalLayout || element.passwordGrid) return false
  const t = normalizeCurrencyContent(element.content ?? '')
  if (/^[¥￥][\d.,]+$/.test(t)) return true
  return /[¥￥][\d.,]+$/.test(t) && t.length <= 24
}

function getCurrencySplitParts(content: string): { prefix: string; symbol: string; tail: string } | null {
  const t = normalizeCurrencyContent(content)
  const m = t.match(/^(.*?)([¥￥])([\d.,]+)$/)
  if (m) return { prefix: m[1], symbol: m[2], tail: m[3] }
  return null
}

/** 数字段 x：前缀宽 + max(OFD 字距, Web ¥ 实际宽) */
function currencyTailOffsetPx(prefix: string, fsMm: number, glyphAdvanceMm?: number): number {
  const sc = MM_TO_PX * renderScale.value
  const prefixW = prefix ? estimateNaturalWidthMm(prefix, fsMm) * sc : 0
  const ofdGap = typeof glyphAdvanceMm === 'number' && glyphAdvanceMm > 0
      ? glyphAdvanceMm * sc : fsMm * sc * 0.55
  const yuanWeb = fsMm * sc * 0.82
  return prefixW + Math.max(ofdGap, yuanWeb) + fsMm * sc * 0.05
}

function buildTextFontStyle(element: ElementData, fsPx: number) {
  const fontStack = [element.fontFamily, 'Microsoft YaHei', 'PingFang SC', 'Noto Sans SC', 'sans-serif']
      .filter((x): x is string => typeof x === 'string' && x.length > 0)
      .join(', ')
  return {
    fontSize:   fsPx,
    fontFamily: fontStack,
    fontStyle:  `${element.bold ? 'bold' : 'normal'} ${element.italic ? 'italic' : ''}`.trim(),
    fill:       element.color ?? '#000000',
    wrap:       'none' as const,
    lineHeight: 1.15,
  }
}

function resolveTextFontPx(element: ElementData): number {
  const content = element.content ?? ''
  const hasNl   = content.includes('\n')
  const fsMm    = element.fontSize ?? 3
  const wMm     = element.width ?? 0
  const hMm     = element.height ?? 0
  const wPx     = wMm > 0 ? wMm * MM_TO_PX * renderScale.value : 0
  const hPx     = hMm > 0 ? hMm * MM_TO_PX * renderScale.value : 0
  const isVertical = element.verticalLayout === true || (hasNl && wMm > 0 && hMm > 0 && hMm > wMm * 1.5)
  let fsPx = fsMm * MM_TO_PX * renderScale.value
  const userOverride = element.fontSizeOverridden === true
  if (isVertical && !userOverride && wPx > 0) fsPx = Math.min(fsPx, wPx * 0.92)
  else if (!userOverride && !hasNl && content.length <= 20 && hPx > 2 && (wPx <= 0 || hPx < wPx * 1.5)) {
    fsPx = Math.min(hPx * 0.94, Math.max(fsPx, hPx * 0.56))
  }
  return fsPx
}

function getCurrencyGroupConfig(element: ElementData) {
  const isSelected = store.selectedElementId === element.id
  return {
    id:        element.id,
    x:         s(element.x),
    y:         s(element.y),
    rotation:  element.rotation ?? 0,
    draggable: elementDraggable(),
    stroke:    isSelected ? '#1a73e8' : undefined,
    strokeWidth: isSelected ? 0.5 : 0,
  }
}

function getCurrencyHeadConfig(element: ElementData) {
  const parts = getCurrencySplitParts(element.content ?? '')
  const fsPx  = resolveTextFontPx(element)
  const isSelected = store.selectedElementId === element.id
  const headText = parts ? parts.prefix + parts.symbol : ''
  return {
    id:    `${element.id}-cur-h`,
    x:     0,
    y:     0,
    text:  headText,
    ...buildTextFontStyle(element, fsPx),
    letterSpacing: 0,
    align: 'left' as const,
    verticalAlign: 'top' as const,
    stroke: isSelected ? '#1a73e8' : undefined,
    strokeWidth: isSelected ? 0.5 : 0,
  }
}

function getCurrencyTailConfig(element: ElementData) {
  const parts = getCurrencySplitParts(element.content ?? '')
  const fsMm  = element.fontSize ?? 3
  const fsPx  = resolveTextFontPx(element)
  const isSelected = store.selectedElementId === element.id
  return {
    id:    `${element.id}-cur-t`,
    x:     parts ? currencyTailOffsetPx(parts.prefix, fsMm, element.glyphAdvanceMm) : 0,
    y:     0,
    text:  parts?.tail ?? '',
    ...buildTextFontStyle(element, fsPx),
    letterSpacing: 0,
    align: 'left' as const,
    verticalAlign: 'top' as const,
    stroke: isSelected ? '#1a73e8' : undefined,
    strokeWidth: isSelected ? 0.5 : 0,
  }
}

function getTextConfig(element: ElementData) {
  const isSelected = store.selectedElementId === element.id
  const content    = element.content ?? ''
  const hasNl      = content.includes('\n')
  const fsMm       = element.fontSize ?? 3
  const wMm        = element.width ?? 0
  const hMm        = element.height ?? 0
  // 后端竖排：content 已按字符拆为多行；用列宽做字号上限，避免被外接框高度撑爆
  const isVertical = element.verticalLayout === true || (hasNl && wMm > 0 && hMm > 0 && hMm > wMm * 1.5)
  const isPasswordGrid = element.passwordGrid === true
  const isMultiLineHorizontal = !isVertical && hasNl && wMm > 0 && !isPasswordGrid

  let fsPx = fsMm * MM_TO_PX * renderScale.value
  const hPx = hMm > 0 ? hMm * MM_TO_PX * renderScale.value : 0
  const wPx = wMm > 0 ? wMm * MM_TO_PX * renderScale.value : 0
  // 用户在属性面板手动改过字号 → 绝对尊重用户输入，跳过任何按外接框尺寸的兜底裁剪
  const userOverride = element.fontSizeOverridden === true
  if (isVertical && !userOverride) {
    // 竖排：字号上限取列宽（避免溢出到隔壁列）
    if (wPx > 0) fsPx = Math.min(fsPx, wPx * 0.92)
  } else if (!userOverride && !hasNl && content.length <= 20 && hPx > 2 && (wPx <= 0 || hPx < wPx * 1.5)) {
    /** 横向短标签：ofdrw 字号偶发偏小，用外接框高度抬到可读下限；长串（如密码区）不抬 */
    fsPx = Math.min(hPx * 0.94, Math.max(fsPx, hPx * 0.56))
  }

  // 仅金额类文本按 OFD DeltaX 补字距（¥ 与数字）；销售方等普通字段不拉伸
  let letterSpacing = 0
  const trimmed = content.trim()
  const isCurrencyAmount = /^[¥￥][\d.,]+$/.test(trimmed)
      || (/[¥￥][\d.,]+$/.test(trimmed) && trimmed.length <= 24)
  const isNumericOrAmount = /^[\d¥￥.,/%\-+\s()（）：:]*$/.test(trimmed)
  const isStretchLabel = !isVertical && !isPasswordGrid && !hasNl && !isMultiLineHorizontal && wMm > 0
      && content.length > 1 && content.length <= 8 && !isNumericOrAmount && !isCurrencyAmount
      && !content.includes('：') && !content.includes(':')
  if (isStretchLabel) {
    const fsMmRendered = fsPx / (MM_TO_PX * renderScale.value)
    const naturalMm    = estimateNaturalWidthMm(content, fsMmRendered)
    const gapMm        = wMm - naturalMm
    if (gapMm > 0.05 && gapMm < wMm * 0.35) {
      const lsMm = gapMm / (content.length - 1)
      letterSpacing = Math.min(lsMm, fsMmRendered * 0.6) * MM_TO_PX * renderScale.value
    }
  } else if (isCurrencyAmount && !isVertical && !isPasswordGrid && !hasNl) {
    const fsMmR = fsPx / (MM_TO_PX * renderScale.value)
    const ofdAdv = element.glyphAdvanceMm
    if (typeof ofdAdv === 'number' && ofdAdv > 0) {
      const lsMm = ofdAdv - fsMmR * 0.32
      if (lsMm > 0.02) letterSpacing = lsMm * MM_TO_PX * renderScale.value
    } else {
      letterSpacing = fsMm * 0.15 * MM_TO_PX * renderScale.value
    }
  }

  const displayText = isCurrencyAmount ? formatCurrencyDisplayText(content) : content

  const fontStack = [element.fontFamily, 'Microsoft YaHei', 'PingFang SC', 'Noto Sans SC', 'sans-serif']
      .filter((x): x is string => typeof x === 'string' && x.length > 0)
      .join(', ')

  const lineCount = hasNl ? content.split('\n').length : 1
  let lineHeight = hasNl ? (isVertical ? 1.05 : 1.12) : 1.15
  if (isPasswordGrid && lineCount > 1 && hMm > 0 && fsMm > 0) {
    /** 密码区：按外接框高度均分行距，避免底部溢出 */
    lineHeight = Math.min(1.12, Math.max(0.92, (hMm * 0.96) / (lineCount * fsMm)))
  }

  const baseCfg = {
    id:             element.id,
    x:              s(element.x),
    y:              s(element.y),
    rotation:       element.rotation ?? 0,
    draggable:      elementDraggable(),
    text:           displayText,
    fontSize:       fsPx,
    letterSpacing,
    lineHeight,
    fontFamily:     fontStack,
    fontStyle:      `${element.bold ? 'bold' : 'normal'} ${element.italic ? 'italic' : ''}`.trim(),
    align:          isVertical ? 'center' : 'left',
    verticalAlign:  (isVertical || isPasswordGrid) ? 'middle' : 'top',
    /** 后端已插入 \n 时不再 word-wrap，防止密码区等二次折行 */
    wrap:           'none' as const,
    ellipsis:       false,
    fill:           element.color    ?? '#000000',
    stroke:         isSelected ? '#1a73e8' : undefined,
    strokeWidth:    isSelected ? 0.5 : 0,
  }
  /** 竖排/密码区绑外接框尺寸以便 verticalAlign 居中 */
  if (isVertical && wPx > 0) return { ...baseCfg, width: wPx }
  if (isPasswordGrid && wPx > 0 && hPx > 0) return { ...baseCfg, width: wPx, height: hPx }
  return baseCfg
}

function getPathConfig(element: ElementData) {
  const e          = element as any
  const isSelected = store.selectedElementId === element.id
  const sc         = MM_TO_PX * renderScale.value
  // OFD 矢量：纯填充/纯描边由 path*Enabled 与线宽控制；无描边时不得强制灰色描边
  const strokeOff  = e.pathStrokeEnabled === false
  const fillOff    = e.pathFillEnabled === false
  const lw         = (typeof e.lineWidth === 'number' && e.lineWidth > 0) ? e.lineWidth : 0
  const hasStrokeColor = isNotEmptyStr(e.strokeColor)
  const canStroke  = !strokeOff && (lw > 0 || hasStrokeColor)
  const strokeW    = isSelected
      ? 2 / sc
      : (strokeOff ? 0 : (lw > 0 ? lw : (hasStrokeColor ? 0.3 : 0)))
  const strokeCol  = isSelected
      ? '#1a73e8'
      : (strokeOff || (strokeW <= 0 && !isSelected) ? undefined : (e.strokeColor || '#222222'))
  return {
    id:                 element.id,
    x:                  0, y: 0,
    scaleX:             sc, scaleY: sc,
    rotation:           element.rotation ?? 0,
    draggable:          elementDraggable(),
    data:               getPathData(element),
    fill:               fillOff ? 'transparent' : (e.fillColor ?? 'transparent'),
    stroke:             strokeCol,
    strokeWidth:        strokeW,
    strokeScaleEnabled: false,
  }
}

function isNotEmptyStr(s: unknown) {
  return typeof s === 'string' && s.length > 0
}

function getSealConfig(element: ElementData) {
  return {
    id:        element.id,
    x:         s(element.x),
    y:         s(element.y),
    width:     s(element.width),
    height:    s(element.height),
    rotation:  element.rotation ?? 0,
    image:     imageMap[element.id],
    listening: false,
    draggable: false,
  }
}

function getSealPlaceholderConfig(element: ElementData) {
  return {
    id: element.id, x: s(element.x), y: s(element.y),
    width: s(element.width), height: s(element.height),
    fill: 'rgba(200,50,50,0.08)', stroke: 'rgba(200,50,50,0.25)',
    strokeWidth: 1, listening: false, draggable: false,
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
    scaleX:      element.scaleX ?? 1,
    scaleY:      element.scaleY ?? 1,
    draggable:   elementDraggable(),
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
    strokeWidth: 1, draggable: elementDraggable(),
  }
}

function getImageFailedConfig(element: ElementData) {
  return {
    id: element.id, x: s(element.x), y: s(element.y),
    width: s(element.width), height: s(element.height),
    fill: 'rgba(255,77,79,0.12)', stroke: '#ff4d4f',
    strokeWidth: 1, draggable: elementDraggable(),
  }
}

function getImageNoSrcPlaceholderConfig(element: ElementData) {
  return {
    id: element.id, x: s(element.x), y: s(element.y),
    width: s(element.width), height: s(element.height),
    fill: 'rgba(255,0,0,0.05)', stroke: '#ffaaaa',
    strokeWidth: 1, draggable: elementDraggable(),
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
    draggable: elementDraggable(),
  }
}

// ─────────────────────────────────────────────
// 注释 Config
// ─────────────────────────────────────────────
function elementDraggable() { return store.isSelectTool && !props.offscreen }

function annDraggable() { return store.currentTool === 'SELECT' && !props.offscreen }

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
    fontSize:      (ann.fontSize ?? 12) * MM_TO_PX * renderScale.value,
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
    fontSize:      (ann.fontSize ?? 12) * MM_TO_PX * renderScale.value,
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

// ─────────────────────────────────────────────
// 打印：把当前页渲染成高分辨率 PNG，供打印预览使用
// ─────────────────────────────────────────────
/** 当前页所需的位图（OFD 图像 + 图章）是否都已加载完成 */
function allImagesReady(): boolean {
  for (const el of props.page.elements) {
    if (el.type === 'IMAGE' || el.type === 'SEAL') {
      const src = getImageSrc(el)
      if (src && !imageMap[el.id] && !imageErrorMap[el.id]) return false
    }
  }
  for (const ann of pageAnnotations.value) {
    if (ann.type === 'STAMP' && ann.stampBase64 && !stampImageMap[ann.id]) return false
  }
  return true
}

/**
 * 把当前页导出为 PNG dataURL，供打印窗口使用。
 * @param pixelRatio        输出分辨率倍率（越大越清晰、越慢）
 * @param includeAnnotations 是否包含注释层
 */
async function captureForPrint(
    pixelRatio = 2,
    includeAnnotations = true,
): Promise<{ dataUrl: string; width: number; height: number }> {
  await nextTick()

  // 等待图片资源加载（最多 ~6s，超时则按现状渲染）
  const start = Date.now()
  while (!allImagesReady() && Date.now() - start < 6000) {
    await new Promise((r) => setTimeout(r, 80))
  }
  await nextTick()

  const stage:   any = stageRef.value?.getNode?.()
  const annLayer: any = annotationLayerRef.value?.getNode?.()
  const prevAnnVisible = annLayer?.visible?.() ?? true

  if (annLayer && !includeAnnotations) annLayer.visible(false)
  stage?.draw?.()

  let dataUrl = ''
  try {
    dataUrl = stage?.toDataURL?.({ pixelRatio, mimeType: 'image/png' }) ?? ''
  } catch (e) {
    console.warn('[print] 画布导出失败（可能存在跨域图片）', e)
    dataUrl = ''
  }

  if (annLayer && !includeAnnotations) {
    annLayer.visible(prevAnnVisible)
    stage?.draw?.()
  }

  return { dataUrl, width: props.page.width, height: props.page.height }
}

defineExpose({ captureForPrint })
</script>

<style scoped>
.canvas-wrapper.cursor-hand {
  cursor: grab;
}

.canvas-wrapper.cursor-hand.cursor-grabbing {
  cursor: grabbing;
}

.canvas-wrapper.cursor-crosshair {
  cursor: crosshair;
}

.canvas-wrapper--offscreen {
  pointer-events: none;
}
</style>