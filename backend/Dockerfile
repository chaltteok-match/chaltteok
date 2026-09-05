# 평가 서버용 이미지. 빌드와 실행을 나눠 실행 이미지에 Gradle·소스를 남기지 않는다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# 의존성만 먼저 받아 둔다. 소스만 바뀌면 이 층을 다시 받지 않는다.
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app

# root로 돌리지 않는다. 포트 80은 컨테이너 밖에서 도커가 붙인다.
RUN useradd --system --create-home --uid 10001 chaltteok
USER chaltteok

COPY --from=build --chown=chaltteok:chaltteok /build/build/libs/*-SNAPSHOT.jar app.jar

# 2GB 장비 기준. 컨테이너에 할당된 메모리의 60%까지 힙으로 쓴다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
