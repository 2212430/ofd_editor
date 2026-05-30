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
  fit: 'fit',
  includeAnnotations: true,
  quality: 'standard',
}

export interface CapturedPage {
  index: number
  dataUrl: string
  width: number
  height: number
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
  const valid = pages.filter((p) => p.dataUrl)
  const sizeRule =
      opts.orientation === 'auto' ? 'auto'
    : opts.orientation === 'landscape' ? 'landscape'
    : 'portrait'

  // 按份数复制整组页面
  const copies = Math.max(1, Math.min(99, Math.floor(opts.copies) || 1))
  const sequence: CapturedPage[] = []
  for (let c = 0; c < copies; c++) sequence.push(...valid)

  const sheetsHtml = sequence.map((p) => {
    const imgStyle = opts.fit === 'actual'
        ? `width:${p.width}mm;height:${p.height}mm;`
        : 'max-width:100%;max-height:100%;width:auto;height:auto;object-fit:contain;'
    return `<div class="sheet"><img src="${p.dataUrl}" style="${imgStyle}" /></div>`
  }).join('\n')

  const html = `<!doctype html>
<html lang="zh">
<head>
<meta charset="utf-8" />
<title>${escapeHtml(title)}</title>
<style>
  @page { size: ${sizeRule}; margin: ${opts.fit === 'fit' ? '6mm' : '0'}; }
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body { background: #fff; }
  .sheet {
    page-break-after: always;
    break-after: page;
    width: 100%;
    ${opts.fit === 'fit' ? 'height: 100vh;' : ''}
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
  }
  .sheet:last-child { page-break-after: auto; break-after: auto; }
  img { display: block; }
  /* 屏幕预览态（打印前）下给页面一点留白与阴影 */
  @media screen {
    body { background: #50555c; padding: 24px 0; }
    .sheet {
      height: auto;
      background: #fff;
      margin: 0 auto 24px;
      width: fit-content;
      box-shadow: 0 8px 28px rgba(0,0,0,.35);
    }
    .sheet img { max-width: 80vw; max-height: none; }
  }
</style>
</head>
<body>
${sheetsHtml || '<div style="color:#fff;text-align:center;padding:80px;font-family:sans-serif">没有可打印的页面</div>'}
<script>
  (function () {
    function go() { setTimeout(function () { window.focus(); window.print(); }, 250); }
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
