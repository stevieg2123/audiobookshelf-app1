<template>
  <div class="w-full h-full py-6 px-4 overflow-y-auto">
    <p class="mb-4 text-base text-fg">{{ $strings.HeaderDownloads }} ({{ downloadItems.length }})</p>

    <div v-if="!downloadItems.length" class="py-6 text-center text-lg">No downloads in progress</div>

    <div v-for="item in downloadItems" :key="item.id" class="w-full border border-border rounded-lg p-4 mb-4 bg-elevated">
      <div class="flex items-start justify-between">
        <div class="pr-4">
          <p class="text-lg font-semibold truncate">{{ itemTitle(item) }}</p>
          <p v-if="itemError(item)" class="text-sm text-warning mt-1">{{ itemError(item) }}</p>
          <p v-else class="text-sm text-subtle mt-1">{{ Math.round(itemProgress(item) * 100) }}%</p>
        </div>
        <div class="flex space-x-2">
          <ui-btn v-if="canResume(item)" small color="primary" @click="resumeDownload(item)">
            {{ $strings?.ButtonResume || 'Resume' }}
          </ui-btn>
          <ui-btn small color="error" @click="stopDownload(item)">
            {{ $strings?.ButtonStopDownload || 'Stop & Delete' }}
          </ui-btn>
        </div>
      </div>

      <div class="mt-4 space-y-2">
        <div v-for="part in item.downloadItemParts" :key="part.id" class="border border-border rounded px-3 py-2 bg-background">
          <div class="flex items-center justify-between">
            <p class="truncate text-sm">{{ part.filename }}</p>
            <p class="text-xs text-subtle ml-4">{{ partStatus(part) }}</p>
          </div>
          <div v-if="!part.completed && !part.paused" class="mt-2 h-1.5 bg-border rounded">
            <div class="h-full bg-primary rounded" :style="{ width: part.progress ? Math.min(100, Math.round(part.progress)) + '%' : '0%' }" />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'
import { AbsDownloader } from '@/plugins/capacitor'

export default {
  data() {
    return {}
  },
  computed: {
    downloadItems() {
      return this.$store.state.globals.itemDownloads
    }
  },
  methods: {
    itemTitle(item) {
      return item.itemTitle || item.media?.metadata?.title || 'Download'
    },
    itemProgress(item) {
      if (typeof item.itemProgress === 'number') return item.itemProgress
      let total = 0
      let downloaded = 0
      const parts = item.downloadItemParts || []
      parts.forEach((part) => {
        total += part.fileSize || 0
        downloaded += part.bytesDownloaded || 0
      })
      return total ? Math.min(1, downloaded / total) : 0
    },
    itemError(item) {
      const parts = item.downloadItemParts || []
      const failedPart = parts.find((part) => part.paused && (part.errorMessage || part.failed))
      if (failedPart?.errorMessage) return failedPart.errorMessage
      if (failedPart) return this.$strings?.MessageDownloadFailed || 'Download failed. Resume to continue.'
      return null
    },
    canResume(item) {
      const parts = item.downloadItemParts || []
      return parts.some((part) => part.paused || part.failed)
    },
    partStatus(part) {
      if (part.completed && part.moved) return this.$strings?.LabelCompleted || 'Completed'
      if (part.paused && part.errorMessage) return `${this.$strings?.LabelPaused || 'Paused'} - ${part.errorMessage}`
      if (part.paused) return this.$strings?.LabelPaused || 'Paused'
      if (part.failed && !part.paused) return this.$strings?.MessageDownloadFailed || 'Failed'
      if (part.completed) return this.$strings?.LabelCompleted || 'Completed'
      return `${Math.round(part.progress || 0)}%`
    },
    async resumeDownload(item) {
      try {
        const res = await AbsDownloader.resumeDownloadItem({ downloadItemId: item.id })
        if (res && res.error) {
          this.$toast.error(res.error)
        } else {
          this.$toast.success(this.$strings?.MessageDownloadResumed || 'Download resumed')
        }
      } catch (error) {
        console.error('Failed to resume download', error)
        this.$toast.error(this.$strings?.MessageDownloadFailed || 'Failed to resume download')
      }
    },
    async stopDownload(item) {
      try {
        const confirmMessage = this.$strings?.MessageConfirmStopDownload
          ? this.$strings.MessageConfirmStopDownload.replace('{0}', this.itemTitle(item))
          : `Stop downloading "${this.itemTitle(item)}" and delete downloaded files?`
        const { value } = await Dialog.confirm({
          title: this.$strings?.Confirm || 'Confirm',
          message: confirmMessage
        })
        if (!value) return

        const res = await AbsDownloader.cancelDownloadItem({ downloadItemId: item.id, deleteFiles: true })
        if (res && res.error) {
          this.$toast.error(res.error)
        } else {
          this.$toast.success(this.$strings?.MessageDownloadCancelled || 'Download cancelled.')
          this.$store.commit('globals/removeItemDownload', item.id)
        }
      } catch (error) {
        console.error('Failed to cancel download', error)
        this.$toast.error(this.$strings?.MessageDownloadFailed || 'Failed to cancel download')
      }
    }
  }
}
</script>

