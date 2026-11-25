package com.noa.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.TextView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var noaView: View
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var noaText: TextView
    
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var recognizerIntent: Intent
    private var isProcessing = false
    private var isWaitingForCommand = false
    private var isListening = false
    private var isDynamicIslandVisible = false // Cờ trạng thái hiển thị

    private lateinit var tts: TextToSpeech
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    
    private val GEMINI_API_KEY = "AQ.Ab8RN6K1hP_NDKFQ-m65lrW4R1Jq8HG1PC0LG-G-MbbdEecEEg"
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())
        
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NOA::WakeLock")
        wakeLock?.acquire()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        initOverlay()
        initTTS()
        initSpeech()
        
        // Ẩn vòng tròn ngay khi khởi động (Dynamic Island ẩn)
        hideDynamicIsland()
        
        // Bắt đầu nghe ngầm
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun initOverlay() {
        noaView = LayoutInflater.from(this).inflate(R.layout.overlay_noa, null)
        noaText = noaView.findViewById(R.id.noa_text)
        
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        layoutParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, flag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 100; layoutParams.y = 100
        
        windowManager.addView(noaView, layoutParams)

        val rotate = RotateAnimation(0f, 360f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f)
        rotate.duration = 4000; rotate.repeatCount = Animation.INFINITE; rotate.interpolator = LinearInterpolator()
        noaView.findViewById<View>(R.id.noa_ring).startAnimation(rotate)

        // Xử lý kéo thả
        noaView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0; private var initialY = 0
            private var initialTouchX = 0f; private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x; initialY = layoutParams.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY
                        isDragging = false
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = Math.abs(event.rawX - initialTouchX)
                        val dy = Math.abs(event.rawY - initialTouchY)
                        if (dx > 10 || dy > 10) {
                            isDragging = true
                            layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                            layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(noaView, layoutParams)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> return isDragging
                }
                return false
            }
        })
    }

    // HÀM ẨN/HIỆN VÒNG TRÒN (Dùng Alpha thay vì xóa View)
    private fun showDynamicIsland() {
        if (!isDynamicIslandVisible) {
            noaView.alpha = 0f
            noaView.animate().alpha(1f).setDuration(300).start()
            isDynamicIslandVisible = true
        }
    }

    private fun hideDynamicIsland() {
        if (isDynamicIslandVisible) {
            noaView.animate().alpha(0f).setDuration(300).withEndAction {
                isDynamicIslandVisible = false
            }.start()
        } else {
            noaView.alpha = 0f
        }
    }

    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale("vi", "VN")
            }
        }
    }

    private fun initSpeech() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            
            override fun onError(error: Int) {
                isListening = false
                // Tự động hồi sinh mic sau 1.5s nếu bị ngắt
                if (!isProcessing && !isWaitingForCommand) {
                    handler.postDelayed({ startListening() }, 1500)
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0].lowercase().trim()
                    // Bắt mọi biến thể của Nô a
                    val wakeRegex = Regex("nô\\s*a|no\\s*a|nô\\s*ạ")
                    
                    if (isWaitingForCommand) {
                        isWaitingForCommand = false
                        processCommand(text)
                    } else if (isProcessing) {
                        processCommand(text)
                    } else if (wakeRegex.containsMatchIn(text)) {
                        wakeUp()
                    } else {
                        // Không phải Nô a, tiếp tục nghe ngầm
                        if (!isProcessing) handler.postDelayed({ startListening() }, 1000)
                    }
                } else {
                    if (!isProcessing) handler.postDelayed({ startListening() }, 1000)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        if (!isListening && !isProcessing && !isWaitingForCommand && speechRecognizer != null) {
            try {
                isListening = true
                speechRecognizer?.startListening(recognizerIntent)
            } catch (e: Exception) {
                handler.postDelayed({ startListening() }, 2000)
            }
        }
    }

    // HÀM ĐÁNH THỨC KHI NGHE "NÔ A"
    private fun wakeUp() {
        isProcessing = true
        isWaitingForCommand = true
        handler.removeCallbacksAndMessages(null)
        
        // Hiện Dynamic Island
        showDynamicIsland()
        noaText.text = "DẠ"
        noaText.setTextColor(Color.WHITE)
        noaView.setBackgroundColor(Color.parseColor("#5500D4FF"))
        
        tts.speak("Dạ, tôi nghe đây", TextToSpeech.QUEUE_FLUSH, null, "wake")
        
        // Chờ 2.5s nghe lệnh
        handler.postDelayed({
            isWaitingForCommand = false
            isProcessing = false
            // Nếu không nói gì, tự động ẩn đi và nghe lại
            hideDynamicIsland()
            noaView.setBackgroundColor(Color.TRANSPARENT)
            startListening()
        }, 2500)
    }

    private fun processCommand(command: String) {
        noaText.text = "XỬ LÝ..."
        noaView.setBackgroundColor(Color.parseColor("#55FFA500"))
        
        // Lệnh tắt
        if (command.contains("tắt") || command.contains("biến đi") || command.contains("ngủ")) {
            respondToUser("Dạ, tôi ngủ đây.")
            return
        }
        
        if (command.startsWith("gọi cho") || command.startsWith("gọi điện cho")) {
            val name = command.replace("gọi cho", "").replace("gọi điện cho", "").trim()
            makeCall(name)
        } else {
            callGemini(command)
        }
    }

    private fun makeCall(name: String) {
        val matchedContacts = mutableListOf<Pair<String, String>>()
        try {
            val cursor = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)
            cursor?.use {
                while (it.moveToNext()) {
                    val contactName = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                    val number = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                    if (contactName.lowercase().replace(" ", "").contains(name.lowercase().replace(" ", ""))) {
                        matchedContacts.add(Pair(contactName, number))
                    }
                }
            }
        } catch (e: Exception) {}

        when {
            matchedContacts.isEmpty() -> respondToUser("Không tìm thấy $name trong danh bạ.")
            matchedContacts.size == 1 -> {
                respondToUser("Đang gọi cho ${matchedContacts[0].first}")
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${matchedContacts[0].second}"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            else -> {
                val names = matchedContacts.take(3).joinToString(", ") { it.first }
                respondToUser("Có nhiều người tên $name: $names. Bạn muốn gọi ai?")
            }
        }
    }

    private fun callGemini(prompt: String) {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$GEMINI_API_KEY"
        val jsonBody = JSONObject()
        val contentsArray = JSONArray()
        val contentObj = JSONObject()
        val partsArray = JSONArray()
        val partObj = JSONObject()
        try {
            partObj.put("text", prompt)
            partsArray.put(partObj)
            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
            jsonBody.put("contents", contentsArray)
        } catch (e: Exception) {
            respondToUser("Lỗi xử lý câu lệnh.")
            return
        }

        val request = Request.Builder().url(url).post(jsonBody.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { respondToUser("Lỗi kết nối mạng.") }
            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                try {
                    val jsonObject = JSONObject(responseBody)
                    if (jsonObject.has("error")) {
                        respondToUser("Máy chủ AI đang bận.")
                        return
                    }
                    val candidates = jsonObject.getJSONArray("candidates")
                    var text = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                    text = text.replace(Regex("[*#`_\\-]"), "")
                    respondToUser(text)
                } catch (e: Exception) {
                    respondToUser("Tôi không hiểu câu này.")
                }
            }
        })
    }

    private fun respondToUser(text: String) {
        handler.post {
            noaText.text = "N O A"
            noaText.setTextColor(Color.parseColor("#00FFFF"))
            noaView.setBackgroundColor(Color.TRANSPARENT)
            
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "respond")
            
            // Nói xong đợi 3s thì ẩn vòng tròn và bật lại mic
            isProcessing = false
            handler.postDelayed({
                hideDynamicIsland()
                startListening()
            }, 3000)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "noa_channel").setContentTitle("NOA Assistant").setContentText("Đang chạy ngầm...").setSmallIcon(android.R.drawable.ic_menu_compass).build()
        } else {
            Notification.Builder(this).setContentTitle("NOA Assistant").setSmallIcon(android.R.drawable.ic_menu_compass).build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("noa_channel", "NOA Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (::noaView.isInitialized) windowManager.removeView(noaView)
        try { speechRecognizer?.destroy() } catch (e: Exception) {}
        tts.stop(); tts.shutdown()
        handler.removeCallbacksAndMessages(null)
        wakeLock?.release()
    }
}
