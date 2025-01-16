/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import io.quarkus.gradle.tasks.QuarkusBuild

plugins {
  alias(libs.plugins.quarkus)
  alias(libs.plugins.jandex)
  alias(libs.plugins.openapi.generator)
  id("polaris-quarkus")
  // id("polaris-license-report")
  id("distribution")
}

val quarkusRunner by
  configurations.creating {
    description = "Used to reference the generated runner-jar (either fast-jar or uber-jar)"
  }

dependencies {
  implementation(project(":polaris-core"))
  implementation(project(":polaris-api-management-service"))
  implementation(project(":polaris-api-iceberg-service"))
  implementation(project(":polaris-service-common"))
  implementation(project(":polaris-quarkus-service"))

  // enforce the Quarkus _platform_ here, to get a consistent and validated set of dependencies
  implementation(enforcedPlatform(libs.quarkus.bom))
  implementation("io.quarkus:quarkus-container-image-docker")

  // override dnsjava version in dependencies due to https://github.com/dnsjava/dnsjava/issues/329
  implementation(platform(libs.dnsjava))
}

val quarkusFatJar = project.hasProperty("uber-jar") || project.hasProperty("release")

quarkus {
  quarkusBuildProperties.put("quarkus.package.type", if (quarkusFatJar) "uber-jar" else "fast-jar")
  // Pull manifest attributes from the "main" `jar` task to get the
  // release-information into the jars generated by Quarkus.
  quarkusBuildProperties.putAll(
    provider {
      tasks
        .named("jar", Jar::class.java)
        .get()
        .manifest
        .attributes
        .map { e -> "quarkus.package.jar.manifest.attributes.\"${e.key}\"" to e.value.toString() }
        .toMap()
    }
  )
}

tasks.named("distZip") { dependsOn("quarkusBuild") }

tasks.named("distTar") { dependsOn("quarkusBuild") }

tasks.withType<Javadoc> { isFailOnError = false }

tasks.register("polarisServerRun") { dependsOn("quarkusRun") }

distributions {
  main {
    contents {
      if (quarkusFatJar) {
        from(project.layout.buildDirectory) { include("polaris-quarkus-admin-*-runner.jar") }
      } else {
        from(project.layout.buildDirectory.dir("quarkus-app"))
      }
      from("../../NOTICE")
      from("../../LICENSE-BINARY-DIST").rename("LICENSE-BINARY-DIST", "LICENSE")
      exclude("lib/main/io.quarkus.quarkus-container-image*")
    }
  }
}

val quarkusBuild = tasks.named<QuarkusBuild>("quarkusBuild")

// Expose runnable jar via quarkusRunner configuration for integration-tests that require the
// server.
artifacts {
  add(
    quarkusRunner.name,
    provider {
      if (quarkusFatJar) quarkusBuild.get().runnerJar
      else quarkusBuild.get().fastJar.resolve("quarkus-run.jar")
    },
  ) {
    builtBy(quarkusBuild)
  }
}

// Add the uber-jar, if built, to the Maven publication
if (quarkusFatJar) {
  afterEvaluate {
    publishing {
      publications {
        named<MavenPublication>("maven") {
          artifact(quarkusBuild.get().runnerJar) {
            classifier = "runner"
            builtBy(quarkusBuild)
          }
        }
      }
    }
  }
}
