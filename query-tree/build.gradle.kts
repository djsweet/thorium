@file:Suppress("UnstableApiUsage")

import kotlinx.benchmark.gradle.*

plugins {
    id("name.djsweet.query.listener.kotlin-library-conventions")
    id("org.jetbrains.kotlinx.benchmark") version "0.4.7"
    id("org.jetbrains.kotlin.plugin.allopen") version "1.8.21"
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
    annotation("org.openjdk.jmh.annotations.BenchmarkMode")
}

sourceSets {
    this.create("benchmarks").java {
        srcDir("src/benchmarks/kotlin")
    }
}

val benchmarksImplementation: Configuration = configurations.getAt("benchmarksImplementation")
val jqwikVersion = "1.7.3"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.5")
    testImplementation(platform("org.junit:junit-bom:5.9.2"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testImplementation("net.jqwik:jqwik:${jqwikVersion}")
    compileOnly("org.jetbrains:annotations:24.0.1")
    benchmarksImplementation(project(mapOf("path" to ":query-tree")))
    benchmarksImplementation("net.jqwik:jqwik:${jqwikVersion}")
    benchmarksImplementation("org.jetbrains.kotlinx:kotlinx-benchmark-runtime:0.4.7")
}

tasks.test {
    // As silly as this looks, Gradle sometimes gets very confused about
    // whether it needs to run the `test` task, because the artifacts
    // of the last test run get dumped, and it thinks "oh well these artifacts
    // are here so I don't need to do this."
    outputs.upToDateWhen { false }
    useJUnitPlatform {
        includeEngines("jqwik", "junit-jupiter")
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
    minHeapSize = "512m"
    maxHeapSize = "2048m"

    // jvmArgs = listOf("-XX:MaxPermSize=512m")
}

benchmark {
    configurations {
        create("point") {
            include("point.*")
        }
        create("iterator") {
            include("iterator.*")
        }
    }
    targets {
        register("benchmarks") {
            this as JvmBenchmarkTarget
            jmhVersion = "1.21"
        }
    }
}