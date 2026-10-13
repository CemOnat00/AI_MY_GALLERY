#!/usr/bin/env python3
"""T-027 QA: tools/prune_text_model.py ve fetch_models.sh icin BAGIMSIZ ek dogrulama (yalnizca standart kutuphane, ag yok).

Gercek ORIJINAL model/sozluk (mobile/.model-cache/) yoksa gercek-dosya testleri ATLANIR (skipped); sentetik testler her
zaman calisir. Calistirma: python3 -I tools/test_t027_qa.py   (cikis kodu 0 = gecti)

Kapsam: (4) ONNX yapisi (bagimsiz protobuf okuyucu; bu dosyada, uretim aracindan AYRI yazildi): Gather tablosu = tutulan x 768,
diger her bayt ayni, satirlar orijinalin tutulan satirlari; (7) bozuk/kesik ONNX, tek bayt degisikligi, yanlis SHA, yazma izni
yok, iki kez calistirma (idempotent), sembolik baglanti, fetch_models.sh ag olmadan (sahte curl hata verir).
"""
import hashlib
import importlib.util
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
import warnings

warnings.simplefilter("ignore", ResourceWarning)
sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
MOBILE = os.path.dirname(HERE)
_spec = importlib.util.spec_from_file_location("prune_text_model", os.path.join(HERE, "prune_text_model.py"))
pr = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pr)
_tspec = importlib.util.spec_from_file_location("test_prune_text_model", os.path.join(HERE, "test_prune_text_model.py"))
tp = importlib.util.module_from_spec(_tspec)
_tspec.loader.exec_module(tp)

CACHE = os.environ.get("MODEL_CACHE", os.path.join(MOBILE, ".model-cache"))
ASSETS = os.path.join(MOBILE, "app", "src", "main", "assets", "models")
REAL = os.path.isfile(os.path.join(CACHE, pr.ONNX_NAME)) and os.path.isfile(os.path.join(CACHE, pr.VOCAB_NAME))
needs_real = unittest.skipUnless(REAL, "gercek orijinal model yok (.model-cache; tools/fetch_models.sh)")
IS_ROOT = hasattr(os, "geteuid") and os.geteuid() == 0


def sha_bytes(b):
    return hashlib.sha256(b).hexdigest()


def sha_file(p):
    return pr.sha256_file(p)


# ------------------------------------------------------------------------ bagimsiz minik protobuf okuyucu
def _varint(b, i):
    r = s = 0
    while True:
        c = b[i]
        i += 1
        r |= (c & 0x7F) << s
        if not c & 0x80:
            return r, i
        s += 7


def parse(b, lo, hi):
    """[(num, wire, fieldStart, valueLo, valueHi, intValue)]; yalniz wire 0/2/1/5."""
    out = []
    i = lo
    while i < hi:
        st = i
        k, i = _varint(b, i)
        num, wire = k >> 3, k & 7
        if wire == 0:
            v, i = _varint(b, i)
            out.append((num, 0, st, i, i, v))
        elif wire == 2:
            n, i = _varint(b, i)
            out.append((num, 2, st, i, i + n, None))
            i += n
        elif wire == 1:
            out.append((num, 1, st, i, i + 8, None))
            i += 8
        elif wire == 5:
            out.append((num, 5, st, i, i + 4, None))
            i += 4
        else:
            raise ValueError("wire %d" % wire)
    assert i == hi, "protobuf hizasiz"
    return out


def model_view(b):
    """-> dict: top_other (graf disi top-level alan baytlari), graph_other (embedding-disi graf alani baytlari listesi),
    emb (dims, other_tensor_fields_bytes, raw (lo, hi)), name -> sha256 (tum initializer'lar)"""
    top = parse(b, 0, len(b))
    graphs = [f for f in top if f[0] == 7]
    assert len(graphs) == 1
    g = graphs[0]
    top_other = b"".join(b[f[2]:f[4]] for f in top if f[0] != 7)
    graph_other = []
    emb = None
    inits = {}
    for f in parse(b, g[3], g[4]):
        if f[0] == 5 and f[1] == 2:
            tf = parse(b, f[3], f[4])
            name = next(b[x[3]:x[4]].decode() for x in tf if x[0] == 8)
            raw = next((x for x in tf if x[0] == 9), None)
            inits[name] = sha_bytes(b[raw[3]:raw[4]]) if raw else None
            if name == pr.WORD_EMB:
                dims = [x[5] for x in tf if x[0] == 1]
                other = b"".join(b[x[2]:x[4]] for x in tf if x[0] not in (1, 9))
                emb = (dims, other, (raw[3], raw[4]), f[2])
                graph_other.append(None)  # yer tutucu: konum korunur
                continue
        graph_other.append(b[f[2]:f[4]])
    return top_other, graph_other, emb, inits


class StdlibOnly(unittest.TestCase):
    def test_imports_are_stdlib_only(self):
        import ast
        tree = ast.parse(open(os.path.join(HERE, "prune_text_model.py"), encoding="utf-8").read())
        mods = set()
        for n in ast.walk(tree):
            if isinstance(n, ast.Import):
                mods |= {a.name.split(".")[0] for a in n.names}
            elif isinstance(n, ast.ImportFrom):
                mods.add((n.module or "").split(".")[0])
        self.assertEqual({"argparse", "hashlib", "os", "sys", "unicodedata"}, mods)
        if hasattr(sys, "stdlib_module_names"):
            self.assertTrue(mods <= set(sys.stdlib_module_names), mods)
        for banned in ("numpy", "onnx", "onnxruntime", "requests", "socket", "urllib", "subprocess"):
            self.assertNotIn(banned, mods)

    def test_cli_help_runs_isolated_without_site_packages_or_pythonpath(self):
        env = {k: v for k, v in os.environ.items() if not k.startswith("PYTHON")}
        p = subprocess.run([sys.executable, "-I", "-S", os.path.join(HERE, "prune_text_model.py"), "--help"],
                           capture_output=True, text=True, env=env, timeout=60)
        self.assertEqual(0, p.returncode, p.stderr)
        self.assertIn("--orig-dir", p.stdout)

    def test_prune_tool_has_no_url_or_network_words(self):
        src = open(os.path.join(HERE, "prune_text_model.py"), encoding="utf-8").read()
        for bad in ("http://", "https://", "socket", "urlopen", "curl"):
            self.assertNotIn(bad, src)


class RuleAndVocab(unittest.TestCase):
    @needs_real
    def test_real_vocab_counts_and_specials_via_unicodedata(self):
        with open(os.path.join(CACHE, pr.VOCAB_NAME), "rb") as f:
            data = f.read()
        self.assertEqual(pr.ORIG_VOCAB_SHA, sha_bytes(data))
        lines = [b.decode("utf-8") for b in pr.read_vocab_lines(data)]
        keep = pr.compute_keep(lines)
        self.assertEqual((119547, 69830), (len(lines), len(keep)))
        self.assertEqual(49717, len(lines) - len(keep))
        kept_set = set(keep)
        dropped = [lines[i] for i in range(len(lines)) if i not in kept_set]
        self.assertEqual(99, sum(1 for t in dropped if t.startswith("[unused")))
        for s in pr.SPECIALS:
            self.assertEqual(1, lines.count(s))
            self.assertIn(lines.index(s), kept_set)
        # specials tutulan sozlukte 0..4
        self.assertEqual([lines[i] for i in keep[:5]], list(pr.SPECIALS))
        # tek-karakterli noktalama/sembol tokenlarin hicbiri atilmadi
        import unicodedata
        for i, t in enumerate(lines):
            if len(t) == 1 and unicodedata.category(t)[0] in "PS":
                self.assertIn(i, kept_set, t)
        # Turkce harfli tokenlarda atilan yalnizca baska betikle karisik olanlar
        for t in dropped:
            if any(c in t for c in "çğıöşüâîûÇĞİÖŞÜÂÎÛ"):
                self.assertTrue(any(not pr.keep_char(c) for c in (t[2:] if t.startswith("##") else t)), t)

    @needs_real
    def test_shipped_assets_vocab_equals_recomputed_if_present(self):
        p = os.path.join(ASSETS, pr.VOCAB_NAME)
        if not os.path.isfile(p):
            self.skipTest("assets/models/text_vocab.txt yok")
        data = open(os.path.join(CACHE, pr.VOCAB_NAME), "rb").read()
        raw = pr.read_vocab_lines(data)
        keep = pr.compute_keep([b.decode() for b in raw])
        self.assertEqual(pr.prune_vocab(raw, keep), open(p, "rb").read())
        # dosya butunlugu: UTF-8 siki, tek '\n' sonu, bos satir/tekrar yok
        got = open(p, "rb").read().decode("utf-8")
        self.assertTrue(got.endswith("\n") and not got.endswith("\n\n"))
        ls = got.split("\n")[:-1]
        self.assertEqual(len(ls), len(set(ls)))
        self.assertNotIn("", ls)
        self.assertNotIn("\r", got)


@needs_real
class RealModel(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="t027qa-")
        cls.out = os.path.join(cls.tmp, "out")
        cls.orig_onnx = open(os.path.join(CACHE, pr.ONNX_NAME), "rb").read()
        cls.orig_vocab = open(os.path.join(CACHE, pr.VOCAB_NAME), "rb").read()
        cls.run1 = cls.cli(CACHE, cls.out)
        cls.out_onnx = open(os.path.join(cls.out, pr.ONNX_NAME), "rb").read()
        cls.out_vocab = open(os.path.join(cls.out, pr.VOCAB_NAME), "rb").read()

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    @staticmethod
    def cli(orig, out, *extra):
        env = {k: v for k, v in os.environ.items() if not k.startswith("PYTHON")}
        return subprocess.run([sys.executable, "-I", os.path.join(HERE, "prune_text_model.py"), "--orig-dir", orig, "--out-dir", out, *extra],
                              capture_output=True, text=True, env=env, timeout=300)

    def make_orig(self, onnx=None, vocab=None):
        d = tempfile.mkdtemp(dir=self.tmp)
        with open(os.path.join(d, pr.ONNX_NAME), "wb") as f:
            f.write(self.orig_onnx if onnx is None else onnx)
        with open(os.path.join(d, pr.VOCAB_NAME), "wb") as f:
            f.write(self.orig_vocab if vocab is None else vocab)
        return d

    # ---- (4) yapi: manifest ile tutarlilik
    def test_outputs_match_manifest_sha_and_sizes(self):
        self.assertEqual(0, self.run1.returncode, self.run1.stderr)
        self.assertEqual(97153651, len(self.out_onnx))
        self.assertEqual(521296, len(self.out_vocab))
        self.assertEqual(pr.PRUNED_ONNX_SHA, sha_bytes(self.out_onnx))
        self.assertEqual(pr.PRUNED_VOCAB_SHA, sha_bytes(self.out_vocab))
        self.assertEqual(135336307 - 49717 * 768, len(self.out_onnx))
        self.assertEqual([pr.ONNX_NAME, pr.VOCAB_NAME], sorted(os.listdir(self.out)))  # .tmp-* yok
        if os.path.isfile(os.path.join(ASSETS, pr.ONNX_NAME)):
            self.assertEqual(pr.PRUNED_ONNX_SHA, sha_file(os.path.join(ASSETS, pr.ONNX_NAME)))
            self.assertEqual(pr.PRUNED_VOCAB_SHA, sha_file(os.path.join(ASSETS, pr.VOCAB_NAME)))

    def test_independent_protobuf_reader_gather_table_is_kept_rows_times_768_and_rest_identical(self):
        keep = pr.compute_keep([b.decode() for b in pr.read_vocab_lines(self.orig_vocab)])
        o_top, o_graph, o_emb, o_inits = model_view(self.orig_onnx)
        p_top, p_graph, p_emb, p_inits = model_view(self.out_onnx)
        self.assertEqual([119547, 768], o_emb[0])
        self.assertEqual([len(keep), 768], p_emb[0])
        self.assertEqual([69830, 768], p_emb[0])
        o_raw = self.orig_onnx[o_emb[2][0]:o_emb[2][1]]
        p_raw = self.out_onnx[p_emb[2][0]:p_emb[2][1]]
        self.assertEqual(119547 * 768, len(o_raw))
        self.assertEqual(69830 * 768, len(p_raw))
        # satir satir: yeni[j] == eski[keep[j]]
        for j in range(0, len(keep), 7):
            self.assertEqual(o_raw[keep[j] * 768:(keep[j] + 1) * 768], p_raw[j * 768:(j + 1) * 768], j)
        self.assertEqual(b"".join(o_raw[i * 768:(i + 1) * 768] for i in keep), p_raw)  # tam esitlik
        # tensor'un dims/raw disindaki alanlari (ad, tip) bayt bayt ayni; graf disi her sey ayni; diger her initializer/dugum ayni
        self.assertEqual(o_emb[1], p_emb[1])
        self.assertEqual(o_top, p_top)
        self.assertEqual(o_graph, p_graph)
        self.assertEqual(o_inits.keys(), p_inits.keys())
        for k in o_inits:
            if k != pr.WORD_EMB:
                self.assertEqual(o_inits[k], p_inits[k], k)
        self.assertNotEqual(o_inits[pr.WORD_EMB], p_inits[pr.WORD_EMB])
        # scale/zero_point skaler ve ayni (satir dilimlemenin gecerlilik kosulu)
        self.assertIn(pr.WORD_EMB_SCALE, p_inits)
        self.assertIn(pr.WORD_EMB_ZERO, p_inits)
        # adin gectigi yer: tablo + Gather dugumu (2) -- ikisi de ayni ad, baska yerde yok
        self.assertEqual(2, self.out_onnx.count(pr.WORD_EMB.encode()))

    def test_ids_remap_specials_and_vocab_alignment(self):
        orig = [b.decode() for b in pr.read_vocab_lines(self.orig_vocab)]
        new = [b.decode() for b in pr.read_vocab_lines(self.out_vocab)]
        self.assertEqual(69830, len(new))
        self.assertEqual({"[PAD]": 0, "[UNK]": 1, "[CLS]": 2, "[SEP]": 3, "[MASK]": 4}, {s: new.index(s) for s in pr.SPECIALS})
        self.assertEqual({"[PAD]": 0, "[UNK]": 100, "[CLS]": 101, "[SEP]": 102, "[MASK]": 103}, {s: orig.index(s) for s in pr.SPECIALS})
        keep = pr.compute_keep(orig)
        self.assertEqual([orig[i] for i in keep], new)
        # tablo satir sayisi == sozluk satir sayisi (Gather indeksi sozluge tasmaz)
        _, _, p_emb, _ = model_view(self.out_onnx)
        self.assertEqual(len(new), p_emb[0][0])

    # ---- (7) idempotans
    def test_second_run_same_bytes_and_overwrites_garbage_outputs(self):
        again = self.cli(CACHE, self.out)
        self.assertEqual(0, again.returncode, again.stderr)
        self.assertEqual(self.out_onnx, open(os.path.join(self.out, pr.ONNX_NAME), "rb").read())
        self.assertEqual(self.out_vocab, open(os.path.join(self.out, pr.VOCAB_NAME), "rb").read())
        # bozuk onceki cikti uzerine yazilir
        out2 = os.path.join(self.tmp, "garbage_out")
        os.makedirs(out2)
        for n in (pr.ONNX_NAME, pr.VOCAB_NAME):
            with open(os.path.join(out2, n), "wb") as f:
                f.write(b"bozuk")
        r = self.cli(CACHE, out2)
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual(pr.PRUNED_ONNX_SHA, sha_file(os.path.join(out2, pr.ONNX_NAME)))
        self.assertEqual(pr.PRUNED_VOCAB_SHA, sha_file(os.path.join(out2, pr.VOCAB_NAME)))
        # keep-list ayni ve tekrarlanabilir
        k1, k2 = os.path.join(self.tmp, "k1.txt"), os.path.join(self.tmp, "k2.txt")
        self.assertEqual(0, self.cli(CACHE, out2, "--keep-list", k1).returncode)
        self.assertEqual(0, self.cli(CACHE, out2, "--keep-list", k2).returncode)
        self.assertEqual(open(k1, "rb").read(), open(k2, "rb").read())
        self.assertEqual(69830, len(open(k1).read().split()))

    # ---- (7) bozuk / kesik / tek bayt / yanlis SHA: SHA kapisi (CLI, cikis 1, hicbir cikti yok)
    def assertRejected(self, orig_dir, label):
        out = os.path.join(self.tmp, "rej_" + label)
        r = self.cli(orig_dir, out)
        self.assertEqual(1, r.returncode, "%s: %s" % (label, r.stderr))
        self.assertIn("HATA:", r.stderr, label)
        self.assertNotIn("Traceback", r.stderr, label)
        self.assertFalse(os.path.exists(os.path.join(out, pr.ONNX_NAME)), label)
        self.assertFalse(os.path.exists(os.path.join(out, pr.VOCAB_NAME)), label)
        self.assertEqual([], [n for n in (os.listdir(out) if os.path.isdir(out) else []) if ".tmp-" in n], label)

    def test_truncated_corrupt_or_flipped_onnx_is_rejected_by_sha_gate(self):
        b = self.orig_onnx
        cases = {
            "trunc_half": b[:len(b) // 2], "trunc_last_byte": b[:-1], "trunc_16": b[:16], "empty": b"",
            "extra_byte": b + b"\x00", "flip_first": bytes([b[0] ^ 1]) + b[1:],
            "flip_mid": b[:len(b) // 2] + bytes([b[len(b) // 2] ^ 0x80]) + b[len(b) // 2 + 1:],
            "flip_last": b[:-1] + bytes([b[-1] ^ 1]),
        }
        for label, data in cases.items():
            self.assertRejected(self.make_orig(onnx=data), label)

    def test_single_byte_or_truncated_vocab_is_rejected_and_wrong_sha_never_writes(self):
        v = self.orig_vocab
        i = v.index("çocuk".encode())
        cases = {
            "vocab_flip": v[:i] + b"c" + v[i + 1:], "vocab_trunc": v[:-3], "vocab_crlf_tail": v[:-1] + b"\r\n",
            "vocab_empty": b"", "vocab_extra_line": v + b"yeni\n", "vocab_bom": b"\xef\xbb\xbf" + v,
        }
        for label, data in cases.items():
            self.assertRejected(self.make_orig(vocab=data), label)

    def test_missing_input_files_rejected_cleanly(self):
        d = tempfile.mkdtemp(dir=self.tmp)
        self.assertRejected(d, "missing_both")
        shutil.copy(os.path.join(CACHE, pr.VOCAB_NAME), d)
        self.assertRejected(d, "missing_onnx")

    # ---- (7) kapi kapaliyken (SHA girdisi gevsek) kesik/bozuk ONNX'i araci yine de reddeder
    def test_gate_off_truncated_onnx_raises_pruneerror_never_other_exception(self):
        b = self.orig_onnx
        n = len(b)
        for label, cut in (("0", 0), ("1", 1), ("7", 7), ("100", 100), ("quarter", n // 4), ("half", n // 2), ("almost", n - 1), ("n-1000", n - 1000)):
            d = self.make_orig(onnx=b[:cut])
            out = os.path.join(self.tmp, "g_" + label)
            try:
                pr.run(d, out, check_orig_sha=False)
            except pr.PruneError:
                pass
            except Exception as e:  # noqa: BLE001
                self.fail("kesik ONNX (%s bayt) PruneError yerine %r verdi" % (label, e))
            else:
                self.fail("kesik ONNX (%s) kabul edildi" % label)
            self.assertFalse(os.path.exists(os.path.join(out, pr.ONNX_NAME)), label)
            self.assertFalse(os.path.exists(os.path.join(out, pr.VOCAB_NAME)), label)

    def test_gate_off_flip_in_kept_row_is_caught_by_output_sha_and_nothing_is_written(self):
        orig = [b.decode() for b in pr.read_vocab_lines(self.orig_vocab)]
        _, _, emb, _ = model_view(self.orig_onnx)
        row = pr.compute_keep(orig)[1234]  # tutulan bir satir
        pos = emb[2][0] + row * 768 + 5
        flipped = self.orig_onnx[:pos] + bytes([self.orig_onnx[pos] ^ 0xFF]) + self.orig_onnx[pos + 1:]
        d = self.make_orig(onnx=flipped)
        out = os.path.join(self.tmp, "flip_out")
        with self.assertRaises(pr.PruneError) as cm:
            pr.run(d, out, check_orig_sha=False)
        self.assertIn("cikti SHA-256 uyusmuyor", str(cm.exception))
        self.assertFalse(os.path.exists(os.path.join(out, pr.ONNX_NAME)))
        self.assertFalse(os.path.exists(os.path.join(out, pr.VOCAB_NAME)))
        # tek baytlik fark: cikti baytlari yalnizca o bayttan ayrilirdi (kapi kapali ve cikti kapisi da kapali)
        out2 = os.path.join(self.tmp, "flip_out2")
        pr.run(d, out2, check_orig_sha=False, expected_out_sha=None)
        got = open(os.path.join(out2, pr.ONNX_NAME), "rb").read()
        diff = [i for i in range(len(got)) if got[i] != self.out_onnx[i]] if len(got) == len(self.out_onnx) else None
        self.assertIsNotNone(diff)
        self.assertEqual(1, len(diff))

    def test_wrong_expected_output_sha_never_writes(self):
        d = self.make_orig()
        out = os.path.join(self.tmp, "wrongsha_out")
        for sha in (("0" * 64, pr.PRUNED_VOCAB_SHA), (pr.PRUNED_ONNX_SHA, "f" * 64), (pr.PRUNED_VOCAB_SHA, pr.PRUNED_ONNX_SHA)):
            with self.assertRaises(pr.PruneError):
                pr.run(d, out, check_orig_sha=True, expected_out_sha=sha)
            self.assertFalse(os.path.exists(out) and os.listdir(out), sha)

    # ---- (7) yazma izni yok
    @unittest.skipIf(IS_ROOT, "root izin denetimini asar")
    def test_read_only_output_dir_fails_cleanly_and_keeps_previous_outputs(self):
        out = os.path.join(self.tmp, "ro_out")
        self.assertEqual(0, self.cli(CACHE, out).returncode)
        before = {n: sha_file(os.path.join(out, n)) for n in (pr.ONNX_NAME, pr.VOCAB_NAME)}
        os.chmod(out, stat.S_IRUSR | stat.S_IXUSR)
        try:
            r = self.cli(CACHE, out)
            self.assertEqual(1, r.returncode, r.stderr)
            self.assertIn("HATA:", r.stderr)
            self.assertNotIn("Traceback", r.stderr)
        finally:
            os.chmod(out, stat.S_IRWXU)
        self.assertEqual(before, {n: sha_file(os.path.join(out, n)) for n in (pr.ONNX_NAME, pr.VOCAB_NAME)})
        self.assertEqual([], [n for n in os.listdir(out) if ".tmp-" in n])

    @unittest.skipIf(IS_ROOT, "root izin denetimini asar")
    def test_unwritable_parent_cannot_create_output_dir(self):
        parent = os.path.join(self.tmp, "ro_parent")
        os.makedirs(parent)
        os.chmod(parent, stat.S_IRUSR | stat.S_IXUSR)
        try:
            r = self.cli(CACHE, os.path.join(parent, "yeni_cikti"))
            self.assertEqual(1, r.returncode, r.stderr)
            self.assertIn("HATA:", r.stderr)
            self.assertNotIn("Traceback", r.stderr)
        finally:
            os.chmod(parent, stat.S_IRWXU)
        self.assertEqual([], os.listdir(parent))

    @unittest.skipIf(IS_ROOT, "root izin denetimini asar")
    def test_read_only_original_dir_is_fine_and_originals_untouched(self):
        d = self.make_orig()
        before = {n: sha_file(os.path.join(d, n)) for n in (pr.ONNX_NAME, pr.VOCAB_NAME)}
        os.chmod(d, stat.S_IRUSR | stat.S_IXUSR)
        try:
            r = self.cli(d, os.path.join(self.tmp, "ro_orig_out"))
            self.assertEqual(0, r.returncode, r.stderr)
        finally:
            os.chmod(d, stat.S_IRWXU)
        self.assertEqual(before, {n: sha_file(os.path.join(d, n)) for n in (pr.ONNX_NAME, pr.VOCAB_NAME)})

    def test_symlinked_output_dir_equal_to_original_is_refused(self):
        d = self.make_orig()
        link = os.path.join(self.tmp, "link_to_orig")
        os.symlink(d, link)
        r = self.cli(d, link)
        self.assertEqual(1, r.returncode)
        self.assertIn("ayni", r.stderr)
        self.assertEqual(self.orig_onnx[:64], open(os.path.join(d, pr.ONNX_NAME), "rb").read(64))
        self.assertEqual(pr.ORIG_ONNX_SHA, sha_file(os.path.join(d, pr.ONNX_NAME)))

    def test_orig_dir_with_relative_dotdot_path_same_as_out_is_refused(self):
        d = self.make_orig()
        r = self.cli(d, os.path.join(d, "alt", ".."))
        self.assertEqual(1, r.returncode)
        self.assertEqual(pr.ORIG_ONNX_SHA, sha_file(os.path.join(d, pr.ONNX_NAME)))


@needs_real
class FetchScriptOffline(unittest.TestCase):
    """fetch_models.sh GERCEK budama araciyla, AG OLMADAN: sahte curl her cagrida 22 ile cikar ve gunluge yazar."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="t027qa-fetch-")
        cls.fakebin = os.path.join(cls.tmp, "bin")
        os.makedirs(cls.fakebin)
        cls.log = os.path.join(cls.tmp, "curl.log")
        with open(os.path.join(cls.fakebin, "curl"), "w") as f:
            f.write('#!/bin/sh\necho "curl $*" >> "%s"\nexit 22\n' % cls.log)
        os.chmod(os.path.join(cls.fakebin, "curl"), 0o755)
        cls.dense = os.path.join(ASSETS, "text_dense_768x512_f32.bin")
        cls.script = os.path.join(HERE, "fetch_models.sh")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def setUp(self):
        if not os.path.isfile(self.dense):
            self.skipTest("assets dense .bin yok: --text-only dense adimi agsiz calistirilamaz")
        self.work = tempfile.mkdtemp(dir=self.tmp)
        self.cache = os.path.join(self.work, "cache")
        self.dest = os.path.join(self.work, "dest")
        os.makedirs(self.cache)
        os.makedirs(self.dest)
        for n in (pr.ONNX_NAME, pr.VOCAB_NAME):
            os.symlink(os.path.join(CACHE, n), os.path.join(self.cache, n))
        shutil.copy(self.dense, self.dest)
        if os.path.exists(self.log):
            os.remove(self.log)

    def fetch(self, cache=None):
        env = dict(os.environ, PATH=self.fakebin + os.pathsep + os.environ["PATH"], MODEL_CACHE=cache or self.cache)
        return subprocess.run(["bash", self.script, "--text-only", self.dest], capture_output=True, text=True, env=env, timeout=300)

    def curls(self):
        return open(self.log).read().count("curl ") if os.path.exists(self.log) else 0

    def test_prunes_offline_verifies_sha_idempotent_and_repairs_tampered_output(self):
        r = self.fetch()
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertEqual(0, self.curls(), "agsiz: curl hic cagrilmamali")
        onnx, vocab = os.path.join(self.dest, pr.ONNX_NAME), os.path.join(self.dest, pr.VOCAB_NAME)
        self.assertEqual(pr.PRUNED_ONNX_SHA, sha_file(onnx))
        self.assertEqual(pr.PRUNED_VOCAB_SHA, sha_file(vocab))
        self.assertEqual(sorted([pr.ONNX_NAME, pr.VOCAB_NAME, "text_dense_768x512_f32.bin"]), sorted(os.listdir(self.dest)))
        # orijinaller (sembolik baglanti hedefleri) degismedi
        self.assertEqual(pr.ORIG_ONNX_SHA, sha_file(os.path.join(CACHE, pr.ONNX_NAME)))
        ino = os.stat(onnx).st_ino
        # ikinci calistirma: budama/yazma yok
        r2 = self.fetch()
        self.assertEqual(0, r2.returncode, r2.stderr)
        self.assertIn("zaten doğrulanmış budanmış", r2.stdout)
        self.assertEqual(ino, os.stat(onnx).st_ino)
        self.assertEqual(0, self.curls())
        # tek bayt bozulmus cikti: yeniden budanir ve duzelir
        with open(vocab, "r+b") as f:
            f.seek(10)
            f.write(b"X")
        r3 = self.fetch()
        self.assertEqual(0, r3.returncode, r3.stderr)
        self.assertIn("budanıyor", r3.stdout)
        self.assertEqual(pr.PRUNED_VOCAB_SHA, sha_file(vocab))
        self.assertEqual(0, self.curls())

    def test_tampered_cache_original_is_not_pruned_and_script_fails_without_outputs(self):
        bad = os.path.join(self.work, "badcache")
        os.makedirs(bad)
        shutil.copy(os.path.join(CACHE, pr.VOCAB_NAME), bad)
        with open(os.path.join(bad, pr.VOCAB_NAME), "r+b") as f:
            f.seek(100)
            f.write(b"Z")
        os.symlink(os.path.join(CACHE, pr.ONNX_NAME), os.path.join(bad, pr.ONNX_NAME))
        r = self.fetch(cache=bad)
        self.assertNotEqual(0, r.returncode)
        self.assertGreaterEqual(self.curls(), 1)  # yeniden indirmeyi dener (sahte curl hata verir), bozuk dosyayla budamaz
        self.assertFalse(os.path.exists(os.path.join(self.dest, pr.ONNX_NAME)))
        self.assertFalse(os.path.exists(os.path.join(self.dest, pr.VOCAB_NAME)))
        self.assertEqual([], [n for n in os.listdir(self.dest) if n.endswith(".part") or ".tmp-" in n])

    def test_script_never_writes_into_cache_when_originals_valid(self):
        before = sorted(os.listdir(self.cache))
        self.assertEqual(0, self.fetch().returncode)
        self.assertEqual(before, sorted(os.listdir(self.cache)))


if __name__ == "__main__":
    unittest.main(verbosity=1, warnings="ignore")
