package com.ktu.aigaleri.ml

import com.ktu.aigaleri.ProjectLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * T-027 QA: (5) ModelManifest sabitleri, fetch_models.sh, prune_text_model.py ve docs'taki SHA/boyut/satır sayıları tutarlı
 * (otomatik grep); (6) git: model ağırlığı, .model-cache, __pycache__ izlenmiyor; MODEL_VERSION ve tokenizer/ModelStore kodu
 * T-027 ile DEĞİŞMEDİ. Yerleşimden bağımsız (ProjectLayout: eski `mobile/` altı ve yeni kök). git yoksa/depo değilse git testleri atlanır (assumeTrue).
 */
class T027QaConsistencyTest {
    private val projectDir = ProjectLayout.projectRoot

    private fun text(rel: String) = File(projectDir, rel).readText(Charsets.UTF_8)

    private val onnx = ModelManifest.TEXT_ONNX
    private val vocab = ModelManifest.TEXT_VOCAB

    private val origOnnxSha = "3bed77e83926519660b5c0833cb3321cfde952331edec4f7d501c7d384b8a8a7"
    private val origVocabSha = "fe0fda7c425b48c516fc8f160d594c8022a0808447475c1a7c6d6479763f310c"
    private val origOnnxSize = 135_336_307L
    private val origVocabSize = 995_526L
    private val dropped = 49_717
    private val kept = 69_830

    @Test
    fun arithmetic_ofManifestSizes_matchesPrunedRows() {
        assertEquals(119_547, kept + dropped)
        assertEquals(GoldenText.ORIGINAL_VOCAB_SIZE, kept + dropped)
        assertEquals(origOnnxSize - dropped * 768L, onnx.sizeBytes)
        assertEquals(38_182_656L, origOnnxSize - onnx.sizeBytes)
        assertTrue(vocab.sizeBytes < origVocabSize)
        assertEquals("MODEL_VERSION değişmez", "clip-b32-multilingual-v1:text-qint8-arm64,vision-int8", ModelManifest.MODEL_VERSION)
        assertEquals(ModelManifest.MODEL_VERSION, ModelManifest.EMBEDDING_SPEC.modelVersion)
        // eski değerler manifestte kalmadı
        assertTrue(onnx.sha256 != origOnnxSha && vocab.sha256 != origVocabSha)
    }

    @Test
    fun fetchScript_shaConstants_equalManifestAndPruneTool() {
        val sh = text("tools/fetch_models.sh")
        fun shVar(name: String) = Regex("""(?m)^$name="([0-9a-f]{64})"$""").find(sh)?.groupValues?.get(1) ?: error("$name yok")
        assertEquals(onnx.sha256, shVar("SHA_TEXT_ONNX_PRUNED"))
        assertEquals(vocab.sha256, shVar("SHA_VOCAB_PRUNED"))
        assertEquals(ModelManifest.TEXT_DENSE.sha256, shVar("SHA_DENSE_BIN"))
        assertEquals(ModelManifest.VISION_ONNX.sha256, shVar("SHA_VISION_INT8"))
        assertEquals(origOnnxSha, shVar("SHA_TEXT_ONNX"))
        assertEquals(origVocabSha, shVar("SHA_VOCAB"))
        val py = text("tools/prune_text_model.py")
        fun pyVar(name: String) = Regex("""(?m)^$name = "([0-9a-f]{64})"$""").find(py)?.groupValues?.get(1) ?: error("$name yok")
        assertEquals(onnx.sha256, pyVar("PRUNED_ONNX_SHA"))
        assertEquals(vocab.sha256, pyVar("PRUNED_VOCAB_SHA"))
        assertEquals(origOnnxSha, pyVar("ORIG_ONNX_SHA"))
        assertEquals(origVocabSha, pyVar("ORIG_VOCAB_SHA"))
        assertTrue(py.contains("EXPECTED_KEPT = 69_830"))
        assertTrue(py.contains("ORIG_VOCAB_LINES = 119547"))
        assertEquals(origVocabSha, PrunedVocabEquivalenceTest.ORIGINAL_VOCAB_SHA256)
        // Dosya adları: betik, araç ve manifest aynı adlara bakar
        assertEquals("text_model_qint8_arm64.onnx", onnx.fileName)
        assertEquals("text_vocab.txt", vocab.fileName)
        assertTrue(py.contains("ONNX_NAME = \"${onnx.fileName}\"") && py.contains("VOCAB_NAME = \"${vocab.fileName}\""))
        assertTrue(sh.contains("${onnx.fileName}") && sh.contains("${vocab.fileName}"))
        // stdlib-only: araç yalnızca bu modülleri içe aktarır; üçüncü taraf yok
        val imports = py.lines().filter { Regex("""^(import|from)\s""").containsMatchIn(it) }
            .map { it.removePrefix("import ").removePrefix("from ").substringBefore(' ').substringBefore('.') }.toSet()
        assertEquals(setOf("argparse", "hashlib", "os", "sys", "unicodedata"), imports)
        assertTrue("kabuk betiği budama için python3 -I kullanır", sh.contains("python3 -I \"\$HERE/prune_text_model.py\""))
        assertFalse("pip/venv kullanılmamalı", Regex("""(?m)^[^#]*\b(pip|pip3|virtualenv)\b""").containsMatchIn(sh))
    }

    @Test
    fun docs_shaAndSizes_matchManifest_andNoStaleTextModelHash() {
        val known = setOf(
            onnx.sha256, vocab.sha256, origOnnxSha, origVocabSha, ModelManifest.TEXT_DENSE.sha256, ModelManifest.VISION_ONNX.sha256,
            "d12568dc7300970a4d3dbb49068ad16cd89b99840b74b026f8e48071e9414f74", // Dense safetensors
            // model-setup.md'deki üçüncü taraf kanıtları (OpenAI CLIP ağırlık URL'i ve LICENSE dosyası); metin modeliyle ilgisiz
            "40d365715913c9da98579312b702a82c18be219cc2a73407c4526f58eba950af",
            "987e63b32f6c89ff5160e429458a872ff048e6860b590a3912e938f9da8f14db",
        )
        val setup = text("docs/model-setup.md")
        fun row(prefix: String) = setup.lines().first { it.startsWith(prefix) }
        val onnxRow = row("| `text_model_qint8_arm64.onnx`")
        assertTrue(onnxRow, onnxRow.contains("| ${onnx.sizeBytes} |") && onnxRow.contains(onnx.sha256) && onnxRow.contains("69830"))
        val vocabRow = row("| `text_vocab.txt`")
        assertTrue(vocabRow, vocabRow.contains("| ${vocab.sizeBytes} |") && vocabRow.contains(vocab.sha256) && vocabRow.contains("69830"))
        assertTrue(row("| `text_dense_768x512_f32.bin`").let { it.contains("| ${ModelManifest.TEXT_DENSE.sizeBytes} |") && it.contains(ModelManifest.TEXT_DENSE.sha256) })
        assertTrue(row("| `vision_model_quantized.onnx`").let { it.contains("| ${ModelManifest.VISION_ONNX.sizeBytes} |") && it.contains(ModelManifest.VISION_ONNX.sha256) })
        assertTrue(setup.contains("$origOnnxSize") && setup.contains(origOnnxSha) && setup.contains("$origVocabSize") && setup.contains(origVocabSha))

        val pruning = text("docs/vocab-pruning.md")
        for (n in listOf("${onnx.sizeBytes}", "${vocab.sizeBytes}", onnx.sha256, vocab.sha256, "69830", "49717", "38182656", "$origOnnxSize", "$origVocabSize", "119547")) {
            assertTrue("vocab-pruning.md içinde yok: $n", pruning.contains(n))
        }
        assertTrue(pruning.contains("49717 satır x 768"))
        // Dokümanlardaki her 64 haneli hex bilinen bir değerdir (eski/yanlış SHA kalmadı)
        for (f in listOf("docs/model-setup.md", "docs/vocab-pruning.md", "docs/golden-text.md")) {
            for (m in Regex("""\b[0-9a-f]{64}\b""").findAll(text(f))) {
                assertTrue("$f içinde bilinmeyen SHA-256: ${m.value}", m.value in known)
            }
        }
        // MODEL_VERSION'ın değişmediği belgelenmiş
        assertTrue(pruning.contains("MODEL_VERSION"))
        assertTrue(text("docs/golden-text.md").contains("T-027"))
        // Lisans notu: "değiştirilmiş model"
        assertTrue(setup.contains("Değiştirilmiş model (T-027)") && setup.contains("DEĞİŞTİRİLMİŞ"))
        // ModelManifest kaynak yorumundaki sayılar kodla uyumlu
        val manifestSrc = File(projectDir, "app/src/main/java/com/ktu/aigaleri/ml/ModelManifest.kt").readText()
        assertTrue(manifestSrc.contains("69830") && manifestSrc.contains("135336307") && manifestSrc.contains("3bed77e8...a8a7") && manifestSrc.contains("fe0fda7c...310c"))
        assertTrue(manifestSrc.contains("sizeBytes = 97_153_651L") && manifestSrc.contains("sizeBytes = 521_296L"))
    }

    // ------------------------------------------------------------------------------------------------------------
    // (6) git. Yollar iki yerleşimde de çalışır: git köküne göreli yol = gitPrefix + proje köküne göreli yol
    // (eski yerleşim: "mobile/", yeni yerleşim: "").

    private fun git(vararg args: String): Pair<Int, String>? {
        val root = ProjectLayout.gitRoot ?: return null
        return ProjectLayout.git(root, *args)
    }

    /** Git kökünden göreli yol için proje köküne göreli karşılık (proje dışındaysa null). */
    private fun inProject(gitPath: String): String? = gitPath.removePrefix(ProjectLayout.gitPrefix!!).takeIf { gitPath.startsWith(ProjectLayout.gitPrefix!!) }

    /** Proje köküne göreli yol -> git köküne göreli yol (git show/diff için). */
    private fun gp(projectRel: String) = ProjectLayout.gitPrefix!! + projectRel

    /** Git kökündeki tüm izlenen dosyalar (git köküne göreli). */
    private fun trackedFiles(): List<String> {
        val r = git("ls-files", "-z")
        assumeTrue("git yok veya depo değil", r != null && r.first == 0 && ProjectLayout.gitRoot != null)
        return r!!.second.split('\u0000').filter { it.isNotEmpty() }
    }

    @Test
    fun git_doesNotTrackModelWeightsCacheOrPycache() {
        val all = trackedFiles()
        assumeTrue("izlenen dosya yok", all.isNotEmpty())
        val gitRoot = ProjectLayout.gitRoot!!
        // Proje köküne göre normalize edilmiş yollar (yalnızca proje altındakiler)
        val files = all.mapNotNull { inProject(it) }
        val bad = files.filter {
            Regex("""\.(onnx|ort|safetensors|pt|pth|bin|gguf|tflite)$""", RegexOption.IGNORE_CASE).containsMatchIn(it) ||
                it.contains(".model-cache") || it.contains("__pycache__") || it.endsWith(".pyc") ||
                it.contains("src/main/assets/models/") || it.endsWith(".part") || it.endsWith(".apk") || it.endsWith(".aab")
        }
        assertEquals("izlenen yasak dosyalar: $bad", emptyList<String>(), bad)
        // Ağırlık gibi büyük dosya yok (en büyüğü orijinal sözlük gzip'i, ~0,5 MB)
        val big = all.map { File(gitRoot, it) }.filter { it.isFile && it.length() > 5_000_000 }
        assertEquals("5 MB üstü izlenen dosya: $big", emptyList<File>(), big)
        // CLAUDE.md kuralı: GitHub'a yalnızca proje (Gradle kökü) ve reports/ gider. Eski yerleşimde proje git kökünün
        // alt dizinidir (dışında yalnızca reports/ ve kök .gitignore olabilir); yeni yerleşimde dışarıda hiçbir şey yoktur.
        val outside = all.filter { inProject(it) == null && !it.startsWith("reports/") && it != ".gitignore" }
        assertEquals("proje kökü ve reports/ dışında izlenen: $outside", emptyList<String>(), outside)
        // Yerel yönetim dosyaları (CLAUDE.md, architecture.md, todo.md, claude_subagent.md, .claude/, Proje_Mobil_26.*)
        // her iki yerleşimde de git dışıdır. Dosyanın diskte olması gerekmez; yalnızca git ls-files denetlenir.
        val local = all.filter { path ->
            listOf(path, inProject(path)).filterNotNull().any {
                val top = it.substringBefore('/')
                top in setOf("CLAUDE.md", "architecture.md", "todo.md", "claude_subagent.md", ".claude") || top.startsWith("Proje_Mobil_26.")
            }
        }
        assertEquals("yerel yönetim dosyaları izleniyor: $local", emptyList<String>(), local)
        // T-027 testi için gereken orijinal sözlük (model ağırlığı DEĞİL) izleniyor
        assertTrue(files.contains("app/src/test/resources/ml/original_vocab.txt.gz"))
    }

    @Test
    fun git_ignoreRules_coverModelFilesCacheAndPycache() {
        trackedFiles()
        val root = ProjectLayout.projectRoot
        for (p in listOf(
            ".model-cache/text_model_qint8_arm64.onnx", ".model-cache/text_vocab.txt",
            "app/src/main/assets/models/text_model_qint8_arm64.onnx", "app/src/main/assets/models/text_vocab.txt",
            "tools/__pycache__/prune_text_model.cpython-313.pyc", "tools/__pycache__/x.pyc", "app/src/main/assets/models/yeni.onnx",
        )) {
            val r = ProjectLayout.git(root, "check-ignore", "-q", p)
            assertEquals("git check-ignore $p (0 = yoksayılıyor)", 0, r?.first)
        }
    }

    /** İlk commit'i (en eski) verilen başlık önekiyle eşleşen / başlığı tam eşleşen commit'in karması; yoksa null. */
    private fun commitBySubject(match: (String) -> Boolean, oldest: Boolean): String? {
        val r = git("log", "--format=%H%x09%s")?.takeIf { it.first == 0 } ?: return null
        val hits = r.second.lines().filter { it.contains('\t') }.map { it.substringBefore('\t') to it.substringAfter('\t') }
            .filter { match(it.second) }
        return (if (oldest) hits.lastOrNull() else hits.firstOrNull())?.first
    }

    @Test
    fun git_tokenizerModelStoreAndModelVersion_unchangedByT027() {
        // Sabit commit aralığı: T-027'nin ilk commit'inin ebeveyni .. reviewer düzeltmesi. Karmalar geçmiş yeniden
        // yazıldığında (örn. filter-repo ile köke yükseltme) değiştiğinden commit'ler başlığa göre bulunur.
        // Yoksa (sığ klon) atlanır.
        assumeTrue("git yok veya depo değil", ProjectLayout.gitRoot != null)
        val first = commitBySubject({ it.startsWith("T-027: metin modeli sözlük budama") }, oldest = true)
        val reviewer = commitBySubject({ it == "T-027: reviewer düzeltmeleri" }, oldest = true)
        assumeTrue("T-027 commit'leri yok", first != null && reviewer != null)
        val base = "$first~1"
        val head = reviewer!!
        assumeTrue("T-027 commit'leri yok", git("rev-parse", "--verify", "-q", "$head^{commit}")?.first == 0 && git("rev-parse", "--verify", "-q", "$base^{commit}")?.first == 0)
        for (f in listOf("WordPieceTokenizer.kt", "ModelStore.kt", "QueryPreprocessor.kt", "EmbeddingMath.kt", "TextEncoder.kt")) {
            val path = gp("app/src/main/java/com/ktu/aigaleri/ml/$f")
            if (git("cat-file", "-e", "$base:$path")?.first != 0) continue
            val d = git("diff", "--stat", base, head, "--", path)
            assertEquals("$f değişti: ${d?.second}", "", d?.second?.trim())
        }
        // Üretim kodundaki diğer değişiklikler yalnızca yorum satırları (OnnxTextEncoder, AppDependencies, TextSessionTrimHandler)
        val d = git("diff", "-U0", base, head, "--", gp("app/src/main/java/com/ktu/aigaleri/ml/OnnxTextEncoder.kt"), gp("app/src/main/java/com/ktu/aigaleri/ui"))!!.second
        val changed = d.lines().filter { (it.startsWith("+") || it.startsWith("-")) && !it.startsWith("+++") && !it.startsWith("---") }
        assertTrue("yorum dışı değişiklik: $changed", changed.all { it.drop(1).trim().let { l -> l.startsWith("*") || l.startsWith("//") || l.startsWith("/*") } })
        // MODEL_VERSION sabiti aynı
        val manifestPath = gp("app/src/main/java/com/ktu/aigaleri/ml/ModelManifest.kt")
        val before = git("show", "$base:$manifestPath")!!.second
        val after = git("show", "$head:$manifestPath")!!.second
        fun mv(s: String) = Regex("""const val MODEL_VERSION = "([^"]+)"""").find(s)!!.groupValues[1]
        assertEquals(mv(before), mv(after))
        assertEquals(mv(after), ModelManifest.MODEL_VERSION)
        // EMBEDDING_DIMENSION aynı
        assertEquals(512, ModelManifest.EMBEDDING_DIMENSION)
    }
}
