package com.orbion.vpn.motor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

/**
 * Celular ligou ou o Orbion foi atualizado: devolve a VPN ao estado em que
 * a pessoa deixou.
 *
 *  - ao ligar o celular: liga se "Conectar ao ligar o celular" estiver ativo
 *    OU se a VPN estava ligada quando o celular desligou;
 *  - depois de uma atualização do Orbion: liga se estava ligada.
 *
 * Roda no processo da VPN e não trava a linha principal (goAsync).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val acao = intent.action ?: return
        val pendente = goAsync()
        val app = context.applicationContext
        Filas.avulso {
            try {
                tratar(app, acao)
            } finally {
                pendente.finish()
            }
        }
    }

    private fun tratar(context: Context, acao: String) {
        val prefs = Preferencias(context)
        val origem: String
        val deveLigar = when (acao) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" -> {
                origem = OrbionVpnService.ORIGEM_BOOT
                prefs.conectarNoBoot || prefs.estavaLigada
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                origem = OrbionVpnService.ORIGEM_ATUALIZACAO
                prefs.estavaLigada
            }
            else -> return
        }
        if (!deveLigar) return
        if (VpnService.prepare(context) != null) {
            Log.w(TAG, "Permissão de VPN ainda não concedida. Não conectando.")
            return
        }
        if (OrbionVpnService.ligar(context, origem)) {
            Log.d(TAG, "VPN iniciada automaticamente ($acao).")
        } else {
            OrbionVpnService.notificarParaReligar(context)
        }
    }

    private companion object {
        const val TAG = "OrbionBoot"
    }
}
