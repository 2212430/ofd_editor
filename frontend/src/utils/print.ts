/* ============================================================
   打印工具：页码范围解析 + 打印窗口生成
   ============================================================ */

export interface PrintOptions {
  /** 打印范围 */
  range: 'all' | 'current' | 'custom'
  /** 自定义范围字符串，如 "1-3,5,8"（1 基） */
  customRange: string
  /** 份数 */
  copies: number
  /** 纸张方向 */
  orientation: 'auto' | 'portrait' | 'landscape'
  /** 单面 / 双面（长边翻转 / 短边翻转） */
  duplex: 'simplex' | 'duplex-long' | 'duplex-short'
  /** 彩色 / 黑白 */
  colorMode: 'color' | 'grayscale'
  /** 缩放方式：适应纸张 / 实际尺寸 */
  fit: 'fit' | 'actual'
  /** 是否包含注释 */
  includeAnnotations: boolean
  /** 输出清晰度 */
  quality: 'standard' | 'high'
}

export const DEFAULT_PRINT_OPTIONS: PrintOptions = {
  range: 'all',
  customRange: '',
  copies: 1,
  orientation: 'auto',
  duplex: 'simplex',
  colorMode: 'color',
  fit: 'fit',
  includeAnnotations: true,
  quality: 'standard',
}

export interface CapturedPage {
  index: number
  dataUrl: string
  width: number
  height: number
  /** 双面打印时补的空白页 */
  blank?: boolean
}

/** quality → Konva pixelRatio */
export function qualityToPixelRatio(q: PrintOptions['quality']): number {
  return q === 'high' ? 2.5 : 1.5
}

/**
 * 把范围选项解析为 0 基的页码数组。
 * @param totalPages   总页数
 * @param currentIndex 当前页（0 基）
 */
export function resolvePageIndices(
    opts: Pick<PrintOptions, 'range' | 'customRange'>,
    totalPages: number,
    currentIndex: number,
): number[] {
  if (opts.range === 'current') return [currentIndex]
  if (opts.range === 'all') {
    return Array.from({ length: totalPages }, (_, i) => i)
  }
  // custom: 解析 "1-3,5,8"
  const out = new Set<number>()
  for (const rawPart of opts.customRange.split(/[,，]/)) {
    const part = rawPart.trim()
    if (!part) continue
    const m = part.match(/^(\d+)\s*[-~]\s*(\d+)$/)
    if (m) {
      let a = parseInt(m[1], 10)
      let b = parseInt(m[2], 10)
      if (a > b) [a, b] = [b, a]
      for (let p = a; p <= b; p++) {
        if (p >= 1 && p <= totalPages) out.add(p - 1)
      }
    } else {
      const p = parseInt(part, 10)
      if (!isNaN(p) && p >= 1 && p <= totalPages) out.add(p - 1)
    }
  }
  return Array.from(out).sort((x, y) => x - y)
}

/** 根据首页尺寸解析 @page 方向（auto 时按页宽高中较大边判断） */
export function resolvePageOrientation(
    opts: Pick<PrintOptions, 'orientation'>,
    pages: CapturedPage[],
): 'portrait' | 'landscape' {
  if (opts.orientation === 'portrait') return 'portrait'
  if (opts.orientation === 'landscape') return 'landscape'
  const ref = pages.find((p) => !p.blank && p.dataUrl)
  if (!ref) return 'portrait'
  return ref.width > ref.height ? 'landscape' : 'portrait'
}

/** 按份数展开，并在双面且奇数页时补空白页（便于长/短边翻转） */
export function buildPrintSequence(
    pages: CapturedPage[],
    opts: Pick<PrintOptions, 'copies' | 'duplex'>,
): CapturedPage[] {
  const valid = pages.filter((p) => p.dataUrl)
  if (valid.length === 0) return []

  const copies = Math.max(1, Math.min(99, Math.floor(opts.copies) || 1))
  const seq: CapturedPage[] = []
  for (let c = 0; c < copies; c++) seq.push(...valid)

  if (opts.duplex !== 'simplex' && seq.length % 2 === 1) {
    const ref = valid[0]
    seq.push({
      index: -1,
      dataUrl: '',
      width: ref.width,
      height: ref.height,
      blank: true,
    })
  }
  return seq
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c] as string
  ))
}

/**
 * 把已捕获的页面图片写入打印窗口并触发打印。
 * 窗口需要在用户手势中预先 open（避免被拦截）。
 */
export function buildPrintWindow(
    win: Window,
    pages: CapturedPage[],
    opts: PrintOptions,
    title: string,
): void {
  const sequence = buildPrintSequence(pages, opts)
  if (sequence.length === 0) {
    win.document.open()
    win.document.write(
        '<!doctype html><meta charset="utf-8"><title>打印</title>' +
        '<body style="font-family:sans-serif;padding:48px;color:#666">没有可打印的页面</body>',
    )
    win.document.close()
    return
  }

  const orient = resolvePageOrientation(opts, sequence)
  const pageSize = `A4 ${orient}`
  const margin = opts.fit === 'fit' ? '8mm' : '0'
  const grayscale = opts.colorMode === 'grayscale'

  const duplexHint = opts.duplex !== 'simplex'
      ? `<p class="duplex-hint">提示：已选择${
          opts.duplex === 'duplex-long' ? '双面（长边翻转）' : '双面（短边翻转）'
      }；奇数页已自动补空白页。请在系统打印对话框中确认「双面打印」选项。</p>`
      : ''

  const sheetsHtml = sequence.map((p) => {
    if (p.blank) {
      return '<div class="sheet sheet-blank" aria-hidden="true"></div>'
    }
    const imgStyle = opts.fit === 'actual'
        ? `width:${p.width}mm;height:${p.height}mm;`
        : 'max-width:100%;max-height:100%;width:auto;height:auto;object-fit:contain;'
    return `<div class="sheet"><img src="${p.dataUrl}" style="${imgStyle}" alt="" /></div>`
  }).join('\n')

  const html = `<!doctype html>
<html lang="zh">
<head>
<meta charset="utf-8" />
<title>${escapeHtml(title)}</title>
<style>
  @page { size: ${pageSize}; margin: ${margin}; }
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body { background: #fff; }
  .duplex-hint {
    display: none;
    font-size: 12px;
    color: #666;
    text-align: center;
    padding: 8px 16px;
    background: #f5f7fa;
    border-bottom: 1px solid #e4e7ed;
  }
  .sheet {
    page-break-after: always;
    break-after: page;
    width: 100%;
    ${opts.fit === 'fit' ? 'min-height: 100vh;' : ''}
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
  }
  .sheet-blank { background: #fff; min-height: 100vh; }
  .sheet:last-child { page-break-after: auto; break-after: auto; }
  img {
    display: block;
    ${grayscale ? 'filter: grayscale(100%);' : ''}
    ${grayscale ? '-webkit-print-color-adjust: economy; print-color-adjust: economy;' : '-webkit-print-color-adjust: exact; print-color-adjust: exact;'}
  }
  @media screen {
    body { background: #50555c; padding: 24px 0; }
    .duplex-hint { display: block; }
    .sheet {
      min-height: auto;
      background: #fff;
      margin: 0 auto 24px;
      width: fit-content;
      max-width: 90vw;
      box-shadow: 0 8px 28px rgba(0,0,0,.35);
    }
    .sheet-blank {
      width: 210mm;
      height: 297mm;
      max-width: 90vw;
    }
    .sheet img { max-width: 80vw; max-height: none; }
  }
</style>
</head>
<body>
${duplexHint}
${sheetsHtml}
<script>
  (function () {
    function go() { setTimeout(function () { window.focus(); window.print(); }, 300); }
    if (document.readyState === 'complete') go();
    else window.addEventListener('load', go);
    window.addEventListener('afterprint', function () { window.close(); });
  })();
<\/script>
</body>
</html>`

  win.document.open()
  win.document.write(html)
  win.document.close()
}
