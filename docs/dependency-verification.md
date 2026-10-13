# Gradle bağımlılık doğrulaması (öneri)

Durum (T-017): `gradle/verification-metadata.xml` YOK, bilerek uygulanmadı. Bu belge karar ve uygulama
yolunu kaydeder. Gradle wrapper zaten `distributionSha256Sum` ile sabitlenmiş.

## Neden değerli

Uygulama cihaz üstüdür ve model/indeks cihazdan çıkmaz. Tedarik zinciri riski derleme tarafındadır: Maven
Central/Google deposundan gelen bir AAR (ör. onnxruntime-android, 53 MB, telemetri bileşeni taşıyor) sessizce
değişirse APK'ya girer. `verification-metadata.xml` her artefaktın SHA-256 değerini sabitler; değişen veya
kurcalanan artefakt derlemeyi durdurur.

## Neden şimdi uygulanmadı

- Dosya büyük (Android/Compose/Kotlin/Room/lint/AGP araçları dahil binlerce satır) ve her bağımlılık
  güncellemesinde değişir.
- `--write-verification-metadata` yalnızca o çalıştırmada çözülen yapılandırmaları görür. `lintDebug`,
  `connectedDebugAndroidTest`, `testReleaseUnitTest` gibi lazy çözülen yapılandırmalar eksik kalırsa
  sonradan "Dependency verification failed" ile kırılır. Üretimi emülatör açıkken ve tüm görevler
  çalıştırılarak yapılmalı.
- Paralel çalışan görevler (T-015, T-019) sırasında üretmek, yarım bağımlılık kümesini sabitleme riski taşır.

## Önerilen uygulama (ayrı görev)

1. Bağımlılıklar durulduğunda, mobile/ içinden:
   `./gradlew --write-verification-metadata sha256 testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease lintDebug connectedDebugAndroidTest`
   (emülatör açık olmalı). Eksik kalan görev olursa aynı komut ek görevle yeniden çalıştırılır; mevcut girdiler korunur.
2. `gradle/verification-metadata.xml` dosyasını gözden geçir: `<verify-metadata>true</verify-metadata>`,
   `<verify-signatures>false</verify-signatures>` (yalnızca sha256; PGP anahtar yönetimi bu proje için fazla).
3. `./gradlew testDebugUnitTest assembleDebug lintDebug` yeşil olmalı. Bir görev patlarsa o görevle metadata'yı yeniden üret.
4. Dosyayı commit'le (mobile/ altında, GitHub'a gider).

## Bağımlılık güncellemesinde zahmet

Her sürüm değişikliğinde (`libs.versions.toml`) metadata yeniden üretilmeli:
`./gradlew --write-verification-metadata sha256 <aynı görev listesi>`, sonra `git diff` ile yalnızca beklenen
artefaktların eklendiği/çıkarıldığı kontrol edilir. Yeni bir sürüm için gelen SHA'ya körü körüne güvenme:
`--write-verification-metadata` mevcut indirmeyi sabitler, doğrulamaz; sürüm değişimi zaten gerekçelendirilmiş
(architecture.md bölüm 8, "bağımlılıklar eklenmeden önce gerekçe") olmalı. Not: aynı sürüm için SHA'nın değişmesi
alarm sebebidir (Maven sürümleri değişmez olmalı), araştırılmadan güncellenmemeli.
