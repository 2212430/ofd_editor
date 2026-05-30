<template>
  <el-dialog
      v-model="visible"
      title="打印"
      width="460px"
      :append-to-body="true"
      class="print-dialog"
      @open="onOpen"
  >
    <div class="print-body">
      <!-- 打印范围 -->
      <section class="opt-group">
        <div class="opt-title">打印范围</div>
        <el-radio-group v-model="opts.range" class="range-group">
          <el-radio value="all">全部页面（共 {{ pageCount }} 页）</el-radio>
          <el-radio value="current">当前页（第 {{ currentPageIndex + 1 }} 页）</el-radio>
          <el-radio value="custom">自定义</el-radio>
        </el-radio-group>
        <el-input
            v-model="opts.customRange"
            :disabled="opts.range !== 'custom'"
            placeholder="例如：1-3, 5, 8"
            size="small"
            class="range-input"
        >
          <template #prepend>页码</template>
        </el-input>
        <div class="hint" v-if="resolvedCount > 0">将打印 {{ resolvedCount }} 页 × {{ opts.copies }} 份</div>
        <div class="hint hint-warn" v-else>未匹配到有效页码</div>
      </section>

      <el-divider />

      <!-- 布局 -->
      <section class="opt-group">
        <div class="opt-title">布局与纸张</div>

        <div class="opt-row">
          <span class="opt-label">方向</span>
          <el-segmented v-model="opts.orientation" :options="orientationOptions" size="small" />
        </div>

        <div class="opt-row">
          <span class="opt-label">缩放</span>
          <el-segmented v-model="opts.fit" :options="fitOptions" size="small" />
        </div>

        <div class="opt-row">
          <span class="opt-label">份数</span>
          <el-input-number v-model="opts.copies" :min="1" :max="99" size="small" />
        </div>
      </section>

      <el-divider />

      <!-- 内容与质量 -->
      <section class="opt-group">
        <div class="opt-title">内容</div>
        <div class="opt-row">
          <span class="opt-label">包含注释</span>
          <el-switch v-model="opts.includeAnnotations" />
        </div>
        <div class="opt-row">
          <span class="opt-label">清晰度</span>
          <el-segmented v-model="opts.quality" :options="qualityOptions" size="small" />
        </div>
      </section>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
          type="primary"
          :icon="Printer"
          :disabled="resolvedCount === 0"
          @click="confirmPrint"
      >
        打印（{{ resolvedCount }} 页）
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive } from 'vue'
import { Printer } from '@element-plus/icons-vue'
import { useEditorStore } from '@/stores/editorStore'
import {
  DEFAULT_PRINT_OPTIONS,
  resolvePageIndices,
  type PrintOptions,
} from '@/utils/print'

const emit = defineEmits<{ (e: 'print', opts: PrintOptions): void }>()

const store = useEditorStore()

const visible = computed({
  get: () => store.printDialogVisible,
  set: (v: boolean) => { store.printDialogVisible = v },
})

const pageCount = computed(() => store.document?.pageCount ?? 0)
const currentPageIndex = computed(() => store.currentPageIndex)

const opts = reactive<PrintOptions>({ ...DEFAULT_PRINT_OPTIONS })

const orientationOptions = [
  { label: '自动', value: 'auto' },
  { label: '纵向', value: 'portrait' },
  { label: '横向', value: 'landscape' },
]
const fitOptions = [
  { label: '适应纸张', value: 'fit' },
  { label: '实际尺寸', value: 'actual' },
]
const qualityOptions = [
  { label: '标准', value: 'standard' },
  { label: '高清', value: 'high' },
]

const resolvedCount = computed(() =>
    resolvePageIndices(opts, pageCount.value, currentPageIndex.value).length
)

function onOpen() {
  // 每次打开重置范围为「全部」，但保留上次的布局偏好
  opts.range = 'all'
  opts.customRange = ''
}

function confirmPrint() {
  if (resolvedCount.value === 0) return
  emit('print', { ...opts })
  visible.value = false
}
</script>

<style scoped>
.print-body { padding: 2px 4px; }
.opt-group { margin-bottom: 4px; }
.opt-title {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: .3px;
  color: var(--text-3, #8b929c);
  text-transform: uppercase;
  margin-bottom: 12px;
}
.range-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 12px;
}
.range-input { margin-bottom: 8px; }
.opt-row {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 14px;
}
.opt-label {
  width: 64px;
  flex-shrink: 0;
  font-size: 13px;
  color: var(--text-2, #4a505a);
}
.hint { font-size: 12px; color: var(--text-3, #8b929c); }
.hint-warn { color: #e6a23c; }
.print-dialog :deep(.el-divider--horizontal) { margin: 8px 0 16px; }
</style>
