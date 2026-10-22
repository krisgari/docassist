# Multi-stage build shared by both services: docker build --build-arg MODULE=query-api ./backend
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /src
COPY pom.xml .
COPY common/pom.xml common/
COPY worker/pom.xml worker/
COPY query-api/pom.xml query-api/
RUN mvn -q -B dependency:go-offline -pl ${MODULE} -am || true
COPY . .
RUN mvn -q -B -pl ${MODULE} -am package -DskipTests && cp ${MODULE}/target/${MODULE}-*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app && apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/*
USER app
COPY --from=build /app.jar /app/app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
