package com.packtrack.app.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.packtrack.app.agent.GeminiVoiceAssistant
import com.packtrack.app.data.FirestoreSyncService
import com.packtrack.app.data.PackTrackDao
import com.packtrack.app.data.PackingItemEntity
import com.packtrack.app.data.TemplateRuleEntity
import com.packtrack.app.domain.BagLocation
import com.packtrack.app.domain.CarTransportMode
import com.packtrack.app.domain.ChecklistCalculator
import com.packtrack.app.domain.LuggageSpace
import com.packtrack.app.domain.PackingCategory
import com.packtrack.app.domain.RuleCondition
import com.packtrack.app.domain.TripDateUtils
import com.packtrack.app.domain.TripMode
import kotlinx.coroutines.launch

/**
 * SCREEN 3: MINIMALIST TRIP CHECKLIST SCREEN
 *  - Clean TopAppBar with destination title and weather refresh IconButton
 *  - Segmented 3-Mode Pill Switcher (Pack | Doorway | Checkout) + sleek progress bar
 *  - High-contrast checklist rows (dimmed when checked)
 *  - Long-Press opens a clean Material 3 ModalBottomSheet (Save to Default Template, Edit, Remove)
 *  - Bottom-right FloatingActionButton (+) for manual additions
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun TripChecklistScreen(
    tripId: Long,
    currentUserId: String,
    dao: PackTrackDao,
    voiceAssistant: GeminiVoiceAssistant,
    onBackToHome: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trip by dao.observeTripById(tripId).collectAsState(initial = null)
    val items by dao.observeItemsForTrip(tripId).collectAsState(initial = emptyList())

    var isRefreshingWeather by remember { mutableStateOf(false) }
    var showRecreateConfirmDialog by remember { mutableStateOf(false) }
    var showAddItemDialog by remember { mutableStateOf(false) }
    var longPressedItem by remember { mutableStateOf<PackingItemEntity?>(null) }
    var itemBeingEdited by remember { mutableStateOf<PackingItemEntity?>(null) }
    var itemSavingToTemplate by remember { mutableStateOf<PackingItemEntity?>(null) }

    LaunchedEffect(tripId) {
        isRefreshingWeather = true
        voiceAssistant.refreshWeatherAndUpdateTrip(tripId)
        isRefreshingWeather = false
    }

    val activeTrip = trip
    if (activeTrip == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val currentMode = TripMode.fromCode(activeTrip.tripMode)
    val isReturnSweep = currentMode == TripMode.RETURN_SWEEP
    val activeItems = remember(items) {
        items.filter { !ChecklistCalculator.isSuggested(it) }
    }
    val suggestedItems = remember(items) {
        items.filter { ChecklistCalculator.isSuggested(it) }
    }
    val displayedItems = remember(activeItems, currentMode) {
        when (currentMode) {
            TripMode.DOORWAY -> activeItems.filter { it.isDoorwayCritical }
            TripMode.RETURN_SWEEP -> activeItems.sortedByDescending { it.isHighLossReturn }
            TripMode.OUTBOUND -> activeItems
        }
    }

    val checkedCount = displayedItems.count { if (isReturnSweep) it.packedReturn else it.packedOutbound }
    val totalCount = displayedItems.size
    val progressFraction = if (totalCount > 0) checkedCount.toFloat() / totalCount.toFloat() else 0f

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = onBackToHome) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    val dateRangeStr = com.packtrack.app.domain.TripDateUtils.formatDateRange(
                        activeTrip.startDate,
                        activeTrip.durationDays
                    )
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = activeTrip.destination,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = "$dateRangeStr · ${activeTrip.durationDays}d",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        val showPlug = com.packtrack.app.domain.TravelerTemplateCatalog.isPlugInfoNeeded(
                            country = activeTrip.country,
                            plugTypes = activeTrip.plugTypes
                        )
                        val plugSuffix = if (showPlug) "  •  Plug ${activeTrip.plugTypes}" else ""
                        val tempRange = com.packtrack.app.ui.theme.PackTrackThemeState.formatTempRange(activeTrip.avgLowF, activeTrip.avgHighF)
                        Text(
                            text = "$dateRangeStr (${activeTrip.durationDays}d)  •  $tempRange  •  ${activeTrip.rainyDaysCount} rainy d$plugSuffix",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showRecreateConfirmDialog = true },
                        enabled = !isRefreshingWeather
                    ) {
                        if (isRefreshingWeather) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Recreate checklist",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Recreate",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddItemDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add item")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Segmented 3-Mode Pill Switcher
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val modeLabels = listOf(
                        TripMode.OUTBOUND to "Pack",
                        TripMode.DOORWAY to "Doorway",
                        TripMode.RETURN_SWEEP to "Checkout"
                    )
                    modeLabels.forEach { (mode, label) ->
                        val selected = currentMode == mode
                        Surface(
                            onClick = { scope.launch { dao.updateTripMode(activeTrip.id, mode.code) } },
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

            // Sleek Progress Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round
                )
                Text(
                    text = "$checkedCount / $totalCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Show clear notice if Gemini was unavailable and rule-based backup engine was used
            if (activeTrip.lastAgentNote.contains(GeminiVoiceAssistant.FALLBACK_TAG)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFF59E0B).copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    Text(
                        text = "⚠️ Gemini is unavailable — rule-based backup results were used for generating/re-generating this checklist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFBBF24),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // Categorized Checklist
            val categoryOrder = PackingCategory.orderedDisplayNames
            val grouped = displayedItems.groupBy { it.category }
            val orderedCategories = categoryOrder.filter { grouped.containsKey(it) } +
                grouped.keys.filter { it !in categoryOrder }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                orderedCategories.forEach { category ->
                    val categoryItems = grouped[category] ?: emptyList()
                    val catDone = categoryItems.count { if (isReturnSweep) it.packedReturn else it.packedOutbound }

                    item(key = "header_$category") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = category.uppercase(),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$catDone/${categoryItems.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    items(categoryItems, key = { it.id }) { item ->
                        val isChecked = if (isReturnSweep) item.packedReturn else item.packedOutbound
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (isChecked) 0.52f else 1f)
                                .clip(MaterialTheme.shapes.medium)
                                .combinedClickable(
                                    onClick = {
                                        scope.launch {
                                            if (isReturnSweep) {
                                                dao.setItemPackedReturn(item.id, !isChecked)
                                            } else {
                                                dao.setItemPackedOutbound(item.id, !isChecked)
                                            }
                                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                        }
                                    },
                                    onLongClick = {
                                        itemBeingEdited = item
                                    }
                                )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = null,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        textDecoration = if (isChecked) TextDecoration.LineThrough else TextDecoration.None,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val badge = when {
                                        isReturnSweep && item.isHighLossReturn -> "High-loss  •  "
                                        item.isDoorwayCritical -> "Doorway  •  "
                                        else -> ""
                                    }
                                    val sub = buildString {
                                        append(badge)
                                        append(item.bagLocation)
                                        if (item.weatherReason.isNotBlank()) {
                                            append("  •  ")
                                            append(item.weatherReason)
                                        }
                                    }
                                    Text(
                                        text = sub,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Quantity Pill
                                Surface(
                                    onClick = { itemBeingEdited = item },
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = "×${item.quantity}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Gemini Weather & Trip Suggestions at the end (user can Add or Dismiss)
                if (suggestedItems.isNotEmpty()) {
                    item(key = "header_gemini_suggestions") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 18.dp, bottom = 4.dp)
                        ) {
                            Text(
                                text = "✨ GEMINI WEATHER & TRIP SUGGESTIONS",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Suggested based on forecast & trip details — tap + Add to include in your checklist",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    items(suggestedItems, key = { "suggested_${it.id}" }) { suggestion ->
                        val cleanReason = ChecklistCalculator.stripSuggestedPrefix(suggestion.weatherReason)
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = suggestion.name,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.surface
                                        ) {
                                            Text(
                                                text = "${suggestion.category} • ${suggestion.bagLocation}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    if (cleanReason.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = cleanReason,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // + Add Button
                                Surface(
                                    onClick = {
                                        scope.launch {
                                            dao.updateItemQuantity(
                                                suggestion.id,
                                                suggestion.quantity,
                                                "✨ $cleanReason"
                                            )
                                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                            Toast.makeText(
                                                context,
                                                "Added '${suggestion.name}' to checklist",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primary
                                ) {
                                    Text(
                                        text = "+ Add",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                // Dismiss Button
                                Surface(
                                    onClick = {
                                        scope.launch {
                                            dao.deleteItem(suggestion.id)
                                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface
                                ) {
                                    Text(
                                        text = "✕",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Clean Material 3 ModalBottomSheet for Long-Press Actions
    longPressedItem?.let { item ->
        ModalBottomSheet(
            onDismissRequest = { longPressedItem = null },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "${item.category}  •  Quantity ×${item.quantity}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    onClick = {
                        val target = item
                        longPressedItem = null
                        itemSavingToTemplate = target
                    },
                    shape = MaterialTheme.shapes.small,
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("Save to Default Template (Choose Condition)", style = MaterialTheme.typography.bodyLarge)
                    }
                }

                Surface(
                    onClick = {
                        val target = item
                        longPressedItem = null
                        itemBeingEdited = target
                    },
                    shape = MaterialTheme.shapes.small,
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Text("Edit Name, Quantity & Bag", style = MaterialTheme.typography.bodyLarge)
                    }
                }

                Surface(
                    onClick = {
                        val target = item
                        longPressedItem = null
                        scope.launch {
                            if (currentMode == TripMode.DOORWAY) {
                                dao.setItemDoorwayCritical(target.id, false)
                                Toast.makeText(
                                    context,
                                    "Removed '${target.name}' from Doorway check (kept in Pack)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                dao.deleteItem(target.id)
                            }
                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                        }
                    },
                    shape = MaterialTheme.shapes.small,
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            if (currentMode == TripMode.DOORWAY) "Remove from Doorway Check (Keep in Pack)" else "Remove from Trip",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Edit Item Dialog (Opened directly on Long-Press or tapping Quantity Pill)
    itemBeingEdited?.let { item ->
        var editName by remember(item) { mutableStateOf(item.name) }
        var editQty by remember(item) { mutableStateOf(item.quantity.toString()) }
        var editCategory by remember(item) { mutableStateOf(item.category) }
        var editBag by remember(item) { mutableStateOf(BagLocation.fromLabel(item.bagLocation).label) }
        var editDoorway by remember(item) { mutableStateOf(item.isDoorwayCritical) }
        val allCats = remember(items) {
            com.packtrack.app.ui.theme.PackTrackThemeState.allCategories(items.map { it.category })
        }

        AlertDialog(
            onDismissRequest = { itemBeingEdited = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Edit Checklist Item") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Item Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editQty,
                        onValueChange = { editQty = it },
                        label = { Text("Quantity") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "BAG LOCATION",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BagLocation.selectableEntries.forEach { bagOpt ->
                            val selected = editBag.equals(bagOpt.label, ignoreCase = true)
                            Surface(
                                onClick = { editBag = bagOpt.label },
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                else MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = bagOpt.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = "CATEGORY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        allCats.forEach { cat ->
                            val selected = editCategory.equals(cat, ignoreCase = true)
                            Surface(
                                onClick = { editCategory = cat },
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                else MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                )
                            ) {
                                Text(
                                    text = cat,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = editDoorway,
                            onCheckedChange = { editDoorway = it }
                        )
                        Text(
                            text = "Include in 60-Second Doorway Check",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val target = item.copy(
                                    name = editName.trim().ifEmpty { item.name },
                                    category = editCategory,
                                    bagLocation = editBag,
                                    isDoorwayCritical = editDoorway
                                )
                                itemBeingEdited = null
                                itemSavingToTemplate = target
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Star,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save to Template", style = MaterialTheme.typography.labelSmall)
                        }

                        OutlinedButton(
                            onClick = {
                                val target = item
                                itemBeingEdited = null
                                scope.launch {
                                    if (currentMode == TripMode.DOORWAY) {
                                        dao.setItemDoorwayCritical(target.id, false)
                                        Toast.makeText(
                                            context,
                                            "Removed '${target.name}' from Doorway check (kept in Pack)",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        dao.deleteItem(target.id)
                                    }
                                    FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (currentMode == TripMode.DOORWAY) "Doorway" else "Remove",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val qty = editQty.toIntOrNull()?.coerceAtLeast(1) ?: item.quantity
                        val cleanName = editName.trim().ifEmpty { item.name }
                        val cleanBag = editBag.trim().ifEmpty { item.bagLocation }
                        val cleanCat = editCategory.trim().ifEmpty { item.category }
                        itemBeingEdited = null
                        scope.launch {
                            dao.updateItemDetails(item.id, cleanName, qty, cleanCat, cleanBag, editDoorway)
                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemBeingEdited = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showRecreateConfirmDialog) {
        val today = remember { java.time.LocalDate.now() }
        val initialParsedTags = remember(activeTrip.tagsCsv) {
            TripDateUtils.parseTripTags(activeTrip.tagsCsv)
        }
        val initialStartIso = remember(activeTrip.startDate) {
            (TripDateUtils.parseStartDate(activeTrip.startDate) ?: today).toString()
        }
        val initialEndIso = remember(initialStartIso, activeTrip.durationDays) {
            TripDateUtils.computeEndDate(initialStartIso, activeTrip.durationDays, today).toString()
        }

        var editStartDate by remember(activeTrip.id, activeTrip.startDate) { mutableStateOf(initialStartIso) }
        var editEndDate by remember(activeTrip.id, activeTrip.startDate, activeTrip.durationDays) { mutableStateOf(initialEndIso) }
        val editDays = remember(editStartDate, editEndDate) {
            TripDateUtils.computeDurationDays(editStartDate, editEndDate, activeTrip.durationDays)
        }
        var editCarMode by remember(activeTrip.id, activeTrip.tagsCsv) { mutableStateOf(initialParsedTags.carMode) }
        var editLuggageSpace by remember(activeTrip.id, activeTrip.tagsCsv) { mutableStateOf(initialParsedTags.luggageSpace) }
        var editIsWork by remember(activeTrip.id, activeTrip.tagsCsv) { mutableStateOf(initialParsedTags.isWork) }

        fun openDatePicker(currentIso: String, onPicked: (String) -> Unit) {
            val initDate = TripDateUtils.parseStartDate(currentIso) ?: today
            android.app.DatePickerDialog(
                context,
                { _, y, m, d ->
                    onPicked(java.time.LocalDate.of(y, m + 1, d).toString())
                },
                initDate.year,
                initDate.monthValue - 1,
                initDate.dayOfMonth
            ).show()
        }

        AlertDialog(
            onDismissRequest = { showRecreateConfirmDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Recreate ${activeTrip.destination} Checklist") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Review or edit any trip detail below. Everything else stays the same, and your checked items will be preserved (manually added items not in your template may be removed).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 1. Travel Dates
                    Text(
                        text = "TRAVEL DATES (${TripDateUtils.formatDateRange(editStartDate, editDays, editEndDate)} · ${editDays}d)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            onClick = {
                                openDatePicker(editStartDate) { pickedStart ->
                                    editStartDate = pickedStart
                                    val s = TripDateUtils.parseStartDate(pickedStart) ?: today
                                    val e = TripDateUtils.parseStartDate(editEndDate) ?: s.plusDays(editDays.toLong())
                                    if (!e.isAfter(s)) {
                                        editEndDate = s.plusDays(maxOf(1, activeTrip.durationDays).toLong()).toString()
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(
                                    text = "Start Date",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = editStartDate,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Surface(
                            onClick = {
                                openDatePicker(editEndDate) { pickedEnd ->
                                    val s = TripDateUtils.parseStartDate(editStartDate) ?: today
                                    val e = TripDateUtils.parseStartDate(pickedEnd) ?: s.plusDays(1)
                                    editEndDate = if (e.isAfter(s)) pickedEnd else s.plusDays(1).toString()
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(
                                    text = "End Date (${editDays}d)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = editEndDate,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // 2. Transport & Rental Car
                    Text(
                        text = "TRANSPORT & RENTAL CAR",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val carOptions = listOf(
                            CarTransportMode.RENTAL_CAR to "Flight + Rental Car",
                            CarTransportMode.NO_CAR to "Flight (No Rental Car)",
                            CarTransportMode.OWN_CAR to "Personal Car (Driving Own Vehicle)"
                        )
                        carOptions.forEach { (mode, label) ->
                            val selected = editCarMode == mode
                            Surface(
                                onClick = {
                                    editCarMode = mode
                                    if (mode == CarTransportMode.OWN_CAR) {
                                        editLuggageSpace = LuggageSpace.TWO_CHECKED_BAGS
                                    }
                                },
                                shape = RoundedCornerShape(9.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                else MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (selected) {
                                        Text(
                                            text = "✓",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Bags / Luggage (shown when flying)
                    if (editCarMode != CarTransportMode.OWN_CAR) {
                        Text(
                            text = "LUGGAGE / BAGS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        val bagOptions = listOf(
                            LuggageSpace.CARRY_ON to "Carry-On + Shoulder",
                            LuggageSpace.CHECKED_BAG to "1 Check-In + Carry-On",
                            LuggageSpace.TWO_CHECKED_BAGS to "2 Check-In + Carry-On",
                            LuggageSpace.PERSONAL_ONLY to "Shoulder Bag Only"
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            bagOptions.chunked(2).forEach { rowPair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    rowPair.forEach { (bag, shortLabel) ->
                                        val selected = editLuggageSpace == bag
                                        Surface(
                                            onClick = { editLuggageSpace = bag },
                                            shape = RoundedCornerShape(9.dp),
                                            color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                            border = BorderStroke(
                                                1.dp,
                                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                            ),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = shortLabel,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = if (selected) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. Trip Purpose (Work vs Personal)
                    Text(
                        text = "TRIP PURPOSE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val purposeOptions = listOf(
                            true to "Work Trip",
                            false to "Personal / Leisure"
                        )
                        purposeOptions.forEach { (workVal, label) ->
                            val selected = editIsWork == workVal
                            Surface(
                                onClick = { editIsWork = workVal },
                                shape = RoundedCornerShape(9.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                else MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 9.dp, horizontal = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRecreateConfirmDialog = false
                        isRefreshingWeather = true
                        scope.launch {
                            try {
                                val summary = voiceAssistant.recreateTripChecklist(
                                    tripId = tripId,
                                    newStartDate = editStartDate,
                                    newEndDate = editEndDate,
                                    newDays = editDays,
                                    newIsWork = editIsWork,
                                    newCarMode = editCarMode,
                                    newLuggageSpace = editLuggageSpace
                                )
                                Toast.makeText(context, summary, Toast.LENGTH_SHORT).show()
                            } finally {
                                isRefreshingWeather = false
                            }
                        }
                    }
                ) {
                    Text("Recreate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecreateConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAddItemDialog) {
        AddItemDialog(
            tripDestination = activeTrip.destination,
            extraCategories = items.map { it.category },
            onDismiss = { showAddItemDialog = false },
            onAddItem = { name, category ->
                scope.launch {
                    dao.insertSingleItem(
                        PackingItemEntity(
                            tripId = activeTrip.id,
                            name = name,
                            category = category,
                            quantity = 1,
                            isDoorwayCritical = currentMode == TripMode.DOORWAY ||
                                category.equals(PackingCategory.ON_ME.displayName, ignoreCase = true) ||
                                category.equals(PackingCategory.DOCS.displayName, ignoreCase = true),
                            isHighLossReturn = true,
                            weatherReason = "Added manually"
                        )
                    )
                    FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                }
            }
        )
    }

    itemSavingToTemplate?.let { item ->
        TemplateRuleDialog(
            title = "Save to Default Template",
            initialName = item.name,
            initialModuleKey = "Always",
            initialCategory = item.category,
            initialBagLocation = BagLocation.fromLabel(item.bagLocation),
            initialScaleByDays = item.quantity > 1,
            initialDoorwayCritical = item.isDoorwayCritical,
            extraCategories = items.map { it.category },
            onDismiss = { itemSavingToTemplate = null },
            onSaveRule = { name, moduleKey, category, bag, scaleByDays, ratioPerDay, isDoorwayCritical ->
                scope.launch {
                    dao.insertTemplateRule(
                        TemplateRuleEntity(
                            userId = currentUserId,
                            moduleKey = moduleKey,
                            name = name,
                            category = category,
                            bagLocation = bag.label,
                            scaleByDays = scaleByDays,
                            ratioPerDay = ratioPerDay,
                            isDoorwayCritical = isDoorwayCritical,
                            isHighLossReturn = true
                        )
                    )
                    FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                    Toast.makeText(
                        context,
                        "Saved '$name' to Default Template",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
}
