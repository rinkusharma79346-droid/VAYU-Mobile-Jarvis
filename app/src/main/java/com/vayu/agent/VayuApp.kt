package com.vayu.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * VAYU Application — Global initialization.
 *
 * Creates notification channels for the accessibility service
 * and the HUD foreground service on app startup.
 */
class VayuApp : Application() {

    companion object {
        const val CHANNEL_AGENT = "vayu_agent"
        const val CHANNEL_HUD = "vayu_hud"
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val agentChannel = NotificationChannel(
            CHANNEL_AGENT,
            "VAYU Agent",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when VAYU agent is actively working"
            setShowBadge(false)
        }

        val hudChannel = NotificationChannel(
            CHANNEL_HUD,
            "VAYU HUD",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VAYU floating HUD overlay"
            setShowBadge(false)
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(agentChannel, hudChannel))
    }
}
