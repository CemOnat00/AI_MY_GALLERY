#!/usr/bin/env bash
# fetch_models.sh için ağsız kabuk testi (T-017 QA; T-027: önbellek + budama adımı). Sahte curl, python3 ve sha256sum
# PATH'in başına konur; gerçek ağa/Hugging Face'e çıkılmaz, gerçek dosya indirilmez, gerçek budama çalışmaz (budama betiği
# tools/test_prune_text_model.py ve verify_pruned_text.py ile sınanır). Çıkış kodu 0 = tüm kontroller geçti.
# Kullanım: tools/test_fetch_models.sh
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/fetch_models.sh"
ROOT="$(mktemp -d "${TMPDIR:-/tmp}/fetch_models_test.XXXXXX")"
trap 'rm -rf "$ROOT"' EXIT

FAILS=0
PASSES=0
ok()   { PASSES=$((PASSES + 1)); echo "ok   - $1"; }
bad()  { FAILS=$((FAILS + 1)); echo "FAIL - $1" >&2; }
check() { if eval "$2"; then ok "$1"; else bad "$1  [$2]"; fi; }

shaval() { sed -n "s/^$1=\"\\(.*\\)\"\$/\\1/p" "$SCRIPT" | head -1; }
SHA_TEXT_ONNX="$(shaval SHA_TEXT_ONNX)"
SHA_TEXT_ONNX_PRUNED="$(shaval SHA_TEXT_ONNX_PRUNED)"
SHA_VOCAB_PRUNED="$(shaval SHA_VOCAB_PRUNED)"
SHA_DENSE_SAFETENSORS="$(shaval SHA_DENSE_SAFETENSORS)"
SHA_VOCAB="$(shaval SHA_VOCAB)"
SHA_VISION_INT8="$(shaval SHA_VISION_INT8)"
SHA_DENSE_BIN="$(shaval SHA_DENSE_BIN)"
check "betikten 7 SHA okundu" '[[ ${#SHA_TEXT_ONNX} -eq 64 && ${#SHA_DENSE_SAFETENSORS} -eq 64 && ${#SHA_VOCAB} -eq 64 && ${#SHA_VISION_INT8} -eq 64 && ${#SHA_DENSE_BIN} -eq 64 && ${#SHA_TEXT_ONNX_PRUNED} -eq 64 && ${#SHA_VOCAB_PRUNED} -eq 64 ]]'

# --- Sahte araçlar ---------------------------------------------------------------------------------------------
FAKEBIN="$ROOT/fakebin"
mkdir -p "$FAKEBIN"

# Sahte curl: argümanları günlüğe yazar. FAKE_CURL_FAIL=1 ise 22 ile çıkar. FAKE_CURL_SIGINT=1 ise ebeveyne SIGINT yollar.
# Aksi halde -o dosyasına "SHA=<istenen yola göre beklenen sha>" yazar. Gerçek ağ yok.
cat >"$FAKEBIN/curl" <<EOF
#!/usr/bin/env bash
echo "\$*" >>"\$FAKE_LOG"
out=""; url=""
while [[ \$# -gt 0 ]]; do
  case "\$1" in -o) out="\$2"; shift 2;; http*|ftp*) url="\$1"; shift;; *) shift;; esac
done
if [[ "\${FAKE_CURL_SIGINT:-0}" == "1" ]]; then echo "yarim" >"\$out"; kill -INT \$PPID; sleep 2; exit 130; fi
if [[ "\${FAKE_CURL_FAIL:-0}" == "1" ]]; then echo "yarim" >"\$out"; exit 22; fi
case "\$url" in
  *onnx/model_qint8_arm64.onnx) sha="$SHA_TEXT_ONNX";;
  *vocab.txt) sha="$SHA_VOCAB";;
  *2_Dense/model.safetensors) sha="$SHA_DENSE_SAFETENSORS";;
  *onnx/vision_model_quantized.onnx) sha="$SHA_VISION_INT8";;
  *) echo "beklenmeyen url: \$url" >&2; exit 99;;
esac
echo "SHA=\$sha" >"\$out"
EOF

# Sahte python3 (YALNIZCA `python3 -I ...` kabul eder, aksi halde 99 ile çıkar):
#  (1) `-I - SRC OUT`: Dense dönüştürme (betik stdin'den gelir); .bin içine FAKE_BIN_SHA (varsayılan doğru) yazar.
#  (2) `-I .../prune_text_model.py --orig-dir O --out-dir D`: budama; O'da girdiler yoksa 3, O==D ise 4 ile çıkar, aksi halde
#      D'ye budanmış işaretli dosyalar yazar (FAKE_PRUNE_BAD=1 ise yanlış SHA'lı). Gerçek budama burada çalışmaz.
cat >"$FAKEBIN/python3" <<EOF
#!/usr/bin/env bash
echo "python3 \$*" >>"\$FAKE_LOG"
if [[ "\${1:-}" == "-I" && "\${2:-}" == "-" ]]; then
  cat >/dev/null
  echo "SHA=\${FAKE_BIN_SHA:-$SHA_DENSE_BIN}" >"\$4"
elif [[ "\${1:-}" == "-I" && "\${2:-}" == *prune_text_model.py ]]; then
  shift 2; orig=""; out=""
  while [[ \$# -gt 0 ]]; do case "\$1" in --orig-dir) orig="\$2"; shift 2;; --out-dir) out="\$2"; shift 2;; *) shift;; esac; done
  [[ -f "\$orig/text_model_qint8_arm64.onnx" && -f "\$orig/text_vocab.txt" ]] || exit 3
  [[ "\$orig" != "\$out" ]] || exit 4
  [[ "\${FAKE_PRUNE_FAIL:-0}" != "1" ]] || exit 5
  if [[ "\${FAKE_PRUNE_BAD:-0}" == "1" ]]; then
    echo "SHA=bozuk" >"\$out/text_model_qint8_arm64.onnx"; echo "SHA=bozuk" >"\$out/text_vocab.txt"
  else
    echo "SHA=$SHA_TEXT_ONNX_PRUNED" >"\$out/text_model_qint8_arm64.onnx"; echo "SHA=$SHA_VOCAB_PRUNED" >"\$out/text_vocab.txt"
  fi
else
  echo "beklenmeyen python3 çağrısı (-I eksik?): \$*" >&2; exit 99
fi
EOF

# Sahte sha256sum: "SHA=" işaretli dosyada işaretin değerini, aksi halde "yok" basar.
cat >"$FAKEBIN/sha256sum" <<'EOF'
#!/usr/bin/env bash
v="$(sed -n 's/^SHA=//p' "$1" 2>/dev/null | head -1)"
echo "${v:-yok}  $1"
EOF
chmod +x "$FAKEBIN"/*

# run_case ADI [ENV=DEĞER ...] -- [betik argümanları]: temiz hedef klasörle betiği sahte PATH ile çalıştırır.
run_case() {
  CASE_DIR="$ROOT/$1"; shift
  DEST="$CASE_DIR/models"; CACHE="$CASE_DIR/cache"; mkdir -p "$DEST"
  FAKE_LOG="$CASE_DIR/log"; : >"$FAKE_LOG"
  local envs=()
  while [[ "${1:-}" != "--" ]]; do envs+=("$1"); shift; done
  shift
  # Önce hedef klasörü hazırlayan kanca (PRE) çalışsın.
  if [[ -n "${PRE:-}" ]]; then eval "$PRE"; fi
  env PATH="$FAKEBIN:$PATH" FAKE_LOG="$FAKE_LOG" MODEL_CACHE="$CACHE" ${envs[@]+"${envs[@]}"} \
    bash "$SCRIPT" "$@" "$DEST" >"$CASE_DIR/out" 2>"$CASE_DIR/err"
  RC=$?
}

# 1) Mutlu yol: tüm dosyalar iner, çıkış 0, .part ve geçici dosya kalmaz, .bin yerinde.
PRE=""; run_case happy --
check "mutlu yol: çıkış 0" '[[ $RC -eq 0 ]]'
check "mutlu yol: 5 çıktı dosyası var" '[[ -f "$DEST/text_model_qint8_arm64.onnx" && -f "$DEST/text_vocab.txt" && -f "$DEST/text_dense_768x512_f32.bin" && -f "$DEST/vision_model_quantized.onnx" ]]'
check "mutlu yol: .part kalmadı" '[[ -z "$(ls "$DEST"/*.part 2>/dev/null)" ]]'
check "mutlu yol: .dense.safetensors kalmadı" '[[ ! -e "$DEST/.dense.safetensors" ]]'
# T-027: HEDEF'te BUDANMIŞ (SHA doğrulanmış) dosyalar, önbellekte ORİJİNALLER; budama python3 -I ile ve orig != out ile çağrıldı.
check "mutlu yol: hedefteki metin ONNX ve sözlük budanmış sürüm" '[[ "$(cat "$DEST/text_model_qint8_arm64.onnx")" == "SHA=$SHA_TEXT_ONNX_PRUNED" && "$(cat "$DEST/text_vocab.txt")" == "SHA=$SHA_VOCAB_PRUNED" ]]'
check "mutlu yol: orijinaller önbellekte kaldı" '[[ "$(cat "$CACHE/text_model_qint8_arm64.onnx")" == "SHA=$SHA_TEXT_ONNX" && "$(cat "$CACHE/text_vocab.txt")" == "SHA=$SHA_VOCAB" ]]'
check "mutlu yol: orijinaller hedefe İNDİRİLMEDİ (curl çıktısı yalnızca önbelleğe)" '! grep -q "$DEST/text_model_qint8_arm64.onnx" "$FAKE_LOG" && grep -q "$CACHE/text_model_qint8_arm64.onnx.part" "$FAKE_LOG"'
check "mutlu yol: budama python3 -I ile, --orig-dir önbellek --out-dir hedef" 'grep -q "^python3 -I .*/prune_text_model.py --orig-dir $CACHE --out-dir $DEST\$" "$FAKE_LOG"'
check "mutlu yol: Dense dönüştürme python3 -I - ile" 'grep -q "^python3 -I - " "$FAKE_LOG"'
check "mutlu yol: python3 -I olmayan çağrı yok" '! grep "^python3" "$FAKE_LOG" | grep -qv "^python3 -I "'

# 2) Her curl çağrısında --proto '=https' --proto-redir '=https' var ve URL https.
CALLS="$(grep -c '^-fsSL' "$FAKE_LOG")"
check "curl 4 kez çağrıldı" '[[ "$CALLS" -eq 4 ]]'
check "her curl çağrısı --proto =https" '[[ "$(grep -c -- "--proto =https" "$FAKE_LOG")" -eq "$CALLS" ]]'
check "her curl çağrısı --proto-redir =https" '[[ "$(grep -c -- "--proto-redir =https" "$FAKE_LOG")" -eq "$CALLS" ]]'
check "her curl URL'si https://huggingface.co ile başlar" '[[ "$(grep -c "https://huggingface.co/" "$FAKE_LOG")" -eq "$CALLS" ]]'
check "http:// (düz) URL yok" '! grep -q "http://" "$FAKE_LOG"'

# 3) --text-only: görüntü modeli indirilmez.
PRE=""; run_case textonly -- --text-only
check "--text-only: çıkış 0" '[[ $RC -eq 0 ]]'
check "--text-only: vision dosyası yok" '[[ ! -e "$DEST/vision_model_quantized.onnx" ]]'

# 4) İndirme hatası: yalnızca betiğin kendi .part'ı silinir, başkasının .part'ı ve tamamlanmış dosya korunur.
PRE='echo baska >"$DEST/baska_arac.part"; echo eski >"$DEST/text_vocab.txt"; echo geri >"$DEST/.baska.safetensors"'
run_case curlfail FAKE_CURL_FAIL=1 --
check "curl hatası: çıkış sıfırdan farklı" '[[ $RC -ne 0 ]]'
check "curl hatası: başkasının .part dosyası korundu" '[[ -f "$DEST/baska_arac.part" && "$(cat "$DEST/baska_arac.part")" == "baska" ]]'
check "curl hatası: betiğin kendi .part dosyası silindi (önbellekte)" '[[ ! -e "$CACHE/text_model_qint8_arm64.onnx.part" && ! -e "$DEST/text_model_qint8_arm64.onnx.part" ]]'
check "curl hatası: ilgisiz gizli dosya korundu" '[[ -f "$DEST/.baska.safetensors" ]]'
check "curl hatası: mevcut tamamlanmış dosyaya dokunulmadı" '[[ "$(cat "$DEST/text_vocab.txt")" == "eski" ]]'

# 5) SIGINT (Ctrl-C): 130 ile çıkar, kendi .part'ı silinir, başkasının .part'ı kalır.
PRE='echo baska >"$DEST/baska_arac.part"'
run_case sigint FAKE_CURL_SIGINT=1 --
check "SIGINT: çıkış 130" '[[ $RC -eq 130 ]]'
check "SIGINT: başkasının .part dosyası korundu" '[[ -f "$DEST/baska_arac.part" ]]'
check "SIGINT: kendi .part dosyası silindi" '[[ ! -e "$CACHE/text_model_qint8_arm64.onnx.part" && ! -e "$DEST/text_model_qint8_arm64.onnx.part" ]]'

# 6) İndirilen dosyanın SHA'sı yanlış: .part silinir, çıkış 1.
PRE='echo baska >"$DEST/baska_arac.part"'
cat >"$FAKEBIN/sha256sum" <<'EOF'
#!/usr/bin/env bash
echo "0000000000000000000000000000000000000000000000000000000000000000  $1"
EOF
run_case badsha --
check "yanlış SHA: çıkış 1" '[[ $RC -eq 1 ]]'
check "yanlış SHA: hedef ve önbellek dosyası oluşmadı" '[[ ! -e "$DEST/text_model_qint8_arm64.onnx" && ! -e "$CACHE/text_model_qint8_arm64.onnx" ]]'
check "yanlış SHA: .part silindi, başkasınınki kaldı" '[[ ! -e "$CACHE/text_model_qint8_arm64.onnx.part" && -f "$DEST/baska_arac.part" ]]'
# sha256sum'ı geri yükle
cat >"$FAKEBIN/sha256sum" <<'EOF'
#!/usr/bin/env bash
v="$(sed -n 's/^SHA=//p' "$1" 2>/dev/null | head -1)"
echo "${v:-yok}  $1"
EOF

# 7) Dönüştürülmüş .bin'in SHA'sı tutmuyor: .bin SİLİNİR, çıkış 1, mesaj "dosya silindi".
PRE=""
run_case badbin FAKE_BIN_SHA=ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff --
check "bozuk .bin: çıkış 1" '[[ $RC -eq 1 ]]'
check "bozuk .bin: text_dense_768x512_f32.bin silindi" '[[ ! -e "$DEST/text_dense_768x512_f32.bin" ]]'
check "bozuk .bin: hata mesajı stderr'de" 'grep -q "Dense .bin SHA-256 uyuşmuyor" "$CASE_DIR/err"'
check "bozuk .bin: .dense.safetensors ve .part kalmadı" '[[ ! -e "$DEST/.dense.safetensors" && -z "$(ls "$DEST"/*.part 2>/dev/null)" ]]'

# 8) Önceden var olan SHA'sı yanlış .bin: yeniden dönüştürülür ve düzelir; zaten doğru olan .bin için indirme yapılmaz.
PRE='echo "SHA=bozuk" >"$DEST/text_dense_768x512_f32.bin"'
run_case repair --
check "yanlış mevcut .bin: onarıldı, çıkış 0" '[[ $RC -eq 0 && "$(cat "$DEST/text_dense_768x512_f32.bin")" == "SHA=$SHA_DENSE_BIN" ]]'
PRE='echo "SHA=$SHA_DENSE_BIN" >"$DEST/text_dense_768x512_f32.bin"'
run_case skipbin --
check "doğru mevcut .bin: safetensors indirilmedi, dönüştürme çağrılmadı" '! grep -q "2_Dense/model.safetensors" "$FAKE_LOG" && ! grep -q "^python3 -I - " "$FAKE_LOG"'

# 10) T-027 idempotans: ikinci çalıştırma ağa çıkmaz, budama çağrılmaz, çıktılar değişmez.
PRE=""; run_case idem --
RC1=$RC
env PATH="$FAKEBIN:$PATH" FAKE_LOG="$FAKE_LOG" MODEL_CACHE="$CACHE" bash "$SCRIPT" "$DEST" >"$CASE_DIR/out2" 2>"$CASE_DIR/err2"; RC2=$?
check "idempotans: iki çalıştırma da 0" '[[ $RC1 -eq 0 && $RC2 -eq 0 ]]'
check "idempotans: ikinci çalıştırmada budama ve curl çağrısı eklenmedi" '[[ "$(grep -c "prune_text_model.py" "$FAKE_LOG")" -eq 1 && "$(grep -c "^-fsSL" "$FAKE_LOG")" -eq 4 ]]'
check "idempotans: ikinci çalıştırma 'zaten doğrulanmış budanmış' diyor" 'grep -q "zaten doğrulanmış budanmış" "$CASE_DIR/out2"'

# 11) T-027: eski kurulum (HEDEF'te ORİJİNAL metin ONNX+sözlük var): ağsız önbelleğe kopyalanır, hedef budanmışla değişir.
PRE='echo "SHA=$SHA_TEXT_ONNX" >"$DEST/text_model_qint8_arm64.onnx"; echo "SHA=$SHA_VOCAB" >"$DEST/text_vocab.txt"'
run_case migrate --
check "eski kurulum: çıkış 0" '[[ $RC -eq 0 ]]'
check "eski kurulum: metin dosyaları için curl yok (yalnızca safetensors ve vision)" '[[ "$(grep -c "^-fsSL" "$FAKE_LOG")" -eq 2 ]] && ! grep -q "vocab.txt\|model_qint8_arm64.onnx" <(grep "^-fsSL" "$FAKE_LOG")'
check "eski kurulum: orijinaller önbelleğe alındı, hedef budanmış" '[[ "$(cat "$CACHE/text_model_qint8_arm64.onnx")" == "SHA=$SHA_TEXT_ONNX" && "$(cat "$DEST/text_model_qint8_arm64.onnx")" == "SHA=$SHA_TEXT_ONNX_PRUNED" && "$(cat "$DEST/text_vocab.txt")" == "SHA=$SHA_VOCAB_PRUNED" ]]'

# 12) T-027: budama çıktısı yanlış SHA'lı: hedefteki çıktılar SİLİNİR, çıkış 1, orijinaller önbellekte korunur.
PRE='echo baska >"$DEST/baska_arac.part"'
run_case badprune FAKE_PRUNE_BAD=1 --
check "bozuk budama: çıkış 1, mesaj stderr'de" '[[ $RC -eq 1 ]] && grep -q "budanmış metin modeli/sözlüğü SHA-256 uyuşmuyor" "$CASE_DIR/err"'
check "bozuk budama: hedefte budanmış çıktılar silindi, başkasının dosyası kaldı" '[[ ! -e "$DEST/text_model_qint8_arm64.onnx" && ! -e "$DEST/text_vocab.txt" && -f "$DEST/baska_arac.part" ]]'
check "bozuk budama: orijinaller önbellekte duruyor" '[[ -f "$CACHE/text_model_qint8_arm64.onnx" && -f "$CACHE/text_vocab.txt" ]]'

# 13) T-027: budama betiği hata verirse (çıkış kodu != 0) fetch_models.sh de hata verir (set -e), hedefte bozuk dosya yoktur.
PRE=""
run_case prunefail FAKE_PRUNE_FAIL=1 --
check "budama betiği hatası: çıkış sıfırdan farklı, hedefte metin ONNX/sözlük yok" '[[ $RC -ne 0 && ! -e "$DEST/text_model_qint8_arm64.onnx" && ! -e "$DEST/text_vocab.txt" ]]'
check "budama betiği hatası: Dense/vision adımına geçilmedi" '[[ ! -e "$DEST/text_dense_768x512_f32.bin" && ! -e "$DEST/vision_model_quantized.onnx" ]]'

# 9) Betik kaynağı: --proto bayrakları ve OWN_TEMP sınırı (geriye dönük regresyon güvencesi).
check "kaynak: curl satırında --proto '=https' --proto-redir '=https'" "grep -q \"curl .*--proto '=https' --proto-redir '=https'\" \"$SCRIPT\""
check "kaynak: cleanup joker (*.part) kullanmıyor" '! grep -E "^cleanup\(\).*\*\.part" "$SCRIPT"'

echo "---"
echo "geçen: $PASSES, kalan: $FAILS"
[[ $FAILS -eq 0 ]]
