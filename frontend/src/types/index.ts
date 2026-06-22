// ========== 元素类型 ==========
export type OfdElementType = 'TEXT' | 'IMAGE' | 'PATH' | 'SEAL' | 'OTHER'

export type AnnotationType =
    | 'HIGHLIGHT'    // 高亮
    | 'UNDERLINE'    // 下划线
    | 'SQUIGGLY'     // 波浪下划线
    | 'STRIKEOUT'    // 删除线
    | 'REPLACE'      // 替换线
    | 'TEXTBOX'      // 文本框
    | 'STICKYNOTE'   // 便利贴
    | 'ARROW'        // 箭头
    | 'CIRCLE'       // 圆形
    | 'RECTANGLE'    // 矩形
    | 'FREEHAND'     // 手绘
    | 'STAMP'        // 图章

// 当前工具类型
export type ToolType =
    | 'SELECT'       // 选择/移动
    | 'HAND'         // 手型/浏览（平移，不可选中）
    | 'HIGHLIGHT'
    | 'UNDERLINE'
    | 'SQUIGGLY'
    | 'STRIKEOUT'
    | 'REPLACE'
    | 'TEXTBOX'
    | 'STICKYNOTE'
    | 'ARROW'
    | 'CIRCLE'
    | 'RECTANGLE'
    | 'FREEHAND'
    | 'STAMP'

// ========== OFD原生元素（保持不变） ==========
export interface ElementData {
    id: string
    xmlObjId?: string
    type: OfdElementType
    x: number
    y: number
    width: number
    height: number
    content?: string
    fontSize?: number
    color?: string
    fontFamily?: string
    bold?: boolean
    italic?: boolean
    /** 是否为竖排文本：true 时 content 内已用 \n 拆字，前端不按外接框高度放大字号 */
    verticalLayout?: boolean
    /** 密码区网格：后端已按 g N 切行，前端禁止二次 word-wrap */
    passwordGrid?: boolean
    /** OFD TextCode DeltaX 平均字距（mm） */
    glyphAdvanceMm?: number
    rotation?: number
    scaleX?: number
    scaleY?: number
    pathData?: string
    fillColor?: string
    strokeColor?: string
    lineWidth?: number
    /** 对应 OFD PathObject Fill；false 为不填充 */
    pathFillEnabled?: boolean
    /** 对应 OFD PathObject Stroke；false 为不描边 */
    pathStrokeEnabled?: boolean
    resourceId?: string
    imageBase64?: string
    imageUrl?: string
    imageData?: string
    /** 用户新插入的元素（如导入图片），保存时由后端追加到 OFD */
    isNew?: boolean
    isDirty?: boolean
    /** 标记删除：保留在列表中以便保存时通知后端移除原 OFD 节点；前端渲染时过滤 */
    isDeleted?: boolean
    /** 图片像素已变更（裁剪等），保存时需写回 OFD 资源 */
    imageContentDirty?: boolean
    /** 图片内容版本号，变更后强制画布重建 Image 节点 */
    imageRevision?: number
    /** 用户在属性面板手动改过字号；为 true 时前端不再按外接框高度自动 clamp 字号 */
    fontSizeOverridden?: boolean
    originalX?: number
    originalY?: number
    originalWidth?: number
    originalHeight?: number
    originalRotation?: number
}

// ========== 注释数据（对应 AnnotationDTO.java） ==========
export interface AnnotationData {
    id: string
    type: AnnotationType
    pageIndex: number
    x: number
    y: number
    width: number
    height: number

    // 样式
    color?: string           // 填充色/高亮色
    /** 透明度，0.0 ~ 1.0 */
    opacity?: number;
    strokeColor?: string     // 边框色
    lineWidth?: number       // 线宽

    // 文本相关（TEXTBOX / STICKYNOTE / REPLACE）
    content?: string
    fontSize?: number
    fontColor?: string

    // 手绘 / 波浪下划线 / 替换线
    pathPoints?: [number, number][]

    // 图章相关（STAMP）
    stampBase64?: string

    /** 是否在画布上隐藏（注释列表可切换；默认 false 为显示） */
    hidden?: boolean

    // 时间戳
    createdAt?: number
    updatedAt?: number
}

// ========== 页面数据（扩展加入注释） ==========
export interface PageData {
    /** 前端稳定标识，用于列表 key 与拖拽重排 */
    id?: string
    pageIndex: number
    /** 对应原始 OFD 页序号（0 基），保存时用于重排/复制 */
    sourcePageIndex?: number
    width: number
    height: number
    /** 单页持久旋转（0/90/180/270）；PDF 导出写入 /Rotate，OFD 旋转时同步变换元素 */
    pageRotate?: number
    elements: ElementData[]
    annotations?: AnnotationData[]   // ← 新增
}

// ========== 文档来源 ==========
export type DocumentSource = 'ofd' | 'pdf'

/** 编辑区页面视图：单页 / 连续滚动 */
export type PageViewMode = 'single' | 'continuous'

// ========== 文档数据（保持不变） ==========
export interface DocumentData {
    fileId?: string
    title: string
    author?: string
    pageCount: number
    pages: PageData[]
    /** 保存/导出时烘焙的全局文本水印（可空） */
    watermark?: WatermarkConfig
}

export interface WatermarkConfig {
    text: string
    fontSize?: number
    color?: string
    opacity?: number
    angle?: number
    tile?: boolean
    bold?: boolean
}

// ========== API响应（保持不变） ==========
export interface ApiResponse<T> {
    data: T
    message?: string
    success: boolean
}

// ========== 注释API响应 ==========
export interface AnnotationResponse {
    fileId: string
    pageIndex: number
    annotations: AnnotationData[]
}

export interface AnnotationsByPage {
    [pageIndex: number]: AnnotationData[];
}

// ========== 电子签章 / 验签 ==========
export interface SignatureVerifyItem {
    id?: string
    type?: string
    valid: boolean
    sealName?: string
    signDate?: string
    message?: string
}

export interface SignatureVerifyResult {
    signed: boolean
    count: number
    valid: boolean
    message: string
    signatures: SignatureVerifyItem[]
}