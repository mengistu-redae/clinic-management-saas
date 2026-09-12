# Multi-stage build: compile with Maven, ship only the JRE + jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first so an unchanged pom.xml lets Docker cache the (slow)
# dependency download and skip straight to compiling on subsequent builds.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
