package com.packtrack.app.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.packtrack.app.agent.GeminiVoiceAssistant
import com.packtrack.app.data.FirestoreSyncService
import com.packtrack.app.data.PackTrackDao
import com.packtrack.app.domain.AppScreen
import com.packtrack.app.domain.TravelerTemplateCatalog

/**
 * Multi-Screen Navigation Router for PackTrack with Multi-User Profile Support:
 *  - [AppScreen.HOME]: Saved Trip Plans for active user + Profile Switcher + "Plan New Trip with Gemini" + "My Default Template"
 *  - [AppScreen.GEMINI_INTERVIEW]: Dedicated Stage-by-Stage Gemini Trip Interview (Destination/Days -> Travel Mode -> Bag Space -> Generate)
 *  - [AppScreen.TRIP_CHECKLIST]: Live Weather + Refresh, 3 Lifecycle Modes, Manual Edits, and Long-Press "⭐ Add to Default Template"
 *  - [AppScreen.TEMPLATE_EDITOR]: View & customize the active user's permanent Default Traveler Template
 */
@Composable
fun PackTrackMainScreen(
    dao: PackTrackDao,
    voiceAssistant: GeminiVoiceAssistant,
    tts: TextToSpeech?,
    autoStartVoice: Boolean = false,
    onVoiceStarted: () -> Unit = {},
    externalOpenTripId: Long? = null,
    onExternalTripOpened: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("packtrack_prefs", Context.MODE_PRIVATE) }
    var currentUserId by rememberSaveable {
        mutableStateOf(
            FirestoreSyncService.sanitizeUserId(
                prefs.getString("active_user_id", TravelerTemplateCatalog.DEFAULT_USER_ID)
            )
        )
    }

    LaunchedEffect(currentUserId) {
        voiceAssistant.currentUserId = currentUserId
    }

    var currentScreen by rememberSaveable { mutableStateOf(AppScreen.HOME) }
    var activeTripId by rememberSaveable { mutableStateOf<Long?>(null) }
    val backStack = remember { mutableStateListOf<AppScreen>() }

    fun navigateTo(target: AppScreen) {
        if (target != currentScreen) {
            backStack.add(currentScreen)
            currentScreen = target
        }
    }

    fun navigateBack() {
        currentScreen = if (backStack.isNotEmpty()) {
            backStack.removeAt(backStack.lastIndex)
        } else {
            AppScreen.HOME
        }
    }

    // If launched via the Home Screen "🎙️ Gemini Voice" widget, jump straight into the Gemini Interview screen
    LaunchedEffect(autoStartVoice) {
        if (autoStartVoice) {
            navigateTo(AppScreen.GEMINI_INTERVIEW)
        }
    }

    // If launched via Share Sheet from Public Gemini App or packtrack://create deep link, open that Trip Checklist directly!
    LaunchedEffect(externalOpenTripId) {
        if (externalOpenTripId != null) {
            activeTripId = externalOpenTripId
            navigateTo(AppScreen.TRIP_CHECKLIST)
            onExternalTripOpened()
        }
    }

    // Handle Android system Back button cleanly across sub-screens
    BackHandler(enabled = currentScreen != AppScreen.HOME) {
        navigateBack()
    }

    when (currentScreen) {
        AppScreen.HOME -> {
            HomeScreen(
                dao = dao,
                currentUserId = currentUserId,
                onSwitchUser = { newRawUser ->
                    val sanitized = FirestoreSyncService.sanitizeUserId(newRawUser)
                    currentUserId = sanitized
                    voiceAssistant.currentUserId = sanitized
                    prefs.edit().putString("active_user_id", sanitized).apply()
                },
                onOpenTrip = { tripId ->
                    activeTripId = tripId
                    navigateTo(AppScreen.TRIP_CHECKLIST)
                },
                onStartNewGeminiTrip = {
                    navigateTo(AppScreen.GEMINI_INTERVIEW)
                },
                onOpenTemplateEditor = {
                    navigateTo(AppScreen.TEMPLATE_EDITOR)
                }
            )
        }
        AppScreen.GEMINI_INTERVIEW -> {
            GeminiInterviewScreen(
                voiceAssistant = voiceAssistant,
                tts = tts,
                autoStartVoice = autoStartVoice,
                onVoiceStarted = onVoiceStarted,
                onBackToHome = { navigateBack() },
                onTripCreated = { newTripId ->
                    activeTripId = newTripId
                    backStack.clear()
                    backStack.add(AppScreen.HOME)
                    currentScreen = AppScreen.TRIP_CHECKLIST
                }
            )
        }
        AppScreen.TRIP_CHECKLIST -> {
            val tripId = activeTripId
            if (tripId == null) {
                currentScreen = AppScreen.HOME
            } else {
                TripChecklistScreen(
                    tripId = tripId,
                    currentUserId = currentUserId,
                    dao = dao,
                    voiceAssistant = voiceAssistant,
                    onBackToHome = { navigateBack() }
                )
            }
        }
        AppScreen.TEMPLATE_EDITOR -> {
            TemplateEditorScreen(
                currentUserId = currentUserId,
                dao = dao,
                onBackToHome = { navigateBack() }
            )
        }
    }
}
