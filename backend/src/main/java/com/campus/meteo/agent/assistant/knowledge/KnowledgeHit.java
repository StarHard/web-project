package com.campus.meteo.agent.assistant.knowledge;

/**
 * 知识库检索命中的片段
 *
 * @param title    来源文章标题（回答引用时必须带出，用户才能溯源）
 * @param category 文章分类中文名
 * @param content  片段正文（含标题前缀）
 * @param score    向量相似度，越大越相关
 */
public record KnowledgeHit(String title, String category, String content, double score) {
}
