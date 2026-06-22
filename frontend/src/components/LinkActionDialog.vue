<template>
  <el-dialog
      v-model="visible"
      :title="editId ? '编辑链接' : '设置链接'"
      width="420px"
      :append-to-body="true"
  >
    <el-form label-width="88px" size="small">
      <el-form-item label="动作类型">
        <el-radio-group v-model="form.actionType">
          <el-radio value="GOTO_PAGE">跳转到页</el-radio>
          <el-radio value="URI">打开网址</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="form.actionType === 'GOTO_PAGE'" label="目标页">
        <el-input-number
            v-model="form.pageNumber"
            :min="1"
            :max="pageCount"
            controls-position="right"
            style="width: 140px"
        />
        <span class="hint">共 {{ pageCount }} 页</span>
      </el-form-item>
      <el-form-item v-else label="网址">
        <el-input v-model="form.uri" placeholder="https://example.com" clearable />
      </el-form-item>
      <el-form-item label="提示文字">
        <el-input v-model="form.tooltip" placeholder="可选，悬停或报告中显示" clearable />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="handleCancel">取消</el-button>
      <el-button type="primary" @click="handleConfirm">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { AnnotationData, LinkActionType } from '@/types'

const props = defineProps<{
  modelValue: boolean
  pageCount: number
  initial?: Partial<AnnotationData> | null
  editId?: string | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  cancel: []
  confirm: [payload: {
    actionType: LinkActionType
    targetPageIndex?: number
    uri?: string
    content?: string
  }]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const editId = computed(() => props.editId ?? null)

const form = reactive({
  actionType: 'GOTO_PAGE' as LinkActionType,
  pageNumber: 1,
  uri: '',
  tooltip: '',
})

watch(
    () => props.modelValue,
    (open) => {
      if (!open) return
      const init = props.initial
      form.actionType = init?.actionType === 'URI' ? 'URI' : 'GOTO_PAGE'
      form.pageNumber = init?.targetPageIndex != null ? init.targetPageIndex + 1 : 1
      form.uri = init?.uri ?? ''
      form.tooltip = init?.content ?? ''
    },
    { immediate: true },
)

function handleCancel() {
  emit('cancel')
  visible.value = false
}

function handleConfirm() {
  if (form.actionType === 'GOTO_PAGE') {
    if (form.pageNumber < 1 || form.pageNumber > props.pageCount) {
      ElMessage.warning('请输入有效的页码')
      return
    }
    emit('confirm', {
      actionType: 'GOTO_PAGE',
      targetPageIndex: form.pageNumber - 1,
      content: form.tooltip.trim() || undefined,
    })
  } else {
    const uri = form.uri.trim()
    if (!uri) {
      ElMessage.warning('请输入网址')
      return
    }
    emit('confirm', {
      actionType: 'URI',
      uri,
      content: form.tooltip.trim() || undefined,
    })
  }
  visible.value = false
}
</script>

<style scoped>
.hint {
  margin-left: 8px;
  color: #888;
  font-size: 12px;
}
</style>
