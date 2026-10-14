# Üçüncü taraf bildirimleri

Ai-galeri'nin kendi kaynak kodu [MIT Lisansı](LICENSE) altındadır. Bu lisans yalnızca bu depodaki kaynak kodu kapsar. Uygulamayla birlikte kullanılan modeller ve kütüphaneler kendi lisanslarına tabidir. Aynı bilgiler uygulama içinde de bulunur (indeks durumu ekranı > Lisanslar); tam lisans metinleri `app/src/main/assets/licenses/` altındadır.

Model ağırlıkları bu depoda **bulunmaz**; `tools/fetch_models.sh` ile Hugging Face'ten indirilir ve SHA-256 ile doğrulanır (bkz. [docs/model-setup.md](docs/model-setup.md)).

## Modeller

| Bileşen | Lisans | Not |
|---|---|---|
| OpenAI CLIP ViT-B/32 (görüntü modeli) | MIT, Telif (c) 2021 OpenAI | Cihazdaki dosya, Xenova/clip-vit-base-patch32 deposundaki int8 ONNX dönüşümüdür. Dönüşümün kendi lisans beyanı yoktur; MIT lisansı yalnızca kaynak OpenAI CLIP deposundan çıkarılmıştır (bkz. docs/model-setup.md, "Lisans kanıtı"). |
| sentence-transformers/clip-ViT-B-32-multilingual-v1 (metin modeli) | Apache-2.0 | **Değiştirilmiş sürüm:** gömme tablosu satır dilimlenmiş (119547 -> 69830 satır) ve sözlük budanmıştır. Orijinal revizyon `58edf8cada9e398793dca955574a48cbb7f18be2`. Ayrıntı: [docs/vocab-pruning.md](docs/vocab-pruning.md). |
| distilbert-base-multilingual-cased (sözlük) | Apache-2.0 | Metin modelinin sözlüğü, **budanmış sürüm**. Orijinal sözlük metni testler için `app/src/test/resources/ml/original_vocab.txt.gz` olarak depoda bulunur (revizyon `45c032ab32cc946ad88a166f7cb282f58c753c2e`). |

## Yazılım bileşenleri

| Bileşen | Lisans |
|---|---|
| ONNX Runtime (onnxruntime-android), Telif (c) Microsoft Corporation | MIT |
| Coil | Apache-2.0 |
| AndroidX (Room, WorkManager, Navigation, Lifecycle, Activity, Core, ExifInterface ve geçişli bileşenler), Jetpack Compose, Material 3 | Apache-2.0 |
| Kotlin, kotlinx.coroutines, kotlinx.serialization, Okio | Apache-2.0 |

Sürümler [gradle/libs.versions.toml](gradle/libs.versions.toml) içindedir. Apache License 2.0 tam metni: `app/src/main/assets/licenses/apache-2.0.txt`; MIT metinleri: `mit-openai-clip.txt`, `mit-onnxruntime.txt` (aynı klasör).
