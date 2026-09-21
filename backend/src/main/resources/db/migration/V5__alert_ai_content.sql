-- 预警文案增强：告警记录增加「AI 生成的处置建议」列
--
-- 单独成列而不覆盖 content：content 是规则判定的事实描述（谁、什么要素、超了多少），
-- 由模板生成、必定有值、可机读；ai_content 是大模型生成的面向人的处置建议（影响对象 + 可执行动作），
-- 大模型不可用时为 NULL。两者分列存放才能分辨「哪句是规则说的、哪句是模型写的」，
-- 也保证大模型故障时预警记录本身不缺失。
ALTER TABLE `alert_record`
    ADD COLUMN `ai_content` VARCHAR(500) DEFAULT NULL COMMENT 'AI 生成的预警处置建议（大模型不可用时为空）' AFTER `content`;
