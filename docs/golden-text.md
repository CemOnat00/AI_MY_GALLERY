# Altın metin vektörleri (T-026)

Model değişikliklerinden (T-027 sözlük budama, T-028 mmap, T-030 .ort) ÖNCE alınan referans: 76 sorgu için ORİJİNAL metin modeliyle token METNİ, token id'leri ve 512 boyutlu L2 normalize vektör. Sonraki değişiklikler bu referansa karşı ölçülür. Yalnızca geliştirici aracıdır; uygulamaya ağ, izin veya bağımlılık eklemez, APK'ya girmez.

## Dosyalar

| Dosya | İçerik |
|---|---|
| `tools/golden_text.py` | Üretici (python3 + numpy + onnxruntime 1.30.0). Yalnızca geliştirici makinesinde çalışır. |
| `tools/golden_text_queries.tsv` | Girdi: kategori, sorgu (76 satır: nesne 8, sahne 6, renk 5, eylem 6, kişi sayısı 5, mekân 7, ekli 5, ekli olmayan 3, sayı 4, aksan/büyük-küçük 5, emoji 2, İngilizce 4, `turkce_ozel` 11: â î û ve büyük Ğ Ü Ş Ö Ç İ içeren doğal sorgular, `unicode` 5: NFD ayrışık aksan, ZWJ ve NBSP). Görünmez/ayrışık karakterler girdide ve çıktıda `\uXXXX` kaçışlıdır (Kotlin `GoldenText.unescape` çözer). |
| `app/src/test/resources/ml/golden_queries.tsv` | Çıktı: sorgu, kategori, token METİNLERİ (boşlukla), id'ler (boşlukla), sekmeyle ayrılmış, başlıksız. ~8 KB. |
| `app/src/test/resources/ml/golden_text_vectors.tsv` | Çıktı: sorgu, 512 float (`%.8g`), ORİJİNAL model, 1 iş parçacığı. ~504 KB (toplam < 1 MB). |
| `GoldenTextSupport.kt`, `GoldenTextTest.kt` (JVM), `GoldenTextVectorsDeviceTest.kt` (androidTest) | Testler; `TokenVocab` id <-> metin yardımcısı. |

## Kurulum ve çalıştırma (yalnızca geliştirici makinesi)

```
python3 -m venv ~/venvs/aigaleri-ort        # repo DIŞINDA bir yer
~/venvs/aigaleri-ort/bin/pip install numpy onnxruntime==1.30.0   # ağ yalnızca bu kurulum için; isteğe bağlı: tokenizers (çapraz kontrol)
tools/fetch_models.sh --text-only                           # orijinaller .model-cache/'e (SHA doğrulamalı) + budanmış sürüm assets'e (T-027)
~/venvs/aigaleri-ort/bin/python -I tools/golden_text.py           # altın dosyaları üretir (geçici dosya + os.replace ile atomik)
~/venvs/aigaleri-ort/bin/python -I tools/golden_text.py --noise   # gürültü tabanı; dosya yazmaz
```

Altın yenileme yalnızca AYNI platform + onnxruntime 1.30.0 ile bit bit tekrarlanır beklenir; başka platformda (ör. farklı CPU/ORT derlemesi) farkın gürültü tabanı olup olmadığına `--noise` ile ve kosinüsle bakın, bit eşitliği aramayın.

Betik şunları DOĞRULAR ve biri tutmazsa dosya yazmadan çıkar (kod 1; eksik/bozuk girdi ve referans dosyaları da `die()` ile temiz çıkış): (1) metin ONNX ve sözlük ORİJİNAL dosyalardır (budanmış/değiştirilmiş model ile altın üretilmez). T-027 sonrası `assets/models/` budanmıştır; bu yüzden `.model-cache/` (veya `MODEL_CACHE`) VARSA orijinaller oradan okunur ve `fetch_models.sh`'taki ORİJİNAL SHA-256 ile doğrulanır; yoksa eski davranış: `assets/models/` dosyalarının boyutu ve SHA-256'sı `ml/ModelManifest.kt` ile eşit olmalı (budanmış manifest + budanmış dosya ile sözlük satır sayısı kapısında durur). `text_dense_768x512_f32.bin` (değişmedi) her durumda `assets/models/`'ten ve manifest ile doğrulanır. Altın yenileme budama sonrası da bayt bayt aynı dosyaları üretti (T-027'de doğrulandı); (2) Python tokenizer, `tokenizer_reference.tsv`'nin tüm satırlarını birebir üretir.

## Tokenizasyon referansı ve ayrışma

Üretimdeki yol: `QueryPreprocessor.normalize` (kontrol/biçim karakterlerini at, boşlukları indir, Türkçe küçük harf) sonra `WordPieceTokenizer.encode`. Betik bu ikisinin birebir Python portunu içerir (Kotlin davranışı referanstır; `unicodedata` kategorileri Java `Character.getType` karşılıklarıdır). Sonuç: `tokenizer_reference.tsv` 18/18 satır eşit; 76 normalize sorguda HF `tokenizers` 0.23.3 (BertNormalizer, lowercase=False, strip_accents=False) ile 0 ayrışma. Bilinen, bu sette TETİKLENMEYEN farklar (portta kasıtlı taklit edildi, doğrulanmadı): Python `str.lower()` ile Java `lowercase(tr)` özel harflerde (ör. `İ`) ayrışır; `tr_lower` `I`, `İ`, `I`+U+0307 için Java kuralını uygular, kalan harfler varsayılan Unicode kuralıdır. Python `unicodedata` ile Java'nın Unicode sürümü farklıysa nadir karakterlerin kategorisi ayrışabilir (sette yok). Sonuç: bu sette ayrışma YOK.

## [UNK] tabanı

411 içerik token'ından 5'i `[UNK]` (oran 0,0122): `🐱 kedi`, `plaj 🏖️ tatil` ve `aile 👨‍👩‍👧 piknik` (emoji sözlükte yok; ZWJ atılınca üç emoji tek ardışık `[UNK]`), ayrıca NFD girdili `c\u0327ocuk plajda gu\u0308len` ve `ka\u0302g\u0306ıt` (birleştirici işaretler Mn olduğu için atılmaz, `QueryPreprocessor` NFC'ye çevirmez; parça yok, `[UNK]`). `turkce_ozel` sorgularının hiçbirinde `[UNK]` yok. Not (ürün gözlemi, kod değişmedi): ayrışık aksanlı girdi kalite düşürür; Android klavyeleri normalde NFC üretir. Test bu sayıyı ve oranı sabitler (`GoldenText.BASELINE_*`); budama sonrası ne sayı ne oran artabilir.

## Kosinüs gürültü tabanı (masaüstü, onnxruntime 1.30.0, Apple Silicon (arm64) Mac, ORT CPUExecutionProvider, 76 sorgu)

`tools/golden_text.py --noise` çıktısı (kosinüs ölçümü float64 ve float32 nokta çarpımı; son işlem EmbeddingMath ile aynı sırada):

| Karşılaştırma | 1-kosinüs (f64, en kötü) | 1-kosinüs (f32 nokta çarpımı) | en büyük \|fark\| | bit özdeş |
|---|---|---|---|---|
| 1 iş parçacığı, tekrar (aynı oturum) | 2,2e-16 | 1,8e-7 | 0 | 76/76 |
| 1 vs 2 iş parçacığı | 2,2e-16 | 1,8e-7 | 0 | 76/76 |
| 2 iş parçacığı, tekrar | 2,2e-16 | 1,8e-7 | 0 | 76/76 |
| ENABLE_ALL vs ENABLE_EXTENDED | 2,2e-16 | 1,8e-7 | 0 | 76/76 |
| ENABLE_ALL vs ENABLE_BASIC | 2,2e-3 | 2,2e-3 | 1,8e-2 | 0/76 |
| ENABLE_ALL vs DISABLE_ALL | 2,2e-3 | 2,2e-3 | 1,8e-2 | 0/76 |

(60 sorguluk ilk ölçümde BASIC/DISABLE_ALL farkının ortalaması 8,4e-4 idi; tablo 76 sorguluk son `--noise` çıktısıdır.) ORT varsayılanı ENABLE_ALL'dür ve ENABLE_EXTENDED ile aynı sonucu verdi.

Yorum:
- 2,2e-16 değeri model farkı değil float64 yuvarlama sınırıdır: aynı iki float32 vektörün float64 kosinüsü bile 1'e tam eşit çıkmaz (makine epsilon'u), yani ölçüm aracının tabanıdır; bu satırlarda vektörler bit özdeştir (`maks|fark|` = 0).
- İş parçacığı sayısı ve tekrar çalıştırma vektörleri BİT ÖZDEŞ bırakır; gerçek gürültü tabanı aynı kodlar için 0'dır. qa prototipindeki "~2,4e-7", vektör farkı değil float32 nokta çarpımının yuvarlama hatasıdır (bu ölçümde 1,8e-7; neden: float32 toplamı, büyük olasılıkla ||a||^2'nin 1'e tam yuvarlanmaması; ayrıca doğrulanmadı). Bu yüzden eşiği 1e-6'nın altına çekmek anlamsızdır: ölçüm aracı gürültüsü ayrışma sinyalinden büyük olur.
- TSV yazım kaybı (8 anlamlı hane): 1-kosinüs ~4e-16.
- Graf optimizasyonunu (dolayısıyla kuantize çekirdek yolunu) değiştirmek kosinüsü 2e-3'e kadar düşürür. Çıkarım yolu değişirse (ör. ARM çekirdekleri, T-030 .ort dönüşümü) bu büyüklükte fark mümkündür. Cihaz (ARM) tabanı T-035'te ölçüldü: aşağıdaki "Cihaz ölçümü" bölümü; gürültü bandı bu 2,2e-3 ile aynı büyüklüktedir.
- T-030 RİSKİ (T-035'te güncellendi): BASIC/kapalıyken ölçülen 2,2e-3 fark hem 0,9999 hem T-030'un 0,99999 kapısını aşar ve ARM cihazdaki çekirdek-farkı gürültüsüyle (min kosinüs 0,99775 => 1-kosinüs 2,25e-3) AYNI büyüklüktedir. Sonuç: cihazda altına karşı 0,9999 kapısı anlamsızdı (budamadan bağımsız düşer) ve cihaz kapısı bir optimizasyon-düzeyi/dönüşüm regresyonunu AYIRT EDEMEZ: ARM cihazda "ENABLE_ALL ile BASIC" farkı gürültü bandının içinde kalır. T-030 regresyon kapısı bu yüzden cihaz-altın kosinüsüyle değil, aşağıdaki "orijinal modelle AYNI cihazda" karşılaştırmasıyla (veya masaüstünde bit özdeşlikle) kurulmalıdır. `OnnxTextEncoder.kt:113-117` graf optimizasyon düzeyini AÇIKÇA ayarlamaz (yalnızca iş parçacığı, bellek deseni ve arena; düzey ORT varsayılanı ENABLE_ALL). Bir .ort dönüşümü veya optimizasyon düzeyi değişikliği kuantize çekirdek yolunu değiştirebilir. ÖN KOŞUL: dönüşüm, ENABLE_ALL ile eşdeğer optimizasyon düzeyinde yapılmalı (çevrimdışı .ort'ta ORT çalışma zamanındaki aynı düzeyi uygulayacak biçimde, T-030'da doğrulanacak). Kapı düşerse bu bir REGRESYONDUR; eşik gevşetilmez.

## Neden 0,9999 (1e-4)

- Aynı platformda aynı hesabın tabanı 0 (en kötü 2e-16, float32 nokta çarpımı gürültüsü 2e-7) olduğundan, budama gibi hesabı değiştirmeyen değişiklikler için 1e-4 sıfırdan çok uzak ve gevşek bir üst sınırdır; asıl kapı yine token METNİ eşitliğidir.
- Kuantize çekirdek yolu farkının büyüklüğü (2e-3, ortalama 8e-4) 1e-4'ten büyüktür; yani eşik bilerek "çekirdek yolu değişmedi" ile "değişti" arasını ayırır. ARM cihaz testi (masaüstü altınına karşı, farklı çekirdek) bu nedenle 0,9999'u TUTTURAMADI (T-035, "Cihaz ölçümü"): 0,9999 yalnızca JVM/masaüstü kapılarında (aynı platform, bit özdeş beklenir) geçerlidir; cihaz kapısı ölçüme dayalı ayrı bir eşiktir (>= 0,995). Eşik ölçümle, gerekçeyle belgelenerek ayarlandı, körlemesine gevşetilmedi.
- Sıralama etkisi: iki birim vektör için |a-b| = sqrt(2(1-cos)); 1-cos = 1e-4 iken bir görüntü vektörüyle skor farkı en çok ~0,014. CLIP skorları dar aralıkta olduğundan bu, yakın komşuların sırasını değiştirebilir; bu yüzden kosinüs tek başına yetmez, top-K örtüşmesi ayrıca kapıdır. Tipik fark çok daha küçüktür.
- Eşikler gevşetilmez; T-030 için todo'daki 0,99999 kapısı ayrı ve daha sıkıdır. Yukarıdaki T-030 riski nedeniyle bu kapı, optimizasyon düzeyi ENABLE_ALL korunmadıkça geçilemez; bu bir eşik sorunu değil dönüşüm hatasıdır.

## Budama (T-027) ve sonraki değişiklikler için kapılar

T-027 (Katman A) bu kapıların HEPSİNİ geçti; ölçümler, sentetik küme ve yöntem: [vocab-pruning.md](vocab-pruning.md). Token METNİ kapıları (1-2) temiz klonda da, depodaki `original_vocab.txt.gz` ile `PrunedVocabEquivalenceTest`te çalışır; kosinüs ve top-K (3-4) ONNX gerektirdiğinden yalnızca geliştirici makinesinde (`verify_pruned_text.py`). Aşağıdaki liste sonraki değişiklikler (T-028 mmap, T-030 .ort) için de geçerlidir.

Hepsi ORİJİNAL referansa karşı, aynı 76 sorgu (T-027 ayrıca büyük TR/EN korpus):
0. KAPI ÇALIŞTI MI: `GoldenTextTest` asıl kapısı (`tokenTexts_equalGolden_forAllGoldenQueries`, 76 sorgu) ve `unkCountAndRate_doNotExceedGoldenBaseline` sözlük dosyası yoksa `assumeTrue` ile ATLANIR. T-027 kapısı çalıştırılırken test raporunda GoldenTextTest için `skipped` sayısının 0 olduğuna bakılmalıdır; atlanmış kapı geçmiş sayılmaz. (`originalVocab_goldenIdsMatchExactly...` budanmış sözlükte KASITLI atlanır: beklenen skipped = 1.)
1. ASIL KAPI: token METNİ dizisi eşitliği (`GoldenTextTest.tokenTexts_equalGolden_forAllGoldenQueries`). Id'lere bağlı değil; budama id'leri yeniden numaralar (`TokenVocab` metne çevirir). Orijinal sözlükte ek olarak id eşitliği de doğrulanır, budanmışta atlanır.
2. `[UNK]` sayısı ve oranı altın tabanı (5 / 411) aşmaz (`unkCountAndRate_doNotExceedGoldenBaseline`).
3. Kosinüs >= 0,9999 (76 vektör, MASAÜSTÜ/aynı platform: `tools/verify_pruned_text.py` ve aynı ölçümü yapan betikler; budama yalnızca gömme satırlarını seçtiği için masaüstünde beklenen kosinüs 1,0'dır, bit özdeş). Bu kapı DEĞİŞMEDİ. Cihazda (`GoldenTextVectorsDeviceTest`, masaüstü altınına karşı) kapı >= 0,995 "çekirdek farkı bandı"dır; bkz. "Cihaz ölçümü". Cihaz kapısı budama regresyonunu tek başına yakalamaz.
4. Top-K örtüşmesi: sabit bir görüntü vektörü kümesine karşı orijinal ve değişmiş sorgu vektörlerinin top-10 ve top-50 kümeleri; öneri eşik top-10 >= 0,99 ve top-50 >= 0,98 (T-031'in koşullarıyla aynı). T-027'de ölçüldü: 10000 sentetik görüntü vektörü (kümelenmiş, sabit seed), 500 sorgu (76 altın + 424 sentetik cümle), top-10 ve top-50 örtüşmesi 1,0000 (`tools/verify_pruned_text.py`). Sentetik veri gerçek galeri dağılımını temsil etmez; gerçek galeride yeterlilik doğrulanmadı.

## Eski model dosyalarını yedekleme (repo dışı)

T-027 ile orijinaller `.model-cache/` önbelleğinde KALIR (`tools/fetch_models.sh` doldurur, git'e eklenmez; eski kurulumdaki orijinaller ağsız önbelleğe taşınır), ayrı yedek gerekmez. Başka dönüşümler (T-028, T-030) `assets/models/` dosyalarını değiştirirse orijinaller yine önbellekten alınır; `tools/fetch_models.sh` sabit revizyondan yeniden indirir. Geri yükleme/denetim için SHA-256 değerleri:

| Dosya | Boyut (bayt) | SHA-256 |
|---|---|---|
| `text_model_qint8_arm64.onnx` | 135336307 | 3bed77e83926519660b5c0833cb3321cfde952331edec4f7d501c7d384b8a8a7 |
| `text_dense_768x512_f32.bin` | 1572864 | e5f6548cbcef6c62631b3946d89042ab4c8451762c7bb0a3be11eb6c480b686d |
| `text_vocab.txt` | 995526 | fe0fda7c425b48c516fc8f160d594c8022a0808447475c1a7c6d6479763f310c |
| `vision_model_quantized.onnx` | 89117001 | 583fd1110a514667812fee7d684952aaf82a99b959760c8d7dca7e0ab9839299 |

Kaynak ve lisans: `model-setup.md` (metin: sentence-transformers/clip-ViT-B-32-multilingual-v1, Apache-2.0; görüntü: Xenova dönüşümü, OpenAI CLIP MIT çıkarım). Altın dosyalar bu modellerin çıktılarıdır; fotoğraf veya kullanıcı verisi içermez.

## Cihaz ölçümü (T-012 ölçtü, T-035 eşiği belgeledi)

Altın, Apple Silicon masaüstünde pip `onnxruntime` 1.30.0 ile üretildi; `GoldenTextVectorsDeviceTest` Android AAR ORT'siyle (ORT 1.30.0 AAR, farklı derleme ve ARM çekirdekleri) çalışır. Ölçümler qa-tester'ın arm64 emülatör androidTest koşularıdır (commit e5c35b3, 2026-10-14; Pixel_8 API 35 arm64 emülatörü; budanmış metin modeli, T-027).

KleidiAI notu: emülatör konuğu SME/SVE2 bildirir ve KleidiAI çekirdeği orada SIGILL verdiği için emülatörde KleidiAI KAPALI çalıştırıldı (test tarafı geçici çözüm `OrtEmulatorWorkaround`; bilgi satırında `kleidiaiDisabledWorkaround=true`). Gerçek telefonda çalışma yolu farklı olabilir (KleidiAI/i8mm/dotprod çekirdekleri açık); emülatör sonucu fiziksel ARM cihazı temsil etmeyebilir ve fiziksel cihazda ölçüm YAPILMADI.

| Tarih | Test (çıkarım) | n | En kötü kosinüs | Ortalama | Dağılım | Sonuç |
|---|---|---|---|---|---|---|
| 2026-10-14 | `GoldenTextVectorsDeviceTest` (çıplak metin vektörü, 76 altın sorgu) | 76 | 0,99775 | 0,99922 | 75/76 sorgu < 0,9999; 20 sorgu < 0,999; 0 sorgu < 0,99 | 0,9999 kapısı düştü; ölçüm gürültü bandıdır (aşağıda) |
| 2026-10-14 | `PromptEnsembleDeviceTest.ensembleVectors_matchPythonReference` (ensemble) | 26 | 0,99881 | 0,99951 | 0,999 eşiği farkla kaçtı | 0,999 kapısı düştü; aynı bant |

Yorum ve eşik gerekçesi (T-035):
- Bu fark budamadan DEĞİL, int8 dinamik nicemleme çekirdek farkıdır (ARM çekirdeği ile masaüstü çekirdeği; hangi çekirdeğin hangi yuvarlamayı yaptığı ayrıca doğrulanmadı). Kanıt: masaüstünde budanmış model orijinalle bit özdeştir (T-027), ve masaüstünde graf optimizasyonu BASIC/kapalı yapılınca da 1-kosinüs ~2,2e-3 çıkar (yukarıdaki gürültü tablosu). Cihazdaki en kötü 1-kosinüs 2,25e-3 aynı büyüklüktedir; bu yüzden cihazda 0,9999 kapısı anlamsızdı.
- Cihaz eşiği: HEM çıplak HEM ensemble için kosinüs >= 0,995 (`GoldenTextVectorsDeviceTest.MIN_COSINE` ve `PromptEnsembleDeviceTest.MIN_COSINE`; `appSearchEncoder_isEnsemble_andDiffersFromBareEncoder`'deki "altınla uyumsuz" denetimi de aynı sabiti kullanır). Ölçülen min 0,99775 / 0,99881 ile eşik arasında ~2,8e-3 / ~3,9e-3 pay vardır; ölçülen gürültü ~2e-3; 0,99 altında hiç sorgu yoktu. Eşik, çekirdek-farkı gürültüsünü kabul eder ama gerçek bir model/tokenizer bozulmasını (kosinüs tipik olarak << 0,99) yakalar.
- Sıralama etkisi: gürültü sıralamayı bozmadı. qa'nın resimli testinde (`SearchQualityDeviceTest`, emülatör): TR T-033 (ensemble) hit@5 1,00, MRR 1,00; TR çıplak hit@5 0,90; EN hit@5 1,00. Yani ~2e-3 mertebesindeki cihaz gürültüsü bu kümede top-K sonucunu etkilemedi (küme küçük; gerçek galeri için garanti değil).
- DEĞİŞMEYENLER: JVM/masaüstü kapıları (token metni eşitliği, bit özdeş vektör, masaüstü kosinüs >= 0,9999, `PromptEnsembleGoldenTest` bileşen farkı < 2e-6) aynen kalır; gevşetilen yalnızca cihaz-altın kosinüs eşikleridir.

### Cihaz eşiği bir "çekirdek farkı bandı"dır, regresyon kapısı DEĞİLDİR

>= 0,995 cihaz eşiği şunu söyler: "bu cihazın çekirdeği masaüstü altınından, bilinen çekirdek-farkı bandı içinde farklıdır". Bir değişikliğin (T-027 budama, T-028 mmap, T-030 .ort dönüşümü, optimizasyon düzeyi) YENİ bir fark getirdiğini AYIRT EDEMEZ: böyle bir regresyon da yaklaşık 2e-3 mertebesinde olur ve bant içinde kalır. Bu yüzden bu eşik budama/format regresyonu için kanıt sayılmaz.

ÖNERİ (uygulanmadı; T-030 ÖN KOŞULU): regresyon kapısı için "orijinal modelle AYNI cihazda" karşılaştırma: aynı cihaz/emülatör ve aynı ORT oturum ayarlarında, ORİJİNAL model (135 MB, `tools/fetch_models.sh --text-only` önbelleği) ile değişmiş model (budanmış / .ort) 76 sorgu için karşılaştırılır; aynı çekirdek yolu olduğundan beklenen kosinüs ~1,0 (bit özdeşe yakın; budama için bit özdeş) ve kapı >= 0,9999 (T-030 için 0,99999) olabilir. Bunun androidTest'te çalışması orijinal modelin cihaza konmasını gerektirir (ör. `adb push` ile geçici dizin ya da yalnızca test APK'sına geçici asset; her ikisi de ağsızdır ama test APK'sını ~135 MB şişirir/ elle adım gerektirir). Orijinal model olmadan mümkün değildir; bu nedenle şimdi uygulanmadı. Alternatif: cihazda değişmiş modelle vektörleri `adb pull` ile alıp masaüstünde, aynı cihazın orijinal model vektörleriyle karşılaştırmak.

## Doğrulanmadı

- Fiziksel ARM telefonda kosinüs: ölçülmedi; yalnızca arm64 emülatör (KleidiAI kapalı) ölçüldü. KleidiAI/i8mm açık gerçek cihazda bant farklı olabilir; ölçülürse tabloya eklenir ve >= 0,995 eşiği yeniden değerlendirilir.
- Cihazda budanmış vs orijinal model AYNI çekirdekte karşılaştırması (budamanın cihazda sıfır etkisinin doğrudan kanıtı): yapılmadı (T-030 ön koşulu önerisi). Dolaylı kanıt: masaüstü bit özdeşlik ve resimli arama testi.
- Java ve Python Unicode sürüm farkları (sette tetiklenmedi).
- Top-K örtüşme eşiklerinin gerçek galeride yeterliliği.
