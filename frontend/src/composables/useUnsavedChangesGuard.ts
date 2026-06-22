import { ElMessageBox } from 'element-plus'
import { useEditorStore } from '@/stores/editorStore'

/**
 * 若当前文档有未保存修改，弹出确认框。
 * @returns true 表示可以继续（无修改或用户确认放弃）；false 表示用户取消
 */
export async function confirmDiscardUnsavedChanges(
    actionHint = '继续此操作',
): Promise<boolean> {
    const store = useEditorStore()
    if (!store.hasUnsavedChanges) return true

    try {
        await ElMessageBox.confirm(
            `当前文档有未保存的修改，${actionHint}将丢失这些更改。是否继续？`,
            '未保存的修改',
            {
                type: 'warning',
                confirmButtonText: '放弃修改',
                cancelButtonText: '取消',
                distinguishCancelAndClose: true,
            },
        )
        return true
    } catch {
        return false
    }
}

/** 注册浏览器关闭/刷新时的原生提示（无法自定义文案，仅触发系统对话框） */
export function registerBeforeUnloadGuard(): () => void {
    const handler = (e: BeforeUnloadEvent) => {
        const store = useEditorStore()
        if (!store.hasUnsavedChanges) return
        e.preventDefault()
        e.returnValue = ''
    }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
}
