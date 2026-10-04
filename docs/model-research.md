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
| Kaynak (görüntü) | https://huggingface.co/Xenova/clip-vit-base-patch32 (`onnx/vision_model*.onnx`, openai/clip-vit-base-patch32 dönüşümü). Alternatif: immich-app/ViT-B-32__openai `visual/model.onnx` (aynı boyut) |
| Lisans | Metin: Apache-2.0 (kart etiketi, doğrulandı). Görüntü: OpenAI CLIP, MIT (github.com/openai/CLIP lisansı MIT, doğrulandı). Xenova repo'sunda lisans etiketi yok, kaynak modelin lisansının geçerli olduğu varsayımı; Xenova repo'sunun ayrıca lisans beyanı doğrulanamadı |
| Giriş çözünürlüğü (görüntü) | 224x224; en kısa kenar 224'e bicubic yeniden boyutlandırma, merkez kırpma, mean [0.48145466, 0.4578275, 0.40821073], std [0.26862954, 0.26130258, 0.27577711] (preprocessor_config, doğrulandı) |
| Metin girişi | WordPiece (distilbert-base-multilingual-cased, 119547 token sözlük, vocab.txt ~1 MB), `[CLS] ... [SEP]`, max 128 token, küçük harfe çevirme YOK (cased) |
| Vektör boyutu | 512 (görüntü: `image_embeds` çıktısı 512, doğrulandı; metin: Dense çıkışı 512) |
| Metin ONNX boyutu | fp32 539 MB; `model_O4` (fp16) 269 MB; `model_qint8_arm64` 135 MB (dinamik int8, ARM64 için); `model_quint8_avx2` 135 MB |
| Görüntü ONNX boyutu | fp32 351.7 MB; fp16 176.1 MB; int8 (`vision_model_quantized`) 89.1 MB; uint8 88.6 MB; q4f16 53.3 MB |
| Toplam (önerilen kombinasyonlar) | metin int8 + görüntü int8 = ~224 MB; metin int8 + görüntü fp16 = ~311 MB; ek olarak Dense 1.6 MB + vocab 1 MB |
| Niceleme | int8 (metin ve görüntü), fp16 (metin ve görüntü) mevcut, hazır dosyalar |
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
| Dikkat edilecekler | Tokenizer SentencePiece (Kotlin'de kütüphane veya el yazımı gerektirir; kütüphane seçimi ayrı karar). ViT-B/16 görüntü kodlayıcı 196 token işler (B/32'de 49), indeksleme yaklaşık 3-4 kat daha yavaş beklenir (masaüstü tek iş parçacığı: 61 ms vs 18-26 ms; telefon süresi doğrulanamadı). Metin ONNX çıktısı 768 boyutlu, kosinüs benzerliği yeterli, logit scale/bias sıralamayı değiştirmez |

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

Ortam: Python onnxruntime 1.30 CPU, Apple silicon Mac. Telefon başarımı DEĞİLDİR. Örneklem çok küçük: 5 COCO val2017 fotoğrafı (iki kedi, ayı, ters dur tabelası, oturma odası, yatak odası), 8-10 el yazımı Türkçe istem. Doğru sonuç oranı için istatistiksel iddia taşımaz; yalnızca "çalışıyor mu, İngilizceye yakın mı" sorusunu yanıtlar.

- Aday A, Türkçe istem -> görüntü: doğru fotoğrafı bilinen 9 istemin 9'unda doğru fotoğraf ilk sırada geldi (10. istem "hayvan" etiketsizdi, ayı çıktı). Aynı istemlerin İngilizce çevirisi orijinal CLIP metin kodlayıcısıyla da aynı 9/9 sonucu verdi, yani Türkçe sürüm bu örneklemde İngilizceyle eşdeğer. Ters dur tabelası isteminde Aday A'nın marjı neredeyse sıfırdı (ilk üç skor 0.223, 0.223, 0.222); bu örnekte sıralama kırılgan.
- Aday A hizalama: 8 Türkçe/İngilizce cümle çiftinde Türkçe metin vektörü ile CLIP İngilizce metin vektörü kosinüsü 0.83-0.98 (int8 arm64 metin modeliyle), ilk sıra doğruluğu 8/8, çift dışı ortalama 0.68.
- Aday A niceleme kayması: görüntü int8, fp32'ye göre kosinüs 0.937-0.987 (5 fotoğraf); fp16 ~1.000. Sıralama bu örneklemde değişmedi ama int8 görüntü vektörü fp32'den belirgin sapıyor; gerçek veride (kendi galeri örneği) doğrulanmalı.
- Aday A, büyük/küçük harf: "Plajda gülen çocuk" vs küçük harf kosinüs 0.999; "IŞIK ALTINDA KEDİ" vs "ışık altında kedi" 0.865 (parçalanıyor: `I ##Ş ##I ##K`). Yani küçük harfe çevirme (Türkçe yerel ayar, I -> ı, İ -> i) önişlemeye eklenmeli. Diakritiksiz yazım ("cocuk plajda gulen") kosinüs 0.961, kabul edilebilir.
- Aday B (int8 metin + int8 görüntü): etiketli 9 Türkçe istemde 9/9 doğru ilk sıra (Aday A'daki 10 istemden "hayvan" hariç aynı küme); ters dur tabelası marjı Aday A'dan geniş (0.095 vs en yakın 0.027; skorlar modeller arası doğrudan kıyaslanamaz, marj oranı yorumlandı). Bu örneklemde kalitesi Aday A'dan iyi görünüyor, fakat örneklem bunu kanıtlamaya yetmez.
- Süre (masaüstü, tek iş parçacığı, 1 görüntü): ViT-B/32 fp32 18 ms, int8 26 ms (masaüstünde int8 daha yavaş, telefonda ARM int8 çekirdekleri farklı davranabilir, doğrulanamadı); SigLIP2 B/16 int8 61 ms. Metin sorgusu (Aday A int8, masaüstü): ~2 ms. Telefon süreleri doğrulanamadı, ölçüm ml görevinde gerçek cihazda yapılmalı.
- Aday C: ölçülmedi.

## 4. Öneri

Öneri: Aday A. Metin: sentence-transformers/clip-ViT-B-32-multilingual-v1 `onnx/model_qint8_arm64.onnx`; görüntü: OpenAI CLIP ViT-B/32 ONNX (Xenova veya immich dönüşümü). Başlangıç niceleme: metin int8, görüntü fp16 (176 MB) ya da int8 (89 MB); ikisi arasında gerçek galeri örneğiyle ölçüp seçilmeli (aşağıda).

Gerekçe:
1. Boyut/süre bütçesi: Toplam ~224 MB (int8+int8) veya ~311 MB (int8 metin + fp16 görüntü); Aday B ~378 MB, Aday C ~1.46 GB. Görüntü kodlayıcı ViT-B/32 yalnızca 49 token işler; fotoğraf başına indeksleme en ucuz seçenek (SigLIP2 B/16'ya göre masaüstünde yaklaşık 2.3-3.4 kat; telefonda doğrulanamadı). Arama anında yalnızca metin kodlayıcı çalışır (sorgu başına masaüstünde ~2 ms), görüntü modeli yalnızca indekslemede yüklenir ve sonra serbest bırakılır; bellek ayak izi iki aşamaya bölünür.
2. Türkçe istem kalitesi: Türkçe, hizalama dillerinin içinde açıkça listeli; küçük sağlama testinde Türkçe sorgular İngilizce çevirileriyle aynı sonuçları verdi (9/9). Uyarı: Aday B'nin marjları daha geniş görünüyor, ancak buna dayanarak karar vermek için yeterli veri yok.
3. Lisans: Apache-2.0 (metin) + MIT (görüntü); ticari olmayan veya araştırma-only kısıtı yok. ONNX dönüşüm repolarında (Xenova/immich) lisans etiketi eksik, kaynak lisansının geçerli olduğu varsayımına dayanıyor. Rapora yazılacak lisans metni için kaynak repolara (sentence-transformers, openai/CLIP) atıf yapılmalı.
4. Hazır ONNX ve niceleme: Metin ve görüntü için hazır int8/fp16 ONNX dosyaları var; kendi dönüşüm/niceleme hattı yazmak gerekmez (sürpriz riski düşük).
5. Kotlin tarafı basit: WordPiece tokenizer için ek kütüphane gerekmez; Aday B ve C için SentencePiece çözümü ayrıca karar gerektirir (bağımlılık gerekçesi, architecture.md Bölüm 8).

Karşı not (karara etki edebilir): Aday B (SigLIP2 B/16) Türkçe kalitesi açısından daha güçlü olabilir; maliyeti ~1.7 kat model boyutu, yaklaşık 3-4 kat yavaş indeksleme ve SentencePiece bağımlılığı. Eğer Türkçe doğruluk, boyut ve süreden önceliklidir denirse B tercih edilebilir; bu durumda önce gerçek cihazda süre ölçülmeli.

## 5. Onay sonrası ml görevleri için açık noktalar

- APK boyutu: ~224-311 MB model dosyası assets'te sıkışmaz; APK/AAB boyutu ~250-350 MB olur (Play Store sınırları ve ders teslim biçimi açısından kullanıcı onayı gerekir).
- Metin tarafı: mean pooling + 768->512 Dense + L2 normalizasyon (ya da bunları ONNX'e gömme) Kotlin'de uygulanmalı; Dense ağırlığı (1.57 MB) assets'e eklenmeli. Bu, mimari belgesinde `ml` paketi ayrıntısı olarak not edilmeli.
- Önişleme: Türkçe yerel ayarla küçük harfe çevirme, uzunluk sınırı (128 token, ayrıca architecture.md Bölüm 8 girdi doğrulama).
- Görüntü önişleme: bitmap'i 224x224'e indirgeyerek (BitmapFactory inSampleSize ile büyük çözünürlüğü baştan küçük açma) serbest bırakma, toplu işleme; bellek bütçesi ölçülecek.
- Veri modeli: PhotoEmbedding vektör boyutu 512 float (2 KB/foto), `modelVersion` alanı model + niceleme kombinasyonunu içermeli.
- Doğrulama: ~50-100 gerçek galeri fotoğrafı + Türkçe istem kümesiyle int8 vs fp16 görüntü kodlayıcı karşılaştırması; telefonda süre ve bellek ölçümü.
- ONNX Runtime Android bağımlılığı ve ABI/boyut etkisi ayrıca gerekçelendirilmeli (bu araştırmada doğrulanmadı).
- Log'a fotoğraf yolu, içerik ve istem yazılmayacak; ağ erişimi yok (INTERNET izni eklenmez). Modeller yalnızca derleme öncesi tek seferlik indirilip assets'e konur, uygulama içinde indirme yok.
