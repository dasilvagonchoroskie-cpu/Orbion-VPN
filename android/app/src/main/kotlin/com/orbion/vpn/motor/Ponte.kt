package com.orbion.vpn.motor

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process

/**
 * Ponte entre a tela (processo principal) e o motor (processo ":vpn").
 *
 * A tela chama `call(método, json)` e recebe um JSON de volta. As chamadas
 * chegam em linhas do Android próprias para isso (nunca na linha principal
 * do motor), e a tela sempre chama de uma linha de fundo — então nenhum dos
 * dois lados pode travar o outro.
 *
 * Não é exportada: só o próprio Orbion consegue falar com ela.
 */
class Ponte : ContentProvider() {

    companion object {
        const val AUTORIDADE = "com.orbion.vpn.ponte"
        const val CHAVE = "json"
        const val ERRO = "__erro"
    }

    override fun onCreate(): Boolean {
        // O processo da VPN acabou de nascer: descobre se o anterior foi
        // derrubado pelo Android (em segundo plano, sem atrasar a abertura).
        val ctx = context?.applicationContext ?: return true
        Filas.avulso { Inicio.processoIniciado(ctx) }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Somente o próprio Orbion.")
        val ctx = context ?: throw IllegalStateException("Motor sem contexto")
        val args = (Json.ler(arg) as? Map<*, *>) ?: emptyMap<String, Any?>()
        val resposta: Any? = try {
            Atendente.atender(ctx, method, args)
        } catch (e: Exception) {
            mapOf(ERRO to (e.message ?: e.javaClass.simpleName))
        }
        return Bundle().apply { putString(CHAVE, Json.escrever(resposta)) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/** O que o motor sabe responder para a tela. */
object Atendente {

    fun atender(ctx: Context, metodo: String, args: Map<*, *>): Any? = when (metodo) {
        "ping" -> true
        "status" -> status(ctx)
        "desligar" -> desligar(ctx)
        "novaIdentidade" -> novaIdentidade()
        "limparAvisos" -> {
            Painel.avisoDeIdentidade = null
            Painel.contornoDeBloqueio = null
            Painel.religamento = null
            true
        }
        "lerConfig" -> Preferencias(ctx).comoMapa()
        "salvarConfig" -> {
            val p = Preferencias(ctx)
            val mudou = p.aplicar(args)
            OrbionVpnService.ativo?.configMudou(mudou)
            p.comoMapa()
        }
        "confirmar" -> {
            // A pessoa confirmou um ajuste que o Android não deixa o app conferir.
            val p = Preferencias(ctx)
            val id = args["id"] as? String ?: ""
            if (id.isNotEmpty()) p.avisosDispensados = p.avisosDispensados + (Diagnostico.PREFIXO + id)
            p.comoMapa()
        }
        "desfazerConfirmacoes" -> {
            val p = Preferencias(ctx)
            p.avisosDispensados = p.avisosDispensados.filterNot { it.startsWith(Diagnostico.PREFIXO) }.toSet()
            p.comoMapa()
        }
        "registros" -> registros((args["max"] as? Number)?.toInt() ?: 200)
        "logTecnico" -> {
            val max = (args["max"] as? Number)?.toInt() ?: 300
            mapOf("tor" to TorManager.lerLog(max), "tunel" to TunelHev.lerLog(ctx, max))
        }
        "diagnostico" -> Diagnostico.verificar(ctx).map { it.comoMapa() }
        "avisosRapidos" -> avisosRapidos(ctx)
        "infoMotor" -> infoMotor(ctx)
        "listarApps" -> RotaInteligente.listarApps(ctx, Preferencias(ctx), args["incluirSistema"] == true)
        "rotas" -> rotas(ctx)
        "rotaDeApp" -> rotaDeApp(ctx, args)
        "rotaDeDominio" -> rotaDeDominio(ctx, args)
        "eventos" -> Eventos.ler(ctx, (args["max"] as? Number)?.toInt() ?: 150).map {
            mapOf("quando" to it.quando, "texto" to it.texto)
        }
        "relatorio" -> Relatorio.montar(ctx)
        else -> throw IllegalArgumentException("Pedido desconhecido: $metodo")
    }

    private fun status(ctx: Context): Map<String, Any?> {
        val m = Painel.comoMapa().toMutableMap()
        m["servicoAtivo"] = OrbionVpnService.ativo != null
        m["estavaLigada"] = Preferencias(ctx).estavaLigada
        m["desviadosAuto"] = FiltroSocks.quantosDesviadosAuto()
        m["pausados"] = FiltroSocks.disjuntor.pausadosAgora(System.currentTimeMillis()).size
        return m
    }

    private fun desligar(ctx: Context): Boolean {
        val prefs = Preferencias(ctx)
        prefs.estavaLigada = false
        val s = OrbionVpnService.ativo
        if (s != null) {
            s.pedirDesligar("Desligada por você.")
        } else {
            Vigia.cancelar(ctx)
            Painel.desconectado()
        }
        return true
    }

    private fun novaIdentidade(): Boolean {
        Painel.avisoDeIdentidade = null
        val s = OrbionVpnService.ativo
        if (s == null) {
            Painel.avisoDeIdentidade = "Conecte primeiro para pedir uma nova identidade."
        } else {
            s.novaIdentidadePelaTela()
        }
        return true
    }

    private fun registros(max: Int): Map<String, Any?> = mapOf(
        "contagem" to FiltroSocks.contagem(),
        "registros" to FiltroSocks.registros(max).map {
            mapOf(
                "dominio" to it.dominio,
                "destino" to it.destino.name,
                "quando" to it.quando,
                "motivo" to it.motivo,
                "base" to if (it.dominio.any { c -> c.isLetter() } && ':' !in it.dominio) RotaInteligente.dominioBase(it.dominio) else null,
            )
        },
    )

    private fun avisosRapidos(ctx: Context): Map<String, Any?> {
        val dns = when (val s = Diagnostico.statusDnsPrivado(ctx)) {
            is Diagnostico.StatusDns.Desligado -> "DESLIGADO"
            is Diagnostico.StatusDns.Automatico -> "AUTOMATICO"
            is Diagnostico.StatusDns.Fixo -> "FIXO:${s.servidor}"
        }
        val notif = Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val autorizada = try {
            VpnService.prepare(ctx) == null
        } catch (_: Exception) {
            true
        }
        return mapOf(
            "bateriaIsenta" to Diagnostico.bateriaResolvida(ctx),
            "dnsPrivado" to dns,
            "notificacoes" to notif,
            "vpnAutorizada" to autorizada,
            "sempreAtiva" to Diagnostico.sempreAtivaLigada(ctx),
            "marca" to Fabricantes.marca().nome,
        )
    }

    private fun infoMotor(ctx: Context): Map<String, Any?> {
        val versaoApp = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        return mapOf(
            "versaoApp" to versaoApp,
            "versaoTor" to TorManager.versao(ctx),
            "abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "?"),
            "android" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT,
            "fabricante" to Build.MANUFACTURER,
            "modelo" to Build.MODEL,
            "marca" to Fabricantes.marca().nome,
            "torDisponivel" to Transportes.torDisponivel(ctx),
            "obfs4Disponivel" to Transportes.obfs4Disponivel(ctx),
            "snowflakeDisponivel" to Transportes.snowflakeDisponivel(ctx),
            "obfs4Embutidas" to Transportes.quantasObfs4Embutidas(ctx),
            "origemEmbutidas" to Transportes.origemDasEmbutidas,
        )
    }

    private fun rotas(ctx: Context): Map<String, Any?> {
        val prefs = Preferencias(ctx)
        return mapOf(
            "categorias" to RotaInteligente.resumoDasCategorias(ctx, prefs),
            "desviadosAuto" to FiltroSocks.desviadosAutomaticamente(),
            "pausados" to FiltroSocks.disjuntor.pausadosAgora(System.currentTimeMillis()),
            "dominiosDiretos" to prefs.dominiosDiretos.sorted(),
            "dominiosSempreTor" to prefs.dominiosSempreTor.sorted(),
            "appsFora" to RotaInteligente.appsForaDoTor(ctx, prefs).size,
        )
    }

    private fun rotaDeApp(ctx: Context, args: Map<*, *>): Map<String, Any?> {
        val pacote = args["pacote"] as? String ?: throw IllegalArgumentException("Falta o app.")
        val pelaTor = args["pelaTor"] as? Boolean ?: throw IllegalArgumentException("Falta a rota.")
        val prefs = Preferencias(ctx)
        val nome = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pacote, 0)).toString()
        } catch (_: Exception) {
            null
        }
        val cat = RotaInteligente.categoriaDoApp(pacote, nome)
        val automatica = prefs.rotaInteligente && cat != null && cat.id !in prefs.categoriasDesligadas
        prefs.definirRotaDoApp(pacote, pelaTor, automatica)
        OrbionVpnService.ativo?.configMudou(setOf("appsForaDoTunel", "appsSempreNoTor"))
        Eventos.anotar(ctx, "${nome ?: pacote}: ${if (pelaTor) "pela rede Tor" else "por fora do Tor"} (escolha sua).")
        return mapOf("config" to prefs.comoMapa(), "rota" to RotaInteligente.rota(pacote, cat, prefs).name)
    }

    private fun rotaDeDominio(ctx: Context, args: Map<*, *>): Map<String, Any?> {
        val dominio = args["dominio"] as? String ?: throw IllegalArgumentException("Falta o site.")
        val rota = args["rota"] as? String ?: "padrao"
        val prefs = Preferencias(ctx)
        if (!prefs.definirRotaDoDominio(dominio, rota)) {
            throw IllegalArgumentException("\"$dominio\" não parece um endereço de site válido.")
        }
        FiltroSocks.esquecerDesvio(dominio)
        OrbionVpnService.ativo?.configMudou(setOf("dominiosDiretos", "dominiosSempreTor"))
        return prefs.comoMapa()
    }
}
