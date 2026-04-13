plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "openpod.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "openpod.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidFeature") {
            id = "openpod.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("androidCompose") {
            id = "openpod.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("kotlinLibrary") {
            id = "openpod.kotlin.library"
            implementationClass = "KotlinLibraryConventionPlugin"
        }
        register("androidHilt") {
            id = "openpod.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
    }
}
