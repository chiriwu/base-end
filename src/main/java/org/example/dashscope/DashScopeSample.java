package org.example.dashscope;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.alibaba.dashscope.utils.Constants;

/**
 * 千问 DashScope 调用样例(私有 MaaS 网关)。
 *
 * 用途:先单独验证 API Key、网关地址、模型名是否可用,再决定是否接入聊天主链路。
 *
 * 运行方式:
 *   1) IDEA 中直接运行 main 方法
 *   2) 命令行:
 *      mvn exec:java "-Dexec.mainClass=org.example.dashscope.DashScopeSample"
 *
 * 配置来源(优先级从高到低):
 *   1) 系统属性  -Ddashscope.api-key=...
 *   2) 项目根目录 application-local.properties 中的 dashscope.* 配置
 *   3) 环境变量  DASHSCOPE_API_KEY / DASHSCOPE_BASE_URL / DASHSCOPE_MODEL
 *   4) 类内默认值
 */
public class DashScopeSample {

    private static final String DEFAULT_BASE_URL = "https://cn-beijing.maas.qianwenaiapi.com/api/v1";
    private static final String DEFAULT_MODEL = "qwen3.8-flash";
    private static final String DEFAULT_IMAGE =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241022/emyrja/dog_and_girl.jpeg";

    /** 项目根目录的本地配置文件(已在 .gitignore 中) */
    private static final Properties LOCAL_CONFIG = loadLocalConfig();

    static {
        // DashScope SDK 通过这个静态字段切换网关地址,必须在任何调用前设置
        Constants.baseHttpApiUrl = readConfig("dashscope.base-url", "DASHSCOPE_BASE_URL", DEFAULT_BASE_URL);
    }

    public static void main(String[] args) {
        String apiKey = readConfig("dashscope.api-key", "DASHSCOPE_API_KEY", "");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("未配置 API Key:请在 application-local.properties 中设置 dashscope.api-key=sk-xxx");
            return;
        }
        String imageUrl = args.length > 0 ? args[0] : DEFAULT_IMAGE;
        String question = args.length > 1 ? args[1] : "图中描绘的是什么景象?";
        try {
            System.out.println(simpleMultiModalConversationCall(apiKey, imageUrl, question));
        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            System.out.println("调用失败: " + e.getMessage());
        }
    }

    /** 多模态对话:图片 + 问题 */
    public static String simpleMultiModalConversationCall(String apiKey, String imageUrl, String question)
            throws ApiException, NoApiKeyException, UploadFileException {
        MultiModalConversation conv = new MultiModalConversation();
        MultiModalMessage userMessage = MultiModalMessage.builder()
                .role(Role.USER.getValue())
                .content(Arrays.asList(
                        Collections.singletonMap("image", imageUrl),
                        Collections.singletonMap("text", question)))
                .build();

        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .apiKey(apiKey)
                .model(readConfig("dashscope.model", "DASHSCOPE_MODEL", DEFAULT_MODEL))
                .messages(Arrays.asList(userMessage))
                .build();

        MultiModalConversationResult result = conv.call(param);
        Object text = result.getOutput().getChoices().get(0).getMessage().getContent().get(0).get("text");
        return text == null ? "" : text.toString();
    }

    private static String readConfig(String sysProp, String envName, String defaultValue) {
        String value = System.getProperty(sysProp);
        if (isBlank(value)) {
            value = LOCAL_CONFIG.getProperty(sysProp);
        }
        if (isBlank(value)) {
            value = System.getenv(envName);
        }
        return isBlank(value) ? defaultValue : value;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 读取项目根目录的 application-local.properties,不存在时返回空配置 */
    private static Properties loadLocalConfig() {
        Properties props = new Properties();
        Path path = Path.of("application-local.properties");
        if (!Files.exists(path)) {
            System.out.println("未找到 " + path.toAbsolutePath() + ",将回退到环境变量/默认值");
            return props;
        }
        try (InputStreamReader reader = new InputStreamReader(
                Files.newInputStream(path), StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            System.out.println("读取 " + path.toAbsolutePath() + " 失败: " + e.getMessage());
        }
        return props;
    }
}
