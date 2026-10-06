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
| `vision_model_quantized.onnx` (T-006 kullanır) | https://huggingface.co/Xenova/clip-vit-base-patch32 `onnx/vision_model_quantized.onnx` (d15189d7028b43f1d3e65039190477f6af591c2a), openai/clip-vit-base-patch32 dönüşümü | MIT (OpenAI CLIP; Xenova repo'sunda ayrı lisans etiketi yok, kaynak lisansı varsayımı) | 89117001 | 583fd1110a514667812fee7d684952aaf82a99b959760c8d7dca7e0ab9839299 |

Rapora lisans atfı: sentence-transformers (Apache-2.0), github.com/openai/CLIP (MIT). Ayrıntı ve araştırma: `model-research.md`. Aynı değerler `ml/ModelManifest.kt` içindedir; bir test betikle tutarlılığı denetler.

## Metin hattı (arama sırasında çalışan tek ML)

istem -> `QueryPreprocessor` (Türkçe küçük harf, kontrol karakteri atma, boşluk, 256 karakter sınırı) -> `WordPieceTokenizer` ([CLS]/[SEP], en fazla 128 token) -> ONNX `last_hidden_state` -> attention mask'li mean pooling -> Dense 768->512 -> L2. Çıktı 512 boyutlu birim vektör. `modelVersion` etiketi `ModelManifest.MODEL_VERSION`; T-006 indeksleyicisi aynı `EMBEDDING_SPEC`'i kullanmalıdır.

## Doğrulama durumu

- Tokenizer: gerçek `text_vocab.txt` ile 18 metinde Python HF `tokenizers` 0.23.2 token kimlikleriyle birebir (Türkçe harfler, CJK, emoji, 300 karakterlik sözcük, 128 token kesmesi dahil).
- Dense + L2: gerçek Dense ağırlığı ve Python (onnxruntime 1.30 masaüstü, numpy) referans vektörleriyle 1e-5 içinde.
- ONNX Runtime Android üzerinde çalıştırma, süre ve bellek: doğrulanamadı (cihaz/emülatör yok). int8 `arm64` dosyasının x86 emülatörde çalışması da doğrulanmadı.

## İlk sorgu gecikmesi ve ısınma (T-008 için)

İlk `encode` çağrısı dosya doğrulaması/kopyası (ilk kurulumda 135 MB akış kopyası + SHA-256), ORT oturumu oluşturma, 119547 satırlık sözlüğün okunması ve Dense yüklemesini içerir; bu iş `Dispatchers.IO`'da yürür, kopya sırasında iptale duyarlıdır (oturum oluşturma bölünemez). Sonraki açılışlarda işaretçi dosyası sayesinde yeniden hash'lenmez. İlk sorgu gecikmesi ÖLÇÜLMEDİ (cihaz yok). T-008, arama ekranı açılmadan önce arka planda bir ısınma (warm-up) `encode` çağrısı yapmalıdır; ilk kurulumdaki kopya süresi kullanıcıya gösterilmelidir (ilerleme/beklet). Tek bir `OnnxTextEncoder` örneği kullanılmalıdır (KDoc: tekil kullanım kuralı).

## Cihaz testi

`OnnxTextEncoderDeviceTest` (androidTest) gerçek modelle ONNX yolunu (encode, oturum, release, slot eviction, Python referans vektörlerine kosinüs >= 0.99) sınar. YAZILDI ve DERLENDİ ama ÇALIŞTIRILMADI (cihaz/emülatör yok). ORT'nin Android'de çalışması, telemetri provider'ı kaldırıldığında yüklemenin bozulmaması, süre ve bellek bu yüzden doğrulanamadı.

## ORT telemetrisi

onnxruntime-android AAR'ı Microsoft telemetri istemcisini içerir (`ai.onnxruntime.TelemetryInitializer` ContentProvider'ı, HttpClient, PowerInfoReceiver, ConnectivityCallback; cihaz kimliği/sistem bilgisi toplar, tmpdir'e önbellek yazar, INTERNET ve ACCESS_NETWORK_STATE izinlerini ekler). Kapatma: (1) manifestte provider ve iki izin `tools:node="remove"`; provider olmayınca yerel katman, libonnxruntime.so içindeki dizeye göre ("Java HttpClient başlatılmadı"), telemetriyi kullanılamaz sayar; çalışma zamanında doğrulanmadı; (2) oturum açılırken `OrtEnvironment.setTelemetry(false)`. `MergedManifestTest` debug ve release birleşik manifestlerini izin/bileşen beyaz listesiyle denetler (test görevi `processDebugMainManifest`/`processReleaseMainManifest`'e bağlıdır). Sürüm yükseltmelerinde bu testin sonucuna bakılmalıdır.

## APK boyutu ve ABI (ileri aşama kararı)

Debug APK şu an yaklaşık 375 MB: 4 ABI (arm64-v8a, armeabi-v7a, x86, x86_64) ORT yerel kütüphaneleri (~135 MB) + ~226 MB model. `abiFilters += "arm64-v8a"` ile ~100 MB kazanılır ama arm32 cihazlar ve x86 emülatör çalışmaz; bu karar ileri aşamaya bırakıldı ve `build.gradle.kts`'de UYGULANMADI (model-research.md 5.2).
