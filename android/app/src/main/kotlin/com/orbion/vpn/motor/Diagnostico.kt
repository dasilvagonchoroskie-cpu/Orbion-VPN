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
 *
 * Roda no processo da VPN (usa as configurações).
 */
object Diagnostico {

    enum class Gravidade { OK, DICA, ATENCAO, GRAVE }

    enum class Acao {
        NENHUMA, ISENTAR_BATERIA, ABRIR_DNS_PRIVADO, PEDIR_NOTIFICACAO, ABRIR_VPN,
        ABRIR_APP_INFO, ABRIR_DESENVOLVEDOR, ABRIR_APPS, ABRIR_AUTOSTART, ABRIR_BATERIA_MARCA,
        ABRIR_REGISTROS
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
        val prefs = Preferencias(context)
        val confirmados = prefs.avisosDispensados
        val itens = listOfNotNull(
            binarios(context),
            bateria(context),
            fabricante(context),
            fechamentos(context),
            sempreAtiva(context),
            processosFilhos(),
            notificacoes(context),
            dnsPrivado(context),
            resolucaoDeNomes(),
            rotaInteligente(context, prefs),
            googlePeloTor(prefs),
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

    /**
     * "Início automático" da Xiaomi está ligado para o Orbion?
     * true/false quando a Xiaomi informa; null quando não dá para saber.
     * A Xiaomi guarda essa chave como uma permissão própria (código 10008).
     */
    fun inicioAutomaticoXiaomi(context: Context): Boolean? {
        if (Fabricantes.marca() != Fabricantes.Marca.XIAOMI) return null
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

    /** Não usa as configurações: pode ser chamado de qualquer processo. */
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
            return Item("bateria", "Economia de bateria do Android", "O Orbion está liberado para rodar em segundo plano.", Gravidade.OK)
        }
        val proprio = Fabricantes.temGerenciadorProprio()
        val texto = "O Android está autorizado a encerrar o Orbion para poupar bateria. Quando isso acontece a VPN cai e o celular pode ficar sem internet.\n\n" +
            "Toque em \"Liberar o Orbion\". Se aparecer uma pergunta, toque em Permitir (Allow). " +
            "Se abrir uma tela de bateria, escolha \"Sem restrições\" (Unrestricted) ou \"Não otimizar\" (Don't optimize) e volte para cá."
        return Item(
            "bateria", "Economia de bateria do Android", texto,
            Gravidade.GRAVE, Acao.ISENTAR_BATERIA, "Liberar o Orbion",
            // Em marcas com gerenciador próprio, a escolha feita na tela da
            // marca nem sempre aparece para o app. Então a pessoa confirma.
            dispensar = if (proprio) "Já deixei \"Sem restrições\"" else "",
        )
    }

    /** O "matador de apps" da marca do celular, além do Android. */
    private fun fabricante(context: Context): Item {
        val marca = Fabricantes.marca()
        val passos = Fabricantes.passos()
        val temInicio = Fabricantes.telasDeInicioAutomatico(context).isNotEmpty()
        val temBateria = Fabricantes.telasDeBateria(context).isNotEmpty()
        val auto = inicioAutomaticoXiaomi(context)
        val linhas = passos.mapIndexed { i, p -> "${i + 1}. $p" }.toMutableList()
        when (auto) {
            true -> linhas.add(0, "✓ Início automático: ligado.")
            false -> linhas.add(0, "✗ Início automático: DESLIGADO — é ele que deixa o Orbion voltar sozinho.")
            null -> Unit
        }
        val texto = "Para o sistema do seu ${marca.nome} não fechar o Orbion:\n\n" + linhas.joinToString("\n\n")
        val (acao1, botao1) = when {
            temInicio && auto != true -> Acao.ABRIR_AUTOSTART to "Abrir início automático"
            temBateria -> Acao.ABRIR_BATERIA_MARCA to "Abrir bateria do ${marca.nome.substringBefore(" /")}"
            else -> Acao.ABRIR_APP_INFO to "Abrir informações do app"
        }
        val (acao2, botao2) = when {
            acao1 == Acao.ABRIR_AUTOSTART && temBateria -> Acao.ABRIR_BATERIA_MARCA to "Bateria"
            acao1 != Acao.ABRIR_APP_INFO -> Acao.ABRIR_APP_INFO to "Informações do app"
            else -> Acao.NENHUMA to ""
        }
        return Item(
            "fabricante", "Ajustes do ${marca.nome}", texto,
            if (auto == false) Gravidade.ATENCAO else Gravidade.DICA,
            acao1, botao1,
            dispensar = "Já fiz esses ajustes",
            acao2 = acao2, botao2 = botao2,
        )
    }

    /** O Android derrubou a VPN nos últimos dias? (Android 11+) */
    private fun fechamentos(context: Context): Item? {
        if (Build.VERSION.SDK_INT < 30) return null
        val semana = System.currentTimeMillis() - 7 * 24 * 3600_000L
        // Travamentos de uma versão anterior do Orbion não contam: eram defeito
        // daquela versão, não do aparelho.
        val recentes = Eventos.fechamentos(context, 20).filter {
            it.daVpn && it.inesperado && it.quando > semana && !(it.anterior && it.codigo in setOf(4, 5, 6))
        }
        if (recentes.isEmpty()) {
            return Item("fechamentos", "Quedas da VPN", "O Android não derrubou a VPN nos últimos 7 dias.", Gravidade.OK)
        }
        val ultimo = recentes.maxByOrNull { it.quando }!!
        return Item(
            "fechamentos", "O Android derrubou a VPN ${recentes.size} vez(es) em 7 dias",
            "A última foi em ${Eventos.hora(ultimo.quando)}: ${ultimo.explicacao}.\n\n" +
                "O Orbion religou sozinho, mas o ideal é não cair. Faça os ajustes de bateria e da marca acima. " +
                "Os detalhes de cada queda ficam em Registros → Eventos.",
            Gravidade.ATENCAO, Acao.ABRIR_REGISTROS, "Ver detalhes",
            dispensar = "Entendi",
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
            if (app != null) return app == context.packageName
        } catch (_: Exception) {
        }
        return when (prefs.sempreAtivaConhecida) {
            1 -> true
            0 -> false
            else -> null
        }
    }

    private fun sempreAtiva(context: Context): Item {
        val texto = "É a proteção mais forte contra quedas: marcado como \"VPN sempre ativa\" (Always-on VPN), é o próprio Android que religa o Orbion se ele cair.\n\n" +
            "Toque no botão, abra a engrenagem ao lado de Orbion VPN e ligue \"VPN sempre ativa\" (Always-on VPN)."
        return when (sempreAtivaLigada(context)) {
            true -> Item("sempreativa", "VPN sempre ativa", "Ligada. Se o Orbion for fechado, o próprio Android religa.", Gravidade.OK)
            false -> Item(
                "sempreativa", "VPN sempre ativa", texto,
                Gravidade.ATENCAO, Acao.ABRIR_VPN, "Abrir configurações de VPN",
                dispensar = "Não quero usar",
            )
            null -> Item(
                "sempreativa", "VPN sempre ativa", texto,
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
                    "Com os ajustes acima isso quase não acontece. Se mesmo assim o Tor cair sozinho, ative as Opções do desenvolvedor (Developer options) e ligue \"Desativar restrições de processos filhos\" (Disable child process restrictions).",
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
                "Bloqueadas. Sem elas você não vê se a VPN está no ar, não consegue desligá-la pela barra e não recebe o aviso \"toque para religar\".",
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
            "Está no modo automático. Funciona, mas o ideal é deixar \"Desativado\" (Off): assim nenhuma consulta de endereço tenta outro caminho antes do túnel.",
            Gravidade.DICA, Acao.ABRIR_DNS_PRIVADO, "Abrir configuração",
            dispensar = "Deixar assim",
        )
        is StatusDns.Fixo -> Item(
            "dnsprivado", "DNS Privado impede o Orbion de funcionar",
            "Está fixo em \"${s.servidor}\".\n\nCom ele ligado, os aplicativos descobrem os endereços por fora do túnel e conectam por número, sem o nome do site. " +
                "O Orbion deixa de reconhecer o destino — e sem isso não consegue desviar os sites que barram o Tor, nem bloquear rastreadores, nem listar os domínios nos Registros.\n\n" +
                "Desligue em Configurações (Settings) → Rede e Internet (Network & internet) → DNS Privado (Private DNS) → \"Desativado\" (Off). Com o Orbion conectado você não perde nada: o próprio túnel resolve os nomes por dentro.\n\n" +
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
                    "Esqueça a rede Wi-Fi e conecte de novo. Se persistir, defina um DNS fixo em Wi-Fi → sua rede → Modificar (Modify) → IP estático (Static) → DNS 1: 1.1.1.1",
                Gravidade.GRAVE,
            )
        }
    }

    private fun rotaInteligente(context: Context, prefs: Preferencias): Item {
        if (!prefs.rotaInteligente) {
            return Item(
                "rotainteligente", "Rota inteligente desligada",
                "Tudo passa pela rede Tor, menos o que você escolheu à mão. Bancos, gov.br, WhatsApp e redes sociais costumam não funcionar assim.",
                Gravidade.DICA, Acao.ABRIR_APPS, "Ver apps",
                dispensar = "Quero assim",
            )
        }
        val fora = RotaInteligente.appsForaDoTor(context, prefs)
        if (fora.isEmpty()) {
            return Item("rotainteligente", "Rota inteligente", "Ligada. Nenhum app deste celular precisa sair do Tor.", Gravidade.OK)
        }
        val porMotivo = fora.values.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }
            .joinToString(", ") { "${it.key} (${it.value})" }
        return Item(
            "rotainteligente", "Rota inteligente",
            "Ligada. ${fora.size} app(s) vão por fora do Tor: $porMotivo. O resto passa pela rede Tor." +
                if (prefs.appsSempreNoTor.isNotEmpty()) " Você mandou ${prefs.appsSempreNoTor.size} app(s) que iriam por fora para dentro do Tor." else "",
            Gravidade.OK,
        )
    }

    /**
     * Serviços do Google mandados para dentro do Tor pela pessoa. São eles que
     * entregam as notificações de quase todos os apps; pelo Tor elas atrasam
     * ou não chegam. Não muda nada sozinho: só explica e leva à aba Apps.
     */
    private fun googlePeloTor(prefs: Preferencias): Item? {
        if (!prefs.rotaInteligente) return null
        val noTor = prefs.appsSempreNoTor.count { it in RotaInteligente.SERVICOS_DO_GOOGLE }
        if (noTor == 0) return null
        return Item(
            "googlepelotor", "Notificações podem atrasar",
            "Você mandou os serviços do Google pela rede Tor. São eles que entregam as notificações de quase todos os apps " +
                "(WhatsApp, banco, e-mail...): pelo Tor elas podem atrasar ou não chegar, e a Play Store pode falhar ao baixar apps.\n\n" +
                "Para voltar ao normal, na aba Apps desligue a chave de \"Google Play Services\", \"Estrutura dos Serviços do Google\" " +
                "e \"Google Play Store\" (ou toque em \"Voltar ao automático\").",
            Gravidade.DICA, Acao.ABRIR_APPS, "Ver apps",
            dispensar = "Quero assim",
        )
    }
}
