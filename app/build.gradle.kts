plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.ktu.aigaleri"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ktu.aigaleri"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // ONNX modelleri zaten az sıkışır; açılışta gereksiz açma süresi olmasın, assets'ten akış kopyası hızlansın
        // (model-research.md 5.1). Model dosyaları repoda yoktur: docs/model-setup.md.
        noCompress += "onnx"
    }
    sourceSets {
        // Room şema dosyaları androidTest'te migration testi için kullanılır.
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
        // OnnxTextEncoderDeviceTest, Python referans vektörlerini (src/test/resources/ml) assets olarak okur.
        getByName("androidTest").assets.directories.add("$projectDir/src/test/resources")
    }
}

// Birleşik manifest testi (MergedManifestTest) dosyaları okur; her unit test çalıştırmasında taze üretilsin
// (bağımlılık manifestleri izin/provider sızdırabilir, ör. onnxruntime-android).
tasks.matching { it.name.endsWith("UnitTest") && it.name.startsWith("test") }.configureEach {
    dependsOn("processDebugMainManifest", "processReleaseMainManifest")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)

    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.onnxruntime.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
