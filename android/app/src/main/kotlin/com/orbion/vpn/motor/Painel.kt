package com.orbion.vpn.motor

/**
 * Estado atual da VPN, num lugar só.
 *
 * O serviço escreve; a tela em Flutter lê (a cada segundo) pelo canal
 * "orbion/motor". Tudo aqui é @Volatile porque quem escreve e quem lê estão
 * em threads diferentes.
 */
object Painel {

    enum class Estado { DESCONECTADO, CONECTANDO, CONECTADO, BLOQUEADO, ERRO }

    @Volatile var estado: Estado = Estado.DESCONECTADO
        private set
    @Volatile var percentual: Int = 0
        private set
    @Volatile var mensagem: String = ""
        private set
    @Volatile var erro: String? = null
        private set

    @Volatile var conectadoDesde: Long = 0L
    @Volatile var enviadoBytes: Long = 0L
    @Volatile var recebidoBytes: Long = 0L
    @Volatile var velocidadeEnvio: Long = 0L
    @Volatile var velocidadeRecebimento: Long = 0L

    /** Mensagem de que a rede bloqueava o Tor e o app contornou. */
    @Volatile var contornoDeBloqueio: String? = null
    /** Resultado do último pedido de nova identidade. */
    @Volatile var avisoDeIdentidade: String? = null
    /** Caminho de entrada em uso: DIRETO, SNOWFLAKE ou OBFS4. */
    @Volatile var caminhoEmUso: String = "DIRETO"
    /** Apps fora do túnel na sessão atual (o que realmente vale agora). */
    @Volatile var appsForaNaSessao: Set<String> = emptySet()
    /** Informações que só o serviço sabe. */
    @Volatile var vpnSempreAtiva: Boolean = false
    @Volatile var bloqueioSemVpn: Boolean = false
    @Volatile var tipoPrimeiroPlano: String = ""
    /** Quantas vezes o Tor caiu e foi religado nesta sessão. */
    @Volatile var quedasDoTor: Int = 0

    fun desconectado() {
        estado = Estado.DESCONECTADO
        percentual = 0
        mensagem = ""
        erro = null
        zerarSessao()
    }

    fun conectando(percentual: Int, mensagem: String) {
        estado = Estado.CONECTANDO
        this.percentual = percentual.coerceIn(0, 100)
        this.mensagem = mensagem
        erro = null
    }

    fun conectado() {
        estado = Estado.CONECTADO
        percentual = 100
        mensagem = "Conectado pela rede Tor"
        erro = null
        if (conectadoDesde == 0L) conectadoDesde = System.currentTimeMillis()
    }

    fun bloqueado(motivo: String) {
        estado = Estado.BLOQUEADO
        percentual = 0
        mensagem = "Tráfego bloqueado pelo Kill Switch"
        erro = motivo
    }

    fun falhou(motivo: String) {
        estado = Estado.ERRO
        percentual = 0
        mensagem = ""
        erro = motivo
        zerarSessao()
    }

    fun zerarSessao() {
        conectadoDesde = 0L
        enviadoBytes = 0L
        recebidoBytes = 0L
        velocidadeEnvio = 0L
        velocidadeRecebimento = 0L
        appsForaNaSessao = emptySet()
    }

    fun comoMapa(): Map<String, Any?> = mapOf(
        "estado" to estado.name,
        "percentual" to percentual,
        "mensagem" to mensagem,
        "erro" to erro,
        "conectadoDesde" to conectadoDesde,
        "enviadoBytes" to enviadoBytes,
        "recebidoBytes" to recebidoBytes,
        "velocidadeEnvio" to velocidadeEnvio,
        "velocidadeRecebimento" to velocidadeRecebimento,
        "contornoDeBloqueio" to contornoDeBloqueio,
        "avisoDeIdentidade" to avisoDeIdentidade,
        "caminhoEmUso" to caminhoEmUso,
        "appsForaNaSessao" to appsForaNaSessao.toList(),
        "vpnSempreAtiva" to vpnSempreAtiva,
        "bloqueioSemVpn" to bloqueioSemVpn,
        "tipoPrimeiroPlano" to tipoPrimeiroPlano,
        "quedasDoTor" to quedasDoTor,
    )
}
