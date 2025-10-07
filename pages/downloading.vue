<template>
  <div class="w-full h-full py-6 px-4 overflow-y-auto">
    <p class="mb-4 text-base text-fg">{{ $strings.HeaderDownloads }} ({{ downloadItems.length }})</p>

    <div v-if="!downloadItems.length" class="py-6 text-center text-lg text-fg-muted">
      {{ $strings.MessageNoActiveDownloads }}
    </div>

    <div v-for="(item, index) in downloadItems" :key="item.id" class="pb-4">
      <div class="flex items-start justify-between">
        <div class="pr-4 flex-1 min-w-0">
          <p class="text-sm font-medium text-fg truncate">{{ downloadTitle(item) }}</p>
          <p class="text-xs mt-1" :class="item.failed ? 'text-error' : 'text-fg-muted'">
            <template v-if="item.failed">
              {{ $strings.LabelDownloadInterrupted }}
            </template>
            <template v-else>
              {{ itemProgressPercent(item) }}%
            </template>
          </p>
        </div>
        <button
          v-if="item.failed"
          class="px-3 py-1 text-xs font-semibold border border-accent text-accent rounded-full"
          @click.stop="resumeDownload(item)"
        >
          {{ $strings.LabelRetry }}
        </button>
      </div>

      <div class="mt-3 space-y-2">
        <div
          v-for="part in item.downloadItemParts"
          :key="part.id"
          class="flex items-center justify-between text-xs"
        >
          <span class="truncate mr-4 text-fg-muted">{{ part.filename }}</span>
          <span :class="partStatusClass(part)">{{ partStatus(part) }}</span>
        </div>
      </div>

      <div v-if="index + 1 < downloadItems.length" class="border-t border-border mt-4 pt-4" />
    </div>
  </div>
</template>

<script>
import { AbsDownloader } from '@/plugins/capacitor'

export default {
  data() {
    return {}
  },
  computed: {
    downloadItems() {
      return this.$store.state.globals.itemDownloads || []
    }
  },
  methods: {
    downloadTitle(item) {
      if (!item) return this.$strings.LabelDownload
      return item.itemTitle || item.media?.metadata?.title || this.$strings.LabelDownload
    },
    itemProgressPercent(item) {
      if (!item || typeof item.itemProgress !== 'number') return 0
      const percent = Math.round(item.itemProgress * 100)
      return Math.min(100, Math.max(0, percent))
    },
    partStatus(part) {
      if (!part) return ''
      if (part.failed) return this.$strings.LabelDownloadInterrupted
      if (part.completed && !part.failed) return this.$strings.LabelDownloaded
      const percent = Math.round(Number(part.progress) || 0)
      return `${Math.min(100, Math.max(0, percent))}%`
    },
    partStatusClass(part) {
      if (part?.failed) return 'text-error font-medium'
      if (part?.completed && !part?.failed) return 'text-success'
      return 'text-fg-muted'
    },
    async resumeDownload(item) {
      if (!item?.id) return
      try {
        const res = await AbsDownloader.resumeDownload({ downloadItemId: item.id })
        if (res && res.error) throw new Error(res.error)
        const message = this.$getString('ToastDownloadResumed', [this.downloadTitle(item)])
        this.$toast.success(message)
      } catch (error) {
        console.error('Failed to resume download', error)
        const message = this.$getString('ToastDownloadInterrupted', [this.downloadTitle(item)]) || this.$strings.ToastDownloadInterrupted
        this.$toast.error(message)
      }
    }
  }
}
</script>

