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
 * Vigia: se a pessoa deixou a VPN ligada e o Android derrubou o Orbion sem
 * religar, o vigia religa.
 *
 * São dois agendamentos com o mesmo trabalho:
 * - o de 15 minutos (periódico, sobrevive a reinício do celular);
 * - a sentinela de 1 minuto (desde a 6.3.4): uma tarefa avulsa que se
 *   reagenda enquanto a VPN deve ficar ligada. Ela existe porque o limpador
 *   da Xiaomi ("limpar tudo" na tela de recentes, PeriodicCleaner) derruba a
 *   VPN em uso e a "VPN sempre ativa" nem sempre religa: só com o de 15 min o
 *   aparelho chegou a ficar 7–11 min sem proteção. Custa quase nada: com a
 *   VPN no ar ela só confere uma variável e se reagenda; com a tela apagada o
 *   próprio Android junta essas conferências nas janelas de manutenção.
 *
 * Se o Android também recusar o religamento (alguns celulares bloqueiam
 * apps em segundo plano), aparece uma notificação "toque para religar" — um
 * toque da pessoa sempre é permitido.
 */
class Vigia : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val sentinela = params.jobId == ID_SENTINELA
        Filas.avulso {
            try {
                if (!sentinela) com.orbion.vpn.Arquivos.limparRelatoriosAntigos(applicationContext)
                verificar(applicationContext)
            } finally {
                jobFinished(params, false)
                // A sentinela é avulsa: ela mesma marca a próxima conferência.
                if (sentinela && Preferencias(applicationContext).estavaLigada) {
                    agendarSentinela(applicationContext, substituir = true)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val TAG = "OrbionVigia"
        private const val ID = 4201
        private const val PERIODO_MS = 15 * 60_000L
        private const val ID_SENTINELA = 4202
        const val SENTINELA_MS = 60_000L

        fun agendar(context: Context) {
            agendarSentinela(context, substituir = false)
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

        /**
         * Marca a próxima conferência da sentinela para daqui a 1 minuto.
         * [substituir] = false não mexe numa conferência que já está marcada
         * (senão cada religamento empurraria a conferência para frente).
         */
        fun agendarSentinela(context: Context, substituir: Boolean) {
            try {
                val js = context.getSystemService(JobScheduler::class.java) ?: return
                if (!substituir && js.getPendingJob(ID_SENTINELA) != null) return
                val job = JobInfo.Builder(ID_SENTINELA, ComponentName(context, Vigia::class.java))
                    .setMinimumLatency(SENTINELA_MS)
                    .build()
                js.schedule(job)
            } catch (e: Exception) {
                Log.w(TAG, "Sentinela não agendada: ${e.message}")
            }
        }

        fun cancelar(context: Context) {
            try {
                val js = context.getSystemService(JobScheduler::class.java) ?: return
                js.cancel(ID)
                js.cancel(ID_SENTINELA)
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
