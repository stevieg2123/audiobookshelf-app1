<template>
  <div v-if="downloadItemPartsRemaining.length" @click="clickedIt">
    <widgets-circle-progress :value="progress" :count="downloadItemPartsRemaining.length" />
  </div>
</template>

<script>
import { AbsDownloader } from '@/plugins/capacitor'

export default {
  data() {
    return {
      downloadItemListener: null,
      completeListener: null,
      itemPartUpdateListener: null
    }
  },
  computed: {
    downloadItems() {
      return this.$store.state.globals.itemDownloads
    },
    activeDownloadItems() {
      return this.downloadItems.filter((downloadItem) => downloadItem.status === 'active')
    },
    downloadItemParts() {
      let parts = []
      this.activeDownloadItems.forEach((di) => {
        if (di.downloadItemParts?.length) {
          parts.push(...di.downloadItemParts)
        }
      })
      return parts
    },
    downloadItemPartsRemaining() {
      return this.downloadItemParts.filter((dip) => !dip.completed)
    },
    progress() {
      let totalBytes = 0
      let totalBytesDownloaded = 0
      this.downloadItemParts.forEach((dip) => {
        totalBytes += dip.fileSize
        totalBytesDownloaded += dip.bytesDownloaded
      })

      if (!totalBytes) return 0
      return Math.min(1, totalBytesDownloaded / totalBytes)
    },
    isIos() {
      return this.$platform === 'ios'
    }
  },
  methods: {
    resolveDownloadItem(data) {
      if (!data) return null

      if (data.downloadItemId) {
        return this.downloadItems.find((di) => di.id === data.downloadItemId)
      }

      if (data.episodeId) {
        return this.downloadItems.find(
          (di) => di.libraryItemId === data.libraryItemId && di.episodeId === data.episodeId
        )
      }

      return this.downloadItems.find((di) => di.libraryItemId === data.libraryItemId)
    },
    clickedIt() {
      this.$router.push('/downloading')
    },
    onItemDownloadComplete(data) {
      console.log('DownloadProgressIndicator onItemDownloadComplete', JSON.stringify(data))
      if (!data || (!data.libraryItemId && !data.downloadItemId)) {
        console.error('Invalid item download complete payload')
        return
      }

      const downloadItem = this.resolveDownloadItem(data)
      const hasFailedParts = downloadItem?.downloadItemParts?.some((part) => part.failed)
      const wasCancelled = !!data.cancelled
      const failed = !!data.failed || hasFailedParts

      if (downloadItem && (failed || wasCancelled)) {
        const status = wasCancelled ? 'stopped' : 'failed'
        const messageKey = wasCancelled
          ? 'MessageDownloadsStoppedConnectionLost'
          : 'MessageDownloadFailed'
        const message = this.$strings?.[messageKey] || null
        this.$store.commit('globals/setDownloadItemStatus', {
          id: downloadItem.id,
          status,
          message
        })

        if (failed && !wasCancelled && message) {
          this.$toast.error(message)
        }

        return
      }

      if (!data.localLibraryItem) {
        this.$toast.error(this.$strings.MessageItemDownloadCompleteFailedToCreate)
      } else {
        this.$eventBus.$emit('new-local-library-item', data.localLibraryItem)
      }

      if (data.localMediaProgress) {
        console.log('onItemDownloadComplete updating local media progress', data.localMediaProgress.id)
        this.$store.commit('globals/updateLocalMediaProgress', data.localMediaProgress)
      }

      const removalId = downloadItem?.id || data.downloadItemId || data.libraryItemId
      this.$store.commit('globals/removeItemDownload', removalId)
    },
    onDownloadItem(downloadItem) {
      console.log('DownloadProgressIndicator onDownloadItem', JSON.stringify(downloadItem))

      downloadItem.itemProgress = 0
      downloadItem.status = 'active'
      downloadItem.statusMessage = null
      downloadItem.episodes = downloadItem.downloadItemParts
        .filter((dip) => dip.episode)
        .map((dip) => dip.episode)

      this.$store.commit('globals/addUpdateItemDownload', downloadItem)
    },
    onDownloadItemPartUpdate(itemPart) {
      this.$store.commit('globals/updateDownloadItemPart', itemPart)
    }
  },
  async mounted() {
    this.downloadItemListener = await AbsDownloader.addListener('onDownloadItem', (data) => this.onDownloadItem(data))
    this.itemPartUpdateListener = await AbsDownloader.addListener('onDownloadItemPartUpdate', (data) => this.onDownloadItemPartUpdate(data))
    this.completeListener = await AbsDownloader.addListener('onItemDownloadComplete', (data) => this.onItemDownloadComplete(data))
  },
  beforeDestroy() {
    this.downloadItemListener?.remove()
    this.completeListener?.remove()
    this.itemPartUpdateListener?.remove()
  }
}
</script>