# Altın metin vektörleri (T-026)

Model değişikliklerinden (T-027 sözlük budama, T-028 mmap, T-030 .ort) ÖNCE alınan referans: 60 sorgu için ORİJİNAL metin modeliyle token METNİ, token id'leri ve 512 boyutlu L2 normalize vektör. Sonraki değişiklikler bu referansa karşı ölçülür. Yalnızca geliştirici aracıdır; uygulamaya ağ, izin veya bağımlılık eklemez, APK'ya girmez.

## Dosyalar

| Dosya | İçerik |
|---|---|
| `tools/golden_text.py` | Üretici (python3 + numpy + onnxruntime 1.30.0). Yalnızca geliştirici makinesinde çalışır. |
| `tools/golden_text_queries.tsv` | Girdi: kategori, sorgu (60 satır: nesne 8, sahne 6, renk 5, eylem 6, kişi sayısı 5, mekân 7, ekli 5, ekli olmayan 3, sayı 4, aksan/büyük-küçük 5, emoji 2, İngilizce 4). |
| `app/src/test/resources/ml/golden_queries.tsv` | Çıktı: sorgu, kategori, token METİNLERİ (boşlukla), id'ler (boşlukla), sekmeyle ayrılmış, başlıksız. ~6 KB. |
| `app/src/test/resources/ml/golden_text_vectors.tsv` | Çıktı: sorgu, 512 float (`%.8g`), ORİJİNAL model, 1 iş parçacığı. ~390 KB (toplam < 1 MB). |
| `GoldenTextSupport.kt`, `GoldenTextTest.kt` (JVM), `GoldenTextVectorsDeviceTest.kt` (androidTest) | Testler; `TokenVocab` id <-> metin yardımcısı. |

## Kurulum ve çalıştırma (yalnızca geliştirici makinesi)

```
python3 -m venv ~/venvs/aigaleri-ort        # repo DIŞINDA bir yer
~/venvs/aigaleri-ort/bin/pip install numpy onnxruntime==1.30.0   # ağ yalnızca bu kurulum için; isteğe bağlı: tokenizers (çapraz kontrol)
cd mobile && tools/fetch_models.sh --text-only                    # orijinal model dosyaları (SHA doğrulamalı)
~/venvs/aigaleri-ort/bin/python -I tools/golden_text.py           # altın dosyaları üretir
~/venvs/aigaleri-ort/bin/python -I tools/golden_text.py --noise   # gürültü tabanı; dosya yazmaz
```

Betik şunları DOĞRULAR ve biri tutmazsa dosya yazmadan çıkar (kod 1): (1) `text_model_qint8_arm64.onnx`, `text_dense_768x512_f32.bin`, `text_vocab.txt` boyutu ve SHA-256'sı `ml/ModelManifest.kt` ile eşit (budanmış/değiştirilmiş model ile altın üretilmez; bu yüzden altın yenileme yalnızca ORİJİNAL dosyalarla mümkündür); (2) Python tokenizer, `tokenizer_reference.tsv`'nin tüm satırlarını birebir üretir.

## Tokenizasyon referansı ve ayrışma

Üretimdeki yol: `QueryPreprocessor.normalize` (kontrol/biçim karakterlerini at, boşlukları indir, Türkçe küçük harf) sonra `WordPieceTokenizer.encode`. Betik bu ikisinin birebir Python portunu içerir (Kotlin davranışı referanstır; `unicodedata` kategorileri Java `Character.getType` karşılıklarıdır). Sonuç: `tokenizer_reference.tsv` 18/18 satır eşit; 60 normalize sorguda HF `tokenizers` 0.23.3 (BertNormalizer, lowercase=False, strip_accents=False) ile 0 ayrışma. Bilinen, bu sette TETİKLENMEYEN farklar (portta kasıtlı taklit edildi, doğrulanmadı): Python `str.lower()` ile Java `lowercase(tr)` özel harflerde (ör. `İ`) ayrışır; `tr_lower` `I`, `İ`, `I`+U+0307 için Java kuralını uygular, kalan harfler varsayılan Unicode kuralıdır. Python `unicodedata` ile Java'nın Unicode sürümü farklıysa nadir karakterlerin kategorisi ayrışabilir (sette yok). Sonuç: bu sette ayrışma YOK.

## [UNK] tabanı

329 içerik token'ından 2'si `[UNK]` (oran 0,0061): `🐱 kedi` ve `plaj 🏖️ tatil` (emoji sözlükte yok). Test bu sayıyı ve oranı sabitler (`GoldenText.BASELINE_*`); budama sonrası ne sayı ne oran artabilir.

## Kosinüs gürültü tabanı (masaüstü, onnxruntime 1.30.0, Apple Silicon (arm64) Mac, ORT CPUExecutionProvider, 60 sorgu)

`tools/golden_text.py --noise` çıktısı (kosinüs ölçümü float64 ve float32 nokta çarpımı; son işlem EmbeddingMath ile aynı sırada):

| Karşılaştırma | 1-kosinüs (f64, en kötü) | 1-kosinüs (f32 nokta çarpımı) | en büyük \|fark\| | bit özdeş |
|---|---|---|---|---|
| 1 iş parçacığı, tekrar (aynı oturum) | 2,2e-16 | 1,8e-7 | 0 | 60/60 |
| 1 vs 2 iş parçacığı | 2,2e-16 | 1,8e-7 | 0 | 60/60 |
| 2 iş parçacığı, tekrar | 2,2e-16 | 1,8e-7 | 0 | 60/60 |
| ENABLE_ALL vs ENABLE_EXTENDED | 2,2e-16 | | ölçülmedi | ölçülmedi |
| ENABLE_ALL vs ENABLE_BASIC / DISABLE_ALL (farklı çekirdek yolu vekili) | 2,2e-3 (ortalama 8,4e-4) | 2,2e-3 | 1,8e-2 | 0/60 |

Yorum:
- İş parçacığı sayısı ve tekrar çalıştırma vektörleri BİT ÖZDEŞ bırakır; gerçek gürültü tabanı aynı kodlar için 0'dır. qa prototipindeki "~2,4e-7", vektör farkı değil float32 nokta çarpımının yuvarlama hatasıdır (bu ölçümde 1,8e-7; neden: float32 toplamı, büyük olasılıkla ||a||^2'nin 1'e tam yuvarlanmaması; ayrıca doğrulanmadı). Bu yüzden eşiği 1e-6'nın altına çekmek anlamsızdır: ölçüm aracı gürültüsü ayrışma sinyalinden büyük olur.
- TSV yazım kaybı (8 anlamlı hane): 1-kosinüs ~4e-16.
- Graf optimizasyonunu (dolayısıyla kuantize çekirdek yolunu) değiştirmek kosinüsü 2e-3'e kadar düşürür. Çıkarım yolu değişirse (ör. ARM çekirdekleri, T-030 .ort dönüşümü) bu büyüklükte fark mümkündür. Cihaz (ARM) tabanı ÖLÇÜLMEDİ.

## Neden 0,9999 (1e-4)

- Aynı platformda aynı hesabın tabanı 0 (en kötü 2e-16, float32 nokta çarpımı gürültüsü 2e-7) olduğundan, budama gibi hesabı değiştirmeyen değişiklikler için 1e-4 sıfırdan çok uzak ve gevşek bir üst sınırdır; asıl kapı yine token METNİ eşitliğidir.
- Kuantize çekirdek yolu farkının büyüklüğü (2e-3, ortalama 8e-4) 1e-4'ten büyüktür; yani eşik bilerek "çekirdek yolu değişmedi" ile "değişti" arasını ayırır. ARM cihaz testi (masaüstü (Apple Silicon arm64) altınına karşı; Android ARM çekirdekleri (dotprod/i8mm varlığı) farklı olabilir) bu nedenle düşebilir: o durumda en kötü kosinüs ve dağılım bu belgeye işlenir ve karar verilir, eşik körlemesine gevşetilmez.
- Sıralama etkisi: iki birim vektör için |a-b| = sqrt(2(1-cos)); 1-cos = 1e-4 iken bir görüntü vektörüyle skor farkı en çok ~0,014. CLIP skorları dar aralıkta olduğundan bu, yakın komşuların sırasını değiştirebilir; bu yüzden kosinüs tek başına yetmez, top-K örtüşmesi ayrıca kapıdır. Tipik fark çok daha küçüktür.
- İşaretli kapı değerleri sıkılaştırılmaz: ORT-format dönüşümü (T-030) için todo'da zaten 0,99999 istenir.

## Budama (T-027) ve sonraki değişiklikler için kapılar

Hepsi ORİJİNAL referansa karşı, aynı 60 sorgu (T-027 ayrıca büyük TR/EN korpus):
1. ASIL KAPI: token METNİ dizisi eşitliği (`GoldenTextTest.tokenTexts_equalGolden_forAll60Queries`). Id'lere bağlı değil; budama id'leri yeniden numaralar (`TokenVocab` metne çevirir). Orijinal sözlükte ek olarak id eşitliği de doğrulanır, budanmışta atlanır.
2. `[UNK]` sayısı ve oranı altın tabanı (2 / 329) aşmaz (`unkCountAndRate_doNotExceedGoldenBaseline`).
3. Kosinüs >= 0,9999 (60 vektör, `GoldenTextVectorsDeviceTest`, cihazda; JVM'de ORT Android olmadığı için masaüstü Python ile de aynı ölçüm betikle yapılabilir). Budama yalnızca gömme satırlarını seçtiği için masaüstünde beklenen kosinüs 1,0'dır.
4. Top-K örtüşmesi: sabit bir görüntü vektörü kümesine karşı orijinal ve değişmiş sorgu vektörlerinin top-10 ve top-50 kümeleri; öneri eşik top-10 >= 0,99 ve top-50 >= 0,98 (T-031'in koşullarıyla aynı). Veri kümesi ve ölçüm T-027'de netleşir; burada ölçülmedi.

## Eski model dosyalarını yedekleme (repo dışı)

Budama/dönüşüm `assets/models/` içindeki dosyaları değiştireceği için, altın yenilemek veya karşılaştırmak gerekirse orijinaller korunmalıdır. Repo DIŞINDA (ör. `~/ModelBackups/ai-galeri-t026/`; git'e eklenmez) yedekleyin; `tools/fetch_models.sh` sabit revizyondan yeniden indirir ama yedek ağsız karşılaştırma sağlar. Geri yükleme sonrası SHA-256 denetlenmelidir:

| Dosya | Boyut (bayt) | SHA-256 |
|---|---|---|
| `text_model_qint8_arm64.onnx` | 135336307 | 3bed77e83926519660b5c0833cb3321cfde952331edec4f7d501c7d384b8a8a7 |
| `text_dense_768x512_f32.bin` | 1572864 | e5f6548cbcef6c62631b3946d89042ab4c8451762c7bb0a3be11eb6c480b686d |
| `text_vocab.txt` | 995526 | fe0fda7c425b48c516fc8f160d594c8022a0808447475c1a7c6d6479763f310c |
| `vision_model_quantized.onnx` | 89117001 | 583fd1110a514667812fee7d684952aaf82a99b959760c8d7dca7e0ab9839299 |

Kaynak ve lisans: `model-setup.md` (metin: sentence-transformers/clip-ViT-B-32-multilingual-v1, Apache-2.0; görüntü: Xenova dönüşümü, OpenAI CLIP MIT çıkarım). Altın dosyalar bu modellerin çıktılarıdır; fotoğraf veya kullanıcı verisi içermez.

## Doğrulanmadı

- ARM cihazda kosinüs (androidTest yazıldı ve derlenmeye çalışıldı, ÇALIŞTIRILMADI: cihaz/emülatör yok).
- Java ve Python Unicode sürüm farkları (sette tetiklenmedi).
- Top-K örtüşme eşiklerinin gerçek galeride yeterliliği.
