package com.orbion.vpn.motor

import android.content.Context
import java.util.Locale

/**
 * Catálogo de serviços que recusam conexões vindas da rede Tor, e os
 * domínios que o filtro desvia para saírem direto.
 */
object AppsProblematicos {

    enum class Motivo(val titulo: String, val explicacao: String) {
        BANCO("Banco", "Bancos recusam conexões de rede anônima por política antifraude. Não é falha do Orbion — é regra do banco."),
        ANTIFRAUDE("Antifraude", "Usa um sistema de detecção de fraude que recusa saídas Tor. Costuma deixar o texto passar e travar envio de arquivos."),
        STREAMING("Streaming", "Bloqueia por causa de licenciamento por país. Como o Tor sai em outro país, o conteúdo é recusado."),
        LOCALIZACAO("Precisa da sua região", "Depende de saber onde você está de verdade. Pelo Tor, o serviço vê outro país e não consegue funcionar."),
    }

    data class Conhecido(val pacote: String, val nome: String, val motivo: Motivo)

    private val catalogo = listOf(
        Conhecido("com.nu.production", "Nubank", Motivo.BANCO),
        Conhecido("com.itau", "Itaú", Motivo.BANCO),
        Conhecido("com.bradesco", "Bradesco", Motivo.BANCO),
        Conhecido("br.com.bb.android", "Banco do Brasil", Motivo.BANCO),
        Conhecido("br.gov.caixa.tem", "Caixa Tem", Motivo.BANCO),
        Conhecido("com.santander.app", "Santander", Motivo.BANCO),
        Conhecido("br.com.intermedium", "Inter", Motivo.BANCO),
        Conhecido("com.c6bank.app", "C6 Bank", Motivo.BANCO),
        Conhecido("com.picpay", "PicPay", Motivo.BANCO),
        Conhecido("com.mercadopago.wallet", "Mercado Pago", Motivo.BANCO),
        Conhecido("br.com.uol.ps.myaccount", "PagBank", Motivo.BANCO),
        Conhecido("com.larus.wolf", "Dola", Motivo.ANTIFRAUDE),
        Conhecido("com.zhiliaoapp.musically", "TikTok", Motivo.ANTIFRAUDE),
        Conhecido("com.ss.android.ugc.trill", "TikTok", Motivo.ANTIFRAUDE),
        Conhecido("com.lemon.lvoverseas", "CapCut", Motivo.ANTIFRAUDE),
        Conhecido("com.instagram.android", "Instagram", Motivo.ANTIFRAUDE),
        Conhecido("com.facebook.katana", "Facebook", Motivo.ANTIFRAUDE),
        Conhecido("com.facebook.orca", "Messenger", Motivo.ANTIFRAUDE),
        Conhecido("com.netflix.mediaclient", "Netflix", Motivo.STREAMING),
        Conhecido("com.disney.disneyplus", "Disney+", Motivo.STREAMING),
        Conhecido("com.globo.globotv", "Globoplay", Motivo.STREAMING),
        Conhecido("com.amazon.avod.thirdpartyclient", "Prime Video", Motivo.STREAMING),
        Conhecido("com.ubercab", "Uber", Motivo.LOCALIZACAO),
        Conhecido("br.com.brainweb.ifood", "iFood", Motivo.LOCALIZACAO),
        Conhecido("com.taxis99", "99", Motivo.LOCALIZACAO),
    )

    private val dominiosSuspeitos: Map<String, Pair<String, Motivo>> = mapOf(
        "byteintl.com" to ("ByteDance" to Motivo.ANTIFRAUDE),
        "ciciai.com" to ("Cici / Dola" to Motivo.ANTIFRAUDE),
        "dola.com" to ("Dola" to Motivo.ANTIFRAUDE),
        "musical.ly" to ("TikTok" to Motivo.ANTIFRAUDE),
        "tiktokv.com" to ("TikTok" to Motivo.ANTIFRAUDE),
        "nubank.com.br" to ("Nubank" to Motivo.BANCO),
        "itau.com.br" to ("Itaú" to Motivo.BANCO),
        "bb.com.br" to ("Banco do Brasil" to Motivo.BANCO),
        "caixa.gov.br" to ("Caixa" to Motivo.BANCO),
        "nflxvideo.net" to ("Netflix" to Motivo.STREAMING),
        "netflix.com" to ("Netflix" to Motivo.STREAMING),
        "cdninstagram.com" to ("Instagram" to Motivo.ANTIFRAUDE),
        "instagram.com" to ("Instagram" to Motivo.ANTIFRAUDE),
        "fbcdn.net" to ("Facebook / Instagram" to Motivo.ANTIFRAUDE),
    )

    /** Domínios desviados para fora do Tor quando o "desvio" está ligado. */
    private val dominiosQueRecusamTor = setOf(
        "instagram.com", "cdninstagram.com", "fbcdn.net", "facebook.com", "fbsbx.com",
        "whatsapp.net", "byteintl.com", "ciciai.com", "dola.com", "tiktokv.com",
        "tiktokcdn.com", "musical.ly", "googlevideo.com", "youtube.com", "ytimg.com",
        "googleusercontent.com", "gstatic.com", "googleapis.com", "gvt1.com", "gvt2.com",
        "ggpht.com", "android.clients.google.com", "play.google.com", "accounts.google.com",
        "clients1.google.com", "clients2.google.com", "clients3.google.com",
        "clients4.google.com", "clients5.google.com", "clients6.google.com",
        "anthropic.com", "claude.ai", "nflxvideo.net", "netflix.com", "globo.com",
        "globoplay.globo.com",
    )

    fun exigeConexaoDireta(host: String): Boolean {
        var d = host.lowercase(Locale.ROOT).trimEnd('.')
        while (true) {
            if (d in dominiosQueRecusamTor) return true
            val p = d.indexOf('.')
            if (p < 0) return false
            d = d.substring(p + 1)
            if (d.isEmpty()) return false
        }
    }

    fun instaladosNoAparelho(context: Context): List<Conhecido> {
        val pm = context.packageManager
        return catalogo.filter {
            try {
                pm.getPackageInfo(it.pacote, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun donoDoDominio(host: String): Pair<String, Motivo>? {
        val limpo = host.trim().removePrefix("[").substringBefore("]").substringBefore(":")
        return dominiosSuspeitos.entries.firstOrNull { limpo.endsWith(it.key) }?.value
    }
}

/**
 * Lê o log do túnel e aponta servidores que estão sendo chamados de novo e
 * de novo — sinal de que o outro lado está recusando a conexão pelo Tor.
 */
object DetectorDeProblemas {

    private const val LIMITE_DE_TENTATIVAS = 8

    data class Suspeita(
        val servidor: String,
        val tentativas: Int,
        val nomeDoServico: String?,
        val motivo: AppsProblematicos.Motivo?,
    )

    fun analisar(context: Context): List<Suspeita> {
        val linhas = TunelHev.lerLog(context, 2000)
        if (linhas.isEmpty()) return emptyList()
        val contagem = LinkedHashMap<String, Int>()
        for (l in linhas) {
            val i = l.indexOf("tcp -> ")
            if (i < 0) continue
            val alvo = l.substring(i + 7).trim().removePrefix("[").substringBefore("]")
            if (alvo.isBlank() || alvo.none { it.isLetter() }) continue
            if (alvo.count { it == ':' } > 1) continue
            contagem[alvo] = (contagem[alvo] ?: 0) + 1
        }
        return contagem.filter { it.value >= LIMITE_DE_TENTATIVAS }
            .map { (servidor, n) ->
                val dono = AppsProblematicos.donoDoDominio(servidor)
                Suspeita(servidor, n, dono?.first, dono?.second)
            }
            .sortedByDescending { it.tentativas }
            .take(5)
    }
}
