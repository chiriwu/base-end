package org.example.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.example.entity.UserMemory;
import org.example.mapper.UserMemoryMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MemoryService {

    private static final int MAX_MEMORIES = 30;
    private static final int MAX_CONTENT_LEN = 50;
    private static final int MAX_FACTS_PER_EXTRACT = 5;

    private final UserMemoryMapper memoryMapper;
    private final ChatClient extractClient;
    private final ObjectMapper objectMapper;
    private final String extractModel;

    private final ExecutorService extractExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "memory-extract");
        t.setDaemon(true);
        return t;
    });

    public MemoryService(UserMemoryMapper memoryMapper, ChatModel chatModel, ObjectMapper objectMapper,
                         @Value("${memory.extract-model:}") String extractModel) {
        this.memoryMapper = memoryMapper;
        this.objectMapper = objectMapper;
        this.extractModel = extractModel;
        this.extractClient = ChatClient.builder(chatModel).build();
    }

    /**
     * 异步提取:回复完成后由 ChatService 触发,失败静默降级,不影响主流程
     */
    public void extractAndSaveAsync(Long userId, String userMsg, String assistantReply) {
        extractExecutor.execute(() -> {
            try {
                extractAndSave(userId, userMsg, assistantReply);
            } catch (Exception e) {
                log.warn("记忆提取失败(静默降级): {}", e.getMessage());
            }
        });
    }

    private void extractAndSave(Long userId, String userMsg, String assistantReply) {
        String prompt = "从下面的对话中提取关于用户的持久信息(所在城市、称呼、偏好、长期目标等)。\n"
                + "规则:\n"
                + "1. 每条信息一句话,不超过50字\n"
                + "2. 只提取持久有用的事实,忽略一次性内容(如某天的天气)\n"
                + "3. 输出 JSON 字符串数组,例如 [\"用户位于上海\",\"用户希望回答简短\"]\n"
                + "4. 没有可提取的信息时输出 []\n"
                + "对话:\n"
                + "用户: " + truncate(userMsg, 500) + "\n"
                + "助手: " + truncate(assistantReply, 500) + "\n"
                + "只输出 JSON 数组,不要输出其他内容。";

        String raw;
        if (extractModel != null && !extractModel.isBlank()) {
            raw = extractClient.prompt().user(prompt)
                    .options(OllamaOptions.builder().model(extractModel).build())
                    .call().content();
        } else {
            raw = extractClient.prompt().user(prompt).call().content();
        }

        List<String> facts = parseFacts(raw);
        if (facts.isEmpty()) {
            log.info("记忆提取:无可提取信息");
            return;
        }
        int saved = 0;
        for (String fact : facts) {
            if (saveMemory(userId, fact)) saved++;
        }
        log.info("记忆提取完成:提取 {} 条,新增 {} 条", facts.size(), saved);
    }

    /**
     * 解析模型输出:剥代码围栏、截取 JSON 数组、逐条校验,失败返回空列表(宁缺毋滥)
     */
    private List<String> parseFacts(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String json = raw.trim();
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end <= start) return List.of();
        json = json.substring(start, end + 1);
        try {
            List<String> items = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            List<String> result = new ArrayList<>();
            for (String item : items) {
                if (item != null && !item.isBlank() && item.length() <= MAX_CONTENT_LEN) {
                    result.add(item.trim());
                }
                if (result.size() >= MAX_FACTS_PER_EXTRACT) break;
            }
            return result;
        } catch (Exception e) {
            log.warn("记忆提取 JSON 解析失败,放弃本次: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 保存单条记忆:完全匹配去重 + 每用户上限 30 条(超出删最旧),返回是否新增
     */
    public boolean saveMemory(Long userId, String content) {
        if (content == null || content.isBlank() || userId == null) return false;
        String c = content.trim();
        if (c.length() > MAX_CONTENT_LEN) c = c.substring(0, MAX_CONTENT_LEN);
        Long dup = memoryMapper.selectCount(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getContent, c));
        if (dup != null && dup > 0) return false;
        Long count = memoryMapper.selectCount(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId));
        if (count != null && count >= MAX_MEMORIES) {
            UserMemory oldest = memoryMapper.selectOne(new LambdaQueryWrapper<UserMemory>()
                    .eq(UserMemory::getUserId, userId)
                    .orderByAsc(UserMemory::getCreateTime)
                    .last("LIMIT 1"));
            if (oldest != null) memoryMapper.deleteById(oldest.getId());
        }
        UserMemory m = new UserMemory();
        m.setUserId(userId);
        m.setContent(c);
        m.setCategory("fact");
        m.setCreateTime(LocalDateTime.now());
        m.setUpdateTime(LocalDateTime.now());
        memoryMapper.insert(m);
        return true;
    }

    /**
     * 生成用户画像注入 prompt,无记忆时返回空串
     */
    public String profilePrompt(Long userId) {
        List<UserMemory> memories = memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .orderByAsc(UserMemory::getId));
        if (memories.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("以下是已知的用户信息,回答时可参考:\n");
        for (UserMemory m : memories) {
            sb.append("- ").append(m.getContent()).append("\n");
        }
        return sb.toString();
    }

    public List<UserMemory> listByUser(Long userId) {
        return memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .orderByDesc(UserMemory::getId));
    }

    /**
     * 删除一条记忆,校验归属权防止越权删除他人记忆
     */
    public boolean delete(Long userId, Long id) {
        UserMemory m = memoryMapper.selectById(id);
        if (m == null || !userId.equals(m.getUserId())) return false;
        memoryMapper.deleteById(id);
        return true;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
