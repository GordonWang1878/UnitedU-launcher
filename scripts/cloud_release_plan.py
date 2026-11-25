#!/usr/bin/env python3
"""Offline plan for a future cloud release. This stage cannot publish.

Input comes from the environment, never from a shell-evaluated argument.
The only successful result is a dry-run plan. Signing, tags, GitHub
Releases, and R2 uploads are refused. No environment value makes the
release capability present.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import traceback
from pathlib import Path

USAGE = """\
用法: python3 scripts/cloud_release_plan.py

只读环境变量，不接收版本号或说明作为命令行参数。
成功退出码只表示 dry-run 计划通过。这个程序不能签名或发布。

  UNITEDU_PLAN_REPO                 仓库根，默认当前目录
  UNITEDU_PLAN_MODE                 dry-run（默认）或 execute（execute 会被拒绝）
  UNITEDU_PLAN_VERSION              稳定版 X.Y.Z 或 Beta X.Y.Z-beta.N
  UNITEDU_PLAN_VERSION_CODE         与 app/build.gradle.kts 里的 versionCode 相同
  UNITEDU_PLAN_SHA                  40 位小写提交 SHA，必须等于 HEAD
  UNITEDU_PLAN_GITHUB_SHA           可选；工作流传入时也必须等于 HEAD
  UNITEDU_PLAN_NOTES_FILE           发布说明文件（与 UNITEDU_PLAN_NOTES 二选一）
  UNITEDU_PLAN_NOTES                发布说明正文
  UNITEDU_PLAN_UPDATE_URLS          必须与 gradle.properties 的 unitedu.updateUrls 逐字相同
  UNITEDU_PLAN_BETA_URLS            必须与 unitedu.betaUrls 逐字相同
  UNITEDU_PLAN_ROLLBACK_URLS        必须与 unitedu.rollbackUrls 逐字相同
  UNITEDU_PLAN_CHANNEL_STATE        离线频道状态 JSON 文件
  UNITEDU_PLAN_CHANNEL_STATE_JSON   同一份 JSON 正文（与文件二选一）

频道状态没有接入线上 GitHub 或 R2。不提供这份快照时，历史检查失败，
不会把已发布版本当成空的。
"""

SCHEMA = 1
NOTES_MAX_BYTES = 32 * 1024
STATE_MAX_BYTES = 64 * 1024
MAX_VERSION_CODE = 2_100_000_000
STABLE_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
BETA_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+-beta\.[0-9]+$")
SHA40 = re.compile(r"^[0-9a-f]{40}$")
HTTPS_URL = re.compile(r"https://[^/?#@\\\s]+(?:[/?#]\S*)?\Z")
SHELL_URL_CHARS = set("$`;\n\r'\"")
VERSION_CODE_LINE = re.compile(
    r"^[ \t]*val appVersionCode: Int = providers\.gradleProperty\(\"versionCodeOverride\"\)"
    r"\.orNull\?\.toInt\(\) \?: ([1-9][0-9]*)[ \t]*$",
    re.MULTILINE,
)
VERSION_ASSIGN = re.compile(r"^[ \t]*versionCode = appVersionCode[ \t]*$", re.MULTILINE)
VERSION_NAME_LINE = re.compile(
    r"^[ \t]*versionName = \"([0-9]+\.[0-9]+\.[0-9]+(?:-beta\.[0-9]+)?)\"[ \t]*$",
    re.MULTILINE,
)
STABLE_TAG = re.compile(r"^v[0-9]+\.[0-9]+\.[0-9]+$")
VERSION_TAG = re.compile(r"^v[0-9]+\.[0-9]+\.[0-9]+(?:-beta\.[0-9]+)?$")
CHANNEL_KEYS = (
    "unitedu.updateUrls",
    "unitedu.betaUrls",
    "unitedu.rollbackUrls",
)
CHANNEL_ENV = {
    "unitedu.updateUrls": "UNITEDU_PLAN_UPDATE_URLS",
    "unitedu.betaUrls": "UNITEDU_PLAN_BETA_URLS",
    "unitedu.rollbackUrls": "UNITEDU_PLAN_ROLLBACK_URLS",
}
ENABLE_SWITCHES = (
    "UNITEDU_PLAN_ALLOW_PUBLISH",
    "UNITEDU_RELEASE_ENABLED",
)
REFUSED_ACTIONS = [
    "sign",
    "build-apk",
    "create-tag",
    "delete-tag",
    "overwrite-tag",
    "retry-live-tag",
    "create-release",
    "overwrite-release",
    "upload-github",
    "upload-r2",
    "dispatch-publish",
]
LIMITS = [
    "成功结果只是 dry-run 计划，不会构建 APK，也不会签名。",
    "发布能力固定为 absent。环境变量不能把它改成 present。",
    "对话中对版本、提交和说明的确认没有写入工作流，程序不核验。",
    "tag、Release 与 versionCode 检查只使用调用方给出的离线快照，不查询 GitHub 或 R2。",
    "三项通道地址必须与提交里的 gradle.properties 逐字相同，不使用 build.gradle.kts 的缺省回落。",
    "本阶段不上传 R2，也不声称已有镜像会同步。",
    "不调用 scripts/release.sh。",
]
STAGE_NAMES = ("inputs", "source", "channels", "history", "capability", "effects")


def release_capability() -> str:
    """发布能力没有开关。这个函数只返回 absent。"""
    return "absent"


def error(code: str, message: str) -> dict[str, str]:
    return {"code": code, "message": message}


def stage(name: str, errors: list[dict[str, str]], status: str | None = None) -> dict:
    if status is None:
        status = "failed" if errors else "passed"
    return {"name": name, "status": status, "errors": errors}


class GitError(RuntimeError):
    pass


class Git:
    def __init__(self, repo: Path):
        self.repo = repo

    def run(self, args: list[str]) -> str:
        if not git_args_allowed(args):
            raise GitError("拒绝执行的 git 参数")
        proc = subprocess.run(
            ["git", "-C", str(self.repo), *args],
            check=False,
            capture_output=True,
            text=True,
        )
        if proc.returncode != 0:
            raise GitError("git 读取失败")
        return proc.stdout


def git_args_allowed(args: list[str]) -> bool:
    if args in (
        ["rev-parse", "HEAD"],
        ["status", "--porcelain"],
        ["for-each-ref", "--format=%(objectname) %(refname)", "refs/tags"],
    ):
        return True
    return (
        len(args) == 3
        and args[0] == "rev-parse"
        and args[1] == "--verify"
        and SHA40.fullmatch(args[2][:40]) is not None
        and args[2].endswith("^{commit}")
    )


def version_channel(version: str) -> str | None:
    if STABLE_VERSION.fullmatch(version):
        return "stable"
    if BETA_VERSION.fullmatch(version):
        return "beta"
    return None


def parse_version_code(raw: str) -> int | None:
    if not re.fullmatch(r"[1-9][0-9]*", raw or ""):
        return None
    value = int(raw)
    if value > MAX_VERSION_CODE:
        return None
    return value


def read_bytes_limited(path: Path, limit: int) -> bytes:
    data = path.read_bytes()
    if len(data) > limit:
        raise ValueError("too-large")
    return data


def notes_errors(text: str) -> list[dict[str, str]]:
    if "\0" in text:
        return [error("notes-unsafe", "发布说明含有 NUL，拒绝把它当作命令或二进制。")]
    if any(ord(ch) < 32 and ch not in "\n\t" for ch in text):
        return [error("notes-unsafe", "发布说明含有不允许的控制字符。")]
    if text.strip() == "":
        return [error("notes-blank", "发布说明是空白。正式计划不会用默认文案填上。")]
    if len(text.encode("utf-8")) > NOTES_MAX_BYTES:
        return [error("notes-too-large", "发布说明超过 32 KiB。")]
    return []


def load_notes(env: dict[str, str]) -> tuple[str | None, list[dict[str, str]]]:
    has_file = "UNITEDU_PLAN_NOTES_FILE" in env and env["UNITEDU_PLAN_NOTES_FILE"] != ""
    has_inline = "UNITEDU_PLAN_NOTES" in env
    if has_file and has_inline:
        return None, [error("notes-ambiguous", "发布说明同时给了文件和正文。只保留一个来源。")]
    if not has_file and not has_inline:
        return None, [error("notes-missing", "没有发布说明。")]
    try:
        if has_inline:
            text = env["UNITEDU_PLAN_NOTES"]
        else:
            path = Path(env["UNITEDU_PLAN_NOTES_FILE"])
            if not path.is_file():
                return None, [error("notes-missing", "发布说明文件不存在或不是普通文件。")]
            text = read_bytes_limited(path, NOTES_MAX_BYTES).decode("utf-8")
    except ValueError:
        return None, [error("notes-too-large", "发布说明超过 32 KiB。")]
    except (OSError, UnicodeError):
        return None, [error("notes-unsafe", "发布说明不是可读的 UTF-8 文本。")]
    stripped = text.strip("\n")
    problems = notes_errors(stripped)
    return (stripped if not problems else None), problems


def split_urls(raw: str) -> list[str]:
    return [part.strip() for part in raw.split(",") if part.strip()]


def url_problem(url: str) -> str | None:
    if any(ch in url for ch in SHELL_URL_CHARS) or not HTTPS_URL.fullmatch(url):
        return "通道地址必须是不含 shell 元字符的 https URL。回环地址不会被接受。"
    return None


def read_gradle_identity(text: str) -> tuple[str, int]:
    codes = VERSION_CODE_LINE.findall(text)
    names = VERSION_NAME_LINE.findall(text)
    assigns = VERSION_ASSIGN.findall(text)
    if len(codes) != 1 or len(names) != 1 or len(assigns) != 1:
        raise ValueError("identity")
    code = int(codes[0])
    if code > MAX_VERSION_CODE or version_channel(names[0]) is None:
        raise ValueError("identity")
    return names[0], code


def read_channel_properties(text: str) -> dict[str, str]:
    found: dict[str, str] = {}
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if key not in CHANNEL_KEYS:
            continue
        if key in found:
            raise ValueError("duplicate")
        found[key] = value
    if any(key not in found for key in CHANNEL_KEYS):
        raise ValueError("missing")
    return found


def manifest_entry(value, kind: str) -> dict | None:
    if value is None:
        return None
    if not isinstance(value, dict) or set(value) != {"versionCode", "versionName"}:
        raise ValueError("manifest")
    code = value["versionCode"]
    name = value["versionName"]
    if isinstance(code, bool) or not isinstance(code, int) or not 0 < code <= MAX_VERSION_CODE:
        raise ValueError("manifest")
    if not isinstance(name, str):
        raise ValueError("manifest")
    if kind == "beta":
        if version_channel(name) != "beta":
            raise ValueError("manifest")
    elif version_channel(name) != "stable":
        raise ValueError("manifest")
    return {"versionCode": code, "versionName": name}


def parse_channel_state(raw: str) -> dict:
    if len(raw.encode("utf-8")) > STATE_MAX_BYTES:
        raise ValueError("large")
    try:
        obj = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ValueError("json") from exc
    if not isinstance(obj, dict) or set(obj) != {"tags", "releases", "manifests"}:
        raise ValueError("shape")
    tags = obj["tags"]
    releases = obj["releases"]
    manifests = obj["manifests"]
    if not isinstance(tags, list) or not isinstance(releases, list) or not isinstance(manifests, dict):
        raise ValueError("shape")
    if set(manifests) != {"latest", "beta", "rollback"}:
        raise ValueError("shape")
    clean_tags = _names(tags, allow_channel=True)
    clean_releases = _names(releases, allow_channel=True)
    return {
        "tags": clean_tags,
        "releases": clean_releases,
        "latest": manifest_entry(manifests["latest"], "latest"),
        "beta": manifest_entry(manifests["beta"], "beta"),
        "rollback": manifest_entry(manifests["rollback"], "rollback"),
    }


def _names(values: list, allow_channel: bool) -> list[str]:
    names: list[str] = []
    for value in values:
        if not isinstance(value, str):
            raise ValueError("name")
        if value == "channel-beta" and allow_channel:
            names.append(value)
            continue
        if VERSION_TAG.fullmatch(value) is None:
            raise ValueError("name")
        names.append(value)
    if len(names) != len(set(names)):
        raise ValueError("duplicate")
    return names


def load_state(env: dict[str, str]) -> tuple[dict | None, list[dict[str, str]]]:
    has_file = "UNITEDU_PLAN_CHANNEL_STATE" in env and env["UNITEDU_PLAN_CHANNEL_STATE"] != ""
    has_inline = "UNITEDU_PLAN_CHANNEL_STATE_JSON" in env and env["UNITEDU_PLAN_CHANNEL_STATE_JSON"].strip() != ""
    if has_file and has_inline:
        return None, [error("state-invalid", "频道状态同时给了文件和正文。")]
    if not has_file and not has_inline:
        return None, [error("state-missing", "没有频道状态快照。拒绝把已发布历史当成空的。")]
    try:
        if has_inline:
            raw = env["UNITEDU_PLAN_CHANNEL_STATE_JSON"]
        else:
            path = Path(env["UNITEDU_PLAN_CHANNEL_STATE"])
            if not path.is_file():
                return None, [error("state-missing", "频道状态文件不存在。")]
            raw = read_bytes_limited(path, STATE_MAX_BYTES).decode("utf-8")
        return parse_channel_state(raw), []
    except ValueError:
        return None, [error("state-invalid", "频道状态不是可接受的离线快照。")]
    except (OSError, UnicodeError):
        return None, [error("state-invalid", "频道状态文件无法按 UTF-8 读取。")]


def version_tuple(name: str) -> tuple[int, int, int]:
    return tuple(int(part) for part in name.split("."))  # type: ignore[return-value]


def history_errors(state: dict, version: str | None, code: int | None, channel: str | None) -> list[dict[str, str]]:
    errors: list[dict[str, str]] = []
    tags = set(state["tags"])
    releases = set(state["releases"])
    for name in sorted(tags):
        if name != "channel-beta" and name not in releases:
            errors.append(error("tag-partial", f"{name} 只有 tag、没有 Release。不会删除、覆盖或重试。"))
    for name in sorted(releases):
        if name != "channel-beta" and name not in tags:
            errors.append(error("release-partial", f"{name} 只有 Release、没有 tag。不会删除、覆盖或重试。"))
    if ("channel-beta" in tags) != ("channel-beta" in releases):
        errors.append(error("tag-partial", "channel-beta 的 tag 与 Release 不一致。不会删除、覆盖或重试。"))
    stable = []
    for tag in state["tags"]:
        if STABLE_TAG.fullmatch(tag):
            stable.append((version_tuple(tag[1:]), tag[1:]))
    latest = state["latest"]
    if latest is None or not stable:
        errors.append(error("history-inconsistent", "快照里没有可用的稳定版 tag 与 latest 清单。"))
        return errors
    newest = max(stable)[1]
    if latest["versionName"] != newest:
        errors.append(error(
            "history-inconsistent",
            "最新稳定 tag 与 latest 清单的 versionName 不一致。拒绝在这份快照上继续。",
        ))
    beta = state["beta"]
    rollback = state["rollback"]
    if (beta is None) != (rollback is None):
        errors.append(error("rollback-pair", "beta 与 rollback 清单必须成对出现。不会修补缺的那一份，也不会重试。"))
    if beta is not None and rollback is not None:
        if rollback["versionCode"] != beta["versionCode"] + 1:
            errors.append(error("rollback-inconsistent", "rollback 的 versionCode 必须等于已发布 beta + 1。"))
        if not any(tag == f"v{rollback['versionName']}" for tag in state["tags"] if STABLE_TAG.fullmatch(tag)):
            errors.append(error("rollback-inconsistent", "rollback 的 versionName 不是快照里的稳定版 tag。"))
        codes = [latest["versionCode"], beta["versionCode"], rollback["versionCode"]]
        if len(codes) != len(set(codes)):
            errors.append(error("rollback-inconsistent", "latest、beta、rollback 的 versionCode 必须互不相同。"))
    if version is None or code is None or channel is None:
        return errors
    requested = f"v{version}"
    if requested in tags or requested in releases:
        errors.append(error("tag-duplicate", f"{requested} 已经存在。不会删除、覆盖或重试。"))
    published = [latest["versionCode"]]
    reserved: set[int] = set()
    if beta is not None:
        published.append(beta["versionCode"])
        reserved.add(beta["versionCode"] + 1)
    if rollback is not None:
        published.append(rollback["versionCode"])
        reserved.add(rollback["versionCode"])
    maximum = max(published)
    if code <= maximum:
        errors.append(error("code-not-monotonic", f"versionCode {code} 不大于快照里已发布的最大值 {maximum}。"))
    if code in reserved:
        errors.append(error("code-reserved", f"versionCode {code} 是已保留的回退号。不会复用。"))
    if channel == "beta":
        rollback_code = code + 1
        if rollback_code > MAX_VERSION_CODE or rollback_code <= maximum or rollback_code in reserved or rollback_code in published:
            errors.append(error("code-reserved", f"回退号 {rollback_code} 与已发布或已保留的 versionCode 冲突。"))
    return errors


def intended_order(channel: str) -> list[str]:
    if channel == "stable":
        return ["apk-on-version-tag", "latest.json"]
    return [
        "beta-apk-on-version-tag",
        "rollback-apk-on-channel-beta",
        "rollback.json",
        "beta.json",
    ]


def build_plan(version: str, code: int, channel: str, sha: str, notes: str, props: dict[str, str], state: dict) -> dict:
    rollback = None
    if channel == "beta":
        rollback = {
            "versionCode": code + 1,
            "versionName": state["latest"]["versionName"],
            "createsTag": False,
            "attachesToRelease": "channel-beta",
            "performed": False,
        }
    return {
        "version": version,
        "versionCode": code,
        "channel": channel,
        "commit": sha,
        "notes": notes,
        "channelSettings": {
            "unitedu.updateUrls": props["unitedu.updateUrls"],
            "unitedu.betaUrls": props["unitedu.betaUrls"],
            "unitedu.rollbackUrls": props["unitedu.rollbackUrls"],
            "source": "committed-gradle.properties",
            "fallbacksApplied": False,
        },
        "github": {
            "tag": f"v{version}",
            "prerelease": channel == "beta",
            "updatesLatest": channel == "stable",
            "manifestFiles": ["latest.json"] if channel == "stable" else ["rollback.json", "beta.json"],
            "intendedOrder": intended_order(channel),
            "performed": False,
        },
        "rollback": rollback,
        "r2": {"action": "not-modified", "performed": False},
        "channelBetaRelease": "present" if "channel-beta" in state["releases"] else "absent",
        "intendedPublishPerformed": False,
    }


def snapshot(git: Git) -> tuple[str, str, str]:
    return (
        git.run(["rev-parse", "HEAD"]).strip(),
        git.run(["status", "--porcelain"]),
        git.run(["for-each-ref", "--format=%(objectname) %(refname)", "refs/tags"]),
    )


def blank_report(mode: str) -> dict:
    return {
        "schema": SCHEMA,
        "tool": "cloud-release-plan",
        "ok": False,
        "mode": mode,
        "releaseCapability": release_capability(),
        "approvalEnforced": False,
        "approval": {
            "mechanism": "none",
            "detail": "对话里确认版本、提交和说明只是流程约定。这个计划不核验是否有人批准。",
        },
        "effects": [],
        "refusedActions": list(REFUSED_ACTIONS),
        "limits": list(LIMITS),
        "stages": [],
        "plan": None,
    }


def build_report(env: dict[str, str], git_factory=Git) -> dict:
    mode = env.get("UNITEDU_PLAN_MODE") or "dry-run"
    report = blank_report(mode if mode in ("dry-run", "execute") else "unrecognized")
    input_errors: list[dict[str, str]] = []
    if mode not in ("dry-run", "execute"):
        input_errors.append(error("mode-invalid", "模式只接受 dry-run。execute 会被拒绝，其它值无效。"))

    version = env.get("UNITEDU_PLAN_VERSION", "")
    channel = version_channel(version)
    if channel is None:
        input_errors.append(error("version-invalid", "版本号只接受稳定版 X.Y.Z 或 Beta X.Y.Z-beta.N。"))
        version_ok = None
        channel_ok = None
    else:
        version_ok = version
        channel_ok = channel

    code = parse_version_code(env.get("UNITEDU_PLAN_VERSION_CODE", ""))
    if code is None:
        input_errors.append(error("version-code-invalid", "versionCode 必须是不带前导零的十进制正整数。"))

    sha = env.get("UNITEDU_PLAN_SHA", "")
    if SHA40.fullmatch(sha) is None:
        input_errors.append(error("sha-invalid", "提交 SHA 必须是 40 位小写十六进制，不接受短 SHA。"))
        sha_ok = None
    else:
        sha_ok = sha

    notes, note_errors = load_notes(env)
    input_errors.extend(note_errors)
    for key, name in CHANNEL_ENV.items():
        if name not in env:
            input_errors.append(error("channels-missing", f"缺少显式通道设置 {key}。不会改用缺省值。"))
    report["stages"].append(stage("inputs", input_errors))

    repo = Path(env.get("UNITEDU_PLAN_REPO") or ".").resolve()
    source_errors: list[dict[str, str]] = []
    channel_errors: list[dict[str, str]] = []
    history = []
    props = None
    state = None
    git = None
    before = None
    head = None
    if not repo.is_dir():
        source_errors.append(error("repo-invalid", "计划仓库不存在。"))
    else:
        git = git_factory(repo)
        try:
            before = snapshot(git)
            head = before[0]
        except GitError:
            source_errors.append(error("repo-invalid", "计划仓库不是可读取的 git 工作区。"))
            git = None
    if git is not None and before is not None:
        if before[1] != "":
            source_errors.append(error("dirty-tree", "工作区不干净。计划只认当前提交，不会代为提交或清理。"))
        if sha_ok is not None and sha_ok != head:
            source_errors.append(error("sha-mismatch", "给定 SHA 与 HEAD 不一致。"))
        github_sha = env.get("UNITEDU_PLAN_GITHUB_SHA", "")
        if github_sha:
            if SHA40.fullmatch(github_sha) is None or github_sha != head or github_sha != sha_ok:
                source_errors.append(error("github-sha-mismatch", "工作流检出的 SHA 与计划 SHA 或 HEAD 不一致。"))
        if sha_ok is not None and sha_ok == head:
            try:
                verified = git.run(["rev-parse", "--verify", f"{sha_ok}^{{commit}}"]).strip()
            except GitError:
                verified = ""
            if verified != sha_ok:
                source_errors.append(error("sha-mismatch", "HEAD 不能解析为给定提交。"))
        gradle_path = repo / "app" / "build.gradle.kts"
        props_path = repo / "gradle.properties"
        try:
            identity_name, identity_code = read_gradle_identity(gradle_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, ValueError):
            identity_name, identity_code = None, None
            source_errors.append(error("identity-unreadable", "无法从 app/build.gradle.kts 读出唯一的 versionName 与 versionCode。"))
        if identity_name is not None and version_ok is not None and identity_name != version_ok:
            source_errors.append(error("version-mismatch", "版本号与 app/build.gradle.kts 的 versionName 不一致。"))
        if identity_code is not None and code is not None and identity_code != code:
            source_errors.append(error("version-code-mismatch", "versionCode 与 app/build.gradle.kts 不一致。"))
        try:
            props = read_channel_properties(props_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, ValueError):
            channel_errors.append(error("channels-unreadable", "gradle.properties 缺少唯一的三项通道地址。拒绝使用 build.gradle.kts 的缺省回落。"))
        if props is not None:
            for key, name in CHANNEL_ENV.items():
                if name not in env:
                    continue
                if env[name] != props[key]:
                    channel_errors.append(error("channels-mismatch", f"{key} 与提交里的值不一致。不会悄悄改用另一套地址。"))
                    continue
                targets = split_urls(props[key])
                if key == "unitedu.updateUrls" and not targets:
                    channel_errors.append(error("channels-url-rejected", "unitedu.updateUrls 是空的。拒绝回落到代码里的单条 GitHub 地址。"))
                for url in targets:
                    problem = url_problem(url)
                    if problem:
                        channel_errors.append(error("channels-url-rejected", problem))
                        break
    report["stages"].append(stage("source", source_errors))
    if git is None:
        report["stages"].append(stage("channels", [], "not-run"))
    else:
        report["stages"].append(stage("channels", channel_errors))

    state, state_errors = load_state(env)
    history.extend(state_errors)
    if state is not None:
        history.extend(history_errors(state, version_ok, code, channel_ok))
    report["stages"].append(stage("history", history))

    capability_errors: list[dict[str, str]] = []
    enable_attempt = any(env.get(name, "").strip() for name in ENABLE_SWITCHES)
    if release_capability() != "absent":
        capability_errors.append(error("capability-absent", "发布能力必须保持 absent。"))
    if mode == "execute" or enable_attempt or mode not in ("dry-run", "execute"):
        capability_errors.append(error(
            "capability-absent",
            "本阶段没有签名和发布能力。拒绝执行，开关也不会打开发布。",
        ))
    capability = stage(
        "capability",
        capability_errors,
        "refused" if capability_errors else "passed",
    )
    if capability["status"] == "passed":
        capability["detail"] = "已确认发布能力为 absent。dry-run 不能打开发布。"
    report["stages"].append(capability)

    effect_errors: list[dict[str, str]] = []
    if git is not None and before is not None:
        try:
            after = snapshot(git)
        except GitError:
            effect_errors.append(error("effects-changed", "结束后无法再读取 git 状态。"))
        else:
            if after != before:
                effect_errors.append(error("effects-changed", "dry-run 期间 git HEAD、工作区或 tag 发生了变化。"))
    elif before is None:
        effect_errors.append(error("effects-changed", "开始时没有拿到 git 快照，不能证明没有副作用。"))
    report["stages"].append(stage("effects", effect_errors))

    passed = all(item["status"] == "passed" for item in report["stages"])
    if (
        passed
        and mode == "dry-run"
        and not enable_attempt
        and release_capability() == "absent"
        and props is not None
        and state is not None
        and notes is not None
        and version_ok is not None
        and code is not None
        and channel_ok is not None
        and sha_ok is not None
    ):
        report["plan"] = build_plan(version_ok, code, channel_ok, sha_ok, notes, props, state)
        report["ok"] = True
    return report


def fatal_report(exc: BaseException) -> dict:
    report = blank_report("unrecognized")
    report["stages"] = [
        stage("inputs", [error("internal-error", "计划程序内部错误，没有执行发布。")]),
        stage("source", [], "not-run"),
        stage("channels", [], "not-run"),
        stage("history", [], "not-run"),
        stage("capability", [error("capability-absent", "发布能力缺失。")], "refused"),
        stage("effects", [], "not-run"),
    ]
    report["internalError"] = type(exc).__name__
    return report


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if args in (["-h"], ["--help"]):
        print(USAGE, end="")
        return 0
    if args:
        print("cloud_release_plan.py 不接收位置参数；输入只来自环境变量。", file=sys.stderr)
        return 2
    try:
        report = build_report(dict(os.environ))
    except Exception as exc:
        traceback.print_exc(file=sys.stderr)
        report = fatal_report(exc)
    json.dump(report, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")
    for item in report["stages"]:
        print(f"{item['name']}: {item['status']}", file=sys.stderr)
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
