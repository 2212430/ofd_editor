import type { AnnotationData } from '@/types'
import { describeLinkTarget } from '@/utils/linkAction'

export const ANNOTATION_TYPE_LABEL: Record<string, string> = {
    HIGHLIGHT: '高亮',
    UNDERLINE: '下划线',
    SQUIGGLY: '波浪下划线',
    STRIKEOUT: '删除线',
    REPLACE: '替换线',
    RECTANGLE: '矩形',
    CIRCLE: '椭圆',
    ARROW: '箭头',
    FREEHAND: '手绘',
    TEXTBOX: '文本框',
    STICKYNOTE: '便利贴',
    STAMP: '图章',
    LINK: '链接',
}

export function annotationTypeLabel(type: string): string {
    return ANNOTATION_TYPE_LABEL[type] ?? type
}

export function annotationListTitle(ann: AnnotationData): string {
    const typeName = annotationTypeLabel(ann.type)
    if (ann.type === 'TEXTBOX' || ann.type === 'STICKYNOTE' || ann.type === 'REPLACE') {
        const text = (ann.content ?? '').trim()
        if (text) {
            const short = text.length > 28 ? `${text.slice(0, 28)}…` : text
            return `${typeName}：${short}`
        }
    }
    if (ann.type === 'STAMP') return '图章'
    if (ann.type === 'LINK') {
        const target = describeLinkTarget(ann)
        return target !== '未设置动作' && target !== '未设置网址' ? `链接：${target}` : '链接'
    }
    return typeName
}

export function isAnnotationVisible(ann: AnnotationData): boolean {
    return !ann.hidden
}
