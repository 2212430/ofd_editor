/** 与后端 SplitPayloadUtil 对应的解包格式：[name utf8][len int32][bytes]… */

export interface SplitPackedFile {
    filename: string
    blob: Blob
}

export function unpackSplitPayload(buffer: ArrayBuffer): SplitPackedFile[] {
    const view = new DataView(buffer)
    const dec = new TextDecoder('utf-8')
    const files: SplitPackedFile[] = []
    let offset = 0

    while (offset < buffer.byteLength) {
        const nameLen = view.getUint16(offset)
        offset += 2
        const nameBytes = new Uint8Array(buffer, offset, nameLen)
        offset += nameLen
        const filename = dec.decode(nameBytes)

        const dataLen = view.getInt32(offset)
        offset += 4
        if (dataLen < 0 || offset + dataLen > buffer.byteLength) {
            throw new Error(`无效的拆分包长度: ${dataLen}`)
        }
        const data = new Uint8Array(buffer, offset, dataLen)
        offset += dataLen

        const lower = filename.toLowerCase()
        const type = lower.endsWith('.pdf')
            ? 'application/pdf'
            : lower.endsWith('.ofd')
                ? 'application/ofd'
                : 'application/octet-stream'
        files.push({ filename, blob: new Blob([data], { type }) })
    }

    if (files.length === 0) {
        throw new Error('拆分包为空')
    }
    return files
}
