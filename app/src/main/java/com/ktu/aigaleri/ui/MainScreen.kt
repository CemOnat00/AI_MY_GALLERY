package com.ktu.aigaleri.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import com.ktu.aigaleri.ui.index.IndexStatusViewModel
import com.ktu.aigaleri.ui.gallery.GalleryViewModel
import com.ktu.aigaleri.ui.index.indexingBanner
import com.ktu.aigaleri.ui.license.LicensesScreen
import com.ktu.aigaleri.ui.search.SearchContent
import com.ktu.aigaleri.ui.search.SearchStatus
import com.ktu.aigaleri.ui.search.SearchViewModel
import com.ktu.aigaleri.ui.theme.AiGaleriDimens
import com.ktu.aigaleri.ui.theme.AiGaleriTheme
import com.ktu.aigaleri.ui.theme.AppIcons
import com.ktu.aigaleri.ui.viewer.PhotoViewerScreen

/** Ekran rotaları. Büyük görünüm rotası fotoğraf kimliğini (MediaStore id) taşır. */
object Routes {
    const val SEARCH = "search"
    const val INDEX_STATUS = "index_status"
    const val LICENSES = "licenses"
    const val PHOTO_ARG = "photoId"
    const val DATE_ARG = "dateTaken"

    /** [NO_DATE]: rota argümanı verilmediyse (çekim tarihi bilinmiyor). */
    const val NO_DATE = -1L
    const val VIEWER = "viewer/{$PHOTO_ARG}?$DATE_ARG={$DATE_ARG}"

    /** Çekim tarihi (epoch ms) biliniyorsa isteğe bağlı sorgu argümanı olarak eklenir. */
    fun viewer(photoId: Long, dateTaken: Long? = null) =
        if (dateTaken != null && dateTaken >= 0) "viewer/$photoId?$DATE_ARG=$dateTaken" else "viewer/$photoId"

    /** Rota argümanını (varsayılan [NO_DATE]) nullable tarihe çevirir. */
    fun dateTakenOrNull(raw: Long?): Long? = raw?.takeIf { it >= 0 }
}

/** Arama durumundaki sonuçlardan [photoId]'ye ait çekim tarihi; bulunamazsa/bilinmiyorsa null. */
internal fun dateTakenOf(status: SearchStatus, photoId: Long): Long? =
    (status as? SearchStatus.Success)?.results?.firstOrNull { it.photoId == photoId }?.dateTaken

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
 * Navigasyon kökü: arama + galeri grid'i (başlangıç) -> büyük görünüm / indeks durumu. Geri tuşu NavHost'un geri
 * yığınıyla çalışır; arama ekranında geri uygulamadan çıkar. İzin yokken arama ve indeks ekranları
 * izin açıklamasını ([MainContent]) gösterir, arama/indeks çağrısı yapılmaz.
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    searchViewModel: SearchViewModel,
    indexStatusViewModel: IndexStatusViewModel,
    galleryViewModel: GalleryViewModel,
    onRequestPermission: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    AiGaleriTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AppNavHost(
                navController = rememberNavController(),
                state = state,
                searchViewModel = searchViewModel,
                indexStatusViewModel = indexStatusViewModel,
                galleryViewModel = galleryViewModel,
                onRequestPermission = onRequestPermission,
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
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
    galleryViewModel: GalleryViewModel,
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
                val indexRunState by indexStatusViewModel.runState.collectAsStateWithLifecycle()
                val galleryState by galleryViewModel.state.collectAsStateWithLifecycle()
                SearchContent(
                    state = searchState,
                    indexing = indexingBanner(indexUiState, indexRunState),
                    onQueryChange = searchViewModel::onQueryChange,
                    onSearch = searchViewModel::search,
                    onQueryFocused = searchViewModel::warmUp,
                    onOpenPhoto = { navController.open(Routes.viewer(it, dateTakenOf(searchState.status, it))) },
                    onOpenIndexStatus = { navController.open(Routes.INDEX_STATUS) },
                    gallery = galleryState,
                    onOpenGalleryPhoto = { id, date -> navController.open(Routes.viewer(id, date)) },
                    onGalleryRetry = galleryViewModel::retry,
                )
            } else {
                MainContent(state, onRequestPermission, onOpenSettings)
            }
        }
        composable(
            Routes.VIEWER,
            arguments = listOf(
                navArgument(Routes.PHOTO_ARG) { type = NavType.LongType },
                navArgument(Routes.DATE_ARG) {
                    type = NavType.LongType
                    defaultValue = Routes.NO_DATE
                },
            ),
        ) { entry ->
            PhotoViewerScreen(
                photoId = entry.arguments?.getLong(Routes.PHOTO_ARG),
                onBack = { navController.safeBack() },
                dateTaken = Routes.dateTakenOrNull(entry.arguments?.getLong(Routes.DATE_ARG)),
            )
        }
        composable(Routes.INDEX_STATUS) {
            if (granted) {
                val indexState by indexStatusViewModel.state.collectAsStateWithLifecycle()
                val inProgress by indexStatusViewModel.reindexInProgress.collectAsStateWithLifecycle()
                val reindexError by indexStatusViewModel.reindexError.collectAsStateWithLifecycle()
                val runState by indexStatusViewModel.runState.collectAsStateWithLifecycle()
                IndexStatusContent(
                    state = indexState,
                    reindexError = reindexError,
                    runState = runState,
                    reindexAvailable = indexStatusViewModel.reindexAvailable,
                    reindexInProgress = inProgress,
                    onReindex = indexStatusViewModel::reindex,
                    onRetry = indexStatusViewModel::retry,
                    onBack = { navController.safeBack() },
                    onOpenLicenses = { navController.open(Routes.LICENSES) },
                )
            } else {
                MainContent(state, onRequestPermission, onOpenSettings)
            }
        }
        // T-037 (K-12): izin gerektirmez, tamamen yerel düz metin.
        composable(Routes.LICENSES) { LicensesScreen(onBack = { navController.safeBack() }) }
    }
}


const val TAG_PERMISSION_GRANT = "permission_grant"
const val TAG_PERMISSION_SETTINGS = "permission_settings"
const val TAG_PERMISSION_HOWTO = "permission_howto"

/**
 * İzin ekranı (T-022 tasarımı): ilk açılış / reddedildi (açıklama + "İzin ver") ve kalıcı red ("Ayarları aç" +
 * "Nasıl etkinleştirilir?"). İzin verilmişse (arama/indeks ekranları yerine geçmediği için normalde görünmez)
 * fotoğraf sayısını gösterir. İkincil "ertele" düğmeleri bilerek yoktur (izin akışında karşılığı yok, Cem kararı olmadan eklenmez).
 */
@Composable
fun MainContent(state: MainUiState, onRequestPermission: () -> Unit, onOpenSettings: () -> Unit) {
    when (state.permission) {
        PermissionState.Granted -> GrantedContent(state)
        PermissionState.NotRequested, PermissionState.Denied -> PermissionLayout(
            icon = AppIcons.PhotoLibrary,
            iconContainer = MaterialTheme.colorScheme.secondaryContainer,
            iconTint = MaterialTheme.colorScheme.primary,
            title = stringResource(
                if (state.permission == PermissionState.Denied) R.string.permission_title_denied
                else R.string.permission_title_needed,
            ),
            body = stringResource(
                if (state.permission == PermissionState.Denied) R.string.permission_denied_message
                else R.string.permission_needed_message,
            ),
            extra = { OfflineBadge() },
            actions = {
                Button(
                    onClick = onRequestPermission,
                    shape = RoundedCornerShape(AiGaleriDimens.ButtonCorner),
                    modifier = Modifier.fillMaxWidth().heightIn(min = AiGaleriDimens.MinTouchTarget).testTag(TAG_PERMISSION_GRANT),
                ) {
                    Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(
                        stringResource(R.string.permission_grant_button),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = AiGaleriDimens.SpaceSm),
                    )
                }
            },
        )
        PermissionState.PermanentlyDenied -> PermissionLayout(
            icon = AppIcons.Lock,
            iconContainer = MaterialTheme.colorScheme.surfaceContainerHighest,
            iconTint = MaterialTheme.colorScheme.secondary,
            title = stringResource(R.string.permission_title_permanently_denied),
            body = stringResource(R.string.permission_permanently_denied_message),
            extra = { HowToPanel() },
            actions = {
                Button(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(AiGaleriDimens.ButtonCorner),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = AiGaleriDimens.MinTouchTarget).testTag(TAG_PERMISSION_SETTINGS),
                ) { Text(stringResource(R.string.permission_open_settings_button), style = MaterialTheme.typography.labelLarge) }
            },
        )
    }
}

@Composable
private fun GrantedContent(state: MainUiState) {
    val count = state.photoCount
    Box(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(AiGaleriDimens.SpaceLg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when {
                state.loadError -> stringResource(R.string.photo_count_error)
                count == null -> stringResource(R.string.permission_granted_loading)
                else -> pluralStringResource(R.plurals.photo_count, count, formatCount(count))
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Ortak yerleşim: ortalanmış, kaydırılabilir içerik (büyük yazıda taşmaz) ve altta sabit eylem düğmeleri.
 */
@Composable
private fun PermissionLayout(
    icon: ImageVector,
    iconContainer: Color,
    iconTint: Color,
    title: String,
    body: String,
    extra: @Composable () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
                    .padding(horizontal = AiGaleriDimens.Margin, vertical = AiGaleriDimens.SpaceLg)
                    .widthIn(max = AiGaleriDimens.ContentMaxWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceMd),
            ) {
                Box(
                    modifier = Modifier.size(AiGaleriDimens.IconCircleLarge).background(iconContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(AiGaleriDimens.IconCircleLargeGlyph))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                extra()
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AiGaleriDimens.Margin).padding(bottom = AiGaleriDimens.SpaceLg)
                .widthIn(max = AiGaleriDimens.ContentMaxWidth).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceXs),
        ) { actions() }
    }
}

/** "Tamamen çevrimdışı ve yerel model" rozeti. */
@Composable
private fun OfflineBadge() {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape) {
        Row(
            modifier = Modifier.padding(horizontal = AiGaleriDimens.SpaceMd, vertical = AiGaleriDimens.SpaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceSm),
        ) {
            Box(Modifier.size(AiGaleriDimens.StatusDot).background(MaterialTheme.colorScheme.primary, CircleShape))
            Text(
                stringResource(R.string.permission_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Kalıcı red için "Nasıl etkinleştirilir?" bilgi paneli. */
@Composable
private fun HowToPanel() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag(TAG_PERMISSION_HOWTO),
    ) {
        Row(
            modifier = Modifier.padding(AiGaleriDimens.SpaceMd),
            horizontalArrangement = Arrangement.spacedBy(AiGaleriDimens.SpaceMd),
        ) {
            Icon(AppIcons.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.permission_howto_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.permission_howto_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AiGaleriDimens.SpaceXs),
                )
            }
        }
    }
}
