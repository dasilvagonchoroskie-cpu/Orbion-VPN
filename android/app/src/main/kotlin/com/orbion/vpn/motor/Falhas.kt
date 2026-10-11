package com.orbion.vpn.motor

import android.content.Context
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Erros internos que nenhum código tratou ("crash").
 *
 * Na 6.1.1 o processo da VPN caiu duas vezes logo depois de uma troca de
 * Wi-Fi para dados móveis, e o Android só informa "o app teve um erro interno"
 * — sem dizer onde. A partir daqui:
 *  - o rastro completo de qualquer erro não tratado vai para `falhas.log`
 *    (entra no "Enviar relatório"), nos dois processos;
 *  - no processo da VPN, um erro numa linha de fundo (uma conexão, uma
 *    leitura) encerra só aquela linha: a VPN continua no ar, em vez de cair e
 *    deixar o celular sem proteção até alguém abrir o app. Erro na linha
 *    principal segue o caminho normal do Android (ela não tem como continuar).
 */
object Falhas {

    private const val TAG = "OrbionFalhas"
    private const val ARQUIVO = "falhas.log"
    private const val LIMITE_BYTES = 96_000L
    private const val MANTER_BYTES = 48_000

    @Volatile private var instalado = false

    fun instalar(context: Context, processo: String, conterNoFundo: Boolean) {
        if (instalado) return
        instalado = true
        val app = context.applicationContext ?: context
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { linha, erro ->
            try {
                gravar(app, processo, linha, erro)
            } catch (_: Throwable) {
            }
            val principal = try {
                linha === Looper.getMainLooper().thread
            } catch (_: Throwable) {
                true
            }
            if (conterNoFundo && !principal) {
                try {
                    Log.e(TAG, "Erro contido na linha ${linha.name}", erro)
                    Eventos.anotar(
                        app,
                        "Erro interno contido (linha \"${linha.name}\": ${erro.javaClass.simpleName}). A VPN continuou no ar.",
                    )
                } catch (_: Throwable) {
                }
                return@setDefaultUncaughtExceptionHandler
            }
            if (anterior != null) {
                anterior.uncaughtException(linha, erro)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    @Synchronized
    private fun gravar(context: Context, processo: String, linha: Thread, erro: Throwable) {
        val texto = StringWriter().also { erro.printStackTrace(PrintWriter(it)) }.toString()
            .lineSequence().take(80).joinToString("\n")
        val quando = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ROOT).format(Date())
        val f = File(context.filesDir, ARQUIVO)
        f.appendText("==== $quando • processo $processo • linha \"${linha.name}\" ====\n$texto\n\n")
        if (f.length() > LIMITE_BYTES) {
            val fim = lerFim(f, MANTER_BYTES)
            f.writeText(fim)
        }
    }

    /** Fim do arquivo de falhas (mais recente no fim), ou null se não houve nenhuma. */
    fun ler(context: Context, maxBytes: Int = 32_000): String? {
        val f = File(context.filesDir, ARQUIVO)
        if (!f.exists() || f.length() == 0L) return null
        return try {
            lerFim(f, maxBytes)
        } catch (e: Exception) {
            "Não foi possível ler: ${e.message}"
        }
    }

    private fun lerFim(f: File, maxBytes: Int): String = RandomAccessFile(f, "r").use { raf ->
        val tamanho = raf.length()
        val janela = minOf(tamanho, maxBytes.toLong())
        raf.seek(tamanho - janela)
        val bytes = ByteArray(janela.toInt())
        raf.readFully(bytes)
        val texto = String(bytes, Charsets.UTF_8)
        // Se cortou no meio, começa no próximo registro inteiro.
        if (janela < tamanho) texto.substringAfter("\n==== ", texto).let { if (it === texto) it else "==== $it" } else texto
    }
}
