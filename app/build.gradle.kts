plugins {
    id("com.android.application")
    id("dev.detekt")
    jacoco
}

detekt {
    buildUponDefaultConfig = true
    source.setFrom("src/main/java", "src/test/java", "src/androidTest/java")
    baseline = file("detekt-baseline.xml")
}

android {
    namespace = "us.marreros.haandroidmediaplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "us.marreros.haandroidmediaplayer"
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 17
        versionName = "1.0.6"
    }

    signingConfigs {
        create("distribution") {
            val keystorePath = System.getenv("HAMEDIACONNECT_KEYSTORE_PATH")
            val keystorePassword = System.getenv("HAMEDIACONNECT_KEYSTORE_PASSWORD")
            val keyAliasValue = System.getenv("HAMEDIACONNECT_KEY_ALIAS")
            val keyPasswordValue = System.getenv("HAMEDIACONNECT_KEY_PASSWORD")
            if (listOf(keystorePath, keystorePassword, keyAliasValue, keyPasswordValue).all { !it.isNullOrBlank() }) {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("distribution")
        }
    }

    buildTypes.getByName("debug").enableAndroidTestCoverage = true

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.browser:browser:1.10.0")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

// Robolectric loads app classes through its sandbox classloader without a code source.
// Include those classes so the gate measures the security tests rather than only plain JVM tests.
tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

jacoco {
    toolVersion = "0.8.13"
}

val coverageExclusions = listOf(
    "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*",
    // Android framework entry points and live transport/Keystore adapters are
    // covered by device/integration testing rather than JVM unit coverage.
    "**/BootReceiver*", "**/ConnectionService*", "**/MainActivity*",
    "**/PlaybackService*", "**/SecureTokenStore*", "**/HaOAuth*",
    "**/HaWebSocketClient*", "**/AppPrefs*", "**/PlayerStateBus*",
    "**/PlayerSnapshot*", "**/AndroidDeviceKeys*", "**/PlaybackNotifications*"
)

tasks.register<JacocoReport>("appCodeCoverageReport") {
    dependsOn("testDebugUnitTest")
    executionData.setFrom(fileTree(layout.buildDirectory) { include("jacoco/testDebugUnitTest.exec") })
    val classes = fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
        exclude(coverageExclusions)
    }
    val kotlinClasses = fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
        exclude(coverageExclusions)
    }
    classDirectories.setFrom(classes, kotlinClasses)
    sourceDirectories.setFrom(files("src/main/java"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.register<JacocoCoverageVerification>("verifyAppCodeCoverage") {
    dependsOn("appCodeCoverageReport")
    executionData.setFrom(fileTree(layout.buildDirectory) { include("jacoco/testDebugUnitTest.exec") })
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
            exclude(coverageExclusions)
        },
        fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
            exclude(coverageExclusions)
        }
    )
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.85".toBigDecimal()
            }
        }
    }
}

// Explicit security targets, independent of the broad application-report exclusions.
val securityPolicyTargets = listOf(
    "**/CredentialTransactions*.class",
    "**/ConnectionFailure*.class", "**/ConnectionEvent*.class", "**/ConnectionPhase*.class", "**/FailureStage*.class",
    "**/DeviceIdentity*.class", "**/HaWebSocketClient*.class", "**/SecureTokenStore*.class",
    "**/HaOAuth*.class", "**/OAuthAttempt*.class", "**/OAuthCallback*.class",
    "**/TransportPolicy*.class", "**/ControllerPolicy*.class", "**/InternalPlayback*.class",
    "**/PlaybackNotifications*.class"
)
fun securityClasses(targets: List<String>) = files(
    fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) { include(targets) },
    fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) { include(targets) }
)

tasks.register<JacocoReport>("securityCodeCoverageReport") {
    dependsOn("testDebugUnitTest")
    executionData.setFrom(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))
    classDirectories.setFrom(securityClasses(securityPolicyTargets))
    sourceDirectories.setFrom(files("src/main/java"))
    reports { xml.required.set(true); html.required.set(true) }
}

tasks.register<JacocoCoverageVerification>("verifySecurityCodeCoverage") {
    dependsOn("securityCodeCoverageReport")
    executionData.setFrom(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))
    classDirectories.setFrom(securityClasses(securityPolicyTargets))
    violationRules {
        rule { limit { counter = "LINE"; value = "COVEREDRATIO"; minimum = "0.85".toBigDecimal() } }
    }
}

// This report shows device-measured framework/Keystore coverage separately; no JVM percentage
// claims to cover hardware. The connected-test task fails if no device is available.
tasks.register("verifySecurityDeviceCoverageData") {
    dependsOn("connectedDebugAndroidTest")
    doLast {
        val data = fileTree(layout.buildDirectory.dir("outputs/code_coverage")) { include("**/*.ec") }
        check(data.files.any { it.length() > 0 }) { "Connected security tests produced no device coverage data" }
    }
}

tasks.register<JacocoReport>("securityDeviceCoverageReport") {
    dependsOn("verifySecurityDeviceCoverageData")
    executionData.setFrom(fileTree(layout.buildDirectory.dir("outputs/code_coverage")) { include("**/*.ec") })
    classDirectories.setFrom(securityClasses(listOf("**/AndroidDeviceKeys*.class", "**/PlaybackService*.class", "**/SecureTokenStore*.class")))
    sourceDirectories.setFrom(files("src/main/java"))
    reports { xml.required.set(true); html.required.set(true) }
}
