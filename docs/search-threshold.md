# Arama sonuç eşiği kalibrasyonu (T-013, Cem kararı K-7)

Sorun: indeksli galeride anlamsız istem ("asdfgh") bile her zaman en çok 50 sonuç döndürüyordu; UI `Empty` yalnızca indeks boşken görünüyordu. Karar K-7: göreli eşik, hiç eşleşme yoksa "bulunamadı". Bu belge ölçümü, seçilen değerleri ve SINIRLARI anlatır. Kural kodu: `data/SearchPolicy.kt`; bağlantı: `RoomSearchRepository(policy = ...)` (sınıf varsayılanı `SearchPolicy.OFF`, eskisiyle bit-bit aynı) ve `AppDependencies` (`SearchPolicy.OFF`: politika uygulamada KAPALI bağlıdır, aşağıdaki "Durum ve açma adımı" bölümü).

## Sonuç (kısa)

- Mutlak taban ilgili ve ilgisiz sorguları AYIRMIYOR: VAR sorgu top1 aralığı 0,226-0,311; YOK+anlamsız 0,224-0,286 (YAKIN grubu 0,308'e kadar). Örtüşme 0,059.
- Göreli marj da AYIRMIYOR: ilgisiz sorgularda skor dağılımı DÜZ (top1 - 10. skor medyanı anlamsızda 0,010, ilgili sorguda 0,05-0,06), ama bu fark kesim için kullanılacak kadar temiz değil ve `bardak` gibi içeriksiz vektörlü VAR sorgularda da düz (aşağıda).
- Bu yüzden aday değerler KONSERVATİF seçildi: "yalnızca belirgin çöp". Taban 0,20, marj 0,12 (`SearchPolicy.DEFAULT`). Ölçülen 102 sorguda hiçbir sorgu BOŞ dönmüyor ve ölçüm kümesinde hiçbir ilgili sonuç elenmiyor.
- SONUÇ: K-7 hedefi ("anlamsız istem -> bulunamadı") bu model ve bu ölçekte (60 foto, emülatör) SAĞLANAMADI. PO kararı: politika altyapısı kalır, uygulamada KAPALI bağlıdır (`SearchPolicy.OFF`); yanlış "bulunamadı" riski ölçülmedi.

## Durum ve açma adımı

- Durum: `AppDependencies.searchRepository` `policy = SearchPolicy.OFF` ile bağlıdır (davranış T-008 ile bit-bit aynı: her zaman top-K). `AppDependenciesSearchWiringTest.searchRepository_policyIsOff_untilReMeasuredOnPhone` bunu sabitler.
- Açma (tek satır): `AppDependencies.kt` içinde `policy = SearchPolicy.OFF` -> `policy = SearchPolicy.DEFAULT` (değerler gerekirse `SearchPolicy.DEFAULT`'ta). Açınca wiring testini de güncelleyin.
- Yeniden ölçüm (Cem'in telefonunda, açmadan önce) ve 4 sorgu grubu için bkz. "Yeniden ölçüm yöntemi" (Sınırlar bölümünün sonunda).
- Yan etki, açılırsa: `SearchViewModel` boş listeyi `Empty`'ye çevirir; `Empty` iki anlam taşır (indeks boş / eşleşme yok); T-014 ayırmak isterse indeks sayısına bakmalıdır.

## Düzenek

- Emülatör `Pixel_8` arm64 API 35, `androidTest` `SearchThresholdCalibrationDeviceTest` (`com.ktu.aigaleri.ml`). Emülatör SME/KleidiAI SIGILL geçici çözümü yalnızca test tarafında (`OrtEmulatorWorkaround`); üretim kodu değişmedi.
- Galeri: 60 etiketli açık lisanslı test fotoğrafı, 10 sınıf (bardak, kedi, köpek, araba, deniz, dağ, çiçek, pizza, bisiklet, kitap; sınıf başına 6), gerçek `RoomPhotoIndexer` + gerçek görüntü modeli ile bellek içi Room'a indekslendi. Görseller repoda YOK (`/sdcard/Pictures/aigaleri-test/`).
- Sorgu kodlayıcı: aramanın kullandığı `AppDependencies.searchTextEncoder` (T-033 şablon topluluğu: `bir {q}` + `bir {q} fotoğrafı`, <=3 sözcük). Skorlar bu yüzden "çıplak" kosinüsten düşüktür (iki vektörün ortalaması yeniden normalize edilir); eşikler yalnızca bu kodlayıcıyla geçerlidir. Kodlayıcı veya şablon değişirse yeniden kalibre edin.
- 102 sorgu: VAR 40 (10 sınıf x [çıplak TR, "bir X fotoğrafı", 2 çok sözcüklü]; etiket = beklenen sınıf), YOK 25 (zürafa, uçak, gitar, pasta, saat, ayakkabı, bilgisayar, fil, helikopter, telefon, tren, piyano, şemsiye, balon, kamyon, buzdolabı, çanta, gözlük, futbol topu, ceket + 5 çok sözcüklü), YAKIN 8 (galeride olmayan ama sınıfa yakın kavram: tekne, aslan, kurt, motosiklet, dergi, kahve fincanı, tepe, gül), ANLAMSIZ 19 (asdfgh, xqzvk, aaaaaa, 123456, ". , !", emoji, tek harf, qwertyuiop, lorem ipsum dolor ...), GENEL 10 (fotoğraf, resim, güzel bir manzara, manzara, doğa ...). Çıktı: instrumentation logcat etiketi `ThresholdCalib` ve `getExternalFilesDir/th-report.txt`; yalnızca sabit sorgu metni ve sayılar yazılır, uygulama koduna log eklenmedi.
- Tek koşu (aynı model, aynı görüntüler). ARM gürültüsü ~2e-3 (docs/golden-text.md, T-035) marj/taban için güvenlik payı ölçüsüdür; tekrarlı koşu varyansı ölçülmedi.

## Ölçüm 1: top1 dağılımı (kosinüs)

| grup | n | top1 min | medyan | maks | (top1 - s10) medyan | (top1 - s50) medyan |
|---|---|---|---|---|---|---|
| VAR çıplak | 10 | 0,2410 | 0,2758 | 0,2961 | 0,0512 | 0,0902 |
| VAR "bir X fotoğrafı" | 10 | 0,2452 | 0,2799 | 0,2992 | 0,0505 | 0,0937 |
| VAR çok sözcüklü | 20 | 0,2264 | 0,2764 | 0,3109 | 0,0640 | 0,1068 |
| YOK | 20 | 0,2243 | 0,2343 | 0,2855 | 0,0200 | 0,0508 |
| YOK çok sözcüklü | 5 | 0,2246 | 0,2305 | 0,2648 | 0,0247 | 0,0622 |
| YAKIN | 8 | 0,2407 | 0,2586 | 0,3082 | 0,0342 | 0,0655 |
| ANLAMSIZ | 19 | 0,2275 | 0,2409 | 0,2467 | 0,0101 | 0,0314 |
| GENEL | 10 | 0,2341 | 0,2430 | 0,2591 | 0,0128 | 0,0407 |

Özet: min(VAR top1) 0,2264 - maks(YOK+anlamsız top1) 0,2855 = -0,059 (ÖRTÜŞÜYOR). Yalnızca kısa VAR sorgularda (<=3 sözcük): min 0,2410, ilgisizlerin maksimumu yine 0,2855 (`şemsiye`; `bir zürafa fotoğrafı` 0,2648, `fil` 0,2579, `zürafa` 0,2578). Ayrıca `kahve fincanı` 0,3082 (galerideki `bardak` sınıfına anlamca yakın; doğru bir eşleşme sayılabilir, bu yüzden YAKIN ayrı tutuldu).

Sorgu başına örnekler (top1 / 10. / 50. skor; ilgilinin en düşük skoru):

| sorgu | top1 | s10 | s50 | en düşük ilgili |
|---|---|---|---|---|
| kedi | 0,2827 | 0,2151 | 0,1736 | 0,2371 |
| çimde yatan kedi | 0,3074 | 0,2188 | 0,1587 | 0,2209 |
| kanepede uyuyan sevimli kedi (en zayıf VAR top1) | 0,2264 | 0,1523 | 0,0922 | 0,1328 |
| bardak (düz dağılım) | 0,2410 | 0,2314 | 0,2059 | 0,2250 |
| bardakta su (ilk ilgili 6. sırada) | 0,2570 | 0,2336 | 0,1921 | 0,2224 |
| zürafa | 0,2578 | 0,2156 | 0,1867 | - |
| uçak | 0,2243 | 0,2100 | 0,1839 | - |
| asdfgh | 0,2353 | 0,2291 | 0,2064 | - |
| 😀 / 🔥🔥🔥 (skorlar birebir aynı: büyük olasılıkla ikisi de [UNK], doğrulanmadı) | 0,2423 | 0,2347 | 0,2110 | - |
| fotoğraf | 0,2417 | 0,2322 | 0,2104 | - |

## Ölçüm 2: mutlak taban (top1 < taban ise liste boş)

Tablo: o tabanla BOŞ dönen sorguların oranı. VAR sütunu YANLIŞ boş oranıdır (0 olmalı).

| taban | VAR | VAR kısa | YOK+anlamsız | YAKIN | GENEL |
|---|---|---|---|---|---|
| 0,200-0,220 | 0,00 | 0,00 | 0,00 | 0,00 | 0,00 |
| 0,225 | 0,00 | 0,00 | 0,05 | 0,00 | 0,00 |
| 0,230 | 0,03 | 0,00 | 0,14 | 0,00 | 0,00 |
| 0,240 | 0,05 | 0,00 | 0,59 | 0,00 | 0,20 |
| 0,250 | 0,10 | 0,06 | 0,91 | 0,25 | 0,90 |
| 0,260 | 0,13 | 0,09 | 0,95 | 0,63 | 1,00 |
| 0,270 | 0,40 | 0,35 | 0,98 | 0,88 | 1,00 |

Yorum: ilgisizlerin çoğunu yakalayan tabanlar (>=0,24) ilgili sorguların da %5-40'ını boş yapar (her biri "kullanıcı doğru fotoğrafı var iken bulunamadı görür"). 0,225 altında ilgili sorgu kaybı yok, ama ilgisizlerin yalnızca ~%5'i boş döner (0,225 satırı: 2/44 sorgu, `uçak` 0,2243 ve bir YOK çok sözcüklü sorgu `gökyüzünde uçan uçak` 0,2246; ikisi de 0,225 altında; 0,20-0,22 satırlarında 0/44). Yanlış boş (var olan fotoğrafın bulunamaması) yanlış dolu (alakasız liste) hatasından kötü sayıldı: taban ölçülen en düşük VAR top1'in (0,2264) güvenlik payıyla altına konuldu.

## Ölçüm 3: göreli marj (skor < top1 - marj elenir)

"Tüm ilgililer korunan sorgu" = o sorgunun 50'lik listesindeki bütün etiketi doğru sonuçların marj içinde kalması. hit@5 ve MRR bu marjlarda DEĞİŞMEZ (baz: hit@5 0,98; MRR 0,95): gereken marj hit@5 için en çok 0,0027, ilk ilgili sonuç (MRR) için en çok 0,0179.

| marj | VAR tüm ilgililer korunan sorgu | VAR ort. sonuç | YOK ort. sonuç | YAKIN ort. | ANLAMSIZ ort. | ANLAMSIZ sonuç <=10 oranı |
|---|---|---|---|---|---|---|
| 0,010 | 0,00 | 3,0 | 3,7 | 4,6 | 8,1 | 0,58 |
| 0,020 | 0,08 | 5,3 | 10,6 | 9,1 | 25,0 | 0,11 |
| 0,030 | 0,30 | 7,5 | 20,9 | 14,1 | 41,7 | 0,00 |
| 0,050 | 0,78 | 12,2 | 43,7 | 28,1 | 58,1 | 0,00 |
| 0,060 | 0,93 | 16,6 | 51,1 | 36,6 | 59,6 | 0,00 |
| 0,080 | 0,95 | 29,0 | 57,7 | 49,5 | 60,0 | 0,00 |
| 0,100 | 1,00 | 42,9 | 59,7 | 54,3 | 60,0 | 0,00 |

(Galeri 60 fotoğraf; "sonuç sayısı" üst sınırı 60.) Tüm ilgililerin korunması için gereken marj sorgu başına: min 0,0152, medyan 0,0372, MAKS 0,0936. Yani ölçüm kümesinde ilgili sonuçları kaybetmeyen marjda (>= 0,094) ilgisiz sorgularda da neredeyse hiçbir sonuç elenmez; ilgisizleri eleyen marjlar (0,01-0,03) ilgili sonuçların %20-60'ını da eler. Ayrışma YOK.

Düzlük gözlemi (uygulanmadı): ANLAMSIZ sorguların (top1 - s10)'u en çok 0,0208, VAR sorguların `bardak` sınıfı dışındakilerinde >=0,0234; "10. sonuç top1'e çok yakınsa boş" kuralı anlamsızı yakalardı. Ama (a) `bardak` (0,0096) ve `bir bardak fotoğrafı` (0,0068) VAR sorguları da düz (T-033'te anlatılan içeriksiz vektör), yani hit@5 düşerdi; (b) değer galeri yoğunluğuna bağlıdır: büyük galeride benzer fotoğraf çoktur, ilgili sorguda da 10. skor top1'e yaklaşır. Bu yüzden eklenmedi; PO kararı gerektirir (yeni kural, farklı ölçüm).

## Seçilen değerler

| parametre | değer | gerekçe |
|---|---|---|
| `minTop1Score` (taban) | 0,20 | ölçülen en düşük VAR top1'in (0,2264) 0,026 altı (~13 x ARM gürültüsü 2e-3); ölçüm kümesinde hiçbir sorguyu boşaltmaz, yalnızca belirgin çöp için ağ |
| `margin` | 0,12 | ölçülen en büyük (top1 - en düşük ilgili) 0,0936 üstüne 0,026 pay; hit@5, MRR ve ilgili sonuç sayısı değişmez |

Etki (`SearchPolicy.DEFAULT`, gerçek `RoomSearchRepository`, limit 50, emülatör, 60 foto) ÖNCE -> SONRA:

| grup | hit@5 | MRR | ort. sonuç sayısı | boş dönen sorgu |
|---|---|---|---|---|
| VAR çıplak (10) | 1,00 -> 1,00 | 1,00 -> 1,00 | 50 -> 50 | 0 -> 0 |
| VAR "bir X fotoğrafı" (10) | 1,00 -> 1,00 | 0,93 -> 0,93 | 50 -> 50 | 0 -> 0 |
| VAR çok sözcüklü (20) | 0,95 -> 0,95 | 0,93 -> 0,93 | 50 -> 43,6 | 0 -> 0 |
| YOK (20+5) | - | - | 50 -> 50 | 0 -> 0 |
| YAKIN (8) | - | - | 50 -> 49,3 | 0 -> 0 |
| ANLAMSIZ (19) | - | - | 50 -> 50 | 0 -> 0 |
| GENEL (10) | - | - | 50 -> 50 | 0 -> 0 |

VAR sorgularda elenen ilgili sonuç: 0 (ölçüm verisinden türetildi: androidTest hit@5/MRR düşmemesini, sıra-önek koşulunu ve `relDropped == 0` değerini assert eder; yalnızca bu 60 fotoluk kümede). Önceki T-033 raporundaki "hit@5 1,00 / MRR 1,00" 6 çıplak TR sınıf sorgusuna aittir; bu kümedeki çok sözcüklü ve "bir X fotoğrafı" sorguları daha zordur (örn. `bardakta su` ilk ilgili sonuç 6. sırada).

## Davranış sözleşmesi

Birim testleri: `SearchPolicyTest` 21 test (kural ve `RoomSearchRepository` entegrasyonu, sabit veri); `AppDependenciesSearchWiringTest`'e 1 test eklendi (politika OFF bağlı).

- Taban: `top1 < taban` -> boş liste; `top1 == taban` korunur. Marj: `skor >= top1 - marj` kalır; top1'e eşit skorlar hiçbir zaman elenmez (eşitlik bölünmez, `photoId` artan sırası korunur).
- Sonuç girdinin sıra korunmuş alt kümesidir (yeni öğe yok, `limit` aşılmaz). `OFF`: aynı liste, eski davranış.
- Boş indeks: boş liste (mevcut semantik). Tek sonuç: taban üstündeyse kalır. Tüm sonuçlar taban altı: boş. Negatif skor geçerli. +-Inf skor, kural açıkken elenir (tarama zaten sonlu olmayanı atlar; NaN `SearchResult`'ta kurulamaz).
- `minScore` (kayıt başına, varsayılan null) ayrı mekanizma olarak durur; ikisi birlikte çalışır (önce `minScore` taramada, sonra politika).
- UI: `SearchViewModel` boş listeyi `SearchStatus.Empty`'ye çevirir (KDoc: "Arama tamamlandı, eşleşme yok"). Eşikler açıkken `Empty` artık "eşleşme yok" anlamı da taşır; indeks boşken de aynı durum görünür (iki neden ayırt edilmez; ayrım gerekirse T-014 `IndexState`/sayı ile yapabilir).

## Sınırlar (ÖNEMLİ)

- Küçük ve yapay küme: 60 fotoğraf, 10 sınıf, sınıf başına 6, açık lisanslı temiz görseller. Gerçek galeri (ekran görüntüsü, belge, seyrek içerik, binlerce benzer fotoğraf) farklı dağılım verir: büyük galeride top1'in yükselmesi (daha çok aday) ve top1-s50'nin daralması beklenir (ölçülmedi); 0,20 tabanı orada hiç tetiklenmeyebilir, 0,12 marjı yalnızca belirgin kuyruğu keser. Marj için güvence YALNIZCA bu 60 foto içindir: en büyük gerekli marj 0,0936 idi; büyük galeride kötü çekilmiş (loş, bulanık, kısmi) ilgili bir fotoğraf top1'den 0,12'den fazla geride kalıp elenebilir. "İlgili sonuç hiç kesilmez" iddiası YAPILMAZ.
- Emülatör arm64 çekirdeği gerçek telefonu temsil etmez (int8 çekirdek farkı ~2e-3 skor gürültüsü; T-035). Tek koşu, tekrar varyansı ölçülmedi. Skorlar T-033 kodlayıcısına ve mevcut `MODEL_VERSION` indeksine bağlıdır; model/şablon/budama değişirse yeniden ölçün.
- K-7 hedefi (anlamsız istem -> "bulunamadı") ölçülen model ve skor ölçeğiyle sağlanamadı: ilgisiz ve ilgili top1 aralıkları örtüşüyor. Doğru çözüm ya düzlük kuralı gibi ek bir sinyal (PO kararı), ya daha ayırt edici model, ya da UI'da "yaklaşık eşleşme" ipucudur (T-014, K-7 ikinci yarısı).
- Cem'in galerisinde yeniden kalibre edilecek sorgular: (1) var olduğu bilinen 10 nesne/sahne (çıplak ve çok sözcüklü), (2) galeride kesin olmayan 10 nesne (zürafa, uçak, gitar ...), (3) anlamsız dizgiler (asdfgh, xqzvk, ". , !", emoji), (4) genel sorgular (fotoğraf, resim, manzara). Her biri için top1, 10. ve 50. skor ve ilgili/ilgisiz ayrımı kaydedilmeli; `SearchThresholdCalibrationDeviceTest` aynı tabloları üretir (görsel kümesi etiketli ve `/sdcard/Pictures/aigaleri-test/` altında olmak zorunda; gerçek galeri için etiketsiz sürüm yazılabilir). Ölçüm sonrası yalnızca `SearchPolicy.DEFAULT` değerleri değişir.

### Yeniden ölçüm yöntemi

- Araç: `SearchThresholdCalibrationDeviceTest` (androidTest, `com.ktu.aigaleri.ml`). Tablo (1)-(5) üretir; çıktı logcat etiketi `ThresholdCalib` ve harici files dizinindeki `th-report.txt`. Uygulama kodu log yazmaz.
- Önkoşul: test görselleri etiketli (`<etiket>_NN.jpg`) olarak `/sdcard/Pictures/aigaleri-test/` altında ve MediaStore'da olmalı. Yoksa test `assumeTrue` ile SESSİZCE ATLANIR (Gradle "başarılı" der, hiçbir ölçüm üretilmez): çıktıda `ThresholdCalib` satırlarının ve XML'de `skipped="0"` değerinin görüldüğünü doğrulayın. Görseller repoda yok.
- Komut (proje kökünde, cihaz seri numarası sabitlenerek): `ANDROID_SERIAL=<seri> ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ktu.aigaleri.ml.SearchThresholdCalibrationDeviceTest`; ardından `adb -s <seri> logcat -d -s ThresholdCalib:I` (uygulama test sonunda kaldırılırsa `th-report.txt` silinir, logcat'i kullanın). Emülatörde SME SIGILL için `OrtEmulatorWorkaround` otomatik devreye girer; gerçek telefonda etkisizdir.
- Gerçek (etiketsiz) galeri için bu test olduğu gibi KULLANILAMAZ: görsel etiketini dosya adından alır ve etiketli görsel yoksa atlanır. Etiketsiz sürüm (yalnızca top1/s10/s50 ve ilgili/ilgisiz elle işaretleme) henüz YAZILMADI; açmadan önce yazılması gerekir. Sorgu grupları: (1) VAR, (2) YOK, (3) anlamsız dizgiler, (4) genel sorgular (yukarıdaki liste).
- Açma kararı ölçütü: VAR sorguların en düşük ilgili skorunun, top1'in en az ~0,02 altında (gürültü 2e-3 üstünde) kaldığı marj; VAR top1'lerin en düşüğü tabanın üstünde; YOK/anlamsızlarda gerçek bir ayrışma görülmedikçe politika KAPALI kalır.
