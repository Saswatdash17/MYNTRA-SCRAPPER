#!/bin/bash

# ============================================
#  Myntra Scraper - One-Click Launcher
# ============================================
#
#  This script:
#  1. Compiles the Java project
#  2. Starts the HTTP server
#  3. Opens the automation page in your browser
#
#  Prerequisites:
#  - Java 11+ installed
#  - Maven installed
#  - Chrome or Firefox installed
#
#  Usage:
#    chmod +x run.sh
#    ./run.sh
# ============================================

set -e

echo ""
echo "==========================================="
echo "  Myntra Scraper - Building & Starting"
echo "==========================================="
echo ""

# Check Java
if ! command -v java &> /dev/null; then
    echo "ERROR: Java is not installed."
    echo "Install it from: https://adoptium.net/"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -1 | awk -F '"' '{print $2}' | cut -d'.' -f1)
echo "Java version: $JAVA_VERSION"

# Check Maven
if ! command -v mvn &> /dev/null; then
    echo "ERROR: Maven is not installed."
    echo "Install it from: https://maven.apache.org/install.html"
    echo "Or on macOS: brew install maven"
    exit 1
fi

echo ""
echo "[1/2] Building project with Maven..."
mvn clean package -q -DskipTests

if [ $? -ne 0 ]; then
    echo "ERROR: Build failed. Check the errors above."
    exit 1
fi

echo "Build successful!"
echo ""

echo "[2/2] Starting Myntra Scraper server..."
echo ""

# Run the fat jar
java -jar target/myntra-0.0.1-SNAPSHOT.jar
