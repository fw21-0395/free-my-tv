package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional crypto prices (Binance, every 5 min) and weather/sunrise/sunset (Open-Meteo, every 30 min), cached
 * so the screen opens with numbers. Only fetched while the home screen or the screensaver is visible (the screen asks).
 * Neither source needs a key. Location and currency come from config.properties.
 */
final class HomeData {
    static final String[] COINS = {"BTC", "ETH", "SOL", "USDT"};
    static final String[] COIN_NAMES = {"Bitcoin", "Ethereum", "Solana", "Dollar"};
    private static final String CURRENCY = Config.CURRENCY.trim().toUpperCase(Locale.ROOT);
    /** Binance has no pairs in some currencies (INR...) and then refuses the whole request: prices go in dollars instead. */
    private static volatile boolean inDollars;

    /** Binance quotes dollars as USDT. */
    private static String quote() {
        return CURRENCY.equals("USD") || inDollars ? "USDT" : CURRENCY;
    }
    private static final String WEATHER_QUERY = "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset"
            + "&hourly=temperature_2m,weather_code,is_day&timezone=auto&forecast_days=2";

    private static String weatherPlace() {
        return Prefs.latitude() + "," + Prefs.longitude();
    }

    private static String weatherUrl() {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + Prefs.latitude() + "&longitude=" + Prefs.longitude() + WEATHER_QUERY;
    }
    /** Open-Meteo's air quality (CAMS forecasts, no key), fetched with the weather. */
    private static final String AIR_URL = "https://air-quality-api.open-meteo.com/v1/air-quality";
    /** The US AQI now, and the last day's pollutants hour by hour for India's scale (which averages them). */
    private static final String AIR_QUERY = "&current=us_aqi&hourly=pm2_5,pm10,nitrogen_dioxide,sulphur_dioxide,ozone,carbon_monoxide"
            + "&past_days=1&forecast_days=1&timezone=auto&timeformat=unixtime";
    private static final long PRICES_EVERY = 5 * 60_000L, WEATHER_EVERY = 30 * 60_000L;
    interface Listener { void onData(); }

    /** BTC, ETH and SOL; in Brazilian reais also the dollar (USDT/BRL), which Binance doesn't list for most currencies. */
    static int coinCount() {
        return Prefs.quotes() ? active().length : 0;
    }

    private static volatile int[] activeCoins;
    private static volatile int activeVersion = -1;

    /** Indexes in COINS of the coins the user has on (and that Binance lists in this currency). */
    static int[] active() {
        int[] a = activeCoins;
        if (a != null && activeVersion == Prefs.version) return a;
        int n = 0;
        int[] tmp = new int[COINS.length];
        for (int i = 0; i < COINS.length; i++) if (Prefs.coin(i) && (i < 3 || CURRENCY.equals("BRL"))) tmp[n++] = i;
        a = java.util.Arrays.copyOf(tmp, n);
        activeCoins = a;
        activeVersion = Prefs.version;
        return a;
    }

    /** Settings changed (city, coins): fetch again now. */
    void settingsChanged() {
        pricesAt = 0;
        weatherAt = 0;
        pricesFailedAt = 0;
        weatherFailedAt = 0;
    }

    static String coinName(int i) {
        return i == 3 ? Ui.tr("Dollar") : COIN_NAMES[i];
    }

    /** Binance.com refuses the US (HTTP 451); Binance.US answers the same call in dollars. */
    private static final String[] PRICE_HOSTS = {"https://api.binance.com", "https://api.binance.us"};

    private static String pricesPath() {
        StringBuilder symbols = new StringBuilder();
        int[] coins = active();
        for (int i = 0; i < coins.length; i++) symbols.append(i == 0 ? "" : ",").append("%22").append(COINS[coins[i]]).append(quote()).append("%22");
        return "/api/v3/ticker/24hr?type=MINI&symbols=%5B" + symbols + "%5D";
    }

    private static String getPrices() throws IOException {
        IOException failed = null;
        for (String host : PRICE_HOSTS) {
            try {
                return get(host + pricesPath());
            } catch (IOException e) {
                failed = e;
                // A pair Binance doesn't list (HTTP 400) isn't a refusal: the caller falls back to dollars instead.
                if ("HTTP 400".equals(e.getMessage())) break;
            }
        }
        throw failed;
    }

    static final class Weather {
        double temp = Double.NaN, max = Double.NaN, min = Double.NaN;
        int code = -1;
        boolean isDay = true;
        long sunrise, sunset;   // today's, in epoch ms
        int aqi = -1;           // air quality index now (-1: unknown)
        boolean aqiIndia;       // on India's scale (CPCB's National AQI), not the US one
        // Next hours (starting at the next full hour): time, temperature, code and day/night.
        final List<Long> hours = new ArrayList<>();
        final List<Double> hourTemp = new ArrayList<>();
        final List<Integer> hourCode = new ArrayList<>();
        final List<Boolean> hourDay = new ArrayList<>();
    }

    private static HomeData instance;

    static synchronized HomeData get(Context c) {
        if (instance == null) instance = new HomeData(c.getApplicationContext());
        return instance;
    }

    final double[] prices = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    /** 24 h change (%), in the same order. */
    final double[] changes = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    volatile long pricesAt, weatherAt;
    volatile Weather weather = new Weather();

    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean fetchingPrices, fetchingWeather;
    private volatile long pricesFailedAt, weatherFailedAt;

    private HomeData(Context c) {
        cache = c.getSharedPreferences("homedata", Context.MODE_PRIVATE);
        inDollars = CURRENCY.equals(cache.getString("dollarsFor", ""));
        try {
            if (Prefs.quotes()) {
                String p = cache.getString("prices", null);
                if (p != null) parsePrices(p);
                pricesAt = cache.getLong("pricesAt", 0);
            }
            String w = cache.getString("weather", null);
            // The cached forecast is for the city it was fetched for; another city means fetch again.
            if (w != null && weatherPlace().equals(cache.getString("weatherPlace", ""))) {
                weather = parseWeather(w);
                weather.aqi = cache.getInt("air", -1);
                weather.aqiIndia = cache.getBoolean("airIndia", false);
                // Saved before the air quality was fetched along: fetch again now.
                weatherAt = cache.contains("air") ? cache.getLong("weatherAt", 0) : 0;
            }
        } catch (Exception ignored) {}
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Fetches whatever is stale. The screen calls this when it appears and every minute while it's visible. */
    void refreshIfStale() {
        long now = System.currentTimeMillis();
        // A negative age (the TV's clock went back) counts as stale: otherwise the data would freeze.
        if (!fetchingPrices && coinCount() > 0 && (now - pricesAt > PRICES_EVERY || now < pricesAt) && !failedRecently(pricesFailedAt, now)) {
            fetchingPrices = true;
            io.execute(() -> {
                try {
                    String body;
                    try {
                        body = getPrices();
                    } catch (IOException e) {
                        if (inDollars || CURRENCY.equals("USD") || !"HTTP 400".equals(e.getMessage())) throw e;
                        inDollars = true;
                        cache.edit().putString("dollarsFor", CURRENCY).apply();
                        AppLog.w("prices: Binance has no " + CURRENCY + " pairs, using dollars");
                        body = getPrices();
                    }
                    parsePrices(body);
                    pricesAt = System.currentTimeMillis();
                    cache.edit().putString("prices", body).putLong("pricesAt", pricesAt).apply();
                    notifyListeners();
                } catch (Exception e) {
                    pricesFailedAt = System.currentTimeMillis();
                    AppLog.w("prices: " + e.getMessage());
                } finally {
                    fetchingPrices = false;
                }
            });
        }
        if (!fetchingWeather && (now - weatherAt > WEATHER_EVERY || now < weatherAt) && !failedRecently(weatherFailedAt, now)) {
            fetchingWeather = true;
            io.execute(() -> {
                try {
                    String body = get(weatherUrl());
                    Weather w = parseWeather(body);
                    try {   // the air quality is extra: without it, the weather still shows
                        parseAir(get(AIR_URL + "?latitude=" + Prefs.latitude() + "&longitude=" + Prefs.longitude() + AIR_QUERY), w);
                    } catch (Exception e) {
                        AppLog.w("air quality: " + e.getMessage());
                    }
                    weather = w;
                    weatherAt = System.currentTimeMillis();
                    cache.edit().putString("weather", body).putInt("air", w.aqi).putBoolean("airIndia", w.aqiIndia)
                            .putLong("weatherAt", weatherAt).putString("weatherPlace", weatherPlace()).apply();
                    notifyListeners();
                } catch (Exception e) {
                    weatherFailedAt = System.currentTimeMillis();
                    AppLog.w("weather: " + e.getMessage());
                } finally {
                    fetchingWeather = false;
                }
            });
        }
    }

    private static boolean failedRecently(long at, long now) {
        return at != 0 && now >= at && now - at < 2 * 60_000L;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onData(); });
    }

    private void parsePrices(String body) throws Exception {
        JSONArray list = new JSONArray(body);
        String quote = quote();
        int found = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            String symbol = o.getString("symbol");
            for (int k = 0; k < COINS.length; k++) {
                if (!symbol.equals(COINS[k] + quote)) continue;
                found++;
                double last = Double.parseDouble(o.optString("lastPrice", o.optString("price", "NaN")));
                double open = Double.parseDouble(o.optString("openPrice", "NaN"));
                prices[k] = last;
                changes[k] = open > 0 ? (last - open) / open * 100 : Double.NaN;
            }
        }
        if (found == 0) pricesAt = 0;   // cached in another currency (config changed): fetch again
    }

    private static Weather parseWeather(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        TimeZone zone = TimeZone.getTimeZone(o.optString("timezone", TimeZone.getDefault().getID()));
        Weather w = new Weather();
        JSONObject current = o.getJSONObject("current");
        w.temp = current.getDouble("temperature_2m");
        w.code = current.getInt("weather_code");
        w.isDay = current.optInt("is_day", 1) == 1;
        JSONObject daily = o.getJSONObject("daily");
        w.max = daily.getJSONArray("temperature_2m_max").getDouble(0);
        w.min = daily.getJSONArray("temperature_2m_min").getDouble(0);
        w.sunrise = localTime(daily.getJSONArray("sunrise").getString(0), zone);
        w.sunset = localTime(daily.getJSONArray("sunset").getString(0), zone);
        JSONObject hourly = o.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray codes = hourly.getJSONArray("weather_code");
            JSONArray days = hourly.optJSONArray("is_day");
            long now = System.currentTimeMillis();
            for (int i = 0; i < times.length() && w.hours.size() < 6; i++) {
                long at = localTime(times.getString(i), zone);
                if (at <= now) continue;
                w.hours.add(at);
                w.hourTemp.add(temps.getDouble(i));
                w.hourCode.add(codes.getInt(i));
                w.hourDay.add(days == null || days.optInt(i, 1) == 1);
            }
        }
        return w;
    }

    private static long localTime(String iso, TimeZone zone) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(zone);
        return f.parse(iso).getTime();
    }

    static List<String[]> findCity(String name) throws Exception {
        String lang = Ui.language.replaceAll("-.*", "");   // Open-Meteo takes the language without the country
        JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=" + lang
                + "&name=" + URLEncoder.encode(name.trim(), "UTF-8")));
        JSONArray results = o.optJSONArray("results");
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();   // places with the same name, state and country read the same
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String city = r.optString("name", "");
            if (city.isEmpty() || !r.has("latitude") || !r.has("longitude")) continue;
            StringBuilder label = new StringBuilder(city);
            for (String part : new String[] {r.optString("admin1", ""), r.optString("country", "")}) {
                if (!part.isEmpty() && !part.equals(city)) label.append(", ").append(part);
            }
            if (!seen.add(label.toString())) continue;
            out.add(new String[] {label.toString(), city,
                    String.format(Locale.US, "%.4f", r.getDouble("latitude")), String.format(Locale.US, "%.4f", r.getDouble("longitude"))});
        }
        return out;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Night at your location: from sunset to sunrise (like Apple's "Automatic" appearance: the light design darkens).
     * Uses the last weather fetched (it moves ~1 min a day); without data, 6:00 and 18:00.
     */
    boolean isNight(long now) {
        String theme = Prefs.theme();
        if (theme.equals("dark")) return true;
        if (theme.equals("light")) return false;
        long t = Shaders.timeOfDay(now, 0);
        long rise = Shaders.timeOfDay(weather.sunrise, 6 * 60 * 60_000L);
        long set = Shaders.timeOfDay(weather.sunset, 18 * 60 * 60_000L);
        return t < rise || t >= set;
    }

    // MARK: - Formatting

    private static Locale locale() {
        return Ui.locale();
    }

    /** 580.4k · 21.0k · 1.04k · 5.61 (as short as possible; the dollar always with cents). */
    static String price(double v) {
        if (Double.isNaN(v)) return "—";
        if (v >= 10_000) return String.format(locale(), "%.1fk", v / 1000);
        if (v >= 1000) return String.format(locale(), "%.2fk", v / 1000);
        return String.format(locale(), "%.2f", v);
    }

    /** 416.3k · 12.2k · 1.04k · 531 · 5.05: abbreviated, for the price row on the home screen. */
    static String compact(double v) {
        if (Double.isNaN(v)) return "—";
        if (v >= 10_000) return String.format(locale(), "%.1fk", v / 1000);
        if (v >= 1000) return String.format(locale(), "%.2fk", v / 1000);
        if (v >= 100) return String.format(locale(), "%.0f", v);
        return String.format(locale(), "%.2f", v);
    }

    /**
     * $108,234 · $185.20 · R$ 581.234 · 74 264 € (cents only below a thousand), in the configured currency, with the
     * symbol and spaces where the language puts them.
     */
    static String brl(double v) {
        String symbol = currencySymbol();
        if (Double.isNaN(v)) return symbol + "—";
        return Ui.once("brl", v, () -> {
            DecimalFormat f = (DecimalFormat) NumberFormat.getCurrencyInstance(locale());
            DecimalFormatSymbols s = f.getDecimalFormatSymbols();
            s.setCurrencySymbol(symbol.trim());   // our symbols: ICU would write "US$" outside the US
            f.setDecimalFormatSymbols(s);
            f.setMinimumFractionDigits(v >= 1000 ? 0 : 2);
            f.setMaximumFractionDigits(v >= 1000 ? 0 : 2);
            return f.format(v);
        });
    }

    /**
     * India's National AQI (CPCB): for each pollutant, its concentration's breakpoints (µg/m³; CO in mg/m³) for the
     * index's 0, 50, 100, 200, 300, 400 and 500. PM2.5, PM10, NO₂ and SO₂ go by the last 24 hours' average, ozone and
     * CO by the worst 8 hours; the AQI is the highest of them.
     */
    private static final String[] AIR_FIELDS = {"pm2_5", "pm10", "nitrogen_dioxide", "sulphur_dioxide", "ozone", "carbon_monoxide"};
    private static final double[][] AIR_BREAKS = {
        {0, 30, 60, 90, 120, 250, 380},
        {0, 50, 100, 250, 350, 430, 510},
        {0, 40, 80, 180, 280, 400, 520},
        {0, 40, 80, 380, 800, 1600, 2100},
        {0, 50, 100, 168, 208, 748, 1000},
        {0, 1, 2, 10, 17, 34, 50},
    };
    private static final int[] AIR_INDEX = {0, 50, 100, 200, 300, 400, 500};

    /** Reads Open-Meteo's answer into w: India's scale for a place in India (by its time zone), the US one elsewhere. */
    private static void parseAir(String body, Weather w) throws Exception {
        JSONObject o = new JSONObject(body);
        String zone = o.optString("timezone");
        if (!zone.equals("Asia/Kolkata") && !zone.equals("Asia/Calcutta")) {
            w.aqi = o.getJSONObject("current").optInt("us_aqi", -1);
            w.aqiIndia = false;
            return;
        }
        JSONObject hourly = o.getJSONObject("hourly");
        JSONArray times = hourly.getJSONArray("time");
        long now = System.currentTimeMillis() / 1000;
        int last = -1;
        for (int i = 0; i < times.length(); i++) if (times.getLong(i) <= now) last = i;
        if (last < 0) throw new IOException("no air quality hours yet");
        int aqi = -1;
        for (int p = 0; p < AIR_FIELDS.length; p++) {
            JSONArray values = hourly.getJSONArray(AIR_FIELDS[p]);
            double scale = p == 5 ? 0.001 : 1;   // Open-Meteo's CO is in µg/m³
            double c = p >= 4 ? worst8h(values, last) : average(values, last - 23, last);
            if (Double.isNaN(c)) continue;
            aqi = Math.max(aqi, subIndex(AIR_BREAKS[p], c * scale));
        }
        if (aqi < 0) throw new IOException("no pollutant readings");
        w.aqi = aqi;
        w.aqiIndia = true;
    }

    /** The average of the hours from..to (the missing ones skipped), or NaN without enough of them. */
    private static double average(JSONArray values, int from, int to) {
        double sum = 0;
        int n = 0;
        for (int i = Math.max(0, from); i <= to; i++) {
            if (values.isNull(i)) continue;
            sum += values.optDouble(i);
            n++;
        }
        return n >= (to - from + 1) * 2 / 3 ? sum / n : Double.NaN;
    }

    /** The highest 8-hour average in the last 24 hours. */
    private static double worst8h(JSONArray values, int last) {
        double worst = Double.NaN;
        for (int end = Math.max(7, last - 16); end <= last; end++) {
            double a = average(values, end - 7, end);
            if (!Double.isNaN(a) && !(a <= worst)) worst = a;
        }
        return worst;
    }

    /** CPCB's sub-index: linear between the breakpoints, 500 at most. */
    private static int subIndex(double[] breaks, double c) {
        for (int i = 1; i < breaks.length; i++) {
            if (c <= breaks[i]) {
                double f = (c - breaks[i - 1]) / (breaks[i] - breaks[i - 1]);
                return (int) Math.round(AIR_INDEX[i - 1] + f * (AIR_INDEX[i] - AIR_INDEX[i - 1]));
            }
        }
        return 500;
    }

    /** The AQI's level in words: India's six (CPCB) or the US EPA's six. */
    static String airLevel(int aqi, boolean india) {
        if (india) {
            if (aqi <= 50) return Ui.tr("air|Good");
            if (aqi <= 100) return Ui.tr("air|Satisfactory");
            if (aqi <= 200) return Ui.tr("air|Moderate");
            if (aqi <= 300) return Ui.tr("air|Poor");
            if (aqi <= 400) return Ui.tr("air|Very poor");
            return Ui.tr("air|Severe");
        }
        if (aqi <= 50) return Ui.tr("air|Good");
        if (aqi <= 100) return Ui.tr("air|Moderate");
        if (aqi <= 150) return Ui.tr("air|Unhealthy for sensitive groups");
        if (aqi <= 200) return Ui.tr("air|Unhealthy");
        if (aqi <= 300) return Ui.tr("air|Very unhealthy");
        return Ui.tr("air|Hazardous");
    }

    /** The level's color, as on each scale (India: greens, yellow, orange, red, dark red; US: up to purple and maroon). */
    static int airColor(int aqi, boolean india) {
        if (india) {
            if (aqi <= 50) return 0xFF00B050;
            if (aqi <= 100) return 0xFF92D050;
            if (aqi <= 200) return 0xFFFFD60A;
            if (aqi <= 300) return 0xFFFF9500;
            if (aqi <= 400) return 0xFFFF3B30;
            return 0xFFC00000;
        }
        if (aqi <= 50) return 0xFF34C759;
        if (aqi <= 100) return 0xFFFFCC00;
        if (aqi <= 150) return 0xFFFF9500;
        if (aqi <= 200) return 0xFFFF3B30;
        if (aqi <= 300) return 0xFFAF52DE;
        return 0xFFA2264B;
    }

    /** The weather in words, from Open-Meteo's WMO code. */
    static String condition(int code, boolean day) {
        if (code < 0) return "";
        if (code == 0) return day ? Ui.tr("Clear") : Ui.tr("night|Clear");
        if (code == 1) return day ? Ui.tr("Mostly Sunny") : Ui.tr("Mostly Clear");
        if (code == 2) return Ui.tr("Partly Cloudy");
        if (code == 3) return Ui.tr("Cloudy");
        if (code == 45 || code == 48) return Ui.tr("Fog");
        if (code >= 51 && code <= 57) return Ui.tr("Drizzle");
        if (code == 65 || code == 67 || code == 82) return Ui.tr("Heavy Rain");
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return Ui.tr("Snow");
        if (code >= 95) return Ui.tr("Thunderstorms");
        return Ui.tr("Rain");
    }

    /** "H:24°  L:15°" (in Portuguese, "Máx. 24°  Mín. 15°"), with the given space between the two. */
    static String highLow(Weather wx, String space) {
        return Ui.trf("H:{max}", "max", degrees(wx.max)) + space + Ui.trf("L:{min}", "min", degrees(wx.min));
    }

    private static String currencySymbol() {
        switch (inDollars ? "USD" : CURRENCY) {
            case "USD": return "$";
            case "BRL": return "R$ ";
            case "EUR": return "€";
            case "GBP": return "£";
            case "JPY": return "¥";
            default: return CURRENCY + " ";
        }
    }

    /** Celsius or Fahrenheit (config.properties "temperature": C, F, or auto = Fahrenheit in the US). */
    static String degrees(double v) {
        if (Double.isNaN(v)) return "—";
        return Math.round(fahrenheit() ? v * 9 / 5 + 32 : v) + "°";
    }

    private static boolean fahrenheit() {
        String unit = Prefs.temperature();
        if (unit.equals("F")) return true;
        if (unit.equals("C")) return false;
        return "US".equals(Locale.getDefault().getCountry());
    }

    /** "+1.2%" / "−0.8%" (a real minus sign); "+1,2 %" in the languages that space the percent sign. */
    static String change(double v) {
        if (Double.isNaN(v)) return "";
        return Ui.once("change", v, () -> {
            NumberFormat f = NumberFormat.getPercentInstance(locale());
            f.setMinimumFractionDigits(1);
            f.setMaximumFractionDigits(1);
            return (v >= 0 ? "+" : "−") + f.format(Math.abs(v) / 100);
        });
    }

    static String hour(long at) {
        return Ui.once("hour", at, () -> Ui.datePattern("format|h a").format(at));
    }

    /** How long ago the data was fetched, if it's stale (no internet): "12 min ago". */
    static String staleness(long at, long every) {
        if (at == 0) return "";
        long age = System.currentTimeMillis() - at;
        if (age < every * 2) return "";
        long minutes = age / 60_000;
        return minutes < 60 ? Ui.trf("{n} min ago", "n", minutes) : Ui.trf("{n} h ago", "n", minutes / 60);
    }
}
