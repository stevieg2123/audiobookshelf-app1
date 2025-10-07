<template>
  <div class="w-full h-full py-6 px-4 overflow-y-auto">
    <p class="mb-4 text-base text-fg">{{ $strings.HeaderDownloads }} ({{ downloadItems.length }})</p>

    <div v-if="!downloadItems.length" class="py-6 text-center text-lg text-fg text-opacity-70">
      {{ $strings.MessageNoDownloads || 'No downloads to show.' }}
    </div>

    <div
      v-for="download in downloadItems"
      :key="download.id"
      class="w-full mb-6 border border-border rounded-lg p-4"
    >
      <div class="flex items-start">
        <div class="flex-grow">
          <p class="text-base font-semibold text-fg truncate">{{ downloadTitle(download) }}</p>
          <p class="text-xs uppercase tracking-wide mt-1" :class="statusTextClass(download)">
            {{ statusLabel(download) }}
          </p>
          <p v-if="download.statusMessage" class="mt-1 text-xs text-warning">
            {{ download.statusMessage }}
          </p>

          <div class="mt-3">
            <div class="h-2 rounded-full bg-border overflow-hidden">
              <div
                class="h-full bg-primary transition-all duration-300"
                :style="{ width: `${progressPercent(download)}%` }"
              />
            </div>
            <p class="mt-1 text-xs text-fg text-opacity-70">{{ Math.round(progressPercent(download)) }}%</p>
          </div>
        </div>

        <div class="flex flex-col items-end ml-4 space-y-2">
          <button
            v-if="canResume(download)"
            class="px-3 py-1 text-xs font-semibold rounded-full bg-primary text-white shadow-sm"
            @click="resumeDownload(download)"
          >
            {{ resumeLabel(download) }}
          </button>
        </div>
      </div>

      <div v-if="download.downloadItemParts?.length" class="mt-4 border-t border-border pt-3 space-y-2">
        <div
          v-for="part in download.downloadItemParts"
          :key="part.id"
          class="flex items-center text-sm text-fg justify-between"
        >
          <span class="truncate pr-3">{{ part.filename }}</span>
          <span :class="part.failed ? 'text-error font-semibold' : 'text-fg text-opacity-70'">
            {{ partStatus(part) }}
          </span>
        </div>
      </div>
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
      return this.$store.state.globals.itemDownloads
    },
    isWeb() {
      return this.$platform === 'web'
    }
  },
  methods: {
    downloadTitle(download) {
      return download.itemTitle || download.media?.metadata?.title || this.$strings.LabelDownload
    },
    statusLabel(download) {
      if (download.status === 'failed') {
        return this.$strings.LabelDownloadStatusFailed || 'Failed'
      }
      if (download.status === 'stopped') {
        return this.$strings.LabelDownloadStatusStopped || 'Stopped'
      }
      return this.$strings.LabelDownloadStatusActive || 'Active'
    },
    statusTextClass(download) {
      if (download.status === 'failed') return 'text-error'
      if (download.status === 'stopped') return 'text-warning'
      return 'text-success'
    },
    partStatus(part) {
      if (part.failed) return this.$strings.LabelDownloadStatusFailed || 'Failed'
      if (part.completed) return this.$strings.LabelDownloaded || 'Done'
      return `${Math.round(part.progress || 0)}%`
    },
    progressPercent(download) {
      return Math.min(100, Math.max(0, Number(download.itemProgress || 0) * 100))
    },
    canResume(download) {
      return !this.isWeb && (download.status === 'stopped' || download.status === 'failed')
    },
    resumeLabel(download) {
      if (download.status === 'failed') {
        return this.$strings.LabelRestartDownload || 'Restart'
      }
      return this.$strings.LabelResumeDownload || 'Resume'
    },
    async resumeDownload(download) {
      if (this.isWeb) return

      try {
        await AbsDownloader.cancelDownloadItem({ downloadItemId: download.id })
      } catch (err) {
        console.warn('cancelDownloadItem error', err)
      }

      const payload = {
        libraryItemId: download.libraryItemId
      }

      if (download.episodeId) {
        payload.episodeId = download.episodeId
      }
      if (download.localFolder?.id) {
        payload.localFolderId = download.localFolder.id
      }

      const res = await AbsDownloader.downloadLibraryItem(payload)
      if (res && res.error) {
        const message = res.error || this.$strings.MessageDownloadFailed || 'Download failed'
        this.$toast.error(message)
        this.$store.commit('globals/setDownloadItemStatus', {
          id: download.id,
          status: 'failed',
          message
        })
        return
      }

      this.$store.commit('globals/setDownloadItemStatus', {
        id: download.id,
        status: 'active',
        message: null
      })
    }
  }
}
</script>
