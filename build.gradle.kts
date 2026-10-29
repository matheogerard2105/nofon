import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// Sans le support Kotlin intégré d'AGP 9 (désactivé, cf. gradle.properties), le
// plugin org.jetbrains.kotlin.android ne déduit pas sa cible JVM de
// android.compileOptions : chaque module la ciblerait sur le JDK du poste (21) au
// lieu de 17, en désaccord avec compileOptions. Configuré une seule fois ici plutôt
// que répété dans les dix modules.
subprojects {
    plugins.withId("org.jetbrains.kotlin.android") {
        extensions.configure<KotlinAndroidProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }
}
