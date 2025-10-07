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
      downloadPaused: false,
      socketDisconnectToastShown: false
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
    },
    socketConnected() {
      return this.$store.state.socketConnected
    },
    hasActiveDownloads() {
      return this.downloadItemPartsRemaining.length > 0
    }
  },
  watch: {
    socketConnected(newVal, oldVal) {
      if (oldVal && !newVal && this.hasActiveDownloads) {
        this.$toast.warning(this.$strings.ToastDownloadSocketDisconnected)
        this.socketDisconnectToastShown = true
      } else if (newVal) {
        this.socketDisconnectToastShown = false
      }
    },
    hasActiveDownloads(newVal) {
      if (!newVal) {
        this.socketDisconnectToastShown = false
        return
      }
      if (!this.socketConnected && !this.socketDisconnectToastShown) {
        this.$toast.warning(this.$strings.ToastDownloadSocketDisconnected)
        this.socketDisconnectToastShown = true
      }
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

      downloadItem.itemProgress = 0
      downloadItem.episodes = downloadItem.downloadItemParts.filter((dip) => dip.episode).map((dip) => dip.episode)

      this.$store.commit('globals/addUpdateItemDownload', downloadItem)
    },
    onDownloadItemPartUpdate(itemPart) {
      this.$store.commit('globals/updateDownloadItemPart', itemPart)
    },
    async startItemPartListener() {
      if (this.itemPartUpdateListener) return
      this.itemPartUpdateListener = await AbsDownloader.addListener('onDownloadItemPartUpdate', (data) =>
        this.onDownloadItemPartUpdate(data)
      )
    },
    stopItemPartListener() {
      if (!this.itemPartUpdateListener) return
      this.itemPartUpdateListener.remove()
      this.itemPartUpdateListener = null
    },
    async pauseDownloads() {
      if (this.downloadPaused) return
      this.downloadPaused = true
      this.stopItemPartListener()
      try {
        await AbsDownloader.pauseActiveDownloads()
      } catch (error) {
        console.error('[DownloadProgressIndicator] Failed to pause downloads', error)
        await this.startItemPartListener()
        this.downloadPaused = false
      }
    },
    async resumeDownloads() {
      if (!this.downloadPaused) return
      try {
        await AbsDownloader.resumeActiveDownloads()
      } catch (error) {
        console.error('[DownloadProgressIndicator] Failed to resume downloads', error)
      }
      await this.startItemPartListener()
      this.downloadPaused = false
    },
    async handleDeviceFocusUpdate(hasFocus) {
      if (hasFocus) {
        await this.resumeDownloads()
      } else {
        await this.pauseDownloads()
      }
    }
  },
  async mounted() {
    this.downloadItemListener = await AbsDownloader.addListener('onDownloadItem', (data) => this.onDownloadItem(data))
    await this.startItemPartListener()
    this.completeListener = await AbsDownloader.addListener('onItemDownloadComplete', (data) => this.onItemDownloadComplete(data))
    this.$eventBus.$on('device-focus-update', this.handleDeviceFocusUpdate)
    if (document.visibilityState === 'hidden') {
      await this.pauseDownloads()
    }
  },
  beforeDestroy() {
    this.downloadItemListener?.remove()
    this.completeListener?.remove()
    this.stopItemPartListener()
    this.$eventBus.$off('device-focus-update', this.handleDeviceFocusUpdate)
  }
}
</script>