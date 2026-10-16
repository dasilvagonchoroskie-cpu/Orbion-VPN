package com.orbion.vpn

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import com.orbion.vpn.motor.Diagnostico
import com.orbion.vpn.motor.Eventos
import com.orbion.vpn.motor.Fabricantes
import com.orbion.vpn.motor.OrbionVpnService
import com.orbion.vpn.motor.PonteCliente
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A tela é toda em Flutter. Esta classe só faz a ponte: recebe os pedidos
 * da interface pelo canal "orbion/motor" e repassa ao motor, que roda em
 * OUTRO processo (":vpn") — se esta tela fechar ou travar, a VPN continua.
 *
 * Regra: nada que possa demorar roda na linha principal. Tudo o que fala com
 * o motor ou com o sistema vai para uma linha de fundo, e a resposta volta
 * para a tela quando fica pronta. É isso que evita o "O app não está
 * respondendo".
 */
class MainActivity : FlutterActivity() {

    private val principal = Handler(Looper.getMainLooper())
    private val fundo: ExecutorService = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "orbion-tela").apply { isDaemon = true }
    }
    private val ponte by lazy { PonteCliente(applicationContext) }

    /** Só para lembretes da tela (o motor tem as configurações dele). */
    private val prefsTela: SharedPreferences by lazy { getSharedPreferences("orbion_tela", MODE_PRIVATE) }

    private var resultadoVpnPendente: MethodChannel.Result? = null
    private var reinicioPendente = false
    private var origemPendente = OrbionVpnService.ORIGEM_TELA
    private var inicioPedidoBateria = 0L
    private var tentativasBateria = 0
    private var pedidoBateriaAberto = false
    private var pausouDesdeOPedido = false

    companion object {
        private const val CANAL = "orbion/motor"
        private const val PEDIDO_VPN = 4101
        private const val PEDIDO_NOTIFICACAO = 4102
        private const val PEDIDO_BATERIA = 4103

        /** Onde a Política de Privacidade e os Termos ficam publicados. */
        private const val SITE_LEGAL = "https://fortalezadigitalsecurity.com.br/orbion/"
        private const val EMAIL_SUPORTE = "fortalezadigitalsecurity@gmail.com"

        /** Pedidos respondidos pelo motor (processo da VPN). */
        private val DO_MOTOR = setOf(
            "status", "desligar", "novaIdentidade", "limparAvisos", "lerConfig", "salvarConfig",
            "confirmar", "desfazerConfirmacoes", "registros", "logTecnico", "diagnostico",
            "avisosRapidos", "infoMotor", "listarApps", "rotas", "rotaDeApp", "rotaDeDominio", "eventos",
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Acorda o processo da VPN e carrega os lembretes da tela, longe da
        // linha principal.
        fundo.execute {
            try {
                Arquivos.limparRelatoriosAntigos(this)
                prefsTela.contains("_")
                ponte.chamar("ping", null)
            } catch (_: Exception) {
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CANAL)
            .setMethodCallHandler { chamada, resultado -> atender(chamada, resultado) }
        // Central de Licenças: entrega o código do aparelho (ANDROID_ID) e o
        // modelo. Só é usado no APK de venda direta (LICENCA_CENTRAL=true).
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "fortaleza/licenca")
            .setMethodCallHandler { chamada, resultado ->
                when (chamada.method) {
                    "idDoAparelho" -> resultado.success(
                        try {
                            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                        } catch (e: Exception) {
                            null
                        } ?: "",
                    )
                    "modelo" -> resultado.success("${Build.MANUFACTURER} ${Build.MODEL}".trim())
                    else -> resultado.notImplemented()
                }
            }
    }

    override fun onDestroy() {
        fundo.shutdown()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        // Se a pergunta de bateria do Android apareceu, ela cobre esta tela.
        pausouDesdeOPedido = true
    }

    private fun atender(chamada: MethodCall, resultado: MethodChannel.Result) {
        when (val metodo = chamada.method) {
            "conectar" -> conectar(resultado, reiniciar = false, origem = chamada.argument<String>("origem") ?: OrbionVpnService.ORIGEM_TELA)
            "reconectar" -> conectar(resultado, reiniciar = true, origem = OrbionVpnService.ORIGEM_TELA)
            "executarAcao" -> executarAcao(chamada.argument<String>("acao") ?: "", resultado)
            "icones" -> {
                val pacotes = chamada.argument<List<String>>("pacotes") ?: emptyList()
                emSegundoPlano(resultado) { pacotes.associateWith { icone(it) }.filterValues { it != null } }
            }
            "fechamentos" -> emSegundoPlano(resultado) {
                Eventos.fechamentos(this, 16, comRastro = true).filter { it.inesperado }.map { it.comoMapa() }
            }
            "compartilharRelatorio" -> compartilharRelatorio(resultado)
            in DO_MOTOR -> {
                val args = chamada.arguments
                emSegundoPlano(resultado) { ponte.chamar(metodo, args) }
            }
            else -> resultado.notImplemented()
        }
    }

    private fun emSegundoPlano(resultado: MethodChannel.Result, tarefa: () -> Any?) {
        try {
            fundo.execute {
                try {
                    val v = tarefa()
                    principal.post { resultado.success(v) }
                } catch (e: Throwable) {
                    principal.post { resultado.error("erro", e.message ?: e.javaClass.simpleName, null) }
                }
            }
        } catch (e: Throwable) {
            resultado.error("erro", "A tela está fechando.", null)
        }
    }

    // ------------------------------------------------------------------
    // Ligar a VPN (permissão de VPN → serviço no processo ":vpn")
    // ------------------------------------------------------------------

    private fun conectar(resultado: MethodChannel.Result, reiniciar: Boolean, origem: String) {
        fundo.execute {
            val pedido = try {
                VpnService.prepare(this)
            } catch (e: Exception) {
                principal.post { resultado.error("vpn", "Não foi possível pedir a permissão de VPN: ${e.message}", null) }
                return@execute
            }
            principal.post {
                if (pedido == null) {
                    if (iniciarServico(reiniciar, origem)) {
                        resultado.success(true)
                    } else {
                        resultado.error("vpn", "O Android não deixou iniciar a VPN agora. Tente de novo.", null)
                    }
                    return@post
                }
                resultadoVpnPendente?.success(false)
                resultadoVpnPendente = resultado
                reinicioPendente = reiniciar
                origemPendente = origem
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(pedido, PEDIDO_VPN)
                } catch (e: Exception) {
                    resultadoVpnPendente = null
                    resultado.error("vpn", "O Android não abriu a tela de permissão de VPN: ${e.message}", null)
                }
            }
        }
    }

    private fun iniciarServico(reiniciar: Boolean, origem: String): Boolean =
        if (reiniciar) OrbionVpnService.reiniciar(this, origem) else OrbionVpnService.ligar(this, origem)

    @Deprecated("API antiga, ainda é o jeito mais simples dentro do FlutterActivity")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PEDIDO_BATERIA) {
            if (!pedidoBateriaAberto) return
            pedidoBateriaAberto = false
            val rapido = SystemClock.elapsedRealtime() - inicioPedidoBateria < 1500
            depoisDaPerguntaDeBateria(rapido)
            return
        }
        if (requestCode == PEDIDO_VPN) {
            val r = resultadoVpnPendente
            resultadoVpnPendente = null
            if (resultCode == RESULT_OK) {
                r?.success(iniciarServico(reinicioPendente, origemPendente))
            } else {
                r?.success(false)
            }
        }
    }

    // ------------------------------------------------------------------
    // Relatório para o suporte (arquivo, pelo menu Compartilhar)
    // ------------------------------------------------------------------

    private fun compartilharRelatorio(resultado: MethodChannel.Result) {
        fundo.execute {
            try {
                val texto = ponte.chamar("relatorio", null) as? String ?: "Relatório indisponível."
                val pasta = Arquivos.pasta(this)
                // Relatórios antigos não ficam guardados.
                Arquivos.limparRelatoriosAntigos(this)
                val nome = "Orbion-relatorio-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt"
                File(pasta, nome).writeText(texto)
                principal.post {
                    try {
                        val uri = Arquivos.uri(nome)
                        val envio = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_SUBJECT, "Relatório do Orbion VPN")
                            .putExtra(Intent.EXTRA_TEXT, "Relatório do Orbion VPN ($nome)")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        envio.clipData = ClipData.newRawUri(nome, uri)
                        startActivity(
                            Intent.createChooser(envio, "Enviar o relatório do Orbion")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                        resultado.success(true)
                    } catch (e: Exception) {
                        resultado.error("relatorio", "Não foi possível abrir o menu de compartilhar: ${e.message}", null)
                    }
                }
            } catch (e: Exception) {
                principal.post { resultado.error("relatorio", "Não foi possível montar o relatório: ${e.message}", null) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Ícones dos apps (aba Apps)
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Telas do Android que a pessoa precisa ver
    // ------------------------------------------------------------------

    /** Executa a ação pedida pela tela. Responde "OK", "FALHOU" ou um código específico. */
    private fun executarAcao(acao: String, resultado: MethodChannel.Result) {
        if (acao == "PEDIR_NOTIFICACAO") {
            // Lê o lembrete fora da linha principal e só depois abre a tela.
            fundo.execute {
                val jaPediu = prefsTela.getBoolean("notificacao_pedida", false)
                if (!jaPediu) prefsTela.edit().putBoolean("notificacao_pedida", true).apply()
                principal.post { resultado.success(pedirNotificacao(jaPediu)) }
            }
            return
        }
        val codigo = try {
            when (acao) {
                "ISENTAR_BATERIA" -> liberarBateria()
                "ABRIR_BATERIA_MARCA" -> abrirTelaDeBateria()
                "ABRIR_AUTOSTART" -> abrirInicioAutomatico()
                "ABRIR_DNS_PRIVADO" -> abrir(
                    listOf(
                        Triple("OK", Intent("android.settings.PRIVATE_DNS_SETTINGS"), null),
                        Triple("OK", Intent(Settings.ACTION_WIRELESS_SETTINGS), "Procure \"DNS Privado\" (Private DNS) e escolha \"Desativado\" (Off)."),
                    ),
                )
                "PEDIR_NOTIFICACAO_INICIO" -> {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PEDIDO_NOTIFICACAO)
                    }
                    "OK"
                }
                "ABRIR_VPN" -> abrir(listOf(Triple("OK", Intent(Settings.ACTION_VPN_SETTINGS), null)))
                "ABRIR_APP_INFO" -> abrir(listOf(Triple("APP_INFO", intentDeInformacoes(), null)))
                "ABRIR_DESENVOLVEDOR" -> abrir(
                    listOf(
                        Triple("OK", Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), null),
                        Triple("OK", Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), "Toque 7 vezes em \"Número da versão\" (Build number) para liberar as opções do desenvolvedor."),
                    ),
                )
                "ABRIR_PONTES" -> abrir(listOf(Triple("OK", Intent(Intent.ACTION_VIEW, Uri.parse("https://bridges.torproject.org/options")), null)))
                // Versões publicadas da Política de Privacidade e dos Termos de Uso.
                "ABRIR_PRIVACIDADE_ONLINE" -> abrir(listOf(Triple("OK", Intent(Intent.ACTION_VIEW, Uri.parse(SITE_LEGAL + "privacidade.html")), null)))
                "ABRIR_TERMOS_ONLINE" -> abrir(listOf(Triple("OK", Intent(Intent.ACTION_VIEW, Uri.parse(SITE_LEGAL + "termos.html")), null)))
                "ESCREVER_PARA_O_SUPORTE" -> abrir(
                    listOf(
                        Triple(
                            "OK",
                            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$EMAIL_SUPORTE"))
                                .putExtra(Intent.EXTRA_SUBJECT, "Orbion VPN — suporte"),
                            null,
                        ),
                    ),
                )
                "FECHAR_APP" -> {
                    finishAndRemoveTask()
                    "OK"
                }
                else -> "FALHOU"
            }
        } catch (_: Exception) {
            "FALHOU"
        }
        resultado.success(codigo)
    }

    private fun pedirNotificacao(jaPediu: Boolean): String = try {
        val falta = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (falta && (!jaPediu || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PEDIDO_NOTIFICACAO)
        } else {
            // Já recusado de vez (ou desligado no canal): só pela tela do sistema.
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }
        "OK"
    } catch (_: Exception) {
        "FALHOU"
    }

    private fun intentDeInformacoes() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    /** Mostra a instrução por ~7 s por cima da tela que abriu. */
    private fun avisarLongo(texto: String) {
        Toast.makeText(this, texto, Toast.LENGTH_LONG).show()
        principal.postDelayed({ Toast.makeText(applicationContext, texto, Toast.LENGTH_LONG).show() }, 3600)
    }

    /**
     * Abre a primeira tela que existir neste celular. Não pergunta antes ao
     * Android se a tela existe (no Android 11+ a resposta pode vir errada
     * para telas de outros apps): tenta abrir e, se não der, passa para a
     * próxima.
     */
    private fun abrir(opcoes: List<Triple<String, Intent, String?>>): String {
        for ((codigo, intent, instrucao) in opcoes) {
            try {
                startActivity(intent)
                if (instrucao != null) avisarLongo(instrucao)
                return codigo
            } catch (_: Exception) {
            }
        }
        return "NADA"
    }

    // ------------------------------------------------------------------
    // Bateria: pergunta do Android → tela da marca → telas do Android
    // ------------------------------------------------------------------

    /** O pedido direto só existe se o manifesto declarar a permissão (pode sair na versão da Play Store). */
    private val podePedirDireto: Boolean by lazy {
        try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.contains(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) == true
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("BatteryLife")
    private fun liberarBateria(): String {
        val isenta = Diagnostico.bateriaIsenta(this)
        if (isenta) {
            // O Android já liberou. Nas marcas com gerenciador próprio, falta a
            // tela da marca; nas outras, não há mais nada a fazer.
            return if (Fabricantes.temGerenciadorProprio()) abrirTelaDeBateria() else "JA_LIBERADO"
        }
        // Na 1ª vez tenta a pergunta oficial do Android. Se a pessoa tocar de
        // novo sem ter dado certo, vai direto para as telas de bateria.
        if (tentativasBateria == 0 && podePedirDireto) {
            tentativasBateria++
            try {
                inicioPedidoBateria = SystemClock.elapsedRealtime()
                pausouDesdeOPedido = false
                pedidoBateriaAberto = true
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                    PEDIDO_BATERIA,
                )
                // Vigia: em algumas marcas a pergunta simplesmente não aparece,
                // sem devolver resposta. Se em 1,6 s nada cobriu a tela, ela
                // não apareceu: segue para as telas de bateria.
                principal.postDelayed({
                    if (pedidoBateriaAberto && !pausouDesdeOPedido) {
                        pedidoBateriaAberto = false
                        depoisDaPerguntaDeBateria(rapido = true)
                    }
                }, 1600)
                return "PERGUNTA"
            } catch (_: Exception) {
                pedidoBateriaAberto = false
            }
        }
        return abrirTelaDeBateria()
    }

    /**
     * Depois da pergunta do Android:
     *  - Xiaomi: abre SEMPRE a economia de bateria da própria Xiaomi, porque é
     *    ela que fecha os apps (a liberação do Android sozinha não basta);
     *  - outras marcas: se a pergunta nem apareceu, abre as telas de bateria.
     */
    private fun depoisDaPerguntaDeBateria(rapido: Boolean) {
        val isenta = Diagnostico.bateriaIsenta(this)
        if (Fabricantes.marca() == Fabricantes.Marca.XIAOMI || (!isenta && rapido)) {
            val codigo = abrirTelaDeBateria()
            if (codigo == "NADA") avisarLongo("Abra Configurações (Settings) → Apps → Orbion VPN → Bateria (Battery) → \"Sem restrições\" (Unrestricted).")
        }
    }

    /** Abre a tela de bateria da marca, ou a do Android, e explica o que tocar. */
    private fun abrirTelaDeBateria(): String {
        val opcoes = mutableListOf<Triple<String, Intent, String?>>()
        for (i in Fabricantes.telasDeBateria(this)) opcoes += Triple("MARCA", i, Fabricantes.instrucaoDaTelaDeBateria())
        val infoApp = Triple<String, Intent, String?>(
            "APP_INFO", intentDeInformacoes(),
            "Toque em Bateria (Battery) e escolha \"Sem restrições\" (Unrestricted).",
        )
        val lista = Triple<String, Intent, String?>(
            "LISTA", Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            "Mude a lista para \"Todos os apps\" (All apps), toque em Orbion VPN e escolha \"Não otimizar\" (Don't optimize).",
        )
        // Android 12+: "Sem restrições" fica nas informações do app; antes disso, na lista.
        if (Build.VERSION.SDK_INT >= 31) {
            opcoes += infoApp
            opcoes += lista
        } else {
            opcoes += lista
            opcoes += infoApp
        }
        return abrir(opcoes)
    }

    /** Abre a tela de "Início automático" da marca, ou o mais perto disso. */
    private fun abrirInicioAutomatico(): String {
        val opcoes = mutableListOf<Triple<String, Intent, String?>>()
        for (i in Fabricantes.telasDeInicioAutomatico(this)) opcoes += Triple("AUTOSTART", i, Fabricantes.instrucaoDoInicioAutomatico())
        opcoes += Triple("APP_INFO", intentDeInformacoes(), "Procure \"Início automático\" (Autostart) ou \"Inicialização automática\" e ligue.")
        return abrir(opcoes)
    }
}
