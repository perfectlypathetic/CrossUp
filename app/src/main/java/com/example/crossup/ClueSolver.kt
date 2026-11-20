package com.example.crossup.ai

import com.example.crossup.game.Candidate
import com.example.crossup.game.TR
import org.json.JSONArray

interface ClueSolver {
    /**
     * cluepng: ipucu hücresinin kırpılmış PNG'si. length: yuvadaki hücre sayısı.
     * pattern: bilinen harfler, ör. "A??E?" (bilinmeyen '?'). Hepsi '?' ise ipucu yok sayılır.
     */
    suspend fun solve(cluepng: ByteArray, length: Int, pattern: String): List<Candidate>
}

class GeminiClueSolver(
    private val apiKey: String,
    private val models: List<String> = Models.SOLVER
) : ClueSolver {
    private val cache = HashMap<String, List<Candidate>>()

    override suspend fun solve(cluepng: ByteArray, length: Int, pattern: String): List<Candidate> {
        val key = "${cluepng.contentHashCode()}:$pattern"
        synchronized(cache) { cache[key] }?.let { return it }

        val known = if (pattern.any { it != '?' }) " Bilinen harfler (?=bilinmiyor): $pattern." else ""
        val prompt = "Bu bir Türkçe bulmaca ipucu (yazı veya resim). Cevap TAM $length harfli, tek kelime olmalı.$known " +
            "En olası en fazla 6 aday ver. Sadece JSON dön: [{\"word\":\"KELİME\",\"p\":0.7}, ...]. " +
            "Büyük harf, Türkçe karakterlerle (İ,I,Ş,Ğ,Ü,Ö,Ç)."
        val json = GeminiHttp.askImage(apiKey, models, cluepng, "image/png", prompt, 300)
        val arr = JSONArray(json)
        val out = (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Candidate(it.getString("word").trim().uppercase(TR), it.optDouble("p", 0.1)) }
            .filter { it.word.length == length }
        synchronized(cache) { cache[key] = out }
        return out
    }
}
