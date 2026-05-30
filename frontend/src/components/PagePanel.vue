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
  width: 178px;
  min-width: 178px;
  background: var(--panel-bg);
  border-right: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  height: 42px;
  padding: 0 16px;
  font-size: 13px;
  font-weight: 650;
  letter-spacing: .2px;
  color: var(--text-1);
  border-bottom: 1px solid var(--line);
  background: #fff;
  flex-shrink: 0;
}

.page-count {
  font-size: 11px;
  color: var(--text-3);
  font-weight: 600;
  background: var(--toolbar-bg-2);
  padding: 2px 8px;
  border-radius: 999px;
}

.empty-tip {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-3);
  font-size: 13px;
}

.page-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px 10px;
}

.page-item {
  margin-bottom: 12px;
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: transform .12s ease;
}
.page-item:last-child { margin-bottom: 0; }

.page-thumbnail {
  background: #fff;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  position: relative;
  min-height: 84px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--line);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
  transition: box-shadow .15s ease, border-color .15s ease;
}

.page-item:hover .page-thumbnail {
  border-color: var(--brand-light, #8fbef5);
  box-shadow: 0 4px 12px rgba(38, 128, 235, .14);
}

.page-item.active .page-thumbnail {
  border-color: var(--ribbon-accent);
  box-shadow: 0 0 0 2px var(--ribbon-accent), 0 4px 12px rgba(232, 119, 34, .2);
}

.page-icon {
  font-size: 30px;
  color: #cfd4db;
}

.element-count {
  position: absolute;
  bottom: 5px;
  right: 5px;
  font-size: 10px;
  color: var(--text-2);
  background: rgba(255, 255, 255, .9);
  border: 1px solid var(--line);
  padding: 1px 6px;
  border-radius: 999px;
}

.page-number {
  text-align: center;
  font-size: 11.5px;
  font-weight: 600;
  padding: 6px 0 2px;
  color: var(--text-3);
}

.page-item.active .page-number {
  color: var(--ribbon-accent);
}
</style>