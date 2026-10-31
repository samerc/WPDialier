plugins {
    // AGP 9.5 requires Gradle 9.5+ (pinned by ./gradlew); its R8 parses the
    // Kotlin 2.4 metadata that Play Billing 9.x ships with.
    id("com.android.application") version "9.5.0" apply false
    // Kotlin 2.3+ required by Play Billing 9.x artifacts.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
