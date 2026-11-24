package org.example;

import java.util.HashMap;
import java.util.Map;

import org.example.common.ApiResponse;
import org.example.common.ResultCode;
import org.example.service.RagService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/loadFile")
    public ApiResponse<Map<String, Object>> loadFile(@RequestBody Map<String, String> body) {
        String filePath = body.get("filePath");
        if (filePath == null || filePath.isBlank()) {
            return ApiResponse.fail(ResultCode.BAD_REQUEST, "filePath 不能为空");
        }
        try {
            int chunks = ragService.loadFile(filePath);
            Map<String, Object> data = new HashMap<>();
            data.put("chunks", chunks);
            data.put("filePath", filePath);
            return ApiResponse.ok(data);
        } catch (IllegalArgumentException e) {
            log.warn("加载文件失败(参数错误): {}", e.getMessage());
            return ApiResponse.fail(ResultCode.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("加载文件失败(服务器错误)", e);
            return ApiResponse.fail(ResultCode.INTERNAL_ERROR, "加载文件失败: " + e.getMessage());
        }
    }

    @PostMapping("/loadText")
    public ApiResponse<Map<String, Object>> loadText(@RequestBody Map<String, String> body) {
        String content = body.get("content");
        String sourceName = body.get("sourceName");
        if (content == null || content.isBlank()) {
            return ApiResponse.fail(ResultCode.BAD_REQUEST, "content 不能为空");
        }
        if (sourceName == null || sourceName.isBlank()) {
            sourceName = "anonymous-" + System.currentTimeMillis();
        }
        try {
            int chunks = ragService.loadText(content, sourceName);
            Map<String, Object> data = new HashMap<>();
            data.put("chunks", chunks);
            data.put("sourceName", sourceName);
            return ApiResponse.ok(data);
        } catch (Exception e) {
            log.error("加载文本失败", e);
            return ApiResponse.fail(ResultCode.INTERNAL_ERROR, "加载文本失败: " + e.getMessage());
        }
    }
}