package org.example.config;

import java.io.File;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class VectorStoreConfig {

    @Value("${vector-store.path:/app/data/vector-store.json}")
    private String storePath;

    @Bean
    public SimpleVectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        File file = new File(storePath);
        if (file.exists()) {
            try {
                store.load(file);
                log.info("已从 {} 加载向量库持久化状态", file.getAbsolutePath());
            } catch (Exception e) {
                log.warn("加载向量库持久化失败,将使用空库启动: {}", e.getMessage());
            }
        } else {
            log.info("向量库持久化文件不存在,使用空库启动");
        }
        return store;
    }

    public void save(SimpleVectorStore store) {
        try {
            File file = new File(storePath);
            store.save(file);
            log.info("向量库已持久化到 {}", file.getAbsolutePath());
        } catch (Exception e) {
            log.error("向量库持久化失败", e);
        }
    }
}
