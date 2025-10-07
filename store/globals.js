export const state = () => ({
  isModalOpen: false,
  itemDownloads: [],
  bookshelfListView: false,
  series: null,
  localMediaProgress: [],
  lastSearch: null,
  jumpForwardItems: [
    {
      icon: 'forward_5',
      value: 5
    },
    {
      icon: 'forward_10',
      value: 10
    },
    {
      icon: 'forward_30',
      value: 30
    }
  ],
  jumpBackwardsItems: [
    {
      icon: 'replay_5',
      value: 5
    },
    {
      icon: 'replay_10',
      value: 10
    },
    {
      icon: 'replay_30',
      value: 30
    }
  ],
  libraryIcons: ['database', 'audiobookshelf', 'books-1', 'books-2', 'book-1', 'microphone-1', 'microphone-3', 'radio', 'podcast', 'rss', 'headphones', 'music', 'file-picture', 'rocket', 'power', 'star', 'heart'],
  selectedPlaylistItems: [],
  showPlaylistsAddCreateModal: false,
  showSelectLocalFolderModal: false,
  localFolderSelectData: null,
  hapticFeedback: 'LIGHT',
  showRSSFeedOpenCloseModal: false,
  rssFeedEntity: null
})

export const getters = {
  getDownloadItem:
    (state) =>
    (libraryItemId, episodeId = null) => {
      return state.itemDownloads.find((i) => {
        // if (episodeId && !i.episodes.some(e => e.id == episodeId)) return false
        if (episodeId && i.episodeId !== episodeId) return false
        return i.libraryItemId == libraryItemId
      })
    },
  getLibraryItemCoverSrc:
    (state, getters, rootState, rootGetters) =>
    (libraryItem, placeholder, raw = false) => {
      if (!libraryItem) return placeholder
      const media = libraryItem.media
      if (!media || !media.coverPath || media.coverPath === placeholder) return placeholder

      // Absolute URL covers (should no longer be used)
      if (media.coverPath.startsWith('http:') || media.coverPath.startsWith('https:')) return media.coverPath

      const serverAddress = rootGetters['user/getServerAddress']
      if (!serverAddress) return placeholder

      const lastUpdate = libraryItem.updatedAt || Date.now()

      if (process.env.NODE_ENV !== 'production') {
        // Testing
        // return `http://localhost:3333/api/items/${libraryItem.id}/cover?ts=${lastUpdate}`
      }

      const url = new URL(`${serverAddress}/api/items/${libraryItem.id}/cover`)
      const urlQuery = new URLSearchParams()
      urlQuery.append('ts', lastUpdate)
      if (raw) urlQuery.append('raw', '1')
      if (rootGetters.getDoesServerImagesRequireToken) {
        urlQuery.append('token', rootGetters['user/getToken'])
      }
      return `${url}?${urlQuery}`
    },
  getLibraryItemCoverSrcById:
    (state, getters, rootState, rootGetters) =>
    (libraryItemId, placeholder = null) => {
      if (!placeholder) placeholder = `${rootState.routerBasePath}/book_placeholder.jpg`
      if (!libraryItemId) return placeholder
      const serverAddress = rootGetters['user/getServerAddress']
      if (!serverAddress) return placeholder

      const url = new URL(`${serverAddress}/api/items/${libraryItemId}/cover`)
      if (rootGetters.getDoesServerImagesRequireToken) {
        return `${url}?token=${rootGetters['user/getToken']}`
      }
      return url.toString()
    },
  getLocalMediaProgressById:
    (state) =>
    (localLibraryItemId, episodeId = null) => {
      return state.localMediaProgress.find((lmp) => {
        if (episodeId != null && lmp.localEpisodeId != episodeId) return false
        return lmp.localLibraryItemId == localLibraryItemId
      })
    },
  getLocalMediaProgressByServerItemId:
    (state) =>
    (libraryItemId, episodeId = null) => {
      return state.localMediaProgress.find((lmp) => {
        if (episodeId != null && lmp.episodeId != episodeId) return false
        return lmp.libraryItemId == libraryItemId
      })
    },
  getJumpForwardIcon: (state) => (jumpForwardTime) => {
    const item = state.jumpForwardItems.find((i) => i.value == jumpForwardTime)
    return item ? item.icon : 'forward_10'
  },
  getJumpBackwardsIcon: (state) => (jumpBackwardsTime) => {
    const item = state.jumpBackwardsItems.find((i) => i.value == jumpBackwardsTime)
    return item ? item.icon : 'replay_10'
  }
}

export const actions = {
  async loadLocalMediaProgress({ state, commit }) {
    const mediaProgress = await this.$db.getAllLocalMediaProgress()
    commit('setLocalMediaProgress', mediaProgress)
  }
}

const normalizeDownloadItemPart = (part) => {
  return {
    ...part,
    bytesDownloaded: Number(part.bytesDownloaded) || 0,
    fileSize: Number(part.fileSize) || 0
  }
}

const normalizeDownloadItem = (downloadItem) => {
  const normalizedParts = (downloadItem.downloadItemParts || []).map((part) => normalizeDownloadItemPart(part))

  return {
    ...downloadItem,
    downloadItemParts: normalizedParts,
    itemProgress: downloadItem.itemProgress || 0,
    status: downloadItem.status || 'active',
    statusMessage: downloadItem.statusMessage || null,
    lastUpdated: Date.now()
  }
}

const computeItemProgress = (downloadItemParts) => {
  let totalBytes = 0
  let totalBytesDownloaded = 0

  downloadItemParts.forEach((part) => {
    totalBytes += part.completed ? Number(part.bytesDownloaded) : Number(part.fileSize)
    totalBytesDownloaded += Number(part.bytesDownloaded)
  })

  if (!totalBytes) return 0
  return Math.min(1, totalBytesDownloaded / totalBytes)
}

export const mutations = {
  setIsModalOpen(state, val) {
    state.isModalOpen = val
  },
  addUpdateItemDownload(state, downloadItem) {
    const normalized = normalizeDownloadItem(downloadItem)
    const index = state.itemDownloads.findIndex((i) => i.id == normalized.id)
    if (index >= 0) {
      state.itemDownloads.splice(index, 1, normalized)
    } else {
      state.itemDownloads.push(normalized)
    }
  },
  updateDownloadItemPart(state, downloadItemPart) {
    const downloadIndex = state.itemDownloads.findIndex((i) => i.id == downloadItemPart.downloadItemId)
    if (downloadIndex === -1) {
      console.error('updateDownloadItemPart: Download item not found for itemPart', JSON.stringify(downloadItemPart))
      return
    }

    const downloadItem = state.itemDownloads[downloadIndex]

    const updatedParts = downloadItem.downloadItemParts.map((dip) => {
      if (dip.id === downloadItemPart.id) {
        return normalizeDownloadItemPart({ ...dip, ...downloadItemPart })
      }
      return dip
    })

    const itemProgress = computeItemProgress(updatedParts)
    let status = downloadItem.status
    let statusMessage = downloadItem.statusMessage

    if (downloadItemPart.failed) {
      status = 'failed'
    }

    const updatedDownloadItem = {
      ...downloadItem,
      downloadItemParts: updatedParts,
      itemProgress,
      status,
      statusMessage,
      lastUpdated: Date.now()
    }

    console.log(
      `updateDownloadItemPart: filename=${downloadItemPart.filename}, itemProgress=${updatedDownloadItem.itemProgress}, status=${updatedDownloadItem.status}`
    )

    state.itemDownloads.splice(downloadIndex, 1, updatedDownloadItem)
  },
  removeItemDownload(state, id) {
    state.itemDownloads = state.itemDownloads.filter((i) => i.id != id)
  },
  setDownloadItemStatus(state, { id, status, message = null }) {
    const index = state.itemDownloads.findIndex((i) => i.id === id)
    if (index === -1) return

    const downloadItem = state.itemDownloads[index]
    const updatedDownloadItem = {
      ...downloadItem,
      status,
      statusMessage: message,
      lastUpdated: Date.now()
    }

    state.itemDownloads.splice(index, 1, updatedDownloadItem)
  },
  markAllDownloadsStopped(state, { reason = null } = {}) {
    const timestamp = Date.now()
    state.itemDownloads = state.itemDownloads.map((downloadItem) => {
      if (downloadItem.status === 'completed' || downloadItem.status === 'failed') return downloadItem
      return {
        ...downloadItem,
        status: 'stopped',
        statusMessage: reason,
        lastUpdated: timestamp
      }
    })
  },
  setBookshelfListView(state, val) {
    state.bookshelfListView = val
  },
  setSeries(state, val) {
    state.series = val
  },
  setLocalMediaProgress(state, val) {
    state.localMediaProgress = val
  },
  updateLocalMediaProgress(state, prog) {
    if (!prog || !prog.id) {
      return
    }
    var index = state.localMediaProgress.findIndex((lmp) => lmp.id == prog.id)
    if (index >= 0) {
      state.localMediaProgress.splice(index, 1, prog)
    } else {
      state.localMediaProgress.push(prog)
    }
  },
  removeLocalMediaProgress(state, id) {
    state.localMediaProgress = state.localMediaProgress.filter((lmp) => lmp.id != id)
  },
  removeLocalMediaProgressForItem(state, llid) {
    state.localMediaProgress = state.localMediaProgress.filter((lmp) => lmp.localLibraryItemId !== llid)
  },
  setLastSearch(state, val) {
    state.lastSearch = val
  },
  setSelectedPlaylistItems(state, items) {
    state.selectedPlaylistItems = items
  },
  setShowPlaylistsAddCreateModal(state, val) {
    state.showPlaylistsAddCreateModal = val
  },
  showSelectLocalFolderModal(state, data) {
    state.localFolderSelectData = data
    state.showSelectLocalFolderModal = true
  },
  setShowSelectLocalFolderModal(state, val) {
    state.showSelectLocalFolderModal = val
  },
  setHapticFeedback(state, val) {
    state.hapticFeedback = val || 'LIGHT'
  },
  setShowRSSFeedOpenCloseModal(state, val) {
    state.showRSSFeedOpenCloseModal = val
  },
  setRSSFeedOpenCloseModal(state, entity) {
    state.rssFeedEntity = entity
    state.showRSSFeedOpenCloseModal = true
  }
}
