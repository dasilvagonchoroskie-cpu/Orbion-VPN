package com.orbion.vpn.motor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.orbion.vpn.MainActivity
import com.orbion.vpn.R
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * O serviço da VPN: cria a interface, sobe o Tor, o filtro e o túnel, e
 * vigia tudo enquanto estiver ligado.
 *
 * CORREÇÕES em relação à versão anterior (motivos de o Android "matar" o app):
 *
 *  1. Entrar em primeiro plano nunca derruba o app. Antes, se o Android 14
 *     recusasse o tipo "systemExempted" (permissão de VPN revogada, outra VPN
 *     ligada, início pelo boot), a chamada sem proteção fechava o Orbion.
 *     Agora tenta systemExempted → specialUse → segue sem derrubar.
 *
 *  2. Com o Kill Switch ligado, a interface da VPN sobe ANTES do Tor. Antes
 *     ela só subia depois de até 2 minutos de conexão do Tor: nesse intervalo
 *     o tráfego vazava sem proteção, e o serviço não tinha o "vínculo de VPN"
 *     que o Android usa para não encerrar apps de VPN.
 *
 *  3. Se o Tor cair (inclusive se o Android encerrar o processo dele), o
 *     Kill Switch religa SÓ o Tor, mantendo túnel e filtro de pé, com
 *     tentativas cada vez mais espaçadas.
 *
 *  4. Troca de rede (Wi-Fi ↔ 4G) avisa o Tor na hora, em vez de esperar
 *     as conexões antigas morrerem por tempo-limite.
 *
 *  5. Suporte a "VPN sempre ativa": o próprio Android religa o Orbion.
 */
class OrbionVpnService : VpnService() {

    companion object {
        private const val TAG = "OrbionVpnService"

        const val ACAO_LIGAR = "com.orbion.vpn.START"
        const val ACAO_DESLIGAR = "com.orbion.vpn.STOP"
        const val ACAO_REINICIAR = "com.orbion.vpn.RESTART"
        const val ACAO_NOVA_IDENTIDADE = "com.orbion.vpn.NOVA_IDENTIDADE"

        private const val CANAL_STATUS = "OrbionVPNChannel"
        private const val CANAL_ALERTAS = "OrbionAlertas"
        private const val NOTIF_STATUS = 1
        private const val NOTIF_ALERTA = 2

        private const val ENDERECO_VPN = "10.8.0.2"
        private const val ENDERECO_VPN_V6 = "fd00:6f72:6269:6f6e::1"
        private const val MTU = 8500

        /** Intervalos entre tentativas de reconexão do Kill Switch (segundos). */
        private val ESPERAS_RECONEXAO = longArrayOf(3, 10, 20, 40, 90, 180, 300)

        @Volatile var ativo: OrbionVpnService? = null
            private set

        fun ligar(context: Context) = enviar(context, ACAO_LIGAR, primeiroPlano = true)

        fun reiniciar(context: Context) = enviar(context, ACAO_REINICIAR, primeiroPlano = true)

        fun desligar(context: Context) {
            if (ativo == null) {
                Preferencias(context).estavaLigada = false
                Painel.desconectado()
                return
            }
            enviar(context, ACAO_DESLIGAR, primeiroPlano = false)
        }

        fun novaIdentidade(context: Context) {
            if (ativo == null) return
            enviar(context, ACAO_NOVA_IDENTIDADE, primeiroPlano = false)
        }

        private fun enviar(context: Context, acao: String, primeiroPlano: Boolean) {
            val i = Intent(context, OrbionVpnService::class.java).setAction(acao)
            try {
                if (primeiroPlano && ativo == null) context.startForegroundService(i) else context.startService(i)
            } catch (e: Exception) {
                Log.e(TAG, "Não foi possível acionar o serviço ($acao): ${e.message}")
                if (acao == ACAO_LIGAR || acao == ACAO_REINICIAR) {
                    Painel.falhou("O Android não deixou iniciar a VPN agora: ${e.message}")
                }
            }
        }
    }

    private val trava = Any()
    private val trabalho = Executors.newSingleThreadExecutor { r -> Thread(r, "orbion-conexao") }
    private val agenda = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "orbion-agenda").apply { isDaemon = true }
    }

    @Volatile private var geracao = 0
    @Volatile private var conexao: Future<*>? = null
    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var appsDaInterface: Set<String> = emptySet()
    @Volatile private var tor: TorManager? = null
    @Volatile private var filtro: FiltroSocks? = null

    private var tarefaContador: ScheduledFuture<*>? = null
    private var tarefaSupervisao: ScheduledFuture<*>? = null
    private var tarefaReconexao: ScheduledFuture<*>? = null
    private var tarefaTrocaDeRede: ScheduledFuture<*>? = null
    private var vigiaDeRede: ConnectivityManager.NetworkCallback? = null
    @Volatile private var redeAtual: Network? = null

    @Volatile private var killSwitch = true
    @Volatile private var jaConectou = false
    @Volatile private var usuarioPediuParar = false
    @Volatile private var tentativaDeReconexao = 0
    @Volatile private var textoNotificacao = "Iniciando..."

    private enum class Modo { NOVA, REINICIO, SO_TOR }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        ativo = this
        criarCanais()
        entrarEmPrimeiroPlano("Iniciando...", 0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val acao = intent?.action
        Log.d(TAG, "onStartCommand: ${acao ?: "(religado pelo sistema)"}")
        // Sempre confirma o primeiro plano: o Android exige isso depois de um
        // startForegroundService, e não custa nada nos outros casos.
        entrarEmPrimeiroPlano(textoNotificacao, null)
        // Quando a pessoa liga "VPN sempre ativa", o Android inicia o serviço
        // na hora: aproveita para registrar, e o aviso some da tela.
        atualizarInfoSempreAtiva()
        return when (acao) {
            ACAO_DESLIGAR -> {
                desligarAPedido()
                START_NOT_STICKY
            }
            ACAO_REINICIAR -> {
                usuarioPediuParar = false
                iniciarConexao(Modo.REINICIO)
                START_STICKY
            }
            ACAO_NOVA_IDENTIDADE -> {
                pedirNovaIdentidade()
                START_STICKY
            }
            else -> {
                // ACAO_LIGAR, religamento do sistema (intent nulo) ou
                // "VPN sempre ativa" (ação android.net.VpnService).
                ligar()
                START_STICKY
            }
        }
    }

    override fun onRevoke() {
        Log.w(TAG, "Permissão de VPN revogada pelo sistema.")
        usuarioPediuParar = true
        Preferencias(this).estavaLigada = false
        geracao++
        tor?.stop()
        conexao?.cancel(true)
        encerrarTudo()
        Painel.falhou("A VPN foi desligada pelo Android: outra VPN foi ligada ou a permissão do Orbion foi retirada.")
        pararServico()
        super.onRevoke()
    }

    override fun onDestroy() {
        geracao++
        try {
            tor?.stop()
            conexao?.cancel(true)
            encerrarTudo()
        } catch (_: Exception) {
        }
        if (Painel.estado != Painel.Estado.ERRO) Painel.desconectado()
        trabalho.shutdownNow()
        agenda.shutdownNow()
        ativo = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Ligar / desligar
    // ------------------------------------------------------------------

    private fun ligar() {
        usuarioPediuParar = false
        Preferencias(this).estavaLigada = true
        val emAndamento = conexao?.isDone == false
        if (emAndamento) {
            Log.d(TAG, "Conexão já em andamento; ignorando pedido repetido.")
            return
        }
        val tudoNoAr = Painel.estado == Painel.Estado.CONECTADO && tor?.estaVivo() == true && TunelHev.rodando()
        if (tudoNoAr) return
        iniciarConexao(if (vpnInterface != null) Modo.REINICIO else Modo.NOVA)
    }

    private fun iniciarConexao(modo: Modo) {
        val g: Int
        synchronized(trava) {
            g = ++geracao
            tarefaReconexao?.cancel(false)
            tor?.stop()
            conexao?.cancel(true)
        }
        Painel.conectando(0, "Iniciando...")
        notificar("Iniciando...", 0)
        conexao = trabalho.submit(Runnable { conectar(g, modo) })
    }

    private fun desligarAPedido() {
        Log.d(TAG, "Desligando a pedido do usuário.")
        usuarioPediuParar = true
        Preferencias(this).estavaLigada = false
        synchronized(trava) {
            geracao++
            tor?.stop()
            conexao?.cancel(true)
        }
        encerrarTudo()
        Painel.desconectado()
        Painel.contornoDeBloqueio = null
        pararServico()
    }

    private fun pararServico() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    // ------------------------------------------------------------------
    // O caminho da conexão
    // ------------------------------------------------------------------

    private fun conectar(g: Int, modo: Modo) {
        try {
            val prefs = Preferencias(this)
            killSwitch = prefs.killSwitch
            val fora = if (prefs.escolherApps) prefs.appsForaDoTunel else emptySet()
            val sempreAtiva = Build.VERSION.SDK_INT >= 29 && isAlwaysOn

            progresso(g, 0, "Iniciando...")
            if (!aguardarRede(20_000, g)) Log.w(TAG, "Sem rede após a espera. Tentando mesmo assim.")
            if (g != geracao) return

            val soTor = modo == Modo.SO_TOR && vpnInterface != null && filtro != null && TunelHev.rodando()
            if (soTor) {
                pararSupervisao()
                tor?.stop()
                tor = null
            } else {
                pararPecas()
            }

            // Fail-closed: com Kill Switch (ou VPN sempre ativa), a interface
            // sobe antes do Tor e segura o tráfego até tudo estar pronto.
            if (!soTor && (killSwitch || sempreAtiva)) {
                if (!garantirInterface(g, fora)) {
                    if (g != geracao) return
                    falhar(g, "O Android recusou criar a interface de VPN. Normalmente é outra VPN já ativa no aparelho, ou a permissão de VPN não foi concedida.", fatal = true)
                    return
                }
            }

            val t = TorManager(this)
            tor = t
            val ok = t.startAndAwaitBootstrap(prefs) { pct, msg -> progresso(g, pct, msg) }
            if (g != geracao) {
                t.stop()
                return
            }
            if (!ok) {
                falhar(g, t.lastError ?: "Não foi possível conectar à rede Tor.", fatal = false)
                return
            }
            Painel.contornoDeBloqueio = if (t.contornouBloqueio) {
                "Esta rede estava bloqueando o Tor. A conexão foi refeita por ${Transportes.nome(t.caminhoEmUso)} e está funcionando."
            } else {
                null
            }

            if (soTor) {
                filtro?.sairDoModoKillSwitch()
            } else {
                progresso(g, 100, "Criando túnel...")
                if (!garantirInterface(g, fora)) {
                    if (g != geracao) return
                    falhar(g, "O Android recusou criar a interface de VPN. Normalmente é outra VPN já ativa no aparelho, ou a permissão de VPN não foi concedida.", fatal = true)
                    return
                }
                val f = FiltroSocks(this, TorManager.SOCKS_PORT)
                val portaDoTunel = if (f.iniciar(prefs.bloquearRastreadores, prefs.desvioDireto)) {
                    filtro = f
                    FiltroSocks.PORTA
                } else {
                    Log.w(TAG, "Filtro não subiu. Seguindo direto para o Tor.")
                    TorManager.SOCKS_PORT
                }
                val fd = vpnInterface?.fd ?: run {
                    falhar(g, "A interface da VPN fechou durante a conexão.", fatal = true)
                    return
                }
                val erro = TunelHev.iniciar(this, fd, portaDoTunel, MTU)
                if (erro != null) {
                    falhar(g, erro, fatal = true)
                    return
                }
            }
            if (g != geracao) return

            jaConectou = true
            tentativaDeReconexao = 0
            Painel.conectado()
            atualizarInfoSempreAtiva()
            notificar("Conectado pela rede Tor", null)
            iniciarContador()
            iniciarSupervisao()
            Log.d(TAG, "VPN CONECTADA (${t.caminhoEmUso.name}).")
        } catch (e: InterruptedException) {
            Log.d(TAG, "Conexão interrompida.")
        } catch (e: Exception) {
            Log.e(TAG, "Falha inesperada", e)
            falhar(g, "Falha inesperada: ${e.message}", fatal = false)
        }
    }

    private fun progresso(g: Int, pct: Int, msg: String) {
        if (g != geracao) return
        Painel.conectando(pct, msg)
        notificar(msg, pct)
    }

    /** Cria a interface se não existir, ou recria se a lista de apps mudou. */
    private fun garantirInterface(g: Int, fora: Set<String>): Boolean {
        synchronized(trava) {
            // Pedido antigo (a pessoa já desligou ou reconectou): não cria nada.
            if (g != geracao) return false
            val atual = vpnInterface
            if (atual != null && fora == appsDaInterface) return true
            val nova = criarInterface(fora) ?: return false
            if (atual != null && atual !== nova) {
                try {
                    atual.close()
                } catch (_: Exception) {
                }
            }
            vpnInterface = nova
            appsDaInterface = fora
            Painel.appsForaNaSessao = fora
        }
        acompanharTrocasDeRede()
        return true
    }

    private fun criarInterface(fora: Set<String>): ParcelFileDescriptor? {
        return try {
            val prefs = Preferencias(this)
            val b = Builder()
                .setSession("Orbion VPN")
                .addAddress(ENDERECO_VPN, 32)
                .addRoute("0.0.0.0", 0)
                .setMtu(MTU)
                .addDnsServer(TunelHev.DNS_MAPEADO)
                .setConfigureIntent(
                    PendingIntent.getActivity(
                        this, 0, Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            if (prefs.capturarIPv6) {
                try {
                    b.addAddress(ENDERECO_VPN_V6, 128)
                    b.addRoute("::", 0)
                } catch (e: Exception) {
                    Log.w(TAG, "Sem IPv6 nesta interface: ${e.message}")
                }
            }
            // O próprio Orbion fica fora do túnel: é assim que o Tor (processo
            // filho, mesmo usuário do Android) alcança a internet de verdade.
            b.addDisallowedApplication(packageName)
            for (pacote in fora) {
                try {
                    b.addDisallowedApplication(pacote)
                } catch (e: Exception) {
                    Log.w(TAG, "Não foi possível tirar '$pacote' do túnel: ${e.message}")
                }
            }
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
            redeAtual?.let { b.setUnderlyingNetworks(arrayOf(it)) }
            b.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar a interface de VPN: ${e.message}", e)
            null
        }
    }

    // ------------------------------------------------------------------
    // Falhas e Kill Switch
    // ------------------------------------------------------------------

    private fun falhar(g: Int, motivo: String, fatal: Boolean) {
        if (g != geracao) return
        Log.e(TAG, motivo)
        val sempreAtiva = Build.VERSION.SDK_INT >= 29 && isAlwaysOn
        val segurar = !fatal && !usuarioPediuParar && vpnInterface != null &&
            ((killSwitch && jaConectou) || sempreAtiva)
        if (segurar) {
            acionarKillSwitch(motivo)
            return
        }
        Painel.falhou(motivo)
        alertar(motivo)
        encerrarTudo()
        pararServico()
    }

    private fun acionarKillSwitch(motivo: String) {
        Log.w(TAG, "KILL SWITCH: $motivo")
        pararSupervisao()
        try {
            tor?.stop()
        } catch (_: Exception) {
        }
        tor = null
        filtro?.entrarEmModoKillSwitch()
        Painel.quedasDoTor++
        Painel.bloqueado(motivo)
        notificar("Rede Tor caiu — tráfego bloqueado, reconectando...", null)
        agendarReconexao()
    }

    private fun agendarReconexao() {
        val i = tentativaDeReconexao.coerceAtMost(ESPERAS_RECONEXAO.size - 1)
        val espera = ESPERAS_RECONEXAO[i]
        tentativaDeReconexao++
        Log.d(TAG, "Kill Switch: nova tentativa em ${espera}s")
        tarefaReconexao?.cancel(false)
        tarefaReconexao = agenda.schedule(Runnable {
            if (Painel.estado != Painel.Estado.BLOQUEADO || usuarioPediuParar) return@Runnable
            if (!temRede()) {
                Log.d(TAG, "Kill Switch: ainda sem rede.")
                agendarReconexao()
                return@Runnable
            }
            val modo = if (filtro != null && TunelHev.rodando()) Modo.SO_TOR else Modo.REINICIO
            iniciarConexao(modo)
        }, espera, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------
    // Limpeza
    // ------------------------------------------------------------------

    private fun pararSupervisao() {
        tarefaContador?.cancel(false)
        tarefaContador = null
        tarefaSupervisao?.cancel(false)
        tarefaSupervisao = null
    }

    /** Para Tor, túnel e filtro. A interface da VPN continua (fail-closed). */
    private fun pararPecas() {
        pararSupervisao()
        try {
            TunelHev.parar()
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao parar o túnel: ${e.message}")
        }
        try {
            filtro?.parar()
        } catch (_: Exception) {
        }
        filtro = null
        try {
            tor?.stop()
        } catch (_: Exception) {
        }
        tor = null
    }

    private fun encerrarTudo() {
        tarefaReconexao?.cancel(false)
        tarefaReconexao = null
        tarefaTrocaDeRede?.cancel(false)
        pararPecas()
        pararDeAcompanharRede()
        synchronized(trava) {
            try {
                vpnInterface?.close()
            } catch (_: Exception) {
            }
            vpnInterface = null
            appsDaInterface = emptySet()
        }
        jaConectou = false
        tentativaDeReconexao = 0
        Painel.zerarSessao()
    }

    // ------------------------------------------------------------------
    // Vigilância
    // ------------------------------------------------------------------

    private fun iniciarSupervisao() {
        tarefaSupervisao?.cancel(false)
        var voltas = 0
        tarefaSupervisao = agenda.scheduleWithFixedDelay({
            try {
                if (Painel.estado != Painel.Estado.CONECTADO) return@scheduleWithFixedDelay
                val g = geracao
                when {
                    tor?.estaVivo() != true ->
                        falhar(g, "A conexão com a rede Tor caiu (o processo do Tor foi encerrado). O tráfego foi bloqueado para não sair sem proteção.", fatal = false)
                    !TunelHev.rodando() ->
                        falhar(g, "O túnel parou de funcionar.", fatal = false)
                }
                voltas++
                if (voltas % 12 == 0) {
                    TunelHev.controlarTamanhoDoLog(this)
                    atualizarInfoSempreAtiva()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Supervisão: ${e.message}")
            }
        }, 5, 5, TimeUnit.SECONDS)
    }

    private fun iniciarContador() {
        tarefaContador?.cancel(false)
        var ultimoEnvio = -1L
        var ultimoReceb = -1L
        var ultimoInstante = 0L
        var voltas = 0
        tarefaContador = agenda.scheduleWithFixedDelay({
            try {
                val s = TunelHev.estatisticas() ?: return@scheduleWithFixedDelay
                val agora = System.currentTimeMillis()
                Painel.enviadoBytes = s.enviadoBytes
                Painel.recebidoBytes = s.recebidoBytes
                if (ultimoInstante > 0 && ultimoEnvio >= 0) {
                    val seg = (agora - ultimoInstante) / 1000.0
                    if (seg > 0) {
                        Painel.velocidadeEnvio = ((s.enviadoBytes - ultimoEnvio).coerceAtLeast(0) / seg).toLong()
                        Painel.velocidadeRecebimento = ((s.recebidoBytes - ultimoReceb).coerceAtLeast(0) / seg).toLong()
                    }
                }
                ultimoEnvio = s.enviadoBytes
                ultimoReceb = s.recebidoBytes
                ultimoInstante = agora
                voltas++
                // Notificação a cada ~4 s: suficiente e economiza bateria.
                if (voltas % 2 == 0 && Painel.estado == Painel.Estado.CONECTADO) {
                    notificar(
                        "↑ ${formatarBytes(Painel.velocidadeEnvio)}/s  ↓ ${formatarBytes(Painel.velocidadeRecebimento)}/s  •  " +
                            "${formatarBytes(s.enviadoBytes + s.recebidoBytes)} na sessão",
                        null,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Contador: ${e.message}")
            }
        }, 0, 2, TimeUnit.SECONDS)
    }

    /** Lê do Android se o Orbion está como "VPN sempre ativa" e guarda o resultado. */
    fun atualizarInfoSempreAtiva() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val ligada = isAlwaysOn
                Painel.vpnSempreAtiva = ligada
                Painel.bloqueioSemVpn = isLockdownEnabled
                Preferencias(this).sempreAtivaConhecida = if (ligada) 1 else 0
            } catch (_: Exception) {
            }
        }
    }

    private fun pedirNovaIdentidade() {
        agenda.execute {
            val t = tor
            val r = if (t == null || Painel.estado != Painel.Estado.CONECTADO) {
                "Conecte primeiro para pedir uma nova identidade."
            } else {
                t.novaIdentidade()
            }
            Painel.avisoDeIdentidade = r ?: "Pronto! As próximas conexões já saem por outro caminho, com outro IP."
        }
    }

    // ------------------------------------------------------------------
    // Rede do aparelho
    // ------------------------------------------------------------------

    private fun conectividade() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun temRede(): Boolean = try {
        val cm = conectividade()
        val n = cm.activeNetwork
        val caps = n?.let { cm.getNetworkCapabilities(it) }
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) {
        true
    }

    private fun aguardarRede(maxMs: Long, g: Int): Boolean {
        val fim = System.currentTimeMillis() + maxMs
        var avisou = false
        while (System.currentTimeMillis() < fim) {
            if (g != geracao) return false
            if (temRede()) return true
            if (!avisou) {
                progresso(g, 0, "Aguardando a rede do aparelho...")
                avisou = true
            }
            Thread.sleep(500)
        }
        return false
    }

    private fun acompanharTrocasDeRede() {
        if (vigiaDeRede != null) return
        val cm = conectividade()
        val vigia = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = redeDeBase(network)
            override fun onLost(network: Network) {
                if (network == redeAtual) redeDeBase(null)
            }
        }
        try {
            // O Orbion está fora do próprio túnel, então a "rede padrão" dele
            // é a rede física de verdade (Wi-Fi ou dados móveis).
            cm.registerDefaultNetworkCallback(vigia)
            vigiaDeRede = vigia
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível acompanhar trocas de rede: ${e.message}")
        }
    }

    private fun redeDeBase(rede: Network?) {
        val anterior = redeAtual
        redeAtual = rede
        try {
            setUnderlyingNetworks(rede?.let { arrayOf(it) })
        } catch (e: Exception) {
            Log.w(TAG, "setUnderlyingNetworks: ${e.message}")
        }
        if (rede != null && anterior != null && rede != anterior) {
            // Espera a rede nova assentar e avisa o Tor.
            tarefaTrocaDeRede?.cancel(false)
            tarefaTrocaDeRede = agenda.schedule(Runnable {
                if (Painel.estado == Painel.Estado.CONECTADO) {
                    val r = tor?.redeMudou()
                    Log.d(TAG, "Rede trocada; Tor avisado (${r ?: "ok"}).")
                }
            }, 2, TimeUnit.SECONDS)
        }
    }

    private fun pararDeAcompanharRede() {
        val v = vigiaDeRede ?: return
        try {
            conectividade().unregisterNetworkCallback(v)
        } catch (_: Exception) {
        }
        vigiaDeRede = null
        redeAtual = null
    }

    // ------------------------------------------------------------------
    // Notificações
    // ------------------------------------------------------------------

    private fun criarCanais() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val status = NotificationChannel(CANAL_STATUS, "Status da VPN", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Mostra se o Orbion VPN está conectado"
            setShowBadge(false)
        }
        val alertas = NotificationChannel(CANAL_ALERTAS, "Alertas do Orbion", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Avisa quando a VPN não conseguiu conectar"
        }
        nm.createNotificationChannel(status)
        nm.createNotificationChannel(alertas)
    }

    private fun montarNotificacao(texto: String, progresso: Int?): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val desligar = PendingIntent.getService(
            this, 1, Intent(this, OrbionVpnService::class.java).setAction(ACAO_DESLIGAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(this, CANAL_STATUS)
            .setContentTitle("Orbion VPN")
            .setContentText(texto)
            .setSmallIcon(R.drawable.ic_vpn_status)
            .setColor(0xFF00E5FF.toInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(abrir)
            .addAction(Notification.Action.Builder(null, "Desligar VPN", desligar).build())
        if (Painel.estado == Painel.Estado.CONECTADO) {
            val nova = PendingIntent.getService(
                this, 2, Intent(this, OrbionVpnService::class.java).setAction(ACAO_NOVA_IDENTIDADE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(Notification.Action.Builder(null, "Nova identidade", nova).build())
        }
        if (progresso != null && progresso < 100) b.setProgress(100, progresso, progresso == 0)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    /**
     * Entra em primeiro plano SEM nunca derrubar o app.
     * Ordem: systemExempted (VPN autorizada) → specialUse → sem tipo.
     */
    private fun entrarEmPrimeiroPlano(texto: String, progresso: Int?) {
        textoNotificacao = texto
        val n = montarNotificacao(texto, progresso)
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(NOTIF_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
                Painel.tipoPrimeiroPlano = "systemExempted"
                return
            } catch (e: Exception) {
                Log.w(TAG, "systemExempted recusado: ${e.message}")
            }
            try {
                startForeground(NOTIF_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                Painel.tipoPrimeiroPlano = "specialUse"
                return
            } catch (e: Exception) {
                Log.w(TAG, "specialUse recusado: ${e.message}")
            }
            Painel.tipoPrimeiroPlano = "nenhum"
        } else {
            try {
                startForeground(NOTIF_STATUS, n)
                Painel.tipoPrimeiroPlano = "padrão"
            } catch (e: Exception) {
                Log.w(TAG, "Primeiro plano recusado: ${e.message}")
                Painel.tipoPrimeiroPlano = "nenhum"
            }
        }
    }

    private fun notificar(texto: String, progresso: Int?) {
        textoNotificacao = texto
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_STATUS, montarNotificacao(texto, progresso))
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível atualizar a notificação: ${e.message}")
        }
    }

    private fun alertar(motivo: String) {
        try {
            val abrir = PendingIntent.getActivity(
                this, 3, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(this, CANAL_ALERTAS)
                .setContentTitle("Orbion VPN desligado")
                .setContentText(motivo.lineSequence().firstOrNull()?.take(120) ?: motivo)
                .setStyle(Notification.BigTextStyle().bigText(motivo.take(600)))
                .setSmallIcon(R.drawable.ic_vpn_status)
                .setAutoCancel(true)
                .setContentIntent(abrir)
                .build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ALERTA, n)
        } catch (_: Exception) {
        }
    }

    private fun formatarBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
    }
}
