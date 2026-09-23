package com.campus.meteo.agent.assistant.knowledge;

import com.campus.meteo.entity.ServiceArticle;
import com.campus.meteo.mapper.ServiceArticleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库单测
 *
 * 向量库本身用 Mock（真实语义排序由 SimpleVectorStore 保证，属于框架职责），
 * 这里只验证本类的三件事：切块口径、检索参数与结果映射、以及向量化失败时的降级——
 * 最后一条最关键：知识库不可用时必须让上层能区分「没检索到」与「检索不可用」，
 * 否则模型会把「知识库挂了」当成「没有这方面的知识」而凭常识作答。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("决策智能体知识库")
class KnowledgeBaseServiceTest {

    @Mock
    private ServiceArticleMapper articleMapper;
    @Mock
    private VectorStore vectorStore;

    private KnowledgeBaseService knowledgeBaseService;

    @BeforeEach
    void setUp() {
        knowledgeBaseService = new KnowledgeBaseService(articleMapper, vectorStore);
        ReflectionTestUtils.setField(knowledgeBaseService, "enabled", true);
        ReflectionTestUtils.setField(knowledgeBaseService, "topK", 4);
        ReflectionTestUtils.setField(knowledgeBaseService, "minScore", 0.35);
        ReflectionTestUtils.setField(knowledgeBaseService, "maxChunkChars", 60);
    }

    @Test
    @DisplayName("切块：段落合并到目标长度，标题与分类拼进片段正文")
    void shouldChunkByParagraphAndPrependTitle() {
        when(articleMapper.selectList(any())).thenReturn(List.of(article(1L, 4, "大风防范指引",
                "第一段：风力等级参考。\n第二段：主要风险点。\n第三段：处置动作。")));

        knowledgeBaseService.rebuild();

        List<Document> documents = captureAddedDocuments();
        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getText())
                .startsWith("大风防范指引（科普）")
                .contains("第一段：风力等级参考。")
                .contains("第三段：处置动作。");
        assertThat(documents.get(0).getMetadata())
                .containsEntry("title", "大风防范指引")
                .containsEntry("category", 4);
        assertThat(knowledgeBaseService.isReady()).isTrue();
    }

    @Test
    @DisplayName("切块：超过目标长度时拆成多条片段，且去掉富文本标签")
    void shouldSplitLongContentAndStripHtml() {
        // 每段 44 字，两段合计超过 maxChunkChars(60)，应拆成两条
        String paragraphA = "甲段落" + "内容".repeat(20) + "。";
        String paragraphB = "乙段落" + "内容".repeat(20) + "。";
        when(articleMapper.selectList(any())).thenReturn(List.of(article(2L, 4, "暴雨应对",
                "<p>%s</p>\n<p>%s</p>".formatted(paragraphA, paragraphB))));

        knowledgeBaseService.rebuild();

        List<Document> documents = captureAddedDocuments();
        assertThat(documents).hasSize(2);
        assertThat(documents.get(0).getText()).doesNotContain("<p>").contains("甲段落");
        assertThat(documents.get(1).getText()).doesNotContain("<p>").contains("乙段落");
    }

    @Test
    @DisplayName("切块：正文为空时不产生片段")
    void shouldSkipArticleWithoutContent() {
        when(articleMapper.selectList(any())).thenReturn(List.of(article(3L, 4, "空文章", "   ")));

        knowledgeBaseService.rebuild();

        verify(vectorStore, never()).add(anyList());
        assertThat(knowledgeBaseService.isReady()).isTrue();
    }

    @Test
    @DisplayName("重建：先清空上一次的索引再写入，避免下架文章残留")
    void shouldClearPreviousIndexBeforeRebuild() {
        when(articleMapper.selectList(any()))
                .thenReturn(List.of(article(1L, 4, "第一篇", "正文内容。")))
                .thenReturn(List.of(article(2L, 4, "第二篇", "正文内容。")));

        knowledgeBaseService.rebuild();
        knowledgeBaseService.rebuild();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).delete(idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly("article-1-0");
    }

    @Test
    @DisplayName("检索：把片段映射为带来源标题与分类的结果，并透传 topK 与相似度下限")
    void shouldMapSearchResultAndPassSearchOptions() {
        when(articleMapper.selectList(any())).thenReturn(List.of(article(1L, 4, "大风防范指引", "正文。")));
        knowledgeBaseService.rebuild();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("id-1", "大风防范指引（科普）\n正文。", Map.of("title", "大风防范指引", "category", 4))
                        .mutate().score(0.82).build()));

        List<KnowledgeHit> hits = knowledgeBaseService.search("大风黄色预警的发布标准");

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.title()).isEqualTo("大风防范指引");
            assertThat(hit.category()).isEqualTo("科普");
            assertThat(hit.score()).isEqualTo(0.82);
        });
        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getTopK()).isEqualTo(4);
        assertThat(requestCaptor.getValue().getSimilarityThreshold()).isEqualTo(0.35);
        assertThat(requestCaptor.getValue().getQuery()).isEqualTo("大风黄色预警的发布标准");
    }

    @Test
    @DisplayName("检索：空问题或未建索引时返回空，不查向量库")
    void shouldReturnEmptyWhenNotReadyOrBlankQuery() {
        assertThat(knowledgeBaseService.search("大风预警")).isEmpty();

        when(articleMapper.selectList(any())).thenReturn(List.of(article(1L, 4, "第一篇", "正文。")));
        knowledgeBaseService.rebuild();

        assertThat(knowledgeBaseService.search("  ")).isEmpty();
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    @DisplayName("向量化失败：标记为未就绪并返回空，不向调用方抛异常")
    void shouldDegradeWhenEmbeddingFails() {
        when(articleMapper.selectList(any())).thenReturn(List.of(article(1L, 4, "第一篇", "正文。")));
        doThrow(new RuntimeException("Connection refused")).when(vectorStore).add(anyList());

        knowledgeBaseService.rebuild();

        assertThat(knowledgeBaseService.isReady()).isFalse();
        assertThat(knowledgeBaseService.search("大风预警")).isEmpty();
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    @DisplayName("未启用时跳过建索引，避免无谓的向量化调用")
    void shouldSkipWhenDisabled() {
        ReflectionTestUtils.setField(knowledgeBaseService, "enabled", false);

        knowledgeBaseService.rebuild();

        verify(articleMapper, never()).selectList(any());
        assertThat(knowledgeBaseService.isReady()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private List<Document> captureAddedDocuments() {
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        return captor.getValue();
    }

    private ServiceArticle article(Long id, int category, String title, String content) {
        ServiceArticle article = new ServiceArticle();
        article.setId(id);
        article.setCategory(category);
        article.setTitle(title);
        article.setContent(content);
        article.setPublishStatus(1);
        return article;
    }
}
