import { defineStore } from 'pinia'
import { ref, computed, reactive } from 'vue'
import type {
    DocumentData, ElementData, PageData,
    AnnotationData, AnnotationType, ToolType
} from '@/types'
import { ofdApi } from '@/api/ofdApi'

export const useEditorStore = defineStore('editor', () => {

    // ==================== 原有状态 ====================
    const document = ref<DocumentData | null>(null)
    const currentPageIndex = ref(0)
    const selectedElementId = ref<string | null>(null)
    const scale = ref(1.0)
    const isLoading = ref(false)
    const loadingText = ref('处理中...')
    const currentFile = ref<File | null>(null)
    const fileId = ref<string | null>(null)

    const history = ref<DocumentData[]>([])
    const historyIndex = ref(-1)

    // ==================== 注释相关状态 ====================
    const currentTool = ref<ToolType>('SELECT')

    // ✅ 改用 reactive Record，Vue 能完整追踪增删改
    const annotationsMap = reactive<Record<number, AnnotationData[]>>({})

    const selectedAnnotationId = ref<string | null>(null)
    const annotationColor = ref('#000000')
    const annotationOpacity = ref(0.5)
    const annotationLineWidth = ref(2)

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

    const canUndo = computed(() => historyIndex.value > 0)
    const canRedo = computed(() => historyIndex.value < history.value.length - 1)

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

    const isAnnotationTool = computed(() => currentTool.value !== 'SELECT')

    // ==================== 原有方法 ====================
    function setDocument(doc: DocumentData) {
        if (doc.fileId) fileId.value = doc.fileId
        document.value = doc
        currentPageIndex.value = 0
        selectedElementId.value = null
        selectedAnnotationId.value = null
        saveToHistory()
    }

    function setCurrentFile(file: File | null) {
        currentFile.value = file
    }

    function setCurrentPage(index: number) {
        if (index >= 0 && index < (document.value?.pageCount ?? 0)) {
            currentPageIndex.value = index
            selectedElementId.value = null
            selectedAnnotationId.value = null
        }
    }

    function selectElement(id: string | null) {
        selectedElementId.value = id
        if (id) selectedAnnotationId.value = null
    }

    function setScale(val: number) {
        scale.value = Math.max(0.25, Math.min(3, val))
    }

    function setLoading(val: boolean, text = '处理中...') {
        isLoading.value = val
        loadingText.value = text
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

        Object.assign(element, changes, { isDirty: true })
        saveToHistory()
    }

    function resetElement(pageIndex: number, elementId: string) {
        if (!document.value) return
        const page = document.value.pages[pageIndex]
        const element = page?.elements.find((e) => e.id === elementId)
        if (!element || !element.isDirty) return

        element.x = element.originalX ?? element.x
        element.y = element.originalY ?? element.y
        element.width = element.originalWidth ?? element.width
        element.height = element.originalHeight ?? element.height
        element.rotation = element.originalRotation ?? 0
        element.scaleX = 1
        element.scaleY = 1
        element.isDirty = false
        saveToHistory()
    }

    function insertPage(position: number) {
        if (!document.value) return
        const newPage: PageData = {
            pageIndex: position,
            width: 210,
            height: 297,
            elements: [],
        }
        document.value.pages.splice(position, 0, newPage)
        document.value.pageCount += 1
        document.value.pages.forEach((p, i) => { p.pageIndex = i })
        saveToHistory()
    }

    function deletePage(pageIndex: number) {
        if (!document.value || document.value.pageCount <= 1) return
        document.value.pages.splice(pageIndex, 1)
        document.value.pageCount -= 1
        document.value.pages.forEach((p, i) => { p.pageIndex = i })
        currentPageIndex.value = Math.min(
            currentPageIndex.value,
            document.value.pageCount - 1
        )
        saveToHistory()
    }

    function reorderPages(newOrder: number[]) {
        if (!document.value) return
        const oldPages = [...document.value.pages]
        document.value.pages = newOrder.map((oldIdx, newIdx) => ({
            ...oldPages[oldIdx],
            pageIndex: newIdx,
        }))
        saveToHistory()
    }

    function saveToHistory() {
        if (!document.value) return
        const snapshot = JSON.parse(JSON.stringify(document.value))
        history.value = history.value.slice(0, historyIndex.value + 1)
        history.value.push(snapshot)
        if (history.value.length > 50) history.value.shift()
        historyIndex.value = history.value.length - 1
    }

    function undo() {
        if (!canUndo.value) return
        historyIndex.value -= 1
        document.value = JSON.parse(JSON.stringify(history.value[historyIndex.value]))
    }

    function redo() {
        if (!canRedo.value) return
        historyIndex.value += 1
        document.value = JSON.parse(JSON.stringify(history.value[historyIndex.value]))
    }

    function getDocumentForSave(): DocumentData | null {
        if (!document.value) return null
        return { ...document.value, fileId: fileId.value ?? undefined }
    }

    // ==================== 注释方法 ====================
    function setTool(tool: ToolType) {
        currentTool.value = tool
        selectedAnnotationId.value = null
        if (tool !== 'SELECT') selectedElementId.value = null
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
        if (id) selectedElementId.value = null
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

        for (const key of Object.keys(annotationsMap)) {
            const pageIdx = Number(key)
            const list = annotationsMap[pageIdx]
            const index = list?.findIndex(a => a.id === annotationId) ?? -1
            if (index !== -1) {
                // list[index] = 新对象，而不是 Object.assign 修改属性
                list[index] = { ...list[index], ...changes }
                break
            }
        }

        try {
            await ofdApi.updateAnnotation(fileId.value, annotationId, changes)
            return true
        } catch (e) {
            console.error('[editorStore] 更新注释失败:', e)
            return false
        }
    }

    async function deleteAnnotation(annotationId: string): Promise<boolean> {
        if (!fileId.value) return false
        try {
            await ofdApi.deleteAnnotation(fileId.value, annotationId)
            // ✅ 直接操作 reactive 对象
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
            return true
        } catch (e) {
            console.error('[editorStore] 删除注释失败:', e)
            return false
        }
    }

    async function loadAllAnnotations(): Promise<void> {
        if (!fileId.value) return
        try {
            const all = await ofdApi.getAllAnnotations(fileId.value)
            // ✅ 清空后重新赋值
            for (const key of Object.keys(annotationsMap)) {
                delete annotationsMap[Number(key)]
            }
            for (const [pageIdx, list] of Object.entries(all)) {
                annotationsMap[Number(pageIdx)] = list
            }
            console.log('[editorStore] 注释加载完成')
        } catch (e) {
            console.warn('[editorStore] 加载注释失败（可能暂无注释）:', e)
        }
    }

    function getAnnotationsByPage(pageIndex: number): AnnotationData[] {
        return annotationsMap[pageIndex] ?? []
    }

    async function exportWithAnnotations(filename?: string): Promise<void> {
        if (!fileId.value) return
        try {
            setLoading(true, '导出中...')
            const blob = await ofdApi.exportWithAnnotations(fileId.value)
            const { downloadBlob } = await import('@/api/ofdApi')
            downloadBlob(blob, filename ?? 'annotated.ofd')
        } catch (e) {
            console.error('[editorStore] 导出失败:', e)
        } finally {
            setLoading(false)
        }
    }

    return {
        // ── 原有状态 ──
        document, currentPageIndex, selectedElementId,
        scale, isLoading, loadingText, currentFile,
        history, historyIndex, fileId,
        // ── 注释状态 ──
        currentTool, annotationsMap, selectedAnnotationId,
        annotationColor, annotationOpacity, annotationLineWidth,
        // ── 原有计算属性 ──
        currentPage, selectedElement, canUndo, canRedo,
        // ── 注释计算属性 ──
        currentPageAnnotations, selectedAnnotation, isAnnotationTool,
        // ── 原有方法 ──
        setDocument, setCurrentFile, setCurrentPage,
        selectElement, setScale, setLoading,
        updateElement, resetElement,
        insertPage, deletePage, reorderPages,
        saveToHistory, undo, redo, getDocumentForSave,
        // ── 注释方法 ──
        setTool, setAnnotationColor, setAnnotationOpacity,
        setAnnotationLineWidth, selectAnnotation,
        addAnnotation, updateAnnotation, deleteAnnotation,
        loadAllAnnotations, getAnnotationsByPage,
        exportWithAnnotations,
    }
})