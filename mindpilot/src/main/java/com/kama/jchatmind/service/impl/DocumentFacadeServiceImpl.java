package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jchatmind.converter.DocumentConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.model.dto.DocumentDTO;
import com.kama.jchatmind.model.dto.RagSearchResultDTO;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.request.CreateDocumentRequest;
import com.kama.jchatmind.model.request.UpdateDocumentRequest;
import com.kama.jchatmind.model.response.CreateDocumentResponse;
import com.kama.jchatmind.model.response.GetDocumentsResponse;
import com.kama.jchatmind.model.vo.DocumentVO;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.DocumentFacadeService;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.ElasticsearchChunkService;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.RagService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@AllArgsConstructor
@Slf4j
public class DocumentFacadeServiceImpl implements DocumentFacadeService {

    private final DocumentMapper documentMapper;
    private final DocumentConverter documentConverter;
    private final DocumentStorageService documentStorageService;
    private final MarkdownParserService markdownParserService;
    private final RagService ragService;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final ObjectMapper objectMapper;
    private final ElasticsearchChunkService elasticsearchChunkService;

    @Override
    public GetDocumentsResponse getDocuments() {
        List<Document> documents = documentMapper.selectAll();
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public GetDocumentsResponse getDocumentsByKbId(String kbId) {
        List<Document> documents = documentMapper.selectByKbId(kbId);
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public CreateDocumentResponse createDocument(CreateDocumentRequest request) {
        try {
            // 将 CreateDocumentRequest 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(request);

            // 将 DocumentDTO 转换为 Document 实体
            Document document = documentConverter.toEntity(documentDTO);

            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，ID 由数据库自动生成
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档失败");
            }

            // 返回生成的 documentId
            return CreateDocumentResponse.builder()
                    .documentId(document.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw new BizException("创建文档时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public CreateDocumentResponse uploadDocument(String kbId, MultipartFile file) {
        try {
            if (file.isEmpty()) {
                throw new BizException("上传的文件为空");
            }

            // 提取文件信息
            String originalFilename = file.getOriginalFilename();
            String filetype = getFileType(originalFilename);
            long fileSize = file.getSize();

            // 创建文档记录（先创建记录，获取 documentId）
            DocumentDTO documentDTO = DocumentDTO.builder()
                    .kbId(kbId)
                    .filename(originalFilename)
                    .filetype(filetype)
                    .size(fileSize)
                    .build();

            Document document = documentConverter.toEntity(documentDTO);
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，获取生成的 documentId
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档记录失败");
            }

            String documentId = document.getId();

            // 保存文件
            String filePath = documentStorageService.saveFile(kbId, documentId, file);

            // 更新文档记录，保存文件路径到 metadata
            DocumentDTO.MetaData metadata = new DocumentDTO.MetaData();
            metadata.setFilePath(filePath);
            metadata.setParseStatus("PENDING");
            documentDTO.setMetadata(metadata);
            documentDTO.setId(documentId);
            documentDTO.setCreatedAt(now);
            documentDTO.setUpdatedAt(now);

            Document updatedDocument = documentConverter.toEntity(documentDTO);
            updatedDocument.setId(documentId);
            updatedDocument.setCreatedAt(now);
            updatedDocument.setUpdatedAt(now);

            documentMapper.updateById(updatedDocument);

            log.info("文档上传成功: kbId={}, documentId={}, filename={}", kbId, documentId, originalFilename);

            // 如果是 Markdown 文件，进行解析并生成 chunks
            if ("md".equalsIgnoreCase(filetype) || "markdown".equalsIgnoreCase(filetype)) {
                processMarkdownDocument(kbId, documentId, filePath);
            } else {
                log.warn("待新增处理的文件类型: {}", filetype);
            }

            return CreateDocumentResponse.builder()
                    .documentId(documentId)
                    .build();
        } catch (IOException e) {
            log.error("文件保存失败", e);
            throw new BizException("文件保存失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteDocument(String documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BizException("文档不存在: " + documentId);
        }

        // 删除文件
        try {
            DocumentDTO documentDTO = documentConverter.toDTO(document);
            if (documentDTO.getMetadata() != null && documentDTO.getMetadata().getFilePath() != null) {
                String filePath = documentDTO.getMetadata().getFilePath();
                documentStorageService.deleteFile(filePath);
            }
        } catch (Exception e) {
            log.warn("删除文件失败，继续删除文档记录: documentId={}, error={}", documentId, e.getMessage());
            // 即使文件删除失败，也继续删除数据库记录
        }

        // 删除数据库记录
        int result = documentMapper.deleteById(documentId);
        if (result <= 0) {
            throw new BizException("删除文档失败");
        }
    }

    /**
     * 处理 Markdown 文档，解析并生成 chunks
     */
    private void processMarkdownDocument(String kbId, String documentId, String filePath) {
        try {
            log.info("开始处理 Markdown 文档: kbId={}, documentId={}, filePath={}", kbId, documentId, filePath);

            // 从保存的文件路径读取文件
            Path path = documentStorageService.getFilePath(filePath);
            try (InputStream inputStream = Files.newInputStream(path)) {
                // 解析 Markdown 文件
                List<MarkdownParserService.MarkdownSection> sections = markdownParserService.parseMarkdown(inputStream);

                if (sections.isEmpty()) {
                    log.warn("Markdown 文档解析后没有找到任何章节: documentId={}", documentId);
                    return;
                }

                LocalDateTime now = LocalDateTime.now();
                int chunkCount = 0;

                List<String> sectionPaths = buildSectionPaths(sections);

                // 为每个章节生成带上下文增强信息的 chunk
                for (int sectionOffset = 0; sectionOffset < sections.size(); sectionOffset++) {
                    MarkdownParserService.MarkdownSection section = sections.get(sectionOffset);
                    String title = section.getTitle();
                    String content = section.getContent();

                    if (title == null || title.trim().isEmpty()) {
                        continue;
                    }

                    String sourceFileName = path.getFileName().toString();
                    String sectionPath = sectionPaths.get(sectionOffset);
                    String previousTitle = sectionOffset > 0 ? sections.get(sectionOffset - 1).getTitle() : null;
                    String nextTitle = sectionOffset < sections.size() - 1 ? sections.get(sectionOffset + 1).getTitle() : null;
                    String contextualSummary = buildContextualSummary(sourceFileName, section, sectionPath, previousTitle, nextTitle);

                    // Contextual Retrieval：把文档名、章节路径、相邻章节与摘要一起送入 embedding。
                    String embeddingText = buildEmbeddingText(contextualSummary, title, content);
                    float[] embedding = ragService.embed(embeddingText);

                    RagSearchResultDTO.ChunkMeta metadata = RagSearchResultDTO.ChunkMeta.builder()
                            .title(title)
                            .sectionPath(sectionPath)
                            .contextualSummary(contextualSummary)
                            .previousTitle(previousTitle)
                            .nextTitle(nextTitle)
                            .headingLevel(section.getHeadingLevel())
                            .chunkIndex(section.getSectionIndex())
                            .charStart(section.getCharStart())
                            .charEnd(section.getCharEnd())
                            .tokenCount(estimateTokenCount(embeddingText))
                            .sourceFileName(sourceFileName)
                            .build();

                    // 创建 ChunkBgeM3 实体
                    ChunkBgeM3 chunk = ChunkBgeM3.builder()
                            .kbId(kbId)
                            .docId(documentId)
                            .content(content != null ? content : "")
                            .metadata(objectMapper.writeValueAsString(metadata))
                            .embedding(embedding)
                            .createdAt(now)
                            .updatedAt(now)
                            .build();

                    // 插入数据库
                    int result = chunkBgeM3Mapper.insert(chunk);

                    if (result > 0) {
                        chunkCount++;
                        elasticsearchChunkService.indexChunk(
                                chunk.getId(),
                                kbId,
                                documentId,
                                sourceFileName,
                                content != null ? content : "",
                                metadata
                        );
                        log.debug("创建 chunk 成功: title={}, chunkId={}", title, chunk.getId());
                    } else {
                        log.warn("创建 chunk 失败: title={}", title);
                    }
                }
                updateDocumentParseMetadata(documentId, chunkCount, "SUCCESS");
                log.info("Markdown 文档处理完成: documentId={}, 共生成 {} 个 chunks", documentId, chunkCount);
            }
        } catch (Exception e) {
            log.error("处理 Markdown 文档失败: documentId={}", documentId, e);
            updateDocumentParseMetadata(documentId, null, "FAILED");
            // 不抛出异常，避免影响文档上传流程
        }
    }

    private int estimateTokenCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    private void updateDocumentParseMetadata(String documentId, Integer chunkCount, String parseStatus) {
        try {
            Document document = documentMapper.selectById(documentId);
            if (document == null) {
                return;
            }
            DocumentDTO documentDTO = documentConverter.toDTO(document);
            DocumentDTO.MetaData metadata = documentDTO.getMetadata();
            if (metadata == null) {
                metadata = new DocumentDTO.MetaData();
            }
            metadata.setParseStatus(parseStatus);
            metadata.setChunkCount(chunkCount);
            metadata.setParserVersion("markdown-legal-contextual-v3");
            metadata.setEmbeddingModel("bge-m3");
            documentDTO.setMetadata(metadata);
            Document updated = documentConverter.toEntity(documentDTO);
            updated.setId(documentId);
            updated.setCreatedAt(document.getCreatedAt());
            updated.setUpdatedAt(LocalDateTime.now());
            documentMapper.updateById(updated);
        } catch (Exception e) {
            log.warn("更新文档解析元数据失败: documentId={}, error={}", documentId, e.getMessage());
        }
    }

    /**
     * 从文件名提取文件类型
     */
    private String getFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    private List<String> buildSectionPaths(List<MarkdownParserService.MarkdownSection> sections) {
        List<String> paths = new ArrayList<>();
        Map<Integer, String> headingStack = new HashMap<>();
        for (MarkdownParserService.MarkdownSection section : sections) {
            int level = section.getHeadingLevel() == null ? 1 : section.getHeadingLevel();
            headingStack.put(level, section.getTitle());
            headingStack.keySet().removeIf(existingLevel -> existingLevel > level);

            List<String> pathParts = new ArrayList<>();
            for (int currentLevel = 1; currentLevel <= level; currentLevel++) {
                String title = headingStack.get(currentLevel);
                if (title != null && !title.isBlank()) {
                    pathParts.add(title);
                }
            }
            paths.add(String.join(" > ", pathParts));
        }
        return paths;
    }

    private String buildContextualSummary(
            String sourceFileName,
            MarkdownParserService.MarkdownSection section,
            String sectionPath,
            String previousTitle,
            String nextTitle
    ) {
        StringBuilder summary = new StringBuilder();
        summary.append("文档: ").append(sourceFileName).append("\n");
        summary.append("章节路径: ").append(sectionPath == null || sectionPath.isBlank() ? section.getTitle() : sectionPath).append("\n");
        if (previousTitle != null && !previousTitle.isBlank()) {
            summary.append("上一章节: ").append(previousTitle).append("\n");
        }
        if (nextTitle != null && !nextTitle.isBlank()) {
            summary.append("下一章节: ").append(nextTitle).append("\n");
        }
        summary.append("片段摘要: ").append(summarizeSection(section.getTitle(), section.getContent()));
        return summary.toString();
    }

    private String summarizeSection(String title, String content) {
        String normalizedContent = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (normalizedContent.isBlank()) {
            return title;
        }
        int maxLength = 220;
        String snippet = normalizedContent.length() <= maxLength
                ? normalizedContent
                : normalizedContent.substring(0, maxLength) + "...";
        return title + " - " + snippet;
    }

    private String buildEmbeddingText(String contextualSummary, String title, String content) {
        return ("""
                【上下文】
                %s

                【标题】
                %s

                【正文】
                %s
                """).formatted(
                contextualSummary == null ? "" : contextualSummary,
                title == null ? "" : title,
                content == null ? "" : content
        ).trim();
    }

    @Override
    public void updateDocument(String documentId, UpdateDocumentRequest request) {
        try {
            // 查询现有的文档
            Document existingDocument = documentMapper.selectById(documentId);
            if (existingDocument == null) {
                throw new BizException("文档不存在: " + documentId);
            }

            // 将现有 Document 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(existingDocument);

            // 使用 UpdateDocumentRequest 更新 DocumentDTO
            documentConverter.updateDTOFromRequest(documentDTO, request);

            // 将更新后的 DocumentDTO 转换回 Document 实体
            Document updatedDocument = documentConverter.toEntity(documentDTO);

            // 保留原有的 ID、kbId 和创建时间
            updatedDocument.setId(existingDocument.getId());
            updatedDocument.setKbId(existingDocument.getKbId());
            updatedDocument.setCreatedAt(existingDocument.getCreatedAt());
            updatedDocument.setUpdatedAt(LocalDateTime.now());

            // 更新数据库
            int result = documentMapper.updateById(updatedDocument);
            if (result <= 0) {
                throw new BizException("更新文档失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新文档时发生序列化错误: " + e.getMessage());
        }
    }
}
