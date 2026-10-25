FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS jdk21
FROM eclipse-temurin:25.0.4.1_1-jdk-noble

COPY --from=jdk21 /opt/java/openjdk /opt/java/jdk-21

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl git unzip xvfb xauth \
        fontconfig fonts-dejavu-core fonts-noto-cjk libxi6 libxtst6 libxrender1 \
    && curl --fail --show-error --location --retry 3 \
        https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb \
        --output /tmp/google-chrome.deb \
    && apt-get install -y --no-install-recommends /tmp/google-chrome.deb \
    && rm -rf /var/lib/apt/lists/* /tmp/google-chrome.deb

COPY .cnb/chrome-headless.sh /usr/local/bin/kvue-chrome
RUN chmod 755 /usr/local/bin/kvue-chrome

ENV JAVA_HOME_21_X64=/opt/java/jdk-21 \
    JAVA_HOME_25_X64=/opt/java/openjdk \
    JAVA_HOME=/opt/java/jdk-21 \
    GRADLE_USER_HOME=/root/.gradle \
    CHROME_BIN=/usr/local/bin/kvue-chrome \
    PATH=/opt/java/jdk-21/bin:$PATH
