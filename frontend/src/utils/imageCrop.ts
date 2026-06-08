export interface CropRect {
    /** 源图像像素坐标 */
    x: number
    y: number
    width: number
    height: number
}

export function loadImageElement(src: string): Promise<HTMLImageElement> {
    return new Promise((resolve, reject) => {
        const img = new Image()
        if (!src.startsWith('data:')) img.crossOrigin = 'anonymous'
        img.onload = () => resolve(img)
        img.onerror = () => reject(new Error('无法加载图片'))
        img.src = src
    })
}

export function getElementImageSrc(el: {
    imageBase64?: string
    imageData?: string
    imageUrl?: string
}): string {
    if (typeof el.imageBase64 === 'string' && el.imageBase64.startsWith('data:')) return el.imageBase64
    if (typeof el.imageData === 'string' && el.imageData.startsWith('data:')) return el.imageData
    if (typeof el.imageUrl === 'string' && el.imageUrl.trim()) return el.imageUrl.trim()
    return ''
}

/** 将裁剪区域导出为 PNG data URL */
export function cropImageToDataUrl(
    img: HTMLImageElement,
    rect: CropRect,
    mime = 'image/png',
): string {
    const x = clampInt(rect.x, 0, img.naturalWidth - 1)
    const y = clampInt(rect.y, 0, img.naturalHeight - 1)
    const w = clampInt(rect.width, 1, img.naturalWidth - x)
    const h = clampInt(rect.height, 1, img.naturalHeight - y)

    const canvas = document.createElement('canvas')
    canvas.width = w
    canvas.height = h
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('无法创建画布')
    ctx.drawImage(img, x, y, w, h, 0, 0, w, h)
    return canvas.toDataURL(mime)
}

/**
 * 裁剪后更新元素外框（mm）：仅保留选区对应区域，并平移到页面上原选区的位置。
 * 避免「整图被缩进小框」的视觉效果。
 */
export function computeElementBoundsAfterCrop(
    elementX: number,
    elementY: number,
    elementWidthMm: number,
    elementHeightMm: number,
    naturalWidth: number,
    naturalHeight: number,
    crop: CropRect,
): { x: number; y: number; width: number; height: number } {
    const nw = Math.max(1, naturalWidth)
    const nh = Math.max(1, naturalHeight)
    const ratioX = crop.x / nw
    const ratioY = crop.y / nh
    const ratioW = crop.width / nw
    const ratioH = crop.height / nh
    return {
        x: elementX + elementWidthMm * ratioX,
        y: elementY + elementHeightMm * ratioY,
        width: Math.max(0.1, elementWidthMm * ratioW),
        height: Math.max(0.1, elementHeightMm * ratioH),
    }
}

function clampInt(v: number, min: number, max: number): number {
    return Math.round(Math.max(min, Math.min(max, v)))
}
