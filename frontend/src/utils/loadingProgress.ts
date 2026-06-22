import type { AxiosRequestConfig } from 'axios'

export type ProgressReporter = (percent: number | null, text?: string) => void

/** json：上传后服务端长时间处理；blob：上传→处理→下载文件 */
export type TransferProgressMode = 'json' | 'blob'

let reporter: ProgressReporter | null = null
let serverWaitTimer: ReturnType<typeof setInterval> | null = null

export function registerProgressReporter(fn: ProgressReporter | null) {
    reporter = fn
}

/** 停止模拟进度（请求结束或开始真实下载时调用） */
export function stopTransferProgress() {
    if (serverWaitTimer) {
        clearInterval(serverWaitTimer)
        serverWaitTimer = null
    }
}

/** 服务端处理阶段：从 fromPct 缓慢爬升到 95%，避免假 100% 或假 0% */
function startServerWaitProgress(fromPct: number, text: string) {
    stopTransferProgress()
    if (!reporter) return

    let pct = fromPct
    reporter(pct, text)

    serverWaitTimer = setInterval(() => {
        if (pct >= 95 || !reporter) return
        const step = Math.max(0.3, (95 - pct) * 0.04)
        pct = Math.min(95, pct + step)
        reporter(Math.round(pct), text)
    }, 400)
}

/** 为 axios 请求附加打开/转换类任务的上传、下载进度回调 */
export function transferProgressConfig(
    phase: string,
    mode: TransferProgressMode = 'blob',
    knownTotal?: number,
): Pick<AxiosRequestConfig, 'onUploadProgress' | 'onDownloadProgress'> {
    const uploadCap = mode === 'json' ? 30 : 70
    const serverWaitText =
        mode === 'json' ? `${phase}（服务器解析中，请稍候…）` : `${phase}（处理中）`

    let uploadDone = false

    const markUploadComplete = () => {
        if (uploadDone) return
        uploadDone = true
        startServerWaitProgress(uploadCap, serverWaitText)
    }

    return {
        onUploadProgress(event) {
            if (!reporter) return
            const total = event.total || knownTotal
            if (!total) return

            if (event.loaded >= total) {
                markUploadComplete()
                return
            }

            stopTransferProgress()
            const pct = Math.max(1, Math.min(uploadCap, Math.round((event.loaded / total) * uploadCap)))
            reporter(pct, `${phase}（上传 ${pct}%）`)
        },
        onDownloadProgress:
            mode === 'json'
                ? undefined
                : (event) => {
                      if (!reporter) return
                      if (event.total) {
                          stopTransferProgress()
                          const pct = Math.min(
                              95,
                              uploadCap + Math.round((event.loaded / event.total) * (95 - uploadCap)),
                          )
                          const label =
                              event.loaded >= event.total
                                  ? `${phase}（下载完成）`
                                  : `${phase}（下载 ${pct}%）`
                          reporter(pct, label)
                      } else if (uploadDone) {
                          startServerWaitProgress(uploadCap, serverWaitText)
                      }
                  },
    }
}
