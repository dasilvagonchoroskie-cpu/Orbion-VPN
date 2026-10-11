package com.orbion.vpn.motor

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log

/**
 * Configurações do Orbion, guardadas no próprio aparelho.
 *
 * DONO ÚNICO: só o processo da VPN (":vpn") abre este arquivo. A tela, que
 * roda em outro processo, lê e grava pela ponte (Ponte.kt). O Android não
 * sincroniza este tipo de arquivo entre processos — se os dois gravassem,
 * um apagaria as mudanças do outro.
 *
 * Só grava quando o valor muda: escrita em disco à toa, várias vezes por
 * minuto, é uma das causas conhecidas de "O app não está respondendo".
 */
class Preferencias(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    init {
        conferirProcesso()
        migrar()
    }

    // ------------------------------------------------------------------
    // Leitura e escrita sem gravação repetida
    // ------------------------------------------------------------------

    private fun bool(chave: String, padrao: Boolean) = sp.getBoolean(chave, padrao)

    private fun gravar(chave: String, v: Boolean) {
        if (!sp.contains(chave) || sp.getBoolean(chave, !v) != v) sp.edit().putBoolean(chave, v).apply()
    }

    private fun gravar(chave: String, v: Int) {
        if (!sp.contains(chave) || sp.getInt(chave, v - 1) != v) sp.edit().putInt(chave, v).apply()
    }

    private fun gravar(chave: String, v: Long) {
        if (!sp.contains(chave) || sp.getLong(chave, v - 1) != v) sp.edit().putLong(chave, v).apply()
    }

    private fun gravar(chave: String, v: String) {
        if (sp.getString(chave, null) != v) sp.edit().putString(chave, v).apply()
    }

    private fun conjunto(chave: String): Set<String> = sp.getStringSet(chave, emptySet())?.toSet() ?: emptySet()

    private fun gravar(chave: String, v: Set<String>) {
        if (conjunto(chave) != v || !sp.contains(chave)) sp.edit().putStringSet(chave, HashSet(v)).apply()
    }

    // ------------------------------------------------------------------
    // Proteção
    // ------------------------------------------------------------------

    var killSwitch: Boolean
        get() = bool(KILL_SWITCH, true)
        set(v) = gravar(KILL_SWITCH, v)

    var bloquearRastreadores: Boolean
        get() = bool(BLOQUEAR_RASTREADORES, true)
        set(v) = gravar(BLOQUEAR_RASTREADORES, v)

    var capturarIPv6: Boolean
        get() = bool(CAPTURAR_IPV6, true)
        set(v) = gravar(CAPTURAR_IPV6, v)

    var intervaloRotacaoSegundos: Int
        get() = sp.getInt(INTERVALO_ROTACAO, 600).coerceIn(60, 3600)
        set(v) = gravar(INTERVALO_ROTACAO, v.coerceIn(60, 3600))

    // ------------------------------------------------------------------
    // Rota inteligente
    // ------------------------------------------------------------------

    /** Chave mestra: apps e sites que recusam o Tor vão por fora sozinhos. */
    var rotaInteligente: Boolean
        get() = bool(ROTA_INTELIGENTE, true)
        set(v) = gravar(ROTA_INTELIGENTE, v)

    /** Categorias que a pessoa desligou (as novas já nascem ligadas). */
    var categoriasDesligadas: Set<String>
        get() = conjunto(CATEGORIAS_DESLIGADAS)
        set(v) = gravar(CATEGORIAS_DESLIGADAS, v)

    /** Desviar sozinho os sites que falham repetidamente pela rede Tor. */
    var desvioAutomatico: Boolean
        get() = bool(DESVIO_AUTOMATICO, true)
        set(v) = gravar(DESVIO_AUTOMATICO, v)

    /** Apps que a pessoa tirou do túnel. */
    var appsForaDoTunel: Set<String>
        get() = conjunto(EXCLUDED_APPS)
        set(v) = gravar(EXCLUDED_APPS, v)

    /** Apps que a pessoa quer SEMPRE pelo Tor, mesmo sendo de uma categoria desviada. */
    var appsSempreNoTor: Set<String>
        get() = conjunto(APPS_SEMPRE_TOR)
        set(v) = gravar(APPS_SEMPRE_TOR, v)

    var dominiosDiretos: Set<String>
        get() = conjunto(DOMINIOS_DIRETOS)
        set(v) = gravar(DOMINIOS_DIRETOS, v)

    var dominiosSempreTor: Set<String>
        get() = conjunto(DOMINIOS_TOR)
        set(v) = gravar(DOMINIOS_TOR, v)

    // ------------------------------------------------------------------
    // Automático e rede Tor
    // ------------------------------------------------------------------

    var conectarAoAbrir: Boolean
        get() = bool(CONECTAR_AO_ABRIR, false)
        set(v) = gravar(CONECTAR_AO_ABRIR, v)

    var conectarNoBoot: Boolean
        get() = bool(CONECTAR_NO_BOOT, false)
        set(v) = gravar(CONECTAR_NO_BOOT, v)

    var usarBridges: Boolean
        get() = bool(USAR_BRIDGES, false)
        set(v) = gravar(USAR_BRIDGES, v)

    var bridges: String
        get() = sp.getString(BRIDGES, "") ?: ""
        set(v) = gravar(BRIDGES, v)

    /** Contorno automático (escada: direto → snowflake → obfs4). */
    var contornoAutomatico: Boolean
        get() = bool(CONTORNO_AUTOMATICO, true)
        set(v) = gravar(CONTORNO_AUTOMATICO, v)

    // ------------------------------------------------------------------
    // Estado
    // ------------------------------------------------------------------

    /** Avisos que a pessoa pediu para não mostrar mais. */
    var avisosDispensados: Set<String>
        get() = conjunto(AVISOS_DISPENSADOS)
        set(v) = gravar(AVISOS_DISPENSADOS, v)

    /** "VPN sempre ativa" como o serviço viu: -1 = nunca, 0 = desligada, 1 = ligada. */
    var sempreAtivaConhecida: Int
        get() = sp.getInt(SEMPRE_ATIVA_CONHECIDA, -1)
        set(v) = gravar(SEMPRE_ATIVA_CONHECIDA, v)

    /**
     * A pessoa quer a VPN ligada? Vira true ao ligar, false quando a pessoa
     * desliga ou quando a conexão falha de vez. Se o Android derrubar o
     * processo, fica true — e é assim que o Orbion sabe que precisa religar.
     */
    var estavaLigada: Boolean
        get() = bool(ESTAVA_LIGADA, false)
        set(v) = gravar(ESTAVA_LIGADA, v)

    /** Último fechamento do Android já anotado no diário. */
    var ultimoFechamentoVisto: Long
        get() = sp.getLong(ULTIMO_FECHAMENTO, 0L)
        set(v) = gravar(ULTIMO_FECHAMENTO, v)

    fun comoMapa(): Map<String, Any> = mapOf(
        "killSwitch" to killSwitch,
        "bloquearRastreadores" to bloquearRastreadores,
        "capturarIPv6" to capturarIPv6,
        "intervaloRotacaoSegundos" to intervaloRotacaoSegundos,
        "rotaInteligente" to rotaInteligente,
        "categoriasDesligadas" to categoriasDesligadas.toList(),
        "desvioAutomatico" to desvioAutomatico,
        "appsForaDoTunel" to appsForaDoTunel.toList(),
        "appsSempreNoTor" to appsSempreNoTor.toList(),
        "dominiosDiretos" to dominiosDiretos.sorted(),
        "dominiosSempreTor" to dominiosSempreTor.sorted(),
        "conectarAoAbrir" to conectarAoAbrir,
        "conectarNoBoot" to conectarNoBoot,
        "usarBridges" to usarBridges,
        "bridges" to bridges,
        "contornoAutomatico" to contornoAutomatico,
        "avisosDispensados" to avisosDispensados.toList(),
    )

    private fun textos(valor: Any?): Set<String>? = (valor as? List<*>)?.filterIsInstance<String>()?.toSet()

    private fun dominios(valor: Any?): Set<String>? =
        textos(valor)?.mapNotNull { RotaInteligente.normalizarDominio(it) }?.toSet()

    /** Grava só as chaves conhecidas. Devolve as que mudaram de verdade. */
    fun aplicar(mapa: Map<*, *>): Set<String> {
        val antes = comoMapa()
        for ((chave, valor) in mapa) {
            when (chave) {
                "killSwitch" -> (valor as? Boolean)?.let { killSwitch = it }
                "bloquearRastreadores" -> (valor as? Boolean)?.let { bloquearRastreadores = it }
                "capturarIPv6" -> (valor as? Boolean)?.let { capturarIPv6 = it }
                "intervaloRotacaoSegundos" -> (valor as? Number)?.let { intervaloRotacaoSegundos = it.toInt() }
                "rotaInteligente" -> (valor as? Boolean)?.let { rotaInteligente = it }
                "categoriasDesligadas" -> textos(valor)?.let { categoriasDesligadas = it }
                "desvioAutomatico" -> (valor as? Boolean)?.let { desvioAutomatico = it }
                "appsForaDoTunel" -> textos(valor)?.let { appsForaDoTunel = it }
                "appsSempreNoTor" -> textos(valor)?.let { appsSempreNoTor = it }
                "dominiosDiretos" -> dominios(valor)?.let { dominiosDiretos = it }
                "dominiosSempreTor" -> dominios(valor)?.let { dominiosSempreTor = it }
                "conectarAoAbrir" -> (valor as? Boolean)?.let { conectarAoAbrir = it }
                "conectarNoBoot" -> (valor as? Boolean)?.let { conectarNoBoot = it }
                "usarBridges" -> (valor as? Boolean)?.let { usarBridges = it }
                "bridges" -> (valor as? String)?.let { bridges = it }
                "contornoAutomatico" -> (valor as? Boolean)?.let { contornoAutomatico = it }
                "avisosDispensados" -> textos(valor)?.let { avisosDispensados = it }
            }
        }
        val depois = comoMapa()
        return depois.keys.filter { antes[it] != depois[it] }.toSet()
    }

    /** Tira o app da rota manual que estiver e coloca na pedida. */
    fun definirRotaDoApp(pacote: String, pelaTor: Boolean, categoriaAutomatica: Boolean) {
        val fora = appsForaDoTunel.toMutableSet()
        val dentro = appsSempreNoTor.toMutableSet()
        fora.remove(pacote)
        dentro.remove(pacote)
        if (pelaTor) {
            // Só precisa de exceção se a rota inteligente o mandaria para fora.
            if (categoriaAutomatica) dentro += pacote
        } else if (!categoriaAutomatica) {
            fora += pacote
        }
        appsForaDoTunel = fora
        appsSempreNoTor = dentro
    }

    /** rota: "direto", "tor" ou "padrao". */
    fun definirRotaDoDominio(dominio: String, rota: String): Boolean {
        val d = RotaInteligente.normalizarDominio(dominio) ?: return false
        val diretos = dominiosDiretos.toMutableSet().apply { remove(d) }
        val tor = dominiosSempreTor.toMutableSet().apply { remove(d) }
        when (rota) {
            "direto" -> diretos += d
            "tor" -> tor += d
        }
        dominiosDiretos = diretos
        dominiosSempreTor = tor
        return true
    }

    // ------------------------------------------------------------------

    private fun migrar() {
        val versao = sp.getInt(VERSAO, 1)
        if (versao >= VERSAO_ATUAL) return
        val e = sp.edit()
        if (versao < 2) {
            // Até a 6.0.x a lista de apps só valia no modo "Escolher apps".
            // Agora toda escolha vale sempre; se o modo estava desligado, a
            // lista antiga não estava em uso e não pode passar a valer.
            if (!sp.getBoolean(SPLIT_TUNNEL_ANTIGO, false)) e.remove(EXCLUDED_APPS)
            e.remove(SPLIT_TUNNEL_ANTIGO)
        }
        e.putInt(VERSAO, VERSAO_ATUAL).apply()
    }

    private fun conferirProcesso() {
        if (avisouProcesso || Build.VERSION.SDK_INT < 28) return
        val nome = Application.getProcessName() ?: return
        if (!nome.endsWith(":vpn")) {
            avisouProcesso = true
            Log.e(TAG, "Configurações abertas fora do processo da VPN ($nome). Use a ponte.")
        }
    }

    companion object {
        private const val TAG = "OrbionPreferencias"
        @Volatile private var avisouProcesso = false

        private const val ARQUIVO = "orbion_settings"
        private const val VERSAO = "versao_config"
        private const val VERSAO_ATUAL = 2

        private const val KILL_SWITCH = "kill_switch"
        private const val BLOQUEAR_RASTREADORES = "block_trackers"
        private const val ROTA_INTELIGENTE = "desvio_direto"
        private const val CAPTURAR_IPV6 = "capturar_ipv6"
        private const val SPLIT_TUNNEL_ANTIGO = "split_tunnel"
        private const val EXCLUDED_APPS = "excluded_apps"
        private const val APPS_SEMPRE_TOR = "apps_sempre_tor"
        private const val CATEGORIAS_DESLIGADAS = "categorias_desligadas"
        private const val DESVIO_AUTOMATICO = "desvio_automatico"
        private const val DOMINIOS_DIRETOS = "dominios_diretos"
        private const val DOMINIOS_TOR = "dominios_tor"
        private const val INTERVALO_ROTACAO = "intervalo_rotacao"
        private const val CONECTAR_AO_ABRIR = "conectar_ao_abrir"
        private const val CONECTAR_NO_BOOT = "conectar_no_boot"
        private const val USAR_BRIDGES = "usar_bridges"
        private const val BRIDGES = "bridges"
        private const val CONTORNO_AUTOMATICO = "contorno_automatico"
        private const val AVISOS_DISPENSADOS = "avisos_dispensados"
        private const val ESTAVA_LIGADA = "estava_ligada"
        private const val SEMPRE_ATIVA_CONHECIDA = "sempre_ativa_conhecida"
        private const val ULTIMO_FECHAMENTO = "ultimo_fechamento_visto"
    }
}
