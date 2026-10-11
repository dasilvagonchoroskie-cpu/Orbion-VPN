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
        private const val MAX_THREADS = 900
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
        private val semCriptografiaBarrados = AtomicLong(0)
        private val recusadosPorLotacao = AtomicLong(0)
        private val ultimos = ArrayDeque<Registro>()

        val disjuntor = Disjuntor()

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
            recusadosPorLotacao.set(0)
            ultimos.clear()
            desviadosAuto.clear()
            disjuntor.limpar()
        }

        fun contagem(): Map<String, Long> = mapOf(
            "bloqueados" to bloqueados.get(),
            "pelaRedeTor" to pelaRedeTor.get(),
            "diretos" to diretos.get(),
            "pausados" to pausados.get(),
            "ipv6Recusados" to ipv6Recusados.get(),
            "reservadosRecusados" to reservadosRecusados.get(),
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
            } catch (e: Exception) {
                if (s.isClosed) return
                Log.w(TAG, "Erro ao aceitar: ${e.message}")
                continue
            }
            try {
                pool.execute { atender(cliente) }
            } catch (e: Exception) {
                // Lotado: recusa esta conexão em vez de deixar a memória estourar.
                recusadosPorLotacao.incrementAndGet()
                fechar(cliente)
            }
        }
    }

    private fun atender(cliente: Socket) {
        var remoto: Socket? = null
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

            // 4. Pela rede Tor.
            registrar(rotulo, Destino.TOR, null)
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
                disjuntor.falhou(chave, System.currentTimeMillis())
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
            if (resp[1].toInt() != 0) {
                // A saída do Tor não alcançou o destino (recusado, inalcançável...).
                disjuntor.falhou(chave, System.currentTimeMillis())
                try {
                    saida.write(resp); saida.write(resto); saida.flush()
                } catch (_: Exception) {
                }
                return
            }
            saida.write(resp); saida.write(resto); saida.flush()
            val inicio = System.currentTimeMillis()
            val (doCliente, doServidor) = encanarNosDoisSentidos(cliente, tor)
            when {
                doServidor > 0 -> disjuntor.funcionou(chave)
                // O app falou, o servidor fechou sem dizer nada: é assim que
                // muitos serviços recusam quem chega pela rede Tor.
                doCliente > 0 && System.currentTimeMillis() - inicio < RECUSA_SILENCIOSA_MS ->
                    disjuntor.falhou(chave, System.currentTimeMillis())
            }
        } catch (_: Exception) {
        } finally {
            fechar(cliente)
            remoto?.let { fechar(it) }
        }
    }

    /** Bytes nos dois sentidos até um lado fechar ou ficar 5 min parado. Devolve (do app, do servidor). */
    private fun encanarNosDoisSentidos(a: Socket, b: Socket): Pair<Long, Long> {
        a.soTimeout = OCIOSO_MS
        b.soTimeout = OCIOSO_MS
        val deA = AtomicLong(0)
        val deB = AtomicLong(0)
        try {
            pool.execute {
                encanar(a.getInputStream(), b.getOutputStream(), deA)
                fechar(a); fechar(b)
            }
        } catch (e: Exception) {
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
