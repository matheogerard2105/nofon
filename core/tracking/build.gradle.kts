plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.nofon.core.tracking"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    // Sélection de TrackingProvider par variante de build (ARCHITECTURE-2.md §9),
    // jamais par un booléen à l'exécution : le code simulé n'existe pas dans le
    // binaire "real". Le module :app déclare la même dimension pour pouvoir choisir.
    flavorDimensions += "tracking"
    productFlavors {
        create("real") {
            dimension = "tracking"
            buildConfigField("String", "TRACKING_SOURCE", "\"MEASURED\"")
        }
        create("simulated") {
            dimension = "tracking"
            buildConfigField("String", "TRACKING_SOURCE", "\"SIMULATED\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:database"))
    implementation(project(":core:network"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)

    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)

    testImplementation(libs.junit)
}
