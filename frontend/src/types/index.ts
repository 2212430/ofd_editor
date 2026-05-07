// ========== 元素类型 ==========
export type OfdElementType = 'TEXT' | 'IMAGE' | 'PATH' | 'OTHER'

export type AnnotationType =
    | 'HIGHLIGHT'    // 高亮
    | 'UNDERLINE'    // 下划线
    | 'STRIKEOUT'    // 删除线
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
    | 'HIGHLIGHT'
    | 'UNDERLINE'
    | 'STRIKEOUT'
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
    isDirty?: boolean
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

    // 文本相关（TEXTBOX / STICKYNOTE）
    content?: string
    fontSize?: number
    fontColor?: string

    // 手绘相关（FREEHAND）
    pathPoints?: [number, number][]

    // 图章相关（STAMP）
    stampBase64?: string

    // 时间戳
    createdAt?: number
    updatedAt?: number
}

// ========== 页面数据（扩展加入注释） ==========
export interface PageData {
    pageIndex: number
    width: number
    height: number
    elements: ElementData[]
    annotations?: AnnotationData[]   // ← 新增
}

// ========== 文档数据（保持不变） ==========
export interface DocumentData {
    fileId?: string
    title: string
    author?: string
    pageCount: number
    pages: PageData[]
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