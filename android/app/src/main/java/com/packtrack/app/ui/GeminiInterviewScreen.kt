package com.packtrack.app.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.packtrack.app.agent.GeminiVoiceAssistant
import com.packtrack.app.domain.CarTransportMode
import com.packtrack.app.domain.LuggageSpace
import com.packtrack.app.domain.TripDateUtils
import com.packtrack.app.domain.WeatherForecast
import kotlinx.coroutines.launch
import java.util.Locale

private enum class InterviewStep {
    DESTINATION,
    EXACT_DATES,
    FLIGHT_OR_CAR,
    RENTAL_CAR,
    BAGS,
    WORK_PURPOSE,
    CREATING
}

/**
 * SCREEN 2: CONDITIONAL TRIP SETUP INTERVIEW
 *  - Never reads aloud (TTS) when typing or tapping options
 *  - Interactive step-by-step question flow:
 *      1. Destination city
 *      2. Exact travel dates (Start Date to End Date) -> fetches exact-date Open-Meteo weather
 *      3. Flight or Personal Car?
 *         - If Personal Car -> skips rental car & bag questions -> asks Work?
 *         - If Flight -> asks Rental Car?
 *      4. Do you plan to take a rental car?
 *         - If International Flight -> automatically assumes 2 check-in bags -> asks Work?
 *         - If Domestic Flight -> asks Bags?
 *      5. How many bags? (Carry-On = standard US carry-on + shoulder bag, or Check-in bags)
 *      6. Do you plan to work on the trip? -> Calls Gemini & generates checklist
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GeminiInterviewScreen(
    voiceAssistant: GeminiVoiceAssistant,
    @Suppress("UNUSED_PARAMETER") tts: TextToSpeech?,
    autoStartVoice: Boolean = false,
    onVoiceStarted: () -> Unit = {},
    onBackToHome: () -> Unit,
    onTripCreated: (Long) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val today = remember { java.time.LocalDate.now() }
    var step by remember { mutableStateOf(InterviewStep.DESTINATION) }
    var destination by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf(today.toString()) }
    var endDate by remember { mutableStateOf(today.plusDays(4).toString()) }
    val days = remember(startDate, endDate) {
        val s = TripDateUtils.parseStartDate(startDate) ?: today
        val e = TripDateUtils.parseStartDate(endDate) ?: s.plusDays(3)
        val diff = java.time.temporal.ChronoUnit.DAYS.between(s, e).toInt()
        maxOf(1, diff).coerceAtMost(30)
    }

    var forecast by remember { mutableStateOf<WeatherForecast?>(null) }
    var carMode by remember { mutableStateOf(CarTransportMode.RENTAL_CAR) }
    var luggageSpace by remember { mutableStateOf(LuggageSpace.CARRY_ON) }
    var isBusy by remember { mutableStateOf(false) }

    var inputText by remember { mutableStateOf("") }
    var promptMessage by remember {
        mutableStateOf("Where are you heading?")
    }

    fun openAndroidDatePicker(currentIso: String, onPicked: (String) -> Unit) {
        val initial = TripDateUtils.parseStartDate(currentIso) ?: today
        android.app.DatePickerDialog(
            context,
            { _, y, m, d ->
                val picked = java.time.LocalDate.of(y, m + 1, d)
                onPicked(picked.toString())
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth
        ).show()
    }

    fun confirmDatesAndAdvance() {
        scope.launch {
            isBusy = true
            val liveWeather = voiceAssistant.fetchLiveForecast(destination, days, startDate, endDate)
            destination = liveWeather.city
            forecast = liveWeather
            isBusy = false
            step = InterviewStep.FLIGHT_OR_CAR
            promptMessage = "Are you taking a flight or driving your own car?"
        }
    }

    fun finalizeTrip(isWorkTrip: Boolean) {
        scope.launch {
            isBusy = true
            step = InterviewStep.CREATING
            val (newTripId, _) = voiceAssistant.createAndSaveTrip(
                destinationQuery = destination,
                days = days,
                isWork = isWorkTrip,
                carMode = carMode,
                luggageSpace = luggageSpace,
                startDate = startDate,
                endDate = endDate
            )
            isBusy = false
            onTripCreated(newTripId)
        }
    }

    fun processUserAnswer(rawAnswer: String) {
        val clean = rawAnswer.trim()
        if (clean.isEmpty() || isBusy) return
        val lower = clean.lowercase()

        scope.launch {
            when (step) {
                InterviewStep.DESTINATION -> {
                    val extractedCity = clean
                        .replace(Regex("""\b(going|traveling|flying|driving|to|for|in|from|days|day|nights|night)\b""", RegexOption.IGNORE_CASE), " ")
                        .replace(Regex("""[-–,]"""), " ")
                        .trim()
                        .replace(Regex("""\s+"""), " ")
                        .ifEmpty { "Seattle" }

                    destination = extractedCity.replaceFirstChar { it.uppercase() }
                    step = InterviewStep.EXACT_DATES
                    promptMessage = "What are your exact travel dates for $destination?"
                }

                InterviewStep.EXACT_DATES -> {
                    // Support natural-language date input if spoken/typed (e.g. "2026-11-10 to 2026-11-15" or "in 7 days for 4 days")
                    val isoDates = Regex("""\d{4}-\d{2}-\d{2}""").findAll(clean).map { it.value }.toList()
                    if (isoDates.size >= 2) {
                        startDate = isoDates[0]
                        endDate = isoDates[1]
                    } else if (isoDates.size == 1) {
                        startDate = isoDates[0]
                        val s = TripDateUtils.parseStartDate(startDate) ?: today
                        endDate = s.plusDays(4).toString()
                    } else {
                        val inMonths = Regex("""in\s+(\d+)\s*months?""", RegexOption.IGNORE_CASE).find(lower)?.groupValues?.get(1)?.toLongOrNull()
                        val inWeeks = Regex("""in\s+(\d+)\s*weeks?""", RegexOption.IGNORE_CASE).find(lower)?.groupValues?.get(1)?.toLongOrNull()
                        val inDays = Regex("""in\s+(\d+)\s*days?""", RegexOption.IGNORE_CASE).find(lower)?.groupValues?.get(1)?.toLongOrNull()
                        val sDate = when {
                            inMonths != null -> today.plusMonths(inMonths)
                            inWeeks != null -> today.plusWeeks(inWeeks)
                            inDays != null -> today.plusDays(inDays)
                            lower.contains("tomorrow") -> today.plusDays(1)
                            else -> TripDateUtils.parseStartDate(startDate) ?: today
                        }
                        val dur = Regex("""(?:for\s+)?(\d+)\s*(?:days?|nights?|d)\b""", RegexOption.IGNORE_CASE)
                            .find(lower.replace(Regex("""in\s+\d+\s*(?:days?|weeks?|months?)"""), ""))
                            ?.groupValues?.get(1)?.toLongOrNull() ?: 4L
                        startDate = sDate.toString()
                        endDate = sDate.plusDays(dur.coerceIn(1L, 30L)).toString()
                    }
                    confirmDatesAndAdvance()
                }

                InterviewStep.FLIGHT_OR_CAR -> {
                    val isPersonalCar = lower.contains("own") || lower.contains("personal") || lower.contains("car") && !lower.contains("flight")
                    if (isPersonalCar) {
                        carMode = CarTransportMode.OWN_CAR
                        luggageSpace = LuggageSpace.TWO_CHECKED_BAGS
                        step = InterviewStep.WORK_PURPOSE
                        promptMessage = "Do you plan to work on the trip?"
                    } else {
                        step = InterviewStep.RENTAL_CAR
                        promptMessage = "Do you plan to take a rental car?"
                    }
                }

                InterviewStep.RENTAL_CAR -> {
                    val wantsRental = lower.startsWith("yes") || lower.contains("rental")
                    carMode = if (wantsRental) CarTransportMode.RENTAL_CAR else CarTransportMode.NO_CAR

                    if (forecast?.isInternational == true) {
                        luggageSpace = LuggageSpace.TWO_CHECKED_BAGS
                        step = InterviewStep.WORK_PURPOSE
                        promptMessage = "Do you plan to work on the trip?"
                    } else {
                        step = InterviewStep.BAGS
                        promptMessage = "How many bags are you bringing?"
                    }
                }

                InterviewStep.BAGS -> {
                    luggageSpace = when {
                        lower.contains("2") || lower.contains("two") -> LuggageSpace.TWO_CHECKED_BAGS
                        lower.contains("1") || lower.contains("check") -> LuggageSpace.CHECKED_BAG
                        lower.contains("only") && lower.contains("shoulder") -> LuggageSpace.PERSONAL_ONLY
                        else -> LuggageSpace.CARRY_ON
                    }
                    step = InterviewStep.WORK_PURPOSE
                    promptMessage = "Do you plan to work on the trip?"
                }

                InterviewStep.WORK_PURPOSE -> {
                    val work = lower.startsWith("yes") || lower.contains("work") || lower.contains("business")
                    finalizeTrip(work)
                }

                InterviewStep.CREATING -> {}
            }
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrEmpty()) {
                processUserAnswer(spoken)
            }
        }
    }

    fun launchVoiceInput() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, promptMessage)
            }
            voiceLauncher.launch(intent)
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(autoStartVoice) {
        if (autoStartVoice) {
            onVoiceStarted()
            launchVoiceInput()
        }
    }

    val currentStepNumber = when (step) {
        InterviewStep.DESTINATION -> 1
        InterviewStep.EXACT_DATES -> 2
        InterviewStep.FLIGHT_OR_CAR -> 3
        InterviewStep.RENTAL_CAR -> 4
        InterviewStep.BAGS -> 5
        InterviewStep.WORK_PURPOSE, InterviewStep.CREATING -> 6
    }

    fun handleStepBack() {
        if (isBusy || step == InterviewStep.CREATING) return
        when (step) {
            InterviewStep.DESTINATION -> onBackToHome()
            InterviewStep.EXACT_DATES -> {
                step = InterviewStep.DESTINATION
                promptMessage = "Where are you heading?"
            }
            InterviewStep.FLIGHT_OR_CAR -> {
                step = InterviewStep.EXACT_DATES
                promptMessage = "What are your exact travel dates for $destination?"
            }
            InterviewStep.RENTAL_CAR -> {
                step = InterviewStep.FLIGHT_OR_CAR
                promptMessage = "Are you taking a flight or driving your own car?"
            }
            InterviewStep.BAGS -> {
                step = InterviewStep.RENTAL_CAR
                promptMessage = "Do you plan to take a rental car?"
            }
            InterviewStep.WORK_PURPOSE -> {
                if (carMode == CarTransportMode.OWN_CAR) {
                    step = InterviewStep.FLIGHT_OR_CAR
                    promptMessage = "Are you taking a flight or driving your own car?"
                } else if (forecast?.isInternational == true) {
                    step = InterviewStep.RENTAL_CAR
                    promptMessage = "Do you plan to take a rental car?"
                } else {
                    step = InterviewStep.BAGS
                    promptMessage = "How many bags are you bringing?"
                }
            }
            InterviewStep.CREATING -> {}
        }
    }

    BackHandler(enabled = !isBusy) {
        handleStepBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = { handleStepBack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Text(
                        text = if (destination.isNotEmpty() && step != InterviewStep.DESTINATION) {
                            "$destination ($days days)"
                        } else {
                            "New Trip"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            )
        },
        bottomBar = {
            if (step == InterviewStep.DESTINATION || step == InterviewStep.EXACT_DATES) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledIconButton(
                            onClick = { launchVoiceInput() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.size(46.dp)
                        ) {
                            Text("🎙️")
                        }

                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = {
                                Text(
                                    text = if (step == InterviewStep.DESTINATION) "e.g. Seattle, London, Tokyo..." else "e.g. In 7 days for 4 days...",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.weight(1f)
                        )

                        FilledIconButton(
                            onClick = {
                                val text = inputText
                                inputText = ""
                                processUserAnswer(text)
                            },
                            enabled = inputText.isNotBlank() && !isBusy,
                            modifier = Modifier.size(46.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Submit",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Subtle Progress Stepper
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (idx in 1..6) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(
                                if (idx <= currentStepNumber) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                    )
                }
            }

            // Question Title
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = promptMessage,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = when (step) {
                        InterviewStep.DESTINATION -> "Type or speak your destination city below, or tap a city preset."
                        InterviewStep.EXACT_DATES -> "Select your exact Start and End dates so we can fetch the weather forecast for your exact travel dates."
                        InterviewStep.FLIGHT_OR_CAR -> "Personal car trips automatically include car essentials and skip bag limits."
                        InterviewStep.RENTAL_CAR -> "Adds rental car documents, office ID, and car wire if needed."
                        InterviewStep.BAGS -> "Carry-On is a standard US carry-on bag plus your shoulder bag."
                        InterviewStep.WORK_PURPOSE -> "Adds laptop, laptop charger, and Bluetooth adapter if working."
                        InterviewStep.CREATING -> "Asking Gemini & building your checklist..."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isBusy || step == InterviewStep.CREATING) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                when (step) {
                    InterviewStep.DESTINATION -> {
                        val presets = listOf(
                            "Seattle",
                            "Atlanta",
                            "Austin",
                            "New York",
                            "London",
                            "Tokyo",
                            "San Francisco",
                            "Chicago"
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            presets.forEach { preset ->
                                Surface(
                                    onClick = { processUserAnswer(preset) },
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                ) {
                                    Text(
                                        text = preset,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                                    )
                                }
                            }
                        }
                    }

                    InterviewStep.EXACT_DATES -> {
                        val datePresets = listOf(
                            "Today → +3d" to (today.toString() to today.plusDays(3).toString()),
                            "Tomorrow → +4d" to (today.plusDays(1).toString() to today.plusDays(5).toString()),
                            "In 3 days (4d)" to (today.plusDays(3).toString() to today.plusDays(7).toString()),
                            "In 7 days (5d)" to (today.plusDays(7).toString() to today.plusDays(12).toString()),
                            "In 2 weeks (5d)" to (today.plusWeeks(2).toString() to today.plusWeeks(2).plusDays(5).toString()),
                            "In 1 month (7d)" to (today.plusMonths(1).toString() to today.plusMonths(1).plusDays(7).toString())
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    onClick = {
                                        openAndroidDatePicker(startDate) { picked ->
                                            startDate = picked
                                            val s = TripDateUtils.parseStartDate(picked) ?: today
                                            val e = TripDateUtils.parseStartDate(endDate) ?: s.plusDays(4)
                                            if (!e.isAfter(s)) {
                                                endDate = s.plusDays(4).toString()
                                            }
                                        }
                                    },
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text(
                                            text = "START DATE",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = startDate,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = TripDateUtils.formatRelativeStart(startDate),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                Surface(
                                    onClick = {
                                        openAndroidDatePicker(endDate) { picked ->
                                            endDate = picked
                                        }
                                    },
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text(
                                            text = "END DATE",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = endDate,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "$days day(s)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                datePresets.forEach { (label, pair) ->
                                    val selected = startDate == pair.first && endDate == pair.second
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            startDate = pair.first
                                            endDate = pair.second
                                        },
                                        label = { Text(label) }
                                    )
                                }
                            }

                            Button(
                                onClick = { confirmDatesAndAdvance() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Text("Continue ($startDate → $endDate · $days days)")
                            }
                        }
                    }

                    InterviewStep.FLIGHT_OR_CAR -> {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OptionSelectCard(
                                title = "Flight",
                                subtitle = "Flying to $destination (adds flight docs, vacuum bags & power bank)",
                                onClick = { processUserAnswer("Flight") }
                            )
                            OptionSelectCard(
                                title = "Personal Car",
                                subtitle = "Driving own car (storage space not an issue; adds car insurance, first aid & microfiber cloths)",
                                onClick = { processUserAnswer("Personal Car") }
                            )
                        }
                    }

                    InterviewStep.RENTAL_CAR -> {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OptionSelectCard(
                                title = "Yes — Rental Car",
                                subtitle = "Adds rental docs, office ID & car wire",
                                onClick = { processUserAnswer("Yes rental car") }
                            )
                            OptionSelectCard(
                                title = "No — No Rental Car",
                                subtitle = "Skip rental car items",
                                onClick = { processUserAnswer("No") }
                            )
                        }
                    }

                    InterviewStep.BAGS -> {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OptionSelectCard(
                                title = "Carry-On + Shoulder Bag",
                                subtitle = "Standard US carry-on bag + personal shoulder bag",
                                onClick = { processUserAnswer("Carry-on") }
                            )
                            OptionSelectCard(
                                title = "1 Check-In Bag + Carry-On & Shoulder Bag",
                                subtitle = "1 checked suitcase + standard carry-on & shoulder bag",
                                onClick = { processUserAnswer("1 check-in bag") }
                            )
                            OptionSelectCard(
                                title = "2 Check-In Bags + Carry-On & Shoulder Bag",
                                subtitle = "Full luggage capacity",
                                onClick = { processUserAnswer("2 check-in bags") }
                            )
                            OptionSelectCard(
                                title = "Shoulder Bag Only",
                                subtitle = "Under-seat personal bag only",
                                onClick = { processUserAnswer("Shoulder bag only") }
                            )
                        }
                    }

                    InterviewStep.WORK_PURPOSE -> {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OptionSelectCard(
                                title = "Yes — Work Trip",
                                subtitle = "Includes laptop, laptop charger & Bluetooth adapter",
                                onClick = { processUserAnswer("Yes work") }
                            )
                            OptionSelectCard(
                                title = "No — Personal / Leisure",
                                subtitle = "Skip work electronics",
                                onClick = { processUserAnswer("No leisure") }
                            )
                        }
                    }

                    InterviewStep.CREATING -> {}
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OptionSelectCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
