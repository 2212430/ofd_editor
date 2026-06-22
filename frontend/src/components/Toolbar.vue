<template>
  <div class="ribbon-shell">
    <!-- 隐藏文件输入 -->
    <input ref="ofdInputRef" type="file" accept=".ofd" style="display:none" @change="handleOfdUpload" />
    <input ref="pdfInputRef" type="file" accept=".pdf" style="display:none" @change="handlePdfImport" />
    <input ref="pdfToOfdInputRef" type="file" accept=".pdf" style="display:none" @change="handlePdfToOfdFile" />
    <input ref="pdfToWordInputRef" type="file" accept=".pdf" style="display:none" @change="handlePdfToWordFile" />
    <input ref="ofdToWordInputRef" type="file" accept=".ofd" style="display:none" @change="handleOfdToWordFile" />
    <input ref="pdfToPptInputRef" type="file" accept=".pdf" style="display:none" @change="handlePdfToPptFile" />
    <input ref="ofdToPptInputRef" type="file" accept=".ofd" style="display:none" @change="handleOfdToPptFile" />
    <input ref="ofdToTextInputRef" type="file" accept=".ofd" style="display:none" @change="handleOfdToTextFile" />
    <input ref="ofdToHtmlInputRef" type="file" accept=".ofd" style="display:none" @change="handleOfdToHtmlFile" />
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
      <span v-if="store.document" class="ribbon-doc-title">
        {{ store.document.title }}<span v-if="store.hasUnsavedChanges" class="unsaved-mark"> *</span>
      </span>
    </nav>

    <!-- Ribbon 面板 -->
    <div class="ribbon-panel">
      <!-- ===== 文件 ===== -->
      <template v-if="activeTab === 'file'">
        <RibbonGroup label="打开">
          <RibbonButton label="打开OFD" :icon="FolderOpened" @click="pickOpenOfd" />
          <RibbonButton label="导入PDF" :icon="Upload" @click="pickOpenPdf" />
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
          <RibbonButton label="删除元素" :icon="Delete" :disabled="!store.canDeleteSelectedElement" @click="handleDeleteElement" />
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
          <RibbonButton label="波浪线" :active="store.currentTool === 'SQUIGGLY'" @click="store.setTool('SQUIGGLY')">
            <template #icon><span class="mark-icon squiggly">U</span></template>
          </RibbonButton>
          <RibbonButton label="删除线" :active="store.currentTool === 'STRIKEOUT'" @click="store.setTool('STRIKEOUT')">
            <template #icon><span class="mark-icon strike">S</span></template>
          </RibbonButton>
          <RibbonButton label="替换线" :active="store.currentTool === 'REPLACE'" @click="store.setTool('REPLACE')">
            <template #icon><span class="mark-icon replace">R</span></template>
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
        <RibbonGroup label="链接">
          <RibbonButton
              label="链接"
              :icon="LinkIcon"
              :active="store.currentTool === 'LINK'"
              :disabled="!store.document"
              tooltip="绘制矩形热区，跳转到页或打开网址"
              @click="store.setTool('LINK')"
          />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="图章">
          <RibbonButton
              label="图章库"
              :icon="Medal"
              :disabled="!store.document"
              @click="stampDialogVisible = true"
          />
          <RibbonButton
              label="导入图章/签名"
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
          <RibbonButton
              label="注释列表"
              :icon="Memo"
              :disabled="!store.document"
              tooltip="打开右侧注释列表面板"
              @click="store.openAnnotationListPanel()"
          />
          <RibbonButton
              label="汇总报告"
              :icon="Files"
              :disabled="!store.document || store.annotationCount === 0"
              tooltip="生成注释汇总报告（可导出 HTML / CSV / 打印）"
              @click="annotationReportVisible = true"
          />
          <RibbonButton label="全部显示" :icon="View" disabled tooltip="批量显示/隐藏即将推出" @click="comingSoon" />
        </RibbonGroup>
      </template>

      <!-- ===== 视图 ===== -->
      <template v-else-if="activeTab === 'view'">
        <RibbonGroup label="导航">
          <RibbonButton label="手型" :icon="HandIcon" :active="store.currentTool === 'HAND'" @click="store.setTool('HAND')" />
          <RibbonButton label="选择" :icon="Rank" :active="store.currentTool === 'SELECT'" @click="store.setTool('SELECT')" />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="查找">
          <RibbonButton label="全文搜索" :icon="Search" :disabled="!store.document" tooltip="搜索文档文本（Ctrl+F）" @click="store.openSearch()" />
          <RibbonButton
              label="文本选择"
              :icon="DocumentCopy"
              :disabled="!store.document"
              :active="store.textSelectMode"
              tooltip="开启后可在页面上选中并复制文本"
              @click="store.toggleTextSelectMode()"
          />
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
          <RibbonButton label="删除元素" :icon="Delete" :disabled="!store.canDeleteSelectedElement" @click="handleDeleteElement" />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="页面结构">
          <RibbonButton label="插入页面" :icon="Plus" :disabled="!store.document" @click="handleInsertPage" />
          <RibbonButton label="删除页面" :icon="Delete" :disabled="!store.document || (store.document?.pageCount ?? 0) <= 1" @click="handleDeletePage" />
          <RibbonButton label="复制页面" :icon="CopyDocument" :disabled="!store.document" @click="handleCopyPage" />
          <RibbonButton label="重排页面" :icon="Sort" :disabled="!store.document" tooltip="拖动左侧缩略图调整顺序" @click="handleReorderHint" />
          <RibbonButton
              label="顺时针转页"
              :icon="RefreshRight"
              :disabled="!store.document"
              tooltip="持久旋转当前页 90°（保存/导出后生效）"
              @click="handleRotatePage(true)"
          />
          <RibbonButton
              label="逆时针转页"
              :icon="RefreshLeft"
              :disabled="!store.document"
              tooltip="持久旋转当前页 -90°"
              @click="handleRotatePage(false)"
          />
          <RibbonButton
              label="提取页面"
              :icon="Scissor"
              :disabled="!store.document"
              tooltip="按页码范围提取为新文件"
              @click="extractDialogVisible = true"
          />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="水印">
          <RibbonButton
              label="文本水印"
              :icon="Stamp"
              :disabled="!store.document"
              :active="!!store.watermarkConfig"
              tooltip="设置全局文本水印，保存/导出时烘焙"
              @click="watermarkDialogVisible = true"
          />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="插入">
          <RibbonButton label="导入图片" :icon="Picture" :disabled="!store.document" @click="imageInputRef?.click()" />
          <RibbonButton
              label="图片裁剪"
              :icon="Crop"
              :disabled="!store.canCropSelectedImage()"
              tooltip="裁剪当前选中的图片元素"
              @click="handleOpenImageCrop"
          />
        </RibbonGroup>
      </template>

      <!-- ===== 转换 ===== -->
      <template v-else-if="activeTab === 'convert'">
        <RibbonGroup label="OFD转换">
          <RibbonButton
              label="OFD转PDF"
              :icon="Download"
              :disabled="!store.document || store.isPdfDocument"
              tooltip="将当前 OFD 导出为 PDF（矢量转换）"
              @click="handleExportPdf"
          />
          <RibbonButton
              label="OFD转Word"
              :icon="Reading"
              :disabled="store.isPdfDocument"
              tooltip="一键将 OFD 转为 Word（.docx）。已打开 OFD 时含当前编辑与批注；也可选择本地 OFD 文件"
              @click="handleOfdToWord"
          />
          <RibbonButton
              label="OFD转PPT"
              :icon="Monitor"
              :disabled="store.isPdfDocument"
              tooltip="一键将 OFD 转为 PowerPoint（.pptx）。流程：OFD→PDF→PPT；已打开 OFD 时含当前编辑与批注"
              @click="handleOfdToPpt"
          />
          <RibbonButton
              label="OFD转文本"
              :icon="DocumentCopy"
              :disabled="store.isPdfDocument"
              tooltip="提取 OFD 文字为纯文本（ofdrw TextExporter）。扫描件/路径字可能无法提取"
              @click="handleOfdToText"
          />
          <RibbonButton
              label="OFD转HTML"
              :icon="View"
              :disabled="store.isPdfDocument"
              tooltip="将 OFD 导出为 HTML 网页（ofdrw HTMLExporter，SVG 版式预览）"
              @click="handleOfdToHtml"
          />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="PDF转换">
          <RibbonButton
              label="PDF转OFD"
              :icon="Upload"
              tooltip="上传 PDF 并转为 OFD（ofdrw 矢量转换）；日常编辑 PDF 请用「文件→导入PDF」"
              @click="pdfToOfdInputRef?.click()"
          />
          <RibbonButton
              label="PDF转Word"
              :icon="Document"
              tooltip="将 PDF 转为 Word（.docx）。已打开的原生 PDF 可直接转换当前文档（含批注）；OFD 请用「OFD转Word」"
              @click="handlePdfToWord"
          />
          <RibbonButton
              label="PDF转PPT"
              :icon="Monitor"
              tooltip="将 PDF 转为 PowerPoint（.pptx）。已打开的原生 PDF 可直接转换当前文档（含批注）；OFD 请用「OFD转PPT」"
              @click="handlePdfToPpt"
          />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="合并拆分">
          <RibbonButton
              label="OFD合并"
              :icon="Files"
              tooltip="合并两个 OFD：第一个文件的页面在前，第二个在后"
              @click="mergeDialogVisible = true"
          />
          <RibbonButton
              label="PDF合并"
              :icon="Files"
              tooltip="合并两个 PDF 并先下载原生 PDF，再导入编辑器"
              @click="pdfMergeDialogVisible = true"
          />
          <RibbonButton
              label="OFD拆分"
              :icon="Scissor"
              :disabled="!canSplitOfd"
              tooltip="将当前打开的 OFD 按页码拆成两份并分别保存"
              @click="ofdSplitDialogVisible = true"
          />
          <RibbonButton
              label="PDF拆分"
              :icon="Scissor"
              tooltip="选择原生 PDF 文件，按页码拆成两份并分别保存"
              @click="pdfSplitDialogVisible = true"
          />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="导出">
          <RibbonButton
              label="导出当前页"
              :icon="PictureFilled"
              :disabled="!store.document"
              tooltip="将当前页导出为 PNG（含批注，保留当前视图旋转）"
              @click="store.exportCurrentPageImage()"
          />
        </RibbonGroup>
      </template>

      <!-- ===== 保护 ===== -->
      <template v-else-if="activeTab === 'protect'">
        <RibbonGroup label="电子签章">
          <RibbonButton
              label="国密签章"
              :icon="Medal"
              :disabled="!store.document || store.isPdfDocument"
              tooltip="为 OFD 加盖国密（GM/T 0099 SES v4）电子签章"
              @click="openSignDialog"
          />
          <RibbonButton
              label="验证签章"
              :icon="CircleCheck"
              :disabled="!store.document || store.isPdfDocument"
              tooltip="校验当前 OFD 的电子签章 / 数字签名"
              @click="handleVerifySignature"
          />
        </RibbonGroup>
        <RibbonSep />
        <RibbonGroup label="安全">
          <RibbonButton label="加密" :icon="Lock" disabled tooltip="即将推出" @click="comingSoon" />
          <RibbonButton label="权限设置" :icon="Key" disabled tooltip="即将推出" @click="comingSoon" />
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
    <OfdMergeDialog v-model="mergeDialogVisible" />
    <PdfMergeDialog v-model="pdfMergeDialogVisible" />
    <OfdSplitDialog v-model="ofdSplitDialogVisible" />
    <PdfSplitDialog v-model="pdfSplitDialogVisible" />
    <ImageCropDialog v-model="store.imageCropDialogVisible" />
    <SignSealDialog v-model="signDialogVisible" />
    <WatermarkDialog v-model="watermarkDialogVisible" />
    <ExtractPagesDialog v-model="extractDialogVisible" />
    <DefaultStampDialog v-model="stampDialogVisible" @picked="activeTab = 'comment'" />
    <AnnotationReportDialog v-model="annotationReportVisible" />
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
  Document, Reading, Sort, Picture, Files, PictureFilled, Monitor,
  Lock, Key, Stamp, Medal, QuestionFilled, Clock, Scissor,
  Search, DocumentCopy, CircleCheck, Link as LinkIcon,
} from '@element-plus/icons-vue'
import { useEditorStore } from '@/stores/editorStore'
import { buildOfdBlobFromPdf, saveDocument } from '@/composables/useDocumentFileActions'
import { confirmDiscardUnsavedChanges } from '@/composables/useUnsavedChangesGuard'
import {
  ofdApi, downloadBlob,
  pickOfdSaveTarget, writeBlobToSaveTarget,
} from '@/api/ofdApi'
import { openNativePdf } from '@/utils/openPdf'
import RibbonButton from '@/components/RibbonButton.vue'
import DocumentPropertiesDialog from '@/components/DocumentPropertiesDialog.vue'
import OfdMergeDialog from '@/components/OfdMergeDialog.vue'
import PdfMergeDialog from '@/components/PdfMergeDialog.vue'
import OfdSplitDialog from '@/components/OfdSplitDialog.vue'
import PdfSplitDialog from '@/components/PdfSplitDialog.vue'
import ImageCropDialog from '@/components/ImageCropDialog.vue'
import SignSealDialog from '@/components/SignSealDialog.vue'
import WatermarkDialog from '@/components/WatermarkDialog.vue'
import ExtractPagesDialog from '@/components/ExtractPagesDialog.vue'
import DefaultStampDialog from '@/components/DefaultStampDialog.vue'
import AnnotationReportDialog from '@/components/AnnotationReportDialog.vue'

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
const pdfToOfdInputRef = ref<HTMLInputElement>()
const pdfToWordInputRef = ref<HTMLInputElement>()
const ofdToWordInputRef = ref<HTMLInputElement>()
const pdfToPptInputRef = ref<HTMLInputElement>()
const ofdToPptInputRef = ref<HTMLInputElement>()
const ofdToTextInputRef = ref<HTMLInputElement>()
const ofdToHtmlInputRef = ref<HTMLInputElement>()
const imageInputRef = ref<HTMLInputElement>()
const stampInputRef = ref<HTMLInputElement>()
const docPropsVisible = ref(false)
const mergeDialogVisible = ref(false)
const pdfMergeDialogVisible = ref(false)
const ofdSplitDialogVisible = ref(false)
const pdfSplitDialogVisible = ref(false)
const signDialogVisible = ref(false)
const watermarkDialogVisible = ref(false)
const extractDialogVisible = ref(false)
const stampDialogVisible = ref(false)
const annotationReportVisible = ref(false)
const activeTab = ref('home')

const canSplitOfd = computed(
    () => !!store.document && !!store.fileId && (store.document.pageCount ?? 0) > 1,
)

const tabs = [
  { id: 'file', label: '文件', disabled: false },
  { id: 'home', label: '主页', disabled: false },
  { id: 'comment', label: '注释', disabled: false },
  { id: 'view', label: '视图', disabled: false },
  { id: 'edit', label: '编辑', disabled: false },
  { id: 'convert', label: '转换', disabled: false },
  { id: 'form', label: '表单', disabled: true },
  { id: 'protect', label: '保护', disabled: false },
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

function openSignDialog() {
  if (!store.document) { ElMessage.warning('请先打开 OFD 文档'); return }
  if (store.isPdfDocument) { ElMessage.warning('国密签章仅支持 OFD 文档'); return }
  signDialogVisible.value = true
}

async function handleVerifySignature() {
  if (!store.document) { ElMessage.warning('请先打开 OFD 文档'); return }
  if (store.isPdfDocument) { ElMessage.warning('验签仅支持 OFD 文档'); return }
  if (!store.currentFile) {
    ElMessage.warning('未找到原始 OFD 文件，请重新打开后再验签')
    return
  }
  store.setLoading(true, '正在验证签章…')
  try {
    const result = await ofdApi.verifySignature(store.currentFile)
    const icon = !result.signed ? 'info' : (result.valid ? 'success' : 'error')
    const detail = result.signed ? `\n\n签名个数：${result.count}` : ''
    await ElMessageBox.alert(result.message + detail, '验签结果', {
      type: icon as any,
      confirmButtonText: '知道了',
    })
  } catch (err: any) {
    ElMessage.error(err?.message ?? '验签失败')
  } finally {
    store.setLoading(false)
  }
}

function handleOpenImageCrop() {
  if (!store.openImageCropDialog()) {
    ElMessage.warning('请先选中一张可编辑的图片元素')
  }
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
      '1. 「文件」打开 OFD 或导入 PDF（Ctrl+O 打开 OFD）\n' +
      '2. 「主页」选择工具并编辑页面元素\n' +
      '3. Ctrl+Z 撤销、Ctrl+Y 或 Ctrl+Shift+Z 重做\n' +
      '4. Ctrl+S 保存、Ctrl+P 打印、Ctrl+F 搜索\n' +
      '5. Ctrl+±0 缩放，PageUp/PageDown 翻页，Esc 逐级退出\n' +
      '6. Delete 删除选中注释或元素\n' +
      '7. 「编辑 → 插入」可导入图片到当前页\n' +
      '8. 「注释 → 图章库」选择内置图章，或「导入图章/签名」上传图片，再点击页面放置\n' +
      '9. 「注释 → 汇总报告」导出全部批注统计与明细（HTML / CSV / 打印）\n' +
      '10. 「注释」添加高亮、图形等批注\n' +
      '11. 「转换」OFD 转 PDF/Word/PPT/文本/HTML、PDF 转 OFD/Word/PPT；「文件 → 打印」输出纸质或 PDF',
      '快速上手',
      { confirmButtonText: '知道了' }
  )
}

function showAbout() {
  ElMessageBox.alert(
      'OFD Studio v1.0\n开放版式文档（OFD）编辑器\n\n支持：解析 · 编辑 · 批注 · PDF 双向转换 · OFD/PDF 转 Word/PPT/文本/HTML · 打印',
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
  await store.deleteAnnotation(store.selectedAnnotationId)
  ElMessage.success('注释已删除')
}

async function pickOpenOfd() {
  ofdInputRef.value?.click()
}

async function pickOpenPdf() {
  pdfInputRef.value?.click()
}

async function handleOfdUpload(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  if (!await confirmDiscardUnsavedChanges('打开新文件')) {
    ;(e.target as HTMLInputElement).value = ''
    return
  }
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
    ElMessage.error('请选择图片格式的图章/签名文件')
    return
  }
  try {
    const dataUrl = await readFileAsDataUrl(file)
    store.setPendingStampImage(dataUrl)
    activeTab.value = 'comment'
    ElMessage.success('已选择图章/签名，请点击页面放置（可重复放置）')
  } catch (err: any) {
    ElMessage.error(err.message || '读取图章/签名失败')
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
  store.setLoading(true, '正在打开PDF...')
  try {
    await openNativePdf(file)
    ElMessage.success('PDF 已打开（原生渲染，可批注）')
  } catch (err: any) {
    ElMessage.error(err.message || 'PDF打开失败')
  } finally {
    store.setLoading(false)
    ;(e.target as HTMLInputElement).value = ''
  }
}

async function handlePdfToOfdFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!/\.pdf$/i.test(file.name)) {
    ElMessage.warning('请选择 PDF 文件')
    return
  }
  store.setLoading(true, '正在转换为 OFD（矢量转换，页数较多时请耐心等待）...')
  try {
    const blob = await ofdApi.fromPdf(file)
    const base = (file.name || 'export').replace(/\.pdf$/i, '').trim() || 'export'
    downloadBlob(blob, `${base}.ofd`)
    ElMessage.success('OFD 已开始下载')
  } catch (err: any) {
    ElMessage.error(err.message || 'PDF 转 OFD 失败')
  } finally {
    store.setLoading(false)
  }
}

/** 原生 PDF：烘焙批注后，经 ofdrw PDFConverter 转为 OFD */
async function handleSaveOfd() {
  await saveDocument()
}

async function handleSaveAs() {
  if (!store.document) return

  const target = await pickOfdSaveTarget(store.document.title)
  if (!target) return

  store.setLoading(true, '正在生成 OFD 文件...')
  try {
    const blob = store.isPdfDocument
      ? await buildOfdBlobFromPdf()
      : await ofdApi.saveOfd(store.getDocumentForSave()!)
    await writeBlobToSaveTarget(blob, target)
    store.markNewElementsPersisted()
    store.markDocumentSaved()
    ElMessage.success(`已另存为：${target.filename}`)
  } catch (err: any) {
    ElMessage.error(err.message || '另存为失败')
  } finally {
    store.setLoading(false)
  }
}

async function handleExportPdf() {
  if (!store.document) { ElMessage.warning('请先打开文件'); return }

  // 原生 PDF：把注释非破坏地烘焙回原 PDF
  if (store.isPdfDocument) {
    const filename = `${store.document.title ?? 'export'}.pdf`
    await store.exportWithAnnotations(filename)
    ElMessage.success('PDF 已开始下载')
    return
  }

  // OFD 文档：OFD → PDF
  if (!store.currentFile) { ElMessage.warning('请先打开OFD文件'); return }
  store.setLoading(true, '正在导出PDF（页数较多时请耐心等待）...')
  try {
    const blob = await ofdApi.toPdf(store.currentFile)
    const filename = `${store.document?.title ?? 'export'}.pdf`
    downloadBlob(blob, filename)
    ElMessage.success('PDF 已开始下载')
  } catch (err: any) {
    ElMessage.error(err.message || '导出失败')
  } finally {
    store.setLoading(false)
  }
}

function docxFilenameFromPdfName(name: string): string {
  const base = (name || 'export').replace(/\.pdf$/i, '').trim() || 'export'
  return `${base}.docx`
}

function docxFilenameFromOfdName(name: string): string {
  const base = (name || 'export').replace(/\.ofd$/i, '').trim() || 'export'
  return `${base}.docx`
}

async function convertOfdFileToWord(file: File) {
  store.setLoading(true, '正在转换为 Word（OFD→PDF→Word，请稍候）...')
  try {
    const blob = await ofdApi.ofdToWord(file)
    downloadBlob(blob, docxFilenameFromOfdName(file.name))
    ElMessage.success('Word 已开始下载')
  } catch (err: any) {
    ElMessage.error(err.message || 'OFD 转 Word 失败')
  } finally {
    store.setLoading(false)
  }
}

async function handleOfdToWord() {
  if (store.isPdfDocument) {
    ElMessage.warning('当前为 PDF 文档，请使用「PDF转Word」')
    return
  }
  if (store.document && !store.isPdfDocument) {
    if (store.fileId && store.getDocumentForSave()) {
      store.setLoading(true, '正在转换为 Word（OFD→PDF→Word，请稍候）...')
      try {
        const ofdBlob = await ofdApi.saveOfd(store.getDocumentForSave()!)
        const ofdName = `${store.document.title ?? 'export'}.ofd`
        const ofdFile = new File([ofdBlob], ofdName, { type: 'application/ofd' })
        const blob = await ofdApi.ofdToWord(ofdFile)
        downloadBlob(blob, docxFilenameFromOfdName(ofdName))
        ElMessage.success('Word 已开始下载')
      } catch (err: any) {
        ElMessage.error(err.message || 'OFD 转 Word 失败')
      } finally {
        store.setLoading(false)
      }
      return
    }
    if (store.currentFile) {
      await convertOfdFileToWord(store.currentFile)
      return
    }
  }
  ofdToWordInputRef.value?.click()
}

async function handleOfdToWordFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!/\.ofd$/i.test(file.name)) {
    ElMessage.warning('请选择 OFD 文件')
    return
  }
  await convertOfdFileToWord(file)
}

function pptxFilenameFromOfdName(name: string): string {
  const base = (name || 'export').replace(/\.ofd$/i, '').trim() || 'export'
  return `${base}.pptx`
}

function pptxFilenameFromPdfName(name: string): string {
  const base = (name || 'export').replace(/\.pdf$/i, '').trim() || 'export'
  return `${base}.pptx`
}

async function convertOfdFileToPpt(file: File) {
  store.setLoading(true, '正在转换为 PPT（OFD→PDF→PPT，请稍候）...')
  try {
    const blob = await ofdApi.ofdToPpt(file)
    downloadBlob(blob, pptxFilenameFromOfdName(file.name))
    ElMessage.success('PPT 已开始下载')
  } catch (err: any) {
    ElMessage.error(err.message || 'OFD 转 PPT 失败')
  } finally {
    store.setLoading(false)
  }
}

async function handleOfdToPpt() {
  if (store.isPdfDocument) {
    ElMessage.warning('当前为 PDF 文档，请使用「PDF转PPT」')
    return
  }
  if (store.document && !store.isPdfDocument) {
    if (store.fileId && store.getDocumentForSave()) {
      store.setLoading(true, '正在转换为 PPT（OFD→PDF→PPT，请稍候）...')
      try {
        const ofdBlob = await ofdApi.saveOfd(store.getDocumentForSave()!)
        const ofdName = `${store.document.title ?? 'export'}.ofd`
        const ofdFile = new File([ofdBlob], ofdName, { type: 'application/ofd' })
        const blob = await ofdApi.ofdToPpt(ofdFile)
        downloadBlob(blob, pptxFilenameFromOfdName(ofdName))
        ElMessage.success('PPT 已开始下载')
      } catch (err: any) {
        ElMessage.error(err.message || 'OFD 转 PPT 失败')
      } finally {
        store.setLoading(false)
      }
      return
    }
    if (store.currentFile) {
      await convertOfdFileToPpt(store.currentFile)
      return
    }
  }
  ofdToPptInputRef.value?.click()
}

async function handleOfdToPptFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!/\.ofd$/i.test(file.name)) {
    ElMessage.warning('请选择 OFD 文件')
    return
  }
  await convertOfdFileToPpt(file)
}

function txtFilenameFromOfdName(name: string): string {
  const base = (name || 'export').replace(/\.ofd$/i, '').trim() || 'export'
  return `${base}.txt`
}

function htmlFilenameFromOfdName(name: string): string {
  const base = (name || 'export').replace(/\.ofd$/i, '').trim() || 'export'
  return `${base}.html`
}

async function convertOfdFileToText(file: File) {
  store.setLoading(true, '正在提取文本（ofdrw TextExporter）...')
  try {
    const blob = await ofdApi.ofdToText(file)
    downloadBlob(blob, txtFilenameFromOfdName(file.name))
    ElMessage.success('文本文件已开始下载')
  } catch (err: any) {
    ElMessage.error(err.message || 'OFD 转文本失败')
  } finally {
    store.setLoading(false)
  }
}

async function convertOfdFileToHtml(file: File) {
  store.setLoading(true, '正在导出 HTML（ofdrw HTMLExporter）...')
  try {
    const blob = await ofdApi.ofdToHtml(file)
    downloadBlob(blob, htmlFilenameFromOfdName(file.name))
    ElMessage.success('HTML 文件已开始下载')
  } catch (err: any) {
    ElMessage.error(err.message || 'OFD 转 HTML 失败')
  } finally {
    store.setLoading(false)
  }
}

async function handleOfdToText() {
  if (store.isPdfDocument) {
    ElMessage.warning('当前为 PDF 文档，无法直接转 OFD 文本')
    return
  }
  if (store.document && !store.isPdfDocument) {
    if (store.fileId && store.getDocumentForSave()) {
      store.setLoading(true, '正在提取文本…')
      try {
        const ofdBlob = await ofdApi.saveOfd(store.getDocumentForSave()!)
        const ofdName = `${store.document.title ?? 'export'}.ofd`
        const ofdFile = new File([ofdBlob], ofdName, { type: 'application/ofd' })
        const blob = await ofdApi.ofdToText(ofdFile)
        downloadBlob(blob, txtFilenameFromOfdName(ofdName))
        ElMessage.success('文本文件已开始下载')
      } catch (err: any) {
        ElMessage.error(err.message || 'OFD 转文本失败')
      } finally {
        store.setLoading(false)
      }
      return
    }
    if (store.currentFile) {
      await convertOfdFileToText(store.currentFile)
      return
    }
  }
  ofdToTextInputRef.value?.click()
}

async function handleOfdToHtml() {
  if (store.isPdfDocument) {
    ElMessage.warning('当前为 PDF 文档，无法直接转 OFD HTML')
    return
  }
  if (store.document && !store.isPdfDocument) {
    if (store.fileId && store.getDocumentForSave()) {
      store.setLoading(true, '正在导出 HTML…')
      try {
        const ofdBlob = await ofdApi.saveOfd(store.getDocumentForSave()!)
        const ofdName = `${store.document.title ?? 'export'}.ofd`
        const ofdFile = new File([ofdBlob], ofdName, { type: 'application/ofd' })
        const blob = await ofdApi.ofdToHtml(ofdFile)
        downloadBlob(blob, htmlFilenameFromOfdName(ofdName))
        ElMessage.success('HTML 文件已开始下载')
      } catch (err: any) {
        ElMessage.error(err.message || 'OFD 转 HTML 失败')
      } finally {
        store.setLoading(false)
      }
      return
    }
    if (store.currentFile) {
      await convertOfdFileToHtml(store.currentFile)
      return
    }
  }
  ofdToHtmlInputRef.value?.click()
}

async function handleOfdToTextFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!/\.ofd$/i.test(file.name)) {
    ElMessage.warning('请选择 OFD 文件')
    return
  }
  await convertOfdFileToText(file)
}

async function handleOfdToHtmlFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!/\.ofd$/i.test(file.name)) {
    ElMessage.warning('请选择 OFD 文件')
    return
  }
  await convertOfdFileToHtml(file)
}

async function convertPdfFileToPpt(file: File) {
  store.setLoading(true, '正在转换为 PPT（请稍候）...')
  try {
    const blob = await ofdApi.pdfToPpt(file)
    downloadBlob(blob, pptxFilenameFromPdfName(file.name))
    ElMessage.success('PPT 已开始下载')
  } catch (err: any) {
    ElMessage.error(err.message || 'PDF 转 PPT 失败')
  } finally {
    store.setLoading(false)
  }
}

async function handlePdfToPpt() {
  if (store.isPdfDocument && store.fileId) {
    store.setLoading(true, '正在导出 PDF 并转换为 PPT…')
    try {
      const pdfBlob = await store.getAnnotatedPdfBlob()
      if (!pdfBlob) throw new Error('无法获取当前 PDF')
      const pdfName = `${store.document?.title ?? 'export'}.pdf`
      const file = new File([pdfBlob], pdfName, { type: 'application/pdf' })
      await convertPdfFileToPpt(file)
    } catch (err: any) {
      ElMessage.error(err.message || 'PDF 转 PPT 失败')
      store.setLoading(false)
    }
    return
  }
  pdfToPptInputRef.value?.click()
}

async function handlePdfToPptFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!/\.pdf$/i.test(file.name)) {
    ElMessage.warning('请选择 PDF 文件')
    return
  }
  await convertPdfFileToPpt(file)
}

async function convertPdfFileToWord(file: File) {
  store.setLoading(true, '正在转换为 Word（pdf2docx，请稍候）...')
  try {
    const blob = await ofdApi.pdfToWord(file)
    downloadBlob(blob, docxFilenameFromPdfName(file.name))
    ElMessage.success('Word 已开始下载')
  } catch (err: any) {
    ElMessage.error(err.message || 'PDF 转 Word 失败')
  } finally {
    store.setLoading(false)
  }
}

async function handlePdfToWord() {
  if (store.isPdfDocument && store.fileId) {
    store.setLoading(true, '正在导出 PDF 并转换为 Word…')
    try {
      const pdfBlob = await store.getAnnotatedPdfBlob()
      if (!pdfBlob) throw new Error('无法获取当前 PDF')
      const pdfName = `${store.document?.title ?? 'export'}.pdf`
      const file = new File([pdfBlob], pdfName, { type: 'application/pdf' })
      await convertPdfFileToWord(file)
    } catch (err: any) {
      ElMessage.error(err.message || 'PDF 转 Word 失败')
      store.setLoading(false)
    }
    return
  }
  pdfToWordInputRef.value?.click()
}

async function handlePdfToWordFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!/\.pdf$/i.test(file.name)) {
    ElMessage.warning('请选择 PDF 文件')
    return
  }
  await convertPdfFileToWord(file)
}

function handleResetElement() {
  if (!store.selectedElementId) return
  store.resetElement(store.currentPageIndex, store.selectedElementId)
  ElMessage.success('已重置到原始状态')
}

async function handleDeleteElement() {
  if (!store.canDeleteSelectedElement) return
  try {
    await ElMessageBox.confirm('确定删除选中的元素吗？删除后保存即从文档中移除。', '删除元素', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  if (store.deleteSelectedElement()) {
    ElMessage.success('元素已删除，保存后生效')
  }
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

function handleRotatePage(clockwise: boolean) {
  if (store.rotateCurrentPagePersist(clockwise)) {
    ElMessage.success(`第 ${store.currentPageIndex + 1} 页已旋转，保存或导出后生效`)
  }
}

async function handleDeletePage() {
  store.deletePage(store.currentPageIndex)
  ElMessage.success('页面已删除')
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

.unsaved-mark {
  color: #e6a23c;
  font-weight: 700;
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
.mark-icon.squiggly { color: var(--ribbon-accent); text-decoration: underline wavy; }
.mark-icon.strike { color: #e74c3c; text-decoration: line-through; }
.mark-icon.replace { color: #2980b9; text-decoration: line-through; font-style: italic; }
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
