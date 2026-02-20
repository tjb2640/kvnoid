plugins {
  kotlin("jvm") version "2.3.0"
}

group = "tech.fouronesoft"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

dependencies {
  implementation(project.dependencies.platform("org.kotlincrypto.hash:bom:0.8.0"))
  implementation("org.kotlincrypto.hash:sha3")
  testImplementation(kotlin("test"))
}

kotlin {
  jvmToolchain(21)
}

tasks.test {
  useJUnitPlatform()
}