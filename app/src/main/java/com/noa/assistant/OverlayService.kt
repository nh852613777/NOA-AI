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
    private var noaView: View? = null // Có thể null vì nó sẽ biến mất
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var noaText: TextView? = null
    
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var recognizerIntent: Intent
    private var isProcessing = false
    private var isWaitingForCommand = false
    private var isViewShowing = false // Cờ kiểm tra vòng tròn đang hiện hay ẩn
    private var isListening = false

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
        
        // Chuẩn bị sẵn LayoutParams nhưng CHƯA vẽ lên màn hình
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, 
            WindowManager.LayoutParams.WRAP_CONTENT, 
            flag, 
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, 
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 100
        layoutParams.y = 100 // Vị trí mặc định khi hiện lên (có thể kéo đi)

        initTTS()
        initSpeech()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // HÀM HIỂN THỊ (Giống Dynamic Island bật ra)
    private fun showView() {
        if (isViewShowing) return
        noaView = LayoutInflater.from(this).inflate(R.layout.overlay_noa, null)
        noaText = noaView?.findViewById(R.id.noa_text)
        
        windowManager.addView(noaView, layoutParams)
        isViewShowing = true

        val rotate = RotateAnimation(0f, 360f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f)
        rotate.duration = 4000; rotate.repeatCount = Animation.INFINITE; rotate.interpolator = LinearInterpolator()
        noaView?.findViewById<View>(R.id.noa_ring)?.startAnimation(rotate)

        // Bắt sự kiện kéo thả
        noaView?.setOnTouchListener(object : View.OnTouchListener {
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
                            noaView?.let { windowManager.updateViewLayout(it, layoutParams) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        return isDragging
                    }
                }
                return false
            }
        })
    }

    // HÀM ẨN ĐI (Giống Dynamic Island thu nhỏ lại)
    private fun hideView() {
        if (!isViewShowing) return
        try {
            noaView?.let { windowManager.removeView(it) }
            noaView = null
            noaText = null
            isViewShowing = false
        } catch (e: Exception) {}
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            
            override fun onError(error: Int) {
                isListening = false
                if (!isProcessing) {
                    scheduleRestart(2000)
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0].lowercase()
                    
                    if (isWaitingForCommand) {
                        isWaitingForCommand = false
                        processCommand(text)
                    } else if (isProcessing) {
                        processCommand(text)
                    } else if (text.contains("nô a") || text.contains("nô ạ") || text.contains("nô a ơi")) {
                        wakeUp()
                    } else {
                        scheduleRestart(2000)
                    }
                } else {
                    scheduleRestart(2000)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        startListening()
    }

    private fun scheduleRestart(delay: Long) {
        if (!isProcessing) {
            handler.postDelayed({ startListening() }, delay)
        }
    }

    private fun startListening() {
        if (!isListening && !isProcessing && speechRecognizer != null) {
            try {
                isListening = true
                speechRecognizer?.startListening(recognizerIntent)
            } catch (e: Exception) {
                scheduleRestart(2000)
            }
        }
    }

    // KHI NGHE THẤY TỪ KHÓA "NÔ A"
    private fun wakeUp() {
        isProcessing = true
        isWaitingForCommand = true
        handler.removeCallbacksAndMessages(null)
        
        // 1. HIỆN VÒNG TRÒN LÊN
        showView()
        noaText?.text = "DẠ"
        noaText?.setTextColor(Color.WHITE)
        noaView?.setBackgroundColor(Color.parseColor("#5500D4FF"))
        
        // 2. NÓI "DẠ"
        tts.speak("Dạ, tôi nghe đây", TextToSpeech.QUEUE_FLUSH, null, "wake")
        
        // 3. ĐỢI 2.5S ĐỂ NGHE LỆNH
        handler.postDelayed({
            isWaitingForCommand = false
            isProcessing = false
            startListening()
        }, 2500)
    }

    private fun processCommand(command: String) {
        noaText?.text = "XỬ LÝ..."
        noaView?.setBackgroundColor(Color.parseColor("#55FFA500"))
        
        // Nếu bạn nói "tắt", nó sẽ ẩn đi và ngủ
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
                        respondToUser("Máy chủ AI đang bận, vui lòng thử lại sau.")
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
            noaText?.text = "N O A"
            noaText?.setTextColor(Color.parseColor("#00FFFF"))
            noaView?.setBackgroundColor(Color.TRANSPARENT)
            
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "respond")
            
            // ĐỢI NÓI XONG THÌ BIẾN MẤT VÀO HƯ KHÔNG
            isProcessing = false
            handler.postDelayed({
                hideView() // Ẩn vòng tròn đi
                startListening() // Bật lại mic nghe ngầm
            }, 4000) 
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
        hideView() // Xóa view khi service chết
        try { speechRecognizer?.destroy() } catch (e: Exception) {}
        tts.stop(); tts.shutdown()
        handler.removeCallbacksAndMessages(null)
        wakeLock?.release()
    }
}
