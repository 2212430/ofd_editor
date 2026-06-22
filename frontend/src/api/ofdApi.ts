import axios from 'axios'
import type { DocumentData, AnnotationData, SignatureVerifyResult } from '@/types'
import { unpackSplitPayload, type SplitPackedFile } from '@/utils/splitPayload'
import { transferProgressConfig } from '@/utils/loadingProgress'

// axios实例（常规接口 60s；大文件转换在单次请求里单独加长 timeout）
const http = axios.create({
    baseURL: '/api/ofd',
    timeout: 60_000,
})

/** 去掉 Windows / 浏览器不允许的文件名字符 */
export function sanitizeFilename(name: string): string {
    const trimmed = (name || 'export').trim() || 'export'
    return trimmed.replace(/[\\/:*?"<>|]/g, '_')
}

/** 保证文件名以 .ofd 结尾 */
export function buildMergedPdfFilename(firstName: string, secondName: string): string {
    const strip = (n: string) => sanitizeFilename(n).replace(/\.pdf$/i, '') || '文档'
    return `${strip(firstName)}_合并_${strip(secondName)}.pdf`
}

export function ensureOfdFilename(name: string): string {
    const base = sanitizeFilename(name).replace(/\.ofd$/i, '')
    return `${base || 'export'}.ofd`
}

const OFD_SAVE_PICKER_TYPES = [
    {
        description: 'OFD 开放版式文档',
        accept: { 'application/ofd': ['.ofd'], 'application/octet-stream': ['.ofd'] },
    },
]

type SaveTarget =
    | { mode: 'handle'; handle: FileSystemFileHandle; filename: string }
    | { mode: 'download'; filename: string }

/**
 * 在用户点击时先选择保存位置/文件名（保留用户手势），供后续异步生成 Blob 再写入。
 */
export async function pickOfdSaveTarget(suggestedName: string): Promise<SaveTarget | null> {
    const suggested = ensureOfdFilename(suggestedName)

    if (typeof window.showSaveFilePicker === 'function') {
        try {
            const handle = await window.showSaveFilePicker({
                suggestedName: suggested,
                types: OFD_SAVE_PICKER_TYPES,
            })
            return { mode: 'handle', handle, filename: handle.name || suggested }
        } catch (e) {
            if ((e as DOMException)?.name === 'AbortError') return null
        }
    }

    return { mode: 'download', filename: suggested }
}

/** 下载 Blob 文件（同步触发，需在用户点击事件回调里调用） */
export function downloadBlob(blob: Blob, filename: string) {
    const safeName = sanitizeFilename(filename)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = safeName
    a.rel = 'noopener'
    a.style.display = 'none'
    document.body.appendChild(a)
    a.click()
    // 大文件（10MB+）若立刻 revoke，Chrome/Edge 可能还没写完就中断下载
    setTimeout(() => {
        document.body.removeChild(a)
        URL.revokeObjectURL(url)
    }, 60_000)
}

/** 将 Blob 写入 pickOfdSaveTarget 选定的目标 */
export async function writeBlobToSaveTarget(blob: Blob, target: SaveTarget): Promise<void> {
    if (target.mode === 'handle') {
        const writable = await target.handle.createWritable()
        await writable.write(blob)
        await writable.close()
        return
    }
    downloadBlob(blob, target.filename)
}

/** 后端用 blob 返回错误正文时，把可读信息抛出来 */
async function ensureBlobOk(data: Blob, contentType?: string): Promise<Blob> {
    const ct = (contentType ?? data.type ?? '').toLowerCase()
    const isBinaryOk =
        ct.includes('pdf')
        || ct.includes('ofd')
        || ct.includes('octet-stream')
        || ct.includes('wordprocessingml')
        || ct.includes('msword')
        || ct.includes('presentationml')
        || ct.includes('text/plain')
        || ct.includes('text/html')
        || ct.includes('openxmlformats-officedocument')
    if (isBinaryOk) {
        return data
    }
    // 空体
    if (data.size === 0) {
        throw new Error('响应为空')
    }
    // 大体积响应体视为文件（避免误把 docx 当 JSON 文本解析）
    if (data.size > 65536) {
        return data
    }
    const looksLikeError =
        ct.includes('json')
        || ct.startsWith('text/')
        || ct.includes('html')
    if (looksLikeError) {
        const text = await data.text()
        throw new Error(text || '请求失败')
    }
    return data
}

/** 从 axios 错误响应中提取可读消息（含 blob 响应体） */
async function extractErrorMessage(err: unknown): Promise<string> {
    const ax = err as { response?: { data?: unknown }; message?: string }
    const data = ax.response?.data
    if (data instanceof Blob) {
        try {
            const text = (await data.text()).trim()
            if (text) return text
        } catch { /* ignore */ }
    }
    if (typeof data === 'string' && data.trim()) return data.trim()
    if (data && typeof data === 'object' && 'message' in data) {
        const m = (data as { message?: string }).message
        if (m) return m
    }
    return ax.message || '请求失败'
}

// 响应拦截器
http.interceptors.response.use(
    (res) => res,
    async (err) => Promise.reject(new Error(await extractErrorMessage(err))),
)

export const ofdApi = {

    // ==================== 原有接口（保持不变） ====================

    /** 健康检查 */
    health: () => http.get<string>('/health'),

    /** 解析OFD文件 */
    parseOfd: async (file: File): Promise<DocumentData> => {
        const form = new FormData()
        form.append('file', file)
        const res = await http.post<DocumentData>('/parse', form, {
            timeout: 300_000,
            ...transferProgressConfig('正在打开 OFD', 'json', file.size),
        })
        return res.data
    },

    /**
     * 原生解析 PDF（不栅格化）：仅返回页面尺寸 + fileId，页面由前端 PDF.js 渲染。
     * POST /api/ofd/parse-pdf
     */
    parsePdfNative: async (file: File): Promise<DocumentData> => {
        const form = new FormData()
        form.append('file', file)
        const res = await http.post<DocumentData>('/parse-pdf', form, {
            timeout: 300_000,
            ...transferProgressConfig('正在打开 PDF', 'json', file.size),
        })
        return res.data
    },

    /**
     * 合并两个 OFD（第一个文件的页面在前，第二个在后）
     * POST /api/ofd/merge
     */
    /**
     * 合并两个 PDF（第一个文件页面在前），返回合并后的 PDF Blob
     * POST /api/ofd/merge-pdf
     */
    mergePdf: async (first: File, second: File): Promise<Blob> => {
        const form = new FormData()
        form.append('first', first)
        form.append('second', second)
        const res = await http.post('/merge-pdf', form, {
            responseType: 'blob',
            timeout: 600_000,
        })
        return ensureBlobOk(res.data, res.headers['content-type'])
    },

    mergeOfd: async (first: File, second: File): Promise<DocumentData> => {
        const form = new FormData()
        form.append('first', first)
        form.append('second', second)
        const res = await http.post<DocumentData>('/merge', form, {
            timeout: 300_000,
        })
        return res.data
    },

    /** 拆分当前缓存 OFD，返回两份文件（XXX第一部分.ofd / XXX第二部分.ofd） */
    splitOfd: async (payload: {
        fileId: string
        splitAfterPage: number
        title?: string
    }): Promise<SplitPackedFile[]> => {
        const res = await http.post('/split-ofd', payload, {
            responseType: 'arraybuffer',
            timeout: 300_000,
            headers: { 'Content-Type': 'application/json' },
        })
        return unpackSplitPayload(res.data)
    },

    /** 读取 PDF 页数 */
    pdfPageCount: async (file: File): Promise<number> => {
        const form = new FormData()
        form.append('file', file)
        const res = await http.post<{ pageCount: number }>('/pdf-page-count', form, {
            timeout: 120_000,
        })
        return res.data.pageCount
    },

    /** 拆分原生 PDF，返回两份文件 */
    splitPdf: async (file: File, splitAfterPage: number): Promise<SplitPackedFile[]> => {
        const form = new FormData()
        form.append('file', file)
        form.append('splitAfterPage', String(splitAfterPage))
        const res = await http.post('/split-pdf', form, {
            responseType: 'arraybuffer',
            timeout: 600_000,
        })
        return unpackSplitPayload(res.data)
    },

    /** OFD转PDF（42 页级文档转换可能超过 60s，单独放宽超时） */
    toPdf: async (file: File): Promise<Blob> => {
        const form = new FormData()
        form.append('file', file)
        const res = await http.post('/to-pdf', form, {
            responseType: 'blob',
            timeout: 600_000,
            ...transferProgressConfig('正在转换为 PDF'),
        })
        return ensureBlobOk(res.data, res.headers['content-type'])
    },

    /** OFD转Word（OFD→PDF→docx，大文档可能较慢） */
    ofdToWord: async (file: File): Promise<Blob> => {
        const form = new FormData()
        form.append('file', file)
        const res = await http.post('/ofd-to-word', form, {
            responseType: 'blob',
            timeout: 900_000,
            ...transferProgressConfig('正在转换为 Word'),
        })
        return ensureBlobOk(res.data, res.headers['content-type'])
    },

    /** OFD转PPT（OFD→PDF→pptx，大文档可能较慢） */
    ofdToPpt: async (file: File): Promise<Blob> => {
        const form = new FormData()
        form.append('file', file)
        const res = await http.post('/ofd-to-ppt', form, {
            responseType: 'blob',
            timeout: 900_000,
            ...transferProgressConfig('正在转换为 PPT'),
        })
        return ensureBlobOk(res.data, res.headers['content-type'])
    },

    /** OFD转纯文本（ofdrw TextExporter） */
    ofdToText: async (file: File): Promise<Blob> => {
        const form = new FormData()
        form.append('file', file)
        const res = await http.post('/ofd-to-text', form, {
            responseType: 'blob',
            timeout: 600_000,
            ...transferProgressConfig('正在提取文本'),
        })
        return ensureBlobOk(res.data, res.headers['content-type'])
    },

    /** OFD转HTML（ofdrw HTMLExporter） */
    ofdToHtml: async (file: File): Promise<Blob> => {
        const form = new FormData()
        form.append('file', file)
        const res = await http.post('/ofd-to-html', form, {
            responseType: 'blob',
            timeout: 600_000,
            ...transferProgressConfig('正在导出 HTML'),
        })
        return ensureBlobOk(res.data, res.headers['content-type'])
    },

    /** PDF转Word（pdf2docx，大文档可能较慢） */
    pdfToWord: async (file: File): Promise<Blob> => {
        const form = new FormData()
        form.append('file', file)
        const res = await http.post('/pdf-to-word', form, {
            responseType: 'blob',
            timeout: 600_000,
            ...transferProgressConfig('正在转换为 Word'),
        })
        return ensureBlobOk(res.data, res.headers['content-type'])
    },

    /** PDF转PPT（每页渲染为幻灯片图片，大文档可能较慢） */
    pdfToPpt: async (file: File): Promise<Blob> => {
        const form = new FormData()
        form.append('file', file)
        const res = await http.post('/pdf-to-ppt', form, {
            responseType: 'blob',
            timeout: 900_000,
            ...transferProgressConfig('正在转换为 PPT'),
        })
        return ensureBlobOk(res.data, res.headers['content-type'])
    },

    /** PDF转OFD（ofdrw PDFConverter 矢量转换，大文档可能较慢） */
    fromPdf: async (file: File): Promise<Blob> => {
        const form = new FormData()
        form.append('file', file)
        const res = await http.post('/from-pdf', form, {
            responseType: 'blob',
            timeout: 600_000,
            ...transferProgressConfig('正在转换为 OFD'),
        })
        return ensureBlobOk(res.data, res.headers['content-type'])
    },

    /** 保存编辑后的OFD（含注释） */
    saveOfd: async (doc: DocumentData): Promise<Blob> => {
        const res = await http.post('/save', doc, {
            responseType: 'blob',
            headers: { 'Content-Type': 'application/json' },
            timeout: 600_000,
            ...transferProgressConfig('正在保存 OFD'),
        })
        return res.data
    },

    // ==================== 电子签章 / 验签 ====================

    /** 验证 OFD 的电子签章 / 数字签名 */
    verifySignature: async (file: Blob): Promise<SignatureVerifyResult> => {
        const form = new FormData()
        form.append('file', file, 'doc.ofd')
        const res = await http.post<SignatureVerifyResult>('/verify-signature', form, {
            timeout: 120_000,
        })
        return res.data
    },

    /** 给 OFD 加盖国密电子签章，返回签章后的 OFD blob */
    signGm: async (params: {
        file: Blob
        sealImage?: File | null
        name?: string
        page?: number
        x?: number
        y?: number
        width?: number
        height?: number
    }): Promise<Blob> => {
        const form = new FormData()
        form.append('file', params.file, 'doc.ofd')
        if (params.sealImage) form.append('sealImage', params.sealImage)
        if (params.name) form.append('name', params.name)
        if (params.page != null) form.append('page', String(params.page))
        if (params.x != null) form.append('x', String(params.x))
        if (params.y != null) form.append('y', String(params.y))
        if (params.width != null) form.append('width', String(params.width))
        if (params.height != null) form.append('height', String(params.height))
        const res = await http.post('/sign-gm', form, {
            responseType: 'blob',
            timeout: 180_000,
        })
        return ensureBlobOk(res.data, res.headers['content-type'])
    },

    // ==================== 注释相关接口 ====================

    /**
     * 获取某文件某页的所有注释
     * GET /api/ofd/{fileId}/annotations?pageIndex=0
     */
    getAnnotations: async (
        fileId: string,
        pageIndex: number
    ): Promise<AnnotationData[]> => {
        const res = await http.get<AnnotationData[]>(
            `/${fileId}/annotations`,
            { params: { pageIndex } }
        )
        return res.data
    },

    /**
     * 获取某文件所有页的注释
     * GET /api/ofd/{fileId}/annotations/all
     */
    getAllAnnotations: async (
        fileId: string
    ): Promise<Record<number, AnnotationData[]>> => {
        const res = await http.get<Record<number, AnnotationData[]>>(
            `/${fileId}/annotations/all`
        )
        const data = res.data as any
        for (const list of Object.values(data)) {
            for (const ann of list as any[]) {
                if (typeof ann.pathPoints === 'string' && ann.pathPoints) {
                    try { ann.pathPoints = JSON.parse(ann.pathPoints) } catch {}
                }
            }
        }
        return data
    },

    /**
     * 新增一条注释
     * POST /api/ofd/{fileId}/annotations
     */
    addAnnotation: async (
        fileId: string,
        annotation: Omit<AnnotationData, 'id' | 'createdAt' | 'updatedAt'>
    ): Promise<AnnotationData> => {
        // 深拷贝，避免修改原始数据
        const payload: any = { ...annotation }
        if (Array.isArray(payload.pathPoints)) {
            payload.pathPoints = JSON.stringify(payload.pathPoints)
        }

        const res = await http.post<AnnotationData>(
            `/${fileId}/annotations`,
            payload,
            { headers: { 'Content-Type': 'application/json' } }
        )
        const data = res.data as any
        if (typeof data.pathPoints === 'string' && data.pathPoints) {
            try { data.pathPoints = JSON.parse(data.pathPoints) } catch {}
        }
        return data
    },

    /**
     * 更新一条注释
     * PUT /api/ofd/{fileId}/annotations/{annotationId}
     */
    updateAnnotation: async (
        fileId: string,
        annotationId: string,
        annotation: Partial<AnnotationData>
    ): Promise<AnnotationData> => {
        const payload: any = { ...annotation }
        if (Array.isArray(payload.pathPoints)) {
            payload.pathPoints = JSON.stringify(payload.pathPoints)
        }
        const res = await http.put<AnnotationData>(
            `/${fileId}/annotations/${annotationId}`,
            payload,
            { headers: { 'Content-Type': 'application/json' } }
        )

        const data = res.data as any
        if (typeof data.pathPoints === 'string' && data.pathPoints) {
            try { data.pathPoints = JSON.parse(data.pathPoints) } catch {}
        }
        return data
    },

    /**
     * 删除一条注释
     * DELETE /api/ofd/{fileId}/annotations/{annotationId}
     */
    deleteAnnotation: async (
        fileId: string,
        annotationId: string
    ): Promise<void> => {
        await http.delete(`/${fileId}/annotations/${annotationId}`)
    },

    /**
     * 删除某页所有注释
     * DELETE /api/ofd/{fileId}/annotations?pageIndex=0
     */
    deleteAllAnnotations: async (
        fileId: string,
        pageIndex: number
    ): Promise<void> => {
        await http.delete(`/${fileId}/annotations`, {
            params: { pageIndex }
        })
    },

    /**
     * 导出含注释的OFD文件
     * GET /api/ofd/{fileId}/export
     */
    exportWithAnnotations: async (fileId: string): Promise<Blob> => {
        const res = await http.get(`/${fileId}/export`, {
            responseType: 'blob',
            timeout: 600_000,
            ...transferProgressConfig('正在导出 OFD'),
        })
        return res.data
    },

    /**
     * 导出含注释的 PDF（原生 PDF 文档，注释非破坏烘焙回原 PDF）
     * POST /api/ofd/{fileId}/export-pdf
     * payload 携带当前页面布局（支持重排/删除/插入/复制页）与注释；省略则按原序导出。
     */
    exportPdfWithAnnotations: async (
        fileId: string,
        payload?: {
            pages: { sourceIndex: number | null; widthMm: number; heightMm: number; rotate?: number }[]
            annotations: Record<number, any[]>
            watermark?: WatermarkOptions
        },
    ): Promise<Blob> => {
        const res = await http.post(`/${fileId}/export-pdf`, payload ?? {}, {
            responseType: 'blob',
            timeout: 300_000,
            headers: { 'Content-Type': 'application/json' },
            ...transferProgressConfig('正在导出 PDF'),
        })
        return res.data
    },

    // ==================== 水印 / 页面提取 ====================

    /** 给 OFD 全部页面加文本水印，返回水印后的 OFD blob */
    watermarkOfd: async (file: Blob, wm: WatermarkOptions): Promise<Blob> => {
        const form = new FormData()
        form.append('file', file, 'doc.ofd')
        form.append('text', wm.text)
        if (wm.fontSize != null) form.append('fontSize', String(wm.fontSize))
        if (wm.color) form.append('color', wm.color)
        if (wm.opacity != null) form.append('opacity', String(wm.opacity))
        if (wm.angle != null) form.append('angle', String(wm.angle))
        if (wm.bold != null) form.append('bold', String(wm.bold))
        const res = await http.post('/watermark-ofd', form, {
            responseType: 'blob',
            timeout: 300_000,
        })
        return ensureBlobOk(res.data, res.headers['content-type'])
    },

    /** 从 OFD 提取指定页（1 基，顺序即输出顺序），返回新 OFD blob */
    extractOfd: async (file: Blob, pages: number[]): Promise<Blob> => {
        const form = new FormData()
        form.append('file', file, 'doc.ofd')
        form.append('pages', pages.join(','))
        const res = await http.post('/extract-ofd', form, {
            responseType: 'blob',
            timeout: 300_000,
        })
        return ensureBlobOk(res.data, res.headers['content-type'])
    },

    /** 从 PDF 提取指定页（1 基，顺序即输出顺序），返回新 PDF blob */
    extractPdf: async (file: Blob, pages: number[]): Promise<Blob> => {
        const form = new FormData()
        form.append('file', file, 'doc.pdf')
        form.append('pages', pages.join(','))
        const res = await http.post('/extract-pdf', form, {
            responseType: 'blob',
            timeout: 300_000,
        })
        return ensureBlobOk(res.data, res.headers['content-type'])
    },
}

/** 文本水印参数（前端） */
export interface WatermarkOptions {
    text: string
    fontSize?: number
    color?: string
    opacity?: number
    angle?: number
    tile?: boolean
    bold?: boolean
}

/** 直接触发浏览器下载（不再弹出确认框） */
export async function promptDownloadBlob(blob: Blob, filename: string): Promise<boolean> {
    downloadBlob(blob, filename)
    return true
}