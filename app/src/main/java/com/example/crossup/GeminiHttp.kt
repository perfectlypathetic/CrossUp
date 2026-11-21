package com.example.crossup.ai
 
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
 
/** Görevlere göre model öncelik listeleri. Soldaki en tercih edilen. */
object Models {
    /** Tahta/raf okuma: doğruluk kritik, tek istek -> en güçlü modeller önce. */
    val READER = listOf(
        "gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3-flash-preview",
        "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-robotics-er-2-preview"
    )
 
    /** İpucu çözme: çok sayıda paralel istek -> kotayı yaymak için 3.6 ile başla, Gemma en sonda. */
    val SOLVER = listOf(
        "gemini-3.6-flash", "gemini-3.8-flash", "gemini-3.5-flash", "gemini-3-flash-preview",
        "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.1-flash-lite-preview",
        "gemini-flash-lite-latest", "gemma-4-31b-it", "gemma-4-26b-a4b-it"
    )
}
 
class ApiError(val code: Int, message: String) : Exception(message)
 
object GeminiHttp {
    private const val TAG = "GeminiHttp"
 
    /** model -> bu zamana (ms) kadar kullanma. */
    private val cooldown = ConcurrentHashMap<String, Long>()
    @Volatile var lastModel: String = ""; private set
 
    private fun now() = System.currentTimeMillis()
    private fun ban(model: String, ms: Long) { cooldown[model] = now() + ms }
 
    /** Soğumada olmayanlar sırayla; hepsi soğumadaysa en erken açılandan başla. */
    private fun order(chain: List<String>): List<String> {
        val ok = chain.filter { (cooldown[it] ?: 0L) <= now() }
        return if (ok.isNotEmpty()) ok else chain.sortedBy { cooldown[it] ?: 0L }
    }
 
    suspend fun askImage(
        apiKey: String, chain: List<String>, image: ByteArray, mime: String,
        prompt: String, maxTokens: Int
    ): String = withContext(Dispatchers.IO) {
        var last = "bilinmiyor"
        for (model in order(chain).take(4)) {      // en fazla 4 model dene
            try {
                val out = try {
                    call(apiKey, model, image, mime, prompt, maxTokens, plain = model.startsWith("gemma"))
                } catch (e: ApiError) {
                    // 400: bu model thinkingConfig / JSON modunu desteklemiyor olabilir -> sade ayarla bir kez daha dene.
                    if (e.code == 400 && !isKeyError(e)) call(apiKey, model, image, mime, prompt, maxTokens, plain = true)
                    else throw e
                }
                lastModel = model
                return@withContext out
            } catch (e: ApiError) {
                last = "$model: ${e.message}"
                Log.w(TAG, last)
                if (isKeyError(e) || e.code == 401 || e.code == 403) throw e      // anahtar sorunu: model değiştirmek çare değil
                when (e.code) {
                    429 -> ban(model, 60_000)                  // kota: 1 dk dinlendir
                    500, 502, 503, 504 -> ban(model, 30_000)   // yoğunluk
                    404 -> ban(model, 3_600_000)               // bu hesapta yok
                    else -> ban(model, 600_000)                // 400 vb.
                }
            } catch (e: IOException) {                         // zaman aşımı / ağ
                last = "$model: ${e.message}"
                Log.w(TAG, last)
                ban(model, 20_000)
            } catch (e: Exception) {                           // JSON parse / boş cevap
                last = "$model: ${e.message}"
                Log.w(TAG, last)
            }
        }
        error("Tüm modeller başarısız oldu. Son hata: ${last.take(200)}")
    }
 
    private fun isKeyError(e: ApiError) =
        e.message?.contains("API key", ignoreCase = true) == true ||
        e.message?.contains("API_KEY", ignoreCase = true) == true
 
    private fun call(
        apiKey: String, model: String, image: ByteArray, mime: String,
        prompt: String, maxTokens: Int, plain: Boolean
    ): String {
        val gen = JSONObject().put("maxOutputTokens", maxOf(maxTokens, 1024))
        if (!plain) {
            gen.put("responseMimeType", "application/json")
            gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("inlineData", JSONObject()
                    .put("mimeType", mime)
                    .put("data", Base64.encodeToString(image, Base64.NO_WRAP))))
                .put(JSONObject().put("text", prompt)))))
            .put("generationConfig", gen)
 
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 10_000; readTimeout = 25_000
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw ApiError(code, resp.take(200))
 
            val parts = JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IllegalStateException("Boş cevap (güvenlik filtresi?)")
            val text = (0 until parts.length()).map { parts.getJSONObject(it) }
                .filter { !it.optBoolean("thought", false) }
                .joinToString("") { it.optString("text", "") }
            val s = text.indexOfFirst { it == '[' || it == '{' }
            val e = text.indexOfLast { it == ']' || it == '}' }
            if (s < 0 || e < s) throw IllegalStateException("JSON bulunamadı: ${text.take(120)}")
            return text.substring(s, e + 1)
        } finally { conn.disconnect() }
    }
}
 
