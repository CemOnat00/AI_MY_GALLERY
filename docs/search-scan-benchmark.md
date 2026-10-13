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

## Yöntem (`SearchScanBenchmarkTest`)
- 50 bin ve 100 bin sahte kayıt, vektör boyutu 512, sabit tohum; kayıt üretimi zamanlamadan ÇIKARILMIŞTIR (indeks
  önceden bellekte). Sorgu aynı, `limit = 50`, sayfa 256.
- 5 ısınma + 20 ölçülen tekrar; A ve B dönüşümlü; p50/p95 (yakın-sıra yüzdelik) stdout'a yazılır.
- Mutlak süre assert EDİLMEZ (CI kırılganlığı); yalnızca A ve B sonucunun eşit olduğu doğrulanır. Log'a istem/yol
  yazılmaz.

## Sonuç (aynı makinede 3 koşu, hepsi tutarlı; son koşu)
| Kayıt | A p50 | A p95 | B p50 | B p95 | B/A p50 | B/A p95 |
|-------|-------|-------|-------|-------|---------|---------|
| 50 bin | 26,4 ms | 27,2 ms | 16,7 ms | 17,5 ms | 0,63 | 0,64 |
| 100 bin | 51,3 ms | 52,6 ms | 33,0 ms | 34,3 ms | 0,64 | 0,65 |

Koşular arası B/A oranı 0,61-0,67 aralığında kaldı. Yani bu JVM düzeneğinde tarama süresi yaklaşık %35 kısaldı.

## Sınırlar (okurken dikkat)
- Kazanç iki değişikliğin toplamıdır (JOIN'siz sayfa ve toplu çözme); ayrı ayrı ölçülmedi.
- Sahte DAO'da JOIN, `String` ayırımı, CursorWindow kopyası ve SQLite okuması YOKTUR (her iki tarafta da satır başına
  `ByteArray` kopyası yok). Gerçek Room'da eski yol satır başına `uri` `String`'i ayırıp `photo` PK araması
  yaptığından cihazdaki kazancın bu düzenekten farklı (büyük olasılıkla daha çok) olması beklenir; ÖLÇÜLMEDİ.
- ART/Android'de `FloatBuffer.get` bulk kopyası farklı derlenir; telefondaki decode kazancı DOĞRULANMADI.
- Nadir yol (üstteki adaylardan çoğu boş uri'li/yetim): aday kapasitesi 8 katına çıkarılıp tarama yinelenir
  (en kötü ~4 geçiş); olağan veride ikinci geçiş olmaz. Bu yol yalnızca testle (`SearchScanEquivalenceTest`) doğrulandı.
