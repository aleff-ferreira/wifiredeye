import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

evaluationDependsOn(":app")
evaluationDependsOn(":cli-runner")

android {
    namespace = "com.a55.wifiradar.e2e"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // CRITICAL: Keep false so :e2e-tests does NOT generate a competing
            // /com/android/tools/test_config.properties that shadows :app's manifest & resources!
            isIncludeAndroidResources = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // STRICT OPAQUE-BOX COMPILE ISOLATION:
    // Zero project(":app"), project(":core:*"), project(":feature:*"), or project(":cli-runner")
    // dependencies! kotlinc physically rejects any internal com.a55.wifiradar.* import.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui)
    testImplementation(libs.androidx.compose.ui.graphics)
    testImplementation(libs.androidx.compose.ui.test.junit4)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit.ktx)
    testImplementation(libs.androidx.test.runner)
    testImplementation(libs.androidx.test.rules)
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.androidx.test.espresso.intents)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}

// Lazy runtime-only attachment for testDebugUnitTest:
// - Does NOT run during `--compile-only` (`:e2e-tests:compileDebugUnitTestKotlin`).
// - During test execution, attaches :app's merged manifest, compiled resources, MainActivity,
//   and runtime graph so Robolectric boots `com.a55.wifiradar` via PackageManager launch Intent.
tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    val testTask = this as Test
    val appProject = project(":app")
    val appTestTaskProvider = appProject.tasks.named<Test>("testDebugUnitTest")

    testTask.dependsOn(
        ":app:compileDebugKotlin",
        ":app:compileDebugJavaWithJavac",
        ":app:processDebugUnitTestManifest",
        ":app:packageDebugUnitTestForUnitTest",
        ":app:generateDebugUnitTestConfig",
        ":app:assembleDebug",
        ":cli-runner:installDist",
    )

    // Prepend :app's unit_test_config_directory + :app's production classes & runtime jars
    // (excluding :app's own unit test classes so only :e2e-tests @Test classes are discovered).
    val appRuntimeClasspath = appProject.files({
        appTestTaskProvider.get().classpath.filter { file ->
            val path = file.invariantSeparatorsPath
            val isAppUnitTestClasses = path.contains("/app/build/") &&
                (path.contains("UnitTest") && !path.contains("unit_test_config_directory") && !path.contains("apk-for-local-test"))
            !isAppUnitTestClasses
        }
    })
    // AGP assigns the test classpath late (after this block runs), so attach :app at execution time.
    testTask.doFirst {
        testTask.classpath = appRuntimeClasspath + testTask.classpath
    }

    testTask.maxParallelForks = 1
    testTask.maxHeapSize = "1536m"
    testTask.jvmArgs(
        "-XX:MaxMetaspaceSize=512m",
        "-Dfile.encoding=UTF-8",
        "-Djava.io.tmpdir=${rootProject.file(".tmp").absolutePath}",
        "-Djavax.net.ssl.trustStore=/home/antigravity/.android-sdk/cacerts.p12",
        "-Djavax.net.ssl.trustStorePassword=changeit",
    )
    testTask.systemProperty("robolectric.graphicsMode", "NATIVE")
    testTask.systemProperty("robolectric.logging", "stdout")
    testTask.systemProperty(
        "wifiradar.projectRoot",
        rootProject.projectDir.absolutePath,
    )
    testTask.systemProperty(
        "wifiradar.cliBinary",
        rootProject.file("cli-runner/build/install/cli-runner/bin/cli-runner").absolutePath,
    )
    testTask.systemProperty(
        "wifiradar.debugApk",
        rootProject.file("app/build/outputs/apk/debug/app-debug.apk").absolutePath,
    )
    testTask.systemProperty(
        "wifiradar.releaseApkDir",
        rootProject.file("app/build/outputs/apk/release").absolutePath,
    )
    testTask.systemProperty(
        "wifiradar.androidSdk",
        System.getenv("ANDROID_HOME") ?: "/home/antigravity/.android-sdk",
    )
}
