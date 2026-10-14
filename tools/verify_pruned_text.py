#!/usr/bin/env python3
"""GELISTIRICI ARACI (T-027): uygulamanin parcasi DEGILDIR, APK'ya girmez, ag kullanmaz.

Budanmis metin modelinin (tools/prune_text_model.py, Katman A) ORIJINALE esdegerligini olcen kalite kapilari:
  (a) token METNI esitligi: 76 altin sorgu (orijinal ve budanmis tokenizer, ayrica golden_queries.tsv ile)
  (b) buyuk sentetik TR+EN kume (sabit seed): sozlukten uretilen sozcuk/ek kombinasyonlari, Turkce govde+ek,
      buyuk/kucuk harf, aksansiz yazim, rastgele cumleler, tum tutulan token'lari kapsayan tarama;
      token METNI esit, id eslemesi (eski->yeni) tutarli, [UNK] sayisi AYNI
  (c) vektor: Python onnxruntime 1.30.0 (varsayilan graf optimizasyonu ENABLE_ALL), altin sorgular icin kosinus >= 0,9999
  (d) Turkce kapisi: turkce_ozel kategorisi (a, c ve [UNK] yok)
  (e) top-K ortusmesi: 10.000 sentetik goruntu vektorune karsi orijinal/budanmis sorgu vektorleri, top-10 >= 0,99, top-50 >= 0,98
Ek (kapi degil, Katman A'nin maliyeti): Latin DISI sozcuklerde ayrisma sayisi.

Gereken: python3 + numpy + onnxruntime==1.30.0 (docs/golden-text.md kurulumu). `python3 -I` ile calistirin.
Orijinal dosyalar: --orig-dir (varsayilan .model-cache, tools/fetch_models.sh doldurur). Budanmis: --pruned-dir
(varsayilan app/src/main/assets/models). Hicbir repo dosyasi yazilmaz (yalniz --write-ref-tokens istenirse).
Cikis kodu 0 = tum kapilar gecti. Gizlilik: yalnizca sentetik/sabit metin; fotograf ve kullanici istemi yoktur.

Kullanim (proje kokunden, Gradle koku):
  ~/venvs/aigaleri-ort/bin/python -I tools/verify_pruned_text.py [--orig-dir D] [--pruned-dir D] [--write-ref-tokens]
"""
import argparse
import importlib.util
import os
import random
import re
import sys

import numpy as np

sys.dont_write_bytecode = True  # tools/ altina __pycache__ yazma
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SEED = 20261013
COS_MIN = 0.9999
TOPK_MIN = {10: 0.99, 50: 0.98}


def load_module(name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, name + ".py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


gt = load_module("golden_text")
pr = load_module("prune_text_model")
FAILS = []


def gate(name, ok, detail=""):
    print("%-4s %s %s" % ("GECTI" if ok else "KALDI", name, detail))
    if not ok:
        FAILS.append(name)


def script_sha(name):
    src = open(os.path.join(HERE, "fetch_models.sh"), encoding="utf-8").read()
    m = re.search(r'^%s="([0-9a-f]{64})"$' % name, src, re.M)
    if not m:
        gt.die("fetch_models.sh icinde %s yok" % name)
    return m.group(1)


def read_lines(path):
    data = open(path, "rb").read()
    return [b.decode("utf-8") for b in pr.read_vocab_lines(data)]


# ---------------------------------------------------------------- sentetik kume

TR_STEMS = ("çocuk köpek kedi plaj deniz gün batım ağaç çiçek öğrenci şehir göl dağ yağmur kar ışık kırmızı mavi yeşil sarı "
            "İstanbul Ankara Çanakkale Şişli Üsküdar Iğdır Diyarbakır ağabey hâlâ kâğıt rüzgâr âşık îtimat mûsiki şûra Âdem lâzım "
            "hükûmet yağmurlu güneş bulut yıldız gökyüzü orman nehir köprü sokak bina araba bisiklet otobüs uçak gemi tren yemek "
            "kahvaltı çay kahve pasta meyve sebze bahçe kuş balık at inek koyun tavuk aslan kaplan kurt tilki ayı maymun fil "
            "zürafa anne baba kardeş arkadaş aile bebek genç yaşlı öğretmen doktor işçi sporcu müzisyen ressam yazar kitap defter "
            "kalem masa sandalye pencere kapı ev oda mutfak salon banyo balkon çatı duvar şölen düğün tatil gezi konser maç futbol "
            "basketbol yüzme koşu yürüyüş dans şarkı resim fotoğraf video gülen koşan oynayan uyuyan yüzen").split()
TR_SUFFIXES = ("ler lar de da den dan te ta ten tan in ın un ün nin nın nun nün imiz ımız umuz ümüz leri ları yor muş miş mış "
               "mak mek lik lık luk lük ci cı cu cü çi çı çu çü siz sız suz süz li lı lu lü e a ye ya i ı u ü yi yı yu yü "
               "ndan nden nde nda ki daki deki ile yle yla sı si su sü m n k z ız iz uz üz dır dir dur dür tır tir tur tür "
               "lerimizden larımızdan çocuklarının köpeklerimiz").split()
EN_WORDS = ("beach child dog cat sunset mountain snow red blue green yellow city lake rain tree flower house car bike bus "
            "plane ship train food breakfast coffee cake fruit garden bird fish horse cow sheep lion tiger wolf fox bear "
            "monkey elephant giraffe mother father brother friend family baby young old teacher doctor worker athlete "
            "musician painter writer book table chair window door room kitchen wedding holiday trip concert match football "
            "swimming running walking dancing song picture photo video smiling playing sleeping jumping").split()
PUNCT = [",", ".", "!", "?", "'", "-", ":", ";", "(", ")", "\"", "/", "&", "#", "%", "+"]
TR_ASCII = str.maketrans("çğıöşüâîûÇĞİÖŞÜÂÎÛ", "cgiosuaiuCGIOSUAIU")


def tr_upper(s):
    return s.replace("i", "İ").replace("ı", "I").upper()


def tr_title(s):
    return tr_upper(s[:1]) + s[1:]


def build_corpus(rng, orig_lines, kept_new_lines):
    """Doner: [(kume_adi, metin)]; sabit seed ile ayni sirayi uretir."""
    def latin_alpha(tok):
        body = tok[2:] if tok.startswith("##") else tok
        return bool(body) and all(pr.keep_char(c) and c.isalpha() for c in body)

    stems = [t for t in orig_lines if not t.startswith("##") and latin_alpha(t) and len(t) >= 2]
    sufs = [t[2:] for t in orig_lines if t.startswith("##") and latin_alpha(t)]
    out = []

    # 1) sozlukten: govde + 0-3 ek (ek ##-parcalari ve govdeler; en uzun eslesme degisik bicimde parcalanir)
    for _ in range(20000):
        w = rng.choice(stems) + "".join(rng.choice(sufs) for _ in range(rng.choice((0, 1, 1, 2, 2, 3))))
        out.append(("sozluk-govde+ek", w))
    # 2) Turkce govde + ek zinciri; ham metin, buyuk/kucuk, aksansiz, tr-kucuk (QueryPreprocessor yolu)
    tr_words = []
    for _ in range(8000):
        w = rng.choice(TR_STEMS) + "".join(rng.choice(TR_SUFFIXES) for _ in range(rng.choice((0, 1, 2, 2, 3))))
        tr_words.append(w)
    for w in tr_words:
        out.append(("tr-ham", w))
        out.append(("tr-BUYUK", tr_upper(w)))
        out.append(("tr-Baslik", tr_title(w)))
        out.append(("tr-aksansiz", w.translate(TR_ASCII)))
        out.append(("tr-aksansiz-BUYUK", tr_upper(w).translate(TR_ASCII)))
    # 3) Ingilizce ve Latin diger diller (sozlukten rastgele) buyuk/kucuk
    for w in EN_WORDS:
        for v in (w, w.capitalize(), w.upper()):
            out.append(("en", v))
    # 4) Rakam/noktalama karisimi
    for _ in range(3000):
        w = rng.choice(stems) + str(rng.randrange(0, 10000)) + rng.choice(PUNCT)
        out.append(("sozcuk+rakam+noktalama", w))
    # 5) Rastgele cumleler (TR + EN + sozluk Latin + rakam + noktalama)
    pools = (tr_words, EN_WORDS, stems)
    weights = (0.5, 0.2, 0.3)
    for _ in range(10000):
        n = rng.randrange(1, 15)
        parts = []
        for _ in range(n):
            pool = rng.choices(pools, weights)[0]
            w = rng.choice(pool)
            r = rng.random()
            if r < 0.15:
                w = tr_upper(w)
            elif r < 0.30:
                w = tr_title(w)
            elif r < 0.40:
                w = w.translate(TR_ASCII)
            if rng.random() < 0.12:
                w += rng.choice(PUNCT)
            if rng.random() < 0.05:
                w = str(rng.randrange(0, 3000))
            parts.append(w)
        out.append(("cumle", " ".join(parts)))
    # 6) Tarama: tutulan her token tek basina; ## tokenlari bir govdeye eklenmis halde
    for t in kept_new_lines:
        if t in pr.SPECIALS:
            continue
        out.append(("tarama-token", t if not t.startswith("##") else "ev" + t[2:]))
        out.append(("tarama-token-ek", "ağaç" + (t[2:] if t.startswith("##") else t)))
    return out


def encode_variants(tok, text):
    """Iki yol: ham (cased, normalize edilmemis) ve uretim yolu (QueryPreprocessor.normalize)."""
    res = [("ham", tok.encode(text))]
    try:
        res.append(("norm", tok.encode(gt.normalize(text))))
    except ValueError:
        pass
    return res


# ---------------------------------------------------------------- ana akis

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--orig-dir", default=os.environ.get("MODEL_CACHE", os.path.join(ROOT, ".model-cache")))
    ap.add_argument("--pruned-dir", default=os.path.join(ROOT, "app", "src", "main", "assets", "models"))
    ap.add_argument("--write-ref-tokens", action="store_true",
                    help="tokenizer_reference_tokens.tsv'yi (orijinal sozlukle) uret ve yaz")
    args = ap.parse_args()

    o_onnx = os.path.join(args.orig_dir, pr.ONNX_NAME)
    o_vocab = os.path.join(args.orig_dir, pr.VOCAB_NAME)
    p_onnx = os.path.join(args.pruned_dir, pr.ONNX_NAME)
    p_vocab = os.path.join(args.pruned_dir, pr.VOCAB_NAME)
    dense_path = os.path.join(args.pruned_dir, "text_dense_768x512_f32.bin")
    for p in (o_onnx, o_vocab, p_onnx, p_vocab, dense_path):
        if not os.path.isfile(p):
            gt.die("dosya yok: %s" % p)

    # --- 0) SHA-256: orijinaller, budanmislar, Dense
    checks = ((o_onnx, pr.ORIG_ONNX_SHA), (o_vocab, pr.ORIG_VOCAB_SHA), (p_onnx, script_sha("SHA_TEXT_ONNX_PRUNED")),
              (p_vocab, script_sha("SHA_VOCAB_PRUNED")), (dense_path, script_sha("SHA_DENSE_BIN")))
    for path, want in checks:
        got = gt.sha256_of(path)
        gate("SHA-256 " + os.path.basename(path) + (" (orijinal)" if path.startswith(args.orig_dir) else ""), got == want, got[:16])

    orig_lines = read_lines(o_vocab)
    pruned_lines = read_lines(p_vocab)
    keep = pr.compute_keep(orig_lines)
    gate("budanmis sozluk = Katman A kurali ile orijinalden uretilen", [orig_lines[i] for i in keep] == pruned_lines,
         "%d / %d satir" % (len(pruned_lines), len(orig_lines)))
    old_to_new = {old: new for new, old in enumerate(keep)}
    o_tok, p_tok = gt.Tokenizer(orig_lines), gt.Tokenizer(pruned_lines)
    print("ozel token id (orijinal -> budanmis): %s" % ", ".join(
        "%s %d->%d" % (s, o_tok.vocab[s], p_tok.vocab[s]) for s in pr.SPECIALS))

    def same_tokens(text_ids_o, ids_p):
        return [o_tok.tokens[i] for i in text_ids_o] == [p_tok.tokens[i] for i in ids_p] and \
            [old_to_new.get(i, -1) for i in text_ids_o] == list(ids_p)

    # --- a) altin sorgular
    rows = gt.read_queries()
    norm = [gt.normalize(q) for _, _, q in rows]
    golden = {}
    for line in open(os.path.join(gt.RES, "golden_queries.tsv"), encoding="utf-8").read().split("\n"):
        if line:
            p = line.split("\t")
            golden[p[0]] = (p[2].split(" "), [int(x) for x in p[3].split(" ")])
    o_ids = [o_tok.encode(q) for q in norm]
    p_ids = [p_tok.encode(q) for q in norm]
    bad = [rows[i][1] for i in range(len(rows)) if not same_tokens(o_ids[i], p_ids[i])]
    gate("(a) altin sorgularda token METNI esit (orijinal vs budanmis tokenizer, id eslemesi tutarli)", not bad,
         "%d/%d sorgu %s" % (len(rows) - len(bad), len(rows), bad[:5]))
    gold_bad = [r[1] for r, i in zip(rows, range(len(rows))) if
                golden[r[1]][1] != o_ids[i] or [gt.unescape(x) for x in golden[r[1]][0]] != [o_tok.tokens[t] for t in o_ids[i]]]
    gate("(a) orijinal tokenizer = golden_queries.tsv (id ve metin)", not gold_bad, "%d sapma" % len(gold_bad))
    unk_o = sum(s.count(o_tok.unk) for s in o_ids)
    unk_p = sum(s.count(p_tok.unk) for s in p_ids)
    body = sum(len(s) - 2 for s in p_ids)
    gate("(a) altin sette [UNK] sayisi AYNI", unk_o == unk_p, "orijinal %d, budanmis %d, icerik token %d" % (unk_o, unk_p, body))

    # --- b) buyuk sentetik kume
    rng = random.Random(SEED)
    corpus = build_corpus(rng, orig_lines, pruned_lines)
    n_texts = n_tokens = unk_o_b = unk_p_b = 0
    diffs = []
    per_set = {}
    for kind, text in corpus:
        for how, ids_o in encode_variants(o_tok, text):
            ids_p = p_tok.encode(text if how == "ham" else gt.normalize(text))
            n_texts += 1
            n_tokens += len(ids_o) - 2
            unk_o_b += ids_o.count(o_tok.unk)
            unk_p_b += ids_p.count(p_tok.unk)
            per_set[kind] = per_set.get(kind, 0) + 1
            if not same_tokens(ids_o, ids_p):
                diffs.append((kind, how, text))
    print("sentetik kume: %d metin (%s)" % (n_texts, ", ".join("%s %d" % kv for kv in sorted(per_set.items()))))
    print("  toplam icerik token: %d, [UNK] orijinal %d, budanmis %d" % (n_tokens, unk_o_b, unk_p_b))
    ok_words = sum(v for k, v in per_set.items() if k != "cumle")
    gate("(b) sentetik TR+EN kume: token METNI ve id eslemesi esit (>= 20000 sozcuk/ek kombinasyonu)",
         not diffs and ok_words >= 20000, "%d ayrisma; sozcuk-kumeleri %d metin, cumle %d" % (
             len(diffs), ok_words, per_set.get("cumle", 0)))
    for d in diffs[:10]:
        print("   AYRISMA %s/%s: %r" % d)
    gate("(b) [UNK] sayisi AYNI (sentetik kume)", unk_o_b == unk_p_b, "%d = %d" % (unk_o_b, unk_p_b))

    # Ek: Latin DISI sozcuklerde maliyet (kapi degil)
    dropped = [t for i, t in enumerate(orig_lines) if i not in old_to_new and not t.startswith("[unused")]
    dropped_words = [t for t in dropped if not t.startswith("##")]
    rng2 = random.Random(SEED + 1)
    sample = rng2.sample(dropped_words, 3000)
    print("bilgi: atilan (Latin disi) sozcuk tokenlari, 3000 ornek: %d tanesi budanmis tokenizer'da [UNK]e dusuyor "
          "(beklenen; Katman A kabul edildi)" % sum(1 for w in sample if p_tok.encode(w)[1:-1] == [p_tok.unk]))

    # --- c, d) vektorler
    dense = np.fromfile(dense_path, dtype="<f4").reshape(gt.OUT_DIM, gt.HIDDEN)
    s_o = gt.make_session(o_onnx, 1)
    s_p = gt.make_session(p_onnx, 1)
    import onnxruntime as ort
    gate("ORT 1.30.0, graf optimizasyonu varsayilan (ENABLE_ALL)",
         ort.__version__ == "1.30.0" and ort.SessionOptions().graph_optimization_level == ort.GraphOptimizationLevel.ORT_ENABLE_ALL,
         ort.__version__)
    vec_o = [gt.embed(s_o, dense, s) for s in o_ids]
    vec_p = [gt.embed(s_p, dense, s) for s in p_ids]
    golden_vec = {}
    for line in open(os.path.join(gt.RES, "golden_text_vectors.tsv"), encoding="utf-8").read().split("\n"):
        if line:
            q, v = line.split("\t")
            golden_vec[gt.unescape(q)] = np.array(v.split(), dtype=np.float32)
    cos_gold = [gt.cosine(vec_p[i], golden_vec[rows[i][2]]) for i in range(len(rows))]
    cos_orig = [gt.cosine(vec_p[i], vec_o[i]) for i in range(len(rows))]
    ident = sum(int(np.array_equal(a, b)) for a, b in zip(vec_o, vec_p))
    maxdiff = max(float(np.abs(a - b).max()) for a, b in zip(vec_o, vec_p))
    gate("(c) kosinus >= 0,9999: budanmis vs altin vektorler (golden_text_vectors.tsv, 76 sorgu)", min(cos_gold) >= COS_MIN,
         "min %.10f, ort %.10f" % (min(cos_gold), sum(cos_gold) / len(cos_gold)))
    gate("(c) kosinus >= 0,9999: budanmis vs bu calistirmada orijinalle yeniden hesaplanan", min(cos_orig) >= COS_MIN,
         "min %.12f, ort %.12f, bit-ozdes %d/%d, maks|fark| %.3e" % (min(cos_orig), sum(cos_orig) / len(cos_orig), ident, len(rows), maxdiff))
    tr_idx = [i for i, (cat, _, _) in enumerate(rows) if cat == "turkce_ozel"]
    tr_tok_ok = all(same_tokens(o_ids[i], p_ids[i]) for i in tr_idx)
    tr_unk = sum(p_ids[i].count(p_tok.unk) for i in tr_idx)
    tr_cos = min(cos_orig[i] for i in tr_idx)
    letters = "".join(rows[i][2] for i in tr_idx)
    required = "âîûĞÜŞÖÇİı"
    covered = [c for c in required if c in letters]
    gate("(d) Turkce kapisi: turkce_ozel sorgulari (token METNI esit, [UNK] yok, kosinus >= 0,9999)",
         len(tr_idx) == 11 and tr_tok_ok and tr_unk == 0 and tr_cos >= COS_MIN and len(covered) == len(required),
         "%d sorgu, min kosinus %.12f, harfler %s" % (len(tr_idx), tr_cos, "".join(covered)))

    # --- e) top-K
    nrng = np.random.default_rng(SEED)
    centers = nrng.standard_normal((64, gt.OUT_DIM)).astype(np.float32)
    imgs = centers[nrng.integers(0, 64, 10000)] + 0.9 * nrng.standard_normal((10000, gt.OUT_DIM)).astype(np.float32)
    anchor = np.stack(vec_o)  # sorgulara yakin goruntuler: komsuluk anlamli olsun
    sel = nrng.integers(0, len(anchor), 2500)
    imgs[:2500] = anchor[sel] + 0.6 * nrng.standard_normal((2500, gt.OUT_DIM)).astype(np.float32) / np.sqrt(gt.OUT_DIM) * 4
    imgs /= np.linalg.norm(imgs, axis=1, keepdims=True)
    extra_q = [t for k, t in corpus if k == "cumle"][:424]
    q_ids_o, q_ids_p = list(o_ids), list(p_ids)
    for t in extra_q:
        try:
            n = gt.normalize(t)
        except ValueError:
            continue
        q_ids_o.append(o_tok.encode(n))
        q_ids_p.append(p_tok.encode(n))
    qo = np.stack([vec_o[i] if i < len(vec_o) else gt.embed(s_o, dense, q_ids_o[i]) for i in range(len(q_ids_o))])
    qp = np.stack([vec_p[i] if i < len(vec_p) else gt.embed(s_p, dense, q_ids_p[i]) for i in range(len(q_ids_p))])
    for k in (10, 50):
        so = np.argsort(-(qo @ imgs.T), axis=1, kind="stable")[:, :k]
        sp = np.argsort(-(qp @ imgs.T), axis=1, kind="stable")[:, :k]
        ov = [len(set(a) & set(b)) / k for a, b in zip(so, sp)]
        gate("(e) top-%d ortusmesi >= %.2f (10000 sentetik goruntu vektoru, %d sorgu)" % (k, TOPK_MIN[k], len(ov)),
             min(ov) >= TOPK_MIN[k] and sum(ov) / len(ov) >= TOPK_MIN[k], "min %.4f, ort %.4f" % (min(ov), sum(ov) / len(ov)))

    # --- tokenizer_reference_tokens.tsv (Kotlin testi icin: eski id'ler yerine token METNI)
    if args.write_ref_tokens:
        ref = os.path.join(gt.RES, "tokenizer_reference.tsv")
        out = []
        for line in open(ref, encoding="utf-8").read().split("\n"):
            if not line:
                continue
            tab = line.rindex("\t")
            text, ids = line[:tab], [int(x) for x in line[tab + 1:].split()]
            if o_tok.encode(text) != ids:
                gt.die("tokenizer_reference.tsv orijinal sozlukle uyusmuyor: %r" % text[:30])
            out.append("%s\t%s\n" % (text, " ".join(gt.escape(o_tok.tokens[i]) for i in ids)))
        gt.write_atomic(os.path.join(gt.RES, "tokenizer_reference_tokens.tsv"), "".join(out))
        print("yazildi: tokenizer_reference_tokens.tsv (%d satir)" % len(out))

    print("SONUC: %s" % ("TUM KAPILAR GECTI" if not FAILS else "KALAN KAPILAR: %s" % FAILS))
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
