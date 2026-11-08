package com.packtrack.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.packtrack.app.data.FirestoreSyncService
import com.packtrack.app.data.PackTrackDao
import com.packtrack.app.data.TemplateRuleEntity
import com.packtrack.app.domain.BagLocation
import com.packtrack.app.domain.PackingCategory
import com.packtrack.app.domain.RuleCondition
import kotlinx.coroutines.launch

/**
 * SCREEN 4: CUSTOMIZABLE CONDITIONAL TEMPLATE SCREEN
 *  - Clean TopAppBar with back IconButton and account subtitle
 *  - Segmented pill switcher to group rules "By Condition" or "By Category"
 *  - Tap any rule row to edit its Name, Condition, Category, Bag Location, or Day-Scaling
 *  - Bottom-right FloatingActionButton (+) to add a new conditional rule
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateEditorScreen(
    currentUserId: String,
    dao: PackTrackDao,
    onBackToHome: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val userRules by dao.observeTemplateRulesForUser(currentUserId).collectAsState(initial = emptyList())
    var groupByCondition by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    var ruleBeingEdited by remember { mutableStateOf<TemplateRuleEntity?>(null) }
    var rulePendingDelete by remember { mutableStateOf<TemplateRuleEntity?>(null) }
    val displayUser = FirestoreSyncService.displayUserLabel(currentUserId)

    LaunchedEffect(currentUserId) {
        FirestoreSyncService.ensureUserTemplateSeeded(dao, currentUserId)
    }

    val categoryOrder = PackingCategory.orderedDisplayNames
    val groupedByCat = remember(userRules) { userRules.groupBy { it.category } }
    val orderedCategories = remember(groupedByCat) {
        categoryOrder.filter { groupedByCat.containsKey(it) } +
            groupedByCat.keys.filter { it !in categoryOrder }
    }

    val groupedByCond = remember(userRules) {
        userRules.groupBy { RuleCondition.formatSummary(it.moduleKey) }
    }
    val orderedConditions = remember(groupedByCond) {
        val preferred = RuleCondition.entries.map { it.label }
        preferred.filter { groupedByCond.containsKey(it) } +
            groupedByCond.keys.filter { it !in preferred }
    }

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
                    Column {
                        Text(
                            text = "Default Template",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "$displayUser  •  ${userRules.size} items (tap any to edit)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
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
            // Group Mode Switcher: By Condition | By Category
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val modes = listOf(
                        true to "By Condition",
                        false to "By Category"
                    )
                    modes.forEach { (isCond, label) ->
                        val selected = groupByCondition == isCond
                        Surface(
                            onClick = { groupByCondition = isCond },
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

            if (userRules.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No template rules yet. Tap '+' below to add one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (groupByCondition) {
                        orderedConditions.forEach { conditionLabel ->
                            val condRules = groupedByCond[conditionLabel] ?: emptyList()

                            item(key = "cond_$conditionLabel") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 14.dp, bottom = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = conditionLabel.uppercase(),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "${condRules.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            items(condRules, key = { "rule_${it.id}" }) { rule ->
                                TemplateRuleRow(
                                    rule = rule,
                                    showCategoryInSubtitle = true,
                                    onEdit = { ruleBeingEdited = rule },
                                    onDelete = { rulePendingDelete = rule }
                                )
                            }
                        }
                    } else {
                        orderedCategories.forEach { categoryName ->
                            val catRules = groupedByCat[categoryName] ?: emptyList()

                            item(key = "cat_$categoryName") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 14.dp, bottom = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = categoryName.uppercase(),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${catRules.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            items(catRules, key = { "rule_${it.id}" }) { rule ->
                                TemplateRuleRow(
                                    rule = rule,
                                    showCategoryInSubtitle = false,
                                    onEdit = { ruleBeingEdited = rule },
                                    onDelete = { rulePendingDelete = rule }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        TemplateRuleDialog(
            title = "Add Item",
            extraCategories = userRules.map { it.category },
            onDismiss = { showAddDialog = false },
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
                }
            }
        )
    }

    ruleBeingEdited?.let { existing ->
        TemplateRuleDialog(
            title = "Edit Item",
            initialName = existing.name,
            initialModuleKey = existing.moduleKey,
            initialCategory = existing.category,
            initialBagLocation = BagLocation.fromLabel(existing.bagLocation),
            initialScaleByDays = existing.scaleByDays,
            initialRatioPerDay = existing.ratioPerDay,
            initialDoorwayCritical = existing.isDoorwayCritical,
            extraCategories = userRules.map { it.category },
            onDismiss = { ruleBeingEdited = null },
            onSaveRule = { name, moduleKey, category, bag, scaleByDays, ratioPerDay, isDoorwayCritical ->
                scope.launch {
                    dao.updateTemplateRule(
                        ruleId = existing.id,
                        name = name,
                        category = category,
                        moduleKey = moduleKey,
                        bagLocation = bag.label,
                        scaleByDays = scaleByDays,
                        ratioPerDay = ratioPerDay,
                        isDoorwayCritical = isDoorwayCritical
                    )
                    FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                }
            }
        )
    }

    rulePendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { rulePendingDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Delete ${rule.name}?",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to remove '${rule.name}' from your Default Template?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val idToRemove = rule.id
                        rulePendingDelete = null
                        scope.launch {
                            dao.deleteTemplateRule(idToRemove)
                            FirestoreSyncService.pushLocalStateToFirestore(dao, currentUserId)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { rulePendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun TemplateRuleRow(
    rule: TemplateRuleEntity,
    showCategoryInSubtitle: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val condSummary = RuleCondition.formatSummary(rule.moduleKey)
    val bagLabel = BagLocation.fromLabel(rule.bagLocation).label
    val rateStr = if (rule.ratioPerDay % 1.0 == 0.0) {
        "${rule.ratioPerDay.toInt()}/day"
    } else {
        "${rule.ratioPerDay}/day"
    }
    val subtitle = buildString {
        if (showCategoryInSubtitle) {
            append(rule.category)
            append("  •  ")
        } else {
            append(condSummary)
            append("  •  ")
        }
        append(bagLabel)
        if (rule.scaleByDays) append("  •  $rateStr")
    }

    Surface(
        onClick = onEdit,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Remove rule",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
