# Metin modeli sözlük budama, Katman A (T-027, K-14)

Karar (Cem, K-14): Türkçe öncelikli, kayıpsız budama. Tüm Latin betikli token'lar, rakamlar ve TÜM noktalama/sembol token'ları (Unicode kategorisi P* ve S*; tam genişlikli ve CJK/Arapça/Ermenice/Devanagari noktalaması dahil) tutulur; atılan yalnızca Latin dışı HARFLİ token'lardır (Kiril, Arapça, CJK, Hangul vb. harf veya o betiklerin işaretlerini içeren) ve `[unusedN]`. `MODEL_VERSION` DEĞİŞMEZ (metin vektörleri orijinalle bit özdeştir; görüntü indeksi korunur, yeniden indeksleme yok). `WordPieceTokenizer`, `OnnxTextEncoder`, `ModelStore` kodu DEĞİŞMEDİ (tokenizer `[UNK]`/`[CLS]`/`[SEP]`'i dizeyle bulur, id sayısı satır numarasıdır).

## Ne yapıldı

| | Önce | Sonra |
|---|---|---|
| `text_vocab.txt` | 119547 satır, 995526 bayt, SHA-256 fe0fda7c...310c | 69830 satır, 521296 bayt, SHA-256 df01274b364c59156c453aa006ecf4fbf93b5fabe235375a1bb07f760e97e825 |
| `text_model_qint8_arm64.onnx` | 135336307 bayt, SHA-256 3bed77e8...a8a7 | 97153651 bayt (-38182656 = 49717 satır x 768 bayt, tam), SHA-256 0dc4e4acba0cbdc6102a305253101a1a9c0633c13496fa0686245b5dd938dbf7 |
| `embeddings.word_embeddings.weight_quantized` | uint8 [119547, 768] | uint8 [69830, 768] |
| Özel token id'leri | `[PAD]` 0, `[UNK]` 100, `[CLS]` 101, `[SEP]` 102, `[MASK]` 103 | 0, 1, 2, 3, 4 (`[unusedN]` atıldı) |

Sayım (tools/prune_text_model.py, `unicodedata` ile): 119547 satırdan 69830 tutuldu (%58,4), 49717 atıldı. Atılan: 99 `[unusedN]` + 49618 Latin dışı harfli veya karışık betikli token (Kiril 13808, CJK 13550, Arapça ~4700, Hangul 3273, İbranice ~2480, Devanagari ~1790, Yunanca ~1570, Ermenice ~1240, Telugu, Bengalce, Tamilce, Hiragana, diğerleri; ayrıca bu betiklerin tek başına duran işaretleri, ör. Arapça harekeler, İbranice noktalar). Türkçe harf içeren (`çğıöşüâîûÇĞİÖŞÜÂÎÛ`) 1978 token'ın 1962'si tutuldu; atılan 16'sı Kiril harfiyle karışık (ör. Çuvaşça "çын").

İlk sürümde (commit 6ece844) kural tam genişlikli/CJK/Arapça/Ermenice/Devanagari noktalamasını atıyordu (188 token; BERT bunları tek başına sözcük yapar, orijinalde 1 token iken budanmışta [UNK] olurdu). Reviewer bulgusu üzerine kural "tüm P* ve S*" olarak düzeltildi: tutulan 69642 -> 69830, ONNX 97009267 -> 97153651 bayt (+0,14 MB). Görev tanımındaki ~69.686 satır hedefi yaklaşık bir sayıydı; kesin sayı kuralın çıktısıdır (69830). `[unusedN]` (99 satır) tasarım gereği atılır: köşeli parantez noktalama olduğundan sözcük parçası olarak hiç eşleşmezler.

### Katman A kuralı (karakter bazlı; `tools/prune_text_model.py` `keep_token`)

Token (`##` öneki atılarak) tutulur, eğer boş değilse ve her karakteri şunlardan biriyse: Unicode adında "LATIN" geçen harf (Türkçe harfler, â î û, diğer aksanlı Latin harfleri, fullwidth Latin dahil); Nd rakam; HERHANGİ bir noktalama veya sembol (kategori P* ve S*); ayrıca ASCII yazdırılabilir (U+0021-007E), Latin-1 noktalama/sembol (U+00A1-00BF, ×, ÷), modifier harfler (U+02B0-02FF), birleştirici işaretler (U+0300-036F), fonetik ek (U+1D00-1DFF) ve genel noktalama, simge, para birimi, ok, matematik, şekil (U+2000-2BFF) blokları. Aynı kural JVM testinde (`PrunedVocabEquivalenceTest`, Java Unicode veritabanı) bağımsız olarak yeniden uygulanır ve çıktısı ModelManifest boyutu/SHA-256'sı ile birebir eşit olmak zorundadır. Beş özel token tutulur; diğer `[...]` token'lar (`[unusedN]`) atılır (köşeli parantez noktalama olduğu için sözcük parçası olarak hiç eşleşmezler).

### Neden Latin için YAPISAL OLARAK kayıpsız

WordPiece her konumda, sözcüğün o konumdan başlayan alt dizgesine uyan EN UZUN sözlük girdisini seçer. Kural karakter bazlı olduğundan, yalnızca tutulan sınıftan karakterler içeren bir sözcük için uyabilecek her token da yalnızca o karakterlerden oluşur, yani tutulur; seçim ve parçalama değişmez. Bu yüzden sonuç sözlüğe veya korpusa bağlı değildir, korpus gerekmez. Noktalama için de geçerlidir: BERT noktalamayı tek başına sözcük yapar; tüm P*/S* tutulduğu için orijinalde 1 token olan bir karakter budanmışta [UNK] olmaz. Kayıp yalnızca tutulmayan karakter (Latin dışı harf) içeren sözcüklerde olur (Kiril, Arapça, CJK vb. sözcükler [UNK]; karışık betikli, ör. Latin+Kiril, sözcükler de). Bu K-14'te kabul edilen maliyettir.

## Yeniden üretme

```
cd mobile
tools/fetch_models.sh                        # orijinal -> .model-cache (SHA), budama -> assets/models (yeni SHA), idempotent
python3 -I tools/test_prune_text_model.py    # budama aracı birim testleri (sentetik ONNX, ağsız, gerçek model gerekmez)
tools/test_fetch_models.sh                   # kabuk testi (sahte curl/python3/sha256sum)
~/venvs/aigaleri-ort/bin/python -I tools/verify_pruned_text.py   # kalite kapıları a-e (numpy + onnxruntime 1.30.0), ~30 sn
```

`prune_text_model.py` yalnızca Python standart kütüphanesi kullanır, `python3 -I` ile çalışır, ağa çıkmaz, yalnızca kendi dosyalarını siler (geçici `*.tmp-<pid>` ve çıktı hedefleri), önce iki geçici dosya yazıp sonra iki `os.replace` yapar (ikincisi başarısız olursa uyumsuz çift bırakılmaz, hedefler silinir), çıktı SHA-256'larını YAZMADAN önce kendisi doğrular (uyuşmazlık: "HATA:" mesajı, çıkış kodu 1, hiçbir şey yazılmaz), geçersiz UTF-8 sözlük için de temiz hata verir, orijinal klasöre yazmayı reddeder. Çıktı deterministiktir: Python 3.13.7 (Unicode 15.1) ve 3.9.6 (Unicode 13) ile aynı SHA-256 çıktı (vocab karakterleri bu sürümlerde aynı sınıflanıyor); yine de bir sürüm sapması olursa araç tutulan satır sayısı/SHA-256 uyuşmazlığıyla durur. Tepe bellek ~0,85 GB, süre ~1 sn (geliştirici makinesi, yalnızca derleme öncesi).

Hangi kontrol nerede çalışır: `PrunedVocabEquivalenceTest` (JVM, `testDebugUnitTest`) model dosyası, `.model-cache` veya assets GEREKTİRMEZ ve ATLANMAZ; orijinal sözlük metni `app/src/test/resources/ml/original_vocab.txt.gz` olarak depoda durur (546 KB gzip; distilbert-base-multilingual-cased `vocab.txt`, Apache-2.0 sözlük metni, model ağırlığı DEĞİL; SHA-256'sı orijinalle doğrulanır). `verify_pruned_text.py` (ONNX kosinüsü ve top-K) ise YALNIZCA geliştirici makinesinde (ort_venv + orijinal ONNX gerekir) çalışır; CI/temiz klonda çalışmaz, kosinüs/top-K kanıtı bu belgedeki ölçüm tablosudur.

## Kalite kapıları (hepsi geçti)

Ölçüm (düzeltme sonrası, 69830 satır, yeniden koşuldu): masaüstü (Apple Silicon, Python 3.13.7, numpy 2.5.4, onnxruntime 1.30.0, CPUExecutionProvider, graf optimizasyonu varsayılan ENABLE_ALL, 1 iş parçacığı, `golden_text.py` ile aynı oturum seçenekleri). Çıktı: `tools/verify_pruned_text.py`. JVM karşılığı: `PrunedVocabEquivalenceTest`, `GoldenTextTest`.

| Kapı | Ölçüt | Sonuç |
|---|---|---|
| (a) token METNİ eşitliği, T-026 altın seti | 76 sorgu, budanmış tokenizer çıktısının token METNİ orijinalinkine eşit; id eşlemesi (eski -> yeni) tutarlı | 76/76 eşit. JVM'de gerçek `WordPieceTokenizer` ile de (`GoldenTextTest.tokenTexts_equalGolden_forAllGoldenQueries`, atlanmadı) |
| (b) büyük sentetik TR+EN küme (sabit seed 20261013) | token METNİ eşit, id eşlemesi tutarlı, `[UNK]` sayısı aynı | Python: 425792 metin (sözlükten gövde+ek 20000 sözcük x ham/normalize, Türkçe gövde+ek 8000 x 5 yazım (ham, BÜYÜK, Başlık, aksansız, aksansız BÜYÜK), İngilizce, sözcük+rakam+noktalama 3000, 10000 rastgele cümle, tutulan 69825 token'ın tek tek ve gövdeye eklenmiş taraması), 1669440 token, 0 ayrışma, `[UNK]` 404 = 404. JVM (depodaki orijinal sözlükle, atlanmaz): 212804 ham + 212804 normalize metin, 1659037 token, 0 ayrışma, `[UNK]` 384 = 384 (ham), 56 = 56 (normalize) |
| (b+) Latin kayıpsızlığı, JVM'in kendi Unicode betik veritabanıyla bağımsız çapraz kontrol | Java `Character.UnicodeScript.LATIN` harf, ASCII rakam/noktalama içeren her orijinal token tutulmuş | 0 kayıp (`everyLatinScriptToken_ofOriginalVocab_isKept_acrossJvmUnicodeScripts`) |
| (b++) noktalama/sembol | orijinaldeki her salt-P*/S* token (tam genişlikli ！（）？，：；＠, CJK 、。「」《》, Arapça ، ؟, Devanagari ।, Ermenice ՚ ։ dahil) tutuldu; tek karakterli olanlar tek başına aynı token'a çözülür, [UNK] olmaz | geçti (`everyPunctuationAndSymbolToken_isKept_andAloneTokenizesIdentically`, JVM) |
| (b+++) sözlük yeniden hesabı | Katman A kuralının Kotlin uygulaması, orijinal sözlükten ModelManifest boyutu/SHA-256'sı ile birebir aynı sözlüğü üretir; assets sözlüğü varsa satır satır eşit | geçti (`recomputedPrunedVocab_equalsManifest_andShippedAssetVocabLineByLine`, JVM) |
| (a+) altın set, JVM, orijinal ve budanmış sözlük | 76 sorgu token METNİ = altın, `[UNK]` 5 = 5 | geçti (`goldenSet_tokenTexts_equalForOriginalAndPrunedVocab`, JVM, atlanmaz) |
| (b-) olumsuz kontrol | Daha agresif bir budama (ASCII + Türkçe) aynı karşılaştırmada yakalanıyor | yakalandı (â î û é ñ ø ł içeren sözcükler ayrışır) |
| (c) vektör kosinüsü | 76 altın sorgu, budanmış model vs `golden_text_vectors.tsv` (orijinal), eşik >= 0,9999 | min = ortalama = 1,0000000000 (TSV 8 haneli); orijinalle aynı oturumda yeniden hesaplanan: min = ortalama = 1,000000000000, 76/76 BİT ÖZDEŞ, maks fark 0 |
| (d) Türkçe kapısı | 11 `turkce_ozel` sorgusu (â î û Ğ Ü Ş Ö Ç İ ı): token METNİ eşit, `[UNK]` yok, kosinüs >= 0,9999 | 11/11 geçti, min kosinüs 1,000000000000 |
| (e) top-K örtüşmesi | 10000 sentetik görüntü vektörü (sabit seed, kümelenmiş, bir kısmı sorgulara yakın); 76 altın + 424 sentetik cümle (500 sorgu); orijinal ve budanmış sorgu vektörleri; top-10 >= 0,99, top-50 >= 0,98 | top-10 min/ort 1,0000/1,0000; top-50 min/ort 1,0000/1,0000 (vektörler bit özdeş olduğundan beklenen) |
| `[UNK]` oranı | altın set: 5/411 (T-026 tabanı) | 5/411 (artmadı); `GoldenTextTest.unkCountAndRate_doNotExceedGoldenBaseline` atlanmadı |

Altın yenileme ayrıca doğrulandı: `golden_text.py` (orijinal önbellekle) `golden_queries.tsv` ve `golden_text_vectors.tsv`'yi bayt bayt aynı yeniden üretti.

Kabul edilen davranış (kapı değil; `nonLatinInput_becomesUnk_acceptedBehaviour` ve `RealModelAssetsTest`): Latin dışı harfli sözcükler budanmış tokenizer'da `[UNK]` olur (orijinalde tek token); atılan 3000 Latin dışı sözcük token'ının hepsi böyle. Türkçe sorgu davranışı etkilenmez; Latin dışı bir sorgu (ör. Rusça "кошка") artık anlamsız vektör üretir. Ürün kapsamı Türkçe ve İngilizce olduğundan kabul edildi (K-14).

### Neden kosinüs 1,0

Budama yalnızca gömme satırlarını seçer, graf, ölçek, sıfır noktası ve diğer ağırlıklar aynıdır. Gather çıktısı (uint8 satır) bit özdeştir, dolayısıyla sonraki her işlem de aynı kodları görür. Cihazda (ARM çekirdekleri) orijinalle budanmış arasında da aynı beklenir; cihazda ölçülmedi (aşağıda).

## Boyut kazancı (ölçüldü)

| | Önce | Sonra | Fark |
|---|---|---|---|
| Metin ONNX | 135336307 B | 97153651 B | -38,18 MB |
| Sözlük | 995526 B | 521296 B | -0,47 MB |
| Debug APK (4 ABI) | 375,3 MB | 336,5 MB (336546813 B) | -38,8 MB |
| Release APK (arm64, R8) | 261,0 MB | 222,6 MB (222602295 B) | -38,4 MB |

Not: Gradle, mevcut debug APK'yı yerinde güncellediğinde eski girdinin baytları zip içinde kullanılmayan alan olarak kalabilir (model değişikliğinden sonraki ilk `assembleDebug` 375,3 MB dosya verdi); ölçümler `app-debug.apk` silinip yeniden derlenerek alındı. Cihazda `filesDir/models` kopyası da 38,2 MB küçülür (ilk açılışta tek seferlik kopya, ModelStore). Mevcut kurulumlarda eski (135 MB) kopya boyutu farklı olduğundan `ModelStore` tarafından geçersiz sayılıp yeniden kopyalanır (kod değişmedi). Sözlüğün bellek haritası ve oturum açılış süresi etkisi cihazda ölçülmedi.

## Lisans

Apache-2.0 türev ("değiştirilmiş model"): bildirim ve gerekçe `model-setup.md` "Değiştirilmiş model (T-027)" ve Lisanslar önerisi 3. madde. Model dosyalarının kendisi git'e girmez; `.gitignore` `app/src/main/assets/models/` ve `.model-cache/` kapsar.

## Doğrulanamayanlar

- Cihazda (Android ARM, ORT AAR) çıkarım: androidTest (`GoldenTextVectorsDeviceTest`, `OnnxTextEncoderDeviceTest`, `SearchEndToEndDeviceTest`) güncel ve derlendi, ÇALIŞTIRILMADI (cihaz/emülatör yok, T-012). Bit özdeşlik masaüstü ORT içindir.
- Budamanın oturum açılış süresi/bellek etkisi (ORT dosyayı mmap mi eder: bilinmiyor) ölçülmedi.
- Python `unicodedata` sürüm farkı: 3.9.6 ve 3.13.7 ile aynı çıktı doğrulandı, diğer sürümler denenmedi (sapma SHA-256 doğrulamasıyla yakalanır).
- Latin dışı betik kullanıcıları için kalite (kapsam dışı, K-14).
