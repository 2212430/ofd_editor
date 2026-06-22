<template>
  <div class="annotation-discussion">
    <div class="discussion-header">
      <span class="discussion-title">讨论</span>
      <span v-if="replies.length > 0" class="discussion-count">{{ replies.length }}</span>
    </div>

    <div class="author-row">
      <label>署名</label>
      <el-input
          v-model="authorName"
          size="small"
          placeholder="您的显示名"
          @change="onAuthorChange"
      />
    </div>

    <div v-if="replies.length === 0" class="discussion-empty">暂无回复，写下第一条讨论吧</div>

    <div v-else class="reply-list">
      <div v-for="node in replyTree" :key="node.id" class="reply-item">
        <AnnotationReplyItem
            :node="node"
            :depth="0"
            @reply="startReply"
            @edit="startEdit"
            @delete="onDelete"
        />
      </div>
    </div>

    <div class="composer">
      <div v-if="replyTargetLabel" class="reply-target">
        回复 {{ replyTargetLabel }}
        <el-button type="primary" link size="small" @click="cancelReplyTarget">取消</el-button>
      </div>
      <el-input
          v-model="draft"
          type="textarea"
          :rows="3"
          size="small"
          :placeholder="replyTargetId ? '输入回复…' : '输入讨论内容…'"
          @keydown.ctrl.enter.prevent="submit"
      />
      <el-button
          type="primary"
          size="small"
          style="margin-top: 8px; width: 100%"
          :disabled="!draft.trim()"
          @click="submit"
      >
        {{ editingReplyId ? '保存修改' : '发表' }}
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useEditorStore } from '@/stores/editorStore'
import type { AnnotationReplyData } from '@/types'
import { buildReplyTree } from '@/utils/annotationDiscussion'
import AnnotationReplyItem from '@/components/AnnotationReplyItem.vue'

const props = defineProps<{
  annotationId: string
}>()

const store = useEditorStore()
const draft = ref('')
const authorName = ref(store.discussionAuthor)
const replyTargetId = ref<string | null>(null)
const replyTargetLabel = ref('')
const editingReplyId = ref<string | null>(null)

const replies = computed(() => store.getRepliesForAnnotation(props.annotationId))
const replyTree = computed(() => buildReplyTree(replies.value))

watch(
    () => props.annotationId,
    () => {
      draft.value = ''
      replyTargetId.value = null
      replyTargetLabel.value = ''
      editingReplyId.value = null
    },
)

function onAuthorChange() {
  store.setDiscussionAuthor(authorName.value)
}

function startReply(node: AnnotationReplyData) {
  editingReplyId.value = null
  replyTargetId.value = node.id
  replyTargetLabel.value = node.author
  draft.value = ''
}

function startEdit(node: AnnotationReplyData) {
  editingReplyId.value = node.id
  replyTargetId.value = null
  replyTargetLabel.value = ''
  draft.value = node.content
}

function cancelReplyTarget() {
  replyTargetId.value = null
  replyTargetLabel.value = ''
}

async function submit() {
  const text = draft.value.trim()
  if (!text) return

  if (editingReplyId.value) {
    const ok = await store.updateAnnotationReply(
        props.annotationId, editingReplyId.value, text,
    )
    if (ok) {
      ElMessage.success({ message: '已更新', duration: 1200, showClose: false })
      draft.value = ''
      editingReplyId.value = null
    } else {
      ElMessage.error('更新失败')
    }
    return
  }

  const saved = await store.addAnnotationReply(
      props.annotationId,
      text,
      replyTargetId.value ?? undefined,
  )
  if (saved) {
    ElMessage.success({ message: '已发表', duration: 1200, showClose: false })
    draft.value = ''
    replyTargetId.value = null
    replyTargetLabel.value = ''
  } else {
    ElMessage.error('发表失败')
  }
}

async function onDelete(node: AnnotationReplyData) {
  try {
    await ElMessageBox.confirm('确定删除这条回复吗？其子回复也会一并删除。', '删除回复', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  const ok = await store.deleteAnnotationReply(props.annotationId, node.id)
  if (ok) {
    ElMessage.success({ message: '已删除', duration: 1200, showClose: false })
    if (editingReplyId.value === node.id) {
      editingReplyId.value = null
      draft.value = ''
    }
  }
}
</script>

<style scoped>
.annotation-discussion {
  margin-top: 4px;
}

.discussion-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}

.discussion-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
}

.discussion-count {
  font-size: 10px;
  font-weight: 700;
  color: #fff;
  background: var(--ribbon-accent);
  border-radius: 8px;
  padding: 0 6px;
  line-height: 16px;
}

.author-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.author-row label {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--text-2);
}

.discussion-empty {
  font-size: 12px;
  color: var(--text-3);
  padding: 8px 0 12px;
}

.reply-list {
  max-height: 220px;
  overflow-y: auto;
  margin-bottom: 10px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  padding: 6px;
  background: #fafbfc;
}

.reply-item + .reply-item {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed #e8e8e8;
}

.composer {
  border-top: 1px dashed var(--line);
  padding-top: 10px;
}

.reply-target {
  font-size: 11px;
  color: var(--text-2);
  margin-bottom: 6px;
}
</style>
