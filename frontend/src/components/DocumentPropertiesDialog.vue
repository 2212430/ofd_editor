<template>
  <el-dialog
      v-model="visible"
      title="文档属性"
      width="520px"
      :append-to-body="true"
      class="doc-props-dialog"
  >
    <div v-if="doc" class="doc-props-body">
      <!-- 基本信息 -->
      <section class="props-section">
        <div class="props-title">基本信息</div>
        <dl class="props-dl">
          <div class="props-row">
            <dt>文档标题</dt>
            <dd>{{ doc.title || '未命名' }}</dd>
          </div>
          <div v-if="fileName" class="props-row">
            <dt>文件名</dt>
            <dd>{{ fileName }}</dd>
          </div>
          <div class="props-row">
            <dt>格式类型</dt>
            <dd>
              <el-tag :type="isOfd ? 'warning' : 'primary'" size="small" effect="plain">
                {{ isOfd ? 'OFD 开放版式文档' : 'PDF 导入（已转为 OFD 编辑）' }}
              </el-tag>
            </dd>
          </div>
          <div class="props-row">
            <dt>页数</dt>
            <dd>{{ doc.pageCount }} 页</dd>
          </div>
          <div v-if="doc.author" class="props-row">
            <dt>作者</dt>
            <dd>{{ doc.author }}</dd>
          </div>
          <div v-if="fileSizeText" class="props-row">
            <dt>文件大小</dt>
            <dd>{{ fileSizeText }}</dd>
          </div>
        </dl>
      </section>

      <el-divider />

      <!-- 格式说明 -->
      <section class="props-section">
        <div class="props-title">{{ isOfd ? '关于 OFD' : '关于 PDF' }}</div>
        <div class="props-text">
          <template v-if="isOfd">
            <p>
              <strong>OFD（Open Fixed-layout Document，开放版式文档）</strong>
              是我国国家标准 GB/T 33190 定义的版式电子文件格式，在定位上对标国际通用的 PDF。
            </p>
            <p>
              版式文档强调页面布局、字体与坐标固定不变，适合公文流转、电子发票、档案归档等
              「所见即所得、长期可读」的场景，在国内政务与行业应用中广泛采用。
            </p>
            <p>
              本应用采用非破坏性编辑思路：在保留原始 OFD 包结构的前提下，精准修改页面元素或
              写入注释层，尽量维持与原件的一致性。
            </p>
          </template>
          <template v-else>
            <p>
              <strong>PDF（Portable Document Format，便携式文档格式）</strong>
              由 Adobe 提出并已成为事实上的国际标准，是一种固定版式的电子文档格式。
            </p>
            <p>
              PDF 将文字、图形、字体等资源封装在统一文件中，跨操作系统与设备显示一致，
              不易因软件版本差异导致排版错乱，因此特别适合<strong>打印输出</strong>、
              合同传阅与长期保存。
            </p>
            <p>
              您当前文档由 PDF 导入并经本应用转换为 OFD 后在编辑器中打开；后续编辑与保存
              均以 OFD 形式进行，原 PDF 中的数字签名不会随转换保留。
            </p>
          </template>
        </div>
      </section>

      <!-- 安全提示 -->
      <el-alert
          v-if="isOfd"
          type="warning"
          :closable="false"
          show-icon
          class="props-alert"
          title="数字签章提示"
      >
        <template #default>
          <p class="alert-p">
            若原文档含有电子签章或数字签名（如发票红章、公文盖章），其法律效力依赖对文档内容的
            密码学校验。
          </p>
          <p class="alert-p">
            使用本应用<strong>修改正文</strong>、调整页面结构或<strong>添加/修改注释</strong>后，
            OFD 包内数据会发生变化，原有签章在官方验签时将<strong>视为失效</strong>。
            请在确有必要时再编辑；若仅浏览，建议使用手型工具，避免误改。
          </p>
          <p class="alert-p muted">
            说明：若打开后未做任何改动直接保存，后端可原样返回原始字节，签章状态不变。
          </p>
        </template>
      </el-alert>

      <el-alert
          v-else
          type="info"
          :closable="false"
          show-icon
          class="props-alert"
          title="编辑说明"
      >
        <template #default>
          <p class="alert-p">
            PDF 以原生方式打开（前端矢量渲染），保留原文字、字体与版式，不再转换为图片。
            当前支持在 PDF 上添加批注（高亮、图形、文本、图章、手绘等），暂不支持修改原文内容。
          </p>
          <p class="alert-p">
            导出时将批注以非破坏方式烘焙回原 PDF（原内容不变）。如原文档含数字签名，添加批注后
            原签名在官方验签时可能视为失效。
          </p>
        </template>
      </el-alert>
    </div>

    <template #footer>
      <el-button type="primary" @click="visible = false">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useEditorStore } from '@/stores/editorStore'

const visible = defineModel<boolean>({ default: false })

const store = useEditorStore()

const doc = computed(() => store.document)
const isOfd = computed(() => store.documentSource !== 'pdf')

const fileName = computed(() => store.currentFile?.name ?? '')
const fileSizeText = computed(() => {
  const size = store.currentFile?.size
  if (!size || size <= 0) return ''
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / (1024 * 1024)).toFixed(2)} MB`
})
</script>

<style scoped>
.doc-props-body {
  font-size: 13px;
  line-height: 1.65;
  color: var(--text-2);
}

.props-section {
  margin-bottom: 4px;
}

.props-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
  margin-bottom: 10px;
}

.props-dl {
  margin: 0;
}

.props-row {
  display: flex;
  gap: 12px;
  margin-bottom: 8px;
  align-items: flex-start;
}

.props-row dt {
  flex: 0 0 72px;
  margin: 0;
  color: var(--text-3);
  font-weight: 500;
}

.props-row dd {
  margin: 0;
  flex: 1;
  word-break: break-all;
  color: var(--text-1);
}

.props-text p {
  margin: 0 0 10px;
}

.props-text p:last-child {
  margin-bottom: 0;
}

.props-alert {
  margin-top: 16px;
}

.alert-p {
  margin: 0 0 8px;
  line-height: 1.6;
}

.alert-p:last-child {
  margin-bottom: 0;
}

.alert-p.muted {
  color: var(--text-3);
  font-size: 12px;
}

:deep(.el-alert__title) {
  font-weight: 600;
}
</style>
