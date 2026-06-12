@file:Suppress("UnstableApiUsage", "unused")

import extensions.BuildNames
import extensions.BuildNames.versionCatalogLibrary
import extensions.Versions.BUILD_TOOLS_VERSION
import extensions.Versions.JavaVersion.KOTLIN_OPTIONS_JVM_TARGET
import extensions.Versions.JavaVersion.PROJECT_JAVA_VERSION
import extensions.Versions.SdkVersions.COMPILE_SDK_VERSION
import extensions.Versions.SdkVersions.MIN_SDK_VERSION
import extensions.androidTestExtension
import extensions.checks
import extensions.excludeList
import extensions.junitExtension
import extensions.testExtension
import generated.accompanistBundle
import generated.androidxBundle
import generated.animationBundle
import generated.cameraBundle
import generated.coroutinesBundle
import generated.cryptoBundle
import generated.databaseBundle
import generated.datamatrixBundle
import generated.datetimeBundle
import generated.diBundle
import generated.imageBundle
import generated.kotlinStdlibLibrary
import generated.lifecycleBundle
import generated.loggingNapierLibrary
import generated.mapsBundle
import generated.materialLibrary
import generated.networkBundle
import generated.othersBundle
import generated.pdfboxBundle
import generated.playBundle
import generated.processphoenixBundle
import generated.serializationBundle
import java.util.Properties

plugins {
    id("com.android.library")
    id("kotlin-android")
    id("kotlin-parcelize")
    kotlin("plugin.serialization")
    id("io.realm.kotlin")
    id("quality-detekt")
    id("org.jetbrains.compose")
    id("org.owasp.dependencycheck")
    id("com.jaredsburrows.license")
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
    id("jacoco")
    id("compose-convention")
}

val versionCatalog: VersionCatalog =
    extensions.getByType<VersionCatalogsExtension>().named(versionCatalogLibrary)

licenseReport {
    generateCsvReport = false
    generateHtmlReport = false
    generateJsonReport = true
    copyJsonReportToAssets = true
}

android {
    buildToolsVersion = BUILD_TOOLS_VERSION
    compileSdk = COMPILE_SDK_VERSION
    defaultConfig {
        minSdk = MIN_SDK_VERSION
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    dependencyCheck {
        checks(project.configurations)
    }
    kotlinOptions {
        jvmTarget = KOTLIN_OPTIONS_JVM_TARGET
        freeCompilerArgs += "-opt-in=kotlin.RequiresOptIn"
    }
    compileOptions {
        sourceCompatibility = PROJECT_JAVA_VERSION
        targetCompatibility = PROJECT_JAVA_VERSION
    }
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        unitTests.isIncludeAndroidResources = true
    }
    // for JNA and JNA-platform
    // for byte-buddy
    packaging {
        jniLibs {
            useLegacyPackaging = false // Required for 16KB page size support (AGP 8.5.1+ aligns uncompressed .so files automatically)
        }
        resources {
            excludes += "META-INF/**"
            // for JNA and JNA-platform
            excludes += "META-INF/AL2.0"
            excludes += "META-INF/LGPL2.1"
            // for byte-buddy
            excludes += "META-INF/licenses/ASM"
            pickFirsts += "win32-x86-64/attach_hotspot_windows.dll"
            pickFirsts += "win32-x86/attach_hotspot_windows.dll"
        }
    }

    buildTypes {
        val debug by getting {
            isJniDebuggable = true
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
    }
    buildFeatures {
        buildConfig = true
    }
    androidResources {
        noCompress.addAll(listOf("srt", "csv", "json"))
    }
}

jacoco {
    toolVersion = BuildNames.jacocoToolsVersion
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    val mainSrc = files("src/main/java", "src/main/kotlin")
    val debugTree = fileTree("${layout.buildDirectory}/intermediates/javac/debug/classes")
    val kotlinDebugTree = fileTree("${layout.buildDirectory}/tmp/kotlin-classes/debug")
    val execFiles = fileTree(layout.buildDirectory).include("**/*.exec")

    sourceDirectories.setFrom(mainSrc)
    classDirectories.setFrom(files(debugTree, kotlinDebugTree).asFileTree.matching {
        excludeList()
    })
    executionData.setFrom(execFiles)
}

dependencies {
    implementation(project(":erp-model"))
    implementation(versionCatalog.pdfboxBundle) {
        exclude(group = "org.bouncycastle")
    }
    implementation(versionCatalog.kotlinStdlibLibrary)
    implementation(kotlin("reflect"))
    implementation(versionCatalog.materialLibrary)
    implementation(versionCatalog.datamatrixBundle)
    implementation(versionCatalog.coroutinesBundle)
    implementation(versionCatalog.datetimeBundle)
    implementation(versionCatalog.accompanistBundle)
    implementation(versionCatalog.othersBundle)
    debugImplementation(versionCatalog.processphoenixBundle)
    implementation(versionCatalog.androidxBundle)
    implementation(versionCatalog.lifecycleBundle)
    implementation(versionCatalog.cameraBundle)
    compileOnly(versionCatalog.databaseBundle)
    implementation(versionCatalog.diBundle)
    implementation(versionCatalog.imageBundle)
    implementation(versionCatalog.cryptoBundle)
    implementation(versionCatalog.mapsBundle)
    implementation(versionCatalog.networkBundle)
    implementation(versionCatalog.animationBundle)
    implementation(versionCatalog.loggingNapierLibrary)
    implementation(versionCatalog.playBundle)
    implementation(versionCatalog.serializationBundle)

    androidTestExtension(versionCatalog)
    testExtension(versionCatalog)
    junitExtension(versionCatalog)
}

secrets {
    defaultPropertiesFileName = when {
        project.rootProject.file("ci-overrides.properties").exists() -> "ci-overrides.properties"
        else -> "gradle.properties"
    }
}

// Trust local development CAs (e.g. OrbStack) in unit-test JVMs.
// No-op unless LOCAL_TEST_TRUSTSTORE is set in ci-overrides.properties or passed as a Gradle property.
// javax.net.ssl.trustStore REPLACES the JVM default truststore, so the referenced file must contain
// the full JDK cacerts plus the local CA (not the local CA alone).
val ciOverrideProperties = Properties().apply {
    val file = project.rootProject.file("ci-overrides.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val localTestTrustStore = ciOverrideProperties.getProperty("LOCAL_TEST_TRUSTSTORE")
    ?: project.findProperty("LOCAL_TEST_TRUSTSTORE") as? String
if (localTestTrustStore != null) {
    val localTestTrustStorePassword = ciOverrideProperties.getProperty("LOCAL_TEST_TRUSTSTORE_PASSWORD")
        ?: project.findProperty("LOCAL_TEST_TRUSTSTORE_PASSWORD") as? String
        ?: "changeit"
    tasks.withType<Test>().configureEach {
        systemProperty("javax.net.ssl.trustStore", localTestTrustStore)
        systemProperty("javax.net.ssl.trustStorePassword", localTestTrustStorePassword)
    }
}
