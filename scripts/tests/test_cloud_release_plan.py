#!/usr/bin/env python3
"""Self-checks for the offline cloud-release plan.

These checks are a self-assessment of the local planner and toolchain script.
They do not dispatch GitHub Actions, sign an APK, or accept a release.
"""

from __future__ import annotations

import json
import os
import stat
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import cloud_release_plan as plan  # noqa: E402

PLAN = ROOT / "scripts" / "cloud_release_plan.py"
ENV_SH = ROOT / "scripts" / "env.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "cloud-release-plan.yml"
OBSERVED = None


def _observe_root():
    def out(*args):
        return subprocess.check_output(args, cwd=ROOT)
    return (
        out("git", "rev-parse", "HEAD"),
        out("git", "status", "--porcelain"),
        out("git", "for-each-ref", "refs/tags"),
    )


def setUpModule():
    global OBSERVED
    OBSERVED = _observe_root()


def tearDownModule():
    current = _observe_root()
    if current != OBSERVED:
        raise AssertionError("planner tests changed the real repository HEAD, status, or tags")


def git(repo, *args):
    subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True, text=True)


def write(path: Path, text: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


class Repo(tempfile.TemporaryDirectory):
    def __enter__(self):
        root = Path(super().__enter__())
        self.root = root
        self.repo = root / "repo"
        self.repo.mkdir()
        self.sentinel = root / "pwned"
        self.stub_log = root / "stub.log"
        self.bin = root / "bin"
        self.bin.mkdir()
        self._install_stubs()
        git_init = subprocess.run(["git", "init", "-b", "main", str(self.repo)], check=True, capture_output=True, text=True)
        del git_init
        git(self.repo, "config", "user.email", "cloud-release-plan@example.invalid")
        git(self.repo, "config", "user.name", "cloud-release-plan-test")
        git(self.repo, "config", "commit.gpgsign", "false")
        git(self.repo, "config", "tag.gpgsign", "false")
        self.urls = {
            "unitedu.updateUrls": "https://dl.example/latest.json,https://github.com/example/releases/latest/download/latest.json",
            "unitedu.betaUrls": "https://dl.example/beta.json,https://github.com/example/releases/download/channel-beta/beta.json",
            "unitedu.rollbackUrls": "https://dl.example/rollback.json,https://github.com/example/releases/download/channel-beta/rollback.json",
        }
        self.write_identity("1.3.0", 44)
        self.commit("identity")
        self.sha = subprocess.check_output(["git", "-C", str(self.repo), "rev-parse", "HEAD"], text=True).strip()
        self.notes = root / "notes.txt"
        self.notes.write_text("中文说明\nEnglish notes\n", encoding="utf-8")
        self.state_path = root / "channel-state.json"
        self.write_state(self.baseline_state())
        return self

    def _install_stubs(self):
        body = textwrap.dedent(f"""\
            #!/bin/sh
            printf '%s\\n' "$0 $*" >> {self.stub_log}
            : > {self.sentinel}
            exit 97
            """)
        for name in ("curl", "gh", "wrangler", "gradle", "apksigner", "jarsigner", "touch", "release.sh"):
            path = self.bin / name
            path.write_text(body, encoding="utf-8")
            path.chmod(path.stat().st_mode | stat.S_IEXEC)

    def write_identity(self, version, code):
        write(self.repo / "app" / "build.gradle.kts", textwrap.dedent(f"""\
            val appVersionCode: Int = providers.gradleProperty("versionCodeOverride").orNull?.toInt() ?: {code}

            android {{
                defaultConfig {{
                    versionCode = appVersionCode
                    versionName = "{version}"
                }}
            }}
            """))
        lines = [f"{key}={value}" for key, value in self.urls.items()]
        write(self.repo / "gradle.properties", "\n".join(lines) + "\n")

    def commit(self, message):
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-m", message)

    def baseline_state(self):
        return {
            "tags": ["v1.2.0", "v1.2.1-beta.1", "channel-beta"],
            "releases": ["v1.2.0", "v1.2.1-beta.1", "channel-beta"],
            "manifests": {
                "latest": {"versionCode": 41, "versionName": "1.2.0"},
                "beta": {"versionCode": 42, "versionName": "1.2.1-beta.1"},
                "rollback": {"versionCode": 43, "versionName": "1.2.0"},
            },
        }

    def write_state(self, payload):
        self.state_path.write_text(json.dumps(payload), encoding="utf-8")

    def env(self, **overrides):
        env = os.environ.copy()
        for key in list(env):
            if key.startswith("UNITEDU_PLAN_") or key.startswith("UNITEDU_RELEASE") or key == "UNITEDU_TOOLCHAIN_OS":
                del env[key]
        env.update({
            "UNITEDU_PLAN_REPO": str(self.repo),
            "UNITEDU_PLAN_MODE": "dry-run",
            "UNITEDU_PLAN_VERSION": "1.3.0",
            "UNITEDU_PLAN_VERSION_CODE": "44",
            "UNITEDU_PLAN_SHA": self.sha,
            "UNITEDU_PLAN_NOTES_FILE": str(self.notes),
            "UNITEDU_PLAN_UPDATE_URLS": self.urls["unitedu.updateUrls"],
            "UNITEDU_PLAN_BETA_URLS": self.urls["unitedu.betaUrls"],
            "UNITEDU_PLAN_ROLLBACK_URLS": self.urls["unitedu.rollbackUrls"],
            "UNITEDU_PLAN_CHANNEL_STATE": str(self.state_path),
            "PATH": str(self.bin) + os.pathsep + env.get("PATH", ""),
            "LC_ALL": "C.UTF-8",
        })
        env.update(overrides)
        return env

    def run(self, **overrides):
        proc = subprocess.run(
            [sys.executable, str(PLAN)],
            cwd=self.root,
            env=self.env(**overrides),
            capture_output=True,
            text=True,
        )
        try:
            report = json.loads(proc.stdout)
        except json.JSONDecodeError:
            report = None
        return proc, report

    def refs(self):
        return subprocess.check_output(
            ["git", "-C", str(self.repo), "for-each-ref", "--format=%(objectname) %(refname)", "refs/tags"],
            text=True,
        )


class PlanTest(unittest.TestCase):
    def assert_codes(self, report, *codes):
        found = [item["code"] for stage in report["stages"] for item in stage["errors"]]
        for code in codes:
            self.assertIn(code, found, report)
        self.assertFalse(report["ok"])
        self.assertIsNone(report["plan"])
        self.assertEqual(report["releaseCapability"], "absent")
        self.assertFalse(report["approvalEnforced"])
        self.assertIn("delete-tag", report["refusedActions"])
        self.assertIn("retry-live-tag", report["refusedActions"])

    def test_valid_stable_and_beta_plans_do_not_publish(self):
        with Repo() as repo:
            before = repo.refs()
            proc, report = repo.run()
            self.assertEqual(proc.returncode, 0, proc.stderr + proc.stdout)
            self.assertTrue(report["ok"])
            self.assertEqual(report["releaseCapability"], "absent")
            self.assertFalse(report["approvalEnforced"])
            self.assertEqual(report["effects"], [])
            self.assertEqual(report["plan"]["channel"], "stable")
            self.assertTrue(report["plan"]["github"]["updatesLatest"])
            self.assertFalse(report["plan"]["github"]["prerelease"])
            self.assertEqual(report["plan"]["github"]["manifestFiles"], ["latest.json"])
            self.assertIsNone(report["plan"]["rollback"])
            self.assertFalse(report["plan"]["github"]["performed"])
            self.assertEqual(report["plan"]["r2"], {"action": "not-modified", "performed": False})
            self.assertFalse(report["plan"]["channelSettings"]["fallbacksApplied"])
            self.assertEqual(report["plan"]["notes"], "中文说明\nEnglish notes")
            self.assertEqual(repo.refs(), before)
            self.assertFalse(repo.sentinel.exists())
            self.assertEqual(repo.stub_log.read_text() if repo.stub_log.exists() else "", "")

            repo.write_identity("1.3.0-beta.1", 44)
            repo.commit("beta identity")
            repo.sha = subprocess.check_output(["git", "-C", str(repo.repo), "rev-parse", "HEAD"], text=True).strip()
            proc, beta = repo.run(UNITEDU_PLAN_VERSION="1.3.0-beta.1", UNITEDU_PLAN_SHA=repo.sha)
            self.assertEqual(proc.returncode, 0, proc.stdout)
            body = beta["plan"]
            self.assertEqual(body["channel"], "beta")
            self.assertTrue(body["github"]["prerelease"])
            self.assertFalse(body["github"]["updatesLatest"])
            self.assertEqual(body["github"]["manifestFiles"], ["rollback.json", "beta.json"])
            self.assertNotIn("latest.json", body["github"]["intendedOrder"])
            self.assertEqual(body["rollback"]["versionCode"], 45)
            self.assertEqual(body["rollback"]["versionName"], "1.2.0")
            self.assertFalse(body["rollback"]["createsTag"])
            self.assertFalse(body["rollback"]["performed"])
            self.assertFalse(body["intendedPublishPerformed"])
            self.assertEqual(repo.refs(), before)
            self.assertFalse(repo.sentinel.exists())

    def test_notes_gaps_controls_and_shell_text(self):
        with Repo() as repo:
            proc, report = repo.run(UNITEDU_PLAN_NOTES_FILE="")
            self.assert_codes(report, "notes-missing")
            self.assertNotEqual(proc.returncode, 0)

            blank = repo.root / "blank.txt"
            blank.write_text(" \n\t", encoding="utf-8")
            _, report = repo.run(UNITEDU_PLAN_NOTES_FILE=str(blank))
            self.assert_codes(report, "notes-blank")

            binary = repo.root / "nul.txt"
            binary.write_bytes(b"hello\0")
            _, report = repo.run(UNITEDU_PLAN_NOTES_FILE=str(binary))
            self.assert_codes(report, "notes-unsafe")

            scary = repo.root / "scary;touch.txt"
            scary.write_text(f"$(touch {repo.sentinel})\n`touch {repo.sentinel}`\n; touch {repo.sentinel}\n", encoding="utf-8")
            proc, report = repo.run(UNITEDU_PLAN_NOTES_FILE=str(scary))
            self.assertEqual(proc.returncode, 0, proc.stdout)
            self.assertIn("$(touch", report["plan"]["notes"])
            self.assertFalse(repo.sentinel.exists())
            self.assertFalse(repo.stub_log.exists())

            _, report = repo.run(UNITEDU_PLAN_VERSION="1.3.0;touch")
            self.assert_codes(report, "version-invalid")
            self.assertFalse(repo.sentinel.exists())

            inline = repo.env()
            inline.pop("UNITEDU_PLAN_NOTES_FILE")
            inline["UNITEDU_PLAN_NOTES"] = f"`touch {repo.sentinel}`"
            proc = subprocess.run([sys.executable, str(PLAN)], cwd=repo.root, env=inline, capture_output=True, text=True)
            report = json.loads(proc.stdout)
            self.assertEqual(proc.returncode, 0, proc.stdout)
            self.assertEqual(report["plan"]["notes"], f"`touch {repo.sentinel}`")
            self.assertFalse(repo.sentinel.exists())

    def test_version_sha_and_channel_mismatches(self):
        with Repo() as repo:
            _, report = repo.run(UNITEDU_PLAN_VERSION="1.3.1")
            self.assert_codes(report, "version-mismatch")
            _, report = repo.run(UNITEDU_PLAN_VERSION_CODE="45")
            self.assert_codes(report, "version-code-mismatch")
            _, report = repo.run(UNITEDU_PLAN_VERSION_CODE="044")
            self.assert_codes(report, "version-code-invalid")
            _, report = repo.run(UNITEDU_PLAN_SHA="abcd")
            self.assert_codes(report, "sha-invalid")
            _, report = repo.run(UNITEDU_PLAN_SHA=repo.sha.upper())
            self.assert_codes(report, "sha-invalid")
            other = "0123456789abcdef0123456789abcdef01234567"
            self.assertNotEqual(other, repo.sha)
            _, report = repo.run(UNITEDU_PLAN_SHA=other)
            self.assert_codes(report, "sha-mismatch")
            _, report = repo.run(UNITEDU_PLAN_GITHUB_SHA=other)
            self.assert_codes(report, "github-sha-mismatch")
            (repo.repo / "dirty").write_text("x", encoding="utf-8")
            _, report = repo.run()
            self.assert_codes(report, "dirty-tree")
            (repo.repo / "dirty").unlink()

            repo.urls["unitedu.updateUrls"] = repo.urls["unitedu.updateUrls"].split(",", 1)[0]
            _, report = repo.run()
            self.assert_codes(report, "channels-mismatch")
            repo.urls["unitedu.updateUrls"] = "https://dl.example/latest.json,https://github.com/example/releases/latest/download/latest.json"
            repo.urls["unitedu.betaUrls"] = "http://127.0.0.1/beta.json"
            repo.write_identity("1.3.0", 44)
            repo.commit("bad beta url")
            repo.sha = subprocess.check_output(["git", "-C", str(repo.repo), "rev-parse", "HEAD"], text=True).strip()
            _, report = repo.run(
                UNITEDU_PLAN_SHA=repo.sha,
                UNITEDU_PLAN_BETA_URLS="http://127.0.0.1/beta.json",
            )
            self.assert_codes(report, "channels-url-rejected")
            self.assertFalse(repo.sentinel.exists())

            repo.urls["unitedu.betaUrls"] = f"https://dl.example/$(touch {repo.sentinel})"
            repo.write_identity("1.3.0", 44)
            repo.commit("shell url")
            repo.sha = subprocess.check_output(["git", "-C", str(repo.repo), "rev-parse", "HEAD"], text=True).strip()
            _, report = repo.run(
                UNITEDU_PLAN_SHA=repo.sha,
                UNITEDU_PLAN_BETA_URLS=repo.urls["unitedu.betaUrls"],
            )
            self.assert_codes(report, "channels-url-rejected")
            self.assertFalse(repo.sentinel.exists())

    def test_version_code_conflicts_partial_history_and_refusal(self):
        with Repo() as repo:
            _, report = repo.run(UNITEDU_PLAN_VERSION_CODE="43")
            # 43 mismatches the committed 44 before history, so check history with a matching identity.
            self.assert_codes(report, "version-code-mismatch")
            repo.write_identity("1.3.0", 43)
            repo.commit("code 43")
            repo.sha = subprocess.check_output(["git", "-C", str(repo.repo), "rev-parse", "HEAD"], text=True).strip()
            _, report = repo.run(UNITEDU_PLAN_SHA=repo.sha, UNITEDU_PLAN_VERSION_CODE="43")
            self.assert_codes(report, "code-not-monotonic", "code-reserved")

            repo.write_identity("1.3.0-beta.1", 43)
            repo.commit("beta code 43")
            repo.sha = subprocess.check_output(["git", "-C", str(repo.repo), "rev-parse", "HEAD"], text=True).strip()
            _, report = repo.run(
                UNITEDU_PLAN_SHA=repo.sha,
                UNITEDU_PLAN_VERSION="1.3.0-beta.1",
                UNITEDU_PLAN_VERSION_CODE="43",
            )
            self.assert_codes(report, "code-not-monotonic", "code-reserved")

            repo.write_identity("1.2.4", 44)
            repo.commit("partial identity")
            repo.sha = subprocess.check_output(["git", "-C", str(repo.repo), "rev-parse", "HEAD"], text=True).strip()
            git(repo.repo, "tag", "v9.9.9")
            before = repo.refs()
            state = repo.baseline_state()
            state["tags"].append("v1.2.4")
            repo.write_state(state)
            proc, report = repo.run(UNITEDU_PLAN_SHA=repo.sha, UNITEDU_PLAN_VERSION="1.2.4")
            self.assertNotEqual(proc.returncode, 0)
            self.assert_codes(report, "tag-partial")
            self.assertTrue(any("不会删除、覆盖或重试" in item["message"] for stage in report["stages"] for item in stage["errors"]))
            self.assertEqual(repo.refs(), before)
            self.assertEqual(
                subprocess.check_output(["git", "-C", str(repo.repo), "rev-parse", "v9.9.9"], text=True).strip(),
                repo.sha,
            )

            state = repo.baseline_state()
            state["tags"].append("v1.2.4")
            state["releases"].append("v1.2.4")
            repo.write_state(state)
            _, report = repo.run(UNITEDU_PLAN_SHA=repo.sha, UNITEDU_PLAN_VERSION="1.2.4")
            self.assert_codes(report, "tag-duplicate")
            self.assertEqual(repo.refs(), before)

            state = repo.baseline_state()
            state["manifests"]["rollback"] = None
            repo.write_state(state)
            _, report = repo.run(UNITEDU_PLAN_SHA=repo.sha, UNITEDU_PLAN_VERSION="1.2.5", UNITEDU_PLAN_VERSION_CODE="44")
            # identity is still 1.2.4, so version mismatch also fires; pair error must still be present
            self.assertIn("rollback-pair", [item["code"] for stage in report["stages"] for item in stage["errors"]])

            repo.write_identity("1.2.5", 44)
            repo.commit("pair identity")
            repo.sha = subprocess.check_output(["git", "-C", str(repo.repo), "rev-parse", "HEAD"], text=True).strip()
            _, report = repo.run(UNITEDU_PLAN_SHA=repo.sha, UNITEDU_PLAN_VERSION="1.2.5")
            self.assert_codes(report, "rollback-pair")
            self.assertEqual(repo.refs(), before)

    def test_execute_switch_and_missing_state_have_no_side_effects(self):
        with Repo() as repo:
            before = repo.refs()
            proc, report = repo.run(UNITEDU_PLAN_MODE="execute")
            self.assertNotEqual(proc.returncode, 0)
            self.assert_codes(report, "capability-absent")
            self.assertEqual(next(item for item in report["stages"] if item["name"] == "capability")["status"], "refused")
            self.assertEqual(next(item for item in report["stages"] if item["name"] == "history")["status"], "passed")
            self.assertEqual(next(item for item in report["stages"] if item["name"] == "effects")["status"], "passed")
            self.assertEqual(repo.refs(), before)

            proc, report = repo.run(UNITEDU_PLAN_ALLOW_PUBLISH="1", UNITEDU_RELEASE_ENABLED="true")
            self.assertNotEqual(proc.returncode, 0)
            self.assert_codes(report, "capability-absent")
            self.assertEqual(repo.refs(), before)
            self.assertFalse(repo.sentinel.exists())

            proc, report = repo.run(UNITEDU_PLAN_CHANNEL_STATE="")
            self.assert_codes(report, "state-missing")
            self.assertEqual(repo.refs(), before)

            state = repo.baseline_state()
            state["publish"] = True
            repo.write_state(state)
            _, report = repo.run()
            self.assert_codes(report, "state-invalid")

            state = repo.baseline_state()
            state["tags"] = ["v1.10.0", "v1.9.0", "channel-beta"]
            state["releases"] = ["v1.10.0", "v1.9.0", "channel-beta"]
            state["manifests"]["latest"] = {"versionCode": 41, "versionName": "1.9.0"}
            state["manifests"]["beta"] = None
            state["manifests"]["rollback"] = None
            repo.write_state(state)
            _, report = repo.run()
            self.assert_codes(report, "history-inconsistent")

    def test_git_wrapper_blocks_writes_and_help_takes_no_positionals(self):
        with Repo() as repo:
            wrapper = repo.bin / "git"
            wrapper.write_text(textwrap.dedent(f"""\
                #!/bin/sh
                printf '%s\\n' "$*" >> {repo.stub_log}
                cmd=
                prev=
                for arg in "$@"; do
                  if [ "$prev" = "-C" ]; then prev=; continue; fi
                  case "$arg" in
                    -C) prev=-C; continue ;;
                    -*) continue ;;
                  esac
                  cmd=$arg
                  break
                done
                case "$cmd" in
                  rev-parse|status|for-each-ref) exec /usr/bin/git "$@" ;;
                  *) echo "denied $cmd" >&2; exit 97 ;;
                esac
                """), encoding="utf-8")
            wrapper.chmod(wrapper.stat().st_mode | stat.S_IEXEC)
            proc, report = repo.run()
            self.assertEqual(proc.returncode, 0, proc.stderr + proc.stdout)
            log = repo.stub_log.read_text(encoding="utf-8")
            for line in log.splitlines():
                self.assertNotRegex(line, r"(^| )(tag|push|fetch|ls-remote)( |$)")
            self.assertFalse(repo.sentinel.exists())
            self.assertTrue(report["ok"])
            self.assertFalse(plan.git_args_allowed(["tag", "-d", "v1.3.0"]))
            self.assertFalse(plan.git_args_allowed(["push", "origin", "v1.3.0"]))
            self.assertEqual(plan.release_capability(), "absent")

            help_proc = subprocess.run([sys.executable, str(PLAN), "--help"], capture_output=True, text=True)
            self.assertEqual(help_proc.returncode, 0)
            self.assertIn("UNITEDU_PLAN_SHA", help_proc.stdout)
            bad = subprocess.run([sys.executable, str(PLAN), "1.3.0"], capture_output=True, text=True)
            self.assertEqual(bad.returncode, 2)
            self.assertEqual(bad.stdout, "")

    def test_committed_files_parse_and_release_help_remains(self):
        name, code = plan.read_gradle_identity((ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8"))
        text = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn(f'versionName = "{name}"', text)
        self.assertIn(f"?: {code}", text)
        props = plan.read_channel_properties((ROOT / "gradle.properties").read_text(encoding="utf-8"))
        for key in plan.CHANNEL_KEYS:
            self.assertTrue(props[key])
            for url in plan.split_urls(props[key]):
                self.assertIsNone(plan.url_problem(url), url)
        self.assertGreaterEqual(len(plan.split_urls(props["unitedu.updateUrls"])), 2)
        source = PLAN.read_text(encoding="utf-8")
        self.assertNotIn("shell=True", source)
        self.assertNotIn("os.system", source)
        self.assertNotIn("eval(", source)
        self.assertEqual(source.count("def release_capability"), 1)
        self.assertNotIn('return "present"', source)

        syntax = subprocess.run(["bash", "-n", str(ENV_SH)], capture_output=True, text=True)
        self.assertEqual(syntax.returncode, 0, syntax.stderr)
        direct = subprocess.run(["bash", str(ENV_SH)], capture_output=True, text=True)
        self.assertEqual(direct.returncode, 2)
        release = subprocess.run(["bash", str(ROOT / "scripts" / "release.sh"), "--help"], cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(release.returncode, 0, release.stderr)
        self.assertIn("--dry-run", release.stdout)


class EnvShTest(unittest.TestCase):
    def source(self, toolchain_os, java_home=None, android_home=None, gradle=False, home="/tmp/unitedu-env-home"):
        env = os.environ.copy()
        for key in ("JAVA_HOME", "ANDROID_HOME", "UNITEDU_TOOLCHAIN_OS"):
            env.pop(key, None)
        env["HOME"] = home
        script = ["set -u", "set +e"]
        if java_home:
            env["JAVA_HOME"] = java_home
        if android_home:
            env["ANDROID_HOME"] = android_home
        if gradle:
            bindir = Path(home) / "bin"
            bindir.mkdir(parents=True, exist_ok=True)
            stub = bindir / "gradle"
            stub.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            stub.chmod(stub.stat().st_mode | stat.S_IEXEC)
            env["PATH"] = str(bindir) + os.pathsep + env.get("PATH", "")
        if toolchain_os != "unset":
            env["UNITEDU_TOOLCHAIN_OS"] = toolchain_os
        script.append(f'source "{ENV_SH}"')
        script.append("status=$?")
        script.append("printf 'status=%s\\n' \"$status\"")
        script.append("printf 'java=%s\\n' \"${JAVA_HOME-unset}\"")
        script.append("printf 'android=%s\\n' \"${ANDROID_HOME-unset}\"")
        script.append("printf 'path=%s\\n' \"$PATH\"")
        return subprocess.run(["bash", "--noprofile", "--norc", "-c", "\n".join(script)], env=env, capture_output=True, text=True)

    def fields(self, proc):
        found = {}
        for line in proc.stdout.splitlines():
            key, value = line.split("=", 1)
            found[key] = value
        return found

    def test_linux_keeps_explicit_toolchain_and_real_uname(self):
        home = tempfile.mkdtemp()
        java = str(Path(home) / "jdk")
        android = str(Path(home) / "sdk")
        Path(java).mkdir()
        Path(android).mkdir()
        proc = self.source("Linux", java, android, gradle=True, home=home)
        got = self.fields(proc)
        self.assertEqual(got["status"], "0", proc.stderr)
        self.assertEqual(got["java"], java)
        self.assertEqual(got["android"], android)
        self.assertFalse(got["path"].startswith(str(Path(home) / "Library" / "Gradle")))
        self.assertTrue(got["path"].startswith(str(Path(home) / "bin")))

        host = subprocess.check_output(["uname", "-s"], text=True).strip()
        proc = self.source("unset", java, android, gradle=True, home=home)
        got = self.fields(proc)
        if host == "Darwin":
            self.assertEqual(got["status"], "0", proc.stderr)
            self.assertEqual(got["java"], str(Path(home) / "Library" / "Java" / "jdk-17" / "Contents" / "Home"))
        else:
            self.assertNotEqual(host, "Darwin")
            self.assertEqual(got["status"], "0", proc.stderr)
            self.assertEqual(got["java"], java)
            self.assertEqual(got["android"], android)

        missing = self.source("Linux", home=home)
        got = self.fields(missing)
        self.assertEqual(got["status"], "1", missing.stderr)
        self.assertEqual(got["java"], "unset")
        self.assertEqual(got["android"], "unset")
        self.assertIn("不会填写或覆盖", missing.stderr)

    def test_darwin_assignment_matches_historical_paths(self):
        home = "/tmp/unitedu-darwin-home"
        proc = self.source("Darwin", java_home="/tmp/other-jdk", android_home="/tmp/other-sdk", home=home)
        got = self.fields(proc)
        self.assertEqual(got["status"], "0", proc.stderr)
        self.assertEqual(got["java"], home + "/Library/Java/jdk-17/Contents/Home")
        self.assertEqual(got["android"], home + "/Library/Android/sdk")
        self.assertTrue(got["path"].startswith(home + "/Library/Gradle/gradle-8.14.5/bin:" + got["java"] + "/bin:" + got["android"] + "/platform-tools:" + got["android"] + "/emulator:"))

        bad = self.source("Linux;touch /tmp/unitedu-pwn", home=home)
        self.assertEqual(self.fields(bad)["status"], "1")
        self.assertFalse(Path("/tmp/unitedu-pwn").exists())


class WorkflowTest(unittest.TestCase):
    def test_workflow_is_manual_read_only_and_secret_free(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        for banned in (
            "secrets.",
            "pull_request",
            "self-hosted",
            "continue-on-error",
            "github.token",
            "workflow_run",
            "workflow_call",
            "id-token",
            "contents: write",
            "environment:",
            "upload-artifact",
        ):
            self.assertNotIn(banned, text)
        for line in text.splitlines():
            stripped = line.strip()
            self.assertFalse(stripped.startswith("push:"))
            self.assertFalse(stripped.startswith("release:"))
            self.assertFalse(stripped.startswith("schedule:"))
            if stripped.startswith("run:"):
                self.assertNotIn("${{", stripped)
            if "uses:" in stripped:
                self.assertEqual(
                    stripped,
                    "uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4",
                )
        self.assertIn("workflow_dispatch:", text)
        self.assertIn("contents: read", text)
        self.assertIn("persist-credentials: false", text)
        self.assertIn("ubuntu-24.04", text)
        self.assertIn("python3 scripts/cloud_release_plan.py", text)
        self.assertIn("dry-run", text)
        self.assertIn("execute", text)
        self.assertNotIn("scripts/release.sh", text)
        self.assertNotIn("scripts/env.sh", text)


if __name__ == "__main__":
    unittest.main()
