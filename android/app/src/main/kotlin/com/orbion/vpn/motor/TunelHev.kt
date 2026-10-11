package com.orbion.vpn.motor

import android.content.Context
import android.util.Log
import hev.sockstun.TProxyService
import java.io.File
import java.io.RandomAccessFile

/**
 * O túnel: pega os pacotes que os aplicativos mandam para a interface da VPN
 * e transforma em conexões SOCKS para o filtro do Orbion (que repassa ao Tor).
 *
 * Usa a biblioteca libhev-socks5-tunnel.so (projeto heiher/hev-socks5-tunnel),
 * compilada pela esteira do GitHub para cada arquitetura.
 */
object TunelHev {

    private const val TAG = "OrbionHev"
    private const val LIB = "hev-socks5-tunnel"

    /** DNS "de mentira" que o túnel responde sozinho (nomes viram IPs internos). */
    const val DNS_MAPEADO = "198.18.0.2"

    /** O log é cortado quando passa disso, para nunca encher a memória. */
    private const val LIMITE_LOG_BYTES = 1_500_000L

    @Volatile private var carregada = false
    @Volatile private var erroDeCarga: String? = null

    data class Estatisticas(
        val enviadoPacotes: Long,
        val enviadoBytes: Long,
        val recebidoPacotes: Long,
        val recebidoBytes: Long,
    )

    @Synchronized
    private fun carregar(): Boolean {
        if (carregada) return true
        return try {
            System.loadLibrary(LIB)
            carregada = true
            true
        } catch (e: Throwable) {
            erroDeCarga = e.message
            Log.e(TAG, "Não foi possível carregar libhev-socks5-tunnel.so: ${e.message}")
            false
        }
    }

    /** null = túnel no ar. Senão, a explicação do problema. */
    @Synchronized
    fun iniciar(context: Context, fd: Int, socksPort: Int, mtu: Int): String? {
        if (!carregar()) {
            return "A peça do túnel (libhev-socks5-tunnel.so) não pôde ser carregada neste aparelho.\n\n" +
                "Detalhe técnico: ${erroDeCarga ?: "desconhecido"}"
        }
        if (rodando()) parar()
        try {
            logFile(context).delete()
        } catch (_: Exception) {
        }
        val config = escreverConfig(context, socksPort, mtu)
        val ok = try {
            TProxyService.TProxyStartService(config.absolutePath, fd)
        } catch (e: Throwable) {
            return "Erro ao iniciar o túnel: ${e.message}"
        }
        if (!ok) return "O túnel não conseguiu iniciar."
        // Dá um instante para o hev ler a configuração e reclamar, se for o caso.
        Thread.sleep(400)
        val queixa = lerLog(context, 200).firstOrNull { l ->
            val x = l.lowercase()
            ("[e]" in x || "error" in x) && ("config" in x || "parse" in x || "invalid" in x)
        }
        if (queixa != null) {
            Log.e(TAG, "hev recusou a configuração: $queixa")
            parar()
            return "O túnel recusou a configuração.\n\nO hev disse: $queixa"
        }
        if (!rodando()) return "O túnel encerrou logo após iniciar. Veja Registros → Ver técnico."
        Log.d(TAG, "Túnel iniciado. fd=$fd socks=$socksPort")
        return null
    }

    @Synchronized
    fun parar() {
        if (!carregada) return
        try {
            TProxyService.TProxyStopService()
        } catch (e: Throwable) {
            Log.w(TAG, "Erro ao parar o túnel: ${e.message}")
        }
    }

    fun rodando(): Boolean = carregada && try {
        TProxyService.TProxyIsRunning()
    } catch (_: Throwable) {
        false
    }

    fun estatisticas(): Estatisticas? {
        if (!carregada) return null
        return try {
            val s = TProxyService.TProxyGetStats() ?: return null
            if (s.size < 4) null else Estatisticas(s[0], s[1], s[2], s[3])
        } catch (_: Throwable) {
            null
        }
    }

    private fun escreverConfig(context: Context, socksPort: Int, mtu: Int): File {
        // Mesmo ajuste comprovado da versão anterior:
        //   task-stack-size = 20480 + tcp-buffer-size  (regra do próprio hev)
        // Mexer em um sem o outro fazia envio de arquivo grande morrer no fim.
        val yaml = """
            tunnel:
              mtu: $mtu

            socks5:
              address: 127.0.0.1
              port: $socksPort
              # 'udp' faz um pedido SOCKS5 padrão. O Tor não aceita UDP e
              # recusa de forma limpa (o 'tcp' do hev é proprietário e o Tor
              # não entende).
              udp: 'udp'

            mapdns:
              address: $DNS_MAPEADO
              port: 53
              network: 100.64.0.0
              netmask: 255.192.0.0
              cache-size: 10000

            misc:
              tcp-buffer-size: 65536
              task-stack-size: 86016
              max-session-count: 512
              connect-timeout: 20000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-file: ${logFile(context).absolutePath}
              # 'warn': só problemas. ('info' gravava no disco uma linha por
              # conexão, com o site — gasto de bateria e histórico de
              # navegação guardado à toa. As decisões por site ficam só na
              # memória, na aba Registros.)
              log-level: warn
        """.trimIndent()
        val f = File(context.filesDir, "hev-socks5.yaml")
        f.writeText(yaml + "\n")
        return f
    }

    fun logFile(context: Context) = File(context.filesDir, "hev.log")

    /**
     * Corta o log se ele crescer demais. O hev escreve com O_APPEND, então
     * zerar o arquivo com ele aberto é seguro.
     *
     * Na versão antiga o log crescia sem limite e a tela lia o arquivo
     * INTEIRO a cada atualização — depois de horas ligado, isso gastava
     * memória e bateria a ponto de o Android encerrar o app.
     */
    fun controlarTamanhoDoLog(context: Context) {
        try {
            val f = logFile(context)
            if (f.exists() && f.length() > LIMITE_LOG_BYTES) {
                val ultimas = lerLog(context, 400)
                RandomAccessFile(f, "rw").use { it.setLength(0) }
                f.appendText(ultimas.joinToString("\n", postfix = "\n"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível controlar o tamanho do log: ${e.message}")
        }
    }

    /** Lê só o fim do arquivo (no máximo ~256 KB), nunca o arquivo inteiro. */
    fun lerLog(context: Context, maxLinhas: Int): List<String> {
        val f = logFile(context)
        if (!f.exists()) return emptyList()
        return try {
            RandomAccessFile(f, "r").use { raf ->
                val tamanho = raf.length()
                val janela = minOf(tamanho, 256L * 1024L)
                raf.seek(tamanho - janela)
                val bytes = ByteArray(janela.toInt())
                raf.readFully(bytes)
                val linhas = String(bytes, Charsets.UTF_8).split('\n')
                // Se cortou no meio de uma linha, descarta a primeira.
                val inteiras = if (janela < tamanho) linhas.drop(1) else linhas
                inteiras.filter { it.isNotBlank() }.takeLast(maxLinhas)
            }
        } catch (e: Exception) {
            listOf("Não foi possível ler o log: ${e.message}")
        }
    }
}
