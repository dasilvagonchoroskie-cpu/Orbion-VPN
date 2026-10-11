package com.orbion.vpn.motor

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diário do Orbion: o que aconteceu com a VPN (ligou, caiu, religou, foi
 * fechada pelo Android...) e POR QUE o Android encerrou o app.
 *
 * O "por quê" vem do próprio Android (ApplicationExitInfo, Android 11+):
 * pouca memória, travamento, limpador da marca, "Forçar parada"... Quando o
 * motivo é travamento, guarda também o rastro da linha principal — é a
 * prova de onde o app parou.
 */
object Eventos {

    private const val TAG = "OrbionEventos"
    private const val ARQUIVO = "eventos.log"
    private const val MAX_BYTES = 48_000L
    private const val LINHAS_APOS_CORTE = 150

    data class Evento(val quando: Long, val texto: String)

    @Synchronized
    fun anotar(context: Context, texto: String) {
        Log.i(TAG, texto)
        try {
            val f = File(context.filesDir, ARQUIVO)
            f.appendText("${System.currentTimeMillis()}\t${texto.replace('\n', ' ').replace('\t', ' ')}\n")
            if (f.length() > MAX_BYTES) {
                val ultimas = f.readLines().takeLast(LINHAS_APOS_CORTE)
                f.writeText(ultimas.joinToString("\n", postfix = "\n"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível anotar o evento: ${e.message}")
        }
    }

    /** Mais recentes primeiro. */
    @Synchronized
    fun ler(context: Context, max: Int): List<Evento> {
        val f = File(context.filesDir, ARQUIVO)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().takeLast(max).mapNotNull { l ->
                val i = l.indexOf('\t')
                if (i <= 0) null else l.substring(0, i).toLongOrNull()?.let { Evento(it, l.substring(i + 1)) }
            }.reversed()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun hora(ms: Long): String = SimpleDateFormat("dd/MM HH:mm", Locale.ROOT).format(Date(ms))

    // ------------------------------------------------------------------
    // Por que o Android fechou o Orbion (Android 11+)
    // ------------------------------------------------------------------

    data class Fechamento(
        val processo: String,
        val quando: Long,
        val codigo: Int,
        val explicacao: String,
        val descricao: String,
        val importancia: Int,
        /** true = não foi a pessoa desligando nem uma atualização: o sistema derrubou. */
        val inesperado: Boolean,
        /** Rastro da linha principal, só quando o motivo é travamento. */
        val rastro: String?,
        /** Aconteceu antes da instalação desta versão do Orbion. */
        val anterior: Boolean = false,
    ) {
        val daVpn: Boolean get() = processo.endsWith(":vpn")

        fun comoMapa(): Map<String, Any?> = mapOf(
            "processo" to if (daVpn) "vpn" else "tela",
            "quando" to quando,
            "codigo" to codigo,
            "explicacao" to explicacao,
            "descricao" to descricao,
            "inesperado" to inesperado,
            "rastro" to rastro,
            "anterior" to anterior,
        )
    }

    /** Quando esta versão do Orbion foi instalada (0 = não sabe). */
    fun instaladaEm(context: Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    } catch (_: Exception) {
        0L
    }

    fun fechamentos(context: Context, max: Int = 16, comRastro: Boolean = false): List<Fechamento> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        return try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            val desde = instaladaEm(context)
            am.getHistoricalProcessExitReasons(context.packageName, 0, max).map { info ->
                Fechamento(
                    processo = info.processName ?: "",
                    quando = info.timestamp,
                    codigo = info.reason,
                    explicacao = explicar(info.reason, info.description, info.status),
                    descricao = info.description ?: "",
                    importancia = info.importance,
                    inesperado = inesperado(info.reason, info.importance),
                    rastro = if (comRastro && info.reason == ApplicationExitInfo.REASON_ANR) lerRastro(info) else null,
                    // A própria atualização (motivo 15/16) fecha o app um instante
                    // ANTES de a versão nova ser gravada: também é "de antes".
                    anterior = desde > 0 && info.timestamp < desde,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sem histórico de fechamentos: ${e.message}")
            emptyList()
        }
    }

    /** O último fechamento inesperado do processo da VPN, se houver. */
    fun ultimoFechamentoDaVpn(context: Context): Fechamento? =
        fechamentos(context, 10).filter { it.daVpn && it.inesperado }.maxByOrNull { it.quando }

    private fun inesperado(motivo: Int, importancia: Int): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        return when (motivo) {
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> true
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> false
            // 15 e 16: mudança de estado do pacote / atualização (Android 14+).
            15, 16 -> false
            // O resto (pouca memória, sinal, "Forçar parada", outros) só conta
            // se o processo estava em uso — senão é a limpeza normal de um
            // processo parado, que não afeta nada.
            else -> importancia <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE
        }
    }

    fun explicar(motivo: Int, descricao: String?, status: Int): String {
        val d = descricao?.lowercase(Locale.ROOT) ?: ""
        return when (motivo) {
            3 -> "o Android fechou o Orbion para liberar memória"
            6 -> "o app travou e o Android ofereceu fechar (\"não está respondendo\")"
            4 -> "o app teve um erro interno e fechou"
            5 -> "um componente nativo teve um erro e fechou"
            10 -> "foi usado \"Forçar parada\" ou o limpador de memória da marca do celular"
            9 -> "o Android achou que o Orbion gastava demais em segundo plano"
            14 -> "o Android congelou o Orbion em segundo plano"
            8 -> "uma permissão do Orbion foi alterada"
            7 -> "o app não conseguiu iniciar"
            12 -> "um serviço do qual o Orbion dependia foi encerrado"
            11 -> "o usuário do aparelho foi trocado"
            1 -> "o próprio Orbion encerrou o processo"
            15, 16 -> "o Orbion foi atualizado ou reinstalado"
            2 -> when {
                "swipe" in d || "clean" in d || "recent" in d -> "o celular encerrou o Orbion ao limpar a tela de apps recentes"
                "power" in d || "battery" in d || "freez" in d -> "a economia de bateria do celular encerrou o Orbion"
                else -> "o sistema encerrou o processo (sinal $status)" + if (d.isNotBlank()) " — $descricao" else ""
            }
            else -> when {
                "remove task" in d -> "o app foi fechado na tela de recentes e o sistema encerrou o processo"
                "swipe" in d || "clean" in d -> "o celular encerrou o Orbion ao limpar a tela de apps recentes"
                "power" in d || "battery" in d -> "a economia de bateria do celular encerrou o Orbion"
                "empty" in d || "cached" in d || "trim" in d -> "o Android encerrou o processo parado"
                d.isNotBlank() -> "o sistema encerrou o processo ($descricao)"
                else -> "o sistema encerrou o processo"
            }
        }
    }

    /** Extrai a linha principal ("main") do relatório de travamento. */
    private fun lerRastro(info: ApplicationExitInfo): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val texto = info.traceInputStream?.use { s ->
                val buf = ByteArray(384 * 1024)
                var total = 0
                while (total < buf.size) {
                    val n = s.read(buf, total, buf.size - total)
                    if (n < 0) break
                    total += n
                }
                String(buf, 0, total, Charsets.UTF_8)
            } ?: return null
            val linhas = texto.lines()
            val inicio = linhas.indexOfFirst { it.startsWith("\"main\"") }
            val trecho = if (inicio >= 0) {
                linhas.drop(inicio).takeWhile { it.isNotBlank() }.take(70)
            } else {
                linhas.take(70)
            }
            trecho.joinToString("\n")
        } catch (e: Exception) {
            "Rastro indisponível: ${e.message}"
        }
    }
}

/**
 * Executado quando o processo da VPN nasce (antes de qualquer outra coisa).
 * Descobre se o processo anterior foi derrubado pelo Android e anota o motivo.
 */
object Inicio {

    @Volatile var ultimoFechamento: Eventos.Fechamento? = null
        private set

    fun processoIniciado(context: Context) {
        val f = Eventos.ultimoFechamentoDaVpn(context) ?: return
        val prefs = Preferencias(context)
        if (f.quando <= prefs.ultimoFechamentoVisto) return
        prefs.ultimoFechamentoVisto = f.quando
        ultimoFechamento = f
        Eventos.anotar(context, "O Android encerrou a VPN em ${Eventos.hora(f.quando)}: ${f.explicacao}.")
    }

    fun consumirFechamento(): Eventos.Fechamento? {
        val f = ultimoFechamento
        ultimoFechamento = null
        return f
    }
}
