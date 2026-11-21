package com.example.crossup.ai

import com.example.crossup.game.Candidate
import com.example.crossup.game.TR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Bir ipucu sorusu: kırpılmış PNG, yuvadaki hücre sayısı, bilinen harfler (ör. "A??E?"). */
class ClueQuery(val png: ByteArray, val length: Int, val pattern: String)

interface ClueSolver {
    /** Tek ipucu çözer (hata olursa fırlatır). */
    suspend fun solve(cluepng: ByteArray, length: Int, pattern: String): List<Candidate>

    /**
     * Çok ipucuyu birkaç paralel istekle çözer. Sonuç listesi girdiyle aynı sıradadır.
     * null = bu ipucu için cevap (henüz) alınamadı (sonra tekrar denenebilir).
     * Boş liste = AI cevap verdi ama kullanılabilir aday yok.
     */
    suspend fun solveBatch(items: List<ClueQuery>, deadline: Long): List<List<Candidate>?>
}

class GeminiClueSolver(
    private val apiKey: String,
    private val models: List<String> = Models.SOLVER
) : ClueSolver {

    companion object {
        /** Tek istekte en fazla kaç ipucu sorulur (küçük = hızlı cevap). */
        const val BATCH_SIZE = 6
        /** Bir analizde AI cevaplarını en fazla bu kadar bekle (ms). Geç gelenler arka planda kaydedilir. */
        const val WAIT_MS = 25_000L
        /** Tek bir isteğin okuma zaman aşımı. */
        const val READ_TIMEOUT_MS = 30_000
        /** Boş sonuç gelen ipucu en fazla kaç kez daha denenir. */
        const val MAX_EMPTY_TRIES = 2
    }

    /** İstekler analiz bitse de arka planda devam eder; cevaplar önbelleğe yazılır. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cache = HashMap<String, List<Candidate>>()
    private val inflight = HashMap<String, Job>()
    private val emptyTries = HashMap<String, Int>()

    /** Son toplu çağrıdaki hata (boşsa hata yok). */
    @Volatile var lastError: String = ""
        private set

    private fun cacheKey(q: ClueQuery) = "${q.png.contentHashCode()}:${q.pattern}"

    override suspend fun solve(cluepng: ByteArray, length: Int, pattern: String): List<Candidate> {
        val r = solveBatch(listOf(ClueQuery(cluepng, length, pattern)), Long.MAX_VALUE)[0]
        return r ?: throw IllegalStateException(lastError.ifEmpty { "Cevap alınamadı" })
    }

    override suspend fun solveBatch(items: List<ClueQuery>, deadline: Long): List<List<Candidate>?> {
        lastError = ""
        val keys = items.map { cacheKey(it) }
        val waitJobs = ArrayList<Job>()
        val toStart = ArrayList<Job>()

        synchronized(cache) {
            // Önbellekte de, yolda da olmayanlar (aynı ipucu iki kez sorulmasın diye key ile tekilleştir).
            val fresh = LinkedHashMap<String, ClueQuery>()
            for (i in items.indices) {
                val k = keys[i]
                if (cache.containsKey(k)) continue
                val running = inflight[k]
                if (running != null) {
                    if (running !in waitJobs) waitJobs.add(running)
                    continue
                }
                fresh.putIfAbsent(k, items[i])
            }
            for (chunk in fresh.entries.chunked(BATCH_SIZE)) {
                val ks = chunk.map { it.key }
                val qs = chunk.map { it.value }
                val job = newChunkJob(ks, qs)
                for (k in ks) inflight[k] = job
                waitJobs.add(job)
                toStart.add(job)
            }
        }
        toStart.forEach { it.start() }

        // Süre dolarsa sadece beklemeyi bırak; istekler arka planda devam eder.
        val remaining = (minOf(deadline, System.currentTimeMillis() + WAIT_MS) - System.currentTimeMillis())
            .coerceAtLeast(1L)
        withTimeoutOrNull(remaining) { waitJobs.joinAll() }

        return keys.map { k -> synchronized(cache) { cache[k] } }
    }

    private fun newChunkJob(ks: List<String>, qs: List<ClueQuery>): Job =
        scope.launch(start = CoroutineStart.LAZY) {
            try {
                val parsed = askChunk(qs)
                for (j in ks.indices) {
                    val r = parsed[j] ?: continue
                    synchronized(cache) {
                        if (r.isEmpty()) {
                            val n = (emptyTries[ks[j]] ?: 0) + 1
                            emptyTries[ks[j]] = n
                            if (n >= MAX_EMPTY_TRIES) cache[ks[j]] = r   // vazgeç, boş olarak kaydet
                        } else {
                            cache[ks[j]] = r
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = (e.message ?: e.javaClass.simpleName).take(160)
            } finally {
                synchronized(cache) { for (k in ks) inflight.remove(k) }
            }
        }

    private suspend fun askChunk(qs: List<ClueQuery>): List<List<Candidate>?> {
        val parts = ArrayList<ReqPart>()
        parts.add(ReqPart.Text("Aşağıda ${qs.size} adet Türkçe bulmaca ipucu var (yazı veya resim). Her biri numaralı."))
        qs.forEachIndexed { i, q ->
            val known = if (q.pattern.any { it != '?' }) " Bilinen harfler (?=bilinmiyor): ${q.pattern}." else ""
            parts.add(ReqPart.Text("İpucu ${i + 1}: cevap TAM ${q.length} harfli, tek kelime.$known"))
            parts.add(ReqPart.Image(q.png, "image/png"))
        }
        parts.add(
            ReqPart.Text(
                "Her ipucu için en olası en fazla 5 aday ver. Bütün ${qs.size} ipucuna cevap ver. " +
                "Sadece JSON dön: [{\"id\":1,\"c\":[{\"word\":\"KELİME\",\"p\":0.7}]}, {\"id\":2,\"c\":[...]}]. " +
                "id = ipucu numarası. Büyük harf, Türkçe karakterlerle (İ,I,Ş,Ğ,Ü,Ö,Ç)."
            )
        )

        val json = GeminiHttp.askParts(apiKey, models, parts, 400 + 150 * qs.size, readTimeoutMs = READ_TIMEOUT_MS)

        // Model bazen diziyi bir nesnenin içine koyar: {"results":[...]}
        val arr: JSONArray = if (json.trimStart().startsWith("{")) {
            val o = JSONObject(json)
            var found: JSONArray? = null
            for (k in o.keys()) {
                val a = o.optJSONArray(k)
                if (a != null) { found = a; break }
            }
            found ?: throw IllegalStateException("Cevap biçimi anlaşılamadı")
        } else JSONArray(json)

        val res = arrayOfNulls<List<Candidate>>(qs.size)
        for (pos in 0 until arr.length()) {
            val obj = arr.optJSONObject(pos) ?: continue
            val idx = obj.optInt("id", pos + 1) - 1
            if (idx !in qs.indices) continue
            val cArr = obj.optJSONArray("c") ?: obj.optJSONArray("candidates") ?: JSONArray()
            val list = ArrayList<Candidate>()
            for (j in 0 until cArr.length()) {
                val c = cArr.optJSONObject(j) ?: continue
                val w = c.optString("word").trim().uppercase(TR)
                if (w.length != qs[idx].length) continue
                if (list.any { it.word == w }) continue
                list.add(Candidate(w, c.optDouble("p", 0.1).coerceAtLeast(0.01)))
            }
            res[idx] = list
        }
        return res.toList()
    }
}
