/** 生成相对锚点的波浪下划线路径点（mm） */
export function buildSquigglyRelativePoints(width: number, baselineY = 0): [number, number][] {
  if (width <= 0) return [[0, baselineY], [0, baselineY]]
  const amplitude = 0.6
  const period = 2.5
  const step = 0.5
  const points: [number, number][] = []
  for (let px = 0; px <= width; px += step) {
    const py = baselineY + amplitude * Math.sin((px / period) * Math.PI * 2)
    points.push([px, py])
  }
  const last = points[points.length - 1]
  if (last[0] !== width) {
    points.push([width, baselineY + amplitude * Math.sin((width / period) * Math.PI * 2)])
  }
  return points
}

/** 将相对路径点转为 Konva 绝对 px 坐标数组 */
export function relativePointsToKonva(
  annX: number,
  annY: number,
  points: [number, number][],
  scale: (v: number) => number,
): number[] {
  const out: number[] = []
  for (const [px, py] of points) {
    out.push(scale(annX + px))
    out.push(scale(annY + py))
  }
  return out
}
