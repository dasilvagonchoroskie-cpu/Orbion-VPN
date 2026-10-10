package com.orbion.vpn.motor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

/**
 * Liga a VPN quando o celular termina de ligar (se a pessoa pediu) e
 * religa depois que o próprio Orbion é atualizado (se estava ligada).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val acao = intent.action ?: return
        val prefs = Preferencias(context)
        val deveLigar = when (acao) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" -> prefs.conectarNoBoot
            Intent.ACTION_MY_PACKAGE_REPLACED -> prefs.estavaLigada
            else -> false
        }
        if (!deveLigar) return
        if (VpnService.prepare(context) != null) {
            Log.w(TAG, "Permissão de VPN ainda não concedida. Não conectando.")
            return
        }
        try {
            OrbionVpnService.ligar(context)
            Log.d(TAG, "VPN iniciada automaticamente ($acao).")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar automaticamente: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "OrbionBoot"
    }
}
