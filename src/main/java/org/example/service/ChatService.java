package org.example.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.example.config.ApiChatModelConfig;
import org.example.dto.ChatRequest;
import org.example.entity.Conversation;
import org.example.mapper.ConversationMapper;
import org.example.mapper.MessageMapper;
import org.example.skill.SkillService;
import org.example.skill.SkillTools;
import org.example.tool.WeatherTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor; 
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class ChatService {

    /** 默认 provider:本地 Ollama */
    private static final String PROVIDER_OLLAMA = "ollama";
    /** API provider:OpenAI 兼容接口 */
    private static final String PROVIDER_API = "api";

    private final ChatClient ollamaClient;
    /** 未开启 ai.api.enabled 时为 null */
    private final ChatClient apiClient;
    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final SimpleVectorStore vectorStore;
    private final MemoryService memoryService;

    /** Agent 循环最大轮数,默认 3。工具调用后模型不产出文本时自动追加收尾指令重试 */
    @Value("${agent.max-iterations:3}")
    private int maxIterations;

    /** 最后一轮降级模型,默认空(不降级)。3B 模型连续失败时切大模型兜底 */
    @Value("${agent.fallback-model:}")
    private String fallbackModel;

    /** 多轮全失败时返回给前端的兜底文案 */
    private static final String FALLBACK_REPLY =
            "抱歉,处理过程中未能生成有效回复,请重试或换一种问法。";

    public ChatService(ChatModel chatModel,
                       MessageMapper messageMapper,
                       ConversationMapper conversationMapper,
                       ObjectProvider<ToolCallbackProvider> toolCallbackProviderProvider,
                       WeatherTools weatherTools,
                       SimpleVectorStore vectorStore,
                       SkillService skillService,
                       MemoryService memoryService,
                       ObjectProvider<ApiChatModelConfig.ApiChatModelHolder> apiModelProvider) {
        // 本地 Ollama 客户端(默认 provider)
        this.ollamaClient = buildClient(chatModel, skillService, weatherTools,
                toolCallbackProviderProvider.getIfAvailable());
        // API 客户端:未配置 ai.api.enabled=true 时为 null,此时 api 请求自动回退 Ollama
        ApiChatModelConfig.ApiChatModelHolder apiHolder = apiModelProvider.getIfAvailable();
        this.apiClient = apiHolder == null ? null
                : buildClient(apiHolder.chatModel(), skillService, weatherTools,
                        toolCallbackProviderProvider.getIfAvailable());
        log.info("AI provider 就绪: ollama=已启用, api={}", this.apiClient == null ? "未配置" : "已启用");

        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.vectorStore = vectorStore;
        this.memoryService = memoryService;
    }

    /**
     * 用同一套技能目录 + 工具装配 ChatClient,供不同 provider 复用,
     * 保证切换 provider 时工具能力一致。
     */
    private ChatClient buildClient(ChatModel chatModel, SkillService skillService,
                                   WeatherTools weatherTools, ToolCallbackProvider provider) {
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        try {
            // Level 1:技能目录(name+description)注入 system prompt
            String catalog = skillService.catalogPrompt();
            if (!catalog.isEmpty()) {
                builder.defaultSystem(catalog);
            }

            // 自定义 @Tool 注解工具(走 defaultTools)
            builder.defaultTools(weatherTools, new SkillTools(skillService));

            // MCP 工具回调(走 defaultToolCallbacks,与上面 defaultTools 不冲突)
            if (provider != null) {
                org.springframework.ai.tool.ToolCallback[] callbacks = provider.getToolCallbacks();
                if (callbacks != null && callbacks.length > 0) {
                    builder.defaultToolCallbacks(callbacks);
                    log.info("已注入 MCP 工具回调,数量: {}", callbacks.length);
                } else {
                    log.warn("MCP ToolCallbackProvider 存在但工具列表为空,该 ChatClient 不带 MCP 工具");
                }
            } else {
                log.warn("未发现 MCP ToolCallbackProvider,该 ChatClient 不带 MCP 工具");
            }
        } catch (Throwable e) {
            log.error("注册工具/Advisor 失败,该 ChatClient 将不带工具启动", e);
        }
        return builder.build();
    }

    /**
     * 解析请求中的 provider。api/openai 走 API 客户端;
     * 未配置 API 时回退 Ollama,避免前端切换后直接报错。
     */
    private ChatClient resolveClient(String provider) {
        if (provider == null || provider.isBlank()) {
            return ollamaClient;
        }
        String p = provider.trim().toLowerCase();
        if (PROVIDER_API.equals(p) || "openai".equals(p)) {
            if (apiClient != null) {
                return apiClient;
            }
            log.warn("请求 provider={} 但 API 模型未配置(ai.api.enabled),回退到 Ollama", provider);
        } else if (!PROVIDER_OLLAMA.equals(p)) {
            log.warn("未知 provider={},回退到 Ollama", provider);
        }
        return ollamaClient;
    }

    /** 是否走 API provider(决定模型覆盖参数类型与降级模型是否生效) */
    private boolean isApiProvider(String provider) {
        if (provider == null || provider.isBlank() || apiClient == null) {
            return false;
        }
        String p = provider.trim().toLowerCase();
        return PROVIDER_API.equals(p) || "openai".equals(p);
    }

    /**
     * 检索 RAG 相关上下文,失败时打 warn 返回空串(降级,不影响主流程)。
     * 从 messages 里取最后一条 user 消息作为查询。
     */
    private String retrieveRagContext(List<ChatRequest.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        String query = messages.get(messages.size() - 1).getContent();
        if (query == null || query.isBlank()) {
            return "";
        }
        try {
            List<org.springframework.ai.document.Document> docs =
                    vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(5).build());
            if (docs == null || docs.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("以下是从知识库检索到的相关内容,供参考:\n\n");
            for (int i = 0; i < docs.size(); i++) {
                sb.append("--- 片段 ").append(i + 1).append(" ---\n")
                  .append(docs.get(i).getText()).append("\n\n");
            }
            log.info("RAG 检索成功,命中 {} 个片段", docs.size());
            return sb.toString();
        } catch (Exception e) {
            log.warn("RAG 检索失败,跳过知识库增强: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 刷新会话的 update_time(每轮对话更新一次)
     */
    private void touchConversation(Long conversationId) {
        if (conversationId == null) return;
        Conversation update = new Conversation();
        update.setId(conversationId);
        update.setUpdateTime(LocalDateTime.now());
        conversationMapper.updateById(update);
    }

    /**
     * 查数据库获取会话历史消息,只取最近 10 条,转成 Spring AI 的 Message 格式。
     * 按时间倒序查再反转,保证最早的在前面。
     */
    private List<org.springframework.ai.chat.messages.Message> loadHistory(Long conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        List<org.example.entity.Message> records = messageMapper.selectList(
                new LambdaQueryWrapper<org.example.entity.Message>()
                        .eq(org.example.entity.Message::getConversationId, conversationId)
                        .orderByDesc(org.example.entity.Message::getCreateTime)
                        .last("LIMIT 10"));
        Collections.reverse(records);
        List<org.springframework.ai.chat.messages.Message> history = new ArrayList<>();
        for (org.example.entity.Message m : records) {
            history.add(toSpringMessage(m.getRole(), m.getContent()));
        }
        return history;
    }

    /**
     * 将 role + content 转成 Spring AI 的 Message 类型
     */
    private org.springframework.ai.chat.messages.Message toSpringMessage(String role, String content) {
        return switch (role) {
            case "user" -> new UserMessage(content);
            case "assistant" -> new AssistantMessage(content);
            case "system" -> new SystemMessage(content);
            default -> new UserMessage(content);
        };
    }

    /**
     * 保存用户本次发送的消息(调用 Ollama 之前)
     */
    private void saveUserMessages(Long conversationId, List<ChatRequest.Message> messages) {
        if (conversationId == null) return;
        for (ChatRequest.Message m : messages) {
            org.example.entity.Message record = new org.example.entity.Message();
            record.setConversationId(conversationId);
            record.setRole(m.getRole());
            record.setContent(m.getContent());
            record.setCreateTime(LocalDateTime.now());
            messageMapper.insert(record);
        }
    }

    /**
     * 保存 AI 的完整回复(流式结束或非流式拿到结果后)
     */
    private void saveAssistantReply(Long conversationId, String content) {
        if (conversationId == null || content == null || content.isEmpty()) return;
        org.example.entity.Message record = new org.example.entity.Message();
        record.setConversationId(conversationId);
        record.setRole("assistant");
        record.setContent(content);
        record.setCreateTime(LocalDateTime.now());
        messageMapper.insert(record);
    }

    /**
     * 合并用户画像 + 历史消息 + 本次新消息
     */
    private List<org.springframework.ai.chat.messages.Message> mergeMessages(
            Long conversationId, Long userId, List<ChatRequest.Message> newMessages) {
        List<org.springframework.ai.chat.messages.Message> all = new ArrayList<>();
        String profile = (userId == null) ? "" : memoryService.profilePrompt(userId);
        if (!profile.isEmpty()) {
            all.add(new SystemMessage(profile));
        }
        all.addAll(loadHistory(conversationId));
        for (ChatRequest.Message m : newMessages) {
            all.add(toSpringMessage(m.getRole(), m.getContent()));
        }
        return all;
    }

    /**
     * 异步触发记忆提取,失败静默降级,不影响主流程
     */
    private void triggerMemoryExtraction(Long userId, List<ChatRequest.Message> messages, String reply) {
        if (userId == null || messages == null || messages.isEmpty()) return;
        String userMsg = messages.get(messages.size() - 1).getContent();
        if (userMsg == null || userMsg.isBlank() || reply == null || reply.isBlank()) return;
        memoryService.extractAndSaveAsync(userId, userMsg, reply);
    }

    /** 非流式:一次性返回完整回复。model 为 null 时用配置文件默认值;provider 为 null 时走 Ollama */
    public String chat(String provider, String model, Long conversationId, Long userId, List<ChatRequest.Message> messages) {
        ChatClient client = resolveClient(provider);
        boolean apiProvider = isApiProvider(provider);
        log.info("非流式请求: provider={}, model={}", apiProvider ? PROVIDER_API : PROVIDER_OLLAMA, model);

        List<org.springframework.ai.chat.messages.Message> allMessages = mergeMessages(conversationId, userId, messages);
        saveUserMessages(conversationId, messages);
        touchConversation(conversationId);

        // === 显式 Agent 循环 ===
        // ChatClient.call() 内部自动执行工具调用,外层循环控制最大轮数并对空回复做收尾恢复。
        String reply = "";
        List<org.springframework.ai.chat.messages.Message> workingMessages = new ArrayList<>(allMessages);
        String ragContext = retrieveRagContext(messages);
        if (!ragContext.isEmpty()) {
            workingMessages.add(0, new SystemMessage(ragContext));
        }
        for (int i = 1; i <= maxIterations; i++) {
            var promptSpec = client.prompt().messages(workingMessages);
            if (model != null && !model.isBlank()) {
                promptSpec = promptSpec.options(buildModelOptions(apiProvider, model));
            }
            var chatResponse = promptSpec.call().chatResponse();
            reply = (chatResponse == null || chatResponse.getResult() == null)
                    ? "" : chatResponse.getResult().getOutput().getText();
            log.info("Agent 第 {}/{} 轮输出: text=[{}], toolCalls={}", i, maxIterations, reply,
                    (chatResponse == null || chatResponse.getResult() == null)
                            ? List.of() : chatResponse.getResult().getOutput().getToolCalls());

            if (reply != null && !reply.isBlank()) {
                break; // 拿到有效文本,结束循环
            }

            // 空回复:模型在工具调用后未产出文本,追加收尾指令继续循环
            if (i < maxIterations) {
                log.warn("第 {} 轮未产出文本,追加收尾指令继续循环", i);
                workingMessages.add(new UserMessage(
                        "(系统:工具调用已完成,请根据以上结果直接用文本回答用户问题,不要再调用工具)"));
            }
        }

        if (reply == null || reply.isBlank()) {
            log.error("Agent 达到最大轮数 {} 仍未产出有效文本,返回兜底回复", maxIterations);
            reply = "抱歉,处理过程中未能生成有效回复,请重试或换一种问法。";
        }

        saveAssistantReply(conversationId, reply);
        triggerMemoryExtraction(userId, messages, reply);
        return reply;
    }

    /**
     * 流式:返回 Flux<String>,每个元素是一个增量片段。
     * model 为 null 时用配置文件默认值。
     */
    public Flux<String> chatStream(String provider, String model, Long conversationId, Long userId, List<ChatRequest.Message> messages) {
        boolean apiProvider = isApiProvider(provider);
        log.info("流式请求: provider={}, model={}", apiProvider ? PROVIDER_API : PROVIDER_OLLAMA, model);

        List<org.springframework.ai.chat.messages.Message> allMessages = mergeMessages(conversationId, userId, messages);
        saveUserMessages(conversationId, messages);
        touchConversation(conversationId);

        StringBuilder fullReply = new StringBuilder();
        AtomicBoolean saved = new AtomicBoolean(false);

        Runnable saveReply = () -> {
            if (saved.compareAndSet(false, true)) {
                saveAssistantReply(conversationId, fullReply.toString());
                triggerMemoryExtraction(userId, messages, fullReply.toString());
            }
        };

        String ragContext = retrieveRagContext(messages);
        List<org.springframework.ai.chat.messages.Message> streamMessages = new ArrayList<>(allMessages);
        if (!ragContext.isEmpty()) {
            streamMessages.add(0, new SystemMessage(ragContext));
        }

        return agentStream(streamMessages, model, apiProvider, 1)
                .doOnNext(fullReply::append)
                .doOnComplete(saveReply)
                .doOnCancel(saveReply)
                .doOnError(e -> {
                    log.error("流式响应异常: {}", e.getMessage());
                    saveReply.run();
                });
    }

    /**
     * 流式 Agent 循环:通过 switchIfEmpty 衔接多轮。
     * 空响应或抛异常都转空流触发下一轮;达到最大轮数仍失败时返回兜底文案。
     * 最后一轮若配置了降级模型且与当前模型不同,自动切换到降级模型。
     */
    private Flux<String> agentStream(List<org.springframework.ai.chat.messages.Message> messages,
                                     String model, boolean apiProvider, int round) {
        String effectiveModel = model;
        // 降级模型只对 Ollama 生效(agent.fallback-model 配置的是本地模型名)
        if (!apiProvider && round >= maxIterations && fallbackModel != null && !fallbackModel.isBlank()
                && !fallbackModel.equals(model)) {
            log.info("Agent 第 {}/{} 轮切换降级模型: {} -> {}",
                    round, maxIterations, model, fallbackModel);
            effectiveModel = fallbackModel;
        }

        Flux<String> roundStream = buildRoundStream(messages, effectiveModel, apiProvider)
                .onErrorResume(e -> {
                    log.warn("Agent 第 {}/{} 轮异常,转空流重试: {}",
                            round, maxIterations, e.getMessage());
                    return Flux.empty();
                });

        if (round >= maxIterations) {
            // 最后一轮:空或异常都返回兜底文案,不再重试
            return roundStream.switchIfEmpty(Mono.just(FALLBACK_REPLY).flux());
        }

        // 非最后一轮:把收尾指令合并到最后一条 user 消息,避免连续 user 角色触发 Ollama 400
        return roundStream.switchIfEmpty(Flux.defer(() -> {
            log.warn("Agent 第 {}/{} 轮未产出文本,追加收尾指令继续循环", round, maxIterations);
            appendClosingInstruction(messages);
            return agentStream(messages, model, apiProvider, round + 1);
        }));
    }

    /**
     * 将收尾指令追加到最后一条 user 消息的文本末尾,保持角色交替。
     * 不新增 UserMessage,避免连续 user 角色导致 Ollama 返回 400。
     */
    private void appendClosingInstruction(List<org.springframework.ai.chat.messages.Message> messages) {
        String instruction = "(系统:工具调用已完成,请根据以上结果直接用文本回答用户问题,不要再调用工具)";
        for (int i = messages.size() - 1; i >= 0; i--) {
            org.springframework.ai.chat.messages.Message m = messages.get(i);
            if (m instanceof UserMessage) {
                String original = m.getText() != null ? m.getText() : "";
                messages.set(i, new UserMessage(original + "\n\n" + instruction));
                return;
            }
        }
        // 兜底:没有 user 消息时才追加新消息
        messages.add(new UserMessage(instruction));
    }

    /** 构建单轮流式输出:装配消息 + 可选模型覆盖(按 provider 选择对应协议参数) */
    private Flux<String> buildRoundStream(List<org.springframework.ai.chat.messages.Message> messages,
                                          String model, boolean apiProvider) {
        ChatClient client = apiProvider ? apiClient : ollamaClient;
        var promptSpec = client.prompt().messages(messages);
        if (model != null && !model.isBlank()) {
            promptSpec = promptSpec.options(buildModelOptions(apiProvider, model));
        }
        return promptSpec.stream().content();
    }

    /**
     * 按 provider 构造模型覆盖参数。Ollama 与 OpenAI 兼容协议的 Options 类型不通用,
     * 混用会导致参数被丢弃或序列化失败。
     */
    private org.springframework.ai.chat.prompt.ChatOptions buildModelOptions(boolean apiProvider, String model) {
        return apiProvider
                ? OpenAiChatOptions.builder().model(model).build()
                : OllamaOptions.builder().model(model).build();
    }

    /** 预热 Ollama 模型:加载到内存/显存,避免首次调用冷启动 */
    public void warmup() {
        try {
            ollamaClient.prompt()
                    .user("hi")
                    .call()
                    .content();
            log.info("Ollama 预热完成");
        } catch (Exception e) {
            log.warn("Ollama 预热失败,启动继续: {}", e.getMessage());
        }
    }
}
