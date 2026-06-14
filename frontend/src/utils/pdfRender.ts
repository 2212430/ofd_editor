/**
 * PDF.js 渲染助手：在前端原生渲染 PDF 页面（矢量/文字保真），
 * 供画布作为背景层使用，替代「PDF→图片→OFD」的栅格化老路径。
 *
 * 设计：
 *  - 每个原生 PDF 文档用 token（即 fileId）缓存一份 PDFDocumentProxy；
 *  - 渲染按需进行，缩放变化时重新渲染以保持清晰；
 *  - 同一页的并发渲染会取消上一个任务，避免画布竞争。
 */
import * as pdfjsLib from 'pdfjs-dist'
import type { PDFDocumentProxy } from 'pdfjs-dist'
// Vite 用 ?url 把 worker 作为独立资源打包，并返回可访问的 URL
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfWorkerUrl

interface DocEntry {
    doc: Promise<PDFDocumentProxy>
    raw: PDFDocumentProxy | null
}

const docs = new Map<string, DocEntry>()
const renderTasks = new Map<string, { cancel: () => void }>()

/** 加载（或复用）一个原生 PDF 文档；token 通常用 fileId */
export function loadPdfDocument(token: string, data: ArrayBuffer): Promise<PDFDocumentProxy> {
    const existing = docs.get(token)
    if (existing) return existing.doc

    // pdf.js 会接管/转移 ArrayBuffer，这里传入副本，避免外部引用被 detach
    const copy = data.slice(0)
    const loadingTask = pdfjsLib.getDocument({ data: copy })
    const entry: DocEntry = { doc: loadingTask.promise, raw: null }
    entry.doc.then((d) => { entry.raw = d }).catch(() => { docs.delete(token) })
    docs.set(token, entry)
    return entry.doc
}

export function hasPdfDocument(token: string): boolean {
    return docs.has(token)
}

/** 取某页可视尺寸（pt，scale=1 的 viewport，已含 /Rotate） */
export async function getPdfPageViewport(token: string, pageIndex: number) {
    const doc = await mustDoc(token)
    const page = await doc.getPage(pageIndex + 1)
    return page.getViewport({ scale: 1 })
}

/**
 * 渲染某页到一个 canvas。
 * @param cssScale   画布展示缩放（与 store.scale 对齐）
 * @param oversample 过采样倍数，提升清晰度（默认按 devicePixelRatio）
 */
export async function renderPdfPage(
    token: string,
    pageIndex: number,
    cssScale: number,
    oversample?: number,
): Promise<HTMLCanvasElement | null> {
    const doc = await mustDoc(token)
    const page = await doc.getPage(pageIndex + 1)

    const dpr = oversample ?? (typeof window !== 'undefined' ? window.devicePixelRatio || 1 : 1)
    // PDF.js viewport.scale=1 → 1pt=1px(72dpi)。画布按 96dpi 展示，故乘 96/72=4/3。
    const renderScale = cssScale * (96 / 72) * dpr
    const viewport = page.getViewport({ scale: renderScale })

    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.ceil(viewport.width))
    canvas.height = Math.max(1, Math.ceil(viewport.height))
    const ctx = canvas.getContext('2d')
    if (!ctx) return null

    const taskKey = `${token}:${pageIndex}`
    const prev = renderTasks.get(taskKey)
    if (prev) { try { prev.cancel() } catch { /* ignore */ } }

    const task = page.render({ canvasContext: ctx, viewport })
    renderTasks.set(taskKey, task)
    try {
        await task.promise
        return canvas
    } catch (e: any) {
        if (e?.name === 'RenderingCancelledException') return null
        throw e
    } finally {
        if (renderTasks.get(taskKey) === task) renderTasks.delete(taskKey)
    }
}

/** 释放某文档，回收内存 */
export async function releasePdfDocument(token: string): Promise<void> {
    const entry = docs.get(token)
    docs.delete(token)
    for (const key of Array.from(renderTasks.keys())) {
        if (key.startsWith(token + ':')) {
            try { renderTasks.get(key)?.cancel() } catch { /* ignore */ }
            renderTasks.delete(key)
        }
    }
    if (entry) {
        try { (await entry.doc).destroy() } catch { /* ignore */ }
    }
}

async function mustDoc(token: string): Promise<PDFDocumentProxy> {
    const entry = docs.get(token)
    if (!entry) throw new Error(`PDF 文档未加载: ${token}`)
    return entry.doc
}
