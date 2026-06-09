package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.service.MarkdownParserService;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Block;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
@Slf4j
public class MarkdownParserServiceImpl implements MarkdownParserService {

    private static final int MAX_SECTION_LENGTH = 2200;

    private static final Pattern LEGAL_BOUNDARY_PATTERN = Pattern.compile(
            "^\\s*((第[一二三四五六七八九十百千万0-9]+[章节条款].*)|(裁判要旨[:：]?.*)|(法院认为[:：]?.*)|(本院认为[:：]?.*)|(争议焦点[:：]?.*)|(合同条款[:：]?.*)|(法院观点[:：]?.*)|(\\d+(\\.\\d+)*[、.．]\\s*.+))\\s*$"
    );

    private final Parser parser;
    private String originalMarkdownContent;

    public MarkdownParserServiceImpl() {
        MutableDataSet options = new MutableDataSet();
        this.parser = Parser.builder(options).build();
    }

    @Override
    public List<MarkdownSection> parseMarkdown(InputStream inputStream) {
        try {
            // 读取文件内容
            originalMarkdownContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            
            // 解析 Markdown
            Document document = parser.parse(originalMarkdownContent);
            
            // 提取标题和内容
            List<MarkdownSection> sections = new ArrayList<>();
            extractSections(document, sections);
            sections = enrichStructuredSections(sections);
            
            log.info("解析 Markdown 完成，共提取 {} 个章节", sections.size());
            return sections;
        } catch (Exception e) {
            log.error("解析 Markdown 失败", e);
            throw new RuntimeException("解析 Markdown 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 提取标题和内容
     * 只遍历文档的直接子节点，遇到任何标题就停止收集当前标题的内容
     */
    private void extractSections(Document document, List<MarkdownSection> sections) {
        // 收集文档的所有直接子节点（顶层节点）
        List<Node> topLevelNodes = new ArrayList<>();
        Node child = document.getFirstChild();
        while (child != null) {
            topLevelNodes.add(child);
            child = child.getNext();
        }
        
        // 遍历顶层节点，找到所有标题
        int sectionIndex = 0;
        for (int i = 0; i < topLevelNodes.size(); i++) {
            Node node = topLevelNodes.get(i);
            
            if (node instanceof Heading) {
                Heading heading = (Heading) node;
                String title = extractHeadingText(heading);
                
                if (title == null || title.trim().isEmpty()) {
                    continue;
                }
                
                // 收集当前标题到下一个标题（任何级别）之间的所有内容
                StringBuilder contentBuilder = new StringBuilder();
                int charEnd = heading.getChars().getEndOffset();
                for (int j = i + 1; j < topLevelNodes.size(); j++) {
                    Node nextNode = topLevelNodes.get(j);
                    
                    // 如果遇到任何标题，停止收集
                    if (nextNode instanceof Heading) {
                        break;
                    }
                    charEnd = Math.max(charEnd, nextNode.getChars().getEndOffset());
                    
                    // 提取节点内容
                    String content = extractNodeContent(nextNode);
                    if (content != null && !content.trim().isEmpty()) {
                        if (contentBuilder.length() > 0) {
                            contentBuilder.append("\n");
                        }
                        contentBuilder.append(content);
                    }
                }
                
                String content = contentBuilder.toString().trim();
                sections.add(new MarkdownSection(
                        title,
                        content,
                        heading.getLevel(),
                        sectionIndex++,
                        heading.getChars().getStartOffset(),
                        charEnd
                ));
            }
        }
    }

    private List<MarkdownSection> enrichStructuredSections(List<MarkdownSection> sections) {
        List<MarkdownSection> baseSections = sections;
        if (baseSections.isEmpty() && originalMarkdownContent != null && !originalMarkdownContent.isBlank()) {
            baseSections = List.of(new MarkdownSection("全文", originalMarkdownContent, 1, 0, 0, originalMarkdownContent.length()));
        }

        List<MarkdownSection> structured = new ArrayList<>();
        int index = 0;
        for (MarkdownSection section : baseSections) {
            List<MarkdownSection> legalSections = splitLegalStructuredSection(section, index);
            for (MarkdownSection legalSection : legalSections) {
                List<MarkdownSection> lengthLimited = splitLongSection(legalSection, index);
                for (MarkdownSection limited : lengthLimited) {
                    limited.setSectionIndex(index++);
                    structured.add(limited);
                }
            }
        }
        return structured;
    }

    private List<MarkdownSection> splitLegalStructuredSection(MarkdownSection section, int startIndex) {
        String content = section.getContent() == null ? "" : section.getContent();
        List<Boundary> boundaries = findLegalBoundaries(content);
        if (boundaries.size() < 2) {
            return List.of(section);
        }

        List<MarkdownSection> result = new ArrayList<>();
        for (int i = 0; i < boundaries.size(); i++) {
            Boundary current = boundaries.get(i);
            int nextStart = i + 1 < boundaries.size() ? boundaries.get(i + 1).start() : content.length();
            String body = content.substring(current.end(), nextStart).trim();
            String title = section.getTitle() + " > " + current.title();
            result.add(new MarkdownSection(
                    title,
                    body,
                    Math.min((section.getHeadingLevel() == null ? 1 : section.getHeadingLevel()) + 1, 6),
                    startIndex + result.size(),
                    safeOffset(section.getCharStart()) + current.start(),
                    safeOffset(section.getCharStart()) + nextStart
            ));
        }
        return result;
    }

    private List<Boundary> findLegalBoundaries(String content) {
        List<Boundary> boundaries = new ArrayList<>();
        int cursor = 0;
        for (String line : content.split("\\R", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && LEGAL_BOUNDARY_PATTERN.matcher(trimmed).matches()) {
                boundaries.add(new Boundary(trimmed, cursor, cursor + line.length()));
            }
            cursor += line.length() + 1;
        }
        return boundaries;
    }

    private List<MarkdownSection> splitLongSection(MarkdownSection section, int startIndex) {
        String content = section.getContent() == null ? "" : section.getContent();
        if (content.length() <= MAX_SECTION_LENGTH) {
            return List.of(section);
        }

        List<MarkdownSection> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int partStart = 0;
        int offset = 0;
        for (String paragraph : content.split("\\n\\s*\\n", -1)) {
            String normalized = paragraph.trim();
            if (normalized.isEmpty()) {
                offset += paragraph.length() + 2;
                continue;
            }
            if (current.length() > 0 && current.length() + normalized.length() > MAX_SECTION_LENGTH) {
                result.add(buildPartSection(section, current.toString().trim(), result.size() + 1, startIndex + result.size(), partStart, offset));
                current.setLength(0);
                partStart = offset;
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(normalized);
            offset += paragraph.length() + 2;
        }
        if (current.length() > 0) {
            result.add(buildPartSection(section, current.toString().trim(), result.size() + 1, startIndex + result.size(), partStart, content.length()));
        }
        return result.isEmpty() ? List.of(section) : result;
    }

    private MarkdownSection buildPartSection(MarkdownSection source, String content, int part, int sectionIndex, int localStart, int localEnd) {
        return new MarkdownSection(
                source.getTitle() + " > part " + part,
                content,
                source.getHeadingLevel(),
                sectionIndex,
                safeOffset(source.getCharStart()) + localStart,
                safeOffset(source.getCharStart()) + localEnd
        );
    }

    private int safeOffset(Integer offset) {
        return offset == null ? 0 : offset;
    }

    private record Boundary(String title, int start, int end) {
    }

    /**
     * 提取标题文本
     */
    private String extractHeadingText(Heading heading) {
        StringBuilder text = new StringBuilder();
        Node child = heading.getFirstChild();
        while (child != null) {
            String childText = extractPlainText(child);
            if (childText != null && !childText.trim().isEmpty()) {
                if (text.length() > 0) {
                    text.append(" ");
                }
                text.append(childText);
            }
            child = child.getNext();
        }
        return text.toString().trim();
    }

    /**
     * 提取节点内容（保留格式，特别是表格）
     */
    private String extractNodeContent(Node node) {
        if (node == null) {
            return null;
        }
        
        // 对于表格，保留原始 Markdown 格式
        if (node instanceof TableBlock) {
            return extractTableMarkdown(node);
        }
        
        // 对于其他节点，提取文本内容
        return extractPlainText(node);
    }

    /**
     * 提取表格的原始 Markdown 格式
     */
    private String extractTableMarkdown(Node tableNode) {
        if (originalMarkdownContent == null) {
            return extractPlainText(tableNode);
        }
        
        try {
            // 获取表格节点在原始文档中的位置
            BasedSequence chars = tableNode.getChars();
            if (chars != null && chars.length() > 0) {
                int startOffset = chars.getStartOffset();
                int endOffset = chars.getEndOffset();
                
                // 从原始 Markdown 中提取表格内容
                if (startOffset >= 0 && endOffset <= originalMarkdownContent.length() && startOffset < endOffset) {
                    String tableMarkdown = originalMarkdownContent.substring(startOffset, endOffset);
                    return tableMarkdown.trim();
                }
            }
            
            // 如果无法从原始内容提取，尝试从节点本身提取
            return extractPlainText(tableNode);
        } catch (Exception e) {
            log.warn("提取表格 Markdown 失败，使用文本提取: {}", e.getMessage());
            return extractPlainText(tableNode);
        }
    }

    /**
     * 提取节点的纯文本内容
     */
    private String extractPlainText(Node node) {
        if (node == null) {
            return null;
        }
        
        StringBuilder text = new StringBuilder();
        extractTextRecursive(node, text);
        return text.length() > 0 ? text.toString().trim() : null;
    }

    /**
     * 递归提取文本
     */
    private void extractTextRecursive(Node node, StringBuilder text) {
        if (node == null) {
            return;
        }
        
        // 跳过标题节点（标题已经在 extractSections 中单独处理）
        if (node instanceof Heading) {
            return;
        }
        
        // 对于有子节点的节点，递归处理子节点
        Node child = node.getFirstChild();
        if (child != null) {
            boolean isFirstChild = true;
            while (child != null) {
                // 在子节点之间添加适当的分隔符
                if (!isFirstChild && text.length() > 0) {
                    // 检查是否需要换行
                    if (child instanceof Block) {
                        if (!text.toString().endsWith("\n")) {
                            text.append("\n");
                        }
                    } else {
                        text.append(" ");
                    }
                }
                extractTextRecursive(child, text);
                child = child.getNext();
                isFirstChild = false;
            }
        } else {
            // 叶子节点，尝试提取文本
            try {
                BasedSequence chars = node.getChars();
                if (chars != null && chars.length() > 0) {
                    String nodeText = chars.toString().trim();
                    if (!nodeText.isEmpty()) {
                        if (text.length() > 0 && !text.toString().endsWith("\n")) {
                            text.append(" ");
                        }
                        text.append(nodeText);
                    }
                }
            } catch (Exception e) {
                // 忽略，继续处理
            }
        }
    }
}
