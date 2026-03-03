plugins {
  kotlin("jvm") version "2.3.0"
}

group = "tech.fouronesoft"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

dependencies {
  testImplementation(kotlin("test"))
}

kotlin {
  jvmToolchain(21)
}

tasks.test {
  useJUnitPlatform()
}