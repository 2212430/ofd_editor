<template>
  <div class="page-panel">
    <div class="panel-header">
      <span>页面列表</span>
      <span class="page-count">
        {{ store.document?.pageCount ?? 0 }} 页
      </span>
    </div>

    <div v-if="!store.document" class="empty-tip">
      暂无文档
    </div>

    <div v-else class="page-list">
      <div
          v-for="(page, index) in store.document.pages"
          :key="index"
          class="page-item"
          :class="{ active: store.currentPageIndex === index }"
          @click="store.setCurrentPage(index)"
      >
        <!-- 缩略图区域 -->
        <div
            class="page-thumbnail"
            :style="{ aspectRatio: `${page.width} / ${page.height}` }"
        >
          <el-icon class="page-icon"><Document /></el-icon>
          <span class="element-count">{{ page.elements.length }}个元素</span>
        </div>
        <!-- 页码 -->
        <div class="page-number">第 {{ index + 1 }} 页</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Document } from '@element-plus/icons-vue'
import { useEditorStore } from '@/stores/editorStore'

const store = useEditorStore()
</script>

<style scoped>
.page-panel {
  width: 150px;
  min-width: 150px;
  background: #f0f2f5;
  border-right: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  border-bottom: 1px solid #e4e7ed;
  background: white;
}

.page-count {
  font-size: 11px;
  color: #909399;
  font-weight: normal;
}

.empty-tip {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #c0c4cc;
  font-size: 13px;
}

.page-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.page-item {
  margin-bottom: 8px;
  cursor: pointer;
  border-radius: 4px;
  overflow: hidden;
  border: 2px solid transparent;
  transition: all 0.2s;
}

.page-item:hover {
  border-color: #a0cfff;
}

.page-item.active {
  border-color: #409eff;
}

.page-thumbnail {
  background: white;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  position: relative;
  min-height: 80px;
}

.page-icon {
  font-size: 32px;
  color: #c0c4cc;
}

.element-count {
  position: absolute;
  bottom: 4px;
  right: 4px;
  font-size: 10px;
  color: #909399;
  background: rgba(255,255,255,0.8);
  padding: 1px 4px;
  border-radius: 2px;
}

.page-number {
  text-align: center;
  font-size: 11px;
  padding: 3px 0;
  background: white;
  color: #606266;
}

.page-item.active .page-number {
  background: #ecf5ff;
  color: #409eff;
}
</style>