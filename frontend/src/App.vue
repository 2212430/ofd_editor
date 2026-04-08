<template>
  <div class="app">
    <!-- 顶部工具栏 -->
    <Toolbar />

    <!-- 主体 -->
    <div class="main-body">
      <!-- 左侧页面面板 -->
      <PagePanel />

      <!-- 中间编辑区 -->
      <div class="editor-area">
        <!-- 加载遮罩 -->
        <div v-if="store.isLoading" class="loading-mask">
          <el-icon class="loading-icon"><Loading /></el-icon>
          <span>{{ store.loadingText }}</span>
        </div>

        <!-- 编辑器 -->
        <div v-if="store.currentPage" class="canvas-container">
          <CanvasEditor
              :page="store.currentPage"
              :page-index="store.currentPageIndex"
          />
        </div>

        <!-- 欢迎页 -->
        <div v-else class="welcome">
          <el-empty description="请打开OFD文件开始编辑">
            <el-button type="primary" :icon="Upload"
                       @click="triggerUpload">
              打开OFD文件
            </el-button>
          </el-empty>
        </div>
      </div>

      <!-- 右侧属性面板 -->
      <PropertyPanel />
    </div>

    <!-- 底部状态栏 -->
    <div class="status-bar">
      <span v-if="store.document">
        📄 {{ store.document.title }}
        &nbsp;|&nbsp;
        第 {{ store.currentPageIndex + 1 }} / {{ store.document.pageCount }} 页
        &nbsp;|&nbsp;
        缩放: {{ Math.round(store.scale * 100) }}%
      </span>
      <span v-else>就绪</span>
      <span class="version">OFD Editor v1.0</span>
    </div>

    <!-- 隐藏的文件输入 -->
    <input ref="uploadRef" type="file" accept=".ofd"
           style="display:none" @change="handleWelcomeUpload" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Upload, Loading } from '@element-plus/icons-vue'
import { useEditorStore } from '@/stores/editorStore'
import { ofdApi } from '@/api/ofdApi'
import Toolbar from '@/components/Toolbar.vue'
import PagePanel from '@/components/PagePanel.vue'
import CanvasEditor from '@/components/CanvasEditor.vue'
import PropertyPanel from '@/components/PropertyPanel.vue'

const store = useEditorStore()
const uploadRef = ref<HTMLInputElement>()

function triggerUpload() {
  uploadRef.value?.click()
}

async function handleWelcomeUpload(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  store.setLoading(true, '正在解析OFD文件...')
  try {
    const doc = await ofdApi.parseOfd(file)
    store.setDocument(doc)
    store.loadAllAnnotations()
    store.setCurrentFile(file)
    ElMessage.success(`解析成功：${doc.title}`)
  } catch (err: any) {
    ElMessage.error(err.message || '解析失败')
  } finally {
    store.setLoading(false)
  }
}
</script>

<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; }
</style>

<style scoped>
.app {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
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
  background: #e8e8e8;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 24px;
  position: relative;
}

.canvas-container {
  display: inline-block;
}

.welcome {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.loading-mask {
  position: absolute;
  inset: 0;
  background: rgba(255, 255, 255, 0.85);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  z-index: 100;
  font-size: 15px;
  color: #606266;
}

.loading-icon {
  font-size: 36px;
  color: #409eff;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.status-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 16px;
  background: #f0f2f5;
  border-top: 1px solid #e4e7ed;
  font-size: 12px;
  color: #909399;
}

.version {
  color: #c0c4cc;
}
</style>