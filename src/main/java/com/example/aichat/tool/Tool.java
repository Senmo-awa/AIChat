package com.example.aichat.tool;

import com.google.gson.JsonObject;

/**
 * 一个可被模型调用的工具。
 * <p>
 * 协议层只做两件事：把 {@link #parameters()} 转成各家格式（OpenAI 的 function.parameters、
 * Anthropic 的 input_schema），以及把模型给的 arguments 拿来执行。具体实现不关心走的是哪家协议，
 * 这样新增一个能力（知识库、搜索、记忆……）只需要加一个实现类，两家客户端都不用改。
 */
public interface Tool {
    /** 回灌内容的默认字符上限，防止长文件把 prompt 预算吃光。 */
    int DEFAULT_MAX_RESULT_CHARS = 6000;

    /** 工具名，必须和提示词里写的一致。 */
    String name();

    /** 工具描述，模型据此决定何时调用。 */
    String description();

    /** JSON Schema：{"type":"object","properties":{...},"required":[...]}。 */
    JsonObject parameters();

    /**
     * 执行一次调用。参数缺省由实现自己判断并抛出可读的异常，
     * {@link Tools#invoke} 会把异常转成错误文本回灌给模型，不会让整次对话失败。
     */
    String invoke(JsonObject arguments, ToolRun run) throws Exception;

    /** 回灌内容的字符上限；记忆文件这类需要读全的可以放宽。 */
    default int maxResultChars() {
        return DEFAULT_MAX_RESULT_CHARS;
    }
}
