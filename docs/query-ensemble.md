# Sorgu şablon topluluğu (T-033)

Kısa Türkçe sorgularda metin kodlayıcının kalitesini artıran, yalnızca sorgu tarafında çalışan bir katman: `ml/PromptEnsembleTextEncoder` (çıplak `OnnxTextEncoder`'ın ÜSTÜNDE). Yeniden indeksleme yok, `MODEL_VERSION` değişmez, görüntü vektörleri aynıdır. Yeni model, yeni bağımlılık, ağ, izin yoktur; kullanılan model dosyası değişmedi (sentence-transformers/clip-ViT-B-32-multilingual-v1 metin kolu, Apache-2.0; bkz. `model-setup.md`).

## Neden

Çok dilli distile metin kodlayıcı, eğitimde az görülen Türkçe TEK sözcüklerde ("bardak", "fincan", "tabak", "plaj") içeriksiz bir vektör üretir: Önceki analizde çıplak "bardak" vektörü çıplak "plaj" ile 0,994 ve çıplak "cup" ile 0,915 kosinüstedir (tek sözcük-tek sözcük; aşağıdaki "a photo of a cup" referansından farklı bir ölçümdür); telefonda "bardak" araması kedi fotoğrafları getirdi, "kedi" doğru çalışıyordu. Gözlem (ölçüldü, bölüm 1-3): aynı sözcük bir cümle şablonuna ("bir bardak", "bir bardak fotoğrafı") yerleştirilince TR-EN çiftlerde sıralama kalitesi artıyor. Nedeni ölçülmedi; şablonlu varyantların ortalaması yalnızca tek şablonun sözcüğe özgü sapmasını yumuşatmak için seçildi.

## Seçilen küme

| | |
|---|---|
| Şablonlar | `bir {q}`, `bir {q} fotoğrafı` |
| Birleştirme | varyant vektörlerinin ortalaması (double biriktirme), ardından L2 normalize |
| Uygulama koşulu | normalleştirilmiş sorgu en çok 3 sözcük VE en çok 64 karakter; aksi halde çıplak sorgu (tek çıkarım, ensemble yok) |
| Çift şablon önleme | sorgu "bir"/sayı sözcüğü (bir, iki, üç, dört, beş, altı, yedi, sekiz, dokuz, on) ya da rakamla başlıyorsa şablonun ön eki ("bir ") eklenmez; sorgu fotoğraf/fotoğrafı/foto/resim/resmi ile bitiyorsa son eki (" fotoğrafı") eklenmez. Çıplakla aynı kalan veya tekrar eden varyant elenir; hiç varyant kalmazsa çıplak sorgu. Örnek: "iki kişi" -> `iki kişi fotoğrafı`; "kedi fotoğrafı" -> `bir kedi fotoğrafı`; "bir kedi fotoğrafı" -> çıplak (bölüm 6) |
| Oturum | `OnnxTextEncoder` `BatchTextEncoder.encodeAll` sunar: tüm varyantlar TEK `SingleSessionSlot` kilidi altında kodlanır |
| Yapı | `PromptEnsembleTextEncoder(base: TextEncoder, templates, maxWords, maxChars)`; `embeddingSpec` ve sözleşme aynen |

Bağlama: `AppDependencies.searchTextEncoder` (`SearchRepository` bunu kullanır). Isınma (`warmUpSearch`/`OnnxTextEncoder.warmUp`) ve altın testler (GoldenTextTest, GoldenTextVectorsDeviceTest) ÇIPLAK kodlayıcıda kalır; altın vektörler değişmez.

## Ölçüm

Düzenek (masaüstü, ORT 1.30.0, ORİJİNAL metin modeli; tablolar betiğin çıktısıdır, `### N.` başlıkları betiğin `## N.` bölümleriyle aynı numaralıdır). Çalıştırma (proje kökünde; venv `docs/golden-text.md` kurulumundaki gibi repo dışında):

```
~/venvs/aigaleri-ort/bin/python -I tools/query_ensemble.py --eval --image-vecs /yol/imgvecs.npy   # ölçüm tabloları (stdout)
~/venvs/aigaleri-ort/bin/python -I tools/query_ensemble.py --golden                               # altın dosyayı yeniler
```

(Bu görevdeki koşular oturum scratchpad'indeki `ort_venv/bin/python` ile yapıldı; aynı onnxruntime 1.30.0 + numpy.) `--image-vecs` verilmezse görüntü sütunları "-" olur.

- 60 tek sözcüklü TR-EN çift (bardak/cup, kedi/cat, ...). TR sorgusu 60 İngilizce karşılık vektörü arasında sıralanır; doğru karşılığın sırası ölçülür. EN tarafı iki biçimde: çıplak ("cup") ve "a photo of a cup" (CLIP'in kendi dilinde, daha güçlü referans). EN vektörleri doğru-cevap DEĞİL, vekil referanstır.
- 45 çok sözcüklü çift (bölüm 3; 2: 20, 3: 8, 4+: 17; "sahilde gülen çocuk" / "a child laughing on the beach", ...).
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

### 2. Tek şablon

Aynı düzenek, "a photo of a" referansı: `{q}` 0,62 / 11,8; `bir {q}` 0,85 / 4,7; `{q} fotoğrafı` 0,78 / 6,7; `bir {q} fotoğrafı` 0,85 / 5,6; `{q} resmi` 0,78 / 7,4; `bir {q} resmi` 0,87 / 4,9; `bu bir {q}` 0,87 / 4,2; `{q} olan bir fotoğraf` 0,85 / 5,7 (top-1 / ort. sıra).

Yorum: Çıplak sorguya göre her şablonlu küme iyileşir: iki referansta da top-1 0,62-0,65 -> 0,70-0,88, ort. sıra 11,8 -> 5,1-6,7 (en zayıf kümeler D ve F). Çıplak sorguyu kümeye KATMAK (B, D, F) iyileşmeyi azaltır: çıplak vektör sorunun kaynağıdır. A, C, G birbirinden ayırt edilemez (60 çiftte bir çift = 0,017; fark gürültü içinde); A en iyi veya eş ortalama sıra/top-1 verir ve 2 çıkarımdır (B'nin yarısı). Tek çıkarımlı C da yakındır, ama iki şablonun ortalaması tek şablonun sözcüğe özgü sapmasını yumuşatır (kapsayıcı seçim, ölçümle çelişmiyor). `bir {q} resmi`, `bu bir {q}` gibi daha ince şablon ayarı 60 çiftte gürültü düzeyinde farklar verdiği için denenmedi/yapılmadı (aşırı uyum riski).

### 3. Kaç sözcüğe kadar (küme A; TR sorgusunun EN çeviri vektörüne kosinüsü)

| sözcük | çift | cos(çıplak, EN) | cos(A, EN) | fark | A daha iyi oran | cos(A, çıplak) ort. | en düşük |
|---|---|---|---|---|---|---|---|
| 1 | 60 | 0,919 | 0,960 | +0,041 | 0,98 | 0,975 | 0,876 |
| 2 | 20 | 0,931 | 0,954 | +0,023 | 0,90 | 0,989 | 0,964 |
| 3 | 8 | 0,888 | 0,939 | +0,051 | 1,00 | 0,977 | 0,884 |
| 4+ | 17 | 0,921 | 0,939 | +0,018 | 1,00 | 0,993 | 0,988 |

(EN: "a photo of ..." referansı. Çıplak EN referansında 4+ sözcükte fark ~0 (0,956 -> 0,958); 4+ grubunun n'i küçüktür.) Kazanç 1-3 sözcükte belirgin, 4+ sözcükte küçük (+0,018; sorgu zaten bağlam taşır) ve her sorguda çıkarımı iki katına çıkarmaya değmez. Karar: eşik 3 sözcük. 64 karakter sınırı sözcük sınırını tamamlar (3 çok uzun sözcük; bkz. Sınırlar).

### 4. Sık sözcüklerin çıplaktan sapması (küme A uygulanırsa)

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

Sağlam sözcükler (kedi, köpek, deniz, ...) çıplaktan >= 0,984 kosinüste kalır (top-5 örtüşmesi 0,6-0,8: ensemble sıralamayı hafifçe, çoğu zaman bir sonuç kadar oynatır). Büyük sapma (araba 0,934, tabak, fincan, plaj) tam da çıplak vektörün kötü olduğu sözcüklerdedir (60 çiftte "araba" çıplakta 14. sırada, A'da 1.). 76 altın sorguda (küme A her sözcük sayısında uygulanmış olsaydı; betik bölüm 5) ensemble-çıplak kosinüsü: 1 sözcük ort. 0,984 (en düşük 0,961), 2 sözcük 0,984 (0,920), 3 sözcük 0,987 (0,971), 4 sözcük 0,990, 5+ sözcük 0,989.

### 5. Altın sorgularda sapma

Bölüm 4'ün son paragrafındaki 76 altın sorgu ölçümü betiğin bölüm 5'idir (küme A, ön/son ek kuralı olmadan, her sözcük sayısında uygulanmış olsaydı).

### 6. Ön/son ek kuralı (çift şablon önleme)

Neden: şablonu olduğu gibi uygulamak "bir kedi" için "bir bir kedi", "iki kişi" için "bir iki kişi" (sayı anlamı bozulur), "kedi fotoğrafı" için "bir kedi fotoğrafı fotoğrafı" üretirdi. Ölçüm: 12 sorgunun tam İngilizce referans cümlesine kosinüsü ve o 12 referans arasındaki sırası; "kuralsız A" = iki şablon olduğu gibi uygulanmış.

| sorgu | EN referans | çıplak cos (sıra) | kuralsız A cos (sıra) | kurallı A cos (sıra) | kurallı varyantlar |
|---|---|---|---|---|---|
| bir kedi | a photo of a cat | 0,980 (1) | 0,988 (1) | 0,987 (1) | bir kedi fotoğrafı |
| kedi fotoğrafı | a photo of a cat | 0,973 (1) | 0,982 (1) | 0,987 (1) | bir kedi fotoğrafı |
| iki kişi | a photo of two people | 0,958 (1) | 0,943 (1) | 0,975 (1) | iki kişi fotoğrafı |
| üç çocuk | a photo of three children | 0,960 (1) | 0,964 (1) | 0,968 (1) | üç çocuk fotoğrafı |
| dört kedi | a photo of four cats | 0,961 (1) | 0,961 (1) | 0,971 (1) | dört kedi fotoğrafı |
| 5 araba | a photo of five cars | 0,856 (7) | 0,938 (1) | 0,942 (1) | 5 araba fotoğrafı |
| 10 kişi | a photo of ten people | 0,941 (1) | 0,944 (1) | 0,951 (1) | 10 kişi fotoğrafı |
| bir bardak çay | a photo of a cup of tea | 0,969 (1) | 0,980 (1) | 0,983 (1) | bir bardak çay fotoğrafı |
| deniz resmi | a photo of the sea | 0,980 (1) | 0,989 (1) | 0,988 (1) | bir deniz resmi |
| bir köpek fotoğrafı | a photo of a dog | 0,985 (1) | 0,981 (1) | 0,985 (1) | (çıplak) |
| bir deniz resmi | a photo of a sea | 0,983 (2) | 0,985 (2) | 0,983 (2) | (çıplak) |
| bir bardak | a photo of a cup | 0,901 (6) | 0,907 (6) | 0,902 (9) | bir bardak fotoğrafı |

Yorum: Kurallı küme çıplaktan hiçbir sorguda kötü değil (10 sorguda yüksek, 2'sinde eşit: kural çıplağa düşürür) ve sayılı sorgularda kuralsız A'dan belirgin iyi ("iki kişi" 0,943 -> 0,975; kuralsız A burada çıplaktan KÖTÜYDÜ). Kuralsız A'dan hafif düşük 4 sorgu var ("bir kedi", "deniz resmi", "bir deniz resmi", "bir bardak": en çok 0,005 kosinüs); "bir bardak" referanslar arasındaki sırası da 6 -> 9 (kosinüs çıplakla eş), yani tek-sözcüklü "bardak" sorunu "bir bardak" yazılınca da çözülmez. n=12 küçük bir örnektir; kural, kosinüsten çok "çift 'bir' / bozulan sayı anlamı" mantığıyla savunulur ve ölçümle çelişmez.

### 7. Hub (merkezcilik; 60 tek sözcüklü sorgunun top-5 görüntü kümesi, 141 görüntü)

| vektör | farklı görüntü sayısı | tek görüntünün en çok görüldüğü sorgu sayısı |
|---|---|---|
| TR çıplak | 58 | 34 |
| TR küme A | 68 | 28 |
| EN "a photo of a" | 71 | 22 |

Aynı birkaç görüntünün çok sayıda alakasız sorguda üstte çıkması (hub) A ile azalır ama EN düzeyine inmez.

### 8. Masaüstü çıkarım süresi

Tek çıkarım ~4 ms (11 token; Apple Silicon Mac, ORT 1.30.0, 2 iş parçacığı, ardışık 100 çalıştırma). Telefon DEĞİL.

## Maliyet

- Şablon uygulanan sorgu (<= 3 sözcük, <= 64 karakter): 2 ORT çıkarımı, ardışık (ön/son ek kuralı bir varyantı elerse 1); uygulanmayan: 1 çıkarım. Her çıkarım 5-12 token (kısa girdi); tokenizer maliyeti önemsiz.
- Masaüstü (bilgi, telefon DEĞİL, bölüm 8): tek çıkarım ~4 ms. Telefonda çıkarım süresi ÖLÇÜLMEDİ; ek maliyet "bir ek kısa çıkarım" olarak beklenir (mertebe tahmini, doğrulanmadı). Arama süresi büyük ölçüde indeks taraması ve oturum açılışıdır; ek çıkarım bunun yanında küçük beklenir.
- Oturum: `OnnxTextEncoder` ml-içi `BatchTextEncoder.encodeAll`'ı uygular ve `PromptEnsembleTextEncoder` taban kodlayıcı `BatchTextEncoder` ise onu kullanır: tüm varyantlar TEK `SingleSessionSlot.withSession` altında, tek kilitle kodlanır (önce hepsi doğrulanır, her çıkarımdan önce iptal denetlenir). İndeksleme sürerken (kilit FIFO) varyantlar arasına görüntü oturumu giremez; arama başına en çok tek metin oturumu açılışı olur (açılış ~97 MB, ölçülmedi, ama artık ikiye katlanmaz). `TextEncoder` sözleşmesi değişmedi; batch olmayan taban için varyantlar tek tek `encode` ile kodlanır (testte: o yolda araya eviction girerse iki açılış, toplu yolda bir).

## Doğruluk ve test

- Kotlin davranışı sahte `TextEncoder` ile birim testlidir (`PromptEnsembleTextEncoderTest`): ortalama + L2 normalize, determinizm, sözcük/karakter eşiği, Türkçe İ/ı ve büyük harf, geçersiz sorgunun şablondan ÖNCE reddi (base çağrılmaz), iptalin varyantlar arasında ve sonra yayılması, hata geçişi, token kırpmasına ulaşılmaması (sentetik sözlük, her karakter bir token), ön/son ek kuralı (bir/sayı/rakam, foto/resim; "bira", "onur" gibi tam sözcük olmayanlar şablonlanır), `BatchTextEncoder` yolu (tek `encodeAll`, aynı sonuç, iptal, hata, boyut/sayı ihlali), `SingleSessionSlot` ile ardışık/eşzamanlı çağrı ve deterministik eviction (toplu olmayan taban: 2 oturum açılışı; toplu taban: 1). `OnnxTextEncoderTest` `encodeAll` doğrulama/hata yollarını sınar (ORT JVM'de yok).
- Python referans uyumu (`PromptEnsembleGoldenTest`): `tools/query_ensemble.py --golden` ORİJİNAL modelle 26 sorgunun varyant (çıplak kodlayıcı) ve ensemble vektörlerini `app/src/test/resources/ml/query_ensemble_golden.tsv` dosyasına yazar. JVM testi varyant vektörlerini sahte kodlayıcı yapar; Kotlin'in ürettiği varyant metinleri dosyadakilerle birebir aynı olmalı ve ensemble vektörü `expect` satırıyla eşleşmelidir (bileşen farkı < 2e-6). Aynı dosya hem tek tek `encode` hem toplu (`BatchTextEncoder`) tabanla sınanır. Gerçek ONNX üstünde ensemble JVM testinde ÇALIŞTIRILMAZ; cihaz kapısı `PromptEnsembleDeviceTest` (androidTest, `expect` vektörlerine kosinüs >= 0,999 ve `AppDependencies.searchTextEncoder`'ın şablon topluluğu olduğunun sınanması): YAZILDI ve DERLENDİ, ÇALIŞTIRILMADI (qa çalıştırır).
- Dosya yenileme: model değişirse (T-027 sonrası budanmış model eşdeğerdir; orijinal modelle üretilir) `~/venvs/aigaleri-ort/bin/python -I tools/query_ensemble.py --golden` (komut yukarıda).

## Sınırlar

- Bu bir Türkçe model KALİTESİ YAMASIDIR, sorunu kökten çözmez. "bardak" hâlâ iyi değildir: "a photo of a cup" ile kosinüs 0,868 -> 0,905, 60 çiftte sırası 32 -> 16 (en yakın İngilizce kavramlar hâlâ man/woman/food). Nadir sözcüklerde sonuç düzelir ama kusursuz olmaz.
- Görüntüde gerçek hit@5 BU GÖREVDE ÖLÇÜLMEDİ: ölçüm masaüstü vekili (TR-EN çiftlerde sıralama, 141 görüntülük etiketsiz küme). Gerçek galeride hit@5 qa'nın resimli testiyle doğrulanacaktır; vekil ölçümlerin cihaz sonucunu garanti ettiği iddia edilmez. Cihazdaki ORT (arm64 nicemli çekirdek) masaüstünden hafifçe farklı vektör üretir.
- 60 çiftlik ölçüm küçüktür; yakın kümeler (A/C/G) arasındaki fark gürültüdür, seçim maliyet ve gerekçeyle yapıldı. Çiftler ve EN karşılıklar elle seçildi.
- Ön/son ek kuralı yalnızca varsayılan şablonlar ("bir " ön eki, " fotoğrafı" son eki) için tasarlandı ve ölçüldü (bölüm 6, n=12); farklı şablonlarla yeniden ölçülmelidir. Sayı sözcüğü listesi yalnızca 1-10'dur ("yirmi", "yüz" için ön ek eklenir).
- Uzunluk: kurucu `maxChars + şablon fazlası + 2 <= 128` koşulunu zorlar (token >= 1 karakter), bu yüzden şablonlu varyant 128 token kırpmasına ve 256 karakter sınırına ulaşamaz; uzun sorgu çıplak yola düşer (şablon sessizce kırpılmaz, `InvalidQueryException` varyantta oluşmaz).
- Anlamdaş elle genişletme (ör. "bardak" -> "cup", "glass") ve İngilizce karşılık EKLENMEDİ: "çeviri yok" kararı (Türkçe istem Türkçe kalır; sözlük/çeviri tablosu bakım yükü ve kapsam sınırı getirir). Tek ek davranış, aynı sözcüğün Türkçe şablonlara gömülmesidir.
- Doğrulanmayanlar: telefon gecikmesi, telefonda hit@5, oturum açılış süresi (~97 MB) ve `PromptEnsembleDeviceTest` sonucu (cihazda çalıştırılmadı).
