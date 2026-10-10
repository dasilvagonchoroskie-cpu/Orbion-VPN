package com.orbion.vpn.motor

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verificação do aparelho: tudo o que pode fazer o Android encerrar o
 * Orbion ou atrapalhar a navegação, com o botão que resolve cada item.
 */
object Diagnostico {

    enum class Gravidade { OK, DICA, ATENCAO, GRAVE }

    enum class Acao {
        NENHUMA, ISENTAR_BATERIA, ABRIR_DNS_PRIVADO, PEDIR_NOTIFICACAO, ABRIR_VPN,
        ABRIR_APP_INFO, ABRIR_DESENVOLVEDOR, ABRIR_APPS
    }

    data class Item(
        val id: String,
        val titulo: String,
        val situacao: String,
        val gravidade: Gravidade,
        val acao: Acao = Acao.NENHUMA,
        val botao: String = "",
    ) {
        fun comoMapa(): Map<String, Any> = mapOf(
            "id" to id, "titulo" to titulo, "situacao" to situacao,
            "gravidade" to gravidade.name, "acao" to acao.name, "botao" to botao,
        )
    }

    fun verificar(context: Context): List<Item> = listOfNotNull(
        binarios(context),
        bateria(context),
        fabricante(context),
        sempreAtiva(),
        processosFilhos(),
        notificacoes(context),
        dnsPrivado(context),
        resolucaoDeNomes(),
        appsIncompativeis(context),
    )

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

    private fun bateria(context: Context): Item =
        if (bateriaIsenta(context)) {
            Item("bateria", "Economia de bateria", "O Orbion está liberado para rodar em segundo plano.", Gravidade.OK)
        } else {
            Item(
                "bateria", "Economia de bateria",
                "O Android está autorizado a encerrar o Orbion para poupar bateria. Quando isso acontece a VPN cai e o celular pode ficar sem internet.",
                Gravidade.GRAVE, Acao.ISENTAR_BATERIA, "Liberar o Orbion",
            )
        }

    /** Fabricantes com "matador de apps" próprio, além do Android. */
    private fun fabricante(context: Context): Item {
        val m = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val passos = when {
            "samsung" in m ->
                "Samsung: abra Configurações → Bateria → Limites de uso em segundo plano → \"Apps que nunca são suspensos\" e adicione o Orbion. " +
                    "Depois, nas informações do app → Bateria, escolha \"Sem restrições\"."
            "xiaomi" in m || "redmi" in m || "poco" in m ->
                "Xiaomi/Redmi/POCO: nas informações do app, ligue \"Início automático\" e em Economia de bateria escolha \"Sem restrições\". " +
                    "Na tela de apps recentes, segure o Orbion e toque no cadeado."
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
        return Item("fabricante", "Ajuste da marca do celular (${Build.MANUFACTURER})", passos, Gravidade.DICA, Acao.ABRIR_APP_INFO, "Abrir informações do app")
    }

    private fun sempreAtiva(): Item {
        val rodando = Painel.estado == Painel.Estado.CONECTADO || Painel.estado == Painel.Estado.CONECTANDO ||
            Painel.estado == Painel.Estado.BLOQUEADO
        return when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> Item(
                "sempreativa", "VPN sempre ativa",
                "Marque o Orbion como \"VPN sempre ativa\" nas configurações de VPN do Android. Assim o próprio sistema religa o Orbion se ele for fechado.",
                Gravidade.DICA, Acao.ABRIR_VPN, "Abrir configurações de VPN",
            )
            rodando && Painel.vpnSempreAtiva -> Item(
                "sempreativa", "VPN sempre ativa",
                "Ligada. Se o Orbion for fechado, o próprio Android religa." +
                    if (Painel.bloqueioSemVpn) " O bloqueio de conexões sem VPN também está ligado." else "",
                Gravidade.OK,
            )
            else -> Item(
                "sempreativa", "VPN sempre ativa",
                "É a proteção mais forte contra o Android encerrar o Orbion: marcada como \"VPN sempre ativa\", é o próprio sistema que religa o Orbion se ele cair.\n\n" +
                    "Toque no botão, abra a engrenagem ao lado de Orbion VPN e ligue \"VPN sempre ativa\".",
                Gravidade.ATENCAO, Acao.ABRIR_VPN, "Abrir configurações de VPN",
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
            )
        } else {
            Item(
                "processosfilhos", "Restrição de processos filhos",
                "O Tor roda como um programa auxiliar do Orbion. No Android 12 e 13, o sistema pode encerrar esse programa se o Orbion não estiver liberado na bateria. Mantenha os ajustes de bateria acima em dia.",
                Gravidade.DICA,
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
        )
    }
}
