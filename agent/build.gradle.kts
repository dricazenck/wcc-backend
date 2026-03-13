plugins {
    java
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.anthropic:anthropic-java:2.15.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
}

application {
    mainClass.set("com.wcc.agent.WccCodingAgent")
}

tasks.withType<JavaExec> {
    standardInput = System.`in`
    // Forward ANTHROPIC_API_KEY from the shell into the forked JVM.
    // The Gradle daemon may have started before the env var was exported,
    // so we also accept it as a -D JVM arg: -Danthropic.api.key=sk-ant-...
    val envKey = System.getenv("ANTHROPIC_API_KEY")
    if (!envKey.isNullOrBlank()) environment("ANTHROPIC_API_KEY", envKey)
}
