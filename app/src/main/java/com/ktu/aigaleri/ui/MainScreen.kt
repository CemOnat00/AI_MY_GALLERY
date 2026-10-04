package com.ktu.aigaleri.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ktu.aigaleri.R

/** Durumu ViewModel'den alan ekran; gerçek grid ve arama sonraki görevlerde (T-009). */
@Composable
fun MainScreen(viewModel: MainViewModel, onRequestPermission: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    MainContent(
        state = state,
        onRequestPermission = onRequestPermission,
        onOpenSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        },
    )
}

@Composable
fun MainContent(state: MainUiState, onRequestPermission: () -> Unit, onOpenSettings: () -> Unit) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (state.permission) {
                    PermissionState.Granted -> {
                        val count = state.photoCount
                        Text(
                            text = if (count == null) {
                                stringResource(R.string.permission_granted_loading)
                            } else {
                                pluralStringResource(R.plurals.photo_count, count, count)
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    PermissionState.NotRequested, PermissionState.Denied -> {
                        Text(
                            text = stringResource(
                                if (state.permission == PermissionState.Denied) R.string.permission_denied_message
                                else R.string.permission_needed_message,
                            ),
                        )
                        Button(onClick = onRequestPermission) { Text(stringResource(R.string.permission_grant_button)) }
                    }
                    PermissionState.PermanentlyDenied -> {
                        Text(stringResource(R.string.permission_permanently_denied_message))
                        Button(onClick = onOpenSettings) { Text(stringResource(R.string.permission_open_settings_button)) }
                    }
                }
            }
        }
    }
}
