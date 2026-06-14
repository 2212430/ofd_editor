/**
 * 打开原生 PDF：走「PDF.js 前端渲染 + 注释层」路径，
 * 不再 PDF→图片→OFD 栅格化，保留矢量/文字/字体格式。
 *
 * 调用方负责 setLoading / 错误提示。
 */
import { useEditorStore } from '@/stores/editorStore'
import { ofdApi } from '@/api/ofdApi'
import { loadPdfDocument, releasePdfDocument } from '@/utils/pdfRender'

export async function openNativePdf(file: File): Promise<void> {
    const store = useEditorStore()

    // 释放上一个原生 PDF 文档，回收内存
    if (store.isPdfDocument && store.fileId) {
        void releasePdfDocument(store.fileId)
    }

    const buf = await file.arrayBuffer()
    const doc = await ofdApi.parsePdfNative(file)
    if (!doc.fileId) throw new Error('后端未返回 fileId')

    // 预加载到 PDF.js（供画布按页渲染）
    await loadPdfDocument(doc.fileId, buf)

    store.setDocument(doc, 'pdf')
    store.setCurrentFile(file, 'pdf')
    await store.loadAllAnnotations()
}
