package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
  private lateinit var webView: WebView
  private var pendingPermissionRequest: PermissionRequest? = null
  private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

  private val requestCameraPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { isGranted: Boolean ->
    runOnUiThread {
      if (isGranted) {
        pendingPermissionRequest?.let { req ->
          req.grant(req.resources)
        }
      } else {
        pendingPermissionRequest?.deny()
      }
      pendingPermissionRequest = null
    }
  }

  private val fileChooserLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == RESULT_OK) {
      val intent = result.data
      val uris = when {
        intent?.clipData != null -> {
          val count = intent.clipData!!.itemCount
          Array(count) { i -> intent.clipData!!.getItemAt(i).uri }
        }
        intent?.data != null -> arrayOf(intent.data!!)
        else -> null
      }
      fileUploadCallback?.onReceiveValue(uris)
    } else {
      fileUploadCallback?.onReceiveValue(null)
    }
    fileUploadCallback = null
  }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // Check and request camera permission upfront if needed
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
    
    // Set matching solid dark status bar to prevent header overlap
    window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    window.statusBarColor = Color.parseColor("#0F172A")
    window.navigationBarColor = Color.parseColor("#0F172A")

    webView = WebView(this).apply {
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
      
      settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        allowFileAccess = true
        allowContentAccess = true
        cacheMode = WebSettings.LOAD_DEFAULT
        useWideViewPort = true
        loadWithOverviewMode = true
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        mediaPlaybackRequiresUserGesture = false
        setSupportMultipleWindows(true)
        javaScriptCanOpenWindowsAutomatically = true
      }

      webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
          val url = request?.url?.toString() ?: return false
          return handleExternalUrl(url, view)
        }

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
          if (url == null) return false
          return handleExternalUrl(url, view)
        }

        private fun handleExternalUrl(url: String, view: WebView?): Boolean {
          if (url.endsWith(".pdf") || url.contains("/api/ticket/pdf/") || url.contains("download=1") || url.contains("github.com") || url.contains("google.com/maps")) {
            try {
              val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                addCategory(android.content.Intent.CATEGORY_BROWSABLE)
              }
              startActivity(intent)
              return true
            } catch (e: Exception) {
              return false
            }
          }
          return false
        }
      }

      setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
        try {
          val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            addCategory(android.content.Intent.CATEGORY_BROWSABLE)
          }
          startActivity(intent)
        } catch (e: Exception) {
          try {
            val request = android.app.DownloadManager.Request(android.net.Uri.parse(url)).apply {
              setMimeType(if (url.contains(".pdf") || mimetype?.contains("pdf") == true) "application/pdf" else mimetype)
              addRequestHeader("User-Agent", userAgent)
              setDescription("Downloading CloudBus Pass...")
              val filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, "application/pdf")
              setTitle(filename)
              setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
              setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, filename)
            }
            val dm = getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            dm.enqueue(request)
            android.widget.Toast.makeText(applicationContext, "Downloading Pass...", android.widget.Toast.LENGTH_SHORT).show()
          } catch (_: Exception) {}
        }
      }

      webChromeClient = object : WebChromeClient() {
        override fun onCreateWindow(
          view: WebView?,
          isDialog: Boolean,
          isUserGesture: Boolean,
          resultMsg: android.os.Message?
        ): Boolean {
          val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
          val tempWebView = WebView(this@MainActivity).apply {
            webViewClient = object : WebViewClient() {
              override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                try {
                  val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                  }
                  startActivity(intent)
                } catch (_: Exception) {}
                return true
              }
            }
          }
          transport.webView = tempWebView
          resultMsg.sendToTarget()
          return true
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
          runOnUiThread {
            request?.let { permReq ->
              val requestedResources = permReq.resources
              val needsCamera = requestedResources.any { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
              
              if (needsCamera) {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                  permReq.grant(requestedResources)
                } else {
                  pendingPermissionRequest = permReq
                  requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
              } else {
                permReq.grant(requestedResources)
              }
            }
          }
        }

        override fun onShowFileChooser(
          webView: WebView?,
          filePathCallback: ValueCallback<Array<Uri>>?,
          fileChooserParams: FileChooserParams?
        ): Boolean {
          fileUploadCallback?.onReceiveValue(null)
          fileUploadCallback = filePathCallback

          try {
            val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
              addCategory(Intent.CATEGORY_OPENABLE)
              type = "image/*"
            }
            fileChooserLauncher.launch(intent)
            return true
          } catch (e: Exception) {
            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = null
            return false
          }
        }

        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
          android.util.Log.d("WebView", "${message.message()} -- line ${message.lineNumber()}")
          return true
        }
      }
      
      // Load live Vercel frontend (Auto-updating without re-installing APK)
      loadUrl("https://code-alpha-bus-pass-chi.vercel.app")
    }

    setContentView(webView)

    // Handle Hardware Back Button smoothly
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        if (webView.canGoBack()) {
          webView.goBack()
        } else {
          isEnabled = false
          onBackPressedDispatcher.onBackPressed()
        }
      }
    })
  }
}

