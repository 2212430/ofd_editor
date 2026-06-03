<template>
  <div class="annotation-list-panel">
    <div class="panel-toolbar">
      <el-radio-group v-model="listScope" size="small">
        <el-radio-button value="current">当前页</el-radio-button>
        <el-radio-button value="all">全部</el-radio-button>
      </el-radio-group>
    </div>

    <div v-if="!store.document" class="empty-tip">
      <el-icon><ChatLineSquare /></el-icon>
      <span>暂无文档</span>
    </div>

    <div v-else-if="displayItems.length === 0" class="empty-tip">
      <el-icon><ChatLineSquare /></el-icon>
      <span>{{ listScope === 'current' ? '当前页暂无注释' : '文档中暂无注释' }}</span>
    </div>

    <ul v-else ref="listRef" class="ann-list">
      <li
          v-for="item in displayItems"
          :key="item.annotation.id"
          :ref="(el) => setItemRef(el, item.annotation.id)"
          class="ann-item"
          :class="{
            active: store.selectedAnnotationId === item.annotation.id,
            hidden: item.annotation.hidden,
          }"
          @click="onItemClick(item)"
      >
        <div class="ann-item-main">
          <span class="ann-type-tag">{{ annotationTypeLabel(item.annotation.type) }}</span>
          <span class="ann-title" :title="annotationListTitle(item.annotation)">
            {{ annotationListTitle(item.annotation) }}
          </span>
        </div>
        <div class="ann-item-meta">
          <span class="ann-page">第 {{ item.pageIndex + 1 }} 页</span>
          <el-switch
              :model-value="isAnnotationVisible(item.annotation)"
              size="small"
              inline-prompt
              active-text="显"
              inactive-text="隐"
              :title="isAnnotationVisible(item.annotation) ? '点击隐藏' : '点击显示'"
              @click.stop
              @change="(visible: boolean) => onVisibilityChange(item.annotation.id, visible)"
          />
        </div>
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { ComponentPublicInstance } from 'vue'
import { ChatLineSquare } from '@element-plus/icons-vue'
import { useEditorStore } from '@/stores/editorStore'
import {
  annotationListTitle, annotationTypeLabel, isAnnotationVisible,
} from '@/utils/annotationLabels'

const store = useEditorStore()
const listRef = ref<HTMLElement>()
const itemRefs = new Map<string, HTMLElement>()

const listScope = computed({
  get: () => store.annotationListScope,
  set: (v: 'current' | 'all') => { store.annotationListScope = v },
})

const displayItems = computed(() => store.filteredAnnotationList)

function setItemRef(el: Element | ComponentPublicInstance | null, id: string) {
  if (el instanceof HTMLElement) itemRefs.set(id, el)
  else itemRefs.delete(id)
}

function onItemClick(item: { annotation: { id: string }; pageIndex: number }) {
  store.focusAnnotation(item.annotation.id)
}

async function onVisibilityChange(annotationId: string, visible: boolean) {
  await store.setAnnotationHidden(annotationId, !visible)
}

watch(
    () => store.selectedAnnotationId,
    async (id) => {
      if (!id) return
      await nextTick()
      const el = itemRefs.get(id)
      el?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
    },
)
</script>

<style scoped>
.annotation-list-panel {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.panel-toolbar {
  flex-shrink: 0;
  padding: 8px 10px;
  border-bottom: 1px solid var(--line);
  background: #fafafa;
}

.panel-toolbar :deep(.el-radio-group) {
  display: flex;
  width: 100%;
}

.panel-toolbar :deep(.el-radio-button) {
  flex: 1;
}

.panel-toolbar :deep(.el-radio-button__inner) {
  width: 100%;
  padding: 5px 8px;
  font-size: 12px;
}

.empty-tip {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--text-3);
  font-size: 13px;
  padding: 24px 16px;
  text-align: center;
}

.empty-tip .el-icon {
  font-size: 32px;
  color: #ccc;
}

.ann-list {
  list-style: none;
  margin: 0;
  padding: 6px 0;
  overflow-y: auto;
  flex: 1;
}

.ann-item {
  padding: 8px 10px;
  margin: 0 6px 4px;
  border-radius: var(--radius-sm);
  border: 1px solid transparent;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.ann-item:hover {
  background: #f0f4f8;
}

.ann-item.active {
  background: #e8f0fe;
  border-color: #b8d4f8;
}

.ann-item.hidden {
  opacity: 0.72;
}

.ann-item-main {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-bottom: 6px;
}

.ann-type-tag {
  flex-shrink: 0;
  font-size: 10px;
  font-weight: 600;
  color: var(--ribbon-accent);
  background: #eef4fc;
  padding: 1px 5px;
  border-radius: 3px;
}

.ann-title {
  font-size: 12px;
  color: var(--text-1);
  line-height: 1.4;
  word-break: break-all;
}

.ann-item.hidden .ann-title {
  color: var(--text-3);
  text-decoration: line-through;
}

.ann-item-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ann-page {
  font-size: 11px;
  color: var(--text-3);
}
</style>
