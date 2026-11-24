package org.example;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.common.ApiResponse;
import org.example.common.ResultCode;
import org.example.dto.ChatRequest;
import org.example.entity.Conversation;
import org.example.entity.User;
import org.example.mapper.ConversationMapper;
import org.example.mapper.UserMapper;
import org.example.service.ChatService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Slf4j 
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ObjectMapper objectMapper;
    private final ConversationMapper conversationMapper;
    private final UserMapper userMapper;

    public ChatController(ChatService chatService, ObjectMapper objectMapper,
                         ConversationMapper conversationMapper, UserMapper userMapper) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
        this.conversationMapper = conversationMapper;
        this.userMapper = userMapper;
    }

    /**
     * 从登录态取当前用户 id,未登录或用户不存在返回 null。
     */
    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, auth.getName()));
        return user == null ? null : user.getId();
    }

    /**
     * 构造一个 SSE error 事件。
     */
    private ServerSentEvent<String> errorEvent(String msg) {
        return ServerSentEvent.<String>builder().event("error").data(msg).build();
    }
    /**
     * 发送消息并获取 AI 回复。
     * body: { "conversationId": 1, "message": "你好" }
     */

    @PostMapping("noStream")
    public ResponseEntity<ApiResponse<Map<String, Object>>> chat(
        @RequestBody ChatRequest body) {

        String email = SecurityContextHolder.getContext().getAuthentication().getName();  
        String conversationIdStr = body.getConversationId();
        List <ChatRequest.Message> message = body.getMessages();
        String model = body.getModel();
        String provider = body.getProvider();

        if (message == null  || message.isEmpty()) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.BAD_REQUEST, "message 不能为空").toEntity();
        }

        Long conversationId;
        try {
            conversationId = Long.valueOf(conversationIdStr);
        } catch (NumberFormatException e) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.BAD_REQUEST, "conversationId 格式不正确").toEntity();
        }

        Long userId = currentUserId();
        if (userId == null) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.UNAUTHORIZED).toEntity();
        }
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.NOT_FOUND, "会话不存在").toEntity();
        }
        if (!userId.equals(conversation.getUserId())) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.FORBIDDEN, "无权访问该会话").toEntity();
        }

        String reply = chatService.chat(provider, model, conversationId, userId, message);

        Map<String, Object> data = new HashMap<>();
        data.put("conversationId", conversationId);
        data.put("reply", reply);
        data.put("email", email);   // 顺带把当前用户返回,方便前端确认
        return ApiResponse.ok(data).toEntity();
    }
    /**
     * 流式聊天:返回 Flux<ServerSentEvent<String>>,Spring MVC 自动转 SSE。
     * 事件格式:event: chunk / event: done / event: error
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest body) {
        String conversationIdStr = body.getConversationId();
        Long conversationId;
        try {
            conversationId = Long.valueOf(conversationIdStr);
        } catch (NumberFormatException e) {
            return Flux.just(errorEvent("conversationId 格式不正确"));
        }

        List<ChatRequest.Message> messages = body.getMessages();
        String model = body.getModel();
        String provider = body.getProvider();

        if (messages == null || messages.isEmpty()) {
            return Flux.just(errorEvent("messages 不能为空"));
        }

        Long userId = currentUserId();
        if (userId == null) {
            return Flux.just(errorEvent("未登录"));
        }
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            return Flux.just(errorEvent("会话不存在"));
        }
        if (!userId.equals(conversation.getUserId())) {
            return Flux.just(errorEvent("无权访问该会话"));
        }

        try {
            ServerSentEvent<String> status = ServerSentEvent.<String>builder()
                    .event("status").data("正在处理,如涉及工具调用可能需要数秒").build();
            return Flux.just(status).concatWith(chatService.chatStream(provider, model, conversationId, userId, messages)
                    .map(chunk -> {
                        try {
                            // JSON 编码:避免 SSE 协议吃掉前导空格和换行
                            String encoded = objectMapper.writeValueAsString(chunk);
                            return ServerSentEvent.<String>builder().event("chunk").data(encoded).build();
                        } catch (Exception e) {
                            return ServerSentEvent.<String>builder().event("error").data("编码失败").build();
                        }
                    })
                    // 正常结束:追加 done 事件
                    .concatWith(Flux.just(ServerSentEvent.<String>builder().event("done").data("").build()))
                    // 出错:发 error 事件
                    .onErrorResume(e -> Flux.just(ServerSentEvent.<String>builder().event("error").data(e.getMessage()).build())));
        } catch (Exception e) {
            return Flux.just(errorEvent("服务器错误: " + e.getMessage()));
        }
    }
}