package app.wainv.wrapper

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var pendingWebPermission: PermissionRequest? = null

    private val home = "https://wainv.vercel.app/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // System bars
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        // Root layout handles status bar and navigation bar spacing
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.WHITE)

        webView = WebView(this)
        webView.setBackgroundColor(Color.WHITE)

        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )

            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                bars.bottom
            )

            insets
        }

        ViewCompat.requestApplyInsets(root)

        // WebView settings
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }

        // JavaScript download bridge
        webView.addJavascriptInterface(
            DownloadBridge(),
            "WaInvDownload"
        )

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (!request.isForMainFrame) return false
                return handleNavigation(request.url)
            }

            @Deprecated("Deprecated in Android")
            override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String
            ): Boolean {
                return handleNavigation(Uri.parse(url))
            }

            override fun onPageFinished(
                view: WebView,
                url: String
            ) {
                super.onPageFinished(view, url)

                if (Uri.parse(url).host == "wainv.vercel.app") {
                    installBlobDownloadSupport()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onPermissionRequest(request: PermissionRequest) {

                val origin = request.origin.host

                val wantsCamera = request.resources.contains(
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE
                )

                if (origin != "wainv.vercel.app" || !wantsCamera) {
                    request.deny()
                    return
                }

                if (
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    request.grant(
                        arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    )
                } else {
                    pendingWebPermission = request

                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.CAMERA),
                        101
                    )
                }
            }
        }

        // Normal file downloads
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            downloadFile(
                url,
                userAgent,
                contentDisposition,
                mimeType
            )
        }

        webView.loadUrl(home)
    }

    // Support JavaScript-generated Blob downloads
    private fun installBlobDownloadSupport() {

        val script = """
            (function() {
                if (window.__wainvDownloadInstalled) return;
                window.__wainvDownloadInstalled = true;

                document.addEventListener('click', function(event) {
                    var link = event.target.closest('a[download]');
                    if (!link) return;

                    var href = link.href;
                    if (!href || !href.startsWith('blob:')) return;

                    event.preventDefault();

                    fetch(href)
                        .then(function(response) {
                            return response.blob();
                        })
                        .then(function(blob) {
                            var reader = new FileReader();

                            reader.onloadend = function() {
                                var base64 = reader.result.split(',')[1];

                                window.WaInvDownload.saveBase64(
                                    base64,
                                    blob.type || 'application/octet-stream',
                                    link.download || 'WaInv-Bill'
                                );
                            };

                            reader.readAsDataURL(blob);
                        })
                        .catch(function(error) {
                            console.error(error);
                        });
                }, true);
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    // Download normal HTTP and HTTPS files
    private fun downloadFile(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        try {

            if (
                !url.startsWith("http://") &&
                !url.startsWith("https://")
            ) {
                Toast.makeText(
                    this,
                    "Unsupported download format",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val fileName = URLUtil.guessFileName(
                url,
                contentDisposition,
                mimeType
            )

            val request = DownloadManager.Request(Uri.parse(url))

            request.setTitle(fileName)
            request.setDescription("Downloading WaInv bill")
            request.setMimeType(mimeType)

            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )

            request.addRequestHeader("User-Agent", userAgent)

            val cookies = CookieManager.getInstance().getCookie(url)

            if (!cookies.isNullOrEmpty()) {
                request.addRequestHeader("Cookie", cookies)
            }

            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            )

            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)

            Toast.makeText(
                this,
                "Bill download started",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Download failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Save JavaScript-generated files
    inner class DownloadBridge {

        @JavascriptInterface
        fun saveBase64(
            base64: String,
            mimeType: String,
            fileName: String
        ) {
            try {

                val bytes = Base64.decode(
                    base64,
                    Base64.DEFAULT
                )

                val safeName = fileName
                    .substringAfterLast("/")
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .let {
                        if (it.isBlank()) "WaInv-Bill" else it
                    }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS
                        )
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }

                    val resolver = contentResolver

                    val uri = resolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values
                    ) ?: throw Exception("Could not create download file")

                    resolver.openOutputStream(uri)?.use {
                        it.write(bytes)
                    } ?: throw Exception("Could not save file")

                    val finished = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }

                    resolver.update(uri, finished, null, null)

                } else {

                    val folder = getExternalFilesDir(
                        Environment.DIRECTORY_DOWNLOADS
                    ) ?: throw Exception("Download folder unavailable")

                    val file = File(folder, safeName)

                    FileOutputStream(file).use {
                        it.write(bytes)
                    }
                }

                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Bill saved to Downloads",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {

                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Could not save bill: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // External links and UPI
    private fun handleNavigation(uri: Uri): Boolean {

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return true

        if (
            scheme == "https" &&
            uri.host == "wainv.vercel.app"
        ) {
            return false
        }

        if (scheme == "http" || scheme == "https") {
            return openExternal(uri)
        }

        if (scheme == "intent") {

            return try {

                val intent = Intent.parseUri(
                    uri.toString(),
                    Intent.URI_INTENT_SCHEME
                )

                try {
                    startActivity(
                        Intent.createChooser(intent, "Open with")
                    )
                } catch (e: Exception) {

                    val fallback = intent.getStringExtra(
                        "browser_fallback_url"
                    )

                    if (!fallback.isNullOrBlank()) {
                        openExternal(Uri.parse(fallback))
                    } else {
                        showNoAppMessage()
                    }
                }

                true

            } catch (e: Exception) {
                showNoAppMessage()
                true
            }
        }

        if (
            scheme == "upi" ||
            scheme == "gpay" ||
            scheme == "phonepe" ||
            scheme == "paytmmp" ||
            scheme == "bhim" ||
            scheme == "tez" ||
            scheme == "mailto" ||
            scheme == "tel" ||
            scheme == "whatsapp"
        ) {
            return openExternal(uri)
        }

        return true
    }

    private fun openExternal(uri: Uri): Boolean {

        return try {

            val intent = Intent(Intent.ACTION_VIEW, uri)

            startActivity(
                Intent.createChooser(intent, "Open with")
            )

            true

        } catch (e: Exception) {

            showNoAppMessage()
            true
        }
    }

    private fun showNoAppMessage() {
        Toast.makeText(
            this,
            "No compatible app found to open this link",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == 101) {

            val request = pendingWebPermission
            pendingWebPermission = null

            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                request?.grant(
                    arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                )
            } else {
                request?.deny()
            }
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {

        if (
            ::webView.isInitialized &&
            webView.canGoBack()
        ) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {

        if (::webView.isInitialized) {
            pendingWebPermission?.deny()
            pendingWebPermission = null
            webView.destroy()
        }

        super.onDestroy()
    }
}
