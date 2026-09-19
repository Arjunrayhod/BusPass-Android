package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    companion object {
        const val TAG = "FCMService"
        const val CHANNEL_ID = "admin_booking_notifications"
        const val CHANNEL_NAME = "New Booking Notifications"
        const val PREFS_NAME = "cloudbus_fcm_prefs"
        const val KEY_FCM_TOKEN = "fcm_token"
        const val KEY_AUTH_TOKEN = "auth_token"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            tts = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(Locale("hi", "IN"))
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.US)
                    }
                    isTtsReady = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS init error: ${e.message}")
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM Token generated: $token")

        // Save token locally in SharedPreferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_FCM_TOKEN, token).apply()

        // Sync token with backend if auth token exists
        val authToken = prefs.getString(KEY_AUTH_TOKEN, null)
        if (!authToken.isNullOrEmpty()) {
            syncTokenWithBackend(token, authToken)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM Message received from: ${remoteMessage.from}")
        Log.d(TAG, "FCM Data payload: ${remoteMessage.data}")

        val data = remoteMessage.data
        val bookingId = data["booking_id"] ?: ""
        val passenger = data["passenger_name"] ?: data["passenger"] ?: "Passenger"
        val source = data["source"] ?: ""
        val destination = data["destination"] ?: ""
        val fare = data["fare"] ?: "0"

        val title = remoteMessage.notification?.title ?: data["title"] ?: "🎟️ New Ticket Booking"
        val formattedId = if (bookingId.isNotEmpty()) "#BP-${bookingId.padStart(6, '0')}" else ""
        val body = remoteMessage.notification?.body ?: data["body"] ?: "New booking $formattedId by $passenger ($source ➔ $destination, ₹$fare)"

        showAdminNotification(title, body, bookingId, passenger, source, destination, fare)
    }

    private fun showAdminNotification(
        title: String,
        body: String,
        bookingId: String,
        passenger: String,
        source: String,
        dest: String,
        fare: String
    ) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        // Intent to launch MainActivity and open the specific booking details
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("booking_id", bookingId)
            putExtra("view", "admin")
            putExtra("action", "open_booking")
        }

        val notifId = bookingId.toIntOrNull() ?: (System.currentTimeMillis() % 100000).toInt()

        val pendingIntent = PendingIntent.getActivity(
            this,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(notifId, notification)
        Log.d(TAG, "Notification posted: $title | ID: $notifId")

        // Voice announcement
        if (isTtsReady && tts != null) {
            val speechText = "ध्यान दें! नया टिकट बुक हुआ है। यात्री $passenger, रूट $source से $dest, किराया $fare रुपये।"
            tts?.speak(speechText, TextToSpeech.QUEUE_FLUSH, null, "FCM_TTS_$notifId")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Instant push notifications when users book tickets"
                enableLights(true)
                lightColor = Color.BLUE
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun syncTokenWithBackend(fcmToken: String, authToken: String) {
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
                    Log.d(TAG, "FCM Token sync response: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "FCM Token sync error: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
