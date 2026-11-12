package myntra.stepdefinitions;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.And;
import myntra.model.Product;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import java.time.Duration;
import java.util.*;

public class MyntraScraperSteps {
    private WebDriver driver;
    private WebDriverWait wait;
    private JavascriptExecutor js;
    private List<Product> products;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration SCROLL_PAUSE = Duration.ofSeconds(2);

    @Before
    public void setup() {
        boolean headless = Boolean.parseBoolean(System.getProperty("headless", "true"));
        driver = createDriver(headless);
        wait = new WebDriverWait(driver, WAIT_TIMEOUT);
        js = (JavascriptExecutor) driver;
        driver.manage().window().maximize();
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
        products = new ArrayList<>();
    }

    private WebDriver createDriver(boolean headless) {
        // Try Chrome first (more commonly available)
        try {
            WebDriverManager.chromedriver().setup();
            ChromeOptions options = new ChromeOptions();

            // Anti-detection measures
            options.setExperimentalOption("excludeSwitches", Arrays.asList("enable-automation"));
            options.setExperimentalOption("useAutomationExtension", false);
            options.addArguments(
                "--disable-blink-features=AutomationControlled",
                "--disable-infobars",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--window-size=1920,1080",
                "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            );

            if (headless) {
                options.addArguments("--headless=new");
            }

            ChromeDriver chromeDriver = new ChromeDriver(options);

            // Remove webdriver property via CDP
            try {
                chromeDriver.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument",
                    Map.of("source", "Object.defineProperty(navigator, 'webdriver', {get: () => undefined})"));
            } catch (Exception e) {
                System.out.println("CDP script injection skipped: " + e.getMessage());
            }

            System.out.println("Using Chrome browser" + (headless ? " (headless)" : ""));
            return chromeDriver;
        } catch (Exception e) {
            System.out.println("Chrome not available, trying Firefox: " + e.getMessage());
        }

        // Fallback to Firefox
        try {
            WebDriverManager.firefoxdriver().setup();
            FirefoxOptions options = new FirefoxOptions();
            if (headless) {
                options.addArguments("--headless");
            }
            options.addPreference("general.useragent.override",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0");
            System.out.println("Using Firefox browser" + (headless ? " (headless)" : ""));
            return new FirefoxDriver(options);
        } catch (Exception e) {
            throw new RuntimeException("No browser available. Install Chrome or Firefox. Error: " + e.getMessage());
        }
    }

    @After
    public void tearDown() {
        if (driver != null) {
            driver.quit();
        }
    }

    @Given("I am on the Myntra homepage")
    public void navigateToMyntra() {
        driver.get("https://www.myntra.com/");
        wait.until(webDriver -> ((JavascriptExecutor) webDriver)
            .executeScript("return document.readyState").equals("complete"));
        System.out.println("Navigated to Myntra homepage");
    }

    @When("I hover over the Men category")
    public void hoverOverMenCategory() {
        for (int i = 0; i < 3; i++) {
            try {
                WebElement menCategory = wait.until(ExpectedConditions.elementToBeClickable(
                    By.xpath("//a[@data-group='men']")));
                new Actions(driver).moveToElement(menCategory).pause(Duration.ofSeconds(2)).perform();
                System.out.println("Hovered over 'Men' category");
                return;
            } catch (Exception e) {
                if (i == 2) throw e;
                System.out.println("Retrying hover over Men category...");
                driver.navigate().refresh();
                wait.until(wd -> ((JavascriptExecutor) wd)
                    .executeScript("return document.readyState").equals("complete"));
            }
        }
    }

    @And("I click on T-shirts section")
    public void clickTshirtsSection() {
        for (int i = 0; i < 3; i++) {
            try {
                wait.until(ExpectedConditions.elementToBeClickable(
                    By.xpath("//a[contains(@href, 'men-tshirts')]"))).click();
                System.out.println("Clicked on Men's T-Shirts");
                wait.until(wd -> ((JavascriptExecutor) wd)
                    .executeScript("return document.readyState").equals("complete"));
                return;
            } catch (Exception e) {
                if (i == 2) throw e;
                System.out.println("Retrying click on T-Shirts...");
            }
        }
    }

    @And("I filter for Van Heusen brand")
    public void filterVanHeusenBrand() {
        clickSearchIcon();

        WebElement searchInput = wait.until(ExpectedConditions.presenceOfElementLocated(
            By.xpath("//input[@type='text'][@placeholder='Search Brand' or contains(@class, 'filter-search-inputBox')]")));
        searchInput.sendKeys("Van Heusen");
        System.out.println("Entered Van Heusen in search");
        wait.withTimeout(Duration.ofSeconds(2));

        clickVanHeusenCheckbox();
        wait.until(wd -> ((JavascriptExecutor) wd)
            .executeScript("return document.readyState").equals("complete"));
    }

    private void clickSearchIcon() {
        String[] searchIconSelectors = {
            "//span[text()='Brand']/parent::div//span[contains(@class, 'myntraweb-sprite') or contains(@class, 'filter-search-filterSearchIcon')]",
            "//div[contains(@class, 'filter-search-filterHeader')]//span[contains(@class, 'myntraweb-sprite')]",
            "//span[text()='Brand']/following-sibling::span[contains(@class, 'filter-search-filterSearchIcon')]"
        };

        for (String selector : searchIconSelectors) {
            try {
                WebElement searchIcon = wait.until(ExpectedConditions.elementToBeClickable(By.xpath(selector)));
                js.executeScript("arguments[0].scrollIntoView({behavior: 'smooth', block: 'center'});", searchIcon);
                wait.withTimeout(Duration.ofSeconds(1));

                try {
                    searchIcon.click();
                } catch (Exception e) {
                    js.executeScript("arguments[0].click();", searchIcon);
                }
                System.out.println("Successfully clicked search icon");
                return;
            } catch (Exception e) {
                System.out.println("Failed to click search icon with selector: " + selector);
            }
        }
        throw new RuntimeException("Failed to click search icon with all attempted selectors");
    }

    private void clickVanHeusenCheckbox() {
        String[] checkboxSelectors = {
            "//label[contains(., 'Van Heusen')]//div[contains(@class, 'common-checkboxIndicator')]",
            "//div[contains(@class, 'common-checkboxIndicator')]/following-sibling::div[text()='Van Heusen']",
            "//div[text()='Van Heusen']/preceding-sibling::div[contains(@class, 'common-checkboxIndicator')]"
        };

        for (String selector : checkboxSelectors) {
            try {
                WebElement checkbox = wait.until(ExpectedConditions.elementToBeClickable(By.xpath(selector)));
                js.executeScript("arguments[0].scrollIntoView({behavior: 'smooth', block: 'center'});", checkbox);
                wait.withTimeout(Duration.ofSeconds(1));

                try {
                    checkbox.click();
                } catch (Exception e) {
                    js.executeScript("arguments[0].click();", checkbox);
                }
                System.out.println("Successfully clicked Van Heusen checkbox");
                return;
            } catch (Exception e) {
                System.out.println("Failed to click checkbox with selector: " + selector);
            }
        }
        throw new RuntimeException("Failed to click Van Heusen checkbox with all attempted selectors");
    }

    @Then("I should see a list of discounted Van Heusen T-shirts")
    public void scrapeDiscountedTshirts() {
        long lastHeight = 0;
        Set<String> processedLinks = new HashSet<>();

        for (int scrollAttempt = 0; scrollAttempt < 5; scrollAttempt++) {
            List<WebElement> productCards = driver.findElements(By.xpath("//li[contains(@class, 'product-base')]"));

            for (WebElement card : productCards) {
                try {
                    js.executeScript("arguments[0].scrollIntoView({behavior: 'smooth', block: 'center'});", card);
                    wait.withTimeout(Duration.ofSeconds(1));

                    String name = card.findElement(By.xpath(".//h3[contains(@class, 'product-brand')]")).getText();
                    String link = card.findElement(By.xpath(".//a")).getAttribute("href");

                    if (!name.toLowerCase().contains("van heusen") || processedLinks.contains(link)) {
                        continue;
                    }

                    processedLinks.add(link);

                    Double[] prices = extractPrices(card);
                    if (prices != null) {
                        double originalPrice = prices[0];
                        double discountedPrice = prices[1];
                        int discountPercentage = calculateDiscount(originalPrice, discountedPrice);

                        if (discountPercentage > 0) {
                            products.add(new Product(name, link, originalPrice, discountedPrice, discountPercentage));
                        }
                    }

                } catch (Exception e) {
                    System.out.println("Error processing a product: " + e.getMessage());
                }
            }

            js.executeScript("window.scrollTo(0, document.body.scrollHeight)");
            try {
                Thread.sleep(SCROLL_PAUSE.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            long newHeight = (long) js.executeScript("return document.body.scrollHeight");
            if (newHeight == lastHeight) break;
            lastHeight = newHeight;
        }

        System.out.println("Scraped " + products.size() + " Van Heusen T-shirts with discounts");
    }

    @And("the T-shirts should be sorted by discount percentage")
    public void displaySortedResults() {
        Collections.sort(products);

        System.out.println("\nFound " + products.size() + " Van Heusen T-shirts with discounts:");
        System.out.println("\nSorted by highest discount first:");
        System.out.println("----------------------------------------");

        for (Product product : products) {
            System.out.println(product);
            System.out.println("----------------------------------------");
        }
    }

    // --- Public accessor for the server to retrieve results ---
    public List<Product> getProducts() {
        return products;
    }

    private Double[] extractPrices(WebElement card) {
        try {
            String discountedPriceText = extractPrice(card, new String[]{
                ".//div[contains(@class, 'discounted-price')]",
                ".//span[contains(@class, 'discounted-price')]",
                ".//div[contains(@class, 'product-discountedPrice')]",
                ".//span[contains(@class, 'product-discountedPrice')]"
            });

            String originalPriceText = extractPrice(card, new String[]{
                ".//div[contains(@class, 'strike')]",
                ".//span[contains(@class, 'strike')]",
                ".//div[contains(@class, 'product-strike')]",
                ".//span[contains(@class, 'product-strike')]"
            });

            if (discountedPriceText != null && originalPriceText != null) {
                double discountedPrice = Double.parseDouble(discountedPriceText.replaceAll("[^0-9]", ""));
                double originalPrice = Double.parseDouble(originalPriceText.replaceAll("[^0-9]", ""));
                return new Double[]{originalPrice, discountedPrice};
            }
        } catch (Exception e) {
            System.out.println("Error extracting prices: " + e.getMessage());
        }
        return null;
    }

    private String extractPrice(WebElement card, String[] selectors) {
        for (String selector : selectors) {
            try {
                WebElement element = card.findElement(By.xpath(selector));
                String price = element.getText().trim();
                if (!price.isEmpty()) {
                    return price;
                }
            } catch (Exception e) {
                continue;
            }
        }
        return null;
    }

    private int calculateDiscount(double originalPrice, double discountedPrice) {
        return (int) Math.round(((originalPrice - discountedPrice) / originalPrice) * 100);
    }
}
