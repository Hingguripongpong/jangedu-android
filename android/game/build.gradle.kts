// Pure Kotlin/JVM module: janggi rules (move generation, rules, notation, UCI mapping, win-probability model).
// No Android dependency so the whole rule set is unit-tested on the JVM (and reused by tools/).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test> {
    useJUnit()
    maxHeapSize = "1g"
    testLogging { events("passed", "failed", "skipped") }
}
