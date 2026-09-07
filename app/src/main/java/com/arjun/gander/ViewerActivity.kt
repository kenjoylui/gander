package com.arjun.gander

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.arjun.gander.FileKind.Companion.detect
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val STATE_COPY_SOURCE = "copy_source"
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

        /**
         * Above this, a document is served in ranges rather than read whole.
         *
         * This is a memory threshold, not a speed one. Bulk and ranged loading were
         * measured against each other at 0.2, 2.7, 8, 16, 32 and 53 MB on a Nothing
         * Phone 2: below about 32 MB the difference had no consistent sign and stayed
         * inside run-to-run noise, and only at 53 MB did ranging win repeatably, by
         * around 80 ms. So anywhere in that band is equally defensible on speed, and
         * the number is chosen instead for what it avoids holding in memory. 16 MB is
         * comfortable to buffer on a low-end device; a 50 MB scan is not.
         */
        private const val RANGE_THRESHOLD_BYTES = 16L * 1024 * 1024

        /**
         * Chromium major version the vendored pdf.js needs. Mozilla puts the legacy
         * build's floor at Chrome 125, and `lib/pdf.min.mjs` is pdfjs-dist 5.7.284
         * legacy. Below it, `pdf.html` says so instead of loading the renderer.
         *
         * Read this before raising it alongside a pdf.js upgrade. Chromium 138 is the
         * last WebView that Android 8.0, 8.1 and 9.0 will ever receive, because 139
         * requires Android 10 and minSdk here is 26. A pdf.js release needing more than
         * 138 therefore does not degrade on API 26 to 28, it ends PDF support there for
         * good. docs/VENDORED.md carries the same warning next to the version fetched.
         */
        private const val PDFJS_MIN_CHROMIUM_MAJOR = 125

        /**
         * Below this, a parsed major is treated as unreadable rather than as ancient.
         * WebView only became updatable at Chromium 33, so a provider reporting
         * something like "1.0" is telling us it does not use Chromium version numbers,
         * not that it predates them, and refusing its PDFs would break a device that
         * works today.
         *
         * This is why the user agent is asked first. Huawei numbers its WebView 12.1.x,
         * 14.0.x, 15.0.x, so a genuinely old engine parsed to 15 from the package alone,
         * landed under this floor, and was waved through as unreadable. Reading Chrome/
         * out of the user agent gives the engine version whatever the vendor calls the
         * package, and a provider that reports neither is caught by
         * [LOCKED_WEBVIEW_PACKAGES] instead of by guessing.
         */
        private const val PLAUSIBLE_CHROMIUM_MAJOR = 30

        /** The Chromium major in a WebView user agent, as in "Chrome/138.0.7204.179". */
        private val CHROME_TOKEN = Regex("""Chrome/(\d+)""")

        /**
         * WebView providers that cannot be swapped for another one.
         *
         * On a Huawei device without Google services the provider is pinned to this
         * package: Chrome and Android System WebView are both rejected, because they are
         * signed against a certificate chain the device does not carry. Two consequences.
         * The card must not tell these users to update Android System WebView, since they
         * cannot. And a version we failed to read is old rather than unknown, because no
         * Huawei build reaches the pdf.js floor, so this is the one case where an
         * unreadable version blocks instead of being waved through.
         */
        private val LOCKED_WEBVIEW_PACKAGES = setOf("com.huawei.webview")

        /**
         * How long the page readout stays up after the last scroll, and how long it
         * takes to arrive and leave.
         *
         * Two seconds rather than the usual one and a half because the pill is a tap
         * target as well as a readout, and one and a half races the hand of somebody
         * who has just decided to reach for it.
         */
        private const val OVERLAY_IDLE_MS = 2000L
        private const val OVERLAY_FADE_IN_MS = 120L
        private const val OVERLAY_FADE_OUT_MS = 180L

        /** Cover art target while the view has not been measured yet. */
        private const val ART_FALLBACK_PX = 512
    }

    private var webView: ScrollProbeWebView? = null
    private var player: ExoPlayer? = null

    /** The file the destination picker is currently open for. */
    private var copySource: Uri? = null

    /**
     * ACTION_CREATE_DOCUMENT with the type set per file. The stock contract fixes
     * it at construction, and the viewer does not know what it is showing until an
     * intent arrives, so the picker would otherwise have to be told that every file
     * is of unknown type and would suggest names without extensions.
     */
    private inner class CreateTypedDocument : ActivityResultContracts.CreateDocument("*/*") {
        var type: String = "*/*"
        override fun createIntent(context: Context, input: String): Intent =
            super.createIntent(context, input).setType(type)
    }

    private val createDocument = CreateTypedDocument()

    /**
     * Writes the open file to wherever the reader pointed the picker.
     *
     * No permission is involved. The picker returns a write grant for the single
     * document it just created and for nothing around it, which is the same shape
     * of grant the home screen already takes for folders, and it is why this can
     * exist in an app whose permission list has to stay empty.
     */
    private val saveCopy = registerForActivityResult(createDocument) { dest ->
        val src = copySource
        copySource = null
        if (dest == null || src == null) return@registerForActivityResult

        // Off the main thread. A document is small enough that it would not matter,
        // but Gander opens video too, and copying a few hundred megabytes inline is
        // an ANR rather than a slow save.
        val app = applicationContext
        val main = Handler(Looper.getMainLooper())
        val bar = findViewById<LinearProgressIndicator>(R.id.saveProgress)

        // Asked once, up front: a provider that cannot say how long the file is
        // gets a spinner rather than a bar that would have to invent a position.
        val total = documentLength(src)
        bar.isIndeterminate = total <= 0L
        if (total > 0L) {
            bar.max = 100
            bar.progress = 0
        }
        bar.visibility = View.VISIBLE

        // Shut down immediately after submitting: the already-queued copy still
        // runs to completion, and the worker thread ends with it instead of idling
        // for the life of the process once per save
        val worker = Executors.newSingleThreadExecutor()
        worker.execute {
            val saved = runCatching {
                contentResolver.openInputStream(src).use { input ->
                    contentResolver.openOutputStream(dest).use { output ->
                        val from = checkNotNull(input)
                        val to = checkNotNull(output)
                        // copyTo would be one line, but it reports nothing on the way
                        // through, and the whole point here is to be able to say how
                        // far along a large file is
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        var shown = -1
                        while (true) {
                            val read = from.read(buffer)
                            if (read < 0) break
                            to.write(buffer, 0, read)
                            copied += read
                            if (total > 0L) {
                                // Whole percent only: a 500 MB file would otherwise
                                // post thousands of updates nobody can see
                                val percent = ((copied * 100) / total).toInt()
                                if (percent != shown) {
                                    shown = percent
                                    main.post {
                                        if (!isDestroyed) bar.setProgressCompat(percent, true)
                                    }
                                }
                            }
                        }
                    }
                }
            }.isSuccess
            // A failed copy has already created the document and may have written
            // part of it, and half a file under the right name is worse than none:
            // it opens, it looks complete, and it is not.
            if (!saved) runCatching { DocumentsContract.deleteDocument(contentResolver, dest) }
            main.post {
                if (!isDestroyed) bar.visibility = View.GONE
                Toast.makeText(
                    app,
                    if (saved) R.string.save_copy_done else R.string.save_copy_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        worker.shutdown()
    }

    /**
     * The picker outlives the activity if Android reclaims the process while it is
     * open, and the callback is handed the destination but never the source, so
     * without this the save comes back to a null source and silently does nothing.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_COPY_SOURCE, copySource?.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        applySystemBarInsets(findViewById(R.id.root))
        onBackPressedDispatcher.addCallback(this, searchBackCallback)
        pageIndicator.setOnClickListener { askForPage() }

        copySource = savedInstanceState?.getString(STATE_COPY_SOURCE)?.let(Uri::parse)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        val container = findViewById<FrameLayout>(R.id.container)

        // Files arrive via VIEW (data), the share sheet (EXTRA_STREAM),
        // a plain path extra, or as shared text (EXTRA_TEXT).
        val uri = intent.data
            ?: IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.getStringExtra(EXTRA_PATH)?.let { Uri.fromFile(File(it)) }
            ?: sharedTextUri()
        if (uri == null) {
            finish()
            return
        }

        val name = resolveDisplayName(uri)
        toolbar.title = name
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull() ?: intent.type

        // Picker selections carry a persistable grant; keep those in Recents.
        // Open-with and folder-browsed URIs throw here and are simply skipped.
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                Recents.add(this, uri, name)
            }
        }

        // Held past the when because setUpSearch needs it to know whether the
        // format it is offering to search actually has anything findable in it
        val kind = detect(ext, mime)
        when (kind) {
            FileKind.IMAGE -> showImage(container, uri, name, ext)
            FileKind.PLAYER -> showPlayer(container, uri, name, ext)
            else -> showWeb(container, uri, kind, name, ext)
        }
        setUpSearch(toolbar, kind)
        setUpActions(toolbar, kind, uri, name, ext, mime)
    }

    /** Night mode, share and "show in file manager" toolbar actions. */
    private fun setUpActions(
        toolbar: MaterialToolbar,
        kind: FileKind,
        uri: Uri,
        name: String,
        ext: String,
        mime: String?
    ) {
        setUpNightMode(toolbar, kind)
        goToPageItem = toolbar.menu.findItem(R.id.action_go_to_page).apply {
            setOnMenuItemClickListener { askForPage(); true }
        }
        toolbar.menu.findItem(R.id.action_share).setOnMenuItemClickListener {
            shareFile(uri, ext, mime)
            true
        }
        toolbar.menu.findItem(R.id.action_save_copy).setOnMenuItemClickListener {
            copySource = uri
            createDocument.type = mime
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "*/*"
            // No picker on the device is the only way this throws, and it leaves
            // the reader on the document rather than on a crash
            runCatching { saveCopy.launch(name) }.onFailure {
                copySource = null
                Toast.makeText(this, R.string.save_copy_failed, Toast.LENGTH_SHORT).show()
            }
            true
        }

        val folder = containingFolder(uri)
        toolbar.menu.findItem(R.id.action_open_folder).apply {
            isVisible = folder != null
            setOnMenuItemClickListener {
                openFolder(folder ?: return@setOnMenuItemClickListener true)
                true
            }
        }
    }

    /**
     * Turning the contents of a PDF over for reading in the dark. Issue #19.
     *
     * Only a PDF, because it is the only format drawn as a picture rather than laid out
     * as a document, and only where the message port exists, because that is the whole
     * of how the page is told. The initial state has already gone out on the URL by the
     * time this runs, so a WebView without the port still opens in the right mode; what
     * it cannot do is change it without reopening the file, and offering a control that
     * quietly does nothing is worse than not offering it. The search item is hidden on
     * the same test a few lines further down, and for the same reason.
     */
    private fun setUpNightMode(toolbar: MaterialToolbar, kind: FileKind) {
        val item = toolbar.menu.findItem(R.id.action_night_mode)
        // The last test is the card pdf.html shows instead of a document when the
        // WebView is too old for the renderer: pdfjsFloorParams is non-empty exactly
        // then, and turning a card that says "update your WebView" dark is not a
        // feature. Same standard as the two above, and as action_search.
        val blocked = pdfjsFloorParams(kind, webView?.settings?.userAgentString).isNotEmpty()
        if (kind != FileKind.PDF || !canPortSearch() || blocked) {
            item.isVisible = false
            return
        }
        item.isVisible = true
        item.isChecked = Settings.night(this)
        item.setOnMenuItemClickListener {
            val on = !it.isChecked
            it.isChecked = on
            Settings.setNight(this, on)
            if (searchPort != null) loadedNight = on
            searchPort?.postMessage(WebMessageCompat(if (on) "i1" else "i0"))
            true
        }
    }

    private fun shareFile(uri: Uri, ext: String, mime: String?) {
        // Received content:// URIs go out with a read grant passed along; our own
        // file:// URIs (the shared-text temp file) go through the FileProvider
        val shareUri =
            if (uri.scheme == "content") uri
            else runCatching {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", File(uri.path!!))
            }.getOrNull()
        if (shareUri == null) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, shareUri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Some Android versions refuse to delegate a tree-derived grant and
        // throw here rather than at read time
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.share_file))) }
            .onFailure { Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show() }
    }

    /**
     * Best-effort document URI of the folder holding [uri], for the Files app.
     * Null when the source provider does not expose a real filesystem location,
     * which hides the menu item.
     */
    private fun containingFolder(uri: Uri): Uri? = runCatching {
        when {
            uri.scheme == "file" ->
                File(uri.path!!).parent?.let { folderDocUri(it) }
            uri.authority == EXTERNAL_STORAGE_AUTHORITY -> {
                // Document id is "volume:relative/path"; drop the file segment
                val docId = DocumentsContract.getDocumentId(uri)
                val volume = docId.substringBefore(':', "")
                val path = docId.substringAfter(':', "")
                if (volume.isEmpty() || path.isEmpty()) null
                else DocumentsContract.buildDocumentUri(
                    EXTERNAL_STORAGE_AUTHORITY,
                    "$volume:${path.substringBeforeLast('/', "")}"
                )
            }
            uri.authority == DOWNLOADS_AUTHORITY -> {
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("raw:")) {
                    File(docId.removePrefix("raw:")).parent?.let { folderDocUri(it) }
                } else {
                    // Opaque ids (msf:42) at least live under Download
                    DocumentsContract.buildDocumentUri(
                        EXTERNAL_STORAGE_AUTHORITY, "primary:Download"
                    )
                }
            }
            uri.authority == "media" ->
                // Our read grant lets us ask MediaStore for the backing path
                contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
                    if (!c.moveToFirst()) null
                    else c.getString(0)?.let { File(it).parent }?.let { folderDocUri(it) }
                }
            else -> null
        }
    }.getOrNull()

    /** Maps an absolute folder path to an ExternalStorageProvider document URI. */
    private fun folderDocUri(path: String): Uri? {
        val primary = Environment.getExternalStorageDirectory().absolutePath
        val docId = when {
            path.startsWith(primary) ->
                "primary:" + path.removePrefix(primary).trimStart('/')
            path.startsWith("/storage/") -> {
                val rest = path.removePrefix("/storage/")
                rest.substringBefore('/') + ":" + rest.substringAfter('/', "")
            }
            else -> return null
        }
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
    }

    private fun openFolder(folder: Uri) {
        // The system Files app (and most file managers) handle VIEW on a
        // directory document; no grant needed, they have provider access
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
        runCatching { startActivity(view) }.onFailure {
            Toast.makeText(this, R.string.no_file_manager, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Where a search goes. Two of these, because PDF cannot use the other one.
     *
     * Every other WebView format is found with findAllAsync, Chromium's own
     * find-in-page, which searches the DOM. pdf.html holds only about eleven pages of
     * a document in the DOM at once, so native find would report matches from the
     * part of it currently on screen and silently miss the rest, which is worse than
     * offering no search at all. PDF therefore searches in the page, over text it
     * extracts itself, and reports back over a message port.
     */
    private interface Finder {
        fun query(q: String)
        fun next()
        fun prev()
        fun clear()
    }

    private class NativeFinder(private val web: WebView) : Finder {
        override fun query(q: String) = web.findAllAsync(q)
        override fun next() { web.findNext(true) }
        override fun prev() { web.findNext(false) }
        override fun clear() = web.clearMatches()
    }

    /** The page end of the PDF search channel, held so it can be closed. */
    private var searchPort: WebMessagePortCompat? = null

    /**
     * The night mode the page was loaded with, so a tap made before the channel exists
     * is not lost. setUpNightMode runs in onCreate and the port only arrives on page
     * finished; in between the item is tappable and there is nowhere to send it. The
     * preference is the truth, so the port corrects the page on the way up, the same
     * way openSearchChannel replays a query typed too early.
     */
    private var loadedNight = false

    /** Set once the counter exists, called with (position, total, indexFinished). */
    private var onSearchCount: ((Int, Int, Boolean) -> Unit)? = null

    /**
     * Closes the search bar, whoever asked: the close button, Back, or a renderer that
     * died underneath it. Held as a field because showRendererGone has to reach it from
     * outside setUpSearch, and it stays a no-op until there is a bar to close.
     */
    private var closeSearchBar: () -> Unit = {}

    /**
     * Takes Back while the search bar is open, so it closes the bar instead of the
     * document. Until this existed, Back on a document with the search box open threw
     * away the reading position and the query together, and from targetSdk 35 on it did
     * it while playing the predictive back animation, so the app visibly peeled away
     * toward the launcher with the cursor still in the box.
     *
     * Nothing here calls finish(). The callback closes the bar and switches itself off,
     * and the next press falls through to the default, which is what keeps predictive
     * back working: an enabled callback suppresses the animation and owns the event.
     */
    private val searchBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeSearchBar()
    }

    /** Last page pdf.html reported, and how many there are. Zero until it says. */
    private var pageAt = 0
    private var pageTotal = 0

    /**
     * True while the find box is up. The page readout stands down for it: two counters
     * on one screen, the lower of them behind the keyboard, is not information.
     */
    private var searchBarOpen = false

    /**
     * An overlay that shows itself while the reader scrolls and puts itself away once
     * they stop.
     *
     * The page readout and the scroll thumb want the same six transitions in the same
     * three timings, and the second was written by copying the first. What separates
     * them is data rather than logic: the thumb brings its track up and down with it
     * and settles to INVISIBLE, because the track's height is what the thumb is
     * measured from and a gone view is never measured, while the pill has nothing
     * beside it and settles to GONE.
     *
     * Whether it is up is tracked here rather than read off the view. A scroll delivers
     * an event per frame, and asking the view would restart the fade on every one of
     * them: a fresh animator each frame never reaches the end of itself, so the overlay
     * would sit part-faded for as long as somebody kept scrolling.
     */
    private class AutoHide(
        private val view: View,
        private val hidden: Int,
        private val companion: View? = null,
        private val canFade: () -> Boolean = { true },
        private val onSettled: () -> Unit = {},
    ) {
        private var up = false
        private val hide = Runnable { if (canFade()) fadeOut() }

        /** Up if it is not already. The idle countdown is the caller's to start. */
        fun show() {
            if (up) return
            up = true
            view.animate().cancel()
            view.alpha = 0f
            view.visibility = View.VISIBLE
            companion?.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(OVERLAY_FADE_IN_MS).start()
        }

        /** Up now, at full alpha, and staying: for as long as a finger is on it. */
        fun pin() {
            up = true
            view.animate().cancel()
            view.removeCallbacks(hide)
            view.visibility = View.VISIBLE
            companion?.visibility = View.VISIBLE
            view.alpha = 1f
        }

        fun restartIdle() {
            view.removeCallbacks(hide)
            view.postDelayed(hide, OVERLAY_IDLE_MS)
        }

        fun cancelIdle() {
            view.removeCallbacks(hide)
        }

        fun fadeOut() {
            up = false
            view.animate().cancel()
            view.animate().alpha(0f).setDuration(OVERLAY_FADE_OUT_MS).withEndAction {
                view.visibility = hidden
                companion?.visibility = hidden
                onSettled()
            }.start()
        }

        /** Straight off, with no fade: for a document that has stopped being one. */
        fun hideNow() {
            up = false
            view.removeCallbacks(hide)
            view.animate().cancel()
            view.visibility = hidden
            companion?.visibility = hidden
            onSettled()
        }
    }

    private val pageIndicator: TextView by lazy { findViewById(R.id.pageIndicator) }

    private val pageFader by lazy { AutoHide(pageIndicator, View.GONE) }

    /** Bound once the toolbar exists, shown once pdf.html has said how long the file is. */
    private var goToPageItem: MenuItem? = null

    /**
     * Put the page readout on screen, or keep it there.
     *
     * Called from two places that know different things. pdf.html says which page is on
     * screen, over the port search already uses, and only when that number changes. The
     * WebView says that something scrolled at all, which is what decides the pill is
     * worth showing: scrolling within one tall page changes no page number, and a
     * readout that only appeared on crossing a boundary would be absent exactly when
     * somebody goes looking for it.
     *
     * A no-op for everything that is not a PDF of more than one page, because nothing
     * else ever sets the total.
     */
    private fun showPageIndicator() {
        if (pageTotal < 2 || searchBarOpen) return
        // While the thumb has hold of it, where the pill sits and whether it is up at
        // all belong to the drag. The number is set where it changes, not here: this
        // runs once a frame for the length of a fling, and the text is the same on all
        // but the few frames that cross a page boundary.
        if (dragging) return
        pageFader.show()
        restartPageIndicatorIdle()
    }

    private fun setPageIndicatorText() {
        val pill = pageIndicator
        // Description before text, as the search counter does it: anything already
        // reading this node has to find the spoken form in place by the time the
        // visible one changes under it.
        pill.contentDescription =
            getString(R.string.page_indicator_spoken, pageAt, pageTotal)
        pill.text = getString(R.string.page_indicator, pageAt, pageTotal)
    }

    private fun restartPageIndicatorIdle() {
        // A page readout does not fade under a screen reader: a view at zero alpha
        // cannot be reached by swipe navigation, and this is the only thing on screen
        // that knows where in the document the reader is. Same reason media controls
        // stay up. A percentage left over from a drag is not worth keeping, so that
        // still goes.
        if (!touchExplorationOn() || pageTotal < 2) pageFader.restartIdle()
        else pageFader.cancelIdle()
    }

    /**
     * Asks for a page number and goes there.
     *
     * A bare EditText rather than a TextInputLayout. That widget is used nowhere else
     * here, and pulling more of Material in for a floating label is a poor trade in a
     * release whose headline was the download halving. setError is also what Android 16
     * names as the way to report a bad value now that announcements are deprecated, so
     * the small answer is the accessible one too.
     */
    private fun askForPage() {
        val total = pageTotal
        if (total < 2) return

        val entry = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_GO
            hint = getString(R.string.page_number)
            setText(pageAt.toString())
            setSelection(text.length)
            requestFocus()
        }
        val gutter = (24 * resources.displayMetrics.density).toInt()
        val holder = FrameLayout(this).apply {
            setPadding(gutter, gutter / 3, gutter, 0)
            addView(
                entry,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.go_to_page)
            .setMessage(getString(R.string.page_range, total))
            .setView(holder)
            .setPositiveButton(R.string.go, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // ALWAYS_VISIBLE rather than VISIBLE: the weaker one leaves the keyboard down
        // until the field is tapped, and the only thing anybody opens this to do is type
        // a number into it.
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        dialog.show()

        // Bound after show() on purpose: the button a builder makes dismisses the dialog
        // before its listener runs, and a number outside the document has to be able to
        // say so without the box closing under the answer.
        val go = {
            val n = entry.text.toString().trim().toIntOrNull()
            if (n == null || n < 1 || n > total) {
                entry.error = getString(R.string.page_out_of_range, total)
            } else {
                // Straight down the channel search uses. See PortFinder for the shape.
                searchPort?.postMessage(WebMessageCompat("g$n"))
                dialog.dismiss()
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { go() }
        entry.setOnEditorActionListener { _, _, _ -> go(); true }
    }

    private val accessibility: AccessibilityManager? by lazy {
        getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }

    private fun touchExplorationOn(): Boolean =
        accessibility?.isTouchExplorationEnabled == true

    /**
     * A WebView that will say how far down it is.
     *
     * The three measurements are protected on View, and widening them is the only reason
     * this class exists. Reading the scroll here rather than asking the page is what lets
     * one thumb serve every viewer, including the six that have no channel back to the
     * app at all. It is also why the thumb is a native view and not drawn in the page:
     * pdf.html has no viewport meta and is zoomed to fit from a fixed 980 CSS px layout,
     * so a position: fixed scrubber would be pinned to a viewport the reader is not
     * looking at and would slide off screen the moment they pinched in.
     */
    private class ScrollProbeWebView(context: Context) : WebView(context) {
        fun verticalOffset(): Int = computeVerticalScrollOffset()
        fun verticalRange(): Int = computeVerticalScrollRange()
        fun verticalExtent(): Int = computeVerticalScrollExtent()
    }

    private val fastScrollTrack: View by lazy { findViewById(R.id.fastScrollTrack) }
    private val fastScrollThumb: View by lazy { findViewById(R.id.fastScrollThumb) }

    /**
     * The track goes up and down with the thumb, so the strip stops standing between a
     * reader and the document the moment there is nothing to grab. The exclusion rect is
     * refreshed once it has settled, because where the thumb ends up is what the back
     * gesture has to be kept off.
     */
    private val thumbFader by lazy {
        AutoHide(
            view = fastScrollThumb,
            hidden = View.INVISIBLE,
            companion = fastScrollTrack,
            canFade = { !dragging },
            onSettled = { excludeThumbFromBackGesture() },
        )
    }

    /** Whether there is enough document to be worth scrubbing. Sticky; see syncFastScroll. */
    private var thumbShown = false

    /**
     * Whether this document gets a thumb at all.
     *
     * The scroll listener runs for every WebView, so the one format setUpFastScroll turns
     * down has to be turned down here too, or its scrolls would put a thumb up anyway.
     */
    private var fastScrollEnabled = false
    /** The rect last handed to the window manager; see excludeThumbFromBackGesture. */
    private var excludedRect: Rect? = null

    private var dragging = false
    private var grabOffset = 0f
    private var pendingScrollY = 0
    private var scrollQueued = false

    /**
     * Wires the thumb to a WebView, for every format but one.
     *
     * A photo is the document this is wrong for. Fitted to the screen it cannot scroll at
     * all, so the gate in syncFastScroll would keep the thumb away by itself; pinched to
     * three times it can, because the scroll range grows with the zoom while the screen
     * does not, and the gate then opens and puts a vertical scrubber over an image
     * somebody is panning in two directions.
     *
     * Nothing else is excluded by kind, and UNSUPPORTED in particular must not be: its
     * "View as text" button replaces the card with text.html inside this same WebView
     * while the kind here stays UNSUPPORTED, and the file it does that for is exactly the
     * multi-megabyte one worth scrubbing. Geometry already hides the thumb on the card,
     * which is a screenful, and brings it back on the text, which is not.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setUpFastScroll(web: ScrollProbeWebView, kind: FileKind) {
        fastScrollEnabled = false
        hideFastScrollNow()
        if (kind == FileKind.IMAGE_WEB) return
        fastScrollEnabled = true
        // Gander's thumb replaces the WebView's own bar rather than sitting beside it.
        // This is the View's scrollbar and not a web one, so no stylesheet reaches it;
        // it stays on for a photo, where the thumb deliberately does not appear and that
        // bar is then the only position feedback there is.
        web.isVerticalScrollBarEnabled = false
        val track = fastScrollTrack
        val thumb = fastScrollThumb

        // Rotation does not rebuild this activity, so there is no onCreate to measure
        // from again; a layout change is the only word we get that the screen turned.
        web.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncFastScroll() }

        track.setOnTouchListener { _, event ->
            val probe = webView
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val top = thumb.translationY
                    val slop = ViewConfiguration.get(this).scaledTouchSlop
                    val onThumb = thumbShown &&
                        event.y >= top - slop && event.y <= top + thumb.height + slop
                    if (probe == null || !onThumb) {
                        // Handed back, so the parent carries on down its child list to
                        // the document. Without this the 48dp strip would swallow every
                        // scroll made with a right thumb.
                        false
                    } else {
                        dragging = true
                        grabOffset = event.y - top
                        thumb.isPressed = true
                        track.parent.requestDisallowInterceptTouchEvent(true)
                        // Nothing else can stop a fling that is already running. This
                        // overlay is a sibling of the WebView, so the touch never reaches
                        // it and never aborts its scroller, and the fling would then
                        // fight every scrollTo below.
                        probe.flingScroll(0, 0)
                        thumbFader.cancelIdle()
                        dragTo(probe, event.y)
                        true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    // The field, every time. onRenderProcessGone destroys the WebView and
                    // nulls it, and a drag under way would otherwise still be talking to
                    // one that has gone.
                    if (dragging && probe != null) { dragTo(probe, event.y); true } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) false else {
                        dragging = false
                        thumb.isPressed = false
                        // Where the finger left it is where the back gesture has to be
                        // kept off; dragTo does not pay for this on every move.
                        excludeThumbFromBackGesture()
                        restartPageIndicatorIdle()
                        thumbFader.restartIdle()
                        true
                    }
                }
                else -> false
            }
        }
        syncFastScroll()
    }

    /** Where the thumb belongs, and whether it belongs on screen at all. */
    private fun syncFastScroll() {
        if (!fastScrollEnabled) return
        val web = webView ?: return
        val track = fastScrollTrack
        val thumb = fastScrollThumb
        val range = web.verticalRange()
        val extent = web.verticalExtent()
        if (extent <= 0) return

        // Two thresholds rather than one. pptx.html reports itself finished as soon as
        // the first slide exists and keeps appending for seconds after, so a single line
        // here has the thumb appear, vanish and appear again while a deck loads.
        thumbShown = when {
            range > extent * 2 -> true
            range < extent * 3 / 2 -> false
            else -> thumbShown
        }
        if (!thumbShown) {
            hideFastScrollNow()
            return
        }

        val trackHeight = track.height
        if (trackHeight <= 0) return
        // Proportional, with a floor. Proportional alone is two pixels on a 357-page
        // document; a fixed height says nothing about how much is left in a short one.
        val floor = resources.getDimensionPixelSize(R.dimen.fast_scroll_thumb_min)
        val height = maxOf(floor, (trackHeight.toLong() * extent / range).toInt())
            .coerceAtMost(trackHeight)
        if (thumb.layoutParams.height != height) {
            thumb.layoutParams = thumb.layoutParams.also { it.height = height }
        }
        if (!dragging) {
            val travel = (trackHeight - height).toFloat()
            val scrollable = (range - extent).toFloat()
            thumb.translationY =
                if (scrollable > 0f) travel * (web.verticalOffset() / scrollable) else 0f
        }
        showFastScroll()
        excludeThumbFromBackGesture()
    }

    private fun dragTo(web: ScrollProbeWebView, y: Float) {
        val track = fastScrollTrack
        val thumb = fastScrollThumb
        val travel = (track.height - thumb.height).toFloat()
        if (travel <= 0f) return
        val at = (y - grabOffset).coerceIn(0f, travel)
        thumb.translationY = at
        val fraction = at / travel
        val scrollable = web.verticalRange() - web.verticalExtent()
        if (scrollable > 0) queueScroll((fraction * scrollable).toInt())
        showDragReadout(fraction)
    }

    /**
     * One scroll per frame however many touch events arrive in it. A drag can deliver
     * events faster than the screen refreshes, and every scrollTo is a hop into the
     * renderer process.
     *
     * The horizontal offset is carried through rather than zeroed. Five of the viewer
     * pages have no viewport meta and lay out at 980 CSS px, and a spreadsheet or a slide
     * is routinely wider than the screen even unzoomed, so scrolling to x = 0 would snap
     * the reader back to the left edge on every move.
     */
    private fun queueScroll(y: Int) {
        pendingScrollY = y
        if (scrollQueued) return
        scrollQueued = true
        fastScrollTrack.postOnAnimation {
            scrollQueued = false
            val web = webView ?: return@postOnAnimation
            web.scrollTo(web.scrollX, pendingScrollY)
        }
    }

    /**
     * The readout during a drag. It stays where it always is, at the foot of the screen.
     *
     * An earlier version had it leave and ride beside the thumb, so the number sat next
     * to the finger. One readout that never moves turned out to be the better trade.
     */
    private fun showDragReadout(fraction: Float) {
        if (searchBarOpen) return
        val pill = pageIndicator
        if (pageTotal < 2) {
            // Nothing else knows how far into a Word document or a five megabyte log the
            // reader is, so the thumb says it rather than dragging blind.
            val percent = (fraction * 100f).toInt().coerceIn(0, 100)
            pill.contentDescription = getString(R.string.scroll_position_spoken, percent)
            pill.text = getString(R.string.scroll_position, percent)
        }
        pageFader.pin()
    }

    private fun showFastScroll() {
        thumbFader.show()
        if (!dragging) thumbFader.restartIdle()
    }

    /** Off, and the drag off with it: for a document too short to be worth scrubbing. */
    private fun hideFastScrollNow() {
        dragging = false
        fastScrollThumb.isPressed = false
        thumbFader.hideNow()
    }

    /**
     * Keeps the back gesture off the thumb.
     *
     * On gesture navigation the edge the thumb sits on is the back swipe, and without
     * this the thumb is a control you cannot touch. A no-op below API 29, and one thumb
     * is nowhere near the 200dp per edge the system allows.
     */
    private fun excludeThumbFromBackGesture() {
        val track = fastScrollTrack
        val thumb = fastScrollThumb
        val want =
            if (thumb.visibility != View.VISIBLE) null
            else thumb.translationY.toInt().let { Rect(0, it, track.width, it + thumb.height) }
        // Only when it has actually moved. This is reached from the scroll listener, so
        // it runs once a frame for the length of every fling, and each call that gets
        // through allocates a Rect and a list and then reports the change to the window
        // manager across a binder. The thumb's travel truncates to the same integer for
        // several frames at a time on a long document, and submitting a rect identical
        // to the standing one buys nothing.
        if (want == excludedRect) return
        excludedRect = want
        ViewCompat.setSystemGestureExclusionRects(track, want?.let { listOf(it) } ?: emptyList())
    }

    /** In-document search: findAllAsync for most formats, a message port for PDF. */
    private fun setUpSearch(toolbar: MaterialToolbar, kind: FileKind) {
        val bar = findViewById<LinearLayout>(R.id.searchBar)
        val input = findViewById<EditText>(R.id.searchInput)
        val count = findViewById<TextView>(R.id.searchCount)
        val searchItem = toolbar.menu.findItem(R.id.action_search)

        val web = webView
        if (web == null || (kind == FileKind.PDF && !canPortSearch())) {
            // No WebView at all, or a WebView too old to carry a message channel. The
            // second is close to unreachable: message channels landed long before the
            // Chromium 125 pdf.html already refuses to run below. Hiding the button is
            // what PDF did in every release up to this one, so it is a known-good
            // place to land rather than a new failure.
            searchItem.isVisible = false
            return
        }
        searchItem.isVisible = true

        // One place that renders a count, whichever transport produced it. The
        // ordering inside it is load-bearing and is explained where it happens.
        val show = { active: Int, total: Int, settled: Boolean ->
            val asked = input.text.isNotEmpty()
            // Set the spoken form before the text: the live region fires on the text
            // change, and by then the description has to be the one to read out
            count.contentDescription = when {
                total > 0 -> getString(R.string.match_count_spoken, active, total)
                // "No matches" only once there is nothing left to look through. While
                // the PDF index is still being read a zero is a not-yet, and saying so
                // out loud would be wrong twice over: wrong now, and again when the
                // count changes under the reader.
                asked && settled -> getString(R.string.no_matches)
                else -> null
            }
            count.text = when {
                total > 0 -> getString(R.string.match_count, active, total)
                asked && settled -> getString(R.string.match_count, 0, 0)
                else -> ""
            }
        }

        val finder: Finder
        if (kind == FileKind.PDF) {
            onSearchCount = { active, total, settled -> show(active, total, settled) }
            finder = PortFinder()
        } else {
            web.setFindListener { active, total, done ->
                // Native find counts from zero and only means it once done.
                if (done) show(active + 1, total, true)
            }
            finder = NativeFinder(web)
        }

        // Every call into the finder goes through here. NativeFinder holds the WebView
        // directly, and onRenderProcessGone destroys it and nulls the field before
        // showRendererGone runs, so a clear arriving after that would land on a dead
        // one. The close path reaches this twice over: once itself, and once through the
        // text watcher that emptying the box fires.
        fun find(action: (Finder) -> Unit) { if (webView != null) action(finder) }

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        searchItem.setOnMenuItemClickListener {
            bar.visibility = LinearLayout.VISIBLE
            searchBackCallback.isEnabled = true
            searchBarOpen = true
            pageFader.hideNow()
            input.requestFocus()
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            true
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty()
                if (q.isEmpty()) {
                    find { it.clear() }
                    count.text = ""
                    count.contentDescription = null
                } else {
                    find { it.query(q) }
                }
            }
        })
        input.setOnEditorActionListener { _, _, _ ->
            find { it.next() }
            true
        }
        findViewById<ImageButton>(R.id.searchPrev).setOnClickListener { find { it.prev() } }
        findViewById<ImageButton>(R.id.searchNext).setOnClickListener { find { it.next() } }

        closeSearchBar = {
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            input.text.clear()
            find { it.clear() }
            bar.visibility = LinearLayout.GONE
            searchBackCallback.isEnabled = false
            searchBarOpen = false
        }
        findViewById<ImageButton>(R.id.searchClose).setOnClickListener { closeSearchBar() }
    }

    /**
     * What the reader last asked for, kept so it can be asked again.
     *
     * The search button is on the toolbar from the moment the activity is built, but
     * the channel does not exist until the page has run. Somebody quick enough to open
     * the box and type in that window would otherwise have their query go nowhere,
     * with the counter sitting empty and no way back except retyping it.
     */
    private var pendingQuery: String = ""

    /** One letter of command, then the payload. Read by onCommand() in pdf.html. */
    private inner class PortFinder : Finder {
        private fun send(s: String) {
            searchPort?.postMessage(WebMessageCompat(s))
        }
        override fun query(q: String) {
            pendingQuery = q
            send("q$q")
        }
        override fun next() = send("n")
        override fun prev() = send("p")
        override fun clear() {
            pendingQuery = ""
            send("c")
        }
    }

    private fun canPortSearch(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE) &&
            WebViewFeature.isFeatureSupported(
                WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK)

    /**
     * Hand pdf.html one end of a message channel.
     *
     * This is the only channel from this app into a page, and it is deliberately not
     * addJavascriptInterface. That call reflects a Java object into script running on
     * an untrusted document and lets it call methods on it; this passes strings, in
     * the same direction the query parameters on the URL already go. Nothing on
     * either side is evaluated, and what comes back is read as three integers and
     * dropped if it is anything else. See the note beside vwAskPassword in pdf.html
     * about why that boundary is most of what keeps a document away from the app.
     *
     * Posted on page finished rather than at load, because the listener that takes it
     * lives in the page and is not there until the page has run.
     */
    private fun openSearchChannel(web: WebView) {
        if (!canPortSearch() || searchPort != null) return
        val ends = WebViewCompat.createWebMessageChannel(web)
        val mine = ends[0]
        mine.setWebMessageCallback(
            Handler(Looper.getMainLooper()),
            object : WebMessagePortCompat.WebMessageCallbackCompat() {
                override fun onMessage(port: WebMessagePortCompat, message: WebMessageCompat?) {
                    val said = message?.data?.split(" ") ?: return
                    // Tagged, and read first. The search message below is three bare
                    // numbers and is left exactly as it was; "page" is not an integer,
                    // so it was already being dropped there before this branch existed.
                    if (said.size == 3 && said[0] == "page") {
                        val n = said[1].toIntOrNull() ?: return
                        val of = said[2].toIntOrNull() ?: return
                        if (n < 1 || of < 1 || n > of) return
                        if (pageTotal == 0) goToPageItem?.isVisible = true
                        pageAt = n
                        pageTotal = of
                        setPageIndicatorText()
                        showPageIndicator()
                        return
                    }
                    if (said.size != 3) return
                    val at = said[0].toIntOrNull() ?: return
                    val total = said[1].toIntOrNull() ?: return
                    if (at < 0 || total < 0) return
                    onSearchCount?.invoke(at, total, said[2] == "1")
                }
            })
        searchPort = mine
        WebViewCompat.postWebMessage(
            web, WebMessageCompat("vw-search-port", arrayOf(ends[1])), Uri.parse("*"))
        // Anything typed while there was nowhere to send it.
        if (pendingQuery.isNotEmpty()) mine.postMessage(WebMessageCompat("q$pendingQuery"))
        // And any night mode tapped in the same window. Compared against what the URL
        // carried rather than tracked as a flag, so any number of taps comes out right.
        if (Settings.night(this) != loadedNight) {
            loadedNight = Settings.night(this)
            mine.postMessage(WebMessageCompat(if (loadedNight) "i1" else "i0"))
        }
    }

    private fun closeSearchChannel() {
        runCatching { searchPort?.close() }
        searchPort = null
        onSearchCount = null
    }

    /**
     * Edge to edge is enforced from targetSdk 35 on, so push the layout out of the
     * status bar, display cutout and navigation bar areas.
     */
    private fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    /** Shared plain text becomes a temp file shown in the text viewer. */
    private fun sharedTextUri(): Uri? {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return runCatching {
            val f = File(cacheDir, "shared-text.txt")
            f.writeText(text)
            Uri.fromFile(f)
        }.getOrNull()
    }

    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) {
                        cursor.getString(idx)?.let { return it }
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun showImage(container: FrameLayout, uri: Uri, name: String, ext: String) {
        val imageView = SubsamplingScaleImageView(this)
        imageView.contentDescription = name
        imageView.setBackgroundColor(Color.BLACK)
        imageView.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
        imageView.maxScale = 12f
        imageView.orientation = Thumbs.exifRotation(contentResolver, uri)
        imageView.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
            override fun onImageLoadError(e: Exception) {
                // Some formats decode fine in the WebView even when the region decoder gives up
                container.removeAllViews()
                showWeb(container, uri, FileKind.IMAGE_WEB, name, ext)
            }
        })
        container.addView(imageView, matchParent())
        imageView.setImage(ImageSource.uri(uri))
    }

    /**
     * Video, and audio, which used to be handed the same screen.
     *
     * A track got a black rectangle with a scrubber that took itself away after two and a
     * half seconds, leaving nothing at all, and the screen was held awake for the length
     * of it: an hour of podcast with the display lit and no reason for it. Three things
     * differ now. The controls do not hide, because on a file with no picture they are
     * the only thing on screen. The screen is allowed to sleep, because nothing is being
     * looked at. And where a video would show its first frame, a track shows its cover
     * art if it has any, or a plain note if it does not.
     *
     * What this deliberately does not do is play on in the background. That needs a
     * foreground service, a foreground service needs a permission, and a permission fails
     * the build. Audio stops when Gander does, which is the honest consequence of the
     * promise on the front of the app.
     */
    /**
     * Album art at the size it will be drawn, not the size it was stored.
     *
     * A bounds pass reads the header only, then inSampleSize halves until one more halving
     * would go under the view. The target is the view's own width, with a fallback for the
     * first frame, where it has not been measured yet.
     */
    private fun sampledArt(bytes: ByteArray, target: Int): Bitmap? {
        val want = if (target > 0) target else ART_FALLBACK_PX
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= want) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun showPlayer(container: FrameLayout, uri: Uri, name: String, ext: String) {
        val audio = FileKind.isAudioExt(ext)
        // Audio comes from a layout because its transport does: controller_layout_id is
        // an XML attribute with no setter behind it.
        val playerView =
            if (audio) layoutInflater.inflate(R.layout.view_audio_player, container, false) as PlayerView
            else PlayerView(this)
        // The cover, and the note that stands in for one. It sits in the transport layout
        // rather than PlayerView's own artwork slot, because that slot centres what it is
        // given and centred is where the play button is: the picture came out underneath
        // the control covering it.
        val cover: ImageView?
        if (audio) {
            // Nothing is being looked at, so the screen may sleep, and the transport is
            // the only thing on the display, so it stays put.
            playerView.keepScreenOn = false
            playerView.controllerShowTimeoutMs = 0
            // Warm near-black rather than pure black. A track is a screen somebody sits
            // and looks at, so it may as well be the dark the rest of the app uses.
            container.setBackgroundColor(ContextCompat.getColor(this, R.color.audio_backdrop))
            cover = playerView.findViewById<ImageView>(R.id.audioCover)?.apply {
                contentDescription = name
            }

            playerView.setBackgroundColor(Color.TRANSPARENT)
            playerView.setShutterBackgroundColor(Color.TRANSPARENT)
            playerView.artworkDisplayMode = PlayerView.ARTWORK_DISPLAY_MODE_OFF
            playerView.controllerHideOnTouch = false
            // One file, so there is nothing to be previous or next to.
            playerView.setShowPreviousButton(false)
            playerView.setShowNextButton(false)
        } else {
            cover = null
            playerView.keepScreenOn = true
            playerView.controllerShowTimeoutMs = 2500
            playerView.setBackgroundColor(Color.BLACK)
        }
        container.addView(playerView, matchParent())

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            /**
             * Cover art, once the extractor has read the tags. Taken from the player
             * rather than opened a second time with a MediaMetadataRetriever, because
             * ExoPlayer has already parsed the ID3 or Vorbis picture by the time this
             * fires and holds the bytes.
             *
             * Sampled down first, the same way Thumbs decodes a photo. Embedded art is
             * commonly 1500 square and not rarely 3000, and this callback arrives on the
             * main thread as playback starts: decoded whole, a 3000 square picture is
             * some 36 MB of ARGB for a view a couple of hundred dp wide, which is the
             * one moment on this screen that cannot afford it. The tags are also
             * re-delivered as they arrive, so the same bytes are decoded once.
             */
            private var artDecoded: ByteArray? = null

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val view = cover ?: return
                val bytes = mediaMetadata.artworkData ?: return
                if (bytes === artDecoded) return
                val bmp = runCatching { sampledArt(bytes, view.width) }.getOrNull() ?: return
                artDecoded = bytes
                view.setImageBitmap(bmp)
            }

            override fun onPlayerError(error: PlaybackException) {
                exo.release()
                player = null
                container.removeAllViews()
                showWeb(container, uri, FileKind.UNSUPPORTED, name, ext)
            }
        })
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        exo.playWhenReady = true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWeb(container: FrameLayout, uri: Uri, kind: FileKind, name: String, ext: String) {
        val web = ScrollProbeWebView(this)
        webView = web

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = false
            /*
             * WebView's own floor on rendered text, which is 8 and is not neutral here.
             *
             * It exists so that a web page cannot make body text unreadably small. A
             * document is not a web page: its type sizes are the author's, the reader
             * can pinch, and a floor silently rewrites the smallest of them.
             *
             * For the PDF viewer it is worse than cosmetic, because pdf.js measures it.
             * TextLayer renders a 1px probe and reads back its height, then, finding 8,
             * lays every text span out at eight times the size it wants and scales it
             * back down by 1/8 so the words still land on the picture. That worked, and
             * it left every span in the selection layer with a layout box eight times
             * the glyphs it covers. Chromium's touch selection then resolved a drag to
             * the layer rather than to the words in it, and a selection dragged with a
             * finger stopped tracking: 584 of 600 moves in a row landing in the same
             * place, which is issue #22.
             *
             * At 1 the probe reads 1, pdf.js skips the compensation, and a span's box
             * is the size of its own text again. Measured against pdf.js's own viewer
             * in Chrome, which reads 1 because Chrome has no such floor, and which
             * tracks a finger correctly.
             *
             * PDF only, deliberately. The argument that a document's type sizes are the
             * author's holds for the Word, slide and spreadsheet viewers too, and the
             * floor is silently rewriting them there as well. But nothing has been
             * measured going wrong in those, and the way to find out is to look rather
             * than to change five viewers on the strength of one.
             */
            if (kind == FileKind.PDF) minimumFontSize = 1
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        // Neither of these can change while the document is open, and a ranged load
        // asks for hundreds of pieces, so resolve them once here instead of per
        // request. documentLength in particular is a round trip to the provider.
        val total = documentLength(uri)
        val mime = documentMime(ext)

        web.webViewClient = object : WebViewClientCompat() {
            override fun onPageFinished(view: WebView, url: String) {
                // PDF is the only viewer that searches from inside the page, so it is
                // the only one that needs a way to answer.
                if (kind == FileKind.PDF) openSearchChannel(view)
            }

            /**
             * The renderer is a process of its own, and Android is free to kill it
             * when memory runs short. That happens most easily while Gander is in
             * the background and something heavy is starting in front of it, which
             * is exactly what tapping Share and picking a large app looks like.
             *
             * Not overriding this is not the neutral choice. The default returns
             * false, and false tells WebView to kill the whole application rather
             * than leave it holding a WebView it can no longer draw. So a document
             * left open in the background could take Gander down with it, with no
             * crash of ours behind it and nothing on screen to explain it.
             *
             * Returning true keeps the process, and the price is that this WebView
             * is finished: nothing may call into it again, so it is detached and
             * destroyed here and the reader is offered the document back.
             */
            override fun onRenderProcessGone(
                view: WebView,
                detail: android.webkit.RenderProcessGoneDetail
            ): Boolean {
                if (webView === view) {
                    webView = null
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                    showRendererGone(container, detail.didCrash())
                }
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                // The document is served here rather than through WebViewAssetLoader
                // because a PathHandler is only given the path, and answering range
                // requests needs the Range header off the request itself.
                if (request.url.host == ASSET_HOST &&
                    request.url.path?.startsWith("/doc/") == true
                ) {
                    return docResponse(uri, mime, total, request.requestHeaders["Range"])
                }
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = request.url.host != ASSET_HOST
        }

        // Any scroll at all is what brings the readout up; the number in it comes from
        // the port. See showPageIndicator for why the two are separate.
        web.setOnScrollChangeListener { _, _, _, _, _ ->
            showPageIndicator()
            syncFastScroll()
        }
        setUpFastScroll(web, kind)

        container.addView(web, matchParent())
        // The load strategy is decided here, not in the page, so the headers we serve
        // and the loader the page picks cannot disagree
        val ranged = if (useRanges(total)) 1 else 0
        // Out on the URL rather than over the port, so a page opens already turned over
        // instead of drawing itself white and then again. It is also the only route that
        // survives process death and the recreate() in showRendererGone, neither of which
        // leaves a port to send anything down.
        val night = if (kind == FileKind.PDF && Settings.night(this)) 1 else 0
        loadedNight = night == 1
        web.loadUrl(
            "https://$ASSET_HOST/assets/viewer/${kind.page}" +
                "?name=${Uri.encode(name)}&ext=${Uri.encode(ext)}&ranged=$ranged" +
                "&night=$night" +
                pdfjsFloorParams(kind, web.settings.userAgentString)
        )
    }

    /**
     * Puts a message and a way back where the document was, once its renderer has
     * gone. Reloading is a full restart of the activity rather than a fresh WebView
     * in place, because search holds the WebView it was wired to and a destroyed one
     * cannot answer findAllAsync; going through onCreate again rebuilds both together.
     */
    private fun showRendererGone(container: FrameLayout, didCrash: Boolean) {
        // Both search surfaces go until there is something to search again, and for
        // PDF so does the channel: its far end was in the renderer that just died, so
        // it can neither be asked anything nor answer. The activity restarts to get a
        // working one, which is where a new channel comes from.
        closeSearchBar()
        pageAt = 0
        pageTotal = 0
        goToPageItem?.isVisible = false
        pageFader.hideNow()
        fastScrollEnabled = false
        hideFastScrollNow()
        // Night mode goes with the search item: the channel both of them speak over is
        // closed two lines down, so leaving either on screen offers a control that does
        // nothing, and night mode would go on rewriting the stored preference at a port
        // nothing is listening to.
        val goneMenu = findViewById<MaterialToolbar>(R.id.toolbar).menu
        goneMenu.findItem(R.id.action_search)?.isVisible = false
        goneMenu.findItem(R.id.action_night_mode)?.isVisible = false
        closeSearchChannel()

        container.removeAllViews()
        val card = layoutInflater.inflate(R.layout.view_render_gone, container, false)
        card.findViewById<TextView>(R.id.renderGoneTitle).setText(
            if (didCrash) R.string.render_gone_crashed else R.string.render_gone_reclaimed
        )
        card.findViewById<View>(R.id.renderGoneReload).setOnClickListener { recreate() }
        container.addView(card)
    }

    /**
     * Serves the open document at /doc/<anything>, answering range requests.
     *
     * Told that ranges are available, pdf.js pulls a large file in pieces as it
     * needs them instead of buffering all of it before drawing anything. On a
     * 53 MB scan that read was about 400 ms of a one second open, and it kept the
     * whole document in memory for as long as it was on screen.
     *
     * A fresh stream per request: the page asks for many, and out of order.
     */
    private fun docResponse(
        uri: Uri,
        mime: String,
        total: Long,
        range: String?
    ): WebResourceResponse {
        return try {
            // Ranges are only offered for documents big enough to be worth the extra
            // round trips. Measured on a Nothing Phone 2: a 53 MB scan opened 177 ms
            // faster ranged, while a 251 KB document opened 67 ms slower. Below the
            // threshold one bulk read wins; above it, reading the lot dominates.
            val rangeable = useRanges(total)
            val span = if (rangeable) range?.let { parseRange(it, total) } else null
            if (span == null) {
                val headers = mutableMapOf<String, String>()
                if (rangeable) headers["Accept-Ranges"] = "bytes"
                if (total >= 0) headers["Content-Length"] = total.toString()
                WebResourceResponse(
                    mime, null, 200, "OK", headers,
                    contentResolver.openInputStream(uri)
                )
            } else {
                val (start, end) = span
                WebResourceResponse(
                    mime, null, 206, "Partial Content",
                    mapOf(
                        "Accept-Ranges" to "bytes",
                        "Content-Range" to "bytes $start-$end/$total",
                        "Content-Length" to (end - start + 1).toString()
                    ),
                    slice(uri, start, end)
                )
            }
        } catch (e: Exception) {
            WebResourceResponse(
                "text/plain", "utf-8", 404, "Not Found",
                null, ByteArrayInputStream(ByteArray(0))
            )
        }
    }

    /**
     * Whether to serve this document in ranges. Decided in one place because both
     * the response headers and the page's choice of loader have to agree.
     */
    private fun useRanges(total: Long): Boolean =
        total >= RANGE_THRESHOLD_BYTES

    /**
     * Chromium major version of the WebView that will render the page.
     *
     * The user agent is asked first, because its Chrome/ token is the engine version
     * whatever the provider calls its package, and a vendor scheme like Huawei's
     * "15.0.4.326" says nothing about the engine. The package versionName is kept as a
     * fallback for a provider whose user agent carries no Chrome/ token at all.
     *
     * Null when neither source answers, or when both are too low to be a Chromium
     * version. A null is treated as new enough unless the provider is locked: refusing
     * PDFs on a WebView that works would be the worse mistake, and pdf.html's nomodule
     * fallback still covers the oldest engines a null could hide.
     */
    private fun webViewChromiumMajor(userAgent: String?): Int? = runCatching {
        val fromUa = userAgent
            ?.let { CHROME_TOKEN.find(it) }
            ?.groupValues?.get(1)
            ?.toIntOrNull()
            ?.takeIf { it >= PLAUSIBLE_CHROMIUM_MAJOR }
        fromUa ?: WebViewCompat.getCurrentWebViewPackage(this)
            ?.versionName
            ?.substringBefore('.')
            ?.toIntOrNull()
            ?.takeIf { it >= PLAUSIBLE_CHROMIUM_MAJOR }
    }.getOrNull()

    /** Whether the WebView about to render cannot be swapped for a different one. */
    private fun webViewProviderIsLocked(): Boolean = runCatching {
        WebViewCompat.getCurrentWebViewPackage(this)?.packageName in LOCKED_WEBVIEW_PACKAGES
    }.getOrDefault(false)

    /**
     * The parameters telling pdf.html it cannot render, and empty otherwise, including
     * for every other format: pdf.html is the only viewer loaded as an ES module, and
     * the rest are classic scripts that any engine can parse.
     *
     * "&webview=<major>&needs=<floor>" when the engine is older than the vendored
     * pdf.js supports. Both numbers are passed so the floor lives only in Kotlin rather
     * than being repeated as a literal inside a user-facing sentence in the page.
     *
     * "&locked=1" is added when updating the WebView is not something the reader can
     * do, so the page can drop the advice to go and update it. On a locked provider
     * whose version would not parse, that flag goes out on its own with no major beside
     * it, which is why the page gates on either parameter rather than on the version.
     */
    private fun pdfjsFloorParams(kind: FileKind, userAgent: String?): String {
        if (kind != FileKind.PDF) return ""
        val locked = webViewProviderIsLocked()
        val major = webViewChromiumMajor(userAgent)
            ?: return if (locked) "&needs=$PDFJS_MIN_CHROMIUM_MAJOR&locked=1" else ""
        if (major >= PDFJS_MIN_CHROMIUM_MAJOR) return ""
        return "&webview=$major&needs=$PDFJS_MIN_CHROMIUM_MAJOR" + if (locked) "&locked=1" else ""
    }

    /** Content type for the document, from the extension rather than the provider. */
    private fun documentMime(ext: String): String = when (ext) {
        "svg" -> "image/svg+xml"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    /** Length in bytes, or -1 when the provider declines to say. */
    private fun documentLength(uri: Uri): Long = runCatching {
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull()?.takeIf { it >= 0 } ?: -1L

    /**
     * "bytes=start-end" resolved against a known total. Null means serve the whole
     * thing: an unparseable header, an unsatisfiable one, or a provider that would
     * not give us a length to range against.
     */
    private fun parseRange(header: String, total: Long): Pair<Long, Long>? {
        if (total <= 0) return null
        // Only the first range of a set; pdf.js never asks for more than one
        val spec = header.substringAfter("bytes=", "").substringBefore(',').trim()
        if (spec.isEmpty()) return null
        val start = spec.substringBefore('-').trim().toLongOrNull() ?: return null
        val end = spec.substringAfter('-').trim().toLongOrNull() ?: (total - 1)
        if (start < 0 || start > end || start >= total) return null
        return start to minOf(end, total - 1)
    }

    /** Exactly [start, end], seeking to the offset rather than reading up to it. */
    private fun slice(uri: Uri, start: Long, end: Long): InputStream {
        val pfd = contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.IOException("cannot open $uri")
        val stream = java.io.FileInputStream(pfd.fileDescriptor)
        // Seekable for anything file backed; a pipe has to be read through instead
        runCatching { stream.channel.position(start) }
            .onFailure { runCatching { stream.skip(start) } }
        return LimitedInputStream(stream, end - start + 1, pfd)
    }

    /** Stops at [remaining] bytes, and closes the descriptor along with the stream. */
    private class LimitedInputStream(
        private val source: InputStream,
        private var remaining: Long,
        private val alsoClose: java.io.Closeable
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            return source.read().also { if (it >= 0) remaining-- }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = source.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int = minOf(source.available().toLong(), remaining).toInt()

        override fun close() {
            runCatching { source.close() }
            runCatching { alsoClose.close() }
        }
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
