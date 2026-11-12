package myntra.server;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public class MyntraServer {

    private static final int PORT = 8080;
    private static final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private static final LinkedList<String> errorLog = new LinkedList<>();
    private static final ExecutorService executor = Executors.newFixedThreadPool(2);
    private static final Object logLock = new Object();

    private static void logError(String msg) {
        synchronized (logLock) {
            errorLog.addLast("[" + java.time.LocalTime.now().toString().substring(0, 8) + "] " + msg);
            if (errorLog.size() > 20) errorLog.removeFirst();
        }
    }

    public static void main(String[] args) throws IOException {
        int port = PORT;
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new RootHandler());
        server.createContext("/api/scrape", new ScrapeStartHandler());
        server.createContext("/api/status/", new StatusHandler());
        server.createContext("/api/logs", new LogsHandler());
        server.createContext("/api/debug", new DebugHandler());
        server.setExecutor(null);
        server.start();

        String url = "http://localhost:" + port;
        System.out.println("===========================================");
        System.out.println("  Myntra Scraper Server Started!");
        System.out.println("  " + url);
        System.out.println("===========================================");

        try {
            if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                Runtime.getRuntime().exec(new String[]{"open", url});
            } else if (System.getProperty("os.name").toLowerCase().contains("win")) {
                Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", url});
            } else {
                Runtime.getRuntime().exec(new String[]{"xdg-open", url});
            }
        } catch (Exception e) {
            System.out.println("Open manually: " + url);
        }
    }

    // =================== JOB MODEL ===================

    static class Job {
        enum Status { RUNNING, DONE, ERROR }
        volatile Status status = Status.RUNNING;
        volatile String error;
        volatile List<Map<String, String>> products;
        volatile String resultHtml;

        synchronized void complete(List<Map<String, String>> products) {
            this.products = products;
            this.resultHtml = buildResultPage(products);
            this.status = Status.DONE;
        }

        synchronized void fail(Exception e) {
            this.error = e.getMessage() != null ? e.getMessage() : e.toString();
            this.resultHtml = buildErrorPage(e);
            this.status = Status.ERROR;
        }
    }

    // =================== HTTP HANDLERS ===================

    static class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"/".equals(ex.getRequestURI().getPath())) {
                sendText(ex, 404, "Not Found");
                return;
            }
            sendHtml(ex, 200, getDashboardHTML());
        }
    }

    static class ScrapeStartHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String query = ex.getRequestURI().getQuery();
            boolean liveMode = false;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if ("live".equals(kv[0]) && "true".equals(kv.length > 1 ? kv[1] : "")) {
                        liveMode = true;
                    }
                }
            }

            String jobId = UUID.randomUUID().toString().substring(0, 8);
            Job job = new Job();
            jobs.put(jobId, job);
            final boolean runLive = liveMode;

            executor.submit(() -> {
                try {
                    System.out.println("[Job " + jobId + "] Starting automation (live=" + runLive + ")...");
                    List<Map<String, String>> products = runScraping(runLive);
                    products.sort((a, b) -> Integer.compare(
                        Integer.parseInt(b.get("discount")),
                        Integer.parseInt(a.get("discount"))));
                    job.complete(products);
                    System.out.println("[Job " + jobId + "] Done. Found " + products.size() + " products.");
                } catch (Exception e) {
                    System.err.println("[Job " + jobId + "] Failed: " + e.getMessage());
                    e.printStackTrace();
                    job.fail(e);
                }
            });

            sendHtml(ex, 200, getPollingHTML(jobId));
        }
    }

    static class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String jobId = path.substring("/api/status/".length());

            Job job = jobs.get(jobId);
            if (job == null) {
                sendText(ex, 404, "{\"error\":\"Job not found\"}");
                return;
            }

            String status = job.status.name().toLowerCase();
            String json;
            if (job.status == Job.Status.DONE) {
                json = "{\"status\":\"" + status + "\",\"html\":" + jsonEscape(job.resultHtml) + "}";
                jobs.remove(jobId);
            } else if (job.status == Job.Status.ERROR) {
                json = "{\"status\":\"" + status + "\",\"error\":" + jsonEscape(job.error) + "}";
                logError("Job " + jobId + ": " + job.error);
                jobs.remove(jobId);
            } else {
                json = "{\"status\":\"running\"}";
            }

            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
    }

    static class LogsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            StringBuilder sb = new StringBuilder();
            synchronized (logLock) {
                for (String line : errorLog) sb.append(line).append("\n");
            }
            if (sb.length() == 0) sb.append("No errors logged yet.\n");
            sendText(ex, 200, sb.toString());
        }
    }

    static class DebugHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            File dir = new File("/tmp/myntra-debug");
            if (!dir.exists()) { sendText(ex, 200, "No debug files yet.\n"); return; }
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) { sendText(ex, 200, "No debug files yet.\n"); return; }
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            StringBuilder sb = new StringBuilder();
            for (File f : files) {
                sb.append("=== ").append(f.getName()).append(" ===\n");
                try {
                    sb.append(Files.readString(f.toPath()));
                } catch (Exception e) {
                    sb.append("Could not read: ").append(e.getMessage());
                }
                sb.append("\n\n");
            }
            sendText(ex, 200, sb.toString());
        }
    }

    // =================== SELENIUM AUTOMATION ===================

    private static List<Map<String, String>> runScraping(boolean liveMode) {
        WebDriver driver = null;
        try {
            driver = createDriver(!liveMode);
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            JavascriptExecutor js = (JavascriptExecutor) driver;

            System.out.println("  [1/5] Navigating to Myntra Men T-Shirts...");
            driver.get("https://www.myntra.com/men-tshirts");
            wait.until(wd -> ((JavascriptExecutor) wd)
                .executeScript("return document.readyState").equals("complete"));
            Thread.sleep(8000);

            saveDebugInfo(driver, "after-load");

            System.out.println("  [2/5] Checking page content...");
            String pageSource = driver.getPageSource();
            System.out.println("  Page size: " + pageSource.length() + " chars");
            System.out.println("  URL: " + driver.getCurrentUrl());
            System.out.println("  Title: " + driver.getTitle());

            boolean hasProducts = pageSource.contains("product-base") || pageSource.contains("product-brand");
            System.out.println("  Products visible: " + hasProducts);

            if (!hasProducts) {
                System.out.println("  No products found on direct URL, trying homepage navigation...");
                driver.get("https://www.myntra.com/");
                wait.until(wd -> ((JavascriptExecutor) wd)
                    .executeScript("return document.readyState").equals("complete"));
                Thread.sleep(5000);
                saveDebugInfo(driver, "homepage");

                try {
                    WebElement menCategory = wait.until(
                        ExpectedConditions.elementToBeClickable(By.xpath("//a[@data-group='men']")));
                    new Actions(driver).moveToElement(menCategory).pause(Duration.ofSeconds(2)).perform();
                    System.out.println("  Hovered Men category");
                } catch (Exception e) {
                    System.out.println("  Could not find Men category, trying direct navigation...");
                    driver.get("https://www.myntra.com/men-tshirts");
                    wait.until(wd -> ((JavascriptExecutor) wd)
                        .executeScript("return document.readyState").equals("complete"));
                    Thread.sleep(8000);
                    saveDebugInfo(driver, "after-retry-nav");
                }

                try {
                    wait.until(ExpectedConditions.elementToBeClickable(
                        By.xpath("//a[contains(@href, 'men-tshirts')]"))).click();
                    wait.until(wd -> ((JavascriptExecutor) wd)
                        .executeScript("return document.readyState").equals("complete"));
                    Thread.sleep(3000);
                } catch (Exception e) {
                    System.out.println("  Could not click T-Shirts link, continuing with current page...");
                }
            }

            System.out.println("  [3/5] On page: " + driver.getCurrentUrl());
            saveDebugInfo(driver, "before-filter");

            System.out.println("  [4/5] Filtering Van Heusen...");
            boolean filtered = false;
            try {
                clickSearchIcon(wait, js);
                WebElement searchInput = wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.xpath("//input[@type='text'][@placeholder='Search Brand' or contains(@class, 'filter-search-inputBox')]")));
                searchInput.sendKeys("Van Heusen");
                Thread.sleep(1500);
                clickVanHeusenCheckbox(wait, js);
                Thread.sleep(3000);
                filtered = true;
                System.out.println("  Van Heusen filter applied");
            } catch (Exception e) {
                System.out.println("  Brand filter not available, scraping all visible products...");
                System.out.println("  Filter error: " + e.getMessage());
            }

            saveDebugInfo(driver, "after-filter-" + filtered);

            System.out.println("  [5/5] Scraping products...");
            return scrapeProducts(driver, js, wait);

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (driver != null) {
                try { driver.quit(); } catch (Exception ignored) {}
            }
        }
    }

    private static void saveDebugInfo(WebDriver driver, String label) {
        try {
            String dir = "/tmp/myntra-debug";
            new File(dir).mkdirs();
            String ts = String.valueOf(System.currentTimeMillis());
            Files.writeString(Path.of(dir, label + "-" + ts + ".txt"),
                "URL: " + driver.getCurrentUrl() + "\n" +
                "Title: " + driver.getTitle() + "\n" +
                "Time: " + java.time.Instant.now() + "\n" +
                "---PAGE SOURCE (first 5000 chars)---\n" +
                driver.getPageSource().substring(0, Math.min(driver.getPageSource().length(), 5000)));
            System.out.println("  Debug saved: " + dir + "/" + label + "-" + ts + ".txt");
        } catch (Exception e) {
            System.out.println("  Could not save debug info: " + e.getMessage());
        }
    }

    private static String findChromeBinary() {
        String[] paths = {
            "/usr/bin/google-chrome-stable",
            "/usr/bin/google-chrome",
            "/usr/bin/chromium-browser",
            "/usr/bin/chromium",
            "/opt/google/chrome/chrome",
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
        };
        for (String p : paths) {
            if (new File(p).exists()) {
                System.out.println("  Found Chrome at: " + p);
                return p;
            }
        }
        return null;
    }

    private static WebDriver createDriver(boolean headless) {
        String chromeBinary = findChromeBinary();
        if (chromeBinary == null) {
            throw new RuntimeException("Chrome is required. Install google-chrome-stable.");
        }

        try {
            WebDriverManager.chromedriver().setup();
            ChromeOptions options = new ChromeOptions();
            options.setBinary(chromeBinary);
            options.setExperimentalOption("excludeSwitches", Arrays.asList("enable-automation"));
            options.setExperimentalOption("useAutomationExtension", false);

            String proxyHost = System.getenv("PROXY_HOST");
            String proxyPort = System.getenv("PROXY_PORT");
            String proxyUser = System.getenv("PROXY_USER");
            String proxyPass = System.getenv("PROXY_PASS");

            List<String> args = new ArrayList<>(Arrays.asList(
                "--headless=new",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--disable-software-rasterizer",
                "--disable-blink-features=AutomationControlled",
                "--disable-infobars",
                "--disable-extensions",
                "--disable-popup-blocking",
                "--window-size=1920,1080",
                "--start-maximized",
                "--lang=en-US",
                "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            ));

            if (proxyHost != null && !proxyHost.isEmpty()) {
                String port = (proxyPort != null && !proxyPort.isEmpty()) ? proxyPort : "80";
                String proxyAddr = proxyHost + ":" + port;
                args.add("--proxy-server=" + proxyAddr);
                System.out.println("  Proxy: " + proxyAddr);
                if (proxyUser != null && !proxyUser.isEmpty()) {
                    System.out.println("  Proxy auth: " + proxyUser);
                }
            } else {
                System.out.println("  No proxy configured (set PROXY_HOST/PROXY_PORT env vars)");
            }

            options.addArguments(args);

            ChromeDriver chromeDriver = new ChromeDriver(options);

            if (proxyUser != null && !proxyUser.isEmpty() && proxyPass != null) {
                try {
                    String auth = java.util.Base64.getEncoder().encodeToString(
                        (proxyUser + ":" + proxyPass).getBytes(StandardCharsets.UTF_8));
                    chromeDriver.executeCdpCommand("Network.setCacheDisabled", Map.of("cacheDisabled", true));
                    chromeDriver.executeCdpCommand("Network.enable", Map.of());
                    chromeDriver.executeCdpCommand("Fetch.enable", Map.of(
                        "patterns", List.of(Map.of("urlPattern", "*", "requestStage", "Request"))
                    ));
                    chromeDriver.executeCdpCommand("Network.setExtraHTTPHeaders", Map.of(
                        "headers", Map.of("Proxy-Authorization", "Basic " + auth)
                    ));
                    System.out.println("  Proxy authentication enabled");
                } catch (Exception e) {
                    System.out.println("  Proxy auth setup failed: " + e.getMessage());
                    System.out.println("  Trying alternative auth method...");
                    try {
                        String auth = java.util.Base64.getEncoder().encodeToString(
                            (proxyUser + ":" + proxyPass).getBytes(StandardCharsets.UTF_8));
                        chromeDriver.executeCdpCommand("Network.setExtraHTTPHeaders", Map.of(
                            "headers", Map.of("Proxy-Authorization", "Basic " + auth)
                        ));
                    } catch (Exception e2) {
                        System.out.println("  All proxy auth methods failed: " + e2.getMessage());
                    }
                }
            }

            try {
                chromeDriver.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument",
                    Map.of("source",
                        "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});" +
                        "Object.defineProperty(navigator, 'plugins', {get: () => [1, 2, 3, 4, 5]});" +
                        "Object.defineProperty(navigator, 'languages', {get: () => ['en-US', 'en']});" +
                        "window.chrome = {runtime: {}};"));
            } catch (Exception ignored) {}

            System.out.println("  Browser: Chrome (headless)");
            return chromeDriver;

        } catch (Exception e) {
            throw new RuntimeException("Chrome start failed: " + e.getMessage(), e);
        }
    }

    private static void clickSearchIcon(WebDriverWait wait, JavascriptExecutor js) {
        String[] selectors = {
            "//span[text()='Brand']/parent::div//span[contains(@class, 'myntraweb-sprite') or contains(@class, 'filter-search-filterSearchIcon')]",
            "//div[contains(@class, 'filter-search-filterHeader')]//span[contains(@class, 'myntraweb-sprite')]",
            "//span[text()='Brand']/following-sibling::span[contains(@class, 'filter-search-filterSearchIcon')]"
        };
        for (String sel : selectors) {
            try {
                WebElement icon = wait.until(ExpectedConditions.elementToBeClickable(By.xpath(sel)));
                js.executeScript("arguments[0].scrollIntoView({behavior:'smooth',block:'center'});", icon);
                Thread.sleep(1000);
                try { icon.click(); } catch (Exception e) { js.executeScript("arguments[0].click();", icon); }
                return;
            } catch (Exception ignored) {}
        }
        throw new RuntimeException("Could not click brand search icon");
    }

    private static void clickVanHeusenCheckbox(WebDriverWait wait, JavascriptExecutor js) {
        String[] selectors = {
            "//label[contains(., 'Van Heusen')]//div[contains(@class, 'common-checkboxIndicator')]",
            "//div[contains(@class, 'common-checkboxIndicator')]/following-sibling::div[text()='Van Heusen']",
            "//div[text()='Van Heusen']/preceding-sibling::div[contains(@class, 'common-checkboxIndicator')]"
        };
        for (String sel : selectors) {
            try {
                WebElement checkbox = wait.until(ExpectedConditions.elementToBeClickable(By.xpath(sel)));
                js.executeScript("arguments[0].scrollIntoView({behavior:'smooth',block:'center'});", checkbox);
                Thread.sleep(1000);
                try { checkbox.click(); } catch (Exception e) { js.executeScript("arguments[0].click();", checkbox); }
                return;
            } catch (Exception ignored) {}
        }
        throw new RuntimeException("Could not click Van Heusen checkbox");
    }

    private static List<Map<String, String>> scrapeProducts(WebDriver driver, JavascriptExecutor js, WebDriverWait wait) {
        List<Map<String, String>> products = new ArrayList<>();
        Set<String> processedLinks = new HashSet<>();
        long lastHeight = 0;

        for (int scrollAttempt = 0; scrollAttempt < 5; scrollAttempt++) {
            List<WebElement> cards = driver.findElements(By.xpath("//li[contains(@class, 'product-base')]"));
            for (WebElement card : cards) {
                try {
                    js.executeScript("arguments[0].scrollIntoView({behavior:'smooth',block:'center'});", card);
                    Thread.sleep(400);

                    String name = card.findElement(By.xpath(".//h3[contains(@class, 'product-brand')]")).getText();
                    String link = card.findElement(By.xpath(".//a")).getAttribute("href");
                    if (!name.toLowerCase().contains("van heusen") || processedLinks.contains(link)) continue;
                    processedLinks.add(link);

                    Double[] prices = extractPrices(card);
                    if (prices != null) {
                        double original = prices[0], discounted = prices[1];
                        int discount = (int) Math.round(((original - discounted) / original) * 100);
                        if (discount > 0) {
                            Map<String, String> p = new LinkedHashMap<>();
                            p.put("name", name);
                            p.put("link", link);
                            p.put("originalPrice", String.format("%.0f", original));
                            p.put("discountedPrice", String.format("%.0f", discounted));
                            p.put("discount", String.valueOf(discount));
                            products.add(p);
                        }
                    }
                } catch (Exception ignored) {}
            }
            js.executeScript("window.scrollTo(0, document.body.scrollHeight)");
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            long newHeight = (long) js.executeScript("return document.body.scrollHeight");
            if (newHeight == lastHeight) break;
            lastHeight = newHeight;
        }
        return products;
    }

    private static Double[] extractPrices(WebElement card) {
        try {
            String dp = extractPrice(card, new String[]{
                ".//div[contains(@class, 'discounted-price')]",
                ".//span[contains(@class, 'discounted-price')]",
                ".//div[contains(@class, 'product-discountedPrice')]",
                ".//span[contains(@class, 'product-discountedPrice')]"
            });
            String op = extractPrice(card, new String[]{
                ".//div[contains(@class, 'strike')]",
                ".//span[contains(@class, 'strike')]",
                ".//div[contains(@class, 'product-strike')]",
                ".//span[contains(@class, 'product-strike')]"
            });
            if (dp != null && op != null) {
                return new Double[]{
                    Double.parseDouble(op.replaceAll("[^0-9]", "")),
                    Double.parseDouble(dp.replaceAll("[^0-9]", ""))
                };
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String extractPrice(WebElement card, String[] selectors) {
        for (String sel : selectors) {
            try {
                String text = card.findElement(By.xpath(sel)).getText().trim();
                if (!text.isEmpty()) return text;
            } catch (Exception ignored) {}
        }
        return null;
    }

    // =================== HTML TEMPLATES ===================

    private static String getDashboardHTML() {
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
            "<title>Myntra Scraper</title>" +
            "<style>" +
            "*{margin:0;padding:0;box-sizing:border-box}" +
            "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:linear-gradient(135deg,#1a1a2e 0%,#16213e 50%,#0f3460 100%);color:#e0e0e0;min-height:100vh;display:flex;flex-direction:column;align-items:center;padding:40px 20px}" +
            ".c{max-width:900px;width:100%}" +
            "h1{font-size:2.5em;background:linear-gradient(90deg,#ff3f6c,#ff7eb3);-webkit-background-clip:text;-webkit-text-fill-color:transparent;margin-bottom:10px;text-align:center}" +
            ".sub{text-align:center;color:#888;margin-bottom:40px;font-size:1.1em}" +
            ".card{background:rgba(255,255,255,.05);border-radius:16px;padding:30px;margin-bottom:20px;border:1px solid rgba(255,255,255,.1)}" +
            ".steps{display:flex;gap:10px;margin-bottom:30px;flex-wrap:wrap;justify-content:center}" +
            ".step{background:rgba(255,255,255,.05);padding:12px 20px;border-radius:8px;font-size:.9em;border:1px solid rgba(255,255,255,.1);text-align:center;flex:1;min-width:140px}" +
            ".step .n{display:inline-block;background:#ff3f6c;color:#fff;width:24px;height:24px;border-radius:50%;line-height:24px;text-align:center;font-weight:700;font-size:.8em;margin-right:6px}" +
            ".bg{display:flex;gap:15px;justify-content:center;flex-wrap:wrap}" +
            ".btn{padding:16px 40px;font-size:1.1em;font-weight:600;border:none;border-radius:12px;cursor:pointer;transition:all .3s;text-decoration:none;display:inline-block}" +
            ".bp{background:linear-gradient(135deg,#ff3f6c,#ff7eb3);color:#fff;box-shadow:0 4px 20px rgba(255,63,108,.4)}" +
            ".bp:hover{transform:translateY(-2px);box-shadow:0 6px 25px rgba(255,63,108,.6)}" +
            ".bl{background:linear-gradient(135deg,#00b894,#00cec9);color:#fff;box-shadow:0 4px 20px rgba(0,184,148,.4)}" +
            ".bl:hover{transform:translateY(-2px);box-shadow:0 6px 25px rgba(0,184,148,.6)}" +
            ".info{text-align:center;color:#888;margin-top:15px;font-size:.9em}" +
            ".note{text-align:center;color:#555;margin-top:10px;font-size:.8em}" +
            "</style></head><body><div class='c'>" +
            "<h1>Myntra Scraper</h1>" +
            "<p class='sub'>Automated Van Heusen T-Shirt Discount Finder</p>" +
            "<div class='card'>" +
            "<div class='steps'>" +
            "<div class='step'><span class='n'>1</span>Navigate to Myntra</div>" +
            "<div class='step'><span class='n'>2</span>Hover Men Category</div>" +
            "<div class='step'><span class='n'>3</span>Click T-Shirts</div>" +
            "<div class='step'><span class='n'>4</span>Filter Van Heusen</div>" +
            "<div class='step'><span class='n'>5</span>Scrape &amp; Sort</div>" +
            "</div>" +
            "<div class='bg'>" +
            "<a href='/api/scrape?live=false' class='btn bp' onclick='showLoad(this)'>Run Automation (Headless)</a>" +
            "<a href='/api/scrape?live=true' class='btn bl' onclick='showLoad(this)'>Watch Live (Visible Browser)</a>" +
            "</div>" +
            "<p class='info'>Headless runs in background &bull; Watch Live opens a visible browser</p>" +
            "<p class='note'>Note: Automation takes 60-120 seconds. You will see a progress page while it runs.</p>" +
            "</div></div>" +
            "<script>function showLoad(b){b.textContent='Starting...';b.style.opacity='.7';b.style.pointerEvents='none';" +
            "document.getElementById(b.id==='runBtn'?'liveBtn':'runBtn').style.opacity='.3';}</script>" +
            "</body></html>";
    }

    private static String getPollingHTML(String jobId) {
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
            "<title>Myntra Scraper - Running</title>" +
            "<style>" +
            "*{margin:0;padding:0;box-sizing:border-box}" +
            "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:linear-gradient(135deg,#1a1a2e 0%,#16213e 50%,#0f3460 100%);color:#e0e0e0;min-height:100vh;display:flex;justify-content:center;align-items:center}" +
            ".loader{text-align:center;max-width:500px}" +
            "h1{font-size:2em;background:linear-gradient(90deg,#ff3f6c,#ff7eb3);-webkit-background-clip:text;-webkit-text-fill-color:transparent;margin-bottom:20px}" +
            ".spinner{width:60px;height:60px;border:4px solid rgba(255,255,255,.1);border-top:4px solid #ff3f6c;border-radius:50%;animation:spin 1s linear infinite;margin:0 auto 25px}" +
            "@keyframes spin{to{transform:rotate(360deg)}}" +
            ".status{color:#888;font-size:1.1em;margin-bottom:10px}" +
            ".steps-list{text-align:left;margin:20px 0;padding:20px;background:rgba(255,255,255,.05);border-radius:12px;border:1px solid rgba(255,255,255,.1)}" +
            ".steps-list div{padding:6px 0;color:#888;font-size:.9em}" +
            ".steps-list div.done{color:#00b894}" +
            ".steps-list div.active{color:#ff3f6c;font-weight:600}" +
            ".timer{color:#555;font-size:.85em;margin-top:15px}" +
            ".back{margin-top:20px}" +
            ".back a{color:#ff3f6c;text-decoration:none;padding:10px 20px;border:1px solid #ff3f6c;border-radius:8px;font-size:.9em}" +
            ".back a:hover{background:#ff3f6c;color:#fff}" +
            "</style></head><body><div class='loader'>" +
            "<h1>Automation Running</h1>" +
            "<div class='spinner'></div>" +
            "<div class='status' id='status'>Starting Chrome browser...</div>" +
            "<div class='steps-list'>" +
            "<div id='s1'>&#9675; Opening Myntra homepage</div>" +
            "<div id='s2'>&#9675; Hovering over Men category</div>" +
            "<div id='s3'>&#9675; Clicking T-Shirts section</div>" +
            "<div id='s4'>&#9675; Filtering Van Heusen brand</div>" +
            "<div id='s5'>&#9675; Scraping &amp; sorting products</div>" +
            "</div>" +
            "<div class='timer' id='timer'>Elapsed: 0s</div>" +
            "<div class='back'><a href='/'>Cancel &amp; Go Back</a></div>" +
            "</div>" +
            "<script>" +
            "var elapsed=0;var jobId='" + jobId + "';" +
            "var timer=setInterval(function(){elapsed++;document.getElementById('timer').textContent='Elapsed: '+elapsed+'s';},1000);" +
            "var steps=['s1','s2','s3','s4','s5'];var si=0;" +
            "var stepper=setInterval(function(){if(si<steps.length){" +
            "if(si>0){document.getElementById(steps[si-1]).className='done';document.getElementById(steps[si-1]).innerHTML='&#10003; '+document.getElementById(steps[si-1]).textContent.substring(2);}" +
            "document.getElementById(steps[si]).className='active';document.getElementById(steps[si]).innerHTML='&#9679; '+document.getElementById(steps[si]).textContent.substring(2);si++;}},15000);" +
            "function poll(){" +
            "fetch('/api/status/"+jobId+"').then(function(r){return r.json();}).then(function(d){" +
            "if(d.status==='done'||d.status==='error'){" +
            "clearInterval(timer);clearInterval(stepper);" +
            "document.open();document.write(d.html);document.close();" +
            "}else{setTimeout(poll,3000);}" +
            "}).catch(function(){setTimeout(poll,5000);});}" +
            "setTimeout(poll,3000);" +
            "</script>" +
            "</body></html>";
    }

    private static String buildResultPage(List<Map<String, String>> products) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>");
        sb.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>");
        sb.append("<title>Myntra Scraper - Results</title><style>");
        sb.append("*{margin:0;padding:0;box-sizing:border-box}");
        sb.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:linear-gradient(135deg,#1a1a2e 0%,#16213e 50%,#0f3460 100%);color:#e0e0e0;min-height:100vh;padding:40px 20px}");
        sb.append(".c{max-width:1000px;margin:0 auto}");
        sb.append("h1{font-size:2em;background:linear-gradient(90deg,#ff3f6c,#ff7eb3);-webkit-background-clip:text;-webkit-text-fill-color:transparent;margin-bottom:5px;text-align:center}");
        sb.append(".sub{text-align:center;color:#888;margin-bottom:30px}");
        sb.append(".back{text-align:center;margin-bottom:30px}");
        sb.append(".back a{color:#ff3f6c;text-decoration:none;font-size:1.1em;padding:10px 25px;border:1px solid #ff3f6c;border-radius:8px;transition:all .3s}");
        sb.append(".back a:hover{background:#ff3f6c;color:#fff}");
        sb.append("table{width:100%;border-collapse:collapse;background:rgba(255,255,255,.05);border-radius:12px;overflow:hidden;border:1px solid rgba(255,255,255,.1)}");
        sb.append("th{background:rgba(255,63,108,.2);padding:15px 12px;text-align:left;font-weight:600;font-size:.9em;text-transform:uppercase;letter-spacing:.5px}");
        sb.append("td{padding:12px;border-top:1px solid rgba(255,255,255,.05)}");
        sb.append("tr:hover td{background:rgba(255,255,255,.03)}");
        sb.append(".db{background:linear-gradient(135deg,#00b894,#00cec9);color:#fff;padding:4px 12px;border-radius:20px;font-weight:600;font-size:.9em;display:inline-block}");
        sb.append(".po{text-decoration:line-through;color:#888;margin-right:8px}");
        sb.append(".pd{color:#ff3f6c;font-weight:600;font-size:1.1em}");
        sb.append("a.pl{color:#74b9ff;text-decoration:none}");
        sb.append("a.pl:hover{text-decoration:underline}");
        sb.append(".cnt{text-align:center;margin-bottom:20px;color:#888}");
        sb.append(".cnt strong{color:#ff3f6c;font-size:1.3em}");
        sb.append("@media(max-width:768px){table{font-size:.85em}th,td{padding:8px 6px}}");
        sb.append("</style></head><body><div class='c'>");
        sb.append("<h1>Myntra Scraper Results</h1>");
        sb.append("<p class='sub'>Van Heusen T-Shirts Sorted by Highest Discount</p>");
        sb.append("<div class='back'><a href='/'>Run Again</a></div>");
        sb.append("<p class='cnt'>Found <strong>").append(products.size()).append("</strong> discounted products</p>");

        if (products.isEmpty()) {
            sb.append("<div style='text-align:center;padding:60px;background:rgba(255,255,255,.05);border-radius:12px'>");
            sb.append("<h2>No discounted Van Heusen T-shirts found</h2>");
            sb.append("<p style='color:#888;margin-top:10px'>Myntra may have updated their page structure.</p></div>");
        } else {
            sb.append("<table><thead><tr><th>#</th><th>Product</th><th>MRP</th><th>Sale Price</th><th>Discount</th><th>Link</th></tr></thead><tbody>");
            int rank = 1;
            for (Map<String, String> p : products) {
                sb.append("<tr>");
                sb.append("<td>").append(rank++).append("</td>");
                sb.append("<td><strong>").append(esc(p.get("name"))).append("</strong></td>");
                sb.append("<td><span class='po'>&#x20B9;").append(esc(p.get("originalPrice"))).append("</span></td>");
                sb.append("<td><span class='pd'>&#x20B9;").append(esc(p.get("discountedPrice"))).append("</span></td>");
                sb.append("<td><span class='db'>").append(esc(p.get("discount"))).append("% OFF</span></td>");
                sb.append("<td><a class='pl' href='").append(esc(p.get("link"))).append("' target='_blank'>View on Myntra &rarr;</a></td>");
                sb.append("</tr>");
            }
            sb.append("</tbody></table>");
        }
        sb.append("</div></body></html>");
        return sb.toString();
    }

    private static String buildErrorPage(Exception e) {
        String msg = esc(e.getMessage() != null ? e.getMessage() : e.toString());
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
            "<title>Myntra Scraper - Error</title><style>" +
            "body{font-family:sans-serif;background:#1a1a2e;color:#e0e0e0;display:flex;justify-content:center;align-items:center;min-height:100vh;padding:20px}" +
            ".err{background:rgba(255,63,108,.1);border:1px solid #ff3f6c;border-radius:12px;padding:40px;max-width:600px;text-align:center}" +
            "h1{color:#ff3f6c;margin-bottom:15px}pre{background:rgba(0,0,0,.3);padding:15px;border-radius:8px;text-align:left;overflow-x:auto;font-size:.85em;margin:15px 0}" +
            "a{color:#ff3f6c;text-decoration:none;padding:10px 25px;border:1px solid #ff3f6c;border-radius:8px;display:inline-block;margin-top:15px}" +
            "a:hover{background:#ff3f6c;color:#fff}" +
            "</style></head><body><div class='err'>" +
            "<h1>Automation Failed</h1>" +
            "<pre>" + msg + "</pre>" +
            "<p style='color:#888;font-size:.9em'>Install Chrome or Firefox if not present.</p>" +
            "<a href='/'>Try Again</a></div></body></html>";
    }

    // =================== HELPERS ===================

    private static void sendHtml(HttpExchange ex, int code, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void sendText(HttpExchange ex, int code, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String jsonEscape(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
