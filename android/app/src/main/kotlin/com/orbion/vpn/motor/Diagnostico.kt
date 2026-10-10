package com.orbion.vpn.motor

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verificação do aparelho: tudo o que pode fazer o Android encerrar o
 * Orbion ou atrapalhar a navegação, com o botão que resolve cada item.
 *
 * Itens que o app consegue conferir sozinho (bateria, notificações, DNS
 * privado, VPN sempre ativa) somem por conta própria quando resolvidos.
 * Os que o Android não deixa conferir (ajustes da marca do celular, por
 * exemplo) ganham o botão "Já fiz", e somem quando a pessoa confirma.
 */
object Diagnostico {

    enum class Gravidade { OK, DICA, ATENCAO, GRAVE }

    enum class Acao {
        NENHUMA, ISENTAR_BATERIA, ABRIR_DNS_PRIVADO, PEDIR_NOTIFICACAO, ABRIR_VPN,
        ABRIR_APP_INFO, ABRIR_DESENVOLVEDOR, ABRIR_APPS, ABRIR_AUTOSTART, ABRIR_BATERIA_MARCA
    }

    data class Item(
        val id: String,
        val titulo: String,
        val situacao: String,
        val gravidade: Gravidade,
        val acao: Acao = Acao.NENHUMA,
        val botao: String = "",
        /** Texto do botão "Já fiz" (vazio = o app confere sozinho). */
        val dispensar: String = "",
        /** Segundo botão, quando o ajuste fica em duas telas diferentes. */
        val acao2: Acao = Acao.NENHUMA,
        val botao2: String = "",
    ) {
        fun comoMapa(): Map<String, Any> = mapOf(
            "id" to id, "titulo" to titulo, "situacao" to situacao,
            "gravidade" to gravidade.name, "acao" to acao.name, "botao" to botao,
            "dispensar" to dispensar, "acao2" to acao2.name, "botao2" to botao2,
        )
    }

    /** Prefixo das confirmações guardadas em Preferencias.avisosDispensados. */
    const val PREFIXO = "diag:"

    fun verificar(context: Context): List<Item> {
        val confirmados = Preferencias(context).avisosDispensados
        val itens = listOfNotNull(
            binarios(context),
            bateria(context),
            fabricante(context),
            sempreAtiva(context),
            processosFilhos(),
            notificacoes(context),
            dnsPrivado(context),
            resolucaoDeNomes(),
            appsIncompativeis(context),
        )
        // O que a pessoa já confirmou vira "em ordem" e sai da lista de pendências.
        return itens.map { i ->
            if (i.gravidade != Gravidade.OK && i.dispensar.isNotEmpty() && (PREFIXO + i.id) in confirmados) {
                Item(i.id, i.titulo, "Feito — confirmado por você.", Gravidade.OK)
            } else {
                i
            }
        }
    }

    // ------------------------------------------------------------------
    // Marca do celular
    // ------------------------------------------------------------------

    private val marca: String
        get() = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(Locale.ROOT)

    fun ehXiaomi(): Boolean = marca.let { "xiaomi" in it || "redmi" in it || "poco" in it }

    /** Marcas com economia de bateria própria, que o Android não deixa conferir. */
    private fun marcaComGerenciadorProprio(): Boolean = ehXiaomi() || marca.let {
        "huawei" in it || "honor" in it || "oppo" in it || "realme" in it ||
            "oneplus" in it || "vivo" in it
    }

    /**
     * "Início automático" da Xiaomi está ligado para o Orbion?
     * true/false quando a Xiaomi informa; null quando não dá para saber.
     *
     * A Xiaomi guarda essa chave como uma permissão própria (código 10008).
     */
    fun inicioAutomaticoXiaomi(context: Context): Boolean? {
        if (!ehXiaomi()) return null
        return try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val metodo = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
            )
            when (metodo.invoke(ops, 10008, android.os.Process.myUid(), context.packageName) as? Int) {
                AppOpsManager.MODE_ALLOWED -> true
                AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> false
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------

    private fun binarios(context: Context): Item {
        val tor = Transportes.torDisponivel(context)
        val obfs4 = Transportes.obfs4Disponivel(context)
        val snow = Transportes.snowflakeDisponivel(context)
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        return if (!tor) {
            Item(
                "binarios", "Motor do Tor",
                "O programa do Tor não está neste aparelho (arquitetura $abi). Este APK foi feito para outro tipo de processador.",
                Gravidade.GRAVE,
            )
        } else {
            val partes = mutableListOf("Tor ✓")
            partes += if (snow) "Snowflake ✓" else "Snowflake ✗"
            partes += if (obfs4) "obfs4 ✓" else "obfs4 ✗"
            Item("binarios", "Motor do Tor", "${partes.joinToString("  •  ")}  ($abi)", Gravidade.OK)
        }
    }

    fun bateriaIsenta(context: Context): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) {
        true
    }

    /** Liberado pelo Android OU confirmado pela pessoa na tela da marca. */
    fun bateriaResolvida(context: Context): Boolean =
        bateriaIsenta(context) || (PREFIXO + "bateria") in Preferencias(context).avisosDispensados

    private fun bateria(context: Context): Item {
        if (bateriaIsenta(context)) {
            return Item("bateria", "Economia de bateria", "O Orbion está liberado para rodar em segundo plano.", Gravidade.OK)
        }
        val texto = if (ehXiaomi()) {
            "O celular pode encerrar o Orbion para poupar bateria, e a VPN cai junto.\n\n" +
                "Toque em \"Liberar o Orbion\". Se aparecer a pergunta do Android, toque em Permitir. " +
                "Se abrir a tela de Economia de bateria da Xiaomi, escolha \"Sem restrições\" e volte para cá."
        } else {
            "O Android está autorizado a encerrar o Orbion para poupar bateria. Quando isso acontece a VPN cai e o celular pode ficar sem internet."
        }
        return Item(
            "bateria", "Economia de bateria", texto,
            Gravidade.GRAVE, Acao.ISENTAR_BATERIA, "Liberar o Orbion",
            // Nas marcas com gerenciador próprio, o "Sem restrições" delas nem
            // sempre aparece para o app. Então a pessoa confirma.
            dispensar = if (marcaComGerenciadorProprio()) "Já deixei \"Sem restrições\"" else "",
        )
    }

    /** Fabricantes com "matador de apps" próprio, além do Android. */
    private fun fabricante(context: Context): Item {
        val m = marca
        if (ehXiaomi()) {
            val auto = inicioAutomaticoXiaomi(context)
            val linhaAuto = when (auto) {
                true -> "✓ Início automático: ligado"
                false -> "✗ Início automático: DESLIGADO — toque em \"Abrir início automático\" e ligue o Orbion VPN"
                null -> "• Início automático: ligado para o Orbion VPN"
            }
            val texto = "Ajustes da Xiaomi para ela não fechar o Orbion:\n\n" +
                "$linhaAuto\n" +
                "• Economia de bateria do app: \"Sem restrições\"\n" +
                "• Na tela de apps recentes: segure o Orbion e toque no cadeado"
            return Item(
                "fabricante", "Ajustes da Xiaomi", texto,
                if (auto == false) Gravidade.ATENCAO else Gravidade.DICA,
                if (auto == true) Acao.ABRIR_BATERIA_MARCA else Acao.ABRIR_AUTOSTART,
                if (auto == true) "Abrir economia de bateria" else "Abrir início automático",
                dispensar = "Já fiz esses ajustes",
                acao2 = if (auto == true) Acao.NENHUMA else Acao.ABRIR_BATERIA_MARCA,
                botao2 = if (auto == true) "" else "Economia de bateria",
            )
        }
        val passos = when {
            "samsung" in m ->
                "Samsung: abra Configurações → Bateria → Limites de uso em segundo plano → \"Apps que nunca são suspensos\" e adicione o Orbion. " +
                    "Depois, nas informações do app → Bateria, escolha \"Sem restrições\"."
            "motorola" in m ->
                "Motorola: nas informações do app → Bateria, escolha \"Sem restrições\"."
            "huawei" in m || "honor" in m ->
                "Huawei/Honor: Configurações → Bateria → Inicialização de apps → Orbion → gerenciar manualmente e ligar as três chaves."
            "oppo" in m || "realme" in m || "oneplus" in m ->
                "OPPO/realme/OnePlus: nas informações do app → Uso da bateria, ligue \"Permitir atividade em segundo plano\" e \"Permitir início automático\"."
            "vivo" in m ->
                "Vivo: Configurações → Bateria → Consumo de energia em segundo plano → permitir para o Orbion."
            else ->
                "Nas informações do app → Bateria, escolha \"Sem restrições\" (o nome muda conforme a marca)."
        }
        return Item(
            "fabricante", "Ajuste da marca do celular (${Build.MANUFACTURER})", passos,
            Gravidade.DICA, Acao.ABRIR_APP_INFO, "Abrir informações do app",
            dispensar = "Já fiz",
        )
    }

    /**
     * O Orbion está marcado como "VPN sempre ativa"? null = não deu para saber.
     *
     * Pergunta direto ao Android (funciona mesmo com a VPN desligada: a
     * resposta depende só de quem pergunta, que é o próprio Orbion). Se não
     * der, usa o último valor que o serviço viu.
     */
    fun sempreAtivaLigada(context: Context): Boolean? {
        val prefs = Preferencias(context)
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val vpn: VpnService = OrbionVpnService.ativo ?: VpnService()
                val ligada = vpn.isAlwaysOn
                prefs.sempreAtivaConhecida = if (ligada) 1 else 0
                Painel.vpnSempreAtiva = ligada
                return ligada
            } catch (_: Throwable) {
                // Segue para as outras fontes.
            }
        }
        try {
            // Android 8 e 9 deixam ler direto; nos mais novos isto é recusado.
            val app = Settings.Secure.getString(context.contentResolver, "always_on_vpn_app")
            return app == context.packageName
        } catch (_: Exception) {
        }
        return when (prefs.sempreAtivaConhecida) {
            1 -> true
            0 -> false
            else -> null
        }
    }

    private fun sempreAtiva(context: Context): Item {
        val ligada = sempreAtivaLigada(context)
        return when (ligada) {
            true -> Item(
                "sempreativa", "VPN sempre ativa",
                "Ligada. Se o Orbion for fechado, o próprio Android religa.",
                Gravidade.OK,
            )
            false -> Item(
                "sempreativa", "VPN sempre ativa",
                "É a proteção mais forte contra o Android encerrar o Orbion: marcada como \"VPN sempre ativa\", é o próprio sistema que religa o Orbion se ele cair.\n\n" +
                    "Toque no botão, abra a engrenagem ao lado de Orbion VPN e ligue \"VPN sempre ativa\".",
                Gravidade.ATENCAO, Acao.ABRIR_VPN, "Abrir configurações de VPN",
                dispensar = "Não quero usar",
            )
            null -> Item(
                "sempreativa", "VPN sempre ativa",
                "Marque o Orbion como \"VPN sempre ativa\" nas configurações de VPN do Android: assim o próprio sistema religa o Orbion se ele for fechado.\n\n" +
                    "Toque no botão, abra a engrenagem ao lado de Orbion VPN e ligue \"VPN sempre ativa\".",
                Gravidade.DICA, Acao.ABRIR_VPN, "Abrir configurações de VPN",
                dispensar = "Já fiz",
            )
        }
    }

    private fun processosFilhos(): Item? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return if (Build.VERSION.SDK_INT >= 34) {
            Item(
                "processosfilhos", "Restrição de processos filhos (opcional)",
                "O Tor roda como um programa auxiliar do Orbion. No Android 12 ou mais novo, o sistema pode encerrar esse programa se achar que ele está gastando demais em segundo plano.\n\n" +
                    "Com os ajustes acima isso quase não acontece. Se mesmo assim o Tor cair sozinho, ative as Opções do desenvolvedor e ligue \"Desativar restrições de processos filhos\".",
                Gravidade.DICA, Acao.ABRIR_DESENVOLVEDOR, "Abrir opções do desenvolvedor",
                dispensar = "Entendi",
            )
        } else {
            Item(
                "processosfilhos", "Restrição de processos filhos",
                "O Tor roda como um programa auxiliar do Orbion. No Android 12 e 13, o sistema pode encerrar esse programa se o Orbion não estiver liberado na bateria. Mantenha os ajustes de bateria acima em dia.",
                Gravidade.DICA,
                dispensar = "Entendi",
            )
        }
    }

    private fun notificacoes(context: Context): Item {
        val habilitadas = try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
        } catch (_: Exception) {
            true
        }
        val permissao = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return if (habilitadas && permissao) {
            Item("notificacao", "Notificações", "Permitidas. Você vê o estado da VPN na barra.", Gravidade.OK)
        } else {
            Item(
                "notificacao", "Notificações",
                "Bloqueadas. Sem elas você não vê se a VPN está no ar nem consegue desligá-la pela barra.",
                Gravidade.ATENCAO, Acao.PEDIR_NOTIFICACAO, "Permitir",
            )
        }
    }

    sealed class StatusDns {
        object Desligado : StatusDns()
        object Automatico : StatusDns()
        data class Fixo(val servidor: String) : StatusDns()
    }

    @Suppress("DEPRECATION")
    fun statusDnsPrivado(context: Context): StatusDns {
        if (Build.VERSION.SDK_INT < 28) return StatusDns.Desligado
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // A VPN é a rede ativa quando conectada; olha também as demais.
            for (rede in cm.allNetworks) {
                val lp = cm.getLinkProperties(rede) ?: continue
                val nome = lp.privateDnsServerName
                if (!nome.isNullOrBlank()) return StatusDns.Fixo(nome)
            }
            val ativa = cm.activeNetwork?.let { cm.getLinkProperties(it) }
            if (ativa?.isPrivateDnsActive == true) StatusDns.Automatico else StatusDns.Desligado
        } catch (_: Exception) {
            StatusDns.Desligado
        }
    }

    private fun dnsPrivado(context: Context): Item = when (val s = statusDnsPrivado(context)) {
        is StatusDns.Desligado -> Item("dnsprivado", "DNS Privado do Android", "Desligado, como deve ser. Os nomes são resolvidos pela rede Tor.", Gravidade.OK)
        is StatusDns.Automatico -> Item(
            "dnsprivado", "DNS Privado do Android",
            "Está no modo automático. Isso faz a primeira página demorar cerca de 15 segundos a mais para abrir.",
            Gravidade.ATENCAO, Acao.ABRIR_DNS_PRIVADO, "Abrir configuração",
            dispensar = "Deixar assim",
        )
        is StatusDns.Fixo -> Item(
            "dnsprivado", "DNS Privado impede o Orbion de funcionar",
            "Está fixo em \"${s.servidor}\".\n\nCom ele ligado, os aplicativos descobrem os endereços por fora do túnel e conectam por número, sem o nome do site. " +
                "O Orbion deixa de reconhecer o destino — e sem isso não consegue desviar os serviços que barram o Tor, nem bloquear rastreadores, nem listar os domínios nos Registros.\n\n" +
                "Desligue. Com o Orbion conectado você não perde nada: o próprio túnel resolve os nomes por dentro.\n\n" +
                "SÓ RELIGUE se, com o Orbion DESCONECTADO, o celular parar de navegar — algumas redes bloqueiam a porta de DNS comum.",
            Gravidade.GRAVE, Acao.ABRIR_DNS_PRIVADO, "Abrir configuração",
        )
    }

    private fun resolucaoDeNomes(): Item {
        val exec = Executors.newSingleThreadExecutor()
        val ok = try {
            exec.submit<Boolean> {
                try {
                    InetAddress.getByName("torproject.org") != null
                } catch (_: Exception) {
                    false
                }
            }.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        } finally {
            exec.shutdownNow()
        }
        return if (ok) {
            Item("dns", "Resolução de nomes", "O aparelho traduz endereços normalmente.", Gravidade.OK)
        } else {
            Item(
                "dns", "Resolução de nomes",
                "O aparelho NÃO está conseguindo traduzir nomes de sites em endereços. Isso não é o Orbion — acontece igual com ele desligado.\n\n" +
                    "Esqueça a rede Wi-Fi e conecte de novo. Se persistir, defina um DNS fixo em Wi-Fi → sua rede → Modificar → IP estático → DNS 1: 1.1.1.1",
                Gravidade.GRAVE,
            )
        }
    }

    private fun appsIncompativeis(context: Context): Item {
        val instalados = AppsProblematicos.instaladosNoAparelho(context)
        if (instalados.isEmpty()) {
            return Item("appsincompativeis", "Aplicativos incompatíveis", "Nenhum aplicativo conhecido por bloquear o Tor está instalado.", Gravidade.OK)
        }
        val prefs = Preferencias(context)
        val fora = if (prefs.escolherApps) prefs.appsForaDoTunel else emptySet()
        val pendentes = instalados.filter { it.pacote !in fora }
        if (pendentes.isEmpty()) {
            return Item("appsincompativeis", "Aplicativos incompatíveis", "Todos os aplicativos conhecidos por bloquear o Tor já estão fora do túnel.", Gravidade.OK)
        }
        return Item(
            "appsincompativeis", "Aplicativos que barram a rede Tor",
            "${pendentes.size} instalado(s): ${pendentes.joinToString(", ") { it.nome }}.\n\n" +
                "Não é preciso fazer nada. O Orbion desvia sozinho apenas os endereços que esses serviços exigem, e mantém o restante do tráfego deles passando pela rede Tor.\n\n" +
                "Se algum continuar sem funcionar mesmo assim, tire-o do túnel pela chave dele na aba Apps.",
            Gravidade.DICA, Acao.ABRIR_APPS, "Ir para Apps",
            dispensar = "Entendi",
        )
    }
}
