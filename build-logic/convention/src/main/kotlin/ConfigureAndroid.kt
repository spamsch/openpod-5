import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project

internal fun Project.configureAndroid(extension: Any) {
    when (extension) {
        is ApplicationExtension -> extension.apply {
            compileSdk = 36
            defaultConfig {
                minSdk = 29
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
        is LibraryExtension -> extension.apply {
            compileSdk = 36
            defaultConfig {
                minSdk = 29
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }
}

internal val Project.libs
    get() = extensions.getByType(
        org.gradle.api.artifacts.VersionCatalogsExtension::class.java
    ).named("libs")
