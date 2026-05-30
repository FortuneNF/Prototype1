FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY files/SkillBridgeServer.java .
RUN javac SkillBridgeServer.java

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/*.class .
COPY files/index.html .
ENV PORT=8080
EXPOSE 8080
CMD ["java", "SkillBridgeServer"]
