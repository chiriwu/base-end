package org.example;

import java.util.List;
import java.util.Map;

import org.example.common.ApiResponse;
import org.example.common.ResultCode;
import org.example.entity.User;
import org.example.entity.UserMemory;
import org.example.mapper.UserMapper;
import org.example.service.MemoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryService memoryService;
    private final UserMapper userMapper;

    public MemoryController(MemoryService memoryService, UserMapper userMapper) {
        this.memoryService = memoryService;
        this.userMapper = userMapper;
    }

    /**
     * 列出当前用户的所有长期记忆(前端可展示"AI 记住了什么")
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<UserMemory>>> list() {
        Long userId = currentUserId();
        if (userId == null) {
            return ApiResponse.<List<UserMemory>>fail(ResultCode.UNAUTHORIZED).toEntity();
        }
        return ApiResponse.ok(memoryService.listByUser(userId)).toEntity();
    }

    /**
     * 删除一条记忆("忘记我"),校验归属权
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> delete(@PathVariable Long id) {
        Long userId = currentUserId();
        if (userId == null) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.UNAUTHORIZED).toEntity();
        }
        boolean ok = memoryService.delete(userId, id);
        if (!ok) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.NOT_FOUND, "记忆不存在或无权删除").toEntity();
        }
        return ApiResponse.ok(Map.<String, Object>of("deleted", true)).toEntity();
    }

    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, auth.getName()));
        return user == null ? null : user.getId();
    }
}
