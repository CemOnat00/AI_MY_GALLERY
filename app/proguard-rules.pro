# T-024: release'te R8 küçültme + kaynak küçültme açık (isMinifyEnabled/isShrinkResources).
# Room, WorkManager, Coil ve Compose kendi consumer kurallarını taşır; ek kural gerekmedi (R8 çıktısı ve
# seeds.txt ile doğrulandı: IndexWorker, AiGaleriDatabase_Impl, WorkDatabase_Impl korunuyor).

# ONNX Runtime: yerel katman (libonnxruntime4j_jni.so) Java sınıf, alan ve yöntemlerini JNI üzerinden AD ile
# çağırır/arar; R8 bunları kullanılmıyor sanıp silerse veya yeniden adlandırırsa oturum açılışında
# UnsatisfiedLinkError/NoSuchMethodError olur. AAR'ın kendi consumer kuralı yalnızca telemetri sınıflarını korur
# (o provider zaten manifestten çıkarıldı), ORT çekirdek sınıflarını korumaz.
-keep class ai.onnxruntime.** { *; }
