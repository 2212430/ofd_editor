<template>
  <div class="toolbar-wrapper">

    <!-- ── 第一行：文件 / 撤销 / 缩放 / 页面操作 ── -->
    <div class="toolbar toolbar-row1">
      <input ref="ofdInputRef" type="file" accept=".ofd"
             style="display:none" @change="handleOfdUpload" />
      <input ref="pdfInputRef" type="file" accept=".pdf"
             style="display:none" @change="handlePdfImport" />

      <!-- 文件操作 -->
      <el-button-group>
        <el-button :icon="Upload" @click="ofdInputRef?.click()">打开OFD</el-button>
        <el-button :icon="Upload" @click="pdfInputRef?.click()">导入PDF</el-button>
        <el-button :icon="Download" :disabled="!store.document" @click="handleSaveOfd">保存OFD</el-button>
        <el-button :icon="Download" :disabled="!store.document" @click="handleExportPdf">导出PDF</el-button>
      </el-button-group>

      <el-divider direction="vertical" />

      <!-- 撤销/重做 -->
      <el-button-group>
        <el-button :icon="RefreshLeft" :disabled="!store.canUndo" @click="store.undo()">撤销</el-button>
        <el-button :icon="RefreshRight" :disabled="!store.canRedo" @click="store.redo()">重做</el-button>
      </el-button-group>

      <el-divider direction="vertical" />

      <!-- 缩放 -->
      <el-button-group>
        <el-button :icon="ZoomOut" @click="store.setScale(store.scale - 0.25)" />
        <el-button style="width:70px;cursor:default">{{ Math.round(store.scale * 100) }}%</el-button>
        <el-button :icon="ZoomIn"  @click="store.setScale(store.scale + 0.25)" />
      </el-button-group>

      <el-divider direction="vertical" />

      <!-- 重置元素 -->
      <el-button :icon="RefreshLeft" :disabled="!store.selectedElement?.isDirty" @click="handleResetElement">
        重置元素
      </el-button>

      <el-divider direction="vertical" />

      <!-- 页面操作 -->
      <el-button-group>
        <el-button :icon="Plus" @click="handleInsertPage">插入页面</el-button>
        <el-button :icon="Delete" type="danger" plain
                   :disabled="!store.document || store.document.pageCount <= 1"
                   @click="handleDeletePage">
          删除页面
        </el-button>
      </el-button-group>
    </div>

    <!-- ── 第二行：注释工具 ── -->
    <div class="toolbar toolbar-row2">

      <!-- SELECT -->
      <el-tooltip content="选择模式（退出注释）" placement="bottom">
        <el-button
            :type="store.currentTool === 'SELECT' ? 'primary' : 'default'"
            :icon="Pointer"
            @click="store.setTool('SELECT')"
        >选择</el-button>
      </el-tooltip>

      <el-divider direction="vertical" />

      <!-- 文字标注组 -->
      <span class="group-label">文字标注</span>

      <el-tooltip content="高亮" placement="bottom">
        <el-button
            :class="{ 'is-active-tool': store.currentTool === 'HIGHLIGHT' }"
            @click="store.setTool('HIGHLIGHT')"
        >
          <span class="btn-icon highlight-icon">高亮</span>
        </el-button>
      </el-tooltip>

      <el-tooltip content="下划线" placement="bottom">
        <el-button
            :class="{ 'is-active-tool': store.currentTool === 'UNDERLINE' }"
            @click="store.setTool('UNDERLINE')"
        >
          <span class="btn-icon underline-icon">下划线</span>
        </el-button>
      </el-tooltip>

      <el-tooltip content="删除线" placement="bottom">
        <el-button
            :class="{ 'is-active-tool': store.currentTool === 'STRIKEOUT' }"
            @click="store.setTool('STRIKEOUT')"
        >
          <span class="btn-icon strikeout-icon">删除线</span>
        </el-button>
      </el-tooltip>

      <el-divider direction="vertical" />

      <!-- 图形标注组 -->
      <span class="group-label">图形</span>

      <el-tooltip content="矩形" placement="bottom">
        <el-button
            :class="{ 'is-active-tool': store.currentTool === 'RECTANGLE' }"
            :icon="ScaleToOriginal"
            @click="store.setTool('RECTANGLE')"
        >矩形</el-button>
      </el-tooltip>

      <el-tooltip content="椭圆" placement="bottom">
        <el-button
            :class="{ 'is-active-tool': store.currentTool === 'CIRCLE' }"
            @click="store.setTool('CIRCLE')"
        >
          <span class="btn-icon">椭圆</span>
        </el-button>
      </el-tooltip>

      <el-tooltip content="箭头" placement="bottom">
        <el-button
            :class="{ 'is-active-tool': store.currentTool === 'ARROW' }"
            :icon="Right"
            @click="store.setTool('ARROW')"
        >箭头</el-button>
      </el-tooltip>

      <el-tooltip content="手绘" placement="bottom">
        <el-button
            :class="{ 'is-active-tool': store.currentTool === 'FREEHAND' }"
            :icon="Edit"
            @click="store.setTool('FREEHAND')"
        >手绘</el-button>
      </el-tooltip>

      <el-divider direction="vertical" />

      <!-- 文本注释组 -->
      <span class="group-label">文本</span>

      <el-tooltip content="文本框" placement="bottom">
        <el-button
            :class="{ 'is-active-tool': store.currentTool === 'TEXTBOX' }"
            :icon="ChatLineSquare"
            @click="store.setTool('TEXTBOX')"
        >文本框</el-button>
      </el-tooltip>

      <el-tooltip content="便利贴" placement="bottom">
        <el-button
            :class="{ 'is-active-tool': store.currentTool === 'STICKYNOTE' }"
            :icon="Memo"
            @click="store.setTool('STICKYNOTE')"
        >便利贴</el-button>
      </el-tooltip>

      <el-divider direction="vertical" />

      <!-- 样式设置 -->
      <span class="group-label">样式</span>

      <el-tooltip content="注释颜色" placement="bottom">
        <div class="inline-item">
          <span class="item-label">颜色</span>
          <el-color-picker
              v-model="annotationColor"
              size="small"
              :predefine="predefineColors"
          />
        </div>
      </el-tooltip>

      <el-tooltip content="线条粗细" placement="bottom">
        <div class="inline-item">
          <span class="item-label">线宽</span>
          <el-select v-model="annotationLineWidth" size="small" style="width:80px">
            <el-option :value="1" label="细 1" />
            <el-option :value="2" label="中 2" />
            <el-option :value="3" label="粗 3" />
            <el-option :value="5" label="特粗 5" />
          </el-select>
        </div>
      </el-tooltip>

      <el-tooltip content="透明度" placement="bottom">
        <div class="inline-item">
          <span class="item-label">透明度</span>
          <el-select v-model="annotationOpacity" size="small" style="width:76px">
            <el-option :value="0.2" label="20%" />
            <el-option :value="0.4" label="40%" />
            <el-option :value="0.6" label="60%" />
            <el-option :value="0.8" label="80%" />
            <el-option :value="1.0" label="100%" />
          </el-select>
        </div>
      </el-tooltip>

      <el-divider direction="vertical" />

      <!-- 删除选中注释 -->
      <el-tooltip content="删除选中注释" placement="bottom">
        <el-button
            type="danger" plain :icon="Delete"
            :disabled="!store.selectedAnnotationId"
            @click="handleDeleteAnnotation"
        >删除注释</el-button>
      </el-tooltip>

    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Upload, Download, RefreshLeft, RefreshRight,
  ZoomIn, ZoomOut, Plus, Delete,
  Edit, Right, ScaleToOriginal,
  ChatLineSquare, Memo, Pointer,
} from '@element-plus/icons-vue'
import { useEditorStore } from '@/stores/editorStore'
import { ofdApi, downloadBlob, promptDownloadBlob } from '@/api/ofdApi'

const store       = useEditorStore()
const ofdInputRef = ref<HTMLInputElement>()
const pdfInputRef = ref<HTMLInputElement>()

// ── 注释样式（双向绑定 store）──
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
  '#FFFF00', '#FF6B6B', '#51CF66',
  '#339AF0', '#FF922B', '#CC5DE8',
  '#000000', '#FFFFFF',
]

// ── 删除注释 ──
async function handleDeleteAnnotation() {
  if (!store.selectedAnnotationId) return
  try {
    await ElMessageBox.confirm('确定删除该注释吗？', '确认删除', { type: 'warning' })
    await store.deleteAnnotation(store.selectedAnnotationId)
    ElMessage.success('注释已删除')
  } catch { /* 取消 */ }
}

// ── 文件操作 ──
async function handleOfdUpload(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  store.setLoading(true, '正在解析OFD文件...')
  try {
    const doc = await ofdApi.parseOfd(file)
    store.setDocument(doc)
    store.setCurrentFile(file)
    await store.loadAllAnnotations()
    ElMessage.success(`解析成功：${doc.title}，共${doc.pageCount}页`)
  } catch (err: any) {
    ElMessage.error(err.message || '解析失败')
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
    const blob    = await ofdApi.fromPdf(file)
    const ofdFile = new File([blob], file.name.replace('.pdf', '.ofd'))
    const doc     = await ofdApi.parseOfd(ofdFile)
    store.setDocument(doc)
    store.setCurrentFile(ofdFile)
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
    ElMessage.success('保存成功！')
  } catch (err: any) {
    ElMessage.error(err.message || '保存失败')
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
    if (saved) {
      ElMessage.success('PDF 已开始下载，请在浏览器下载栏或「下载」文件夹中查看')
    } else {
      ElMessage.info('已取消下载；可再次点击「导出PDF」重新生成')
    }
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
  ElMessage.success(`已在第${store.currentPageIndex + 1}页后插入空白页`)
}

async function handleDeletePage() {
  try {
    await ElMessageBox.confirm(
        `确定删除第${store.currentPageIndex + 1}页吗？`, '确认删除', { type: 'warning' }
    )
    store.deletePage(store.currentPageIndex)
    ElMessage.success('页面已删除')
  } catch { /* 取消 */ }
}
</script>

<style scoped>
/* 外层容器：两行堆叠 */
.toolbar-wrapper {
  display: flex;
  flex-direction: column;
  border-bottom: 1px solid #e4e7ed;
  background: #f5f7fa;
  flex-shrink: 0;          /* 不被 flex 压缩 */
}

/* 每行公共样式 */
.toolbar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 16px;
  flex-wrap: nowrap;       /* 不换行，保证在同一行 */
  overflow-x: auto;        /* 真的太窄时允许横向滚动 */
}

/* 第一行底部分隔线 */
.toolbar-row1 {
  border-bottom: 1px solid #ececec;
}

/* 第二行注释工具栏底色稍深以区分 */
.toolbar-row2 {
  background: #eef2f8;
}

/* 分组标签 */
.group-label {
  font-size: 11px;
  color: #909399;
  white-space: nowrap;
  padding: 0 2px;
  user-select: none;
}

/* 当前激活的注释工具按钮 */
.is-active-tool {
  background-color: #409eff !important;
  color: #ffffff !important;
  border-color: #409eff !important;
}

/* 按钮内文字图标 */
.btn-icon {
  font-size: 12px;
  font-weight: 600;
}
.highlight-icon {
  background: #ffff00;
  color: #333;
  padding: 0 2px;
  border-radius: 2px;
}
.underline-icon {
  color: #409eff;
  text-decoration: underline;
}
.strikeout-icon {
  color: #f56c6c;
  text-decoration: line-through;
}

/* 颜色/线宽/透明度内联项 */
.inline-item {
  display: flex;
  align-items: center;
  gap: 4px;
}
.item-label {
  font-size: 12px;
  color: #606266;
  white-space: nowrap;
}
</style>