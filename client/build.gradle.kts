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
    implementation(Libs.ktor_client_apache)
    implementation(Libs.ktor_client_jackson)
    implementation(Libs.gson)

    implementation(Libs.log4j_api)

    implementation(Libs.jackson_module_kotlin)
    implementation(Libs.jackson_datatype_jsr310)
    implementation(Libs.commons_lang3)

    testImplementation(project(":common", JarTest.configurationName))
    testImplementation(Libs.ktor_client_mock_jvm)
}


val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    freeCompilerArgs = listOf("-XXLanguage:+InlineClasses")
}

val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    freeCompilerArgs = listOf("-XXLanguage:+InlineClasses")
}
