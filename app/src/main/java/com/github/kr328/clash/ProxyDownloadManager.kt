package com.github.kr328.clash

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * 应用内下载管理器：下载走本地 Clash 代理(127.0.0.1:7890)，
 * 带进度、暂停、续传(Range)、取消。替代系统 DownloadManager(绕过代理)。
 */
object ProxyDownloadManager {
    const val PROXY_HOST = "127.0.0.1"
    const val PROXY_PORT = 7890

    enum class State { DOWNLOADING, PAUSED, COMPLETED, FAILED }

    data class ProxyDownload(
        val id: Long,
        val url: String,
        val fileName: String,
        val filePath: String,
        val totalBytes: Long,
        val downloadedBytes: Long,
        val state: State,
        val error: String? = null,
        val userAgent: String? = null,
        val mimeType: String? = null
    ) {
        val progress: Float
            get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
    }

    private val _downloads = MutableStateFlow<List<ProxyDownload>>(emptyList())
    val downloads: StateFlow<List<ProxyDownload>> = _downloads.asStateFlow()

    private val nextId = AtomicLong(1)
    private val jobs = mutableMapOf<Long, Job>()
    private val pausedDownloads = mutableMapOf<Long, ProxyDownload>() // 暂停时保存断点

    fun download(context: Context, url: String, fileName: String, mimeType: String?, userAgent: String?) {
        val id = nextId.getAndIncrement()
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "cfawb"
        )
        dir.mkdirs()
        val file = File(dir, fileName)
        val task = ProxyDownload(
            id = id,
            url = url,
            fileName = fileName,
            filePath = file.absolutePath,
            totalBytes = 0,
            downloadedBytes = 0,
            state = State.DOWNLOADING,
            userAgent = userAgent,
            mimeType = mimeType
        )
        addOrUpdate(task)
        startDownload(context.applicationContext, task, file, resume = false)
    }

    fun pause(id: Long) {
        val task = _downloads.value.find { it.id == id } ?: return
        if (task.state != State.DOWNLOADING) return
        jobs.remove(id)?.cancel()
        pausedDownloads[id] = task.copy(state = State.PAUSED)
        addOrUpdate(task.copy(state = State.PAUSED))
    }

    fun resume(context: Context, id: Long) {
        val paused = pausedDownloads.remove(id) ?: return
        val file = File(paused.filePath)
        addOrUpdate(paused.copy(state = State.DOWNLOADING))
        startDownload(context.applicationContext, paused, file, resume = true)
    }

    fun cancel(id: Long) {
        jobs.remove(id)?.cancel()
        val task = _downloads.value.find { it.id == id }
        pausedDownloads.remove(id)
        if (task != null) {
            runCatching { File(task.filePath).delete() }
            removeDownload(id)
        }
    }

    private fun startDownload(context: Context, task: ProxyDownload, file: File, resume: Boolean) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            var conn: HttpURLConnection? = null
            try {
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(PROXY_HOST, PROXY_PORT))
                val connection = URL(task.url).openConnection(proxy) as HttpURLConnection
                conn = connection
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                task.userAgent?.let { connection.setRequestProperty("User-Agent", it) }
                val startPos = if (resume && file.exists() && file.length() > 0) file.length() else 0L
                if (startPos > 0) {
                    connection.setRequestProperty("Range", "bytes=$startPos-")
                }
                connection.connect()
                val code = connection.responseCode
                if (code !in 200..299) {
                    addOrUpdate(task.copy(state = State.FAILED, error = "HTTP $code"))
                    return@launch
                }
                val total = if (code == 206) {
                    connection.contentLengthLong + startPos
                } else {
                    connection.contentLengthLong
                }
                FileOutputStream(file, startPos > 0).use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var written = startPos
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            written += n
                            updateProgress(task.id, written, total, task)
                        }
                        output.flush()
                    }
                }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf(task.mimeType),
                    null
                )
                addOrUpdate(task.copy(state = State.COMPLETED, totalBytes = total, downloadedBytes = file.length()))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    pausedDownloads[task.id] = task.copy(downloadedBytes = file.length())
                    addOrUpdate(task.copy(state = State.PAUSED, downloadedBytes = file.length()))
                } else {
                    e.printStackTrace()
                    addOrUpdate(task.copy(state = State.FAILED, error = e.message ?: "网络错误"))
                }
            } finally {
                conn?.disconnect()
                jobs.remove(task.id)
            }
        }
        jobs[task.id] = job
    }

    private fun updateProgress(id: Long, downloaded: Long, total: Long, task: ProxyDownload) {
        val current = _downloads.value.find { it.id == id } ?: return
        // 避免把 PAUSED/COMPLETED 状态覆盖回 DOWNLOADING
        if (current.state == State.DOWNLOADING) {
            addOrUpdate(task.copy(downloadedBytes = downloaded, totalBytes = total))
        }
    }

    private fun addOrUpdate(task: ProxyDownload) {
        val list = _downloads.value.toMutableList()
        val idx = list.indexOfFirst { it.id == task.id }
        if (idx >= 0) list[idx] = task else list.add(0, task)
        _downloads.value = list
    }

    private fun removeDownload(id: Long) {
        _downloads.value = _downloads.value.filterNot { it.id == id }
    }
}
