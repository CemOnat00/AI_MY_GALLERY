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
import com.ktu.aigaleri.data.MediaStorePhotoSource

class MainActivity : ComponentActivity() {
    private val permission get() = GalleryPermission.requiredPermission()

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory { initializer { MainViewModel(MediaStorePhotoSource(applicationContext.contentResolver)) } }
    }

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onPermissionResult(granted, shouldShowRequestPermissionRationale(permission))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MainScreen(
                viewModel = viewModel,
                onRequestPermission = { requestPermission.launch(permission) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Ayarlardan dönüşte de gerçek durumla uzlaştırır.
        viewModel.onResume(ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED)
    }
}
