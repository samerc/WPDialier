plugins {
    // AGP 9 requires Gradle 9 (C:\gradle\gradle-9.7.1); its R8 parses the
    // Kotlin 2.4 metadata that Play Billing 9.x ships with.
    id("com.android.application") version "9.3.2" apply false
    // Kotlin 2.3+ required by Play Billing 9.x artifacts.
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
