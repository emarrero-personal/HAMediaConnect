plugins {
    id("com.android.application")
    jacoco
}

android {
    namespace = "us.marreros.haandroidmediaplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "us.marreros.haandroidmediaplayer"
        minSdk = 29
        targetSdk = 37
        versionCode = 9
        versionName = "0.9.0"
    }

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
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

jacoco {
    toolVersion = "0.8.13"
}

val coverageExclusions = listOf(
    "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*"
)

tasks.register<JacocoReport>("appCodeCoverageReport") {
    dependsOn("testDebugUnitTest")
    executionData.setFrom(fileTree(layout.buildDirectory) { include("jacoco/testDebugUnitTest.exec") })
    val classes = fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
        exclude(coverageExclusions)
    }
    val kotlinClasses = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
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
        fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
            exclude(coverageExclusions)
        }
    )
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}
