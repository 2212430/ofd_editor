import { defineStore, acceptHMRUpdate } from 'pinia'
import { ref, computed, reactive, nextTick } from 'vue'
import type {
    DocumentData, ElementData, PageData,
    AnnotationData, AnnotationReplyData, AnnotationType, ToolType, DocumentSource, PageViewMode, WatermarkConfig,
} from '@/types'
import { ofdApi } from '@/api/ofdApi'
import { getPdfPageTextItems, type PageTextItem } from '@/utils/pdfRender'
import { matchRectsInTextItem, type SearchRect } from '@/utils/textMetrics'
import {
    normalizePageRotate,
    rotateAnnotationInPage,
    rotateElementInPage,
} from '@/utils/pageRotate'
import { effectivePageSizeMm, normalizeViewRotation } from '@/utils/viewRotation'
import {
    getElementImageSrc,
    computeElementBoundsAfterCrop,
    type CropRect,
} from '@/utils/imageCrop'
import { registerProgressReporter, stopTransferProgress } from '@/utils/loadingProgress'
import { executeLinkAction as runLinkAction } from '@/utils/linkAction'
import {
    loadDiscussionAuthor, remapRepliesByFingerprint, saveDiscussionAuthor,
} from '@/utils/annotationDiscussion'

/** 撤销栈条目：文档 + 注释 + 讨论 + 当前页 */
interface HistoryEntry {
    document: DocumentData
    annotations: Record<number, AnnotationData[]>
    annotationReplies: Record<string, AnnotationReplyData[]>
    currentPageIndex: number
}

export const useEditorStore = defineStore('editor', () => {

    // ==================== 原有状态 ====================
    const document = ref<DocumentData | null>(null)
    const currentPageIndex = ref(0)
    const pageViewMode = ref<PageViewMode>('single')
    /** 视图旋转角度（仅显示，0/90/180/270，不写回 OFD） */
    const viewRotation = ref(0)
    const selectedElementId = ref<string | null>(null)
    const scale = ref(1.0)
    const isLoading = ref(false)
    const loadingText = ref('处理中...')
    /** 0–100 为确定进度；null 表示服务器处理中（不确定进度） */
    const loadingProgress = ref<number | null>(null)
    const currentFile = ref<File | null>(null)
    const documentSource = ref<DocumentSource | null>(null)
    /** 当前文档的真实承载格式：ofd（含 PDF→OFD 老路径）或 pdf（原生 PDF.js 渲染） */
    const documentKind = ref<'ofd' | 'pdf'>('ofd')
    const fileId = ref<string | null>(null)

    // 打印对话框可见性（跨组件协调：Toolbar 打开、App 编排打印）
    const printDialogVisible = ref(false)
    /** 图片裁剪对话框（Toolbar / 属性面板共用） */
    const imageCropDialogVisible = ref(false)

    const history = ref<HistoryEntry[]>([])
    const historyIndex = ref(-1)
    let historySyncInProgress = false
    /**
     * 撤销 / 重做触发后单调递增。CanvasEditor 监听该计数并显式调用 Konva stage.batchDraw()，
     * 兜底 vue-konva deep watcher 在 ref 整体替换 + 多页文本场景下偶尔漏发的 setAttrs。
     */
    const renderVersion = ref(0)

    // ==================== 全文搜索 / 文本选择 ====================
    /** 单个命中：所在页 + 一组高亮矩形（mm，可视页坐标，左上原点） */
    interface SearchMatch { pageIndex: number; rects: SearchRect[]; snippet: string }

    const searchVisible = ref(false)
    const searchQuery = ref('')
    const searchMatches = ref<SearchMatch[]>([])
    const searchActiveIndex = ref(-1)
    const searching = ref(false)
    /** 文本选择模式：开启后画布上叠加可选中文本层（关闭批注/元素交互） */
    const textSelectMode = ref(false)

    /** 全局文本水印（保存/导出时烘焙） */
    const watermarkConfig = ref<WatermarkConfig | null>(null)

    // 每页文本项缓存（OFD 从已解析元素取，PDF 从 PDF.js 取）
    const pageTextItemsCache = new Map<string, PageTextItem[]>()

    function textCacheKey(pageIndex: number) {
        return `${documentKind.value}:${fileId.value ?? 'nofile'}:${pageIndex}`
    }

    /** 取某页的文本项（统一 mm，左上原点，未叠加视图旋转） */
    async function getPageTextItems(pageIndex: number): Promise<PageTextItem[]> {
        const key = textCacheKey(pageIndex)
        const cached = pageTextItemsCache.get(key)
        if (cached) return cached

        let items: PageTextItem[] = []
        if (documentKind.value === 'pdf') {
            if (fileId.value) {
                try {
                    items = await getPdfPageTextItems(fileId.value, pageIndex)
                } catch (e) {
                    console.warn('[editorStore] 取PDF文本失败:', e)
                }
            }
        } else {
            const page = document.value?.pages[pageIndex]
            if (page) {
                items = page.elements
                    .filter((el) => el.type === 'TEXT' && !el.isDeleted && (el.content ?? '').length > 0)
                    .map((el) => ({
                        str: el.content ?? '',
                        xMm: el.x,
                        yMm: el.y,
                        wMm: el.width,
                        hMm: el.height,
                        fontSizeMm: el.fontSize,
                        glyphAdvanceMm: el.glyphAdvanceMm,
                        verticalLayout: el.verticalLayout,
                        passwordGrid: el.passwordGrid,
                        fontSizeOverridden: el.fontSizeOverridden,
                    }))
            }
        }
        pageTextItemsCache.set(key, items)
        return items
    }

    function clearTextItemsCache() {
        pageTextItemsCache.clear()
    }

    const searchMatchesByPage = computed(() => {
        const map: Record<number, { match: SearchMatch; index: number }[]> = {}
        searchMatches.value.forEach((match, index) => {
            ;(map[match.pageIndex] ??= []).push({ match, index })
        })
        return map
    })

    function openSearch() {
        searchVisible.value = true
    }

    function closeSearch() {
        searchVisible.value = false
        searchQuery.value = ''
        searchMatches.value = []
        searchActiveIndex.value = -1
    }

    function toggleTextSelectMode(on?: boolean) {
        textSelectMode.value = on ?? !textSelectMode.value
    }

    async function runSearch(query: string) {
        searchQuery.value = query
        const q = query.trim()
        searchMatches.value = []
        searchActiveIndex.value = -1
        if (!q || !document.value) return

        searching.value = true
        try {
            const lowerQ = q.toLowerCase()
            const matches: SearchMatch[] = []
            const pageCount = document.value.pageCount
            for (let pi = 0; pi < pageCount; pi++) {
                const items = await getPageTextItems(pi)
                for (const item of items) {
                    const mode = documentKind.value === 'pdf' ? 'pdf' : 'ofd'
                    const rects = matchRectsInTextItem(item, q, lowerQ, mode)
                    for (const r of rects) {
                        matches.push({ pageIndex: pi, rects: [r], snippet: item.str })
                    }
                }
            }
            searchMatches.value = matches
            if (matches.length > 0) {
                searchActiveIndex.value = 0
                jumpToMatch(0)
            }
        } finally {
            searching.value = false
        }
    }

    function jumpToMatch(i: number) {
        const m = searchMatches.value[i]
        if (!m) return
        searchActiveIndex.value = i
        if (m.pageIndex !== currentPageIndex.value) {
            setCurrentPage(m.pageIndex, { preserveSelection: true, scrollIntoView: true })
        } else {
            scrollToPageInViewHook?.(m.pageIndex)
        }
        renderVersion.value++
    }

    function nextMatch() {
        if (searchMatches.value.length === 0) return
        jumpToMatch((searchActiveIndex.value + 1) % searchMatches.value.length)
    }

    function prevMatch() {
        if (searchMatches.value.length === 0) return
        const n = searchMatches.value.length
        jumpToMatch((searchActiveIndex.value - 1 + n) % n)
    }

    // ==================== 注释相关状态 ====================
    const currentTool = ref<ToolType>('SELECT')

    // ✅ 改用 reactive Record，Vue 能完整追踪增删改
    const annotationsMap = reactive<Record<number, AnnotationData[]>>({})

    /** annotationId -> 讨论回复（会话缓存，不写入 OFD） */
    const annotationRepliesMap = reactive<Record<string, AnnotationReplyData[]>>({})

    const discussionAuthor = ref(loadDiscussionAuthor())

    const selectedAnnotationId = ref<string | null>(null)
    /** 右侧面板：属性 / 注释列表 */
    const rightPanelTab = ref<'properties' | 'annotations'>('properties')
    /** 注释列表范围：当前页 / 全部页 */
    const annotationListScope = ref<'current' | 'all'>('current')
    const annotationColor = ref('#000000')
    const annotationOpacity = ref(0.5)
    const annotationLineWidth = ref(2)

    /** 待放置的图章图片（data URL），选择图片后点击页面放置 */
    const pendingStampImage = ref<string | null>(null)

    /** 左侧页面列表缩略图（pageIndex → PNG dataURL），随滚动按需加载 */
    const pageThumbnails = reactive<Record<number, string>>({})
    /** 正在生成缩略图的页码 */
    const thumbnailLoadingPages = reactive<Record<number, boolean>>({})
    const isGeneratingThumbnails = ref(false)
    const thumbnailQueue: number[] = []
    let thumbnailWorkerRunning = false
    let thumbnailCaptureHook: ((pageIndex: number) => Promise<string | null>) | null = null
    const thumbnailRefreshTimers: Partial<Record<number, ReturnType<typeof setTimeout>>> = {}

    // ==================== 计算属性 ====================
    const currentPage = computed<PageData | null>(() =>
        document.value?.pages[currentPageIndex.value] ?? null
    )

    const selectedElement = computed<ElementData | null>(() => {
        if (!selectedElementId.value || !currentPage.value) return null
        return currentPage.value.elements.find(
            (e) => e.id === selectedElementId.value
        ) ?? null
    })

    /** 后端 deleteElementNode 支持移除的元素类型（TEXT / IMAGE / PATH） */
    const DELETABLE_ELEMENT_TYPES = ['TEXT', 'IMAGE', 'PATH'] as const
    const canDeleteSelectedElement = computed(() => {
        const t = selectedElement.value?.type
        return !!t && (DELETABLE_ELEMENT_TYPES as readonly string[]).includes(t)
    })

    const canUndo = computed(() => historyIndex.value > 0)
    const canRedo = computed(() => historyIndex.value < history.value.length - 1)
    /** 自上次加载/保存以来是否有可撤销的编辑（含注释、讨论、页面结构等） */
    const hasUnsavedChanges = computed(
        () => !!document.value && historyIndex.value > 0,
    )

    // ✅ 直接访问 reactive 对象的属性，Vue 能追踪
    const currentPageAnnotations = computed<AnnotationData[]>(() =>
        annotationsMap[currentPageIndex.value] ?? []
    )

    const selectedAnnotation = computed<AnnotationData | null>(() => {
        if (!selectedAnnotationId.value) return null
        for (const key of Object.keys(annotationsMap)) {
            const list = annotationsMap[Number(key)]
            const found = list?.find(a => a.id === selectedAnnotationId.value)
            if (found) return found
        }
        return null
    })

    const isPdfDocument = computed(() => documentKind.value === 'pdf')

    const isHandTool = computed(() => currentTool.value === 'HAND')
    const isSelectTool = computed(() => currentTool.value === 'SELECT')
    const isAnnotationTool = computed(() =>
        currentTool.value !== 'SELECT' && currentTool.value !== 'HAND'
    )

    const hasPendingStamp = computed(() => !!pendingStampImage.value)

    const flatAnnotationList = computed(() => {
        const items: { annotation: AnnotationData; pageIndex: number }[] = []
        const pageCount = document.value?.pageCount ?? 0
        for (let p = 0; p < pageCount; p++) {
            for (const ann of annotationsMap[p] ?? []) {
                items.push({ annotation: ann, pageIndex: p })
            }
        }
        return items.sort((a, b) => {
            if (a.pageIndex !== b.pageIndex) return a.pageIndex - b.pageIndex
            return (a.annotation.createdAt ?? 0) - (b.annotation.createdAt ?? 0)
        })
    })

    const filteredAnnotationList = computed(() => {
        if (annotationListScope.value === 'current') {
            return flatAnnotationList.value.filter(
                (item) => item.pageIndex === currentPageIndex.value,
            )
        }
        return flatAnnotationList.value
    })

    const annotationCount = computed(() => flatAnnotationList.value.length)

    // ==================== 原有方法 ====================
    function ensurePageIds() {
        if (!document.value) return
        const ts = Date.now()
        document.value.pages.forEach((p, i) => {
            if (!p.id) p.id = `page-${ts}-${i}`
            if (p.sourcePageIndex == null) p.sourcePageIndex = i
        })
    }

    function remapAnnotationsAfterInsert(insertAt: number, newPageAnns: AnnotationData[]) {
        const count = document.value!.pageCount
        const shifted: Record<number, AnnotationData[]> = {}
        for (let i = 0; i < count; i++) {
            if (i < insertAt) {
                shifted[i] = (annotationsMap[i] ?? []).map(a => ({ ...a, pageIndex: i }))
            } else if (i === insertAt) {
                shifted[i] = newPageAnns.map(a => ({ ...a, pageIndex: i }))
            } else {
                shifted[i] = (annotationsMap[i - 1] ?? []).map(a => ({ ...a, pageIndex: i }))
            }
        }
        for (const key of Object.keys(annotationsMap)) {
            delete annotationsMap[Number(key)]
        }
        for (const [k, v] of Object.entries(shifted)) {
            annotationsMap[Number(k)] = v
        }
    }

    function remapAnnotationsAfterDelete(deletedIndex: number) {
        if (!document.value) return
        const count = document.value.pageCount
        const next: Record<number, AnnotationData[]> = {}
        for (let i = 0; i < count; i++) {
            const srcIdx = i >= deletedIndex ? i + 1 : i
            next[i] = (annotationsMap[srcIdx] ?? []).map(a => ({ ...a, pageIndex: i }))
        }
        for (const key of Object.keys(annotationsMap)) {
            delete annotationsMap[Number(key)]
        }
        for (const [k, v] of Object.entries(next)) {
            annotationsMap[Number(k)] = v
        }
    }

    function remapAnnotationsAfterMove(fromIndex: number, toIndex: number) {
        if (!document.value) return
        const n = document.value.pageCount
        const order = Array.from({ length: n }, (_, i) => i)
        const [moved] = order.splice(fromIndex, 1)
        order.splice(toIndex, 0, moved)

        const snapshot: Record<number, AnnotationData[]> = {}
        for (const key of Object.keys(annotationsMap)) {
            snapshot[Number(key)] = [...(annotationsMap[Number(key)] ?? [])]
        }
        for (const key of Object.keys(annotationsMap)) {
            delete annotationsMap[Number(key)]
        }
        for (let newIdx = 0; newIdx < n; newIdx++) {
            const oldIdx = order[newIdx]
            annotationsMap[newIdx] = (snapshot[oldIdx] ?? []).map(a => ({
                ...a,
                pageIndex: newIdx,
            }))
        }
    }

    function adjustCurrentPageAfterMove(fromIndex: number, toIndex: number) {
        const cur = currentPageIndex.value
        if (cur === fromIndex) {
            currentPageIndex.value = toIndex
        } else if (fromIndex < toIndex) {
            if (cur > fromIndex && cur <= toIndex) currentPageIndex.value = cur - 1
        } else if (fromIndex > toIndex) {
            if (cur >= toIndex && cur < fromIndex) currentPageIndex.value = cur + 1
        }
    }

    function newElementId(prefix: string, index: number) {
        return `${prefix}-${Date.now()}-${index}-${Math.random().toString(36).slice(2, 8)}`
    }

    /** mm 与屏幕像素换算（96dpi） */
    const MM_TO_PX = 96 / 25.4

    /**
     * 将本地图片导入当前页，作为可编辑 IMAGE 元素。
     * 尺寸按像素换算为 mm，过大时缩放到页内 85% 以内并居中放置。
     */
    async function importImageToPage(pageIndex: number, file: File): Promise<string | null> {
        if (!document.value) return null
        const page = document.value.pages[pageIndex]
        if (!page) return null

        if (!file.type.startsWith('image/')) {
            throw new Error('请选择图片文件（PNG、JPEG、GIF、WebP 等）')
        }

        const dataUrl = await readFileAsDataUrl(file)
        const { width: pxW, height: pxH } = await loadImageDimensions(dataUrl)

        let wMm = pxW / MM_TO_PX
        let hMm = pxH / MM_TO_PX
        const maxW = page.width * 0.85
        const maxH = page.height * 0.85
        if (wMm > maxW || hMm > maxH) {
            const scale = Math.min(maxW / wMm, maxH / hMm)
            wMm *= scale
            hMm *= scale
        }

        const x = Math.max(0, (page.width - wMm) / 2)
        const y = Math.max(0, (page.height - hMm) / 2)
        const id = newElementId('img', page.elements.length)

        const element: ElementData = {
            id,
            type: 'IMAGE',
            x,
            y,
            width: wMm,
            height: hMm,
            rotation: 0,
            scaleX: 1,
            scaleY: 1,
            imageBase64: dataUrl,
            imageData: dataUrl,
            isNew: true,
            isDirty: true,
            imageContentDirty: true,
            originalX: x,
            originalY: y,
            originalWidth: wMm,
            originalHeight: hMm,
            originalRotation: 0,
        }

        page.elements.push(element)
        selectedElementId.value = id
        selectedAnnotationId.value = null
        currentTool.value = 'SELECT'
        saveToHistory()
        schedulePageThumbnailRefresh(pageIndex, 300)
        return id
    }

    function readFileAsDataUrl(file: File): Promise<string> {
        return new Promise((resolve, reject) => {
            const reader = new FileReader()
            reader.onload = () => resolve(String(reader.result))
            reader.onerror = () => reject(new Error('读取图片失败'))
            reader.readAsDataURL(file)
        })
    }

    function loadImageDimensions(src: string): Promise<{ width: number; height: number }> {
        return new Promise((resolve, reject) => {
            const img = new Image()
            img.onload = () => resolve({ width: img.naturalWidth, height: img.naturalHeight })
            img.onerror = () => reject(new Error('无法解析图片'))
            img.src = src
        })
    }

    function setDocument(doc: DocumentData, kind: 'ofd' | 'pdf' = 'ofd') {
        if (doc.fileId) fileId.value = doc.fileId
        documentKind.value = kind
        document.value = doc
        ensurePageIds()
        currentPageIndex.value = 0
        selectedElementId.value = null
        selectedAnnotationId.value = null
        pendingStampImage.value = null
        rightPanelTab.value = 'properties'
        annotationListScope.value = 'current'
        clearPageThumbnails()
        clearTextItemsCache()
        watermarkConfig.value = null
        searchMatches.value = []
        searchActiveIndex.value = -1
        searchQuery.value = ''
        viewRotation.value = 0
        resetHistory()
        clearAnnotationRepliesMap()
        void nextTick(() => { fitToWidth() })
    }

    function clearPageThumbnails() {
        for (const key of Object.keys(pageThumbnails)) {
            delete pageThumbnails[Number(key)]
        }
        for (const key of Object.keys(thumbnailLoadingPages)) {
            delete thumbnailLoadingPages[Number(key)]
        }
        thumbnailQueue.length = 0
        clearThumbnailRefreshTimers()
    }

    function clearThumbnailRefreshTimers() {
        for (const key of Object.keys(thumbnailRefreshTimers)) {
            const t = thumbnailRefreshTimers[Number(key)]
            if (t) clearTimeout(t)
            delete thumbnailRefreshTimers[Number(key)]
        }
    }

    /** 清除某页缓存，便于重新截图 */
    function invalidatePageThumbnail(pageIndex: number) {
        delete pageThumbnails[pageIndex]
        delete thumbnailLoadingPages[pageIndex]
        const qIdx = thumbnailQueue.indexOf(pageIndex)
        if (qIdx >= 0) thumbnailQueue.splice(qIdx, 1)
    }

    /** 立即作废并重新加入生成队列 */
    function refreshPageThumbnail(pageIndex: number) {
        if (!document.value || !thumbnailCaptureHook) return
        if (pageIndex < 0 || pageIndex >= document.value.pageCount) return
        invalidatePageThumbnail(pageIndex)
        requestPageThumbnail(pageIndex)
    }

    /** 编辑后防抖刷新缩略图（避免拖拽时每帧截图） */
    function schedulePageThumbnailRefresh(pageIndex: number, delayMs = 500) {
        if (!document.value) return
        if (pageIndex < 0 || pageIndex >= document.value.pageCount) return
        const prev = thumbnailRefreshTimers[pageIndex]
        if (prev) clearTimeout(prev)
        thumbnailRefreshTimers[pageIndex] = setTimeout(() => {
            delete thumbnailRefreshTimers[pageIndex]
            refreshPageThumbnail(pageIndex)
        }, delayMs)
    }

    function setPageThumbnail(pageIndex: number, dataUrl: string) {
        pageThumbnails[pageIndex] = dataUrl
        delete thumbnailLoadingPages[pageIndex]
    }

    function registerThumbnailCaptureHook(
        hook: ((pageIndex: number) => Promise<string | null>) | null,
    ) {
        thumbnailCaptureHook = hook
    }

    function isPageThumbnailLoading(pageIndex: number): boolean {
        return !!thumbnailLoadingPages[pageIndex] && !pageThumbnails[pageIndex]
    }

    const thumbnailLoadedCount = computed(() => Object.keys(pageThumbnails).length)

    function requestPageThumbnail(pageIndex: number) {
        if (!document.value || !thumbnailCaptureHook) return
        if (pageIndex < 0 || pageIndex >= document.value.pageCount) return
        if (pageThumbnails[pageIndex] || thumbnailLoadingPages[pageIndex]) return
        if (thumbnailQueue.includes(pageIndex)) return

        thumbnailQueue.push(pageIndex)
        void processThumbnailQueue()
    }

    async function processThumbnailQueue() {
        if (thumbnailWorkerRunning || !thumbnailCaptureHook || !document.value) return
        thumbnailWorkerRunning = true
        isGeneratingThumbnails.value = true

        try {
            while (thumbnailQueue.length > 0) {
                const pageIndex = thumbnailQueue.shift()!
                if (pageThumbnails[pageIndex]) continue
                if (pageIndex < 0 || pageIndex >= document.value!.pageCount) continue

                thumbnailLoadingPages[pageIndex] = true
                try {
                    let dataUrl = await thumbnailCaptureHook(pageIndex)
                    // 首次失败（离屏画布未就绪等）短暂重试一次
                    if (!dataUrl) {
                        await new Promise((r) => setTimeout(r, 120))
                        dataUrl = await thumbnailCaptureHook(pageIndex)
                    }
                    if (dataUrl) pageThumbnails[pageIndex] = dataUrl
                } catch (e) {
                    console.warn(`[editorStore] 缩略图生成失败 page=${pageIndex}:`, e)
                } finally {
                    delete thumbnailLoadingPages[pageIndex]
                }
            }
        } finally {
            thumbnailWorkerRunning = false
            isGeneratingThumbnails.value = thumbnailQueue.length > 0
            if (isGeneratingThumbnails.value) void processThumbnailQueue()
        }
    }

    /** 页面顺序变更后，按「新下标 → 旧下标」映射保留已有缩略图 */
    function remapPageThumbnails(oldIndexAtNewPos: number[]) {
        const prev: Record<number, string> = {}
        for (const key of Object.keys(pageThumbnails)) {
            prev[Number(key)] = pageThumbnails[Number(key)]
        }
        for (const key of Object.keys(pageThumbnails)) {
            delete pageThumbnails[Number(key)]
        }
        oldIndexAtNewPos.forEach((oldIdx, newIdx) => {
            if (prev[oldIdx] !== undefined) pageThumbnails[newIdx] = prev[oldIdx]
        })
    }

    function buildOrderAfterMove(fromIndex: number, toIndex: number, count: number): number[] {
        const order = Array.from({ length: count }, (_, i) => i)
        const [moved] = order.splice(fromIndex, 1)
        order.splice(toIndex, 0, moved)
        return order
    }

    function shiftThumbnailsAfterInsert(insertAt: number, copyFromIndex?: number) {
        const n = document.value?.pageCount ?? 0
        const prev: Record<number, string> = {}
        for (const key of Object.keys(pageThumbnails)) {
            prev[Number(key)] = pageThumbnails[Number(key)]
        }
        for (const key of Object.keys(pageThumbnails)) {
            delete pageThumbnails[Number(key)]
        }
        for (let newIdx = 0; newIdx < n; newIdx++) {
            if (newIdx === insertAt) {
                if (copyFromIndex !== undefined && prev[copyFromIndex] !== undefined) {
                    pageThumbnails[newIdx] = prev[copyFromIndex]
                }
                continue
            }
            const oldIdx = newIdx < insertAt ? newIdx : newIdx - 1
            if (prev[oldIdx] !== undefined) pageThumbnails[newIdx] = prev[oldIdx]
        }
    }

    function shiftThumbnailsAfterDelete(deletedIndex: number) {
        const n = document.value?.pageCount ?? 0
        const prev: Record<number, string> = {}
        for (const key of Object.keys(pageThumbnails)) {
            prev[Number(key)] = pageThumbnails[Number(key)]
        }
        for (const key of Object.keys(pageThumbnails)) {
            delete pageThumbnails[Number(key)]
        }
        for (let newIdx = 0; newIdx < n; newIdx++) {
            const oldIdx = newIdx < deletedIndex ? newIdx : newIdx + 1
            if (prev[oldIdx] !== undefined) pageThumbnails[newIdx] = prev[oldIdx]
        }
    }

    function setCurrentFile(file: File | null, source?: DocumentSource) {
        currentFile.value = file
        if (source) {
            documentSource.value = source
        } else if (!file) {
            documentSource.value = null
        }
    }

    let scrollToPageInViewHook: ((pageIndex: number) => void) | null = null
    let exportCurrentPageImageHook: (() => Promise<void>) | null = null

    function registerScrollToPageInViewHook(hook: (pageIndex: number) => void) {
        scrollToPageInViewHook = hook
    }

    function registerExportCurrentPageImageHook(hook: (() => Promise<void>) | null) {
        exportCurrentPageImageHook = hook
    }

    async function exportCurrentPageImage() {
        if (!document.value) return
        if (!exportCurrentPageImageHook) {
            console.warn('[editorStore] exportCurrentPageImage: hook not registered')
            return
        }
        await exportCurrentPageImageHook()
    }

    function setPageViewMode(mode: PageViewMode) {
        pageViewMode.value = mode
    }

    function setCurrentPage(
        index: number,
        opts?: { preserveSelection?: boolean; scrollIntoView?: boolean },
    ) {
        if (index < 0 || index >= (document.value?.pageCount ?? 0)) return
        currentPageIndex.value = index
        if (!opts?.preserveSelection) {
            selectedElementId.value = null
            selectedAnnotationId.value = null
        }
        if (opts?.scrollIntoView) {
            scrollToPageInViewHook?.(index)
        }
    }

    function selectElement(id: string | null) {
        selectedElementId.value = id
        if (id) selectedAnnotationId.value = null
    }

    function setScale(val: number) {
        scale.value = Math.max(0.25, Math.min(3, val))
    }

    function rotateViewClockwise() {
        viewRotation.value = normalizeViewRotation(viewRotation.value + 90)
    }

    function rotateViewCounterClockwise() {
        viewRotation.value = normalizeViewRotation(viewRotation.value - 90)
    }

    function resetViewRotation() {
        viewRotation.value = 0
    }

    function setWatermarkConfig(wm: WatermarkConfig | null) {
        watermarkConfig.value = wm
    }

    /** 持久旋转当前页 90°（写入 pageRotate；OFD 同步变换元素/批注坐标） */
    function rotateCurrentPagePersist(clockwise = true) {
        if (!document.value) return false
        const idx = currentPageIndex.value
        const page = document.value.pages[idx]
        if (!page) return false

        const delta = clockwise ? 90 : -90
        page.pageRotate = normalizePageRotate((page.pageRotate ?? 0) + delta)

        if (documentKind.value === 'ofd') {
            let pw = page.width
            let ph = page.height
            for (const el of page.elements) {
                if (el.isDeleted) continue
                const r = rotateElementInPage(el, pw, ph, clockwise)
                pw = r.pageW
                ph = r.pageH
            }
            for (const ann of annotationsMap[idx] ?? []) {
                const r = rotateAnnotationInPage(ann, pw, ph, clockwise)
                pw = r.pageW
                ph = r.pageH
            }
            page.width = pw
            page.height = ph
        } else {
            // PDF：仅记录 pageRotate，展示与导出分别由 Canvas /export-pdf 处理
        }

        saveToHistory()
        renderVersion.value++
        schedulePageThumbnailRefresh(idx)
        return true
    }

    /** 与 App.vue `.editor-area` 的四边 padding 之和（各 24px，取宽/高方向合计 48） */
    const EDITOR_AREA_PADDING = 48

    let editorAreaResolver: (() => HTMLElement | null) | null = null

    function registerEditorAreaResolver(resolver: () => HTMLElement | null) {
        editorAreaResolver = resolver
    }

    function getFitViewport(): { page: PageData; area: HTMLElement } | null {
        const page = currentPage.value
        const area = editorAreaResolver?.()
        if (!page || !area) return null
        return { page, area }
    }

    /**
     * 适应宽度：按当前页宽度缩放，使画布横向铺满编辑区且不出现水平滚动条。
     */
    function fitToWidth(): boolean {
        const ctx = getFitViewport()
        if (!ctx) return false

        const eff = effectivePageSizeMm(ctx.page.width, ctx.page.height, viewRotation.value)
        const baseWidthPx = eff.widthMm * MM_TO_PX
        if (baseWidthPx <= 0) return false

        const availableW = ctx.area.clientWidth - EDITOR_AREA_PADDING - 2
        if (availableW <= 0) return false

        setScale(availableW / baseWidthPx)
        ctx.area.scrollLeft = 0
        return true
    }

    /**
     * 适应页面：按当前页宽高缩放，使整页可见且不出现横向/纵向滚动条。
     */
    function fitToPage(): boolean {
        const ctx = getFitViewport()
        if (!ctx) return false

        const eff = effectivePageSizeMm(ctx.page.width, ctx.page.height, viewRotation.value)
        const baseWidthPx = eff.widthMm * MM_TO_PX
        const baseHeightPx = eff.heightMm * MM_TO_PX
        if (baseWidthPx <= 0 || baseHeightPx <= 0) return false

        const availableW = ctx.area.clientWidth - EDITOR_AREA_PADDING - 2
        const availableH = ctx.area.clientHeight - EDITOR_AREA_PADDING - 2
        if (availableW <= 0 || availableH <= 0) return false

        setScale(Math.min(availableW / baseWidthPx, availableH / baseHeightPx))
        ctx.area.scrollLeft = 0
        ctx.area.scrollTop = 0
        return true
    }

    function setLoading(val: boolean, text = '处理中...') {
        if (!val) stopTransferProgress()
        isLoading.value = val
        loadingText.value = text
        loadingProgress.value = val ? 0 : null
    }

    function setLoadingProgress(percent: number | null, text?: string) {
        loadingProgress.value = percent
        if (text !== undefined) loadingText.value = text
    }

    registerProgressReporter((percent, text) => {
        setLoadingProgress(percent, text)
    })

    /**
     * 对 IMAGE 元素应用像素级裁剪，并同步缩小外框尺寸（mm）。
     */
    async function applyImageCrop(
        pageIndex: number,
        elementId: string,
        crop: CropRect,
        croppedDataUrl: string,
        sourceNatural: { width: number; height: number },
    ): Promise<boolean> {
        if (!document.value) return false
        const page = document.value.pages[pageIndex]
        const element = page?.elements.find((e) => e.id === elementId)
        if (!element || element.type !== 'IMAGE') return false

        const bounds = computeElementBoundsAfterCrop(
            element.x ?? 0,
            element.y ?? 0,
            element.width ?? 0,
            element.height ?? 0,
            sourceNatural.width,
            sourceNatural.height,
            crop,
        )

        // 预加载裁剪图，避免画布仍绑定旧 HTMLImageElement
        await loadImageDimensions(croppedDataUrl)

        updateElement(pageIndex, elementId, {
            imageBase64: croppedDataUrl,
            imageData: croppedDataUrl,
            imageUrl: undefined,
            x: bounds.x,
            y: bounds.y,
            width: bounds.width,
            height: bounds.height,
            scaleX: 1,
            scaleY: 1,
            imageContentDirty: true,
            imageRevision: (element.imageRevision ?? 0) + 1,
        })
        renderVersion.value++
        return true
    }

    function canCropSelectedImage(): boolean {
        const el = selectedElement.value
        if (!el || el.type !== 'IMAGE') return false
        return !!getElementImageSrc(el)
    }

    function openImageCropDialog(): boolean {
        if (!canCropSelectedImage()) return false
        imageCropDialogVisible.value = true
        return true
    }

    function updateElement(
        pageIndex: number,
        elementId: string,
        changes: Partial<ElementData>
    ) {
        if (!document.value) return
        const page = document.value.pages[pageIndex]
        if (!page) return
        const element = page.elements.find((e) => e.id === elementId)
        if (!element) return

        if (!element.isDirty) {
            element.originalX = element.x
            element.originalY = element.y
            element.originalWidth = element.width
            element.originalHeight = element.height
            element.originalRotation = element.rotation ?? 0
        }

        const next: Partial<ElementData> & { isDirty: true } = { ...changes, isDirty: true }
        // 用户在属性面板改了字号 → 关掉前端自动 clamp，让用户输入真正生效
        if (changes.fontSize !== undefined) next.fontSizeOverridden = true

        Object.assign(element, next)
        clearTextItemsCache()
        saveToHistory()
        schedulePageThumbnailRefresh(pageIndex)
    }

    function resetElement(pageIndex: number, elementId: string) {
        if (!document.value) return
        const page = document.value.pages[pageIndex]
        const idx = page?.elements.findIndex((e) => e.id === elementId) ?? -1
        if (idx === -1) return
        const element = page!.elements[idx]

        if (element.isNew) {
            page!.elements.splice(idx, 1)
            if (selectedElementId.value === elementId) selectedElementId.value = null
            saveToHistory()
            schedulePageThumbnailRefresh(pageIndex)
            return
        }

        if (!element.isDirty) return

        element.x = element.originalX ?? element.x
        element.y = element.originalY ?? element.y
        element.width = element.originalWidth ?? element.width
        element.height = element.originalHeight ?? element.height
        element.rotation = element.originalRotation ?? 0
        element.scaleX = 1
        element.scaleY = 1
        element.isDirty = false
        saveToHistory()
        schedulePageThumbnailRefresh(pageIndex)
    }

    /**
     * 删除元素：
     * - 新插入未保存的元素（isNew）：直接从列表移除，无需后端
     * - 已有 OFD 元素：标记 isDeleted（+isDirty），保留在列表里，保存时由后端移除原节点
     */
    function deleteElement(pageIndex: number, elementId: string): boolean {
        if (!document.value) return false
        const page = document.value.pages[pageIndex]
        const idx = page?.elements.findIndex((e) => e.id === elementId) ?? -1
        if (idx === -1) return false
        const element = page!.elements[idx]

        if (element.isNew) {
            page!.elements.splice(idx, 1)
        } else {
            element.isDeleted = true
            element.isDirty = true
        }
        if (selectedElementId.value === elementId) selectedElementId.value = null
        clearTextItemsCache()
        saveToHistory()
        schedulePageThumbnailRefresh(pageIndex)
        return true
    }

    function deleteSelectedElement(): boolean {
        if (!selectedElementId.value) return false
        return deleteElement(currentPageIndex.value, selectedElementId.value)
    }

    function insertPage(position: number) {
        if (!document.value) return
        const newPage: PageData = {
            id: `page-${Date.now()}-new`,
            pageIndex: position,
            width: 210,
            height: 297,
            elements: [],
        }
        document.value.pages.splice(position, 0, newPage)
        document.value.pageCount += 1
        document.value.pages.forEach((p, i) => { p.pageIndex = i })
        remapAnnotationsAfterInsert(position, [])
        shiftThumbnailsAfterInsert(position)
        if (currentPageIndex.value >= position) {
            currentPageIndex.value += 1
        }
        saveToHistory()
    }

    function deletePage(pageIndex: number) {
        if (!document.value || document.value.pageCount <= 1) return
        document.value.pages.splice(pageIndex, 1)
        document.value.pageCount -= 1
        document.value.pages.forEach((p, i) => { p.pageIndex = i })
        remapAnnotationsAfterDelete(pageIndex)
        if (currentPageIndex.value >= pageIndex) {
            currentPageIndex.value = Math.max(0, currentPageIndex.value - 1)
        }
        currentPageIndex.value = Math.min(currentPageIndex.value, document.value.pageCount - 1)
        shiftThumbnailsAfterDelete(pageIndex)
        saveToHistory()
    }

    /** 将页面从 fromIndex 拖到 toIndex */
    function movePage(fromIndex: number, toIndex: number) {
        if (!document.value) return
        const n = document.value.pageCount
        if (fromIndex === toIndex || fromIndex < 0 || toIndex < 0 || fromIndex >= n || toIndex >= n) {
            return
        }
        const pages = document.value.pages
        const [page] = pages.splice(fromIndex, 1)
        pages.splice(toIndex, 0, page)
        pages.forEach((p, i) => { p.pageIndex = i })
        remapAnnotationsAfterMove(fromIndex, toIndex)
        remapPageThumbnails(buildOrderAfterMove(fromIndex, toIndex, n))
        adjustCurrentPageAfterMove(fromIndex, toIndex)
        saveToHistory()
    }

    function reorderPages(newOrder: number[]) {
        if (!document.value) return
        const n = document.value.pageCount
        if (newOrder.length !== n) return

        const oldPages = [...document.value.pages]
        const oldAnns: Record<number, AnnotationData[]> = {}
        for (const key of Object.keys(annotationsMap)) {
            oldAnns[Number(key)] = [...(annotationsMap[Number(key)] ?? [])]
        }

        document.value.pages = newOrder.map((oldIdx, newIdx) => ({
            ...oldPages[oldIdx],
            pageIndex: newIdx,
        }))

        for (const key of Object.keys(annotationsMap)) {
            delete annotationsMap[Number(key)]
        }
        for (let newIdx = 0; newIdx < n; newIdx++) {
            const oldIdx = newOrder[newIdx]
            annotationsMap[newIdx] = (oldAnns[oldIdx] ?? []).map(a => ({
                ...a,
                pageIndex: newIdx,
            }))
        }

        const oldCur = currentPageIndex.value
        const newCur = newOrder.indexOf(oldCur)
        currentPageIndex.value = newCur >= 0 ? newCur : 0
        remapPageThumbnails(newOrder)
        saveToHistory()
    }

    /** 复制页面（含元素与注释），默认插入到源页之后 */
    async function copyPage(sourceIndex: number, insertAt?: number) {
        if (!document.value) return
        const src = document.value.pages[sourceIndex]
        if (!src) return

        const position = insertAt ?? sourceIndex + 1
        const ts = Date.now()

        const clonedPage: PageData = JSON.parse(JSON.stringify(src))
        clonedPage.id = `page-${ts}-copy`
        clonedPage.pageIndex = position
        clonedPage.sourcePageIndex = src.sourcePageIndex ?? sourceIndex
        clonedPage.elements = clonedPage.elements.map((el, i) => ({
            ...el,
            id: newElementId('el', i),
        }))

        document.value.pages.splice(position, 0, clonedPage)
        document.value.pageCount += 1
        document.value.pages.forEach((p, i) => { p.pageIndex = i })

        const srcAnns = annotationsMap[sourceIndex] ?? []
        let copiedAnns: AnnotationData[] = srcAnns.map((ann, i) => {
            const copy = JSON.parse(JSON.stringify(ann)) as AnnotationData
            copy.id = newElementId('ann', i)
            copy.pageIndex = position
            copy.createdAt = ts
            copy.updatedAt = ts
            return copy
        })

        remapAnnotationsAfterInsert(position, copiedAnns)
        shiftThumbnailsAfterInsert(position, sourceIndex)

        if (fileId.value && copiedAnns.length > 0) {
            const persisted: AnnotationData[] = []
            for (const ann of copiedAnns) {
                const { id, createdAt, updatedAt, ...payload } = ann
                const saved = await ofdApi.addAnnotation(
                    fileId.value,
                    payload as Omit<AnnotationData, 'id' | 'createdAt' | 'updatedAt'>,
                )
                persisted.push(saved)
            }
            annotationsMap[position] = persisted
        }

        currentPageIndex.value = position
        selectedElementId.value = null
        selectedAnnotationId.value = null
        saveToHistory()
        return position
    }

    function cloneAnnotationsForHistory(): Record<number, AnnotationData[]> {
        const out: Record<number, AnnotationData[]> = {}
        for (const key of Object.keys(annotationsMap)) {
            out[Number(key)] = JSON.parse(JSON.stringify(annotationsMap[Number(key)] ?? []))
        }
        return out
    }

    function cloneRepliesForHistory(): Record<string, AnnotationReplyData[]> {
        const out: Record<string, AnnotationReplyData[]> = {}
        for (const key of Object.keys(annotationRepliesMap)) {
            const list = annotationRepliesMap[key]
            if (list?.length) out[key] = JSON.parse(JSON.stringify(list))
        }
        return out
    }

    function applyAnnotationsMap(source: Record<number, AnnotationData[]>) {
        for (const key of Object.keys(annotationsMap)) {
            delete annotationsMap[Number(key)]
        }
        for (const [k, v] of Object.entries(source)) {
            annotationsMap[Number(k)] = JSON.parse(JSON.stringify(v))
        }
    }

    function clearAnnotationRepliesMap() {
        for (const key of Object.keys(annotationRepliesMap)) {
            delete annotationRepliesMap[key]
        }
    }

    function applyRepliesMap(source: Record<string, AnnotationReplyData[]>) {
        clearAnnotationRepliesMap()
        for (const [k, v] of Object.entries(source)) {
            if (v?.length) annotationRepliesMap[k] = JSON.parse(JSON.stringify(v))
        }
    }

    function flatAnnotations(): AnnotationData[] {
        const out: AnnotationData[] = []
        for (const key of Object.keys(annotationsMap)) {
            out.push(...(annotationsMap[Number(key)] ?? []))
        }
        return out
    }

    function getReplyCount(annotationId: string): number {
        return annotationRepliesMap[annotationId]?.length ?? 0
    }

    function getRepliesForAnnotation(annotationId: string): AnnotationReplyData[] {
        return annotationRepliesMap[annotationId] ?? []
    }

    function setDiscussionAuthor(name: string) {
        const v = name.trim() || '用户'
        discussionAuthor.value = v
        saveDiscussionAuthor(v)
    }

    function captureHistoryEntry(): HistoryEntry {
        return {
            document: JSON.parse(JSON.stringify(document.value!)),
            annotations: cloneAnnotationsForHistory(),
            annotationReplies: cloneRepliesForHistory(),
            currentPageIndex: currentPageIndex.value,
        }
    }

    function resetHistory() {
        history.value = []
        historyIndex.value = -1
    }

    /** 文档与注释加载完成后，将当前状态设为撤销基线（不可再撤销） */
    function commitHistoryBaseline() {
        if (!document.value) return
        history.value = [captureHistoryEntry()]
        historyIndex.value = 0
    }

    /** 保存成功后调用，清除「未保存」状态 */
    function markDocumentSaved() {
        commitHistoryBaseline()
    }

    function saveToHistory() {
        if (!document.value || historySyncInProgress) return
        const entry = captureHistoryEntry()
        history.value = history.value.slice(0, historyIndex.value + 1)
        history.value.push(entry)
        if (history.value.length > 50) {
            history.value.shift()
        }
        historyIndex.value = history.value.length - 1
    }

    /** 撤销/重做后，将本地注释快照写回后端缓存，保证导出与保存一致 */
    async function syncAnnotationsAfterRestore() {
        if (!fileId.value || historySyncInProgress) return
        historySyncInProgress = true
        try {
            const oldFlat = flatAnnotations()
            const oldReplies = cloneRepliesForHistory()

            const serverAll = await ofdApi.getAllAnnotations(fileId.value)
            for (const list of Object.values(serverAll)) {
                for (const ann of list) {
                    await ofdApi.deleteAnnotation(fileId.value, ann.id)
                }
            }

            const rebuilt: Record<number, AnnotationData[]> = {}
            const pageIndices = Object.keys(annotationsMap).map(Number).sort((a, b) => a - b)
            for (const pageIdx of pageIndices) {
                const list = annotationsMap[pageIdx] ?? []
                if (list.length === 0) continue
                rebuilt[pageIdx] = []
                for (const ann of list) {
                    const { id, createdAt, updatedAt, ...payload } = ann
                    const saved = await ofdApi.addAnnotation(
                        fileId.value,
                        payload as Omit<AnnotationData, 'id' | 'createdAt' | 'updatedAt'>,
                    )
                    rebuilt[pageIdx].push(saved)
                }
            }
            applyAnnotationsMap(rebuilt)

            const newFlat = flatAnnotations()
            const remapped = remapRepliesByFingerprint(oldFlat, newFlat, oldReplies)
            applyRepliesMap(remapped)
            await ofdApi.syncAnnotationReplies(fileId.value, remapped)

            const current = history.value[historyIndex.value]
            if (current) {
                current.annotations = cloneAnnotationsForHistory()
                current.annotationReplies = cloneRepliesForHistory()
            }
        } catch (e) {
            console.error('[editorStore] 撤销后注释同步失败:', e)
        } finally {
            historySyncInProgress = false
        }
    }

    /**
     * 递归把 source 的内容写到 target 上（原地修改，保留 target 的引用）。
     *
     * 旧实现里 undo / redo 用 `document.value = JSON.parse(JSON.stringify(snap))`
     * 整个把 document 换成新对象。这样虽然 Pinia / Vue 顶层 ref 会触发，但
     * vue-konva 的 v-text 用的是 `watch(() => a.config, ..., { deep: true })`，
     * 在某些时序下新旧 config 的 deep diff 不能稳定唤醒 Konva 节点的 setAttrs，
     * 导致数据回退了但 canvas 上 text/fontSize/fill 依旧停留在撤销前。
     *
     * 改成原地深合并后，每个 element 上 content/fontSize/color 这类原子字段
     * 的赋值都会直接命中 reactive proxy，Vue 会按属性触发 getTextConfig 重算，
     * vue-konva 的 watcher 也能稳定拿到 diff 后的新 config。
     */
    function applyInPlace(target: any, source: any): void {
        if (Array.isArray(target) && Array.isArray(source)) {
            if (target.length > source.length) target.length = source.length
            for (let i = 0; i < source.length; i++) {
                const tv = target[i]
                const sv = source[i]
                if (tv && sv && typeof tv === 'object' && typeof sv === 'object') {
                    applyInPlace(tv, sv)
                } else {
                    target[i] = sv
                }
            }
            return
        }
        if (target && source && typeof target === 'object' && typeof source === 'object') {
            for (const k of Object.keys(target)) {
                if (!(k in source)) delete target[k]
            }
            for (const k of Object.keys(source)) {
                const tv = target[k]
                const sv = source[k]
                if (tv && sv && typeof tv === 'object' && typeof sv === 'object'
                    && Array.isArray(tv) === Array.isArray(sv)) {
                    applyInPlace(tv, sv)
                } else {
                    target[k] = sv
                }
            }
        }
    }

    function restoreFromHistory(idx: number) {
        if (idx < 0 || idx >= history.value.length) return
        const entry = JSON.parse(JSON.stringify(history.value[idx])) as HistoryEntry
        if (!document.value) {
            document.value = entry.document
        } else {
            applyInPlace(document.value, entry.document)
        }
        applyAnnotationsMap(entry.annotations)
        applyRepliesMap(entry.annotationReplies ?? {})
        const maxPage = Math.max(0, (document.value?.pageCount ?? 1) - 1)
        currentPageIndex.value = Math.min(Math.max(0, entry.currentPageIndex), maxPage)
        selectedElementId.value = null
        selectedAnnotationId.value = null
    }

    async function undo() {
        if (!canUndo.value) return
        historyIndex.value -= 1
        restoreFromHistory(historyIndex.value)
        renderVersion.value++
        schedulePageThumbnailRefresh(currentPageIndex.value, 400)
        await syncAnnotationsAfterRestore()
    }

    async function redo() {
        if (!canRedo.value) return
        historyIndex.value += 1
        restoreFromHistory(historyIndex.value)
        renderVersion.value++
        schedulePageThumbnailRefresh(currentPageIndex.value, 400)
        await syncAnnotationsAfterRestore()
    }

    function getDocumentForSave(): DocumentData | null {
        if (!document.value) return null
        const pages = document.value.pages.map((page) => {
            const { pageRotate, ...rest } = page
            return {
                ...rest,
                rotate: pageRotate,
                elements: page.elements.map((el) => ({ ...el })),
            }
        })
        return {
            ...document.value,
            pages,
            fileId: fileId.value ?? undefined,
            watermark: watermarkConfig.value ?? undefined,
        }
    }

    /** 保存成功后调用：同步 original*、清除 dirty / isNew / imageContentDirty，并清掉已删除元素 */
    function markNewElementsPersisted() {
        if (!document.value) return
        for (const page of document.value.pages) {
            // 已标记删除的元素在保存写回后从列表彻底移除
            page.elements = page.elements.filter((el) => !el.isDeleted)
            for (const el of page.elements) {
                if (el.isNew) el.isNew = false
                el.imageContentDirty = false
                if (el.isDirty) {
                    el.originalX = el.x
                    el.originalY = el.y
                    el.originalWidth = el.width
                    el.originalHeight = el.height
                    el.originalRotation = el.rotation ?? 0
                    el.isDirty = false
                }
            }
        }
        saveToHistory()
    }

    // ==================== 注释方法 ====================
    function setTool(tool: ToolType) {
        currentTool.value = tool
        selectedAnnotationId.value = null
        if (tool !== 'SELECT') selectedElementId.value = null
    }

    function setPendingStampImage(dataUrl: string) {
        pendingStampImage.value = dataUrl
        currentTool.value = 'STAMP'
        selectedAnnotationId.value = null
        selectedElementId.value = null
    }

    function clearPendingStampImage() {
        pendingStampImage.value = null
    }

    function setAnnotationColor(color: string) {
        annotationColor.value = color
    }

    function setAnnotationOpacity(opacity: number) {
        annotationOpacity.value = Math.max(0, Math.min(1, opacity))
    }

    function setAnnotationLineWidth(width: number) {
        annotationLineWidth.value = width
    }

    function selectAnnotation(id: string | null) {
        selectedAnnotationId.value = id
        if (id) {
            selectedElementId.value = null
            rightPanelTab.value = 'properties'
        }
    }

    function openAnnotationListPanel() {
        rightPanelTab.value = 'annotations'
    }

    function focusAnnotation(annotationId: string) {
        for (const key of Object.keys(annotationsMap)) {
            const pageIdx = Number(key)
            const ann = annotationsMap[pageIdx]?.find((a) => a.id === annotationId)
            if (!ann) continue
            setCurrentPage(pageIdx, { scrollIntoView: true })
            setTool('SELECT')
            selectAnnotation(annotationId)
            rightPanelTab.value = 'properties'
            return
        }
    }

    async function setAnnotationHidden(annotationId: string, hidden: boolean) {
        const ok = await updateAnnotation(annotationId, { hidden })
        if (ok && hidden && selectedAnnotationId.value === annotationId) {
            selectedAnnotationId.value = null
        }
        return ok
    }

    async function addAnnotation(
        annotationInput: Omit<AnnotationData, 'id' | 'createdAt' | 'updatedAt'>
    ): Promise<AnnotationData | null> {
        if (!fileId.value) {
            console.error('[editorStore] 无 fileId，无法保存注释')
            return null
        }
        try {
            const saved = await ofdApi.addAnnotation(fileId.value, annotationInput)
            const pageIdx = saved.pageIndex
            // ✅ 直接操作 reactive 对象
            if (!annotationsMap[pageIdx]) {
                annotationsMap[pageIdx] = []
            }
            annotationsMap[pageIdx].push(saved)
            renderVersion.value++
            schedulePageThumbnailRefresh(pageIdx, 400)
            saveToHistory()
            return saved
        } catch (e) {
            console.error('[editorStore] 添加注释失败:', e)
            return null
        }
    }

    async function updateAnnotation(
        annotationId: string,
        changes: Partial<AnnotationData>
    ): Promise<boolean> {
        if (!fileId.value) return false

        let affectedPageIndex: number | null = null
        for (const key of Object.keys(annotationsMap)) {
            const pageIdx = Number(key)
            const list = annotationsMap[pageIdx]
            const index = list?.findIndex(a => a.id === annotationId) ?? -1
            if (index !== -1) {
                affectedPageIndex = changes.pageIndex ?? pageIdx
                list[index] = { ...list[index], ...changes }
                break
            }
        }

        try {
            await ofdApi.updateAnnotation(fileId.value, annotationId, changes)
            if (affectedPageIndex !== null) {
                schedulePageThumbnailRefresh(affectedPageIndex, 400)
            }
            saveToHistory()
            return true
        } catch (e) {
            console.error('[editorStore] 更新注释失败:', e)
            return false
        }
    }

    async function deleteAnnotation(annotationId: string): Promise<boolean> {
        if (!fileId.value) return false

        let affectedPageIndex: number | null = null
        for (const key of Object.keys(annotationsMap)) {
            const pageIdx = Number(key)
            const list = annotationsMap[pageIdx]
            const idx = list?.findIndex(a => a.id === annotationId) ?? -1
            if (idx !== -1) {
                affectedPageIndex = pageIdx
                break
            }
        }

        try {
            await ofdApi.deleteAnnotation(fileId.value, annotationId)
            for (const key of Object.keys(annotationsMap)) {
                const pageIdx = Number(key)
                const list = annotationsMap[pageIdx]
                const idx = list?.findIndex(a => a.id === annotationId) ?? -1
                if (idx !== -1) {
                    list.splice(idx, 1)
                    break
                }
            }
            if (selectedAnnotationId.value === annotationId) {
                selectedAnnotationId.value = null
            }
            delete annotationRepliesMap[annotationId]
            if (affectedPageIndex !== null) {
                schedulePageThumbnailRefresh(affectedPageIndex, 400)
            }
            saveToHistory()
            return true
        } catch (e) {
            console.error('[editorStore] 删除注释失败:', e)
            return false
        }
    }

    function executeLinkAction(ann: AnnotationData): boolean {
        const pageCount = document.value?.pageCount ?? 0
        return runLinkAction(ann, {
            pageCount,
            goToPage: (index) => setCurrentPage(index, { scrollIntoView: true }),
        })
    }

    async function loadAllAnnotations(): Promise<void> {
        try {
            if (!fileId.value) return
            const all = await ofdApi.getAllAnnotations(fileId.value)
            // ✅ 清空后重新赋值
            for (const key of Object.keys(annotationsMap)) {
                delete annotationsMap[Number(key)]
            }
            for (const [pageIdx, list] of Object.entries(all)) {
                annotationsMap[Number(pageIdx)] = list
            }
            clearAnnotationRepliesMap()
            try {
                const replies = await ofdApi.getAllAnnotationReplies(fileId.value)
                applyRepliesMap(replies ?? {})
            } catch {
                // 无讨论数据时忽略
            }
            console.log('[editorStore] 注释加载完成')
        } catch (e) {
            console.warn('[editorStore] 加载注释失败（可能暂无注释）:', e)
        } finally {
            commitHistoryBaseline()
            requestPageThumbnail(currentPageIndex.value)
            requestPageThumbnail(0)
        }
    }

    async function addAnnotationReply(
        annotationId: string,
        content: string,
        parentReplyId?: string,
    ): Promise<AnnotationReplyData | null> {
        if (!fileId.value) return null
        const text = content.trim()
        if (!text) return null
        try {
            const saved = await ofdApi.addAnnotationReply(fileId.value, annotationId, {
                author: discussionAuthor.value,
                content: text,
                parentReplyId: parentReplyId || undefined,
            })
            if (!annotationRepliesMap[annotationId]) {
                annotationRepliesMap[annotationId] = []
            }
            annotationRepliesMap[annotationId].push(saved)
            saveToHistory()
            return saved
        } catch (e) {
            console.error('[editorStore] 添加讨论回复失败:', e)
            return null
        }
    }

    async function updateAnnotationReply(
        annotationId: string,
        replyId: string,
        content: string,
    ): Promise<boolean> {
        if (!fileId.value) return false
        const text = content.trim()
        if (!text) return false
        try {
            const updated = await ofdApi.updateAnnotationReply(
                fileId.value, annotationId, replyId, { content: text },
            )
            const list = annotationRepliesMap[annotationId]
            const idx = list?.findIndex(r => r.id === replyId) ?? -1
            if (idx !== -1 && list) list[idx] = updated
            saveToHistory()
            return true
        } catch (e) {
            console.error('[editorStore] 更新讨论回复失败:', e)
            return false
        }
    }

    async function deleteAnnotationReply(
        annotationId: string,
        replyId: string,
    ): Promise<boolean> {
        if (!fileId.value) return false
        try {
            await ofdApi.deleteAnnotationReply(fileId.value, annotationId, replyId)
            const list = annotationRepliesMap[annotationId]
            if (list) {
                annotationRepliesMap[annotationId] = list.filter(
                    r => r.id !== replyId && r.parentReplyId !== replyId,
                )
                if (annotationRepliesMap[annotationId].length === 0) {
                    delete annotationRepliesMap[annotationId]
                }
            }
            saveToHistory()
            return true
        } catch (e) {
            console.error('[editorStore] 删除讨论回复失败:', e)
            return false
        }
    }

    function getAnnotationsByPage(pageIndex: number): AnnotationData[] {
        return annotationsMap[pageIndex] ?? []
    }

    /** 构造原生 PDF 导出负载：当前页序（含原始页号/空白页/旋转）+ 各页注释 + 水印 */
    function buildPdfExportPayload() {
        const pages = (document.value?.pages ?? []).map((p) => ({
            sourceIndex: p.sourcePageIndex ?? null,
            widthMm: p.width,
            heightMm: p.height,
            rotate: p.pageRotate ? normalizePageRotate(p.pageRotate) : undefined,
        }))
        const annotations: Record<number, any[]> = {}
        ;(document.value?.pages ?? []).forEach((_, idx) => {
            const list = annotationsMap[idx] ?? []
            if (list.length === 0) return
            annotations[idx] = list.map((a) => {
                const o: any = { ...a }
                if (Array.isArray(o.pathPoints)) o.pathPoints = JSON.stringify(o.pathPoints)
                return o
            })
        })
        const wm = watermarkConfig.value
        return {
            pages,
            annotations,
            watermark: wm?.text ? wm : undefined,
        }
    }

    /** 从当前文档状态提取指定页（1 基，当前顺序），返回新文件 blob */
    async function extractPagesAsBlob(pages1Based: number[]): Promise<Blob> {
        if (!document.value) throw new Error('无打开文档')
        let blob: Blob
        if (documentKind.value === 'pdf') {
            if (!fileId.value) throw new Error('文件会话已失效')
            blob = await ofdApi.exportPdfWithAnnotations(fileId.value, buildPdfExportPayload())
            return ofdApi.extractPdf(blob, pages1Based)
        }
        blob = await ofdApi.saveOfd(getDocumentForSave()!)
        return ofdApi.extractOfd(blob, pages1Based)
    }

    async function exportWithAnnotations(filename?: string): Promise<void> {
        if (!fileId.value) return
        try {
            setLoading(true, '导出中...')
            const isPdf = documentKind.value === 'pdf'
            const blob = isPdf
                ? await ofdApi.exportPdfWithAnnotations(fileId.value, buildPdfExportPayload())
                : await ofdApi.exportWithAnnotations(fileId.value)
            const { downloadBlob } = await import('@/api/ofdApi')
            const fallback = isPdf ? 'annotated.pdf' : 'annotated.ofd'
            downloadBlob(blob, filename ?? fallback)
        } catch (e) {
            console.error('[editorStore] 导出失败:', e)
        } finally {
            setLoading(false)
        }
    }

    /** 返回当前原生 PDF 烘焙批注后的 PDF blob（不触发下载），供「保存为 OFD」等二次转换使用 */
    async function getAnnotatedPdfBlob(): Promise<Blob | null> {
        if (!fileId.value || documentKind.value !== 'pdf') return null
        return ofdApi.exportPdfWithAnnotations(fileId.value, buildPdfExportPayload())
    }

    return {
        // ── 原有状态 ──
        document, currentPageIndex, pageViewMode, viewRotation, selectedElementId,
        scale, isLoading, loadingText, loadingProgress, currentFile, documentSource, documentKind,
        history, historyIndex, fileId, renderVersion,
        printDialogVisible,
        imageCropDialogVisible,
        // ── 搜索 / 文本选择 ──
        searchVisible, searchQuery, searchMatches, searchActiveIndex, searching,
        textSelectMode, searchMatchesByPage,
        openSearch, closeSearch, runSearch, nextMatch, prevMatch, jumpToMatch,
        toggleTextSelectMode, getPageTextItems,
        // ── 注释状态 ──
        currentTool, annotationsMap, annotationRepliesMap, discussionAuthor, selectedAnnotationId,
        rightPanelTab, annotationListScope, flatAnnotationList, filteredAnnotationList, annotationCount,
        annotationColor, annotationOpacity, annotationLineWidth,
        pendingStampImage, hasPendingStamp,
        pageThumbnails, thumbnailLoadingPages, thumbnailLoadedCount, isGeneratingThumbnails,
        // ── 原有计算属性 ──
        currentPage, selectedElement, canDeleteSelectedElement, canUndo, canRedo, hasUnsavedChanges, isPdfDocument,
        // ── 注释计算属性 ──
        currentPageAnnotations, selectedAnnotation,
        isHandTool, isSelectTool, isAnnotationTool,
        // ── 原有方法 ──
        setDocument, setCurrentFile, setCurrentPage, setPageViewMode,
        registerScrollToPageInViewHook,
        registerExportCurrentPageImageHook, exportCurrentPageImage,
        selectElement, setScale, fitToWidth, fitToPage,
        rotateViewClockwise, rotateViewCounterClockwise, resetViewRotation,
        rotateCurrentPagePersist,
        setWatermarkConfig, watermarkConfig,
        extractPagesAsBlob,
        registerEditorAreaResolver, setLoading, setLoadingProgress,
        updateElement, resetElement, deleteElement, deleteSelectedElement,
        importImageToPage, applyImageCrop,
        canCropSelectedImage, openImageCropDialog,
        insertPage, deletePage, movePage, copyPage, reorderPages,
        saveToHistory, resetHistory, commitHistoryBaseline, markDocumentSaved, undo, redo,
        getDocumentForSave, markNewElementsPersisted,
        // ── 注释方法 ──
        setTool, setAnnotationColor, setAnnotationOpacity,
        setAnnotationLineWidth, setPendingStampImage, clearPendingStampImage,
        selectAnnotation, openAnnotationListPanel, focusAnnotation, setAnnotationHidden,
        addAnnotation, updateAnnotation, deleteAnnotation, executeLinkAction,
        getReplyCount, getRepliesForAnnotation, setDiscussionAuthor,
        addAnnotationReply, updateAnnotationReply, deleteAnnotationReply,
        loadAllAnnotations, getAnnotationsByPage,
        exportWithAnnotations,
        getAnnotatedPdfBlob,
        setPageThumbnail, registerThumbnailCaptureHook,
        requestPageThumbnail, isPageThumbnailLoading,
    }
})

// 让 Vite HMR 时直接热替换 store 函数（包括 undo / redo），不需要刷整个页面
if (import.meta.hot) {
    import.meta.hot.accept(acceptHMRUpdate(useEditorStore, import.meta.hot))
}