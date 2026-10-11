package com.orbion.vpn.motor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.orbion.vpn.MainActivity
import com.orbion.vpn.R
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicInteger

/**
 * O serviço da VPN. Roda no processo próprio ":vpn", separado da tela.
 *
 * Por que separado: na 6.0 a tela (Flutter) e a VPN dividiam o mesmo
 * processo. Quando o Android fechava a tela — ao limpar os apps recentes, ou
 * no "O app não está respondendo → Fechar" — levava a VPN junto. Agora a tela
 * pode fechar, travar ou ser encerrada que a VPN continua.
 *
 * Regras deste arquivo:
 *  1. A linha principal só recebe o pedido e devolve na hora. Todo trabalho
 *     (ligar, desligar, montar interface, parar Tor/túnel) vai para a fila de
 *     controle — uma tarefa por vez, sem travas, sem "não está respondendo".
 *  2. Cada pedido novo incrementa a "geração". Uma tarefa de uma geração
 *     antiga, ao perceber, desiste sozinha.
 *  3. Se o Android derrubar o processo com a VPN ligada, ela volta sozinha:
 *     o próprio Android religa o serviço (START_STICKY / VPN sempre ativa),
 *     o vigia confere a cada 15 minutos, e a tela religa ao ser aberta.
 *  4. Entrar em primeiro plano nunca derruba o app (systemExempted →
 *     specialUse → segue).
 *  5. Com Kill Switch, a interface sobe ANTES do Tor: nada sai sem proteção.
 *     O que vai por fora (rota inteligente) já funciona durante a conexão.
 */
class OrbionVpnService : VpnService() {

    companion object {
        private const val TAG = "OrbionVpnService"

        const val ACAO_LIGAR = "com.orbion.vpn.START"
        const val ACAO_DESLIGAR = "com.orbion.vpn.STOP"
        const val ACAO_REINICIAR = "com.orbion.vpn.RESTART"
        const val ACAO_NOVA_IDENTIDADE = "com.orbion.vpn.NOVA_IDENTIDADE"
        const val EXTRA_ORIGEM = "origem"

        const val ORIGEM_TELA = "tela"
        const val ORIGEM_REABERTURA = "reabertura"
        const val ORIGEM_BOOT = "boot"
        const val ORIGEM_ATUALIZACAO = "atualizacao"
        const val ORIGEM_VIGIA = "vigia"
        const val ORIGEM_NOTIFICACAO = "notificacao"
        const val ORIGEM_SISTEMA = "sistema"
        const val ORIGEM_SEMPRE_ATIVA = "sempre-ativa"

        private const val CANAL_STATUS = "OrbionVPNChannel"
        private const val CANAL_ALERTAS = "OrbionAlertas"
        private const val NOTIF_STATUS = 1
        private const val NOTIF_ALERTA = 2
        private const val NOTIF_RELIGAR = 3

        private const val ENDERECO_VPN = "10.8.0.2"
        private const val ENDERECO_VPN_V6 = "fd00:6f72:6269:6f6e::1"
        private const val MTU = 8500

        private const val MSG_INTERFACE =
            "O Android recusou criar a interface de VPN. Normalmente é outra VPN já ativa no aparelho, ou a permissão de VPN não foi concedida."
        private const val MSG_TOR_CAIU =
            "A conexão com a rede Tor caiu (o processo do Tor foi encerrado). O tráfego que depende dela ficou bloqueado para não sair sem proteção."
        private const val MSG_TUNEL_PAROU = "O túnel parou de funcionar."

        /** Intervalos entre tentativas de reconexão do Kill Switch (segundos). */
        private val ESPERAS_RECONEXAO = longArrayOf(3, 10, 20, 40, 90, 180, 300)

        @Volatile var ativo: OrbionVpnService? = null
            private set

        /** Liga a VPN. Funciona a partir de qualquer processo do app. */
        fun ligar(context: Context, origem: String): Boolean = enviar(context, ACAO_LIGAR, origem)

        /** Reconecta do zero (Tor novo). */
        fun reiniciar(context: Context, origem: String): Boolean = enviar(context, ACAO_REINICIAR, origem)

        private fun enviar(context: Context, acao: String, origem: String): Boolean {
            val i = Intent(context, OrbionVpnService::class.java).setAction(acao).putExtra(EXTRA_ORIGEM, origem)
            return try {
                context.startForegroundService(i)
                true
            } catch (e: Exception) {
                // Android 12+: em segundo plano, sem isenção de bateria, o
                // sistema pode recusar. Quem chamou decide o que fazer.
                Log.e(TAG, "Não foi possível acionar o serviço ($acao/$origem): ${e.message}")
                false
            }
        }

        fun origemAutomatica(o: String) =
            o == ORIGEM_REABERTURA || o == ORIGEM_BOOT || o == ORIGEM_ATUALIZACAO ||
                o == ORIGEM_VIGIA || o == ORIGEM_SISTEMA || o == ORIGEM_SEMPRE_ATIVA

        fun descreverOrigem(o: String): String = when (o) {
            ORIGEM_TELA -> "pela tela do app"
            ORIGEM_REABERTURA -> "ao abrir o app"
            ORIGEM_BOOT -> "ao ligar o celular"
            ORIGEM_ATUALIZACAO -> "depois da atualização do app"
            ORIGEM_VIGIA -> "pelo vigia de 15 minutos"
            ORIGEM_NOTIFICACAO -> "pela notificação"
            ORIGEM_SISTEMA -> "pelo próprio Android"
            ORIGEM_SEMPRE_ATIVA -> "pela \"VPN sempre ativa\""
            else -> o
        }

        /** Notificação "toque para religar", quando o Android não deixa religar sozinho. */
        fun notificarParaReligar(context: Context) {
            try {
                criarCanais(context)
                val religar = PendingIntent.getForegroundService(
                    context, 9,
                    Intent(context, OrbionVpnService::class.java).setAction(ACAO_LIGAR).putExtra(EXTRA_ORIGEM, ORIGEM_NOTIFICACAO),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val n = Notification.Builder(context, CANAL_ALERTAS)
                    .setContentTitle("A VPN do Orbion foi fechada pelo Android")
                    .setContentText("Toque para religar a proteção.")
                    .setStyle(
                        Notification.BigTextStyle().bigText(
                            "O sistema do celular encerrou o Orbion e não deixou que ele voltasse sozinho. Toque para religar. " +
                                "Para isso não se repetir, abra o Orbion → Config → Manter o Orbion vivo.",
                        ),
                    )
                    .setSmallIcon(R.drawable.ic_vpn_status)
                    .setAutoCancel(true)
                    .setContentIntent(religar)
                    .addAction(Notification.Action.Builder(null, "Religar a VPN", religar).build())
                    .build()
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_RELIGAR, n)
            } catch (e: Exception) {
                Log.w(TAG, "Sem notificação de religar: ${e.message}")
            }
        }

        private fun criarCanais(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val status = NotificationChannel(CANAL_STATUS, "Status da VPN", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mostra se o Orbion VPN está conectado"
                setShowBadge(false)
            }
            val alertas = NotificationChannel(CANAL_ALERTAS, "Alertas do Orbion", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Avisa quando a VPN não conseguiu conectar ou foi fechada pelo sistema"
            }
            nm.createNotificationChannel(status)
            nm.createNotificationChannel(alertas)
        }
    }

    private enum class Modo { NOVA, REINICIO, SO_TOR }

    private val geracao = AtomicInteger(0)

    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var appsDaInterface: Set<String> = emptySet()
    @Volatile private var ipv6DaInterface = false
    @Volatile private var tor: TorManager? = null
    @Volatile private var filtro: FiltroSocks? = null

    @Volatile private var tarefaContador: ScheduledFuture<*>? = null
    @Volatile private var tarefaSupervisao: ScheduledFuture<*>? = null
    @Volatile private var tarefaReconexao: ScheduledFuture<*>? = null
    @Volatile private var tarefaTrocaDeRede: ScheduledFuture<*>? = null
    @Volatile private var tarefaReconstrucao: ScheduledFuture<*>? = null
    @Volatile private var vigiaDeRede: ConnectivityManager.NetworkCallback? = null
    private val redesVivas: MutableSet<Network> = Collections.newSetFromMap(ConcurrentHashMap())
    private val redesSemInternet: MutableSet<Network> = Collections.newSetFromMap(ConcurrentHashMap())
    /** A rede física que o Tor estava usando da última vez que (re)conectou. */
    @Volatile private var redeDoTor: Network? = null
    /** Sem rede nenhuma: o Tor está com DisableNetwork=1 até a internet voltar. */
    @Volatile private var torEmEspera = false
    @Volatile private var ultimoRecomecoDeRede = 0L

    @Volatile private var killSwitch = true
    @Volatile private var jaConectou = false
    @Volatile private var conectando = false
    @Volatile private var reconstruindo = false
    @Volatile private var usuarioPediuParar = false
    @Volatile private var tentativaDeReconexao = 0
    @Volatile private var origemAtual = ORIGEM_TELA
    @Volatile private var textoNotificacao = "Iniciando..."
    @Volatile private var ultimoAvisoDeMemoria = 0L
    /** Quantos pedidos de ligar chegaram (só a linha principal escreve). */
    @Volatile private var pedidosDeLigar = 0
    private var receptorDePacotes: BroadcastReceiver? = null

    // ------------------------------------------------------------------
    // Ciclo de vida (linha principal: só recebe e repassa)
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        ativo = this
        Painel.servicoAtivo = true
        criarCanais(this)
        entrarEmPrimeiroPlano(textoNotificacao, 0)
        registrarReceptorDePacotes()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Sempre confirma o primeiro plano: o Android exige isso depois de um
        // startForegroundService, e não custa nada nos outros casos.
        entrarEmPrimeiroPlano(textoNotificacao, null)
        val acao = intent?.action
        val origem = intent?.getStringExtra(EXTRA_ORIGEM)
        Log.d(TAG, "onStartCommand: ${acao ?: "(religado pelo sistema)"} origem=$origem")
        if (acao != ACAO_DESLIGAR && acao != ACAO_NOVA_IDENTIDADE) pedidosDeLigar++
        // Se um "desligar" chegar depois deste pedido e antes de ele rodar,
        // o pedido de ligar perde a vez (a geração muda).
        val gPedido = geracao.get()
        fun seAindaValer(tarefa: () -> Unit) = Filas.noControle { if (geracao.get() == gPedido) tarefa() }
        when {
            acao == ACAO_DESLIGAR -> {
                pedirDesligar("Desligada por você (pela notificação).")
                return START_NOT_STICKY
            }
            acao == ACAO_NOVA_IDENTIDADE -> pedirNovaIdentidade()
            acao == ACAO_REINICIAR -> seAindaValer { ligar(origem ?: ORIGEM_TELA, forcar = true) }
            acao == ACAO_LIGAR -> seAindaValer { ligar(origem ?: ORIGEM_TELA, forcar = false) }
            intent == null -> seAindaValer {
                // O Android religou o serviço depois de encerrar o processo.
                if (Preferencias(this).estavaLigada) {
                    ligar(ORIGEM_SISTEMA, forcar = false)
                } else {
                    pararServicoSeOcioso()
                }
            }
            // "VPN sempre ativa": o Android chama com a ação android.net.VpnService.
            else -> seAindaValer { ligar(ORIGEM_SEMPRE_ATIVA, forcar = false) }
        }
        Filas.avulso { atualizarInfoSempreAtiva() }
        return START_STICKY
    }

    override fun onRevoke() {
        Log.w(TAG, "Permissão de VPN revogada pelo sistema.")
        // Não chama super.onRevoke(): ele pararia o serviço antes de a fila
        // terminar de desmontar tudo na ordem certa.
        pedirDesligar(
            "A VPN foi desligada pelo Android: outra VPN foi ligada ou a permissão foi retirada.",
            erro = "A VPN foi desligada pelo Android: outra VPN foi ligada ou a permissão do Orbion foi retirada.",
        )
    }

    override fun onDestroy() {
        ativo = null
        Painel.servicoAtivo = false
        desregistrarReceptorDePacotes()
        if (vpnInterface != null || tor != null || filtro != null) {
            // Encerrado sem passar pelo desligamento normal: desmonta na fila.
            geracao.incrementAndGet()
            tor?.sinalizarParada()
            Filas.noControle {
                encerrarTudo()
                if (Painel.estado != Painel.Estado.ERRO) Painel.desconectado()
            }
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (Painel.estado == Painel.Estado.CONECTADO || Painel.estado == Painel.Estado.CONECTANDO) {
            Filas.avulso { Eventos.anotar(this, "A tela do Orbion foi fechada na tela de recentes. A VPN continua ligada.") }
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            FiltroSocks.aliviarMemoria()
            TorManager.aliviarMemoria()
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            val agora = System.currentTimeMillis()
            if (agora - ultimoAvisoDeMemoria > 10 * 60_000L) {
                ultimoAvisoDeMemoria = agora
                Filas.avulso { Eventos.anotar(this, "O aparelho está com pouca memória (nível $level). A VPN continua, mas o Android pode encerrar apps.") }
            }
        }
    }

    // ------------------------------------------------------------------
    // Pedidos (qualquer linha) → fila de controle
    // ------------------------------------------------------------------

    /** Desliga a pedido. [erro] != null mostra a tela de falha em vez de "desconectado". */
    fun pedirDesligar(motivo: String, erro: String? = null) {
        val pedidosNaDecisao = pedidosDeLigar
        usuarioPediuParar = true
        geracao.incrementAndGet()
        tor?.sinalizarParada()
        if (erro != null) Painel.falhou(erro) else Painel.desconectado()
        Painel.contornoDeBloqueio = null
        Painel.religamento = null
        Filas.noControle {
            Preferencias(this).estavaLigada = false
            Vigia.cancelar(this)
            encerrarTudo()
            if (erro != null) Painel.falhou(erro) else Painel.desconectado()
            Eventos.anotar(this, motivo)
            pararServicoNaPrincipal(pedidosNaDecisao)
        }
    }

    /** Configurações mudaram pela tela: aplica o que dá para aplicar ao vivo. */
    fun configMudou(chaves: Set<String>) {
        if (chaves.isEmpty()) return
        Filas.noControle {
            val prefs = Preferencias(this)
            killSwitch = prefs.killSwitch
            val f = filtro
            f?.atualizar(prefs.bloquearRastreadores, RotaInteligente.regras(prefs))
            if ("intervaloRotacaoSegundos" in chaves) {
                tor?.ajustarRotacao(prefs.intervaloRotacaoSegundos)
            }
            val mexeNosApps = chaves.any {
                it in setOf("rotaInteligente", "categoriasDesligadas", "appsForaDoTunel", "appsSempreNoTor", "capturarIPv6")
            }
            if (mexeNosApps) agendarReconstrucao("configuração alterada")
        }
    }

    private fun pedirNovaIdentidade() {
        Filas.avulso {
            val t = tor
            val r = if (t == null || Painel.estado != Painel.Estado.CONECTADO) {
                "Conecte primeiro para pedir uma nova identidade."
            } else {
                t.novaIdentidade()
            }
            Painel.avisoDeIdentidade = r ?: "Pronto! As próximas conexões já saem por outro caminho, com outro IP."
        }
    }

    /** Chamado pela ponte (botão "Nova identidade" da tela). */
    fun novaIdentidadePelaTela() = pedirNovaIdentidade()

    // ------------------------------------------------------------------
    // Ligar (fila de controle)
    // ------------------------------------------------------------------

    private fun ligar(origem: String, forcar: Boolean) {
        if (ativo !== this) {
            // Este serviço já foi encerrado enquanto o pedido esperava na
            // fila: entrega o pedido ao serviço novo.
            OrbionVpnService.ligar(applicationContext, origem)
            return
        }
        val prefs = Preferencias(this)
        prefs.estavaLigada = true
        usuarioPediuParar = false
        Vigia.agendar(this)
        cancelarNotificacaoDeReligar()
        if (!forcar) {
            val noAr = Painel.estado == Painel.Estado.CONECTADO && tor?.estaVivo() == true && TunelHev.rodando()
            if (noAr || conectando) {
                Log.d(TAG, "Já conectada ou conectando; pedido ($origem) ignorado.")
                return
            }
        }
        origemAtual = origem
        Eventos.anotar(this, (if (origemAutomatica(origem)) "Religando " else "Ligando ") + descreverOrigem(origem) + ".")
        val g = geracao.incrementAndGet()
        tor?.sinalizarParada()
        tarefaReconexao?.cancel(false)
        conectar(g, if (vpnInterface != null) Modo.REINICIO else Modo.NOVA, origem)
    }

    private fun conectar(g: Int, modo: Modo, origem: String) {
        conectando = true
        val inicio = SystemClock.elapsedRealtime()
        try {
            val prefs = Preferencias(this)
            killSwitch = prefs.killSwitch
            val sempreAtiva = sempreAtivaAgora()

            progresso(g, 0, "Iniciando...")
            if (!aguardarRede(20_000, g)) Log.w(TAG, "Sem rede após a espera. Tentando mesmo assim.")
            if (g != geracao.get()) return

            val soTor = modo == Modo.SO_TOR && vpnInterface != null && filtro != null && TunelHev.rodando()
            if (soTor) {
                pararSupervisao()
                tor?.stop()
                tor = null
            } else {
                pararPecas()
            }

            val fora = if (soTor) null else RotaInteligente.appsForaDoTor(this, prefs)
            val ipv6 = prefs.capturarIPv6
            if (g != geracao.get()) return

            // Fail-closed: com Kill Switch (ou VPN sempre ativa), interface,
            // filtro e túnel sobem ANTES do Tor. O que depende do Tor espera;
            // o que vai por fora (rota inteligente) já funciona.
            val segurarAntes = killSwitch || sempreAtiva
            if (!soTor && segurarAntes) {
                if (!montarTunel(g, fora!!.keys, ipv6, prefs, comecarBloqueado = true)) return
            }

            val t = TorManager(this)
            tor = t
            if (g != geracao.get()) {
                t.stop()
                return
            }
            val ok = t.startAndAwaitBootstrap(prefs) { pct, msg -> progresso(g, pct, msg) }
            if (g != geracao.get()) {
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

            if (soTor || segurarAntes) {
                filtro?.sairDoModoKillSwitch()
            } else {
                progresso(g, 100, "Criando túnel...")
                if (!montarTunel(g, fora!!.keys, ipv6, prefs, comecarBloqueado = false)) return
            }
            if (g != geracao.get()) return

            jaConectou = true
            tentativaDeReconexao = 0
            // Tor novo, conexões novas: a referência de rede recomeça daqui.
            redeDoTor = redePadrao()
            torEmEspera = false
            Painel.semInternet = false
            FiltroSocks.redeEmEspera = false
            Painel.conectado()
            if (origemAutomatica(origem) && origem != ORIGEM_BOOT) {
                val f = Inicio.consumirFechamento()
                Painel.religamento = if (f != null && System.currentTimeMillis() - f.quando < 6 * 3600_000L) {
                    "O Android encerrou a VPN em ${Eventos.hora(f.quando)} (${f.explicacao}). Ela foi religada sozinha."
                } else {
                    "A VPN tinha sido encerrada pelo sistema e foi religada sozinha (${descreverOrigem(origem)})."
                }
            }
            val segundos = (SystemClock.elapsedRealtime() - inicio) / 1000
            Eventos.anotar(
                this,
                "Conectada em ${segundos}s por ${Transportes.nome(t.caminhoEmUso)}" +
                    (if (fora != null) "; ${fora.size} app(s) por fora do Tor." else "."),
            )
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
        } finally {
            conectando = false
        }
    }

    /** Interface + filtro + túnel. false = já tratou a falha. */
    private fun montarTunel(g: Int, fora: Set<String>, ipv6: Boolean, prefs: Preferencias, comecarBloqueado: Boolean): Boolean {
        if (!garantirInterface(g, fora, ipv6)) {
            if (g == geracao.get()) falhar(g, MSG_INTERFACE, fatal = true)
            return false
        }
        if (filtro == null) {
            val f = FiltroSocks(this, TorManager.SOCKS_PORT)
            if (f.iniciar(prefs.bloquearRastreadores, RotaInteligente.regras(prefs), comecarBloqueado)) {
                filtro = f
            } else {
                Log.w(TAG, "Filtro não subiu. Seguindo direto para o Tor.")
            }
        }
        if (!TunelHev.rodando()) {
            val fd = vpnInterface?.fd ?: run {
                falhar(g, "A interface da VPN fechou durante a conexão.", fatal = true)
                return false
            }
            val porta = if (filtro != null) FiltroSocks.PORTA else TorManager.SOCKS_PORT
            val erro = TunelHev.iniciar(this, fd, porta, MTU)
            if (erro != null) {
                falhar(g, erro, fatal = true)
                return false
            }
        }
        return true
    }

    private fun progresso(g: Int, pct: Int, msg: String) {
        if (g != geracao.get()) return
        Painel.conectando(pct, msg)
        notificar(msg, pct)
    }

    /** Cria a interface se não existir, ou recria se a lista de apps mudou. */
    private fun garantirInterface(g: Int, fora: Set<String>, ipv6: Boolean): Boolean {
        if (g != geracao.get()) return false
        val atual = vpnInterface
        if (atual != null && fora == appsDaInterface && ipv6 == ipv6DaInterface) return true
        val nova = criarInterface(fora, ipv6) ?: return false
        if (atual != null && atual !== nova) fechar(atual)
        vpnInterface = nova
        appsDaInterface = fora
        ipv6DaInterface = ipv6
        Painel.appsForaNaSessao = fora
        acompanharTrocasDeRede()
        return true
    }

    private fun criarInterface(fora: Set<String>, ipv6: Boolean): ParcelFileDescriptor? {
        return try {
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
            if (ipv6) {
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
            // Sem setUnderlyingNetworks: a VPN acompanha sozinha a rede padrão
            // do aparelho (o próprio Android atualiza quando ela muda).
            b.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar a interface de VPN: ${e.message}", e)
            null
        }
    }

    // ------------------------------------------------------------------
    // Rotas de apps aplicadas ao vivo (sem reconectar o Tor)
    // ------------------------------------------------------------------

    private fun agendarReconstrucao(motivo: String) {
        // 2,5 s de espera: quem está mexendo em vários apps seguidos vê a
        // internet piscar uma vez só, no fim.
        tarefaReconstrucao?.cancel(false)
        tarefaReconstrucao = Filas.depois(2_500) { Filas.noControle { reconstruirInterface(motivo) } }
    }

    private fun reconstruirInterface(motivo: String) {
        if (vpnInterface == null || usuarioPediuParar) return
        if (conectando) {
            // A conexão em andamento já vai usar a lista nova quando terminar? Não:
            // ela leu a lista no começo. Tenta de novo daqui a pouco.
            agendarReconstrucao(motivo)
            return
        }
        val prefs = Preferencias(this)
        val fora = RotaInteligente.appsForaDoTor(this, prefs)
        val ipv6 = prefs.capturarIPv6
        if (fora.keys == appsDaInterface && ipv6 == ipv6DaInterface) return
        reconstruindo = true
        Painel.aplicandoRotas = true
        try {
            val antiga = vpnInterface
            val nova = criarInterface(fora.keys, ipv6)
            if (nova == null) {
                Eventos.anotar(this, "Não foi possível aplicar as rotas novas agora; valem na próxima conexão.")
                return
            }
            vpnInterface = nova
            appsDaInterface = fora.keys
            ipv6DaInterface = ipv6
            Painel.appsForaNaSessao = fora.keys
            TunelHev.parar()
            val porta = if (filtro != null) FiltroSocks.PORTA else TorManager.SOCKS_PORT
            var erro = TunelHev.iniciar(this, nova.fd, porta, MTU)
            if (erro != null) {
                // Uma segunda chance antes de acionar o Kill Switch.
                Log.w(TAG, "Túnel não voltou na troca de rotas ($erro). Tentando de novo.")
                Thread.sleep(600)
                erro = TunelHev.iniciar(this, nova.fd, porta, MTU)
            }
            if (antiga != null && antiga !== nova) fechar(antiga)
            if (erro != null) {
                reconstruindo = false
                Eventos.anotar(this, "O túnel não voltou depois da troca de rotas: ${erro.lineSequence().firstOrNull() ?: erro}")
                falhar(geracao.get(), erro, fatal = false)
                return
            }
            Eventos.anotar(this, "Rotas aplicadas ($motivo): ${fora.size} app(s) por fora do Tor.")
        } finally {
            reconstruindo = false
            Painel.aplicandoRotas = false
        }
    }

    private fun registrarReceptorDePacotes() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                val pacote = i.data?.schemeSpecificPart ?: return
                val servico = this@OrbionVpnService
                Filas.noControle {
                    if (vpnInterface == null || usuarioPediuParar) return@noControle
                    val motivo = RotaInteligente.appNovoFicaFora(servico, Preferencias(servico), pacote) ?: return@noControle
                    Eventos.anotar(servico, "App instalado vai por fora do Tor: $motivo.")
                    agendarReconstrucao("app instalado")
                }
            }
        }
        val filtroDeIntents = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(r, filtroDeIntents, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(r, filtroDeIntents)
            }
            receptorDePacotes = r
        } catch (e: Exception) {
            Log.w(TAG, "Sem aviso de apps instalados: ${e.message}")
        }
    }

    private fun desregistrarReceptorDePacotes() {
        val r = receptorDePacotes ?: return
        receptorDePacotes = null
        try {
            unregisterReceiver(r)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // Falhas e Kill Switch (fila de controle)
    // ------------------------------------------------------------------

    private fun falhar(g: Int, motivo: String, fatal: Boolean) {
        if (g != geracao.get()) return
        val sempreAtiva = sempreAtivaAgora()
        val automatica = origemAutomatica(origemAtual)
        // Segura (bloqueia e tenta de novo) em vez de desistir quando: já
        // tinha conectado, ou foi religada sozinha (a pessoa não está olhando),
        // ou o Android está com "VPN sempre ativa".
        val segurar = !fatal && !usuarioPediuParar && vpnInterface != null &&
            (killSwitch || sempreAtiva) && (jaConectou || automatica || sempreAtiva)
        if (segurar) {
            acionarKillSwitch(motivo)
            return
        }
        Log.e(TAG, motivo)
        Preferencias(this).estavaLigada = false
        Vigia.cancelar(this)
        Eventos.anotar(this, "Falha: ${motivo.lineSequence().firstOrNull() ?: motivo}")
        alertar(motivo)
        encerrarTudo()
        Painel.falhou(motivo)
        pararServicoNaPrincipal(pedidosDeLigar)
    }

    private fun acionarKillSwitch(motivo: String) {
        Log.w(TAG, "KILL SWITCH: $motivo")
        pararSupervisao()
        tor?.stop()
        tor = null
        filtro?.entrarEmModoKillSwitch()
        if (jaConectou) Painel.quedasDoTor++
        Painel.bloqueado(motivo)
        notificar("Rede Tor fora do ar — protegendo o tráfego e reconectando...", null)
        Eventos.anotar(this, "Kill Switch: ${motivo.lineSequence().firstOrNull() ?: motivo}")
        agendarReconexao()
    }

    private fun agendarReconexao() {
        val i = tentativaDeReconexao.coerceAtMost(ESPERAS_RECONEXAO.size - 1)
        val espera = ESPERAS_RECONEXAO[i]
        tentativaDeReconexao++
        Log.d(TAG, "Kill Switch: nova tentativa em ${espera}s")
        tarefaReconexao?.cancel(false)
        tarefaReconexao = Filas.depois(espera * 1000) {
            Filas.noControle {
                if (Painel.estado != Painel.Estado.BLOQUEADO || usuarioPediuParar) return@noControle
                if (!temRede()) {
                    Log.d(TAG, "Kill Switch: ainda sem rede.")
                    agendarReconexao()
                    return@noControle
                }
                val g = geracao.incrementAndGet()
                val modo = if (filtro != null && TunelHev.rodando()) Modo.SO_TOR else Modo.REINICIO
                conectar(g, modo, origemAtual)
            }
        }
    }

    // ------------------------------------------------------------------
    // Limpeza (fila de controle)
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
        tarefaReconstrucao?.cancel(false)
        pararPecas()
        pararDeAcompanharRede()
        vpnInterface?.let { fechar(it) }
        vpnInterface = null
        appsDaInterface = emptySet()
        jaConectou = false
        tentativaDeReconexao = 0
        Painel.zerarSessao()
    }

    private fun fechar(pfd: ParcelFileDescriptor) {
        try {
            pfd.close()
        } catch (_: Exception) {
        }
    }

    /**
     * Encerra o serviço — só se nenhum pedido de ligar chegou depois da
     * decisão de parar (os dois lados são conferidos na linha principal, sem
     * corrida). Encerrar o serviço já tira a notificação.
     */
    private fun pararServicoNaPrincipal(pedidosNaDecisao: Int) {
        Filas.naPrincipal {
            if (pedidosDeLigar != pedidosNaDecisao) return@naPrincipal
            stopSelf()
        }
    }

    private fun pararServicoSeOcioso() {
        if (vpnInterface == null && !conectando) pararServicoNaPrincipal(pedidosDeLigar)
    }

    // ------------------------------------------------------------------
    // Vigilância (agenda)
    // ------------------------------------------------------------------

    private fun iniciarSupervisao() {
        tarefaSupervisao?.cancel(false)
        var voltas = 0
        tarefaSupervisao = Filas.repetir(5_000, 5_000) {
            if (Painel.estado != Painel.Estado.CONECTADO) return@repetir
            // A conferência roda na fila de controle: assim ela nunca enxerga
            // o túnel "parado" no meio de uma troca de rotas (na 6.1.0 isso
            // acionava o Kill Switch à toa e reiniciava o Tor).
            Filas.noControle { verificarSaude() }
            voltas++
            if (voltas % 12 == 0) {
                TunelHev.controlarTamanhoDoLog(this)
                atualizarInfoSempreAtiva()
            }
        }
    }

    /** Tor e túnel estão de pé? (fila de controle) */
    private fun verificarSaude() {
        if (Painel.estado != Painel.Estado.CONECTADO || reconstruindo || usuarioPediuParar) return
        val problema = when {
            tor?.estaVivo() != true -> MSG_TOR_CAIU
            !TunelHev.rodando() -> MSG_TUNEL_PAROU
            else -> return
        }
        falhar(geracao.get(), problema, fatal = false)
    }

    private fun telaLigada(): Boolean = try {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    } catch (_: Exception) {
        true
    }

    private fun iniciarContador() {
        tarefaContador?.cancel(false)
        var ultimoEnvio = -1L
        var ultimoReceb = -1L
        var ultimoInstante = 0L
        var voltas = 0
        tarefaContador = Filas.repetir(0, 2_000) {
            val s = TunelHev.estatisticas() ?: return@repetir
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
            // Notificação a cada ~4 s, e só com a tela ligada: com a tela
            // apagada ninguém vê, e redesenhar a barra gasta bateria.
            if (voltas % 2 == 0 && Painel.estado == Painel.Estado.CONECTADO && telaLigada()) {
                val fora = appsDaInterface.size
                notificar(
                    "↑ ${formatarBytes(Painel.velocidadeEnvio)}/s  ↓ ${formatarBytes(Painel.velocidadeRecebimento)}/s  •  " +
                        "${formatarBytes(s.enviadoBytes + s.recebidoBytes)} na sessão" +
                        if (fora > 0) "  •  $fora app(s) por fora" else "",
                    null,
                )
            }
        }
    }

    private fun sempreAtivaAgora(): Boolean = Build.VERSION.SDK_INT >= 29 && try {
        isAlwaysOn
    } catch (_: Exception) {
        false
    }

    /** Lê do Android se o Orbion está como "VPN sempre ativa" e guarda o resultado. */
    private fun atualizarInfoSempreAtiva() {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val ligada = isAlwaysOn
            Painel.vpnSempreAtiva = ligada
            Painel.bloqueioSemVpn = isLockdownEnabled
            Preferencias(this).sempreAtivaConhecida = if (ligada) 1 else 0
        } catch (_: Exception) {
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
            if (g != geracao.get()) return false
            if (temRede()) return true
            if (!avisou) {
                progresso(g, 0, "Aguardando a rede do aparelho...")
                avisou = true
            }
            Thread.sleep(500)
        }
        return false
    }

    /**
     * Acompanha as redes FÍSICAS do aparelho (Wi-Fi, dados móveis...).
     *
     * Na 6.1.0 qualquer troca da "rede padrão" desligava e religava a rede do
     * Tor na hora. O registro técnico mostrou isso acontecendo a cada 2–8 s
     * (troca de apps na aba Apps / Wi-Fi oscilando): o Tor derrubava todas as
     * conexões sem parar. Agora:
     *  - só redes de verdade contam (NOT_VPN): mexer na própria VPN não é
     *    "troca de rede";
     *  - o Tor só é refeito se a rede que ELE usava sumiu ou ficou sem
     *    internet, e existe outra boa; espera 3 s a rede assentar e no máximo
     *    uma vez a cada 30 s;
     *  - sem rede nenhuma, o Tor fica em espera (economiza bateria) e volta
     *    sozinho quando a internet voltar.
     */
    private fun acompanharTrocasDeRede() {
        if (vigiaDeRede != null) return
        val cm = conectividade()
        val pedido = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val vigia = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                redesVivas.add(network)
                agendarAvaliacaoDeRede()
            }

            override fun onLost(network: Network) {
                redesVivas.remove(network)
                redesSemInternet.remove(network)
                // As conexões que estavam nessa rede vão falhar: não é culpa
                // dos sites, então não pausam nem desviam ninguém.
                FiltroSocks.perdoarFalhas(45_000)
                agendarAvaliacaoDeRede()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Só interessa quando a rede ganha ou perde internet de verdade
                // (o sinal do celular muda o tempo todo e não conta).
                val semInternet = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val mudou = if (semInternet) redesSemInternet.add(network) else redesSemInternet.remove(network)
                if (mudou) {
                    FiltroSocks.perdoarFalhas(45_000)
                    agendarAvaliacaoDeRede()
                }
            }
        }
        try {
            redeDoTor = redePadrao()
            cm.registerNetworkCallback(pedido, vigia)
            vigiaDeRede = vigia
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível acompanhar trocas de rede: ${e.message}")
        }
    }

    /** A rede física que o Android usa agora para o Orbion (nunca uma VPN). */
    private fun redePadrao(): Network? = try {
        val cm = conectividade()
        cm.activeNetwork?.takeIf { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true }
            ?: redesVivas.firstOrNull { it !in redesSemInternet }
            ?: redesVivas.firstOrNull()
    } catch (_: Exception) {
        null
    }

    private fun descreverRede(rede: Network?): String {
        val caps = try {
            rede?.let { conectividade().getNetworkCapabilities(it) }
        } catch (_: Exception) {
            null
        } ?: return "outra rede"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "dados móveis"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "cabo"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "outra rede"
        }
    }

    private fun agendarAvaliacaoDeRede(esperaMs: Long = 3_000) {
        tarefaTrocaDeRede?.cancel(false)
        tarefaTrocaDeRede = Filas.depois(esperaMs) { Filas.noControle { avaliarRede() } }
    }

    /** Decide se o Tor precisa refazer as conexões (fila de controle). */
    private fun avaliarRede() {
        val t = tor ?: return
        if (Painel.estado != Painel.Estado.CONECTADO) return
        if (redesVivas.isEmpty()) {
            if (!torEmEspera) {
                torEmEspera = true
                Painel.semInternet = true
                FiltroSocks.redeEmEspera = true
                t.pausarRede()
                Eventos.anotar(this, "O aparelho ficou sem internet. A rede Tor ficou em espera (poupa bateria) e volta sozinha.")
            }
            return
        }
        val padrao = redePadrao()
        if (torEmEspera) {
            torEmEspera = false
            Painel.semInternet = false
            FiltroSocks.perdoarFalhas(30_000)
            FiltroSocks.redeEmEspera = false
            // O que falhou sem internet ganha outra chance.
            FiltroSocks.disjuntor.limpar()
            t.retomarRede()
            redeDoTor = padrao
            ultimoRecomecoDeRede = System.currentTimeMillis()
            ResolvedorDoH.limparCache()
            Eventos.anotar(this, "A internet voltou (${descreverRede(padrao)}). A rede Tor retomou.")
            return
        }
        val doTor = redeDoTor
        val torSemRede = doTor == null || doTor !in redesVivas ||
            (doTor in redesSemInternet && padrao != null && padrao != doTor && padrao !in redesSemInternet)
        if (!torSemRede || padrao == null) return
        val falta = ultimoRecomecoDeRede + 30_000 - System.currentTimeMillis()
        if (falta > 0) {
            agendarAvaliacaoDeRede(falta)
            return
        }
        ultimoRecomecoDeRede = System.currentTimeMillis()
        redeDoTor = padrao
        FiltroSocks.perdoarFalhas(30_000)
        val r = t.redeMudou()
        ResolvedorDoH.limparCache()
        Eventos.anotar(this, "Rede trocada para ${descreverRede(padrao)}: conexões da rede Tor refeitas" + (if (r != null) " ($r)." else "."))
    }

    private fun pararDeAcompanharRede() {
        tarefaTrocaDeRede?.cancel(false)
        val v = vigiaDeRede ?: return
        try {
            conectividade().unregisterNetworkCallback(v)
        } catch (_: Exception) {
        }
        vigiaDeRede = null
        redesVivas.clear()
        redesSemInternet.clear()
        redeDoTor = null
        torEmEspera = false
        Painel.semInternet = false
        FiltroSocks.redeEmEspera = false
    }

    // ------------------------------------------------------------------
    // Notificações
    // ------------------------------------------------------------------

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

    private fun cancelarNotificacaoDeReligar() {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_RELIGAR)
        } catch (_: Exception) {
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
