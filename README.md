# Myntra Scraper

An automated web scraper that finds the **best discounted Van Heusen T-shirts** on Myntra, sorts them by discount percentage, and displays results on a clean web dashboard. Built with Java, Selenium WebDriver, and Cucumber BDD.

![Screenshot 2024-12-25 000614](https://github.com/user-attachments/assets/8e3cadee-cd1b-4726-9234-39257adeffcf)
![Screenshot 2024-12-25 000635](https://github.com/user-attachments/assets/e0c4492d-2023-485e-991d-14cff9271c6e)

---

## Features

- **Automated End-to-End Scraping** — Navigates Myntra, hovers over Men's category, clicks T-Shirts, filters by Van Heusen brand, and scrapes all discounted products
- **Smart Anti-Detection** — Uses realistic user-agents, disables webdriver flags, and applies Chrome DevTools Protocol stealth to bypass bot detection
- **Dual Browser Support** — Tries Chrome first (auto-detected), falls back to Firefox. No manual driver installation needed
- **Headless & Live Modes** — Run silently in the background (headless) or watch the browser perform automation in real-time
- **Web Dashboard** — Built-in HTTP server with a styled dashboard. Click one button and get results
- **Sorted Results** — Products sorted by highest discount % first, displayed in a clean HTML table with product links
- **One-Click Launcher** — `run.sh` builds and starts everything automatically
- **Docker Ready** — Dockerfile included for one-command deployment to any cloud platform
- **Cucumber BDD Tests** — Full test suite with Gherkin feature files

---

## Tech Stack

| Component | Technology |
|---|---|
| Language | Java 11+ |
| Browser Automation | Selenium WebDriver 4.27 |
| Driver Management | WebDriverManager 5.9.2 |
| BDD Testing | Cucumber 7.20 + JUnit 4 |
| Web Server | Java `com.sun.net.httpserver` (built-in) |
| Build Tool | Maven + Shade Plugin |
| Containerization | Docker |

---

## Project Structure

```
Myntra-Scrapper/
├── pom.xml                                      # Maven config with all dependencies
├── run.sh                                       # One-click launcher script
├── Dockerfile                                   # Docker deployment config
├── .gitignore
├── src/
│   ├── main/
│   │   ├── java/myntra/server/
│   │   │   └── MyntraServer.java                # HTTP server + Selenium automation engine
│   │   └── resources/                           # Static resources
│   └── test/
│       ├── java/myntra/
│       │   ├── runner/TestRunner.java            # Cucumber test runner
│       │   ├── stepdefinitions/
│       │   │   └── MyntraScraperSteps.java       # Step definitions with anti-detection
│       │   └── model/
│       │       └── Product.java                  # Product data model
│       └── resources/features/
│           └── MyntraScrapper.feature            # Gherkin feature file
└── target/
    └── myntra-0.0.1-SNAPSHOT.jar                # Executable fat jar (after build)
```

---

## How It Works

```
[Click Link] → [Server Starts] → [Chrome Launches (headless)]
       ↓
[Open Myntra] → [Hover Men] → [Click T-Shirts]
       ↓
[Filter Van Heusen] → [Scroll & Scrape Products]
       ↓
[Extract Prices] → [Calculate Discounts] → [Sort by Discount %]
       ↓
[Display Results in HTML Table]
```

---

## Quick Start (Local)

### Prerequisites
- **Java 11+** — [Install from Adoptium](https://adoptium.net/)
- **Maven** — `brew install maven` (macOS) or [install guide](https://maven.apache.org/install.html)
- **Chrome or Firefox** — [Download Chrome](https://www.google.com/chrome/)

### Option A: One-Click Script
```bash
git clone https://github.com/Saswatdash17/Myntra-Scrapper.git
cd Myntra-Scrapper
chmod +x run.sh
./run.sh
```
Browser opens automatically to `http://localhost:8080`.

### Option B: Manual Build
```bash
git clone https://github.com/Saswatdash17/Myntra-Scrapper.git
cd Myntra-Scrapper

# Build
mvn clean package -DskipTests

# Run
java -jar target/myntra-0.0.1-SNAPSHOT.jar
```
Open `http://localhost:8080` in your browser.

### Option C: Cucumber Tests
```bash
mvn clean test
```
Report generated at `target/cucumber-reports/`.

---

## Using the Web Dashboard

Once the server is running, you'll see two buttons:

| Button | What It Does |
|---|---|
| **Run Automation (Headless)** | Runs silently in the background. Results appear as an HTML table |
| **Watch Live (Visible Browser)** | Opens a real Chrome window so you can watch the entire automation happen step-by-step |

Both buttons execute the same pipeline:
1. Navigate to Myntra homepage
2. Hover over the **Men** category
3. Click **T-Shirts**
4. Search and filter for **Van Heusen** brand
5. Scroll through all products and scrape discounted ones
6. Sort by highest discount percentage
7. Display results with product name, original price, discounted price, discount %, and link to Myntra

---

## Command Line Options

```bash
# Run on a custom port
java -jar target/myntra-0.0.1-SNAPSHOT.jar 9090

# Run with visible browser (non-headless)
java -Dheadless=false -jar target/myntra-0.0.1-SNAPSHOT.jar

# Run Cucumber tests in headless mode (default)
mvn clean test

# Run Cucumber tests with visible browser
mvn clean test -Dheadless=false
```

---

## Deploy with Docker

```bash
# Build the Docker image
docker build -t myntra-scraper .

# Run the container
docker run -p 8080:8080 myntra-scraper
```

Open `http://localhost:8080`.

---

## Deploy to Cloud (Free Hosting)

### Render.com (Easiest — 5 minutes)
1. Push code to GitHub (already done)
2. Go to [render.com](https://render.com) → New → **Web Service**
3. Connect your GitHub repo
4. Set **Runtime** to `Docker`, **Port** to `8080`
5. Click **Create** → Wait for build → Get your public URL

### Railway.app
1. Go to [railway.app](https://railway.app) → New Project → Deploy from GitHub
2. Select your repo → Auto-detects Dockerfile
3. Settings → Networking → Generate Domain
4. Get your public URL

### Oracle Cloud Free Tier (Best Performance — Free Forever)
1. Create free account at [cloud.oracle.com](https://cloud.oracle.com)
2. Create an **A1 Flex** ARM instance (4 cores, 24GB RAM — free forever)
3. SSH in, install Java + Chrome, upload the jar
4. Open port 8080 in security rules
5. Your link: `http://YOUR_PUBLIC_IP:8080`

---

## Configuration

| Property | Default | Description |
|---|---|---|
| `headless` | `true` | Run browser in headless mode. Set `false` to see the browser |
| Port | `8080` | Pass a different port as CLI argument |
| Target Brand | Van Heusen | Edit `MyntraScraperSteps.java` to change brand filter |
| `PROXY_HOST` | *(none)* | Residential proxy host/IP — **required for Render deployment** |
| `PROXY_PORT` | `80` | Proxy port |
| `PROXY_USER` | *(none)* | Proxy auth username (if required) |
| `PROXY_PASS` | *(none)* | Proxy auth password (if required) |

### Why a Proxy?

Myntra blocks requests from datacenter/cloud IPs (AWS, GCP, etc.). Render, Railway, and similar platforms run on these IPs. A **residential proxy** routes traffic through real ISP connections, bypassing the block.

### Setting Up on Render

1. Sign up for a residential proxy service (see recommendations below)
2. Go to your Render service → **Environment** tab
3. Add these env vars:
   ```
   PROXY_HOST=proxy.example.com
   PROXY_PORT=7777
   PROXY_USER=your-username
   PROXY_PASS=your-password
   ```
4. Save — Render auto-deploys with the new config

### Proxy Provider Recommendations

| Provider | Free Tier | Residential IPs | Pricing |
|---|---|---|---|
| [Bright Data](https://brightdata.com) | 7-day trial | Yes (72M+ IPs) | Pay per GB |
| [Smartproxy](https://smartproxy.com) | 3-day trial | Yes (55M+ IPs) | From $7/GB |
| [Oxylabs](https://oxylabs.io) | 7-day trial | Yes (100M+ IPs) | From $10/GB |
| [WebShare](https://webshare.io) | 10 free proxies | Yes (30M+ IPs) | From $5/mo |

> **Tip:** For testing, WebShare's free tier gives you 10 residential proxies with no credit card. For production, Bright Data or Smartproxy offer the best India residential IP coverage.

---

## Sample Output

```
============================================================
  Myntra Scraper - Results
  Van Heusen T-Shirts Sorted by Highest Discount
============================================================

| # | Product              | MRP    | Sale   | Discount | Link          |
|---|----------------------|--------|--------|----------|---------------|
| 1 | VAN HEUSEN Polo T-Shirt | ₹1,499 | ₹449  | 70% OFF  | View →        |
| 2 | VAN HEUSEN Round Neck  | ₹999   | ₹349  | 65% OFF  | View →        |
| 3 | VAN HEUSEN V-Neck T-Shirt | ₹1,299 | ₹499 | 62% OFF | View →       |
```

---

## Troubleshooting

| Issue | Fix |
|---|---|
| `No browser available` | Install Chrome or Firefox |
| `ChromeDriver version mismatch` | WebDriverManager handles this automatically. If it fails, delete `~/.cache/selenium/` and retry |
| Page loads but products not found | Myntra may have updated their HTML. Try "Watch Live" mode to debug |
| `Port already in use` | Run with a different port: `java -jar target/myntra-0.0.1-SNAPSHOT.jar 9090` |
| Scraping fails on Render/cloud | Myntra blocks datacenter IPs. Set `PROXY_HOST`/`PROXY_PORT` env vars with a residential proxy |
| Build fails | Ensure Java 11+ and Maven 3.6+ are installed |

---

## References

- [Web Scraping with Java — ZenRows](https://www.zenrows.com/blog/web-scraping-java)
- [Selenium Java Guide — LambdaTest](https://www.lambdatest.com/blog/web-scraping-with-selenium-java/)
- [Selenium Cucumber Playlist](https://youtube.com/playlist?list=PLU6MMFAD297l__y1n3WmTd0VF_zwaSpFP)

---

## License

This project is for educational purposes only. Respect Myntra's Terms of Service.

---

**Made by [Saswat Dash](https://github.com/Saswatdash17)**
