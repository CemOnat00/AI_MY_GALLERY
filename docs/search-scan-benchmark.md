# Arama taraması A/B (T-025): JOIN'siz okuma + toplu çözme

Bu belge JVM mikro-benchmark sonucunu ve yöntemini anlatır. ÖLÇÜM MAKİNESİ TELEFON DEĞİLDİR (Apple M4, JDK 24,
HotSpot); mutlak süreler telefon için bir şey söylemez, yalnızca A/B ORANI anlamlıdır. Gerçek cihaz ölçümü T-020.

## Ne değişti
- A (eski): `getPageForModel` `photo_embedding INNER JOIN photo` okur, her satırda `uri` ve `dateTaken` gelir;
  `VectorCodec.decodeInto` bayt bayt birleştirir.
- B (yeni): `getPageForModel` yalnızca `photo_embedding`'den (photoId + vektör BLOB) okur. `uri`/`dateTaken` yalnızca
  top-K adayları (`limit + 32`) için `getPhotoRefsByIds` ile tek toplu sorguyla (<= 500 kimlik/çağrı) gelir;
  `decodeInto` LITTLE_ENDIAN `FloatBuffer.get` kullanır. Sonuç bit-bit aynıdır (eşdeğerlik testi:
  `SearchScanEquivalenceTest`; NaN bit desenleri dahil).

## Nasıl çalıştırılır
Ağır boyutlar (50 bin / 100 bin; ~230 MB sahte indeks, ~10 sn) varsayılan `testDebugUnitTest` içinde KOŞMAZ
(Gradle test JVM varsayılan 512 MB sınırı). Yalnızca ortam değişkeniyle:

    cd mobile && RUN_SEARCH_BENCH=1 ./gradlew testDebugUnitTest --tests '*SearchScanBenchmarkTest'

Sonuç satırları stdout'a yazılır (`app/build/test-results/testDebugUnitTest/` altındaki `...SearchScanBenchmarkTest.xml`).
Varsayılan koşuda yalnızca 10 bin kayıtla doğruluk (A == B) denetlenir. Paralel Gradle koşusu sonuç dosyasını
silebilir; dosya yoksa yeniden dene.

## Yöntem (`SearchScanBenchmarkTest`)
- Sahte kayıt, vektör boyutu 512, sabit tohum; kayıt üretimi zamanlamadan ÇIKARILMIŞTIR (indeks önceden bellekte).
  Sorgu aynı, `limit = 50`, sayfa 256.
- 5 ısınma + 20 ölçülen tekrar; A ve B dönüşümlü; p50/p95 (yakın-sıra yüzdelik) stdout'a yazılır.
- Mutlak süre assert EDİLMEZ (CI kırılganlığı); yalnızca A ve B sonucunun eşit olduğu doğrulanır. Log'a istem/yol
  yazılmaz.

## Sonuç: gözlenen TÜM aralık (GÜRÜLTÜLÜ; yalnızca A/B oranı yaklaşık; JVM masaüstü, telefon değil)
Apple M4, JDK 24, makinede başka işler (paralel derlemeler) koşarken. 11 koşu: ilk oturumda 4, düzeltme turunda 7;
bunlara reviewer'ın kendi koşusundaki gözlem eklendi.

| Kayıt | Eski (A) p50 | Eski p95 | Yeni (B) p50 | Yeni p95 | B/A p50 | B/A p95 |
|-------|--------------|----------|--------------|----------|---------|---------|
| 50 bin | 26,4-28,1 ms (ilk oturum), 50,6-52,4 ms (tur 2) | 27,2-55,4 ms | 16,7-17,6 ms (ilk oturum), 32,1-32,5 ms (tur 2) | 17,3-36,1 ms | 0,61-0,64 | 0,60-0,66; reviewer koşusunda 0,83 |
| 100 bin | 51,2-59,5 ms (ilk oturum), 98,5-100,9 ms (tur 2) | 51,7-105,8 ms | 32,8-36,6 ms (ilk oturum), 62,6-65,0 ms (tur 2) | 33,5-67,6 ms | 0,61-0,65; reviewer koşusunda 0,72 | 0,40-0,67 |

Yorum:
- Mutlak süreler oturumlar arasında yaklaşık 2 kat değişti (makine yükü/CPU durumu); bu yüzden mutlak süre hiçbir yerde
  iddia edilmez. Aynı koşu içinde A ve B dönüşümlü ölçüldüğünden oran daha kararlıdır.
- Oran çoğu koşuda 0,61-0,66 (yani yaklaşık %35 kısalma); gürültülü koşularda kötü uçta 0,72 (p50) ve 0,83 (p95)
  görüldü. p95 oranının alt ucu (0,40) tek bir A outlier'ından geliyor (A p95 101,7 ms, A p50 59,5 ms).
- Dürüst özet: "JVM'de B, A'dan kabaca %30-40 hızlı; gürültüde %15-30'a düşebilir". 10 bin kayıtla (varsayılan koşu,
  yalnızca 2 tekrar) oran 0,64-0,71 çıktı; anlamlı ölçüm değil.

## Sınırlar (okurken dikkat)
- Kazanç iki değişikliğin toplamıdır (JOIN'siz sayfa ve toplu çözme); ayrı ayrı ölçülmedi.
- Sahte DAO'da JOIN, `String` ayırımı, CursorWindow kopyası ve SQLite okuması YOKTUR (her iki tarafta da satır başına
  `ByteArray` kopyası yok). Gerçek Room'da eski yol satır başına `uri` `String`'i ayırıp `photo` PK araması
  yaptığından cihazdaki kazancın bu düzenekten farklı (büyük olasılıkla daha çok) olması beklenir; ÖLÇÜLMEDİ.
- ART/Android'de `FloatBuffer.get` bulk kopyası farklı derlenir; telefondaki decode kazancı DOĞRULANMADI.
- Nadir yol (üstteki adaylardan çoğu boş uri'li/yetim): aday kapasitesi 8 katına çıkarılıp tarama yinelenir
  (en kötü ~4 geçiş); olağan veride ikinci geçiş olmaz. Bu yol yalnızca testle (`SearchScanEquivalenceTest`) doğrulandı.
