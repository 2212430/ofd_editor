/** 96dpi 下 mm → px，与 CanvasEditor 一致 */
export const MM_TO_PX = 96 / 25.4

export function normalizeViewRotation(deg: number): number {
    return ((deg % 360) + 360) % 360
}

export function isViewSideways(rotation: number): boolean {
    const r = normalizeViewRotation(rotation)
    return r === 90 || r === 270
}

/** 页面在 scale=1 时的像素宽高（未旋转） */
export function pagePixelSizeMm(pageWidthMm: number, pageHeightMm: number, scale = 1) {
    return {
        w: pageWidthMm * MM_TO_PX * scale,
        h: pageHeightMm * MM_TO_PX * scale,
    }
}

/** Konva 舞台外框尺寸（旋转后的可视区域） */
export function viewStagePixelSize(
    pageWidthMm: number,
    pageHeightMm: number,
    scale: number,
    rotation: number,
) {
    const { w, h } = pagePixelSizeMm(pageWidthMm, pageHeightMm, scale)
    const r = normalizeViewRotation(rotation)
    if (r === 90 || r === 270) return { width: w, height: h, stageWidth: h, stageHeight: w }
    return { width: w, height: h, stageWidth: w, stageHeight: h }
}

/** 适应宽度/页面时用的有效页宽（mm），90°/270° 时宽高互换 */
export function effectivePageSizeMm(pageWidthMm: number, pageHeightMm: number, rotation: number) {
    if (isViewSideways(rotation)) {
        return { widthMm: pageHeightMm, heightMm: pageWidthMm }
    }
    return { widthMm: pageWidthMm, heightMm: pageHeightMm }
}

export interface KonvaStageRotationConfig {
    stageWidth: number
    stageHeight: number
    rotation: number
    offsetX: number
    offsetY: number
    x: number
    y: number
}

/** Konva Stage 旋转配置（仅改变显示，不写回 OFD） */
export function konvaStageRotationConfig(
    pageWidthMm: number,
    pageHeightMm: number,
    scale: number,
    viewRotation: number,
): KonvaStageRotationConfig {
    const { w, h } = pagePixelSizeMm(pageWidthMm, pageHeightMm, scale)
    const r = normalizeViewRotation(viewRotation)

    if (r === 90) {
        return {
            stageWidth: h,
            stageHeight: w,
            rotation: 90,
            offsetX: w / 2,
            offsetY: h / 2,
            x: h / 2,
            y: w / 2,
        }
    }
    if (r === 270) {
        return {
            stageWidth: h,
            stageHeight: w,
            rotation: 270,
            offsetX: w / 2,
            offsetY: h / 2,
            x: h / 2,
            y: w / 2,
        }
    }
    if (r === 180) {
        return {
            stageWidth: w,
            stageHeight: h,
            rotation: 180,
            offsetX: w / 2,
            offsetY: h / 2,
            x: w / 2,
            y: h / 2,
        }
    }
    return {
        stageWidth: w,
        stageHeight: h,
        rotation: 0,
        offsetX: 0,
        offsetY: 0,
        x: 0,
        y: 0,
    }
}
