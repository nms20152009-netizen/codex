package com.example.facetracker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class VoiceCommandManager(
    private val context: Context,
    private val onCommand: (VoiceCommand) -> Unit,
    private val onListeningState: (Boolean) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(CommandListener())
            }
        }
        listen()
    }

    fun stop() {
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()
        isListening = false
        onListeningState(false)
    }

    fun release() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.startListening(intent)
        isListening = true
        onListeningState(true)
    }

    private fun handleResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        for (phrase in matches) {
            val normalized = phrase.lowercase().trim()
            when {
                normalized.contains("graba ya") -> {
                    onCommand(VoiceCommand.StartRecording)
                    return
                }
                normalized.contains("shoot") -> {
                    onCommand(VoiceCommand.CapturePhoto)
                    return
                }
                normalized.contains("para") || normalized.contains("deten") -> {
                    onCommand(VoiceCommand.StopRecording)
                    return
                }
            }
        }
    }

    private inner class CommandListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            Log.w("VoiceCommandManager", "Speech error: $error")
            isListening = false
            onListeningState(false)
            listen()
        }

        override fun onResults(results: Bundle?) {
            handleResults(results)
            listen()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            handleResults(partialResults)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

sealed class VoiceCommand {
    data object StartRecording : VoiceCommand()
    data object StopRecording : VoiceCommand()
    data object CapturePhoto : VoiceCommand()
}
