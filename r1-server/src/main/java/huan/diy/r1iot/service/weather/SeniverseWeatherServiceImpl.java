package huan.diy.r1iot.service.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import huan.diy.r1iot.model.Device;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Service("SeniverseWeatherService")
@Slf4j
public class SeniverseWeatherServiceImpl implements IWeatherService {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("cityLocations")
    private java.util.List<huan.diy.r1iot.model.CityLocation> cityLocations;

    @Override
    public String getAlias() {
        return "心知天气";
    }

    @Override
    public String getWeather(String locationName, int offsetDay, Device device) {
        if (!StringUtils.hasLength(locationName)) {
            if (device.getWeatherConfig() != null && StringUtils.hasLength(device.getWeatherConfig().getLocationId())) {
                String locId = device.getWeatherConfig().getLocationId();
                for (huan.diy.r1iot.model.CityLocation cityLocation : cityLocations) {
                    if (cityLocation.getLocationId().equals(locId)) {
                        locationName = cityLocation.getCityName();
                        break;
                    }
                }
            }
        }

        if (!StringUtils.hasLength(locationName)) {
            return "抱歉，由于您使用心知天气，必须提供城市名称，或在配置中设置默认城市。";
        }

        if (device.getWeatherConfig() == null || !StringUtils.hasLength(device.getWeatherConfig().getKey())) {
            return "抱歉，您还没有在控制台配置心知天气的秘钥(Key)。";
        }

        try {
            String apiKey = device.getWeatherConfig().getKey();
            String url = "https://api.seniverse.com/v3/weather/daily.json?key=" + apiKey +
                    "&location=" + locationName + "&language=zh-Hans&unit=c&start=0&days=3";

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode dailyArray = root.path("results").get(0).path("daily");

            if (dailyArray == null || dailyArray.isMissingNode() || dailyArray.size() <= offsetDay) {
                return "抱歉，未能获取到" + locationName + "指定日期的天气数据。";
            }

            JsonNode targetDay = dailyArray.get(offsetDay);
            
            String textDay = targetDay.path("text_day").asText();
            String textNight = targetDay.path("text_night").asText();
            String high = targetDay.path("high").asText();
            String low = targetDay.path("low").asText();
            String windDirection = targetDay.path("wind_direction").asText();
            String windScale = targetDay.path("wind_scale").asText();

            String dayStr = "";
            if (offsetDay == 0) {
                dayStr = "今天";
            } else if (offsetDay == 1) {
                dayStr = "明天";
            } else if (offsetDay == 2) {
                dayStr = "后天";
            }

            String weatherText = textDay;
            if (!textDay.equals(textNight)) {
                weatherText = textDay + "转" + textNight;
            }

            return locationName + dayStr + "的天气情况是：" + weatherText + "，最高温度" + high + "度，最低温度" + low + "度，" + windDirection + "风" + windScale + "级。";

        } catch (Exception e) {
            log.error("Seniverse query failed", e);
            return "抱歉，通过心知天气查询时服务出现异常，请检查秘钥或网络。";
        }
    }
}
