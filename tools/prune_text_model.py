#!/usr/bin/env python3
"""GELISTIRICI ARACI (T-027, K-14 Katman A): uygulamanin parcasi DEGILDIR, APK'ya girmez, ag kullanmaz.

Metin modelinin (sentence-transformers/clip-ViT-B-32-multilingual-v1, Apache-2.0) sozlugunu ve gomme tablosunu
budar. YALNIZCA Python standart kutuphanesi (protobuf ayristirici elle yazildi); `python3 -I` ile calistirin.

Girdi (ORIJINAL, SHA-256 dogrulanir): <orig-dir>/text_model_qint8_arm64.onnx ve <orig-dir>/text_vocab.txt
Cikti: <out-dir>/text_model_qint8_arm64.onnx ve <out-dir>/text_vocab.txt (ayni dosya adlari; orig-dir != out-dir).

Ne yapar (degisiklik YALNIZCA bu ikisidir; "degistirilmis model", bkz. docs/model-setup.md):
  1. text_vocab.txt: satir SIRASI korunarak Katman A kumesi disindaki satirlar atilir (yeni id = budanmis dosyadaki
     satir numarasi). Tutulan satirlar bayt bayt aynidir.
  2. ONNX: `embeddings.word_embeddings.weight_quantized` (uint8 [119547, 768]; per-tensor scale/zero_point skaler)
     ayni satir kumesiyle dilimlenir; initializer boyutu [kalan, 768] olur. Graf, dugumler, olcek ve diger agirliklar
     bayt bayt aynidir. Gather dugumu (axis 0) tek tuketicidir; tutarlilik dogrulanir.
Gorsel model ve Dense agirligina dokunulmaz.

Katman A (tutulan token): ozel tokenlar ([PAD] [UNK] [CLS] [SEP] [MASK]; [unusedN] atilir) + butun karakterleri
asagidakilerden olan tokenlar (`##` oneki atilarak bakilir, bos kalan atilir):
  - Latin betigi harfi (unicodedata adinda "LATIN" sozcugu: Turkce c g i o s u, a-circumflex, i-circumflex, u-circumflex,
    buyuk/kucuk harfler, diger aksanli Latin harfleri, fullwidth Latin dahil),
  - rakamlar (unicodedata kategorisi Nd: ASCII 0-9 ve diger betiklerin/fullwidth rakamlari; rakam tokenlari betikten bagimsiz),
  - ASCII yazdirilabilir noktalama/sembol, Latin-1 noktalama/sembol (U+00A1-U+00BF, x, /), bosluk-uyumlu modifier harfler
    (U+02B0-U+02FF), birlestirici isaretler (U+0300-U+036F), fonetik ek (U+1D00-U+1DFF), genel noktalama/ust-alt
    simge/para birimi/ok/matematik/sekil/dingbat (U+2000-U+2BFF).
Kiril, Yunan, Arapca, Ibrani, Hint dilleri, CJK, Hangul, Kana, Tay, Ermeni, Gurcu vb. harfi iceren token atilir.

Neden Latin icin YAPISAL OLARAK kayipsiz: WordPiece her konumda sozcugun alt dizgesine uyan EN UZUN sozluk girdisini
secer. Alt dizge yalnizca tutulan siniftan karakterler iceriyorsa uyabilecek her token da yalnizca o karakterlerden
olusur, yani tutulur; secim ve parcalama degismez. Karakter-bazli bir kural oldugu icin kanit sozluge bagli degil
(kapi testleri bunu ayrica olcer). Karisik betikli sozcuklerde (ornegin Latin+Kiril) sonuc degisir; bu K-14'te kabul edildi.

Guvenlik: ag yok; yalnizca kendi gecici dosyalarini (`*.tmp-<pid>`) siler; ciktilar gecici dosya + os.replace ile
atomik yazilir; orijinal dosyalara yazilmaz; sabit dosya adlari (yol gezintisi yok). Idempotent: ayni girdi, ayni bayt.

Kullanim:
  python3 -I tools/prune_text_model.py --orig-dir DIR --out-dir DIR [--keep-list DOSYA]
"""
import argparse
import hashlib
import os
import sys
import unicodedata

ONNX_NAME = "text_model_qint8_arm64.onnx"
VOCAB_NAME = "text_vocab.txt"
WORD_EMB = "embeddings.word_embeddings.weight_quantized"
WORD_EMB_SCALE = "embeddings.word_embeddings.weight_scale"
WORD_EMB_ZERO = "embeddings.word_embeddings.weight_zero_point"

# ORIJINAL dosyalar (ml/ModelManifest.kt eski degerleri; fetch_models.sh SHA_TEXT_ONNX / SHA_VOCAB ile ayni).
ORIG_ONNX_SHA = "3bed77e83926519660b5c0833cb3321cfde952331edec4f7d501c7d384b8a8a7"
ORIG_VOCAB_SHA = "fe0fda7c425b48c516fc8f160d594c8022a0808447475c1a7c6d6479763f310c"
ORIG_VOCAB_LINES = 119547
HIDDEN = 768
# Katman A sonucu (Python unicodedata surumune bagli sapmayi yakalar; bkz. docs/vocab-pruning.md).
EXPECTED_KEPT = 69_642

SPECIALS = ("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]")
_COMMON_RANGES = (
    (0x21, 0x7E), (0xA1, 0xBF), (0xD7, 0xD7), (0xF7, 0xF7),
    (0x2B0, 0x2FF), (0x300, 0x36F), (0x1D00, 0x1DFF), (0x2000, 0x2BFF),
)


class PruneError(Exception):
    pass


# ---------------------------------------------------------------- Katman A kurali

def keep_char(c):
    cp = ord(c)
    if "LATIN" in unicodedata.name(c, "").split(" "):
        return True
    if unicodedata.category(c) == "Nd":
        return True
    return any(lo <= cp <= hi for lo, hi in _COMMON_RANGES)


def keep_token(token):
    """Katman A: token (str) tutulur mu?"""
    if token in SPECIALS:
        return True
    if token.startswith("[") and token.endswith("]"):
        return False  # [unused1]..[unused99]: kose parantez noktalama oldugundan sozcuk parcasi olarak hic eslesmez
    body = token[2:] if token.startswith("##") else token
    return bool(body) and all(keep_char(c) for c in body)


def compute_keep(lines):
    """Tutulan satirlarin ESKI indeksleri (artan)."""
    return [i for i, t in enumerate(lines) if keep_token(t)]


# ---------------------------------------------------------------- protobuf (yalniz gereken kadar)

def _read_varint(b, i):
    r = 0
    s = 0
    while True:
        if i >= len(b):
            raise PruneError("protobuf: beklenmedik dosya sonu")
        c = b[i]
        i += 1
        r |= (c & 0x7F) << s
        if not c & 0x80:
            return r, i
        s += 7
        if s > 63:
            raise PruneError("protobuf: varint cok uzun")


def _varint(n):
    out = bytearray()
    while True:
        c = n & 0x7F
        n >>= 7
        if n:
            out.append(c | 0x80)
        else:
            out.append(c)
            return bytes(out)


def _tag(f, w):
    return _varint((f << 3) | w)


class _F:
    """Bir alan: num, wire, value (wire 0) veya span (wire 2: (basla, bitis)); start/end tum alan baytlari."""
    __slots__ = ("num", "wire", "value", "span", "start", "end")

    def __init__(self, num, wire, value, span, start, end):
        self.num, self.wire, self.value, self.span, self.start, self.end = num, wire, value, span, start, end


def _fields(b, start, end):
    i = start
    out = []
    while i < end:
        fs = i
        key, i = _read_varint(b, i)
        num, wire = key >> 3, key & 7
        if wire == 0:
            v, i = _read_varint(b, i)
            out.append(_F(num, 0, v, None, fs, i))
        elif wire == 2:
            ln, i = _read_varint(b, i)
            if i + ln > end:
                raise PruneError("protobuf: alan sinirin disinda")
            out.append(_F(num, 2, None, (i, i + ln), fs, i + ln))
            i += ln
        elif wire == 1:
            out.append(_F(num, 1, None, (i, i + 8), fs, i + 8))
            i += 8
        elif wire == 5:
            out.append(_F(num, 5, None, (i, i + 4), fs, i + 4))
            i += 4
        else:
            raise PruneError("protobuf: desteklenmeyen wire tipi %d" % wire)
    if i != end:
        raise PruneError("protobuf: alan sonu hizasiz")
    return out


def _text(b, span):
    return bytes(b[span[0]:span[1]]).decode("utf-8")


def _tensor_info(b, span):
    """TensorProto: name(8), data_type(2), dims(1), raw_data(9) ve tum alan numaralari."""
    info = {"name": "", "dtype": 0, "dims": [], "raw": None, "nums": set()}
    for f in _fields(b, span[0], span[1]):
        info["nums"].add(f.num)
        if f.num == 1:
            if f.wire == 0:
                info["dims"].append(f.value)
            else:  # packed
                j = f.span[0]
                while j < f.span[1]:
                    x, j = _read_varint(b, j)
                    info["dims"].append(x)
        elif f.num == 2:
            info["dtype"] = f.value
        elif f.num == 8:
            info["name"] = _text(b, f.span)
        elif f.num == 9:
            info["raw"] = f.span
    return info


def prune_onnx(model, keep, vocab_size):
    """ONNX bayt dizisinden word_embeddings satirlarini `keep` (artan eski indeksler) ile dilimler; yeni bayt dizisi doner.

    Tutarlilik dogrulamalari basarisizsa PruneError. `vocab_size` = beklenen eski satir sayisi (dims[0]).
    """
    top = _fields(model, 0, len(model))
    graphs = [f for f in top if f.num == 7 and f.wire == 2]
    if len(graphs) != 1:
        raise PruneError("ModelProto: tam bir graph bekleniyordu")
    g = graphs[0]
    gfields = _fields(model, g.span[0], g.span[1])

    # Initializer: tam bir tane, uint8 [vocab_size, H], yalniz {dims, data_type, name, raw_data} alanlari.
    cands = []
    scalars = {}
    for f in gfields:
        if f.num == 5 and f.wire == 2:
            t = _tensor_info(model, f.span)
            if t["name"] == WORD_EMB:
                cands.append((f, t))
            elif t["name"] in (WORD_EMB_SCALE, WORD_EMB_ZERO):
                scalars[t["name"]] = t
    if len(cands) != 1:
        raise PruneError("%s initializer'i tam bir tane olmali (%d bulundu)" % (WORD_EMB, len(cands)))
    tf, t = cands[0]
    if t["dtype"] != 2:
        raise PruneError("%s uint8 (data_type 2) degil: %s" % (WORD_EMB, t["dtype"]))
    if not t["nums"] <= {1, 2, 8, 9} or t["raw"] is None:
        raise PruneError("%s beklenmeyen alanlar iceriyor (harici veri/typed data?): %s" % (WORD_EMB, sorted(t["nums"])))
    if len(t["dims"]) != 2 or t["dims"][0] != vocab_size:
        raise PruneError("%s boyutu %s, beklenen [%d, H]" % (WORD_EMB, t["dims"], vocab_size))
    hidden = t["dims"][1]
    raw_lo, raw_hi = t["raw"]
    if raw_hi - raw_lo != vocab_size * hidden:
        raise PruneError("raw_data uzunlugu %d, beklenen %d" % (raw_hi - raw_lo, vocab_size * hidden))
    for nm in (WORD_EMB_SCALE, WORD_EMB_ZERO):
        s = scalars.get(nm)
        if s is None or s["dims"] != []:
            raise PruneError("%s skaler (per-tensor) degil ya da yok: satir dilimleme gecerli degil" % nm)

    # Tuketici: tam bir Gather dugumu (axis 0), ilk girdi; baska hicbir yerde ad gecmez.
    consumers = []
    for f in gfields:
        if f.num == 1 and f.wire == 2:
            inputs, op, axis = [], "", None
            for nf in _fields(model, f.span[0], f.span[1]):
                if nf.num == 1:
                    inputs.append(_text(model, nf.span))
                elif nf.num == 4:
                    op = _text(model, nf.span)
                elif nf.num == 5:
                    aname, ai = "", None
                    for af in _fields(model, nf.span[0], nf.span[1]):
                        if af.num == 1:
                            aname = _text(model, af.span)
                        elif af.num == 3:
                            ai = af.value
                    if aname == "axis":
                        axis = ai
            if WORD_EMB in inputs:
                consumers.append((op, inputs, axis))
    if len(consumers) != 1 or consumers[0][0] != "Gather" or consumers[0][1][0] != WORD_EMB or consumers[0][2] not in (None, 0):
        raise PruneError("%s tek bir Gather(axis=0, veri girdisi) dugumunce tuketilmeli: %s" % (WORD_EMB, consumers))
    if model.count(WORD_EMB.encode("utf-8")) != 2:
        raise PruneError("%s adi beklenenden fazla yerde geciyor (value_info/cikti?)" % WORD_EMB)

    if not keep or keep != sorted(set(keep)) or keep[0] < 0 or keep[-1] >= vocab_size:
        raise PruneError("keep listesi gecersiz")

    mv = memoryview(model)
    new_raw = b"".join(mv[raw_lo + i * hidden: raw_lo + (i + 1) * hidden] for i in keep)
    # TensorProto'yu yeniden kur: dims yerinde (ilk dims alaninin yerinde yeni ikili), digerleri bayt bayt.
    parts = bytearray()
    dims_done = False
    for f in _fields(model, tf.span[0], tf.span[1]):
        if f.num == 1:
            if not dims_done:
                parts += _tag(1, 0) + _varint(len(keep)) + _tag(1, 0) + _varint(hidden)
                dims_done = True
        elif f.num == 9:
            parts += _tag(9, 2) + _varint(len(new_raw)) + new_raw
        else:
            parts += model[f.start:f.end]
    new_tensor = _tag(5, 2) + _varint(len(parts)) + bytes(parts)
    new_graph_body = model[g.span[0]:tf.start] + new_tensor + model[tf.end:g.span[1]]
    new_graph = _tag(7, 2) + _varint(len(new_graph_body)) + new_graph_body
    return model[:g.start] + new_graph + model[g.end:]


# ---------------------------------------------------------------- sozluk

def read_vocab_lines(data):
    """Orijinal vocab baytlari -> satir baytlari listesi (WordPieceTokenizer.fromVocabStream ile ayni kural)."""
    if b"\r" in data:
        raise PruneError("sozluk CR iceriyor (readLine ayrisabilir)")
    parts = data.split(b"\n")
    if parts and parts[-1] == b"":
        parts.pop()
    return parts


def prune_vocab(lines, keep):
    return b"".join(lines[i] + b"\n" for i in keep)


# ---------------------------------------------------------------- dosya islemleri

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _write_atomic(path, data, tmp_list):
    tmp = "%s.tmp-%d" % (path, os.getpid())
    tmp_list.append(tmp)
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)
    tmp_list.remove(tmp)


def run(orig_dir, out_dir, keep_list=None, check_orig_sha=True, expected_kept=EXPECTED_KEPT, vocab_lines=ORIG_VOCAB_LINES):
    orig_dir = os.path.abspath(orig_dir)
    out_dir = os.path.abspath(out_dir)
    if os.path.realpath(orig_dir) == os.path.realpath(out_dir):
        raise PruneError("orig-dir ve out-dir ayni olamaz (orijinaller korunur)")
    onnx_path = os.path.join(orig_dir, ONNX_NAME)
    vocab_path = os.path.join(orig_dir, VOCAB_NAME)
    for p in (onnx_path, vocab_path):
        if not os.path.isfile(p):
            raise PruneError("orijinal dosya yok: %s (tools/fetch_models.sh)" % p)
    if check_orig_sha:
        for p, want in ((onnx_path, ORIG_ONNX_SHA), (vocab_path, ORIG_VOCAB_SHA)):
            got = sha256_file(p)
            if got != want:
                raise PruneError("%s SHA-256 uyusmuyor (got %s): ORIJINAL dosya degil" % (os.path.basename(p), got))
    with open(vocab_path, "rb") as f:
        raw_lines = read_vocab_lines(f.read())
    if len(raw_lines) != vocab_lines:
        raise PruneError("sozluk satir sayisi %d, beklenen %d" % (len(raw_lines), vocab_lines))
    lines = [b.decode("utf-8") for b in raw_lines]
    keep = compute_keep(lines)
    if expected_kept and len(keep) != expected_kept:
        raise PruneError("tutulan satir sayisi %d, beklenen %d (Python unicodedata surumu farkli olabilir)" % (len(keep), expected_kept))
    for s in SPECIALS:
        if s not in lines or lines.index(s) not in keep:
            raise PruneError("ozel token tutulmadi: %s" % s)
    with open(onnx_path, "rb") as f:
        model = f.read()
    new_model = prune_onnx(model, keep, vocab_lines)
    del model
    new_vocab = prune_vocab(raw_lines, keep)

    os.makedirs(out_dir, exist_ok=True)
    tmps = []
    try:
        _write_atomic(os.path.join(out_dir, ONNX_NAME), new_model, tmps)
        _write_atomic(os.path.join(out_dir, VOCAB_NAME), new_vocab, tmps)
        if keep_list:
            _write_atomic(keep_list, ("\n".join(str(i) for i in keep) + "\n").encode("ascii"), tmps)
    finally:
        for t in tmps:  # yalniz bu surecin kendi gecici dosyalari
            try:
                os.remove(t)
            except OSError:
                pass
    return len(keep), len(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--orig-dir", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--keep-list", help="tutulan eski satir indekslerini (her satirda bir) bu dosyaya da yaz")
    args = ap.parse_args(argv)
    try:
        kept, total = run(args.orig_dir, args.out_dir, args.keep_list)
    except PruneError as e:
        print("HATA: %s" % e, file=sys.stderr)
        return 1
    print("budandi: %d / %d satir tutuldu, %d atildi -> %s" % (kept, total, total - kept, args.out_dir))
    return 0


if __name__ == "__main__":
    sys.exit(main())
