# T-004: Çok dilli CLIP ONNX model araştırması

Tarih: 2026-10-04. Durum: ÖNERİ, onay bekliyor. Hiçbir model seçilmedi, koda eklenmedi, uygulamaya indirilmedi.

Karar kısıtı (architecture.md Bölüm 3, 8, 11): CLIP benzeri, tek vektör uzayı, Türkçe istem doğrudan metin kodlayıcıya girer (çeviri yok), tamamen cihaz üstü, model assets'ten yüklenir, kaynak ve lisans belgelenir.

Yöntem: Hugging Face API/model kartları ve GitHub lisans bilgisi ağ üzerinden okundu (2026-10-04). Önerilen aday ve SigLIP2 için ONNX dosyaları yalnızca araştırma amacıyla geçici klasöre indirilip masaüstünde (Python onnxruntime, Apple silicon CPU) denendi; repoya girmedi. Bu denemeler benchmark değil, sağlama testidir (aşağıda "Ölçümler"). Doğrulanamayan her bilgi "doğrulanamadı" diye işaretlidir.

## 1. Adaylar

Boyutlar HF API'den alınan bayt değerlerinin MB (10^6) karşılığıdır. "Doğrulandı" = dosya/kart/ölçümle görüldü.

### Aday A: sentence-transformers/clip-ViT-B-32-multilingual-v1 (metin) + OpenAI CLIP ViT-B/32 (görüntü)

Metin kodlayıcı OpenAI CLIP ViT-B/32 metin uzayına damıtılmış çok dilli DistilBERT. Görüntü kodlayıcı değiştirilmemiş orijinal CLIP ViT-B/32 (model kartı: "The image encoder from CLIP is unchanged"). Yani iki ayrı ONNX dosyası, tek vektör uzayı.

| Özellik | Değer |
|---|---|
| Kaynak (metin) | https://huggingface.co/sentence-transformers/clip-ViT-B-32-multilingual-v1 (revizyon 58edf8cada9e398793dca955574a48cbb7f18be2). Repoda hazır `onnx/` klasörü var |
| Kaynak (görüntü) | https://huggingface.co/Xenova/clip-vit-base-patch32 (revizyon d15189d7028b43f1d3e65039190477f6af591c2a; `onnx/vision_model*.onnx`, openai/clip-vit-base-patch32 dönüşümü). Alternatif: https://huggingface.co/immich-app/ViT-B-32__openai `visual/model.onnx` (revizyon a857c8de2c07bbcfa6646adfcf31b798845afa1e; fp32, 351.6 MB; fp16/int8 yok) |
| Lisans | Metin: Apache-2.0 (kart etiketi, doğrulandı). Görüntü: OpenAI CLIP, MIT (github.com/openai/CLIP lisansı MIT, doğrulandı). Xenova repo'sunda lisans etiketi yok, kaynak modelin lisansının geçerli olduğu varsayımı; Xenova repo'sunun ayrıca lisans beyanı doğrulanamadı |
| Giriş çözünürlüğü (görüntü) | 224x224; en kısa kenar 224'e bicubic yeniden boyutlandırma, merkez kırpma, mean [0.48145466, 0.4578275, 0.40821073], std [0.26862954, 0.26130258, 0.27577711] (preprocessor_config, doğrulandı) |
| Metin girişi | WordPiece (distilbert-base-multilingual-cased, 119547 token sözlük, vocab.txt ~1 MB), `[CLS] ... [SEP]`, max 128 token, küçük harfe çevirme YOK (cased) |
| Vektör boyutu | 512 (görüntü: `image_embeds` çıktısı 512, doğrulandı; metin: Dense çıkışı 512) |
| Metin ONNX boyutu | fp32 539 MB; `model_O4` (fp16) 269 MB; `model_qint8_arm64` 135 MB (dinamik int8, ARM64 için); `model_quint8_avx2` 135 MB |
| Görüntü ONNX boyutu | fp32 351.7 MB; fp16 176.1 MB; int8 (`vision_model_quantized`) 89.1 MB; uint8 88.6 MB; q4f16 53.3 MB |
| Toplam (önerilen kombinasyonlar) | ana yol: metin int8 + görüntü int8 = ~224 MB; yedek: metin int8 + görüntü fp16 = ~311 MB, ya da görüntü fp32 = ~487 MB; ek olarak Dense 1.6 MB + vocab 1 MB |
| Niceleme | int8 (metin ve görüntü), fp16 (metin ve görüntü) mevcut, hazır dosyalar. Ana yol int8. fp16 görüntü dosyası yedek; telefon CPU'sunda (ORT Android) fp16 çalışması ve hızı doğrulanamadı |
| Türkçe kalitesi | Türkçe (`tr`), hizalama için kullanılan 50+ dilin içinde açıkça listeli (kart, doğrulandı). Yayımlanmış Türkçe geri getirme skoru doğrulanamadı. Kendi ölçümüm: aşağıda |
| Dikkat edilecekler | (1) ONNX çıktısı yalnızca `last_hidden_state` (1x seq x 768); mean pooling (attention mask ile) + 768->512 Dense (bias yok, `2_Dense/model.safetensors`, 1.57 MB) + L2 normalizasyon uygulama tarafında yapılmalı, ya da Dense/pooling ONNX'e gömülmeli. (2) Cased sözlük: tamamı BÜYÜK HARF istem kalitesini düşürüyor (ölçüm aşağıda); istem Türkçe yerel ayarla (`tr`) küçük harfe çevrilmeli. (3) Tokenizer WordPiece, Kotlin'de kütüphanesiz yazılabilir (kural basit; normalizer BertNormalizer, clean_text, handle_chinese_chars) |

### Aday B: google/siglip2-base-patch16-224 (onnx-community dönüşümü)

Çok dilli WebLI üzerinde eğitilmiş SigLIP 2, ViT-B/16. Metin ve görüntü kodlayıcı aynı eğitimle gelir (sigmoid kayıp).

| Özellik | Değer |
|---|---|
| Kaynak | https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX (revizyon ba1f3b0843f24bc5417d38e19c37b287d719b2f4; orijinal: google/siglip2-base-patch16-224) |
| Lisans | Orijinal model Apache-2.0 (kart etiketi, doğrulandı). ONNX repo'sunda lisans etiketi yok, kaynak lisansı varsayımı; doğrulanamadı |
| Giriş çözünürlüğü | 224x224 (kare, kırpmasız yeniden boyutlandırma), mean/std 0.5 (preprocessor_config, doğrulandı) |
| Metin girişi | Gemma SentencePiece, 256000 token sözlük (tokenizer.json 34 MB, tokenizer.model 4.2 MB). Önerilen kullanım: küçük harf, 64 token'a pad (HF pratiği; kartta doğrulanmadı, denemede böyle kullandım) |
| Vektör boyutu | 768 (`pooler_output`, doğrulandı) |
| Metin ONNX boyutu | fp32 1129 MB; fp16 565 MB; int8 283 MB; q4f16 443 MB |
| Görüntü ONNX boyutu | fp32 372 MB; fp16 186 MB; int8 94.6 MB; q4f16 54.6 MB |
| Toplam | int8+int8 = ~378 MB (+ tokenizer); fp16+fp16 = ~751 MB |
| Niceleme | int8, fp16, q4, q4f16, bnb4 hazır dosyalar |
| Türkçe kalitesi | Çok dilli WebLI eğitimi; Türkçe'ye özel yayımlanmış skor bu araştırmada doğrulanamadı. Kendi ölçümüm: aşağıda |
| Dikkat edilecekler | Tokenizer SentencePiece (Kotlin'de kütüphane veya el yazımı gerektirir; kütüphane seçimi ayrı karar). ViT-B/16 görüntü kodlayıcı 196 token işler (B/32'de 49), indeksleme masaüstünde 2.3-3.4 kat daha yavaş ölçüldü (Bölüm 3; telefon süresi doğrulanamadı). Metin ONNX çıktısı 768 boyutlu, kosinüs benzerliği yeterli, logit scale/bias sıralamayı değiştirmez |

### Aday C: laion/CLIP-ViT-B-32-xlm-roberta-base-laion5B-s13B-b90k (immich-app ONNX dönüşümü)

LAION-5B (çok dilli) üzerinde OpenCLIP ile uçtan uca eğitilmiş: ViT-B/32 görüntü, XLM-RoBERTa-base metin kodlayıcı.

| Özellik | Değer |
|---|---|
| Kaynak | Orijinal: https://huggingface.co/laion/CLIP-ViT-B-32-xlm-roberta-base-laion5B-s13B-b90k (rev. 506d40eb551f4801a1c27fc20a31c7b8f590deda). ONNX: https://huggingface.co/immich-app/XLM-Roberta-Base-ViT-B-32__laion5b_s13b_b90k (rev. 111f6ddca817bb6cd8343c3e3dc5ce39d9ba54b0, `textual/model.onnx`, `visual/model.onnx`) |
| Lisans | MIT (orijinal kart, doğrulandı). immich ONNX repo'sunda lisans etiketi yok, kaynak lisansı varsayımı; doğrulanamadı |
| Giriş çözünürlüğü | 224x224, OpenAI CLIP ile aynı mean/std, bicubic, en kısa kenar + merkez kırpma (`preprocess_cfg.json`, doğrulandı) |
| Metin girişi | XLM-R SentencePiece (`sentencepiece.bpe.model` 5.1 MB, tokenizer.json 17 MB), mean pooler |
| Vektör boyutu | 512 (`config.json`, doğrulandı) |
| ONNX boyutu | Metin fp32 1113 MB; görüntü fp32 351.6 MB. Repoda fp16/int8 YOK |
| Toplam | fp32: ~1465 MB. Kendimiz nicelersek metin tarafı yaklaşık 280 MB'a inebilir (tahmin, doğrulanamadı) |
| Niceleme | Hazır yok; kendi dönüşüm/niceleme adımı gerekir, doğruluk kaybı ölçülmedi |
| Türkçe kalitesi | XLM-R Türkçe'yi kapsar. Kart yalnızca İtalyanca (ImageNet-1k %43) ve Japonca (%37) için ön ölçüm veriyor; Türkçe skoru doğrulanamadı. Kendi Türkçe ölçümümü yapmadım (1.4 GB indirme gerekirdi) |
| Dikkat edilecekler | Boyut en büyük; SentencePiece tokenizer gerekir |

## 2. Elenenler

| Model | Neden elendi |
|---|---|
| M-CLIP/XLM-Roberta-Large-Vit-B-32 | Yalnızca PyTorch/TF ağırlığı var (2.24 GB), hazır ONNX yok; kart lisans etiketi vermiyor (doğrulanamadı); XLM-R Large telefona ağır |
| jinaai/jina-clip-v2 | Lisans CC-BY-NC-4.0 (ticari olmayan), 865M parametre; hem lisans hem boyut açısından uygunsuz |
| Apple MobileCLIP2 (PicQuery'nin kullandığı) | Metin kodlayıcı İngilizce; PicQuery Çinceden İngilizceye ayrı bir çeviri modeli kullanıyor, bu bizim kararımızla (çeviri yok) çelişir. Lisans Apple "Machine Learning Research Model" (yalnızca bilimsel araştırma amaçlı) |
| OpenAI CLIP ViT-B/32 (yalnız İngilizce) | Türkçe istemi doğrudan alamaz |

Referans proje: github.com/greyovo/PicQuery (MIT, Flutter, MobileCLIP2-S0 görsel/metin ONNX + ayrı MT modeli). İndeks/ONNX mimarisi örnek alınabilir, model seçimi örnek alınmaz.

## 3. Ölçümler (masaüstü sağlama testi)

Ortam: Python onnxruntime 1.30 CPU, Apple silicon Mac. Bu ölçümler telefon başarımı veya telefon süresi DEĞİLDİR; telefon sayıları doğrulanamadı. Örneklem çok küçük: 5 COCO val2017 fotoğrafı (iki kedi, ayı, ters dur tabelası, oturma odası, yatak odası), 18 el yazımı Türkçe istem. İstatistiksel iddia taşımaz; yalnızca "çalışıyor mu, İngilizceye yakın mı, niceleme ne kadar kaydırıyor" sorularını yanıtlar. Adaylar arasında ayrım yapmak için KULLANILAMAZ.

- Türkçe istem -> görüntü: Aday A ve Aday B (SigLIP2 int8) etiketli 9 istemde 9/9 doğru ilk sırayı verdi; Aday A'da aynı isteklerin İngilizce çevirisi orijinal CLIP metin kodlayıcısıyla da 9/9. Bu 5 fotoğraflık küme iki adayı ayırt etmiyor, sonuç "kalite kanıtı" değil, yalnızca "bozuk değil" işaretidir. Aday A'da ters dur tabelası isteminde ilk üç skor 0.223, 0.223, 0.222 (marj sıfıra yakın, kırılgan). Aday B'nin skor marjları hakkında sonuç çıkarılmadı (skorlar modeller arası kıyaslanamaz).
- Metin damıtma farkı (niceleme kaymasından ayrı): 8 Türkçe/İngilizce cümle çiftinde, fp32 Türkçe metin vektörü ile fp32 orijinal CLIP İngilizce metin vektörü kosinüsü 0.811-0.977 (ortalama 0.914). Bu fark damıtmadan geliyor, nicelemeden değil.
- Metin niceleme kayması: 18 Türkçe istemde fp32 metin vektörü ile int8 (`model_qint8_arm64`) metin vektörü kosinüsü 0.9990-0.9999 (ortalama 0.9996). int8 Türkçe vektörün İngilizce fp32 CLIP vektörüne kosinüsü 0.816-0.978 (ortalama 0.913), fp32 ile aynı. Yani metin tarafında int8 kaybı ihmal edilebilir. (8 çiftli ilk-sıra eşleşmesi fp32'de 7/8, int8'de 8/8 çıktı; tek cümlelik fark gürültüdür, anlam taşımaz.)
- Görüntü niceleme kayması (RİSK): int8 görüntü vektörü fp32'ye göre kosinüs 0.937-0.987 (5 fotoğraf); fp16 ~1.000. Metin tarafının aksine görüntü int8 belirgin sapıyor. Sıralama bu örneklemde değişmedi ama 5 fotoğraf bunu güvence altına almaz; gerçek galeri örneğiyle doğrulanmalı.
- Büyük/küçük harf: "Plajda gülen çocuk" vs küçük harf kosinüs 0.999; "IŞIK ALTINDA KEDİ" vs "ışık altında kedi" 0.865 (parçalanıyor: `I ##Ş ##I ##K`). Küçük harfe çevirme (Türkçe yerel ayar, I -> ı, İ -> i) önişlemeye eklenmeli. Diakritiksiz yazım ("cocuk plajda gulen") kosinüs 0.961.
- Hız (masaüstü, tek iş parçacığı, 1 görüntü): ViT-B/32 fp32 18 ms, int8 26 ms; SigLIP2 B/16 int8 61 ms. Aday B bu yüzden masaüstünde Aday A'dan 2.3-3.4 kat daha yavaş (61/26 = 2.3, 61/18 = 3.4). Masaüstünde int8, fp32'den yavaştı; telefon ARM çekirdeklerinde davranış farklı olabilir. Telefon süresi doğrulanamadı. Metin sorgusu (Aday A int8, masaüstü): ~2 ms.
- Masaüstü tepe bellek (RSS, `ru_maxrss`; Python + ORT taban değeri ayrıca ölçülmedi, yani üst sınır): metin int8 368 MB (batch 1), görüntü int8 340 MB, fp32 459 MB, fp16 646 MB (batch 8). fp16 masaüstü CPU'da fp32'den bile fazla bellek kullandı. Telefon belleği doğrulanamadı.
- Op türleri: int8 metin `DynamicQuantizeLinear`/`MatMulInteger`; int8 görüntü `MatMulInteger` ağırlıklı (opset 11). Bunlar ORT standart CPU op'larıdır; Android ORT'de çalıştığı bu araştırmada doğrulanamadı (telefonda denenmedi).
- Aday C: ölçülmedi.

## 4. Öneri

Öneri: Aday A. Metin: sentence-transformers/clip-ViT-B-32-multilingual-v1 `onnx/model_qint8_arm64.onnx`; görüntü: OpenAI CLIP ViT-B/32 ONNX, int8 (`Xenova/clip-vit-base-patch32` `onnx/vision_model_quantized.onnx`). Ana yol: metin int8 + görüntü int8 (~224 MB). Yedek yol: görüntü fp16 (176 MB), onun da olmadığı durumda fp32 (352 MB). fp16 telefon CPU'sunda (ORT Android) doğrulanmadı; masaüstünde fp32'den fazla bellek kullandı, bu yüzden ana yol değil, yedektir.

Özgünlük ölçütü (b) ile ilişkisi: Bu seçim, ders raporundaki (b) ölçütüne ("arama sırasında ML Kit gibi araçların dinamik kullanımına ihtiyaç duyulmaması") şöyle uyar: fotoğraflar indeksleme aşamasında görüntü kodlayıcıyla BİR KEZ vektöre çevrilip Room'a yazılır (ölçüt a). Aramada görüntü kodlayıcı hiç yüklenmez ve hiçbir fotoğraf işlenmez; ML Kit veya başka bir görüntü analizi aracı çalışmaz. Yalnızca Türkçe sorgu metni metin kodlayıcıdan geçirilip 512 boyutlu vektöre çevrilir ve kosinüs benzerliğiyle indeksteki vektörlerle karşılaştırılır. Metin kodlayıcı da önceden belirlenmiş, assets'ten gelen statik bir modeldir, ağ ve uzak servis kullanmaz.

Gerekçe (dayanak boyut, lisans ve basitliktir; Türkçe kalitesi bu seçimin kanıtı DEĞİLDİR):
1. Boyut/süre bütçesi: Toplam ~224 MB (ana yol); Aday B ~378 MB, Aday C ~1.46 GB. Görüntü kodlayıcı ViT-B/32 yalnızca 49 token işler; masaüstünde SigLIP2 B/16'dan 2.3-3.4 kat hızlı (telefonda doğrulanamadı). Arama anında yalnızca metin kodlayıcı çalışır, görüntü modeli yalnızca indekslemede yüklenir ve sonra kapatılır; bellek ayak izi iki aşamaya bölünür.
2. Lisans: Apache-2.0 (metin) + MIT (görüntü); ticari olmayan veya araştırma-only kısıtı yok. ONNX dönüşüm repolarında (Xenova/immich) lisans etiketi eksik, kaynak lisansının geçerli olduğu varsayımına dayanıyor. Rapora yazılacak lisans metni için kaynak repolara (sentence-transformers, openai/CLIP) atıf yapılmalı.
3. Basitlik: Metin ve görüntü için hazır int8 ONNX var (kendi dönüşüm hattı gerekmez); WordPiece tokenizer Kotlin'de ek kütüphanesiz yazılabilir. Aday B ve C için SentencePiece çözümü ayrıca karar gerektirir (bağımlılık gerekçesi, architecture.md Bölüm 8).
4. Türkçe: Türkçe, hizalama dillerinin içinde açıkça listeli; küçük sağlama testinde bozuk görünmüyor. Gerçek Türkçe geri getirme kalitesi bu araştırmada KANITLANMADI; ml görevinde gerçek galeri + Türkçe istem kümesiyle ölçülmeli.

Riskler (öneriyle birlikte kabul edilmesi gerekenler):
- Görüntü int8 vektörleri fp32'den belirgin sapıyor (kosinüs 0.937-0.987, 5 fotoğraf). Ana yol int8 olduğu için gerçek galeri örneğinde fp32 ile sıralama karşılaştırması zorunlu; kötüyse yedek yola geçilir (APK +87 MB fp16 ya da +263 MB fp32).
- Metin damıtma farkı (Türkçe-İngilizce kosinüs ortalama ~0.91) Türkçe aramanın İngilizce CLIP kadar iyi olmayabileceğine işaret edebilir; etkisi ölçülmedi.
- APK/depolama boyutu (Bölüm 5).

Karşı not: Aday B (SigLIP2 B/16) Türkçe kalitesi açısından daha güçlü olabilir, ancak bunu destekleyen/çürüten yeterli veri üretmedim (iki aday da küçük örneklemde 9/9). Maliyeti: ~1.7 kat model boyutu (378 vs 224 MB), masaüstünde 2.3-3.4 kat yavaş indeksleme (telefonda doğrulanamadı), SentencePiece bağımlılığı. Türkçe doğruluk boyut ve süreden önceliklidir denirse B tercih edilebilir; bu durumda önce gerçek cihazda süre ve bellek ölçülmeli.

## 5. Model yükleme, bellek ve APK bütçesi

### 5.1 Model yükleme biçimi
- Modeller assets'ten bayt dizisi olarak okunup `OrtSession`'a verilmemeli: 135 MB (metin) veya 89 MB (görüntü) bayt dizisi Java yığınına girer ve ORT ayrıca kendi kopyasını oluşturur; Java yığın sınırı cihaza göre değişir (doğrulanamadı), OOM riski yüksek.
- Önerilen: ilk açılışta (arka plan iş parçacığında, ana thread'i bloklamadan) assets'ten `filesDir`'e akış kopyası (64 KB tampon), geçici dosyaya yazıp atomik yeniden adlandırma, boyut + SHA-256 doğrulaması (Bölüm 6) ve `modelVersion` damgası; sonra `OrtEnvironment.createSession(dosyaYolu, seçenekler)` ile dosya yolundan açma. Ağ yok, indirme yok. Yetersiz depolama durumu kullanıcıya bildirilir.
- `build.gradle.kts`: `androidResources { noCompress += "onnx" }` (AGP sürümüne göre `aaptOptions`). Gerekçe: int8 ağırlıklar zaten az sıkışır (sıkışma kazancı ölçülmedi, doğrulanamadı), açılışta gereksiz açma süresi olmaz, akış kopyası hızlanır.
- Depolama: APK içindeki model (224 MB) + `filesDir` kopyası (224 MB) = yaklaşık 2 kat, ~450 MB cihaz depolaması (APK + uygulama verisi). Assets'ten APK içindeki kopya silinemez. `allowBackup="false"` olduğu için kopya bulut yedeğine girmez.
- Bir seferde yalnızca bir oturum: arama için metin, indeksleme için görüntü; iş bitince `OrtSession.close()`. Görüntüleri toplu (başlangıç öneri: batch 1-4) işle, bitmap'i 224x224'e küçültüp `recycle()` et.
- Çalışma zamanı tepe bellek: telefonda ölçülmedi, doğrulanamadı. Masaüstü RSS (Bölüm 3; metin 368 MB, görüntü int8 340 MB @ batch 8, Python/ORT tabanı dahil) yalnızca büyüklük mertebesi içindir. ORT'nin dosya yolundan açılan modeli bellek eşlemeli (mmap) mi okuduğu, yoksa belleğe mi aldığı doğrulanamadı; en kötü durumda model boyutu kadar yerel bellek + etkinleştirmeler varsayılmalı. ml görevinde gerçek cihazda ölçülecek (Android Studio profiler / `dumpsys meminfo`).

### 5.2 APK / AAB boyut bütçesi
- ORT yerel kütüphane: `com.microsoft.onnxruntime:onnxruntime-android:1.30.0` (Maven Central'daki en son sürüm, 2026-10-04) AAR 53.0 MB. İçindeki `libonnxruntime.so` açılmış boyutları: arm64-v8a 33.0 MB, armeabi-v7a 23.3 MB, x86 39.3 MB, x86_64 39.3 MB (+ ~0.1 MB JNI). APK içinde sıkıştırılmış boyutları ölçülmedi, doğrulanamadı. ORT'nin sadeleştirilmiş (özel op kümesi) derlemesi bu boyutu küçültebilir; denenmedi, doğrulanamadı.
- ABI seçimi: yalnızca `arm64-v8a` (`ndk { abiFilters += "arm64-v8a" }`) önerilir. Etkisi: arm32 (armeabi-v7a) cihazlar ve x86/x86_64 emülatör çalışmaz (x86_64 emülatör için ayrı bir debug ABI eklenmesi gerekebilir; Apple silicon Mac'te arm64 emülatör çalışır). minSdk 26 cihazlarda arm64 payı doğrulanamadı.
- Tahmini APK boyutu (arm64 yalnız, ana yol modeller): 224 MB model + ~33 MB ORT .so (sıkıştırılmamış üst sınır) + birkaç MB uygulama kodu = yaklaşık 260 MB. Tüm ABI'lerle ORT .so'ları ~135 MB'a çıkar, APK ~360 MB üstü. Tahmindir, derleme ile ölçülmedi, doğrulanamadı.
- AAB/Play sınırları (taban modül sıkıştırılmış indirme sınırı, asset pack seçenekleri) bu araştırmada kontrol edilmedi, doğrulanamadı; ders teslimi yan yükleme (APK) ise sınır geçerli olmayabilir, kullanıcı onayı gerekir.
- Boyut azaltma seçenekleri:
  1. Yalnızca arm64 (yukarıda): ~100 MB (açılmış) tasarruf.
  2. Metin kodlayıcı gömme (embedding) matrisini budama: int8 metin modelinin en büyük ağırlığı `embeddings.word_embeddings.weight_quantized` [119547 x 768] uint8 = 91.8 MB (modelin ~%68'i, ölçüldü). Sözlüğü Türkçe + İngilizce kullanılan token'lara (ör. 30.000 token) indirip kimlikleri yeniden eşlersek metin modeli yaklaşık 67 MB'a inebilir, yaklaşık 69 MB tasarruf (hesap, doğrulanamadı; gerçek budama yapılmadı). Riskler: Türkçe özel adlar ve nadir kelimeler [UNK]'a düşer, ONNX'in Gather düğümü ve sözlük dosyası birlikte düzenlenmeli, kalite kaybı ölçülmedi. Bu bir ml görevi ve kullanıcı onayı konusudur.
  3. Görüntü: int8 zaten en küçük güvenli seçenek (89 MB); `q4f16` 53 MB ama kalitesi ve Android desteği doğrulanamadı.
  4. Bu üçü birleşirse (arm64 + budama + int8) tahmini APK ~190 MB (tahmin, doğrulanamadı).

### 5.3 Diğer ml görevleri için açık noktalar
- Metin tarafı: mean pooling (attention mask ile) + 768->512 Dense + L2 normalizasyon (ya da bunları ONNX'e gömme) Kotlin'de uygulanmalı; Dense ağırlığı (1.57 MB) assets'e eklenmeli. Mimari belgesinde `ml` paketi ayrıntısı olarak not edilmeli.
- Önişleme: Türkçe yerel ayarla küçük harfe çevirme, uzunluk sınırı (128 token, ayrıca architecture.md Bölüm 8 girdi doğrulama).
- Veri modeli: PhotoEmbedding vektör boyutu 512 float (2 KB/foto), `modelVersion` alanı model + niceleme kombinasyonunu içermeli.
- Doğrulama: ~50-100 gerçek galeri fotoğrafı + Türkçe istem kümesiyle int8 vs fp32 (ve varsa fp16) görüntü kodlayıcı sıralama karşılaştırması; telefonda süre ve bellek ölçümü.
- Log'a fotoğraf yolu, içerik ve istem yazılmayacak; INTERNET izni eklenmez. Modeller derleme öncesi tek seferlik indirilip assets'e konur, uygulama içinde indirme yok.

## 6. Dosya bütünlüğü (SHA-256)

assets'e konan dosyalar bu değerlerle doğrulanmalıdır. "LFS" = HF API'deki `lfs.oid`; "yerel" = araştırma sırasında indirilen dosyada hesaplanan değer. İndirilebilen dosyalarda ikisi birbiriyle eşleşti. Revizyonlar: sentence-transformers 58edf8cada9e398793dca955574a48cbb7f18be2, Xenova d15189d7028b43f1d3e65039190477f6af591c2a, immich ViT-B-32__openai a857c8de2c07bbcfa6646adfcf31b798845afa1e.

| Dosya | Rol | Boyut (bayt) | SHA-256 | Kaynak |
|---|---|---|---|---|
| sentence-transformers/clip-ViT-B-32-multilingual-v1 `onnx/model_qint8_arm64.onnx` | Metin, ana yol | 135336307 | 3bed77e83926519660b5c0833cb3321cfde952331edec4f7d501c7d384b8a8a7 | LFS + yerel |
| sentence-transformers/clip-ViT-B-32-multilingual-v1 `2_Dense/model.safetensors` | Metin 768->512 Dense | 1572984 | d12568dc7300970a4d3dbb49068ad16cd89b99840b74b026f8e48071e9414f74 | LFS + yerel |
| sentence-transformers/clip-ViT-B-32-multilingual-v1 `tokenizer.json` | Metin tokenizer | 1961847 | 5b4e1a8171c81dfd666ae40265b9530c6e0b3d53923fe8ac493dcc84229adf81 | yerel (LFS değil) |
| sentence-transformers/clip-ViT-B-32-multilingual-v1 `vocab.txt` | Metin sözlük | 995526 | fe0fda7c425b48c516fc8f160d594c8022a0808447475c1a7c6d6479763f310c | yerel (LFS değil) |
| Xenova/clip-vit-base-patch32 `onnx/vision_model_quantized.onnx` | Görüntü int8, ana yol | 89117001 | 583fd1110a514667812fee7d684952aaf82a99b959760c8d7dca7e0ab9839299 | LFS + yerel |
| Xenova/clip-vit-base-patch32 `onnx/vision_model_fp16.onnx` | Görüntü fp16, yedek | 176080659 | 35c4e0fb0aeee527dcde1693520b214a34424a786babd530f35366bad5844efd | LFS + yerel |
| Xenova/clip-vit-base-patch32 `onnx/vision_model.onnx` | Görüntü fp32, ikinci yedek ve karşılaştırma | 351685709 | fd6e1402a588279d1723c7534d4bcba5bc0b14b47dfab0e46f8c47b8270d7d40 | LFS + yerel |
| immich-app/ViT-B-32__openai `visual/model.onnx` | Görüntü fp32, alternatif kaynak | 351613724 | 33a3df41ceef21acdf371af00f6dd0456ec1f9eba24d03a7720f9c3734e40859 | LFS (indirilmedi) |
| sentence-transformers/clip-ViT-B-32-multilingual-v1 `onnx/model.onnx` | Metin fp32, yalnızca karşılaştırma | 539049415 | ca09969eb6e0f36fb1622654d1b47ac2025f576794f48b249a50b78d4b70373f | LFS + yerel |

Not: Dense dosyası safetensors biçiminde; Kotlin'de okumak yerine derleme öncesi düz float32 ikili dosyaya çevrilmesi ml görevinde değerlendirilebilir (doğrulanmadı). İkinci yedek ve alternatif kaynak dosyalar assets'e konmaz, yalnızca karşılaştırma içindir.
