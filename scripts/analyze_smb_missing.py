#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
================================================================================
EhViewer SMB 漫画未入库与孤儿目录分析工具 (SMB Comic Missing & Orphan Analyzer)
================================================================================

【背景与用途】
    在使用 EhViewer 下载漫画至远程 SMB / NAS 共享存储时，可能会因为换机迁移、
    误删本地下载记录、重装系统或异常中断等情况，导致 SMB 远程存储上残留了大量
    漫画文件夹，但在手机 App 的“下载列表”中却没有任何记录（即“孤儿”文件）。

    手动逐本核对成百上千本漫画既耗时又极易遗漏。本工具通过比对 EhViewer 的
    应用备份文件（或数据库）与 SMB 目录下的所有文件夹名称列表，自动化完成：
    1. 快速排查哪些 SMB 文件夹未收录在当前 App 的下载列表中；
    2. 深度检索未收录漫画是否存在于“浏览历史”或“本地收藏”中，并还原原始画廊名与网址；
    3. 导出轻量级的缺失列表供用户快速全选、批量移动或删除，同时生成详细的 Excel 表格。

--------------------------------------------------------------------------------
【支持的输入文件及获取方法】
--------------------------------------------------------------------------------
1. EhViewer 数据库 / 备份文件 (二选一):
   - 完整备份包 (.zip / .ehbak):
     获取方式: 在 EhViewer App 中进入【设置】->【高级设置】->【数据备份与恢复】->【导出备份】。
     说明: 导出后直接将该压缩包传给本脚本即可，工具会自动解压并定位内部的 `eh.db`，无需手动解压。
   - 原始 SQLite 数据库文件 (.db / .sqlite):
     获取方式: 旧版本 EhViewer 直接导出的 `eh.export.db`，或从备份包手动解压出的 `eh.db`。
   - 工具内置解析的数据表:
     - `DOWNLOADS`: 主下载表（包含 GID、画廊标题、日文标题、下载状态、标签分类等）
     - `DOWNLOAD_DIRNAME`: 目录名映射表（记录画廊 GID 与 SMB 实际文件夹名的对应关系）
     - `HISTORY`: 浏览历史表（用于辅助找回曾经看过但未保留下载项的漫画）
     - `LOCAL_FAVORITES`: 本地收藏表（用于辅助匹配已收藏但未下载的漫画）

2. SMB 文件夹名称清单文件 (.txt):
   - 内容要求: 纯文本文件，每行一个漫画文件夹名（或完整路径）。
   - 获取方式（在挂载了 SMB 路径的电脑上执行以下任意命令）:
     - Windows PowerShell (推荐):
         (Get-ChildItem -Directory -Path "Z:\Manga").Name | Out-File -Encoding utf8 smb_folders.txt
     - Windows CMD 命令行:
         cd /d Z:\Manga
         dir /ad /b > smb_folders.txt
     - Linux / 群晖 / TrueNAS 终端:
         ls -1 /volume1/manga > smb_folders.txt
   - 编码兼容: 自动识别 UTF-8、UTF-8 with BOM、GBK、GB18030、UTF-16 等多种常见编码。
   - 格式容错: 自动去除首尾引号、空格、反斜杠，若每行包含绝对路径会自动截取最后的文件夹名，自动跳过空行与 `#` 注释行。

--------------------------------------------------------------------------------
【比对机制与匹配优先级】
--------------------------------------------------------------------------------
对于 SMB 目录中的每个文件夹名，按以下优先级智能匹配：
1. 目录名精确匹配 (DIRNAME):
   比对 `DOWNLOAD_DIRNAME.DIRNAME`（App 在下载和归档时记录的准确文件夹命名）。
2. 目录名模糊匹配 (DIRNAME_FUZZY):
   将下划线 `_`、连字符 `-`、空格统一归一化，比对处理由于非法字符过滤造成的命名差异。
3. GID 正则提取比对 (GID):
   支持从以下常见命名规则中提取画廊 GID 并比对 `DOWNLOADS` 与 `DOWNLOAD_DIRNAME` 表：
   - 开头数字: `1234567-Title`、`1234567_Title`、`1234567 Title`、纯 `1234567`
   - 括号包围: `[1234567] Title`、`(1234567) Title`
   - URL 结构: `/g/1234567/`
4. 画廊标题匹配 (TITLE / TITLE_FUZZY):
   无 GID 的文件夹将尝试与画廊英文标题或日文标题进行模糊比对。
5. 关联记录深度追溯:
   若确定不在“下载列表”中，会进一步检索该 GID 是否存在于 `HISTORY`（历史记录）
   或 `LOCAL_FAVORITES`（本地收藏）中。若命中，则标记对应状态并还原其原始画廊名称与原站直达链接。

--------------------------------------------------------------------------------
【四级状态分类】
--------------------------------------------------------------------------------
- 【已在下载库】: 正常记录在 App 下载列表中，显示下载状态（已完成/仅记录/下载中等）与标签。
- 【不在下载库(在历史中)】: 下载列表无此项，但用户曾在 App 中浏览过，可一键查看原站链接。
- 【不在下载库(在收藏中)】: 下载列表无此项，但用户收藏过该画廊，可一键查看原站链接。
- 【完全未入库】: App 数据库完全没有任何记录的真正“孤儿”文件夹。

--------------------------------------------------------------------------------
【导出的报表文件说明】
--------------------------------------------------------------------------------
默认只生成最核心、最常用的 2 个文件；另外 2 个文件设为可选导出：

★ 核心默认文件 (按需常用):
1. `smb_missing_folders.txt` (不在下载库的清单):
   纯文本，每行一个文件夹名。包含了所有不在下载库的漫画（完全未入库 + 历史/收藏中）。
   用途: 适合直接复制或脚本自动化批量移出、归档或删除。
2. `smb_analysis_report.csv` (完整分析数据大表):
   Excel 友好的 CSV 表格（含 UTF-8 BOM，Excel 双击直接正常打开不乱码）。
   包含列: 文件夹名、提取GID、入库状态、匹配方式、下载状态、标签、画廊标题、日文标题、原站URL、补充说明。
   用途: 方便在 Excel 中筛选核对，点击原站 URL 可直接跳到网页查看该漫画内容。

☆ 附加可选文件 (默认不导出，按需开启):
3. `smb_completely_orphan_folders.txt` (完全未入库的“纯孤儿”清单):
   仅包含既不在下载、也不在历史与收藏的文件夹名（彻底无主）。
   用途: 用于最保守的清理场景，避免误删曾经浏览或收藏过的漫画。
4. `smb_analysis_summary.txt` (总体统计诊断报告):
   格式化纯文本，包含各分类的统计数量、百分比占比及清单明细。

--------------------------------------------------------------------------------
【运行方式与参数示例】
--------------------------------------------------------------------------------
1. GUI 图形界面模式 (推荐，直观方便):
   直接双击运行本脚本，或在终端输入:
     python scripts/analyze_smb_missing.py
     python scripts/analyze_smb_missing.py --gui
   特性:
     - 窗口可视化选择文件
     - KPI 卡片统计展示（总计、未入库、仅历史/收藏、已下载）
     - 分页表格展示与即时搜索框
     - “一键复制未入库文件夹名”按钮（直接写入剪贴板）
     - 导出弹窗支持自由勾选是否输出可选文件

2. CLI 命令行模式 (适合自动化处理与服务器环境):
   基础用法 (默认只导出 missing txt + csv 报表):
     python scripts/analyze_smb_missing.py --db backup.ehbak --txt smb_folders.txt

   指定输出目录:
     python scripts/analyze_smb_missing.py --db backup.zip --txt smb_folders.txt -o ./output

   开启可选文件导出:
     python scripts/analyze_smb_missing.py --db eh.db --txt smb_folders.txt --export-orphan-txt
     python scripts/analyze_smb_missing.py --db eh.db --txt smb_folders.txt --export-summary-txt
     python scripts/analyze_smb_missing.py --db eh.db --txt smb_folders.txt --all-reports

--------------------------------------------------------------------------------
【打包为 EXE (可选)】
--------------------------------------------------------------------------------
如需分发给没有 Python 环境的 Windows 电脑使用:
    pip install pyinstaller
    pyinstaller --onefile --windowed scripts/analyze_smb_missing.py
"""

import argparse
import csv
import io
import os
import re
import sqlite3
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from typing import Dict, List, Optional, Set, Tuple

# Windows 控制台 UTF-8 支持
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
        sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

# ================= 状态与常量定义 =================

STATE_MAP = {
    -1: "无效",
    0: "未下载/仅记录",
    1: "等待下载",
    2: "正在下载",
    3: "已完成",
    4: "下载失败",
    5: "需更新",
    6: "转到新版本"
}

STATUS_IN_DOWNLOADS = "已在下载库"
STATUS_IN_HISTORY = "不在下载库(在历史中)"
STATUS_IN_FAVORITES = "不在下载库(在收藏中)"
STATUS_NOT_FOUND = "完全未入库"

# GID 提取正则表达式：
# 1. 常见目录命名: "1234567-Title" 或 "1234567_Title" 或 "1234567 Title" 或纯 "1234567"
PATTERN_START_GID = re.compile(r'^(\d{1,10})(?:[-_\s]+.*)?$')
# 2. 括号包围格式: "[1234567] Title" 或 "(1234567) Title"
PATTERN_BRACKET_GID = re.compile(r'^[\[\(](\d{1,10})[\]\)]')
# 3. 包含在 URL 或路径中的 GID: "/g/1234567/"
PATTERN_URL_GID = re.compile(r'/g/(\d{1,10})/')


@dataclass
class MatchResult:
    raw_name: str
    cleaned_name: str
    extracted_gid: Optional[int]
    status: str                 # STATUS_*
    match_method: str           # "DIRNAME" | "DIRNAME_FUZZY" | "GID" | "TITLE" | "TITLE_FUZZY" | "NONE"
    db_title: str = ""
    db_title_jpn: str = ""
    download_state: Optional[int] = None
    download_label: str = ""
    token: str = ""
    extra_info: str = ""

    @property
    def eh_url(self) -> str:
        if self.extracted_gid:
            if self.token:
                return f"https://e-hentai.org/g/{self.extracted_gid}/{self.token}/"
            return f"https://e-hentai.org/g/{self.extracted_gid}/"
        return ""

    @property
    def state_str(self) -> str:
        if self.download_state is not None:
            return STATE_MAP.get(self.download_state, f"状态{self.download_state}")
        return ""


# ================= 数据库解析核心 =================

class EhDatabase:
    def __init__(self):
        self.downloads: Dict[int, dict] = {}          # gid -> download_info
        self.download_dirnames: Dict[int, str] = {}   # gid -> dirname
        self.dirname_to_gid: Dict[str, int] = {}      # normalized/fuzzy_dirname -> gid
        self.history: Dict[int, dict] = {}            # gid -> history_info
        self.favorites: Dict[int, dict] = {}          # gid -> favorite_info
        self.title_to_gid: Dict[str, int] = {}        # normalized/fuzzy_title -> gid

    @staticmethod
    def normalize_name(name: str) -> str:
        if not name:
            return ""
        return name.strip().lower()

    @staticmethod
    def fuzzy_name(name: str) -> str:
        if not name:
            return ""
        s = name.strip().lower()
        return re.sub(r'[\s_\-]+', ' ', s)

    def register_dirname(self, dirname: str, gid: int):
        if not dirname:
            return
        self.download_dirnames[gid] = dirname
        norm = self.normalize_name(dirname)
        self.dirname_to_gid[norm] = gid
        fuzzy = self.fuzzy_name(dirname)
        if fuzzy not in self.dirname_to_gid:
            self.dirname_to_gid[fuzzy] = gid

    def register_title(self, title: str, gid: int):
        if not title:
            return
        norm = self.normalize_name(title)
        if norm not in self.title_to_gid:
            self.title_to_gid[norm] = gid
        fuzzy = self.fuzzy_name(title)
        if fuzzy not in self.title_to_gid:
            self.title_to_gid[fuzzy] = gid

    @classmethod
    def load(cls, file_path: str) -> "EhDatabase":
        db = cls()
        if not os.path.exists(file_path):
            raise FileNotFoundError(f"文件不存在: {file_path}")

        # 判断是否为 ZIP / ehbak 容器
        if zipfile.is_zipfile(file_path):
            db._load_from_zip(file_path)
        else:
            db._load_from_sqlite(file_path)

        return db

    def _load_from_zip(self, zip_path: str):
        with zipfile.ZipFile(zip_path, 'r') as z:
            names = z.namelist()
            # 寻找 eh.db，或任何包含 db 的文件
            target_entry = None
            if "eh.db" in names:
                target_entry = "eh.db"
            else:
                for name in names:
                    if name.endswith("eh.db") or (name.endswith(".db") and "cookie" not in name and "stats" not in name):
                        target_entry = name
                        break

            if not target_entry:
                raise ValueError(f"备份包中未找到 eh.db 数据库文件 (包含文件: {names[:5]})")

            # 解压到临时文件读取
            with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as tmp_file:
                tmp_path = tmp_file.name
                tmp_file.write(z.read(target_entry))

            try:
                self._load_from_sqlite(tmp_path)
            finally:
                if os.path.exists(tmp_path):
                    try:
                        os.remove(tmp_path)
                    except Exception:
                        pass

    def _load_from_sqlite(self, sqlite_path: str):
        conn = sqlite3.connect(sqlite_path)
        cursor = conn.cursor()

        # 检查所有表
        cursor.execute("SELECT name FROM sqlite_master WHERE type='table'")
        tables = {row[0] for row in cursor.fetchall()}

        # 1. 读取 DOWNLOAD_DIRNAME 表
        if "DOWNLOAD_DIRNAME" in tables:
            try:
                cursor.execute("SELECT GID, DIRNAME FROM DOWNLOAD_DIRNAME")
                for gid, dirname in cursor.fetchall():
                    if dirname:
                        self.register_dirname(dirname, gid)
            except Exception as e:
                print(f"[警告] 读取 DOWNLOAD_DIRNAME 表失败: {e}", file=sys.stderr)

        # 2. 读取 DOWNLOADS 表
        if "DOWNLOADS" in tables:
            try:
                # 动态获取字段
                cursor.execute("PRAGMA table_info(DOWNLOADS)")
                cols = [row[1].upper() for row in cursor.fetchall()]
                has_label = "LABEL" in cols
                has_state = "STATE" in cols
                has_token = "TOKEN" in cols
                has_jpn = "TITLE_JPN" in cols

                query = f"""
                    SELECT GID, TITLE
                    {', TITLE_JPN' if has_jpn else ', NULL'}
                    {', STATE' if has_state else ', NULL'}
                    {', LABEL' if has_label else ', NULL'}
                    {', TOKEN' if has_token else ', NULL'}
                    FROM DOWNLOADS
                """
                cursor.execute(query)
                for gid, title, title_jpn, state, label, token in cursor.fetchall():
                    self.downloads[gid] = {
                        "gid": gid,
                        "title": title or "",
                        "title_jpn": title_jpn or "",
                        "state": state,
                        "label": label or "",
                        "token": token or ""
                    }
                    if title:
                        self.register_title(title, gid)
                    if title_jpn:
                        self.register_title(title_jpn, gid)
            except Exception as e:
                print(f"[警告] 读取 DOWNLOADS 表失败: {e}", file=sys.stderr)

        # 3. 读取 HISTORY 表
        if "HISTORY" in tables:
            try:
                cursor.execute("PRAGMA table_info(HISTORY)")
                cols = [row[1].upper() for row in cursor.fetchall()]
                has_jpn = "TITLE_JPN" in cols
                has_token = "TOKEN" in cols
                query = f"""
                    SELECT GID, TITLE
                    {', TITLE_JPN' if has_jpn else ', NULL'}
                    {', TOKEN' if has_token else ', NULL'}
                    FROM HISTORY
                """
                cursor.execute(query)
                for gid, title, title_jpn, token in cursor.fetchall():
                    self.history[gid] = {
                        "gid": gid,
                        "title": title or "",
                        "title_jpn": title_jpn or "",
                        "token": token or ""
                    }
                    if gid not in self.title_to_gid:
                        if title:
                            self.register_title(title, gid)
                        if title_jpn:
                            self.register_title(title_jpn, gid)
            except Exception as e:
                print(f"[警告] 读取 HISTORY 表失败: {e}", file=sys.stderr)

        # 4. 读取 LOCAL_FAVORITES 表
        if "LOCAL_FAVORITES" in tables:
            try:
                cursor.execute("PRAGMA table_info(LOCAL_FAVORITES)")
                cols = [row[1].upper() for row in cursor.fetchall()]
                has_jpn = "TITLE_JPN" in cols
                has_token = "TOKEN" in cols
                query = f"""
                    SELECT GID, TITLE
                    {', TITLE_JPN' if has_jpn else ', NULL'}
                    {', TOKEN' if has_token else ', NULL'}
                    FROM LOCAL_FAVORITES
                """
                cursor.execute(query)
                for gid, title, title_jpn, token in cursor.fetchall():
                    self.favorites[gid] = {
                        "gid": gid,
                        "title": title or "",
                        "title_jpn": title_jpn or "",
                        "token": token or ""
                    }
                    if gid not in self.title_to_gid:
                        if title:
                            self.register_title(title, gid)
                        if title_jpn:
                            self.register_title(title_jpn, gid)
            except Exception as e:
                print(f"[警告] 读取 LOCAL_FAVORITES 表失败: {e}", file=sys.stderr)

        conn.close()


# ================= 文件夹清单读取与匹配 =================

def extract_gid_from_folder(name: str) -> Optional[int]:
    """从文件夹名中尝试提取 GID"""
    # 1. 尝试开头数字: "1234567-..." 或 "1234567"
    m = PATTERN_START_GID.match(name)
    if m:
        try:
            return int(m.group(1))
        except ValueError:
            pass

    # 2. 尝试括号包围: "[1234567] ..."
    m = PATTERN_BRACKET_GID.match(name)
    if m:
        try:
            return int(m.group(1))
        except ValueError:
            pass

    # 3. 尝试 URL 中的 GID
    m = PATTERN_URL_GID.search(name)
    if m:
        try:
            return int(m.group(1))
        except ValueError:
            pass

    return None


def clean_line_to_folder_name(line: str) -> str:
    """清理文件路径与格式，提取纯文件夹名"""
    line = line.strip().strip('"\'')
    # 处理 Windows 反斜杠和 Unix 斜杠
    line = line.rstrip('/\\')
    if not line:
        return ""
    # 若提供的是完整路径，取最后一段文件夹名
    if '/' in line or '\\' in line:
        parts = re.split(r'[/\\]+', line)
        if parts:
            line = parts[-1]
    return line.strip()


def load_folder_list(txt_path: str) -> List[Tuple[str, str]]:
    """读取 txt 文件中的文件夹列表，返回 [(raw_line, cleaned_name), ...]"""
    if not os.path.exists(txt_path):
        raise FileNotFoundError(f"文件夹列表文件不存在: {txt_path}")

    # 依次尝试 utf-8-sig, utf-8, gbk, gb18030
    encodings = ['utf-8-sig', 'utf-8', 'gbk', 'gb18030', 'utf-16']
    lines = None
    for enc in encodings:
        try:
            with open(txt_path, 'r', encoding=enc) as f:
                lines = f.readlines()
            break
        except (UnicodeDecodeError, UnicodeError):
            continue

    if lines is None:
        raise ValueError(f"无法使用常见编码 (UTF-8/GBK) 读取文件: {txt_path}")

    results = []
    seen = set()
    for line in lines:
        raw = line.strip()
        if not raw or raw.startswith('#'):
            continue
        cleaned = clean_line_to_folder_name(raw)
        if not cleaned:
            continue
        # 避免文本中完全重复的行
        if cleaned not in seen:
            seen.add(cleaned)
            results.append((raw, cleaned))

    return results


def analyze_folders(db: EhDatabase, folders: List[Tuple[str, str]]) -> List[MatchResult]:
    """对每个文件夹进行比对分析"""
    results: List[MatchResult] = []

    for raw, cleaned in folders:
        gid = extract_gid_from_folder(cleaned)
        norm_name = EhDatabase.normalize_name(cleaned)

        matched_gid = None
        match_method = "NONE"
        fuzzy_name = EhDatabase.fuzzy_name(cleaned)

        # 匹配优先级 1: DOWNLOAD_DIRNAME 目录名精确匹配
        if norm_name in db.dirname_to_gid:
            matched_gid = db.dirname_to_gid[norm_name]
            match_method = "DIRNAME"

        # 匹配优先级 2: DOWNLOAD_DIRNAME 目录名模糊匹配 (空格/下划线/连字符归一)
        elif fuzzy_name in db.dirname_to_gid:
            matched_gid = db.dirname_to_gid[fuzzy_name]
            match_method = "DIRNAME_FUZZY"

        # 匹配优先级 3: GID 匹配 (提取到的 GID 是否在 DOWNLOADS 中)
        elif gid is not None and gid in db.downloads:
            matched_gid = gid
            match_method = "GID"

        # 匹配优先级 4: GID 在 DOWNLOAD_DIRNAME 中
        elif gid is not None and gid in db.download_dirnames:
            matched_gid = gid
            match_method = "GID"

        # 匹配优先级 5: 标题精确/模糊匹配
        elif norm_name in db.title_to_gid:
            matched_gid = db.title_to_gid[norm_name]
            match_method = "TITLE"
        elif fuzzy_name in db.title_to_gid:
            matched_gid = db.title_to_gid[fuzzy_name]
            match_method = "TITLE_FUZZY"

        final_gid = matched_gid or gid

        # 判断分类
        if final_gid and final_gid in db.downloads:
            info = db.downloads[final_gid]
            res = MatchResult(
                raw_name=raw,
                cleaned_name=cleaned,
                extracted_gid=final_gid,
                status=STATUS_IN_DOWNLOADS,
                match_method=match_method,
                db_title=info["title"],
                db_title_jpn=info["title_jpn"],
                download_state=info["state"],
                download_label=info["label"],
                token=info["token"]
            )
        elif final_gid and final_gid in db.history:
            info = db.history[final_gid]
            res = MatchResult(
                raw_name=raw,
                cleaned_name=cleaned,
                extracted_gid=final_gid,
                status=STATUS_IN_HISTORY,
                match_method=match_method if match_method != "NONE" else "GID",
                db_title=info["title"],
                db_title_jpn=info["title_jpn"],
                token=info["token"],
                extra_info="该项不在下载列表中，但存在于历史记录"
            )
        elif final_gid and final_gid in db.favorites:
            info = db.favorites[final_gid]
            res = MatchResult(
                raw_name=raw,
                cleaned_name=cleaned,
                extracted_gid=final_gid,
                status=STATUS_IN_FAVORITES,
                match_method=match_method if match_method != "NONE" else "GID",
                db_title=info["title"],
                db_title_jpn=info["title_jpn"],
                token=info["token"],
                extra_info="该项不在下载列表中，但存在于本地收藏夹"
            )
        else:
            res = MatchResult(
                raw_name=raw,
                cleaned_name=cleaned,
                extracted_gid=final_gid,
                status=STATUS_NOT_FOUND,
                match_method="NONE",
                extra_info="数据库下载表、历史表、收藏表中均无此条目"
            )

        results.append(res)

    return results


# ================= 报表生成 =================

def write_reports(
    results: List[MatchResult],
    output_dir: str,
    export_orphan_txt: bool = False,
    export_summary_txt: bool = False
) -> Dict[str, str]:
    """
    生成分析报表文件，返回生成的路径映射字典。
    默认生成:
      1. smb_missing_folders.txt (不在下载库的清单)
      2. smb_analysis_report.csv (完整明细表格)
    可选生成:
      3. smb_completely_orphan_folders.txt (完全未入库清单) - export_orphan_txt=True
      4. smb_analysis_summary.txt (统计摘要报告) - export_summary_txt=True
    """
    os.makedirs(output_dir, exist_ok=True)
    generated = {}

    # 1. 核心默认文件 1: missing_folders.txt（不在下载库中的文件夹名列表）
    missing_txt_path = os.path.join(output_dir, "smb_missing_folders.txt")
    with open(missing_txt_path, 'w', encoding='utf-8') as f:
        for r in results:
            if r.status != STATUS_IN_DOWNLOADS:
                f.write(r.cleaned_name + "\n")
    generated["missing_txt"] = missing_txt_path

    # 2. 核心默认文件 2: 详细 CSV 报表（Excel 双击直接正常打开）
    csv_path = os.path.join(output_dir, "smb_analysis_report.csv")
    with open(csv_path, 'w', encoding='utf-8-sig', newline='') as f:
        writer = csv.writer(f)
        writer.writerow([
            "文件夹名",
            "提取GID",
            "比对结果状态",
            "匹配方式",
            "下载状态",
            "标签/分类",
            "数据库标题",
            "日文标题",
            "E-Hentai画廊链接",
            "补充说明"
        ])
        for r in results:
            writer.writerow([
                r.cleaned_name,
                r.extracted_gid or "",
                r.status,
                r.match_method,
                r.state_str,
                r.download_label,
                r.db_title,
                r.db_title_jpn,
                r.eh_url,
                r.extra_info
            ])
    generated["csv_report"] = csv_path

    # 3. 可选文件: 完全未入库的孤儿文件夹名清单
    if export_orphan_txt:
        orphan_txt_path = os.path.join(output_dir, "smb_completely_orphan_folders.txt")
        with open(orphan_txt_path, 'w', encoding='utf-8') as f:
            for r in results:
                if r.status == STATUS_NOT_FOUND:
                    f.write(r.cleaned_name + "\n")
        generated["orphan_txt"] = orphan_txt_path

    # 4. 可选文件: 统计汇总报告文本
    if export_summary_txt:
        summary_path = os.path.join(output_dir, "smb_analysis_summary.txt")
        total = len(results)
        c_in_downloads = sum(1 for r in results if r.status == STATUS_IN_DOWNLOADS)
        c_in_history = sum(1 for r in results if r.status == STATUS_IN_HISTORY)
        c_in_fav = sum(1 for r in results if r.status == STATUS_IN_FAVORITES)
        c_not_found = sum(1 for r in results if r.status == STATUS_NOT_FOUND)

        with open(summary_path, 'w', encoding='utf-8') as f:
            f.write("=====================================================\n")
            f.write("        SMB 漫画与 EhViewer 数据库比对分析报告         \n")
            f.write("=====================================================\n\n")
            f.write(f"SMB 目录文件夹总数: {total}\n")
            f.write(f"├─ 已在下载库中:       {c_in_downloads:5d}  ({c_in_downloads/total*100:.1f}%)\n")
            f.write(f"├─ 不在下载库(在历史): {c_in_history:5d}  ({c_in_history/total*100:.1f}%)\n")
            f.write(f"├─ 不在下载库(在收藏): {c_in_fav:5d}  ({c_in_fav/total*100:.1f}%)\n")
            f.write(f"└─ 完全未入库(孤儿):   {c_not_found:5d}  ({c_not_found/total*100:.1f}%)\n\n")

            if c_not_found > 0:
                f.write(f"--- 完全未入库的文件夹列表 ({c_not_found} 项) ---\n")
                for r in results:
                    if r.status == STATUS_NOT_FOUND:
                        gid_str = f" [GID: {r.extracted_gid}]" if r.extracted_gid else " [无GID]"
                        f.write(f"- {r.cleaned_name}{gid_str}\n")
                f.write("\n")

            if c_in_history > 0 or c_in_fav > 0:
                f.write(f"--- 不在下载库但有历史/收藏记录的文件夹 ({c_in_history + c_in_fav} 项) ---\n")
                for r in results:
                    if r.status in (STATUS_IN_HISTORY, STATUS_IN_FAVORITES):
                        source = "历史记录" if r.status == STATUS_IN_HISTORY else "本地收藏"
                        f.write(f"- {r.cleaned_name}\n")
                        f.write(f"  来源: {source} | GID: {r.extracted_gid} | 原名: {r.db_title}\n")
                        if r.eh_url:
                            f.write(f"  链接: {r.eh_url}\n")
                f.write("\n")
        generated["summary_txt"] = summary_path

    return generated


# ================= GUI 图形界面 (Tkinter) =================

def run_gui():
    import tkinter as tk
    from tkinter import ttk, filedialog, messagebox

    root = tk.Tk()
    root.title("EhViewer SMB 漫画未入库分析工具")
    root.geometry("960, 680")
    root.minsize(800, 520)

    # 尝试设置现代样式
    style = ttk.Style()
    available_themes = style.theme_names()
    if "vista" in available_themes:
        style.theme_use("vista")
    elif "clam" in available_themes:
        style.theme_use("clam")

    # 全局数据
    analysis_results: List[MatchResult] = []

    # 顶部配置区
    top_frame = ttk.LabelFrame(root, text="文件选择", padding=12)
    top_frame.pack(fill=tk.X, padx=12, pady=8)

    # DB 文件选择
    ttk.Label(top_frame, text="EhViewer 数据库/备份:").grid(row=0, column=0, sticky=tk.W, pady=4)
    db_var = tk.StringVar()
    db_entry = ttk.Entry(top_frame, textvariable=db_var)
    db_entry.grid(row=0, column=1, sticky=tk.EW, padx=8, pady=4)

    def browse_db():
        path = filedialog.askopenfilename(
            title="选择 EhViewer 数据库或备份文件",
            filetypes=[
                ("EhViewer 支持文件", "*.db *.zip *.ehbak *.sqlite"),
                ("SQLite 数据库 (*.db)", "*.db"),
                ("完整备份包 (*.zip, *.ehbak)", "*.zip *.ehbak"),
                ("所有文件", "*.*")
            ]
        )
        if path:
            db_var.set(path)

    ttk.Button(top_frame, text="浏览...", command=browse_db).grid(row=0, column=2, padx=4)

    # TXT 文件选择
    ttk.Label(top_frame, text="SMB 文件夹清单 (.txt):").grid(row=1, column=0, sticky=tk.W, pady=4)
    txt_var = tk.StringVar()
    txt_entry = ttk.Entry(top_frame, textvariable=txt_var)
    txt_entry.grid(row=1, column=1, sticky=tk.EW, padx=8, pady=4)

    def browse_txt():
        path = filedialog.askopenfilename(
            title="选择 SMB 文件夹清单文件",
            filetypes=[("文本文件 (*.txt)", "*.txt"), ("所有文件", "*.*")]
        )
        if path:
            txt_var.set(path)

    ttk.Button(top_frame, text="浏览...", command=browse_txt).grid(row=1, column=2, padx=4)
    top_frame.columnconfigure(1, weight=1)

    # 按钮操作栏与状态卡片
    action_frame = ttk.Frame(root, padding=4)
    action_frame.pack(fill=tk.X, padx=12, pady=4)

    status_var = tk.StringVar(value="请选择数据库文件与 SMB 文件夹列表 txt 文件后点击开始分析")
    status_label = ttk.Label(action_frame, textvariable=status_var, foreground="#333333")
    status_label.pack(side=tk.LEFT, padx=4)

    # 汇总卡片区
    kpi_frame = ttk.Frame(root, padding=4)
    kpi_frame.pack(fill=tk.X, padx=12, pady=4)

    lbl_total = ttk.Label(kpi_frame, text="总计: 0", font=("Microsoft YaHei UI", 10, "bold"))
    lbl_total.pack(side=tk.LEFT, padx=10)

    lbl_missing = ttk.Label(kpi_frame, text="完全未入库: 0", foreground="#d9534f", font=("Microsoft YaHei UI", 10, "bold"))
    lbl_missing.pack(side=tk.LEFT, padx=10)

    lbl_hist = ttk.Label(kpi_frame, text="仅在历史/收藏: 0", foreground="#f0ad4e", font=("Microsoft YaHei UI", 10, "bold"))
    lbl_hist.pack(side=tk.LEFT, padx=10)

    lbl_downloaded = ttk.Label(kpi_frame, text="已在下载库: 0", foreground="#5cb85c", font=("Microsoft YaHei UI", 10, "bold"))
    lbl_downloaded.pack(side=tk.LEFT, padx=10)

    # 中部结果展示区（Notebook Tab + 检索框）
    mid_frame = ttk.Frame(root, padding=4)
    mid_frame.pack(fill=tk.BOTH, expand=True, padx=12, pady=4)

    # 过滤检索栏
    filter_frame = ttk.Frame(mid_frame)
    filter_frame.pack(fill=tk.X, pady=2)
    ttk.Label(filter_frame, text="快速检索:").pack(side=tk.LEFT, padx=4)
    search_var = tk.StringVar()
    search_entry = ttk.Entry(filter_frame, textvariable=search_var)
    search_entry.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=4)

    notebook = ttk.Notebook(mid_frame)
    notebook.pack(fill=tk.BOTH, expand=True, pady=4)

    # 创建表格辅助方法
    def create_tree_tab(parent):
        frame = ttk.Frame(parent)
        tree = ttk.Treeview(
            frame,
            columns=("name", "gid", "status", "match", "state", "title"),
            show="headings",
            selectmode="extended"
        )
        tree.heading("name", text="文件夹名")
        tree.heading("gid", text="GID")
        tree.heading("status", text="入库状态")
        tree.heading("match", text="匹配方式")
        tree.heading("state", text="下载状态/分类")
        tree.heading("title", text="匹配画廊标题")

        tree.column("name", width=340, anchor=tk.W)
        tree.column("gid", width=90, anchor=tk.CENTER)
        tree.column("status", width=140, anchor=tk.CENTER)
        tree.column("match", width=80, anchor=tk.CENTER)
        tree.column("state", width=100, anchor=tk.CENTER)
        tree.column("title", width=220, anchor=tk.W)

        vsb = ttk.Scrollbar(frame, orient="vertical", command=tree.yview)
        hsb = ttk.Scrollbar(frame, orient="horizontal", command=tree.xview)
        tree.configure(yscrollcommand=vsb.set, xscrollcommand=hsb.set)

        tree.grid(row=0, column=0, sticky="nsew")
        vsb.grid(row=0, column=1, sticky="ns")
        hsb.grid(row=1, column=0, sticky="ew")
        frame.grid_columnconfigure(0, weight=1)
        frame.grid_rowconfigure(0, weight=1)
        return frame, tree

    tab_missing, tree_missing = create_tree_tab(notebook)
    tab_hist, tree_hist = create_tree_tab(notebook)
    tab_downloads, tree_downloads = create_tree_tab(notebook)
    tab_all, tree_all = create_tree_tab(notebook)

    notebook.add(tab_missing, text=" 完全未入库 (0) ")
    notebook.add(tab_hist, text=" 仅在历史/收藏 (0) ")
    notebook.add(tab_downloads, text=" 已在下载库 (0) ")
    notebook.add(tab_all, text=" 全部列表 (0) ")

    def refresh_tables():
        query = search_var.get().strip().lower()

        # 清空所有表
        for tree in (tree_missing, tree_hist, tree_downloads, tree_all):
            for row in tree.get_children():
                tree.delete(row)

        c_missing = 0
        c_hist = 0
        c_dl = 0

        for r in analysis_results:
            # 搜索过滤
            if query:
                match_text = f"{r.cleaned_name} {r.extracted_gid or ''} {r.db_title} {r.db_title_jpn}".lower()
                if query not in match_text:
                    continue

            vals = (
                r.cleaned_name,
                str(r.extracted_gid) if r.extracted_gid else "-",
                r.status,
                r.match_method,
                r.state_str or r.download_label or "-",
                r.db_title or r.extra_info or "-"
            )

            tree_all.insert("", tk.END, values=vals)

            if r.status == STATUS_NOT_FOUND:
                tree_missing.insert("", tk.END, values=vals)
                c_missing += 1
            elif r.status in (STATUS_IN_HISTORY, STATUS_IN_FAVORITES):
                tree_hist.insert("", tk.END, values=vals)
                c_hist += 1
            elif r.status == STATUS_IN_DOWNLOADS:
                tree_downloads.insert("", tk.END, values=vals)
                c_dl += 1

        notebook.tab(0, text=f" 完全未入库 ({c_missing}) ")
        notebook.tab(1, text=f" 仅在历史/收藏 ({c_hist}) ")
        notebook.tab(2, text=f" 已在下载库 ({c_dl}) ")
        notebook.tab(3, text=f" 全部列表 ({len(analysis_results)}) ")

    search_var.trace_add("write", lambda *args: refresh_tables())

    # 分析执行方法
    def do_analysis():
        db_path = db_var.get().strip()
        txt_path = txt_var.get().strip()

        if not db_path or not os.path.exists(db_path):
            messagebox.showerror("错误", "请选择有效的 EhViewer 数据库文件或备份包！")
            return
        if not txt_path or not os.path.exists(txt_path):
            messagebox.showerror("错误", "请选择有效的 SMB 文件夹清单 txt 文件！")
            return

        status_var.set("正在读取数据库中，请稍候...")
        root.update_idletasks()

        try:
            db = EhDatabase.load(db_path)
            status_var.set("数据库解析完成，正在读取 SMB 文件夹列表并比对...")
            root.update_idletasks()

            folders = load_folder_list(txt_path)
            if not folders:
                messagebox.showwarning("提示", "所选 txt 文件中未读取到任何有效文件夹名！")
                return

            nonlocal analysis_results
            analysis_results = analyze_folders(db, folders)

            total = len(analysis_results)
            c_missing = sum(1 for r in analysis_results if r.status == STATUS_NOT_FOUND)
            c_hist = sum(1 for r in analysis_results if r.status in (STATUS_IN_HISTORY, STATUS_IN_FAVORITES))
            c_dl = sum(1 for r in analysis_results if r.status == STATUS_IN_DOWNLOADS)

            lbl_total.config(text=f"总计: {total}")
            lbl_missing.config(text=f"完全未入库: {c_missing}")
            lbl_hist.config(text=f"仅在历史/收藏: {c_hist}")
            lbl_downloaded.config(text=f"已在下载库: {c_dl}")

            refresh_tables()
            status_var.set(f"分析完成！共扫描 {total} 个 SMB 目录，发现 {c_missing} 个完全未入库漫画")
            messagebox.showinfo("完成", f"分析完成！\n\n总文件夹: {total}\n未入库: {c_missing}\n仅历史/收藏: {c_hist}\n已入下载库: {c_dl}")

        except Exception as e:
            status_var.set(f"执行出错: {e}")
            messagebox.showerror("错误", f"分析过程中发生错误:\n{e}")

    btn_start = ttk.Button(action_frame, text="▶ 开始分析", style="Accent.TButton" if "Accent.TButton" in style.theme_names() else "TButton", command=do_analysis)
    btn_start.pack(side=tk.RIGHT, padx=4)

    # 底部操作栏（复制与导出）
    bottom_frame = ttk.Frame(root, padding=8)
    bottom_frame.pack(fill=tk.X, padx=12, pady=4)

    def copy_missing_to_clipboard():
        missing_names = [r.cleaned_name for r in analysis_results if r.status != STATUS_IN_DOWNLOADS]
        if not missing_names:
            messagebox.showinfo("提示", "当前没有未入库的文件夹")
            return
        text = "\n".join(missing_names)
        root.clipboard_clear()
        root.clipboard_append(text)
        messagebox.showinfo("成功", f"已复制 {len(missing_names)} 个未入库/不在下载库的文件夹名到剪贴板！")

    def export_reports_dialog():
        if not analysis_results:
            messagebox.showwarning("提示", "请先点击【开始分析】生成数据后再导出！")
            return

        # 弹出导出选项弹窗
        dlg = tk.Toplevel(root)
        dlg.title("选择导出选项")
        dlg.geometry("420, 260")
        dlg.resizable(False, False)
        dlg.transient(root)
        dlg.grab_set()

        ttk.Label(dlg, text="请勾选需要导出的报表文件：", font=("Microsoft YaHei UI", 10, "bold")).pack(anchor=tk.W, padx=16, pady=(16, 8))

        var_missing_txt = tk.BooleanVar(value=True)
        var_csv = tk.BooleanVar(value=True)
        var_orphan_txt = tk.BooleanVar(value=False)
        var_summary_txt = tk.BooleanVar(value=False)

        ttk.Checkbutton(dlg, text="[默认] 不在下载库清单 (smb_missing_folders.txt)", variable=var_missing_txt, state=tk.DISABLED).pack(anchor=tk.W, padx=20, pady=3)
        ttk.Checkbutton(dlg, text="[默认] 完整分析报表 (smb_analysis_report.csv)", variable=var_csv, state=tk.DISABLED).pack(anchor=tk.W, padx=20, pady=3)
        ttk.Checkbutton(dlg, text="[可选] 完全未入库清单 (smb_completely_orphan_folders.txt)", variable=var_orphan_txt).pack(anchor=tk.W, padx=20, pady=3)
        ttk.Checkbutton(dlg, text="[可选] 汇总统计诊断报告 (smb_analysis_summary.txt)", variable=var_summary_txt).pack(anchor=tk.W, padx=20, pady=3)

        btn_box = ttk.Frame(dlg, padding=8)
        btn_box.pack(side=tk.BOTTOM, fill=tk.X, padx=12, pady=10)

        def do_export_confirmed():
            dlg.destroy()
            out_dir = filedialog.askdirectory(title="选择报表导出保存目录")
            if not out_dir:
                return
            try:
                gen = write_reports(
                    analysis_results,
                    out_dir,
                    export_orphan_txt=var_orphan_txt.get(),
                    export_summary_txt=var_summary_txt.get()
                )
                msg = "报表导出成功！生成文件如下:\n\n"
                msg += f"1. 缺失清单文本: {gen['missing_txt']}\n"
                msg += f"2. 详细分析表格: {gen['csv_report']}\n"
                if "orphan_txt" in gen:
                    msg += f"3. 孤儿清单文本: {gen['orphan_txt']}\n"
                if "summary_txt" in gen:
                    msg += f"4. 汇总说明报告: {gen['summary_txt']}\n"
                messagebox.showinfo("导出成功", msg)
            except Exception as e:
                messagebox.showerror("错误", f"导出失败: {e}")

        ttk.Button(btn_box, text="选择目录并导出", command=do_export_confirmed).pack(side=tk.RIGHT, padx=6)
        ttk.Button(btn_box, text="取消", command=dlg.destroy).pack(side=tk.RIGHT, padx=6)

    ttk.Button(bottom_frame, text="📋 复制未入库文件夹名", command=copy_missing_to_clipboard).pack(side=tk.LEFT, padx=6)
    ttk.Button(bottom_frame, text="💾 导出分析报表...", command=export_reports_dialog).pack(side=tk.LEFT, padx=6)
    ttk.Button(bottom_frame, text="退出", command=root.destroy).pack(side=tk.RIGHT, padx=6)

    root.mainloop()


# ================= CLI 命令行模式 =================

def run_cli(args):
    db_path = args.db
    txt_path = args.txt
    output_dir = args.output or os.path.dirname(os.path.abspath(txt_path))

    export_orphan = bool(args.export_orphan_txt or args.all_reports)
    export_summary = bool(args.export_summary_txt or args.all_reports)

    print("=====================================================")
    print("      EhViewer SMB 漫画未入库分析工具 (CLI 模式)      ")
    print("=====================================================")
    print(f"数据库文件:   {db_path}")
    print(f"文件夹清单:   {txt_path}")
    print(f"输出目录:     {output_dir}")
    print(f"可选输出项:   [孤儿清单: {'开启' if export_orphan else '未开启'}] [统计文本: {'开启' if export_summary else '未开启'}]")
    print("-----------------------------------------------------")

    print("[1/3] 正在加载并解析数据库...")
    try:
        db = EhDatabase.load(db_path)
    except Exception as e:
        print(f"错误: 数据库解析失败 - {e}", file=sys.stderr)
        sys.exit(1)

    print(f"      - 下载表记录数:   {len(db.downloads)}")
    print(f"      - 路径映射记录数: {len(db.download_dirnames)}")
    print(f"      - 历史表记录数:   {len(db.history)}")
    print(f"      - 收藏表记录数:   {len(db.favorites)}")

    print("[2/3] 正在读取 SMB 文件夹清单并比对...")
    try:
        folders = load_folder_list(txt_path)
    except Exception as e:
        print(f"错误: 文件夹清单读取失败 - {e}", file=sys.stderr)
        sys.exit(1)

    results = analyze_folders(db, folders)
    total = len(results)
    c_dl = sum(1 for r in results if r.status == STATUS_IN_DOWNLOADS)
    c_hist = sum(1 for r in results if r.status == STATUS_IN_HISTORY)
    c_fav = sum(1 for r in results if r.status == STATUS_IN_FAVORITES)
    c_orphan = sum(1 for r in results if r.status == STATUS_NOT_FOUND)

    print(f"      - 扫描到有效目录: {total}")
    print(f"      - 已在下载库:     {c_dl} ({c_dl/total*100:.1f}%)")
    print(f"      - 仅在历史记录:   {c_hist} ({c_hist/total*100:.1f}%)")
    print(f"      - 仅在本地收藏:   {c_fav} ({c_fav/total*100:.1f}%)")
    print(f"      - 完全未入库(孤儿): {c_orphan} ({c_orphan/total*100:.1f}%)")

    print("[3/3] 正在生成分析报表文件...")
    gen = write_reports(
        results,
        output_dir,
        export_orphan_txt=export_orphan,
        export_summary_txt=export_summary
    )
    print("-----------------------------------------------------")
    print("报表生成完毕:")
    print(f"  [核心] 缺失列表 (TXT): {gen['missing_txt']}")
    print(f"  [核心] 详细表格 (CSV): {gen['csv_report']}")
    if "orphan_txt" in gen:
        print(f"  [可选] 孤儿清单 (TXT): {gen['orphan_txt']}")
    if "summary_txt" in gen:
        print(f"  [可选] 汇总统计 (TXT): {gen['summary_txt']}")
    print("=====================================================")


def main():
    parser = argparse.ArgumentParser(
        description="分析 SMB 目录中未被 EhViewer 数据库收录的漫画文件夹",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  GUI 模式 (不加参数或带 --gui 直接启动图形界面):
    python scripts/analyze_smb_missing.py
    python scripts/analyze_smb_missing.py --gui

  CLI 模式 (默认只生成 smb_missing_folders.txt 和 smb_analysis_report.csv):
    python scripts/analyze_smb_missing.py --db eh.db --txt smb_folders.txt
    python scripts/analyze_smb_missing.py --db backup.zip --txt smb_folders.txt -o ./results

  CLI 模式 (开启可选文件输出):
    python scripts/analyze_smb_missing.py --db backup.ehbak --txt smb_folders.txt --export-orphan-txt
    python scripts/analyze_smb_missing.py --db backup.ehbak --txt smb_folders.txt --all-reports
        """
    )
    parser.add_argument("--db", "-d", help="EhViewer 数据库文件 (.db) 或备份包 (.zip / .ehbak)")
    parser.add_argument("--txt", "-t", help="SMB 目录下的所有漫画文件夹清单 (.txt)")
    parser.add_argument("--output", "-o", help="报表输出保存目录 (默认当前目录或 txt 所在目录)")
    parser.add_argument("--export-orphan-txt", action="store_true", help="[可选] 额外导出完全未入库(孤儿)文件夹名列表 (smb_completely_orphan_folders.txt)")
    parser.add_argument("--export-summary-txt", action="store_true", help="[可选] 额外导出汇总统计文本报告 (smb_analysis_summary.txt)")
    parser.add_argument("--all-reports", action="store_true", help="[可选] 导出所有报表文件 (包括孤儿列表与汇总报告)")
    parser.add_argument("--gui", "-g", action="store_true", help="强制启动图形界面模式")

    args = parser.parse_args()

    # 如果指定了 --gui 或者没有任何参数输入，尝试启动图形界面
    if args.gui or (not args.db and not args.txt):
        try:
            run_gui()
            return
        except Exception as e:
            # 在无桌面环境或图形界面启动失败时回退到交互式终端
            print(f"[提示] 图形界面不可用 ({e})，进入交互式终端模式\n")

    # 如果参数不全，在控制台交互式提示输入
    if not args.db:
        args.db = input("请输入 EhViewer 数据库文件路径 (.db / .zip / .ehbak): ").strip().strip('"\'')
    if not args.txt:
        args.txt = input("请输入 SMB 文件夹清单文件路径 (.txt): ").strip().strip('"\'')

    run_cli(args)


if __name__ == "__main__":
    main()
