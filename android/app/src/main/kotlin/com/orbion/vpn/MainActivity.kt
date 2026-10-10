package com.orbion.vpn

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.orbion.vpn.motor.AppsProblematicos
import com.orbion.vpn.motor.DetectorDeProblemas
import com.orbion.vpn.motor.Diagnostico
import com.orbion.vpn.motor.FiltroSocks
import com.orbion.vpn.motor.OrbionVpnService
import com.orbion.vpn.motor.Painel
import com.orbion.vpn.motor.Preferencias
import com.orbion.vpn.motor.TorManager
import com.orbion.vpn.motor.Transportes
import com.orbion.vpn.motor.TunelHev
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * A tela é toda em Flutter. Esta classe só faz a ponte: recebe os pedidos
 * da interface pelo canal "orbion/motor" e repassa ao motor nativo.
 */
class MainActivity : FlutterActivity() {

    private val principal = Handler(Looper.getMainLooper())
    private val fundo = Executors.newCachedThreadPool()
    private var resultadoVpnPendente: MethodChannel.Result? = null

    companion object {
        private const val CANAL = "orbion/motor"
        private const val PEDIDO_VPN = 4101
        private const val PEDIDO_NOTIFICACAO = 4102
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CANAL)
            .setMethodCallHandler { chamada, resultado -> atender(chamada, resultado) }
    }

    override fun onDestroy() {
        fundo.shutdown()
        super.onDestroy()
    }

    private fun atender(chamada: MethodCall, resultado: MethodChannel.Result) {
        val prefs = Preferencias(this)
        when (chamada.method) {
            "status" -> resultado.success(Painel.comoMapa())

            "conectar" -> conectar(resultado)
            "desconectar" -> {
                OrbionVpnService.desligar(this)
                resultado.success(true)
            }
            "reconectar" -> {
                if (VpnService.prepare(this) != null) {
                    conectar(resultado)
                } else {
                    OrbionVpnService.reiniciar(this)
                    resultado.success(true)
                }
            }
            "novaIdentidade" -> {
                Painel.avisoDeIdentidade = null
                OrbionVpnService.novaIdentidade(this)
                resultado.success(true)
            }
            "limparAvisos" -> {
                Painel.avisoDeIdentidade = null
                Painel.contornoDeBloqueio = null
                resultado.success(true)
            }

            "lerConfig" -> resultado.success(prefs.comoMapa())
            "salvarConfig" -> {
                val mapa = chamada.arguments as? Map<*, *>
                if (mapa != null) prefs.aplicar(mapa)
                resultado.success(prefs.comoMapa())
            }

            "listarApps" -> {
                val incluirSistema = chamada.argument<Boolean>("incluirSistema") ?: false
                emSegundoPlano(resultado) { listarApps(incluirSistema) }
            }
            "icones" -> {
                val pacotes = chamada.argument<List<String>>("pacotes") ?: emptyList()
                emSegundoPlano(resultado) { pacotes.associateWith { icone(it) }.filterValues { it != null } }
            }

            "registros" -> {
                val max = chamada.argument<Int>("max") ?: 200
                resultado.success(
                    mapOf(
                        "contagem" to FiltroSocks.contagem(),
                        "registros" to FiltroSocks.registros(max).map {
                            mapOf("dominio" to it.dominio, "destino" to it.destino.name, "quando" to it.quando)
                        },
                    ),
                )
            }
            "logTecnico" -> {
                val max = chamada.argument<Int>("max") ?: 300
                emSegundoPlano(resultado) {
                    mapOf("tor" to TorManager.lerLog(max), "tunel" to TunelHev.lerLog(this, max))
                }
            }
            "suspeitas" -> emSegundoPlano(resultado) {
                DetectorDeProblemas.analisar(this).map {
                    mapOf(
                        "servidor" to it.servidor, "tentativas" to it.tentativas,
                        "servico" to it.nomeDoServico, "motivo" to it.motivo?.titulo,
                        "explicacao" to it.motivo?.explicacao,
                    )
                }
            }
            "appsProblematicos" -> emSegundoPlano(resultado) {
                AppsProblematicos.instaladosNoAparelho(this).map {
                    mapOf("pacote" to it.pacote, "nome" to it.nome, "motivo" to it.motivo.titulo, "explicacao" to it.motivo.explicacao)
                }
            }
            "diagnostico" -> emSegundoPlano(resultado) {
                Diagnostico.verificar(this).map { it.comoMapa() }
            }
            "infoMotor" -> emSegundoPlano(resultado) { infoMotor() }
            "avisosRapidos" -> resultado.success(avisosRapidos())

            "executarAcao" -> {
                val acao = chamada.argument<String>("acao") ?: ""
                resultado.success(executarAcao(acao))
            }
            else -> resultado.notImplemented()
        }
    }

    // ------------------------------------------------------------------

    private fun conectar(resultado: MethodChannel.Result) {
        val pedido = try {
            VpnService.prepare(this)
        } catch (e: Exception) {
            resultado.error("vpn", "Não foi possível pedir a permissão de VPN: ${e.message}", null)
            return
        }
        if (pedido == null) {
            OrbionVpnService.ligar(this)
            resultado.success(true)
            return
        }
        resultadoVpnPendente?.success(false)
        resultadoVpnPendente = resultado
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pedido, PEDIDO_VPN)
        } catch (e: Exception) {
            resultadoVpnPendente = null
            resultado.error("vpn", "O Android não abriu a tela de permissão de VPN: ${e.message}", null)
        }
    }

    @Deprecated("API antiga, ainda é o jeito mais simples dentro do FlutterActivity")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PEDIDO_VPN) {
            val r = resultadoVpnPendente
            resultadoVpnPendente = null
            if (resultCode == RESULT_OK) {
                OrbionVpnService.ligar(this)
                r?.success(true)
            } else {
                r?.success(false)
            }
        }
    }

    private fun <T> emSegundoPlano(resultado: MethodChannel.Result, tarefa: () -> T) {
        fundo.execute {
            try {
                val v = tarefa()
                principal.post { resultado.success(v) }
            } catch (e: Exception) {
                principal.post { resultado.error("erro", e.message, null) }
            }
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun listarApps(incluirSistema: Boolean): List<Map<String, Any>> {
        val pm = packageManager
        val apps = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
        return apps.asSequence()
            .filter { it.packageName != packageName }
            .filter { pm.checkPermission(Manifest.permission.INTERNET, it.packageName) == PackageManager.PERMISSION_GRANTED }
            .map { info ->
                val sistema = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                Triple(info, sistema, pm.getLaunchIntentForPackage(info.packageName) != null)
            }
            .filter { (_, sistema, temTela) -> incluirSistema || !sistema || temTela }
            .map { (info, sistema, _) ->
                mapOf(
                    "pacote" to info.packageName,
                    "nome" to (pm.getApplicationLabel(info)?.toString() ?: info.packageName),
                    "sistema" to sistema,
                )
            }
            .sortedBy { (it["nome"] as String).lowercase() }
            .toList()
    }

    private fun icone(pacote: String): ByteArray? = try {
        val d = packageManager.getApplicationIcon(pacote)
        val tam = 96
        val bmp = Bitmap.createBitmap(tam, tam, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, tam, tam)
        d.draw(c)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        out.toByteArray()
    } catch (_: Exception) {
        null
    }

    private fun infoMotor(): Map<String, Any?> {
        val versaoApp = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        return mapOf(
            "versaoApp" to versaoApp,
            "versaoTor" to TorManager.versao(this),
            "abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "?"),
            "android" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT,
            "fabricante" to Build.MANUFACTURER,
            "modelo" to Build.MODEL,
            "torDisponivel" to Transportes.torDisponivel(this),
            "obfs4Disponivel" to Transportes.obfs4Disponivel(this),
            "snowflakeDisponivel" to Transportes.snowflakeDisponivel(this),
            "obfs4Embutidas" to Transportes.quantasObfs4Embutidas(this),
            "origemEmbutidas" to Transportes.origemDasEmbutidas,
        )
    }

    /** Avisos baratos de calcular, usados pelo Painel a cada atualização. */
    private fun avisosRapidos(): Map<String, Any?> {
        val dns = when (val s = Diagnostico.statusDnsPrivado(this)) {
            is Diagnostico.StatusDns.Desligado -> "DESLIGADO"
            is Diagnostico.StatusDns.Automatico -> "AUTOMATICO"
            is Diagnostico.StatusDns.Fixo -> "FIXO:${s.servidor}"
        }
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return mapOf(
            "bateriaIsenta" to Diagnostico.bateriaIsenta(this),
            "dnsPrivado" to dns,
            "notificacoes" to notif,
            "vpnAutorizada" to (VpnService.prepare(this) == null),
        )
    }

    @SuppressLint("BatteryLife")
    private fun executarAcao(acao: String): Boolean = try {
        when (acao) {
            "ISENTAR_BATERIA" -> {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
                true
            }
            "ABRIR_DNS_PRIVADO" -> {
                try {
                    startActivity(Intent("android.settings.PRIVATE_DNS_SETTINGS"))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                }
                true
            }
            "PEDIR_NOTIFICACAO" -> {
                val p = Preferencias(this)
                val falta = Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                val jaPediu = "_notificacao_pedida" in p.avisosDispensados
                if (falta && (!jaPediu || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
                    p.avisosDispensados = p.avisosDispensados + "_notificacao_pedida"
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PEDIDO_NOTIFICACAO)
                } else {
                    // Já recusado de vez (ou desligado no canal): só pela tela do sistema.
                    startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                    )
                }
                true
            }
            "PEDIR_NOTIFICACAO_INICIO" -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PEDIDO_NOTIFICACAO)
                }
                true
            }
            "ABRIR_VPN" -> {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                true
            }
            "ABRIR_APP_INFO" -> {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                true
            }
            "ABRIR_DESENVOLVEDOR" -> {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                }
                true
            }
            "ABRIR_PONTES" -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://bridges.torproject.org/options")))
                true
            }
            else -> false
        }
    } catch (_: Exception) {
        false
    }
}
