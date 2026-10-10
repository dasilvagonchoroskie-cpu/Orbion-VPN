package com.orbion.vpn.motor

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolve nomes por DNS criptografado (HTTPS), só para os endereços que saem
 * DIRETO (desvio de serviços que recusam o Tor). Assim o provedor de
 * internet não vê a lista de sites nem nesse caso.
 *
 * O próprio Orbion fica fora do túnel, então estas consultas saem pela rede
 * normal do aparelho.
 */
object ResolvedorDoH {

    private const val TAG = "OrbionDoH"
    private const val VALIDADE_MS = 300_000L

    private val servidores = listOf(
        "https://1.1.1.1/dns-query?type=A&name=",
        "https://1.0.0.1/dns-query?type=A&name=",
        "https://8.8.8.8/resolve?type=A&name=",
    )

    private data class Entrada(val enderecos: List<InetAddress>, val expiraEm: Long)

    private val cache = ConcurrentHashMap<String, Entrada>()

    fun resolver(nome: String): List<InetAddress> {
        val chave = nome.lowercase(Locale.ROOT).trimEnd('.')
        cache[chave]?.let { e ->
            if (System.currentTimeMillis() < e.expiraEm) return e.enderecos
            cache.remove(chave)
        }
        for (servidor in servidores) {
            val r = consultar(servidor, chave)
            if (r.isNotEmpty()) {
                cache[chave] = Entrada(r, System.currentTimeMillis() + VALIDADE_MS)
                return r
            }
        }
        return emptyList()
    }

    private fun consultar(servidor: String, nome: String): List<InetAddress> {
        var con: HttpURLConnection? = null
        return try {
            con = URL(servidor + URLEncoder.encode(nome, "UTF-8")).openConnection() as HttpURLConnection
            con.requestMethod = "GET"
            con.setRequestProperty("Accept", "application/dns-json")
            con.connectTimeout = 5000
            con.readTimeout = 5000
            if (con.responseCode != 200) return emptyList()
            val corpo = con.inputStream.bufferedReader().use { it.readText() }
            val respostas = JSONObject(corpo).optJSONArray("Answer") ?: return emptyList()
            val lista = ArrayList<InetAddress>()
            for (i in 0 until respostas.length()) {
                val o = respostas.optJSONObject(i) ?: continue
                if (o.optInt("type") != 1) continue
                val ip = o.optString("data")
                // Só aceita IPv4 literal: nunca deixa o InetAddress sair
                // fazendo consulta de DNS comum por conta própria.
                if (ip.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) {
                    try {
                        lista.add(InetAddress.getByName(ip))
                    } catch (_: Exception) {
                    }
                }
            }
            lista
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao resolver $nome em $servidor: ${e.message}")
            emptyList()
        } finally {
            con?.disconnect()
        }
    }

    fun limparCache() = cache.clear()
}
