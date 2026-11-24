package org.example.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

/**
 * OpenAI 兼容 API 的 ChatModel 手动配置。
 *
 * 不引入 starter 自动配置,避免与本地 Ollama 的 ChatModel 抢注入点,
 * 也保证未配置 API Key 时应用照常启动(此时只有 Ollama 一个 provider)。
 *
 * 开启方式: ai.api.enabled=true + ai.api.api-key=xxx
 * 适配对象: DeepSeek、通义千问 compatible-mode、以及其他 OpenAI 协议网关。
 *
 * 用 Holder 包装而不直接注册 ChatModel Bean,是为了不污染按类型注入 ChatModel 的地方
 * (MemoryService 等仍然注入 Ollama 模型)。
 */
@Slf4j
@Configuration
public class ApiChatModelConfig {

    /** API 侧模型的持有者,未开启时该 Bean 不存在 */
    public record ApiChatModelHolder(ChatModel chatModel) {
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai.api", name = "enabled", havingValue = "true")
    public ApiChatModelHolder apiChatModelHolder(
            @Value("${ai.api.base-url}") String baseUrl,
            @Value("${ai.api.api-key:}") String apiKey,
            @Value("${ai.api.model}") String model,
            @Value("${ai.api.temperature:0.7}") Double temperature) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "已开启 ai.api.enabled=true 但未配置 ai.api.api-key,请设置环境变量或在 application-local.properties 中配置");
        }

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .build())
                .build();

        log.info("OpenAI 兼容 API provider 已启用: baseUrl={}, model={}", baseUrl, model);
        return new ApiChatModelHolder(chatModel);
    }
}
