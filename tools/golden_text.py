#!/usr/bin/env python3
"""GELISTIRICI ARACI (T-026): uygulamanin parcasi DEGILDIR, APK'ya girmez, ag kullanmaz.

ORIJINAL metin modeli dosyalariyla 60 sorgunun "altin" token ve vektor referansini uretir:
  - tools/golden_text_queries.tsv   (girdi: kategori <TAB> sorgu)
  - app/src/test/resources/ml/golden_queries.tsv        (sorgu, kategori, token metinleri, id'ler)
  - app/src/test/resources/ml/golden_text_vectors.tsv   (sorgu, 512 float, L2 normalize)

Gereken: python3 + numpy + onnxruntime==1.30.0 (kurulum notu: docs/golden-text.md). Baska paket gerekmez;
varsa `tokenizers` yalnizca ek capraz kontrol icin kullanilir.

Guvenlik kapilari (hicbiri atlanamaz):
  1. Metin ONNX ve sozluk ORIJINAL dosyalar olmali (budanmis/degismis model ile altin uretilmez). T-027 sonrasi
     assets/models/ icindeki bu iki dosya BUDANMISTIR; bu yuzden .model-cache/ (tools/fetch_models.sh doldurur)
     VARSA orijinaller oradan okunur ve tools/fetch_models.sh'taki ORIJINAL SHA-256 ile dogrulanir; .model-cache yoksa
     eski davranis: assets/models/ icindeki text_model_qint8_arm64.onnx ve text_vocab.txt boyutu ve SHA-256'si
     ml/ModelManifest.kt ile ESIT olmali. Dense (degismez) her durumda assets/models/'ten, manifest ile dogrulanir.
  2. Buradaki Python tokenizer, Kotlin QueryPreprocessor + WordPieceTokenizer'in davranisinin birebir portudur;
     app/src/test/resources/ml/tokenizer_reference.tsv'nin TUM satirlarini birebir uretmedikce cikis hatalidir.
Kotlin davranisi referanstir; ayrisma gorulurse betik raporlar ve cikis kodu 1 verir.

Kullanim (proje kokunden, Gradle koku):
  python3 -I tools/golden_text.py           # altin dosyalari uret
  python3 -I tools/golden_text.py --noise   # kosinus gurultu tabani olc (dosya yazmaz)

Girdi dosyasinda gorunmez/ayrisik karakterler \\uXXXX (BMP) kacisiyle yazilir; ayni kacisli metin cikti
dosyalarina da yazilir (Kotlin tarafi GoldenText.unescape ile cozer). Bu yuzden sorguda ters bolu yok.
Uretilen dosyalar ayni platform + onnxruntime 1.30.0 ile BIT BIT tekrarlanir; baska platformda --noise ile bakin.

Gizlilik: yalnizca bu repodaki sabit test sorgulari islenir; fotograf ve kullanici istemi yoktur.
"""
import argparse
import hashlib
import os
import re
import sys
import unicodedata

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
MODELS = os.path.join(ROOT, "app", "src", "main", "assets", "models")
ORIG_CACHE = os.environ.get("MODEL_CACHE") or os.path.join(ROOT, ".model-cache")
FETCH_SCRIPT = os.path.join(HERE, "fetch_models.sh")
VOCAB_DIR = MODELS  # verify_models() ORIJINAL sozlugun bulundugu klasoru buraya yazar
MANIFEST = os.path.join(ROOT, "app", "src", "main", "java", "com", "ktu", "aigaleri", "ml", "ModelManifest.kt")
RES = os.path.join(ROOT, "app", "src", "test", "resources", "ml")
QUERIES_IN = os.path.join(HERE, "golden_text_queries.tsv")
TOKENIZER_REF = os.path.join(RES, "tokenizer_reference.tsv")
OUT_QUERIES = os.path.join(RES, "golden_queries.tsv")
OUT_VECTORS = os.path.join(RES, "golden_text_vectors.tsv")

EXPECTED_QUERIES = 76
EXPECTED_VOCAB_LINES = 119547
HIDDEN, OUT_DIM = 768, 512
MAX_LEN = 128
MAX_CHARS_PER_WORD = 100
MAX_QUERY_LENGTH = 256
MAX_RAW_LENGTH = 2048
UNK = "[UNK]"


def die(msg):
    print("HATA: " + msg, file=sys.stderr)
    sys.exit(1)


# ---------------------------------------------------------------- model dosyasi dogrulama

def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def manifest_entries():
    """ModelManifest.kt'den TEXT_ONNX/TEXT_DENSE/TEXT_VOCAB (dosya adi, boyut, sha256) okur."""
    src = open(MANIFEST, encoding="utf-8").read()
    out = {}
    for name in ("TEXT_ONNX", "TEXT_DENSE", "TEXT_VOCAB"):
        m = re.search(r"val %s = ModelFile\((.*?)\n    \)" % name, src, re.S)
        if not m:
            die("ModelManifest.kt icinde %s bulunamadi" % name)
        body = m.group(1)
        fn = re.search(r'fileName = "([^"]+)"', body)
        sz = re.search(r"sizeBytes = ([0-9_]+)L", body)
        sh = re.search(r'sha256 = "([0-9a-f]{64})"', body)
        if not (fn and sz and sh):
            die("ModelManifest.kt %s alanlari ayristirilamadi" % name)
        out[name] = (fn.group(1), int(sz.group(1).replace("_", "")), sh.group(1))
    return out


def original_sha(const):
    """tools/fetch_models.sh icindeki ORIJINAL (budanmamis) dosya SHA-256 sabiti (T-027)."""
    m = re.search(r'^%s="([0-9a-f]{64})"$' % const, open(FETCH_SCRIPT, encoding="utf-8").read(), re.M)
    if not m:
        die("%s icinde %s bulunamadi" % (FETCH_SCRIPT, const))
    return m.group(1)


def verify_models():
    global VOCAB_DIR
    paths = {}
    entries = manifest_entries()
    dirs = {name: MODELS for name in entries}
    if os.path.isfile(os.path.join(ORIG_CACHE, "text_model_qint8_arm64.onnx")):
        # T-027: orijinal metin ONNX + sozluk onbellekte; manifest BUDANMIS surumu tanimlar, orijinalin SHA'si betiktedir.
        for name, const in (("TEXT_ONNX", "SHA_TEXT_ONNX"), ("TEXT_VOCAB", "SHA_VOCAB")):
            fn = entries[name][0]
            dirs[name] = ORIG_CACHE
            entries[name] = (fn, os.path.getsize(os.path.join(ORIG_CACHE, fn)) if os.path.isfile(os.path.join(ORIG_CACHE, fn)) else -1,
                             original_sha(const))
        VOCAB_DIR = ORIG_CACHE
    for name, (fn, size, sha) in entries.items():
        p = os.path.join(dirs[name], fn)
        if not os.path.isfile(p):
            die("%s yok (tools/fetch_models.sh --text-only ile indirin)" % p)
        if os.path.getsize(p) != size:
            die("%s boyutu %d, manifest %d: ORIJINAL model dosyasi degil" % (fn, os.path.getsize(p), size))
        got = sha256_of(p)
        if got != sha:
            die("%s SHA-256 uyusmuyor (got %s, manifest %s): ORIJINAL model dosyasi degil" % (fn, got, sha))
        paths[name] = p
        print("SHA-256 tamam: %s %s" % (fn, sha))
    return paths


# ---------------------------------------------------------------- QueryPreprocessor portu

def _is_space(ch):
    # Java: Character.isWhitespace || isSpaceChar (Zs/Zl/Zp) || 0x85
    return ch in "\t\n\x0b\x0c\r\x1c\x1d\x1e\x1f\x85" or unicodedata.category(ch) in ("Zs", "Zl", "Zp")


def _is_dropped(ch):
    if ch == "\x00" or ch == "�":
        return True
    return unicodedata.category(ch) in ("Cc", "Cf", "Cs", "Co")


def tr_lower(s):
    # Java lowercase(Locale tr-TR): I -> i-noktasiz, I+U+0307 -> i, I-noktali -> i; digerleri varsayilan kural.
    s = s.replace("İ", "i").replace("I", "ı").replace("İ", "i")
    return s.lower()


def normalize(raw):
    if len(raw.encode("utf-16-le")) // 2 > MAX_RAW_LENGTH:
        raise ValueError("TOO_LONG")
    out = []
    pending = False
    for ch in raw:
        if _is_space(ch):
            pending = True
        elif _is_dropped(ch):
            pass
        else:
            if pending and out:
                out.append(" ")
            pending = False
            out.append(ch)
    res = tr_lower("".join(out))
    if not res:
        raise ValueError("EMPTY")
    if len(res.encode("utf-16-le")) // 2 > MAX_QUERY_LENGTH:
        raise ValueError("TOO_LONG")
    return res


# ---------------------------------------------------------------- WordPieceTokenizer portu

def _is_punct(ch):
    cp = ord(ch)
    if 33 <= cp <= 47 or 58 <= cp <= 64 or 91 <= cp <= 96 or 123 <= cp <= 126:
        return True
    return unicodedata.category(ch) in ("Pc", "Pd", "Ps", "Pe", "Pi", "Pf", "Po")


def _is_chinese(cp):
    return (0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF or 0x20000 <= cp <= 0x2A6DF or
            0x2A700 <= cp <= 0x2B73F or 0x2B740 <= cp <= 0x2B81F or 0x2B820 <= cp <= 0x2CEAF or
            0xF900 <= cp <= 0xFAFF or 0x2F800 <= cp <= 0x2FA1F)


def _clean(text):
    sb = []
    for ch in text:
        cp = ord(ch)
        if cp == 0 or cp == 0xFFFD:
            continue
        if _is_space(ch):  # Kotlin isBertWhitespace
            sb.append(" ")
            continue
        if ch not in "\t\n\r" and unicodedata.category(ch) in ("Cc", "Cf", "Cs", "Co"):
            continue
        if _is_chinese(cp):
            sb.append(" " + ch + " ")
        else:
            sb.append(ch)
    return "".join(sb)


def _split_punct(word):
    start = 0
    for i, ch in enumerate(word):
        if _is_punct(ch):
            if i > start:
                yield word[start:i]
            yield ch
            start = i + 1
    if start < len(word):
        yield word[start:]


class Tokenizer:
    def __init__(self, vocab_lines):
        self.vocab = {}
        for i, line in enumerate(vocab_lines):
            self.vocab.setdefault(line, i)  # Kotlin putIfAbsent
        self.tokens = vocab_lines
        self.cls, self.sep, self.unk = self.vocab["[CLS]"], self.vocab["[SEP]"], self.vocab[UNK]

    def _wordpiece(self, word, out):
        if len(word) > MAX_CHARS_PER_WORD:
            out.append(self.unk)
            return
        pieces = []
        start = 0
        while start < len(word):
            end = len(word)
            found = -1
            while end > start:
                sub = word[start:end]
                key = "##" + sub if start > 0 else sub
                tid = self.vocab.get(key)
                if tid is not None:
                    found = tid
                    break
                end -= 1
            if found < 0:
                out.append(self.unk)
                return
            pieces.append(found)
            start = end
        out.extend(pieces)

    def encode(self, text):
        budget = MAX_LEN - 2
        body = []
        for word in _clean(text).split(" "):
            if len(body) >= budget:
                break
            if not word:
                continue
            for piece in _split_punct(word):
                if len(body) < budget:
                    self._wordpiece(piece, body)
        body = body[:budget]
        return [self.cls] + body + [self.sep]


def load_vocab():
    data = open(os.path.join(VOCAB_DIR, "text_vocab.txt"), "rb").read().decode("utf-8")
    lines = data.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    if "\r" in data:
        die("text_vocab.txt satir sonu CR iceriyor (Kotlin readLine ile ayrisabilir)")
    if len(lines) != EXPECTED_VOCAB_LINES:
        die("sozluk satir sayisi %d, beklenen %d (T-027 budanmis sozluk olabilir: tools/fetch_models.sh ile "
            "orijinalleri .model-cache/ altina indirin)" % (len(lines), EXPECTED_VOCAB_LINES))
    for i, t in enumerate(lines):
        if any(c in t for c in "\t \n"):  # TSV ve bosluk-ayracli token listesini bozar
            die("sozluk satiri %d bosluk/sekme iceriyor: %r" % (i, t))
    return lines


def check_tokenizer_reference(tok):
    """Mevcut Kotlin referansi (tokenizer_reference.tsv): bu dosya normalize EDILMEMIS metni dogrudan encode eder."""
    if not os.path.isfile(TOKENIZER_REF):
        die("tokenizer referansi yok: %s" % TOKENIZER_REF)
    bad = n = 0
    for k, line in enumerate(open(TOKENIZER_REF, encoding="utf-8").read().split("\n"), 1):
        if not line:
            continue
        try:
            tab = line.rindex("\t")
            text, exp = line[:tab], [int(x) for x in line[tab + 1:].split()]
        except ValueError:
            die("%s satir %d bozuk ('metin<TAB>id id ...' bekleniyordu)" % (TOKENIZER_REF, k))
        n += 1
        got = tok.encode(text)
        if got != exp:
            bad += 1
            print("AYRISMA tokenizer_reference: %r\n  kotlin=%s\n  python=%s" % (text[:40], exp, got), file=sys.stderr)
    print("tokenizer_reference.tsv: %d satir, %d ayrisma" % (n, bad))
    return bad


def hf_cross_check(tok, normalized):
    """Opsiyonel: HF tokenizers varsa 60 normalize sorguda port ile karsilastir (yalniz rapor)."""
    try:
        from tokenizers import Tokenizer as HF, models, normalizers, pre_tokenizers, processors
    except ImportError:
        print("HF tokenizers yok: capraz kontrol atlandi")
        return 0
    hf = HF(models.WordPiece(tok.vocab, unk_token=UNK, max_input_chars_per_word=MAX_CHARS_PER_WORD))
    hf.normalizer = normalizers.BertNormalizer(clean_text=True, handle_chinese_chars=True, strip_accents=False, lowercase=False)
    hf.pre_tokenizer = pre_tokenizers.BertPreTokenizer()
    hf.post_processor = processors.TemplateProcessing(
        single="[CLS] $A [SEP]", special_tokens=[("[CLS]", tok.cls), ("[SEP]", tok.sep)])
    hf.enable_truncation(MAX_LEN)
    bad = 0
    for q in normalized:
        if hf.encode(q).ids != tok.encode(q):
            bad += 1
            print("AYRISMA HF tokenizers: %r" % q, file=sys.stderr)
    print("HF tokenizers capraz kontrol: %d sorgu, %d ayrisma" % (len(normalized), bad))
    return bad


# ---------------------------------------------------------------- model calistirma

def make_session(path, threads, opt=None):
    import onnxruntime as ort
    if ort.__version__ != "1.30.0":
        die("onnxruntime==1.30.0 gerekli (kurulu: %s)" % ort.__version__)
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    so.inter_op_num_threads = 1
    so.enable_mem_pattern = False  # OnnxTextEncoder ile ayni secenekler
    so.enable_cpu_mem_arena = False
    if opt is not None:  # yalniz --noise: farkli graf optimizasyonu = farkli cekirdek yolu vekili
        so.graph_optimization_level = opt
    return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])


def embed(sess, dense, ids):
    """OnnxTextEncoder/EmbeddingMath ile ayni sira ve duyarlilik: mean pool -> Dense -> L2 (double biriktirme, float32 ara)."""
    n = len(ids)
    names = {i.name for i in sess.get_inputs()}
    feeds = {}
    if "input_ids" in names:
        feeds["input_ids"] = np.array([ids], dtype=np.int64)
    if "attention_mask" in names:
        feeds["attention_mask"] = np.ones((1, n), dtype=np.int64)
    if "token_type_ids" in names:
        feeds["token_type_ids"] = np.zeros((1, n), dtype=np.int64)
    h = sess.run(None, feeds)[0]
    if h.shape != (1, n, HIDDEN):
        die("beklenmeyen model cikti sekli %s" % (h.shape,))
    pooled = (h[0].astype(np.float64).sum(axis=0) / n).astype(np.float32)
    proj = (dense.astype(np.float64) @ pooled.astype(np.float64)).astype(np.float32)
    norm = np.sqrt((proj.astype(np.float64) ** 2).sum())
    if not (norm > 0 and np.isfinite(norm)):
        die("L2 normu gecersiz")
    return (proj.astype(np.float64) / norm).astype(np.float32)


def cosine(a, b):
    a, b = a.astype(np.float64), b.astype(np.float64)
    return float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b)))


def unescape(q):
    """\\uXXXX -> karakter (yalnizca BMP; GoldenText.unescape ile ayni kural)."""
    return re.sub(r"\\u([0-9A-Fa-f]{4})", lambda m: chr(int(m.group(1), 16)), q)


def escape(t):
    """Gorunmez/ayrisik karakterleri (Mn/Mc/Me/Cf/Cc/Z* bosluk disi, BMP) \\uXXXX yap; unescape ile geri donusumlu."""
    out = "".join(
        "\\u%04x" % ord(c) if (ord(c) < 0x10000 and c != " " and
                               unicodedata.category(c) in ("Mn", "Mc", "Me", "Cf", "Cc", "Zs", "Zl", "Zp")) else c
        for c in t)
    if unescape(out) != t:
        die("kacis geri donusumlu degil: %r" % t)
    return out


def read_queries():
    """Satirlar: kategori <TAB> sorgu (kacisli). Doner: (kategori, kacisli sorgu, cozulmus sorgu)."""
    if not os.path.isfile(QUERIES_IN):
        die("girdi dosyasi yok: %s" % QUERIES_IN)
    rows = []
    for n, line in enumerate(open(QUERIES_IN, encoding="utf-8").read().split("\n"), 1):
        if not line:
            continue
        parts = line.split("\t")
        if len(parts) != 2 or not parts[0] or not parts[1]:
            die("%s satir %d: 'kategori<TAB>sorgu' bekleniyordu" % (QUERIES_IN, n))
        if "\\" in unescape(parts[1]):
            die("%s satir %d: sorguda kacissiz ters bolu / gecersiz kacis" % (QUERIES_IN, n))
        rows.append((parts[0], parts[1], unescape(parts[1])))
    return rows


def write_atomic(path, text):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    os.replace(tmp, path)


def fmt(v):
    return " ".join("%.8g" % x for x in v)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--noise", action="store_true", help="kosinus gurultu tabanini olc, dosya yazma")
    args = ap.parse_args()

    paths = verify_models()
    tok = Tokenizer(load_vocab())
    if check_tokenizer_reference(tok):
        die("Python tokenizer Kotlin referansindan ayriliyor; altin uretilmedi")

    rows = read_queries()
    if len(rows) != EXPECTED_QUERIES:
        die("%d sorgu bekleniyordu, %d var" % (EXPECTED_QUERIES, len(rows)))
    if len({r[1] for r in rows}) != len(rows):
        die("yinelenen sorgu var")
    normalized = [normalize(q) for _, _, q in rows]
    if hf_cross_check(tok, normalized):
        print("UYARI: HF tokenizers ile ayrisma var (Kotlin davranisi referanstir; raporla)", file=sys.stderr)
    ids = [tok.encode(q) for q in normalized]
    toks = [[tok.tokens[i] for i in seq] for seq in ids]

    unk_total = sum(t.count(UNK) for t in toks)
    body_total = sum(len(t) - 2 for t in toks)
    print("[UNK]: %d / %d icerik tokeni (%.4f), unk iceren sorgu: %s" % (
        unk_total, body_total, unk_total / body_total,
        [rows[i][1] for i, t in enumerate(toks) if UNK in t]))

    dense = np.fromfile(paths["TEXT_DENSE"], dtype="<f4").reshape(OUT_DIM, HIDDEN)
    s1 = make_session(paths["TEXT_ONNX"], 1)

    if args.noise:
        import onnxruntime as ort
        s2 = make_session(paths["TEXT_ONNX"], 2)
        s_basic = make_session(paths["TEXT_ONNX"], 1, ort.GraphOptimizationLevel.ORT_ENABLE_BASIC)
        s_off = make_session(paths["TEXT_ONNX"], 1, ort.GraphOptimizationLevel.ORT_DISABLE_ALL)
        s_ext = make_session(paths["TEXT_ONNX"], 1, ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED)
        pairs = {
            "1 is parcacigi, tekrar (ayni oturum)": lambda seq, a: embed(s1, dense, seq),
            "1 vs 2 is parcacigi": lambda seq, a: embed(s2, dense, seq),
            "2 is parcacigi, tekrar": None,
            "1 is parcacigi: ENABLE_ALL vs ENABLE_EXTENDED": lambda seq, a: embed(s_ext, dense, seq),
            "1 is parcacigi: ENABLE_ALL vs ENABLE_BASIC": lambda seq, a: embed(s_basic, dense, seq),
            "1 is parcacigi: ENABLE_ALL vs DISABLE_ALL": lambda seq, a: embed(s_off, dense, seq),
        }
        print("%-46s %-12s %-12s %-12s %s" % ("olcum", "1-cos(f64)", "1-cos(f32)", "maks|d|", "bit-ozdes"))
        for name, fn in pairs.items():
            c64, c32, mx, same = [], [], 0.0, 0
            for seq in ids:
                a = embed(s1, dense, seq)
                if fn is None:
                    a = embed(s2, dense, seq)
                    b = embed(s2, dense, seq)
                else:
                    b = fn(seq, a)
                c64.append(1 - cosine(a, b))
                c32.append(1 - float(np.dot(a, b)))  # float32 nokta carpim: qa prototipindeki 2.4e-7 olcumu bu turdendir
                mx = max(mx, float(np.abs(a - b).max()))
                same += int(np.array_equal(a, b))
            print("%-46s %-12.3e %-12.3e %-12.3e %d/%d" % (name, max(c64), max(abs(x) for x in c32), mx, same, len(ids)))
        return 0

    vecs = [embed(s1, dense, seq) for seq in ids]
    write_atomic(OUT_QUERIES, "".join(
        "%s\t%s\t%s\t%s\n" % (q, cat, " ".join(escape(x) for x in t), " ".join(map(str, seq)))
        for (cat, q, _), t, seq in zip(rows, toks, ids)))
    write_atomic(OUT_VECTORS, "".join("%s\t%s\n" % (q, fmt(v)) for (_, q, _), v in zip(rows, vecs)))
    print("yazildi: %s (%d bayt), %s (%d bayt)" % (
        OUT_QUERIES, os.path.getsize(OUT_QUERIES), OUT_VECTORS, os.path.getsize(OUT_VECTORS)))
    # Yazilan dosyadan okunan vektor ile bellekteki vektor arasindaki bicimleme kaybi
    worst = 0.0
    for line, v in zip(open(OUT_VECTORS, encoding="utf-8").read().split("\n"), vecs):
        r = np.array(line.split("\t")[1].split(), dtype=np.float32)
        worst = max(worst, 1 - cosine(r, v))
    print("TSV bicimleme kaybi: en kotu 1-kosinus = %.3e" % worst)
    return 0


if __name__ == "__main__":
    sys.exit(main())
