import axios from 'axios'
import { ElMessageBox } from 'element-plus'
import type { DocumentData, AnnotationData } from '@/types'

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

    try {
        const { value } = await ElMessageBox.prompt(
            '请输入文件名。不支持选择文件夹时将保存到浏览器默认下载目录。',
            '另存为',
            {
                inputValue: suggested,
                confirmButtonText: '保存',
                cancelButtonText: '取消',
                inputPattern: /^[^\\/:*?"<>|]+$/,
                inputErrorMessage: '文件名不能包含 \\ / : * ? " < > |',
            },
        )
        const name = (value ?? '').trim()
        if (!name) return null
        return { mode: 'download', filename: ensureOfdFilename(name) }
    } catch {
        return null
    }
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

function formatFileSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/** 后端用 blob 返回错误正文时，把可读信息抛出来 */
async function ensureBlobOk(data: Blob, contentType?: string): Promise<Blob> {
    const ct = (contentType ?? data.type ?? '').toLowerCase()
    if (ct.includes('pdf') || ct.includes('ofd') || ct.includes('octet-stream')) {
        return data
    }
    const text = await data.text()
    throw new Error(text || '请求失败')
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
        const res = await http.post<DocumentData>('/parse', form)
        return res.data
    },

    /** OFD转PDF（42 页级文档转换可能超过 60s，单独放宽超时） */
    toPdf: async (file: File): Promise<Blob> => {
        const form = new FormData()
        form.append('file', file)
        const res = await http.post('/to-pdf', form, {
            responseType: 'blob',
            timeout: 600_000,
        })
        return ensureBlobOk(res.data, res.headers['content-type'])
    },

    /** PDF转OFD */
    fromPdf: async (file: File): Promise<Blob> => {
        const form = new FormData()
        form.append('file', file)
        const res = await http.post('/from-pdf', form, {
            responseType: 'blob',
            timeout: 600_000,
        })
        return ensureBlobOk(res.data, res.headers['content-type'])
    },

    /** 保存编辑后的OFD（含注释） */
    saveOfd: async (doc: DocumentData): Promise<Blob> => {
        const res = await http.post('/save', doc, {
            responseType: 'blob',
            headers: { 'Content-Type': 'application/json' },
        })
        return res.data
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
            responseType: 'blob'
        })
        return res.data
    },
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

/**
 * 异步任务完成后弹出「下载」按钮，由用户再点一次保存。
 * 长时间 await 之后直接 a.click() 会被浏览器当成非用户手势而静默拦截。
 */
export async function promptDownloadBlob(blob: Blob, filename: string): Promise<boolean> {
    const safeName = sanitizeFilename(filename)
    try {
        await ElMessageBox.confirm(
            `文件已生成（${formatFileSize(blob.size)}）。\n` +
            `文件名：${safeName}\n\n` +
            `由于转换耗时较长，请点击「下载」保存到浏览器默认下载目录（通常是「下载」文件夹）。`,
            '导出完成',
            {
                confirmButtonText: '下载',
                cancelButtonText: '稍后',
                type: 'success',
                closeOnClickModal: false,
            },
        )
        downloadBlob(blob, safeName)
        return true
    } catch {
        return false
    }
}