# Release ABI daraltma (T-029, K-13)

Karar (Cem, 2026-10-13): release APK yalnızca `arm64-v8a` yerel kütüphanelerini taşır; debug tüm ABI'lerde kalır.

Yapılandırma: `app/build.gradle.kts` içinde `buildTypes.release { ndk { abiFilters += "arm64-v8a" } }`. `defaultConfig` ve `splits` altında ABI filtresi yoktur; `debug` bloğu tanımlı değildir (varsayılan: tüm ABI). Doğrulama: `ReleaseAbiConfigTest` (yapılandırma + üretilmiş APK varsa `lib/` dizinleri).

Ölçüm (APK, imzasız): release 363,3 MB -> 261,0 MB (-102,3 MB); debug 375,3 MB (değişmedi, 4 ABI).

Release dışında kalanlar:
- 32-bit ARM (`armeabi-v7a`) cihazlar.
- x86 / x86_64 cihazlar ve emülatör görüntüleri. ARM çeviri katmanlı x86 emülatör görüntüleri arm64 APK çalıştırabilir; çalışması garanti değil, doğrulanmadı.
- 32-bit kullanıcı alanı çalıştıran arm64 SoC'li cihazlar: kurulumda `INSTALL_FAILED_NO_MATCHING_ABIS`.

Debug build tüm ABI'leri taşıdığı için Intel (x86_64) emülatörde çalışır. Ancak metin modeli `qint8_arm64` nicemlemelidir; x86 üzerinde doğruluğu doğrulanmadı. x86 emülatörde alınan arama kalitesi ölçümü release (arm64) davranışını kanıtlamaz; kalite doğrulaması arm64 emülatör/cihazda yapılmalıdır.

APK boyutu bağlamı ve model kurulumu: [model-setup.md](model-setup.md) ("APK boyutu ve ABI").
