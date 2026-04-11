package huan.diy.r1iot.service.music;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import huan.diy.r1iot.model.Device;
import huan.diy.r1iot.model.MusicAiResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;


@Service("VIP")
@Slf4j
public class VIPMusic implements IMusicService {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RestTemplate restTemplate;

    @Override
    public JsonNode fetchMusics(MusicAiResp musicAiResp, Device device) {

        StringBuilder sb = new StringBuilder();
        sb.append(StringUtils.hasLength(musicAiResp.getAuthor()) ? musicAiResp.getAuthor() : "");
        sb.append(" ");
        sb.append(StringUtils.hasLength(musicAiResp.getMusicName()) ? musicAiResp.getMusicName() : "");
        String keyword = sb.toString().trim();
        if (keyword.isEmpty()) {
            keyword = musicAiResp.getKeyword();
        }
        
        log.info("VIP解锁 - 准备获取音乐，搜索关键词: {}", keyword);
        JsonNode searchRet = searchByKeyword(keyword, device);
        
        ArrayNode arrayNode = null;
        if (searchRet.has("musicinfo")) {
            arrayNode = (ArrayNode) searchRet.get("musicinfo");
        } else if (searchRet.has("data")) {
            arrayNode = (ArrayNode) searchRet.get("data");
        }

        ArrayNode musicInfo = objectMapper.createArrayNode();
        if (arrayNode != null) {
            for (JsonNode node : arrayNode) {
                try {
                    ObjectNode music = objectMapper.createObjectNode();
                    Long id = node.get("id").asLong();
                    music.put("id", id);
                    
                    if (node.has("title")) { 
                        // 新接口格式 (musicinfo)
                        music.put("title", node.get("title").asText());
                        music.put("artist", node.get("artist").asText());
                        music.put("album", node.get("album").asText());
                    } else { 
                        // 老接口格式 (data)
                        music.put("title", node.get("name").asText());
                        music.put("artist", node.get("artists").get(0).get("name").asText());
                        music.put("album", node.get("album").get("name").asText());
                    }
                    
                    music.put("url", node.get("url").asText());
                    musicInfo.add(music);
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            }
        }

        ObjectNode result = objectMapper.createObjectNode();

        ObjectNode ret = objectMapper.createObjectNode();
        int count = arrayNode != null ? arrayNode.size() : 0;
        
        log.info("VIP解锁 - 解析完成，共封装了 {} 首歌曲信息", count);
        
        ret.put("count", count);
        ret.set("musicinfo", musicInfo);
        ret.put("pagesize", String.valueOf(count));
        ret.put("errorCode", 0);
        ret.put("page", "1");
        ret.put("source", 1);

        result.set("result", ret);

        return result;
    }


    public JsonNode searchByKeyword(String keyword, Device device) {
        String endpoint = device.getMusicConfig().getEndpoint();
        endpoint = endpoint.endsWith("/") ? endpoint : (endpoint + "/");

        String requestUrl = endpoint + "search?keyword=" + keyword;
        log.info("VIP解锁 - 发起请求: {}", requestUrl);
        
        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
                requestUrl,
                JsonNode.class
        );
        
        log.info("VIP解锁 - 接口返回响应: {}", response.getBody() != null ? response.getBody().toString() : "null");

        return response.getBody();
    }

    @Override
    public String getAlias() {
        return "VIP解锁";
    }

}
