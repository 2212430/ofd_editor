export type StampIcon =
    | 'check'
    | 'verify'
    | 'pencil'
    | 'search'
    | 'envelope'
    | 'rubber-stamp'
    | 'clock'
    | 'draft'
    | 'lock'
    | 'completed'
    | 'void'
    | 'urgent'

export interface DefaultStampDef {
    id: string
    text: string
    bgColor: string
    category: 'sign' | 'standard'
    icon?: StampIcon
}

/** 「在此签名」类：纯文字图章 */
export const SIGN_HERE_STAMPS: DefaultStampDef[] = [
    { id: 'accepted', text: '已接受', bgColor: '#4EAD52', category: 'sign' },
    { id: 'evidence', text: '证据', bgColor: '#4EAD52', category: 'sign' },
    { id: 'sign-here', text: '在此签名', bgColor: '#4EAD52', category: 'sign' },
    { id: 'initial', text: '初始', bgColor: '#4EAD52', category: 'sign' },
    { id: 'rejected', text: '已拒绝', bgColor: '#D94A3A', category: 'sign' },
]

/** 「标准图章」类：图标 + 文字 */
export const STANDARD_STAMPS: DefaultStampDef[] = [
    { id: 'approved', text: '已批准', bgColor: '#3B7DD8', category: 'standard', icon: 'check' },
    { id: 'verified', text: '已核实', bgColor: '#3B7DD8', category: 'standard', icon: 'verify' },
    { id: 'revised', text: '已修订', bgColor: '#3B7DD8', category: 'standard', icon: 'pencil' },
    { id: 'reviewed', text: '已审阅', bgColor: '#3B7DD8', category: 'standard', icon: 'search' },
    { id: 'received', text: '已接收', bgColor: '#3B7DD8', category: 'standard', icon: 'envelope' },
    { id: 'final', text: '最终', bgColor: '#3B7DD8', category: 'standard', icon: 'rubber-stamp' },
    { id: 'expired', text: '过期', bgColor: '#3B7DD8', category: 'standard', icon: 'clock' },
    { id: 'draft', text: '草稿', bgColor: '#3B7DD8', category: 'standard', icon: 'draft' },
    { id: 'confidential', text: '机密', bgColor: '#3B7DD8', category: 'standard', icon: 'lock' },
    { id: 'completed', text: '已完成', bgColor: '#3B7DD8', category: 'standard', icon: 'completed' },
    { id: 'void', text: '作废', bgColor: '#D94A3A', category: 'standard', icon: 'void' },
    { id: 'urgent', text: '紧急', bgColor: '#E67E22', category: 'standard', icon: 'urgent' },
]

export const ALL_DEFAULT_STAMPS: DefaultStampDef[] = [
    ...SIGN_HERE_STAMPS,
    ...STANDARD_STAMPS,
]

const STAMP_W = 320
const STAMP_H = 64
const dataUrlCache = new Map<string, string>()

function drawStampIcon(ctx: CanvasRenderingContext2D, icon: StampIcon, cx: number, cy: number, size: number) {
    const s = size / 24
    ctx.save()
    ctx.translate(cx, cy)
    ctx.scale(s, s)
    ctx.strokeStyle = '#FFFFFF'
    ctx.fillStyle = '#FFFFFF'
    ctx.lineWidth = 2
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'

    switch (icon) {
        case 'check':
            ctx.beginPath()
            ctx.moveTo(4, 12)
            ctx.lineTo(10, 18)
            ctx.lineTo(20, 6)
            ctx.stroke()
            break
        case 'verify':
            ctx.beginPath()
            ctx.moveTo(12, 4)
            ctx.lineTo(21, 20)
            ctx.lineTo(3, 20)
            ctx.closePath()
            ctx.stroke()
            ctx.fillRect(9, 13, 6, 5)
            ctx.strokeRect(9, 13, 6, 5)
            break
        case 'pencil':
            ctx.beginPath()
            ctx.moveTo(5, 19)
            ctx.lineTo(16, 8)
            ctx.lineTo(19, 11)
            ctx.lineTo(8, 22)
            ctx.closePath()
            ctx.fill()
            ctx.beginPath()
            ctx.moveTo(16, 8)
            ctx.lineTo(19, 5)
            ctx.stroke()
            break
        case 'search':
            ctx.beginPath()
            ctx.arc(10, 10, 6, 0, Math.PI * 2)
            ctx.stroke()
            ctx.beginPath()
            ctx.moveTo(14.5, 14.5)
            ctx.lineTo(20, 20)
            ctx.stroke()
            break
        case 'envelope':
            ctx.strokeRect(4, 7, 16, 12)
            ctx.beginPath()
            ctx.moveTo(4, 7)
            ctx.lineTo(12, 14)
            ctx.lineTo(20, 7)
            ctx.stroke()
            break
        case 'rubber-stamp':
            ctx.fillRect(6, 14, 12, 5)
            ctx.strokeRect(6, 14, 12, 5)
            ctx.beginPath()
            ctx.moveTo(8, 14)
            ctx.lineTo(8, 10)
            ctx.lineTo(16, 10)
            ctx.lineTo(16, 14)
            ctx.stroke()
            ctx.beginPath()
            ctx.moveTo(10, 7)
            ctx.lineTo(14, 7)
            ctx.stroke()
            break
        case 'clock':
            ctx.beginPath()
            ctx.arc(12, 12, 8, 0, Math.PI * 2)
            ctx.stroke()
            ctx.beginPath()
            ctx.moveTo(12, 12)
            ctx.lineTo(12, 7)
            ctx.moveTo(12, 12)
            ctx.lineTo(16, 14)
            ctx.stroke()
            break
        case 'draft':
            ctx.strokeRect(6, 5, 12, 16)
            ctx.beginPath()
            ctx.moveTo(6, 9)
            ctx.lineTo(18, 9)
            ctx.stroke()
            break
        case 'lock':
            ctx.strokeRect(7, 11, 10, 9)
            ctx.beginPath()
            ctx.arc(12, 11, 4, Math.PI, 0)
            ctx.stroke()
            break
        case 'completed':
            ctx.strokeRect(5, 5, 11, 15)
            ctx.beginPath()
            ctx.moveTo(18, 8)
            ctx.lineTo(20, 10)
            ctx.lineTo(24, 5)
            ctx.stroke()
            break
        case 'void':
            ctx.beginPath()
            ctx.moveTo(6, 6)
            ctx.lineTo(18, 18)
            ctx.moveTo(18, 6)
            ctx.lineTo(6, 18)
            ctx.stroke()
            break
        case 'urgent':
            ctx.beginPath()
            ctx.moveTo(12, 4)
            ctx.lineTo(21, 20)
            ctx.lineTo(3, 20)
            ctx.closePath()
            ctx.stroke()
            ctx.beginPath()
            ctx.moveTo(12, 9)
            ctx.lineTo(12, 14)
            ctx.stroke()
            ctx.beginPath()
            ctx.arc(12, 17, 1.2, 0, Math.PI * 2)
            ctx.fill()
            break
    }
    ctx.restore()
}

function renderStampToDataUrl(def: DefaultStampDef): string {
    const canvas = document.createElement('canvas')
    canvas.width = STAMP_W
    canvas.height = STAMP_H
    const ctx = canvas.getContext('2d')
    if (!ctx) return ''

    ctx.fillStyle = def.bgColor
    ctx.fillRect(0, 0, STAMP_W, STAMP_H)

    const inset = 3
    ctx.strokeStyle = '#FFFFFF'
    ctx.lineWidth = 1.5
    ctx.strokeRect(inset + 0.5, inset + 0.5, STAMP_W - inset * 2 - 1, STAMP_H - inset * 2 - 1)

    ctx.fillStyle = '#FFFFFF'
    ctx.textBaseline = 'middle'

    if (def.icon) {
        const iconSize = 28
        const iconX = 22
        drawStampIcon(ctx, def.icon, iconX, STAMP_H / 2, iconSize)
        const fontSize = def.text.length >= 4 ? 22 : 24
        ctx.font = `bold ${fontSize}px "Microsoft YaHei", "PingFang SC", "Segoe UI", sans-serif`
        ctx.textAlign = 'left'
        ctx.fillText(def.text, iconX + iconSize / 2 + 14, STAMP_H / 2 + 1)
    } else {
        const fontSize = def.text.length >= 4 ? 24 : 26
        ctx.font = `bold ${fontSize}px "Microsoft YaHei", "PingFang SC", "Segoe UI", sans-serif`
        ctx.textAlign = 'center'
        ctx.fillText(def.text, STAMP_W / 2, STAMP_H / 2 + 1)
    }

    return canvas.toDataURL('image/png')
}

/** 获取默认图章 PNG data URL（带缓存） */
export function getDefaultStampDataUrl(def: DefaultStampDef): string {
    const cached = dataUrlCache.get(def.id)
    if (cached) return cached
    const url = renderStampToDataUrl(def)
    if (url) dataUrlCache.set(def.id, url)
    return url
}

/** 预生成全部图章，避免对话框首次打开时卡顿 */
export function warmupDefaultStamps(): void {
    for (const def of ALL_DEFAULT_STAMPS) {
        getDefaultStampDataUrl(def)
    }
}
