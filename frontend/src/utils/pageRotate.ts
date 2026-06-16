import type { AnnotationData, ElementData } from '@/types'

export function normalizePageRotate(deg: number): number {
    return ((deg % 360) + 360) % 360
}

/** 将矩形（左上原点 mm）在页内顺时针旋转 90°，并交换页宽高 */
export function rotateBoxCw90(
    x: number, y: number, w: number, h: number,
    pageW: number, pageH: number,
) {
    return {
        x: y,
        y: pageW - x - w,
        w: h,
        h: w,
        pageW: pageH,
        pageH: pageW,
    }
}

/** 将矩形在页内逆时针旋转 90° */
export function rotateBoxCcw90(
    x: number, y: number, w: number, h: number,
    pageW: number, pageH: number,
) {
    return {
        x: pageH - y - h,
        y: x,
        w: h,
        h: w,
        pageW: pageH,
        pageH: pageW,
    }
}

export function rotateElementInPage(el: ElementData, pageW: number, pageH: number, cw: boolean) {
    const b = cw
        ? rotateBoxCw90(el.x, el.y, el.width, el.height, pageW, pageH)
        : rotateBoxCcw90(el.x, el.y, el.width, el.height, pageW, pageH)
    el.x = b.x
    el.y = b.y
    el.width = b.w
    el.height = b.h
    el.rotation = normalizePageRotate((el.rotation ?? 0) + (cw ? 90 : -90))
    el.isDirty = true
    return { pageW: b.pageW, pageH: b.pageH }
}

export function rotateAnnotationInPage(ann: AnnotationData, pageW: number, pageH: number, cw: boolean) {
    const w = ann.width ?? 0
    const h = ann.height ?? 0
    const oldX = ann.x
    const oldY = ann.y
    const b = cw
        ? rotateBoxCw90(oldX, oldY, w, h, pageW, pageH)
        : rotateBoxCcw90(oldX, oldY, w, h, pageW, pageH)
    if (ann.pathPoints?.length) {
        ann.pathPoints = ann.pathPoints.map(([px, py]) => {
            const ax = oldX + px
            const ay = oldY + py
            const p = cw
                ? rotateBoxCw90(ax, ay, 0, 0, pageW, pageH)
                : rotateBoxCcw90(ax, ay, 0, 0, pageW, pageH)
            return [p.x - b.x, p.y - b.y]
        })
    }
    ann.x = b.x
    ann.y = b.y
    ann.width = b.w
    ann.height = b.h
    return { pageW: b.pageW, pageH: b.pageH }
}

/** 解析页码范围，如 "1,3,5-8" → [1,3,5,6,7,8]（1 基） */
export function parsePageRange(input: string, maxPage: number): number[] {
    const out: number[] = []
    const seen = new Set<number>()
    for (const part of input.split(/[,，]/)) {
        const p = part.trim()
        if (!p) continue
        const dash = p.indexOf('-')
        if (dash > 0) {
            const a = parseInt(p.slice(0, dash).trim(), 10)
            const b = parseInt(p.slice(dash + 1).trim(), 10)
            if (Number.isNaN(a) || Number.isNaN(b)) throw new Error(`无效范围：${p}`)
            const lo = Math.min(a, b)
            const hi = Math.max(a, b)
            for (let n = lo; n <= hi; n++) pushPage(out, seen, n, maxPage)
        } else {
            const n = parseInt(p, 10)
            if (Number.isNaN(n)) throw new Error(`无效页码：${p}`)
            pushPage(out, seen, n, maxPage)
        }
    }
    if (out.length === 0) throw new Error('未解析到有效页码')
    return out
}

function pushPage(out: number[], seen: Set<number>, n: number, maxPage: number) {
    if (n < 1 || n > maxPage) throw new Error(`页码 ${n} 超出范围 1-${maxPage}`)
    if (!seen.has(n)) {
        seen.add(n)
        out.push(n)
    }
}
