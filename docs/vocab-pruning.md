# Metin modeli sözlük budama, Katman A (T-027, K-14)

Karar (Cem, K-14): Türkçe öncelikli, kayıpsız budama. Tüm Latin betikli token'lar, rakamlar ve noktalama/sembol token'ları tutulur; diğer betikler (Kiril, Arapça, CJK, Hangul vb.) atılır. `MODEL_VERSION` DEĞİŞMEZ (metin vektörleri orijinalle bit özdeştir; görüntü indeksi korunur, yeniden indeksleme yok). `WordPieceTokenizer`, `OnnxTextEncoder`, `ModelStore` kodu DEĞİŞMEDİ (tokenizer `[UNK]`/`[CLS]`/`[SEP]`'i dizeyle bulur, id sayısı satır numarasıdır).

## Ne yapıldı

| | Önce | Sonra |
|---|---|---|
| `text_vocab.txt` | 119547 satır, 995526 bayt | 69642 satır, 520398 bayt |
| `text_model_qint8_arm64.onnx` | 135336307 bayt | 97009267 bayt (-38327040 = 49905 satır x 768 bayt, tam) |
| `embeddings.word_embeddings.weight_quantized` | uint8 [119547, 768] | uint8 [69642, 768] |
| Özel token id'leri | `[PAD]` 0, `[UNK]` 100, `[CLS]` 101, `[SEP]` 102, `[MASK]` 103 | 0, 1, 2, 3, 4 (`[unusedN]` atıldı) |

Sayım (tools/prune_text_model.py, `unicodedata` ile): 119547 satırdan 69642 tutuldu (%58,3), 49905 atıldı. Tutulan: 5 özel + 50162 sözcük başı + 19475 `##` devam parçası. Atılan: 99 `[unusedN]` + 49806 Latin dışı betikli veya karışık betikli (Kiril 13808, CJK 13550, Arapça 4723, Hangul 3273, İbranice 2482, Devanagari 1792, Yunanca 1566, Ermenice 1236, Telugu 887, Bengalce 873, Tamilce 832, Hiragana 800, diğerleri). Türkçe harf içeren (`çğıöşüâîûÇĞİÖŞÜÂÎÛ`) 1978 token'ın 1962'si tutuldu; atılan 16'sı Kiril harfiyle karışık (ör. Çuvaşça "çын").

Görev tanımındaki ~69.686 satır hedefi ile fark (-44) sınıflandırma ayrıntısındandır (hedefin hesap ayrıntısı bilinmediği için kalem kalem açıklanamadı; tasarım gereği `[unusedN]` atıldı, tüm betiklerin Nd rakamları tutuldu). Kazanç hedefle uyumludur (-38,33 MB ONNX).

### Katman A kuralı (karakter bazlı; `tools/prune_text_model.py` `keep_token`)

Token (`##` öneki atılarak) tutulur, eğer boş değilse ve her karakteri şunlardan biriyse: Unicode adında "LATIN" geçen harf (Türkçe harfler, â î û, diğer aksanlı Latin harfleri, fullwidth Latin dahil); Nd rakam; ASCII yazdırılabilir (U+0021-007E); Latin-1 noktalama/sembol (U+00A1-00BF, ×, ÷); modifier harfler (U+02B0-02FF); birleştirici işaretler (U+0300-036F); fonetik ek (U+1D00-1DFF); genel noktalama, simge, para birimi, ok, matematik, şekil (U+2000-2BFF). Beş özel token tutulur; diğer `[...]` token'lar (`[unusedN]`) atılır (köşeli parantez noktalama olduğu için sözcük parçası olarak hiç eşleşmezler).

### Neden Latin için YAPISAL OLARAK kayıpsız

WordPiece her konumda, sözcüğün o konumdan başlayan alt dizgesine uyan EN UZUN sözlük girdisini seçer. Kural karakter bazlı olduğundan, yalnızca tutulan sınıftan karakterler içeren bir sözcük için uyabilecek her token da yalnızca o karakterlerden oluşur, yani tutulur; seçim ve parçalama değişmez. Bu yüzden sonuç sözlüğe veya korpusa bağlı değildir, korpus gerekmez. Kayıp yalnızca tutulmayan karakter içeren sözcüklerde olur (Kiril, Arapça, CJK vb. sözcükler [UNK]; karışık betikli, ör. Latin+Kiril, sözcükler de). Bu K-14'te kabul edilen maliyettir.

## Yeniden üretme

```
cd mobile
tools/fetch_models.sh                        # orijinal -> .model-cache (SHA), budama -> assets/models (yeni SHA), idempotent
python3 -I tools/test_prune_text_model.py    # budama aracı birim testleri (sentetik ONNX, ağsız, gerçek model gerekmez)
tools/test_fetch_models.sh                   # kabuk testi (sahte curl/python3/sha256sum)
~/venvs/aigaleri-ort/bin/python -I tools/verify_pruned_text.py   # kalite kapıları a-e (numpy + onnxruntime 1.30.0), ~30 sn
```

`prune_text_model.py` yalnızca Python standart kütüphanesi kullanır, `python3 -I` ile çalışır, ağa çıkmaz, yalnızca kendi geçici dosyalarını (`*.tmp-<pid>`) siler, çıktıyı geçici dosya + `os.replace` ile atomik yazar, orijinal klasöre yazmayı reddeder. Çıktı deterministiktir (iki çalıştırma aynı SHA-256). Kaynak Python 3.13.7 `unicodedata`; sınıflandırma sonucu farklı sürümde sapabilir, bu durumda araç tutulan satır sayısı uyuşmazlığı ve `fetch_models.sh` SHA-256 doğrulaması ile durur (dosyalar silinir). Tepe bellek ~0,85 GB, süre ~1 sn (geliştirici makinesi, yalnızca derleme öncesi).

## Kalite kapıları (hepsi geçti)

Ölçüm: masaüstü (Apple Silicon, Python 3.13.7, numpy 2.5.4, onnxruntime 1.30.0, CPUExecutionProvider, graf optimizasyonu varsayılan ENABLE_ALL, 1 iş parçacığı, `golden_text.py` ile aynı oturum seçenekleri). Çıktı: `tools/verify_pruned_text.py`. JVM karşılığı: `PrunedVocabEquivalenceTest`, `GoldenTextTest`.

| Kapı | Ölçüt | Sonuç |
|---|---|---|
| (a) token METNİ eşitliği, T-026 altın seti | 76 sorgu, budanmış tokenizer çıktısının token METNİ orijinalinkine eşit; id eşlemesi (eski -> yeni) tutarlı | 76/76 eşit. JVM'de gerçek `WordPieceTokenizer` ile de (`GoldenTextTest.tokenTexts_equalGolden_forAllGoldenQueries`, atlanmadı) |
| (b) büyük sentetik TR+EN küme (sabit seed 20261013) | token METNİ eşit, id eşlemesi tutarlı, `[UNK]` sayısı aynı | Python: 425040 metin (sözlükten gövde+ek 20000 sözcük x ham/normalize, Türkçe gövde+ek 8000 x 5 yazım (ham, BÜYÜK, Başlık, aksansız, aksansız BÜYÜK), İngilizce, sözcük+rakam+noktalama 3000, 10000 rastgele cümle, tutulan 69637 token'ın tek tek ve gövdeye eklenmiş taraması), 1667748 token, 0 ayrışma, `[UNK]` 404 = 404. JVM: 212428 ham + 212428 normalize metin, 1657345 token, 0 ayrışma, `[UNK]` 384 = 384 (ham), 56 = 56 (normalize) |
| (b+) Latin kayıpsızlığı, JVM'in kendi Unicode betik veritabanıyla bağımsız çapraz kontrol | Java `Character.UnicodeScript.LATIN` harf, ASCII rakam/noktalama içeren her orijinal token tutulmuş | 0 kayıp (`everyLatinScriptToken_ofOriginalVocab_isKept_acrossJvmUnicodeScripts`) |
| (b-) olumsuz kontrol | Daha agresif bir budama (ASCII + Türkçe) aynı karşılaştırmada yakalanıyor | yakalandı (â î û é ñ ø ł içeren sözcükler ayrışır) |
| (c) vektör kosinüsü | 76 altın sorgu, budanmış model vs `golden_text_vectors.tsv` (orijinal), eşik >= 0,9999 | min = ortalama = 1,0000000000 (TSV 8 haneli); orijinalle aynı oturumda yeniden hesaplanan: min = ortalama = 1,000000000000, 76/76 BİT ÖZDEŞ, maks fark 0 |
| (d) Türkçe kapısı | 11 `turkce_ozel` sorgusu (â î û Ğ Ü Ş Ö Ç İ ı): token METNİ eşit, `[UNK]` yok, kosinüs >= 0,9999 | 11/11 geçti, min kosinüs 1,000000000000 |
| (e) top-K örtüşmesi | 10000 sentetik görüntü vektörü (sabit seed, kümelenmiş, bir kısmı sorgulara yakın); 76 altın + 424 sentetik cümle (500 sorgu); orijinal ve budanmış sorgu vektörleri; top-10 >= 0,99, top-50 >= 0,98 | top-10 min/ort 1,0000/1,0000; top-50 min/ort 1,0000/1,0000 (vektörler bit özdeş olduğundan beklenen) |
| `[UNK]` oranı | altın set: 5/411 (T-026 tabanı) | 5/411 (artmadı); `GoldenTextTest.unkCountAndRate_doNotExceedGoldenBaseline` atlanmadı |

Altın yenileme ayrıca doğrulandı: `golden_text.py` (orijinal önbellekle) `golden_queries.tsv` ve `golden_text_vectors.tsv`'yi bayt bayt aynı yeniden üretti.

Bilgi (kapı değil): atılan 3000 Latin dışı sözcük token'ının hepsi budanmış tokenizer'da `[UNK]` olur (orijinalde tek token). Türkçe sorgu davranışı etkilenmez; Latin dışı bir sorgu (ör. Rusça "кошка") artık anlamsız vektör üretir. Ürün kapsamı Türkçe ve İngilizce olduğundan kabul edildi (K-14).

### Neden kosinüs 1,0

Budama yalnızca gömme satırlarını seçer, graf, ölçek, sıfır noktası ve diğer ağırlıklar aynıdır. Gather çıktısı (uint8 satır) bit özdeştir, dolayısıyla sonraki her işlem de aynı kodları görür. Cihazda (ARM çekirdekleri) orijinalle budanmış arasında da aynı beklenir; cihazda ölçülmedi (aşağıda).

## Boyut kazancı (ölçüldü)

| | Önce | Sonra | Fark |
|---|---|---|---|
| Metin ONNX | 135336307 B | 97009267 B | -38,33 MB |
| Sözlük | 995526 B | 520398 B | -0,48 MB |
| Debug APK (4 ABI) | 375,3 MB | 336,4 MB | -38,9 MB |
| Release APK (arm64, R8) | 261,0 MB | 222,5 MB | -38,5 MB |

Not: Gradle, mevcut debug APK'yı yerinde güncellediğinde eski girdinin baytları zip içinde kullanılmayan alan olarak kalabilir: model değişikliğinden sonraki ilk `assembleDebug` 375,3 MB dosya verdi (girdi listesinde yeni modeller vardı); `app-debug.apk` silinip yeniden derlenince 336,4 MB (zip girdilerinin sıkıştırılmış toplamı 336,3 MB). Kazanç bu temiz derlemeyle ölçüldü. Cihazda `filesDir/models` kopyası da 38,3 MB küçülür (ilk açılışta tek seferlik kopya, ModelStore). Mevcut kurulumlarda eski (135 MB) kopya boyutu farklı olduğundan `ModelStore` tarafından geçersiz sayılıp yeniden kopyalanır (kod değişmedi). Sözlüğün bellek haritası ve oturum açılış süresi etkisi cihazda ölçülmedi.

## Lisans

Apache-2.0 türev ("değiştirilmiş model"): bildirim ve gerekçe `model-setup.md` "Değiştirilmiş model (T-027)" ve Lisanslar önerisi 3. madde. Model dosyalarının kendisi git'e girmez; `.gitignore` `app/src/main/assets/models/` ve `.model-cache/` kapsar.

## Doğrulanamayanlar

- Cihazda (Android ARM, ORT AAR) çıkarım: androidTest (`GoldenTextVectorsDeviceTest`, `OnnxTextEncoderDeviceTest`, `SearchEndToEndDeviceTest`) güncel ve derlendi, ÇALIŞTIRILMADI (cihaz/emülatör yok, T-012). Bit özdeşlik masaüstü ORT içindir.
- Budamanın oturum açılış süresi/bellek etkisi (ORT dosyayı mmap mi eder: bilinmiyor) ölçülmedi.
- Python `unicodedata` sürüm farkı (yalnızca sınıflandırma; sapma SHA-256 doğrulamasıyla yakalanır).
- Latin dışı betik kullanıcıları için kalite (kapsam dışı, K-14).
