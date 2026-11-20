package com.example.aichat.ai;

import com.example.aichat.AiChat;
import com.example.aichat.config.ConfigManager;
import com.example.aichat.model.ModelRegistry;
import com.example.aichat.model.ModelSpec;
import com.example.aichat.state.StateManager;
import com.example.aichat.text.ConsoleSafe;
import com.example.aichat.usage.TokenUsage;
import com.example.aichat.usage.UsageStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 多模型编排（2.2.0）。
 * <p>
 * 一次玩家事件的处理流程：
 * <ol>
 *   <li><b>管理员特权请求</b>：有 admin 模型就交给它，没有就退回 chat 模型；</li>
 *   <li><b>判断</b>：每个 judge 模型独立判一次「要不要接话」，判为要回复就按它的
 *       {@code routes_to} 路由到 chat 模型（留空 = 所有 chat 模型）；</li>
 *   <li><b>回复</b>：命中的 chat 模型各自写一条回复，公屏按各自的前缀广播；</li>
 *   <li><b>模型互相对话</b>：上一步的回复会作为「AI 同伴的发言」发给其它 {@code reply_to_ai=true}
 *       的 chat 模型，最多 {@code ai_dialogue_max_rounds} 轮。最后一轮会在提问里注入
 *       {@link #FINAL_ROUND_NOTICE}，让模型知道这轮之后不会再有 AI 接话。</li>
 * </ol>
 * 每个模型的 token 用量都记在自己名下（{@code usage.jsonl} 的 {@code model} 字段 = 模型 id），
 * 所以 {@code /aichat token model} 能分开看。
 */
public final class MultiModelChat {
    /** 一次事件最多产生多少条 AI 回复（含互相接话），防跑飞。 */
    private static final int MAX_AI_MESSAGES = 8;

    /** 最后一轮注入的提示：告诉模型别指望对方再回。 */
    public static final String FINAL_ROUND_NOTICE =
            "【最终轮】此轮为 AI 对话最终轮数，接下来对方回复你后（你回复后）将不会进行新一轮对话，除非有玩家消息干预。";

    private MultiModelChat() {
    }

    /** 一条最终要广播的 AI 回复。 */
    public record ModelReply(String modelId, String displayName, String prefix, String content) {
    }

    /**
     * 处理一条玩家事件，返回要广播的全部回复（已按顺序排好）。
     * 会在内部完成 token 记账，调用方只需要负责广播和历史。
     */
    public static List<ModelReply> run(ChatContext ctx, List<ConversationTurn> history) {
        List<ModelSpec> judges = ModelRegistry.byRole(ModelSpec.Role.JUDGE);
        List<ModelSpec> chats = ModelRegistry.byRole(ModelSpec.Role.CHAT);
        List<ModelSpec> admins = ModelRegistry.byRole(ModelSpec.Role.ADMIN);

        List<ModelSpec> targets = pickTargets(ctx, judges, chats, admins, history);
        if (targets.isEmpty()) {
            if (StateManager.isDebug()) {
                AiChat.LOGGER.info("[debug] 多模型：本次事件没有命中任何回复模型（玩家：{}）", ctx.playerName());
            }
            return List.of();
        }

        List<ModelReply> replies = new ArrayList<>();
        for (ModelSpec target : targets) {
            ModelReply reply = answer(target, ctx, ctx.userMessageForAi(), history,
                    UsageStore.CHANNEL_PUBLIC);
            if (reply != null) {
                replies.add(reply);
            }
        }
        if (replies.isEmpty()) {
            return replies;
        }

        int rounds = ConfigManager.aiDialogueMaxRounds();
        int produced = replies.size();
        List<ModelReply> pending = new ArrayList<>(replies);
        int round = 0;
        while (round < rounds && !pending.isEmpty() && produced < MAX_AI_MESSAGES) {
            round++;
            boolean last = round >= rounds || produced >= MAX_AI_MESSAGES - 1;
            List<ModelReply> next = new ArrayList<>();
            for (ModelReply source : pending) {
                for (ModelSpec speaker : chats) {
                    if (!speaker.replyToAi() || speaker.id().equals(source.modelId())) {
                        continue;
                    }
                    if (produced >= MAX_AI_MESSAGES) {
                        break;
                    }
                    ChatContext aiCtx = new ChatContext(
                            source.displayName(), "ai", source.content(),
                            System.currentTimeMillis(), false, false);
                    String message = aiCtx.userMessageForAi()
                            + (last ? "\n" + FINAL_ROUND_NOTICE : "");
                    ModelReply reply = answer(speaker, aiCtx, message, history,
                            UsageStore.CHANNEL_PUBLIC);
                    if (reply != null) {
                        next.add(reply);
                        produced++;
                    }
                }
            }
            pending = next;
            replies.addAll(next);
            if (StateManager.isDebug()) {
                AiChat.LOGGER.info("[debug] 多模型：AI 互相对话第 {} 轮，产生 {} 条回复（累计 {}）",
                        round, next.size(), produced);
            }
        }
        return replies;
    }

    /** 私聊等「一对一」场景：直接指定模型问一句，返回解析后的结果（含 reply 标记）。 */
    public static AiResult answerDirect(ModelSpec spec, ChatContext ctx,
                                        List<ConversationTurn> history, String channel) {
        return call(spec, ctx, ctx.userMessageForAi(), history, true, channel);
    }

    /** 私聊该用哪个模型：特权请求走 admin，其余走第一个 chat。 */
    public static ModelSpec pickForPrivate(ChatContext ctx) {
        List<ModelSpec> admins = ModelRegistry.byRole(ModelSpec.Role.ADMIN);
        if (ctx.serverTools() && !admins.isEmpty()) {
            return admins.get(0);
        }
        List<ModelSpec> chats = ModelRegistry.byRole(ModelSpec.Role.CHAT);
        if (!chats.isEmpty()) {
            return chats.get(0);
        }
        if (!admins.isEmpty()) {
            return admins.get(0);
        }
        List<ModelSpec> all = ModelRegistry.all();
        return all.isEmpty() ? null : all.get(0);
    }

    // ---------------- 内部 ----------------

    private static List<ModelSpec> pickTargets(ChatContext ctx, List<ModelSpec> judges,
                                               List<ModelSpec> chats, List<ModelSpec> admins,
                                               List<ConversationTurn> history) {
        // 特权请求优先交给管理模型；它不存在时退回普通 chat，行为与 2.0.x 一致
        if (ctx.serverTools()) {
            if (!admins.isEmpty()) {
                return List.of(admins.get(0));
            }
            return chats;
        }
        if (judges.isEmpty()) {
            // 没配判断模型 = 每条消息都回（省一次请求，也省判断模型的判断误差）
            return chats;
        }
        List<ModelSpec> routed = new ArrayList<>();
        for (ModelSpec judge : judges) {
            AiResult verdict;
            try {
                verdict = call(judge, ctx, ctx.userMessageForAi(), history, false,
                        UsageStore.CHANNEL_PUBLIC);
            } catch (Exception e) {
                AiChat.LOGGER.warn("多模型：判断模型 {} 失败（{}）", judge.id(),
                        ConsoleSafe.escape(e.getMessage()));
                continue;
            }
            if (verdict == null || !verdict.reply()) {
                if (StateManager.isDebug()) {
                    AiChat.LOGGER.info("[debug] 多模型：判断模型 {} 认为不用接话", judge.id());
                }
                continue;
            }
            for (ModelSpec pick : route(judge, chats)) {
                if (!routed.contains(pick)) {
                    routed.add(pick);
                }
            }
        }
        return routed;
    }

    /** judge 的 routes_to → chat 模型；留空 / 全部无效时退回所有 chat 模型。 */
    private static List<ModelSpec> route(ModelSpec judge, List<ModelSpec> chats) {
        List<String> routes = judge.routesTo();
        if (routes == null || routes.isEmpty()) {
            return chats;
        }
        List<ModelSpec> picked = new ArrayList<>();
        for (String id : routes) {
            ModelSpec spec = ModelRegistry.byId(id);
            if (spec != null && spec.role() == ModelSpec.Role.CHAT) {
                picked.add(spec);
            }
        }
        if (picked.isEmpty()) {
            AiChat.LOGGER.warn("多模型：判断模型 {} 的 routes_to 里没有有效的 chat 模型，改为全部 chat 模型",
                    judge.id());
            return chats;
        }
        return picked;
    }

    private static ModelReply answer(ModelSpec spec, ChatContext ctx, String userMessage,
                                     List<ConversationTurn> history, String channel) {
        try {
            AiResult result = call(spec, ctx, userMessage, history, true, channel);
            if (result == null || !result.reply() || result.content() == null) {
                return null;
            }
            String content = ConfigManager.get().redact_secrets
                    ? com.example.aichat.text.SecretRedactor.apply(result.content())
                    : result.content();
            return new ModelReply(spec.id(), spec.displayName(), spec.replyPrefix(), content);
        } catch (Exception e) {
            AiChat.LOGGER.warn("多模型：模型 {} 请求失败（{}）", spec.id(),
                    ConsoleSafe.escape(e.getMessage()));
            return null;
        }
    }

    private static AiResult call(ModelSpec spec, ChatContext ctx, String userMessage,
                                 List<ConversationTurn> history, boolean allowProseRepair,
                                 String channel) {
        AiResult result = AiClient.askOnce(ConfigManager.profileFor(spec), ctx, userMessage,
                history, allowProseRepair);
        TokenUsage usage = result.usage() == null ? TokenUsage.ZERO : result.usage();
        if (usage.total() > 0) {
            UsageStore.record(ctx.playerName(), spec.id(), usage, channel);
            AiChat.LOGGER.info("AI token 用量（{}）：prompt={} completion={} total={}（玩家：{}）",
                    spec.id(), usage.prompt(), usage.completion(), usage.total(), ctx.playerName());
        }
        return result;
    }
}
