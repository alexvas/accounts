import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
}

repositories {
    maven { url = uri("https://dl.bintray.com/kotlin/ktor") }
}

apply<JUnit5Plugin>()

// --- dependencies ---

repositories {
    maven("https://kotlin.bintray.com/kotlinx")
}

dependencies {
    implementation(project(":common"))
    implementation(Libs.kotlin_stdlib_jdk8)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.ktor_jackson)
    implementation(Libs.ktor_server_core)
    implementation(Libs.ktor_server_netty)
    implementation(Libs.ktor_locations) {
        exclude(group = "junit")
    }

    implementation(Libs.log4j_api)

    implementation(Libs.jackson_datatype_jsr310)

    testImplementation(project(":common", JarTest.configurationName))
    testImplementation(Libs.ktor_server_test_host) {
        exclude(group = "junit")
    }
    testImplementation(Libs.gson)
}


val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    freeCompilerArgs = listOf("-XXLanguage:+InlineClasses", "-Xuse-experimental=kotlin.ExperimentalUnsignedTypes", "-Xuse-experimental=io.ktor.locations.KtorExperimentalLocationsAPI")
}

val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    freeCompilerArgs = listOf("-XXLanguage:+InlineClasses", "-Xuse-experimental=kotlin.ExperimentalUnsignedTypes", "-Xuse-experimental=io.ktor.locations.KtorExperimentalLocationsAPI")
}
