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
        self.density_cache = None

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

    def hierarchy(self):
        self.adb("shell", "uiautomator", "dump", "/sdcard/katago_test_ui.xml", timeout=25)
        data = self.adb("exec-out", "cat", "/sdcard/katago_test_ui.xml", binary=True)
        start = data.find(b"<?xml")
        if start >= 0:
            data = data[start:]
        try:
            root = ET.fromstring(data)
        except ET.ParseError as exc:
            raise TestFailure("Android UI XML 无法解析: " + str(exc)) from exc
        self.last_xml = data
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
        end = time.monotonic() + timeout
        detail = ""
        while time.monotonic() < end:
            try:
                root = self.hierarchy()
                result = callback(root)
                if result:
                    return result
                detail = " ".join(n.get("text", "") for n in root.iter("node")
                                  if n.get("text"))[-350:]
            except TestFailure as exc:
                detail = str(exc)
                if "模型" in detail or "回退" in detail or "后端" in detail:
                    raise
            time.sleep(1)
        raise TestFailure(f"{description}，等待 {timeout}s；最近界面: {detail}")

    def tap(self, x, y):
        self.adb("shell", "input", "tap", str(x), str(y), timeout=15)

    def click(self, rid=None, text=None, timeout=20):
        item = self.poll(lambda r: self.node(r, rid=rid, text=text, enabled=True),
                         timeout, f"控件不可点击: {rid or text}")
        self.tap(*self.center(item))
        self.log("点击 " + (rid or text))

    def spinner(self, rid, option):
        self.click(rid=rid)
        item = self.poll(lambda r: self.node(r, text=option, enabled=True, last=True),
                         15, f"选项不存在: {option}")
        self.tap(*self.center(item))
        self.log(f"选择 {rid}: {option}")

    def text(self, root, rid):
        item = self.node(root, rid=rid)
        return item.get("text", "") if item is not None else ""

    def wait_home(self):
        def ready(root):
            status = self.text(root, "statusText")
            if "启动失败" in status:
                raise TestFailure("首页引擎初始化失败: " + status)
            return self.node(root, rid="aiBattleButton", enabled=True)
        self.poll(ready, self.args.start_timeout, "首页 AI 尚未就绪")
        self.log("首页 AI 已就绪")

    def wait_turn(self, size, backend, minimum=1):
        def ready(root):
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
        turn = self.poll(ready, self.args.think_timeout, f"{backend} 没有进入玩家回合")
        self.log(f"{backend.upper()} 就绪，第 {turn} 手")
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
        # The coordinates below are grid intersections, not screen pixels.
        choices = [(2, 2), (size - 3, size - 3), (size - 3, 2),
                   (2, size - 3), (size // 2, size // 2), (1, size // 2)]
        for offset in range(len(choices)):
            ix, iy = choices[(index + offset) % len(choices)]
            root = self.hierarchy()
            item = self.node(root, rid="boardView")
            if item is None:
                raise TestFailure("找不到棋盘 boardView，停止点击")
            m = BOUNDS.fullmatch(item.get("bounds", ""))
            if not m:
                raise TestFailure("棋盘 bounds 无效")
            x1, y1, x2, y2 = map(int, m.groups())
            side = min(x2 - x1, y2 - y1)
            outer = 2.0 * self.density()
            cell = (side - 2 * outer) / (size - 1 + 2 * 0.68)
            pad = outer + 0.68 * cell
            px, py = round(x1 + pad + ix * cell), round(y1 + pad + iy * cell)
            if not (x1 <= px < x2 and y1 <= py < y2):
                raise TestFailure("棋盘点超出可见区域")
            self.tap(px, py)
            time.sleep(0.7)
            title = self.text(self.hierarchy(), "gameTitleText")
            m = TURN.search(title)
            if m and int(m.group(1)) > current_turn:
                self.log(f"落子 ({ix},{iy})，屏幕 ({px},{py})")
                return self.wait_turn(size, backend, current_turn + 2)
            self.log(f"尝试 ({ix},{iy}) 未落子，换交叉点")
        raise TestFailure("未能在任何候选位置落子")

    def screenshot(self, filename):
        png = self.adb("exec-out", "screencap", "-p", binary=True, timeout=20)
        if png.startswith(b"\x89PNG"):
            (self.out / filename).write_bytes(png)
            self.log("截图 " + filename)

    def diagnostics(self, label):
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

    def case(self, backend, index):
        self.log(f"=== Case {index}: {backend.upper()} / {self.args.size}x{self.args.size} ===")
        root = self.hierarchy()
        self.click(rid="newRoundButton" if self.node(root, rid="gameTitleText") else "aiBattleButton")
        self.spinner("sizeSpinner", f"{self.args.size}路")
        self.click(rid="blackRadio")
        root = self.hierarchy()
        gpu = self.node(root, rid="backendSpinner")
        if gpu is None:
            raise TestFailure("新开一局界面找不到 backendSpinner")
        if backend == "gpu" and gpu.get("enabled") != "true":
            raise TestFailure("GPU 选项不可用，APK 可能没有打包 libkatago_gpu.so")
        if gpu.get("enabled") == "true":
            self.spinner("backendSpinner", "GPU / OpenCL" if backend == "gpu" else "CPU / Eigen")
        self.click(rid="startGameButton")
        turn = self.wait_turn(self.args.size, backend)
        self.check_process()
        for n in range(self.args.moves):
            turn = self.move(self.args.size, backend, turn, n)
        if self.args.undo and self.args.moves >= 2:
            self.click(rid="undoButton")
            prior = turn
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
        self.log("全部 PASS")


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
    parser.add_argument("--out", default="build/app_ui_test/" + datetime.datetime.now().strftime("%Y%m%d_%H%M%S"))
    args = parser.parse_args()
    args.backends = ",".join(x.strip().lower() for x in args.backends.split(","))
    if any(x not in BACKEND_TEXT for x in args.backends.split(",")):
        parser.error("--backends 只能用 cpu,gpu")
    if args.moves < 0 or args.rounds < 1:
        parser.error("--moves 必须 >= 0，--rounds 必须 >= 1")
    runner = AppTest(args)
    status = "PASS"
    try:
        runner.run()
    except TestFailure as exc:
        status = "FAIL"
        runner.log("失败: " + str(exc))
        runner.diagnostics("failed")
    finally:
        (runner.out / "report.json").write_text(json.dumps({
            "status": status, "serial": runner.serial, "backends": args.backends,
            "rounds": args.rounds, "size": args.size, "moves": args.moves,
            "undo": args.undo, "pass": args.pass_move, "events": runner.events
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        runner.log("报告: " + str(runner.out))
    return 0 if status == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
