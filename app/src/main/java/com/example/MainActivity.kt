package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
  private lateinit var webView: WebView
  private var pendingPermissionRequest: PermissionRequest? = null
  private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
  private var tts: TextToSpeech? = null
  private var isTtsReady = false
  private var currentFcmToken: String = ""

  companion object {
    const val CHANNEL_ID = "admin_booking_notifications"
    const val CHANNEL_NAME = "New Booking Notifications"
  }

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

  private val requestNotificationPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { isGranted: Boolean ->
    android.util.Log.d("NotificationPermission", "Granted: $isGranted")
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

  override fun onInit(status: Int) {
    if (status == TextToSpeech.SUCCESS) {
      val result = tts?.setLanguage(Locale("hi", "IN"))
      if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
        tts?.setLanguage(Locale.US)
      }
      isTtsReady = true
      android.util.Log.d("TTS", "TextToSpeech initialized successfully")
    } else {
      android.util.Log.e("TTS", "TextToSpeech initialization failed: $status")
    }
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        CHANNEL_NAME,
        NotificationManager.IMPORTANCE_HIGH
      ).apply {
        description = "Instant notifications for new ticket bookings and admin alerts"
        enableLights(true)
        lightColor = Color.BLUE
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 250, 150, 250)
      }
      val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      notificationManager.createNotificationChannel(channel)
    }
  }

  private fun vibratePhone(durationMs: Long) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
      } else {
        @Suppress("DEPRECATION")
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          v?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
          v?.vibrate(durationMs)
        }
      }
    } catch (e: Exception) {
      android.util.Log.e("Vibrator", "Vibration error: ${e.message}")
    }
  }

  inner class AndroidBridge {
    @JavascriptInterface
    fun isAndroid(): Boolean = true

    @JavascriptInterface
    fun speak(text: String, lang: String?) {
      runOnUiThread {
        if (!isTtsReady || tts == null) return@runOnUiThread
        try {
          val locale = when (lang?.lowercase()) {
            "hi", "hi-in", "hindi" -> Locale("hi", "IN")
            else -> Locale.US
          }
          val result = tts?.setLanguage(locale)
          if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(Locale.US)
          }
          tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "UTTERANCE_ID_${System.currentTimeMillis()}")
        } catch (e: Exception) {
          android.util.Log.e("TTS", "Speak error: ${e.message}")
        }
      }
    }

    @JavascriptInterface
    fun stopSpeaking() {
      runOnUiThread {
        tts?.stop()
      }
    }

    @JavascriptInterface
    fun showNotification(title: String, message: String, tag: String?) {
      runOnUiThread {
        try {
          val intent = Intent(this@MainActivity, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
          }
          val pendingIntent = PendingIntent.getActivity(
            this@MainActivity,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
          )

          val notification = NotificationCompat.Builder(this@MainActivity, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

          val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
          val notifId = (System.currentTimeMillis() % 100000).toInt()
          notificationManager.notify(notifId, notification)

          vibratePhone(250)
        } catch (e: Exception) {
          android.util.Log.e("Notification", "Show notification error: ${e.message}")
        }
      }
    }

    @JavascriptInterface
    fun getFcmToken(): String = currentFcmToken

    @JavascriptInterface
    fun saveAuthToken(authToken: String) {
      if (authToken.isNotEmpty()) {
        val prefs = getSharedPreferences(MyFirebaseMessagingService.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(MyFirebaseMessagingService.KEY_AUTH_TOKEN, authToken).apply()
        if (currentFcmToken.isNotEmpty()) {
          syncFcmTokenWithBackend(currentFcmToken, authToken)
        }
      }
    }

    @JavascriptInterface
    fun vibrate(durationMs: Long) {
      runOnUiThread {
        vibratePhone(durationMs)
      }
    }
  }

  private fun syncFcmTokenWithBackend(fcmToken: String, authToken: String) {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val client = OkHttpClient()
        val json = JSONObject().apply {
          put("fcm_token", fcmToken)
          put("device_name", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
        }
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
          .url("https://cloudbus-backend.onrender.com/api/fcm/register-token")
          .addHeader("Authorization", "Bearer $authToken")
          .post(body)
          .build()

        client.newCall(request).execute().use { response ->
          android.util.Log.d("MainActivity", "FCM token register response: ${response.code}")
        }
      } catch (e: Exception) {
        android.util.Log.e("MainActivity", "FCM sync error: ${e.message}")
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleNotificationIntent(intent)
  }

  private fun handleNotificationIntent(intent: Intent?) {
    val bookingId = intent?.getStringExtra("booking_id")
    if (!bookingId.isNullOrEmpty()) {
      val targetUrl = "https://code-alpha-bus-pass-chi.vercel.app/?view=admin&booking_id=$bookingId"
      runOnUiThread {
        webView.loadUrl(targetUrl)
      }
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Initialize Notification Channel
    createNotificationChannel()

    // Initialize Text-To-Speech
    tts = TextToSpeech(this, this)

    // Retrieve FCM Token
    try {
      FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
        if (task.isSuccessful && task.result != null) {
          currentFcmToken = task.result
          val prefs = getSharedPreferences(MyFirebaseMessagingService.PREFS_NAME, Context.MODE_PRIVATE)
          prefs.edit().putString(MyFirebaseMessagingService.KEY_FCM_TOKEN, currentFcmToken).apply()
          android.util.Log.d("MainActivity", "FCM Token: $currentFcmToken")

          val savedAuth = prefs.getString(MyFirebaseMessagingService.KEY_AUTH_TOKEN, null)
          if (!savedAuth.isNullOrEmpty()) {
            syncFcmTokenWithBackend(currentFcmToken, savedAuth)
          }
        }
      }
    } catch (e: Exception) {
      android.util.Log.e("MainActivity", "FCM init error: ${e.message}")
    }
    
    // Check and request camera permission upfront if needed
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Check and request notification permission for Android 13+ (API 33)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
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

      // Add Native Android Bridge for Voice Assistant and Notifications
      addJavascriptInterface(AndroidBridge(), "AndroidBridge")

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
          val appHost = "code-alpha-bus-pass-chi.vercel.app"
          val isExternal = !url.contains(appHost) && !url.startsWith("file://") && !url.contains("localhost")
          val isFileOrMedia = url.endsWith(".pdf") || url.contains("/api/ticket/pdf/") || url.contains("download=1")
          val isSocialOrExternal = url.contains("linkedin.com") || url.contains("github.com") || 
                                   url.contains("google.com/maps") || url.contains("instagram.com") || 
                                   url.contains("twitter.com") || url.contains("x.com") ||
                                   url.startsWith("mailto:") || url.startsWith("tel:")

          if (isExternal || isFileOrMedia || isSocialOrExternal) {
            try {
              val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addCategory(Intent.CATEGORY_BROWSABLE)
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
          val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addCategory(Intent.CATEGORY_BROWSABLE)
          }
          startActivity(intent)
        } catch (e: Exception) {
          try {
            val request = android.app.DownloadManager.Request(Uri.parse(url)).apply {
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
                return openExternal(url)
              }

              @Suppress("DEPRECATION")
              override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                return openExternal(url)
              }

              private fun openExternal(targetUrl: String): Boolean {
                try {
                  val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addCategory(Intent.CATEGORY_BROWSABLE)
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

  override fun onDestroy() {
    tts?.stop()
    tts?.shutdown()
    super.onDestroy()
  }
}

