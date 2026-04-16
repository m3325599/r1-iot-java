package huan.diy.r1iot.free;

import com.fasterxml.jackson.databind.JsonNode;
import huan.diy.r1iot.direct.AIDirect;
import huan.diy.r1iot.direct.AiAssistant;
import huan.diy.r1iot.service.YoutubeService;
import huan.diy.r1iot.service.music.IMusicService;
import huan.diy.r1iot.util.R1IotUtils;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@CrossOrigin(origins = "*")
@Slf4j
public class NoAuthController {

    @Autowired
    private AIDirect aidirect;

    @Autowired
    private YoutubeService youtubeService;

    @Autowired
    private Map<String, IMusicService> musicServiceMap;

    @Autowired
    private huan.diy.r1iot.model.R1GlobalConfig globalConfig;

    @PostMapping("/auth")
    public String login(@RequestBody final Map<String, String> map) {
        String password = map.get("password");
        String envPass = System.getenv("password");
        if (password.equals(envPass)) {
            String token = UUID.randomUUID().toString();
            R1IotUtils.setAuthToken(token);
            return token;
        } else {
            throw new RuntimeException("password does not match");
        }
    }


    @GetMapping("/audio/play/{vId}.m4a")
    public void streamAudio(@PathVariable String vId,
                            @RequestHeader(value = "Range", required = false) String rangeHeader,
                            HttpServletResponse response) throws Exception {
        youtubeService.streamAudio(vId, rangeHeader, response);
    }

    @GetMapping("/music/{musicSvc}/{songId}.mp3")
    public void streamMusic(@PathVariable String musicSvc,
                            @PathVariable String songId,
                            HttpServletResponse response) throws Exception {
        musicServiceMap.get(musicSvc).streamMusic(songId, response);
    }


    @GetMapping("/test")
    public String test(@RequestParam String deviceId, @RequestParam String text) {
        long start = System.currentTimeMillis();
        String resp = aidirect.getAssistants().get(deviceId).chat(text);

        System.out.println(resp);
        System.out.println(System.currentTimeMillis() - start);
        return resp;
    }

    @PostMapping("/getUserInfo")
    public Map<String, Integer> getUserInfo() {
        Map<String, Integer> map = new HashMap<>();
        map.put("status", 0);
        return map;
    }


    @GetMapping("/r1/ai/chat")
    public JsonNode chat(@RequestParam("text") String text, @RequestHeader("r1-serial") String serial,
                         HttpServletResponse response) {
        try {
            log.info("r1 asr: {}", text);
            R1IotUtils.setCurrentDeviceId(serial);
            R1IotUtils.JSON_RET.set(R1IotUtils.sampleMusic());
            AiAssistant assistant = aidirect.getAssistants().get(serial);
            String answer = assistant.chat(text);
            JsonNode ret;
            if (answer != null) {
                ret = R1IotUtils.sampleChatResp(answer);
            } else {
                ret = R1IotUtils.JSON_RET.get();
            }
            response.setHeader("r1-sname", ret.get("service").asText());
            log.info("from ai: {}", ret.toString());
            
            // Fix host in response node
            try {
                String fixedJson = R1IotUtils.fixHost(R1IotUtils.getObjectMapper().writeValueAsString(ret), globalConfig.getHostIp());
                return R1IotUtils.getObjectMapper().readTree(fixedJson);
            } catch (Exception e) {
                log.error("Failed to fix host in chat response", e);
                return ret;
            }
        } finally {
            R1IotUtils.JSON_RET.remove();
        }

    }
}
