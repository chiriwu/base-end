package org.example.service;

import java.io.File;
import java.util.List;

import org.example.config.VectorStoreConfig;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class RagService {

    private final SimpleVectorStore vectorStore;
    private final VectorStoreConfig vectorStoreConfig;
    private final TokenTextSplitter textSplitter;

    public RagService(SimpleVectorStore vectorStore, VectorStoreConfig vectorStoreConfig) {
        this.vectorStore = vectorStore;
        this.vectorStoreConfig = vectorStoreConfig;
        this.textSplitter = TokenTextSplitter.builder()
                .withChunkSize(512)
                .withMinChunkSizeChars(35)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(10000)
                .withKeepSeparator(false)
                .build();
    }

    public int loadFile(String filePath) {
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }
        log.info("开始加载文件: {}", file.getAbsolutePath());

        Resource resource = new FileSystemResource(file);
        TextReader reader = new TextReader(resource);
        List<Document> docs = reader.get();
        List<Document> chunks = textSplitter.apply(docs);
        for (Document chunk : chunks) {
            chunk.getMetadata().put("source", file.getAbsolutePath());
            chunk.getMetadata().put("filename", file.getName());
        }
        vectorStore.add(chunks);
        vectorStoreConfig.save(vectorStore);

        log.info("文件 {} 切分为 {} 块,已入库", file.getName(), chunks.size());
        return chunks.size();
    }

    public int loadText(String content, String sourceName) {
        Document doc = new Document(content);
        doc.getMetadata().put("source", sourceName);
        List<Document> chunks = textSplitter.apply(List.of(doc));
        for (Document chunk : chunks) {
            chunk.getMetadata().put("source", sourceName);
        }
        vectorStore.add(chunks);
        vectorStoreConfig.save(vectorStore);

        log.info("文本 [{}] 切分为 {} 块,已入库", sourceName, chunks.size());
        return chunks.size();
    }
}
