# Model kurulumu (geliştirici)

Model dosyaları repoda YOKTUR (büyük; `app/src/main/assets/models/` `.gitignore`'dadır). Derleme öncesi bir kez indirilir; dosyalar APK'ya assets olarak girer. Uygulama hiçbir zaman ağdan model indirmez (INTERNET izni yok).

## Kurulum

```
cd mobile
tools/fetch_models.sh              # metin + görüntü (yaklaşık 226 MB)
tools/fetch_models.sh --text-only  # yalnızca metin (T-007 için yeterli)
```

`tools/fetch_models.sh` yalnızca geliştirici makinesinde çalışır, uygulamanın parçası değildir. Gereken: bash, curl, python3 (yalnızca standart kütüphane), shasum/sha256sum. Sabitlenmiş Hugging Face revizyonlarından indirir (Ctrl-C veya hatada `*.part` ve geçici safetensors silinir), her dosyanın SHA-256 değerini doğrular (uyuşmazsa siler ve hata verir), zaten doğrulanmış dosyayı yeniden indirmez. Dense ağırlığını `model.safetensors`'tan ham float32 `.bin`'e çevirir.

Dosyalar yoksa modelsiz testler yine çalışır; gerçek dosya gerektiren testler (`assumeTrue`) atlanır. Uygulama dosya eksikse `ModelException.Missing` verir.

## Dosyalar, kaynak ve lisans

| assets/models/ | Kaynak (revizyon) | Lisans | Boyut (bayt) | SHA-256 |
|---|---|---|---|---|
| `text_model_qint8_arm64.onnx` | https://huggingface.co/sentence-transformers/clip-ViT-B-32-multilingual-v1 `onnx/model_qint8_arm64.onnx` (58edf8cada9e398793dca955574a48cbb7f18be2) | Apache-2.0 | 135336307 | 3bed77e83926519660b5c0833cb3321cfde952331edec4f7d501c7d384b8a8a7 |
| `text_dense_768x512_f32.bin` | Aynı revizyon, `2_Dense/model.safetensors` (SHA-256 d12568dc7300970a4d3dbb49068ad16cd89b99840b74b026f8e48071e9414f74) içindeki `linear.weight`, ham float32 little-endian, satır sıralı [512 çıkış][768 giriş], bias yok | Apache-2.0 | 1572864 | e5f6548cbcef6c62631b3946d89042ab4c8451762c7bb0a3be11eb6c480b686d |
| `text_vocab.txt` | Aynı revizyon, `vocab.txt` (distilbert-base-multilingual-cased WordPiece, 119547 satır) | Apache-2.0 | 995526 | fe0fda7c425b48c516fc8f160d594c8022a0808447475c1a7c6d6479763f310c |
| `vision_model_quantized.onnx` (T-006 kullanır) | https://huggingface.co/Xenova/clip-vit-base-patch32 `onnx/vision_model_quantized.onnx` (d15189d7028b43f1d3e65039190477f6af591c2a), openai/clip-vit-base-patch32 dönüşümü | Beyan yok (MIT çıkarım: OpenAI CLIP MIT, repo düzeyi; Xenova repo'sunda lisans beyanı YOK, bkz. "Lisans kanıtı") | 89117001 | 583fd1110a514667812fee7d684952aaf82a99b959760c8d7dca7e0ab9839299 |

Rapora lisans atfı: sentence-transformers (Apache-2.0), github.com/openai/CLIP (MIT). Ayrıntı ve araştırma: `model-research.md`. Aynı değerler `ml/ModelManifest.kt` içindedir; bir test betikle tutarlılığı denetler. Lisansların hangi kanıta dayandığı ve nelerin doğrulanamadığı aşağıdaki "Lisans kanıtı" bölümündedir.

## Lisans kanıtı (T-019, kontrol tarihi 2026-10-13)

Yalnızca doküman araştırmasıdır: Hugging Face API/ham README, GitHub API/ham dosya ve Maven POM'ları okundu. Uygulamaya ağ özelliği eklenmedi. "Doğrulandı" = kaynak sayfada/dosyada görüldü; "doğrulanamadı" = bulunamadı, uydurulmadı.

### Metin modeli: sentence-transformers/clip-ViT-B-32-multilingual-v1 (Apache-2.0, DOĞRULANDI)

- Kaynak: https://huggingface.co/sentence-transformers/clip-ViT-B-32-multilingual-v1, revizyon `58edf8cada9e398793dca955574a48cbb7f18be2` (fetch_models.sh'taki sabitlenmiş revizyonla aynı; son değişiklik 2024-11-05).
- Kanıt: bu revizyonda `README.md` front-matter'ında `license: apache-2.0`; HF API etiketi `license:apache-2.0`. Repoda ayrı LICENSE veya NOTICE dosyası YOK (dosya listesinde yalnızca README.md lisans/bilgi dosyası).
- Türetme zinciri (kart, "Training" bölümü): öğretmen `clip-ViT-B-32` (OpenAI CLIP, MIT) ile çok dilli bilgi damıtma; öğrenci `distilbert-base-multilingual-cased`. Öğrenci taban modelin lisansı Apache-2.0 (HF `distilbert/distilbert-base-multilingual-cased`, kart etiketi, revizyon `45c032ab32cc946ad88a166f7cb282f58c753c2e`, kontrol anındaki en son sürüm). `text_vocab.txt` bu taban modelin sözlüğüdür; aynı Apache-2.0 kapsamında kabul edilir (kartta ayrı beyan yok).
- DOĞRULANAMADI: damıtmada kullanılan paralel veri kümesinin(lerin) lisansı. Kart yalnızca "parallel data" der ve veri kümesi adı/lisansı vermez. Bu, model sahibinin Apache-2.0 beyanını değiştirmez ama ağırlıkların eğitim verisi kaynaklı bir kısıtı olup olmadığı bilinmiyor.

### Görüntü modeli: OpenAI CLIP ViT-B/32 (MIT, repo düzeyinde DOĞRULANDI; ağırlıklara özgü ayrı beyan DOĞRULANAMADI)

- Orijinal lisans: https://github.com/openai/CLIP `LICENSE`, "MIT License, Copyright (c) 2021 OpenAI". GitHub API lisansı `MIT` (SPDX) olarak tanır. `LICENSE` dosyası tek commit'tir: `b1c4b6be5871f1b94359ba55901627f29ecc9ae9` (2021-01-05, "initial commit"); kontrol anında `main` ucu `d05afc436d78f1c48dc0dbf8e5980a9d471f35f6` (2026-03-25). Ham `LICENSE` SHA-256: `987e63b32f6c89ff5160e429458a872ff048e6860b590a3912e938f9da8f14db`.
- ViT-B/32 ağırlığının dağıtımı: `clip/clip.py` içinde `openaipublic.azureedge.net/clip/models/40d365715913c9da98579312b702a82c18be219cc2a73407c4526f58eba950af/ViT-B-32.pt` bağlantısı (yani repodaki kod ağırlığı OpenAI CDN'inden indirir; ağırlık dosyası repoda yok). Repo README'si ve `model-card.md` ağırlıklar için ayrı bir lisans cümlesi içermiyor; MIT'nin ağırlık dosyasını açıkça kapsadığına dair AYRI bir beyan DOĞRULANAMADI. Dayanak, repo düzeyindeki MIT lisansı ve ağırlığın bu repo'nun kodunun referans verdiği bağlantıdan dağıtılmasıdır (hukuki görüş değildir).
- HF aynası https://huggingface.co/openai/clip-vit-base-patch32 (revizyon `3d74acf9a28c67741b2f4f2ea7635f0aaf6f0268`): HF lisans etiketi YOK, kartta lisans cümlesi YOK; kart GitHub model kartından uyarlanmıştır. Bu yüzden HF tarafından MIT DOĞRULANAMADI; kanıt yalnızca GitHub'dadır.

### Görüntü dönüşümü: Xenova/clip-vit-base-patch32 (lisans beyanı YOK, DOĞRULANAMADI)

- Kaynak: https://huggingface.co/Xenova/clip-vit-base-patch32, revizyon `d15189d7028b43f1d3e65039190477f6af591c2a` (fetch_models.sh'taki revizyonla aynı; son değişiklik 2025-07-08). Kullanılan dosya: `onnx/vision_model_quantized.onnx`.
- Bulgu: HF API'de lisans etiketi yok; kart front-matter'ında yalnızca `base_model: openai/clip-vit-base-patch32` ve `library_name: transformers.js`; repoda LICENSE/NOTICE dosyası yok. Kart, repo'yu "openai/clip-vit-base-patch32 with ONNX weights" diye tanımlar.
- Sonuç: dönüşümün KENDİ lisansı doğrulanamadı. MIT'nin geçerli olması, dönüşümün orijinal ağırlıkların türevi olması ve orijinalin MIT olması çıkarımına dayanır (türev, MIT şartlarının, yani telif ve izin notunun, korunmasını gerektirir). "MIT varsayım" ifadesi bu nedenle "varsayım" yerine açıkça "çıkarım, repo beyanı yok" diye bırakıldı. Kesin hukuki güvence gerekiyorsa seçenek: Xenova repo'su yerine orijinal ağırlıkları kendi ONNX dönüşümümüzle üretmek (yapılmadı; kapsam dışı).

### Kullanılmayan alternatif: immich-app/ViT-B-32__openai (lisans beyanı YOK, DOĞRULANAMADI)

Revizyon `a857c8de2c07bbcfa6646adfcf31b798845afa1e`. HF etiketi yok; kart yalnızca "ONNX exports for the associated CLIP model by OpenCLIP" der. Uygulamaya girmez (yalnızca model-research.md'de alternatif olarak anılır).

### Özet

| Bileşen | Lisans | Durum |
|---|---|---|
| sentence-transformers/clip-ViT-B-32-multilingual-v1 (metin ONNX, Dense, sözlük) | Apache-2.0 | Doğrulandı (kart + API); eğitim verisi lisansı doğrulanamadı |
| distilbert-base-multilingual-cased (taban, sözlük kaynağı) | Apache-2.0 | Doğrulandı (kart etiketi) |
| OpenAI CLIP (github.com/openai/CLIP) | MIT, Copyright (c) 2021 OpenAI | Repo düzeyinde doğrulandı; ağırlığa özgü ayrı beyan ve HF'de etiket doğrulanamadı |
| Xenova/clip-vit-base-patch32 (görüntü ONNX int8) | Beyan yok | Doğrulanamadı; MIT yalnızca kaynaktan çıkarım |

## Üçüncü taraf lisansları ve uygulama içi "Lisanslar" notu

Değerlendirme (T-019): uygulama içinde bir "Lisanslar" notu GEREKİR. Gerekçe (hukuki görüş değil, mühendislik değerlendirmesi):
- Model ağırlıkları (Apache-2.0 ve MIT) ve kütüphaneler APK içinde yeniden dağıtılır (ders teslimi APK ile yapılacaksa bu da dağıtımdır). MIT telif ve izin notunun kopyalarda korunmasını ister; Apache-2.0 (Bölüm 4) lisans metninin bir kopyasının alıcıya verilmesini, varsa NOTICE içeriğinin korunmasını ister. APK'da bu metinleri taşıyan bir yer yoksa şartlar karşılanmaz.
- Kontrol: `onnxruntime-android-1.30.0.aar` içinde LICENSE/NOTICE/ThirdParty dosyası YOK (arşiv listesinde yok); yani ORT'nin MIT notu kendiliğinden APK'ya girmez. Uygulamada şu an lisans ekranı veya lisans metni içeren asset YOK (kaynak kodda arandı).
- Bu belge ve rapor, yalnızca kaynak ağacını/raporu okuyanlara atıf sağlar; APK'yı kullanan kişi bunu görmez.

Kullanılan üçüncü taraf bileşenler (lisans, Maven POM `<license>` alanından 2026-10-13'te okundu; sürüm = libs.versions.toml):

| Bileşen | Sürüm | Lisans |
|---|---|---|
| onnxruntime-android (Microsoft) | 1.30.0 | MIT (POM + GitHub API) |
| Coil 3 (coil-compose, coil-core) | 3.4.0 | Apache-2.0 |
| kotlinx-coroutines-core | 1.11.0 | Apache-2.0 |
| Kotlin stdlib | 2.2.10 | Apache-2.0 |
| AndroidX: Room, WorkManager, Navigation Compose, ExifInterface, Activity Compose, Lifecycle, core-ktx | libs.versions.toml'daki sürümler | Apache-2.0 (her birinin POM'unda, Google Maven) |
| Jetpack Compose (ui, material3) | BOM 2026.02.01 | Apache-2.0 (POM'u okunan `ui` 1.10.3 ve `material3` 1.4.0 için doğrulandı; BOM'un çözdüğü tam sürümler ayrıca kontrol edilmedi) |
| Yalnızca test/araç: JUnit (4.13.2) vb., KSP, AGP | | Dağıtılan APK'ya girmez; not gerekmez (JUnit lisansı kontrol edilmedi) |

Doğrulanamayan: ORT'nin telif satırı (hangi "Copyright (c)" metni) ve geçişli bağımlılıkların tam listesi (Gradle bağımlılık ağacından lisans çıkarımı yapılmadı); Apache NOTICE dosyaları (AndroidX/Coil) tek tek kontrol edilmedi.

Öneri (uygulanmadı, kod yazılmadı; karar PO'da):
1. Ağsız, statik bir "Lisanslar" ekranı veya `assets/` altında düz metin dosyası: yukarıdaki tablodaki her bileşen için ad, telif sahibi, lisans adı ve bağlantı; bir kez tam Apache-2.0 metni; OpenAI CLIP için tam MIT metni ("Copyright (c) 2021 OpenAI"); modeller için Bölüm "Lisans kanıtı" özeti (sentence-transformers Apache-2.0, distilbert Apache-2.0). Bağımlılık eklemez. Erişim: indeks durumu ekranından bir bağlantı. Bu bir UI/gezinti eklemesidir, architecture.md Bölüm 4'e yansıtılmalıdır.
2. Otomatik üretim (ör. bir lisans toplama eklentisi) ek bağımlılık/eklenti gerektirir; architecture.md Bölüm 8 gereği gerekçe ve seçim kararı ayrıdır. Seçilmedi, değerlendirilmedi.
3. Xenova dönüşümünün lisans belirsizliği ekranda "OpenAI CLIP (MIT) ağırlıklarından dönüştürülmüştür" diye dürüstçe belirtilmeli; ekranda "Xenova MIT lisansı verdi" denmemeli.

## Metin hattı (arama sırasında çalışan tek ML)

istem -> `QueryPreprocessor` (Türkçe küçük harf, kontrol karakteri atma, boşluk, 256 karakter sınırı) -> `WordPieceTokenizer` ([CLS]/[SEP], en fazla 128 token) -> ONNX `last_hidden_state` -> attention mask'li mean pooling -> Dense 768->512 -> L2. Çıktı 512 boyutlu birim vektör. `modelVersion` etiketi `ModelManifest.MODEL_VERSION`; T-006 indeksleyicisi aynı `EMBEDDING_SPEC`'i kullanmalıdır.

## Doğrulama durumu

- Tokenizer: gerçek `text_vocab.txt` ile 18 metinde Python HF `tokenizers` 0.23.2 token kimlikleriyle birebir (Türkçe harfler, CJK, emoji, 300 karakterlik sözcük, 128 token kesmesi dahil).
- Dense + L2: gerçek Dense ağırlığı ve Python (onnxruntime 1.30 masaüstü, numpy) referans vektörleriyle 1e-5 içinde.
- ONNX Runtime Android üzerinde çalıştırma, süre ve bellek: doğrulanamadı (cihaz/emülatör yok). int8 `arm64` dosyasının x86 emülatörde çalışması da doğrulanmadı.

## İlk sorgu gecikmesi ve ısınma (T-008 için)

İlk `encode` çağrısı dosya doğrulaması/kopyası (ilk kurulumda 135 MB akış kopyası + SHA-256), ORT oturumu oluşturma, 119547 satırlık sözlüğün okunması ve Dense yüklemesini içerir; bu iş `Dispatchers.IO`'da yürür, kopya sırasında iptale duyarlıdır (oturum oluşturma bölünemez). Sonraki açılışlarda işaretçi dosyası sayesinde yeniden hash'lenmez. İlk sorgu gecikmesi ÖLÇÜLMEDİ (cihaz yok). T-008, arama ekranı açılmadan önce arka planda bir ısınma (warm-up) `encode` çağrısı yapmalıdır; ilk kurulumdaki kopya süresi kullanıcıya gösterilmelidir (ilerleme/beklet). Tek bir `OnnxTextEncoder` örneği kullanılmalıdır (KDoc: tekil kullanım kuralı).

## Cihaz testi

Altın metin vektörleri (T-026, model değişikliklerinden önce alınan referans), eşikler ve budama kapıları: `golden-text.md`.

`OnnxTextEncoderDeviceTest` (androidTest) gerçek modelle ONNX yolunu (encode, oturum, release, slot eviction, Python referans vektörlerine kosinüs >= 0.99) sınar. YAZILDI ve DERLENDİ ama ÇALIŞTIRILMADI (cihaz/emülatör yok). ORT'nin Android'de çalışması, telemetri provider'ı kaldırıldığında yüklemenin bozulmaması, süre ve bellek bu yüzden doğrulanamadı.

## ORT telemetrisi

onnxruntime-android AAR'ı Microsoft telemetri istemcisini içerir (`ai.onnxruntime.TelemetryInitializer` ContentProvider'ı, HttpClient, PowerInfoReceiver, ConnectivityCallback; cihaz kimliği/sistem bilgisi toplar, tmpdir'e önbellek yazar, INTERNET ve ACCESS_NETWORK_STATE izinlerini ekler). Kapatma: (1) manifestte provider ve iki izin `tools:node="remove"`; provider olmayınca yerel katman, libonnxruntime.so içindeki dizeye göre ("Java HttpClient başlatılmadı"), telemetriyi kullanılamaz sayar; çalışma zamanında doğrulanmadı; (2) oturum açılırken `OrtEnvironment.setTelemetry(false)`. `MergedManifestTest` debug ve release birleşik manifestlerini izin/bileşen beyaz listesiyle denetler (test görevi `processDebugMainManifest`/`processReleaseMainManifest`'e bağlıdır). Sürüm yükseltmelerinde bu testin sonucuna bakılmalıdır.

## APK boyutu ve ABI (ileri aşama kararı)

Debug APK şu an yaklaşık 375 MB: 4 ABI (arm64-v8a, armeabi-v7a, x86, x86_64) ORT yerel kütüphaneleri (~135 MB) + ~226 MB model. `abiFilters += "arm64-v8a"` ile ~100 MB kazanılır ama arm32 cihazlar ve x86 emülatör çalışmaz; bu karar ileri aşamaya bırakıldı ve `build.gradle.kts`'de UYGULANMADI (model-research.md 5.2).
