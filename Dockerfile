FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn -B -DskipTests package \
    && cp target/bot.jar /app/bot.jar

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

COPY --from=build /app/bot.jar /app/bot.jar

CMD ["java", "-jar", "/app/bot.jar"]
