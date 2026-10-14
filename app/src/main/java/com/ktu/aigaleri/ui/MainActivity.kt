package com.ktu.aigaleri.ui

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ktu.aigaleri.ui.gallery.GalleryViewModel
import com.ktu.aigaleri.ui.index.IndexStatusViewModel
import com.ktu.aigaleri.ui.search.SearchViewModel

class MainActivity : ComponentActivity() {
    private val permission get() = GalleryPermission.requiredPermission()

    private val deps by lazy { AppDependencies.get(applicationContext) }

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory { initializer { MainViewModel(deps.photoSource, deps.indexLauncher) } }
    }

    private val searchViewModel: SearchViewModel by viewModels {
        viewModelFactory { initializer { SearchViewModel(deps.searchRepository, deps::warmUpSearch) } }
    }

    private val galleryViewModel: GalleryViewModel by viewModels {
        viewModelFactory { initializer { GalleryViewModel(deps.photoSource) } }
    }

    private val indexStatusViewModel: IndexStatusViewModel by viewModels {
        viewModelFactory { initializer { IndexStatusViewModel(deps.indexState, deps.indexLauncher) } }
    }

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onPermissionResult(granted, shouldShowRequestPermissionRationale(permission))
            galleryViewModel.refresh(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MainScreen(
                viewModel = viewModel,
                searchViewModel = searchViewModel,
                indexStatusViewModel = indexStatusViewModel,
                galleryViewModel = galleryViewModel,
                onRequestPermission = { requestPermission.launch(permission) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Ayarlardan dönüşte de gerçek durumla uzlaştırır.
        val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        viewModel.onResume(granted)
        // Galeri listesi her ön plana gelişte yenilenir (kameradan dönünce yeni fotoğraflar görünür).
        galleryViewModel.refresh(granted)
    }
}
