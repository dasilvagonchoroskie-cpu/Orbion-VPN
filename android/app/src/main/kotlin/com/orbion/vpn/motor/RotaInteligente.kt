package com.orbion.vpn.motor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A "VPN inteligente": decide quem passa pela rede Tor e quem vai por fora.
 *
 * Princípio: o Tor serve para esconder QUEM você é. Nos serviços em que você
 * entra com seu nome — banco, governo, WhatsApp, redes sociais, compras — o
 * serviço já sabe quem você é, então o Tor não protege nada ali, e esses
 * serviços quase sempre RECUSAM conexões vindas do Tor (antifraude, bloqueio
 * por país, chamadas por UDP). Então eles vão por fora, e o resto — navegador,
 * buscas, apps sem login — continua pelo Tor.
 *
 * Duas camadas, que se completam:
 *  1. POR APLICATIVO: os apps reconhecidos (catálogo + nome do pacote + nome
 *     do app) ficam fora do túnel da VPN. O tráfego deles nem passa pelo
 *     Orbion — vai direto, com a criptografia do próprio app (HTTPS, e de
 *     ponta a ponta nos mensageiros).
 *  2. POR SITE: no navegador e nos apps que continuam no túnel, os endereços
 *     dessas mesmas categorias saem direto (só com HTTPS — nunca http://),
 *     com o nome consultado por DNS criptografado. O resto vai pelo Tor.
 *
 * A pessoa pode desligar categorias, tirar ou devolver qualquer app, e
 * mandar qualquer site sempre por fora ou sempre pelo Tor. O Orbion nunca
 * esconde do Android nem dos apps que é uma VPN: a separação é feita às
 * claras, pelo próprio Android (apps fora do túnel).
 */
object RotaInteligente {

    /** Serviços do Google que entregam as notificações de todos os apps. */
    val SERVICOS_DO_GOOGLE = setOf("com.google.android.gms", "com.google.android.gsf", "com.android.vending")

    enum class Categoria(val id: String, val titulo: String, val resumo: String, val porque: String) {
        BANCOS(
            "bancos", "Bancos e pagamentos",
            "Bancos, carteiras digitais, Pix, maquininhas e corretoras",
            "Bancos recusam conexões da rede Tor por regra antifraude. Por fora do Tor eles funcionam normalmente — e os dados continuam protegidos pela criptografia do próprio banco.",
        ),
        GOVERNO(
            "governo", "Governo e serviços públicos",
            "gov.br, Receita, INSS, FGTS, Carteira de Trabalho, CNH, e-Título",
            "Os serviços públicos bloqueiam acessos de fora do Brasil, e a rede Tor sempre sai por outro país.",
        ),
        MENSAGEIROS(
            "mensageiros", "Mensageiros e chamadas",
            "WhatsApp, Telegram, Signal, Messenger, Meet, Zoom, Teams",
            "As chamadas de voz e vídeo usam um tipo de conexão (UDP) que a rede Tor não transporta. As mensagens continuam com a criptografia de ponta a ponta do próprio app.",
        ),
        REDES(
            "redes", "Redes sociais",
            "Instagram, Facebook, TikTok, Kwai, X, Threads, Snapchat",
            "Recusam ou travam conexões vindas da rede Tor. Você entra nelas com a sua conta, então o Tor não esconderia quem você é.",
        ),
        GOOGLE(
            "google", "Google, YouTube e notificações",
            "Serviços do Google (entregam as notificações de todos os apps), Play Store, YouTube, Gmail, Maps",
            "O Google pede verificação o tempo todo pela rede Tor, e as notificações de todos os aplicativos chegam pelos serviços dele.",
        ),
        STREAMING(
            "streaming", "Filmes, séries e música",
            "Netflix, Globoplay, Disney+, Prime Video, Max, Spotify, Deezer",
            "Liberam o conteúdo por país. Pela rede Tor o serviço vê outro país e bloqueia.",
        ),
        COMPRAS(
            "compras", "Compras, entregas e transporte",
            "Uber, 99, iFood, Mercado Livre, Shopee, Amazon, AliExpress",
            "Precisam saber a sua região de verdade e usam antifraude que recusa a rede Tor.",
        ),
        IA(
            "ia", "Assistentes de IA",
            "Claude, ChatGPT, Gemini, Copilot, Perplexity",
            "Bloqueiam conexões vindas da rede Tor.",
        );

        companion object {
            fun porId(id: String?): Categoria? = values().firstOrNull { it.id == id }
        }
    }

    enum class Rota { TOR, FORA_AUTO, FORA_MANUAL, TOR_MANUAL }

    // ------------------------------------------------------------------
    // Catálogo de aplicativos
    // ------------------------------------------------------------------

    private val exatos: Map<String, Categoria> = buildMap {
        fun cat(c: Categoria, vararg p: String) = p.forEach { put(it, c) }
        cat(
            Categoria.BANCOS,
            "com.nu.production", "com.itau", "com.bradesco", "br.com.bb.android", "br.gov.caixa.tem",
            "br.com.gabba.Caixa", "com.santander.app", "br.com.intermedium", "com.c6bank.app", "com.picpay",
            "com.mercadopago.wallet", "br.com.uol.ps.myaccount", "com.paypal.android.p2pmobile",
            "com.google.android.apps.walletnfcrel", "com.samsung.android.spay", "com.transferwise.android",
            "com.revolut.revolut", "com.squareup.cash", "com.venmo", "com.chase.sig.android", "com.infonow.bofa",
            "com.wf.wellsfargomobile", "com.citi.citimobile", "com.konylabs.capitalone", "com.zellepay.zelle",
        )
        cat(
            Categoria.GOVERNO,
            "br.gov.meugovbr", "br.gov.dataprev.meuinss", "br.gov.caixa.fgts.trabalhador",
            "br.gov.dataprev.carteiradigital", "br.gov.serpro.cnhe", "br.jus.tse.eleitoral.etitulo",
        )
        cat(
            Categoria.MENSAGEIROS,
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web",
            "org.thunderdog.challegram", "org.thoughtcrime.securesms", "com.facebook.orca",
            "com.google.android.apps.tachyon", "com.skype.raider", "com.microsoft.teams", "us.zoom.videomeetings",
            "com.discord", "jp.naver.line.android", "com.viber.voip", "com.imo.android.imoim", "com.tencent.mm",
            "com.kakao.talk",
        )
        cat(
            Categoria.REDES,
            "com.instagram.android", "com.instagram.lite", "com.instagram.barcelona", "com.facebook.katana",
            "com.facebook.lite", "com.zhiliaoapp.musically", "com.zhiliaoapp.musically.go", "com.ss.android.ugc.trill",
            "com.kwai.video", "com.twitter.android", "com.snapchat.android", "com.pinterest", "com.linkedin.android",
            "com.reddit.frontpage", "com.lemon.lvoverseas", "com.larus.wolf",
        )
        cat(
            Categoria.GOOGLE,
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.google.android.youtube",
            "com.google.android.apps.youtube.music", "com.google.android.apps.maps", "com.google.android.gm",
            "com.google.android.googlequicksearchbox", "com.google.android.apps.photos", "com.google.android.apps.docs",
        )
        cat(
            Categoria.STREAMING,
            "com.netflix.mediaclient", "com.disney.disneyplus", "com.globo.globotv", "com.amazon.avod.thirdpartyclient",
            "com.wbd.stream", "com.hbo.hbonow", "com.spotify.music", "deezer.android.app", "com.apple.android.music",
            "tv.pluto.android", "com.crunchyroll.crunchyroid", "tv.twitch.android.app",
        )
        cat(
            Categoria.COMPRAS,
            "com.ubercab", "com.ubercab.eats", "com.ubercab.driver", "com.taxis99", "br.com.brainweb.ifood",
            "com.mercadolibre", "com.shopee.br", "com.amazon.mShop.android.shopping", "com.alibaba.aliexpresshd",
            "com.einnovation.temu", "com.zzkko", "com.luizalabs.mlapp", "com.grability.rappi", "sinet.startup.inDriver",
            "com.airbnb.android", "com.booking",
        )
        cat(
            Categoria.IA,
            "com.anthropic.claude", "com.openai.chatgpt", "com.google.android.apps.bard", "com.microsoft.copilot",
            "ai.perplexity.app.android", "com.deepseek.chat",
        )
    }

    /** Começo do nome do pacote → categoria. Bancos vêm antes do governo (Caixa). */
    private val prefixos: List<Pair<String, Categoria>> = buildList {
        listOf(
            "com.itau", "com.bradesco", "br.com.bradesco", "br.com.bb.", "br.gov.caixa", "br.com.gabba.caixa",
            "com.santander", "br.com.santander", "br.com.intermedium", "br.com.bancointer", "com.nu.", "com.c6bank",
            "br.com.c6bank", "br.com.sicredi", "com.sicredi", "br.com.sicoob", "br.com.banrisul", "com.btg",
            "br.com.btg", "br.com.xp", "br.com.neon", "br.com.bancopan", "com.mercadopago", "br.com.uol.ps",
            "com.picpay", "com.paypal", "br.com.original", "br.com.safra", "br.com.agibank", "br.com.bancobv",
            "br.com.brb", "br.com.banestes", "br.com.bnb", "br.com.banpara", "br.com.bmg", "br.com.digio",
            "br.com.mercantil", "br.com.daycoval", "br.com.rico", "br.com.easynvest", "br.com.toro",
            "br.com.pagseguro", "br.com.stone", "br.com.willbank", "com.ame.",
        ).forEach { add(it.lowercase(Locale.ROOT) to Categoria.BANCOS) }
        listOf("br.gov.", "br.jus.", "br.mp.", "br.leg.", "br.def.").forEach { add(it to Categoria.GOVERNO) }
        add("com.google.android." to Categoria.GOOGLE)
    }

    private val palavrasDeBancoNoPacote = listOf(
        "bank", "banco", "banking", "sicredi", "sicoob", "banrisul", "picpay", "pagbank", "pagseguro",
        "mercadopago", "paypal", "serasa",
    )
    private val palavrasDeGovernoNoNome = listOf(
        "gov.br", "receita federal", "meu inss", "fgts", "detran", "e-título", "e-titulo", "carteira de trabalho",
        "cnh do brasil", "carteira digital de trânsito", "conecte sus", "meu sus",
    )
    private val regexPix = Regex("(^|[^a-z])pix([^a-z]|$)")

    /** Categoria de um app pelo pacote (e, se preciso, pelo nome que aparece na tela). */
    fun categoriaDoApp(pacote: String, rotulo: String?): Categoria? {
        exatos[pacote]?.let { return it }
        val p = pacote.lowercase(Locale.ROOT)
        for ((prefixo, cat) in prefixos) if (p.startsWith(prefixo)) return cat
        val segmentos = p.split('.')
        if ("gov" in segmentos) return Categoria.GOVERNO
        if (segmentos.any { s -> palavrasDeBancoNoPacote.any { it in s } }) return Categoria.BANCOS
        val r = rotulo?.lowercase(Locale.ROOT)?.trim() ?: return null
        if (palavrasDeGovernoNoNome.any { it in r }) return Categoria.GOVERNO
        if ("bank" in r || "banco" in r || "internet banking" in r || "serasa" in r || regexPix.containsMatchIn(r)) {
            return Categoria.BANCOS
        }
        return null
    }

    /** Pacote cuja categoria já sai sem precisar do nome do app (barato). */
    private fun categoriaSemNome(pacote: String): Categoria? = categoriaDoApp(pacote, null)

    // ------------------------------------------------------------------
    // Domínios de cada categoria (para o navegador e os apps no túnel)
    // ------------------------------------------------------------------

    private val dominios: Map<Categoria, Set<String>> = mapOf(
        Categoria.BANCOS to setOf(
            "nubank.com.br", "itau.com.br", "itau.com", "itau", "bradesco.com.br", "bradesco", "bb.com.br",
            "bancobrasil.com.br", "caixa.gov.br", "santander.com.br", "bancointer.com.br", "inter.co",
            "c6bank.com.br", "picpay.com", "mercadopago.com.br", "mercadopago.com", "pagseguro.uol.com.br",
            "pagbank.com.br", "sicredi.com.br", "sicoob.com.br", "banrisul.com.br", "btgpactual.com", "xpi.com.br",
            "neon.com.br", "bancopan.com.br", "safra.com.br", "original.com.br", "agibank.com.br", "bv.com.br",
            "serasa.com.br", "paypal.com", "paypalobjects.com", "wise.com", "revolut.com",
        ),
        Categoria.GOVERNO to setOf("gov.br", "jus.br", "leg.br", "mp.br"),
        Categoria.MENSAGEIROS to setOf(
            "whatsapp.net", "whatsapp.com", "wa.me", "telegram.org", "t.me", "telegram.me", "signal.org",
            "discord.com", "discordapp.com", "discordapp.net", "discord.gg", "zoom.us", "zoom.com", "skype.com",
            "messenger.com",
        ),
        Categoria.REDES to setOf(
            "instagram.com", "cdninstagram.com", "facebook.com", "facebook.net", "fbcdn.net", "fbsbx.com", "fb.com",
            "fb.me", "threads.net", "threads.com", "tiktok.com", "tiktokv.com", "tiktokcdn.com", "tiktokcdn-us.com",
            "ttwstatic.com", "byteoversea.com", "ibytedtos.com", "ibyteimg.com", "musical.ly", "byteintl.com",
            "ciciai.com", "dola.com", "capcut.com", "kwai.com", "x.com", "twitter.com", "twimg.com", "t.co",
            "snapchat.com", "sc-cdn.net", "pinterest.com", "pinimg.com", "linkedin.com", "licdn.com", "reddit.com",
            "redd.it", "redditmedia.com", "redditstatic.com",
        ),
        Categoria.GOOGLE to setOf(
            "google.com", "google.com.br", "googleapis.com", "gstatic.com", "googleusercontent.com",
            "googlevideo.com", "youtube.com", "youtu.be", "ytimg.com", "youtube-nocookie.com", "ggpht.com",
            "gvt1.com", "gvt2.com", "gvt3.com", "1e100.net", "withgoogle.com",
        ),
        Categoria.STREAMING to setOf(
            "netflix.com", "nflxvideo.net", "nflximg.net", "nflxext.com", "nflxso.net", "globo.com", "glbimg.com",
            "disneyplus.com", "disney-plus.net", "bamgrid.com", "dssott.com", "primevideo.com", "aiv-cdn.net",
            "aiv-delivery.net", "amazonvideo.com", "max.com", "hbomax.com", "spotify.com", "scdn.co",
            "spotifycdn.com", "deezer.com", "dzcdn.net", "crunchyroll.com", "twitch.tv", "ttvnw.net", "jtvnw.net",
            "paramountplus.com", "pluto.tv",
        ),
        Categoria.COMPRAS to setOf(
            "uber.com", "ubereats.com", "99app.com", "ifood.com.br", "mercadolivre.com.br", "mercadolivre.com",
            "mercadolibre.com", "mlstatic.com", "shopee.com.br", "shopee.com", "susercontent.com", "amazon.com.br",
            "amazon.com", "media-amazon.com", "ssl-images-amazon.com", "aliexpress.com", "aliexpress.us",
            "alicdn.com", "temu.com", "shein.com", "magazineluiza.com.br", "magalu.com", "americanas.com.br",
            "casasbahia.com.br", "airbnb.com", "airbnb.com.br", "booking.com", "rappi.com.br", "indrive.com",
        ),
        Categoria.IA to setOf(
            "anthropic.com", "claude.ai", "claudeusercontent.com", "openai.com", "chatgpt.com", "oaistatic.com",
            "oaiusercontent.com", "perplexity.ai", "gemini.google.com", "copilot.microsoft.com", "deepseek.com",
            "meta.ai",
        ),
    )

    private val dominioParaCategoria: Map<String, Categoria> = buildMap {
        for ((cat, lista) in dominios) for (d in lista) put(d, cat)
    }

    fun quantosDominios(cat: Categoria): Int = dominios[cat]?.size ?: 0

    /** Procura o host e cada domínio-pai dele num conjunto. Devolve o que casou. */
    fun casar(host: String, conjunto: Collection<String>): String? {
        if (conjunto.isEmpty()) return null
        var d = host.lowercase(Locale.ROOT).trimEnd('.')
        while (true) {
            if (d in conjunto) return d
            val p = d.indexOf('.')
            if (p < 0) return null
            d = d.substring(p + 1)
            if (d.isEmpty()) return null
        }
    }

    fun categoriaDoDominio(host: String): Categoria? {
        var d = host.lowercase(Locale.ROOT).trimEnd('.')
        while (true) {
            dominioParaCategoria[d]?.let { return it }
            val p = d.indexOf('.')
            if (p < 0) return null
            d = d.substring(p + 1)
            if (d.isEmpty()) return null
        }
    }

    private val segundoNivel = setOf(
        "com", "net", "org", "gov", "edu", "co", "ac", "jus", "mil", "art", "adv", "blog", "eco", "eng", "ind",
        "inf", "med", "nom", "ong", "psc", "rec", "srv", "tmp", "tur", "tv", "vet", "ne", "or", "go", "gob",
        "gouv", "ltd", "plc", "sch", "nic", "mus", "leg", "mp", "def",
    )

    /** "api.meubanco.com.br" → "meubanco.com.br"; "cdn.x.com" → "x.com". */
    fun dominioBase(host: String): String {
        val h = host.lowercase(Locale.ROOT).trimEnd('.')
        if (h.isEmpty() || h.none { it.isLetter() }) return h
        val p = h.split('.').filter { it.isNotEmpty() }
        if (p.size <= 2) return p.joinToString(".")
        val ultimo = p[p.size - 1]
        val penultimo = p[p.size - 2]
        val n = if (ultimo.length == 2 && penultimo in segundoNivel) 3 else 2
        return p.takeLast(n).joinToString(".")
    }

    private val regexDominio = Regex("^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)+$")

    /** Limpa o que a pessoa digitou ("https://www.Banco.com.br/x" → "banco.com.br"). null = inválido. */
    fun normalizarDominio(texto: String): String? {
        var t = texto.trim().lowercase(Locale.ROOT)
        t = t.substringAfter("://")
        t = t.substringBefore('/').substringBefore('?').substringBefore('#')
        t = t.substringBefore(':')
        t = t.removePrefix("*.").trimStart('.').trimEnd('.')
        if (t.startsWith("www.")) t = t.removePrefix("www.")
        return if (t.length in 3..253 && regexDominio.matches(t)) t else null
    }

    // ------------------------------------------------------------------
    // Regras do filtro (instantâneo imutável, trocado ao vivo)
    // ------------------------------------------------------------------

    class Regras(
        val inteligente: Boolean,
        val desvioAutomatico: Boolean,
        private val semprePeloTor: Set<String>,
        private val sempreDireto: Set<String>,
        private val categoriasLigadas: Set<Categoria>,
    ) {
        enum class Tipo { SEMPRE_TOR, DIRETO_USUARIO, DIRETO_CATEGORIA, PADRAO }

        class Decisao(val tipo: Tipo, val categoria: Categoria? = null)

        fun decidir(host: String): Decisao {
            if (casar(host, semprePeloTor) != null) return SEMPRE_TOR
            if (casar(host, sempreDireto) != null) return DIRETO_USUARIO
            if (inteligente) {
                val c = categoriaDoDominio(host)
                if (c != null && c in categoriasLigadas) return Decisao(Tipo.DIRETO_CATEGORIA, c)
            }
            return PADRAO
        }

        companion object {
            private val SEMPRE_TOR = Decisao(Tipo.SEMPRE_TOR)
            private val DIRETO_USUARIO = Decisao(Tipo.DIRETO_USUARIO)
            private val PADRAO = Decisao(Tipo.PADRAO)
            val VAZIA = Regras(false, false, emptySet(), emptySet(), emptySet())
        }
    }

    fun regras(prefs: Preferencias): Regras {
        val desligadas = prefs.categoriasDesligadas
        return Regras(
            inteligente = prefs.rotaInteligente,
            desvioAutomatico = prefs.desvioAutomatico,
            semprePeloTor = prefs.dominiosSempreTor,
            sempreDireto = prefs.dominiosDiretos,
            categoriasLigadas = Categoria.values().filter { it.id !in desligadas }.toSet(),
        )
    }

    // ------------------------------------------------------------------
    // Aplicativos instalados
    // ------------------------------------------------------------------

    class AppClassificado(
        val pacote: String,
        val nome: String?,
        val sistema: Boolean,
        val categoria: Categoria?,
    )

    private class Lembranca(val versao: Long, val nome: String?, val categoria: Categoria?, val comNome: Boolean)

    private val memoria = ConcurrentHashMap<String, Lembranca>()

    // ------------------------------------------------------------------
    // Classificação guardada em disco
    //
    // Ler o nome de cada app é caro (o Android abre o APK de cada um). Sem
    // isso guardado, toda vez que o processo da VPN nascia (atualização,
    // religamento pelo Android, ligar o celular) a classificação inteira era
    // refeita ANTES de a interface da VPN subir — segundos com o tráfego
    // saindo sem proteção e a VPN demorando a conectar. Agora fica num
    // arquivo pequeno; só app novo ou atualizado é lido de novo. O arquivo
    // é refeito a cada versão do Orbion (o catálogo pode ter mudado).
    // ------------------------------------------------------------------

    private const val ARQUIVO_CLASSIFICACAO = "classificacao.cache"
    @Volatile private var discoLido = false
    private val travaDoDisco = Any()

    private fun cabecalho(context: Context): String {
        val v = try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        } catch (_: Exception) {
            0L
        }
        return "orbion-classificacao 1 $v"
    }

    private fun lerDoDisco(context: Context) {
        if (discoLido) return
        synchronized(travaDoDisco) {
            if (discoLido) return
            discoLido = true
            try {
                val f = File(context.filesDir, ARQUIVO_CLASSIFICACAO)
                if (!f.exists()) return
                val linhas = f.readLines()
                if (linhas.firstOrNull() != cabecalho(context)) return
                val porId = Categoria.values().associateBy { it.id }
                for (l in linhas.drop(1)) {
                    val p = l.split('\t')
                    if (p.size < 3) continue
                    val versao = p[1].toLongOrNull() ?: continue
                    memoria.putIfAbsent(p[0], Lembranca(versao, null, porId[p[2]], comNome = false))
                }
            } catch (e: Exception) {
                Log.w("OrbionRota", "Classificação guardada ilegível: ${e.message}")
            }
        }
    }

    private fun gravarNoDisco(context: Context, instalados: Set<String>?) {
        synchronized(travaDoDisco) {
            try {
                if (instalados != null) memoria.keys.retainAll(instalados)
                val sb = StringBuilder(memoria.size * 48).append(cabecalho(context)).append('\n')
                for ((pacote, l) in memoria) {
                    sb.append(pacote).append('\t').append(l.versao).append('\t').append(l.categoria?.id ?: "-").append('\n')
                }
                val f = File(context.filesDir, ARQUIVO_CLASSIFICACAO)
                val tmp = File(context.filesDir, "$ARQUIVO_CLASSIFICACAO.tmp")
                tmp.writeText(sb.toString())
                if (!tmp.renameTo(f)) {
                    f.writeText(sb.toString())
                    tmp.delete()
                }
            } catch (e: Exception) {
                Log.w("OrbionRota", "Não foi possível guardar a classificação: ${e.message}")
            }
        }
    }

    private fun pacotesInstalados(pm: PackageManager, flags: Int): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }

    private fun ehSistema(info: ApplicationInfo?): Boolean =
        info != null && (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
            (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

    /**
     * Classifica os apps instalados. O nome do app (caro de ler) só é lido
     * quando precisa: se [comNomes], ou quando o pacote sozinho não diz nada
     * e o app não é do sistema. Tudo fica guardado até o app ser atualizado.
     */
    fun classificados(context: Context, comNomes: Boolean, pacotes: List<PackageInfo>? = null): List<AppClassificado> {
        lerDoDisco(context)
        val pm = context.packageManager
        val lista = pacotes ?: pacotesInstalados(pm, 0)
        val saida = ArrayList<AppClassificado>(lista.size)
        var novos = 0
        for (pi in lista) {
            val pacote = pi.packageName ?: continue
            if (pacote == context.packageName) continue
            val info = pi.applicationInfo
            val sistema = ehSistema(info)
            val versao = pi.lastUpdateTime
            val lembrado = memoria[pacote]
            if (lembrado != null && lembrado.versao == versao && (lembrado.comNome || !comNomes)) {
                saida += AppClassificado(pacote, lembrado.nome, sistema, lembrado.categoria)
                continue
            }
            var categoria = categoriaSemNome(pacote)
            val precisaNome = comNomes || (categoria == null && !sistema)
            val nome = if (precisaNome && info != null) {
                try {
                    pm.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            if (categoria == null && !sistema && nome != null) categoria = categoriaDoApp(pacote, nome)
            if (lembrado == null || lembrado.versao != versao || lembrado.categoria != categoria) novos++
            memoria[pacote] = Lembranca(versao, nome, categoria, precisaNome)
            saida += AppClassificado(pacote, nome, sistema, categoria)
        }
        // Com a lista completa, também tira do arquivo os apps desinstalados.
        val instalados = if (pacotes == null) lista.mapNotNullTo(HashSet()) { it.packageName } else null
        val desinstalados = instalados != null && memoria.keys.any { it !in instalados }
        if (novos > 0 || desinstalados) gravarNoDisco(context, instalados)
        return saida
    }

    fun rota(pacote: String, categoria: Categoria?, prefs: Preferencias): Rota = when {
        pacote in prefs.appsSempreNoTor -> Rota.TOR_MANUAL
        pacote in prefs.appsForaDoTunel -> Rota.FORA_MANUAL
        prefs.rotaInteligente && categoria != null && categoria.id !in prefs.categoriasDesligadas -> Rota.FORA_AUTO
        else -> Rota.TOR
    }

    /** Apps que ficam fora do túnel agora: pacote → motivo (para mostrar). */
    fun appsForaDoTor(context: Context, prefs: Preferencias): Map<String, String> {
        val saida = LinkedHashMap<String, String>()
        val sempreTor = prefs.appsSempreNoTor
        for (p in prefs.appsForaDoTunel) {
            if (p != context.packageName && p !in sempreTor) saida[p] = "você escolheu"
        }
        if (prefs.rotaInteligente) {
            val desligadas = prefs.categoriasDesligadas
            for (app in classificados(context, comNomes = false)) {
                val c = app.categoria ?: continue
                if (c.id in desligadas || app.pacote in sempreTor || app.pacote in saida) continue
                saida[app.pacote] = c.titulo
            }
        }
        return saida
    }

    /** Um app recém-instalado vai por fora? (para aplicar na hora) */
    fun appNovoFicaFora(context: Context, prefs: Preferencias, pacote: String): String? {
        if (pacote in prefs.appsSempreNoTor) return null
        if (pacote in prefs.appsForaDoTunel) return "você escolheu"
        if (!prefs.rotaInteligente) return null
        val pm = context.packageManager
        val nome = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pacote, 0)).toString()
        } catch (_: Exception) {
            null
        }
        val c = categoriaDoApp(pacote, nome) ?: return null
        return if (c.id in prefs.categoriasDesligadas) null else "${nome ?: pacote} (${c.titulo})"
    }

    /** Lista para a aba Apps: todos os apps com internet, cada um com a sua rota. */
    fun listarApps(context: Context, prefs: Preferencias, incluirSistema: Boolean): List<Map<String, Any?>> {
        val pm = context.packageManager
        val pacotes = pacotesInstalados(pm, PackageManager.GET_PERMISSIONS)
        val comInternet = pacotes.filter { pi ->
            pi.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
        }
        val lancaveis = try {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
        return classificados(context, comNomes = true, pacotes = comInternet)
            .filter { incluirSistema || !it.sistema || it.categoria != null || it.pacote in lancaveis }
            .map { app ->
                val r = rota(app.pacote, app.categoria, prefs)
                mapOf(
                    "pacote" to app.pacote,
                    "nome" to (app.nome ?: app.pacote),
                    "sistema" to app.sistema,
                    "rota" to r.name,
                    "categoria" to app.categoria?.titulo,
                    "categoriaId" to app.categoria?.id,
                )
            }
            .sortedBy { (it["nome"] as String).lowercase(Locale.ROOT) }
    }

    /** Resumo das categorias para a aba Config. */
    fun resumoDasCategorias(context: Context, prefs: Preferencias): List<Map<String, Any?>> {
        val apps = classificados(context, comNomes = true)
        val desligadas = prefs.categoriasDesligadas
        return Categoria.values().map { c ->
            val nomes = apps.filter { it.categoria == c }.map { it.nome ?: it.pacote }.sortedBy { it.lowercase(Locale.ROOT) }
            mapOf(
                "id" to c.id,
                "titulo" to c.titulo,
                "resumo" to c.resumo,
                "porque" to c.porque,
                "ligada" to (c.id !in desligadas),
                "apps" to nomes,
                "dominios" to quantosDominios(c),
            )
        }
    }
}
