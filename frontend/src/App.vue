<template>
  <div class="app">
    <!-- Ribbon 工具栏（含品牌 + 标签页） -->
    <Toolbar />

    <!-- 主体 -->
    <div class="main-body">
      <!-- 左侧页面面板 -->
      <PagePanel />

      <!-- 中间编辑区 -->
      <div ref="editorAreaRef" class="editor-area">
        <!-- 加载遮罩 -->
        <div v-if="store.isLoading" class="loading-mask">
          <div class="spinner"></div>
          <span>{{ store.loadingText }}</span>
        </div>

        <!-- 编辑器 -->
        <template v-if="store.document">
          <ContinuousPageView
              v-if="store.pageViewMode === 'continuous'"
              ref="continuousViewRef"
          />
          <div
              v-else-if="store.currentPage"
              class="canvas-container"
              :style="singleCanvasFrameStyle"
          >
            <CanvasEditor
                ref="singleCanvasRef"
                :page="store.currentPage"
                :page-index="store.currentPageIndex"
            />
          </div>
        </template>

        <!-- 欢迎页 -->
        <div v-else class="welcome">
          <div class="welcome-card">
            <div class="welcome-logo">OFD</div>
            <h1 class="welcome-title">OFD Studio</h1>
            <p class="welcome-desc">专业的开放版式文档（OFD）编辑器<br/>解析 · 编辑 · 批注 · 与 PDF 双向转换</p>

            <div class="welcome-actions">
              <el-button type="primary" size="large" :icon="Upload" @click="triggerUpload">
                打开 OFD 文件
              </el-button>
              <el-button size="large" :icon="Upload" @click="triggerPdf">
                导入 PDF
              </el-button>
            </div>

            <div class="welcome-feats">
              <div class="feat"><span class="feat-dot"></span>非破坏性编辑</div>
              <div class="feat"><span class="feat-dot"></span>十类注释批注</div>
              <div class="feat"><span class="feat-dot"></span>像素级保真转换</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧属性面板 -->
      <PropertyPanel />
    </div>

    <!-- 底部状态栏 -->
    <footer class="status-bar">
      <div class="status-left">
        <template v-if="store.document">
          <span>{{ store.document.title }}</span>
          <span class="status-sep">|</span>
          <span>第 {{ store.currentPageIndex + 1 }} / {{ store.document.pageCount }} 页</span>
          <span class="status-sep">|</span>
          <span>缩放 {{ Math.round(store.scale * 100) }}%</span>
          <template v-if="store.viewRotation !== 0">
            <span class="status-sep">|</span>
            <span>视图旋转 {{ normalizeViewRotation(store.viewRotation) }}°</span>
          </template>
        </template>
        <span v-else>就绪</span>
      </div>
      <span class="version">OFD Studio v1.0</span>
    </footer>

    <!-- 打印对话框 -->
    <PrintDialog @print="handlePrint" />

    <!-- 隐藏的文件输入 -->
    <input ref="uploadRef" type="file" accept=".ofd"
           style="display:none" @change="handleWelcomeUpload" />
    <input ref="pdfRef" type="file" accept=".pdf"
           style="display:none" @change="handleWelcomePdf" />

    <!-- 离屏画布：仅供左侧缩略图截图，不切换主编辑区当前页 -->
    <div
        v-if="store.document && thumbCapturePage"
        class="thumb-capture-host"
        aria-hidden="true"
    >
      <CanvasEditor
          ref="thumbCanvasRef"
          offscreen
          :fixed-scale="1"
          :page="thumbCapturePage"
          :page-index="thumbCapturePageIndex"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Upload } from '@element-plus/icons-vue'
import { useEditorStore } from '@/stores/editorStore'
import { ofdApi } from '@/api/ofdApi'
import Toolbar from '@/components/Toolbar.vue'
import PagePanel from '@/components/PagePanel.vue'
import CanvasEditor from '@/components/CanvasEditor.vue'
import ContinuousPageView from '@/components/ContinuousPageView.vue'
import PropertyPanel from '@/components/PropertyPanel.vue'
import PrintDialog from '@/components/PrintDialog.vue'
import {
  buildPrintWindow, resolvePageIndices, qualityToPixelRatio,
  type PrintOptions, type CapturedPage,
} from '@/utils/print'
import { normalizeViewRotation, viewStagePixelSize } from '@/utils/viewRotation'

const store = useEditorStore()
const uploadRef = ref<HTMLInputElement>()
const pdfRef = ref<HTMLInputElement>()
const singleCanvasRef = ref<InstanceType<typeof CanvasEditor> | null>(null)
const continuousViewRef = ref<InstanceType<typeof ContinuousPageView> | null>(null)

function getActiveCanvas() {
  if (store.pageViewMode === 'continuous') {
    return continuousViewRef.value?.getCanvasForPage(store.currentPageIndex) ?? null
  }
  return singleCanvasRef.value
}
const thumbCanvasRef = ref<InstanceType<typeof CanvasEditor> | null>(null)
const editorAreaRef = ref<HTMLElement>()
const thumbCapturePageIndex = ref(0)
const thumbCapturePage = computed(() =>
    store.document?.pages[thumbCapturePageIndex.value] ?? null,
)

const singleCanvasFrameStyle = computed(() => {
  const page = store.currentPage
  if (!page) return {}
  const { stageWidth, stageHeight } = viewStagePixelSize(
      page.width,
      page.height,
      store.scale,
      store.viewRotation,
  )
  return {
    width: `${stageWidth}px`,
    height: `${stageHeight}px`,
  }
})

/** 左侧缩略图：按需截取单页（低分辨率） */
const THUMBNAIL_PIXEL_RATIO = 0.22

async function waitForCanvasPaint() {
  await nextTick()
  await new Promise<void>((r) => {
    requestAnimationFrame(() => requestAnimationFrame(() => r()))
  })
}

onMounted(() => {
  store.registerEditorAreaResolver(() => editorAreaRef.value ?? null)

  store.registerThumbnailCaptureHook(async (pageIndex: number) => {
    const doc = store.document
    if (!doc || !thumbCanvasRef.value) return null
    if (pageIndex < 0 || pageIndex >= doc.pageCount) return null

    thumbCapturePageIndex.value = pageIndex
    await waitForCanvasPaint()
    const cap = await thumbCanvasRef.value.captureForPrint(THUMBNAIL_PIXEL_RATIO, true)
    return cap?.dataUrl ?? null
  })
})

function triggerUpload() {
  uploadRef.value?.click()
}
function triggerPdf() {
  pdfRef.value?.click()
}

async function handleWelcomeUpload(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  store.setLoading(true, '正在解析OFD文件...')
  try {
    const doc = await ofdApi.parseOfd(file)
    store.setDocument(doc)
    await store.loadAllAnnotations()
    store.setCurrentFile(file, 'ofd')
    ElMessage.success(`解析成功：${doc.title}`)
  } catch (err: any) {
    ElMessage.error(err.message || '解析失败')
  } finally {
    store.setLoading(false)
    ;(e.target as HTMLInputElement).value = ''
  }
}

async function handlePrint(opts: PrintOptions) {
  const doc = store.document
  if (!doc) return

  // 1) 先在用户手势内打开打印窗口，避免被浏览器拦截
  const win = window.open('', '_blank', 'width=920,height=1200')
  if (!win) {
    ElMessage.error('打印窗口被浏览器拦截，请允许本站弹出窗口后重试')
    return
  }
  win.document.write(
    '<!doctype html><meta charset="utf-8"><title>正在准备打印…</title>' +
    '<body style="font-family:sans-serif;color:#666;padding:48px;background:#f4f5f7">' +
    '正在渲染页面，请稍候…</body>'
  )

  const indices = resolvePageIndices(opts, doc.pageCount, store.currentPageIndex)
  const pixelRatio = qualityToPixelRatio(opts.quality)

  // 2) 保存当前视图状态，逐页渲染捕获
  const savedScale = store.scale
  const savedPage = store.currentPageIndex
  store.selectElement(null)
  store.selectAnnotation(null)
  store.setScale(1)
  store.setLoading(true, '正在准备打印…')

  const captured: CapturedPage[] = []
  try {
    for (let i = 0; i < indices.length; i++) {
      const idx = indices[i]
      store.setLoading(true, `正在渲染第 ${i + 1} / ${indices.length} 页…`)
      store.setCurrentPage(idx)
      await nextTick()
      const cap = await getActiveCanvas()?.captureForPrint(pixelRatio, opts.includeAnnotations)
      if (cap?.dataUrl) {
        captured.push({ index: idx, ...cap })
      }
    }
  } catch (err: any) {
    console.error('[print] 渲染失败', err)
    ElMessage.error('打印渲染失败：' + (err?.message ?? '未知错误'))
  } finally {
    store.setScale(savedScale)
    store.setCurrentPage(savedPage)
    store.setLoading(false)
  }

  // 3) 写入打印窗口并触发打印
  if (captured.length === 0) {
    win.close()
    ElMessage.warning('没有可打印的页面')
    return
  }
  buildPrintWindow(win, captured, opts, doc.title || 'OFD 文档')
  ElMessage.success(`已生成 ${captured.length} 页打印预览`)
}

async function handleWelcomePdf(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  store.setLoading(true, '正在转换PDF...')
  try {
    const blob = await ofdApi.fromPdf(file)
    const ofdFile = new File([blob], file.name.replace(/\.pdf$/i, '.ofd'))
    const doc = await ofdApi.parseOfd(ofdFile)
    store.setDocument(doc)
    store.setCurrentFile(ofdFile, 'pdf')
    await store.loadAllAnnotations()
    ElMessage.success('PDF 转换成功！')
  } catch (err: any) {
    ElMessage.error(err.message || 'PDF转换失败')
  } finally {
    store.setLoading(false)
    ;(e.target as HTMLInputElement).value = ''
  }
}
</script>

<style scoped>
.app {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
  background: var(--work-bg);
}

.main-body {
  display: flex;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.editor-area {
  flex: 1;
  overflow: auto;
  background: var(--work-bg);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 24px;
  position: relative;
}

.canvas-container {
  display: inline-block;
  box-shadow: var(--shadow-page);
  background: #fff;
}

/* 欢迎页 */
.welcome {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #ececec;
}
.welcome-card {
  text-align: center;
  padding: 40px 48px;
  background: #fff;
  border-radius: 8px;
  box-shadow: var(--shadow-md);
  max-width: 480px;
}
.welcome-logo {
  display: grid;
  place-items: center;
  width: 64px;
  height: 64px;
  margin: 0 auto 18px;
  border-radius: 12px;
  background: linear-gradient(145deg, var(--ribbon-accent), var(--ribbon-accent-dark));
  color: #fff;
  font-size: 18px;
  font-weight: 800;
}
.welcome-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-1);
  margin: 0 0 8px;
}
.welcome-desc {
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-3);
  margin: 0 0 24px;
}
.welcome-actions { display: flex; gap: 10px; justify-content: center; margin-bottom: 20px; }
.welcome-feats {
  display: flex;
  gap: 16px;
  justify-content: center;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--text-2);
}
.feat { display: flex; align-items: center; gap: 6px; }
.feat-dot {
  width: 6px; height: 6px; border-radius: 50%;
  background: var(--ribbon-accent);
}

.loading-mask {
  position: absolute;
  inset: 0;
  background: rgba(255, 255, 255, .75);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  z-index: 100;
  font-size: 14px;
  color: var(--text-2);
}
.spinner {
  width: 36px;
  height: 36px;
  border: 3px solid #eee;
  border-top-color: var(--ribbon-accent);
  border-radius: 50%;
  animation: spin .8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.status-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  height: 24px;
  padding: 0 12px;
  background: var(--chrome-bg);
  border-top: 1px solid var(--line);
  font-size: 11px;
  color: var(--text-3);
  flex-shrink: 0;
}
.status-left { display: flex; align-items: center; gap: 8px; }

/* 缩略图离屏渲染：不参与布局、不可见，避免截图时主画布闪动 */
.thumb-capture-host {
  position: fixed;
  left: -100000px;
  top: 0;
  overflow: hidden;
  visibility: hidden;
  pointer-events: none;
  z-index: -1;
}
.status-sep { color: #ccc; }
.version { color: #aaa; }
</style>
