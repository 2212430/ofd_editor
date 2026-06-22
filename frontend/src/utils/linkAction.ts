import { ElMessage } from 'element-plus'
import type { AnnotationData } from '@/types'

export function describeLinkTarget(ann: AnnotationData): string {
    if (ann.type !== 'LINK') return ''
    if (ann.actionType === 'URI') {
        const uri = (ann.uri ?? '').trim()
        return uri || '未设置网址'
    }
    if (ann.actionType === 'GOTO_PAGE' && ann.targetPageIndex != null) {
        return `第 ${ann.targetPageIndex + 1} 页`
    }
    return '未设置动作'
}

export function executeLinkAction(
    ann: AnnotationData,
    ctx: { pageCount: number; goToPage: (index: number) => void },
): boolean {
    if (ann.type !== 'LINK') return false

    if (ann.actionType === 'URI') {
        const raw = (ann.uri ?? '').trim()
        if (!raw) {
            ElMessage.warning('链接地址为空')
            return false
        }
        const href = /^[a-zA-Z][a-zA-Z\d+\-.]*:/.test(raw) ? raw : `https://${raw}`
        window.open(href, '_blank', 'noopener,noreferrer')
        return true
    }

    if (ann.actionType === 'GOTO_PAGE') {
        const idx = ann.targetPageIndex
        if (idx == null || idx < 0 || idx >= ctx.pageCount) {
            ElMessage.warning('目标页不存在')
            return false
        }
        ctx.goToPage(idx)
        return true
    }

    ElMessage.warning('链接动作未配置')
    return false
}
