package com.example.crossup.ai

import com.example.crossup.game.TR
import com.example.crossup.parser.CellPos
import com.example.crossup.parser.ParsedBoard
import org.json.JSONObject

class Reading(val rack: List<Char>, val letters: Map<CellPos, Char>)

/** Ekran görüntüsünden eldeki harfleri ve tahtada duran harfleri okur. */
class GeminiBoardReader(
    private val apiKey: String,
    private val models: List<String> = Models.READER
) {

    /** need: harfi henüz bilinmeyen dolu hücreler (satır 0 = en üstteki ipucu satırı, 0 tabanlı). */
    suspend fun read(jpeg: ByteArray, board: ParsedBoard, need: List<CellPos>): Reading {
        val cellsTxt = if (need.isEmpty()) "yok" else need.joinToString(" ") { "(${it.r},${it.c})" }
        val prompt = "Bu bir Cross-Up (Türkçe kelime bulmaca) ekran görüntüsü. Izgara ${board.rows} satır x ${board.cols} sütun; " +
            "satır 0 en üstteki ipucu satırı, sütun 0 en soldaki sütun (ikisi de 0 tabanlı). " +
            "1) Altta rafta duran harfleri soldan sağa oku. " +
            "2) Şu hücrelerde duran harfi oku (satır,sütun): $cellsTxt. " +
            "Büyük harf, Türkçe karakterlerle (İ ile I farklıdır). Okuyamadığın hücreyi atla. " +
            "Sadece JSON dön: {\"rack\":[\"L\",\"İ\"],\"cells\":[{\"r\":3,\"c\":2,\"ch\":\"A\"}]}"
        val o = JSONObject(GeminiHttp.askImage(apiKey, models, jpeg, "image/jpeg", prompt, 800))
        val rackArr = o.optJSONArray("rack")
        val rack = (0 until (rackArr?.length() ?: 0)).mapNotNull { rackArr!!.getString(it).trim().uppercase(TR).firstOrNull() }
        val cArr = o.optJSONArray("cells")
        val letters = HashMap<CellPos, Char>()
        for (i in 0 until (cArr?.length() ?: 0)) {
            val c = cArr!!.getJSONObject(i)
            val ch = c.optString("ch").trim().uppercase(TR).firstOrNull() ?: continue
            letters[CellPos(c.getInt("r"), c.getInt("c"))] = ch
        }
        return Reading(rack, letters)
    }
}
