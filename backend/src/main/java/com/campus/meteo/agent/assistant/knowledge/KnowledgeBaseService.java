package com.campus.meteo.agent.assistant.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.meteo.entity.ServiceArticle;
import com.campus.meteo.mapper.ServiceArticleMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * 决策智能体知识库：把「气象服务」已发布文章切块向量化，供助手检索并引用原文
 *
 * 解决的是「模型只有观测数据、没有规范常识」的问题——问「大风黄色预警是什么标准」
 * 「暴雨天校园要注意什么」时，实时/历史/预报工具都答不上来，只能靠模型自己的记忆，
 * 而这恰恰是最容易编错、也最需要可溯源的场景。
 *
 * 设计取舍：
 * 1. 语料只取 publish_status=1 的文章——草稿与下架内容不该被引用；
 * 2. 全量重建而非增量维护：校园知识库只有数十篇，全量重建逻辑简单且不会留下删除残留；
 * 3. 检索以工具形式暴露给模型（模型自行决定是否检索），而不是每轮强制注入上下文，
 *    这样「查了没有」在对话里是可见的，也避免无关问答被塞入噪声；
 * 4. 向量化是外部依赖，失败只让知识库标记为未就绪，绝不影响助手其它工具与系统其它功能。
 */
@Slf4j
@Service
public class KnowledgeBaseService {

    /** 文档元数据键 */
    static final String META_TITLE = "title";
    static final String META_CATEGORY = "category";

    /** 分类编码 → 中文名，与 service_article.category 口径一致 */
    private static final Map<Integer, String> CATEGORY_NAMES =
            Map.of(1, "农业气象", 2, "旅游气象", 3, "出行指数", 4, "科普");

    /** 富文本标签与空行：知识库只要文字，标签混进向量会污染语义 */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{2,}");

    /** 单批提交的片段数：厂商对 embedding 批量大小有限制（常见 10~25），分批提交更稳 */
    private static final int EMBED_BATCH_SIZE = 10;

    private final ServiceArticleMapper articleMapper;
    private final VectorStore vectorStore;

    @Value("${meteo.rag.enabled:true}")
    private boolean enabled;
    @Value("${meteo.rag.top-k:4}")
    private int topK;
    @Value("${meteo.rag.min-score:0.35}")
    private double minScore;
    @Value("${meteo.rag.max-chunk-chars:400}")
    private int maxChunkChars;

    /** 已入库的片段 ID，重建前据此清空旧索引 */
    private volatile List<String> indexedIds = List.of();
    /** 索引是否就绪：未就绪时检索返回空，由工具如实告知「未检索到依据」 */
    private volatile boolean ready;

    /** 文章变更后的重建串行执行，避免并发重建把索引写乱 */
    private final ExecutorService rebuildExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "kb-rebuild");
        thread.setDaemon(true);
        return thread;
    });

    public KnowledgeBaseService(ServiceArticleMapper articleMapper, VectorStore vectorStore) {
        this.articleMapper = articleMapper;
        this.vectorStore = vectorStore;
    }

    /** 应用启动后建索引：放在 ApplicationReady 而非构造阶段，避免拖慢启动关键路径 */
    @EventListener(ApplicationReadyEvent.class)
    public void buildOnStartup() {
        rebuild();
    }

    /** 文章变更后异步重建（不阻塞内容保存的请求线程） */
    @EventListener(KnowledgeBaseChangedEvent.class)
    public void rebuildOnArticleChange(KnowledgeBaseChangedEvent event) {
        log.info("气象服务内容变更，触发知识库重建: articleId={}", event.articleId());
        rebuildExecutor.submit(this::rebuild);
    }

    @PreDestroy
    public void shutdown() {
        rebuildExecutor.shutdownNow();
    }

    /**
     * 全量重建索引：读取已发布文章 → 切块 → 向量化入库
     *
     * 向量化失败（未配置密钥、网络不通、厂商限流）时把 ready 置为 false 并告警，
     * 而不是抛异常——调用方是启动事件与内容保存，都不该因为知识库不可用而失败。
     */
    public synchronized void rebuild() {
        if (!enabled) {
            log.info("知识库未启用（meteo.rag.enabled=false），跳过建索引");
            return;
        }
        try {
            List<ServiceArticle> articles = articleMapper.selectList(new LambdaQueryWrapper<ServiceArticle>()
                    .eq(ServiceArticle::getPublishStatus, 1)
                    .orderByAsc(ServiceArticle::getId));
            List<Document> documents = new ArrayList<>();
            for (ServiceArticle article : articles) {
                documents.addAll(chunk(article));
            }

            if (!indexedIds.isEmpty()) {
                vectorStore.delete(indexedIds);
                indexedIds = List.of();
            }
            for (int from = 0; from < documents.size(); from += EMBED_BATCH_SIZE) {
                vectorStore.add(new ArrayList<>(documents.subList(from,
                        Math.min(from + EMBED_BATCH_SIZE, documents.size()))));
            }
            indexedIds = documents.stream().map(Document::getId).toList();
            ready = true;
            log.info("知识库索引重建完成: 文章 {} 篇，片段 {} 条", articles.size(), documents.size());
        } catch (Exception e) {
            ready = false;
            log.warn("知识库索引重建失败，检索将返回空: err={}", e.getMessage());
        }
    }

    /**
     * 检索知识库
     *
     * @return 相似度达标的片段（按相关度降序）；知识库未就绪或无命中时返回空列表，
     *         由调用方决定如何表述——不能把「没检索到」说成「没有这方面的知识」
     */
    public List<KnowledgeHit> search(String query) {
        if (!ready || !StringUtils.hasText(query)) {
            return List.of();
        }
        try {
            List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query.trim())
                    .topK(topK)
                    .similarityThreshold(minScore)
                    .build());
            return documents.stream().map(document -> new KnowledgeHit(
                    String.valueOf(document.getMetadata().get(META_TITLE)),
                    categoryName(document.getMetadata().get(META_CATEGORY)),
                    document.getText(),
                    document.getScore() == null ? 0 : document.getScore())).toList();
        } catch (Exception e) {
            log.warn("知识库检索失败: query={}, err={}", query, e.getMessage());
            return List.of();
        }
    }

    /** 索引是否就绪（供工具区分「没检索到」与「知识库不可用」） */
    public boolean isReady() {
        return ready;
    }

    /**
     * 文章切块：按段落合并到目标长度，标题拼进片段正文
     *
     * 标题必须进正文——检索「大风预警」时，正文里未必出现「大风」二字（可能只写「阵风」），
     * 标题是最好的语义锚点。段落超长时不再硬切，避免把句子截断反而伤召回。
     */
    private List<Document> chunk(ServiceArticle article) {
        String raw = article.getContent() == null ? "" : article.getContent();
        String plain = BLANK_LINES.matcher(HTML_TAG.matcher(raw).replaceAll("")).replaceAll("\n");
        List<String> paragraphs = Arrays.stream(plain.split("\n"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        if (paragraphs.isEmpty()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            if (current.length() > 0 && current.length() + paragraph.length() > maxChunkChars) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(paragraph);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }

        String category = categoryName(article.getCategory());
        List<Document> documents = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            documents.add(new Document(
                    "article-%d-%d".formatted(article.getId(), index),
                    "%s（%s）\n%s".formatted(article.getTitle(), category, chunks.get(index)),
                    Map.of(META_TITLE, article.getTitle(), META_CATEGORY, article.getCategory())));
        }
        return documents;
    }

    private String categoryName(Object category) {
        if (category instanceof Number number) {
            return CATEGORY_NAMES.getOrDefault(number.intValue(), "气象服务");
        }
        return "气象服务";
    }
}
