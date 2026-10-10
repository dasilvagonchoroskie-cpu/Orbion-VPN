package com.orbion.vpn.motor

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Os caminhos de entrada na rede Tor — a "escada" de contorno de bloqueio.
 *
 *   1. DIRETO     — entrada normal, pelos relés públicos. Mais rápida.
 *   2. SNOWFLAKE  — voluntários rotativos (WebRTC). Atravessa a maioria dos
 *                   bloqueios, sem precisar de endereço nenhum.
 *   3. OBFS4      — entrada disfarçada. Usa as bridges que você colar nas
 *                   configurações ou, se não houver, as bridges públicas que
 *                   vêm embutidas no pacote oficial do Tor.
 *
 * O app só sobe um degrau quando o anterior falha. Quando um degrau
 * funciona, ele é lembrado e passa a ser o primeiro da próxima vez.
 */
object Transportes {

    private const val TAG = "OrbionTransportes"

    const val BINARIO_TOR = "libtor.so"
    const val BINARIO_OBFS4 = "libobfs4proxy.so"
    const val BINARIO_SNOWFLAKE = "libsnowflake.so"

    enum class Caminho { DIRETO, SNOWFLAKE, OBFS4 }

    /**
     * Linhas de reserva, usadas só se o arquivo bridges_embutidas.json não
     * vier no pacote. A esteira do GitHub gera esse arquivo com as linhas
     * ATUAIS do pacote oficial do Tor, então normalmente estas aqui nem são
     * usadas.
     */
    private val SNOWFLAKE_RESERVA = listOf(
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "url=https://1098762253.rsc.cdn77.org/ fronts=www.cdn77.com,www.phpmyadmin.net " +
            "ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478," +
            "stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478," +
            "stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 utls-imitate=hellorandomizedalpn",
        "snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA " +
            "fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA " +
            "url=https://1098762253.rsc.cdn77.org/ fronts=www.cdn77.com,www.phpmyadmin.net " +
            "ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478," +
            "stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478," +
            "stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 utls-imitate=hellorandomizedalpn",
    )

    @Volatile private var embutidasSnowflake: List<String>? = null
    @Volatile private var embutidasObfs4: List<String>? = null
    @Volatile var origemDasEmbutidas: String = "reserva"
        private set

    // ------------------------------------------------------------------
    // Binários
    // ------------------------------------------------------------------

    fun binario(context: Context, nome: String): File? {
        val arquivo = File(context.applicationInfo.nativeLibraryDir, nome)
        return if (arquivo.exists() && arquivo.canExecute()) arquivo else null
    }

    fun torDisponivel(context: Context) = binario(context, BINARIO_TOR) != null
    fun obfs4Disponivel(context: Context) = binario(context, BINARIO_OBFS4) != null
    fun snowflakeDisponivel(context: Context) = binario(context, BINARIO_SNOWFLAKE) != null

    // ------------------------------------------------------------------
    // Bridges embutidas (geradas pela esteira a partir do pt_config.json)
    // ------------------------------------------------------------------

    private fun carregarEmbutidas(context: Context) {
        if (embutidasSnowflake != null) return
        try {
            val texto = context.assets.open("bridges_embutidas.json").bufferedReader().use { it.readText() }
            val json = JSONObject(texto)
            fun lista(chave: String): List<String> {
                val arr = json.optJSONArray(chave) ?: return emptyList()
                return (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
            }
            val sf = lista("snowflake")
            val o4 = lista("obfs4")
            embutidasSnowflake = sf.ifEmpty { SNOWFLAKE_RESERVA }
            embutidasObfs4 = o4
            origemDasEmbutidas = json.optString("origem", "pacote oficial do Tor")
            Log.d(TAG, "Bridges embutidas: ${sf.size} snowflake, ${o4.size} obfs4 ($origemDasEmbutidas)")
        } catch (e: Exception) {
            Log.w(TAG, "Sem bridges_embutidas.json, usando reserva: ${e.message}")
            embutidasSnowflake = SNOWFLAKE_RESERVA
            embutidasObfs4 = emptyList()
            origemDasEmbutidas = "reserva"
        }
    }

    fun quantasObfs4Embutidas(context: Context): Int {
        carregarEmbutidas(context)
        return embutidasObfs4?.size ?: 0
    }

    // ------------------------------------------------------------------
    // Linhas de bridge digitadas pela pessoa
    // ------------------------------------------------------------------

    /** Linhas válidas que a pessoa colou, já sem o prefixo "Bridge ". */
    fun linhasDaPessoa(texto: String): List<String> =
        texto.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { if (it.startsWith("Bridge ", ignoreCase = true)) it.substring(7).trim() else it }
            .filter { it.isNotEmpty() }

    fun exigeObfs4(linha: String): Boolean = linha.trim().startsWith("obfs4", ignoreCase = true)

    private fun linhasObfs4(context: Context, prefs: Preferencias): List<String> {
        val daPessoa = if (prefs.usarBridges) linhasDaPessoa(prefs.bridges).filter { exigeObfs4(it) } else emptyList()
        if (daPessoa.isNotEmpty()) return daPessoa
        carregarEmbutidas(context)
        return embutidasObfs4 ?: emptyList()
    }

    // ------------------------------------------------------------------
    // A escada
    // ------------------------------------------------------------------

    fun disponivel(context: Context, prefs: Preferencias, caminho: Caminho): Boolean = when (caminho) {
        Caminho.DIRETO -> true
        Caminho.SNOWFLAKE -> snowflakeDisponivel(context)
        Caminho.OBFS4 -> obfs4Disponivel(context) && linhasObfs4(context, prefs).isNotEmpty()
    }

    fun montarEscada(context: Context, prefs: Preferencias): List<Caminho> {
        // Bridges ligadas à mão: a pessoa quer a entrada disfarçada primeiro.
        val primeiro: Caminho? = if (prefs.usarBridges && disponivel(context, prefs, Caminho.OBFS4)) {
            Caminho.OBFS4
        } else {
            lembrado(context)
        }
        val ordem = if (prefs.contornoAutomatico || prefs.usarBridges) {
            listOf(Caminho.DIRETO, Caminho.SNOWFLAKE, Caminho.OBFS4)
        } else {
            listOf(Caminho.DIRETO)
        }
        val base = if (primeiro != null && (primeiro in ordem)) listOf(primeiro) + ordem else ordem
        return base.distinct().filter { disponivel(context, prefs, it) }
    }

    fun nome(caminho: Caminho): String = when (caminho) {
        Caminho.DIRETO -> "entrada normal"
        Caminho.SNOWFLAKE -> "voluntários rotativos (Snowflake)"
        Caminho.OBFS4 -> "entrada disfarçada (obfs4)"
    }

    fun oQueFalta(context: Context, prefs: Preferencias, caminho: Caminho): String? = when (caminho) {
        Caminho.DIRETO -> null
        Caminho.SNOWFLAKE ->
            if (snowflakeDisponivel(context)) null
            else "O contorno por voluntários rotativos (Snowflake) não veio nesta versão do aplicativo."
        Caminho.OBFS4 -> when {
            !obfs4Disponivel(context) -> "A entrada disfarçada (obfs4) não veio nesta versão do aplicativo."
            linhasObfs4(context, prefs).isEmpty() ->
                "Faltam endereços de entrada disfarçada. Pegue os seus em bridges.torproject.org e cole nas configurações."
            else -> null
        }
    }

    /** Trecho do torrc para o degrau escolhido. */
    fun blocoDeConfiguracao(context: Context, prefs: Preferencias, caminho: Caminho): String {
        val sb = StringBuilder()
        when (caminho) {
            Caminho.DIRETO -> Unit
            Caminho.SNOWFLAKE -> {
                val exe = binario(context, BINARIO_SNOWFLAKE) ?: return ""
                carregarEmbutidas(context)
                sb.append("UseBridges 1\n")
                sb.append("ClientTransportPlugin snowflake exec ").append(exe.absolutePath).append('\n')
                for (linha in embutidasSnowflake ?: SNOWFLAKE_RESERVA) {
                    sb.append("Bridge ").append(linha).append('\n')
                }
            }
            Caminho.OBFS4 -> {
                val exe = binario(context, BINARIO_OBFS4) ?: return ""
                val linhas = linhasObfs4(context, prefs)
                if (linhas.isEmpty()) return ""
                sb.append("UseBridges 1\n")
                // O lyrebird atende obfs4, meek_lite e webtunnel.
                sb.append("ClientTransportPlugin obfs4,meek_lite,webtunnel exec ").append(exe.absolutePath).append('\n')
                for (linha in linhas) sb.append("Bridge ").append(linha).append('\n')
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // Memória do último degrau que funcionou
    // ------------------------------------------------------------------

    private fun arquivoDeMemoria(context: Context) = File(context.filesDir, "ultimo_caminho")

    fun lembrar(context: Context, caminho: Caminho) {
        try {
            arquivoDeMemoria(context).writeText(caminho.name)
        } catch (e: Exception) {
            Log.w(TAG, "Não deu para lembrar o caminho: ${e.message}")
        }
    }

    fun lembrado(context: Context): Caminho? = try {
        Caminho.valueOf(arquivoDeMemoria(context).readText().trim())
    } catch (e: Exception) {
        null
    }

    fun esquecer(context: Context) {
        try {
            arquivoDeMemoria(context).delete()
        } catch (_: Exception) {
        }
    }
}
