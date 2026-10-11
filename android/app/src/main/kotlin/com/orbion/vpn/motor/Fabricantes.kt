package com.orbion.vpn.motor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Locale

/**
 * O que cada marca de celular faz para fechar apps em segundo plano, e onde
 * fica o ajuste que impede isso.
 *
 * O Android puro respeita apps de VPN. Mas quase toda marca coloca um
 * "gerenciador de bateria" próprio por cima, que encerra apps sem pedir
 * licença — é o principal motivo de uma VPN cair sozinha. Esta tabela diz,
 * para cada marca, quais telas abrir e o que tocar em cada uma.
 *
 * Os nomes das opções vêm em português, com o nome em inglês entre
 * parênteses (o celular pode estar em qualquer um dos dois idiomas).
 *
 * Não usa as configurações do Orbion: pode ser chamada de qualquer processo.
 */
object Fabricantes {

    enum class Marca(val nome: String, val temInicioAutomatico: Boolean) {
        XIAOMI("Xiaomi / Redmi / POCO", true),
        SAMSUNG("Samsung", false),
        MOTOROLA("Motorola", false),
        HUAWEI("Huawei", true),
        HONOR("Honor", true),
        ONEPLUS("OnePlus", true),
        REALME("realme", true),
        OPPO("OPPO", true),
        VIVO("vivo / iQOO", true),
        ASUS("ASUS", true),
        NOKIA("Nokia", false),
        SONY("Sony", false),
        GOOGLE("Google Pixel", false),
        TRANSSION("Tecno / Infinix / itel", true),
        LG("LG", false),
        OUTRA("Android", false),
    }

    fun marca(): Marca {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(Locale.ROOT)
        val partes = m.split(' ', '-', '_')
        return when {
            "xiaomi" in m || "redmi" in m || "poco" in partes -> Marca.XIAOMI
            "samsung" in m -> Marca.SAMSUNG
            "motorola" in m -> Marca.MOTOROLA
            "honor" in m -> Marca.HONOR
            "huawei" in m -> Marca.HUAWEI
            "oneplus" in m -> Marca.ONEPLUS
            "realme" in m -> Marca.REALME
            "oppo" in m -> Marca.OPPO
            "vivo" in partes || "iqoo" in m -> Marca.VIVO
            "asus" in m -> Marca.ASUS
            "nokia" in m || "hmd" in partes -> Marca.NOKIA
            "sony" in m -> Marca.SONY
            "google" in m -> Marca.GOOGLE
            "tecno" in m || "infinix" in m || "itel" in partes || "transsion" in m -> Marca.TRANSSION
            "lge" in partes || "lg" in partes -> Marca.LG
            else -> Marca.OUTRA
        }
    }

    /** Marcas cujo gerenciador próprio o Android não deixa o app conferir. */
    fun temGerenciadorProprio(): Boolean = marca() !in setOf(Marca.GOOGLE, Marca.MOTOROLA, Marca.OUTRA, Marca.SONY)

    private const val BATERIA_PADRAO =
        "Configurações (Settings) → Apps → Orbion VPN → Bateria (Battery) → \"Sem restrições\" (Unrestricted)."

    /** Passo a passo da marca, na ordem em que vale a pena fazer. */
    fun passos(): List<String> = when (marca()) {
        Marca.XIAOMI -> listOf(
            "Início automático (Autostart): ligue a chave do Orbion VPN.",
            "Economia de bateria (Battery saver) do app: escolha \"Sem restrições\" (No restrictions).",
            "Na tela de apps recentes, segure o Orbion e toque no cadeado (Lock).",
        )
        Marca.SAMSUNG -> listOf(
            BATERIA_PADRAO,
            "Configurações → Cuidados com o aparelho (Device care) → Bateria (Battery) → Limites de uso em segundo plano (Background usage limits) → Apps que nunca são suspensos (Never sleeping apps) → adicione o Orbion VPN.",
            "Na tela de apps recentes, toque no ícone do Orbion → \"Manter aberto\" (Keep open).",
        )
        Marca.MOTOROLA -> listOf(
            BATERIA_PADRAO,
            "Se aparecer \"Otimização de bateria\" (Battery optimization), escolha \"Não otimizar\" (Don't optimize) para o Orbion.",
        )
        Marca.HUAWEI, Marca.HONOR -> listOf(
            "Configurações (Settings) → Bateria (Battery) → Inicialização de apps (App launch) → Orbion VPN → desligue \"Gerenciar automaticamente\" (Manage automatically) e ligue as três chaves: início automático, início secundário e execução em segundo plano.",
            "Na tela de apps recentes, puxe o Orbion para baixo para travar (cadeado).",
        )
        Marca.OPPO, Marca.REALME -> listOf(
            "Configurações (Settings) → Apps → Orbion VPN → Uso da bateria (Battery usage) → ligue \"Permitir atividade em segundo plano\" (Allow background activity) e \"Permitir início automático\" (Allow auto launch).",
            "Na tela de apps recentes, toque nos três pontinhos do Orbion → \"Bloquear\" (Lock).",
        )
        Marca.ONEPLUS -> listOf(
            "Configurações (Settings) → Apps → Orbion VPN → Uso da bateria (Battery usage) → \"Permitir atividade em segundo plano\" (Allow background activity) e \"Permitir início automático\" (Allow auto launch).",
            "Configurações → Bateria (Battery) → Otimização de bateria (Battery optimization) → Orbion VPN → \"Não otimizar\" (Don't optimize).",
            "Na tela de apps recentes, trave o Orbion (cadeado).",
        )
        Marca.VIVO -> listOf(
            "Configurações (Settings) → Bateria (Battery) → Consumo de energia em segundo plano (Background power consumption) → Orbion VPN → \"Permitir\" (Allow).",
            "i Manager → Gerenciador de apps (App manager) → Início automático (Autostart) → ligue o Orbion VPN.",
            "Na tela de apps recentes, trave o Orbion (cadeado).",
        )
        Marca.ASUS -> listOf(
            "Mobile Manager → Gerenciador de início automático (Auto-start Manager) → permita o Orbion VPN.",
            BATERIA_PADRAO,
        )
        Marca.NOKIA -> listOf(
            BATERIA_PADRAO,
            "Se existir \"Proteção de bateria\" (Battery protection / Power saver exceptions), coloque o Orbion nas exceções.",
        )
        Marca.SONY -> listOf(
            BATERIA_PADRAO,
            "Se usar o modo STAMINA (Configurações → Bateria), coloque o Orbion nas exceções ou desligue o modo.",
        )
        Marca.TRANSSION -> listOf(
            "Phone Master → Gerenciamento de apps (App management) → Início automático (Auto-start) → ligue o Orbion VPN.",
            BATERIA_PADRAO,
            "Na tela de apps recentes, trave o Orbion (cadeado).",
        )
        Marca.LG, Marca.GOOGLE, Marca.OUTRA -> listOf(
            BATERIA_PADRAO,
            "Se o seu celular tiver \"Início automático\" (Autostart), ligue para o Orbion.",
            "Se a tela de apps recentes tiver cadeado (Lock), trave o Orbion.",
        )
    }

    private fun comp(pacote: String, classe: String) = Intent().setComponent(ComponentName(pacote, classe))

    /** Telas de "Início automático" da marca, da mais provável para a menos. */
    fun telasDeInicioAutomatico(context: Context): List<Intent> {
        val pkg = context.packageName
        val huawei = listOf(
            comp("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            comp("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            comp("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        )
        val coloros = listOf(
            comp("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            comp("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            comp("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        )
        return when (marca()) {
            Marca.XIAOMI -> listOf(
                comp("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", pkg),
            )
            Marca.HUAWEI -> huawei
            Marca.HONOR -> listOf(
                comp("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ) + huawei
            Marca.OPPO, Marca.REALME -> coloros
            Marca.ONEPLUS -> listOf(
                comp("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            ) + coloros
            Marca.VIVO -> listOf(
                comp("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                comp("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                comp("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            )
            Marca.ASUS -> listOf(
                comp("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
                comp("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"),
            )
            Marca.TRANSSION -> listOf(
                comp("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
            )
            else -> emptyList()
        }
    }

    /** Telas de bateria da própria marca (além das do Android). */
    fun telasDeBateria(context: Context): List<Intent> {
        val pkg = context.packageName
        return when (marca()) {
            Marca.XIAOMI -> listOf(
                comp("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                    .putExtra("package_name", pkg)
                    .putExtra("package_label", "Orbion VPN"),
            )
            Marca.SAMSUNG -> listOf(
                comp("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                comp("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                comp("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            )
            Marca.OPPO, Marca.REALME, Marca.ONEPLUS -> listOf(
                comp("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
                comp("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerSaverModeActivity"),
            )
            Marca.VIVO -> listOf(
                comp("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
                comp("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            )
            Marca.HUAWEI, Marca.HONOR -> listOf(
                comp("com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"),
            )
            Marca.NOKIA -> listOf(
                comp("com.evenwell.powersaving.g3", "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity"),
            )
            else -> emptyList()
        }
    }

    /** O que dizer por cima da tela da marca que acabou de abrir. */
    fun instrucaoDaTelaDeBateria(): String = when (marca()) {
        Marca.XIAOMI -> "Escolha \"Sem restrições\" (No restrictions) e volte para o Orbion."
        Marca.SAMSUNG -> "Toque em Limites de uso em segundo plano (Background usage limits) → Apps que nunca são suspensos (Never sleeping apps) e adicione o Orbion VPN."
        Marca.VIVO -> "Encontre o Orbion VPN e permita o consumo em segundo plano."
        Marca.OPPO, Marca.REALME, Marca.ONEPLUS -> "Encontre o Orbion VPN e permita a atividade em segundo plano."
        else -> "Encontre o Orbion VPN e deixe sem restrições de bateria."
    }

    fun instrucaoDoInicioAutomatico(): String = when (marca()) {
        Marca.HUAWEI, Marca.HONOR -> "Encontre o Orbion VPN, desligue \"Gerenciar automaticamente\" e ligue as três chaves."
        else -> "Encontre o Orbion VPN na lista e ligue a chave dele."
    }
}
