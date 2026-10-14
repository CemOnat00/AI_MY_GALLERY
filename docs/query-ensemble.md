# Sorgu şablon topluluğu (T-033)

Kısa Türkçe sorgularda metin kodlayıcının kalitesini artıran, yalnızca sorgu tarafında çalışan bir katman: `ml/PromptEnsembleTextEncoder` (çıplak `OnnxTextEncoder`'ın ÜSTÜNDE). Yeniden indeksleme yok, `MODEL_VERSION` değişmez, görüntü vektörleri aynıdır. Yeni model, yeni bağımlılık, ağ, izin yoktur; kullanılan model dosyası değişmedi (sentence-transformers/clip-ViT-B-32-multilingual-v1 metin kolu, Apache-2.0; bkz. `model-setup.md`).

## Neden

Çok dilli distile metin kodlayıcı, eğitimde az görülen Türkçe TEK sözcüklerde ("bardak", "fincan", "tabak", "plaj") içeriksiz bir vektör üretir: "bardak" vektörü "plaj" ile 0,994, "cup" ile 0,915 kosinüstedir; telefonda "bardak" araması kedi fotoğrafları getirdi, "kedi" doğru çalışıyordu. Aynı sözcük bir cümle şablonuna ("bir bardak", "bir bardak fotoğrafı") yerleştirilince model sözcüğü bağlamdan daha iyi yerleştirir. Şablonlu varyantların ortalaması tek şablonun gürültüsünü azaltır.

## Seçilen küme

| | |
|---|---|
| Şablonlar | `bir {q}`, `bir {q} fotoğrafı` |
| Birleştirme | varyant vektörlerinin ortalaması (double biriktirme), ardından L2 normalize |
| Uygulama koşulu | normalleştirilmiş sorgu en çok 3 sözcük VE en çok 64 karakter; aksi halde çıplak sorgu (tek çıkarım, ensemble yok) |
| Yapı | `PromptEnsembleTextEncoder(base: TextEncoder, templates, maxWords, maxChars)`; `embeddingSpec` ve sözleşme aynen |

Bağlama: `AppDependencies.searchTextEncoder` (`SearchRepository` bunu kullanır). Isınma (`warmUpSearch`/`OnnxTextEncoder.warmUp`) ve altın testler (GoldenTextTest, GoldenTextVectorsDeviceTest) ÇIPLAK kodlayıcıda kalır; altın vektörler değişmez.

## Ölçüm

Düzenek (`tools/query_ensemble.py --eval --image-vecs <npy>`; masaüstü, ORT 1.30.0, ORİJİNAL metin modeli; tablolar bu betiğin çıktısıdır):

- 60 tek sözcüklü TR-EN çift (bardak/cup, kedi/cat, ...). TR sorgusu 60 İngilizce karşılık vektörü arasında sıralanır; doğru karşılığın sırası ölçülür. EN tarafı iki biçimde: çıplak ("cup") ve "a photo of a cup" (CLIP'in kendi dilinde, daha güçlü referans). EN vektörleri doğru-cevap DEĞİL, vekil referanstır.
- 45 çok sözcüklü çift (2: 20, 3: 8, 4+: 17; "sahilde gülen çocuk" / "a child laughing on the beach", ...).
- 141 görüntülük küme: emülatör galerisinden türetilen manzara ağırlıklı VEKİL küme (repoda yok; görüntü vektörleri Python'da görüntü modeliyle üretildi). Etiketi yok; "görüntü ov@k" = TR sorgusunun top-k görüntü kümesinin, EN "a photo of a" sorgusunun top-k kümesiyle örtüşme oranı (60 çift ortalaması). Bu gerçek hit@5 DEĞİLDİR.

### 1. Aday kümeler (60 tek sözcüklü çift)

| küme | çıkarım | top-1 (EN çıplak) | ort. sıra | top-1 (EN "a photo of a") | ort. sıra | bardak sırası | görüntü ov@5 | ov@10 |
|---|---|---|---|---|---|---|---|---|
| çıplak (şablonsuz) | 1 | 0,65 | 11,8 | 0,62 | 11,8 | 32 | 0,43 | 0,50 |
| **A [bir q, bir q fotoğrafı]** | **2** | **0,88** | **5,5** | **0,85** | **5,1** | **16** | **0,64** | **0,70** |
| B [q, bir q, bir q fotoğrafı, q fotoğrafı] | 4 | 0,83 | 5,8 | 0,80 | 5,8 | 22 | 0,62 | 0,66 |
| C [bir q fotoğrafı] | 1 | 0,85 | 6,0 | 0,85 | 5,6 | 22 | 0,65 | 0,70 |
| D [q, bir q fotoğrafı] | 2 | 0,77 | 6,7 | 0,73 | 6,5 | 26 | 0,60 | 0,64 |
| E [q fotoğrafı, bir q fotoğrafı] | 2 | 0,83 | 6,2 | 0,82 | 6,0 | 23 | 0,63 | 0,66 |
| F [q, bir q] | 2 | 0,83 | 6,2 | 0,70 | 6,2 | 20 | 0,55 | 0,62 |
| G [bir q, q fotoğrafı] | 2 | 0,87 | 5,6 | 0,82 | 5,3 | 19 | 0,61 | 0,67 |

Tek şablon (aynı düzenek, "a photo of a" referansı): `{q}` 0,62 / 11,8; `bir {q}` 0,85 / 4,7; `{q} fotoğrafı` 0,78 / 6,7; `bir {q} fotoğrafı` 0,85 / 5,6; `{q} resmi` 0,78 / 7,4; `bir {q} resmi` 0,87 / 4,9; `bu bir {q}` 0,87 / 4,2; `{q} olan bir fotoğraf` 0,85 / 5,7 (top-1 / ort. sıra).

Yorum: Çıplak sorguya göre her şablonlu küme belirgin iyileşir (top-1 0,62-0,65 -> 0,80-0,88, ort. sıra 11,8 -> 5,1-6,5). Çıplak sorguyu kümeye KATMAK (B, D, F) iyileşmeyi azaltır: çıplak vektör sorunun kaynağıdır. A, C, G birbirinden ayırt edilemez (60 çiftte bir çift = 0,017; fark gürültü içinde); A en iyi veya eş ortalama sıra/top-1 verir ve 2 çıkarımdır (B'nin yarısı). Tek çıkarımlı C da yakındır, ama iki şablonun ortalaması tek şablonun sözcüğe özgü sapmasını yumuşatır (kapsayıcı seçim, ölçümle çelişmiyor). `bir {q} resmi`, `bu bir {q}` gibi daha ince şablon ayarı 60 çiftte gürültü düzeyinde farklar verdiği için denenmedi/yapılmadı (aşırı uyum riski).

### 2. Kaç sözcüğe kadar (küme A; TR sorgusunun EN çeviri vektörüne kosinüsü)

| sözcük | çift | cos(çıplak, EN) | cos(A, EN) | fark | A daha iyi oran | cos(A, çıplak) ort. | en düşük |
|---|---|---|---|---|---|---|---|
| 1 | 60 | 0,919 | 0,960 | +0,041 | 0,98 | 0,975 | 0,876 |
| 2 | 20 | 0,931 | 0,954 | +0,023 | 0,90 | 0,989 | 0,964 |
| 3 | 8 | 0,888 | 0,939 | +0,051 | 1,00 | 0,977 | 0,884 |
| 4+ | 17 | 0,921 | 0,939 | +0,018 | 1,00 | 0,993 | 0,988 |

(EN: "a photo of ..." referansı. Çıplak EN referansında 4+ sözcükte fark ~0 (0,956 -> 0,958); 4+ grubunun n'i küçüktür.) Kazanç 1-3 sözcükte belirgin, 4+ sözcükte küçük (+0,018; sorgu zaten bağlam taşır) ve her sorguda çıkarımı iki katına çıkarmaya değmez. Karar: eşik 3 sözcük. 64 karakter sınırı sözcük sınırını tamamlar (3 çok uzun sözcük; bkz. Sınırlar).

### 3. Sık sözcüklerin çıplaktan sapması (küme A uygulanırsa)

| sorgu | sözcük | cos(ensemble, çıplak) | görüntü top-5 örtüşme (çıplak vs ensemble) |
|---|---|---|---|
| kedi | 1 | 0,984 | 0,8 |
| köpek | 1 | 0,986 | 0,6 |
| deniz | 1 | 0,991 | 0,8 |
| dağ | 1 | 0,989 | 0,8 |
| çiçek | 1 | 0,987 | 0,8 |
| bisiklet | 1 | 0,989 | 0,6 |
| ağaç | 1 | 0,992 | 0,8 |
| su bardağı | 2 | 0,991 | 0,8 |
| çay bardağı | 2 | 0,991 | 0,8 |
| gün batımı | 2 | 0,990 | 0,6 |
| kırmızı araba | 2 | 0,993 | 0,8 |
| karlı dağ | 2 | 0,988 | 0,8 |
| beyaz kedi | 2 | 0,990 | 0,6 |
| sahilde gülen çocuk | 3 | 0,986 | 0,8 |
| bir bardak çay | 3 | 0,996 | 0,8 |
| **bardak** | 1 | 0,986 | 0,4 |
| **araba** | 1 | 0,934 | 0,0 |
| fincan | 1 | 0,971 | 0,2 |
| tabak | 1 | 0,959 | 0,2 |
| plaj | 1 | 0,955 | 0,4 |

Sağlam sözcükler (kedi, köpek, deniz, ...) çıplaktan >= 0,984 kosinüste kalır (top-5 örtüşmesi 0,6-0,8: ensemble sıralamayı hafifçe, çoğu zaman bir sonuç kadar oynatır). Büyük sapma (araba 0,934, tabak, fincan, plaj) tam da çıplak vektörün kötü olduğu sözcüklerdedir (60 çiftte "araba" çıplakta 14. sırada, A'da 1.). 76 altın sorguda (küme A her sözcük sayısında uygulanmış olsaydı; betik bölüm 5a) ensemble-çıplak kosinüsü: 1 sözcük ort. 0,984 (en düşük 0,961), 2 sözcük 0,984 (0,920), 3 sözcük 0,987 (0,971), 4 sözcük 0,990, 5+ sözcük 0,989.

### 4. Hub (merkezcilik; 60 tek sözcüklü sorgunun top-5 görüntü kümesi, 141 görüntü)

| vektör | farklı görüntü sayısı | tek görüntünün en çok görüldüğü sorgu sayısı |
|---|---|---|
| TR çıplak | 58 | 34 |
| TR küme A | 68 | 28 |
| EN "a photo of a" | 71 | 22 |

Aynı birkaç görüntünün çok sayıda alakasız sorguda üstte çıkması (hub) A ile azalır ama EN düzeyine inmez.

## Maliyet

- Şablon uygulanan sorgu (<= 3 sözcük, <= 64 karakter): 2 ORT çıkarımı, ardışık; uygulanmayan: 1 çıkarım. Her çıkarım 5-12 token (kısa girdi); tokenizer maliyeti önemsiz.
- Masaüstü (bilgi, telefon DEĞİL): tek çıkarım ~4 ms (Apple Silicon Mac, 2 iş parçacığı, 11 token). Telefonda çıkarım süresi ÖLÇÜLMEDİ; ek maliyet "bir ek kısa çıkarım" olarak beklenir (mertebe tahmini, doğrulanmadı). Arama süresi büyük ölçüde indeks taraması ve oturum açılışıdır; ek çıkarım bunun yanında küçük beklenir.
- Her varyant `SingleSessionSlot` kilidini AYRI alır. Indeksleme sürerken iki varyant arasında görüntü oturumu araya girerse metin oturumu yeniden açılır (~97 MB yükleme, ölçülmedi); ensemble yalnız bu durumda bir ek oturum açılışı riski taşır. Çözüm gerekirse `OnnxTextEncoder`'a toplu `encodeAll` (tek kilit altında) eklemektir; şimdilik `TextEncoder` sözleşmesi korunduğu için yapılmadı.

## Doğruluk ve test

- Kotlin davranışı sahte `TextEncoder` ile birim testlidir (`PromptEnsembleTextEncoderTest`): ortalama + L2 normalize, determinizm, sözcük/karakter eşiği, Türkçe İ/ı ve büyük harf, geçersiz sorgunun şablondan ÖNCE reddi (base çağrılmaz), iptalin varyantlar arasında ve sonra yayılması, hata geçişi, token kırpmasına ulaşılmaması (sentetik sözlük, her karakter bir token), `SingleSessionSlot` ile ardışık/eşzamanlı çağrı.
- Python referans uyumu (`PromptEnsembleGoldenTest`): `tools/query_ensemble.py --golden` ORİJİNAL modelle 19 sorgunun varyant (çıplak kodlayıcı) ve ensemble vektörlerini `app/src/test/resources/ml/query_ensemble_golden.tsv` dosyasına yazar. JVM testi varyant vektörlerini sahte kodlayıcı yapar; Kotlin'in ürettiği varyant metinleri dosyadakilerle birebir aynı olmalı ve ensemble vektörü `expect` satırıyla eşleşmelidir (bileşen farkı < 2e-6). Gerçek ONNX üstünde ensemble bu testte ÇALIŞTIRILMAZ.
- Dosya yenileme: model değişirse (T-027 sonrası budanmış model eşdeğerdir; orijinal modelle üretilir) `python3 -I tools/query_ensemble.py --golden`.

## Sınırlar

- Bu bir Türkçe model KALİTESİ YAMASIDIR, sorunu kökten çözmez. "bardak" hâlâ iyi değildir: "a photo of a cup" ile kosinüs 0,868 -> 0,905, 60 çiftte sırası 32 -> 16 (en yakın İngilizce kavramlar hâlâ man/woman/food). Nadir sözcüklerde sonuç düzelir ama kusursuz olmaz.
- Görüntüde gerçek hit@5 BU GÖREVDE ÖLÇÜLMEDİ: ölçüm masaüstü vekili (TR-EN çiftlerde sıralama, 141 görüntülük etiketsiz küme). Gerçek galeride hit@5 qa'nın resimli testiyle doğrulanacaktır; vekil ölçümlerin cihaz sonucunu garanti ettiği iddia edilmez. Cihazdaki ORT (arm64 nicemli çekirdek) masaüstünden hafifçe farklı vektör üretir.
- 60 çiftlik ölçüm küçüktür; yakın kümeler (A/C/G) arasındaki fark gürültüdür, seçim maliyet ve gerekçeyle yapıldı. Çiftler ve EN karşılıklar elle seçildi.
- "bir" ile başlayan sorgu ("bir bardak çay") şablonda "bir bir bardak çay" olur; ölçümde zararsız (cos(şablonlu, çıplak) = 0,996) bulundu, ayrı kural eklenmedi.
- Uzunluk: kurucu `maxChars + şablon fazlası + 2 <= 128` koşulunu zorlar (token >= 1 karakter), bu yüzden şablonlu varyant 128 token kırpmasına ve 256 karakter sınırına ulaşamaz; uzun sorgu çıplak yola düşer (şablon sessizce kırpılmaz, `InvalidQueryException` varyantta oluşmaz).
- Anlamdaş elle genişletme (ör. "bardak" -> "cup", "glass") ve İngilizce karşılık EKLENMEDİ: "çeviri yok" kararı (Türkçe istem Türkçe kalır; sözlük/çeviri tablosu bakım yükü ve kapsam sınırı getirir). Tek ek davranış, aynı sözcüğün Türkçe şablonlara gömülmesidir.
- Doğrulanmayanlar: telefon gecikmesi, telefonda hit@5, indeksleme sürerken varyantlar arası oturum açılışı maliyeti.
