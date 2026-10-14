# Uygulama içi "Lisanslar" ekranı (T-037, K-12)

Amaç: APK ile dağıtılan üçüncü taraf bileşenlerin lisans metni ve telif bildirimi internetsiz, uygulamanın İÇİNDE görüntülenir; metin modelinin DEĞİŞTİRİLMİŞ olduğu bildirimi (Apache-2.0 Bölüm 4(b)) yer alır. Hukuki görüş değildir.

- Erişim: (1) İndeks durumu ekranı, "Lisanslar" satırı; (2) izin ekranı (izin verilmemiş/reddedilmiş/kalıcı red durumları), altta "Lisanslar" bağlantısı. Rota `licenses` (`Routes.LICENSES`) izin gerektirmez; geri `safeBack()` ile çıkılan ekrana döner.
- İçerik düz metin olarak `app/src/main/assets/licenses/` altındadır (kodda gömülü uzun metin yok): `components.txt` (bileşen listesi: ad, sürüm, lisans, metin dosyası, kaynak URL, not), `modified-notice.txt` (değiştirilmiş model bildirimi), `app-license.txt` (uygulamanın kendi lisansı: MIT, Copyright (c) 2026 Cem Onat SATIR; metin repo kökündeki LICENSE dosyasından kelimesi kelimesine kopyalandı; proje lisansı değişirse metin yalnızca bu dosyadan okunduğu için yalnızca bu dosya güncellenir), `apache-2.0.txt`, `mit-openai-clip.txt`, `mit-onnxruntime.txt`.
- Kod: `ui/license/LicenseCatalog.kt` (ayrıştırma, paragraf biçimi; JVM testli), `ui/license/LicensesScreen.kt` (Compose; içerik IO'da okunur).
- Geçişli bileşenler tek kayıtta (`Diğer geçişli bileşenler`) özetlenir; sürümler `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` çözümlemesinden alındı (startup 1.1.1, profileinstaller 1.4.1, emoji2 1.4.0, lifecycle-process/viewmodel-savedstate 2.9.4, sqlite 2.6.2, savedstate 1.3.3, collection 1.5.0, tracing 1.2.0, graphics-path 1.0.1, JetBrains Compose 1.9.3, Okio 3.16.4, kotlinx-serialization-core 1.7.3).
- Sürümler `gradle/libs.versions.toml` ile aynı olmalıdır; `LicenseAssetsTest` denetler. Bağımlılık sürümü değişince `components.txt` güncellenmelidir.

## Apache-2.0 metin kaynağı (internetten İNDİRİLMEDİ)

`apache-2.0.txt` (202 satır, 11358 bayt, SHA-256 `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`), yerel Gradle önbelleğinden alındı ve bayt bayt doğrulandı:
- `~/.gradle/caches/9.3.1/transforms/*/transformed/aapt2-9.1.0-14792394-osx/NOTICE`, satır 7-208 (`sed -n 7,208p NOTICE | cmp - apache-2.0.txt` farksız). Bu kopya Ek bölümünü (APPENDIX) içerir.
- Gövde (APPENDIX öncesi 178 satır) ayrıca AndroidX `.../transformed/ui/META-INF/androidx/compose/ui/ui/LICENSE.txt` ile birebir aynıdır (`diff` yalnızca APPENDIX'in eklendiğini gösterir; aynı 178 satırlı AndroidX LICENSE.txt dosyası önbellekte 64 AAR'da özdeştir).

MIT metinleri standart MIT gövdesidir; telif satırları: `Copyright (c) 2021 OpenAI` (kaynak: github.com/openai/CLIP LICENSE, bkz. model-setup.md "Lisans kanıtı") ve `Copyright (c) Microsoft Corporation`.

## Doğrulanamayanlar

- ORT için `mit-onnxruntime.txt`: AAR içinde LICENSE dosyası yoktur; metin standart MIT + Microsoft telifidir (POM: MIT). Java API dosyalarındaki olası ek telif bildirimleri doğrulanamadı; kayıtta yalnızca "ek bildirimler içerebilir, ayrıntı ONNX Runtime deposundadır" denir, telif satırı uydurulmadı.
- Xenova ONNX dönüşümünün kendi lisansı beyan edilmemiştir; ekranda "MIT, kaynak OpenAI CLIP deposundan çıkarım" olarak yazar.
- Geçişli bağımlılıklar artefakt artefakt ayrı kayıt olarak verilmedi, tek kayıtta gruplandı; kayıttaki her bileşenin Apache-2.0 olması bilinen yayın lisansına dayanır, tek tek POM'ları okunmadı. Kotlin stdlib çalışma zamanında 2.3.10 çözülür (libs: 2.2.10).
- Ekran cihazda/emülatörde çalıştırılmadı (androidTest yazıldı ve derlendi).
