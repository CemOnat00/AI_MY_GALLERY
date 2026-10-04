package com.ktu.aigaleri.ui.index

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ktu.aigaleri.R
import java.text.DateFormat
import java.util.Date

/** Durumsuz indeks durumu ekranı: işlenen/toplam, ilerleme çubuğu, son çalışma ve yeniden indeksleme butonu. */
@Composable
fun IndexStatusContent(state: IndexStatusUiState, onReindex: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            IndexStatusUiState.Loading -> CircularProgressIndicator()
            IndexStatusUiState.NeverRun -> Text(stringResource(R.string.index_never_run))
            IndexStatusUiState.Error -> Text(stringResource(R.string.index_status_error))
            is IndexStatusUiState.Data -> {
                Text(
                    stringResource(R.string.index_processed_of_total, state.processed, state.total),
                    style = MaterialTheme.typography.titleMedium,
                )
                LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    state.lastRunAt?.let {
                        stringResource(R.string.index_last_run, DateFormat.getDateTimeInstance().format(Date(it)))
                    } ?: stringResource(R.string.index_last_run_none),
                )
            }
        }
        Button(onClick = onReindex) { Text(stringResource(R.string.reindex_button)) }
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.back_button)) }
    }
}
