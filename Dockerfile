FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
# 先只拷 pom 并拉取依赖，利用 Docker 层缓存：改动源码不再触发全量重下依赖
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 10001 -m echomind
WORKDIR /app
COPY --from=build /workspace/target/echomind-java-0.1.0.jar /app/echomind-java.jar
COPY skills ./skills
RUN mkdir -p /app/data && chown -R echomind:echomind /app
# 非 root 运行：任何应用层 RCE 都不再直接拿到容器 root
USER echomind
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -sf http://localhost:8080/health || exit 1
# exec 形式：Java 进程为 PID 1，docker stop 的 SIGTERM 能直达进程实现优雅停机；
# 额外 JVM 参数通过 JDK_JAVA_OPTIONS 环境变量传入
ENTRYPOINT ["java", "-jar", "/app/echomind-java.jar"]
