package com.example.aichat.search;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联网搜索执行器。不依赖 Minecraft，方便单独编译测试。
 *
 * <p>provider：
 * <ul>
 *   <li>bing —— 抓 cn.bing.com 结果页，零 Key、国内直连（默认）</li>
 *   <li>searxng —— 自建实例的 /search?format=json，零费用</li>
 *   <li>tavily / bocha / zhipu —— 商业搜索 API，需要 Key</li>
 * </ul>
 *
 * <p>返回给模型的文本保持"标题 + 摘要 + 链接"的紧凑格式，控制在 maxChars 以内，
 * 避免一次搜索就把 prompt 预算吃光。
 */
public final class WebSearch {

    private static final int MAX_SNIPPET_CHARS = 200;
    private static final int MAX_TOTAL_CHARS = 4000;
    private static final String DEFAULT_BING = "https://cn.bing.com/search";
    private static final String DEFAULT_TAVILY = "https://api.tavily.com/search";
    private static final String DEFAULT_BOCHA = "https://api.bochaai.com/v1/web-search";
    private static final String DEFAULT_ZHIPU = "https://open.bigmodel.cn/api/paas/v4/web_search";

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0 Safari/537.36";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Pattern BING_BLOCK = Pattern.compile("<li class=\"b_algo\"");
    private static final Pattern BING_TITLE = Pattern.compile(
            "<h2[^>]*>\\s*<a\\b[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>");
    private static final Pattern BING_SNIPPET = Pattern.compile("<p[^>]*>([\\s\\S]*?)</p>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ENTITY = Pattern.compile("&(#[xX]?[0-9a-fA-F]+|[a-zA-Z]+);");

    private WebSearch() {
    }

    public record Item(String title, String url, String snippet) {
    }

    /**
     * 执行一次搜索并返回可直接塞进 tool 消息的文本。
     *
     * @throws Exception 网络失败、鉴权失败、解析不到结果等，调用方负责降级
     */
    public static String search(String provider, String apiUrl, String apiKey, String query,
                                int maxResults, int timeoutSeconds) throws Exception {
        String name = provider == null || provider.isBlank()
                ? "bing" : provider.trim().toLowerCase(Locale.ROOT);
        int limit = Math.min(10, Math.max(1, maxResults));
        int timeout = Math.min(60, Math.max(3, timeoutSeconds));
        String keyword = query == null ? "" : query.trim();
        if (keyword.isEmpty()) {
            throw new IllegalArgumentException("搜索词为空");
        }

        List<Item> items = switch (name) {
            case "bing", "ddg" -> bing(apiUrl, keyword, limit, timeout);
            case "searxng" -> searxng(apiUrl, keyword, limit, timeout);
            case "tavily" -> tavily(apiUrl, apiKey, keyword, limit, timeout);
            case "bocha" -> bocha(apiUrl, apiKey, keyword, limit, timeout);
            case "zhipu" -> zhipu(apiUrl, apiKey, keyword, limit, timeout);
            default -> throw new IllegalArgumentException("未知的 search_provider: " + name);
        };

        if (items.isEmpty()) {
            return "搜索「" + keyword + "」没有返回任何结果。可以换个关键词，或直接告诉玩家搜不到。";
        }
        return format(keyword, items);
    }

    /** 该 provider 是否必须有 Key。bing / searxng / ddg 不需要。 */
    public static boolean requiresApiKey(String provider) {
        String name = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return "tavily".equals(name) || "bocha".equals(name) || "zhipu".equals(name);
    }

    // ---------------------------------------------------------------- providers

    private static List<Item> bing(String apiUrl, String query, int limit, int timeout) throws Exception {
        String base = blankTo(apiUrl, DEFAULT_BING);
        String url = base + (base.contains("?") ? "&" : "?")
                + "q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&count=" + limit
                + "&setlang=zh-Hans";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeout))
                .header("User-Agent", BROWSER_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .build();
        String html = send(request).body();

        List<Item> items = new ArrayList<>();
        String[] blocks = BING_BLOCK.split(html);
        // blocks[0] 是第一个结果块之前的内容，跳过
        for (int i = 1; i < blocks.length && items.size() < limit; i++) {
            String block = blocks[i].length() > 8000 ? blocks[i].substring(0, 8000) : blocks[i];
            Matcher title = BING_TITLE.matcher(block);
            if (!title.find()) {
                continue;
            }
            String link = decode(title.group(1));
            String name = strip(title.group(2));
            if (link.isBlank() || name.isBlank()) {
                continue;
            }
            Matcher snippet = BING_SNIPPET.matcher(block);
            String text = snippet.find() ? strip(snippet.group(1)) : "";
            items.add(new Item(name, link, text));
        }
        return items;
    }

    private static List<Item> searxng(String apiUrl, String query, int limit, int timeout) throws Exception {
        if (apiUrl == null || apiUrl.isBlank()) {
            throw new IllegalArgumentException("searxng 需要在 config.json 里填 search_api_url");
        }
        String base = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        String url = base + "/search?format=json&language=zh-CN&safesearch=0&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeout))
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "application/json")
                .GET()
                .build();
        JsonObject root = parseJson(send(request).body());
        List<Item> items = new ArrayList<>();
        for (JsonElement element : array(root, "results")) {
            if (!element.isJsonObject() || items.size() >= limit) {
                break;
            }
            JsonObject hit = element.getAsJsonObject();
            items.add(new Item(text(hit, "title"), text(hit, "url"), text(hit, "content")));
        }
        return items;
    }

    private static List<Item> tavily(String apiUrl, String apiKey, String query,
                                     int limit, int timeout) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("api_key", blankTo(apiKey, ""));
        body.addProperty("query", query);
        body.addProperty("max_results", limit);
        body.addProperty("search_depth", "basic");
        body.addProperty("include_answer", false);
        JsonObject root = parseJson(post(blankTo(apiUrl, DEFAULT_TAVILY), body, timeout, null));
        List<Item> items = new ArrayList<>();
        for (JsonElement element : array(root, "results")) {
            if (!element.isJsonObject() || items.size() >= limit) {
                break;
            }
            JsonObject hit = element.getAsJsonObject();
            items.add(new Item(text(hit, "title"), text(hit, "url"), text(hit, "content")));
        }
        return items;
    }

    private static List<Item> bocha(String apiUrl, String apiKey, String query,
                                    int limit, int timeout) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("query", query);
        body.addProperty("count", limit);
        body.addProperty("summary", true);
        body.addProperty("freshness", "noLimit");
        JsonObject root = parseJson(post(blankTo(apiUrl, DEFAULT_BOCHA), body, timeout, apiKey));
        List<Item> items = new ArrayList<>();
        JsonElement data = root.get("data");
        if (data != null && data.isJsonObject()) {
            JsonElement pages = data.getAsJsonObject().get("webPages");
            if (pages != null && pages.isJsonObject()) {
                for (JsonElement element : array(pages.getAsJsonObject(), "value")) {
                    if (!element.isJsonObject() || items.size() >= limit) {
                        break;
                    }
                    JsonObject hit = element.getAsJsonObject();
                    String snippet = text(hit, "summary");
                    if (snippet.isBlank()) {
                        snippet = text(hit, "snippet");
                    }
                    items.add(new Item(text(hit, "name"), text(hit, "url"), snippet));
                }
            }
        }
        return items;
    }

    private static List<Item> zhipu(String apiUrl, String apiKey, String query,
                                    int limit, int timeout) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("search_engine", "search_std");
        body.addProperty("search_query", query);
        body.addProperty("count", limit);
        body.addProperty("content_size", "medium");
        JsonObject root = parseJson(post(blankTo(apiUrl, DEFAULT_ZHIPU), body, timeout, apiKey));
        List<Item> items = new ArrayList<>();
        for (JsonElement element : array(root, "search_result")) {
            if (!element.isJsonObject() || items.size() >= limit) {
                break;
            }
            JsonObject hit = element.getAsJsonObject();
            String link = text(hit, "link");
            if (link.isBlank()) {
                link = text(hit, "url");
            }
            items.add(new Item(text(hit, "title"), link, text(hit, "content")));
        }
        return items;
    }

    // ---------------------------------------------------------------- plumbing

    private record Response(int status, String body) {
    }

    private static Response send(HttpRequest request) throws Exception {
        HttpResponse<String> response =
                HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).get();
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + "：" + trim(response.body(), 160));
        }
        return new Response(response.statusCode(), response.body());
    }

    private static String post(String url, JsonObject body, int timeout, String apiKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json")
                .header("User-Agent", BROWSER_UA)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }
        return send(builder.build()).body();
    }

    private static String format(String keyword, List<Item> items) {
        StringBuilder builder = new StringBuilder();
        builder.append("搜索「").append(keyword).append("」的结果（").append(items.size()).append(" 条）：\n");
        int index = 1;
        for (Item item : items) {
            String block = index + ". " + trim(item.title(), 80) + "\n   "
                    + trim(item.snippet(), MAX_SNIPPET_CHARS) + "\n   " + item.url() + "\n";
            if (builder.length() + block.length() > MAX_TOTAL_CHARS) {
                break;
            }
            builder.append(block);
            index++;
        }
        return builder.toString().trim();
    }

    private static JsonObject parseJson(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) {
            throw new IllegalStateException("搜索接口返回的不是 JSON 对象");
        }
        return root.getAsJsonObject();
    }

    private static Iterable<JsonElement> array(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        JsonArray array = element.getAsJsonArray();
        List<JsonElement> copy = new ArrayList<>(array.size());
        array.forEach(copy::add);
        return copy;
    }

    private static String text(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element == null || element.isJsonNull() || !element.isJsonPrimitive()
                ? "" : decode(element.getAsString());
    }

    private static String strip(String html) {
        return decode(TAG.matcher(html).replaceAll(" ")).replaceAll("\\s+", " ").trim();
    }

    /** 只处理搜索页常见的几种实体，够用且不引额外依赖。 */
    private static String decode(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text == null ? "" : text;
        }
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1);
            String replacement;
            switch (token.toLowerCase(Locale.ROOT)) {
                case "amp" -> replacement = "&";
                case "lt" -> replacement = "<";
                case "gt" -> replacement = ">";
                case "quot" -> replacement = "\"";
                case "apos", "#39" -> replacement = "'";
                case "nbsp", "#160" -> replacement = " ";
                case "ensp", "#8194" -> replacement = " ";
                case "emsp", "#8195" -> replacement = " ";
                default -> {
                    try {
                        if (token.startsWith("#x") || token.startsWith("#X")) {
                            replacement = new String(Character.toChars(Integer.parseInt(token.substring(2), 16)));
                        } else if (token.startsWith("#")) {
                            replacement = new String(Character.toChars(Integer.parseInt(token.substring(1))));
                        } else {
                            replacement = matcher.group(0);
                        }
                    } catch (RuntimeException ignored) {
                        replacement = matcher.group(0);
                    }
                }
            }
            matcher.appendReplacement(builder, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private static String trim(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
