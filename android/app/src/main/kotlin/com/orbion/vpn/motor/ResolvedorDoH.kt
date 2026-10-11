package com.orbion.vpn.motor

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolve nomes por DNS criptografado (HTTPS), só para os endereços que saem
 * DIRETO (rota inteligente). Assim o provedor de internet não vê nem a
 * consulta desses nomes.
 *
 * Se a rede bloquear o DNS criptografado (algumas redes de empresa bloqueiam),
 * usa o DNS normal do aparelho como último recurso — o site vai por fora do
 * Tor de qualquer forma, e a conexão em si continua cifrada (HTTPS).
 *
 * O próprio Orbion fica fora do túnel, então estas consultas saem pela rede
 * normal do aparelho.
 */
object ResolvedorDoH {

    private const val TAG = "OrbionDoH"
    private const val VALIDADE_MS = 300_000L
    private const val VALIDADE_FALHA_MS = 30_000L

    private val servidores = listOf(
        "https://1.1.1.1/dns-query?type=A&name=",
        "https://1.0.0.1/dns-query?type=A&name=",
        "https://8.8.8.8/resolve?type=A&name=",
    )

    private val regexIPv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    private class Entrada(val enderecos: List<InetAddress>, val expiraEm: Long)

    private val cache = ConcurrentHashMap<String, Entrada>()

    fun resolver(nome: String): List<InetAddress> {
        val chave = nome.lowercase(Locale.ROOT).trimEnd('.')
        val agora = System.currentTimeMillis()
        cache[chave]?.let { e ->
            if (agora < e.expiraEm) return e.enderecos
            cache.remove(chave)
        }
        for (servidor in servidores) {
            val r = consultar(servidor, chave)
            if (r.isNotEmpty()) {
                cache[chave] = Entrada(r, agora + VALIDADE_MS)
                return r
            }
        }
        val doAparelho = try {
            InetAddress.getAllByName(chave).filterIsInstance<Inet4Address>()
        } catch (_: Exception) {
            emptyList()
        }
        cache[chave] = Entrada(doAparelho, agora + if (doAparelho.isEmpty()) VALIDADE_FALHA_MS else VALIDADE_MS)
        return doAparelho
    }

    private fun consultar(servidor: String, nome: String): List<InetAddress> {
        var con: HttpURLConnection? = null
        return try {
            con = URL(servidor + URLEncoder.encode(nome, "UTF-8")).openConnection() as HttpURLConnection
            con.requestMethod = "GET"
            con.setRequestProperty("Accept", "application/dns-json")
            con.connectTimeout = 4000
            con.readTimeout = 4000
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
                if (ip.matches(regexIPv4)) {
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
