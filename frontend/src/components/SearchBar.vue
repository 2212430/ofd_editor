<template>
  <transition name="search-fade">
    <div v-if="store.searchVisible" class="search-bar" @keydown.stop>
      <el-input
          ref="inputRef"
          v-model="query"
          class="search-input"
          placeholder="搜索文档内容…"
          clearable
          @input="onInput"
          @keydown.enter.prevent="onEnter"
          @keydown.esc.prevent="store.closeSearch()"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>

      <span class="search-count">
        <template v-if="store.searching">搜索中…</template>
        <template v-else-if="query && store.searchMatches.length === 0">无结果</template>
        <template v-else-if="store.searchMatches.length > 0">
          {{ store.searchActiveIndex + 1 }} / {{ store.searchMatches.length }}
        </template>
        <template v-else>&nbsp;</template>
      </span>

      <el-button-group>
        <el-button :icon="ArrowUp" :disabled="store.searchMatches.length === 0" @click="store.prevMatch()" />
        <el-button :icon="ArrowDown" :disabled="store.searchMatches.length === 0" @click="store.nextMatch()" />
      </el-button-group>

      <el-button :icon="Close" text class="search-close" @click="store.closeSearch()" />
    </div>
  </transition>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { Search, ArrowUp, ArrowDown, Close } from '@element-plus/icons-vue'
import { useEditorStore } from '@/stores/editorStore'

const store = useEditorStore()
const query = ref('')
const inputRef = ref()

let debounceTimer: ReturnType<typeof setTimeout> | null = null

function onInput() {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    void store.runSearch(query.value)
  }, 220)
}

function onEnter() {
  if (store.searchMatches.length === 0) {
    void store.runSearch(query.value)
  } else {
    store.nextMatch()
  }
}

watch(
    () => store.searchVisible,
    (visible) => {
      if (visible) {
        query.value = store.searchQuery
        void nextTick(() => inputRef.value?.focus?.())
      }
    },
)
</script>

<style scoped>
.search-bar {
  position: absolute;
  top: 14px;
  right: 24px;
  z-index: 60;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  background: var(--chrome-bg, #fff);
  border: 1px solid var(--line, #e0e0e0);
  border-radius: 8px;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.16);
}

.search-input {
  width: 240px;
}

.search-count {
  min-width: 64px;
  text-align: center;
  font-size: 12px;
  color: var(--text-3, #888);
  user-select: none;
}

.search-close {
  margin-left: 2px;
}

.search-fade-enter-active,
.search-fade-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}
.search-fade-enter-from,
.search-fade-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
