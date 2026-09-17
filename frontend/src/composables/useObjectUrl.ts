import { ref } from 'vue'

export interface ObjectUrlOptions<T> {
  loadBlob: (value: T, signal: AbortSignal) => Promise<Blob>
  createObjectUrl?: (blob: Blob) => string
  revokeObjectUrl?: (url: string) => void
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : '图片加载失败，请稍后重试。'
}

export function useObjectUrl<T>(options: ObjectUrlOptions<T>) {
  const url = ref<string | null>(null)
  const loading = ref(false)
  const error = ref('')
  const createObjectUrl = options.createObjectUrl ?? (blob => URL.createObjectURL(blob))
  const revokeObjectUrl = options.revokeObjectUrl ?? (value => URL.revokeObjectURL(value))
  let controller: AbortController | undefined
  let generation = 0

  function release() {
    if (url.value) revokeObjectUrl(url.value)
    url.value = null
  }

  function clear() {
    generation++
    controller?.abort()
    controller = undefined
    release()
    loading.value = false
    error.value = ''
  }

  async function load(value: T): Promise<void> {
    clear()
    const current = ++generation
    const nextController = new AbortController()
    controller = nextController
    loading.value = true
    try {
      const blob = await options.loadBlob(value, nextController.signal)
      if (current !== generation || nextController.signal.aborted) return
      if (!blob.size || !blob.type.toLowerCase().startsWith('image/')) throw new Error('图片加载失败，请重试。')
      url.value = createObjectUrl(blob)
    } catch (cause) {
      if (current !== generation || nextController.signal.aborted) return
      error.value = message(cause)
      throw cause
    } finally {
      if (current === generation) {
        loading.value = false
        if (controller === nextController) controller = undefined
      }
    }
  }

  return { url, loading, error, load, clear, dispose: clear }
}
