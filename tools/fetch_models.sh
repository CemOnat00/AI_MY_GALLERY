#!/usr/bin/env bash
# GELİŞTİRİCİ ARACI: uygulamanın parçası DEĞİLDİR, APK'ya girmez, uygulama bunu çalıştırmaz.
# Yalnızca geliştirici makinesinde, derleme öncesi bir kez çalıştırılır. Uygulama hiçbir zaman ağdan
# model indirmez (INTERNET izni yok). Dosyalar sabitlenmiş Hugging Face revizyonlarından indirilir,
# SHA-256 ile doğrulanır ve app/src/main/assets/models/ altına yazılır (bu klasör git'e eklenmez).
# T-027 (K-14 Katman A): metin modelinin ONNX'i ve sözlüğü ORİJİNAL olarak önce MODEL_CACHE'e (varsayılan
# mobile/.model-cache/, git'e eklenmez) indirilip SHA-256 doğrulanır, sonra tools/prune_text_model.py (yalnızca Python
# standart kütüphanesi, python3 -I) ile BUDANMIŞ sürüm HEDEF_KLASÖR'e yazılır ve yeni SHA-256 doğrulanır. Orijinaller
# önbellekte kalır (altın üretimi/kalite kapıları için); HEDEF'te zaten doğrulanmış budanmış dosya varsa ağa çıkılmaz.
# Gereken: bash, curl, python3 (yalnızca standart kütüphane, >= 3.8), shasum veya sha256sum.
# Kaynak ve lisanslar: mobile/docs/model-setup.md. Değerler: mobile/docs/model-research.md bölüm 6.
#
# Kullanım: [MODEL_CACHE=DIZIN] tools/fetch_models.sh [--text-only] [HEDEF_KLASÖR]
set -euo pipefail

WITH_VISION=1
if [[ "${1:-}" == "--text-only" ]]; then WITH_VISION=0; shift; fi
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="${1:-$HERE/../app/src/main/assets/models}"
CACHE="${MODEL_CACHE:-$HERE/../.model-cache}"
mkdir -p "$DEST"

# Ctrl-C/hata/çıkışta YALNIZCA bu betiğin kendi başlattığı yarım dosyaları sil (.part ve geçici safetensors).
# Hedef klasörde başka birine ait *.part varsa dokunulmaz; tamamlanmış dosyalara da dokunulmaz.
# (bash 3.2: boş dizi uzunluk kontrolüyle korunur.)
OWN_TEMP=()
cleanup() { if (( ${#OWN_TEMP[@]} )); then rm -f "${OWN_TEMP[@]}"; fi; }
trap cleanup EXIT
trap 'echo "iptal edildi" >&2; exit 130' INT TERM

TEXT_REPO="sentence-transformers/clip-ViT-B-32-multilingual-v1"
TEXT_REV="58edf8cada9e398793dca955574a48cbb7f18be2"
VISION_REPO="Xenova/clip-vit-base-patch32"
VISION_REV="d15189d7028b43f1d3e65039190477f6af591c2a"

# ORİJİNAL (Hugging Face) dosyaların SHA-256 değerleri; budama girdisidir (tools/prune_text_model.py ile aynı).
SHA_TEXT_ONNX="3bed77e83926519660b5c0833cb3321cfde952331edec4f7d501c7d384b8a8a7"
SHA_DENSE_SAFETENSORS="d12568dc7300970a4d3dbb49068ad16cd89b99840b74b026f8e48071e9414f74"
SHA_VOCAB="fe0fda7c425b48c516fc8f160d594c8022a0808447475c1a7c6d6479763f310c"
# BUDANMIŞ (uygulamaya giren) dosyaların SHA-256 değerleri: ml/ModelManifest.kt ile aynı olmalıdır (testle denetlenir).
SHA_TEXT_ONNX_PRUNED="4e73a91f8463faaece382c033a25b035fe7adc54260910acb5d8e4a77872301c"
SHA_VOCAB_PRUNED="e3375077b9b203e67d8ed9ca271e68dc2e463af61a0c976041de6ee3e7b19cc8"
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
  OWN_TEMP+=("$out.part")
  curl -fsSL --proto '=https' --proto-redir '=https' --retry 3 -o "$out.part" "$url"
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
  OWN_TEMP+=("$out.part")
  python3 -I - "$src" "$out" <<'PY'
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

# --- Metin ONNX + sözlük: ORİJİNAL -> önbellek (SHA doğrulamalı) -> budama -> HEDEF (yeni SHA doğrulamalı) -----------
# ensure_original REPO REV YOL_REPODA DOSYA_ADI BEKLENEN_SHA: önbellekte doğrulanmış orijinal yoksa, HEDEF'te hâlâ
# orijinal (eski kurulum) varsa ondan kopyalar (ağsız), aksi halde indirir. Her durumda yalnızca doğrulanmış dosya kalır.
ensure_original() {
  local repo="$1" rev="$2" path="$3" name="$4" want="$5"
  local out="$CACHE/$name" old="$DEST/$name"
  if [[ -f "$out" && "$(sha256 "$out")" == "$want" ]]; then
    echo "tamam (önbellekte doğrulanmış orijinal): $name"; return
  fi
  if [[ -f "$old" && "$(sha256 "$old")" == "$want" ]]; then
    echo "önbelleğe alınıyor (hedefteki orijinalden, ağsız): $name"
    OWN_TEMP+=("$out.part")
    cp "$old" "$out.part"
    if [[ "$(sha256 "$out.part")" != "$want" ]]; then rm -f "$out.part"; echo "HATA: kopya SHA-256 uyuşmuyor: $name" >&2; exit 1; fi
    mv "$out.part" "$out"; return
  fi
  fetch "$repo" "$rev" "$path" "$out" "$want"
}

pruned_ok() {
  [[ -f "$DEST/text_model_qint8_arm64.onnx" && "$(sha256 "$DEST/text_model_qint8_arm64.onnx")" == "$SHA_TEXT_ONNX_PRUNED" &&
     -f "$DEST/text_vocab.txt" && "$(sha256 "$DEST/text_vocab.txt")" == "$SHA_VOCAB_PRUNED" ]]
}

if pruned_ok; then
  echo "tamam (zaten doğrulanmış budanmış): text_model_qint8_arm64.onnx, text_vocab.txt"
else
  mkdir -p "$CACHE"
  ensure_original "$TEXT_REPO" "$TEXT_REV" "onnx/model_qint8_arm64.onnx" "text_model_qint8_arm64.onnx" "$SHA_TEXT_ONNX"
  ensure_original "$TEXT_REPO" "$TEXT_REV" "vocab.txt" "text_vocab.txt" "$SHA_VOCAB"
  echo "budanıyor (Katman A, K-14): $CACHE -> $DEST"
  python3 -I "$HERE/prune_text_model.py" --orig-dir "$CACHE" --out-dir "$DEST"
  if ! pruned_ok; then
    # SHA'sı tutmayan budanmış çıktıyı bırakma: sonraki derleme bozuk dosyayı kullanmasın (orijinaller önbellekte durur).
    rm -f "$DEST/text_model_qint8_arm64.onnx" "$DEST/text_vocab.txt"
    echo "HATA: budanmış metin modeli/sözlüğü SHA-256 uyuşmuyor (dosyalar silindi; Python unicodedata sürümü?)" >&2; exit 1
  fi
  echo "tamam (budanmış, SHA-256 doğrulandı): text_model_qint8_arm64.onnx, text_vocab.txt"
fi

if [[ ! -f "$DEST/text_dense_768x512_f32.bin" || "$(sha256 "$DEST/text_dense_768x512_f32.bin")" != "$SHA_DENSE_BIN" ]]; then
  TMP="$DEST/.dense.safetensors"
  OWN_TEMP+=("$TMP")
  fetch "$TEXT_REPO" "$TEXT_REV" "2_Dense/model.safetensors" "$TMP" "$SHA_DENSE_SAFETENSORS"
  convert_dense "$TMP" "$DEST/text_dense_768x512_f32.bin"
  rm -f "$TMP"
  if [[ "$(sha256 "$DEST/text_dense_768x512_f32.bin")" != "$SHA_DENSE_BIN" ]]; then
    # SHA'sı tutmayan dönüştürülmüş .bin'i bırakma: sonraki çalıştırma/derleme bozuk dosyayı kullanmasın.
    rm -f "$DEST/text_dense_768x512_f32.bin"
    echo "HATA: dönüştürülen Dense .bin SHA-256 uyuşmuyor (dosya silindi)" >&2; exit 1
  fi
fi
echo "tamam: text_dense_768x512_f32.bin"

if [[ "$WITH_VISION" == "1" ]]; then
  fetch "$VISION_REPO" "$VISION_REV" "onnx/vision_model_quantized.onnx" "$DEST/vision_model_quantized.onnx" "$SHA_VISION_INT8"
fi
echo "Bitti. Hedef: $DEST"
