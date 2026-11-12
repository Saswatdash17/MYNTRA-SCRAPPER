FROM debian:bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       wget gnupg2 ca-certificates fonts-liberation \
       libnss3 libatk-bridge2.0-0 libdrm2 libxkbcommon0 \
       libgbm1 libasound2 libxshmfence1 libgtk-3-0 \
       libx11-xcb1 libxcb-dri3-0 libxcomposite1 libxdamage1 \
       libxrandr2 libxss1 libxtst6 \
    && wget -q -O - https://dl.google.com/linux/linux_signing_key.pub | gpg --dearmor -o /usr/share/keyrings/google-chrome.gpg \
    && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/google-chrome.gpg] http://dl.google.com/linux/chrome/deb/ stable main" \
       > /etc/apt/sources.list.d/google-chrome.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends google-chrome-stable \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

RUN google-chrome --version

RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jdk maven \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -q -DskipTests

EXPOSE 8080

CMD ["java", "-Djava.awt.headless=true", "-jar", "target/myntra-0.0.1-SNAPSHOT.jar", "8080"]
