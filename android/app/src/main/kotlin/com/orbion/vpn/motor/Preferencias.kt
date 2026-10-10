package com.orbion.vpn.motor

import android.content.Context
import android.content.SharedPreferences

/**
 * Configurações do Orbion, guardadas no próprio aparelho.
 *
 * Fica do lado nativo de propósito: o serviço da VPN precisa ler estes valores
 * mesmo com a tela fechada (no boot, na reconexão do Kill Switch, quando o
 * Android religa a "VPN sempre ativa"). A tela em Flutter lê e grava pelo
 * canal "orbion/motor".
 */
class Preferencias(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    var killSwitch: Boolean
        get() = sp.getBoolean(KILL_SWITCH, true)
        set(v) = sp.edit().putBoolean(KILL_SWITCH, v).apply()

    var bloquearRastreadores: Boolean
        get() = sp.getBoolean(BLOQUEAR_RASTREADORES, true)
        set(v) = sp.edit().putBoolean(BLOQUEAR_RASTREADORES, v).apply()

    var desvioDireto: Boolean
        get() = sp.getBoolean(DESVIO_DIRETO, true)
        set(v) = sp.edit().putBoolean(DESVIO_DIRETO, v).apply()

    var capturarIPv6: Boolean
        get() = sp.getBoolean(CAPTURAR_IPV6, true)
        set(v) = sp.edit().putBoolean(CAPTURAR_IPV6, v).apply()

    var escolherApps: Boolean
        get() = sp.getBoolean(SPLIT_TUNNEL, false)
        set(v) = sp.edit().putBoolean(SPLIT_TUNNEL, v).apply()

    var appsForaDoTunel: Set<String>
        get() = sp.getStringSet(EXCLUDED_APPS, emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(EXCLUDED_APPS, HashSet(v)).apply()

    var intervaloRotacaoSegundos: Int
        get() = sp.getInt(INTERVALO_ROTACAO, 600).coerceIn(60, 3600)
        set(v) = sp.edit().putInt(INTERVALO_ROTACAO, v.coerceIn(60, 3600)).apply()

    var conectarAoAbrir: Boolean
        get() = sp.getBoolean(CONECTAR_AO_ABRIR, false)
        set(v) = sp.edit().putBoolean(CONECTAR_AO_ABRIR, v).apply()

    var conectarNoBoot: Boolean
        get() = sp.getBoolean(CONECTAR_NO_BOOT, false)
        set(v) = sp.edit().putBoolean(CONECTAR_NO_BOOT, v).apply()

    var usarBridges: Boolean
        get() = sp.getBoolean(USAR_BRIDGES, false)
        set(v) = sp.edit().putBoolean(USAR_BRIDGES, v).apply()

    var bridges: String
        get() = sp.getString(BRIDGES, "") ?: ""
        set(v) = sp.edit().putString(BRIDGES, v).apply()

    /** Contorno automático (escada: direto → snowflake → obfs4). */
    var contornoAutomatico: Boolean
        get() = sp.getBoolean(CONTORNO_AUTOMATICO, true)
        set(v) = sp.edit().putBoolean(CONTORNO_AUTOMATICO, v).apply()

    /** Avisos que a pessoa pediu para não mostrar mais. */
    var avisosDispensados: Set<String>
        get() = sp.getStringSet(AVISOS_DISPENSADOS, emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(AVISOS_DISPENSADOS, HashSet(v)).apply()

    /**
     * "VPN sempre ativa" como o serviço viu da última vez que rodou:
     * -1 = nunca verificado, 0 = desligada, 1 = ligada.
     */
    var sempreAtivaConhecida: Int
        get() = sp.getInt(SEMPRE_ATIVA_CONHECIDA, -1)
        set(v) = sp.edit().putInt(SEMPRE_ATIVA_CONHECIDA, v).apply()

    /** A VPN estava ligada quando o app foi fechado? Usado na reabertura. */
    var estavaLigada: Boolean
        get() = sp.getBoolean(ESTAVA_LIGADA, false)
        set(v) = sp.edit().putBoolean(ESTAVA_LIGADA, v).apply()

    fun comoMapa(): Map<String, Any> = mapOf(
        "killSwitch" to killSwitch,
        "bloquearRastreadores" to bloquearRastreadores,
        "desvioDireto" to desvioDireto,
        "capturarIPv6" to capturarIPv6,
        "escolherApps" to escolherApps,
        "appsForaDoTunel" to appsForaDoTunel.toList(),
        "intervaloRotacaoSegundos" to intervaloRotacaoSegundos,
        "conectarAoAbrir" to conectarAoAbrir,
        "conectarNoBoot" to conectarNoBoot,
        "usarBridges" to usarBridges,
        "bridges" to bridges,
        "contornoAutomatico" to contornoAutomatico,
        "avisosDispensados" to avisosDispensados.toList(),
    )

    /** Grava só as chaves conhecidas que vierem no mapa. */
    fun aplicar(mapa: Map<*, *>) {
        for ((chave, valor) in mapa) {
            when (chave) {
                "killSwitch" -> (valor as? Boolean)?.let { killSwitch = it }
                "bloquearRastreadores" -> (valor as? Boolean)?.let { bloquearRastreadores = it }
                "desvioDireto" -> (valor as? Boolean)?.let { desvioDireto = it }
                "capturarIPv6" -> (valor as? Boolean)?.let { capturarIPv6 = it }
                "escolherApps" -> (valor as? Boolean)?.let { escolherApps = it }
                "appsForaDoTunel" -> (valor as? List<*>)?.let { lista ->
                    appsForaDoTunel = lista.filterIsInstance<String>().toSet()
                }
                "intervaloRotacaoSegundos" -> (valor as? Number)?.let {
                    intervaloRotacaoSegundos = it.toInt()
                }
                "conectarAoAbrir" -> (valor as? Boolean)?.let { conectarAoAbrir = it }
                "conectarNoBoot" -> (valor as? Boolean)?.let { conectarNoBoot = it }
                "usarBridges" -> (valor as? Boolean)?.let { usarBridges = it }
                "bridges" -> (valor as? String)?.let { bridges = it }
                "contornoAutomatico" -> (valor as? Boolean)?.let { contornoAutomatico = it }
                "avisosDispensados" -> (valor as? List<*>)?.let { lista ->
                    avisosDispensados = lista.filterIsInstance<String>().toSet()
                }
            }
        }
    }

    companion object {
        private const val ARQUIVO = "orbion_settings"
        private const val KILL_SWITCH = "kill_switch"
        private const val BLOQUEAR_RASTREADORES = "block_trackers"
        private const val DESVIO_DIRETO = "desvio_direto"
        private const val CAPTURAR_IPV6 = "capturar_ipv6"
        private const val SPLIT_TUNNEL = "split_tunnel"
        private const val EXCLUDED_APPS = "excluded_apps"
        private const val INTERVALO_ROTACAO = "intervalo_rotacao"
        private const val CONECTAR_AO_ABRIR = "conectar_ao_abrir"
        private const val CONECTAR_NO_BOOT = "conectar_no_boot"
        private const val USAR_BRIDGES = "usar_bridges"
        private const val BRIDGES = "bridges"
        private const val CONTORNO_AUTOMATICO = "contorno_automatico"
        private const val AVISOS_DISPENSADOS = "avisos_dispensados"
        private const val ESTAVA_LIGADA = "estava_ligada"
        private const val SEMPRE_ATIVA_CONHECIDA = "sempre_ativa_conhecida"
    }
}
