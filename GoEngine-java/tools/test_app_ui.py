#!/usr/bin/env python3
"""KataGo UI regression test via ADB (Python 3.8+, no extra dependencies).

Targets the GoEngine-java application on the rk3588-engine branch.
Runs in the ordinary Android app process; the optional GPU smoke Activity
in :katago_gpu is NOT used. This script does not modify app data.

Examples:
  python3 tools/test_app_ui.py -s 8719e18a71a2a66c --backends cpu,gpu,cpu --size 9 --moves 2 --undo
  python3 tools/test_app_ui.py -s 192.168.0.184:5555 --backends cpu --moves 1
  python3 tools/test_app_ui.py -s 8719e18a71a2a66c --backends cpu,gpu --rounds 10 --moves 1
"""

import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "com.badukai.java"
ACTIVITY = PACKAGE + "/com.badukai.MainActivity"
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
TURN = re.compile(r"第\s*(\d+)\s*手")
BACKEND_TEXT = {"cpu": "CPU/Eigen JNI", "gpu": "GPU/OpenCL JNI"}


class TestFailure(RuntimeError):
    pass


class AppTest:
    def __init__(self, args):
        self.args = args
        self.serial = args.serial
        self.out = Path(args.out).resolve()
        self.out.mkdir(parents=True, exist_ok=True)
        self.started = time.monotonic()
        self.events = []
        self.last_xml = None
        self.last_root = None
        self.last_root_time = 0.0
        self.screen_size_cached = None
        self.density_cache = None
        self.failure = None
        self.cache_file = Path("build/app_ui_test/ui_geometry_cache.json").resolve()
        self.geometry = {}
        self.geometry_key = None
        self.selected_size = None
        self.selected_backend = None
        self.player_black = True  # MainActivity initializes playerColor=BLACK.
        self.use_log_wait = False
        self.log_baseline = None
        self.ui_dumps = 0

    def log(self, message):
        elapsed = round(time.monotonic() - self.started, 3)
        self.events.append({"seconds": elapsed, "message": message})
        print(f"[{elapsed:8.2f}s] {message}", flush=True)

    def adb(self, *argv, timeout=30, binary=False, check=True):
        command = [self.args.adb, "-s", self.serial] + list(argv)
        try:
            proc = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise TestFailure("ADB 失败: " + repr(exc)) from exc
        if check and proc.returncode != 0:
            raise TestFailure("ADB 命令失败: " + " ".join(command) + "\n" +
                              proc.stderr.decode("utf-8", "replace")[-1000:])
        return proc.stdout if binary else proc.stdout.decode("utf-8", "replace").strip()

    def connect(self):
        try:
            proc = subprocess.run([self.args.adb, "devices"], capture_output=True, text=True,
                                  check=True, timeout=15)
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            raise TestFailure("无法列出 ADB 设备: " + repr(exc)) from exc
        devices = [line.split()[0] for line in proc.stdout.splitlines()[1:]
                   if len(line.split()) >= 2 and line.split()[1] == "device"]
        if self.serial:
            if self.serial not in devices:
                raise TestFailure(f"设备 {self.serial} 不在线，在线设备: {devices}")
        else:
            if len(devices) != 1:
                raise TestFailure(f"当前在线设备 {devices}，请用 -s 指定设备")
            self.serial = devices[0]
        self.log("连接设备 " + self.serial)
        self.init_geometry_cache()

    def init_geometry_cache(self):
        """Persistent local geometry, keyed by device, display and APK update."""
        w, h = self.screen_size()
        scale = self.density()
        meta = self.adb("shell", "dumpsys", "package", PACKAGE, timeout=15, check=False)
        m = re.search(r"lastUpdateTime=([^\r\n]+)", meta)
        updated = m.group(1).strip() if m else "unknown"
        self.geometry_key = f"v2|{self.serial}|{w}x{h}|{scale:.4f}|{updated}"
        try:
            data = json.loads(self.cache_file.read_text(encoding="utf-8"))
            if data.get("key") == self.geometry_key:
                self.geometry = data.get("bounds", {})
                self.log(f"已加载本地 UI 缓存：{len(self.geometry)} 个控件/菜单位置")
            else:
                self.log("APK 或显示环境变化，重新建立 UI 缓存")
        except (OSError, ValueError, AttributeError):
            self.log("首次运行，建立 UI 坐标缓存")

    def store_geometry(self, key, bounds):
        m = BOUNDS.fullmatch(bounds) if isinstance(bounds, str) else None
        if not m:
            return
        values = list(map(int, m.groups()))
        if values[2] <= values[0] or values[3] <= values[1]:
            return
        if self.geometry.get(key) == values:
            return
        self.geometry[key] = values
        self.cache_file.parent.mkdir(parents=True, exist_ok=True)
        temp = self.cache_file.with_suffix(".tmp")
        temp.write_text(json.dumps({"key": self.geometry_key, "bounds": self.geometry},
                                   ensure_ascii=False, indent=2), encoding="utf-8")
        temp.replace(self.cache_file)

    def cached_center(self, key):
        bounds = self.geometry.get(key)
        if bounds is None or len(bounds) != 4:
            return None
        x1, y1, x2, y2 = bounds
        w, h = self.screen_size()
        if not (0 <= x1 < x2 <= w and 0 <= y1 < y2 <= h):
            return None
        return (x1 + x2) // 2, (y1 + y2) // 2

    def remember_screen(self, root):
        keys = ("aiBattleButton", "sizeSpinner", "blackRadio", "backendSpinner",
                "startGameButton", "difficultySpinner", "boardView",
                "newRoundButton", "undoButton", "passButton")
        for key in keys:
            item = self.node(root, rid=key)
            if item is not None:
                self.store_geometry(key, item.get("bounds"))

    def read_log_state(self):
        # Do not include MainActivity:I as a second filter. Android logcat
        # resolves duplicate tag specs by keeping the last priority; using
        # MainActivity:D alone includes both DEBUG and INFO events.
        raw = self.adb("logcat", "-d", "-s", "MainActivity:D",
                       "AndroidRuntime:E", "*:S", timeout=15)
        lines = raw.splitlines()
        backend = []
        for line in lines:
            m = re.search(r"Game backend requested=(CPU|GPU) actual=(\S+(?: JNI)?) ready=(true|false)", line)
            if m:
                backend.append((m.group(1), m.group(2), m.group(3) == "true"))
        return {
            "backend": backend,
            "turns": sum("render in, message=轮到你了" in line for line in lines),
            "ai_moves": sum("handleAiMove in" in line for line in lines),
            "thinking": sum("render in, message=AI 思考中" in line for line in lines),
            "undo_done": sum("render in, message=已悔棋，轮到你了" in line for line in lines),
            "fatal": any("FATAL EXCEPTION" in line for line in lines),
            "debug_active": any("render in, message=" in line for line in lines),
        }

    def wait_log(self, predicate, timeout, description):
        deadline = time.monotonic() + timeout
        progress = time.monotonic() + 8
        while time.monotonic() < deadline:
            state = self.read_log_state()
            if state["fatal"]:
                raise TestFailure("APP 发生 Java 崩溃：" + description)
            result = predicate(state)
            if result is not None and result is not False:
                return result
            if time.monotonic() >= progress:
                self.log("等待日志：" + description)
                progress = time.monotonic() + 8
            time.sleep(0.25)
        raise TestFailure(f"{description}，日志等待超过 {timeout}s")

    def hierarchy(self, fresh=False):
        # UIAutomator can take seconds to export a hierarchy. One snapshot is
        # reusable across several reads unless the UI was touched or timed out.
        if not fresh and self.last_root is not None and time.monotonic() - self.last_root_time < 1.5:
            return self.last_root
        self.ui_dumps += 1
        data = self.adb("exec-out", "sh", "-c",
                        "uiautomator dump /sdcard/katago_test_ui.xml >/dev/null 2>&1 && cat /sdcard/katago_test_ui.xml",
                        timeout=18, binary=True)
        start = data.find(b"<?xml")
        if start < 0:
            raise TestFailure("UIAutomator 未输出 XML；请检查手机是否解锁")
        data = data[start:]
        try:
            root = ET.fromstring(data)
        except ET.ParseError as exc:
            raise TestFailure("Android UI XML 无法解析: " + str(exc)) from exc
        self.last_xml = data
        self.last_root = root
        self.last_root_time = time.monotonic()
        self.remember_screen(root)
        return root

    @staticmethod
    def node(root, rid=None, text=None, enabled=None, last=False):
        found = []
        for item in root.iter("node"):
            if rid is not None and not item.get("resource-id", "").endswith("/" + rid):
                continue
            if text is not None and text not in (item.get("text", ""), item.get("content-desc", "")):
                continue
            if enabled is not None and (item.get("enabled") == "true") != enabled:
                continue
            if item.get("bounds") in ("", "[0,0][0,0]"):
                continue
            found.append(item)
        return (found[-1] if last else found[0]) if found else None

    @staticmethod
    def center(item):
        m = BOUNDS.fullmatch(item.get("bounds", ""))
        if not m:
            raise TestFailure("控件没有 bounds: " + repr(item.attrib))
        x1, y1, x2, y2 = map(int, m.groups())
        return ((x1 + x2) // 2, (y1 + y2) // 2)

    def poll(self, callback, timeout, description):
        started = time.monotonic()
        end = started + timeout
        next_progress = started + 10
        detail = ""
        attempts = 0
        while time.monotonic() < end:
            try:
                root = self.hierarchy(fresh=(attempts > 0))
                attempts += 1
                result = callback(root)
                # XML leaf nodes (e.g. Button) are falsy in ElementTree even
                # when found. Treat only None and literal False as no result.
                if result is not None and result is not False:
                    return result
                detail = " ".join(n.get("text", "") for n in root.iter("node")
                                  if n.get("text"))[-350:]
            except TestFailure as exc:
                detail = str(exc)
                if "模型" in detail or "回退" in detail or "后端" in detail:
                    raise
            now = time.monotonic()
            if now >= next_progress:
                self.log(f"等待: {description}；已过 {int(now - started)}s；界面: {detail[:160]}")
                next_progress = now + 10
            time.sleep(1)
        raise TestFailure(f"{description}，等待 {timeout}s；最近界面: {detail}")

    def tap(self, x, y):
        self.last_root = None
        self.adb("shell", "input", "tap", str(x), str(y), timeout=15)

    def click(self, rid=None, text=None, timeout=20):
        if rid in ("newRoundButton", "undoButton", "passButton", "backendSpinner",
                   "sizeSpinner", "aiBattleButton"):
            point = self.cached_center(rid)
            if point is not None:
                self.tap(*point)
                self.log(f"点击 {rid}（本地缓存），坐标={point}")
                return
        # Some devices expose the visible label of a dialog Button, but omit
        # its app resource-id. Try the exact ID first, then an exact known
        # label. Never guess coordinates when neither is in the hierarchy.
        labels = {
            "startGameButton": "开始对局",
            "newRoundButton": "新开一局",
            "undoButton": "悔棋",
            "passButton": "停一手",
        }
        label = rid or text
        matched_by = [None]

        def locate(root):
            item = self.node(root, rid=rid, text=text)
            if item is not None:
                matched_by[0] = "id" if rid else "text"
                return item
            alternate = labels.get(rid) if rid else None
            if not alternate:
                return None
            for candidate in root.iter("node"):
                if candidate.get("text", "").strip() != alternate:
                    continue
                if not BOUNDS.fullmatch(candidate.get("bounds", "")):
                    continue
                matched_by[0] = "label"
                return candidate
            return None

        item = self.poll(locate, timeout, f"未找到控件或可见文字: {label}")
        x, y = self.center(item)
        width, height = self.screen_size()
        if not (0 <= x < width and 0 <= y < height):
            raise TestFailure(f"{label} 在屏幕外，禁止盲点坐标: ({x}, {y})")
        if matched_by[0] == "label":
            self.log(f"{label} 未暴露资源ID，使用文字 {item.get('text')} 定位")
        if item.get("enabled") != "true":
            self.log(f"{label} 的 UI enabled={item.get('enabled')}，尝试点击可见控件")
        self.store_geometry(rid, item.get("bounds")) if rid else None
        self.tap(x, y)
        self.log(f"点击 {label}，坐标=({x}, {y})")

    def start_game_from_layout(self):
        """Click start button, preferring cached geometry over UIAutomator."""
        point = self.cached_center("startGameButton")
        if point is not None:
            self.tap(*point)
            self.log(f"点击开始对局（本地缓存），坐标={point}")
            return
        # dialog_new_game.xml: difficultySpinner row center to button center
        # = half-row 25dp + margin 12dp + half-button 23dp = 60dp.
        root = self.hierarchy()
        button = self.node(root, rid="startGameButton")
        if button is None:
            button = self.node(root, text="开始对局")
        if button is not None:
            x, y = self.center(button)
            how = "实际控件"
        else:
            anchor = self.node(root, rid="difficultySpinner")
            if anchor is None:
                anchor = self.node(root, text="棋力等级")
            if anchor is None:
                raise TestFailure("无法找到 startGameButton，也没有棋力等级锚点；请查看 failed.xml")
            x = self.screen_size()[0] // 2
            y = self.center(anchor)[1] + round(60 * self.density())
            how = "dialog_new_game.xml: 棋力等级行中心向下60dp"
        width, height = self.screen_size()
        if not (0 < x < width and 0 < y < height):
            raise TestFailure(f"开始对局坐标超出 RK3588 屏幕: ({x}, {y})")
        self.log(f"点击开始对局（{how}），坐标=({x},{y})")
        self.tap(x, y)

    def spinner_selected(self, root, rid):
        """Return visible current Spinner label when exposed by UIAutomator."""
        spinner = self.node(root, rid=rid)
        if spinner is None:
            raise TestFailure("找不到下拉控件: " + rid)
        m = BOUNDS.fullmatch(spinner.get("bounds", ""))
        if m is None:
            return ""
        sx1, sy1, sx2, sy2 = map(int, m.groups())
        labels = []
        for item in root.iter("node"):
            label = item.get("text", "").strip()
            bounds = BOUNDS.fullmatch(item.get("bounds", ""))
            if not label or bounds is None:
                continue
            x1, y1, x2, y2 = map(int, bounds.groups())
            if sx1 <= (x1 + x2) // 2 <= sx2 and sy1 <= (y1 + y2) // 2 <= sy2:
                labels.append(label)
        return labels[-1] if labels else spinner.get("text", "").strip()

    def ensure_black(self):
        """MainActivity starts as BLACK; no UI dump needed in a controlled run."""
        if self.player_black:
            self.log("执黑已由本次会话确认，跳过 UI 检查")
            return
        root = self.hierarchy()
        radio = self.node(root, rid="blackRadio")
        if radio is None:
            raise TestFailure("新局对话框缺少 blackRadio；界面内容=" +
                              " ".join(n.get("text", "") for n in root.iter("node") if n.get("text"))[-200:])
        if radio.get("checked") == "true":
            self.player_black = True
            self.log("执黑已选中，无需重复点击")
            return
        x, y = self.center(radio)
        self.tap(x, y)
        self.poll(lambda r: (self.node(r, rid="blackRadio") is not None and
                             self.node(r, rid="blackRadio").get("checked") == "true"),
                  8, "点击执黑后未能确认选中")
        self.player_black = True
        self.log("已选择执黑")

    def spinner(self, rid, option):
        if rid == "sizeSpinner" and self.selected_size is not None:
            selected = f"{self.selected_size}路"
        elif rid == "backendSpinner" and self.selected_backend is not None:
            selected = self.selected_backend + (" / OpenCL" if self.selected_backend == "GPU" else " / Eigen")
        else:
            selected = self.spinner_selected(self.hierarchy(), rid)
        if selected == option or (option == "CPU / Eigen" and selected.startswith("CPU / Eigen")):
            self.log(f"{rid} 已选择 {selected}，跳过下拉菜单")
            return

        choice_key = f"choice:{rid}:{selected}->{option}"
        cached = self.cached_center(choice_key)
        if cached is not None and self.cached_center(rid) is not None:
            self.click(rid=rid)
            time.sleep(0.12)
            self.tap(*cached)
            self.log(f"选择 {rid}（本地缓存）: {selected} -> {option}，坐标={cached}")
        else:
            self.click(rid=rid)
            self.screen_size()

            def option_point(root):
                for item in reversed(list(root.iter("node"))):
                    label = item.get("text", "").strip() or item.get("content-desc", "").strip()
                    if label != option:
                        continue
                    bounds = BOUNDS.fullmatch(item.get("bounds", ""))
                    if bounds is None:
                        continue
                    x1, y1, x2, y2 = map(int, bounds.groups())
                    x, y = (x1 + x2) // 2, (y1 + y2) // 2
                    w, h = self.screen_size_cached
                    if x1 < x2 and y1 < y2 and 0 <= x < w and 0 <= y < h:
                        self.store_geometry(choice_key, item.get("bounds"))
                        return (x, y)
                return None

            point = self.poll(option_point, 15, f"下拉选项不可见: {rid}={option}")
            self.tap(*point)
            self.log(f"选择 {rid}: {option}，坐标={point}")
        if rid == "sizeSpinner":
            self.selected_size = int(option.replace("路", ""))
        if not getattr(getattr(self, "args", None), "slow_ui", True):
            # The app-start acknowledgment validates the actual selected
            # backend and board size; waiting a brief animation interval
            # avoids another ~2-second UIAutomator export here.
            time.sleep(0.45)
            self.log(f"{rid} 已点击 {option}，等待开局日志确认实际参数")
        else:
            def selected_in_dialog(root):
                if self.node(root, rid="startGameButton") is None:
                    return None
                try:
                    actual = self.spinner_selected(root, rid)
                except TestFailure:
                    return None
                if actual == option or (option == "CPU / Eigen" and actual.startswith("CPU / Eigen")):
                    return True
                return None
            self.poll(selected_in_dialog, 12, f"等待 {rid} 显示已选择 {option}")
            self.log(f"{rid} 已确认选中 {option}，可以继续点击")
        if rid == "sizeSpinner":
            self.selected_size = int(option.replace("路", ""))
        if rid == "backendSpinner":
            self.selected_backend = "GPU" if option.startswith("GPU") else "CPU"
    def text(self, root, rid):
        item = self.node(root, rid=rid)
        return item.get("text", "") if item is not None else ""

    def wait_home(self):
        # The AI-training button lives below the fold in the home ScrollView.
        # UIAutomator may omit it even after the AI is fully initialized.
        # MainActivity writes this explicit log before enabling that button.
        self.log("等待 MainActivity 的引擎完成日志（不依赖屏幕下方的按钮）")
        started = time.monotonic()
        next_progress = started
        while time.monotonic() - started < self.args.start_timeout:
            logs = self.adb("logcat", "-d", "-s", "MainActivity:I", "AndroidRuntime:E", "*:S", timeout=20)
            ready = re.findall(r"Initial engine ready=(true|false)", logs)
            if ready:
                if ready[-1] != "true":
                    raise TestFailure("MainActivity 日志报告首页 AI 初始化失败，请检查 logcat")
                self.log("MainActivity 已报告 Initial engine ready=true")
                prefs = re.findall(r"preload boardSize=(\d+); backend=(CPU|GPU)", logs)
                if prefs:
                    self.selected_size = int(prefs[-1][0])
                    self.selected_backend = prefs[-1][1]
                    self.log(f"已读取 APP 会话设置：{self.selected_size}路，{self.selected_backend}")
                # At this point no game has started yet, so there cannot be
                # any "render in, message=轮到你了" log. Detect DebugLog after
                # start_game_confirmed(), not during home initialization.
                self.use_log_wait = False
                self.log("动态检测将在第一局启动后选择 logcat 或 UIAutomator")
                return
            if "FATAL EXCEPTION" in logs:
                raise TestFailure("引擎启动期间发生 Java 崩溃")
            pid = self.adb("shell", "pidof", PACKAGE, timeout=10, check=False)
            if not pid:
                raise TestFailure("等待首页初始化期间 APP 主进程已退出")
            now = time.monotonic()
            if now >= next_progress:
                self.log(f"首页引擎尚未发出 ready 日志，已等待 {int(now-started)}s")
                next_progress = now + 10
            time.sleep(2)
        raise TestFailure(f"等待 MainActivity 初始化日志超过 {self.args.start_timeout}s")

    def screen_size(self):
        if self.screen_size_cached is not None:
            return self.screen_size_cached
        raw = self.adb("shell", "wm", "size")
        m = re.search(r"Override size:\s*(\d+)x(\d+)", raw)
        if m is None:
            m = re.search(r"Physical size:\s*(\d+)x(\d+)", raw)
        if m is None:
            raise TestFailure("无法读取屏幕尺寸，不能自动滚动")
        self.screen_size_cached = (int(m.group(1)), int(m.group(2)))
        return self.screen_size_cached

    def open_home_ai(self):
        cached = self.cached_center("aiBattleButton")
        if cached is not None:
            self.tap(*cached)
            self.log(f"打开 AI训练（本地缓存），坐标={cached}")
            return
        width, height = self.screen_size()
        self.log("寻找首页 AI训练按钮（如不在当前屏幕，会自动上滑）")
        for attempt in range(9):
            root = self.hierarchy()
            button = self.node(root, rid="aiBattleButton", enabled=True)
            if button is not None:
                x, y = self.center(button)
                if 0 <= x < width and 0 <= y < height:
                    self.tap(x, y)
                    self.log("已进入 AI训练新开一局界面")
                    return
            if attempt == 8:
                break
            self.log(f"按钮未出现在当前屏幕，向上滑动 {attempt+1}/8")
            self.adb("shell", "input", "swipe", str(width//2), str(height*4//5),
                     str(width//2), str(height//3), "380", timeout=15)
            time.sleep(0.7)
        raise TestFailure("首页已启动但找不到可点击的 AI训练 按钮，已保存失败截图和 UI XML")

    def wait_turn(self, size, backend, minimum=1, baseline=None, require_ai=False):
        if self.use_log_wait and baseline is not None:
            def ready_from_log(state):
                if not state["backend"]:
                    return None
                if not require_ai and len(state["backend"]) <= len(baseline["backend"]):
                    return None
                requested, actual, ready = state["backend"][-1]
                expected = BACKEND_TEXT[backend]
                if requested != backend.upper() or actual != expected or not ready:
                    raise TestFailure(f"后端不匹配：请求={backend} 实际={actual} ready={ready}")
                if require_ai and state["ai_moves"] <= baseline["ai_moves"]:
                    return None
                if state["turns"] <= baseline["turns"]:
                    return None
                return minimum
            turn = self.wait_log(ready_from_log, self.args.think_timeout, f"{backend} 第{minimum}手回合")
            self.log(f"{backend.upper()} 就绪，第 {turn} 手（logcat）")
            return turn

        def ready_from_ui(root):
            status = self.text(root, "statusText")
            name = self.text(root, "aiDifficultyText")
            title = self.text(root, "gameTitleText")
            visible = " ".join(n.get("text", "") for n in root.iter("node"))
            if "首次准备棋力模型" in visible:
                raise TestFailure("Human SL 模型尚未准备好，请先在 APP 内下载")
            if "准备失败" in status or "同步失败" in status or "初始化失败" in status:
                raise TestFailure("APP 显示失败: " + status)
            if f"{size}路" not in title:
                return None
            m = TURN.search(title)
            if m and int(m.group(1)) >= minimum and "轮到你了" in status:
                if BACKEND_TEXT[backend] not in name:
                    raise TestFailure(f"请求 {backend}，但界面实际显示 {name}，可能回退了")
                return int(m.group(1))
            return None
        turn = self.poll(ready_from_ui, self.args.think_timeout, f"{backend} 没有进入玩家回合")
        self.log(f"{backend.upper()} 就绪，第 {turn} 手（UI后备）")
        return turn

    def check_process(self):
        main = self.adb("shell", "pidof", PACKAGE, check=False)
        extra = self.adb("shell", "pidof", PACKAGE + ":katago_gpu", check=False)
        if not main:
            raise TestFailure("APP 主进程已经退出")
        if extra:
            raise TestFailure("发现不应出现的 GPU 独立测试进程: " + extra)
        self.log("主进程 PID=" + main + "，没有独立 GPU 进程")

    def density(self):
        if self.density_cache is None:
            raw = self.adb("shell", "wm", "density")
            m = re.search(r"Override density:\s*(\d+)", raw) or re.search(r"Physical density:\s*(\d+)", raw)
            if not m:
                raise TestFailure("无法获得显示密度，拒绝猜测棋盘落点")
            self.density_cache = int(m.group(1)) / 160.0
        return self.density_cache

    def move(self, size, backend, current_turn, index):
        choices = [(2, 2), (size - 3, size - 3), (size - 3, 2),
                   (2, size - 3), (size // 2, size // 2), (1, size // 2)]
        cached = self.geometry.get("boardView")
        if cached is None:
            root = self.hierarchy()
            item = self.node(root, rid="boardView")
            if item is None:
                raise TestFailure("找不到棋盘 boardView，停止点击")
            cached = list(map(int, BOUNDS.fullmatch(item.get("bounds", "")).groups()))
        x1, y1, x2, y2 = cached
        side = min(x2 - x1, y2 - y1)
        outer = 2.0 * self.density()
        cell = (side - 2 * outer) / (size - 1 + 2 * 0.68)
        pad = outer + 0.68 * cell
        for offset in range(len(choices)):
            ix, iy = choices[(index + offset) % len(choices)]
            px, py = round(x1 + pad + ix * cell), round(y1 + pad + iy * cell)
            if not (x1 <= px < x2 and y1 <= py < y2):
                raise TestFailure("棋盘点超出可见区域")
            baseline = self.read_log_state() if self.use_log_wait else None
            self.tap(px, py)
            self.log(f"落子 ({ix},{iy})，屏幕 ({px},{py})" + ("（缓存棋盘）" if cached else ""))
            if self.use_log_wait:
                # If the candidate point is occupied, Android reports the
                # onBoardTap but never enters AI thinking. Check acceptance
                # quickly before waiting for a reply; otherwise a rejected
                # click would waste the entire think_timeout.
                try:
                    self.wait_log(lambda st: st["thinking"] > baseline["thinking"],
                                  2.5, f"确认落子 ({ix},{iy}) 已被 APP 接受")
                except TestFailure as exc:
                    if "日志等待超过" not in str(exc):
                        raise
                    self.log(f"落子 ({ix},{iy}) 未被接受，换下一个交叉点")
                    continue
                return self.wait_turn(size, backend, current_turn + 2,
                                      baseline=baseline, require_ai=True)
            time.sleep(0.45)
            title = self.text(self.hierarchy(), "gameTitleText")
            m = TURN.search(title)
            if m and int(m.group(1)) > current_turn:
                return self.wait_turn(size, backend, current_turn + 2)
            self.log(f"尝试 ({ix},{iy}) 未落子，换交叉点")
        raise TestFailure("未能在任何候选位置落子")

    def screenshot(self, filename):
        png = self.adb("exec-out", "screencap", "-p", binary=True, timeout=20)
        if png.startswith(b"\x89PNG"):
            (self.out / filename).write_bytes(png)
            self.log("截图 " + filename)

    def diagnostics(self, label):
        # Always capture the current hierarchy; do not upload a stale cached
        # XML snapshot from a previous popup or spinner selection.
        try:
            self.hierarchy(fresh=True)
        except TestFailure as exc:
            self.log("故障时无法刷新 UI XML: " + str(exc)[:180])
        try:
            self.screenshot(label + ".png")
        except TestFailure as exc:
            self.log("无法截图: " + str(exc))
        if self.last_xml is not None:
            (self.out / (label + ".xml")).write_bytes(self.last_xml)
        try:
            data = self.adb("logcat", "-d", "-v", "time", timeout=45)
            (self.out / (label + "_logcat.txt")).write_text(data, encoding="utf-8")
            filters = ("STARTUP_TIMING", "Game backend requested=", "Initial engine ready=",
                       "FATAL EXCEPTION", "Fatal signal", "AndroidRuntime", "KataGoEngine",
                       "MainActivity", "JNI ", "DEBUG")
            lines = [line for line in data.splitlines() if any(x in line for x in filters)]
            (self.out / (label + "_summary.log")).write_text(
                "\n".join(lines[-500:]) + "\n", encoding="utf-8")
        except TestFailure as exc:
            self.log("无法提取 logcat: " + str(exc))

    def wait_new_game_dialog(self):
        """Do not tap cached Spinner coordinates before AlertDialog is visible.

        Even if ADB input tap has returned, Android can still be creating the
        popup window. Exporting the tree once here serves as a synchronization
        barrier; the rest of the dialog uses the local coordinate cache.
        """
        def dialog_ready(root):
            return (self.node(root, rid="backendSpinner") is not None and
                    self.node(root, rid="startGameButton") is not None)
        self.poll(dialog_ready, 12, "等待新开一局弹窗完全显示")
        self.log("新开一局弹窗已显示")

    def start_game_confirmed(self, backend):
        """Confirm the start click was received by MainActivity.

        The Dialog Button logs "Saved new-game preference" synchronously at
        the start of its listener. This is a more reliable acknowledgment than
        assuming an adb tap succeeded. Retry once only if no new start
        command was observed; never double-start an accepted game.
        """
        def launches():
            raw = self.adb("logcat", "-d", "-s", "MainActivity:I", "*:S", timeout=15)
            return re.findall(r"Saved new-game preference boardSize=(\d+) backend=(CPU|GPU)", raw)

        before = launches()
        for attempt in range(2):
            if attempt:
                root = self.hierarchy(fresh=True)
                if self.node(root, rid="startGameButton") is None:
                    raise TestFailure("开始对局未收到确认日志，但弹窗已经关闭；请查看故障日志")
                actual = self.spinner_selected(root, "backendSpinner")
                desired = "GPU / OpenCL" if backend == "gpu" else "CPU / Eigen"
                if not actual.startswith(desired):
                    raise TestFailure(f"开始按钮重试前后端被改变：预期 {desired}，实际 {actual}")
                self.log("开始对局首次点击未生效，弹窗仍在，重试按钮一次")
            self.start_game_from_layout()
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                observed = launches()
                if len(observed) > len(before):
                    size, actual_backend = observed[-1]
                    if int(size) != self.args.size or actual_backend != backend.upper():
                        raise TestFailure(f"新局参数不符：{size}路，{actual_backend}")
                    self.log(f"MainActivity 已确认开始：{size}路 {actual_backend}")
                    if not self.args.slow_ui and not self.use_log_wait:
                        # DebugLog may be emitted a few ms after the Info
                        # acknowledgment. Probe briefly, never wait for a
                        # full UI dump just to decide which backend to use.
                        until = time.monotonic() + 0.9
                        while time.monotonic() < until:
                            if self.read_log_state()["debug_active"]:
                                self.use_log_wait = True
                                self.log("MainActivity DebugLog 已就绪，切换到 logcat 快速检测")
                                break
                            time.sleep(0.12)
                        if not self.use_log_wait:
                            self.log("APP 未输出对局 DebugLog，继续使用 UIAutomator 后备检测")
                    return
                time.sleep(0.25)
        raise TestFailure("两次点击开始对局后 MainActivity 均未响应（未发现 Saved new-game preference 日志）")

    def case(self, backend, index):
        self.log(f"=== Case {index}: {backend.upper()} / {self.args.size}x{self.args.size} ===")
        if index == 1:
            self.open_home_ai()
        else:
            self.click(rid="newRoundButton")
        self.wait_new_game_dialog()
        self.spinner("sizeSpinner", f"{self.args.size}路")
        self.ensure_black()
        if self.cached_center("backendSpinner") is None:
            root = self.hierarchy()
            if self.node(root, rid="backendSpinner") is None:
                raise TestFailure("新开一局界面找不到 backendSpinner")
        # Check actual dropdown selection instead of trusting enabled=false.
        # An APK without the GPU library has no GPU option, in which case
        # spinner() will fail with the observed list of available options.
        self.spinner("backendSpinner", "GPU / OpenCL" if backend == "gpu" else "CPU / Eigen")
        # Record counters before the click, even if log mode is not active
        # yet: the first started game can enable DebugLog fast detection.
        baseline = self.read_log_state() if not self.args.slow_ui else None
        self.start_game_confirmed(backend)
        turn = self.wait_turn(self.args.size, backend, baseline=baseline)
        self.check_process()
        for n in range(self.args.moves):
            turn = self.move(self.args.size, backend, turn, n)
        if self.args.undo and self.args.moves >= 2:
            baseline = self.read_log_state() if self.use_log_wait else None
            self.click(rid="undoButton")
            prior = turn
            if self.use_log_wait:
                self.wait_log(lambda state: state["undo_done"] > baseline["undo_done"],
                              self.args.think_timeout, "悔棋完成")
            else:
                def undone(root):
                    m = TURN.search(self.text(root, "gameTitleText"))
                    return bool(m and int(m.group(1)) <= prior - 2
                                and "轮到你了" in self.text(root, "statusText"))
                self.poll(undone, self.args.think_timeout, "悔棋没有完成")
            turn -= 2
            self.log("悔棋通过")
        if self.args.pass_move:
            self.click(rid="passButton")
            self.wait_turn(self.args.size, backend, turn + 2)
            self.log("停一手通过")
        self.check_process()
        self.screenshot(f"case_{index:03d}_{backend}.png")
        self.log(f"=== Case {index}: PASS ===")

    def run(self):
        self.connect()
        installed = self.adb("shell", "pm", "path", PACKAGE, check=False)
        if self.args.install:
            apk = Path(self.args.install)
            if not apk.is_file():
                raise TestFailure("APK 不存在: " + str(apk))
            self.log("安装 APK: " + str(apk))
            self.adb("install", "-r", str(apk), timeout=180)
        elif not installed.startswith("package:"):
            raise TestFailure("没有安装 " + PACKAGE + "，使用 --install 指定 APK")
        self.adb("shell", "am", "force-stop", PACKAGE)
        self.adb("logcat", "-c", check=False)
        self.adb("shell", "am", "start", "-W", "-n", ACTIVITY, timeout=35)
        self.log("启动 APP")
        self.wait_home()
        index = 0
        for _ in range(self.args.rounds):
            for backend in self.args.backends.split(","):
                index += 1
                self.case(backend, index)
        self.diagnostics("final")
        self.log(f"全部 PASS；本次 UIAutomator 导出次数={self.ui_dumps}")


    def upload_report(self, status, failure):
        """Upload raw RK3588 test diagnostics to the separate GitHub results branch.

        This GitHub repository is PUBLIC. No redaction is performed, as
        requested. Do not use this mode on a device with private data.
        Uses the existing Git remote credentials; no GitHub token in code.
        The local worktree, index, and current branch stay unchanged.
        """
        if self.args.no_upload:
            self.log("自动上传已关闭（--no-upload）")
            return
        remote, branch = self.args.upload_remote, self.args.upload_branch
        if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_-]{0,60}", branch):
            raise TestFailure("上传分支名无效")
        root = Path(__file__).resolve().parents[2]

        def git(*params, input_data=None, env=None, timeout=35):
            cmd = ["git", "-C", str(root)] + list(params)
            options = dict(os.environ)
            options["GIT_TERMINAL_PROMPT"] = "0"
            if env:
                options.update(env)
            try:
                proc = subprocess.run(cmd, input=input_data, stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE, timeout=timeout, env=options)
            except (OSError, subprocess.TimeoutExpired) as exc:
                raise TestFailure("自动上传 git 调用失败: " + repr(exc)) from exc
            if proc.returncode != 0:
                info = proc.stderr.decode("utf-8", "replace")[-350:]
                raise TestFailure("自动上传 git 命令失败 (" + params[0] + "): " + info)
            return proc.stdout.decode("utf-8", "replace").strip()

        remote_url = git("remote", "get-url", remote)
        if not re.search(r"(^|[:/])rstg00po54/go(?:\.git)?$", remote_url):
            raise TestFailure("上传已阻止：Git remote 不是 rstg00po54/go")
        timestamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        name = timestamp + "_" + str(os.getpid())
        report_path = "runs/" + name + ".json"
        phases, backend_results = [], []
        for file in sorted(self.out.glob("*_summary.log")):
            content = file.read_text(encoding="utf-8", errors="replace")
            for phase, ms in re.findall(r"STARTUP_TIMING phase=([A-Za-z0-9_]+) ms=([0-9.]+)", content):
                phases.append({"phase": phase, "ms": float(ms)})
            for requested, actual, ready in re.findall(
                    r"Game backend requested=(CPU|GPU) actual=(CPU/Eigen JNI|GPU/OpenCL JNI|PIE/ProcessBuilder) ready=(true|false)", content):
                backend_results.append({"requested": requested, "actual": actual, "ready": ready == "true"})
        report = {
            "schema": 3, "status": status, "timestamp_utc": timestamp,
            "ui_dumps": self.ui_dumps, "cached_controls": len(self.geometry),
            "serial": self.serial, "backends": self.args.backends,
            "rounds": self.args.rounds, "size": self.args.size,
            "moves": self.args.moves, "undo": self.args.undo, "pass": self.args.pass_move,
            "failure": failure, "events": self.events,
            "startup_timings": phases, "game_backends": backend_results,
            "note": "PUBLIC and NOT REDACTED, diagnostics under runs/" + name + "/"
        }
        items = [(report_path, (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))]
        # Attach original UI tree, screenshot and logs so failures can be
        # diagnosed from GitHub alone. Cap each artifact to avoid giant pushes.
        for file in sorted(self.out.iterdir()):
            if not file.is_file() or file.name == "report.json":
                continue
            if not (file.name.endswith((".xml", ".png", ".log", "_logcat.txt"))):
                continue
            if file.stat().st_size > 12 * 1024 * 1024:
                self.log("文件超过 12MB，仅保留本地：" + file.name)
                continue
            items.append(("runs/" + name + "/" + file.name, file.read_bytes()))
        self.log(f"上传未脱敏诊断数据到公开仓库：{len(items)} 个文件")
        with tempfile.TemporaryDirectory(prefix="katago_ui_git_") as directory:
            env = {"GIT_INDEX_FILE": str(Path(directory) / "index")}
            heads = git("ls-remote", "--heads", remote, "refs/heads/" + branch, timeout=45)
            parent = heads.split()[0] if heads else None
            if parent:
                git("fetch", "--no-tags", "--quiet", "--depth=1", remote, parent, timeout=60)
                git("read-tree", parent + "^{tree}", env=env)
            else:
                git("read-tree", "--empty", env=env)
            for name_in_repo, payload in items:
                blob = git("hash-object", "-w", "--stdin", input_data=payload)
                git("update-index", "--add", "--cacheinfo",
                    "100644," + blob + "," + name_in_repo, env=env)
            tree = git("write-tree", env=env)
            cmd = ["-c", "user.name=KataGo UI Test", "-c", "user.email=katago-ui-test@users.noreply.github.com",
                   "commit-tree", tree]
            if parent:
                cmd += ["-p", parent]
            cmd += ["-m", "UI test " + status + " " + timestamp]
            commit = git(*cmd)
            git("push", remote, commit + ":refs/heads/" + branch, timeout=90)
        link = "https://github.com/rstg00po54/go/blob/" + branch + "/" + report_path
        self.log("测试报告及诊断文件已上传：" + link)
        return link


def main():
    parser = argparse.ArgumentParser(description="ADB automatic KataGo app UI regression test")
    parser.add_argument("-s", "--serial", default=os.getenv("ADB_SERIAL"))
    parser.add_argument("--adb", default=os.getenv("ADB", "adb"))
    parser.add_argument("--backends", default="cpu,gpu,cpu", help="cpu,gpu,cpu 或 cpu")
    parser.add_argument("--rounds", type=int, default=1, help="重复整组后端测试次数")
    parser.add_argument("--size", type=int, choices=(9, 11, 13, 15, 19), default=9)
    parser.add_argument("--moves", type=int, default=1)
    parser.add_argument("--undo", action="store_true")
    parser.add_argument("--pass", dest="pass_move", action="store_true")
    parser.add_argument("--install", help="测试前安装 APK 路径")
    parser.add_argument("--start-timeout", type=int, default=300)
    parser.add_argument("--think-timeout", type=int, default=180)
    parser.add_argument("--slow-ui", action="store_true", help="强制旧版 UI 树检测，调试对照用")
    parser.add_argument("--no-upload", action="store_true", help="只保存本地报告，不上传 GitHub")
    parser.add_argument("--upload-remote", default="origin", help="使用现有 git remote 凭据上传")
    parser.add_argument("--upload-branch", default="ui-test-results", help="单独保存报告的分支")
    parser.add_argument("--out", default="build/app_ui_test/" + datetime.datetime.now().strftime("%Y%m%d_%H%M%S"))
    args = parser.parse_args()
    args.backends = ",".join(x.strip().lower() for x in args.backends.split(","))
    if any(x not in BACKEND_TEXT for x in args.backends.split(",")):
        parser.error("--backends 只能用 cpu,gpu")
    if args.moves < 0 or args.rounds < 1:
        parser.error("--moves 必须 >= 0，--rounds 必须 >= 1")
    runner = AppTest(args)
    status, failure = "PASS", None
    try:
        runner.run()
    except KeyboardInterrupt:
        status, failure = "INTERRUPTED", "用户按 Ctrl+C 中断测试"
        runner.log("收到 Ctrl+C，停止测试并保存已完成的结果")
        try:
            runner.diagnostics("interrupted")
        except (TestFailure, KeyboardInterrupt) as diagnostic_exc:
            runner.log("中断时无法保存完整诊断: " + str(diagnostic_exc)[:150])
    except TestFailure as exc:
        status, failure = "FAIL", str(exc)
        runner.log("失败: " + failure)
        try:
            runner.diagnostics("failed")
        except (TestFailure, KeyboardInterrupt) as diagnostic_exc:
            runner.log("故障诊断未完成: " + str(diagnostic_exc)[:150])
    except Exception as exc:
        status, failure = "ERROR", type(exc).__name__ + ": " + str(exc)
        runner.log("脚本异常: " + failure[:250])
    finally:
        (runner.out / "report.json").write_text(json.dumps({
            "status": status, "ui_dumps": runner.ui_dumps, "serial": runner.serial, "backends": args.backends,
            "rounds": args.rounds, "size": args.size, "moves": args.moves,
            "undo": args.undo, "pass": args.pass_move, "failure": failure,
            "events": runner.events
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        runner.log("本地报告: " + str(runner.out))
        try:
            runner.upload_report(status, failure)
        except (TestFailure, KeyboardInterrupt) as upload_exc:
            runner.log("自动上传失败，本地报告仍保留：" + str(upload_exc)[:350])
    return 0 if status == "PASS" else 130 if status == "INTERRUPTED" else 1


if __name__ == "__main__":
    sys.exit(main())
