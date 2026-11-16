package cn.com.omnimind.bot.ui.channel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import cn.com.omnimind.baselib.permission.PermissionRequest
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Voice-to-text input for the chat composer.
 *
 * Uses the on-device/system [SpeechRecognizer] so any language the system
 * recognizer supports works, including Indonesian (`id-ID`, the default).
 * Flutter calls start/stop/cancel; recognition results come back through
 * `onPartial`, `onResult`, `onError` and `onEnd` method calls.
 */
class SpeechInputChannel {
    companion object {
        private const val CHANNEL = "cn.com.omnimind.bot/SpeechInput"
        private const val DEFAULT_LANGUAGE = "id-ID"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var channel: MethodChannel? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    fun onCreate(context: Context) {
        appContext = context.applicationContext
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        channel?.setMethodCallHandler(null)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).also {
            it.setMethodCallHandler { call, result -> handle(call, result) }
        }
    }

    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isAvailable" -> {
                val ctx = appContext
                result.success(ctx != null && SpeechRecognizer.isRecognitionAvailable(ctx))
            }
            "start" -> start(call.argument<String>("language")?.takeIf { it.isNotBlank() } ?: DEFAULT_LANGUAGE, result)
            "stop" -> {
                mainHandler.post { recognizer?.stopListening() }
                result.success(true)
            }
            "cancel" -> {
                mainHandler.post { releaseRecognizer() }
                result.success(true)
            }
            else -> result.notImplemented()
        }
    }

    private fun start(language: String, result: MethodChannel.Result) {
        val ctx = appContext
        if (ctx == null || !SpeechRecognizer.isRecognitionAvailable(ctx)) {
            result.error("SPEECH_UNAVAILABLE", "Layanan pengenalan suara tidak tersedia di perangkat ini", null)
            return
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            beginListening(ctx, language, result)
            return
        }
        PermissionRequest.requestPermissions(ctx, arrayOf(Manifest.permission.RECORD_AUDIO)) { granted ->
            if (granted[Manifest.permission.RECORD_AUDIO] == true) {
                beginListening(ctx, language, result)
            } else {
                result.error("SPEECH_PERMISSION_DENIED", "Izin mikrofon ditolak", null)
            }
        }
    }

    private fun beginListening(ctx: Context, language: String, result: MethodChannel.Result) {
        mainHandler.post {
            try {
                releaseRecognizer()
                val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
                sr.setRecognitionListener(listener)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
                }
                recognizer = sr
                listening = true
                sr.startListening(intent)
                result.success(true)
            } catch (t: Throwable) {
                releaseRecognizer()
                result.error("SPEECH_START_FAILED", t.message ?: "Gagal memulai input suara", null)
            }
        }
    }

    private fun releaseRecognizer() {
        listening = false
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private fun emit(method: String, argument: Any?) {
        mainHandler.post { channel?.invokeMethod(method, argument) }
    }

    private fun firstText(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = emit("onReady", null)
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstText(partialResults)
            if (text.isNotEmpty()) emit("onPartial", text)
        }
        override fun onResults(results: Bundle?) {
            emit("onResult", firstText(results))
            emit("onEnd", null)
            mainHandler.post { releaseRecognizer() }
        }
        override fun onError(error: Int) {
            // NO_MATCH / SPEECH_TIMEOUT are normal "heard nothing" outcomes.
            val benign = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (!benign) emit("onError", error)
            emit("onEnd", null)
            mainHandler.post { releaseRecognizer() }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
        mainHandler.post { releaseRecognizer() }
    }
}
