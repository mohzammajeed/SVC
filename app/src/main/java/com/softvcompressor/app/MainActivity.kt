package com.softvcompressor.app

import android.content.ClipData
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@UnstableApi
class MainActivity : AppCompatActivity() {

    // ---- source video info
    private var inputUri: Uri? = null
    private var inputName = "video"
    private var inputSize = 0L
    private var srcW = 0          // as displayed (rotation applied)
    private var srcH = 0
    private var srcDurMs = 0L
    private var srcBitrate = 0L

    // ---- work state
    private var outFile: File? = null
    private var transformer: Transformer? = null
    private var cancelled = false
    private val handler = Handler(Looper.getMainLooper())
    private val progressHolder = ProgressHolder()

    // ---- views
    private lateinit var pickBtn: Button
    private lateinit var infoText: TextView
    private lateinit var qualityGroup: RadioGroup
    private lateinit var startBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var cancelBtn: Button
    private lateinit var resultText: TextView
    private lateinit var saveBtn: Button
    private lateinit var shareBtn: Button

    private val pickLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onPicked(uri)
        }

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
            if (uri != null) saveTo(uri)
        }

    private val progressPoller = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            if (t.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                val p = progressHolder.progress
                progressBar.progress = p
                statusText.text = "Compressing… $p%"
            }
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pickBtn = findViewById(R.id.pickBtn)
        infoText = findViewById(R.id.infoText)
        qualityGroup = findViewById(R.id.qualityGroup)
        startBtn = findViewById(R.id.startBtn)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        cancelBtn = findViewById(R.id.cancelBtn)
        resultText = findViewById(R.id.resultText)
        saveBtn = findViewById(R.id.saveBtn)
        shareBtn = findViewById(R.id.shareBtn)

        pickBtn.setOnClickListener { pickLauncher.launch(arrayOf("video/*")) }
        startBtn.setOnClickListener { startCompress() }
        cancelBtn.setOnClickListener {
            cancelled = true
            transformer?.cancel()
            finishWork()
            statusText.text = "Cancelled"
            showSetup()
        }
        saveBtn.setOnClickListener {
            saveLauncher.launch("Compressed_" + inputName.substringBeforeLast('.') + ".mp4")
        }
        shareBtn.setOnClickListener { shareOutput() }
        qualityGroup.setOnCheckedChangeListener { _, _ -> updateInfo() }

        showSetup()
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressPoller)
        transformer?.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ picking

    private fun onPicked(uri: Uri) {
        var name = "video"
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0) size = c.getLong(si)
            }
        }

        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            if (w <= 0 || h <= 0 || dur <= 0) {
                toast("This file could not be read as a video.")
                return
            }
            val swap = rot == 90 || rot == 270
            srcW = if (swap) h else w
            srcH = if (swap) w else h
            srcDurMs = dur
            srcBitrate = br
        } catch (e: Exception) {
            toast("This video format is not supported on this device.")
            return
        } finally {
            try { r.release() } catch (_: Exception) {}
        }

        inputUri = uri
        inputName = name
        inputSize = size
        resultText.text = ""
        statusText.text = ""
        showSetup()
        updateInfo()
    }

    // ------------------------------------------------------------------ settings

    private data class Plan(val w: Int, val h: Int, val scale: Boolean, val bitrate: Int)

    private fun plan(): Plan {
        val (maxShort, bpp) = when (qualityGroup.checkedRadioButtonId) {
            R.id.qLow -> 480 to 0.045
            R.id.qHigh -> 1080 to 0.10
            else -> 720 to 0.07
        }
        val k = min(1.0, maxShort.toDouble() / min(srcW, srcH))
        var w = (srcW * k).roundToInt()
        var h = (srcH * k).roundToInt()
        w = max(2, w - w % 2)
        h = max(2, h - h % 2)
        var bitrate = (w.toDouble() * h * 30 * bpp).toLong()
        if (srcBitrate > 0) bitrate = min(bitrate, (srcBitrate * 0.9).toLong())  // never bigger than the source
        bitrate = max(bitrate, 200_000L)
        return Plan(w, h, k < 1.0, min(bitrate, Int.MAX_VALUE.toLong()).toInt())
    }

    private fun updateInfo() {
        if (inputUri == null) {
            infoText.text = "No video selected"
            startBtn.isEnabled = false
            return
        }
        val p = plan()
        val estBytes = (p.bitrate / 8.0 * (srcDurMs / 1000.0) * 1.05).toLong() + 96_000L / 8 * (srcDurMs / 1000)
        infoText.text = String.format(
            Locale.US,
            "%s\n%d×%d · %s · %s\nOutput: %d×%d · about %s",
            inputName, srcW, srcH, fmtTime(srcDurMs), fmtSize(inputSize),
            p.w, p.h, fmtSize(min(estBytes, if (inputSize > 0) inputSize else estBytes))
        )
        startBtn.isEnabled = true
    }

    // ------------------------------------------------------------------ compress

    private fun startCompress() {
        val uri = inputUri ?: return
        val p = plan()
        cancelled = false

        val out = File(cacheDir, "shared").apply { mkdirs() }.let { dir ->
            dir.listFiles()?.forEach { it.delete() }
            File(dir, "Compressed_" + inputName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_") + ".mp4")
        }
        outFile = out

        val videoEffects = ArrayList<Effect>()
        if (p.scale) videoEffects.add(Presentation.createForHeight(p.h))
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(p.bitrate).build()
            )
            .build()

        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (cancelled) return
                    finishWork()
                    showResult()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    if (cancelled) return
                    finishWork()
                    showSetup()
                    statusText.text = ""
                    toast("Compression failed: " + (exportException.message ?: "unknown error"))
                }
            })
            .build()
        transformer = t

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showWorking()
        try {
            t.start(edited, out.absolutePath)
        } catch (e: Exception) {
            finishWork()
            showSetup()
            statusText.text = ""
            toast("Could not start compression: " + (e.message ?: "unknown error"))
            return
        }
        handler.post(progressPoller)
    }

    private fun finishWork() {
        handler.removeCallbacks(progressPoller)
        transformer = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ------------------------------------------------------------------ result

    private fun showResult() {
        val f = outFile ?: return
        val saved = if (inputSize > 0) max(0, ((1.0 - f.length().toDouble() / inputSize) * 100).roundToInt()) else 0
        val bigger = inputSize > 0 && f.length() >= inputSize
        resultText.text = String.format(
            Locale.US, "%s → %s\n%s",
            fmtSize(inputSize), fmtSize(f.length()),
            if (bigger) "Output is not smaller — try Low quality." else "$saved% space saved"
        )
        pickBtn.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        statusText.text = "Done ✓"
        cancelBtn.visibility = View.GONE
        startBtn.visibility = View.GONE
        qualityGroup.visibility = View.GONE
        saveBtn.visibility = View.VISIBLE
        shareBtn.visibility = View.VISIBLE
        pickBtn.isEnabled = true
    }

    private fun saveTo(target: Uri) {
        val src = outFile ?: return
        Thread {
            val ok = try {
                src.inputStream().use { input ->
                    contentResolver.openOutputStream(target, "w")!!.use { output -> input.copyTo(output) }
                }
                true
            } catch (e: Exception) {
                false
            }
            runOnUiThread { toast(if (ok) "Video saved" else "Could not save the video") }
        }.start()
    }

    private fun shareOutput() {
        val f = outFile ?: return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("video", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share video"))
        } catch (e: Exception) {
            toast("Cannot share the video")
        }
    }

    // ------------------------------------------------------------------ UI states

    private fun showSetup() {
        pickBtn.visibility = View.VISIBLE
        pickBtn.isEnabled = true
        qualityGroup.visibility = View.VISIBLE
        startBtn.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        cancelBtn.visibility = View.GONE
        saveBtn.visibility = View.GONE
        shareBtn.visibility = View.GONE
        updateInfo()
    }

    private fun showWorking() {
        pickBtn.isEnabled = false
        qualityGroup.visibility = View.GONE
        startBtn.visibility = View.GONE
        saveBtn.visibility = View.GONE
        shareBtn.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        cancelBtn.visibility = View.VISIBLE
        statusText.text = "Starting…"
        resultText.text = ""
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun fmtSize(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        else -> String.format(Locale.US, "%d KB", b / 1024)
    }

    private fun fmtTime(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}
