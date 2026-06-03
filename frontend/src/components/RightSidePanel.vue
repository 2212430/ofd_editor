<template>
  <aside class="right-sidebar">
    <div class="right-sidebar-tabs">
      <button
          type="button"
          class="tab-btn"
          :class="{ active: store.rightPanelTab === 'properties' }"
          @click="store.rightPanelTab = 'properties'"
      >
        属性
      </button>
      <button
          type="button"
          class="tab-btn"
          :class="{ active: store.rightPanelTab === 'annotations' }"
          @click="store.rightPanelTab = 'annotations'"
      >
        注释
        <span v-if="store.annotationCount > 0" class="tab-badge">{{ store.annotationCount }}</span>
      </button>
    </div>
    <PropertyPanel v-show="store.rightPanelTab === 'properties'" class="right-sidebar-pane" />
    <AnnotationListPanel v-show="store.rightPanelTab === 'annotations'" class="right-sidebar-pane" />
  </aside>
</template>

<script setup lang="ts">
import { useEditorStore } from '@/stores/editorStore'
import PropertyPanel from '@/components/PropertyPanel.vue'
import AnnotationListPanel from '@/components/AnnotationListPanel.vue'

const store = useEditorStore()
</script>

<style scoped>
.right-sidebar {
  width: 260px;
  min-width: 260px;
  background: var(--panel-bg);
  border-left: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.right-sidebar-tabs {
  display: flex;
  flex-shrink: 0;
  border-bottom: 1px solid var(--line);
  background: #f5f5f5;
}

.tab-btn {
  flex: 1;
  height: 36px;
  border: none;
  background: transparent;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-2);
  cursor: pointer;
  position: relative;
  transition: color 0.15s, background 0.15s;
}

.tab-btn:hover {
  color: var(--text-1);
  background: #ebebeb;
}

.tab-btn.active {
  color: var(--ribbon-accent);
  background: var(--panel-bg);
  box-shadow: inset 0 -2px 0 var(--ribbon-accent);
}

.tab-badge {
  display: inline-block;
  min-width: 16px;
  margin-left: 4px;
  padding: 0 5px;
  font-size: 10px;
  font-weight: 700;
  line-height: 16px;
  border-radius: 8px;
  background: var(--ribbon-accent);
  color: #fff;
  vertical-align: middle;
}

.right-sidebar-pane {
  flex: 1;
  min-height: 0;
  width: 100% !important;
  min-width: 0 !important;
  border-left: none !important;
}
</style>
