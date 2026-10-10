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
import java.util.Locale
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Filtro SOCKS5 que fica entre o túnel e o Tor (127.0.0.1:19055).
 *
 * Para cada conexão, decide:
 *   - RECUSADO: domínio da lista de rastreadores (se o bloqueio estiver ligado);
 *   - DESVIADO: serviço que recusa a rede Tor (Instagram, YouTube, bancos...)
 *               sai direto, só aquele endereço;
 *   - PELO TOR: todo o resto.
 *
 * Mudanças em relação à versão anterior, todas para o Android não encerrar o
 * app por excesso de consumo:
 *   - threads com pilha pequena e em número limitado;
 *   - conexão parada há 5 minutos é fechada (antes ficava pendurada para
 *     sempre, acumulando threads a cada troca de rede).
 */
class FiltroSocks(private val context: Context, private val portaDoTor: Int) {

    enum class Destino { TOR, DIRETO, BLOQUEADO }

    data class Registro(val dominio: String, val destino: Destino, val quando: Long)

    companion object {
        private const val TAG = "OrbionFiltro"
        const val PORTA = 19055
        private const val MAX_THREADS = 900
        private const val OCIOSO_MS = 300_000
        private const val MAX_REGISTROS = 300

        private val bloqueados = AtomicLong(0)
        private val pelaRedeTor = AtomicLong(0)
        private val diretos = AtomicLong(0)
        private val ipv6Recusados = AtomicLong(0)
        private val semCriptografiaBarrados = AtomicLong(0)
        private val recusadosPorLotacao = AtomicLong(0)
        private val ultimos = ArrayDeque<Registro>()

        @Synchronized
        private fun anotar(r: Registro) {
            ultimos.addFirst(r)
            while (ultimos.size > MAX_REGISTROS) ultimos.removeLast()
        }

        @Synchronized
        fun registros(max: Int): List<Registro> = ultimos.take(max)

        @Synchronized
        fun zerar() {
            bloqueados.set(0); pelaRedeTor.set(0); diretos.set(0)
            ipv6Recusados.set(0); semCriptografiaBarrados.set(0); recusadosPorLotacao.set(0)
            ultimos.clear()
        }

        fun contagem(): Map<String, Long> = mapOf(
            "bloqueados" to bloqueados.get(),
            "pelaRedeTor" to pelaRedeTor.get(),
            "diretos" to diretos.get(),
            "ipv6Recusados" to ipv6Recusados.get(),
            "semCriptografiaBarrados" to semCriptografiaBarrados.get(),
            "recusadosPorLotacao" to recusadosPorLotacao.get(),
        )
    }

    @Volatile private var bloqueioAtivo = true
    @Volatile private var desvioDiretoAtivo = true
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

    fun iniciar(comBloqueio: Boolean, comDesvioDireto: Boolean): Boolean {
        bloqueioAtivo = comBloqueio
        desvioDiretoAtivo = comDesvioDireto
        listaDeBloqueio = carregarLista()
        zerar()
        ResolvedorDoH.limparCache()
        return try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORTA), 256)
            servidor = s
            Log.d(TAG, "Filtro em 127.0.0.1:$PORTA (bloqueio=$comBloqueio, desvio=$comDesvioDireto, ${listaDeBloqueio.size} rastreadores)")
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
            if (comando != 1) { // só CONNECT
                responder(saida, 7)
                return
            }
            if (bloqueioAtivo && tipo == 3 && estaNaLista(nome)) {
                registrar(nome, Destino.BLOQUEADO)
                responder(saida, 2)
                return
            }
            val desviar = desvioDiretoAtivo && tipo == 3 && AppsProblematicos.exigeConexaoDireta(nome)
            if (desviar) {
                val direto = conectarDireto(nome, porta)
                if (direto != null) {
                    registrar(nome, Destino.DIRETO)
                    remoto = direto
                    responder(saida, 0)
                    encanarNosDoisSentidos(cliente, direto)
                    return
                }
                Log.w(TAG, "Via direta falhou para $nome. Tentando pelo Tor.")
            }
            if (modoKillSwitch) {
                responder(saida, 2)
                return
            }

            registrar(if (tipo == 3) nome else "$nome:$porta", Destino.TOR)
            val tor = Socket()
            remoto = tor
            tor.connect(InetSocketAddress("127.0.0.1", portaDoTor), 5_000)
            tor.soTimeout = 60_000
            tor.tcpNoDelay = true
            val torIn = DataInputStream(tor.getInputStream())
            val torOut = tor.getOutputStream()
            torOut.write(byteArrayOf(5, 1, 0)); torOut.flush()
            torIn.readFully(ByteArray(2))
            torOut.write(byteArrayOf(5, 1, 0, tipo.toByte()) + enderecoBruto + portaBytes); torOut.flush()
            val resp = ByteArray(4).also { torIn.readFully(it) }
            val resto = when (resp[3].toInt()) {
                1 -> ByteArray(6)
                3 -> ByteArray(torIn.read() + 2)
                4 -> ByteArray(18)
                else -> ByteArray(0)
            }
            if (resto.isNotEmpty()) torIn.readFully(resto)
            saida.write(resp); saida.write(resto); saida.flush()
            if (resp[1].toInt() != 0) return
            encanarNosDoisSentidos(cliente, tor)
        } catch (_: Exception) {
        } finally {
            fechar(cliente)
            remoto?.let { fechar(it) }
        }
    }

    /** Bytes nos dois sentidos até um lado fechar ou ficar 5 min parado. */
    private fun encanarNosDoisSentidos(a: Socket, b: Socket) {
        a.soTimeout = OCIOSO_MS
        b.soTimeout = OCIOSO_MS
        try {
            pool.execute {
                encanar(a.getInputStream(), b.getOutputStream())
                fechar(a); fechar(b)
            }
        } catch (e: Exception) {
            recusadosPorLotacao.incrementAndGet()
            return
        }
        encanar(b.getInputStream(), a.getOutputStream())
    }

    private fun encanar(de: InputStream, para: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = de.read(buf)
                if (n < 0) return
                para.write(buf, 0, n)
                para.flush()
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
                s.connect(InetSocketAddress(ip, porta), 10_000)
                s.tcpNoDelay = true
                return s
            } catch (e: Exception) {
                fechar(s)
            }
        }
        return null
    }

    private fun estaNaLista(dominio: String): Boolean {
        var d = dominio.lowercase(Locale.ROOT).trimEnd('.')
        while (true) {
            if (d in listaDeBloqueio) return true
            val p = d.indexOf('.')
            if (p < 0) return false
            d = d.substring(p + 1)
            if (d.isEmpty()) return false
        }
    }

    private fun registrar(dominio: String, destino: Destino) {
        when (destino) {
            Destino.BLOQUEADO -> bloqueados.incrementAndGet()
            Destino.DIRETO -> diretos.incrementAndGet()
            Destino.TOR -> pelaRedeTor.incrementAndGet()
        }
        anotar(Registro(dominio, destino, System.currentTimeMillis()))
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
