plugins {
    // JVM 21 toolchain 자동 프로비저닝(설치 안 돼 있으면 다운로드).
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "central-server"
