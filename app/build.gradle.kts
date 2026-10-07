plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

android {
    namespace = "app.anglerfish"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.anglerfish"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.material)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.dnsjava)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom("$projectDir/src/main/java")
}

// Generates the XML report scripts/manage.py's coverage command reads (app/build/reports/kover/
// reportDebug.xml). The threshold (issue #23) is measured against testable code only: the
// excluded classes below are the ones contexts/design-patterns.md documents as deliberately
// manual (VpnService.Builder, real PackageManager/socket/HTTP glue, Compose UI) or trivial
// wiring (DI container, ViewModel factories, the Application class) -- none of it is meant to
// be unit-tested, so it should not silently drag the gate down. manage.py's own Python-side 80%
// check stays a soft report/warning, unaffected by this filter.
dependencies {
    kover(project(":core"))
}

koverReport {
    androidReports("debug") {
        filters {
            excludes {
                classes(
                    "**.R",
                    "**.R$*",
                    "**.BuildConfig",
                    "**.Manifest*",
                    "**.*Test*",
                    "android.*",
                    "androidx.*",
                    // Compose UI -- manual, see contexts/tests.md
                    "**.AppListScreenKt*",
                    "**.BlocklistScreenKt*",
                    "**.ComposableSingletons\$*",
                    "**.MainActivity*",
                    "**.Screen",
                    // VpnService.Builder / real Context -- cannot be unit-tested, see contexts/architecture.md
                    "**.AnglerfishVpnService*",
                    "**.VpnController",
                    "**.VpnConsentKt",
                    // Real PackageManager/socket/HTTP glue -- untested, same treatment as the above
                    "**.InstalledAppsProvider",
                    "**.HttpBlocklistFetcher*",
                    "**.UdpDnsForwarder*",
                    "**.ConnectivityManagerDnsResolverProvider",
                    "**.UdpRelay*",
                    "**.TcpRelay*",
                    "**.RelaySessionFactory",
                    "**.TunWriter",
                    // Trivial wiring -- no branching logic worth gating on
                    "**.AppContainer*",
                    "**.AnglerfishApplication*",
                    "**.AppListViewModelFactory",
                    "**.BlocklistViewModelFactory",
                )
            }
        }
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
