plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.nofon.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nofon.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    // Doit correspondre à la dimension déclarée par :core:tracking et :feature:home
    // pour que Gradle résolve sans ambiguïté quelle variante de TrackingProvider lier.
    flavorDimensions += "tracking"
    productFlavors {
        create("real") {
            dimension = "tracking"
            resValue("string", "app_name", "Nofon")
        }
        create("simulated") {
            dimension = "tracking"
            applicationIdSuffix = ".simulated"
            // Sinon les deux variantes s'affichent avec le même nom sur l'appareil,
            // impossibles à distinguer — la connexion Google ne fonctionne que sur
            // "real" (com.nofon.app), voir SupabaseAuthRepository/PRODUCT_TODOS.
            resValue("string", "app_name", "Nofon (simulé)")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        resValues = true
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:tracking"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:home"))
    implementation(project(":feature:social"))
    implementation(project(":feature:group"))
    implementation(project(":feature:exploits"))
    implementation(project(":feature:profile"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
}
