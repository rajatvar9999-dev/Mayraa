package com.mayra.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var keyBox: EditText
    private val history = JSONArray()
    private val ui = Handler(Looper.getMainLooper())

    private val systemPrompt =
        "Tum Mayra ho, ek pyaari, bahut friendly ladki jaisi AI assistant. " +
        "Hinglish/Hindi mein baat karo, dost ki tarah, garmjoshi se. Jawab chhote rakho (1-3 vaakya) " +
        "kyunki wo bolkar sunaye jaayenge. Emoji, markdown aur list mat use karo. " +
        "Tum female ho, isliye 'karungi', 'bataungi' jaise shabd use karo. Problem ka poora, saaf solution do."

    private val aliases = mapOf(
        "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "whatsapp" to "whatsapp",
        "यूट्यूब" to "youtube", "youtube" to "youtube",
        "कैमरा" to "camera", "camera" to "camera",
        "क्रोम" to "chrome", "chrome" to "chrome",
        "इंस्टाग्राम" to "instagram", "instagram" to "instagram",
        "सेटिंग" to "settings", "settings" to "settings",
        "कैलकुलेटर" to "calculator", "calculator" to "calculator",
        "मैप" to "maps", "maps" to "maps",
        "गैलरी" to "album", "gallery" to "album",
        "प्ले स्टोर" to "play store", "play store" to "play store"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestPermissions(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.CALL_PHONE
            ), 1
        )
        tts = TextToSpeech(this, this)
    }

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        val title = TextView(this).apply {
            text = "Mayra"
            textSize = 28f
            gravity = Gravity.CENTER
        }
        keyBox = EditText(this).apply {
            hint = "Gemini API key yahan paste karo"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs().getString("gkey", ""))
        }
        val save = Button(this).apply {
            text = "Key save karo"
            setOnClickListener {
                prefs().edit().putString("gkey", keyBox.text.toString().trim()).apply()
                say("Key save ho gayi!")
            }
        }
        logView = TextView(this).apply { textSize = 16f }
        scroll = ScrollView(this).apply { addView(logView) }
        val mic = Button(this).apply {
            text = "Mayra se baat karo"
            textSize = 18f
            setOnClickListener { startListening() }
        }
        root.addView(title)
        root.addView(keyBox)
        root.addView(save)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(mic, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun prefs() = getSharedPreferences("mayra", Context.MODE_PRIVATE)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale("hi", "IN")
        val voices = tts.voices?.filter { it.locale.language == "hi" } ?: emptyList()
        val v = voices.firstOrNull {
            it.name.contains("hia") || it.name.contains("hic") || it.name.contains("female", true)
        } ?: voices.firstOrNull()
        if (v != null) tts.voice = v
        tts.setPitch(1.15f)
        tts.setSpeechRate(1.0f)
        ttsReady = true
        say("Hi! Main Mayra hu. Bolo, kya kaam hai?")
    }

    private fun say(text: String) {
        logView.append("Mayra: $text\n\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mayra")
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Is phone mein voice recognition available nahi hai.")
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { say("Mujhe suna nahi, dobara bolo na.") }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) return
                logView.append("Tum: $text\n\n")
                handle(text)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        }
        r.startListening(i)
    }

    private fun handle(text: String) {
        val t = text.lowercase()
        val answer = listOf("call utha", "call uthao", "phone utha", "call receive", "कॉल उठा", "फोन उठा", "कॉल रिसीव", "call answer")
        val dial = listOf("call kar", "call lag", "कॉल कर", "कॉल लगा", "dial")
        val open = listOf("khol", "open", "खोल", "चालू कर", "start kar")
        when {
            answer.any { t.contains(it) } -> answerCall(t)
            dial.any { t.contains(it) } -> dialNumber(t)
            open.any { t.contains(it) } -> openApp(t)
            else -> askAi(text)
        }
    }

    private fun openApp(t: String) {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val wanted = aliases.entries.firstOrNull { t.contains(it.key) }?.value
        val target = apps.firstOrNull {
            val label = it.loadLabel(pm).toString().lowercase()
            label.isNotBlank() && ((wanted != null && label.contains(wanted)) || t.contains(label))
        }
        val launch = target?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (launch != null) {
            say("Ye lo, ${target.loadLabel(pm)} khol rahi hu.")
            startActivity(launch)
        } else {
            say("Mujhe wo app nahi mila. Naam dobara bolo?")
        }
    }

    private fun dialNumber(t: String) {
        val digits = t.filter { it.isDigit() }
        if (digits.length < 5) {
            say("Number bolo, jaise call kar 9 8 7 6 5 4 3 2 1 0.")
            return
        }
        try {
            say("Call laga rahi hu.")
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$digits")))
        } catch (e: SecurityException) {
            say("Call ki permission nahi mili.")
        }
    }

    private fun answerCall(t: String) {
        try {
            val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.acceptRingingCall()
        } catch (e: SecurityException) {
            say("Call uthane ki permission nahi mili.")
            return
        } catch (e: Exception) {
            say("Abhi koi call ring nahi ho rahi.")
            return
        }
        val markers = listOf("bolo", "bol do", "बोलो", "बोल दो", "keh do", "कह दो")
        var msg = ""
        for (m in markers) {
            val idx = t.indexOf(m)
            if (idx >= 0) { msg = t.substring(idx + m.length).trim(); break }
        }
        ui.postDelayed({
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = true
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL,
                am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
            if (msg.isNotBlank()) {
                askAi(
                    "Tum abhi mere phone par call utha chuki ho aur caller se baat kar rahi ho. " +
                    "Meri taraf se caller ko ye baat politely, 2 vaakya mein bolo: $msg"
                )
            } else {
                say("Hello, main Mayra bol rahi hu, inki assistant. Boliye?")
            }
        }, 1500)
    }

    private fun part(text: String) = JSONArray().put(JSONObject().put("text", text))

    private fun askAi(userText: String) {
        val key = prefs().getString("gkey", "") ?: ""
        if (key.isBlank()) {
            say("Pehle upar Gemini API key daal ke save karo.")
            return
        }
        history.put(JSONObject().put("role", "user").put("parts", part(userText)))
        Thread {
            try {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", part(systemPrompt)))
                    .put("contents", history)
                    .put("generationConfig", JSONObject().put("maxOutputTokens", 1024))
                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent"
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 15000
                c.readTimeout = 40000
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-goog-api-key", key)
                c.doOutput = true
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val resp = stream.bufferedReader().readText()
                if (code !in 200..299) throw Exception("Error $code")
                val parts = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                val sb = StringBuilder()
                for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text", ""))
                val reply = sb.toString().trim()
                if (reply.isEmpty()) throw Exception("Khali jawab aaya")
                history.put(JSONObject().put("role", "model").put("parts", part(reply)))
                while (history.length() > 12) history.remove(0)
                while (history.length() > 0 && history.getJSONObject(0).getString("role") != "user") history.remove(0)
                ui.post { say(reply) }
            } catch (e: Exception) {
                if (history.length() > 0 && history.getJSONObject(history.length() - 1).getString("role") == "user") {
                    history.remove(history.length() - 1)
                }
                ui.post { say("Kuch gadbad ho gayi: ${e.message}. Internet ya key check karo, ya thodi der baad try karo.") }
            }
        }.start()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts.shutdown()
        super.onDestroy()
    }
}
