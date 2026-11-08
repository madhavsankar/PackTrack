package com.packtrack.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.packtrack.app.agent.GeminiVoiceAssistant
import com.packtrack.app.data.FirestoreSyncService
import com.packtrack.app.data.PackTrackDatabase
import com.packtrack.app.domain.CarTransportMode
import com.packtrack.app.domain.ChecklistCalculator
import com.packtrack.app.domain.LuggageSpace
import com.packtrack.app.domain.TravelerTemplateCatalog
import com.packtrack.app.domain.TripMode
import com.packtrack.app.domain.WeatherForecast
import com.packtrack.app.ui.PackTrackMainScreen
import com.packtrack.app.ui.theme.PackTrackTheme
import com.packtrack.app.ui.theme.PackTrackThemeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Main Entry Point for the PackTrack Android App:
 *  1. Initializes local Room SQLite DB (`/data/data/com.packtrack.app/databases/packtrack_local.db`)
 *  2. Seeds a default London 5-day Work + Rental Car trip on first launch via [ChecklistCalculator]
 *  3. Handles:
 *     - 1-Tap Home Screen Voice Widget (`ACTION_START_VOICE`, `ACTION_DOORWAY_CHECK`)
 *     - Share Sheet from Public Gemini App (`Intent.ACTION_SEND`) -> auto-creates & opens Trip Checklist
 *     - Deep Links from Public Gemini Gem (`packtrack://create?city=...&days=...&car=...&bag=...&work=...`)
 */
class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var database: PackTrackDatabase
    private lateinit var voiceAssistant: GeminiVoiceAssistant
    private var tts: TextToSpeech? = null
    private var shouldAutoStartVoice by mutableStateOf(false)
    private var externalOpenTripId by mutableStateOf<Long?>(null)

    companion object {
        const val ACTION_START_VOICE = "com.packtrack.app.ACTION_START_VOICE"
        const val ACTION_DOORWAY_CHECK = "com.packtrack.app.ACTION_DOORWAY_CHECK"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        try {
            FirebaseApp.initializeApp(this)
            FirebaseAnalytics.getInstance(this)
        } catch (_: Exception) {
            // Ignored when built without google-services.json
        }
        PackTrackThemeState.loadFromPrefs(this)

        database = Room.databaseBuilder(
            applicationContext,
            PackTrackDatabase::class.java,
            "packtrack_local.db"
        ).fallbackToDestructiveMigration().build()

        val prefs = getSharedPreferences("packtrack_prefs", Context.MODE_PRIVATE)
        val activeUserId = FirestoreSyncService.sanitizeUserId(
            prefs.getString("active_user_id", TravelerTemplateCatalog.DEFAULT_USER_ID)
        )

        voiceAssistant = GeminiVoiceAssistant(
            dao = database.dao(),
            geminiApiKey = BuildConfig.GEMINI_API_KEY,
            currentUserId = activeUserId
        )

        tts = TextToSpeech(this, this)

        lifecycleScope.launch(Dispatchers.IO) {
            val pulled = FirestoreSyncService.pullFromFirestoreIfNeeded(database.dao(), activeUserId)
            val existing = database.dao().getTripsForUser(activeUserId)
            if (existing.isEmpty() && !pulled) {
                val starterForecast = WeatherForecast(
                    city = "London",
                    country = "United Kingdom",
                    countryCode = "GB",
                    avgHighF = 56.0,
                    avgLowF = 43.0,
                    rainyDaysCount = 3,
                    plugTypes = "Type G (UK 3-pin)",
                    isInternational = true
                )
                val tripId = database.dao().insertTrip(
                    com.packtrack.app.data.TripEntity(
                        userId = activeUserId,
                        destination = starterForecast.city,
                        country = starterForecast.country,
                        startDate = "Saved Trip",
                        durationDays = 5,
                        avgHighF = starterForecast.avgHighF,
                        avgLowF = starterForecast.avgLowF,
                        rainyDaysCount = starterForecast.rainyDaysCount,
                        plugTypes = starterForecast.plugTypes,
                        tagsCsv = "work,rental_car,international",
                        lastAgentNote = "Starter 5-day London trip (Work + Rental Vehicle)"
                    )
                )
                val userRules = database.dao().getTemplateRulesForUser(activeUserId)
                val items = ChecklistCalculator.buildChecklistForTrip(
                    tripId = tripId,
                    days = 5,
                    isWork = true,
                    carMode = CarTransportMode.RENTAL_CAR,
                    forecast = starterForecast,
                    customTemplateRules = userRules
                )
                database.dao().insertItems(items)
            }
            handleIncomingIntent(intent)
        }

        setContent {
            PackTrackTheme {
                PackTrackMainScreen(
                    dao = database.dao(),
                    voiceAssistant = voiceAssistant,
                    tts = tts,
                    autoStartVoice = shouldAutoStartVoice,
                    onVoiceStarted = { shouldAutoStartVoice = false },
                    externalOpenTripId = externalOpenTripId,
                    onExternalTripOpened = { externalOpenTripId = null }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::database.isInitialized && ::voiceAssistant.isInitialized) {
            lifecycleScope.launch(Dispatchers.IO) {
                FirestoreSyncService.pullFromFirestoreIfNeeded(database.dao(), voiceAssistant.currentUserId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lifecycleScope.launch(Dispatchers.IO) {
            handleIncomingIntent(intent)
        }
    }

    private suspend fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            ACTION_START_VOICE -> {
                shouldAutoStartVoice = true
            }
            ACTION_DOORWAY_CHECK -> {
                val activeTrip = database.dao().getTripsForUser(voiceAssistant.currentUserId).firstOrNull()
                if (activeTrip != null) {
                    database.dao().updateTripMode(activeTrip.id, TripMode.DOORWAY.code)
                    externalOpenTripId = activeTrip.id
                }
            }
            Intent.ACTION_SEND -> {
                // Public Gemini Android App Share Sheet -> Creates trip & opens TripChecklistScreen
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrBlank()) {
                    voiceAssistant.handleVoiceTurn(sharedText, null)
                    val newestTrip = database.dao().getTripsForUser(voiceAssistant.currentUserId).firstOrNull()
                    if (newestTrip != null) {
                        externalOpenTripId = newestTrip.id
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                // Deep Link: packtrack://create?city=Seattle&days=5&car=rental&bag=carry_on&work=true
                val uri = intent.data ?: return
                if (uri.scheme == "packtrack" && uri.host == "create") {
                    val city = uri.getQueryParameter("city")?.ifBlank { "Seattle" } ?: "Seattle"
                    val days = uri.getQueryParameter("days")?.toIntOrNull()?.coerceIn(1, 30) ?: 5
                    val isWork = uri.getQueryParameter("work")?.lowercase() != "false"
                    val carParam = uri.getQueryParameter("car")?.lowercase() ?: "rental"
                    val bagParam = uri.getQueryParameter("bag")?.lowercase() ?: "carry_on"

                    val carMode = when {
                        carParam.contains("own") -> CarTransportMode.OWN_CAR
                        carParam.contains("no") || carParam.contains("transit") -> CarTransportMode.NO_CAR
                        else -> CarTransportMode.RENTAL_CAR
                    }
                    val luggageSpace = when {
                        bagParam.contains("personal") || bagParam.contains("backpack") -> LuggageSpace.PERSONAL_ONLY
                        bagParam.contains("checked") -> LuggageSpace.CHECKED_BAG
                        else -> LuggageSpace.CARRY_ON
                    }
                    val (newTripId, _) = voiceAssistant.createAndSaveTrip(
                        destinationQuery = city,
                        days = days,
                        isWork = isWork,
                        carMode = carMode,
                        luggageSpace = luggageSpace
                    )
                    externalOpenTripId = newTripId
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}
