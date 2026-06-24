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

export interface PageTextItem {
    str: string
    /** 左上角 X（mm，可视页坐标，左上原点，未叠加视图旋转） */
    xMm: number
    /** 左上角 Y（mm） */
    yMm: number
    /** 宽（mm） */
    wMm: number
    /** 高（mm，约等于字号） */
    hMm: number
    /** 可选：OFD 解析出的字号（mm） */
    fontSizeMm?: number
    glyphAdvanceMm?: number
    verticalLayout?: boolean
    passwordGrid?: boolean
    fontSizeOverridden?: boolean
}

const PT_TO_MM = 25.4 / 72
const textItemCache = new Map<string, PageTextItem[]>()

/**
 * 提取某页的文本项（用于全文搜索 + 文本选择）。
 * 坐标统一转换为「可视页 mm，左上原点」，与画布元素坐标系一致。
 */
export async function getPdfPageTextItems(token: string, pageIndex: number): Promise<PageTextItem[]> {
    const key = `${token}:${pageIndex}`
    const cached = textItemCache.get(key)
    if (cached) return cached

    const doc = await mustDoc(token)
    const page = await doc.getPage(pageIndex + 1)
    // scale=1 → 1px=1pt(72dpi)，viewport 已含 /Rotate，原点左上、y 向下
    const viewport = page.getViewport({ scale: 1 })
    const tc = await page.getTextContent()

    const out: PageTextItem[] = []
    for (const raw of tc.items) {
        const item = raw as any
        if (typeof item.str !== 'string' || item.str.length === 0) continue
        const tx = pdfjsLib.Util.transform(viewport.transform, item.transform)
        const fontHeight = Math.hypot(tx[2], tx[3]) || Math.abs(tx[3]) || 1
        const x = tx[4]
        // PDF 基线 → 左上：略减小 ascent 偏移，与浏览器渲染更接近
        const y = tx[5] - fontHeight * 0.88
        const w = typeof item.width === 'number' ? item.width : 0
        const hMm = fontHeight * PT_TO_MM
        out.push({
            str: item.str,
            xMm: x * PT_TO_MM,
            yMm: y * PT_TO_MM,
            wMm: w * PT_TO_MM,
            hMm,
            fontSizeMm: hMm,
        })
    }
    textItemCache.set(key, out)
    return out
}

/** 释放某文档，回收内存 */
export async function releasePdfDocument(token: string): Promise<void> {
    for (const k of Array.from(textItemCache.keys())) {
        if (k.startsWith(token + ':')) textItemCache.delete(k)
    }
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

export async function mustDoc(token: string): Promise<PDFDocumentProxy> {
    const entry = docs.get(token)
    if (!entry) throw new Error(`PDF 文档未加载: ${token}`)
    return entry.doc
}
