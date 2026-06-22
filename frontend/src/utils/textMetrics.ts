/**
 * 文本布局估算（与 CanvasEditor.getTextConfig 对齐），供全文搜索高亮定位。
 */

export interface TextLayoutMeta {
    str: string
    xMm: number
    yMm: number
    wMm: number
    hMm: number
    fontSizeMm?: number
    glyphAdvanceMm?: number
    verticalLayout?: boolean
    passwordGrid?: boolean
    fontSizeOverridden?: boolean
}

export interface SearchRect {
    x: number
    y: number
    w: number
    h: number
}

export interface CharBoxMm {
    char: string
    xMm: number
    yMm: number
    wMm: number
    hMm: number
}

function normalizeCurrencyContent(raw: string): string {
    return raw.replace(/\s*\n\s*/g, '').trim()
}

function getCurrencySplitParts(content: string): { prefix: string; symbol: string; tail: string } | null {
    const t = normalizeCurrencyContent(content)
    const m = t.match(/^(.*?)([¥￥])([\d.,]+)$/)
    if (m) return { prefix: m[1], symbol: m[2], tail: m[3] }
    return null
}

function isCurrencySplitText(meta: TextLayoutMeta): boolean {
    if (meta.verticalLayout || meta.passwordGrid) return false
    const t = normalizeCurrencyContent(meta.str)
    if (/^[¥￥][\d.,]+$/.test(t)) return true
    return /[¥￥][\d.,]+$/.test(t) && t.length <= 24
}

/** 按字符大致猜测系统字体下的水平 advance（单位：em） */
export function approxAdvanceEm(ch: string): number {
    if (!ch || ch === '\n') return 0
    const code = ch.charCodeAt(0)
    if (
        (code >= 0x2e80 && code <= 0x9fff) ||
        (code >= 0xa000 && code <= 0xa4cf) ||
        (code >= 0xac00 && code <= 0xd7af) ||
        (code >= 0xf900 && code <= 0xfaff) ||
        (code >= 0xff00 && code <= 0xffef) ||
        (code >= 0x3000 && code <= 0x303f)
    ) return 1.0
    if (ch === ' ') return 0.32
    if (ch === '¥' || ch === '￥' || ch === '\u00a5' || ch === '\uffe5') return 0.72
    if (/[il.,;:'!|`]/.test(ch)) return 0.30
    if (/[A-Z0-9#&%@$]/.test(ch)) return 0.62
    return 0.55
}

export function estimateNaturalWidthMm(text: string, fsMm: number): number {
    let sum = 0
    for (const ch of text) sum += approxAdvanceEm(ch)
    return sum * fsMm
}

/** 与 CanvasEditor.resolveTextFontPx 对应的字号（mm） */
export function resolveRenderedFontSizeMm(meta: TextLayoutMeta): number {
    const content = meta.str
    const hasNl = content.includes('\n')
    const fsMm = meta.fontSizeMm ?? 3
    const wMm = meta.wMm
    const hMm = meta.hMm
    const isVertical = meta.verticalLayout === true || (hasNl && wMm > 0 && hMm > 0 && hMm > wMm * 1.5)

    if (meta.fontSizeOverridden) return fsMm
    if (isVertical && wMm > 0) return Math.min(fsMm, wMm * 0.92)
    return fsMm
}

function resolveLetterSpacingMm(meta: TextLayoutMeta, fsMm: number): number {
    const content = meta.str
    const trimmed = content.trim()
    const hasNl = content.includes('\n')
    const wMm = meta.wMm
    const isVertical = meta.verticalLayout === true
    const isPasswordGrid = meta.passwordGrid === true
    const isMultiLineHorizontal = !isVertical && hasNl && wMm > 0 && !isPasswordGrid

    const isCurrencyAmount = /^[¥￥][\d.,]+$/.test(trimmed)
        || (/[¥￥][\d.,]+$/.test(trimmed) && trimmed.length <= 24)
    const isNumericOrAmount = /^[\d¥￥.,/%\-+\s()（）：:]*$/.test(trimmed)
    const isStretchLabel = !isVertical && !isPasswordGrid && !hasNl && !isMultiLineHorizontal && wMm > 0
        && content.length > 1 && content.length <= 8 && !isNumericOrAmount && !isCurrencyAmount
        && !content.includes('：') && !content.includes(':')

    if (isStretchLabel) {
        const naturalMm = estimateNaturalWidthMm(content, fsMm)
        const gapMm = wMm - naturalMm
        if (gapMm > 0.05 && gapMm < wMm * 0.35) {
            return Math.min(gapMm / (content.length - 1), fsMm * 0.6)
        }
    } else if (isCurrencyAmount && !isVertical && !isPasswordGrid && !hasNl) {
        if (typeof meta.glyphAdvanceMm === 'number' && meta.glyphAdvanceMm > 0) {
            const lsMm = meta.glyphAdvanceMm - fsMm * 0.32
            if (lsMm > 0.02) return lsMm
        } else {
            return fsMm * 0.15
        }
    }
    return 0
}

function currencyTailOffsetMm(prefix: string, fsMm: number, glyphAdvanceMm?: number): number {
    const prefixW = prefix ? estimateNaturalWidthMm(prefix, fsMm) : 0
    const ofdGap = typeof glyphAdvanceMm === 'number' && glyphAdvanceMm > 0
        ? glyphAdvanceMm : fsMm * 0.55
    const yuanWeb = fsMm * 0.82
    return prefixW + Math.max(ofdGap, yuanWeb) + fsMm * 0.05
}

function resolveLineHeight(meta: TextLayoutMeta, fsMm: number): number {
    const content = meta.str
    const hasNl = content.includes('\n')
    const isVertical = meta.verticalLayout === true
    const isPasswordGrid = meta.passwordGrid === true
    const lineCount = hasNl ? content.split('\n').length : 1
    if (isPasswordGrid && lineCount > 1 && meta.hMm > 0 && fsMm > 0) {
        return Math.min(1.12, Math.max(0.92, (meta.hMm * 0.96) / (lineCount * fsMm)))
    }
    if (hasNl) return isVertical ? 1.05 : 1.12
    return 1.15
}

function highlightHeightMm(meta: TextLayoutMeta, fsMm: number): number {
    return Math.max(fsMm * 0.88, Math.min(fsMm * 1.05, fsMm * resolveLineHeight(meta, fsMm) * 0.92))
}

function layoutHorizontalLine(
    line: string,
    xStart: number,
    yMm: number,
    fsMm: number,
    lsMm: number,
    hMm: number,
): CharBoxMm[] {
    const boxes: CharBoxMm[] = []
    let x = xStart
    for (let i = 0; i < line.length; i++) {
        const ch = line[i]
        const w = approxAdvanceEm(ch) * fsMm
        boxes.push({ char: ch, xMm: x, yMm, wMm: w, hMm })
        if (i < line.length - 1) x += w + lsMm
        else x += w
    }
    return boxes
}

/** 按字符生成 mm 坐标框，与 Konva 文本渲染尽量一致 */
export function layoutCharacterBoxes(meta: TextLayoutMeta): CharBoxMm[] {
    const content = meta.str
    if (!content) return []

    const hasNl = content.includes('\n')
    const isVertical = meta.verticalLayout === true
        || (hasNl && meta.wMm > 0 && meta.hMm > 0 && meta.hMm > meta.wMm * 1.5)
    const isPasswordGrid = meta.passwordGrid === true
    const fsMm = resolveRenderedFontSizeMm(meta)
    const hHighlight = highlightHeightMm(meta, fsMm)
    const lineHeightFactor = resolveLineHeight(meta, fsMm)
    const lineStepMm = fsMm * lineHeightFactor

    if (isCurrencySplitText(meta)) {
        const parts = getCurrencySplitParts(content)
        if (parts) {
            const head = parts.prefix + parts.symbol
            const tailX = meta.xMm + currencyTailOffsetMm(parts.prefix, fsMm, meta.glyphAdvanceMm)
            return [
                ...layoutHorizontalLine(head, meta.xMm, meta.yMm, fsMm, 0, hHighlight),
                ...layoutHorizontalLine(parts.tail, tailX, meta.yMm, fsMm, 0, hHighlight),
            ]
        }
    }

    if (isVertical || isPasswordGrid) {
        const lines = content.split('\n')
        const blockH = lines.length * lineStepMm
        const yStart = (isVertical || isPasswordGrid) && meta.hMm > blockH
            ? meta.yMm + (meta.hMm - blockH) / 2
            : meta.yMm
        const boxes: CharBoxMm[] = []
        lines.forEach((line, li) => {
            const y = yStart + li * lineStepMm
            let x = meta.xMm
            if (isVertical && meta.wMm > 0) {
                const lineW = estimateNaturalWidthMm(line, fsMm)
                x = meta.xMm + Math.max(0, (meta.wMm - lineW) / 2)
            }
            for (const ch of line) {
                const w = approxAdvanceEm(ch) * fsMm
                boxes.push({ char: ch, xMm: x, yMm: y, wMm: w, hMm: hHighlight })
                x += w
            }
        })
        return boxes
    }

    const lsMm = resolveLetterSpacingMm(meta, fsMm)
    const lines = hasNl ? content.split('\n') : [content]
    const blockH = lines.length * lineStepMm
    const yStart = hasNl && meta.hMm > blockH ? meta.yMm + (meta.hMm - blockH) / 2 : meta.yMm

    const boxes: CharBoxMm[] = []
    lines.forEach((line, li) => {
        const y = yStart + li * lineStepMm
        boxes.push(...layoutHorizontalLine(line, meta.xMm, y, fsMm, lsMm, hHighlight))
    })
    return boxes
}

/** PDF 文本项：按 item.width 缩放字宽 */
export function layoutPdfCharacterBoxes(meta: TextLayoutMeta): CharBoxMm[] {
    const fsMm = meta.fontSizeMm ?? meta.hMm
    const content = meta.str.replace(/\n/g, '')
    if (!content) return []

    const naturalW = estimateNaturalWidthMm(content, fsMm)
    const scale = meta.wMm > 0 && naturalW > 0 ? meta.wMm / naturalW : 1
    const hHighlight = Math.max(fsMm * 0.85, meta.hMm * 0.9)
    const yMm = meta.yMm + Math.max(0, (meta.hMm - hHighlight) * 0.08)

    let x = meta.xMm
    const boxes: CharBoxMm[] = []
    for (const ch of content) {
        const w = approxAdvanceEm(ch) * fsMm * scale
        boxes.push({ char: ch, xMm: x, yMm, wMm: w, hMm: hHighlight })
        x += w
    }
    return boxes
}

function buildStringIndexToBoxMap(str: string, boxes: CharBoxMm[]): number[] {
    const map: number[] = []
    let bi = 0
    for (let si = 0; si < str.length; si++) {
        if (str[si] === '\n') {
            map[si] = -1
            continue
        }
        if (bi < boxes.length) {
            map[si] = bi
            bi++
        } else {
            map[si] = -1
        }
    }
    return map
}

function unionBoxes(boxes: CharBoxMm[]): SearchRect | null {
    if (boxes.length === 0) return null
    let minX = Infinity
    let minY = Infinity
    let maxX = -Infinity
    let maxY = -Infinity
    for (const b of boxes) {
        minX = Math.min(minX, b.xMm)
        minY = Math.min(minY, b.yMm)
        maxX = Math.max(maxX, b.xMm + b.wMm)
        maxY = Math.max(maxY, b.yMm + b.hMm)
    }
    return { x: minX, y: minY, w: maxX - minX, h: maxY - minY }
}

/** 在单个文本项内查找 query 的全部命中矩形 */
export function matchRectsInTextItem(
    meta: TextLayoutMeta,
    query: string,
    lowerQuery: string,
    mode: 'ofd' | 'pdf' = 'ofd',
): SearchRect[] {
    const rects: SearchRect[] = []
    const lower = meta.str.toLowerCase()
    if (!meta.str || !query) return rects

    const charBoxes = mode === 'pdf' ? layoutPdfCharacterBoxes(meta) : layoutCharacterBoxes(meta)
    if (charBoxes.length === 0) {
        const len = meta.str.length
        let from = 0
        while (true) {
            const idx = lower.indexOf(lowerQuery, from)
            if (idx < 0) break
            const x = meta.xMm + (idx / len) * meta.wMm
            const w = (query.length / len) * meta.wMm
            const fsMm = resolveRenderedFontSizeMm(meta)
            rects.push({ x, y: meta.yMm, w, h: highlightHeightMm(meta, fsMm) })
            from = idx + Math.max(1, query.length)
        }
        return rects
    }

    const indexToBox = buildStringIndexToBoxMap(meta.str, charBoxes)
    let from = 0
    while (true) {
        const idx = lower.indexOf(lowerQuery, from)
        if (idx < 0) break
        const hitBoxes: CharBoxMm[] = []
        let ok = true
        for (let k = 0; k < query.length; k++) {
            const bi = indexToBox[idx + k]
            if (bi == null || bi < 0) {
                ok = false
                break
            }
            hitBoxes.push(charBoxes[bi])
        }
        if (ok) {
            const rect = unionBoxes(hitBoxes)
            if (rect && rect.w > 0 && rect.h > 0) rects.push(rect)
        }
        from = idx + Math.max(1, query.length)
    }
    return rects
}
