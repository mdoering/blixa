# syntax=docker/dockerfile:1
#
# Backend image: builds the Spring Boot API into an executable jar (JDK 25) and runs it on a JRE.
# The CLB reader/vocab snapshots resolve from repository.gbif.org (see pom.xml <repositories>), so
# the build needs network access. Tests are skipped here (they use Testcontainers / Docker); run
# them with `mvn verify` locally or in CI, not in the image build.

# --- build ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
# Wrapper + pom first so dependency resolution layer-caches independently of the source.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests dependency:go-offline || true
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests clean package \
    && cp target/coldp-editor-backend-*.jar /build/app.jar

# --- runtime ---
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd -r app && useradd -r -g app app \
    && mkdir -p /data/exports /data/imports
COPY --from=build /build/app.jar app.jar
RUN chown -R app:app /app /data
USER app
# Persist export/import artifacts outside the ephemeral tmpdir; mount /data for durability.
ENV COLDP_EXPORT_DIR=/data/exports \
    COLDP_IMPORT_DIR=/data/imports \
    JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
