# Uygulama içi "Lisanslar" ekranı (T-037, K-12)

Amaç: APK ile dağıtılan üçüncü taraf bileşenlerin lisans metni ve telif bildirimi internetsiz, uygulamanın İÇİNDE görüntülenir; metin modelinin DEĞİŞTİRİLMİŞ olduğu bildirimi (Apache-2.0 Bölüm 4(b)) yer alır. Hukuki görüş değildir.

- Erişim: İndeks durumu ekranı, "Lisanslar" satırı -> rota `licenses` (`Routes.LICENSES`); geri `safeBack()`. İzin gerektirmez ama satır, izin verilmeden açılan indeks ekranında görünmediği için izin yokken ulaşılamaz.
- İçerik düz metin olarak `app/src/main/assets/licenses/` altındadır (kodda gömülü uzun metin yok): `components.txt` (bileşen listesi: ad, sürüm, lisans, metin dosyası, kaynak URL, not), `modified-notice.txt` (değiştirilmiş model bildirimi), `app-license.txt` (uygulamanın kendi lisansı; proje lisansı belirlenince YALNIZCA bu dosya güncellenir), `apache-2.0.txt`, `mit-openai-clip.txt`, `mit-onnxruntime.txt`.
- Kod: `ui/license/LicenseCatalog.kt` (ayrıştırma, paragraf biçimi; JVM testli), `ui/license/LicensesScreen.kt` (Compose; içerik IO'da okunur).
- Sürümler `gradle/libs.versions.toml` ile aynı olmalıdır; `LicenseAssetsTest` denetler. Bağımlılık sürümü değişince `components.txt` güncellenmelidir.

## Apache-2.0 metin kaynağı (internetten İNDİRİLMEDİ)

`apache-2.0.txt` (202 satır, 11358 bayt, SHA-256 `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`), yerel Gradle önbelleğinden alındı ve bayt bayt doğrulandı:
- `~/.gradle/caches/9.3.1/transforms/*/transformed/aapt2-9.1.0-14792394-osx/NOTICE`, satır 7-208 (`sed -n 7,208p NOTICE | cmp - apache-2.0.txt` farksız). Bu kopya Ek bölümünü (APPENDIX) içerir.
- Gövde (APPENDIX öncesi 178 satır) ayrıca AndroidX `.../transformed/ui/META-INF/androidx/compose/ui/ui/LICENSE.txt` ile birebir aynıdır (`diff` yalnızca APPENDIX'in eklendiğini gösterir; aynı 178 satırlı AndroidX LICENSE.txt dosyası önbellekte 64 AAR'da özdeştir).

MIT metinleri standart MIT gövdesidir; telif satırları: `Copyright (c) 2021 OpenAI` (kaynak: github.com/openai/CLIP LICENSE, bkz. model-setup.md "Lisans kanıtı") ve `Copyright (c) Microsoft Corporation`.

## Doğrulanamayanlar

- ORT için `mit-onnxruntime.txt`: AAR içinde LICENSE dosyası yoktur; metin standart MIT + Microsoft telifidir (POM: MIT). ORT Java API dosyalarındaki olası ek telif satırları (başka kuruluşlar) doğrulanamadığı için eklenmedi.
- Xenova ONNX dönüşümünün kendi lisansı beyan edilmemiştir; ekranda "MIT, kaynak OpenAI CLIP deposundan çıkarım" olarak yazar.
- Geçişli bağımlılıkların tam listesi çıkarılmadı; listelenen doğrudan bileşenlerin hepsi Apache-2.0 ya da MIT'dir. Kotlin stdlib çalışma zamanında 2.3.10 çözülür (libs: 2.2.10).
- Ekran cihazda/emülatörde çalıştırılmadı (androidTest yazıldı ve derlendi).
