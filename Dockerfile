# --- STAGE 1: Build ---
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Dépendances système pour Alpine
RUN apk add --no-cache bash gcompat libc6-compat

# 1. Copie des fichiers de configuration racine
COPY gradlew .
COPY gradle/ gradle/
COPY settings.gradle .
COPY build.gradle .

# ASTUCE PRO : On crée les répertoires vides déclarés dans settings.gradle
# pour que Gradle accepte de télécharger les dépendances sans le code source.
RUN mkdir proto-schema log-ingestor log-sdk-starter

# On télécharge les dépendances (c'est cette étape qui est longue mais mise en cache)
RUN ./gradlew help --no-daemon

# 2. Maintenant on copie le vrai code source (ce qui invalidera le cache seulement ici)
COPY . .

# 3. Build & Publication locale
RUN ./gradlew :proto-schema:publishToMavenLocal --no-daemon && \
    ./gradlew :log-ingestor:bootJar -x test --no-daemon

# --- STAGE 2: Runtime ---
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache gcompat && \
    addgroup -S spring && adduser -S spring -G spring

WORKDIR /app
RUN chown spring:spring /app

# On utilise un wildcard pour copier le bon jar généré
COPY --from=builder --chown=spring:spring /build/log-ingestor/build/libs/*.jar app.jar

USER spring:spring

# Optimisations JVM
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

EXPOSE 50051

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]