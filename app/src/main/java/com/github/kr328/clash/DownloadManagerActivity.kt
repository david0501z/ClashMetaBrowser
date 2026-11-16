package com.github.kr328.clash

import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.DownloadManagerDesign
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class DownloadManagerActivity : BaseActivity<DownloadManagerDesign>() {
    private lateinit var downloadAdapter: DownloadAdapter
    private val downloadDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "cfawb"
    )
    private val browserDownloadDir by lazy {
        File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "browser_downloads")
    }

    override suspend fun main() {
        val design = DownloadManagerDesign(this)
        setContentDesign(design)

        initializeDownloadManager()

        // 观察进行中的下载，实时刷新进度
        launch {
            ProxyDownloadManager.downloads.collectLatest { refreshList() }
        }

        while (isActive) {
            events.receive()
        }
    }

    private fun initializeDownloadManager() {
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
        downloadAdapter = DownloadAdapter(
            onDownloadAction = { id, action ->
                when (action) {
                    "pause" -> ProxyDownloadManager.pause(id)
                    "resume" -> ProxyDownloadManager.resume(this, id)
                    "cancel" -> ProxyDownloadManager.cancel(id)
                }
                refreshList()
            },
            onFileClick = { file ->
                Toast.makeText(
                    this,
                    "${file.name}  (${formatSize(file.length())})",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
        design?.downloadRecyclerView?.layoutManager = LinearLayoutManager(this)
        design?.downloadRecyclerView?.adapter = downloadAdapter
        refreshList()
    }

    private fun refreshList() {
        val active = ProxyDownloadManager.downloads.value
        val files = mutableListOf<File>()
        listOfNotNull(
            downloadDir.takeIf { it.exists() },
            browserDownloadDir.takeIf { it.exists() }
        ).forEach { dir ->
            dir.listFiles()?.forEach { if (it.isFile) files.add(it) }
        }
        files.sortByDescending { it.lastModified() }
        downloadAdapter.submit(active, files)
    }
}

private fun formatSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
        size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024.0))
        else -> String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0))
    }
}

class DownloadAdapter(
    private val onDownloadAction: (Long, String) -> Unit,
    private val onFileClick: (File) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Active(val d: ProxyDownloadManager.ProxyDownload) : Row()
        data class Completed(val file: File) : Row()
    }

    private var rows: List<Row> = emptyList()

    fun submit(active: List<ProxyDownloadManager.ProxyDownload>, files: List<File>) {
        val newRows = mutableListOf<Row>()
        // 进行中的在前，已完成文件在后
        newRows += active.map { Row.Active(it) }
        newRows += files.map { Row.Completed(it) }
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Active) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 0) {
            return ActiveHolder(buildActiveView(parent))
        }
        return CompletedHolder(buildCompletedView(parent))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Active -> (holder as ActiveHolder).bind(row.d)
            is Row.Completed -> (holder as CompletedHolder).bind(row.file)
        }
    }

    private fun buildActiveView(parent: ViewGroup): View {
        val lp = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val container = LinearLayout(parent.context).apply {
            layoutParams = lp
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        val name = TextView(parent.context).apply { textSize = 14f; setPadding(0, 0, 0, 8) }
        val progress = ProgressBar(
            parent.context,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply { max = 1000 }
        val status = TextView(parent.context).apply { textSize = 12f; setPadding(0, 8, 0, 8) }
        val btnRow = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        val pause = Button(parent.context).apply { text = "暂停" }
        val resume = Button(parent.context).apply { text = "继续" }
        val cancel = Button(parent.context).apply { text = "取消" }
        btnRow.addView(pause)
        btnRow.addView(resume)
        btnRow.addView(cancel)
        container.addView(name)
        container.addView(progress)
        container.addView(status)
        container.addView(btnRow)
        container.tag = ActiveTag(name, progress, status, pause, resume, cancel)
        return container
    }

    private fun buildCompletedView(parent: ViewGroup): View {
        val lp = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return TextView(parent.context).apply {
            layoutParams = lp
            setPadding(32, 24, 32, 24)
            textSize = 14f
        }
    }

    private class ActiveTag(
        val name: TextView,
        val progress: ProgressBar,
        val status: TextView,
        val pause: Button,
        val resume: Button,
        val cancel: Button
    )

    inner class ActiveHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tag = view.tag as ActiveTag

        fun bind(d: ProxyDownloadManager.ProxyDownload) {
            tag.name.text = d.fileName
            tag.progress.progress = (d.progress * 1000).toInt()
            val stateText = when (d.state) {
                ProxyDownloadManager.State.DOWNLOADING ->
                    "${formatSize(d.downloadedBytes)} / ${if (d.totalBytes > 0) formatSize(d.totalBytes) else "?"} (${(d.progress * 100).toInt()}%)"
                ProxyDownloadManager.State.PAUSED -> "已暂停 (${formatSize(d.downloadedBytes)})"
                ProxyDownloadManager.State.COMPLETED -> "已完成 (${formatSize(d.downloadedBytes)})"
                ProxyDownloadManager.State.FAILED -> "失败: ${d.error ?: "网络错误"}"
            }
            tag.status.text = stateText
            val showPause = d.state == ProxyDownloadManager.State.DOWNLOADING
            tag.pause.visibility = if (showPause) View.VISIBLE else View.GONE
            tag.resume.visibility = if (d.state == ProxyDownloadManager.State.PAUSED) View.VISIBLE else View.GONE
            tag.cancel.visibility = if (d.state != ProxyDownloadManager.State.COMPLETED) View.VISIBLE else View.GONE
            tag.pause.setOnClickListener { onDownloadAction(d.id, "pause") }
            tag.resume.setOnClickListener { onDownloadAction(d.id, "resume") }
            tag.cancel.setOnClickListener { onDownloadAction(d.id, "cancel") }
        }
    }

    inner class CompletedHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textView = view as TextView

        fun bind(file: File) {
            textView.text = "${file.name}  (${formatSize(file.length())})"
            textView.setOnClickListener { onFileClick(file) }
        }
    }
}
