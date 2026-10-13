#!/usr/bin/env python3
"""T-027: tools/prune_text_model.py birim testleri (yalnizca standart kutuphane, ag ve gercek model dosyasi yok).

Kucuk sentetik ONNX (elle uretilmis protobuf) ile satir dilimlemeyi, tutarlilik denetimlerini, atomik ve idempotent
yazimi ve Katman A kuralini sinar. Calistirma: python3 -I tools/test_prune_text_model.py   (cikis kodu 0 = gecti)
"""
import hashlib
import importlib.util
import os
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("prune_text_model", os.path.join(HERE, "prune_text_model.py"))
pr = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pr)

H = 4  # kucuk gizli boyut


def ld(num, payload):
    return pr._tag(num, 2) + pr._varint(len(payload)) + payload


def vi(num, value):
    return pr._tag(num, 0) + pr._varint(value)


def tensor(name, dims, dtype, raw, extra=b""):
    body = b"".join(vi(1, d) for d in dims) + vi(2, dtype) + ld(8, name.encode()) + ld(9, raw) + extra
    return ld(5, body)


def node(op, inputs, outputs, name="n", axis=None):
    body = b"".join(ld(1, i.encode()) for i in inputs) + b"".join(ld(2, o.encode()) for o in outputs)
    body += ld(3, name.encode()) + ld(4, op.encode())
    if axis is not None:
        body += ld(5, ld(1, b"axis") + vi(3, axis) + vi(20, 2))
    return ld(1, body)


def model(n_rows, rows=None, dtype=2, scale_dims=(), consumer_extra=False, axis=None, value_info_name=False, extra_tensor=b"",
          gather=True):
    rows = rows if rows is not None else [bytes([r, r + 1, r + 2, r + 3]) for r in range(n_rows)]
    raw = b"".join(rows)
    g = b""
    if gather:
        g += node("Gather", [pr.WORD_EMB, "input_ids"], ["emb_out"], "gather", axis)
    if consumer_extra:
        g += node("Identity", [pr.WORD_EMB], ["leak"], "leak")
    g += node("DequantizeLinear", ["emb_out", pr.WORD_EMB_SCALE, pr.WORD_EMB_ZERO], ["deq"], "deq")
    g += tensor(pr.WORD_EMB, [n_rows, H], dtype, raw, extra_tensor)
    g += tensor(pr.WORD_EMB_SCALE, list(scale_dims), 1, b"\x00\x00\x80\x3f" * max(1, len(scale_dims)))
    g += tensor(pr.WORD_EMB_ZERO, [], 2, b"\x80")
    g += tensor("other.weight", [2, 2], 3, b"\x01\x02\x03\x04")
    if value_info_name:
        g += ld(13, ld(1, pr.WORD_EMB.encode()))
    g += ld(2, b"main_graph")
    return vi(1, 6) + ld(7, g) + ld(8, vi(2, 11))


def parse_emb(buf):
    top = pr._fields(buf, 0, len(buf))
    g = [f for f in top if f.num == 7][0]
    out = {}
    for f in pr._fields(buf, g.span[0], g.span[1]):
        if f.num == 5:
            t = pr._tensor_info(buf, f.span)
            out[t["name"]] = (t, bytes(buf[t["raw"][0]:t["raw"][1]]))
    return out


class KeepRule(unittest.TestCase):
    def test_turkish_and_latin_kept(self):
        for t in ["kedi", "çocuk", "ığdır", "İstanbul", "Ğ", "##ş", "##ü", "â", "##â", "î", "##î", "û", "##û", "Â", "é", "ñ", "ø",
                  "ł", "Ａ", "0", "##9", "2024", "1ª", "µm", ".", "!", "'", "-", "€", "→", "½", "②", "０", "٣", "d'", "[PAD]",
                  "[UNK]", "[CLS]", "[SEP]", "[MASK]", "<S>"]:
            self.assertTrue(pr.keep_token(t), t)

    def test_non_latin_and_unused_dropped(self):
        for t in ["привет", "##ад", "猫", "한국", "مرحبا", "שלום", "हिन्दी", "ελληνικά", "Ωμέγα", "ภาษา", "ひらがな", "カタカナ",
                  "[unused1]", "[unused99]", "[foo]", "", "##", "。", "、", "،", "।", "кedi", "kедi", "a۔"]:
            self.assertFalse(pr.keep_token(t), t)

    def test_mixed_script_dropped(self):
        self.assertFalse(pr.keep_token("abcд"))
        self.assertFalse(pr.keep_token("##d猫"))

    def test_compute_keep_indices(self):
        lines = ["[PAD]", "[unused1]", "kedi", "кошка", "##lar", "##ыы", "x"]
        self.assertEqual([0, 2, 4, 6], pr.compute_keep(lines))


class PruneOnnx(unittest.TestCase):
    def test_rows_are_sliced_and_everything_else_is_byte_identical(self):
        m = model(10)
        keep = [0, 3, 4, 9]
        out = pr.prune_onnx(m, keep, 10)
        emb = parse_emb(out)
        t, raw = emb[pr.WORD_EMB]
        self.assertEqual([4, H], t["dims"])
        self.assertEqual(2, t["dtype"])
        self.assertEqual(b"".join(bytes([r, r + 1, r + 2, r + 3]) for r in keep), raw)
        before = parse_emb(m)
        for name in (pr.WORD_EMB_SCALE, pr.WORD_EMB_ZERO, "other.weight"):
            self.assertEqual(before[name][1], emb[name][1])
            self.assertEqual(before[name][0]["dims"], emb[name][0]["dims"])
        # Dugumler ve graf disi alanlar ayni: iki modelin Gather/Dequantize dugum baytlari esit
        def nodes(buf):
            top = pr._fields(buf, 0, len(buf))
            g = [f for f in top if f.num == 7][0]
            return [bytes(buf[f.start:f.end]) for f in pr._fields(buf, g.span[0], g.span[1]) if f.num in (1, 2)]
        self.assertEqual(nodes(m), nodes(out))
        self.assertEqual(len(m) - 6 * H, len(out))  # 6 satir x 4 bayt (uzunluk varint'leri ayni bayt sayisi)

    def test_keep_all_is_identity(self):
        m = model(5)
        self.assertEqual(m, pr.prune_onnx(m, list(range(5)), 5))

    def test_idempotent(self):
        m = model(8)
        a = pr.prune_onnx(m, [1, 2, 7], 8)
        b = pr.prune_onnx(m, [1, 2, 7], 8)
        self.assertEqual(a, b)

    def test_rejects_wrong_vocab_size(self):
        with self.assertRaises(pr.PruneError):
            pr.prune_onnx(model(10), [0, 1], 11)

    def test_rejects_non_uint8(self):
        with self.assertRaises(pr.PruneError):
            pr.prune_onnx(model(10, dtype=3), [0, 1], 10)

    def test_rejects_non_scalar_scale(self):
        with self.assertRaises(pr.PruneError):
            pr.prune_onnx(model(10, scale_dims=(10,)), [0, 1], 10)

    def test_rejects_second_consumer(self):
        with self.assertRaises(pr.PruneError):
            pr.prune_onnx(model(10, consumer_extra=True), [0, 1], 10)

    def test_rejects_missing_gather(self):
        with self.assertRaises(pr.PruneError):
            pr.prune_onnx(model(10, gather=False), [0, 1], 10)

    def test_rejects_gather_axis_not_zero(self):
        with self.assertRaises(pr.PruneError):
            pr.prune_onnx(model(10, axis=1), [0, 1], 10)
        pr.prune_onnx(model(10, axis=0), [0, 1], 10)  # axis=0 kabul

    def test_rejects_name_in_value_info(self):
        with self.assertRaises(pr.PruneError):
            pr.prune_onnx(model(10, value_info_name=True), [0, 1], 10)

    def test_rejects_unexpected_tensor_fields(self):
        with self.assertRaises(pr.PruneError):  # doc_string (alan 12) gibi bilinmeyen alan: guvenli degil, durur
            pr.prune_onnx(model(10, extra_tensor=ld(12, b"x")), [0, 1], 10)

    def test_rejects_bad_keep_lists(self):
        for bad in ([], [1, 1], [2, 1], [-1, 2], [0, 10]):
            with self.assertRaises(pr.PruneError):
                pr.prune_onnx(model(10), bad, 10)

    def test_rejects_truncated_input(self):
        with self.assertRaises(pr.PruneError):
            pr.prune_onnx(model(10)[:-5], [0, 1], 10)


class Vocab(unittest.TestCase):
    def test_prune_vocab_keeps_order_and_bytes(self):
        data = "[PAD]\n[unused1]\nçocuk\nкот\n##ğ\n".encode()
        lines = pr.read_vocab_lines(data)
        keep = pr.compute_keep([b.decode() for b in lines])
        self.assertEqual("[PAD]\nçocuk\n##ğ\n".encode(), pr.prune_vocab(lines, keep))

    def test_rejects_cr(self):
        with self.assertRaises(pr.PruneError):
            pr.read_vocab_lines(b"a\r\nb\n")


class EndToEnd(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.orig = os.path.join(self.tmp.name, "orig")
        self.out = os.path.join(self.tmp.name, "out")
        os.makedirs(self.orig)
        self.lines = ["[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "[unused1]", "çocuk", "кот", "##â", "猫", "kedi", "##1"]
        with open(os.path.join(self.orig, pr.VOCAB_NAME), "wb") as f:
            f.write(("\n".join(self.lines) + "\n").encode())
        with open(os.path.join(self.orig, pr.ONNX_NAME), "wb") as f:
            f.write(model(len(self.lines)))

    def sha(self, *parts):
        h = hashlib.sha256()
        with open(os.path.join(*parts), "rb") as f:
            h.update(f.read())
        return h.hexdigest()

    def run_prune(self, **kw):
        return pr.run(self.orig, self.out, check_orig_sha=False, expected_kept=0, vocab_lines=len(self.lines), **kw)

    def test_end_to_end_idempotent_atomic_and_originals_untouched(self):
        before = (self.sha(self.orig, pr.ONNX_NAME), self.sha(self.orig, pr.VOCAB_NAME))
        kept, total = self.run_prune()
        self.assertEqual((9, 12), (kept, total))
        out_files = sorted(os.listdir(self.out))
        self.assertEqual(sorted([pr.ONNX_NAME, pr.VOCAB_NAME]), out_files)  # .tmp-* kalmadi
        with open(os.path.join(self.out, pr.VOCAB_NAME), "rb") as f:
            self.assertEqual("[PAD]\n[UNK]\n[CLS]\n[SEP]\n[MASK]\nçocuk\n##â\nkedi\n##1\n", f.read().decode())
        first = (self.sha(self.out, pr.ONNX_NAME), self.sha(self.out, pr.VOCAB_NAME))
        self.run_prune()
        self.assertEqual(first, (self.sha(self.out, pr.ONNX_NAME), self.sha(self.out, pr.VOCAB_NAME)))
        self.assertEqual(before, (self.sha(self.orig, pr.ONNX_NAME), self.sha(self.orig, pr.VOCAB_NAME)))

    def test_refuses_same_dir(self):
        with self.assertRaises(pr.PruneError):
            pr.run(self.orig, self.orig, check_orig_sha=False, expected_kept=0, vocab_lines=len(self.lines))

    def test_orig_sha_gate_blocks_modified_input(self):
        with self.assertRaises(pr.PruneError):
            pr.run(self.orig, self.out, vocab_lines=len(self.lines))  # SHA kontrolu acik: sentetik dosya orijinal degil
        self.assertFalse(os.path.exists(self.out))

    def test_missing_special_token_fails_without_outputs(self):
        self.lines = [l for l in self.lines if l != "[MASK]"]
        with open(os.path.join(self.orig, pr.VOCAB_NAME), "wb") as f:
            f.write(("\n".join(self.lines) + "\n").encode())
        with open(os.path.join(self.orig, pr.ONNX_NAME), "wb") as f:
            f.write(model(len(self.lines)))
        with self.assertRaises(pr.PruneError):
            self.run_prune()
        self.assertFalse(os.path.exists(os.path.join(self.out, pr.ONNX_NAME)))

    def test_unexpected_count_is_reported(self):
        with self.assertRaises(pr.PruneError):
            pr.run(self.orig, self.out, check_orig_sha=False, expected_kept=99, vocab_lines=len(self.lines))

    def test_failed_write_leaves_no_temp_files(self):
        os.makedirs(self.out)
        os.makedirs(os.path.join(self.out, pr.VOCAB_NAME))  # vocab hedefi dizin: os.replace basarisiz
        with self.assertRaises(OSError):
            self.run_prune()
        self.assertEqual([], [n for n in os.listdir(self.out) if ".tmp-" in n])

    def test_cli_exit_codes(self):
        self.assertEqual(1, pr.main(["--orig-dir", self.orig, "--out-dir", self.orig]))
        self.assertEqual(1, pr.main(["--orig-dir", os.path.join(self.tmp.name, "yok"), "--out-dir", self.out]))


if __name__ == "__main__":
    unittest.main(verbosity=1)
