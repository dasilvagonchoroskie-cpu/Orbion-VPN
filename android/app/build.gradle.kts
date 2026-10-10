import java.io.FileInputStream
import java.util.Properties

// Assinatura de produção: a esteira do GitHub escreve android/key.properties
// a partir dos segredos do repositório. Sem esse arquivo (build local de quem
// não tem a chave), cai na chave de depuração.
val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties()
val temAssinaturaDeProducao = keystorePropertiesFile.exists()
if (temAssinaturaDeProducao) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

plugins {
    id("com.android.application")
    // O plugin do Flutter precisa vir depois do Android.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.orbion.vpn"
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // Mesmo identificador do Orbion antigo.
        applicationId = "com.orbion.vpn"
        minSdk = 26
        targetSdk = 35
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    // OBRIGATÓRIO para o Orbion: o Tor, o lyrebird (obfs4) e o snowflake são
    // PROGRAMAS executados pelo app, guardados com nome de biblioteca
    // (libtor.so etc.). O Android só dá permissão de execução se eles forem
    // extraídos para a pasta de bibliotecas na instalação — é isso que o
    // "legacy packaging" faz.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        create("release") {
            if (temAssinaturaDeProducao) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }
    buildTypes {
        release {
            signingConfig = if (temAssinaturaDeProducao) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            // O motor é chamado por JNI (hev) e por reflexão do Android
            // (serviço, receptor). Sem encolhimento, nada some na compilação.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
