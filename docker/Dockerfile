# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage: Java 25 + the committed Gradle wrapper. Tests and quality gates
# run on the host/CI; the image only needs the executable jar.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

# Wrapper and build scripts first so the Gradle distribution layer is cached
# until the wrapper or the build definition changes.
COPY source/gradlew source/settings.gradle source/build.gradle ./
COPY source/gradle/ ./gradle/
RUN ./gradlew --no-daemon --console=plain --version

COPY source/src/ ./src/
RUN ./gradlew --no-daemon --console=plain bootJar \
    && cp "$(find build/libs -maxdepth 1 -name 'fribourg-events-digest-*.jar' \
             ! -name '*-plain.jar' -print -quit)" /workspace/app.jar

# ---------------------------------------------------------------------------
# JRE stage: copy the Temurin 25 runtime into a Debian base, because Debian
# still ships a real Chromium + chromedriver while Ubuntu (the Temurin base)
# only ships a snap stub. The JRE binaries are the official eclipse-temurin
# ones; only the userland is Debian.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS jre

# ---------------------------------------------------------------------------
# Runtime stage: non-root, Chromium available for Selenium, SQLite on /data.
# ---------------------------------------------------------------------------
FROM debian:bookworm-slim AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        chromium \
        chromium-driver \
        curl \
        gosu \
        passwd \
        tini \
    && rm -rf /var/lib/apt/lists/*

# Fail the build if Debian's packages are not the real browser/driver pair
# (Ubuntu's chromium is a snap stub; Debian's is a working deb).
RUN chromium --version && chromedriver --version

COPY --from=jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Chromium needs a writable home and a large /dev/shm; HOME stays disposable
# and compose raises shm_size (see docker-compose.yml).
ENV HOME=/tmp
ENV TZ=Europe/Zurich

WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
COPY docker/entrypoint.sh /usr/local/bin/docker-entrypoint.sh
COPY docker/healthcheck.sh /app/healthcheck.sh
RUN chmod 0755 /usr/local/bin/docker-entrypoint.sh /app/healthcheck.sh \
    && mkdir -p /data

# Defaults; override PUID/PGID to match the Unraid share owner. The entrypoint
# creates that user and drops root before starting Java.
ENV PUID=1000 \
    PGID=1000 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

EXPOSE 8080
VOLUME ["/data"]

HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=5 \
    CMD ["/app/healthcheck.sh"]

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/docker-entrypoint.sh"]
