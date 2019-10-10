import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    application
}

repositories {
    maven { url = uri("https://dl.bintray.com/kotlin/ktor") }
}

apply<JUnit5Plugin>()

// --- dependencies ---

repositories {
    maven("https://kotlin.bintray.com/kotlinx")
}

application {
    mainClassName = "revolut.accounts.integration.MainKt"
}

dependencies {
    implementation(project(":common"))
    implementation(project(":core"))
    implementation(project(":dal"))
    implementation(project(":web"))

    implementation(Libs.kotlin_stdlib_jdk8)
    implementation(Libs.kotlinx_coroutines_core)
    implementation(Libs.ktor_jackson)
    implementation(Libs.ktor_server_core)
    implementation(Libs.ktor_server_netty)
    implementation(Libs.ktor_locations) {
        exclude(group = "junit")
    }

    implementation(Libs.jackson_annotations)

    implementation(Libs.jackson_datatype_jsr310)

    implementation(Libs.postgresql)
    implementation(Libs.hikaricp)

    // since we are running in-memory db
    implementation(Libs.otj_pg_embedded)
    implementation(Libs.liquibase_core){
        exclude(group = "ch.qos.logback")
    }

    // jOOQ Open Source Edition
    implementation(Libs.jooq)
    implementation(Libs.jooq_meta)
    implementation(Libs.jooq_codegen)

    implementation("com.github.ajalt:clikt:2.2.0")

    implementation(Libs.netty_transport_native_epoll)
    implementation(Libs.netty_transport_native_unix_common)

    implementation(Libs.log4j_core)
    implementation(Libs.log4j_jcl)
    implementation(Libs.log4j_jul)
    implementation(Libs.log4j_slf4j_impl)
    implementation(Libs.disruptor)
}


val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    freeCompilerArgs = listOf("-XXLanguage:+InlineClasses", "-Xuse-experimental=io.ktor.locations.KtorExperimentalLocationsAPI")
}

val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    freeCompilerArgs = listOf("-XXLanguage:+InlineClasses", "-Xuse-experimental=io.ktor.locations.KtorExperimentalLocationsAPI")
}
