#!/usr/bin/env bash
# GELİŞTİRİCİ ARACI: uygulamanın parçası DEĞİLDİR, APK'ya girmez, uygulama bunu çalıştırmaz.
# Yalnızca geliştirici makinesinde, derleme öncesi bir kez çalıştırılır. Uygulama hiçbir zaman ağdan
# model indirmez (INTERNET izni yok). Dosyalar sabitlenmiş Hugging Face revizyonlarından indirilir,
# SHA-256 ile doğrulanır ve app/src/main/assets/models/ altına yazılır (bu klasör git'e eklenmez).
# Gereken: bash, curl, python3 (yalnızca standart kütüphane), shasum veya sha256sum.
# Kaynak ve lisanslar: mobile/docs/model-setup.md. Değerler: mobile/docs/model-research.md bölüm 6.
#
# Kullanım: tools/fetch_models.sh [--text-only] [HEDEF_KLASÖR]
set -euo pipefail

WITH_VISION=1
if [[ "${1:-}" == "--text-only" ]]; then WITH_VISION=0; shift; fi
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="${1:-$HERE/../app/src/main/assets/models}"
mkdir -p "$DEST"

# Ctrl-C/hata/çıkışta yarım dosyaları sil (.part ve geçici safetensors); tamamlanmış dosyalara dokunulmaz.
cleanup() { rm -f "$DEST"/*.part "$DEST/.dense.safetensors"; }
trap cleanup EXIT
trap 'echo "iptal edildi" >&2; exit 130' INT TERM

TEXT_REPO="sentence-transformers/clip-ViT-B-32-multilingual-v1"
TEXT_REV="58edf8cada9e398793dca955574a48cbb7f18be2"
VISION_REPO="Xenova/clip-vit-base-patch32"
VISION_REV="d15189d7028b43f1d3e65039190477f6af591c2a"

# Uygulamadaki ml/ModelManifest.kt ile aynı değerler olmalıdır.
SHA_TEXT_ONNX="3bed77e83926519660b5c0833cb3321cfde952331edec4f7d501c7d384b8a8a7"
SHA_DENSE_SAFETENSORS="d12568dc7300970a4d3dbb49068ad16cd89b99840b74b026f8e48071e9414f74"
SHA_VOCAB="fe0fda7c425b48c516fc8f160d594c8022a0808447475c1a7c6d6479763f310c"
SHA_VISION_INT8="583fd1110a514667812fee7d684952aaf82a99b959760c8d7dca7e0ab9839299"
SHA_DENSE_BIN="e5f6548cbcef6c62631b3946d89042ab4c8451762c7bb0a3be11eb6c480b686d"

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# fetch REPO REV YOL_REPODA HEDEF_DOSYA BEKLENEN_SHA
fetch() {
  local repo="$1" rev="$2" path="$3" out="$4" want="$5"
  if [[ -f "$out" && "$(sha256 "$out")" == "$want" ]]; then
    echo "tamam (zaten doğrulanmış): $(basename "$out")"; return
  fi
  local url="https://huggingface.co/${repo}/resolve/${rev}/${path}"
  echo "indiriliyor: ${repo}@${rev:0:8} ${path}"
  curl -fsSL --retry 3 -o "$out.part" "$url"
  local got; got="$(sha256 "$out.part")"
  if [[ "$got" != "$want" ]]; then
    rm -f "$out.part"
    echo "HATA: SHA-256 uyuşmuyor: $path (beklenen $want, gelen $got)" >&2
    exit 1
  fi
  mv "$out.part" "$out"
}

# Dense ağırlığı: safetensors (F32, [512,768], satır sıralı [çıkış][giriş]) -> ham float32 little-endian .bin
convert_dense() {
  local src="$1" out="$2"
  python3 - "$src" "$out" <<'PY'
import json, struct, sys
src, out = sys.argv[1], sys.argv[2]
with open(src, "rb") as f:
    n = struct.unpack("<Q", f.read(8))[0]
    header = json.loads(f.read(n))
    t = header["linear.weight"]
    assert t["dtype"] == "F32" and t["shape"] == [512, 768], t
    start, end = t["data_offsets"]
    f.seek(8 + n + start)
    data = f.read(end - start)
assert len(data) == 512 * 768 * 4
with open(out + ".part", "wb") as o:
    o.write(data)
import os
os.replace(out + ".part", out)
PY
}

fetch "$TEXT_REPO" "$TEXT_REV" "onnx/model_qint8_arm64.onnx" "$DEST/text_model_qint8_arm64.onnx" "$SHA_TEXT_ONNX"
fetch "$TEXT_REPO" "$TEXT_REV" "vocab.txt" "$DEST/text_vocab.txt" "$SHA_VOCAB"

if [[ ! -f "$DEST/text_dense_768x512_f32.bin" || "$(sha256 "$DEST/text_dense_768x512_f32.bin")" != "$SHA_DENSE_BIN" ]]; then
  TMP="$DEST/.dense.safetensors"
  fetch "$TEXT_REPO" "$TEXT_REV" "2_Dense/model.safetensors" "$TMP" "$SHA_DENSE_SAFETENSORS"
  convert_dense "$TMP" "$DEST/text_dense_768x512_f32.bin"
  rm -f "$TMP"
  if [[ "$(sha256 "$DEST/text_dense_768x512_f32.bin")" != "$SHA_DENSE_BIN" ]]; then
    echo "HATA: dönüştürülen Dense .bin SHA-256 uyuşmuyor" >&2; exit 1
  fi
fi
echo "tamam: text_dense_768x512_f32.bin"

if [[ "$WITH_VISION" == "1" ]]; then
  fetch "$VISION_REPO" "$VISION_REV" "onnx/vision_model_quantized.onnx" "$DEST/vision_model_quantized.onnx" "$SHA_VISION_INT8"
fi
echo "Bitti. Hedef: $DEST"
