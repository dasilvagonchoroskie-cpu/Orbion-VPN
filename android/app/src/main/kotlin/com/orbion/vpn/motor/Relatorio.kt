package com.orbion.vpn.motor

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Relatório para o suporte: tudo o que ajuda a entender um problema, num
 * arquivo só, para enviar pelo WhatsApp, e-mail etc. (copiar e colar cortava
 * os registros em 20 mil caracteres).
 *
 * Privacidade: NÃO entram os sites visitados, as listas de sites da pessoa
 * nem as bridges dela — só contagens. O registro do Tor já vem sem endereços
 * (SafeLogging do próprio Tor).
 */
object Relatorio {

    private fun data(ms: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ROOT).format(Date(ms))

    private fun StringBuilder.secao(titulo: String, corpo: StringBuilder.() -> Unit) {
        append("\n==== ").append(titulo).append(" ====\n")
        try {
            corpo()
        } catch (e: Exception) {
            append("(não foi possível ler: ").append(e.message).append(")\n")
        }
    }

    private fun StringBuilder.linha(rotulo: String, valor: Any?) {
        append(rotulo).append(": ").append(valor?.toString() ?: "—").append('\n')
    }

    fun montar(context: Context): String {
        val sb = StringBuilder(64 * 1024)
        val agora = System.currentTimeMillis()
        val prefs = Preferencias(context)
        val pi = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) {
            null
        }
        val codigo = when {
            pi == null -> "?"
            Build.VERSION.SDK_INT >= 28 -> pi.longVersionCode.toString()
            else -> @Suppress("DEPRECATION") pi.versionCode.toString()
        }
        sb.append("ORBION VPN — RELATÓRIO PARA SUPORTE\n")
        sb.append("Gerado em ").append(data(agora)).append('\n')
        sb.append("App ").append(pi?.versionName ?: "?").append(" (").append(codigo).append(")")
            .append("  •  Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")")
            .append("  •  ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" (").append(Fabricantes.marca().nome).append(")")
            .append("  •  ").append(Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
            .append("  •  Tor ").append(TorManager.versao(context)).append('\n')
        sb.append("Sem sites visitados, listas de sites ou bridges: só contagens.\n")

        sb.secao("ESTADO") {
            linha("Estado", Painel.estado.name)
            linha("Mensagem", Painel.mensagem.ifEmpty { null })
            linha("Erro", Painel.erro?.replace('\n', ' '))
            linha("Serviço ativo", OrbionVpnService.ativo != null)
            linha("A pessoa deixou ligada", prefs.estavaLigada)
            linha("Sem internet agora", Painel.semInternet)
            linha("Caminho de entrada", Painel.caminhoEmUso)
            linha("Conectada desde", if (Painel.conectadoDesde > 0) data(Painel.conectadoDesde) else null)
            linha("Quedas do Tor na sessão", Painel.quedasDoTor)
            linha("Apps por fora do Tor agora", Painel.appsForaNaSessao.size)
            linha("Sites desviados sozinhos", FiltroSocks.quantosDesviadosAuto())
            linha("Destinos em pausa", FiltroSocks.disjuntor.pausadosAgora(agora).size)
            linha("VPN sempre ativa", Painel.vpnSempreAtiva)
            linha("Bloquear conexões sem VPN", Painel.bloqueioSemVpn)
            linha("Primeiro plano", Painel.tipoPrimeiroPlano)
            linha("Avisos \"only an IP address\" do Tor (inofensivos, fora do registro)", TorManager.avisosDeIpDireto())
        }

        sb.secao("CONFIGURAÇÃO") {
            linha("Kill Switch", prefs.killSwitch)
            linha("Bloqueio de rastreadores", prefs.bloquearRastreadores)
            linha("IPv6 capturado", prefs.capturarIPv6)
            linha("Rotação (s)", prefs.intervaloRotacaoSegundos)
            linha("Rota inteligente", prefs.rotaInteligente)
            linha("Categorias desligadas", prefs.categoriasDesligadas.sorted().joinToString().ifEmpty { "nenhuma" })
            linha("Desvio automático", prefs.desvioAutomatico)
            linha("Apps por fora (escolha da pessoa)", prefs.appsForaDoTunel.size)
            linha("Apps sempre pelo Tor (escolha da pessoa)", prefs.appsSempreNoTor.size)
            linha(
                "Serviços do Google/Play Store pelo Tor (escolha da pessoa)",
                prefs.appsSempreNoTor.count { it in RotaInteligente.SERVICOS_DO_GOOGLE },
            )
            linha("Sites sempre por fora", prefs.dominiosDiretos.size)
            linha("Sites sempre pelo Tor", prefs.dominiosSempreTor.size)
            linha("Conectar ao abrir / ao ligar", "${prefs.conectarAoAbrir} / ${prefs.conectarNoBoot}")
            linha("Bridges próprias", if (prefs.usarBridges) "sim (${Transportes.linhasDaPessoa(prefs.bridges).size})" else "não")
            linha("Contorno automático", prefs.contornoAutomatico)
        }

        sb.secao("DIAGNÓSTICO") {
            for (i in Diagnostico.verificar(context)) {
                append('[').append(i.gravidade.name).append("] ").append(i.titulo).append(": ")
                append(i.situacao.replace("\n\n", " / ").replace('\n', ' ')).append('\n')
            }
        }

        sb.secao("FECHAMENTOS REGISTRADOS PELO ANDROID") {
            val lista = Eventos.fechamentos(context, 16, comRastro = true)
            if (lista.isEmpty()) append("Nenhum (ou Android anterior ao 11).\n")
            val instalada = Eventos.instaladaEm(context)
            if (instalada > 0) append("Esta versão foi instalada em ").append(data(instalada)).append(".\n")
            for (f in lista) {
                append(data(f.quando)).append("  •  ").append(if (f.daVpn) "VPN" else "tela")
                append("  •  motivo ").append(f.codigo).append(": ").append(f.explicacao)
                append("  •  importância ").append(f.importancia)
                if (f.inesperado) append("  •  INESPERADO")
                else if (!f.daVpn && f.importancia >= 400) append("  •  normal (tela em segundo plano)")
                if (f.anterior) append("  •  antes desta versão")
                if (f.descricao.isNotBlank()) append("  •  \"").append(f.descricao).append('"')
                append('\n')
                f.rastro?.let { append(it).append('\n') }
            }
        }

        sb.secao("DIÁRIO DA VPN (mais recente primeiro)") {
            for (e in Eventos.ler(context, 200)) append(data(e.quando)).append("  ").append(e.texto).append('\n')
        }

        sb.secao("FILTRO (contagens da sessão)") {
            for ((k, v) in FiltroSocks.contagem()) linha(k, v)
        }

        sb.secao("TOR (últimas 300 linhas)") {
            for (l in TorManager.lerLog(300)) append(l).append('\n')
        }

        sb.secao("TÚNEL (avisos)") {
            val linhas = TunelHev.lerLog(context, 80)
            if (linhas.isEmpty()) append("Nenhum aviso.\n")
            for (l in linhas) append(l).append('\n')
        }
        return sb.toString()
    }
}
