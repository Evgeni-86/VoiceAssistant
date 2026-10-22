package com.voiceassistant.ai;

import java.util.Arrays;
import java.util.List;

public enum OpenRouterModel {
    FREE("openrouter/free", "OpenRouter Free", ReasoningMode.DEFAULT),
    QWEN3("qwen/qwen3-235b-a22b-2507", "Qwen3 235B Instruct", ReasoningMode.DEFAULT),
    GPT_4_1_MINI("openai/gpt-4.1-mini", "GPT-4.1 Mini", ReasoningMode.DEFAULT),
    GPT_5_MINI("openai/gpt-5-mini", "GPT-5 Mini", ReasoningMode.DEFAULT);

    private final String modelId;
    private final String displayName;
    private final ReasoningMode reasoningMode;

    OpenRouterModel(String modelId, String displayName, ReasoningMode reasoningMode) {
        this.modelId = modelId;
        this.displayName = displayName;
        this.reasoningMode = reasoningMode;
    }

    public String modelId() {
        return modelId;
    }

    public String displayName() {
        return displayName;
    }

    public ReasoningMode reasoningMode() {
        return reasoningMode;
    }

    public static List<String> modelIds() {
        return Arrays.stream(values()).map(OpenRouterModel::modelId).toList();
    }

    public static OpenRouterModel fromModelId(String modelId) {
        for (OpenRouterModel model : values()) {
            if (model.modelId.equals(modelId)) return model;
        }
        return null;
    }
}
