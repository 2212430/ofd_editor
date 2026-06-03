<template>
  <div class="ribbon-shell">
    <!-- 隐藏文件输入 -->
    <input ref="ofdInputRef" type="file" accept=".ofd" style="display:none" @change="handleOfdUpload" />
    <input ref="pdfInputRef" type="file" accept=".pdf" style="display:none" @change="handlePdfImport" />
    <input ref="imageInputRef" type="file" accept="image/*" style="display:none" @change="handleImageImport" />
    <input ref="stampInputRef" type="file" accept="image/*" style="display:none" @change="handleStampImageSelect" />

    <!-- 标签栏 -->
    <nav class="ribbon-tabs">
      <div class="ribbon-brand">
        <span class="brand-icon">OFD</span>
        <span class="brand-name">OFD Studio</span>
      </div>
      <button
          v-for="tab in tabs"
          :key="tab.id"
          type="button"
          class="ribbon-tab"
          :class="{ active: activeTab === tab.id, disabled: tab.disabled }"
          :disabled="tab.disabled"
          @click="switchTab(tab)"
      >
        {{ tab.label }}
      </button>
      <div class="ribbon-tabs-spacer" />
      <span v-if="store.document" class="ribbon-doc-title">{{ store.document.title }}</span>
    </nav>

    <!-- Ribbon 面板 -->
    <div class="ribbon-panel">
      <!-- ===== 文件 ===== -->
      <template v-if="activeTab === 'file'">
        <RibbonGroup label="打开">
          <RibbonButton label="打开OFD" :icon="FolderOpened" @click="ofdInputRef?.click()" />
          <RibbonButton label="导入PDF" :icon="Upload" @click="pdfInputRef?.click()" />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="保存">
          <RibbonButton label="保存OFD" :icon="DocumentChecked" :disabled="!store.document" @click="handleSaveOfd" />
          <RibbonButton label="导出PDF" :icon="Download" :disabled="!store.document" @click="handleExportPdf" />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="输出">
          <RibbonButton label="打印" :icon="Printer" :disabled="!store.document" @click="store.printDialogVisible = true" />
          <RibbonButton label="另存为" :icon="CopyDocument" :disabled="!store.document" @click="handleSaveAs" />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="文档">
          <RibbonButton label="文档属性" :icon="InfoFilled" :disabled="!store.document" @click="showDocumentProperties" />
        </RibbonGroup>
      </template>

      <!-- ===== 主页 ===== -->
      <template v-else-if="activeTab === 'home'">
        <RibbonGroup label="工具">
          <RibbonButton label="手型" :icon="HandIcon" :active="store.currentTool === 'HAND'" @click="store.setTool('HAND')" />
          <RibbonButton label="选择" :icon="Rank" :active="store.currentTool === 'SELECT'" @click="store.setTool('SELECT')" />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="编辑">
          <RibbonButton label="撤销" :icon="RefreshLeft" :disabled="!store.canUndo" @click="store.undo()" />
          <RibbonButton label="重做" :icon="RefreshRight" :disabled="!store.canRedo" @click="store.redo()" />
          <RibbonButton label="重置元素" :icon="RefreshLeft" :disabled="!store.selectedElement?.isDirty" @click="handleResetElement" />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="缩放">
          <RibbonButton label="缩小" :icon="ZoomOut" @click="store.setScale(store.scale - 0.25)" />
          <button type="button" class="ribbon-scale-display" @click="store.setScale(1)">
            {{ Math.round(store.scale * 100) }}%
          </button>
          <RibbonButton label="放大" :icon="ZoomIn" @click="store.setScale(store.scale + 0.25)" />
          <RibbonButton label="实际大小" :icon="FullScreen" @click="store.setScale(1)" />
          <RibbonButton label="适应宽度" :icon="Expand" :disabled="!store.document" @click="handleFitWidth" />
          <RibbonButton label="适应页面" :icon="Crop" :disabled="!store.document" @click="handleFitPage" />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="页面">
          <RibbonButton label="插入页面" :icon="Plus" :disabled="!store.document" @click="handleInsertPage" />
          <RibbonButton label="复制页面" :icon="CopyDocument" :disabled="!store.document" @click="handleCopyPage" />
          <RibbonButton label="删除页面" :icon="Delete" :disabled="!store.document || (store.document?.pageCount ?? 0) <= 1" @click="handleDeletePage" />
        </RibbonGroup>
      </template>

      <!-- ===== 注释 ===== -->
      <template v-else-if="activeTab === 'comment'">
        <RibbonGroup label="模式">
          <RibbonButton label="选择" :icon="Pointer" :active="store.currentTool === 'SELECT'" @click="store.setTool('SELECT')" />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="文字标注">
          <RibbonButton label="高亮" :active="store.currentTool === 'HIGHLIGHT'" @click="store.setTool('HIGHLIGHT')">
            <template #icon><span class="mark-icon highlight">A</span></template>
          </RibbonButton>
          <RibbonButton label="下划线" :active="store.currentTool === 'UNDERLINE'" @click="store.setTool('UNDERLINE')">
            <template #icon><span class="mark-icon underline">U</span></template>
          </RibbonButton>
          <RibbonButton label="删除线" :active="store.currentTool === 'STRIKEOUT'" @click="store.setTool('STRIKEOUT')">
            <template #icon><span class="mark-icon strike">S</span></template>
          </RibbonButton>
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="图形">
          <RibbonButton label="矩形" :icon="ScaleToOriginal" :active="store.currentTool === 'RECTANGLE'" @click="store.setTool('RECTANGLE')" />
          <RibbonButton label="椭圆" :active="store.currentTool === 'CIRCLE'" @click="store.setTool('CIRCLE')">
            <template #icon><span class="shape-icon oval">○</span></template>
          </RibbonButton>
          <RibbonButton label="箭头" :icon="Right" :active="store.currentTool === 'ARROW'" @click="store.setTool('ARROW')" />
          <RibbonButton label="手绘" :icon="EditPen" :active="store.currentTool === 'FREEHAND'" @click="store.setTool('FREEHAND')" />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="文本">
          <RibbonButton label="文本框" :icon="ChatLineSquare" :active="store.currentTool === 'TEXTBOX'" @click="store.setTool('TEXTBOX')" />
          <RibbonButton label="便利贴" :icon="Memo" :active="store.currentTool === 'STICKYNOTE'" @click="store.setTool('STICKYNOTE')" />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="图章">
          <RibbonButton
              label="导入图章"
              :icon="Stamp"
              :active="store.currentTool === 'STAMP'"
              :disabled="!store.document"
              @click="stampInputRef?.click()"
          />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="样式">
          <div class="ribbon-style-row">
            <span class="style-label">颜色</span>
            <el-color-picker v-model="annotationColor" size="small" :predefine="predefineColors" />
          </div>
          <div class="ribbon-style-row">
            <span class="style-label">线宽</span>
            <el-select v-model="annotationLineWidth" size="small" style="width:72px">
              <el-option :value="1" label="细" />
              <el-option :value="2" label="中" />
              <el-option :value="3" label="粗" />
              <el-option :value="5" label="特粗" />
            </el-select>
          </div>
          <div class="ribbon-style-row">
            <span class="style-label">透明</span>
            <el-select v-model="annotationOpacity" size="small" style="width:72px">
              <el-option :value="0.2" label="20%" />
              <el-option :value="0.4" label="40%" />
              <el-option :value="0.6" label="60%" />
              <el-option :value="0.8" label="80%" />
              <el-option :value="1.0" label="100%" />
            </el-select>
          </div>
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="管理">
          <RibbonButton label="删除注释" :icon="Delete" :disabled="!store.selectedAnnotationId" @click="handleDeleteAnnotation" />
          <RibbonButton label="显示/隐藏" :icon="View" disabled tooltip="即将推出" @click="comingSoon" />
        </RibbonGroup>
      </template>

      <!-- ===== 视图 ===== -->
      <template v-else-if="activeTab === 'view'">
        <RibbonGroup label="导航">
          <RibbonButton label="手型" :icon="HandIcon" :active="store.currentTool === 'HAND'" @click="store.setTool('HAND')" />
          <RibbonButton label="选择" :icon="Rank" :active="store.currentTool === 'SELECT'" @click="store.setTool('SELECT')" />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="缩放">
          <RibbonButton label="放大" :icon="ZoomIn" @click="store.setScale(store.scale + 0.25)" />
          <RibbonButton label="缩小" :icon="ZoomOut" @click="store.setScale(store.scale - 0.25)" />
          <RibbonButton label="实际大小" :icon="FullScreen" @click="store.setScale(1)" />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="页面适应">
          <RibbonButton label="适应宽度" :icon="Expand" :disabled="!store.document" @click="handleFitWidth" />
          <RibbonButton label="适应页面" :icon="Crop" :disabled="!store.document" @click="handleFitPage" />
          <RibbonButton
              label="单页"
              :icon="Document"
              :disabled="!store.document"
              :active="store.pageViewMode === 'single'"
              @click="store.setPageViewMode('single')"
          />
          <RibbonButton
              label="连续页"
              :icon="Reading"
              :disabled="!store.document"
              :active="store.pageViewMode === 'continuous'"
              @click="store.setPageViewMode('continuous')"
          />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="旋转">
          <RibbonButton
              label="顺时针"
              :icon="RefreshRight"
              :disabled="!store.document"
              tooltip="视图顺时针旋转 90°"
              @click="store.rotateViewClockwise()"
          />
          <RibbonButton
              label="逆时针"
              :icon="RefreshLeft"
              :disabled="!store.document"
              tooltip="视图逆时针旋转 90°"
              @click="store.rotateViewCounterClockwise()"
          />
        </RibbonGroup>
      </template>

      <!-- ===== 编辑 ===== -->
      <template v-else-if="activeTab === 'edit'">
        <RibbonGroup label="对象">
          <RibbonButton label="选择" :icon="Pointer" :active="store.currentTool === 'SELECT'" @click="store.setTool('SELECT')" />
          <RibbonButton label="重置元素" :icon="RefreshLeft" :disabled="!store.selectedElement?.isDirty" @click="handleResetElement" />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="页面结构">
          <RibbonButton label="插入页面" :icon="Plus" :disabled="!store.document" @click="handleInsertPage" />
          <RibbonButton label="删除页面" :icon="Delete" :disabled="!store.document || (store.document?.pageCount ?? 0) <= 1" @click="handleDeletePage" />
          <RibbonButton label="复制页面" :icon="CopyDocument" :disabled="!store.document" @click="handleCopyPage" />
          <RibbonButton label="重排页面" :icon="Sort" :disabled="!store.document" tooltip="拖动左侧缩略图调整顺序" @click="handleReorderHint" />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="插入">
          <RibbonButton label="导入图片" :icon="Picture" :disabled="!store.document" @click="imageInputRef?.click()" />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="高级">
          <RibbonButton label="图片裁剪" :icon="Crop" disabled tooltip="即将推出" @click="comingSoon" />
          <RibbonButton label="路径编辑" :icon="EditPen" disabled tooltip="即将推出" @click="comingSoon" />
        </RibbonGroup>
      </template>

      <!-- ===== 转换 ===== -->
      <template v-else-if="activeTab === 'convert'">
        <RibbonGroup label="格式转换">
          <RibbonButton label="PDF转OFD" :icon="Upload" @click="pdfInputRef?.click()" />
          <RibbonButton label="OFD转PDF" :icon="Download" :disabled="!store.document" @click="handleExportPdf" />
          <RibbonButton label="批量转换" :icon="Files" disabled tooltip="即将推出" @click="comingSoon" />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="导出">
          <RibbonButton label="导出图片" :icon="PictureFilled" disabled tooltip="即将推出" @click="comingSoon" />
        </RibbonGroup>
      </template>

      <!-- ===== 保护 ===== -->
      <template v-else-if="activeTab === 'protect'">
        <RibbonGroup label="安全">
          <RibbonButton label="加密" :icon="Lock" disabled tooltip="即将推出" @click="comingSoon" />
          <RibbonButton label="权限设置" :icon="Key" disabled tooltip="即将推出" @click="comingSoon" />
          <RibbonButton label="数字签名" :icon="Stamp" disabled tooltip="即将推出" @click="comingSoon" />
          <RibbonButton label="国密签章" :icon="Medal" disabled tooltip="即将推出" @click="comingSoon" />
        </RibbonGroup>
      </template>

      <!-- ===== 帮助 ===== -->
      <template v-else-if="activeTab === 'help'">
        <RibbonGroup label="帮助">
          <RibbonButton label="使用说明" :icon="QuestionFilled" @click="showHelp" />
          <RibbonButton label="关于" :icon="InfoFilled" @click="showAbout" />
        </RibbonGroup>
      </template>

      <!-- 占位 / 未开放标签 -->
      <template v-else>
        <div class="ribbon-placeholder">
          <el-icon><Clock /></el-icon>
          <span>「{{ currentTabLabel }}」功能即将推出</span>
        </div>
      </template>
    </div>

    <DocumentPropertiesDialog v-model="docPropsVisible" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, h, defineComponent } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Upload, Download, RefreshLeft, RefreshRight,
  ZoomIn, ZoomOut, Plus, Delete, EditPen, Right,
  ScaleToOriginal, ChatLineSquare, Memo, Pointer,
  Printer, FolderOpened, DocumentChecked, CopyDocument,
  InfoFilled, Rank, FullScreen, View, Expand, Crop,
  Document, Reading, Sort, Picture, Files, PictureFilled,
  Lock, Key, Stamp, Medal, QuestionFilled, Clock,
} from '@element-plus/icons-vue'
import { useEditorStore } from '@/stores/editorStore'
import {
  ofdApi, downloadBlob, promptDownloadBlob,
  pickOfdSaveTarget, writeBlobToSaveTarget,
} from '@/api/ofdApi'
import RibbonButton from '@/components/RibbonButton.vue'
import DocumentPropertiesDialog from '@/components/DocumentPropertiesDialog.vue'

const HandIcon = defineComponent({
  name: 'HandIcon',
  render() {
    return h('svg', {
      viewBox: '0 0 24 24',
      width: '1em',
      height: '1em',
      fill: 'none',
      stroke: 'currentColor',
      'stroke-width': '1.8',
      'stroke-linecap': 'round',
      'stroke-linejoin': 'round',
    }, [
      h('path', { d: 'M18 11V6a2 2 0 0 0-4 0v5' }),
      h('path', { d: 'M14 10V4a2 2 0 0 0-4 0v6' }),
      h('path', { d: 'M10 10V5a2 2 0 0 0-4 0v8a8 8 0 0 0 16 0v-5a2 2 0 0 0-4 0v2' }),
    ])
  },
})

const store = useEditorStore()
const ofdInputRef = ref<HTMLInputElement>()
const pdfInputRef = ref<HTMLInputElement>()
const imageInputRef = ref<HTMLInputElement>()
const stampInputRef = ref<HTMLInputElement>()
const docPropsVisible = ref(false)
const activeTab = ref('home')

const tabs = [
  { id: 'file', label: '文件', disabled: false },
  { id: 'home', label: '主页', disabled: false },
  { id: 'comment', label: '注释', disabled: false },
  { id: 'view', label: '视图', disabled: false },
  { id: 'edit', label: '编辑', disabled: false },
  { id: 'convert', label: '转换', disabled: false },
  { id: 'form', label: '表单', disabled: true },
  { id: 'protect', label: '保护', disabled: false },
  { id: 'share', label: '共享', disabled: true },
  { id: 'cloud', label: '云服务', disabled: true },
  { id: 'help', label: '帮助', disabled: false },
]

const currentTabLabel = computed(() => tabs.find(t => t.id === activeTab.value)?.label ?? '')

function switchTab(tab: typeof tabs[0]) {
  if (tab.disabled) {
    ElMessage.info(`「${tab.label}」功能即将推出`)
    return
  }
  activeTab.value = tab.id
}

function comingSoon() {
  ElMessage.info('该功能即将推出，敬请期待')
}

function handleFitWidth() {
  if (!store.fitToWidth()) {
    ElMessage.warning('无法适应宽度，请先打开文档')
  }
}

function handleFitPage() {
  if (!store.fitToPage()) {
    ElMessage.warning('无法适应页面，请先打开文档')
  }
}

function showDocumentProperties() {
  if (!store.document) {
    ElMessage.warning('请先打开文档')
    return
  }
  docPropsVisible.value = true
}

function showHelp() {
  ElMessageBox.alert(
      '1. 「文件」打开 OFD 或导入 PDF\n' +
      '2. 「主页」选择工具并编辑页面元素\n' +
      '3. 「编辑 → 插入」可导入图片到当前页\n' +
      '4. 「注释 → 导入图章」选择图片后点击页面放置图章\n' +
      '5. 「注释」添加高亮、图形等批注\n' +
      '6. 「转换」导出 PDF；「文件 → 打印」输出纸质或 PDF',
      '快速上手',
      { confirmButtonText: '知道了' }
  )
}

function showAbout() {
  ElMessageBox.alert(
      'OFD Studio v1.0\n开放版式文档（OFD）编辑器\n\n支持：解析 · 编辑 · 批注 · PDF 双向转换 · 打印',
      '关于 OFD Studio',
      { confirmButtonText: '确定' }
  )
}

// Ribbon 子组件（轻量内联）
const RibbonGroup = defineComponent({
  name: 'RibbonGroup',
  props: { label: { type: String, required: true } },
  setup(props, { slots }) {
    return () => h('div', { class: 'ribbon-group' }, [
      h('div', { class: 'ribbon-group-items' }, slots.default?.()),
      h('span', { class: 'ribbon-group-label' }, props.label),
    ])
  },
})

const RibbonSep = defineComponent({
  name: 'RibbonSep',
  setup() {
    return () => h('div', { class: 'ribbon-sep' })
  },
})

const annotationColor = computed({
  get: () => store.annotationColor,
  set: (v: string) => store.setAnnotationColor(v),
})
const annotationLineWidth = computed({
  get: () => store.annotationLineWidth,
  set: (v: number) => store.setAnnotationLineWidth(v),
})
const annotationOpacity = computed({
  get: () => store.annotationOpacity,
  set: (v: number) => store.setAnnotationOpacity(v),
})

const predefineColors = [
  '#FFFF00', '#FF6B6B', '#51CF66', '#339AF0',
  '#FF922B', '#CC5DE8', '#000000', '#FFFFFF',
]

async function handleDeleteAnnotation() {
  if (!store.selectedAnnotationId) return
  try {
    await ElMessageBox.confirm('确定删除该注释吗？', '确认删除', { type: 'warning' })
    await store.deleteAnnotation(store.selectedAnnotationId)
    ElMessage.success('注释已删除')
  } catch { /* 取消 */ }
}

async function handleOfdUpload(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  store.setLoading(true, '正在解析OFD文件...')
  try {
    const doc = await ofdApi.parseOfd(file)
    store.setDocument(doc)
    store.setCurrentFile(file, 'ofd')
    await store.loadAllAnnotations()
    ElMessage.success(`解析成功：${doc.title}，共${doc.pageCount}页`)
  } catch (err: any) {
    ElMessage.error(err.message || '解析失败')
  } finally {
    store.setLoading(false)
    ;(e.target as HTMLInputElement).value = ''
  }
}

async function handleStampImageSelect(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  if (!store.document) {
    ElMessage.warning('请先打开 OFD 文件')
    return
  }
  if (!file.type.startsWith('image/')) {
    ElMessage.error('请选择图片格式的图章文件')
    return
  }
  try {
    const dataUrl = await readFileAsDataUrl(file)
    store.setPendingStampImage(dataUrl)
    activeTab.value = 'comment'
    ElMessage.success('已选择图章，请点击页面放置（可重复放置）')
  } catch (err: any) {
    ElMessage.error(err.message || '读取图章失败')
  } finally {
    ;(e.target as HTMLInputElement).value = ''
  }
}

function readFileAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(new Error('读取文件失败'))
    reader.readAsDataURL(file)
  })
}

async function handleImageImport(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  if (!store.document) {
    ElMessage.warning('请先打开 OFD 文件')
    return
  }
  store.setLoading(true, '正在导入图片...')
  try {
    await store.importImageToPage(store.currentPageIndex, file)
    ElMessage.success('图片已添加到当前页')
  } catch (err: any) {
    ElMessage.error(err.message || '导入图片失败')
  } finally {
    store.setLoading(false)
    ;(e.target as HTMLInputElement).value = ''
  }
}

async function handlePdfImport(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  store.setLoading(true, '正在转换PDF...')
  try {
    const blob = await ofdApi.fromPdf(file)
    const ofdFile = new File([blob], file.name.replace('.pdf', '.ofd'))
    const doc = await ofdApi.parseOfd(ofdFile)
    store.setDocument(doc)
    store.setCurrentFile(ofdFile, 'pdf')
    await store.loadAllAnnotations()
    ElMessage.success('PDF转换成功！')
  } catch (err: any) {
    ElMessage.error(err.message || 'PDF转换失败')
  } finally {
    store.setLoading(false)
    ;(e.target as HTMLInputElement).value = ''
  }
}

async function handleSaveOfd() {
  if (!store.document) return
  store.setLoading(true, '正在保存...')
  try {
    const blob = await ofdApi.saveOfd(store.getDocumentForSave()!)
    downloadBlob(blob, `${store.document.title}.ofd`)
    store.markNewElementsPersisted()
    ElMessage.success('保存成功！')
  } catch (err: any) {
    ElMessage.error(err.message || '保存失败')
  } finally {
    store.setLoading(false)
  }
}

async function handleSaveAs() {
  if (!store.document) return

  const target = await pickOfdSaveTarget(store.document.title)
  if (!target) return

  store.setLoading(true, '正在生成 OFD 文件...')
  try {
    const blob = await ofdApi.saveOfd(store.getDocumentForSave()!)
    await writeBlobToSaveTarget(blob, target)
    store.markNewElementsPersisted()
    ElMessage.success(`已另存为：${target.filename}`)
  } catch (err: any) {
    ElMessage.error(err.message || '另存为失败')
  } finally {
    store.setLoading(false)
  }
}

async function handleExportPdf() {
  if (!store.currentFile) { ElMessage.warning('请先打开OFD文件'); return }
  store.setLoading(true, '正在导出PDF（页数较多时请耐心等待）...')
  try {
    const blob = await ofdApi.toPdf(store.currentFile)
    const filename = `${store.document?.title ?? 'export'}.pdf`
    const saved = await promptDownloadBlob(blob, filename)
    if (saved) ElMessage.success('PDF 已开始下载')
  } catch (err: any) {
    ElMessage.error(err.message || '导出失败')
  } finally {
    store.setLoading(false)
  }
}

function handleResetElement() {
  if (!store.selectedElementId) return
  store.resetElement(store.currentPageIndex, store.selectedElementId)
  ElMessage.success('已重置到原始状态')
}

function handleInsertPage() {
  store.insertPage(store.currentPageIndex + 1)
  ElMessage.success(`已在第 ${store.currentPageIndex + 1} 页后插入空白页`)
}

async function handleCopyPage() {
  if (!store.document) return
  const src = store.currentPageIndex
  try {
    const newIndex = await store.copyPage(src)
    if (newIndex !== undefined) {
      ElMessage.success(`已复制第 ${src + 1} 页为第 ${newIndex + 1} 页`)
    }
  } catch (err: any) {
    ElMessage.error(err?.message || '复制页面失败')
  }
}

function handleReorderHint() {
  ElMessage.info('请在左侧页面列表中拖动缩略图调整页面顺序')
}

async function handleDeletePage() {
  try {
    await ElMessageBox.confirm(`确定删除第${store.currentPageIndex + 1}页吗？`, '确认删除', { type: 'warning' })
    store.deletePage(store.currentPageIndex)
    ElMessage.success('页面已删除')
  } catch { /* 取消 */ }
}
</script>

<style scoped>
.ribbon-shell {
  flex-shrink: 0;
  background: var(--ribbon-bg);
  border-bottom: 1px solid var(--line);
  z-index: 10;
}

/* ---- 标签栏 ---- */
.ribbon-tabs {
  display: flex;
  align-items: stretch;
  height: 36px;
  padding: 0 8px 0 0;
  background: var(--ribbon-tab-bar-bg);
  border-bottom: 1px solid var(--line);
  overflow-x: auto;
}
.ribbon-tabs::-webkit-scrollbar { height: 4px; }

.ribbon-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 16px 0 12px;
  margin-right: 4px;
  flex-shrink: 0;
}
.brand-icon {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 6px;
  background: linear-gradient(145deg, var(--ribbon-accent), var(--ribbon-accent-dark));
  color: #fff;
  font-size: 10px;
  font-weight: 800;
}
.brand-name {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-1);
  white-space: nowrap;
}

.ribbon-tab {
  padding: 0 14px;
  border: none;
  background: transparent;
  font-size: 13px;
  color: var(--text-2);
  cursor: pointer;
  white-space: nowrap;
  border-radius: 4px 4px 0 0;
  margin-top: 4px;
  transition: background .12s, color .12s;
}
.ribbon-tab:hover:not(.disabled):not(.active) {
  background: rgba(0, 0, 0, .04);
  color: var(--text-1);
}
.ribbon-tab.active {
  background: var(--ribbon-accent);
  color: #fff;
  font-weight: 600;
}
.ribbon-tab.disabled {
  opacity: .45;
  cursor: not-allowed;
}

.ribbon-tabs-spacer { flex: 1; min-width: 12px; }
.ribbon-doc-title {
  align-self: center;
  max-width: 280px;
  padding: 0 12px;
  font-size: 12px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---- Ribbon 面板 ---- */
.ribbon-panel {
  display: flex;
  align-items: stretch;
  min-height: 88px;
  padding: 6px 12px 4px;
  overflow-x: auto;
  background: var(--ribbon-panel-bg);
}
.ribbon-panel::-webkit-scrollbar { height: 6px; }

:deep(.ribbon-group) {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 0 6px;
  flex-shrink: 0;
}
:deep(.ribbon-group-items) {
  display: flex;
  align-items: flex-start;
  gap: 2px;
  flex: 1;
  padding-bottom: 2px;
}
:deep(.ribbon-group-label) {
  font-size: 10px;
  color: var(--text-3);
  text-align: center;
  padding: 2px 0 0;
  white-space: nowrap;
}

:deep(.ribbon-sep) {
  width: 1px;
  align-self: stretch;
  margin: 4px 6px;
  background: var(--line-strong);
  flex-shrink: 0;
}

.ribbon-scale-display {
  min-width: 52px;
  height: 62px;
  border: 1px solid var(--line);
  border-radius: 4px;
  background: #fff;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
  cursor: pointer;
  flex-shrink: 0;
}
.ribbon-scale-display:hover {
  border-color: var(--ribbon-accent);
  color: var(--ribbon-accent);
}

.ribbon-style-row {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 4px 6px;
  min-width: 72px;
}
.style-label { font-size: 10px; color: var(--text-3); }

.mark-icon, .shape-icon {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  font-size: 14px;
  font-weight: 700;
  border-radius: 3px;
}
.mark-icon.highlight { background: #ffe566; color: #333; }
.mark-icon.underline { color: var(--ribbon-accent); text-decoration: underline; }
.mark-icon.strike { color: #e74c3c; text-decoration: line-through; }
.shape-icon.oval { font-size: 18px; color: var(--text-2); }

.ribbon-placeholder {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 24px 32px;
  color: var(--text-3);
  font-size: 14px;
}
.ribbon-placeholder .el-icon { font-size: 22px; }
</style>
