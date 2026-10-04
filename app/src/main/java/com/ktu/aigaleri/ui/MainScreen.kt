package com.ktu.aigaleri.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ktu.aigaleri.R
import com.ktu.aigaleri.ui.image.ImageLoader
import com.ktu.aigaleri.ui.index.IndexStatusContent
import com.ktu.aigaleri.ui.index.IndexStatusViewModel
import com.ktu.aigaleri.ui.search.SearchContent
import com.ktu.aigaleri.ui.search.SearchViewModel
import com.ktu.aigaleri.ui.viewer.PhotoViewerScreen

/** Ekran rotaları. Büyük görünüm rotası fotoğraf kimliğini (MediaStore id) taşır. */
object Routes {
    const val SEARCH = "search"
    const val INDEX_STATUS = "index_status"
    const val PHOTO_ARG = "photoId"
    const val VIEWER = "viewer/{$PHOTO_ARG}"
    fun viewer(photoId: Long) = "viewer/$photoId"
}

/**
 * Navigasyon kökü: arama (başlangıç) -> büyük görünüm / indeks durumu. Geri tuşu NavHost'un geri
 * yığınıyla çalışır; arama ekranında geri uygulamadan çıkar. İzin yokken arama ve indeks ekranları
 * izin açıklamasını ([MainContent]) gösterir, arama/indeks çağrısı yapılmaz.
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    searchViewModel: SearchViewModel,
    indexStatusViewModel: IndexStatusViewModel,
    imageLoader: ImageLoader,
    onRequestPermission: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val openSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
    }
    val granted = state.permission == PermissionState.Granted
    val navController = rememberNavController()
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Routes.SEARCH,
                modifier = Modifier.safeDrawingPadding(),
            ) {
                composable(Routes.SEARCH) {
                    if (granted) {
                        val searchState by searchViewModel.state.collectAsState()
                        SearchContent(
                            state = searchState,
                            imageLoader = imageLoader,
                            onQueryChange = searchViewModel::onQueryChange,
                            onSearch = searchViewModel::search,
                            onOpenPhoto = { navController.navigate(Routes.viewer(it)) },
                            onOpenIndexStatus = { navController.navigate(Routes.INDEX_STATUS) },
                        )
                    } else {
                        MainContent(state, onRequestPermission, openSettings)
                    }
                }
                composable(
                    Routes.VIEWER,
                    arguments = listOf(navArgument(Routes.PHOTO_ARG) { type = NavType.LongType }),
                ) { entry ->
                    val photoId = entry.arguments?.getLong(Routes.PHOTO_ARG)
                    PhotoViewerScreen(
                        uri = photoId?.let { searchViewModel.findResult(it)?.uri },
                        imageLoader = imageLoader,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.INDEX_STATUS) {
                    if (granted) {
                        val indexState by indexStatusViewModel.state.collectAsState()
                        IndexStatusContent(
                            state = indexState,
                            onReindex = indexStatusViewModel::reindex,
                            onBack = { navController.popBackStack() },
                        )
                    } else {
                        MainContent(state, onRequestPermission, openSettings)
                    }
                }
            }
        }
    }
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
                            text = when {
                                state.loadError -> stringResource(R.string.photo_count_error)
                                count == null -> stringResource(R.string.permission_granted_loading)
                                else -> pluralStringResource(R.plurals.photo_count, count, count)
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
