package com.campus.meteo.agent.assistant;

import com.campus.meteo.dto.ToolInvocation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具调用留痕收集器：MeteoTools 每次执行把调用记录写入当前线程，供 /assistant/chat 组装响应。
 *
 * 用 ThreadLocal 而非请求作用域 Bean：Spring AI 的内部工具执行循环与本次问答在同一线程同步完成，
 * 线程内收集最直接，不引入作用域代理开销；且工具被非请求链路调用时（当前不存在）自动降级为不记录。
 * 用完必须 drain 清理，否则线程池复用会让下一次问答读到上一轮的调用记录。
 */
@Component
public class ToolCallRecorder {

    private static final ThreadLocal<List<ToolInvocation>> CURRENT = new ThreadLocal<>();

    /** 开始新一轮收集（覆盖上一轮可能残留的记录） */
    public void begin() {
        CURRENT.set(new ArrayList<>());
    }

    public void record(String name, String arguments, String result) {
        List<ToolInvocation> sink = CURRENT.get();
        if (sink != null) {
            sink.add(new ToolInvocation(name, arguments, result));
        }
    }

    /** 取出本轮记录并清理线程副本 */
    public List<ToolInvocation> drain() {
        List<ToolInvocation> sink = CURRENT.get();
        CURRENT.remove();
        return sink == null ? List.of() : sink;
    }
}
