import axios from 'axios'
import type { DocumentData, AnnotationData, AnnotationResponse } from '@/types'

// axios实例
const http = axios.create({
    baseURL: '/api/ofd',
    timeout: 60000,
})

// 响应拦截器
http.interceptors.response.use(
    (res) => res,
    (err) => {
        const msg = err.response?.data || err.message || '请求失败'
        return Promise.reject(new Error(typeof msg === 'string' ? msg : JSON.stringify(msg)))
    }
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

    /** OFD转PDF */
    toPdf: async (file: File): Promise<Blob> => {
        const form = new FormData()
        form.append('file', file)
        const res = await http.post('/to-pdf', form, { responseType: 'blob' })
        return res.data
    },

    /** PDF转OFD */
    fromPdf: async (file: File): Promise<Blob> => {
        const form = new FormData()
        form.append('file', file)
        const res = await http.post('/from-pdf', form, { responseType: 'blob' })
        return res.data
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

/** 下载Blob文件 */
export function downloadBlob(blob: Blob, filename: string) {
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
}