package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class BackgroundBookingService : Service(), TextToSpeech.OnInitListener {
  private val serviceJob = SupervisorJob()
  private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
  private var tts: TextToSpeech? = null
  private var isTtsReady = false
  private val knownIds = mutableSetOf<Int>()
  private var isFirstCheck = true

  companion object {
    const val CHANNEL_ID = "cloudbus_admin_channel"
  }

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

  override fun onCreate() {
    super.onCreate()
    tts = TextToSpeech(this, this)
    createNotificationChannel()
  }

  override fun onInit(status: Int) {
    if (status == TextToSpeech.SUCCESS) {
      val result = tts?.setLanguage(Locale("hi", "IN"))
      if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
        tts?.setLanguage(Locale.US)
      }
      isTtsReady = true
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startPolling()
    return START_STICKY
  }

  private fun startPolling() {
    serviceScope.launch {
      while (isActive) {
        try {
          checkNewBookings()
        } catch (e: Exception) {
          android.util.Log.e("BackgroundService", "Error polling: ${e.message}")
        }
        delay(15000)
      }
    }
  }

  private fun checkNewBookings() {
    val url = "https://else-decision-dust-asylum.trycloudflare.com/admin/bookings"
    val request = Request.Builder()
      .url(url)
      .header("Accept", "application/json")
      .build()

    httpClient.newCall(request).execute().use { response ->
      if (!response.isSuccessful) return
      val bodyStr = response.body?.string() ?: return
      val json = JSONObject(bodyStr)
      val bookingsArray = json.optJSONArray("bookings") ?: return

      if (isFirstCheck) {
        for (i in 0 until bookingsArray.length()) {
          val b = bookingsArray.getJSONObject(i)
          knownIds.add(b.optInt("id"))
        }
        isFirstCheck = false
        return
      }

      for (i in 0 until bookingsArray.length()) {
        val b = bookingsArray.getJSONObject(i)
        val id = b.optInt("id")
        if (id != 0 && !knownIds.contains(id)) {
          knownIds.add(id)
          val name = b.optString("user_name", "यात्री")
          val source = b.optString("source", "सोर्स")
          val dest = b.optString("destination", "डेस्टिनेशन")
          val fare = b.optString("fare", b.optString("price", ""))
          
          triggerNotificationAndVoice(name, source, dest, fare, id)
        }
      }
    }
  }

  private fun triggerNotificationAndVoice(passenger: String, source: String, dest: String, fare: String, bookingId: Int) {
    val title = "🎟️ New Ticket Booked: $passenger"
    val summary = "$source ➔ $dest | ₹$fare (#BP-${String.format("%06d", bookingId)})"
    val speechText = "ध्यान दें! नया टिकट बुक हुआ है। यात्री $passenger, रूट $source से $dest, किराया $fare रुपये।"

    val intent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
      this,
      bookingId,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(title)
      .setContentText(summary)
      .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setDefaults(NotificationCompat.DEFAULT_ALL)
      .setAutoCancel(true)
      .setContentIntent(pendingIntent)
      .build()

    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.notify(bookingId, notification)

    vibratePhone(400)

    if (isTtsReady && tts != null) {
      tts?.speak(speechText, TextToSpeech.QUEUE_FLUSH, null, "BG_TTS_$bookingId")
    }
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "CloudBus Booking & Alerts",
        NotificationManager.IMPORTANCE_HIGH
      ).apply {
        description = "Instant background notifications for ticket bookings"
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
    } catch (_: Exception) {}
  }

  override fun onDestroy() {
    serviceScope.cancel()
    tts?.stop()
    tts?.shutdown()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
