/**
 * 从 PDF.js 文档加载书签大纲，并解析跳转目标页。
 */
import type { PDFDocumentProxy } from 'pdfjs-dist'
import type { OutlineItem } from '@/types'
import { mustDoc } from '@/utils/pdfRender'

type PdfOutlineNode = {
    title: string
    dest?: string | unknown[] | null
    url?: string | null
    items?: PdfOutlineNode[]
}

async function resolveDestPageIndex(doc: PDFDocumentProxy, dest: string | unknown[] | null | undefined): Promise<number | undefined> {
    if (dest == null) return undefined
    try {
        let explicitDest = dest
        if (typeof dest === 'string') {
            explicitDest = await doc.getDestination(dest)
        }
        if (!explicitDest || !Array.isArray(explicitDest) || explicitDest.length === 0) return undefined
        const pageRef = explicitDest[0]
        const pageIndex = await doc.getPageIndex(pageRef)
        return pageIndex >= 0 ? pageIndex : undefined
    } catch {
        return undefined
    }
}

async function convertOutlineNodes(doc: PDFDocumentProxy, nodes: PdfOutlineNode[] | undefined): Promise<OutlineItem[]> {
    if (!nodes?.length) return []
    const result: OutlineItem[] = []
    for (const node of nodes) {
        const title = (node.title ?? '').trim() || '未命名'
        const item: OutlineItem = { title }

        const url = (node.url ?? '').trim()
        if (url) {
            item.uri = url
        } else {
            const pageIndex = await resolveDestPageIndex(doc, node.dest)
            if (pageIndex != null) item.pageIndex = pageIndex
        }

        if (node.items?.length) {
            item.children = await convertOutlineNodes(doc, node.items)
        }
        result.push(item)
    }
    return result
}

/** 读取 PDF 书签大纲（token 即 fileId） */
export async function loadPdfOutline(token: string): Promise<OutlineItem[]> {
    const doc = await mustDoc(token)
    const raw = await doc.getOutline()
    if (!raw?.length) return []
    return convertOutlineNodes(doc, raw as PdfOutlineNode[])
}

export function countOutlineItems(items: OutlineItem[]): number {
    let n = 0
    for (const item of items) {
        n++
        if (item.children?.length) n += countOutlineItems(item.children)
    }
    return n
}

export function describeOutlineTarget(item: OutlineItem): string {
    if (item.uri) return item.uri
    if (item.pageIndex != null) return `第 ${item.pageIndex + 1} 页`
    return ''
}
