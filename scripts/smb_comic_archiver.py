#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SMB漫画文件夹压缩工具
将EhViewer下载的漫画文件夹压缩为CBZ格式，删除原图，并修复缺失的元数据文件

功能:
    - 扫描漫画文件夹，匹配EhViewer数据库记录，批量压缩为CBZ
    - 修复缺失的 .ehviewer 元数据文件和 .thumb 缩略图（支持未压缩与已压缩漫画）
    - 预览模式下导出上传者统计 (uploader_stats.csv)、未完成漫画统计 (unfinished_stats.csv) 和扫描结果 (scan_result.csv)

启动方式:
    GUI 模式（无参数启动图形界面）:
        python smb_comic_archiver.py

    CLI 模式（带参数走命令行）:
        python smb_comic_archiver.py --db eh.db --root ./comic

选项:
    --db           EhViewer数据库文件路径（必填）
    --root         漫画文件夹根目录路径（必填）
    --dry-run      预览模式，只扫描不修改或压缩，并导出上传者及未完成统计
    --fix-missing  修复缺失的 .ehviewer 和 .thumb 文件
    --force        强制重新压缩已压缩的漫画
    --no-progress  不显示进度条
    -h, --help     显示帮助信息

输出文件:
    comic_archiver_report.txt   运行报告（时间、模式、缺失文件统计、压缩结果）
    scan_result.csv             扫描结果明细（gid、文件夹名、图片数、缺失文件、数据库状态）
    uploader_stats.csv          上传者统计，按数量降序排列（仅预览模式生成）
    unfinished_stats.csv        未完成下载但图片数较多的漫画，按已下载数降序（仅预览模式生成）

打包为 exe:
    pip install pyinstaller
    pyinstaller --onefile --windowed smb_comic_archiver.py
"""

import argparse
import csv
import html
import io
import os
import re
import sqlite3
import sys
import threading
import zipfile
from datetime import datetime
from typing import Dict, List, Optional, Tuple

# 防止在 PyInstaller --windowed 模式下 sys.stdout / sys.stderr 为 None 导致 print 崩溃
if sys.stdout is None:
    sys.stdout = open(os.devnull, 'w', encoding='utf-8')
if sys.stderr is None:
    sys.stderr = open(os.devnull, 'w', encoding='utf-8')

try:
    from tqdm import tqdm
except ImportError:
    tqdm = None

try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    HAS_PIL = False

# ============ 常量定义 ============
# 文件夹名模式: {gid}-{title}
FOLDER_PATTERN = re.compile(r'^(\d+)-(.+)$')

# 图片文件名模式 (8位数字.jpg/png/gif/webp)
IMAGE_PATTERN = re.compile(r'^\d{8}\.(jpg|jpeg|png|gif|webp)$', re.IGNORECASE)

# 下载状态常量
STATE_FINISH = 3

# 报告文件
REPORT_FILE = "comic_archiver_report.txt"

# 缩略图尺寸
THUMB_SIZE = (160, 240)
THUMB_QUALITY = 85

# .ehviewer 文件配置
START_PAGE = "00000000"
PREVIEW_PAGES = "1"
PREVIEW_PER_PAGE = "20"

# 分页显示数量
DRY_RUN_PREVIEW_COUNT = 10

# 扫描结果输出文件
SCAN_RESULT_FILE = "scan_result.csv"

# 上传者统计文件
UPLOADER_STATS_FILE = "uploader_stats.csv"

# 未完成漫画统计文件
UNFINISHED_STATS_FILE = "unfinished_stats.csv"


def parse_args():
    parser = argparse.ArgumentParser(
        description='SMB漫画文件夹压缩工具 - 将漫画文件夹压缩为CBZ格式',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  预览模式:
    python smb_comic_archiver.py --db eh.db --root ./comic --dry-run

  修复缺失文件:
    python smb_comic_archiver.py --db eh.db --root ./comic --fix-missing

  正式压缩:
    python smb_comic_archiver.py --db eh.db --root ./comic

  强制重新压缩已压缩的:
    python smb_comic_archiver.py --db eh.db --root ./comic --force
        """
    )
    parser.add_argument('--db', required=True, help='EhViewer数据库文件路径')
    parser.add_argument('--root', required=True, help='漫画文件夹根目录路径')
    parser.add_argument('--dry-run', action='store_true', help='预览模式，只扫描不修改或压缩')
    parser.add_argument('--fix-missing', action='store_true', help='修复缺失的.ehviewer和.thumb文件')
    parser.add_argument('--force', action='store_true', help='强制重新压缩已压缩的漫画')
    parser.add_argument('--no-progress', action='store_true', help='不显示进度条')
    
    return parser.parse_args()


def read_database(db_path: str) -> Dict[int, Dict]:
    """从数据库读取下载记录，返回 {gid: info}"""
    if not os.path.exists(db_path):
        print(f"错误: 数据库文件不存在: {db_path}")
        raise FileNotFoundError(f"数据库文件不存在: {db_path}")
    
    # 优先使用只读 URI 打开，避免锁冲突
    try:
        conn = sqlite3.connect(f"file:{os.path.abspath(db_path)}?mode=ro", uri=True)
    except Exception:
        conn = sqlite3.connect(db_path)

    with conn:
        cursor = conn.cursor()
        cursor.execute('''
            SELECT GID, TITLE, TITLE_JPN, TOKEN, THUMB, CATEGORY, POSTED, UPLOADER, RATING, STATE, LEGACY 
            FROM DOWNLOADS 
            ORDER BY GID
        ''')
        
        results = {}
        for row in cursor.fetchall():
            gid, title, title_jpn, token, thumb, category, posted, uploader, rating, state, legacy = row
            # legacy 在 EhViewer 中表示未下载的剩余张数 (mTotal - mFinished)
            # 若 legacy > 0，则说明未下载完全，绝不能误判为完成
            if legacy is not None and legacy > 0:
                is_finished = False
            else:
                is_finished = (state == STATE_FINISH) or (legacy == 0)

            results[gid] = {
                'gid': gid,
                'title': title or '',
                'title_jpn': title_jpn or '',
                'token': token or '',
                'thumb': thumb or '',
                'category': category or 0,
                'posted': posted or '',
                'uploader': uploader or '',
                'rating': rating or 0.0,
                'state': state,
                'legacy': legacy,
                'is_finished': is_finished
            }
    
    print(f"从数据库读取到 {len(results)} 条下载记录")
    return results


def get_image_files(folder: str) -> List[str]:
    """获取文件夹中的图片文件列表"""
    if not os.path.isdir(folder):
        return []
    
    images = []
    for f in os.listdir(folder):
        if IMAGE_PATTERN.match(f):
            images.append(f)
    
    return sorted(images)


def has_ehviewer(folder: str) -> bool:
    """检查是否存在.ehviewer文件"""
    return os.path.isfile(os.path.join(folder, '.ehviewer'))


def has_thumb(folder: str) -> bool:
    """检查是否存在.thumb文件"""
    return os.path.isfile(os.path.join(folder, '.thumb'))


def is_already_compressed(folder: str) -> bool:
    """检查是否已经压缩（存在有效的.cbz文件）"""
    if not os.path.isdir(folder):
        return False
    
    for f in os.listdir(folder):
        if f.lower().endswith('.cbz'):
            try:
                if os.path.getsize(os.path.join(folder, f)) > 0:
                    return True
            except OSError:
                pass
    return False


def find_cbz_file(folder: str) -> Optional[str]:
    """查找文件夹中的第一个.cbz文件路径，未找到返回 None"""
    if not os.path.isdir(folder):
        return None
    for f in os.listdir(folder):
        if f.lower().endswith('.cbz'):
            return os.path.join(folder, f)
    return None


def get_cbz_image_info(cbz_path: str) -> Tuple[List[str], Optional[bytes]]:
    """从 CBZ 压缩包中读取图片列表及第一张图片的二进制数据"""
    try:
        with zipfile.ZipFile(cbz_path, 'r') as zf:
            cbz_images = [
                n for n in zf.namelist() 
                if IMAGE_PATTERN.match(os.path.basename(n))
            ]
            cbz_images.sort()
            first_img_bytes = zf.read(cbz_images[0]) if cbz_images else None
            return cbz_images, first_img_bytes
    except Exception:
        return [], None


def read_ehviewer_pages(folder: str) -> int:
    """读取.ehviewer文件获取页数
    
    Returns:
        页数，如果读取失败返回-1
    """
    ehviewer_path = os.path.join(folder, '.ehviewer')
    if not os.path.isfile(ehviewer_path):
        return -1
    
    try:
        with open(ehviewer_path, 'r', encoding='utf-8') as f:
            lines = [line.strip() for line in f.readlines()]
        
        # VERSION2 格式：第8行（索引7）是页数
        if len(lines) >= 8 and lines[0] == 'VERSION2':
            return int(lines[7])
        # VERSION1 格式：第7行（索引6）是页数
        elif len(lines) >= 7 and lines[0] != 'VERSION2':
            return int(lines[6])
        # 兜底：纯数字单行
        elif len(lines) >= 1 and lines[-1].isdigit():
            return int(lines[-1])
        return -1
    except Exception:
        return -1


def export_scan_result(scan_data: List[Dict], output_path: str) -> bool:
    """导出扫描结果到CSV文件
    
    Args:
        scan_data: 扫描数据列表
        output_path: 输出文件路径
    
    Returns:
        是否成功
    """
    if not scan_data:
        print("没有数据可导出")
        return False
    
    try:
        headers = [
            'gid', 'folder_name', 'db_finished', 'image_count', 'ehviewer_pages',
            'has_ehviewer', 'has_thumb', 'created_ehviewer', 'created_thumb'
        ]
        
        with open(output_path, 'w', encoding='utf-8-sig', newline='') as f:
            writer = csv.writer(f)
            writer.writerow(headers)
            for item in scan_data:
                row = [
                    str(item.get('gid', '')),
                    str(item.get('folder_name', '')),
                    '1' if item.get('db_finished') else '0',
                    str(item.get('image_count', 0)),
                    str(item.get('ehviewer_pages', -1)),
                    '1' if item.get('has_ehviewer') else '0',
                    '1' if item.get('has_thumb') else '0',
                    '1' if item.get('created_ehviewer') else '0',
                    '1' if item.get('created_thumb') else '0'
                ]
                writer.writerow(row)
        
        print(f"扫描结果已导出到: {os.path.abspath(output_path)}")
        return True
    except Exception as e:
        print(f"导出失败: {e}")
        return False


def export_uploader_stats(downloads: Dict[int, Dict], output_path: str) -> bool:
    """导出上传者统计，按数量从高到低排序"""
    from collections import Counter

    counter = Counter(info['uploader'] for info in downloads.values() if info.get('uploader'))

    if not counter:
        print("没有上传者数据")
        return False

    try:
        with open(output_path, 'w', encoding='utf-8-sig', newline='') as f:
            writer = csv.writer(f)
            writer.writerow(['uploader', 'count'])
            for name, count in counter.most_common():
                writer.writerow([name, count])

        print(f"上传者统计已导出到: {os.path.abspath(output_path)} (共 {len(counter)} 人)")
        return True
    except Exception as e:
        print(f"导出失败: {e}")
        return False


def export_unfinished_stats(scan_data: List[Dict], downloads: Dict[int, Dict], output_path: str, min_images: int = 10, min_ratio: float = 0.5) -> bool:
    """导出未完成下载的漫画，按下载比例降序排列（同比例按下载图片数降序）

    筛选条件（满足其一即可）:
        1. 已下载图片数 >= min_images
        2. 下载比例 >= min_ratio（需要 .ehviewer 中有总页数）
    """
    unfinished = []
    for item in scan_data:
        if item.get('db_finished') or item.get('image_count', 0) == 0:
            continue

        expected = item.get('ehviewer_pages', 0) if item.get('ehviewer_pages', 0) > 0 else 0
        ratio_num = item['image_count'] / expected if expected > 0 else 0

        # 满足任一条件才纳入
        if item['image_count'] < min_images and ratio_num < min_ratio:
            continue

        gid = item['gid']
        title = downloads[gid]['title'] if gid in downloads else item['folder_name']
        ratio_str = f"{ratio_num:.1%}" if expected > 0 else '?'
        unfinished.append({
            'gid': gid,
            'title': title,
            'image_count': item['image_count'],
            'expected_pages': expected if expected > 0 else '?',
            'ratio': ratio_str,
            'ratio_num': ratio_num,
        })

    if not unfinished:
        print("没有符合条件的未完成漫画")
        return False

    unfinished.sort(key=lambda x: (x['ratio_num'], x['image_count']), reverse=True)

    try:
        with open(output_path, 'w', encoding='utf-8-sig', newline='') as f:
            writer = csv.writer(f)
            writer.writerow(['gid', 'title', 'downloaded', 'expected', 'ratio'])
            for item in unfinished:
                writer.writerow([
                    item['gid'],
                    item['title'],
                    item['image_count'],
                    item['expected_pages'],
                    item['ratio']
                ])

        print(f"未完成漫画统计已导出到: {os.path.abspath(output_path)} (共 {len(unfinished)} 个)")
        return True
    except Exception as e:
        print(f"导出失败: {e}")
        return False


def build_ehviewer_file(gid: int, token: str, pages: int) -> str:
    """构建.ehviewer文件内容 (VERSION2格式)"""
    if not token:
        token = "empty"
    return f"VERSION2\n{START_PAGE}\n{gid}\n{token}\n1\n{PREVIEW_PAGES}\n{PREVIEW_PER_PAGE}\n{pages}\n"


def create_ehviewer(folder: str, info: Dict, pages: int) -> bool:
    """创建.ehviewer文件"""
    try:
        content = build_ehviewer_file(info['gid'], info['token'], pages)
        path = os.path.join(folder, '.ehviewer')
        with open(path, 'w', encoding='utf-8') as f:
            f.write(content)
        return True
    except Exception as e:
        print(f"  创建.ehviewer失败: {e}")
        return False


def create_thumb(folder: str, image_source, warn_once: bool = True) -> bool:
    """创建.thumb缩略图
    
    Args:
        folder: 目标目录
        image_source: 图片文件名 (str) 或图片二进制数据 (bytes)
        warn_once: 是否警告 Pillow 未安装
    """
    if not HAS_PIL:
        if warn_once:
            print("  警告: Pillow未安装，无法创建缩略图")
        return False
    
    try:
        if isinstance(image_source, (bytes, bytearray)):
            img_obj = Image.open(io.BytesIO(image_source))
        elif isinstance(image_source, str):
            img_path = os.path.join(folder, image_source)
            img_obj = Image.open(img_path)
        else:
            img_obj = Image.open(image_source)

        with img_obj as img:
            resample_method = getattr(getattr(Image, 'Resampling', None), 'LANCZOS', getattr(Image, 'LANCZOS', getattr(Image, 'ANTIALIAS', None)))
            if resample_method is not None:
                img.thumbnail(THUMB_SIZE, resample_method)
            else:
                img.thumbnail(THUMB_SIZE)
            
            # 转换为RGB模式（处理透明通道）
            if img.mode in ('RGBA', 'LA') or (img.mode == 'P' and 'transparency' in getattr(img, 'info', {})):
                img_rgba = img.convert('RGBA')
                background = Image.new('RGB', img_rgba.size, (255, 255, 255))
                background.paste(img_rgba, mask=img_rgba.split()[3])
                img = background
            elif img.mode != 'RGB':
                img = img.convert('RGB')
            
            thumb_path = os.path.join(folder, '.thumb')
            img.save(thumb_path, 'JPEG', quality=THUMB_QUALITY)
        return True
    except Exception as e:
        print(f"  创建.thumb失败: {e}")
        return False


def build_comic_info_xml(gid: int, title: str, pages: int) -> str:
    """构建ComicInfo.xml内容，过滤 XML 1.0 非法控制字符"""
    title_str = title or ''
    # 过滤 XML 1.0 非法字符 (\x00-\x08, \x0b-\x0c, \x0e-\x1f)
    title_clean = re.sub(r'[\x00-\x08\x0b\x0c\x0e-\x1f]', '', title_str)
    escaped_title = html.escape(title_clean)
    
    xml = f'''<?xml version="1.0" encoding="UTF-8"?>
<ComicInfo>
  <Title>{escaped_title}</Title>
  <Series></Series>
  <Number></Number>
  <Writer></Writer>
  <LanguageISO></LanguageISO>
  <Pages>{pages}</Pages>
  <Web></Web>
  <Summary></Summary>
</ComicInfo>'''
    return xml


def compress_to_cbz(folder: str, gid: int, title: str, images: List[str] = None) -> Tuple[bool, str]:
    """将文件夹压缩为CBZ文件"""
    if images is None:
        images = get_image_files(folder)
    
    if not images:
        return False, "没有找到图片文件"
    
    pages = len(images)
    cbz_name = f"{gid}.cbz"
    cbz_path = os.path.join(folder, cbz_name)
    tmp_cbz_path = cbz_path + '.tmp'
    
    # 清理残留的旧临时文件
    if os.path.exists(tmp_cbz_path):
        try:
            os.remove(tmp_cbz_path)
        except OSError:
            pass

    try:
        # 1. 写入临时文件
        with zipfile.ZipFile(tmp_cbz_path, 'w', zipfile.ZIP_DEFLATED) as zipf:
            for img_name in images:
                img_path = os.path.join(folder, img_name)
                zipf.write(img_path, img_name)
            
            comic_info = build_comic_info_xml(gid, title, pages)
            zipf.writestr('ComicInfo.xml', comic_info.encode('utf-8'))
        
        # 2. 在替换原文件前，先校验临时 CBZ 完整性
        if not zipfile.is_zipfile(tmp_cbz_path):
            if os.path.exists(tmp_cbz_path):
                os.remove(tmp_cbz_path)
            return False, "CBZ 临时文件格式损坏，已保留原图"

        with zipfile.ZipFile(tmp_cbz_path, 'r') as zf:
            bad = zf.testzip()
            if bad is not None:
                if os.path.exists(tmp_cbz_path):
                    os.remove(tmp_cbz_path)
                return False, f"CBZ 中文件损坏: {bad}，已保留原图"

        # 3. 校验完全通过，原子替换为正式 CBZ 文件
        os.replace(tmp_cbz_path, cbz_path)

        # 4. 删除原始图片文件
        deleted_count = 0
        for img_name in images:
            img_path = os.path.join(folder, img_name)
            try:
                os.remove(img_path)
                deleted_count += 1
            except Exception as e:
                print(f"  警告: 删除图片失败 {img_name}: {e}")
        
        if deleted_count < pages:
            return True, f"成功压缩 {pages} 张图片, 删除 {deleted_count} 个原文件 (警告: {pages - deleted_count} 个文件删除失败)"
        return True, f"成功压缩 {pages} 张图片, 删除 {deleted_count} 个原文件"
        
    except Exception as e:
        if os.path.exists(tmp_cbz_path):
            try:
                os.remove(tmp_cbz_path)
            except OSError:
                pass
        return False, f"压缩失败: {str(e)}"


def fix_missing_files(folders: List[Tuple], downloads: Dict, report_lines: List[str]) -> Dict[int, Tuple[bool, bool]]:
    """修复缺失的.ehviewer和.thumb文件（支持未压缩与已压缩漫画）
    
    Args:
        folders: [(gid, title, folder_path), ...]
        downloads: 数据库字典
        report_lines: 报告文本列表
    
    Returns:
        Dict of {gid: (created_ehviewer, created_thumb)}
    """
    if not HAS_PIL:
        print("警告: Pillow未安装，无法创建缩略图")
    
    fixed_ehviewer = 0
    fixed_thumb = 0
    failed_ehviewer = 0
    failed_thumb = 0
    created_results = {}
    warned_pil = False
    
    for gid, title, folder_path in folders:
        if gid not in downloads:
            continue
        
        info = downloads[gid]
        has_eh = has_ehviewer(folder_path)
        has_th = has_thumb(folder_path)
        
        if has_eh and has_th:
            continue
        
        # 1. 优先从裸图获取
        images = get_image_files(folder_path)
        pages = len(images)
        first_img_source = images[0] if images else None
        
        # 2. 若裸图不存在，尝试从已有的 CBZ 压缩包读取封面和页数
        if not images:
            cbz_path = find_cbz_file(folder_path)
            if cbz_path:
                cbz_images, first_img_bytes = get_cbz_image_info(cbz_path)
                pages = len(cbz_images)
                first_img_source = first_img_bytes
        
        if pages == 0 or first_img_source is None:
            continue
        
        created_eh = False
        created_th = False
        
        # 检查并创建 .ehviewer
        if not has_eh:
            if create_ehviewer(folder_path, info, pages):
                fixed_ehviewer += 1
                created_eh = True
                report_lines.append(f"[修复] {gid}: 创建.ehviewer成功")
            else:
                failed_ehviewer += 1
                report_lines.append(f"[失败] {gid}: 创建.ehviewer失败")
        
        # 检查并创建 .thumb
        if not has_th:
            warn_flag = not warned_pil
            if create_thumb(folder_path, first_img_source, warn_once=warn_flag):
                fixed_thumb += 1
                created_th = True
                report_lines.append(f"[修复] {gid}: 创建.thumb成功")
            else:
                failed_thumb += 1
                report_lines.append(f"[失败] {gid}: 创建.thumb失败")
            if not HAS_PIL:
                warned_pil = True
        
        if created_eh or created_th:
            created_results[gid] = (created_eh, created_th)
    
    print(f"\n修复完成:")
    print(f"  .ehviewer: {fixed_ehviewer} 成功, {failed_ehviewer} 失败")
    print(f"  .thumb:    {fixed_thumb} 成功, {failed_thumb} 失败")
    
    return created_results


def do_compress(to_process: List[Dict], stats_result: Dict, report_lines: List[str], show_progress: bool = True) -> Tuple[Dict, bool]:
    """执行压缩处理
    
    Returns:
        (stats_result, was_interrupted)
    """
    print(f"\n准备压缩 {len(to_process)} 个漫画...")
    print("按 Ctrl+C 可以中断")
    
    need_manual_print = show_progress and tqdm is None
    interrupted = False
    
    try:
        if show_progress and tqdm:
            iterator = tqdm(to_process, desc="压缩进度", unit="个")
        else:
            iterator = to_process
        
        for item in iterator:
            gid = item['gid']
            title = item['title']
            folder = item['folder']
            images = item.get('images')
            
            success, msg = compress_to_cbz(folder, gid, title, images)
            
            if success:
                stats_result['compressed'] += 1
                report_lines.append(f"[成功] {gid}: {title[:40]} - {msg}")
                if need_manual_print:
                    print(f"✓ [{gid}] {title[:40]} - {msg}")
            else:
                stats_result['failed'] += 1
                report_lines.append(f"[失败] {gid}: {title[:40]} - {msg}")
                if need_manual_print:
                    print(f"✗ [{gid}] {title[:40]} - {msg}")
    
    except KeyboardInterrupt:
        interrupted = True
        print()
        print("收到中断信号，正在退出...")
        report_lines.append("")
        report_lines.append("用户中断处理")
    
    print()
    print("=" * 60)
    print("处理完成!")
    print(f"  成功压缩: {stats_result['compressed']}")
    print(f"  失败:     {stats_result['failed']}")
    if interrupted:
        print("  (用户已中断)")
    print("=" * 60)
    
    report_lines.append("")
    report_lines.append("=== 处理结果 ===")
    report_lines.append(f"成功压缩: {stats_result['compressed']}")
    report_lines.append(f"失败: {stats_result['failed']}")
    
    return stats_result, interrupted


def scan_folders_with_info(root_path: str, downloads: Dict, force: bool) -> Tuple[List, Dict, List]:
    """扫描文件夹并收集信息，单次遍历完成所有检查
    
    Returns:
        Tuple of (to_process_list, stats_dict, scan_data_list)
    """
    to_process = []
    scan_data = []
    stats = {
        'total_folders': 0,
        'in_database': 0,
        'not_in_database': 0,
        'not_finished': 0,
        'already_compressed': 0,
        'to_compress': 0,
        'compressed': 0,
        'failed': 0,
        'missing_ehviewer': 0,
        'missing_thumb': 0,
        'missing_both': 0,
    }
    
    for name in os.listdir(root_path):
        folder_path = os.path.join(root_path, name)
        if not os.path.isdir(folder_path):
            continue
        
        match = FOLDER_PATTERN.match(name)
        if not match:
            continue
        
        stats['total_folders'] += 1
        gid = int(match.group(1))
        title = match.group(2)
        
        has_eh = has_ehviewer(folder_path)
        has_th = has_thumb(folder_path)
        
        if not has_eh and not has_th:
            stats['missing_both'] += 1
        elif not has_eh:
            stats['missing_ehviewer'] += 1
        elif not has_th:
            stats['missing_thumb'] += 1
        
        db_finished = False
        if gid in downloads:
            db_finished = downloads[gid].get('is_finished', False)
        
        images = get_image_files(folder_path)
        image_count = len(images)
        
        ehviewer_pages = read_ehviewer_pages(folder_path) if has_eh else -1
        
        scan_data.append({
            'gid': gid,
            'folder_name': name,
            'image_count': image_count,
            'has_ehviewer': has_eh,
            'has_thumb': has_th,
            'created_ehviewer': False,
            'created_thumb': False,
            'ehviewer_pages': ehviewer_pages,
            'db_finished': db_finished
        })
        
        if gid not in downloads:
            stats['not_in_database'] += 1
            continue
        
        stats['in_database'] += 1
        download = downloads[gid]
        
        if not download['is_finished']:
            stats['not_finished'] += 1
            continue
        
        if is_already_compressed(folder_path):
            if not force:
                stats['already_compressed'] += 1
                continue
            print(f"  警告: {gid} 已压缩，使用 --force 将重新压缩")
        
        if not images:
            continue
        
        stats['to_compress'] += 1
        to_process.append({
            'gid': gid,
            'title': download['title'] or title,
            'folder': folder_path,
            'images': images
        })
    
    return to_process, stats, scan_data


def process_comics(db_path: str, root: str, dry_run: bool = False, 
                   fix_missing: bool = False, force: bool = False, 
                   show_progress: bool = True):
    """处理所有漫画"""
    
    print("=" * 60)
    print("SMB漫画压缩工具")
    print("=" * 60)
    
    report_lines = []
    report_lines.append(f"运行时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    report_lines.append(f"数据库: {db_path}")
    report_lines.append(f"漫画目录: {root}")
    report_lines.append("")
    
    if dry_run:
        print("⚠️  预览模式 - 不会实际修改或压缩文件")
        report_lines.append("模式: 预览模式")
    elif fix_missing:
        print("⚠️  修复模式 - 修复缺失文件并执行压缩")
        report_lines.append("模式: 压缩并修复缺失的.ehviewer和.thumb")
    else:
        report_lines.append("模式: 压缩模式")
    print()
    
    downloads = read_database(db_path)
    folder_count = len(os.listdir(root))
    print(f"扫描到 {folder_count} 个项目")
    
    to_process, stats, scan_data = scan_folders_with_info(root, downloads, force)
    export_scan_result(scan_data, SCAN_RESULT_FILE)
    
    report_lines.append("=== 缺失文件统计 ===")
    report_lines.append(f"总文件夹数: {stats['total_folders']}")
    report_lines.append(f"仅缺失.ehviewer: {stats['missing_ehviewer']}")
    report_lines.append(f"仅缺失.thumb: {stats['missing_thumb']}")
    report_lines.append(f"两者都缺失: {stats['missing_both']}")
    report_lines.append("")
    
    print("缺失文件统计:")
    print(f"  总文件夹数:     {stats['total_folders']}")
    print(f"  仅缺失.ehviewer: {stats['missing_ehviewer']}")
    print(f"  仅缺失.thumb:   {stats['missing_thumb']}")
    print(f"  两者都缺失:     {stats['missing_both']}")
    
    print()
    print("统计:")
    print(f"  文件夹总数:     {stats['total_folders']}")
    print(f"  数据库有记录:   {stats['in_database']}")
    print(f"  数据库无记录:   {stats['not_in_database']}")
    print(f"  未完成下载:     {stats['not_finished']}")
    print(f"  已压缩:         {stats['already_compressed']}")
    print(f"  等待压缩:       {stats['to_compress']}")
    
    report_lines.append("=== 处理统计 ===")
    report_lines.append(f"文件夹总数: {stats['total_folders']}")
    report_lines.append(f"数据库有记录: {stats['in_database']}")
    report_lines.append(f"数据库无记录: {stats['not_in_database']}")
    report_lines.append(f"未完成下载: {stats['not_finished']}")
    report_lines.append(f"已压缩: {stats['already_compressed']}")
    report_lines.append(f"等待压缩: {stats['to_compress']}")
    report_lines.append("")
    
    # 筛选需要修复的画廊（只要已在数据库中标记完成，且缺失文件，无论未压缩还是已压缩均支持修复）
    folders_to_fix = [
        (item['gid'], item['folder_name'], os.path.join(root, item['folder_name']))
        for item in scan_data
        if item['gid'] in downloads and downloads[item['gid']]['is_finished'] and (not item['has_ehviewer'] or not item['has_thumb'])
    ]

    # ── 预览模式（严格只读，不进行任何写磁盘操作）──
    if dry_run:
        if fix_missing:
            print(f"\n[预览] 发现 {len(folders_to_fix)} 个文件夹需要修复缺失文件:")
            report_lines.append("=== 待修复文件列表 (预览) ===")
            for gid, title, _ in folders_to_fix[:DRY_RUN_PREVIEW_COUNT]:
                print(f"  [{gid}] {title[:50]}")
                report_lines.append(f"  [{gid}] {title[:50]}")
            if len(folders_to_fix) > DRY_RUN_PREVIEW_COUNT:
                print(f"  ... 还有 {len(folders_to_fix) - DRY_RUN_PREVIEW_COUNT} 个")
                report_lines.append(f"  ... 还有 {len(folders_to_fix) - DRY_RUN_PREVIEW_COUNT} 个")

        if to_process:
            print(f"\n以下 {len(to_process)} 个漫画将进行压缩:")
            report_lines.append("=== 待压缩列表 ===")
            for item in to_process[:DRY_RUN_PREVIEW_COUNT]:
                report_lines.append(f"  [{item['gid']}] {item['title'][:50]} - {len(item['images'])} 页")
                print(f"  [{item['gid']}] {item['title'][:50]} - {len(item['images'])} 页")
            if len(to_process) > DRY_RUN_PREVIEW_COUNT:
                report_lines.append(f"  ... 还有 {len(to_process) - DRY_RUN_PREVIEW_COUNT} 个")
                print(f"  ... 还有 {len(to_process) - DRY_RUN_PREVIEW_COUNT} 个")
        else:
            print("\n没有需要压缩的漫画")
            report_lines.append("没有需要压缩的漫画")

        export_uploader_stats(downloads, UPLOADER_STATS_FILE)
        export_unfinished_stats(scan_data, downloads, UNFINISHED_STATS_FILE)
        save_report(report_lines)
        return

    # ── 真实执行阶段 ──
    created_results = {}
    if fix_missing:
        report_lines.append("=== 修复详情 ===")
        created_results = fix_missing_files(folders_to_fix, downloads, report_lines)
        for item in scan_data:
            gid = item['gid']
            if gid in created_results:
                item['created_ehviewer'] = created_results[gid][0]
                item['created_thumb'] = created_results[gid][1]
        export_scan_result(scan_data, SCAN_RESULT_FILE)
        report_lines.append("")

    if not to_process:
        print("\n没有需要压缩的漫画")
        report_lines.append("没有需要压缩的漫画")
        save_report(report_lines)
        return

    report_lines.append("=== 压缩详情 ===")
    stats, interrupted = do_compress(to_process, stats, report_lines, show_progress)
    
    # 压缩完成后重新扫描并导出最新结果
    _, _, scan_data = scan_folders_with_info(root, downloads, force)
    for item in scan_data:
        gid = item['gid']
        if gid in created_results:
            item['created_ehviewer'] = created_results[gid][0]
            item['created_thumb'] = created_results[gid][1]
    export_scan_result(scan_data, SCAN_RESULT_FILE)
    
    if interrupted:
        print("\n已导出中断前的状态，程序退出")
        save_report(report_lines)
        return
    
    save_report(report_lines)


def save_report(report_lines: List[str]) -> bool:
    """保存报告到文件"""
    try:
        with open(REPORT_FILE, 'w', encoding='utf-8') as f:
            f.write('\n'.join(report_lines))
        print(f"\n报告已保存到: {os.path.abspath(REPORT_FILE)}")
        return True
    except Exception as e:
        print(f"\n警告: 保存报告失败: {e}")
        return False


def main():
    if len(sys.argv) == 1:
        _launch_gui()
        return

    args = parse_args()
    root_path = args.root.strip('"').strip("'")
    db_path = args.db.strip('"').strip("'")

    if not os.path.isdir(root_path):
        print(f"错误: 漫画文件夹不存在: {root_path}")
        sys.exit(1)

    show_progress = not args.no_progress and tqdm is not None

    if args.fix_missing and not HAS_PIL:
        print("警告: Pillow未安装，修复.thumb功能将不可用")

    try:
        process_comics(
            db_path=db_path,
            root=root_path,
            dry_run=args.dry_run,
            fix_missing=args.fix_missing,
            force=args.force,
            show_progress=show_progress
        )
    except Exception as e:
        print(f"\n执行过程中出错: {e}")
        sys.exit(1)


# ============ GUI ============

def _launch_gui():
    """启动 tkinter GUI（无参数时自动调用）"""
    try:
        import tkinter as tk
        from tkinter import ttk, filedialog, messagebox, scrolledtext
    except ImportError:
        print("错误: tkinter 不可用，请使用命令行模式:")
        print("  python smb_comic_archiver.py --db <数据库> --root <目录> --help")
        sys.exit(1)

    class App:
        def __init__(self, root: tk.Tk):
            self.root = root
            self.root.title("SMB 漫画压缩工具")
            self.root.geometry("720x600")
            self.root.minsize(600, 500)
            self.running = False
            self.stop_flag = False
            self.root.protocol("WM_DELETE_WINDOW", self._on_close)
            self._build_ui()

        def _on_close(self):
            if self.running:
                if messagebox.askyesno("提示", "任务正在进行中，确定要中断并退出吗？"):
                    self.stop_flag = True
                    self.root.destroy()
            else:
                self.root.destroy()

        def _build_ui(self):
            pad = {"padx": 8, "pady": 4}

            # ── 路径选择区 ──
            path_frame = ttk.LabelFrame(self.root, text="路径设置")
            path_frame.pack(fill=tk.X, **pad)

            ttk.Label(path_frame, text="数据库文件:").grid(row=0, column=0, sticky=tk.W, **pad)
            self.db_var = tk.StringVar()
            ttk.Entry(path_frame, textvariable=self.db_var, width=50).grid(row=0, column=1, sticky=tk.EW, **pad)
            ttk.Button(path_frame, text="浏览...", command=self._browse_db).grid(row=0, column=2, **pad)

            ttk.Label(path_frame, text="漫画目录:").grid(row=1, column=0, sticky=tk.W, **pad)
            self.root_var = tk.StringVar()
            ttk.Entry(path_frame, textvariable=self.root_var, width=50).grid(row=1, column=1, sticky=tk.EW, **pad)
            ttk.Button(path_frame, text="浏览...", command=self._browse_root).grid(row=1, column=2, **pad)

            path_frame.columnconfigure(1, weight=1)

            # ── 选项区 ──
            opt_frame = ttk.LabelFrame(self.root, text="选项")
            opt_frame.pack(fill=tk.X, **pad)

            self.dry_run_var = tk.BooleanVar()
            self.fix_missing_var = tk.BooleanVar()
            self.force_var = tk.BooleanVar()

            ttk.Checkbutton(opt_frame, text="预览模式 (只扫描，不修改或压缩文件)", variable=self.dry_run_var).pack(anchor=tk.W, padx=8, pady=2)
            ttk.Checkbutton(opt_frame, text="修复缺失的 .ehviewer 和 .thumb 文件", variable=self.fix_missing_var).pack(anchor=tk.W, padx=8, pady=2)
            ttk.Checkbutton(opt_frame, text="强制重新压缩已压缩的漫画", variable=self.force_var).pack(anchor=tk.W, padx=8, pady=2)

            # ── 按钮区 ──
            btn_frame = ttk.Frame(self.root)
            btn_frame.pack(fill=tk.X, **pad)

            self.start_btn = ttk.Button(btn_frame, text="开始", command=self._start)
            self.start_btn.pack(side=tk.LEFT, padx=4)

            self.stop_btn = ttk.Button(btn_frame, text="停止", command=self._stop, state=tk.DISABLED)
            self.stop_btn.pack(side=tk.LEFT, padx=4)

            # ── 进度条 ──
            prog_frame = ttk.Frame(self.root)
            prog_frame.pack(fill=tk.X, **pad)

            self.progress_var = tk.DoubleVar()
            self.progress_bar = ttk.Progressbar(prog_frame, variable=self.progress_var, maximum=100)
            self.progress_bar.pack(fill=tk.X, side=tk.LEFT, expand=True, padx=(0, 8))

            self.progress_label = ttk.Label(prog_frame, text="0/0")
            self.progress_label.pack(side=tk.RIGHT)

            # ── 日志区 ──
            log_frame = ttk.LabelFrame(self.root, text="日志")
            log_frame.pack(fill=tk.BOTH, expand=True, **pad)

            self.log_text = scrolledtext.ScrolledText(log_frame, height=15, state=tk.DISABLED, wrap=tk.WORD, font=("Consolas", 9))
            self.log_text.pack(fill=tk.BOTH, expand=True, padx=4, pady=4)

            # ── 底部统计栏 ──
            self.stats_var = tk.StringVar(value="就绪")
            stats_label = ttk.Label(self.root, textvariable=self.stats_var, relief=tk.SUNKEN, anchor=tk.W)
            stats_label.pack(fill=tk.X, side=tk.BOTTOM, padx=8, pady=(0, 8))

        def _browse_db(self):
            path = filedialog.askopenfilename(
                title="选择 EhViewer 数据库文件",
                filetypes=[("SQLite 数据库", "*.db"), ("所有文件", "*.*")],
            )
            if path:
                self.db_var.set(os.path.normpath(path))

        def _browse_root(self):
            path = filedialog.askdirectory(title="选择漫画文件夹根目录")
            if path:
                self.root_var.set(os.path.normpath(path))

        def _log(self, msg: str):
            def _append():
                try:
                    self.log_text.config(state=tk.NORMAL)
                    self.log_text.insert(tk.END, msg + "\n")
                    self.log_text.see(tk.END)
                    self.log_text.config(state=tk.DISABLED)
                except Exception:
                    pass
            try:
                self.root.after(0, _append)
            except Exception:
                pass

        def _clear_log(self):
            self.log_text.config(state=tk.NORMAL)
            self.log_text.delete("1.0", tk.END)
            self.log_text.config(state=tk.DISABLED)

        def _update_stats(self, text: str):
            try:
                self.root.after(0, lambda: self.stats_var.set(text))
            except Exception:
                pass

        def _update_progress(self, current: int, total: int):
            pct = (current / total * 100) if total > 0 else 0
            try:
                self.root.after(0, lambda: self.progress_var.set(pct))
                self.root.after(0, lambda: self.progress_label.config(text=f"{current}/{total}"))
            except Exception:
                pass

        def _set_running(self, running: bool):
            self.running = running
            try:
                self.root.after(0, lambda: self.start_btn.config(state=tk.DISABLED if running else tk.NORMAL))
                self.root.after(0, lambda: self.stop_btn.config(state=tk.NORMAL if running else tk.DISABLED))
            except Exception:
                pass

        def _start(self):
            db_path = self.db_var.get().strip().strip('"').strip("'")
            root_path = self.root_var.get().strip().strip('"').strip("'")
            if not db_path:
                messagebox.showwarning("提示", "请选择数据库文件")
                return
            if not root_path:
                messagebox.showwarning("提示", "请选择漫画目录")
                return
            if not os.path.exists(db_path):
                messagebox.showerror("错误", f"数据库文件不存在:\n{db_path}")
                return
            if not os.path.isdir(root_path):
                messagebox.showerror("错误", f"漫画目录不存在:\n{root_path}")
                return

            self._clear_log()
            self._set_running(True)
            self.stop_flag = False
            self.progress_var.set(0)

            t = threading.Thread(target=self._run, args=(db_path, root_path), daemon=True)
            t.start()

        def _stop(self):
            self.stop_flag = True
            self._log("\n⏹ 正在停止，等待当前任务完成...")

        def _run(self, db_path: str, root_path: str):
            try:
                self._run_internal(db_path, root_path)
            except Exception as e:
                import traceback
                self._log(f"\n❌ 运行发生未捕获异常: {e}")
                self._log(traceback.format_exc())
                self._update_stats("运行出错")
            finally:
                self._set_running(False)

        def _run_internal(self, db_path: str, root_path: str):
            dry_run = self.dry_run_var.get()
            fix_missing = self.fix_missing_var.get()
            force = self.force_var.get()

            report_lines = []
            report_lines.append(f"运行时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
            report_lines.append(f"数据库: {db_path}")
            report_lines.append(f"漫画目录: {root_path}")
            report_lines.append("")

            self._log("=" * 50)
            self._log("SMB 漫画压缩工具")
            self._log("=" * 50)

            if dry_run:
                self._log("⚠️  预览模式 - 不会实际修改或压缩文件")
            elif fix_missing:
                self._log("⚠️  修复模式 - 修复缺失文件并压缩")
            self._log("")

            self._log("正在读取数据库...")
            downloads = read_database(db_path)
            self._update_stats(f"数据库记录: {len(downloads)}")

            self._log("正在扫描文件夹...")
            to_process, stats, scan_data = scan_folders_with_info(root_path, downloads, force)

            export_scan_result(scan_data, SCAN_RESULT_FILE)
            self._log(f"扫描结果已导出: {os.path.abspath(SCAN_RESULT_FILE)}")

            self._log("")
            self._log("── 统计 ──")
            self._log(f"  文件夹总数:     {stats['total_folders']}")
            self._log(f"  数据库有记录:   {stats['in_database']}")
            self._log(f"  数据库无记录:   {stats['not_in_database']}")
            self._log(f"  未完成下载:     {stats['not_finished']}")
            self._log(f"  已压缩:         {stats['already_compressed']}")
            self._log(f"  等待压缩:       {stats['to_compress']}")
            self._log(f"  仅缺失.ehviewer: {stats['missing_ehviewer']}")
            self._log(f"  仅缺失.thumb:   {stats['missing_thumb']}")
            self._log(f"  两者都缺失:     {stats['missing_both']}")
            self._log("")

            folders_to_fix = [
                (item['gid'], item['folder_name'], os.path.join(root_path, item['folder_name']))
                for item in scan_data
                if item['gid'] in downloads and downloads[item['gid']]['is_finished'] and (not item['has_ehviewer'] or not item['has_thumb'])
            ]

            # 预览模式（严格只读）
            if dry_run:
                if fix_missing:
                    self._log(f"[预览] 发现 {len(folders_to_fix)} 个文件夹需要修复缺失文件:")
                    for gid, title, _ in folders_to_fix[:10]:
                        self._log(f"  [{gid}] {title[:50]}")
                    if len(folders_to_fix) > 10:
                        self._log(f"  ... 还有 {len(folders_to_fix) - 10} 个")
                    self._log("")

                if not to_process:
                    self._log("没有需要压缩的漫画")
                else:
                    self._log(f"以下 {len(to_process)} 个漫画将进行压缩:")
                    for item in to_process[:10]:
                        self._log(f"  [{item['gid']}] {item['title'][:50]} - {len(item['images'])} 页")
                    if len(to_process) > 10:
                        self._log(f"  ... 还有 {len(to_process) - 10} 个")

                export_uploader_stats(downloads, UPLOADER_STATS_FILE)
                self._log(f"上传者统计已导出: {os.path.abspath(UPLOADER_STATS_FILE)}")

                export_unfinished_stats(scan_data, downloads, UNFINISHED_STATS_FILE)
                self._log(f"未完成漫画统计已导出: {os.path.abspath(UNFINISHED_STATS_FILE)}")

                self._update_stats("预览完成")
                return

            created_results = {}
            if fix_missing:
                self._log(f"── 修复缺失文件 (共 {len(folders_to_fix)} 个待处理) ──")
                created_results = fix_missing_files(folders_to_fix, downloads, report_lines)
                for item in scan_data:
                    gid = item['gid']
                    if gid in created_results:
                        item['created_ehviewer'] = created_results[gid][0]
                        item['created_thumb'] = created_results[gid][1]
                export_scan_result(scan_data, SCAN_RESULT_FILE)
                self._log("")

            if not to_process:
                self._log("没有需要压缩的漫画")
                self._update_stats("无待压缩任务")
                save_report(report_lines)
                return

            self._log(f"\n── 开始压缩 {len(to_process)} 个漫画 ──")
            total = len(to_process)
            success_count = 0
            fail_count = 0

            for i, item in enumerate(to_process):
                if self.stop_flag:
                    self._log(f"\n⏹ 用户中断，已完成 {i}/{total}")
                    break

                gid = item['gid']
                title = item['title']
                folder = item['folder']

                self._update_progress(i, total)
                self._update_stats(f"压缩中: {i + 1}/{total} | 成功: {success_count} | 失败: {fail_count}")

                success, msg = compress_to_cbz(folder, gid, title, item.get('images'))

                if success:
                    success_count += 1
                    self._log(f"  ✓ [{gid}] {title[:40]} - {msg}")
                    report_lines.append(f"[成功] {gid}: {title[:40]} - {msg}")
                else:
                    fail_count += 1
                    self._log(f"  ✗ [{gid}] {title[:40]} - {msg}")
                    report_lines.append(f"[失败] {gid}: {title[:40]} - {msg}")

            if not self.stop_flag:
                self._update_progress(total, total)

            # 重新扫描导出
            _, _, scan_data = scan_folders_with_info(root_path, downloads, force)
            for item in scan_data:
                gid = item['gid']
                if gid in created_results:
                    item['created_ehviewer'] = created_results[gid][0]
                    item['created_thumb'] = created_results[gid][1]
            export_scan_result(scan_data, SCAN_RESULT_FILE)

            self._log("")
            self._log("=" * 50)
            self._log("处理完成!")
            self._log(f"  成功压缩: {success_count}")
            self._log(f"  失败:     {fail_count}")
            if self.stop_flag:
                self._log("  (用户已中断)")
            self._log("=" * 50)

            if self.stop_flag:
                self._update_stats(f"已停止 | 成功: {success_count} | 失败: {fail_count}")
            else:
                self._update_stats(f"完成 | 成功: {success_count} | 失败: {fail_count}")

            report_lines.append("")
            report_lines.append("=== 处理结果 ===")
            report_lines.append(f"成功压缩: {success_count}")
            report_lines.append(f"失败: {fail_count}")
            save_report(report_lines)
            self._log(f"报告已保存: {os.path.abspath(REPORT_FILE)}")

    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == '__main__':
    main()
