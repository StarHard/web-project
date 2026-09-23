package com.campus.meteo.agent.assistant.knowledge;

/**
 * 气象服务内容发生变更（新增/修改/上下架）时发布，触发知识库重建索引
 *
 * 用事件而非直接调用：内容模块不该知道知识库的存在，否则「气象服务」的读写
 * 会被检索实现绑架。索引重建在监听方异步完成，不阻塞内容保存。
 */
public record KnowledgeBaseChangedEvent(Long articleId) {
}
