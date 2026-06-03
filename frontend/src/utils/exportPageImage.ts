import { sanitizeFilename } from '@/api/ofdApi'

/** 导出 PNG 时的分辨率倍率（相对 96dpi 画布） */
export const EXPORT_PAGE_PIXEL_RATIO = 2

export function buildCurrentPagePngFilename(docTitle: string, pageIndex: number): string {
    const base = sanitizeFilename(docTitle)
    return `${base}_第${pageIndex + 1}页.png`
}

export async function dataUrlToBlob(dataUrl: string): Promise<Blob> {
    const res = await fetch(dataUrl)
    return res.blob()
}
