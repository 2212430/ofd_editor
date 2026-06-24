<template>
  <div class="outline-panel">
    <div v-if="!store.document" class="empty-tip">暂无文档</div>
    <div v-else-if="!store.hasOutlines" class="empty-tip">
      <p>本文档无大纲</p>
      <p class="empty-sub">部分 OFD/PDF 未嵌入书签目录</p>
    </div>
    <el-tree
        v-else
        class="outline-tree"
        :data="treeData"
        node-key="key"
        :props="treeProps"
        :expand-on-click-node="false"
        default-expand-all
        highlight-current
        @node-click="onNodeClick"
    >
      <template #default="{ data }">
        <div class="outline-node">
          <span class="outline-title" :title="data.title">{{ data.title }}</span>
          <span v-if="data.targetHint" class="outline-target">{{ data.targetHint }}</span>
        </div>
      </template>
    </el-tree>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { useEditorStore } from '@/stores/editorStore'
import type { OutlineItem } from '@/types'
import { describeOutlineTarget } from '@/utils/pdfOutline'

const store = useEditorStore()

interface OutlineTreeNode extends OutlineItem {
  key: string
  targetHint: string
  children?: OutlineTreeNode[]
}

const treeProps = { label: 'title', children: 'children' }

function buildTreeNodes(items: OutlineItem[], prefix = 'n'): OutlineTreeNode[] {
  return items.map((item, index) => {
    const key = `${prefix}-${index}`
    const node: OutlineTreeNode = {
      ...item,
      key,
      targetHint: describeOutlineTarget(item),
    }
    if (item.children?.length) {
      node.children = buildTreeNodes(item.children, key)
    }
    return node
  })
}

const treeData = computed(() => buildTreeNodes(store.outlines))

function onNodeClick(data: OutlineTreeNode) {
  store.navigateOutline(data)
  if (data.uri) return
  if (data.pageIndex == null) {
    ElMessage.info('该大纲项未配置跳转目标')
  }
}
</script>

<style scoped>
.outline-panel {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.empty-tip {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 16px;
  text-align: center;
  color: var(--text-3);
  font-size: 13px;
  line-height: 1.5;
}

.empty-sub {
  margin-top: 6px;
  font-size: 11px;
  color: var(--text-3);
}

.outline-tree {
  flex: 1;
  overflow: auto;
  padding: 8px 6px 12px;
  background: transparent;
}

.outline-tree :deep(.el-tree-node__content) {
  height: auto;
  min-height: 28px;
  padding: 4px 2px;
  border-radius: var(--radius-sm);
}

.outline-tree :deep(.el-tree-node__content:hover) {
  background: var(--toolbar-bg-2);
}

.outline-tree :deep(.el-tree-node.is-current > .el-tree-node__content) {
  background: var(--ribbon-accent-soft);
}

.outline-node {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  padding-right: 4px;
}

.outline-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.outline-target {
  font-size: 10px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
