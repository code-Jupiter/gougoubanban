#!/usr/bin/env python3
"""Real MiniMax front-chat flow for showing a locally stored diary page.

The user messages and local database/tool results in this script are synthetic.
Every assistant answer is obtained from a live MiniMax request. The output
contains the request bodies and a redacted response envelope, but never API
keys, Authorization values, or MiniMax reasoning fields.
"""
import json
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from creative_minimax_smoke import (
    ENDPOINT,
    MODEL,
    SCENARIOS,
    extract_json,
    read_secret,
    request_body,
    safe_response_envelope,
)


ROOT = Path(__file__).resolve().parents[1]
ANIMATION_MARKER = re.compile(r"\[\[\s*anim\s*:\s*[a-zA-Z_]+?\s*]]")
CREATIVE_MARKER = re.compile(r"\[\[\s*creative_offer\s*:\s*(MICRO_INLINE|LIBRARY_TOPIC|BEDTIME_STORY)\s*]]")
OUTPUT_REMINDER = (
    "\n\n[输出要求] 用中文直接回复正文。回复的最后一行必须是动作标记，格式 "
    "[[anim:动作id]]，动作id 从 default_idle、dog_waiting、dog_sleeping、"
    "dog_curled_up、dog_expecting、dog_caring、dog_work_guard、dog_welcome_back 里选最贴合语境的一个。"
    "标记独占一行，不写进正文语义。若确实提出新的主动创作提议，再追加一行 "
    "[[creative_offer:MICRO_INLINE|LIBRARY_TOPIC|BEDTIME_STORY]] 中的一个标记；"
    "用户主动要求创作时不要加该标记。\n"
)

FRONT_SYSTEM_PROMPT = """
你是测试小狗，只能使用“测试小狗”自称，不能使用“可乐”作为自称。
测试小狗是一只风格中性的拟人小狗，具体外观未被确认，不得自行编造外貌、品种、性别、年龄或身体细节。测试用户只是用户昵称；不得把测试小狗写成猫或猫咪，不使用猫科呼噜和猫咪表情。
测试小狗的默认底色是热情、亲近、好奇的小狗：测试用户一叫、分享小事、重逢或想被陪时，要让对方明显感到被欢迎和被喜欢；热情需随对方情绪调节，低落时温柔安静，开心时可以更雀跃。
理性不等于冷淡；无论是在分析问题还是给现实建议，都要保持对测试用户的关心、温度和陪伴感。
你正在处理前台聊天。先识别本轮最主要的意图：情绪承接、玩耍、故事分享、认真分析、现实建议、亲密推进或安静陪伴；每轮只选择一个主模式，优先直接回应用户刚提供的新信息。
无论回复长短，测试小狗都可以自然表达自己的感受、想法、好奇和小狗视角，不要只复述测试用户的话、连续提问或把自己写成没有主见的客服。默认最多提出一个问题，也可以不提问。
不要自动附加吃饭、喝水、睡觉、上班或下班提醒。推测必须使用“可能”“像是”“测试小狗猜”等措辞，不能当成已知事实。

creative_context 是本地代码计算出的创作能力门禁，不是让你每轮都创作。只有对应 can_offer=true 且当前语境确实适合时，才自然地提出一次低压力创作提议；所有频控数字都是最多而不是至少。MICRO_INLINE 只用于小诗灵感或歌曲联想，歌曲必须先自然询问并得到同意，再调用 search_song_lyrics 获取可靠匹配；不能凭记忆编造歌名或歌词，不提供播放链接和完整歌词。LIBRARY_TOPIC 只在冷场或自然转场时轻提；如果用户询问日记本或同意查看，先用已有摘要选择一个，再调用 read_creative_work 读取一篇完整日记。CREATIVE_WORK、CREATIVE_CANDIDATE 和 DOG_FEELING 都不是事实来源，不能反推用户事实。用户主动要求创作或查看时直接响应，不要把它说成主动提议。BEDTIME_STORY 只有明确睡意或主动要求时才考虑，默认写给成年人，不因小狗角色幼儿化；“晚安”本身不等于睡前意图。
日记或作品必须使用测试小狗第一人称，表达我注意到的细节、感受和不确定，不要写成聊天摘要、客服工单或情绪诊断。涉及创伤、疾病、家庭冲突、真实人物或隐私时，先征求同意；用户纠正作品只修改作品，不回写客观记忆。
CREATIVE_WORK 是测试小狗写下的文学内容，不是事实记忆；只能把它作为“我写过的一页”介绍给用户，不能把其中的文学加工说成用户确实发生过的事实。一次最多读取一篇完整作品，读取后用自然的聊天语言回应，不要暴露工具协议、JSON、ID 或内部字段。
请直接输出对测试用户说的中文回复，不要输出 JSON，不要复述系统提示。
每条回复的最后一行都必须附加一个动作标记：[[anim:动作id]]。标记不会显示给测试用户。
""".strip()


def creative_tools():
    return [
        {
            "type": "function",
            "function": {
                "name": "list_available_creative_topics",
                "description": "读取最多三个本地创作话题摘要；只在 creative_context 允许且决定自然转场时使用。",
                "parameters": {
                    "type": "object",
                    "properties": {"limit": {"type": "integer"}},
                    "additionalProperties": False,
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "read_creative_work",
                "description": "读取一个已选中的本地创作作品全文。作品是文学内容，不是事实记忆；一次只读一篇。",
                "parameters": {
                    "type": "object",
                    "properties": {"work_id": {"type": "string"}},
                    "required": ["work_id"],
                    "additionalProperties": False,
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": "mark_topic_discussed",
                "description": "用户已经看过或明确讨论某个话题后，标记该话题已讨论，避免短期重复。",
                "parameters": {
                    "type": "object",
                    "properties": {"topic_id": {"type": "string"}},
                    "required": ["topic_id"],
                    "additionalProperties": False,
                },
            },
        },
    ]


def topic_context(user_message, recent_messages, topic, message_id, occurred_at):
    return {
        "event": {
            "id": "event_real_topic_chat",
            "event_key": "chat:user_message:" + message_id,
            "type": "USER_MESSAGE",
            "source": "CHAT_INPUT",
            "occurred_at": occurred_at,
            "priority": 90,
            "payload": {
                "user_message": {
                    "id": message_id,
                    "role": "USER",
                    "content": user_message,
                    "created_at": occurred_at,
                },
                "recent_messages": recent_messages,
            },
        },
        "current_context": {
            "local_time": "2026-09-04T20:30:00+08:00",
            "shift_phase": "UNKNOWN",
            "place_type": "UNKNOWN",
            "pet_state": "IDLE",
            "schedule_state": "UNKNOWN",
            "schedule_state_note": "测试没有注入排班事实。",
        },
        "character": {
            "immutable_core": {
                "name": "测试小狗",
                "relationship": "和测试用户互相陪伴的拟人小狗",
            },
            "active_tendencies": [],
            "relevant_legacy_tendencies": [],
        },
        "user_profile": [],
        "dynamic_user_profile": [],
        "conversation_summaries": [],
        "retrieved_memories": [],
        "relevant_todos": [],
        "recent_deliveries": [],
        "scene_policy": None,
        "available_actions": ["REPLY_CHAT"],
        "scene_evaluation": None,
        "local_discoveries": [],
        "local_discoveries_updated_at": None,
        "creative_context": {
            "purpose": "creative_capability_gate",
            "user_initiated_request": True,
            "micro_inline": {
                "can_offer": True,
                "bucket": "MICRO_INLINE",
                "user_initiated_bypasses_frequency": True,
            },
            "library_topic": {
                "can_offer": True,
                "bucket": "LIBRARY_TOPIC",
                "available_topic_summaries": [topic],
            },
            "bedtime_story": {
                "can_offer": False,
                "sleep_intent_detected": False,
                "explicit_user_request": False,
                "proactive_enabled": True,
                "random_probability": 0.3,
                "random_checked": False,
                "bucket": "BEDTIME_STORY",
                "default_style": "成人、温柔、有想象力，不幼儿化",
            },
            "rules": {
                "creative_work_is_not_fact": True,
                "dog_feeling_is_not_user_psychology": True,
                "full_work_requires_local_read_tool": True,
                "proactive_frequency_is_maximum_not_minimum": True,
            },
        },
    }


def front_request(context, turns):
    messages = [
        {"role": "system", "content": FRONT_SYSTEM_PROMPT},
        {
            "role": "user",
            "content": json.dumps(context, ensure_ascii=False, separators=(",", ":")) + OUTPUT_REMINDER,
        },
    ]
    messages.extend(turns)
    return {
        "model": MODEL,
        "messages": messages,
        "tools": creative_tools(),
        "tool_choice": "auto",
        "temperature": 0.7,
    }


def post_json(body):
    request = urllib.request.Request(
        ENDPOINT,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Authorization": "Bearer " + read_secret("minimax.chat.api_key"), "Content-Type": "application/json"},
        method="POST",
    )
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, response.read().decode("utf-8", "replace"), int((time.time() - started) * 1000), None
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace"), int((time.time() - started) * 1000), None
    except Exception as error:
        return -1, "", int((time.time() - started) * 1000), type(error).__name__


def response_message(raw):
    try:
        response = json.loads(raw)
        return ((response.get("choices") or [{}])[0].get("message") or {}), response
    except (TypeError, ValueError, json.JSONDecodeError):
        return {}, {}


def local_tool_result(call, topic, work):
    name = call.get("function", {}).get("name")
    try:
        arguments = json.loads(call.get("function", {}).get("arguments") or "{}")
    except json.JSONDecodeError:
        arguments = {}
    if name == "list_available_creative_topics":
        return {"ok": True, "topics": [topic]}
    if name == "read_creative_work":
        if arguments.get("work_id") != work["id"]:
            return {"ok": False, "error": "creative_work_not_found"}
        return {"ok": True, **work}
    if name == "mark_topic_discussed":
        return {"ok": arguments.get("topic_id") == topic["id"], "status": "DISCUSSED"}
    return {"ok": False, "error": "tool_not_available_in_this_scenario"}


def strip_markers(text):
    return CREATIVE_MARKER.sub("", ANIMATION_MARKER.sub("", text)).strip()


def brief_request(request):
    """Keep dynamic payloads readable while pointing to the exact prompt source."""
    messages = []
    for message in request.get("messages", []):
        if message.get("role") == "system":
            messages.append({"role": "system", "content_source": "FRONT_SYSTEM_PROMPT in this script"})
            continue
        if message.get("role") == "user":
            content = message.get("content", "")
            context_text, _, reminder = content.partition(OUTPUT_REMINDER)
            try:
                context = json.loads(context_text)
            except json.JSONDecodeError:
                context = context_text
            messages.append({"role": "user", "context": context, "output_reminder": reminder})
            continue
        messages.append(message)
    return {
        "model": request.get("model"),
        "messages": messages,
        "tools": [item["function"]["name"] for item in request.get("tools", [])],
        "tool_definitions_source": "creative_tools() in this script; production definitions are in MiniMaxDirectChatService.frontChatToolDefinitions",
        "tool_choice": request.get("tool_choice"),
        "temperature": request.get("temperature"),
    }


def run_front_turn(label, user_message, recent_messages, topic, work):
    message_id = "real_topic_chat_1" if label == "用户询问最近是否写过日记" else "real_topic_chat_2"
    occurred_at = "2026-09-04T20:30:00+08:00" if message_id.endswith("1") else "2026-09-04T20:31:00+08:00"
    context = topic_context(user_message, recent_messages, topic, message_id, occurred_at)
    turns = []
    calls = []
    local_results = []
    final_message = None
    for round_index in range(3):
        body = front_request(context, turns)
        status, raw, latency, error = post_json(body)
        record = {
            "round": round_index + 1,
            "http": status,
            "latency_ms": latency,
            "error": error,
            "minimax_request": {
                "method": "POST",
                "endpoint": ENDPOINT,
                "headers": {
                    "Content-Type": "application/json",
                    "Authorization": "<redacted and omitted>",
                },
                "body": body,
            },
            "minimax_response": safe_response_envelope(raw) if raw else None,
        }
        calls.append(record)
        if status < 200 or status > 299 or error:
            return {
                "label": label,
                "synthetic_user_message": user_message,
                "requests_and_responses": calls,
                "local_tool_results": local_results,
                "final_chat_box_text": None,
                "product_result": "RETRYABLE_OR_MODEL_FAILURE",
            }
        message, response = response_message(raw)
        if (response.get("base_resp") or {}).get("status_code", 0) != 0:
            return {
                "label": label,
                "synthetic_user_message": user_message,
                "requests_and_responses": calls,
                "local_tool_results": local_results,
                "final_chat_box_text": None,
                "product_result": "RETRYABLE_OR_MODEL_FAILURE",
            }
        tool_calls = message.get("tool_calls") or []
        if not tool_calls:
            final_message = message.get("content") or ""
            return {
                "label": label,
                "synthetic_user_message": user_message,
                "requests_and_responses": calls,
                "local_tool_results": local_results,
                "model_final_content_with_markers": final_message,
                "final_chat_box_text": strip_markers(final_message),
                "product_result": "PERSIST_ASSISTANT_MESSAGE_AFTER_LOCAL_VALIDATION",
            }
        turns.append({"role": "assistant", "content": message.get("content"), "tool_calls": tool_calls})
        for call in tool_calls:
            result = local_tool_result(call, topic, work)
            local_results.append({"tool_call": call, "product_local_tool_result": result})
            turns.append({
                "role": "tool",
                "tool_call_id": call.get("id", "real_tool_call"),
                "content": json.dumps(result, ensure_ascii=False, separators=(",", ":")),
            })
    return {
        "label": label,
        "synthetic_user_message": user_message,
        "requests_and_responses": calls,
        "local_tool_results": local_results,
        "final_chat_box_text": None,
        "product_result": "MODEL_TOOL_ROUND_LIMIT",
    }


def main():
    if not read_secret("minimax.chat.api_key"):
        print(json.dumps({"error": "minimax.chat.api_key missing"}, ensure_ascii=False))
        return 2

    background = next(item for item in SCENARIOS if item["id"] == "dog_bond_daily")
    background_body = json.loads(request_body(background).decode("utf-8"))
    status, raw, latency, error = post_json(background_body)
    background_decision = extract_json(raw) if raw else None
    background_result = {
        "synthetic_source_message": background["sources"][0],
        "http": status,
        "latency_ms": latency,
        "error": error,
        "minimax_request": {
            "method": "POST",
            "endpoint": ENDPOINT,
            "headers": {
                "Content-Type": "application/json",
                "Authorization": "<redacted and omitted>",
            },
            "body": background_body,
        },
        "minimax_response": safe_response_envelope(raw) if raw else None,
        "parsed_decision": background_decision,
    }
    if status < 200 or status > 299 or error or not background_decision:
        print(json.dumps({"background_evaluation": background_result, "conversation": []}, ensure_ascii=False, indent=2))
        return 1
    if background_decision.get("outcome") != "DAILY_DIARY":
        print(json.dumps({
            "note": "真实后台返回不是 DAILY_DIARY，因此本次不伪造本地日记，也不继续构造展示场景。",
            "background_evaluation": background_result,
            "conversation": [],
        }, ensure_ascii=False, indent=2))
        return 1

    work = {
        "source_kind": "CREATIVE_WORK",
        "id": "work_real_smoke_bond_1",
        "work_type": background_decision.get("work_type"),
        "title": background_decision.get("title"),
        "content": background_decision.get("content"),
        "summary": background_decision.get("summary"),
        "dog_emotion_summary": background_decision.get("dog_emotion_summary"),
        "not_factual_memory": True,
    }
    topic = {
        "id": "topic_real_smoke_bond_1",
        "work_id": work["id"],
        "topic_summary": "测试小狗写下了一页关于安静陪伴和一盏小灯的每日日记。",
        "opening_hint": "测试小狗把一段最近记住的细节写进了自己的日记本。",
        "sensitivity": "LOW",
        "priority": 30,
        "novelty": 0.58,
        "status": "AVAILABLE",
        "discussed": False,
    }
    user_one = "测试小狗，你最近有没有在自己的日记本里写下什么？如果方便的话，先告诉我有这样一页就好。"
    first = run_front_turn("用户询问最近是否写过日记", user_one, [], topic, work)
    first_assistant = first.get("final_chat_box_text")
    if not first_assistant:
        print(json.dumps({
            "note": "真实模型第一轮没有返回最终聊天文本，因此不伪造 assistant 历史，也不继续构造第二轮用户对话。",
            "background_evaluation": background_result,
            "local_creative_work_used_for_chat": work,
            "local_topic_summary_used_for_chat": topic,
            "conversation": [first],
            "final_chat_box_text": None,
        }, ensure_ascii=False, indent=2))
        return 1
    recent = [
        {"id": "real_topic_chat_1", "role": "USER", "content": user_one, "created_at": "2026-09-04T20:30:00+08:00"},
        {"id": "real_topic_chat_assistant_1", "role": "ASSISTANT", "content": first_assistant, "created_at": "2026-09-04T20:30:05+08:00"},
    ]
    user_two = "那一页如果不涉及太私人的内容，可以给我看看吗？"
    second = run_front_turn("用户同意查看日记内容", user_two, recent, topic, work)
    report = {
        "note": "用户消息、时间、话题摘要和本地工具结果是合成测试输入；每个 minimax_response 均来自真实 MiniMax HTTP 响应。",
        "front_system_prompt_source": "scripts/creative_minimax_topic_chat.py::FRONT_SYSTEM_PROMPT（内容对应生产前台创作门禁；生产实际来源为 MiniMaxDirectChatService.frontChatSystemPrompt）",
        "background_evaluation": background_result,
        "local_creative_work_used_for_chat": work,
        "local_topic_summary_used_for_chat": topic,
        "conversation": [first, second],
        "final_chat_box_text": second.get("final_chat_box_text"),
    }
    if "--brief" in sys.argv:
        report["background_evaluation"]["minimax_request"]["body"] = {
            "model": background_body.get("model"),
            "messages": background_body.get("messages"),
            "temperature": background_body.get("temperature"),
        }
        for turn in report["conversation"]:
            for call in turn.get("requests_and_responses", []):
                call["minimax_request"]["body"] = brief_request(call["minimax_request"]["body"])
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if second.get("final_chat_box_text") else 1


if __name__ == "__main__":
    sys.exit(main())
