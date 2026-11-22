package com.ukpro.android.ime

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ukpro.android.UkRuntime
import kotlinx.coroutines.launch

/** One entry in the strip: the current clipboard first, then recent history. */
data class StripItem(val kind: String, val preview: String, val text: String?, val uri: Uri?, val mime: String, val current: Boolean, val sensitive: Boolean)

/**
 * Gboard-style clipboard strip above the keyboard: shows what was copied last (on the phone or synced
 * from a laptop) plus recent history; tap to paste into the focused field. Sensitive clips (marked by
 * password managers) are masked.
 */
class ClipStrip(private val context: Context, private val dark: Boolean, private val onPaste: (StripItem) -> Unit) {
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val view: HorizontalScrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isForceDarkAllowed = false
        setPadding(dp(6), dp(4), dp(6), 0)
        addView(row)
    }
    private val cm = context.getSystemService(ClipboardManager::class.java)
    private val fg = if (dark) 0xFFE8ECF8.toInt() else 0xFF1B2133.toInt()
    private val chipBg = if (dark) 0xFF1A2034.toInt() else 0xFFFFFFFF.toInt()
    private val accent = 0xFF5CC8FF.toInt()
    @Volatile private var hiddenForSession = false
    private var generation = 0

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun hideForSession() { hiddenForSession = true; view.visibility = View.GONE }

    /** Rebuilds the strip (call when the keyboard opens and whenever the clipboard changes). */
    fun refresh() {
        if (!UkRuntime.settings.current.clipStrip || hiddenForSession) { view.visibility = View.GONE; return }
        val gen = ++generation
        val current = currentClip()
        UkRuntime.scope.launch {
            val recent = runCatching { UkRuntime.db.clips().recent(12) }.getOrDefault(emptyList())
            val items = ArrayList<StripItem>()
            current?.let { items.add(it) }
            for (e in recent) {
                val uri = e.uris?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let { Uri.parse(it) }
                val item = StripItem(e.kind, e.preview, e.text, uri, if (e.kind == "image") "image/*" else "*/*", false, false)
                if (items.none { same(it, item) }) items.add(item)
            }
            UkRuntime.main.post { if (gen == generation) render(items) }
        }
    }

    private fun same(a: StripItem, b: StripItem) =
        (a.text != null && a.text == b.text) || (a.uri != null && a.uri == b.uri)

    private fun currentClip(): StripItem? {
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return null // only the default IME may read it
        if (clip.itemCount == 0) return null
        val desc = clip.description
        val sensitive = desc?.extras?.getBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE
            else "android.content.extra.IS_SENSITIVE") == true
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (uri != null) {
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: desc?.getMimeType(0) ?: "*/*"
            val kind = if (mime.startsWith("image/")) "image" else "files"
            return StripItem(kind, if (kind == "image") "Image" else (uri.lastPathSegment ?: "File"), null, uri, mime, true, sensitive)
        }
        val text = item.coerceToText(context)?.toString() ?: return null
        if (text.isBlank()) return null
        return StripItem("text", text, text, null, "text/plain", true, sensitive)
    }

    private fun render(items: List<StripItem>) {
        row.removeAllViews()
        if (items.isEmpty()) { view.visibility = View.GONE; return }
        view.visibility = View.VISIBLE
        for (it in items) row.addView(chip(it))
        row.addView(TextView(context).apply {
            text = "✕"; setTextColor(fg); textSize = 14f; setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { hideForSession() }
        })
    }

    private fun chip(item: StripItem): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(chipBg)
                if (item.current) setStroke(dp(1), accent)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginEnd = dp(6) }
            setOnClickListener { onPaste(item) }
            contentDescription = "Paste " + item.preview.take(40)
        }
        if (item.kind == "image" && item.uri != null) {
            val img = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(6) }
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            box.addView(img)
            UkRuntime.io.execute {
                val bmp = thumbnail(item.uri)
                if (bmp != null) UkRuntime.main.post { img.setImageBitmap(bmp) }
            }
        }
        val label = when {
            item.sensitive -> "••••••••"
            item.kind == "image" -> "Image"
            item.kind == "files" -> "📄 " + item.preview
            else -> item.preview.replace('\n', ' ').trim()
        }
        box.addView(TextView(context).apply {
            text = (if (item.current) "📋 " else "") + label
            setTextColor(fg); textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(180)
            if (item.current) typeface = Typeface.DEFAULT_BOLD
        })
        return box
    }

    private fun thumbnail(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 96 && bounds.outHeight / (sample * 2) >= 96) sample *= 2
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }.getOrNull()

    /** Puts an item back on the clipboard (used when the field cannot accept an image/file directly). */
    fun copyBack(item: StripItem) {
        val clip = when {
            item.text != null -> ClipData.newPlainText("UK Pro", item.text)
            item.uri != null -> ClipData(item.kind, arrayOf(item.mime), ClipData.Item(item.uri))
            else -> return
        }
        runCatching { cm.setPrimaryClip(clip) }
    }
}
