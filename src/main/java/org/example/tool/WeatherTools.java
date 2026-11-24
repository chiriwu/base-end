package org.example.tool;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class WeatherTools {

    private final RestClient restClient;
    private final String apiKey;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile Map<String, String> cityCodeMap;

    public WeatherTools(RestClient.Builder builder,
                        @Value("${weather.api.base-url:http://api.yytianqi.com}") String baseUrl,
                        @Value("${weather.api.key:}") String apiKey) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    @Tool(description = "查询指定城市未来 7 天的天气预报,返回可读中文文本(日期、天气、气温范围、风向风力)。城市名用中文,例如 北京、杭州")
    public String getWeather(@ToolParam(description = "城市名,例如 北京") String city) {
        log.info("getWeather 工具被调用, city={}", city);
        if (city == null || city.isBlank()) {
            return "城市名不能为空";
        }
        if (apiKey == null || apiKey.isBlank()) {
            return "天气服务未配置 API key(weather.api.key)";
        }
        String name = city.trim();
        try {
            String code = resolveCityCode(name);
            if (code == null) {
                return "未找到城市 " + name + " 的编码,请确认城市名称";
            }
            String body = restClient.get()
                    .uri(uri -> uri.path("/forecast7d")
                            .queryParam("city", code)
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .body(String.class);
            log.info("天气接口调用成功 city={}", name);
            return formatForecast(objectMapper.readTree(body), name);
        } catch (Exception e) {
            log.warn("天气查询失败 city={}: {}", name, e.getMessage());
            return "天气查询失败: " + e.getMessage();
        }
    }

    private String resolveCityCode(String cityName) {
        Map<String, String> map = cityCodeMap();
        String code = map.get(cityName);
        if (code == null && cityName.endsWith("市") && cityName.length() > 1) {
            code = map.get(cityName.substring(0, cityName.length() - 1));
        }
        return code;
    }

    private Map<String, String> cityCodeMap() {
        Map<String, String> map = cityCodeMap;
        if (map == null || map.isEmpty()) {
            synchronized (this) {
                if (cityCodeMap == null || cityCodeMap.isEmpty()) {
                    cityCodeMap = loadCityMap();
                }
                map = cityCodeMap;
            }
        }
        return map;
    }

    private Map<String, String> loadCityMap() {
        try {
            String body = restClient.get().uri("/citylist/id/1").retrieve().body(String.class);
            Map<String, String> map = new java.util.LinkedHashMap<>();
            collectLeafCities(objectMapper.readTree(body), map);
            log.info("城市列表加载完成,共 {} 个城市", map.size());
            return map;
        } catch (Exception e) {
            log.warn("城市列表加载失败: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 递归展平城市树:带 list 的是省级节点(如 CH01 北京),不带的是城市叶子(如 CH010100) */
    private void collectLeafCities(JsonNode node, Map<String, String> map) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectLeafCities(child, map);
            }
            return;
        }
        if (node.isObject()) {
            JsonNode list = node.get("list");
            if (list != null && list.isArray()) {
                collectLeafCities(list, map);
                return;
            }
            JsonNode id = node.get("city_id");
            JsonNode name = node.get("name");
            if (id != null && name != null && !map.containsKey(name.asText())) {
                map.put(name.asText(), id.asText());
            }
        }
    }

    /** 解析 forecast7d 响应,把 tq1/qw1 等密文级字段转成模型易读的中文文本 */
    private String formatForecast(JsonNode root, String cityName) {
        JsonNode data = root.get("data");
        if (root.path("code").asInt() != 1 || data == null || !data.has("list")) {
            return "天气接口返回异常: " + root.path("msg").asText("");
        }
        StringBuilder sb = new StringBuilder(cityName).append(" 未来 7 天预报:\n");
        for (JsonNode day : data.get("list")) {
            sb.append(day.path("date").asText(""))
              .append(": ")
              .append(day.path("tq1").asText(""))
              .append("转")
              .append(day.path("tq2").asText(""))
              .append(", ")
              .append(day.path("qw2").asText(""))
              .append("~")
              .append(day.path("qw1").asText(""))
              .append("℃, ")
              .append(day.path("fx1").asText(""))
              .append(day.path("fl1").asText(""))
              .append('\n');
        }
        return sb.toString().stripTrailing();
    }
}