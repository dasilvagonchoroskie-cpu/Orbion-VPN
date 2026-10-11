package com.orbion.vpn.motor

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sobe e controla o programa do Tor (libtor.so).
 *
 * O Tor roda como processo separado, filho do app. Por isso o serviço da
 * VPN precisa ficar de verdade em primeiro plano: no Android 12 ou mais
 * novo, processo filho de app "esquecido" em segundo plano é encerrado pelo
 * sistema (é o "phantom process killer"). Isso, somado ao serviço que não
 * conseguia entrar em primeiro plano, era o principal motivo de o Android
 * "matar" o Orbion.
 */
class TorManager(private val context: Context) {

    companion object {
        private const val TAG = "OrbionTor"

        // Portas fora do padrão (9050/9051) para não brigar com o Orbot ou
        // outro app de Tor que esteja instalado no mesmo aparelho.
        const val SOCKS_PORT = 19050
        const val CONTROL_PORT = 19051

        private const val ESPERA_DIRETO_MS = 60_000L
        private const val ESPERA_SNOWFLAKE_MS = 100_000L
        private const val ESPERA_OBFS4_MS = 90_000L
        private const val ESPERA_UNICO_MS = 150_000L
        private const val PRORROGACAO_MAX_MS = 90_000L

        private const val MAX_LINHAS_LOG = 500
        private val logDoTor = ArrayDeque<String>()

        /**
         * "Your application is giving Tor only an IP address": app que conecta
         * direto num número (WhatsApp, apps com endereço fixo ou DNS próprio).
         * Não é vazamento — nenhuma consulta de DNS sai do túnel —, mas o Tor
         * repete o aviso a cada poucos segundos e ele empurrava o que importa
         * para fora do registro. Só é contado (aparece no relatório).
         */
        private val avisosDeIpDireto = java.util.concurrent.atomic.AtomicLong(0)

        fun avisosDeIpDireto(): Long = avisosDeIpDireto.get()

        @Synchronized
        fun registrarLog(linha: String) {
            logDoTor.addLast(linha)
            while (logDoTor.size > MAX_LINHAS_LOG) logDoTor.removeFirst()
        }

        @Synchronized
        fun lerLog(maxLinhas: Int): List<String> = logDoTor.toList().takeLast(maxLinhas)

        @Synchronized
        fun limparLog() = logDoTor.clear()

        /** Pouca memória no aparelho: guarda só o fim do log. */
        @Synchronized
        fun aliviarMemoria() {
            while (logDoTor.size > 80) logDoTor.removeFirst()
        }

        @Volatile private var versaoGuardada: String? = null

        /** Lê a versão do Tor embutido (para a tela "Sobre"). Só roda o programa uma vez. */
        fun versao(context: Context): String {
            versaoGuardada?.let { return it }
            val bin = Transportes.binario(context, Transportes.BINARIO_TOR) ?: return "não encontrado"
            return try {
                val p = ProcessBuilder(bin.absolutePath, "--version").redirectErrorStream(true).start()
                val saida = p.inputStream.bufferedReader().use { it.readText() }
                p.waitFor()
                val v = Regex("Tor version ([0-9.a-z-]+)").find(saida)?.groupValues?.get(1)
                    ?: saida.lineSequence().firstOrNull()?.take(60) ?: "?"
                versaoGuardada = v
                v
            } catch (e: Exception) {
                "erro: ${e.message}"
            }
        }
    }

    private val sintomasDeBloqueio = listOf(
        "Problem bootstrapping",
        "no running bridges",
        "Unable to find IPv4 address for ORPort",
        "giving up. (waiting for circuit)",
        "General SOCKS server failure",
    )

    @Volatile private var process: Process? = null
    @Volatile var bootstrapPercent = 0
        private set
    @Volatile var isReady = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var caminhoEmUso: Transportes.Caminho = Transportes.Caminho.DIRETO
        private set
    @Volatile var contornouBloqueio = false
        private set

    @Volatile private var sinaisDeBloqueio = 0
    @Volatile private var torEncerrouSozinho = false
    @Volatile private var ultimoAvanco = 0L
    private val starting = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    private val dataDir: File get() = File(context.filesDir, "tor_data")
    private val pidFile: File get() = File(dataDir, "tor.pid")

    /**
     * Tenta conectar subindo a escada. Bloqueia a thread até conseguir ou
     * esgotar todos os degraus. onProgress(percentual, mensagem).
     */
    fun startAndAwaitBootstrap(prefs: Preferencias, onProgress: (Int, String) -> Unit): Boolean {
        if (!starting.compareAndSet(false, true)) {
            Log.w(TAG, "Início já em andamento; ignorando.")
            return isReady
        }
        try {
            bootstrapPercent = 0
            isReady = false
            lastError = null
            contornouBloqueio = false
            torEncerrouSozinho = false
            // stopRequested NÃO é zerado aqui: cada conexão usa um TorManager
            // novo, e um pedido de parada que chegou antes do início vale.
            if (stopRequested.get()) return false

            val tor = File(context.applicationInfo.nativeLibraryDir, Transportes.BINARIO_TOR)
            if (!tor.exists()) {
                lastError = "O programa do Tor (libtor.so) não veio para a arquitetura deste aparelho. " +
                    "Instale o APK certo para o seu celular."
                return false
            }
            if (!tor.canExecute()) {
                lastError = "O programa do Tor existe mas o Android não deixou executar. " +
                    "Reinstale o aplicativo."
                return false
            }

            matarTorEsquecido()
            // Um Tor que acabou de ser parado pode levar um instante para
            // soltar as portas: espera até 3 s antes de dizer que estão ocupadas.
            var ocupada = portaOcupada()
            val limite = System.currentTimeMillis() + 3_000
            while (ocupada != null && System.currentTimeMillis() < limite && !stopRequested.get()) {
                Thread.sleep(250)
                ocupada = portaOcupada()
            }
            if (ocupada != null) {
                lastError = "A porta $ocupada do aparelho já está em uso por outro aplicativo. " +
                    "Feche outros apps de Tor/VPN e tente de novo."
                return false
            }

            val escada = Transportes.montarEscada(context, prefs)
            Log.d(TAG, "Escada de entrada: ${escada.joinToString { it.name }}")
            for ((i, degrau) in escada.withIndex()) {
                if (stopRequested.get()) return false
                caminhoEmUso = degrau
                Painel.caminhoEmUso = degrau.name
                if (i > 0) {
                    contornouBloqueio = true
                    onProgress(0, "Esta rede parece bloquear o Tor. Tentando por ${Transportes.nome(degrau)}...")
                    Thread.sleep(1200)
                }
                val espera = when {
                    escada.size == 1 -> ESPERA_UNICO_MS
                    degrau == Transportes.Caminho.DIRETO -> ESPERA_DIRETO_MS
                    degrau == Transportes.Caminho.SNOWFLAKE -> ESPERA_SNOWFLAKE_MS
                    else -> ESPERA_OBFS4_MS
                }
                if (tentarUmDegrau(tor, prefs, degrau, espera, onProgress)) {
                    Transportes.lembrar(context, degrau)
                    return true
                }
            }
            if (lastError == null && !stopRequested.get()) {
                lastError = explicacaoDaFalha(prefs, escada) +
                    if (torEncerrouSozinho) "\n\nO Tor chegou a fechar sozinho durante a tentativa. Os detalhes estão em Registros → Ver técnico." else ""
            }
            return false
        } catch (e: Exception) {
            lastError = "Falha ao iniciar o Tor: ${e.message}"
            Log.e(TAG, lastError!!, e)
            return false
        } finally {
            starting.set(false)
        }
    }

    private fun explicacaoDaFalha(prefs: Preferencias, tentados: List<Transportes.Caminho>): String {
        val faltas = Transportes.Caminho.values()
            .filter { it !in tentados }
            .mapNotNull { Transportes.oQueFalta(context, prefs, it) }
        val base = if (tentados.size <= 1) {
            "Não foi possível conectar à rede Tor. Confira se a internet do aparelho está funcionando."
        } else {
            "Não foi possível conectar por nenhum dos caminhos. Esta rede provavelmente bloqueia o Tor."
        }
        return if (faltas.isEmpty()) base else base + "\n\n" + faltas.joinToString("\n\n")
    }

    private fun tentarUmDegrau(
        tor: File,
        prefs: Preferencias,
        degrau: Transportes.Caminho,
        esperaMs: Long,
        onProgress: (Int, String) -> Unit,
    ): Boolean {
        try {
            bootstrapPercent = 0
            isReady = false
            sinaisDeBloqueio = 0
            dataDir.mkdirs()
            dataDir.setReadable(true, true)
            dataDir.setWritable(true, true)
            dataDir.setExecutable(true, true)

            val torrc = File(context.filesDir, "torrc")
            torrc.writeText(montarTorrc(prefs, degrau))

            registrarLog("—— Iniciando Tor por ${degrau.name} ——")
            onProgress(0, "Iniciando o Tor...")
            val p = ProcessBuilder(tor.absolutePath, "-f", torrc.absolutePath)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()
            process = p
            ultimoAvanco = System.currentTimeMillis()

            val leitor = Thread({ lerSaida(p, onProgress) }, "orbion-tor-log")
            leitor.isDaemon = true
            leitor.start()

            val inicio = System.currentTimeMillis()
            var prazo = inicio + esperaMs
            val prazoMaximo = inicio + esperaMs + PRORROGACAO_MAX_MS
            while (System.currentTimeMillis() < prazo) {
                if (isReady) return true
                if (stopRequested.get()) return false
                if (!vivo(p)) {
                    Log.w(TAG, "Tor encerrou sozinho no degrau ${degrau.name}.")
                    torEncerrouSozinho = true
                    return false
                }
                if (sinaisDeBloqueio >= 4) {
                    Log.w(TAG, "Sinais de bloqueio no degrau ${degrau.name}. Subindo a escada.")
                    matarProcesso()
                    return false
                }
                // Está avançando? Dá mais um tempo em vez de desistir no meio.
                val agora = System.currentTimeMillis()
                if (prazo - agora < 5_000 && bootstrapPercent >= 25 && agora - ultimoAvanco < 20_000 && prazo < prazoMaximo) {
                    prazo = minOf(prazo + 15_000, prazoMaximo)
                }
                Thread.sleep(250)
            }
            Log.w(TAG, "Tempo esgotado no degrau ${degrau.name} (parou em $bootstrapPercent%).")
            matarProcesso()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Falha no degrau ${degrau.name}: ${e.message}", e)
            lastError = "Falha ao iniciar o Tor: ${e.message}"
            return false
        }
    }

    private fun lerSaida(p: Process, onProgress: (Int, String) -> Unit) {
        try {
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                while (true) {
                    val linha = r.readLine() ?: break
                    if (linha.contains("is giving Tor only an IP address")) {
                        avisosDeIpDireto.incrementAndGet()
                        continue
                    }
                    registrarLog(linha)
                    analisarLinha(linha, onProgress)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Leitura do log do Tor encerrada: ${e.message}")
        }
    }

    private val regexBootstrap = Regex("Bootstrapped (\\d+)%")

    private fun analisarLinha(linha: String, onProgress: (Int, String) -> Unit) {
        if (sintomasDeBloqueio.any { linha.contains(it, ignoreCase = true) }) sinaisDeBloqueio++
        val pct = regexBootstrap.find(linha)?.groupValues?.get(1)?.toIntOrNull() ?: return
        if (pct > bootstrapPercent) ultimoAvanco = System.currentTimeMillis()
        bootstrapPercent = pct
        if (!isReady) {
            val sufixo = if (caminhoEmUso == Transportes.Caminho.DIRETO) "" else " (${Transportes.nome(caminhoEmUso)})"
            onProgress(pct, "Conectando à rede Tor: $pct%$sufixo")
        }
        if (pct >= 100) {
            isReady = true
            Log.d(TAG, "Tor pronto por ${caminhoEmUso.name}.")
        }
    }

    private fun montarTorrc(prefs: Preferencias, degrau: Transportes.Caminho): String {
        val dados = dataDir.absolutePath
        // Sem DNSPort: os nomes chegam ao Tor dentro do pedido SOCKS (o túnel
        // nunca resolve nada sozinho), então uma porta de DNS seria só mais
        // uma porta aberta no aparelho.
        // (WarnUnsafeSocks saiu: o Tor 0.4.9 não tem mais essa opção e
        // reclamava dela a cada início. O aviso "only an IP address" agora é
        // filtrado na leitura do registro — ver lerSaida.)
        return """
            SocksPort 127.0.0.1:$SOCKS_PORT
            ControlPort 127.0.0.1:$CONTROL_PORT
            CookieAuthentication 1
            DataDirectory $dados
            PidFile ${pidFile.absolutePath}
            RunAsDaemon 0
            AvoidDiskWrites 1
            ClientOnly 1
            DormantCanceledByStartup 1
            Log notice stdout
            MaxCircuitDirtiness ${prefs.intervaloRotacaoSegundos}
        """.trimIndent() + "\n" + Transportes.blocoDeConfiguracao(context, prefs, degrau)
    }

    private fun vivo(p: Process?): Boolean = p != null && p.isAlive

    fun estaVivo(): Boolean = vivo(process)

    private fun matarProcesso() {
        try {
            process?.destroy()
            Thread.sleep(300)
            if (vivo(process)) process?.destroyForcibly()
        } catch (_: Exception) {
        }
        process = null
    }

    /** Se um Tor de uma execução anterior ficou vivo, encerra antes de subir outro. */
    private fun matarTorEsquecido() {
        try {
            if (!pidFile.exists()) return
            val pid = pidFile.readText().trim().toIntOrNull() ?: return
            val cmd = File("/proc/$pid/cmdline")
            if (cmd.exists() && cmd.readText().contains(Transportes.BINARIO_TOR)) {
                Log.w(TAG, "Encerrando Tor esquecido (pid $pid).")
                android.os.Process.killProcess(pid)
                Thread.sleep(400)
            }
            pidFile.delete()
        } catch (e: Exception) {
            Log.d(TAG, "Verificação de Tor esquecido: ${e.message}")
        }
    }

    private fun portaOcupada(): Int? {
        for (porta in listOf(SOCKS_PORT, CONTROL_PORT)) {
            try {
                ServerSocket().use { s ->
                    s.reuseAddress = true
                    s.bind(InetSocketAddress("127.0.0.1", porta))
                }
            } catch (e: Exception) {
                return porta
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Porta de controle (autenticação por cookie — sem senha no código)
    // ------------------------------------------------------------------

    private fun controlar(vararg comandos: String): String? {
        if (!estaVivo()) return "O Tor não está rodando."
        val cookie = File(dataDir, "control_auth_cookie")
        if (!cookie.exists()) return "Arquivo de autenticação do Tor não encontrado."
        val hex = cookie.readBytes().joinToString("") { "%02x".format(it) }
        return try {
            Socket("127.0.0.1", CONTROL_PORT).use { s ->
                s.soTimeout = 5000
                val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                w.write("AUTHENTICATE $hex\r\n"); w.flush()
                val auth = r.readLine() ?: return "Sem resposta do Tor."
                if (!auth.startsWith("250")) return "O Tor recusou a autenticação: $auth"
                for (c in comandos) {
                    w.write("$c\r\n"); w.flush()
                    val resp = r.readLine() ?: return "Sem resposta ao comando."
                    if (!resp.startsWith("250")) return "O Tor recusou \"$c\": $resp"
                }
                w.write("QUIT\r\n"); w.flush()
                null
            }
        } catch (e: Exception) {
            "Não foi possível falar com o Tor: ${e.message}"
        }
    }

    /** Pede circuitos novos (outro IP de saída). null = deu certo. */
    fun novaIdentidade(): String? = controlar("SIGNAL NEWNYM")

    /** Muda a rotação de identidade na hora, sem reconectar. */
    fun ajustarRotacao(segundos: Int): String? = controlar("SETCONF MaxCircuitDirtiness=$segundos")

    /**
     * A rede do aparelho mudou (Wi-Fi ↔ dados móveis). As conexões antigas do
     * Tor morreram junto com a rede antiga; desligar e religar a rede dele
     * faz o Tor refazer tudo na hora em vez de esperar os tempos-limite.
     */
    fun redeMudou(): String? = controlar("SETCONF DisableNetwork=1", "SETCONF DisableNetwork=0", "SIGNAL ACTIVE")

    /** Sem internet no aparelho: o Tor para de tentar (poupa bateria). */
    fun pausarRede(): String? = controlar("SETCONF DisableNetwork=1")

    /** A internet voltou: o Tor volta a conectar na hora. */
    fun retomarRede(): String? = controlar("SETCONF DisableNetwork=0", "SIGNAL ACTIVE")

    /**
     * Avisa a escada de conexão para parar e pede ao Tor para sair.
     * Não espera nada: pode ser chamado de qualquer linha, até da principal.
     */
    fun sinalizarParada() {
        stopRequested.set(true)
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
    }

    /**
     * Para o Tor sem bloquear quem chamou. Se em meio segundo ele ainda não
     * saiu, é encerrado à força; o arquivo de pid só é apagado depois que ele
     * sumiu de verdade (assim o próximo início sabe quem encerrar).
     */
    fun stop() {
        stopRequested.set(true)
        val p = process
        process = null
        isReady = false
        bootstrapPercent = 0
        val pidAntigo = lerPid()
        if (p == null) {
            apagarPid(pidAntigo)
            return
        }
        try {
            p.destroy()
        } catch (_: Exception) {
        }
        Filas.depois(500) {
            try {
                if (p.isAlive) p.destroyForcibly()
            } catch (_: Exception) {
            }
            apagarPid(pidAntigo)
        }
    }

    private fun lerPid(): String? = try {
        if (pidFile.exists()) pidFile.readText().trim() else null
    } catch (_: Exception) {
        null
    }

    /** Apaga o arquivo de pid só se ainda for o do Tor que foi parado. */
    private fun apagarPid(pidEsperado: String?) {
        try {
            if (lerPid() == pidEsperado) pidFile.delete()
        } catch (_: Exception) {
        }
    }
}
