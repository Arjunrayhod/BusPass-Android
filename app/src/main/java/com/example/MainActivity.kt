package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
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

  private val requestCameraPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { isGranted: Boolean ->
    if (isGranted) {
      pendingPermissionRequest?.grant(pendingPermissionRequest?.resources)
    } else {
      pendingPermissionRequest?.deny()
    }
    pendingPermissionRequest = null
  }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
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
      }

      webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
          url?.let { view?.loadUrl(it) }
          return true
        }
      }

      webChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest?) {
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

