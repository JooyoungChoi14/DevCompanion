package com.devcompanion

import android.app.Application
import android.util.Log
import com.devcompanion.bridge.BridgeServer
import com.devcompanion.bridge.BoreTunnel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DevCompanionApp : Application() {
    companion object {
        private const val TAG = "DevCompanionApp"
    }

    lateinit var bridgeServer: BridgeServer
        private set

    lateinit var boreTunnel: BoreTunnel
        private set

    var bridgeAuthToken: String = ""
        private set

    private val _tunnelUrl = MutableStateFlow<String?>(null)
    val tunnelUrl: StateFlow<String?> = _tunnelUrl.asStateFlow()

    private val _tunnelError = MutableStateFlow<String?>(null)
    val tunnelError: StateFlow<String?> = _tunnelError.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "DevCompanionApp.onCreate() starting")
        CrashHandler.install(this)

        // Initialize LLM settings (encrypted preferences)
        com.devcompanion.llm.LlmSettings.initialize(this)

        // Initialize GitHub settings (encrypted preferences)
        com.devcompanion.github.GitHubSettings.initialize(this)

        // Initialize UI preferences (non-encrypted, for UI settings)
        com.devcompanion.ui.UiPreferences.initialize(this)

        // Start BridgeServer for AI agent access
        bridgeAuthToken = generateAuthToken()
        bridgeServer = BridgeServer(authToken = bridgeAuthToken)
        try {
            bridgeServer.startServer()
            Log.i(TAG, "Bridge server started — token: $bridgeAuthToken")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start bridge server", e)
        }

        // Start bore tunnel for external network access
        boreTunnel = BoreTunnel(localPort = bridgeServer.port)
        boreTunnel.onStatusChanged = { connected, url, error ->
            _tunnelUrl.value = url
            _tunnelError.value = error
            if (connected) {
                Log.i(TAG, "Tunnel active → $url")
            } else {
                Log.w(TAG, "Tunnel disconnected: $error")
            }
        }
        try {
            boreTunnel.start()
            Log.i(TAG, "Bore tunnel starting...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start bore tunnel", e)
            _tunnelError.value = e.message
        }

        Log.i(TAG, "DevCompanionApp.onCreate() complete")
    }

    override fun onTerminate() {
        boreTunnel.stop()
        bridgeServer.stopServer()
        super.onTerminate()
    }

    private fun generateAuthToken(): String {
        val chars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..24).map { chars.random() }.joinToString("")
    }
}