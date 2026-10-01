# syntax=docker/dockerfile:1
FROM node:24.21.0-bookworm-slim AS frontend
WORKDIR /build/front-end
RUN npm install --global pnpm@12.4.1
COPY front-end/package.json front-end/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY front-end/ ./
RUN pnpm build

FROM eclipse-temurin:21-jdk-jammy AS backend
WORKDIR /build/back-end
COPY back-end/ ./
COPY --from=frontend /build/front-end/dist/ /build/front-end/dist/
# Node stage의 dist를 사용하고 Gradle stage에서는 Front를 다시 빌드하지 않습니다.
RUN bash gradlew --no-daemon :app:bootJar -PfrontendPrebuilt=true

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
RUN mkdir /app/config && chown 10001:10001 /app/config
COPY --from=backend --chown=10001:10001 /build/back-end/app/build/libs/jn-waiting-room.jar ./jn-waiting-room.jar
ENV SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/app/config/
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/jn-waiting-room.jar"]
