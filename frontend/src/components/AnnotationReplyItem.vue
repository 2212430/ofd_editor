<template>
  <div class="reply-block" :style="{ marginLeft: `${depth * 12}px` }">
    <div class="reply-meta">
      <span class="reply-author">{{ node.author }}</span>
      <span class="reply-time">{{ formatReplyTime(node.createdAt) }}</span>
    </div>
    <div class="reply-content">{{ node.content }}</div>
    <div class="reply-actions">
      <button type="button" class="reply-action-btn" @click="emit('reply', node)">回复</button>
      <button type="button" class="reply-action-btn" @click="emit('edit', node)">编辑</button>
      <button type="button" class="reply-action-btn danger" @click="emit('delete', node)">删除</button>
    </div>
    <AnnotationReplyItem
        v-for="child in node.children"
        :key="child.id"
        :node="child"
        :depth="depth + 1"
        @reply="emit('reply', $event)"
        @edit="emit('edit', $event)"
        @delete="emit('delete', $event)"
    />
  </div>
</template>

<script setup lang="ts">
import type { AnnotationReplyData } from '@/types'
import { formatReplyTime, type ReplyTreeNode } from '@/utils/annotationDiscussion'

defineProps<{
  node: ReplyTreeNode
  depth?: number
}>()

const emit = defineEmits<{
  reply: [node: AnnotationReplyData]
  edit: [node: AnnotationReplyData]
  delete: [node: AnnotationReplyData]
}>()
</script>

<style scoped>
.reply-meta {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 4px;
}

.reply-author {
  font-size: 12px;
  font-weight: 600;
  color: var(--ribbon-accent);
}

.reply-time {
  font-size: 10px;
  color: var(--text-3);
}

.reply-content {
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-1);
  white-space: pre-wrap;
  word-break: break-word;
}

.reply-actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}

.reply-action-btn {
  border: none;
  background: none;
  padding: 0;
  font-size: 11px;
  color: var(--ribbon-accent);
  cursor: pointer;
}

.reply-action-btn.danger {
  color: #c0392b;
}
</style>
