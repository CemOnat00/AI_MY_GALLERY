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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ktu.aigaleri.R
import com.ktu.aigaleri.ui.index.IndexStatusContent
import com.ktu.aigaleri.ui.index.indexingBanner
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
 * Geri yığınında önceki ekran varsa bir adım geri gider; kökteyse (veya çift dokunmada yığın zaten
 * boşaldıysa) hiçbir şey yapmaz, böylece boş ekran/çökme olmaz.
 */
fun NavController.safeBack() {
    if (previousBackStackEntry != null) popBackStack()
}

/** Tekrarlı dokunmada aynı ekranın üst üste eklenmesini önler. */
private fun NavController.open(route: String) = navigate(route) { launchSingleTop = true }

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
    onRequestPermission: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            AppNavHost(
                navController = rememberNavController(),
                state = state,
                searchViewModel = searchViewModel,
                indexStatusViewModel = indexStatusViewModel,
                onRequestPermission = onRequestPermission,
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
                modifier = Modifier.safeDrawingPadding(),
            )
        }
    }
}

/** Ekran grafı; [navController] dışarıdan verilir (androidTest'te geri davranışı sınanır). */
@Composable
fun AppNavHost(
    navController: NavHostController,
    state: MainUiState,
    searchViewModel: SearchViewModel,
    indexStatusViewModel: IndexStatusViewModel,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val granted = state.permission == PermissionState.Granted
    NavHost(navController = navController, startDestination = Routes.SEARCH, modifier = modifier) {
        composable(Routes.SEARCH) {
            if (granted) {
                val searchState by searchViewModel.state.collectAsStateWithLifecycle()
                val indexUiState by indexStatusViewModel.state.collectAsStateWithLifecycle()
                val indexRunning by indexStatusViewModel.reindexInProgress.collectAsStateWithLifecycle()
                SearchContent(
                    state = searchState,
                    indexing = indexingBanner(indexUiState, indexRunning),
                    onQueryChange = searchViewModel::onQueryChange,
                    onSearch = searchViewModel::search,
                    onOpenPhoto = { navController.open(Routes.viewer(it)) },
                    onOpenIndexStatus = { navController.open(Routes.INDEX_STATUS) },
                )
            } else {
                MainContent(state, onRequestPermission, onOpenSettings)
            }
        }
        composable(
            Routes.VIEWER,
            arguments = listOf(navArgument(Routes.PHOTO_ARG) { type = NavType.LongType }),
        ) { entry ->
            PhotoViewerScreen(
                photoId = entry.arguments?.getLong(Routes.PHOTO_ARG),
                onBack = { navController.safeBack() },
            )
        }
        composable(Routes.INDEX_STATUS) {
            if (granted) {
                val indexState by indexStatusViewModel.state.collectAsStateWithLifecycle()
                val inProgress by indexStatusViewModel.reindexInProgress.collectAsStateWithLifecycle()
                val reindexError by indexStatusViewModel.reindexError.collectAsStateWithLifecycle()
                IndexStatusContent(
                    state = indexState,
                    reindexError = reindexError,
                    reindexAvailable = indexStatusViewModel.reindexAvailable,
                    reindexInProgress = inProgress,
                    onReindex = indexStatusViewModel::reindex,
                    onRetry = indexStatusViewModel::retry,
                    onBack = { navController.safeBack() },
                )
            } else {
                MainContent(state, onRequestPermission, onOpenSettings)
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
