import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HostTestBuilder
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.roborazzi) apply false
}

val sharedTmpDir = layout.projectDirectory.dir(".tmp").asFile

subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<LibraryAndroidComponentsExtension> {
            beforeVariants(selector().withBuildType("release")) { variantBuilder ->
                variantBuilder.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
            }
        }
    }

    plugins.withId("com.android.application") {
        extensions.configure<ApplicationAndroidComponentsExtension> {
            beforeVariants(selector().withBuildType("release")) { variantBuilder ->
                variantBuilder.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
            }
        }
    }

    tasks.withType<Test>().configureEach {
        sharedTmpDir.mkdirs()
        maxHeapSize = "1536m"
        maxParallelForks = 1
        systemProperty("java.io.tmpdir", sharedTmpDir.absolutePath)
        systemProperty("javax.net.ssl.trustStore", "/home/antigravity/.android-sdk/cacerts.p12")
        systemProperty("javax.net.ssl.trustStorePassword", "changeit")
        systemProperty("robolectric.graphicsMode", "NATIVE")
        systemProperty("robolectric.logging", "stdout")
    }
}
