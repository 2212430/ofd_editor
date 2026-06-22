import type { AnnotationData, DocumentData } from '@/types'
import { annotationListTitle, annotationTypeLabel } from '@/utils/annotationLabels'
import { describeLinkTarget } from '@/utils/linkAction'
import { sanitizeFilename } from '@/api/ofdApi'

export interface AnnotationReportRow {
    index: number
    pageNumber: number
    type: string
    typeLabel: string
    title: string
    content: string
    position: string
    size: string
    color: string
    opacity: string
    visible: boolean
    createdAt: string
}

export interface AnnotationTypeSummary {
    type: string
    label: string
    count: number
}

export interface AnnotationReportData {
    documentTitle: string
    pageCount: number
    documentKind: 'ofd' | 'pdf'
    generatedAt: string
    totalCount: number
    visibleCount: number
    hiddenCount: number
    typeSummary: AnnotationTypeSummary[]
    rows: AnnotationReportRow[]
}

function round1(n: number): number {
    return Math.round(n * 10) / 10
}

function formatTimestamp(ts?: number): string {
    if (!ts || ts <= 0) return '—'
    try {
        return new Date(ts).toLocaleString('zh-CN', { hour12: false })
    } catch {
        return '—'
    }
}

function describeContent(ann: AnnotationData, replyCount = 0): string {
    if (replyCount > 0) {
        const base = describeAnnotationContent(ann)
        const suffix = `讨论 ${replyCount} 条`
        return base && base !== '—' ? `${base}；${suffix}` : suffix
    }
    return describeAnnotationContent(ann)
}

function describeAnnotationContent(ann: AnnotationData): string {
    if (ann.type === 'LINK') {
        const tip = (ann.content ?? '').trim()
        const target = describeLinkTarget(ann)
        if (tip && target !== '未设置动作' && target !== '未设置网址') return `${target}；${tip}`
        if (target !== '未设置动作' && target !== '未设置网址') return target
        return tip || '—'
    }
    const text = (ann.content ?? '').trim()
    if (text) return text
    if (ann.type === 'STAMP') return ann.stampBase64 ? '图章（含图片）' : '图章'
    if (ann.type === 'FREEHAND' || ann.type === 'SQUIGGLY' || ann.type === 'REPLACE') {
        const n = ann.pathPoints?.length ?? 0
        return n > 0 ? `路径 ${n} 点` : '—'
    }
    return '—'
}

function describeColor(ann: AnnotationData): string {
    return ann.color ?? ann.strokeColor ?? ann.fontColor ?? '—'
}

function escapeHtml(s: string): string {
    return s
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
}

function escapeCsvCell(s: string): string {
    const v = s.replace(/"/g, '""')
    return /[",\n\r]/.test(v) ? `"${v}"` : v
}

export function buildAnnotationReport(
    doc: DocumentData,
    items: { annotation: AnnotationData; pageIndex: number }[],
    documentKind: 'ofd' | 'pdf',
    replyCountByAnnotationId?: Record<string, number>,
): AnnotationReportData {
    const typeCounts = new Map<string, number>()
    let visibleCount = 0
    let hiddenCount = 0

    const rows: AnnotationReportRow[] = items.map((item, i) => {
        const ann = item.annotation
        const type = ann.type
        typeCounts.set(type, (typeCounts.get(type) ?? 0) + 1)
        if (ann.hidden) hiddenCount++
        else visibleCount++

        const opacity = ann.opacity != null ? `${Math.round(ann.opacity * 100)}%` : '—'

        return {
            index: i + 1,
            pageNumber: item.pageIndex + 1,
            type,
            typeLabel: annotationTypeLabel(type),
            title: annotationListTitle(ann),
            content: describeContent(ann, replyCountByAnnotationId?.[ann.id] ?? 0),
            position: `(${round1(ann.x)}, ${round1(ann.y)})`,
            size: `${round1(ann.width)} × ${round1(ann.height)} mm`,
            color: describeColor(ann),
            opacity,
            visible: !ann.hidden,
            createdAt: formatTimestamp(ann.createdAt),
        }
    })

    const typeSummary: AnnotationTypeSummary[] = [...typeCounts.entries()]
        .map(([type, count]) => ({ type, label: annotationTypeLabel(type), count }))
        .sort((a, b) => b.count - a.count || a.label.localeCompare(b.label, 'zh-CN'))

    return {
        documentTitle: doc.title || '未命名文档',
        pageCount: doc.pageCount,
        documentKind,
        generatedAt: new Date().toLocaleString('zh-CN', { hour12: false }),
        totalCount: items.length,
        visibleCount,
        hiddenCount,
        typeSummary,
        rows,
    }
}

function reportStyles(forPrint: boolean): string {
    const page = forPrint
        ? '@page { margin: 16mm; } body { margin: 0; }'
        : ''
    return `
${page}
body { font-family: "Microsoft YaHei", "PingFang SC", sans-serif; color: #222; line-height: 1.5; }
h1 { font-size: 20px; margin: 0 0 8px; }
.meta { color: #666; font-size: 13px; margin-bottom: 20px; }
.meta p { margin: 4px 0; }
.summary { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; }
.stat { background: #f4f6f9; border-radius: 8px; padding: 10px 16px; min-width: 100px; }
.stat b { display: block; font-size: 22px; color: #1a73e8; }
.stat span { font-size: 12px; color: #666; }
.types { margin-bottom: 20px; }
.types h2, .detail h2 { font-size: 15px; margin: 0 0 10px; border-left: 3px solid #1a73e8; padding-left: 8px; }
.type-tags { display: flex; flex-wrap: wrap; gap: 8px; }
.tag { background: #eef4fc; color: #1a5fb4; font-size: 12px; padding: 4px 10px; border-radius: 4px; }
table { width: 100%; border-collapse: collapse; font-size: 12px; }
th, td { border: 1px solid #ddd; padding: 6px 8px; text-align: left; vertical-align: top; }
th { background: #f0f4f8; font-weight: 600; }
tr:nth-child(even) td { background: #fafafa; }
.hidden-row td { color: #888; }
.footer { margin-top: 20px; font-size: 11px; color: #999; text-align: center; }
`
}

export function reportToHtml(data: AnnotationReportData, opts?: { forPrint?: boolean }): string {
    const kindLabel = data.documentKind === 'pdf' ? 'PDF（原生）' : 'OFD'
    const typeTags = data.typeSummary
        .map((t) => `<span class="tag">${escapeHtml(t.label)} × ${t.count}</span>`)
        .join('')
    const tableRows = data.rows.map((r) => {
        const cls = r.visible ? '' : ' class="hidden-row"'
        return `<tr${cls}>
  <td>${r.index}</td>
  <td>${r.pageNumber}</td>
  <td>${escapeHtml(r.typeLabel)}</td>
  <td>${escapeHtml(r.title)}</td>
  <td>${escapeHtml(r.content)}</td>
  <td>${escapeHtml(r.position)}</td>
  <td>${escapeHtml(r.size)}</td>
  <td>${escapeHtml(r.color)}</td>
  <td>${escapeHtml(r.opacity)}</td>
  <td>${r.visible ? '显示' : '隐藏'}</td>
  <td>${escapeHtml(r.createdAt)}</td>
</tr>`
    }).join('\n')

    return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>${escapeHtml(data.documentTitle)} — 注释汇总报告</title>
<style>${reportStyles(!!opts?.forPrint)}</style>
</head>
<body>
<h1>注释汇总报告</h1>
<div class="meta">
  <p><strong>文档：</strong>${escapeHtml(data.documentTitle)}</p>
  <p><strong>格式：</strong>${kindLabel} · <strong>页数：</strong>${data.pageCount} 页</p>
  <p><strong>生成时间：</strong>${escapeHtml(data.generatedAt)}</p>
</div>
<div class="summary">
  <div class="stat"><b>${data.totalCount}</b><span>注释总数</span></div>
  <div class="stat"><b>${data.visibleCount}</b><span>显示中</span></div>
  <div class="stat"><b>${data.hiddenCount}</b><span>已隐藏</span></div>
  <div class="stat"><b>${data.typeSummary.length}</b><span>注释类型</span></div>
</div>
<div class="types">
  <h2>类型统计</h2>
  <div class="type-tags">${typeTags || '<span class="tag">无</span>'}</div>
</div>
<div class="detail">
  <h2>注释明细（共 ${data.totalCount} 条）</h2>
  <table>
    <thead>
      <tr>
        <th>#</th><th>页码</th><th>类型</th><th>标题</th><th>内容</th>
        <th>位置 (mm)</th><th>尺寸</th><th>颜色</th><th>透明度</th><th>状态</th><th>创建时间</th>
      </tr>
    </thead>
    <tbody>
${tableRows}
    </tbody>
  </table>
</div>
<p class="footer">由 OFD Studio 生成</p>
</body>
</html>`
}

export function reportToCsv(data: AnnotationReportData): string {
    const header = [
        '序号', '页码', '类型', '标题', '内容', '位置X_Y_mm', '尺寸_mm', '颜色', '透明度', '状态', '创建时间',
    ]
    const lines = [header.join(',')]
    for (const r of data.rows) {
        lines.push([
            String(r.index),
            String(r.pageNumber),
            r.typeLabel,
            r.title,
            r.content,
            r.position,
            r.size,
            r.color,
            r.opacity,
            r.visible ? '显示' : '隐藏',
            r.createdAt,
        ].map(escapeCsvCell).join(','))
    }
    lines.push('')
    lines.push(`文档,${escapeCsvCell(data.documentTitle)}`)
    lines.push(`页数,${data.pageCount}`)
    lines.push(`生成时间,${escapeCsvCell(data.generatedAt)}`)
    lines.push(`注释总数,${data.totalCount}`)
    return '\uFEFF' + lines.join('\r\n')
}

export function reportBaseFilename(data: AnnotationReportData): string {
    const base = sanitizeFilename(data.documentTitle).replace(/\.(ofd|pdf)$/i, '') || 'document'
    return `${base}_注释汇总报告`
}

export function downloadAnnotationReportHtml(data: AnnotationReportData): void {
    const blob = new Blob([reportToHtml(data)], { type: 'text/html;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${reportBaseFilename(data)}.html`
    a.click()
    URL.revokeObjectURL(url)
}

export function downloadAnnotationReportCsv(data: AnnotationReportData): void {
    const blob = new Blob([reportToCsv(data)], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${reportBaseFilename(data)}.csv`
    a.click()
    URL.revokeObjectURL(url)
}

export function printAnnotationReport(data: AnnotationReportData): boolean {
    const win = window.open('', '_blank', 'width=960,height=900')
    if (!win) return false
    win.document.write(reportToHtml(data, { forPrint: true }))
    win.document.close()
    win.onload = () => {
        win.focus()
        win.print()
    }
    return true
}
