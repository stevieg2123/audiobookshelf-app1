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
      itemPartUpdateListener: null,
      failureListener: null
    }
  },
  computed: {
    downloadItems() {
      return this.$store.state.globals.itemDownloads
    },
    downloadItemParts() {
      let parts = []
      this.downloadItems.forEach((di) => parts.push(...di.downloadItemParts))
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
    clickedIt() {
      this.$router.push('/downloading')
    },
    onItemDownloadComplete(data) {
      console.log('DownloadProgressIndicator onItemDownloadComplete', JSON.stringify(data))
      if (!data || !data.libraryItemId) {
        console.error('Invalid item download complete payload')
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

      this.$store.commit('globals/removeItemDownload', data.libraryItemId)
    },
    onDownloadItem(downloadItem) {
      console.log('DownloadProgressIndicator onDownloadItem', JSON.stringify(downloadItem))

      if (typeof downloadItem.itemProgress !== 'number') {
        downloadItem.itemProgress = 0
      }
      downloadItem.episodes = downloadItem.downloadItemParts.filter((dip) => dip.episode).map((dip) => dip.episode)

      this.$store.commit('globals/addUpdateItemDownload', downloadItem)
    },
    onDownloadItemPartUpdate(itemPart) {
      this.$store.commit('globals/updateDownloadItemPart', itemPart)
    },
    onDownloadFailed(data) {
      if (!data) return
      if (Array.isArray(data.parts)) {
        data.parts.forEach((part) => this.$store.commit('globals/updateDownloadItemPart', part))
      }

      const downloadItemId = data.downloadItemId || data.libraryItemId
      const existing = this.downloadItems.find((item) => item.id === downloadItemId || item.libraryItemId === data.libraryItemId)
      const title = data.itemTitle || existing?.itemTitle || existing?.media?.metadata?.title || this.$strings.LabelDownload
      const message = this.$getString('ToastDownloadInterrupted', [title]) || this.$strings.ToastDownloadInterrupted
      this.$toast.error(message)
    },
    async syncActiveDownloads() {
      try {
        const queue = await AbsDownloader.getDownloadQueue()
        if (queue && Array.isArray(queue.items)) {
          queue.items.forEach((item) => this.onDownloadItem(item))
        }
      } catch (error) {
        console.error('Failed to load active downloads', error)
      }
    }
  },
  async mounted() {
    this.syncActiveDownloads()
    this.downloadItemListener = await AbsDownloader.addListener('onDownloadItem', (data) => this.onDownloadItem(data))
    this.itemPartUpdateListener = await AbsDownloader.addListener('onDownloadItemPartUpdate', (data) => this.onDownloadItemPartUpdate(data))
    this.completeListener = await AbsDownloader.addListener('onItemDownloadComplete', (data) => this.onItemDownloadComplete(data))
    this.failureListener = await AbsDownloader.addListener('onDownloadItemFailed', (data) => this.onDownloadFailed(data))
  },
  beforeDestroy() {
    this.downloadItemListener?.remove()
    this.completeListener?.remove()
    this.itemPartUpdateListener?.remove()
    this.failureListener?.remove()
  }
}
</script>