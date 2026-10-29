# =============================================================================================
# LOCAL Jenkins controller for the learning setup (docker compose --profile ci up -d jenkins).
#
# Jenkins itself runs on JDK 17 (lts-jdk17). The project is built with JDK 8, so the image also
# contains the exact toolchain the Jenkinsfile asks for - configure them once in
# Manage Jenkins > Tools (untick "Install automatically"):
#   JDK    "jdk8"      -> /opt/jdk8              (build + tests: Java 8 bytecode)
#   JDK    "jdk17"     -> /opt/java/openjdk      (SonarQube 10 scanner needs a Java 17 runtime)
#   Maven  "maven-3.9" -> /opt/maven-3.9
# The Jenkinsfile prefers ./mvnw anyway; the Maven tool is a fallback / for the tools{} block.
#
# Tools are copied from official images (COPY --from) instead of apt/curl downloads: pinned,
# multi-arch (arm64 on Apple Silicon), no package repo setup.
# Running builds on the controller is fine for a laptop; real setups use separate agents.
# =============================================================================================
FROM eclipse-temurin:8-jdk AS jdk8
FROM maven:3.9.9-eclipse-temurin-8 AS maven
FROM docker:27-cli AS dockercli

FROM jenkins/jenkins:lts-jdk17

USER root
COPY --from=jdk8      /opt/java/openjdk /opt/jdk8
COPY --from=maven     /usr/share/maven  /opt/maven-3.9
# docker CLI + compose v2 plugin: talk to the HOST daemon through the mounted socket.
COPY --from=dockercli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=dockercli /usr/local/libexec/docker/cli-plugins/docker-compose /usr/local/libexec/docker/cli-plugins/docker-compose
RUN ln -s /opt/maven-3.9/bin/mvn /usr/local/bin/mvn \
 && apt-get update && apt-get install -y --no-install-recommends jq && rm -rf /var/lib/apt/lists/*

USER jenkins
# Plugins needed by the Jenkinsfile (see deploy/jenkins/README.md). Versions float to the
# latest compatible release at build time; pin "plugin:version" for reproducibility.
COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
