rootProject.name = "accounts"

include(
        "common",
        "dal",
        "core",
        "web",
        "client",
        "app",
        "white-box"
)

pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "kotlinx-serialization") {
                useModule("org.jetbrains.kotlin:kotlin-serialization:${requested.version}")
            }
        }
    }
}
