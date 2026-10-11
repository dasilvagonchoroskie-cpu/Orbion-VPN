package com.orbion.vpn.motor

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Filtro SOCKS5 entre o túnel e o Tor (127.0.0.1:19055). É aqui que mora a
 * parte "por site" da rota inteligente.
 *
 * Para cada conexão, nesta ordem:
 *   1. RECUSADO  — rastreador (se o bloqueio estiver ligado);
 *   2. DESVIADO  — site da sua lista, de uma categoria ligada (banco, gov.br,
 *                  redes...) ou que já recusou o Tor nesta sessão: sai direto,
 *                  SÓ com criptografia (porta 80 nunca sai por fora);
 *   3. PAUSADO   — endereço que falhou 3 vezes seguidas pelo Tor: o Orbion
 *                  para de insistir por um tempo que vai dobrando (30 s, 1 min,
 *                  2 min... até 10 min). Se for um site e o desvio automático
 *                  estiver ligado, ele sai direto em vez de falhar;
 *   4. PELO TOR  — todo o resto.
 *
 * O disjuntor (item 3) é o que impede um app teimoso de bombardear o Tor
 * com milhares de tentativas por minuto — isso pesava a bateria e fazia o
 * Android encerrar o Orbion em segundo plano.
 */
class FiltroSocks(private val context: Context, private val portaDoTor: Int) {

    enum class Destino { TOR, DIRETO, BLOQUEADO, PAUSADO }

    class Registro(val dominio: String, val destino: Destino, val quando: Long, val motivo: String?)

    companion object {
        private const val TAG = "OrbionFiltro"
        const val PORTA = 19055
        /**
         * Teto de linhas (threads) do filtro. Cada conexão aberta usa duas.
         * Na 6.1.1 o teto era 900 e o celular não aguentava: na troca de rede
         * todos os apps reconectam ao mesmo tempo, o Android recusava criar
         * mais linhas e o processo da VPN caía ("erro interno", 2× em 1 min).
         * O túnel (hev) também foi limitado a 224 conexões simultâneas.
         */
        private const val MAX_THREADS = 480
        /** No máximo tantas conexões esperando o Tor responder; as demais falham na hora. */
        private const val MAX_ESPERANDO_O_TOR = 96
        /** Saúde da rede Tor: janela para contar sucessos e falhas. */
        private const val JANELA_SAUDE_MS = 120_000L
        /** Uma falha só é culpa do destino se o Tor funcionou para outro há pouco. */
        private const val SUCESSO_RECENTE_MS = 60_000L
        /** Destinos diferentes falhando juntos, sem nenhum sucesso: a rede está instável. */
        private const val DESTINOS_PARA_INSTAVEL = 4
        private const val OCIOSO_MS = 300_000
        private const val MAX_REGISTROS = 300
        /** Quanto esperar o Tor responder se alcançou o destino. */
        private const val ESPERA_RESPOSTA_TOR_MS = 25_000
        /** Fechou sem o servidor mandar nada, antes disto: conta como recusa. */
        private const val RECUSA_SILENCIOSA_MS = 30_000L

        private val bloqueados = AtomicLong(0)
        private val pelaRedeTor = AtomicLong(0)
        private val diretos = AtomicLong(0)
        private val pausados = AtomicLong(0)
        private val ipv6Recusados = AtomicLong(0)
        private val reservadosRecusados = AtomicLong(0)
        /** Parte dos reservados: endereço interno de um início anterior do túnel. */
        private val enderecosAntigosDoTunel = AtomicLong(0)
        /** Parte dos reservados: rede local (roteador, impressora, TV...). */
        private val redeLocalRecusada = AtomicLong(0)
        private val semCriptografiaBarrados = AtomicLong(0)
        private val recusadosPorLotacao = AtomicLong(0)
        private val ultimos = ArrayDeque<Registro>()

        val disjuntor = Disjuntor()

        /**
         * O aparelho está sem internet (o Tor ficou em espera). As conexões
         * pela rede Tor falham na hora, em vez de prender o app por 25 s, e
         * nada conta como "recusou o Tor": a culpa não é do destino.
         */
        @Volatile var redeEmEspera = false

        /** Até quando as falhas pelo Tor não contam (a rede caiu, voltou ou trocou). */
        @Volatile private var semCulpaAte = 0L

        /**
         * A rede do aparelho mudou: por um tempo, falhas pelo Tor não pausam
         * destinos nem desviam sites. Na 6.1.0, quedas de rede faziam sites
         * normais serem desviados para fora do Tor como se recusassem a rede.
         */
        fun perdoarFalhas(ms: Long) {
            semCulpaAte = maxOf(semCulpaAte, System.currentTimeMillis() + ms)
        }

        private fun falhaConta(): Boolean = !redeEmEspera && System.currentTimeMillis() >= semCulpaAte

        // ------------------------------------------------------------------
        // Saúde da rede Tor, medida pelas próprias conexões.
        //
        // Na 6.1.2, com dados móveis fracos, o Tor demorava a responder e cada
        // site que falhava 3 vezes era "pausado" (e às vezes desviado para fora
        // do Tor) como se ELE recusasse o Tor — uma enxurrada de PAUSADO na aba
        // Registros, um "loop de erros". Agora uma falha só pesa contra o site
        // se o Tor acabou de funcionar para outro; quando vários sites falham
        // juntos e nada funciona, é a rede: o Painel mostra "Conexão instável"
        // e ninguém é pausado nem desviado.
        // ------------------------------------------------------------------

        /** Falhas seguidas de um destino: desde quando (início da 1ª tentativa) e quantas. */
        private class FalhasDoDestino(val desde: Long, @Volatile var vezes: Int, @Volatile var ultima: Long)

        private val falhasRecentes = ConcurrentHashMap<String, FalhasDoDestino>()
        private val janela = JanelaDeSaude(JANELA_SAUDE_MS)
        @Volatile private var ultimoSucessoPeloTor = 0L
        @Volatile private var instavelDesde = 0L

        /**
         * A rede Tor está falhando para quase todos agora (rede fraca/instável):
         * ou vários destinos falharam sem nada funcionar desde que começaram, ou
         * menos de 30% das conexões deram certo nos últimos 2 minutos.
         */
        fun torInstavel(): Boolean = torInstavelEm(System.currentTimeMillis())

        private fun torInstavelEm(agora: Long): Boolean {
            if (instavelDesde != 0L) return true
            val (ok, ruim) = janela.contar(agora)
            return ok + ruim >= 10 && ok * 10 < (ok + ruim) * 3
        }

        private fun sucessoPeloTor(chave: String, agora: Long) {
            ultimoSucessoPeloTor = agora
            janela.anotar(agora, true)
            falhasRecentes.remove(chave)
            if (instavelDesde != 0L) {
                // Voltou a funcionar depois de tudo falhar: começa a medir do zero.
                instavelDesde = 0L
                falhasRecentes.clear()
                janela.limpar()
                janela.anotar(agora, true)
            }
        }

        /**
         * Registra a falha (a tentativa começou em [inicio]) e diz se a culpa
         * parece ser do DESTINO: o Tor funcionou há pouco e acerta pelo menos
         * 70% das conexões dos outros destinos.
         */
        private fun culpaDoDestino(chave: String, inicio: Long, agora: Long): Boolean {
            janela.anotar(agora, false)
            val f = falhasRecentes.compute(chave) { _, v ->
                if (v == null || agora - v.ultima > JANELA_SAUDE_MS) {
                    FalhasDoDestino(inicio, 1, agora)
                } else {
                    v.vezes++
                    v.ultima = agora
                    v
                }
            }!!
            if (falhasRecentes.size > 200) falhasRecentes.entries.removeIf { agora - it.value.ultima > JANELA_SAUDE_MS }
            // Destinos que começaram a falhar depois do último sucesso.
            val semNadaFuncionar = falhasRecentes.values.count { it.desde > ultimoSucessoPeloTor && agora - it.ultima <= JANELA_SAUDE_MS }
            if (semNadaFuncionar >= DESTINOS_PARA_INSTAVEL && instavelDesde == 0L) instavelDesde = agora
            if (instavelDesde != 0L) return false
            if (ultimoSucessoPeloTor == 0L || agora - ultimoSucessoPeloTor > SUCESSO_RECENTE_MS) return false
            val (ok, ruim) = janela.contar(agora)
            val ruimDosOutros = (ruim - f.vezes).coerceAtLeast(0)
            return ok >= 3 && ok * 10 >= (ok + ruimDosOutros) * 7
        }

        /** Rede trocada ou de volta: começa a medir de novo. */
        fun recomecarSaude() {
            falhasRecentes.clear()
            janela.limpar()
            instavelDesde = 0L
        }

        /** Sites desviados sozinhos nesta sessão: domínio-base → quando. */
        private val desviadosAuto = ConcurrentHashMap<String, Long>()

        @Synchronized
        private fun anotar(r: Registro) {
            // Um app teimoso repete o mesmo endereço dezenas de vezes: junta
            // as repetições seguidas numa linha só, para a lista continuar útil.
            val topo = ultimos.firstOrNull()
            if (topo != null && topo.dominio == r.dominio && topo.destino == r.destino && r.quando - topo.quando < 10_000) {
                ultimos.removeFirst()
            }
            ultimos.addFirst(r)
            while (ultimos.size > MAX_REGISTROS) ultimos.removeLast()
        }

        @Synchronized
        fun registros(max: Int): List<Registro> = ultimos.take(max)

        @Synchronized
        fun aliviarMemoria() {
            while (ultimos.size > 60) ultimos.removeLast()
        }

        @Synchronized
        fun zerar() {
            bloqueados.set(0); pelaRedeTor.set(0); diretos.set(0); pausados.set(0)
            ipv6Recusados.set(0); reservadosRecusados.set(0); semCriptografiaBarrados.set(0)
            enderecosAntigosDoTunel.set(0); redeLocalRecusada.set(0)
            recusadosPorLotacao.set(0)
            ultimos.clear()
            desviadosAuto.clear()
            disjuntor.limpar()
            redeEmEspera = false
            semCulpaAte = 0L
            falhasRecentes.clear()
            janela.limpar()
            instavelDesde = 0L
            ultimoSucessoPeloTor = 0L
        }

        fun contagem(): Map<String, Long> = mapOf(
            "bloqueados" to bloqueados.get(),
            "pelaRedeTor" to pelaRedeTor.get(),
            "diretos" to diretos.get(),
            "pausados" to pausados.get(),
            "ipv6Recusados" to ipv6Recusados.get(),
            "reservadosRecusados" to reservadosRecusados.get(),
            "reservadosAntigosDoTunel" to enderecosAntigosDoTunel.get(),
            "reservadosRedeLocal" to redeLocalRecusada.get(),
            "semCriptografiaBarrados" to semCriptografiaBarrados.get(),
            "recusadosPorLotacao" to recusadosPorLotacao.get(),
        )

        fun desviadosAutomaticamente(): List<Map<String, Any?>> =
            desviadosAuto.entries.sortedByDescending { it.value }.map {
                mapOf(
                    "dominio" to it.key,
                    "quando" to it.value,
                    "categoria" to RotaInteligente.categoriaDoDominio(it.key)?.titulo,
                )
            }

        fun quantosDesviadosAuto(): Int = desviadosAuto.size

        /** A pessoa decidiu a rota deste site: sai da lista automática. */
        fun esquecerDesvio(dominio: String) {
            desviadosAuto.remove(RotaInteligente.dominioBase(dominio))
        }
    }

    @Volatile private var bloqueioAtivo = true
    @Volatile private var regras: RotaInteligente.Regras = RotaInteligente.Regras.VAZIA
    @Volatile private var modoKillSwitch = false
    @Volatile private var listaDeBloqueio: Set<String> = emptySet()
    @Volatile private var servidor: ServerSocket? = null

    private val contadorDeThreads = AtomicInteger(0)
    private val fabrica = ThreadFactory { r ->
        // Pilha de 128 KB: o encanamento de bytes não precisa de mais que isso.
        Thread(null, r, "orbion-filtro-${contadorDeThreads.incrementAndGet()}", 128 * 1024L).apply { isDaemon = true }
    }
    private val pool = ThreadPoolExecutor(
        4, MAX_THREADS, 30L, TimeUnit.SECONDS, SynchronousQueue(), fabrica,
        ThreadPoolExecutor.AbortPolicy(),
    )

    /**
     * [comecarBloqueado] = true quando o filtro sobe antes do Tor: o que vai
     * por fora já funciona, e o que depende do Tor espera ele ficar pronto.
     */
    fun iniciar(comBloqueio: Boolean, regras: RotaInteligente.Regras, comecarBloqueado: Boolean): Boolean {
        bloqueioAtivo = comBloqueio
        this.regras = regras
        modoKillSwitch = comecarBloqueado
        listaDeBloqueio = carregarLista()
        zerar()
        ResolvedorDoH.limparCache()
        return try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORTA), 256)
            servidor = s
            Log.d(TAG, "Filtro em 127.0.0.1:$PORTA (bloqueio=$comBloqueio, inteligente=${regras.inteligente}, ${listaDeBloqueio.size} rastreadores)")
            val t = Thread({ lacoDeAceitacao(s) }, "orbion-filtro-aceite")
            t.isDaemon = true
            t.start()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Não foi possível subir o filtro: ${e.message}")
            servidor = null
            false
        }
    }

    /** Troca as regras ao vivo, sem derrubar nenhuma conexão. */
    fun atualizar(comBloqueio: Boolean, novas: RotaInteligente.Regras) {
        bloqueioAtivo = comBloqueio
        regras = novas
    }

    fun entrarEmModoKillSwitch() {
        modoKillSwitch = true
    }

    fun sairDoModoKillSwitch() {
        modoKillSwitch = false
    }

    fun parar() {
        try {
            servidor?.close()
        } catch (_: Exception) {
        }
        servidor = null
        pool.shutdownNow()
    }

    private fun lacoDeAceitacao(s: ServerSocket) {
        while (!s.isClosed) {
            val cliente = try {
                s.accept()
            } catch (e: Throwable) {
                if (s.isClosed) return
                Log.w(TAG, "Erro ao aceitar: ${e.message}")
                try {
                    Thread.sleep(50) // não gira em falso se o erro se repetir
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            try {
                pool.execute { atender(cliente) }
            } catch (e: Throwable) {
                // Lotado, ou o Android não deixou criar mais uma linha
                // (OutOfMemoryError: pthread_create): recusa esta conexão em
                // vez de deixar o processo da VPN cair.
                recusadosPorLotacao.incrementAndGet()
                fechar(cliente)
            }
        }
    }

    /** Conexões esperando a resposta do Tor agora. */
    private val esperandoOTor = AtomicInteger(0)

    private fun atender(cliente: Socket) {
        var remoto: Socket? = null
        var esperando = false
        try {
            cliente.soTimeout = 30_000
            cliente.tcpNoDelay = true
            val entrada = DataInputStream(cliente.getInputStream())
            val saida = cliente.getOutputStream()

            // Saudação SOCKS5: versão, nº de métodos, métodos. Respondemos "sem autenticação".
            if (entrada.read() != 5) return
            val nMetodos = entrada.read()
            if (nMetodos <= 0) return
            entrada.skipBytes(nMetodos)
            saida.write(byteArrayOf(5, 0)); saida.flush()

            val cab = ByteArray(4)
            entrada.readFully(cab)
            if (cab[0].toInt() != 5) return
            val comando = cab[1].toInt()
            val tipo = cab[3].toInt()

            val enderecoBruto: ByteArray
            val nome: String
            when (tipo) {
                1 -> { // IPv4
                    enderecoBruto = ByteArray(4).also { entrada.readFully(it) }
                    nome = enderecoBruto.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
                3 -> { // nome de domínio
                    val tam = entrada.read()
                    if (tam <= 0) return
                    val b = ByteArray(tam).also { entrada.readFully(it) }
                    nome = String(b, Charsets.US_ASCII)
                    enderecoBruto = byteArrayOf(tam.toByte()) + b
                }
                4 -> { // IPv6
                    enderecoBruto = ByteArray(16).also { entrada.readFully(it) }
                    nome = "[IPv6]"
                }
                else -> return
            }
            val portaBytes = ByteArray(2).also { entrada.readFully(it) }
            val porta = ((portaBytes[0].toInt() and 0xFF) shl 8) or (portaBytes[1].toInt() and 0xFF)

            // IPv6: recusa na hora; o app tenta IPv4 em milissegundos.
            if (tipo == 4) {
                ipv6Recusados.incrementAndGet()
                responder(saida, 3)
                return
            }
            if (comando != 1) { // só CONNECT (a rede Tor não transporta UDP)
                responder(saida, 7)
                return
            }
            // Rede local, endereços internos do próprio túnel (inclusive o DNS
            // Privado tentando a porta 853) e faixas reservadas: a rede Tor
            // nunca alcançaria. Recusar na hora evita esperas de 10–15 s.
            if (tipo == 1 && reservado(enderecoBruto)) {
                reservadosRecusados.incrementAndGet()
                val a = enderecoBruto[0].toInt() and 0xFF
                val b = enderecoBruto[1].toInt() and 0xFF
                when {
                    a == 100 && b in 64..127 -> enderecosAntigosDoTunel.incrementAndGet()
                    a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) ->
                        redeLocalRecusada.incrementAndGet()
                }
                responder(saida, 5)
                return
            }

            val host: String? = if (tipo == 3) nome.lowercase(Locale.ROOT).trimEnd('.') else null
            val rotulo = host ?: "$nome:$porta"
            val r = regras
            val decisao = if (host != null) r.decidir(host) else null
            val tipoDecisao = decisao?.tipo

            // 1. Rastreadores. Só a escolha explícita da pessoa passa por cima.
            if (host != null && bloqueioAtivo && tipoDecisao != RotaInteligente.Regras.Tipo.DIRETO_USUARIO && estaNaLista(host)) {
                registrar(host, Destino.BLOQUEADO, "rastreador")
                responder(saida, 2)
                return
            }

            // 2. Rotas por fora do Tor.
            val autoPermitido = host != null && r.inteligente && r.desvioAutomatico &&
                tipoDecisao != RotaInteligente.Regras.Tipo.SEMPRE_TOR
            val motivoDireto: String? = when {
                host == null -> null
                tipoDecisao == RotaInteligente.Regras.Tipo.DIRETO_USUARIO -> "sua lista"
                tipoDecisao == RotaInteligente.Regras.Tipo.DIRETO_CATEGORIA -> decisao?.categoria?.titulo
                autoPermitido && desviadosAuto.containsKey(RotaInteligente.dominioBase(host)) -> "recusou o Tor"
                else -> null
            }
            if (host != null && motivoDireto != null) {
                val direto = conectarDireto(host, porta)
                if (direto != null) {
                    remoto = direto
                    registrar(host, Destino.DIRETO, motivoDireto)
                    responder(saida, 0)
                    encanarNosDoisSentidos(cliente, direto)
                    return
                }
                // Porta 80 ou a rede falhou: tenta pelo Tor.
            }

            // 3. Disjuntor: este endereço anda falhando pelo Tor?
            val chave = rotulo
            val agora = System.currentTimeMillis()
            val pausa = disjuntor.pausaRestante(chave, agora)
            if (pausa > 0) {
                if (host != null && autoPermitido && porta != 80) {
                    val base = RotaInteligente.dominioBase(host)
                    if (desviadosAuto.putIfAbsent(base, agora) == null) {
                        Log.i(TAG, "$base recusa o Tor: desviado automaticamente nesta sessão.")
                    }
                    val direto = conectarDireto(host, porta)
                    if (direto != null) {
                        remoto = direto
                        registrar(host, Destino.DIRETO, "recusou o Tor")
                        responder(saida, 0)
                        encanarNosDoisSentidos(cliente, direto)
                        return
                    }
                }
                registrar(rotulo, Destino.PAUSADO, "falhou seguidas vezes pelo Tor; nova tentativa em ${(pausa + 999) / 1000}s")
                responder(saida, 5)
                return
            }

            if (modoKillSwitch) {
                responder(saida, 2)
                return
            }

            // Sem internet no aparelho: responde na hora "rede inalcançável".
            if (redeEmEspera) {
                responder(saida, 3)
                return
            }

            // 4. Pela rede Tor.
            // Muitas conexões já esperando o Tor (todo mundo reconectando
            // depois de uma troca de rede, ou rede fraca): esta falha na hora,
            // sem prender uma linha por até 25 s. O app tenta de novo depois.
            if (esperandoOTor.incrementAndGet() > MAX_ESPERANDO_O_TOR) {
                esperandoOTor.decrementAndGet()
                recusadosPorLotacao.incrementAndGet()
                responder(saida, 1)
                return
            }
            esperando = true
            registrar(rotulo, Destino.TOR, null)
            val inicioNoTor = System.currentTimeMillis()
            val tor = Socket()
            remoto = tor
            try {
                tor.connect(InetSocketAddress("127.0.0.1", portaDoTor), 5_000)
            } catch (e: Exception) {
                // O próprio Tor está fora: não é culpa do destino.
                responder(saida, 1)
                return
            }
            tor.soTimeout = ESPERA_RESPOSTA_TOR_MS
            tor.tcpNoDelay = true
            val torIn = DataInputStream(tor.getInputStream())
            val torOut = tor.getOutputStream()
            torOut.write(byteArrayOf(5, 1, 0)); torOut.flush()
            torIn.readFully(ByteArray(2))
            torOut.write(byteArrayOf(5, 1, 0, tipo.toByte()) + enderecoBruto + portaBytes); torOut.flush()
            val resp = ByteArray(4)
            try {
                torIn.readFully(resp)
            } catch (e: SocketTimeoutException) {
                falhaPeloTor(chave, inicioNoTor)
                responder(saida, 4)
                return
            }
            val resto = when (resp[3].toInt()) {
                1 -> ByteArray(6)
                3 -> ByteArray(torIn.read() + 2)
                4 -> ByteArray(18)
                else -> ByteArray(0)
            }
            if (resto.isNotEmpty()) torIn.readFully(resto)
            esperandoOTor.decrementAndGet()
            esperando = false
            if (resp[1].toInt() != 0) {
                // A saída do Tor não alcançou o destino (recusado, inalcançável...).
                falhaPeloTor(chave, inicioNoTor)
                try {
                    saida.write(resp); saida.write(resto); saida.flush()
                } catch (_: Exception) {
                }
                return
            }
            // O Tor alcançou o destino: a rede Tor está funcionando.
            sucessoPeloTor(chave, System.currentTimeMillis())
            saida.write(resp); saida.write(resto); saida.flush()
            val inicio = System.currentTimeMillis()
            val (doCliente, doServidor) = encanarNosDoisSentidos(cliente, tor)
            when {
                doServidor > 0 -> disjuntor.funcionou(chave)
                // O app falou, o servidor fechou sem dizer nada: é assim que
                // muitos serviços recusam quem chega pela rede Tor.
                doCliente > 0 && System.currentTimeMillis() - inicio < RECUSA_SILENCIOSA_MS ->
                    falhaPeloTor(chave, inicio)
            }
        } catch (_: Throwable) {
            // Nada aqui pode derrubar o processo da VPN (nem um erro do Java,
            // como faltar memória para mais uma linha): a conexão é encerrada.
        } finally {
            if (esperando) esperandoOTor.decrementAndGet()
            fechar(cliente)
            remoto?.let { fechar(it) }
        }
    }

    /**
     * Uma falha pelo Tor só pesa contra o destino (pausa e, se for site, desvio
     * automático) quando a rede do aparelho estava normal E o Tor acabou de
     * funcionar para outro destino. Se tudo está falhando junto, é a rede.
     */
    private fun falhaPeloTor(chave: String, inicio: Long) {
        val agora = System.currentTimeMillis()
        val culpa = culpaDoDestino(chave, inicio, agora)
        if (culpa && falhaConta()) disjuntor.falhou(chave, agora)
    }

    /** Bytes nos dois sentidos até um lado fechar ou ficar 5 min parado. Devolve (do app, do servidor). */
    private fun encanarNosDoisSentidos(a: Socket, b: Socket): Pair<Long, Long> {
        a.soTimeout = OCIOSO_MS
        b.soTimeout = OCIOSO_MS
        val deA = AtomicLong(0)
        val deB = AtomicLong(0)
        try {
            pool.execute {
                try {
                    encanar(a.getInputStream(), b.getOutputStream(), deA)
                } catch (_: Throwable) {
                } finally {
                    fechar(a); fechar(b)
                }
            }
        } catch (e: Throwable) {
            recusadosPorLotacao.incrementAndGet()
            return 0L to 0L
        }
        encanar(b.getInputStream(), a.getOutputStream(), deB)
        return deA.get() to deB.get()
    }

    private fun encanar(de: InputStream, para: OutputStream, contador: AtomicLong) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = de.read(buf)
                if (n < 0) return
                para.write(buf, 0, n)
                para.flush()
                contador.addAndGet(n.toLong())
            }
        } catch (_: Exception) {
        }
    }

    private fun responder(saida: OutputStream, codigo: Int) {
        try {
            saida.write(byteArrayOf(5, codigo.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
            saida.flush()
        } catch (_: Exception) {
        }
    }

    private fun conectarDireto(nome: String, porta: Int): Socket? {
        if (porta == 80) {
            // Sem criptografia e fora do Tor seria entregar o conteúdo de bandeja.
            semCriptografiaBarrados.incrementAndGet()
            return null
        }
        val enderecos = ResolvedorDoH.resolver(nome)
        for (ip in enderecos.take(2)) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(ip, porta), 8_000)
                s.tcpNoDelay = true
                return s
            } catch (e: Exception) {
                fechar(s)
            }
        }
        return null
    }

    private fun reservado(ip: ByteArray): Boolean {
        val a = ip[0].toInt() and 0xFF
        val b = ip[1].toInt() and 0xFF
        return a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 198 && (b == 18 || b == 19)) ||
            (a == 100 && b in 64..127)
    }

    private fun estaNaLista(dominio: String): Boolean = RotaInteligente.casar(dominio, listaDeBloqueio) != null

    private fun registrar(dominio: String, destino: Destino, motivo: String?) {
        when (destino) {
            Destino.BLOQUEADO -> bloqueados.incrementAndGet()
            Destino.DIRETO -> diretos.incrementAndGet()
            Destino.TOR -> pelaRedeTor.incrementAndGet()
            Destino.PAUSADO -> pausados.incrementAndGet()
        }
        anotar(Registro(dominio, destino, System.currentTimeMillis(), motivo))
    }

    private fun carregarLista(): Set<String> = try {
        context.assets.open("rastreadores.txt").bufferedReader().useLines { linhas ->
            linhas.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.lowercase(Locale.ROOT) }
                .toSet()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Não foi possível ler a lista de rastreadores: ${e.message}")
        emptySet()
    }

    private fun fechar(s: Socket) {
        try {
            s.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Disjuntor por destino ("circuit breaker").
 *
 * 3 falhas seguidas pelo Tor → pausa de 30 s. Depois da pausa, UMA nova
 * tentativa: se falhar de novo, a pausa dobra (1 min, 2 min, 4 min, 8 min,
 * no máximo 10 min). Um único sucesso zera tudo.
 */
class Disjuntor {

    private class Estado {
        var falhasSeguidas = 0
        var nivel = 0
        var abertoAte = 0L
        var ultimoUso = 0L
        var falhasTotal = 0
    }

    companion object {
        const val LIMITE = 3
        const val PAUSA_INICIAL_MS = 30_000L
        const val PAUSA_MAXIMA_MS = 600_000L
        private const val ESQUECER_APOS_MS = 30 * 60_000L
    }

    private val mapa = ConcurrentHashMap<String, Estado>()

    /** Milissegundos de pausa que ainda faltam (0 = pode tentar). */
    fun pausaRestante(chave: String, agora: Long): Long {
        val e = mapa[chave] ?: return 0
        synchronized(e) {
            e.ultimoUso = agora
            return (e.abertoAte - agora).coerceAtLeast(0)
        }
    }

    /** Registra uma falha. true = a pausa acabou de começar. */
    fun falhou(chave: String, agora: Long): Boolean {
        val e = mapa.computeIfAbsent(chave) { Estado() }
        val abriu = synchronized(e) {
            e.ultimoUso = agora
            e.falhasTotal++
            if (e.abertoAte > agora) return@synchronized false
            e.falhasSeguidas++
            // Já esteve pausado antes: uma falha basta para pausar de novo.
            val limite = if (e.nivel > 0) 1 else LIMITE
            if (e.falhasSeguidas >= limite) {
                val pausa = (PAUSA_INICIAL_MS shl e.nivel.coerceAtMost(5)).coerceAtMost(PAUSA_MAXIMA_MS)
                e.abertoAte = agora + pausa
                e.nivel++
                e.falhasSeguidas = 0
                true
            } else {
                false
            }
        }
        if (mapa.size > 3000) limparAntigos(agora)
        return abriu
    }

    fun funcionou(chave: String) {
        mapa.remove(chave)
    }

    fun limpar() = mapa.clear()

    /** Destinos pausados agora, para mostrar na tela. */
    fun pausadosAgora(agora: Long): List<Map<String, Any?>> =
        mapa.entries.mapNotNull { (chave, e) ->
            synchronized(e) {
                if (e.abertoAte > agora) {
                    mapOf("destino" to chave, "falhas" to e.falhasTotal, "segundos" to ((e.abertoAte - agora + 999) / 1000))
                } else {
                    null
                }
            }
        }.sortedByDescending { it["falhas"] as Int }

    private fun limparAntigos(agora: Long) {
        mapa.entries.removeIf { agora - it.value.ultimoUso > ESQUECER_APOS_MS }
    }
}

/**
 * Sucessos e falhas pela rede Tor nos últimos [janelaMs] (contados por segundo,
 * sem guardar nada além de números).
 */
class JanelaDeSaude(janelaMs: Long) {
    private val segundos = (janelaMs / 1000).toInt().coerceAtLeast(1)
    private val sucessos = IntArray(segundos)
    private val falhas = IntArray(segundos)
    private val segundoDe = LongArray(segundos) { -1L }

    @Synchronized
    fun anotar(agora: Long, ok: Boolean) {
        val s = agora / 1000
        val i = Math.floorMod(s, segundos.toLong()).toInt()
        if (segundoDe[i] != s) {
            segundoDe[i] = s
            sucessos[i] = 0
            falhas[i] = 0
        }
        if (ok) sucessos[i]++ else falhas[i]++
    }

    /** (sucessos, falhas) na janela. */
    @Synchronized
    fun contar(agora: Long): Pair<Int, Int> {
        val s = agora / 1000
        var ok = 0
        var ruim = 0
        for (i in 0 until segundos) {
            if (segundoDe[i] >= 0 && s - segundoDe[i] < segundos) {
                ok += sucessos[i]
                ruim += falhas[i]
            }
        }
        return ok to ruim
    }

    @Synchronized
    fun limpar() {
        segundoDe.fill(-1L)
        sucessos.fill(0)
        falhas.fill(0)
    }
}
