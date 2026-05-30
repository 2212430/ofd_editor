<template>
  <button
      type="button"
      class="ribbon-btn"
      :class="{ active, disabled: disabled || loading }"
      :disabled="disabled || loading"
      :title="tooltip || label"
      @click="$emit('click')"
  >
    <span class="ribbon-btn-icon">
      <slot name="icon">
        <el-icon v-if="icon"><component :is="icon" /></el-icon>
      </slot>
    </span>
    <span class="ribbon-btn-label">{{ label }}</span>
  </button>
</template>

<script setup lang="ts">
import type { Component } from 'vue'

defineProps<{
  label: string
  icon?: Component
  active?: boolean
  disabled?: boolean
  loading?: boolean
  tooltip?: string
}>()

defineEmits<{ (e: 'click'): void }>()
</script>

<style scoped>
.ribbon-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-width: 52px;
  max-width: 72px;
  height: 62px;
  padding: 6px 8px 4px;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  color: var(--text-1);
  cursor: pointer;
  transition: background .12s, border-color .12s, color .12s;
  flex-shrink: 0;
}
.ribbon-btn:hover:not(.disabled) {
  background: rgba(0, 0, 0, .04);
  border-color: rgba(0, 0, 0, .06);
}
.ribbon-btn.active {
  background: var(--ribbon-btn-active-bg, #fff3e8);
  border-color: var(--ribbon-accent-soft, #ffd4b0);
  color: var(--ribbon-accent, #e87722);
}
.ribbon-btn.disabled {
  opacity: .42;
  cursor: not-allowed;
}
.ribbon-btn-icon {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  font-size: 22px;
  line-height: 1;
}
.ribbon-btn-icon :deep(svg) { width: 22px; height: 22px; }
.ribbon-btn-label {
  font-size: 11px;
  line-height: 1.15;
  text-align: center;
  white-space: nowrap;
  max-width: 64px;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
