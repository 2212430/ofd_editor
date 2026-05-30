<template>
  <div class="page-panel">
    <div class="panel-header">
      <span>页面列表</span>
      <span class="page-count">{{ store.document?.pageCount ?? 0 }} 页</span>
    </div>
    <div v-if="store.document" class="panel-hint">拖动缩略图可调整顺序</div>

    <div v-if="!store.document" class="empty-tip">暂无文档</div>

    <div v-else class="page-list">
      <div
          v-for="(page, index) in store.document.pages"
          :key="page.id ?? `page-${index}`"
          class="page-item"
          :class="{
            active: store.currentPageIndex === index,
            dragging: dragFromIndex === index,
            'drag-over': dragOverIndex === index && dragFromIndex !== index,
          }"
          draggable="true"
          @click="store.setCurrentPage(index)"
          @dragstart="onDragStart(index, $event)"
          @dragover="onDragOver(index, $event)"
          @dragleave="onDragLeave(index)"
          @drop="onDrop(index, $event)"
          @dragend="onDragEnd"
      >
        <div
            class="page-thumbnail"
            :style="{ aspectRatio: `${page.width} / ${page.height}` }"
        >
          <el-icon class="page-icon"><Document /></el-icon>
          <span class="element-count">{{ page.elements.length }} 个元素</span>
          <button
              type="button"
              class="copy-btn"
              title="复制此页"
              @click.stop="handleCopyPage(index)"
          >
            <el-icon><CopyDocument /></el-icon>
          </button>
        </div>
        <div class="page-number">第 {{ index + 1 }} 页</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Document, CopyDocument } from '@element-plus/icons-vue'
import { useEditorStore } from '@/stores/editorStore'

const store = useEditorStore()
const dragFromIndex = ref<number | null>(null)
const dragOverIndex = ref<number | null>(null)

function onDragStart(index: number, e: DragEvent) {
  dragFromIndex.value = index
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move'
    e.dataTransfer.setData('text/plain', String(index))
  }
}

function onDragOver(index: number, e: DragEvent) {
  e.preventDefault()
  dragOverIndex.value = index
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'
}

function onDragLeave(index: number) {
  if (dragOverIndex.value === index) dragOverIndex.value = null
}

function onDrop(toIndex: number, e: DragEvent) {
  e.preventDefault()
  const from = dragFromIndex.value
  if (from === null || from === toIndex) {
    onDragEnd()
    return
  }
  store.movePage(from, toIndex)
  ElMessage.success(`已将第 ${from + 1} 页移动到第 ${toIndex + 1} 位`)
  onDragEnd()
}

function onDragEnd() {
  dragFromIndex.value = null
  dragOverIndex.value = null
}

async function handleCopyPage(index: number) {
  try {
    const newIndex = await store.copyPage(index)
    if (newIndex !== undefined) {
      ElMessage.success(`已复制第 ${index + 1} 页为第 ${newIndex + 1} 页`)
    }
  } catch (err: any) {
    ElMessage.error(err?.message || '复制页面失败')
  }
}
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
  color: var(--text-1);
  border-bottom: 1px solid var(--line);
  background: #fff;
  flex-shrink: 0;
}

.panel-hint {
  padding: 6px 10px;
  font-size: 10px;
  color: var(--text-3);
  background: #fff8f0;
  border-bottom: 1px solid #ffe8cc;
  text-align: center;
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
  cursor: grab;
  border-radius: var(--radius-sm);
  transition: transform .12s ease, opacity .12s ease;
}
.page-item:last-child { margin-bottom: 0; }
.page-item:active { cursor: grabbing; }

.page-item.dragging {
  opacity: .45;
}
.page-item.drag-over .page-thumbnail {
  border-color: var(--ribbon-accent);
  box-shadow: 0 0 0 2px var(--ribbon-accent-soft);
}

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
  border-color: var(--ribbon-accent-soft);
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
  left: 5px;
  font-size: 10px;
  color: var(--text-2);
  background: rgba(255, 255, 255, .9);
  border: 1px solid var(--line);
  padding: 1px 6px;
  border-radius: 999px;
}

.copy-btn {
  position: absolute;
  top: 5px;
  right: 5px;
  width: 24px;
  height: 24px;
  border: 1px solid var(--line);
  border-radius: 4px;
  background: rgba(255, 255, 255, .95);
  color: var(--text-2);
  cursor: pointer;
  display: grid;
  place-items: center;
  opacity: 0;
  transition: opacity .12s, color .12s, border-color .12s;
}
.page-item:hover .copy-btn,
.page-item.active .copy-btn {
  opacity: 1;
}
.copy-btn:hover {
  color: var(--ribbon-accent);
  border-color: var(--ribbon-accent-soft);
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
