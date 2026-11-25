plugins {
    id("com.android.library")
    `maven-publish`
}

group = "com.tappytaps"
version = "0.4.0"

android {
    namespace = "com.tappytaps.ttmailer"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // Only for the automatic purchases (TTM-585): the app brings its own Play Billing; without it the feature is off.
    compileOnly("com.android.billingclient:billing:7.1.1")
    // Rich push (TTM-624): links of the in-app browser action open in Custom Tabs.
    implementation("androidx.browser:browser:1.8.0")
    testImplementation("junit:junit:4.13.2")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.tappytaps"
            artifactId = "ttmailer"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        // A local repository for checks; apps get the library through JitPack (README).
        maven { name = "local"; url = uri(rootProject.layout.buildDirectory.dir("repo")) }
    }
}
