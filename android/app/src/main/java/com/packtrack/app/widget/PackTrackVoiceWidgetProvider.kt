package com.packtrack.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.packtrack.app.MainActivity
import com.packtrack.app.R

/**
 * 1-Tap Home Screen Voice Widget for PackTrack:
 *  - Button 1 ("🎙️ Talk to Gemini"): Opens PackTrack and immediately starts listening via SpeechRecognizer
 *  - Button 2 ("🚪 Doorway Check"): Opens PackTrack directly into the 60-Second Doorway Check mode
 *    (Must-Have Departure Essentials) right before walking out the door!
 */
class PackTrackVoiceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_packtrack_voice)

            // 1. "🎙️ Talk to Gemini" PendingIntent -> Launches MainActivity with ACTION_START_VOICE
            val voiceIntent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_START_VOICE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val voicePendingIntent = PendingIntent.getActivity(
                context,
                1001,
                voiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_widget_voice, voicePendingIntent)

            // 2. "🚪 Doorway Check" PendingIntent -> Launches MainActivity directly in DOORWAY mode
            val doorwayIntent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_DOORWAY_CHECK
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val doorwayPendingIntent = PendingIntent.getActivity(
                context,
                1002,
                doorwayIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_widget_doorway, doorwayPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

