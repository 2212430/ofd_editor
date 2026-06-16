<template>
  <div ref="rootRef" class="continuous-pages">
    <div
        v-for="(page, index) in store.document!.pages"
        :key="page.id ?? `page-${index}`"
        :ref="(el) => setSlotRef(el, index)"
        class="continuous-page-slot"
        :class="{ active: store.currentPageIndex === index }"
        :data-page-index="index"
        :style="{ minHeight: `${slotMinHeightMm(page)}px` }"
    >
      <div class="continuous-page-label">第 {{ index + 1 }} 页</div>
      <div
          class="canvas-container"
          :style="canvasFrameStyle(page)"
      >
        <CanvasEditor
            v-if="isPageMounted(index)"
            :ref="(el) => setCanvasRef(el, index)"
            :page="page"
            :page-index="index"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import {
  computed, nextTick, onMounted, onUnmounted, reactive, ref, watch,
} from 'vue'
import type { ComponentPublicInstance } from 'vue'
import CanvasEditor from '@/components/CanvasEditor.vue'
import { useEditorStore } from '@/stores/editorStore'
import type { PageData } from '@/types'
import { viewStagePixelSize, normalizeViewRotation } from '@/utils/viewRotation'

const MM_TO_PX = 96 / 25.4
const PAGE_GAP_PX = 28
const LABEL_HEIGHT_PX = 28

const store = useEditorStore()
const rootRef = ref<HTMLElement>()
const slotRefs = new Map<number, HTMLElement>()
const canvasRefs = new Map<number, InstanceType<typeof CanvasEditor>>()
const mountedPages = reactive<Record<number, boolean>>({})

let visibilityObserver: IntersectionObserver | null = null
let scrollRootEl: HTMLElement | null = null
let scrollSyncFromUserClick = false

const scale = computed(() => store.scale)

function canvasFrameStyle(page: PageData) {
  const { stageWidth, stageHeight } = viewStagePixelSize(
      page.width,
      page.height,
      scale.value,
      normalizeViewRotation((page.pageRotate ?? 0) + store.viewRotation),
  )
  return {
    width: `${stageWidth}px`,
    height: `${stageHeight}px`,
  }
}

function slotMinHeightMm(page: PageData) {
  const { stageHeight } = viewStagePixelSize(
      page.width,
      page.height,
      scale.value,
      normalizeViewRotation((page.pageRotate ?? 0) + store.viewRotation),
  )
  return stageHeight + PAGE_GAP_PX + LABEL_HEIGHT_PX
}

function isPageMounted(index: number) {
  return !!mountedPages[index]
}

function mountPage(index: number) {
  if (index < 0 || index >= (store.document?.pageCount ?? 0)) return
  mountedPages[index] = true
  for (const n of [index - 1, index + 1]) {
    if (n >= 0 && n < (store.document?.pageCount ?? 0)) mountedPages[n] = true
  }
}

function setSlotRef(el: Element | ComponentPublicInstance | null, index: number) {
  if (el instanceof HTMLElement) slotRefs.set(index, el)
  else slotRefs.delete(index)
}

function setCanvasRef(el: Element | ComponentPublicInstance | null, index: number) {
  if (el && typeof (el as InstanceType<typeof CanvasEditor>).captureForPrint === 'function') {
    canvasRefs.set(index, el as InstanceType<typeof CanvasEditor>)
  } else {
    canvasRefs.delete(index)
  }
}

function getCanvasForPage(pageIndex: number) {
  return canvasRefs.get(pageIndex) ?? null
}

function scrollToPageInView(pageIndex: number, behavior: ScrollBehavior = 'smooth') {
  scrollSyncFromUserClick = true
  const el = slotRefs.get(pageIndex)
  if (el) {
    el.scrollIntoView({ behavior, block: 'center' })
  }
  window.setTimeout(() => { scrollSyncFromUserClick = false }, 600)
}

function syncCurrentPageFromScroll() {
  if (scrollSyncFromUserClick || !scrollRootEl) return
  const rootRect = scrollRootEl.getBoundingClientRect()
  const centerY = rootRect.top + rootRect.height / 2
  let bestIdx = store.currentPageIndex
  let bestDist = Number.POSITIVE_INFINITY
  slotRefs.forEach((el, idx) => {
    const r = el.getBoundingClientRect()
    const dist = Math.abs(r.top + r.height / 2 - centerY)
    if (dist < bestDist) {
      bestDist = dist
      bestIdx = idx
    }
  })
  if (bestIdx !== store.currentPageIndex) {
    store.setCurrentPage(bestIdx, { preserveSelection: true })
  }
}

function onScrollAreaScroll() {
  syncCurrentPageFromScroll()
}

function setupObservers() {
  visibilityObserver?.disconnect()
  scrollRootEl?.removeEventListener('scroll', onScrollAreaScroll)

  const root = rootRef.value?.closest('.editor-area') as HTMLElement | null
  if (!root) return
  scrollRootEl = root

  visibilityObserver = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue
          const idx = Number((entry.target as HTMLElement).dataset.pageIndex)
          if (!Number.isNaN(idx)) mountPage(idx)
        }
      },
      { root, rootMargin: '240px 0px', threshold: 0 },
  )

  slotRefs.forEach((el) => visibilityObserver!.observe(el))
  root.addEventListener('scroll', onScrollAreaScroll, { passive: true })
}

function primeMountedPages() {
  for (const key of Object.keys(mountedPages)) {
    delete mountedPages[Number(key)]
  }
  mountPage(store.currentPageIndex)
}

onMounted(() => {
  store.registerScrollToPageInViewHook(scrollToPageInView)
  primeMountedPages()
  nextTick(() => {
    setupObservers()
    scrollToPageInView(store.currentPageIndex, 'auto')
  })
})

onUnmounted(() => {
  store.registerScrollToPageInViewHook(() => {})
  visibilityObserver?.disconnect()
  scrollRootEl?.removeEventListener('scroll', onScrollAreaScroll)
  scrollRootEl = null
})

watch(
    () => store.document?.pageCount,
    () => {
      for (const key of Object.keys(mountedPages)) {
        delete mountedPages[Number(key)]
      }
      primeMountedPages()
      nextTick(setupObservers)
    },
)

watch(scale, () => {
  nextTick(setupObservers)
})

defineExpose({ getCanvasForPage, scrollToPageInView, ensurePageMounted: mountPage })
</script>

<style scoped>
.continuous-pages {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 28px;
  width: 100%;
  padding-bottom: 48px;
}

.continuous-page-slot {
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.continuous-page-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-3);
  margin-bottom: 8px;
  user-select: none;
}

.continuous-page-slot.active .continuous-page-label {
  color: var(--ribbon-accent);
}

.continuous-page-slot .canvas-container {
  display: inline-block;
  box-shadow: var(--shadow-page);
  background: #fff;
}

.continuous-page-slot.active .canvas-container {
  box-shadow: 0 0 0 2px var(--ribbon-accent-soft), var(--shadow-page);
}
</style>
