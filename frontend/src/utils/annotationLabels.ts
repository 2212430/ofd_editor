import type { AnnotationData } from '@/types'

export const ANNOTATION_TYPE_LABEL: Record<string, string> = {
    HIGHLIGHT: '高亮',
    UNDERLINE: '下划线',
    STRIKEOUT: '删除线',
    RECTANGLE: '矩形',
    CIRCLE: '椭圆',
    ARROW: '箭头',
    FREEHAND: '手绘',
    TEXTBOX: '文本框',
    STICKYNOTE: '便利贴',
    STAMP: '图章',
}

export function annotationTypeLabel(type: string): string {
    return ANNOTATION_TYPE_LABEL[type] ?? type
}

export function annotationListTitle(ann: AnnotationData): string {
    const typeName = annotationTypeLabel(ann.type)
    if (ann.type === 'TEXTBOX' || ann.type === 'STICKYNOTE') {
        const text = (ann.content ?? '').trim()
        if (text) {
            const short = text.length > 28 ? `${text.slice(0, 28)}…` : text
            return `${typeName}：${short}`
        }
    }
    if (ann.type === 'STAMP') return '图章'
    return typeName
}

export function isAnnotationVisible(ann: AnnotationData): boolean {
    return !ann.hidden
}
