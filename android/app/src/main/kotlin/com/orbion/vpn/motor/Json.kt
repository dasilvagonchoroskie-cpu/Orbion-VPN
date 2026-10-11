package com.orbion.vpn.motor

import org.json.JSONArray
import org.json.JSONObject

/**
 * Conversão entre os mapas/listas do Kotlin e texto JSON — é assim que a
 * tela (processo principal) e o motor (processo ":vpn") trocam dados.
 *
 * Tudo vai embrulhado em {"v": ...} para que qualquer valor (inclusive um
 * texto solto ou null) chegue do outro lado exatamente como saiu.
 */
object Json {

    fun escrever(valor: Any?): String {
        val embrulho = JSONObject()
        embrulho.put("v", if (valor == null) JSONObject.NULL else JSONObject.wrap(valor) ?: JSONObject.NULL)
        return embrulho.toString()
    }

    fun ler(texto: String?): Any? {
        if (texto.isNullOrEmpty()) return null
        return try {
            paraKotlin(JSONObject(texto).opt("v"))
        } catch (_: Exception) {
            null
        }
    }

    fun paraKotlin(x: Any?): Any? = when (x) {
        null -> null
        JSONObject.NULL -> null
        is JSONObject -> {
            val m = LinkedHashMap<String, Any?>()
            val chaves = x.keys()
            while (chaves.hasNext()) {
                val k = chaves.next()
                m[k] = paraKotlin(x.opt(k))
            }
            m
        }
        is JSONArray -> (0 until x.length()).map { paraKotlin(x.opt(it)) }
        else -> x
    }
}
