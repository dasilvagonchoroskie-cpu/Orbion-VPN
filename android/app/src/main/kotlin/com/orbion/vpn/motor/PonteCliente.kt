package com.orbion.vpn.motor

import android.content.ContentProviderClient
import android.content.Context
import android.os.RemoteException
import android.util.Log

/**
 * O lado da tela da ponte: fala com o motor (processo ":vpn").
 *
 * Usa uma ligação "instável" de propósito: se o processo da VPN morrer no
 * meio de uma conversa, a tela recebe um erro e tenta de novo — em vez de o
 * Android derrubar a tela junto (é o que acontece com a ligação "estável").
 *
 * NUNCA chamar da linha principal: a primeira chamada pode ter de esperar o
 * processo da VPN nascer.
 */
class PonteCliente(private val context: Context) {

    private var cliente: ContentProviderClient? = null

    @Synchronized
    private fun obter(): ContentProviderClient? {
        if (cliente == null) {
            cliente = context.contentResolver.acquireUnstableContentProviderClient(Ponte.AUTORIDADE)
        }
        return cliente
    }

    @Synchronized
    private fun descartar() {
        try {
            cliente?.close()
        } catch (_: Exception) {
        }
        cliente = null
    }

    /** Chama o motor e devolve a resposta já convertida (mapas, listas...). */
    fun chamar(metodo: String, argumentos: Any?): Any? {
        var ultimoErro: Exception? = null
        repeat(2) {
            val c = obter() ?: throw IllegalStateException("O motor da VPN não está disponível.")
            try {
                val b = c.call(metodo, Json.escrever(argumentos), null)
                val r = Json.ler(b?.getString(Ponte.CHAVE))
                if (r is Map<*, *> && r.containsKey(Ponte.ERRO)) {
                    throw IllegalStateException(r[Ponte.ERRO] as? String ?: "Erro no motor.")
                }
                return r
            } catch (e: RemoteException) {
                // O processo da VPN morreu ou está nascendo de novo: tenta outra vez.
                Log.w("OrbionPonte", "Motor não respondeu ($metodo): ${e.message}")
                ultimoErro = e
                descartar()
            }
        }
        throw IllegalStateException("O motor da VPN não respondeu: ${ultimoErro?.message}")
    }
}
