package com.orbion.vpn.motor

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Filas de trabalho do processo da VPN.
 *
 * Regra de ouro — é o que acaba com o "O app não está respondendo": a linha
 * principal do Android NUNCA espera por nada. Ligar, desligar, montar a
 * interface, parar o Tor ou o túnel: tudo roda aqui, em segundo plano.
 *
 *  - controle: UMA tarefa por vez, na ordem em que chegaram. Conectar,
 *    desligar e reconfigurar passam todos por ela, então nunca se atropelam
 *    e ninguém precisa de trava.
 *  - agenda: tarefas com hora marcada (vigilância, contador, religamentos).
 *  - avulsos: consultas que podem rodar em paralelo.
 *
 * As filas são do processo inteiro (não do serviço): uma tarefa de
 * encerramento que ficou na fila termina mesmo depois de o serviço acabar.
 * Nenhuma tarefa pode derrubar o processo: todo erro é registrado e engolido.
 */
object Filas {

    private const val TAG = "OrbionFilas"

    private fun fabrica(nome: String, daemon: Boolean) = object : ThreadFactory {
        private val n = AtomicInteger(0)
        override fun newThread(r: Runnable) = Thread(r, "$nome-${n.incrementAndGet()}").apply { isDaemon = daemon }
    }

    private val controle: ExecutorService = Executors.newSingleThreadExecutor(fabrica("orbion-controle", false))
    val agenda: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(fabrica("orbion-agenda", true))
    private val avulsos: ExecutorService = Executors.newCachedThreadPool(fabrica("orbion-avulso", true))
    val principal = Handler(Looper.getMainLooper())

    private fun protegido(nome: String, tarefa: () -> Unit) = Runnable {
        try {
            tarefa()
        } catch (e: InterruptedException) {
            Log.d(TAG, "$nome interrompida")
        } catch (t: Throwable) {
            Log.e(TAG, "Erro em $nome", t)
        }
    }

    /** Fila única e ordenada do motor (ligar, desligar, reconfigurar). */
    fun noControle(tarefa: () -> Unit) {
        controle.execute(protegido("controle", tarefa))
    }

    /** Tarefa independente, em paralelo. */
    fun avulso(tarefa: () -> Unit) {
        avulsos.execute(protegido("avulso", tarefa))
    }

    /** Roda daqui a [ms] milissegundos, na agenda. */
    fun depois(ms: Long, tarefa: () -> Unit): ScheduledFuture<*> =
        agenda.schedule(protegido("agenda", tarefa), ms, TimeUnit.MILLISECONDS)

    /** Repete a cada [periodoMs], começando daqui a [inicioMs]. */
    fun repetir(inicioMs: Long, periodoMs: Long, tarefa: () -> Unit): ScheduledFuture<*> =
        agenda.scheduleWithFixedDelay(protegido("agenda", tarefa), inicioMs, periodoMs, TimeUnit.MILLISECONDS)

    /** Volta para a linha principal (só para o que o Android exige nela). */
    fun naPrincipal(tarefa: () -> Unit) {
        principal.post(protegido("principal", tarefa))
    }
}
