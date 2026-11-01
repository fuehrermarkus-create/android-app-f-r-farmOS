package at.hof.farmos.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

val JsonObject.attributes: JsonObject get() = obj("attributes") ?: JsonObject(emptyMap())

/** Text aus einem farmOS-Textfeld (notes ist { value, format }). */
fun JsonObject.textField(key: String): String? {
    return when (val el: JsonElement? = this[key]) {
        is JsonObject -> el.str("value")
        is JsonPrimitive -> el.contentOrNull
        else -> null
    }
}

/** IDs einer Beziehung (einzeln oder mehrfach). */
fun JsonObject.relationIds(name: String): List<String> {
    val data = obj("relationships")?.obj(name)?.get("data") ?: return emptyList()
    return when (data) {
        is JsonArray -> data.mapNotNull { (it as? JsonObject)?.str("id") }
        is JsonObject -> listOfNotNull(data.str("id"))
        else -> emptyList()
    }
}

fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

@Suppress("unused")
fun JsonElement.asObj(): JsonObject = jsonObject
