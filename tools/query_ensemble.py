#!/usr/bin/env python3
"""GELISTIRICI ARACI (T-033): uygulamanin parcasi DEGILDIR, APK'ya girmez, ag kullanmaz.

Sorgu sablon topluluğu (prompt ensemble, bkz. docs/query-ensemble.md) icin iki is yapar:

  --golden   Python referansi: secilen kume icin sabit sorgularin ensemble vektorunu uretir ve
             app/src/test/resources/ml/query_ensemble_golden.tsv dosyasina yazar. Satirlar:
               variant <TAB> sorgu (kacisli) <TAB> normalize edilmis varyant metni (kacisli) <TAB> 512 float (cıplak kodlayici)
               expect  <TAB> sorgu (kacisli) <TAB> -                                           <TAB> 512 float (ensemble)
             JVM testi (PromptEnsembleGoldenTest) ONNX calistiramaz: variant satirlarini sahte kodlayici olarak kullanir,
             Kotlin ensemble matematiginin expect satirini uretmesini dogrular.
  --eval     Olcum tablolari (docs/query-ensemble.md): aday kume karsilastirmasi, kelime sayisi esigi, sik kelime sapmasi.
             --image-vecs yol.npy verilirse (N x 512, L2 normalize goruntu vektorleri; repoda YOK, emulator galerisinden
             turetilen vekil kume) goruntu siralama olcumleri de yapilir. Cikti stdout'a; dosya yazmaz.

Model: ORIJINAL metin modeli (sentence-transformers/clip-ViT-B-32-multilingual-v1, Apache-2.0; golden_text.py ile ayni
dogrulama kapilari: mobile/.model-cache + SHA-256). Gereken: python3 + numpy + onnxruntime==1.30.0 (docs/golden-text.md).

Kullanim (mobile/ icinden; python = onnxruntime+numpy kurulu venv, docs/golden-text.md kurulumu, repo DISINDA):
  ~/venvs/aigaleri-ort/bin/python -I tools/query_ensemble.py --golden
  ~/venvs/aigaleri-ort/bin/python -I tools/query_ensemble.py --eval [--image-vecs /yol/imgvecs.npy]
(T-033 kosularinda oturum scratchpad'indeki ort_venv/bin/python kullanildi; ayni onnxruntime 1.30.0 + numpy.)

Kural (PromptEnsembleTextEncoder.variantsFor ile AYNI): sablon bir/sayi sozcugu/rakamla baslayan sorguya on ek, foto/resim sozcugu ile
biten sorguya son ek almaz; ensemble'in sirasi/varyantlari altin dosyada kilitlidir.

Gizlilik: yalnizca bu dosyadaki sabit test sorgulari islenir; fotograf yok (goruntu vektorleri disaridan, isteğe bagli).
"""
import argparse
import importlib.util
import os
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("golden_text", os.path.join(HERE, "golden_text.py"))
gt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gt)

OUT_GOLDEN = os.path.join(gt.RES, "query_ensemble_golden.tsv")

# ---- Secilen yapilandirma: PromptEnsembleTextEncoder.kt ile AYNI olmak zorunda (JVM testi dosyayi bununla karsilastirir).
TEMPLATES = ["bir {q}", "bir {q} fotoğrafı"]
MAX_WORDS = 3
MAX_CHARS = 64

# Goruntu degil metin altin sorgulari: ham sorgu (kacisli), kasitli cesitlilik (buyuk harf, I/İ, bosluk, esik asimi).
GOLDEN_QUERIES = [
    "kedi", "köpek", "araba", "deniz", "bardak", "fincan", "tabak", "plaj",
    "su bardağı", "çay bardağı", "bir bardak çay", "sahilde gülen çocuk",
    "KIRMIZI ARABA", "İSTANBUL", "ISPARTA", "  su   bardağı  ",
    "bir kedi", "kedi fotoğrafı", "iki kişi", "üç çocuk", "5 araba", "deniz resmi", "bir kedi fotoğrafı",  # ön/son ek kuralı
    "dağda yürüyüş yapan insanlar",  # 4 sözcük: şablonsuz (çıplak)
    "çok uzun bir sözcük çok uzun bir sözcük çok uzun bir sözcük çok uzun",  # > MAX_CHARS ve > MAX_WORDS: çıplak
    "abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuvwxyz abcde",  # 3 sözcük ama > 55 karakter, <= 64: şablonlu uç durum
]

PAIRS_SINGLE = [("bardak", "cup"), ("kedi", "cat"), ("köpek", "dog"), ("araba", "car"), ("deniz", "sea"), ("plaj", "beach"), ("dağ", "mountain"),
    ("ağaç", "tree"), ("çiçek", "flower"), ("kuş", "bird"), ("at", "horse"), ("balık", "fish"), ("ev", "house"), ("masa", "table"),
    ("sandalye", "chair"), ("kitap", "book"), ("telefon", "phone"), ("bilgisayar", "computer"), ("bisiklet", "bicycle"), ("uçak", "airplane"),
    ("gemi", "ship"), ("tren", "train"), ("çocuk", "child"), ("kadın", "woman"), ("adam", "man"), ("bebek", "baby"), ("yemek", "food"),
    ("pizza", "pizza"), ("elma", "apple"), ("muz", "banana"), ("çay", "tea"), ("kahve", "coffee"), ("tabak", "plate"), ("çatal", "fork"),
    ("kaşık", "spoon"), ("bıçak", "knife"), ("şişe", "bottle"), ("gözlük", "glasses"), ("saat", "clock"), ("şapka", "hat"),
    ("ayakkabı", "shoe"), ("çanta", "bag"), ("kapı", "door"), ("pencere", "window"), ("köprü", "bridge"), ("kale", "castle"),
    ("cami", "mosque"), ("güneş", "sun"), ("ay", "moon"), ("yıldız", "star"), ("kar", "snow"), ("yağmur", "rain"), ("orman", "forest"),
    ("göl", "lake"), ("nehir", "river"), ("çöl", "desert"), ("havuz", "pool"), ("gitar", "guitar"), ("sepet", "basket"), ("battaniye", "blanket")]
PAIRS_MULTI = [("su bardağı", "a glass of water"), ("çay bardağı", "a tea glass"), ("kırmızı araba", "a red car"),
    ("sahilde gülen çocuk", "a child laughing on the beach"), ("karlı dağ", "a snowy mountain"), ("gün batımı", "sunset"),
    ("beyaz kedi", "a white cat"), ("siyah köpek", "a black dog"), ("kahve fincanı", "a coffee cup"), ("yeşil orman", "a green forest"),
    ("mavi deniz", "a blue sea"), ("ahşap masa", "a wooden table"), ("uçan kuş", "a flying bird"), ("tarihi cami", "a historic mosque"),
    ("büyük köprü", "a big bridge"), ("gece gökyüzü", "night sky"), ("yağmurlu sokak", "a rainy street"), ("kalabalık şehir", "a crowded city"),
    ("çiçek buketi", "a bouquet of flowers"), ("koşan at", "a running horse"), ("doğum günü pastası", "a birthday cake"),
    ("bisiklet süren adam", "a man riding a bicycle"), ("masada oturan kadın", "a woman sitting at a table"),
    ("bahçede oynayan çocuklar", "children playing in the garden"), ("dağda yürüyüş yapan insanlar", "people hiking in the mountains"),
    ("sahilde gün batımı", "sunset on the beach"), ("karda oynayan köpek", "a dog playing in the snow"), ("kırmızı çiçekler", "red flowers"),
    ("eski bir ev", "an old house"), ("deniz kenarında bir kafe", "a cafe by the sea"),
    ("ağaçların arasında yürüyen iki kişi", "two people walking among the trees"), ("sahilde yürüyen bir çift", "a couple walking on the beach"),
    ("çocuklarla dolu bir park", "a park full of children"), ("karlı dağların önünde bir göl", "a lake in front of snowy mountains"),
    ("masanın üzerinde duran kırmızı bir elma", "a red apple on the table"), ("denizde yüzen küçük bir tekne", "a small boat floating on the sea"),
    ("gün batımında sahilde koşan köpek", "a dog running on the beach at sunset"), ("ormanda yürüyüş yapan üç arkadaş", "three friends hiking in the forest"),
    ("yağmurlu bir günde şemsiyeli kadın", "a woman with an umbrella on a rainy day"), ("bahçede oynayan iki küçük kedi", "two small cats playing in the garden"),
    ("eski bir taş köprünün üzerinden geçen tren", "a train crossing an old stone bridge"), ("mutfakta yemek yapan yaşlı bir adam", "an old man cooking in the kitchen"),
    ("kalabalık bir caddede yürüyen insanlar", "people walking on a crowded street"), ("büyük bir ağacın altında oturan çocuk", "a child sitting under a big tree"),
    ("gece yanan şehir ışıkları", "glowing city lights at night")]

# Ön/son ek kuralı ölçümü (bölüm 7): TR sorgu -> tam İngilizce referans cümlesi
RULE_PAIRS = [("bir kedi", "a photo of a cat"), ("kedi fotoğrafı", "a photo of a cat"), ("iki kişi", "a photo of two people"),
    ("üç çocuk", "a photo of three children"), ("dört kedi", "a photo of four cats"), ("5 araba", "a photo of five cars"),
    ("10 kişi", "a photo of ten people"), ("bir bardak çay", "a photo of a cup of tea"), ("deniz resmi", "a photo of the sea"),
    ("bir köpek fotoğrafı", "a photo of a dog"), ("bir deniz resmi", "a photo of a sea"), ("bir bardak", "a photo of a cup")]

CANDIDATES = {
    "bare (şablonsuz)": ["{q}"],
    "A [bir q, bir q fotoğrafı]": ["bir {q}", "bir {q} fotoğrafı"],
    "B [q, bir q, bir q fotoğrafı, q fotoğrafı]": ["{q}", "bir {q}", "bir {q} fotoğrafı", "{q} fotoğrafı"],
    "C [bir q fotoğrafı]": ["bir {q} fotoğrafı"],
    "D [q, bir q fotoğrafı]": ["{q}", "bir {q} fotoğrafı"],
    "E [q fotoğrafı, bir q fotoğrafı]": ["{q} fotoğrafı", "bir {q} fotoğrafı"],
    "F [q, bir q]": ["{q}", "bir {q}"],
    "G [bir q, q fotoğrafı]": ["bir {q}", "{q} fotoğrafı"],
}
SINGLE_TEMPLATES = ["{q}", "bir {q}", "{q} fotoğrafı", "bir {q} fotoğrafı", "{q} resmi", "bir {q} resmi", "bu bir {q}", "{q} olan bir fotoğraf"]
COMMON = ["kedi", "köpek", "araba", "deniz", "su bardağı", "çay bardağı", "bardak", "fincan", "tabak", "plaj", "dağ", "çiçek",
          "bisiklet", "ağaç", "gün batımı", "kırmızı araba", "sahilde gülen çocuk", "karlı dağ", "beyaz kedi", "bir bardak çay"]


class Model:
    def __init__(self, threads=1):
        paths = gt.verify_models()
        self.tok = gt.Tokenizer(gt.load_vocab())
        if gt.check_tokenizer_reference(self.tok):
            gt.die("tokenizer referansi ayrisiyor")
        self.dense = np.frombuffer(open(paths["TEXT_DENSE"], "rb").read(), dtype="<f4").reshape(gt.OUT_DIM, gt.HIDDEN)
        self.sess = gt.make_session(paths["TEXT_ONNX"], threads)
        self.cache = {}

    def base(self, text):
        """Cıplak kodlayıcı: QueryPreprocessor.normalize + WordPiece + model + Dense + L2 (OnnxTextEncoder ile ayni)."""
        n = gt.normalize(text)
        if n not in self.cache:
            self.cache[n] = gt.embed(self.sess, self.dense, self.tok.encode(n))
        return self.cache[n]


COUNT_WORDS = {"bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz", "on"}
PHOTO_WORDS = {"fotoğraf", "fotoğrafı", "foto", "resim", "resmi"}


def variants_for(n, templates, drop_prefix_rule=True, drop_suffix_rule=True):
    """PromptEnsembleTextEncoder.variantsFor ile AYNI kural (normalize edilmis sorgu icin). Doner: varyantlar veya None (cıplak).
    On ek (sablonda {q}'dan once) sorgu bir/sayi sozcugu/rakamla basliyorsa, son ek sorgu foto/resim sozcugu ile bitiyorsa
    eklenmez; cıplakla ayni veya tekrar eden varyant elenir; hic kalmazsa cıplak."""
    words = n.count(" ") + 1
    if words > MAX_WORDS or len(n.encode("utf-16-le")) // 2 > MAX_CHARS:
        return None
    drop_p = drop_prefix_rule and (n[0].isdecimal() or n.split(" ")[0] in COUNT_WORDS)
    drop_s = drop_suffix_rule and n.split(" ")[-1] in PHOTO_WORDS
    out = []
    for t in templates:
        pre, suf = t.split("{q}")
        v = ("" if drop_p else pre) + n + ("" if drop_s else suf)
        if v != n and v not in out:
            out.append(v)
    return out or None


def variants_of(query):
    return variants_for(gt.normalize(query), TEMPLATES)


def ensemble_from(vecs):
    """Kotlin: double biriktir, /n, float32, EmbeddingMath.l2Normalize."""
    acc = np.zeros(vecs[0].shape[0], dtype=np.float64)
    for v in vecs:
        acc += v.astype(np.float64)
    mean = (acc / len(vecs)).astype(np.float32)
    return (mean.astype(np.float64) / np.sqrt((mean.astype(np.float64) ** 2).sum())).astype(np.float32)


def ensemble(m, query):
    vs = variants_of(query)
    return m.base(query) if vs is None else ensemble_from([m.base(v) for v in vs])


# ---------------------------------------------------------------- --golden

def run_golden():
    m = Model(threads=1)
    lines = []
    for q in GOLDEN_QUERIES:
        vs = variants_of(q)
        texts = [gt.normalize(q)] if vs is None else [gt.normalize(v) for v in vs]
        for t in texts:
            lines.append("variant\t%s\t%s\t%s" % (gt.escape(q), gt.escape(t), gt.fmt(m.base(t))))
        lines.append("expect\t%s\t-\t%s" % (gt.escape(q), gt.fmt(ensemble(m, q))))
    gt.write_atomic(OUT_GOLDEN, "\n".join(lines) + "\n")
    print("yazildi: %s (%d satir, %d sorgu)" % (OUT_GOLDEN, len(lines), len(GOLDEN_QUERIES)))


# ---------------------------------------------------------------- --eval

def cand_vec(m, tmpls, q):
    v = np.mean([m.base(t.replace("{q}", q)).astype(np.float64) for t in tmpls], axis=0)
    return v / np.linalg.norm(v)


def run_eval(image_vecs):
    m = Model(threads=2)
    V = np.load(image_vecs).astype(np.float64) if image_vecs else None
    if V is not None:
        print("goruntu vektoru: %s" % (V.shape,))

    def top(v, k):
        return set(np.argsort(-(V @ v))[:k].tolist())

    def en_ref(b):
        return m.base("a photo of " + b if b.startswith(("a ", "an ")) else "a photo of a " + b)

    n1 = len(PAIRS_SINGLE)
    en_bare = np.array([m.base(b) for a, b in PAIRS_SINGLE]).astype(np.float64)
    en_ph = np.array([en_ref(b) for a, b in PAIRS_SINGLE]).astype(np.float64)

    print("\n## 1. Aday kümeler: %d tek sözcüklü TR-EN çift (TR sorgusu EN karşılıklar arasında sıralanır)" % n1)
    print("| küme | çıkarım | top-1 (EN çıplak) | ort. sıra (EN çıplak) | top-1 (EN 'a photo of a') | ort. sıra | bardak sırası | görüntü ov@5 | ov@10 |")
    print("|---|---|---|---|---|---|---|---|---|")
    for name, tm in CANDIDATES.items():
        tr = np.array([cand_vec(m, tm, a) for a, b in PAIRS_SINGLE])
        cells = []
        for EN in (en_bare, en_ph):
            S = tr @ EN.T
            r = np.array([(S[i] > S[i, i]).sum() + 1 for i in range(n1)])
            cells.append((np.mean(r == 1), r.mean(), r[0]))
        ov = ("-", "-")
        if V is not None:
            ov = tuple("%.2f" % np.mean([len(top(tr[i], k) & top(en_ph[i], k)) / k for i in range(n1)]) for k in (5, 10))
        print("| %s | %d | %.2f | %.1f | %.2f | %.1f | %d | %s | %s |" % (name, len(tm), cells[0][0], cells[0][1], cells[1][0], cells[1][1], cells[1][2], ov[0], ov[1]))

    print("\n## 2. Tek şablon (sıralama kalitesi, 'a photo of a' referansı)")
    print("| şablon | top-1 | ort. sıra |")
    print("|---|---|---|")
    for t in SINGLE_TEMPLATES:
        tr = np.array([cand_vec(m, [t], a) for a, b in PAIRS_SINGLE])
        S = tr @ en_ph.T
        r = np.array([(S[i] > S[i, i]).sum() + 1 for i in range(n1)])
        print("| `%s` | %.2f | %.1f |" % (t, np.mean(r == 1), r.mean()))

    print("\n## 3. Sözcük sayısına göre etki (küme A): TR sorgusunun EN çeviri vektörüne kosinüsü ve çıplaktan sapma")
    print("| sözcük | çift | cos(çıplak, EN 'a photo of') | cos(A, EN 'a photo of') | fark | A daha iyi oran | cos(A, çıplak) ort. | en düşük |")
    print("|---|---|---|---|---|---|---|---|")
    groups = {}
    for a, b in PAIRS_SINGLE + PAIRS_MULTI:
        groups.setdefault(min(len(a.split()), 4), []).append((a, b))
    A = CANDIDATES["A [bir q, bir q fotoğrafı]"]
    for k in sorted(groups):
        rows = []
        for a, b in groups[k]:
            en = en_ref(b).astype(np.float64)
            bare = m.base(a).astype(np.float64)
            ens = cand_vec(m, A, a)
            rows.append((bare @ en, ens @ en, ens @ bare))
        r = np.array(rows)
        label = str(k) if k < 4 else "4+"
        print("| %s | %d | %.3f | %.3f | %+.3f | %.2f | %.3f | %.3f |" % (label, len(r), r[:, 0].mean(), r[:, 1].mean(), r[:, 1].mean() - r[:, 0].mean(), np.mean(r[:, 1] > r[:, 0]), r[:, 2].mean(), r[:, 2].min()))

    print("\n## 4. Sık sözcüklerin çıplaktan sapması (küme A uygulanırsa; eşik %d sözcük)" % MAX_WORDS)
    print("| sorgu | sözcük | uygulanır | cos(ensemble, çıplak) | görüntü top-5 örtüşme (çıplak vs ensemble) |")
    print("|---|---|---|---|---|")
    for q in COMMON:
        e = ensemble(m, q).astype(np.float64)
        b = m.base(q).astype(np.float64)
        ov = "%.1f" % (len(top(e, 5) & top(b, 5)) / 5) if V is not None else "-"
        print("| %s | %d | %s | %.3f | %s |" % (q, len(q.split()), "evet" if variants_of(q) else "hayır", e @ b, ov))

    print("\n## 5. Altın sorgularda (tools/golden_text_queries.tsv) sapma: küme A HER sözcük sayısında uygulanmış olsaydı cos(A, çıplak)")
    print("| sözcük | sorgu | ort. | en düşük |")
    print("|---|---|---|---|")
    byw = {}
    for line in open(gt.QUERIES_IN, encoding="utf-8").read().split("\n"):
        if not line:
            continue
        try:
            n = gt.normalize(gt.unescape(line.split("\t")[1]))
        except ValueError:
            continue
        byw.setdefault(min(n.count(" ") + 1, 5), []).append(float(cand_vec(m, A, n) @ m.base(n).astype(np.float64)))
    for k in sorted(byw):
        print("| %s | %d | %.3f | %.3f |" % (str(k) if k < 5 else "5+", len(byw[k]), np.mean(byw[k]), np.min(byw[k])))

    print("\n## 6. Ön/son ek kuralı: TR sorgusunun tam İngilizce referansına kosinüsü ve %d referans arasında sırası" % len(RULE_PAIRS))
    print("| sorgu | EN referans | çıplak cos (sıra) | kuralsız A cos (sıra) | kurallı A cos (sıra) | kurallı varyantlar |")
    print("|---|---|---|---|---|---|")
    ens_en = np.array([m.base(b).astype(np.float64) for a, b in RULE_PAIRS])

    def rank_of(v, i):
        sc = ens_en @ v
        return int((sc > sc[i]).sum() + 1)

    for i, (a, b) in enumerate(RULE_PAIRS):
        n = gt.normalize(a)
        bare = m.base(n).astype(np.float64)
        naive_t = [t.replace("{q}", n) for t in TEMPLATES]
        naive = ensemble_from([m.base(v) for v in naive_t]).astype(np.float64)
        rv = variants_of(a)
        rule = ensemble_from([m.base(v) for v in rv]).astype(np.float64) if rv else bare
        print("| %s | %s | %.3f (%d) | %.3f (%d) | %.3f (%d) | %s |" % (
            a, b, bare @ ens_en[i], rank_of(bare, i), naive @ ens_en[i], rank_of(naive, i), rule @ ens_en[i], rank_of(rule, i),
            " / ".join(rv) if rv else "(çıplak)"))

    if V is not None:
        print("\n## 7. Hub (merkezcilik): 60 tek sözcüklü sorgunun top-5 görüntü kümesi")
        print("| vektör | farklı görüntü sayısı (/%d) | tek görüntünün en çok görüldüğü sorgu sayısı |" % len(V))
        print("|---|---|---|")
        import collections
        for name, f in (("TR çıplak", lambda a, b: m.base(a)), ("TR küme A", lambda a, b: cand_vec(m, A, a)),
                        ("EN 'a photo of a'", lambda a, b: en_ref(b))):
            cnt = collections.Counter()
            for a, b in PAIRS_SINGLE:
                cnt.update(top(np.asarray(f(a, b), dtype=np.float64), 5))
            print("| %s | %d | %d |" % (name, len(cnt), max(cnt.values())))

    print("\n## 8. Masaüstü çıkarım süresi (BİLGİ: Apple Silicon Mac, ORT 1.30.0, 2 iş parçacığı; telefon DEĞİL)")
    ids = m.tok.encode(gt.normalize("bir su bardağı fotoğrafı"))
    for _ in range(10):
        gt.embed(m.sess, m.dense, ids)
    t0 = time.perf_counter()
    for _ in range(100):
        gt.embed(m.sess, m.dense, ids)
    print("tek çıkarım ~%.1f ms (%d token, ardışık 100 çalıştırma ortalaması)" % ((time.perf_counter() - t0) * 10, len(ids)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--golden", action="store_true")
    ap.add_argument("--eval", action="store_true")
    ap.add_argument("--image-vecs", default=None)
    args = ap.parse_args()
    if not (args.golden or args.eval):
        ap.error("--golden veya --eval gerekli")
    if args.golden:
        run_golden()
    if args.eval:
        run_eval(args.image_vecs)


if __name__ == "__main__":
    main()
