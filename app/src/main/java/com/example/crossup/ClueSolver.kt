package com.example.crossup.ai

import com.example.crossup.game.Candidate
import com.example.crossup.GameLog
import com.example.crossup.game.TR
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Bir ipucu sorusu: kırpılmış PNG, yuvadaki hücre sayısı, bilinen harfler (ör. "A??E?"). */
class ClueQuery(val png: ByteArray, val length: Int, val pattern: String)

interface ClueSolver {
    /** Tek ipucu çözer (hata olursa fırlatır). */
    suspend fun solve(cluepng: ByteArray, length: Int, pattern: String): List<Candidate>

    /**
     * Çok ipucuyu birkaç toplu istekle çözer. Sonuç listesi girdiyle aynı sıradadır.
     * null = bu ipucu için cevap alınamadı (sonra tekrar denenebilir).
     * Boş liste = AI cevap verdi ama kullanılabilir aday yok.
     */
    suspend fun solveBatch(items: List<ClueQuery>, deadline: Long): List<List<Candidate>?>
}

class GeminiClueSolver(
    private val apiKey: String,
    private val models: List<String> = Models.SOLVER
) : ClueSolver {

    companion object {
        /** Tek istekte en fazla kaç ipucu sorulur. */
        const val BATCH_SIZE = 12
    }

    private val cache = HashMap<String, List<Candidate>>()

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
        val out = arrayOfNulls<List<Candidate>>(items.size)

        // Önce önbellek.
        val todo = ArrayList<Int>()
        for (i in items.indices) {
            val c = synchronized(cache) { cache[cacheKey(items[i])] }
            if (c != null) out[i] = c else todo.add(i)
        }

        GameLog.i("ClueSolver", "toplu çözüm: ${items.size} ipucu, önbellekten ${items.size - todo.size}, AI'a sorulacak ${todo.size}")
        // Kalanları gruplar halinde, sırayla sor (kotayı ve hız sınırını korumak için).
        for (chunk in todo.chunked(BATCH_SIZE)) {
            if (System.currentTimeMillis() > deadline) break
            try {
                val parsed = askChunk(chunk.map { items[it] })
                GameLog.i("ClueSolver", "grup cevabı: ${parsed.count { it != null }}/${chunk.size} ipucu için cevap geldi")
                for ((j, idx) in chunk.withIndex()) {
                    val r = parsed[j] ?: continue
                    out[idx] = r
                    synchronized(cache) { cache[cacheKey(items[idx])] = r }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = (e.message ?: e.javaClass.simpleName).take(160)
                GameLog.e("ClueSolver", "toplu istek hatası: $lastError", e)
                break   // bir grup başarısızsa diğerleri de büyük ihtimalle başarısız olur; kotayı harcama
            }
        }
        return out.toList()
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

        val json = GeminiHttp.askParts(apiKey, models, parts, 400 + 150 * qs.size, readTimeoutMs = 60_000)
        GameLog.d("ClueSolver", "ham AI cevabı: ${json.take(600)}")

        // Model bazen diziyi bir nesnenin içine koyar: {"results":[...]}
        val arr: JSONArray = if (json.trimStart().startsWith("{")) {
            val o = JSONObject(json)
            var found: JSONArray? = null
            for (k in o.keys()) { o.optJSONArray(k)?.let { found = it; return@let } ; if (found != null) break }
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
