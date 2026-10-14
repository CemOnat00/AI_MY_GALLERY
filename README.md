# AI Galeri

Telefon galerisindeki fotoğrafları **Türkçe doğal dil** ile arayan, **tamamen cihaz üstünde** çalışan bir Android uygulaması. KTÜ Mobil Programlama dersi dönem projesidir.

> "gün batımında deniz", "kırmızı araba", "parkta koşan köpek", "bir bardak fotoğrafı"

## Özellikler

- Türkçe cümleyle fotoğraf arama; sonuçlar benzerlik sırasıyla listelenir.
- Galeri görünümü: cihazdaki tüm fotoğraflar 3 sütunlu ızgarada, büyük görünüm, çekim tarihi.
- Arka planda indeksleme (WorkManager): yeni fotoğraflar artımlı işlenir, indeks durumu ve yeniden indeksleme ekranı vardır.
- Gizlilik: **INTERNET izni yoktur.** Fotoğraflar, vektörler ve arama metni cihazdan çıkmaz; sunucu, hesap, analitik veya uzak servis yoktur.

## Nasıl çalışır

1. Fotoğraflar önceden analiz edilir: çok dilli CLIP (ONNX Runtime) her fotoğraf için 512 boyutlu bir vektör üretir, Room/SQLite'a yazılır.
2. Arama sırasında yalnızca sorgu metni vektöre çevrilir (çok dilli metin kodlayıcı, Türkçeyi doğrudan alır; kısa sorgularda "bir X" ve "bir X fotoğrafı" şablonlarının ortalaması kullanılır).
3. Sorgu vektörü ile indeksteki vektörler arasındaki kosinüs benzerliğine göre en yakın fotoğraflar döner. Arama sırasında görüntü analizi veya ML Kit gibi servisler çağrılmaz.

Ayrıntılı tasarım ve ölçümler `docs/` altındadır.

## Gereksinimler

- Android Studio (güncel sürüm), JDK 17 veya üzeri (Android Studio'nun JBR'si yeterlidir), Android SDK 36
- Çalıştırmak için arm64 bir Android cihaz veya arm64 emülatör (minSdk 26)
- Model kurulumu için: `bash`, `curl`, `python3` (>= 3.8, yalnızca standart kütüphane), `shasum` ya da `sha256sum`

## Kurulum

```bash
git clone https://github.com/CemOnat00/AI_MY_GALLERY.git
cd AI_MY_GALLERY

# Model dosyaları repoda YOKTUR (büyük). Bir kez indirilir ve SHA-256 ile doğrulanır:
tools/fetch_models.sh

# Derleme
./gradlew assembleDebug
```

Model kaynakları, sürümleri, lisansları ve budama adımı: [docs/model-setup.md](docs/model-setup.md). Model dosyaları APK'ya gömülür; uygulama hiçbir zaman ağdan model indirmez.

## Testler

```bash
./gradlew testDebugUnitTest        # birim testleri (JVM)
./gradlew lintDebug                # lint
./gradlew connectedDebugAndroidTest   # cihaz/emülatör testleri (cihaz bağlı olmalı)
```

## Proje yapısı

```
app/            Android uygulaması (Kotlin, Jetpack Compose, Room, WorkManager, ONNX Runtime)
docs/           Tasarım, model kurulumu, ölçümler, lisans kanıtı
tools/          Model indirme/budama ve doğrulama betikleri (geliştirici makinesi)
reports/        Ders raporları
gradle/         Gradle sarmalayıcısı ve sürüm kataloğu
```

## Bilinen sınırlar

- Metin modeli budandığı için Latin harfli olmayan sorgular (Kiril, Arapça, CJK) anlamlı sonuç vermez; Türkçe ve İngilizce etkilenmez.
- Android 14 "seçili fotoğraflar" erişimi desteklenmez; "Tüm fotoğraflara izin ver" gerekir.
- Release APK imzasızdır (yalnızca arm64-v8a).

## Lisans

Bu proje [MIT Lisansı](LICENSE) ile yayımlanmıştır. Kullanılan modeller ve kütüphaneler kendi lisanslarına tabidir: bkz. [NOTICE.md](NOTICE.md) ve [docs/model-setup.md](docs/model-setup.md).
