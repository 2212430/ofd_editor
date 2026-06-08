<template>
  <el-dialog
      v-model="visible"
      title="裁剪图片"
      width="760px"
      :append-to-body="true"
      class="image-crop-dialog"
      destroy-on-close
      @opened="initCrop"
      @closed="resetState"
  >
    <p v-if="fileName" class="crop-hint">
      拖动选框或拖拽边角调整<strong>保留区域</strong>（暗区将被裁掉），确认后替换像素内容。
      画布上拖拽控制点仅为缩放，请使用本对话框进行裁剪。
      <span class="crop-file">{{ fileName }}</span>
    </p>
    <p v-else class="crop-hint">请先选中页面上的图片元素。</p>

    <div v-if="loadError" class="crop-error">{{ loadError }}</div>
    <div v-else-if="loading" class="crop-loading">
      <div class="spinner" />
      <span>加载图片…</span>
    </div>
    <div v-else ref="workspaceRef" class="crop-workspace">
      <canvas
          ref="canvasRef"
          class="crop-canvas"
          @mousedown="onPointerDown"
          @mousemove="onPointerMove"
          @mouseup="onPointerUp"
          @mouseleave="onPointerUp"
      />
    </div>

    <div v-if="!loading && !loadError && naturalSize.width" class="crop-meta">
      选区：{{ Math.round(cropRect.width) }} × {{ Math.round(cropRect.height) }} px
      （原图 {{ naturalSize.width }} × {{ naturalSize.height }}）
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="visible = false">取消</el-button>
        <el-button :disabled="!!loadError || loading" @click="resetCrop">重置选区</el-button>
        <el-button
            type="primary"
            :disabled="!!loadError || loading || !canConfirm"
            :loading="submitting"
            @click="confirmCrop"
        >
          确认裁剪
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useEditorStore } from '@/stores/editorStore'
import {
    cropImageToDataUrl,
    getElementImageSrc,
    loadImageElement,
    type CropRect,
} from '@/utils/imageCrop'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [boolean] }>()

const store = useEditorStore()
const visible = computed({
    get: () => props.modelValue,
    set: (v: boolean) => emit('update:modelValue', v),
})

const canvasRef = ref<HTMLCanvasElement | null>(null)
const workspaceRef = ref<HTMLElement | null>(null)
const loading = ref(false)
const loadError = ref('')
const submitting = ref(false)
const image = ref<HTMLImageElement | null>(null)
const naturalSize = ref({ width: 0, height: 0 })
const displayScale = ref(1)
const cropRect = ref<CropRect>({ x: 0, y: 0, width: 0, height: 0 })

type DragMode = 'move' | 'nw' | 'ne' | 'sw' | 'se' | 'n' | 's' | 'w' | 'e' | 'new' | null
const dragMode = ref<DragMode>(null)
const dragStart = ref({ x: 0, y: 0, rect: { x: 0, y: 0, width: 0, height: 0 } })

const MIN_CROP = 8

const fileName = computed(() => {
    const el = store.selectedElement
    if (!el || el.type !== 'IMAGE') return ''
    return el.content && el.content !== '[图片]' ? el.content : '图片元素'
})

const canConfirm = computed(() =>
    cropRect.value.width >= MIN_CROP && cropRect.value.height >= MIN_CROP,
)

watch(visible, (open) => {
    if (open) void initCrop()
})

function resetState() {
    loading.value = false
    loadError.value = ''
    submitting.value = false
    image.value = null
    naturalSize.value = { width: 0, height: 0 }
    dragMode.value = null
}

async function initCrop() {
    resetState()
    const el = store.selectedElement
    if (!el || el.type !== 'IMAGE') {
        loadError.value = '请先选中图片类型的元素'
        return
    }
    const src = getElementImageSrc(el)
    if (!src) {
        loadError.value = '当前图片无可用数据源，无法裁剪'
        return
    }

    loading.value = true
    try {
        const img = await loadImageElement(src)
        image.value = img
        naturalSize.value = { width: img.naturalWidth, height: img.naturalHeight }
        cropRect.value = {
            x: 0,
            y: 0,
            width: img.naturalWidth,
            height: img.naturalHeight,
        }
        await nextTick()
        layoutCanvas()
        draw()
    } catch {
        loadError.value = '图片加载失败'
    } finally {
        loading.value = false
    }
}

function layoutCanvas() {
    const canvas = canvasRef.value
    const workspace = workspaceRef.value
    const img = image.value
    if (!canvas || !workspace || !img) return

    const maxW = Math.min(680, workspace.clientWidth || 680)
    const maxH = 420
    const scale = Math.min(maxW / img.naturalWidth, maxH / img.naturalHeight, 1)
    displayScale.value = scale

    canvas.width = Math.round(img.naturalWidth * scale)
    canvas.height = Math.round(img.naturalHeight * scale)
    canvas.style.width = `${canvas.width}px`
    canvas.style.height = `${canvas.height}px`
}

function resetCrop() {
    if (!image.value) return
    cropRect.value = {
        x: 0,
        y: 0,
        width: image.value.naturalWidth,
        height: image.value.naturalHeight,
    }
    draw()
}

function toDisplayRect(rect: CropRect) {
    const s = displayScale.value
    return {
        x: rect.x * s,
        y: rect.y * s,
        width: rect.width * s,
        height: rect.height * s,
    }
}

function toNaturalPoint(clientX: number, clientY: number) {
    const canvas = canvasRef.value
    const img = image.value
    if (!canvas || !img) return { x: 0, y: 0 }
    const box = canvas.getBoundingClientRect()
    if (box.width <= 0 || box.height <= 0) return { x: 0, y: 0 }
    return {
        x: ((clientX - box.left) / box.width) * img.naturalWidth,
        y: ((clientY - box.top) / box.height) * img.naturalHeight,
    }
}

function pointerDeltaToNatural(dxPx: number, dyPx: number) {
    const canvas = canvasRef.value
    const img = image.value
    if (!canvas || !img) return { dx: 0, dy: 0 }
    const box = canvas.getBoundingClientRect()
    if (box.width <= 0 || box.height <= 0) return { dx: 0, dy: 0 }
    return {
        dx: (dxPx / box.width) * img.naturalWidth,
        dy: (dyPx / box.height) * img.naturalHeight,
    }
}

function draw() {
    const canvas = canvasRef.value
    const img = image.value
    if (!canvas || !img) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    const s = displayScale.value
    ctx.clearRect(0, 0, canvas.width, canvas.height)
    ctx.drawImage(img, 0, 0, img.naturalWidth * s, img.naturalHeight * s)

    const d = toDisplayRect(cropRect.value)
    ctx.save()
    ctx.fillStyle = 'rgba(0, 0, 0, 0.45)'
    ctx.fillRect(0, 0, canvas.width, canvas.height)
    ctx.clearRect(d.x, d.y, d.width, d.height)
    ctx.drawImage(img, cropRect.value.x, cropRect.value.y, cropRect.value.width, cropRect.value.height,
        d.x, d.y, d.width, d.height)
    ctx.restore()

    ctx.strokeStyle = '#1a73e8'
    ctx.lineWidth = 2
    ctx.setLineDash([6, 4])
    ctx.strokeRect(d.x + 0.5, d.y + 0.5, d.width - 1, d.height - 1)
    ctx.setLineDash([])

    const handle = 8
    ctx.fillStyle = '#ffffff'
    ctx.strokeStyle = '#1a73e8'
    for (const p of handlePoints(d)) {
        ctx.fillRect(p.x - handle / 2, p.y - handle / 2, handle, handle)
        ctx.strokeRect(p.x - handle / 2 + 0.5, p.y - handle / 2 + 0.5, handle - 1, handle - 1)
    }
}

function handlePoints(d: { x: number; y: number; width: number; height: number }) {
    const { x, y, width, height } = d
    const cx = x + width / 2
    const cy = y + height / 2
    return [
        { x, y, mode: 'nw' as DragMode },
        { x: x + width, y, mode: 'ne' as DragMode },
        { x, y: y + height, mode: 'sw' as DragMode },
        { x: x + width, y: y + height, mode: 'se' as DragMode },
        { x: cx, y, mode: 'n' as DragMode },
        { x: cx, y: y + height, mode: 's' as DragMode },
        { x, y: cy, mode: 'w' as DragMode },
        { x: x + width, y: cy, mode: 'e' as DragMode },
    ]
}

function hitTest(clientX: number, clientY: number): DragMode {
    const canvas = canvasRef.value
    if (!canvas) return 'new'
    const box = canvas.getBoundingClientRect()
    const px = clientX - box.left
    const py = clientY - box.top
    const d = toDisplayRect(cropRect.value)
    const tol = 10

    for (const p of handlePoints(d)) {
        if (Math.abs(px - p.x) <= tol && Math.abs(py - p.y) <= tol) return p.mode
    }
    if (px >= d.x && px <= d.x + d.width && py >= d.y && py <= d.y + d.height) return 'move'
    return 'new'
}

function clampRect(rect: CropRect): CropRect {
    const maxW = naturalSize.value.width
    const maxH = naturalSize.value.height
    let { x, y, width, height } = rect
    width = Math.max(MIN_CROP, Math.min(width, maxW))
    height = Math.max(MIN_CROP, Math.min(height, maxH))
    x = Math.max(0, Math.min(x, maxW - width))
    y = Math.max(0, Math.min(y, maxH - height))
    return { x, y, width, height }
}

function onPointerDown(e: MouseEvent) {
    if (!image.value) return
    const mode = hitTest(e.clientX, e.clientY)
    dragMode.value = mode
    dragStart.value = {
        x: e.clientX,
        y: e.clientY,
        rect: { ...cropRect.value },
    }
    if (mode === 'new') {
        const p = toNaturalPoint(e.clientX, e.clientY)
        cropRect.value = clampRect({ x: p.x, y: p.y, width: MIN_CROP, height: MIN_CROP })
        dragMode.value = 'se'
        dragStart.value = { x: e.clientX, y: e.clientY, rect: { ...cropRect.value } }
    }
    draw()
}

function onPointerMove(e: MouseEvent) {
    if (!dragMode.value || !image.value) return
    const { dx, dy } = pointerDeltaToNatural(
        e.clientX - dragStart.value.x,
        e.clientY - dragStart.value.y,
    )
    const base = dragStart.value.rect
    let next = { ...base }

    switch (dragMode.value) {
        case 'move':
            next.x = base.x + dx
            next.y = base.y + dy
            break
        case 'nw':
            next.x = base.x + dx
            next.y = base.y + dy
            next.width = base.width - dx
            next.height = base.height - dy
            break
        case 'ne':
            next.y = base.y + dy
            next.width = base.width + dx
            next.height = base.height - dy
            break
        case 'sw':
            next.x = base.x + dx
            next.width = base.width - dx
            next.height = base.height + dy
            break
        case 'se':
            next.width = base.width + dx
            next.height = base.height + dy
            break
        case 'n':
            next.y = base.y + dy
            next.height = base.height - dy
            break
        case 's':
            next.height = base.height + dy
            break
        case 'w':
            next.x = base.x + dx
            next.width = base.width - dx
            break
        case 'e':
            next.width = base.width + dx
            break
        default:
            break
    }

    cropRect.value = clampRect(next)
    draw()
}

function onPointerUp() {
    dragMode.value = null
}

async function confirmCrop() {
    const el = store.selectedElement
    const img = image.value
    if (!el || el.type !== 'IMAGE' || !img || !store.selectedElementId) return

    submitting.value = true
    try {
        const dataUrl = cropImageToDataUrl(img, cropRect.value)
        const ok = await store.applyImageCrop(
            store.currentPageIndex,
            store.selectedElementId,
            cropRect.value,
            dataUrl,
            { width: naturalSize.value.width, height: naturalSize.value.height },
        )
        if (ok) {
            ElMessage.success('图片已裁剪')
            visible.value = false
        } else {
            ElMessage.error('裁剪失败')
        }
    } catch (e: any) {
        ElMessage.error(e?.message || '裁剪失败')
    } finally {
        submitting.value = false
    }
}
</script>

<style scoped>
.crop-hint {
    margin: 0 0 12px;
    font-size: 13px;
    color: #606266;
    line-height: 1.5;
}
.crop-file {
    color: #303133;
    font-weight: 500;
}
.crop-workspace {
    display: flex;
    justify-content: center;
    align-items: center;
    min-height: 200px;
    padding: 8px;
    background: #f5f7fa;
    border-radius: 8px;
    overflow: auto;
}
.crop-canvas {
    cursor: crosshair;
    display: block;
}
.crop-meta {
    margin-top: 10px;
    font-size: 12px;
    color: #909399;
}
.crop-loading,
.crop-error {
    min-height: 160px;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 10px;
    color: #909399;
}
.crop-error {
    color: #f56c6c;
}
.spinner {
    width: 22px;
    height: 22px;
    border: 2px solid #dcdfe6;
    border-top-color: #409eff;
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
}
@keyframes spin {
    to { transform: rotate(360deg); }
}
.dialog-footer {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
}
</style>
