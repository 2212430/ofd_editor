import { ElMessage } from 'element-plus'
import { ofdApi, downloadBlob } from '@/api/ofdApi'
import { useEditorStore } from '@/stores/editorStore'

export async function buildOfdBlobFromPdf(): Promise<Blob> {
    const store = useEditorStore()
    const pdfBlob = await store.getAnnotatedPdfBlob()
    if (!pdfBlob) throw new Error('PDF 导出失败')
    const baseName = store.document?.title ?? 'export'
    const pdfFile = new File([pdfBlob], `${baseName}.pdf`, { type: 'application/pdf' })
    return ofdApi.fromPdf(pdfFile)
}

/** 保存当前文档为 OFD（与工具栏「保存 OFD」一致） */
export async function saveDocument(): Promise<void> {
    const store = useEditorStore()
    if (!store.document) return

    if (store.isPdfDocument) {
        store.setLoading(true, '正在转换为 OFD（矢量转换）...')
        try {
            const blob = await buildOfdBlobFromPdf(store)
            downloadBlob(blob, `${store.document.title ?? 'export'}.ofd`)
            store.markDocumentSaved()
            ElMessage.success('已保存为 OFD')
        } catch (err: any) {
            ElMessage.error(err.message || '保存失败')
        } finally {
            store.setLoading(false)
        }
        return
    }

    store.setLoading(true, '正在保存...')
    try {
        const blob = await ofdApi.saveOfd(store.getDocumentForSave()!)
        downloadBlob(blob, `${store.document.title}.ofd`)
        store.markNewElementsPersisted()
        store.markDocumentSaved()
        ElMessage.success('保存成功！')
    } catch (err: any) {
        ElMessage.error(err.message || '保存失败')
    } finally {
        store.setLoading(false)
    }
}

/** 打开打印对话框 */
export function openPrintDialog(): void {
    const store = useEditorStore()
    if (!store.document) return
    store.printDialogVisible = true
}
