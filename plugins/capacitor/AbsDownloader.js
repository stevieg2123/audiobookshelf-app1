import { registerPlugin, WebPlugin } from '@capacitor/core';

class AbsDownloaderWeb extends WebPlugin {
  constructor() {
    super()
  }

  async cancelDownloadItem() {
    return { success: false, error: 'cancelDownloadItem is not available on web platform' }
  }
}

const AbsDownloader = registerPlugin('AbsDownloader', {
  web: () => new AbsDownloaderWeb()
})

export { AbsDownloader }