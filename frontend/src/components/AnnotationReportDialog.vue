<template>
  <el-dialog
      v-model="visible"
      title="注释汇总报告"
      width="860px"
      top="5vh"
      :append-to-body="true"
      class="annotation-report-dialog"
      destroy-on-close
  >
    <div v-if="!store.document" class="empty-tip">请先打开文档</div>

    <div v-else-if="report.totalCount === 0" class="empty-tip">
      <p>当前文档暂无注释</p>
      <p class="empty-hint">在「注释」选项卡中添加高亮、图章、文本框等批注后，可在此生成汇总报告。</p>
    </div>

    <template v-else>
      <section class="report-meta">
        <div class="meta-row">
          <span class="meta-label">文档</span>
          <span class="meta-value">{{ report.documentTitle }}</span>
        </div>
        <div class="meta-row">
          <span class="meta-label">页数</span>
          <span class="meta-value">{{ report.pageCount }} 页 · {{ kindLabel }}</span>
        </div>
        <div class="meta-row">
          <span class="meta-label">生成时间</span>
          <span class="meta-value">{{ report.generatedAt }}</span>
        </div>
      </section>

      <div class="stat-cards">
        <div class="stat-card">
          <strong>{{ report.totalCount }}</strong>
          <span>注释总数</span>
        </div>
        <div class="stat-card">
          <strong>{{ report.visibleCount }}</strong>
          <span>显示中</span>
        </div>
        <div class="stat-card">
          <strong>{{ report.hiddenCount }}</strong>
          <span>已隐藏</span>
        </div>
        <div class="stat-card">
          <strong>{{ report.typeSummary.length }}</strong>
          <span>类型数</span>
        </div>
      </div>

      <section class="type-section">
        <h3>类型统计</h3>
        <div class="type-tags">
          <el-tag
              v-for="t in report.typeSummary"
              :key="t.type"
              size="small"
              effect="plain"
          >
            {{ t.label }} × {{ t.count }}
          </el-tag>
        </div>
      </section>

      <section class="table-section">
        <h3>注释明细</h3>
        <el-table
            :data="report.rows"
            size="small"
            stripe
            max-height="360"
            empty-text="无数据"
        >
          <el-table-column prop="index" label="#" width="44" fixed />
          <el-table-column prop="pageNumber" label="页码" width="52" />
          <el-table-column prop="typeLabel" label="类型" width="88" />
          <el-table-column prop="title" label="标题" min-width="120" show-overflow-tooltip />
          <el-table-column prop="content" label="内容" min-width="100" show-overflow-tooltip />
          <el-table-column prop="position" label="位置" width="88" />
          <el-table-column prop="size" label="尺寸" width="100" />
          <el-table-column prop="color" label="颜色" width="72" />
          <el-table-column label="状态" width="56">
            <template #default="{ row }">
              <el-tag :type="row.visible ? 'success' : 'info'" size="small" effect="plain">
                {{ row.visible ? '显示' : '隐藏' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </template>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button
          :disabled="report.totalCount === 0"
          :icon="Printer"
          @click="handlePrint"
      >
        打印
      </el-button>
      <el-button
          :disabled="report.totalCount === 0"
          :icon="Download"
          @click="handleExportCsv"
      >
        导出 CSV
      </el-button>
      <el-button
          type="primary"
          :disabled="report.totalCount === 0"
          :icon="Download"
          @click="handleExportHtml"
      >
        导出 HTML
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Printer } from '@element-plus/icons-vue'
import { useEditorStore } from '@/stores/editorStore'
import {
    buildAnnotationReport,
    downloadAnnotationReportCsv,
    downloadAnnotationReportHtml,
    printAnnotationReport,
    type AnnotationReportData,
} from '@/utils/annotationReport'

const visible = defineModel<boolean>({ default: false })
const store = useEditorStore()

const report = computed<AnnotationReportData>(() => {
    if (!store.document) {
        return emptyReport()
    }
    return buildAnnotationReport(
        store.document,
        store.flatAnnotationList,
        store.isPdfDocument ? 'pdf' : 'ofd',
        Object.fromEntries(
            Object.entries(store.annotationRepliesMap).map(([id, list]) => [id, list?.length ?? 0]),
        ),
    )
})

const kindLabel = computed(() =>
    report.value.documentKind === 'pdf' ? 'PDF（原生）' : 'OFD',
)

function emptyReport(): AnnotationReportData {
    return {
        documentTitle: '',
        pageCount: 0,
        documentKind: 'ofd',
        generatedAt: '',
        totalCount: 0,
        visibleCount: 0,
        hiddenCount: 0,
        typeSummary: [],
        rows: [],
    }
}

function handleExportHtml() {
    downloadAnnotationReportHtml(report.value)
    ElMessage.success('HTML 报告已开始下载')
}

function handleExportCsv() {
    downloadAnnotationReportCsv(report.value)
    ElMessage.success('CSV 报告已开始下载')
}

function handlePrint() {
    if (!printAnnotationReport(report.value)) {
        ElMessage.error('打印窗口被浏览器拦截，请允许弹出窗口后重试')
    }
}
</script>

<style scoped>
.empty-tip {
  text-align: center;
  padding: 32px 16px;
  color: var(--text-2);
  font-size: 14px;
}

.empty-hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-3);
}

.report-meta {
  background: #f8f9fb;
  border-radius: 8px;
  padding: 12px 14px;
  margin-bottom: 14px;
}

.meta-row {
  display: flex;
  gap: 12px;
  font-size: 13px;
  line-height: 1.8;
}

.meta-label {
  flex-shrink: 0;
  width: 64px;
  color: var(--text-3);
}

.meta-value {
  color: var(--text-1);
  word-break: break-all;
}

.stat-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  margin-bottom: 16px;
}

.stat-card {
  background: #eef4fc;
  border-radius: 8px;
  padding: 10px 12px;
  text-align: center;
}

.stat-card strong {
  display: block;
  font-size: 22px;
  color: var(--ribbon-accent, #1a73e8);
  line-height: 1.2;
}

.stat-card span {
  font-size: 11px;
  color: var(--text-3);
}

.type-section,
.table-section {
  margin-bottom: 12px;
}

.type-section h3,
.table-section h3 {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
}

.type-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
