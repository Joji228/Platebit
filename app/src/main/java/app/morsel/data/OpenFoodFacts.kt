package app.morsel.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One product from Open Food Facts, with its nutrition per 100 g. */
data class FoodFact(
    val code: String,
    val name: String,
    val brand: String?,
    val kcalPer100: Double,
    val proteinPer100: Double,
    val carbsPer100: Double,
    val fatPer100: Double,
    /** One serving in grams, when the product lists it (a Kit Kat 4-finger bar is 41.5 g). */
    val servingGrams: Double? = null,
    /** Snacks, sweets and drinks by Open Food Facts' categories: Quick add files these as a Snack. */
    val snack: Boolean = false,
) {
    /** "Oreo · Oreo Original" style label, without repeating the brand. */
    val label: String get() = if (brand.isNullOrBlank() || name.contains(brand, ignoreCase = true)) name else "$brand $name"
}

/**
 * The free, open Open Food Facts product database (openfoodfacts.org), read through its
 * Search-a-licious full-text search. No key is needed; Open Food Facts asks apps to identify
 * themselves with a User-Agent. Only the text typed or the product name is sent, never photos.
 */
class OpenFoodFacts(version: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val userAgent = "Platebit/$version (https://github.com/Joji228/Platebit)"

    /** Products matching [query], best first; only ones with usable nutrition are kept. */
    suspend fun search(query: String, size: Int = 8): List<FoodFact> {
        val url = "https://search.openfoodfacts.org/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .addQueryParameter("page_size", (size * 2).toString()) // some hits lack nutrition and are dropped
            .addQueryParameter("fields", "code,product_name,brands,nutriments,serving_quantity,categories_tags")
            .build()
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        val body = http.newCall(request).await()
        return FoodFactsParser.parse(body).take(size)
    }

    /**
     * The best match for a product the AI named in a photo ("Oreo Original"), or null when
     * nothing close enough is found. Every word of the AI's name has to appear in the product's
     * name or brand, so a guess never swaps in an unrelated product.
     */
    /** The product with this barcode, or null when Open Food Facts doesn't have it (or lacks its nutrition). */
    suspend fun product(barcode: String): FoodFact? {
        val url = "https://world.openfoodfacts.org/api/v2/product/$barcode.json".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "code,product_name,brands,nutriments,serving_quantity,categories_tags")
            .build()
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        val body = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            if (e.message?.contains("404") == true) return null else throw e
        }
        return FoodFactsParser.parseProduct(body, barcode)
    }

    suspend fun match(product: String): FoodFact? = FoodFactsParser.bestMatch(product, search(product, size = 5))

    private suspend fun Call.await(): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) cont.resumeWithException(IOException("Open Food Facts answered ${it.code}"))
                    else cont.resume(it.body.string())
                }
            }
        })
    }
}

/** Reading Open Food Facts' answers; separate so it can be tested without the network. */
object FoodFactsParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<FoodFact> {
        val hits = runCatching { json.parseToJsonElement(body).jsonObject["hits"]?.jsonArray }.getOrNull() ?: return emptyList()
        val parsed = hits.mapNotNull { hit -> (hit as? JsonObject)?.let { obj -> product(obj)?.let { it to obj.hasCategories() } } }
        // Many entries have no categories at all. One of those counts as a snack when another
        // result from the same brand is one (an uncategorized "Kit Kat" next to categorized ones).
        val snackBrands = parsed.filter { (fact, _) -> fact.snack }.mapNotNull { (fact, _) -> fact.brand?.lowercase() }.toSet()
        return parsed.map { (fact, categorized) ->
            if (!categorized && fact.brand?.lowercase() in snackBrands) fact.copy(snack = true) else fact
        }
    }

    private fun JsonObject.hasCategories() = (this["categories_tags"] as? JsonArray)?.isNotEmpty() == true

    /** A single product answer ({"status":1,"product":{...}}) from the barcode lookup. */
    fun parseProduct(body: String, barcode: String): FoodFact? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val product = root["product"] as? JsonObject ?: return null
        return product(product)?.let { if (it.code.isEmpty()) it.copy(code = barcode) else it }
    }

    private fun product(hit: JsonObject): FoodFact? {
        val name = (hit["product_name"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (name.isEmpty()) return null
        val brand = when (val b = hit["brands"]) {
            is JsonArray -> b.firstOrNull()?.let { (it as? JsonPrimitive)?.content }
            is JsonPrimitive -> b.content.split(',').firstOrNull()
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
        val n = hit["nutriments"] as? JsonObject ?: return null
        fun num(key: String) = (n[key] as? JsonPrimitive)?.doubleOrNull
        // Energy is often only given in kilojoules.
        val kcal = num("energy-kcal_100g") ?: num("energy-kj_100g")?.div(KJ_PER_KCAL) ?: num("energy_100g")?.div(KJ_PER_KCAL) ?: return null
        if (kcal <= 0 || kcal > 950) return null
        return FoodFact(
            code = (hit["code"] as? JsonPrimitive)?.content.orEmpty(),
            name = name,
            brand = brand,
            kcalPer100 = kcal,
            proteinPer100 = num("proteins_100g") ?: 0.0,
            carbsPer100 = num("carbohydrates_100g") ?: 0.0,
            fatPer100 = num("fat_100g") ?: 0.0,
            servingGrams = (hit["serving_quantity"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it in 1.0..2000.0 },
            snack = (hit["categories_tags"] as? JsonArray).orEmpty().any { tag ->
                val t = (tag as? JsonPrimitive)?.content.orEmpty()
                SNACK_WORDS.any { it in t }
            },
        )
    }

    fun bestMatch(product: String, candidates: List<FoodFact>): FoodFact? {
        val words = product.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }
        if (words.isEmpty()) return null
        return candidates.firstOrNull { fact ->
            val text = "${fact.brand.orEmpty()} ${fact.name}".lowercase()
            words.all { it in text }
        }
    }

    private const val KJ_PER_KCAL = 4.184
    private val SNACK_WORDS = listOf("snack", "sweet", "chocolate", "biscuit", "confectioner", "crisp", "chips", "candies", "candy", "beverage", "drink")
}
