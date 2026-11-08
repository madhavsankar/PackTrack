package com.packtrack.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.packtrack.app.domain.BagLocation
import com.packtrack.app.domain.PackingCategory
import com.packtrack.app.domain.RuleCondition
import com.packtrack.app.ui.theme.PackTrackThemeState

/**
 * Minimalist reusable modal dialog for adding an item to a Trip (with "+ Add Category" support).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddItemDialog(
    tripDestination: String,
    extraCategories: Iterable<String> = emptyList(),
    onDismiss: () -> Unit,
    onAddItem: (name: String, category: String) -> Unit
) {
    val context = LocalContext.current
    var itemName by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(PackingCategory.ON_ME.displayName) }
    var showNewCategoryInput by remember { mutableStateOf(false) }
    var newCategoryText by remember { mutableStateOf("") }
    val categories = PackTrackThemeState.allCategories(extraCategories)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "Add to $tripDestination",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = itemName,
                    onValueChange = { itemName = it },
                    label = { Text("Item Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Category",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    categories.forEach { catName ->
                        FilterChip(
                            selected = selectedCategory.equals(catName, ignoreCase = true),
                            onClick = { selectedCategory = catName },
                            label = {
                                Text(
                                    text = catName,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                    FilterChip(
                        selected = showNewCategoryInput,
                        onClick = { showNewCategoryInput = !showNewCategoryInput },
                        label = {
                            Text(
                                text = "+ Add Category",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
                if (showNewCategoryInput) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = newCategoryText,
                            onValueChange = { newCategoryText = it },
                            label = { Text("New Category") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = {
                                val added = PackTrackThemeState.addCustomCategory(context, newCategoryText)
                                selectedCategory = added
                                newCategoryText = ""
                                showNewCategoryInput = false
                            },
                            enabled = newCategoryText.isNotBlank()
                        ) {
                            Text("Add")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = itemName.trim()
                    if (clean.isNotEmpty()) {
                        val finalCat = if (showNewCategoryInput && newCategoryText.isNotBlank()) {
                            PackTrackThemeState.addCustomCategory(context, newCategoryText)
                        } else {
                            selectedCategory
                        }
                        onAddItem(clean, finalCat)
                    }
                    onDismiss()
                },
                enabled = itemName.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Clean, generic Add / Edit Template Item Dialog:
 *  - Item Name
 *  - Category: On Me, Docs, Electronics, Clothes, Toiletry, Others, or "+ Add Category"
 *  - Bag Type: Personal, Carry-On, Check-In
 *  - Condition (free text): e.g. "Always", "Only when taking rental car", "Only when working"
 *  - Scale per day: Fixed (1 total), 0.5 / day, 1 / day, 2 / day, 3 / day, or custom
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplateRuleDialog(
    title: String = "Add Item",
    initialName: String = "",
    initialModuleKey: String = "Always",
    initialCategory: String = PackingCategory.ON_ME.displayName,
    initialBagLocation: BagLocation = BagLocation.PERSONAL_ITEM,
    initialScaleByDays: Boolean = false,
    initialRatioPerDay: Double = 1.0,
    initialDoorwayCritical: Boolean = false,
    extraCategories: Iterable<String> = emptyList(),
    onDismiss: () -> Unit,
    onSaveRule: (
        name: String,
        moduleKey: String,
        category: String,
        bagLocation: BagLocation,
        scaleByDays: Boolean,
        ratioPerDay: Double,
        isDoorwayCritical: Boolean
    ) -> Unit
) {
    val context = LocalContext.current
    var itemName by remember(initialName) { mutableStateOf(initialName) }
    var selectedCategory by remember(initialCategory) {
        mutableStateOf(PackingCategory.canonicalize(initialCategory))
    }
    var showNewCategoryInput by remember { mutableStateOf(false) }
    var newCategoryText by remember { mutableStateOf("") }
    val categories = PackTrackThemeState.allCategories(extraCategories + listOf(selectedCategory))

    var selectedBag by remember(initialBagLocation) { mutableStateOf(initialBagLocation) }
    var conditionText by remember(initialModuleKey) {
        val formatted = RuleCondition.formatSummary(initialModuleKey)
        mutableStateOf(if (formatted.equals("Always", ignoreCase = true)) "" else formatted)
    }
    var scaleByDays by remember(initialScaleByDays) { mutableStateOf(initialScaleByDays) }
    var ratioInput by remember(initialRatioPerDay) {
        val cleanStr = if (initialRatioPerDay % 1.0 == 0.0) {
            initialRatioPerDay.toInt().toString()
        } else {
            initialRatioPerDay.toString()
        }
        mutableStateOf(cleanStr)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = itemName,
                    onValueChange = { itemName = it },
                    label = { Text("Item Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Category",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    categories.forEach { catName ->
                        FilterChip(
                            selected = selectedCategory.equals(catName, ignoreCase = true),
                            onClick = {
                                selectedCategory = catName
                                if (catName.equals(PackingCategory.ON_ME.displayName, ignoreCase = true)) {
                                    selectedBag = BagLocation.PERSONAL_ITEM
                                }
                            },
                            label = {
                                Text(
                                    text = catName,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                    FilterChip(
                        selected = showNewCategoryInput,
                        onClick = { showNewCategoryInput = !showNewCategoryInput },
                        label = {
                            Text(
                                text = "+ Add Category",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
                if (showNewCategoryInput) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = newCategoryText,
                            onValueChange = { newCategoryText = it },
                            label = { Text("New Category") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = {
                                val added = PackTrackThemeState.addCustomCategory(context, newCategoryText)
                                selectedCategory = added
                                newCategoryText = ""
                                showNewCategoryInput = false
                            },
                            enabled = newCategoryText.isNotBlank()
                        ) {
                            Text("Add")
                        }
                    }
                }

                Text(
                    text = "Bag Type",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BagLocation.selectableEntries.forEach { bag ->
                        FilterChip(
                            selected = selectedBag == bag,
                            onClick = { selectedBag = bag },
                            label = {
                                Text(
                                    text = bag.label,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = conditionText,
                    onValueChange = { conditionText = it },
                    label = { Text("Condition (leave blank for Always)") },
                    placeholder = { Text("e.g. Only when taking rental car") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Scale per day",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val presets = listOf(
                    null to "Fixed (1 total)",
                    0.5 to "0.5 / day",
                    1.0 to "1 / day",
                    2.0 to "2 / day",
                    3.0 to "3 / day"
                )
                val currentRatio = ratioInput.toDoubleOrNull() ?: 1.0
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    presets.forEach { (rate, label) ->
                        val isSelected = if (rate == null) {
                            !scaleByDays
                        } else {
                            scaleByDays && kotlin.math.abs(currentRatio - rate) < 0.01
                        }
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (rate == null) {
                                    scaleByDays = false
                                } else {
                                    scaleByDays = true
                                    ratioInput = if (rate % 1.0 == 0.0) rate.toInt().toString() else rate.toString()
                                }
                            },
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
                if (scaleByDays) {
                    OutlinedTextField(
                        value = ratioInput,
                        onValueChange = { ratioInput = it },
                        label = { Text("Quantity per day (e.g. 1, 2, 3, 0.5)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = itemName.trim()
                    if (clean.isNotEmpty()) {
                        val cleanCond = conditionText.trim().ifEmpty { "Always" }
                        val parsedRatio = (ratioInput.toDoubleOrNull() ?: 1.0).coerceAtLeast(0.1)
                        val finalCat = if (showNewCategoryInput && newCategoryText.isNotBlank()) {
                            PackTrackThemeState.addCustomCategory(context, newCategoryText)
                        } else {
                            selectedCategory
                        }
                        val doorway = finalCat.equals(PackingCategory.ON_ME.displayName, ignoreCase = true) ||
                            finalCat.equals(PackingCategory.DOCS.displayName, ignoreCase = true) ||
                            initialDoorwayCritical
                        onSaveRule(
                            clean,
                            cleanCond,
                            finalCat,
                            selectedBag,
                            scaleByDays,
                            if (scaleByDays) parsedRatio else 1.0,
                            doorway
                        )
                    }
                    onDismiss()
                },
                enabled = itemName.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
