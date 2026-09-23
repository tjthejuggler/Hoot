package com.example.hoot.ui.intake

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.example.hoot.domain.nutrition.NutritionProcessState
import com.example.hoot.ui.common.EmptyState
import com.example.hoot.ui.common.SectionHeader
import java.io.File
import java.util.UUID

/**
 * Intake tab (second tab, phase 7): Tail-style meal capture — Text / Photo /
 * Voice composer → LLM → Tail-compatible structure → Hoot's engine. Below it,
 * "Today's intake" lists today's meals (with macros), supplements and
 * water/misc entries read-only; water quick-add buttons round it off.
 */
@Composable
fun IntakeScreen() {
    val context = LocalContext.current
    val vm: IntakeViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val processing by vm.processing.collectAsStateWithLifecycle()

    var mode by remember { mutableStateOf(IntakeMode.TEXT) }
    var text by remember { mutableStateOf("") }
    var timeText by remember { mutableStateOf("") }
    var supplementMode by remember { mutableStateOf(false) }
    var photoFile by remember { mutableStateOf<File?>(null) }
    var listening by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    var detailRow by remember { mutableStateOf<IntakeRow?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // ── Voice capture: ACTION_RECOGNIZE_SPEECH intent (no runtime permission) ──
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        listening = false
        val heard = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull { !it.isNullOrBlank() }
        if (heard != null) text = text.ifBlank { "" } + if (text.isBlank()) heard else " $heard"
        else voiceError = "No speech heard"
    }

    // ── Photo capture: full-res TakePicture into an app-private file via the
    // FileProvider — the camera app holds the CAMERA permission, Hoot needs none.
    val pendingCameraFile = remember { mutableStateOf<File?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val tmp = pendingCameraFile.value
        if (ok && tmp != null) photoFile = tmp   // camera wrote our app-private file directly
        else runCatching { tmp?.delete() }
        pendingCameraFile.value = null
    }

    // ── Gallery pick: PickVisualMedia → copied into app-private storage ──
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) photoFile = copyUriToFile(context, uri)
    }

    LaunchedEffect(ui.lastOutcomeText) {
        ui.lastOutcomeText?.let {
            snackbar.showSnackbar(it, withDismissAction = true)
            vm.clearOutcome()
        }
    }

    androidx.compose.material3.Scaffold(
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Intake", style = MaterialTheme.typography.headlineSmall)
            }

            // ---- Composer card -----------------------------------------
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (supplementMode) "Supplement quick-add" else "What did you eat?",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text("Supplements", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(6.dp))
                            Switch(checked = supplementMode, onCheckedChange = {
                                supplementMode = it
                                if (it) photoFile = null
                            })
                        }

                        if (!supplementMode) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = mode == IntakeMode.TEXT,
                                    onClick = { mode = IntakeMode.TEXT },
                                    label = { Text("Text") },
                                    leadingIcon = { Icon(Icons.Filled.TextFields, null, Modifier.size(18.dp)) }
                                )
                                FilterChip(
                                    selected = mode == IntakeMode.PHOTO,
                                    onClick = { mode = IntakeMode.PHOTO },
                                    label = { Text("Photo") },
                                    leadingIcon = { Icon(Icons.Filled.PhotoCamera, null, Modifier.size(18.dp)) }
                                )
                                FilterChip(
                                    selected = mode == IntakeMode.VOICE,
                                    onClick = { mode = IntakeMode.VOICE },
                                    label = { Text("Voice") },
                                    leadingIcon = { Icon(Icons.Filled.Mic, null, Modifier.size(18.dp)) }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            placeholder = {
                                Text(
                                    when {
                                        supplementMode -> "Magnesium 400 mg, vitamin D3 2000 IU"
                                        mode == IntakeMode.VOICE -> "Tap the mic and say what you ate…"
                                        else -> "150 g lentils, spinach, 1 tbsp olive oil"
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = if (supplementMode) 2 else 3,
                            supportingText = {
                                if (voiceError != null) Text(voiceError!!) else if (!supplementMode && mode == IntakeMode.VOICE && listening) {
                                    Text("Listening…")
                                }
                            }
                        )

                        if (mode == IntakeMode.VOICE && !supplementMode) {
                            Button(
                                onClick = {
                                    voiceError = null
                                    listening = true
                                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(
                                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                        )
                                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Describe what you ate…")
                                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                                    }
                                    runCatching { voiceLauncher.launch(intent) }
                                        .onFailure {
                                            listening = false
                                            voiceError = "Speech recognition unavailable on this device"
                                        }
                                },
                                enabled = !ui.saving,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Mic, null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (listening) "Listening… tap result when done" else "Record voice description")
                            }
                        }

                        if (mode == IntakeMode.PHOTO && !supplementMode) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    val dir = File(context.filesDir, "intake_images").apply { mkdirs() }
                                    val tmp = File(dir, "shot_${UUID.randomUUID()}.jpg")
                                    val uri = FileProvider.getUriForFile(
                                        context, "${context.packageName}.fileprovider", tmp
                                    )
                                    pendingCameraFile.value = tmp
                                    cameraLauncher.launch(uri)
                                }) {
                                    Icon(Icons.Filled.PhotoCamera, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Take photo")
                                }
                                OutlinedButton(onClick = {
                                    galleryLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }) {
                                    Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Gallery")
                                }
                            }
                            photoFile?.let { f ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(
                                        model = f,
                                        contentDescription = "Attached meal photo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "Photo attached — sent to vision analysis on save.",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { photoFile = null }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Remove photo")
                                    }
                                }
                            }
                        }

                        if (!supplementMode) {
                            OutlinedTextField(
                                value = timeText,
                                onValueChange = { timeText = it },
                                label = { Text("Time (optional, HH:mm)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                        Button(
                            enabled = !ui.saving && text.isNotBlank(),
                            onClick = {
                                if (supplementMode) vm.saveSupplements(text, timeText)
                                else vm.saveMeal(text, photoFile, timeText)
                                text = ""
                                photoFile = null
                                timeText = ""
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                when {
                                    ui.saving -> "Analyzing…"
                                    supplementMode -> "Save supplements"
                                    else -> "Save with AI analysis"
                                }
                            )
                        }

                        when (val p = processing) {
                            is NutritionProcessState.Processing -> {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(
                                    "Analyzing nutrition… ${p.pending} foods left",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            else -> Unit
                        }
                    }
                }
            }

            // ---- Water quick-add ----------------------------------------
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("💧 Water", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { vm.quickAddWater(250) }, enabled = !ui.saving) {
                            Text("+250 ml")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { vm.quickAddWater(500) }, enabled = !ui.saving) {
                            Text("+500 ml")
                        }
                    }
                }
            }

            // ---- Today's intake -----------------------------------------
            item {
                SectionHeader(
                    "Today's intake",
                    "Meals, supplements and water logged for today."
                )
            }
            if (rows.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        EmptyState(
                            emoji = "🍽️",
                            title = "Nothing logged yet",
                            body = "Type it, shoot it, or say it above — Hoot analyzes it " +
                                "into macros and ingredients just like Tail does.",
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(rows, key = { it.key }) { row ->
                    IntakeRowCard(row, onClick = { if (row.kind == "meal") detailRow = row })
                }
            }
        }
    }

    detailRow?.let { row ->
        MealDetailDialog(row = row, onDismiss = { detailRow = null })
    }
}
