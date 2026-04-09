FROM eclipse-temurin:25.0.2_10-jdk

WORKDIR /app

COPY target/*.jar app.jar

ENV USER_NAME=Docker_Mohannad_Kotb
ENV ID=Docker_55_18423

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
