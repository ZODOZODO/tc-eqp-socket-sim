// settings.gradle.kts
rootProject.name = "tc-eqp-socket-sim"

pluginManagement {
    repositories {
        // Gradle 플러그인 해석용
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // 라이브러리(의존성) 해석용
        mavenCentral()
    }
}