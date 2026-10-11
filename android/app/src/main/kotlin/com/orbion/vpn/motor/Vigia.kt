package com.orbion.vpn.motor

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.VpnService
import android.util.Log

/**
 * Vigia de 15 minutos: se a pessoa deixou a VPN ligada e o Android derrubou
 * o Orbion sem religar, o vigia religa.
 *
 * Se o Android também recusar o religamento (alguns celulares bloqueiam
 * apps em segundo plano), aparece uma notificação "toque para religar" — um
 * toque da pessoa sempre é permitido.
 */
class Vigia : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Filas.avulso {
            try {
                com.orbion.vpn.Arquivos.limparRelatoriosAntigos(applicationContext)
                verificar(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val TAG = "OrbionVigia"
        private const val ID = 4201
        private const val PERIODO_MS = 15 * 60_000L

        fun agendar(context: Context) {
            try {
                val js = context.getSystemService(JobScheduler::class.java) ?: return
                if (js.getPendingJob(ID) != null) return
                val job = JobInfo.Builder(ID, ComponentName(context, Vigia::class.java))
                    .setPeriodic(PERIODO_MS)
                    .setPersisted(true)
                    .build()
                js.schedule(job)
            } catch (e: Exception) {
                Log.w(TAG, "Vigia não agendado: ${e.message}")
            }
        }

        fun cancelar(context: Context) {
            try {
                context.getSystemService(JobScheduler::class.java)?.cancel(ID)
            } catch (_: Exception) {
            }
        }

        fun verificar(context: Context) {
            val prefs = Preferencias(context)
            if (!prefs.estavaLigada) {
                cancelar(context)
                return
            }
            if (OrbionVpnService.ativo != null) return
            if (VpnService.prepare(context) != null) return // permissão de VPN foi retirada
            Eventos.anotar(context, "Vigia: a VPN devia estar ligada e não estava. Religando.")
            if (!OrbionVpnService.ligar(context, OrbionVpnService.ORIGEM_VIGIA)) {
                Eventos.anotar(context, "O Android não deixou religar em segundo plano. Notificação \"toque para religar\" exibida.")
                OrbionVpnService.notificarParaReligar(context)
            }
        }
    }
}
