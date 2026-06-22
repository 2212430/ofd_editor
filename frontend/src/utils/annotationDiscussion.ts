import type { AnnotationData, AnnotationReplyData } from '@/types'

function round1(n: number): number {
    return Math.round(n * 10) / 10
}

/** 用于撤销后按几何特征把讨论挂回新 annotationId */
export function annotationFingerprint(ann: AnnotationData): string {
    return [
        ann.pageIndex,
        ann.type,
        round1(ann.x),
        round1(ann.y),
        round1(ann.width),
        round1(ann.height),
    ].join('|')
}

export function remapRepliesByFingerprint(
    oldAnnotations: AnnotationData[],
    newAnnotations: AnnotationData[],
    repliesByAnnotationId: Record<string, AnnotationReplyData[]>,
): Record<string, AnnotationReplyData[]> {
    const repliesByFp = new Map<string, AnnotationReplyData[]>()
    const oldById = new Map(oldAnnotations.map(a => [a.id, a]))

    for (const [annId, replies] of Object.entries(repliesByAnnotationId)) {
        if (!replies?.length) continue
        const ann = oldById.get(annId)
        if (!ann) continue
        repliesByFp.set(annotationFingerprint(ann), replies)
    }

    const next: Record<string, AnnotationReplyData[]> = {}
    for (const ann of newAnnotations) {
        const fp = annotationFingerprint(ann)
        const replies = repliesByFp.get(fp)
        if (!replies?.length) continue
        next[ann.id] = replies.map(r => ({
            ...r,
            annotationId: ann.id,
        }))
    }
    return next
}

export interface ReplyTreeNode extends AnnotationReplyData {
    children: ReplyTreeNode[]
}

/** 将扁平回复列表构建为树（按 createdAt 排序） */
export function buildReplyTree(replies: AnnotationReplyData[]): ReplyTreeNode[] {
    const sorted = [...replies].sort((a, b) => (a.createdAt ?? 0) - (b.createdAt ?? 0))
    const map = new Map<string, ReplyTreeNode>()
    const roots: ReplyTreeNode[] = []

    for (const r of sorted) {
        map.set(r.id, { ...r, children: [] })
    }
    for (const r of sorted) {
        const node = map.get(r.id)!
        if (r.parentReplyId && map.has(r.parentReplyId)) {
            map.get(r.parentReplyId)!.children.push(node)
        } else {
            roots.push(node)
        }
    }
    return roots
}

export function formatReplyTime(ts?: number): string {
    if (!ts) return ''
    try {
        return new Date(ts).toLocaleString('zh-CN', { hour12: false })
    } catch {
        return ''
    }
}

export const DISCUSSION_AUTHOR_KEY = 'ofd-editor-discussion-author'

export function loadDiscussionAuthor(): string {
    try {
        return localStorage.getItem(DISCUSSION_AUTHOR_KEY)?.trim() || '用户'
    } catch {
        return '用户'
    }
}

export function saveDiscussionAuthor(name: string) {
    try {
        localStorage.setItem(DISCUSSION_AUTHOR_KEY, name.trim() || '用户')
    } catch { /* ignore */ }
}
