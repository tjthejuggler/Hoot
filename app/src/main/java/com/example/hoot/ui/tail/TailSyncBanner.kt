package com.example.hoot.ui.tail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.hoot.data.local.entity.TailAppConfigEntity
import com.example.hoot.data.tail.TailSyncState

/**
 * Minimal Tail-sync status banner for the Today placeholder screen.
 *  - Unconfigured → a tap-through prompt to the setup route.
 *  - Syncing / error → inline progress or message.
 *  - Configured + meal logs unavailable → pointer to docs/TAIL_REQUEST.md.
 */
@Composable
fun TailSyncBanner(
    config: TailAppConfigEntity?,
    syncState: TailSyncState,
    onOpenSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (text, syncing, isError) = when {
        config == null || (!config.integrationEnabled &&
            config.mealHabitName == null && config.pillsHabitName == null &&
            config.waterHabitName == null && config.miscHabitNames.isEmpty()) ->
            Triple("Connect Tail to import your meals & supplements", false, false)
        syncState is TailSyncState.Syncing ->
            Triple("Syncing from Tail…", true, false)
        syncState is TailSyncState.Error ->
            Triple("Tail sync failed: ${syncState.message}", false, true)
        syncState is TailSyncState.Success && syncState.mealLogsUnavailable ->
            Triple("Meal logs not yet exposed by Tail — using text entries (docs/TAIL_REQUEST.md)", false, false)
        else -> return
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = config == null || !config.integrationEnabled, onClick = onOpenSetup),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (syncing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    if (isError) Icons.Filled.CloudOff else Icons.Filled.CloudSync,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
