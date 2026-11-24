package org.example.config;

import java.time.Duration;
import java.util.List;

import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import lombok.extern.slf4j.Slf4j;

/**
 * MCP ToolCallbackProvider 手动配置。
 *
 * 禁用 Spring AI 自动配置(spring.ai.mcp.client.toolcallback.enabled=false)后,
 * 在此手工创建 ToolCallbackProvider Bean。整个创建过程用 try-catch 包裹,
 * MCP server 连接失败时返回空 provider,确保应用仍能正常启动。
 *
 * ChatService 通过 ObjectProvider<ToolCallbackProvider> 注入:
 *   - MCP server 在线: 走 defaultToolCallbacks 注册 MCP 工具
 *   - MCP server 离线: 拿到空 provider,走 warn 分支不带 MCP 工具启动
 */
@Slf4j
@Configuration
public class McpToolConfig implements DisposableBean {

    @Value("${spring.ai.mcp.client.stdio.connections.filesystem.command:cmd.exe}")
    private String command;

    @Value("${spring.ai.mcp.client.request-timeout:20s}")
    private Duration requestTimeout;

    private McpSyncClient mcpSyncClient;

    @Bean
    public ToolCallbackProvider mcpToolCallbackProvider() {
        try {
            List<String> args = List.of("/c", "npx", "-y",
                    "@modelcontextprotocol/server-filesystem",
                    "C:/Users/15321/Desktop");

            ServerParameters params = ServerParameters.builder(command)
                    .args(args)
                    .build();

            mcpSyncClient = McpClient.sync(
                            new StdioClientTransport(params))
                    .requestTimeout(requestTimeout)
                    .build();
            mcpSyncClient.initialize();

            log.info("MCP filesystem server 已连接, command={} args={}", command, args);
            return new SyncMcpToolCallbackProvider(List.of(mcpSyncClient));
        } catch (Exception e) {
            log.warn("MCP ToolCallbackProvider 创建失败,使用空 provider: {}", e.getMessage());
            return () -> new ToolCallback[0];
        }
    }

    @Override
    public void destroy() throws Exception {
        if (mcpSyncClient != null) {
            try {
                mcpSyncClient.close();
                log.info("MCP client 已关闭");
            } catch (Exception e) {
                log.warn("关闭 MCP client 失败: {}", e.getMessage());
            }
        }
    }
}