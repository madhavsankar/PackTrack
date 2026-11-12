package com.packtrack.app.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.packtrack.app.BuildConfig
import com.packtrack.app.data.FirestoreSyncService
import com.packtrack.app.data.PackTrackDao
import com.packtrack.app.data.TripEntity
import com.packtrack.app.domain.TripDateUtils
import com.packtrack.app.ui.theme.AccentPreset
import com.packtrack.app.ui.theme.PackTrackThemeState
import kotlinx.coroutines.launch

/**
 * SCREEN 1: MINIMALIST HOME DASHBOARD
 *  - TopAppBar with Hamburger Menu (`Icons.Outlined.Menu`)
 *  - Slide-out Settings & Menu Drawer (`ModalNavigationDrawer`) containing:
 *      1. Cloud Profile / Account Switcher
 *      2. Default Packing Template Editor link
 *      3. Tunable Accent Theme Color swatches
 *      4. Cloud Sync button
 *  - Home Screen body contains ONLY the clean list of Trip Cards (+ New Trip FAB)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    dao: PackTrackDao,
    currentUserId: String,
    onSwitchUser: (String) -> Unit,
    onOpenTrip: (Long) -> Unit,
    onStartNewGeminiTrip: () -> Unit,
    onOpenTemplateEditor: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val trips by dao.observeTripsForUser(currentUserId).collectAsState(initial = emptyList())
    val customRules by dao.observeTemplateRulesForUser(currentUserId).collectAsState(initial = emptyList())
    val displayUser = FirestoreSyncService.displayUserLabel(currentUserId)

    var tripPendingDelete by remember { mutableStateOf<TripEntity?>(null) }
    var isSyncing by remember { mutableStateOf(false) }
    var showSwitchUserDialog by remember(currentUserId) {
        mutableStateOf(!FirestoreSyncService.isUserAllowed(currentUserId))
    }

    LaunchedEffect(currentUserId) {
        if (FirestoreSyncService.isUserAllowed(currentUserId)) {
            FirestoreSyncService.pullFromFirestoreIfNeeded(dao, currentUserId)
        }
    }

    // Close the Settings & Menu drawer on system Back press instead of exiting the app
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerContentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(300.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        // Drawer Header
                        Column {
                            Text(
                                text = "Settings & Menu",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (currentUserId != "default") "Cloud Sync Active" else "Local Sandbox",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // 1. Cloud Profile Section
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "CLOUD PROFILE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    showSwitchUserDialog = true
                                },
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (currentUserId != "default") MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                        )
                                        Text(
                                            text = displayUser,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = "Switch",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        // 2. Default Packing Template Section
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "DEFAULT TEMPLATE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    onOpenTemplateEditor()
                                },
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Default Packing Items",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = "${customRules.size} items saved",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = "Edit →",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        // 3. Temperature Preference Section (°F / °C)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "TEMPERATURE UNIT",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val units = listOf(
                                        false to "°F (Fahrenheit)",
                                        true to "°C (Celsius)"
                                    )
                                    units.forEach { (isCelsius, label) ->
                                        val selected = PackTrackThemeState.useCelsius == isCelsius
                                        Surface(
                                            onClick = { PackTrackThemeState.setTemperatureUnit(context, isCelsius) },
                                            shape = RoundedCornerShape(9.dp),
                                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                modifier = Modifier.padding(vertical = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = label,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 4. Continuous Rainbow Spectrum Accent Color Chooser
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "ACCENT COLOR",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(PackTrackThemeState.primaryColor)
                                        .border(1.5.dp, Color.White, CircleShape)
                                )
                            }
                            val spectrumColors = remember {
                                listOf(
                                    Color.hsv(0f, 0.80f, 0.92f),
                                    Color.hsv(60f, 0.80f, 0.92f),
                                    Color.hsv(120f, 0.80f, 0.92f),
                                    Color.hsv(180f, 0.80f, 0.92f),
                                    Color.hsv(240f, 0.80f, 0.92f),
                                    Color.hsv(300f, 0.80f, 0.92f),
                                    Color.hsv(360f, 0.80f, 0.92f)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        androidx.compose.ui.graphics.Brush.horizontalGradient(spectrumColors)
                                    )
                            )
                            Slider(
                                value = PackTrackThemeState.accentHue,
                                onValueChange = { PackTrackThemeState.setAccentHue(context, it) },
                                valueRange = 0f..360f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(28.dp)
                            )
                        }
                    }

                    // Bottom Sync Action inside Drawer
                    OutlinedButton(
                        onClick = {
                            isSyncing = true
                            scope.launch {
                                FirestoreSyncService.pullFromFirestoreIfNeeded(dao, currentUserId, trips)
                                val ok = FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                isSyncing = false
                                drawerState.close()
                                Toast.makeText(
                                    context,
                                    if (ok) "Synced with Cloud Firestore" else "Saved locally",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        enabled = !isSyncing,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Sync",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isSyncing) "Syncing..." else "Sync with Cloud Now")
                    }
                }
            }
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch {
                                if (drawerState.isOpen) drawerState.close() else drawerState.open()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.Menu,
                                contentDescription = "Toggle Menu",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = "PackTrack",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = "${trips.size} trip(s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = onStartNewGeminiTrip,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp),
                    icon = {
                        Icon(Icons.Default.Add, contentDescription = "New Trip")
                    },
                    text = {
                        Text(
                            text = "New Trip",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (trips.isEmpty()) {
                    item(key = "empty_state") {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "No trips yet",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Tap '+ New Trip' below to create a weather-tailored packing checklist.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    val sortedTrips = trips.sortedBy { TripDateUtils.sortKeyClosestToFarthest(it.startDate) }
                    items(sortedTrips, key = { it.id }) { trip ->
                        SavedTripCard(
                            trip = trip,
                            dao = dao,
                            onClick = { onOpenTrip(trip.id) },
                            onDeleteClick = { tripPendingDelete = trip }
                        )
                    }
                }
            }
        }
    }

    // Google Sign-In Dialog
    if (showSwitchUserDialog) {
        val isInitialPrompt = !FirestoreSyncService.isUserAllowed(currentUserId)
        var errorText by remember { mutableStateOf("") }

        val googleLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val account = task.getResult(ApiException::class.java)
                val email = account?.email?.trim()?.lowercase() ?: ""
                if (email.isBlank() || !FirestoreSyncService.isUserAllowed(email)) {
                    errorText = if (email.isBlank()) {
                        "Google Sign-In did not return an email address."
                    } else {
                        "Access denied for $email. Only allowlisted Google accounts are permitted."
                    }
                    return@rememberLauncherForActivityResult
                }
                val nextUser = FirestoreSyncService.sanitizeUserId(email)
                showSwitchUserDialog = false
                onSwitchUser(nextUser)
                scope.launch {
                    FirestoreSyncService.pullFromFirestoreIfNeeded(dao, nextUser)
                }
            } catch (e: ApiException) {
                if (result.resultCode != Activity.RESULT_CANCELED) {
                    errorText = "Google Sign-In failed (code ${e.statusCode}). Make sure your SHA-1 fingerprint is registered in Firebase."
                }
            }
        }

        AlertDialog(
            onDismissRequest = {
                if (!isInitialPrompt) {
                    showSwitchUserDialog = false
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    if (isInitialPrompt) "Sign in to PackTrack" else "Switch Google Account",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Sign in with your allowlisted Google account to access and sync your packing checklists:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (errorText.isNotEmpty()) {
                        Text(
                            text = errorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        errorText = ""
                        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestEmail()
                        val webClientId = BuildConfig.GOOGLE_CLIENT_ID.trim()
                        if (webClientId.isNotEmpty()) {
                            gsoBuilder.requestIdToken(webClientId)
                        }
                        val client = GoogleSignIn.getClient(context, gsoBuilder.build())
                        client.signOut().addOnCompleteListener {
                            googleLauncher.launch(client.signInIntent)
                        }
                    }
                ) {
                    Text("Sign in with Google")
                }
            },
            dismissButton = {
                if (!isInitialPrompt) {
                    TextButton(onClick = { showSwitchUserDialog = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    // Delete Confirmation Dialog
    tripPendingDelete?.let { trip ->
        AlertDialog(
            onDismissRequest = { tripPendingDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Delete ${trip.destination}?") },
            text = {
                Text(
                    "Remove this ${trip.durationDays}-day trip checklist?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val idToRemove = trip.id
                        tripPendingDelete = null
                        scope.launch {
                            dao.deleteTrip(idToRemove)
                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { tripPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedTripCard(
    trip: TripEntity,
    dao: PackTrackDao,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val items by dao.observeItemsForTrip(trip.id).collectAsState(initial = emptyList())
    val activeItems = remember(items) {
        items.filter { !it.weatherReason.startsWith("[SUGGESTED] ") }
    }
    val packedCount = activeItems.count { it.packedOutbound }
    val totalCount = activeItems.size

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = trip.destination,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = TripDateUtils.formatRelativeStart(trip.startDate),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.38f))
                    ) {
                        val dateRangeStr = TripDateUtils.formatDateRange(trip.startDate, trip.durationDays)
                        Text(
                            text = "$dateRangeStr · ${trip.durationDays}d",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = PackTrackThemeState.formatTempRange(trip.avgLowF, trip.avgHighF),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "$packedCount/$totalCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Delete trip",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
