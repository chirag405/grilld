# syntax=docker/dockerfile:1

# --- build: compile with the full JDK, then extract Spring Boot's layered jar ---
# amazoncorretto:26 to match the JDK this project is actually built and tested
# against (pom.xml compiles with release 26) - a JRE-only or older-JDK base would
# either fail to compile or silently target a different bytecode level.
FROM amazoncorretto:26 AS build
WORKDIR /build

# mvnw (distributionType=only-script, .mvn/wrapper/maven-wrapper.properties) needs
# unzip to bootstrap its own Maven distribution - the corretto base image doesn't
# ship it.
RUN yum install -y unzip && yum clean all

# Dependencies first, source after: this layer only invalidates (and re-downloads
# the whole dependency tree) when pom.xml itself changes, not on every source edit.
COPY mvnw .
COPY .mvn/ .mvn/
COPY pom.xml .
RUN chmod +x ./mvnw && ./mvnw -B dependency:go-offline

COPY src/ src/
RUN ./mvnw -B clean package -DskipTests

# The extracted launcher jar keeps the input jar's name - rename to application.jar
# first so the runtime stage's `java -jar application.jar` actually finds it.
RUN cp target/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# --- runtime: headless JRE only, no Maven/JDK/build tools in the shipped image ---
FROM amazoncorretto:26-al2023-headless
WORKDIR /application

# A fixed non-root numeric UID, not a named user (the headless image has no
# package manager to add one with, and doesn't need one - Docker's USER
# instruction accepts a bare uid).
# One COPY per extracted layer (dependencies change least often, application code
# most often) so a source-only change invalidates just the last, smallest layer.
COPY --from=build --chown=1001:1001 /build/extracted/dependencies/ ./
COPY --from=build --chown=1001:1001 /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=1001:1001 /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=1001:1001 /build/extracted/application/ ./

USER 1001:1001
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "application.jar"]
