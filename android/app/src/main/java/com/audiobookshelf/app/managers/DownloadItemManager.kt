package com.audiobookshelf.app.managers

import android.app.DownloadManager
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.callback.FileCallback
import com.anggrayudi.storage.file.DocumentFileCompat
import com.anggrayudi.storage.file.MimeType
import com.anggrayudi.storage.file.getAbsolutePath
import com.anggrayudi.storage.file.moveFileTo
import com.anggrayudi.storage.media.FileDescription
import com.audiobookshelf.app.MainActivity
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.JSObject
import java.io.File
import java.io.FileOutputStream
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call

/** Manages download items and their parts. */
class DownloadItemManager(
        var downloadManager: DownloadManager,
        private var folderScanner: FolderScanner,
        var mainActivity: MainActivity,
        private var clientEventEmitter: DownloadEventEmitter
) {
  val tag = "DownloadItemManager"
  private val maxSimultaneousDownloads = 3
  private var jacksonMapper =
          jacksonObjectMapper()
                  .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  enum class DownloadCheckStatus {
    InProgress,
    Successful,
    Failed
  }

  private fun getFailureReason(code: Int): String {
    return when (code) {
      DownloadManager.ERROR_CANNOT_RESUME -> "Cannot resume download"
      DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Destination not found"
      DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "File already exists"
      DownloadManager.ERROR_FILE_ERROR -> "File system error"
      DownloadManager.ERROR_HTTP_DATA_ERROR -> "Network data error"
      DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage space"
      DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many redirects"
      DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Unhandled server response"
      DownloadManager.ERROR_UNKNOWN -> "Unknown download error"
      else -> "Download error ($code)"
    }
  }

  var downloadItemQueue: MutableList<DownloadItem> =
          mutableListOf() // All pending and downloading items
  var currentDownloadItemParts: MutableList<DownloadItemPart> =
          mutableListOf() // Item parts currently being downloaded
  private val internalDownloadCalls: MutableMap<String, Call> = mutableMapOf()

  interface DownloadEventEmitter {
    fun onDownloadItem(downloadItem: DownloadItem)
    fun onDownloadItemPartUpdate(downloadItemPart: DownloadItemPart)
    fun onDownloadItemComplete(jsobj: JSObject)
    fun onDownloadItemError(
            downloadItem: DownloadItem,
            downloadItemPart: DownloadItemPart,
            errorMessage: String
    )
    fun onDownloadItemCancelled(downloadItemId: String)
  }

  interface InternalProgressCallback {
    fun onProgress(totalBytesWritten: Long, progress: Long, totalBytes: Long)
    fun onComplete(failed: Boolean, errorMessage: String?)
  }

  companion object {
    var isDownloading: Boolean = false
  }

  /** Adds a download item to the queue and starts processing the queue. */
  fun addDownloadItem(downloadItem: DownloadItem) {
    DeviceManager.dbManager.saveDownloadItem(downloadItem)
    Log.i(tag, "Add download item ${downloadItem.media.metadata.title}")

    downloadItemQueue.add(downloadItem)
    clientEventEmitter.onDownloadItem(downloadItem)
    checkUpdateDownloadQueue()
  }

  fun resumeDownloadItem(downloadItemId: String): Boolean {
    val downloadItem = downloadItemQueue.find { it.id == downloadItemId }
    if (downloadItem == null) {
      Log.w(tag, "resumeDownloadItem: Download item not found $downloadItemId")
      return false
    }

    downloadItem.downloadItemParts.forEach { part ->
      if (part.paused || part.failed) {
        part.paused = false
        part.failed = false
        part.errorMessage = null
        part.downloadId = null
        part.bytesDownloaded = 0
        part.progress = 0
        part.completed = false
        removeDownloadArtifacts(part)
        clientEventEmitter.onDownloadItemPartUpdate(part)
      }
    }

    DeviceManager.dbManager.saveDownloadItem(downloadItem)
    checkUpdateDownloadQueue()
    return true
  }

  fun cancelDownloadItem(downloadItemId: String, deleteFiles: Boolean): Boolean {
    val downloadItem = downloadItemQueue.find { it.id == downloadItemId }
    if (downloadItem == null) {
      Log.w(tag, "cancelDownloadItem: Download item not found $downloadItemId")
      return false
    }

    val iterator = currentDownloadItemParts.iterator()
    while (iterator.hasNext()) {
      val part = iterator.next()
      if (part.downloadItemId == downloadItem.id) {
        if (part.isInternalStorage) {
          internalDownloadCalls.remove(part.id)?.cancel()
        } else {
          part.downloadId?.let { downloadManager.remove(it) }
        }
        iterator.remove()
      }
    }

    downloadItem.downloadItemParts.forEach { part ->
      part.downloadId = null
      part.completed = false
      part.isMoving = false
      part.paused = true
      if (deleteFiles) {
        removeDownloadArtifacts(part)
      }
    }

    downloadItemQueue.remove(downloadItem)
    DeviceManager.dbManager.removeDownloadItem(downloadItem.id)
    clientEventEmitter.onDownloadItemCancelled(downloadItem.id)
    return true
  }

  /** Checks and updates the download queue. */
  private fun checkUpdateDownloadQueue() {
    for (downloadItem in downloadItemQueue) {
      val numPartsToGet = maxSimultaneousDownloads - currentDownloadItemParts.size
      val nextDownloadItemParts = downloadItem.getNextDownloadItemParts(numPartsToGet)
      Log.d(
              tag,
              "checkUpdateDownloadQueue: numPartsToGet=$numPartsToGet, nextDownloadItemParts=${nextDownloadItemParts.size}"
      )

      if (nextDownloadItemParts.isNotEmpty()) {
        processDownloadItemParts(nextDownloadItemParts)
      }

      if (currentDownloadItemParts.size >= maxSimultaneousDownloads) {
        break
      }
    }

    if (currentDownloadItemParts.isNotEmpty()) startWatchingDownloads()
  }

  /** Processes the download item parts. */
  private fun processDownloadItemParts(nextDownloadItemParts: List<DownloadItemPart>) {
    nextDownloadItemParts.forEach {
      if (it.isInternalStorage) {
        startInternalDownload(it)
      } else {
        startExternalDownload(it)
      }
    }
  }

  /** Starts an internal download. */
  private fun startInternalDownload(downloadItemPart: DownloadItemPart) {
    val file = File(downloadItemPart.finalDestinationPath)
    file.parentFile?.mkdirs()

    val fileOutputStream = FileOutputStream(downloadItemPart.finalDestinationPath)
    val internalProgressCallback =
            object : InternalProgressCallback {
              override fun onProgress(totalBytesWritten: Long, progress: Long, totalBytes: Long) {
                downloadItemPart.bytesDownloaded = totalBytesWritten
                downloadItemPart.progress =
                        if (totalBytes > 0) progress else 0
              }

              override fun onComplete(failed: Boolean, errorMessage: String?) {
                downloadItemPart.failed = failed
                downloadItemPart.completed = true
                downloadItemPart.errorMessage = errorMessage
              }
            }

    Log.d(
            tag,
            "Start internal download to destination path ${downloadItemPart.finalDestinationPath} from ${downloadItemPart.serverUrl}"
    )
    val call =
            InternalDownloadManager(fileOutputStream, internalProgressCallback)
                    .download(downloadItemPart.serverUrl)
    internalDownloadCalls[downloadItemPart.id] = call
    downloadItemPart.downloadId = System.currentTimeMillis()
    currentDownloadItemParts.add(downloadItemPart)
  }

  /** Starts an external download. */
  private fun startExternalDownload(downloadItemPart: DownloadItemPart) {
    val dlRequest = downloadItemPart.getDownloadRequest()
    dlRequest.setAllowedOverMetered(true)
    dlRequest.setAllowedOverRoaming(true)
    dlRequest.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
    val downloadId = downloadManager.enqueue(dlRequest)
    downloadItemPart.downloadId = downloadId
    Log.d(tag, "checkUpdateDownloadQueue: Starting download item part, downloadId=$downloadId")
    currentDownloadItemParts.add(downloadItemPart)
  }

  /** Starts watching the downloads. */
  private fun startWatchingDownloads() {
    if (isDownloading) return // Already watching

    GlobalScope.launch(Dispatchers.IO) {
      Log.d(tag, "Starting watching downloads")
      isDownloading = true

      while (currentDownloadItemParts.isNotEmpty()) {
        val itemParts = currentDownloadItemParts.filter { !it.isMoving }
        for (downloadItemPart in itemParts) {
          if (downloadItemPart.isInternalStorage) {
            handleInternalDownloadPart(downloadItemPart)
          } else {
            handleExternalDownloadPart(downloadItemPart)
          }
        }

        delay(500)

        if (currentDownloadItemParts.size < maxSimultaneousDownloads) {
          checkUpdateDownloadQueue()
        }
      }

      Log.d(tag, "Finished watching downloads")
      isDownloading = false
    }
  }

  /** Handles an internal download part. */
  private fun handleInternalDownloadPart(downloadItemPart: DownloadItemPart) {
    clientEventEmitter.onDownloadItemPartUpdate(downloadItemPart)

    if (downloadItemPart.completed) {
      internalDownloadCalls.remove(downloadItemPart.id)
      val downloadItem = downloadItemQueue.find { it.id == downloadItemPart.downloadItemId }
      if (downloadItem == null) {
        Log.e(tag, "Download item not found for completed internal part ${downloadItemPart.filename}")
      } else if (downloadItemPart.failed) {
        handleDownloadFailure(
                downloadItem,
                downloadItemPart,
                downloadItemPart.errorMessage ?: "Internal download failed"
        )
      } else {
        checkDownloadItemFinished(downloadItem)
        DeviceManager.dbManager.saveDownloadItem(downloadItem)
      }
      currentDownloadItemParts.remove(downloadItemPart)
    }
  }

  /** Handles an external download part. */
  private fun handleExternalDownloadPart(downloadItemPart: DownloadItemPart) {
    val downloadCheckStatus = checkDownloadItemPart(downloadItemPart)
    clientEventEmitter.onDownloadItemPartUpdate(downloadItemPart)

    // Will move to final destination, remove current item parts, and check if download item is
    // finished
    handleDownloadItemPartCheck(downloadCheckStatus, downloadItemPart)
  }

  /** Checks the status of a download item part. */
  private fun checkDownloadItemPart(downloadItemPart: DownloadItemPart): DownloadCheckStatus {
    val downloadId = downloadItemPart.downloadId ?: return DownloadCheckStatus.Failed

    val query = DownloadManager.Query().setFilterById(downloadId)
    downloadManager.query(query).use {
      if (it.moveToFirst()) {
        val bytesColumnIndex = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        val statusColumnIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val bytesDownloadedColumnIndex =
                it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
        val reasonColumnIndex = it.getColumnIndex(DownloadManager.COLUMN_REASON)

        val totalBytes = if (bytesColumnIndex >= 0) it.getInt(bytesColumnIndex) else 0
        val downloadStatus = if (statusColumnIndex >= 0) it.getInt(statusColumnIndex) else 0
        val bytesDownloadedSoFar =
                if (bytesDownloadedColumnIndex >= 0) it.getLong(bytesDownloadedColumnIndex) else 0
        val reason = if (reasonColumnIndex >= 0) it.getInt(reasonColumnIndex) else 0
        Log.d(
                tag,
                "checkDownloads Download ${downloadItemPart.filename} bytes $totalBytes | bytes dled $bytesDownloadedSoFar | downloadStatus $downloadStatus"
        )

        return when (downloadStatus) {
          DownloadManager.STATUS_SUCCESSFUL -> {
            Log.d(tag, "checkDownloads Download ${downloadItemPart.filename} Successful")
            downloadItemPart.completed = true
            downloadItemPart.progress = 1
            downloadItemPart.bytesDownloaded = bytesDownloadedSoFar

            DownloadCheckStatus.Successful
          }
          DownloadManager.STATUS_FAILED -> {
            Log.d(tag, "checkDownloads Download ${downloadItemPart.filename} Failed")
            downloadItemPart.errorMessage = getFailureReason(reason)

            DownloadCheckStatus.Failed
          }
          else -> {
            val percentProgress =
                    if (totalBytes > 0) ((bytesDownloadedSoFar * 100L) / totalBytes) else 0
            Log.d(
                    tag,
                    "checkDownloads Download ${downloadItemPart.filename} Progress = $percentProgress%"
            )
            downloadItemPart.progress = percentProgress
            downloadItemPart.bytesDownloaded = bytesDownloadedSoFar

            DownloadCheckStatus.InProgress
          }
        }
      } else {
        Log.d(tag, "Download ${downloadItemPart.filename} not found in dlmanager")
        downloadItemPart.errorMessage = "Download cancelled or missing"
        return DownloadCheckStatus.Failed
      }
    }
  }

  /** Handles the result of a download item part check. */
  private fun handleDownloadItemPartCheck(
          downloadCheckStatus: DownloadCheckStatus,
          downloadItemPart: DownloadItemPart
  ) {
    val downloadItem = downloadItemQueue.find { it.id == downloadItemPart.downloadItemId }
    if (downloadItem == null) {
      Log.e(
              tag,
              "Download item part finished but download item not found ${downloadItemPart.filename}"
      )
      currentDownloadItemParts.remove(downloadItemPart)
    } else if (downloadCheckStatus == DownloadCheckStatus.Successful) {
      moveDownloadedFile(downloadItem, downloadItemPart)
    } else if (downloadCheckStatus == DownloadCheckStatus.Failed) {
      handleDownloadFailure(
              downloadItem,
              downloadItemPart,
              downloadItemPart.errorMessage ?: "Download failed"
      )
      currentDownloadItemParts.remove(downloadItemPart)
    }
  }

  private fun handleDownloadFailure(
          downloadItem: DownloadItem,
          downloadItemPart: DownloadItemPart,
          errorMessage: String
  ) {
    Log.e(tag, "Download failed for ${downloadItemPart.filename}: $errorMessage")
    downloadItemPart.completed = false
    downloadItemPart.failed = true
    downloadItemPart.paused = true
    downloadItemPart.errorMessage = errorMessage
    downloadItemPart.downloadId?.let {
      if (!downloadItemPart.isInternalStorage) {
        downloadManager.remove(it)
      }
    }
    internalDownloadCalls.remove(downloadItemPart.id)?.cancel()
    downloadItemPart.downloadId = null
    downloadItemPart.bytesDownloaded = 0
    downloadItemPart.progress = 0
    downloadItemPart.moved = false
    removeDownloadArtifacts(downloadItemPart)
    clientEventEmitter.onDownloadItemPartUpdate(downloadItemPart)
    val iterator = currentDownloadItemParts.iterator()
    while (iterator.hasNext()) {
      val part = iterator.next()
      if (part.downloadItemId == downloadItem.id && part.id != downloadItemPart.id) {
        if (part.isInternalStorage) {
          internalDownloadCalls.remove(part.id)?.cancel()
        } else {
          part.downloadId?.let { downloadManager.remove(it) }
        }
        part.downloadId = null
        part.paused = true
        part.failed = false
        part.completed = false
        part.isMoving = false
        part.bytesDownloaded = 0
        part.progress = 0
        iterator.remove()
        clientEventEmitter.onDownloadItemPartUpdate(part)
      }
    }
    downloadItem.downloadItemParts.forEach { part ->
      if (!part.completed) {
        part.paused = true
      }
    }
    clientEventEmitter.onDownloadItemError(downloadItem, downloadItemPart, errorMessage)
    DeviceManager.dbManager.saveDownloadItem(downloadItem)
  }

  private fun removeDownloadArtifacts(downloadItemPart: DownloadItemPart) {
    try {
      downloadItemPart.destinationUri.path?.let { path ->
        val tempFile = File(path)
        if (tempFile.exists()) {
          tempFile.delete()
        }
      }
    } catch (e: Exception) {
      Log.w(tag, "Failed to delete temp file for ${downloadItemPart.filename}", e)
    }

    try {
      val finalFile = File(downloadItemPart.finalDestinationPath)
      if (finalFile.exists() && downloadItemPart.paused) {
        finalFile.delete()
      }
    } catch (e: Exception) {
      Log.w(tag, "Failed to delete incomplete file for ${downloadItemPart.filename}", e)
    }
  }

  /** Moves the downloaded file to its final destination. */
  private fun moveDownloadedFile(downloadItem: DownloadItem, downloadItemPart: DownloadItemPart) {
    val file = DocumentFileCompat.fromUri(mainActivity, downloadItemPart.destinationUri)
    Log.d(tag, "DOWNLOAD: DESTINATION URI ${downloadItemPart.destinationUri}")

    val fcb =
            object : FileCallback() {
              override fun onPrepare() {
                Log.d(tag, "DOWNLOAD: PREPARING MOVE FILE")
              }

              override fun onFailed(errorCode: ErrorCode) {
                Log.e(tag, "DOWNLOAD: FAILED TO MOVE FILE $errorCode")
                downloadItemPart.failed = true
                downloadItemPart.isMoving = false
                file?.delete()
                checkDownloadItemFinished(downloadItem)
                currentDownloadItemParts.remove(downloadItemPart)
              }

              override fun onCompleted(result: Any) {
                Log.d(tag, "DOWNLOAD: FILE MOVE COMPLETED")
                val resultDocFile = result as DocumentFile
                Log.d(
                        tag,
                        "DOWNLOAD: COMPLETED FILE INFO (name=${resultDocFile.name}) ${resultDocFile.getAbsolutePath(mainActivity)}"
                )

                // Rename to fix appended .mp3 on m4b/m4a files
                //  REF: https://github.com/anggrayudi/SimpleStorage/issues/94
                val docNameLowerCase = resultDocFile.name?.lowercase(Locale.getDefault()) ?: ""
                if (docNameLowerCase.endsWith(".m4b.mp3") || docNameLowerCase.endsWith(".m4a.mp3")
                ) {
                  resultDocFile.renameTo(downloadItemPart.filename)
                }

                downloadItemPart.moved = true
                downloadItemPart.isMoving = false
                DeviceManager.dbManager.saveDownloadItem(downloadItem)
                checkDownloadItemFinished(downloadItem)
                currentDownloadItemParts.remove(downloadItemPart)
              }
            }

    val localFolderFile =
            DocumentFileCompat.fromUri(mainActivity, Uri.parse(downloadItemPart.localFolderUrl))
    if (localFolderFile == null) {
      // Failed
      downloadItemPart.failed = true
      Log.e(tag, "Local Folder File from uri is null")
      checkDownloadItemFinished(downloadItem)
      currentDownloadItemParts.remove(downloadItemPart)
    } else {
      downloadItemPart.isMoving = true
      val mimetype = if (downloadItemPart.audioTrack != null) MimeType.AUDIO else MimeType.IMAGE
      val fileDescription =
              FileDescription(
                      downloadItemPart.filename,
                      downloadItemPart.finalDestinationSubfolder,
                      mimetype
              )
      file?.moveFileTo(mainActivity, localFolderFile, fileDescription, fcb)
    }
  }

  /** Checks if a download item is finished and processes it. */
  private fun checkDownloadItemFinished(downloadItem: DownloadItem) {
    if (downloadItem.isDownloadFinished) {
      Log.i(tag, "Download Item finished ${downloadItem.media.metadata.title}")

      GlobalScope.launch(Dispatchers.IO) {
        folderScanner.scanDownloadItem(downloadItem) { downloadItemScanResult ->
          Log.d(
                  tag,
                  "Item download complete ${downloadItem.itemTitle} | local library item id: ${downloadItemScanResult?.localLibraryItem?.id}"
          )

          val jsobj =
                  JSObject().apply {
                    put("libraryItemId", downloadItem.id)
                    put("localFolderId", downloadItem.localFolder.id)

                    downloadItemScanResult?.localLibraryItem?.let { localLibraryItem ->
                      put(
                              "localLibraryItem",
                              JSObject(jacksonMapper.writeValueAsString(localLibraryItem))
                      )
                    }
                    downloadItemScanResult?.localMediaProgress?.let { localMediaProgress ->
                      put(
                              "localMediaProgress",
                              JSObject(jacksonMapper.writeValueAsString(localMediaProgress))
                      )
                    }
                  }

          launch(Dispatchers.Main) {
            clientEventEmitter.onDownloadItemComplete(jsobj)
            downloadItemQueue.remove(downloadItem)
            DeviceManager.dbManager.removeDownloadItem(downloadItem.id)
          }
        }
      }
    }
  }
}
