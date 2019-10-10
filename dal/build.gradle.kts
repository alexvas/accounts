@file:Suppress("UnstableApiUsage")

import com.opentable.db.postgres.embedded.EmbeddedPostgres
import nu.studer.gradle.jooq.JooqConfiguration
import nu.studer.gradle.jooq.JooqEdition
import nu.studer.gradle.jooq.JooqExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jooq.meta.jaxb.Configuration
import org.jooq.meta.jaxb.Database
import org.jooq.meta.jaxb.Generator
import org.jooq.meta.jaxb.Jdbc


repositories {
    maven("https://kotlin.bintray.com/kotlinx")
}

plugins {
    kotlin("jvm")
    id("org.liquibase.gradle")
    id("nu.studer.jooq")
}

buildscript {
    dependencies {
        classpath(Libs.otj_pg_embedded)
    }
}

apply<JUnit5Plugin>()

dependencies {
    jooqRuntime(Libs.postgresql)

    implementation(project(":common"))

    implementation(Libs.kotlin_stdlib_jdk8)

    implementation(Libs.log4j_api)

    liquibaseRuntime(Libs.liquibase_core)
    liquibaseRuntime(Libs.postgresql)

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

    testImplementation(project(":common", JarTest.configurationName))
    testImplementation(Libs.kotlinx_coroutines_core)
}

fun JooqExtension.applyCfg(configName: String, sourceSet: SourceSet, closure: Action<Configuration>) {
    var jooqConfig = configs[configName]
    if (jooqConfig == null) {
        jooqConfig = JooqConfiguration(configName, sourceSet, Configuration())
        whenConfigAdded(jooqConfig)
        configs[configName] = jooqConfig
    }
    require(sourceSet.name == jooqConfig.sourceSet.name) { "Configuration '$configName' configured for multiple source sets: $sourceSet and $jooqConfig.sourceSet" }
    closure(jooqConfig.configuration)
}

jooq {
    version = Versions.org_jooq
    edition = JooqEdition.OSS // open source database

    //generateMainJooqSchemaSource -- created task name
    //        ^^^^
    applyCfg("main", sourceSets["main"]) {
        withJdbc(Jdbc()
                .withDriver("org.postgresql.Driver")
                .withUrl("no url") // configure later before task start
                .withSchema("public")
        )
        withGenerator(Generator()
                .withDatabase(Database()
                        .withName("org.jooq.meta.postgres.PostgresDatabase")
                        .withIncludes(".*")
                        .withExcludes("databasechangelog | databasechangeloglock") // liquibase tables
                        .withInputSchema("public")
                )
                .withTarget(org.jooq.meta.jaxb.Target()
                        .withPackageName("revolute.accounts.dal.jooq")
                )
        )
    }
}

val liquibaseDir: File = project(":dal").projectDir.resolve("src/main/liquibase").canonicalFile
val liquibaseMainFiles = fileTree(liquibaseDir) { include("**/*.xml", "**/*.sql") }

liquibase {
    activities.register("main") {
        this.arguments = mapOf(
                "classpath" to liquibaseDir,
                "logLevel" to "info",
                "changeLogFile" to "changelog.xml",
                "url" to "no url" // configure later before task start
        )
    }
    runList = "main"
}

val jdbcUrl: String by lazy {
    val embp = EmbeddedPostgres.builder().start()
    logger.warn("[lazy jdbcUrl] start embedded Postgres on port ${embp.port}")
    embp.getJdbcUrl("postgres", "postgres")
}

val liqbUpdate by tasks.named("update")

tasks {
    register("targetDb") {
        // force run liquibase "update" task
        doLast {
            liqbUpdate.outputs.upToDateWhen { false }
        }
    }

    "update" { // liquibase
        inputs.files(liquibaseMainFiles)
        liqbUpdate.outputs.upToDateWhen { false } // a hack to always run update task
                                                  // since update results are not persisted
                                                  // when using in-memory database

        doFirst {
            // configure jdbcUrl
            liquibase.activities.forEach { a ->
                @Suppress("UNCHECKED_CAST")
                (a.arguments as MutableMap<String, Any?>)["url"] = jdbcUrl as Any?
            }
        }
    }

    clean {
        delete("target") // default jooq output dir
    }

    "generateMainJooqSchemaSource" {
        inputs.files(liquibaseMainFiles)

        dependsOn("update") // liquibase

        doFirst {
            // configure jdbcUrl
            jooq.configs["main"]!!.configuration.jdbc.url = jdbcUrl
        }
    }

    "compileKotlin" {
        dependsOn("generateMainJooqSchemaSource")
    }
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    freeCompilerArgs = listOf("-XXLanguage:+InlineClasses")
}

val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    freeCompilerArgs = listOf("-XXLanguage:+InlineClasses")
}
