plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":package-source"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
