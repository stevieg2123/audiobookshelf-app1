import { registerPlugin, WebPlugin } from '@capacitor/core';

class AbsDownloaderWeb extends WebPlugin {
  constructor() {
    super()
  }

  async pauseActiveDownloads() {
    return
  }

  async resumeActiveDownloads() {
    return
  }
}

const AbsDownloader = registerPlugin('AbsDownloader', {
  web: () => new AbsDownloaderWeb()
})

export { AbsDownloader }