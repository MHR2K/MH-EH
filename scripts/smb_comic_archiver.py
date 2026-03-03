#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SMB漫画文件夹压缩工具
用于将SMB上的漫画文件夹压缩为CBZ格式，并删除原文件

用法:
    python smb_comic_archiver.py --db /path/to/eh.db --root /path/to/comic/folder

选项:
    --db         EhViewer数据库文件路径
    --root       漫画文件夹根目录路径
    --dry-run    预览模式，只扫描不压缩
    --fix-missing 修复缺失的.ehviewer和.thumb文件
    --force      强制重新压缩已压缩的漫画
    --no-progress 不显示进度条
    -h, --help   显示帮助信息
"""

import argparse
import html
import os
import re
import sqlite3
import sys
import zipfile
from datetime import datetime
from typing import Dict, List, Tuple

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
    parser.add_argument('--dry-run', action='store_true', help='预览模式，只扫描不压缩')
    parser.add_argument('--fix-missing', action='store_true', help='修复缺失的.ehviewer和.thumb文件')
    parser.add_argument('--force', action='store_true', help='强制重新压缩已压缩的漫画')
    parser.add_argument('--no-progress', action='store_true', help='不显示进度条')
    
    return parser.parse_args()


def read_database(db_path: str) -> Dict[int, Dict]:
    """从数据库读取下载记录，返回 {gid: info}"""
    if not os.path.exists(db_path):
        print(f"错误: 数据库文件不存在: {db_path}")
        sys.exit(1)
    
    with sqlite3.connect(db_path) as conn:
        cursor = conn.cursor()
        
        # 查询Downloads表
        cursor.execute('''
            SELECT GID, TITLE, TITLE_JPN, TOKEN, THUMB, CATEGORY, POSTED, UPLOADER, RATING, STATE, LEGACY 
            FROM DOWNLOADS 
            ORDER BY GID
        ''')
        
        results = {}
        for row in cursor.fetchall():
            gid, title, title_jpn, token, thumb, category, posted, uploader, rating, state, legacy = row
            # STATE=3 表示下载完成
            is_finished = (state == STATE_FINISH) or (legacy is not None and legacy == 0)
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
    return os.path.exists(os.path.join(folder, '.ehviewer'))


def has_thumb(folder: str) -> bool:
    """检查是否存在.thumb文件"""
    return os.path.exists(os.path.join(folder, '.thumb'))


def is_already_compressed(folder: str) -> bool:
    """检查是否已经压缩（存在.cbz文件）"""
    if not os.path.isdir(folder):
        return False
    
    for f in os.listdir(folder):
        if f.lower().endswith('.cbz'):
            return True
    return False


def read_ehviewer_pages(folder: str) -> int:
    """读取.ehviewer文件获取页数
    
    Returns:
        页数，如果读取失败返回-1
    """
    ehviewer_path = os.path.join(folder, '.ehviewer')
    if not os.path.exists(ehviewer_path):
        return -1
    
    try:
        with open(ehviewer_path, 'r', encoding='utf-8') as f:
            lines = [line.strip() for line in f.readlines()]
        
        # VERSION2 格式，第8行是页数
        if len(lines) >= 8 and lines[0] == 'VERSION2':
            return int(lines[7])
        # VERSION1 格式，页数在最后
        elif len(lines) >= 3:
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
        # CSV表头
        headers = [
            'gid', 'folder_name', 'db_finished', 'image_count', 'ehviewer_pages',
            'has_ehviewer', 'has_thumb', 'created_ehviewer', 'created_thumb'
        ]
        
        with open(output_path, 'w', encoding='utf-8-sig', newline='') as f:
            # 写入表头
            f.write(','.join(headers) + '\n')
            
            # 写入数据
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
                f.write(','.join(row) + '\n')
        
        print(f"扫描结果已导出到: {output_path}")
        return True
    except Exception as e:
        print(f"导出失败: {e}")
        return False


def build_ehviewer_file(gid: int, token: str, pages: int) -> str:
    """构建.ehviewer文件内容 (VERSION2格式)
    
    格式:
    VERSION2
    {startPage (8位十六进制)}
    {gid}
    {token}
    1 (deprecated mode, 废弃)
    {previewPages}
    {previewPerPage}
    {pages}
    """
    # token为空时使用占位符
    if not token:
        token = "empty"
    
    ehviewer = f"VERSION2\n{START_PAGE}\n{gid}\n{token}\n1\n{PREVIEW_PAGES}\n{PREVIEW_PER_PAGE}\n{pages}"
    return ehviewer


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


def create_thumb(folder: str, first_image: str, warn_once: bool = True) -> bool:
    """创建.thumb缩略图"""
    if not HAS_PIL:
        if warn_once:
            print("  警告: Pillow未安装，无法创建缩略图")
        return False
    
    try:
        img_path = os.path.join(folder, first_image)
        with Image.open(img_path) as img:
            # 保持纵横比，压缩为指定尺寸
            img.thumbnail(THUMB_SIZE, Image.Resampling.LANCZOS)
            
            # 转换为RGB模式（如果是RGBA）
            if img.mode == 'RGBA':
                # 创建白色背景
                background = Image.new('RGB', img.size, (255, 255, 255))
                background.paste(img, mask=img.split()[3])
                img = background
            elif img.mode != 'RGB':
                img = img.convert('RGB')
            
            # 保存为.jpg
            thumb_path = os.path.join(folder, '.thumb')
            img.save(thumb_path, 'JPEG', quality=THUMB_QUALITY)
        return True
    except Exception as e:
        print(f"  创建.thumb失败: {e}")
        return False


def build_comic_info_xml(gid: int, title: str, pages: int) -> str:
    """构建ComicInfo.xml内容"""
    title = html.escape(title) if title else ''
    
    xml = f'''<?xml version="1.0" encoding="UTF-8"?>
<ComicInfo>
  <Title>{title}</Title>
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
    
    # 如果没有传入图片列表，则获取
    if images is None:
        images = get_image_files(folder)
    
    if not images:
        return False, "没有找到图片文件"
    
    pages = len(images)
    
    # 目标CBZ文件名
    cbz_name = f"{gid}.cbz"
    cbz_path = os.path.join(folder, cbz_name)
    
    # 如果已存在，先删除
    if os.path.exists(cbz_path):
        os.remove(cbz_path)
    
    # 创建临时文件名
    tmp_cbz_path = cbz_path + '.tmp'
    
    try:
        with zipfile.ZipFile(tmp_cbz_path, 'w', zipfile.ZIP_DEFLATED) as zipf:
            # 添加图片文件
            for img_name in images:
                img_path = os.path.join(folder, img_name)
                zipf.write(img_path, img_name)
            
            # 添加ComicInfo.xml
            comic_info = build_comic_info_xml(gid, title, pages)
            zipf.writestr('ComicInfo.xml', comic_info)
        
        # 原子替换
        os.replace(tmp_cbz_path, cbz_path)
        
        # 删除原始图片文件
        deleted_count = 0
        for img_name in images:
            img_path = os.path.join(folder, img_name)
            try:
                os.remove(img_path)
                deleted_count += 1
            except Exception as e:
                print(f"  警告: 删除图片失败 {img_name}: {e}")
        
        return True, f"成功压缩 {pages} 张图片, 删除 {deleted_count} 个原文件"
        
    except Exception as e:
        # 清理临时文件
        if os.path.exists(tmp_cbz_path):
            os.remove(tmp_cbz_path)
        return False, f"压缩失败: {str(e)}"


def fix_missing_files(folders: List[Tuple], downloads: Dict, report_lines: List[str], scan_data: List[Dict] = None) -> Dict[int, Tuple[bool, bool]]:
    """修复缺失的.ehviewer和.thumb文件
    
    Returns:
        Dict of {gid: (created_ehviewer, created_thumb)}
    """
    if not HAS_PIL:
        print("警告: Pillow未安装，无法创建缩略图")
    
    fixed_ehviewer = 0
    fixed_thumb = 0
    failed_ehviewer = 0
    failed_thumb = 0
    
    # 记录哪些文件被成功创建
    created_results = {}
    
    for gid, title, folder_path in folders:
        if gid not in downloads:
            continue
        
        info = downloads[gid]
        images = get_image_files(folder_path)
        
        if not images:
            continue
        
        pages = len(images)
        
        created_eh = False
        created_th = False
        
        # 检查并创建.ehviewer
        if not has_ehviewer(folder_path):
            if create_ehviewer(folder_path, info, pages):
                fixed_ehviewer += 1
                created_eh = True
                report_lines.append(f"[修复] {gid}: 创建.ehviewer成功")
            else:
                failed_ehviewer += 1
                report_lines.append(f"[失败] {gid}: 创建.ehviewer失败")
        
        # 检查并创建.thumb
        if not has_thumb(folder_path):
            if create_thumb(folder_path, images[0]):
                fixed_thumb += 1
                created_th = True
                report_lines.append(f"[修复] {gid}: 创建.thumb成功")
            else:
                failed_thumb += 1
                report_lines.append(f"[失败] {gid}: 创建.thumb失败")
        
        if created_eh or created_th:
            created_results[gid] = (created_eh, created_th)
    
    print(f"\n修复完成:")
    print(f"  .ehviewer: {fixed_ehviewer} 成功, {failed_ehviewer} 失败")
    print(f"  .thumb: {fixed_thumb} 成功, {failed_thumb} 失败")
    
    return created_results


def do_compress(to_process: List[Dict], stats_result: Dict, report_lines: List[str], show_progress: bool = True) -> Tuple[Dict, bool]:
    """执行压缩处理
    
    Returns:
        (stats_result, was_interrupted)
    """
    print(f"\n准备压缩 {len(to_process)} 个漫画...")
    print("按 Ctrl+C 可以中断")
    
    # 预计算是否需要手动打印进度
    need_manual_print = show_progress and tqdm is None
    
    interrupted = False
    
    try:
        # 创建进度条
        if show_progress and tqdm:
            iterator = tqdm(to_process, desc="压缩进度", unit="个")
        else:
            iterator = to_process
        
        for item in iterator:
            gid = item['gid']
            title = item['title']
            folder = item['folder']
            
            success, msg = compress_to_cbz(folder, gid, title)
            
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
            
            # 检查是否收到中断信号（在每个项目处理完后检查）
            # 这里通过检查标志位来处理，但Python的信号处理需要特殊方式
            # 由于KeyboardInterrupt是在下一次循环开始时抛出的，当前项目会处理完
    
    except KeyboardInterrupt:
        interrupted = True
        print()
        print("收到中断信号，正在完成当前任务...")
        report_lines.append("")
        report_lines.append("用户中断处理")
    
    # 最终统计
    print()
    print("=" * 60)
    print("处理完成!")
    print(f"  成功压缩: {stats_result['compressed']}")
    print(f"  失败:     {stats_result['failed']}")
    print("=" * 60)
    
    report_lines.append("")
    report_lines.append("=== 处理结果 ===")
    report_lines.append(f"成功压缩: {stats_result['compressed']}")
    report_lines.append(f"失败: {stats_result['failed']}")
    
    # 返回成功/失败数量和中断状态，便于外部更新 scan_data
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
        
        # 检查.ehviewer和.thumb是否存在
        has_eh = os.path.exists(os.path.join(folder_path, '.ehviewer'))
        has_th = os.path.exists(os.path.join(folder_path, '.thumb'))
        
        if not has_eh and not has_th:
            stats['missing_both'] += 1
        elif not has_eh:
            stats['missing_ehviewer'] += 1
        elif not has_th:
            stats['missing_thumb'] += 1
        
        # 检查数据库
        db_finished = False
        if gid in downloads:
            db_finished = downloads[gid].get('is_finished', False)
        
        # 获取图片数量
        images = get_image_files(folder_path)
        image_count = len(images)
        
        # 读取.ehviewer页数
        ehviewer_pages = read_ehviewer_pages(folder_path) if has_eh else -1
        
        # 收集扫描数据（所有文件夹都记录）
        scan_data.append({
            'gid': gid,
            'folder_name': name,
            'image_count': image_count,
            'has_ehviewer': has_eh,
            'has_thumb': has_th,
            'created_ehviewer': False,  # 初始为False，将在fix_missing后更新
            'created_thumb': False,
            'ehviewer_pages': ehviewer_pages,
            'db_finished': db_finished
        })
        
        # 如果不在数据库中，跳过后续处理
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
    
    # 报告内容
    report_lines = []
    report_lines.append(f"运行时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    report_lines.append(f"数据库: {db_path}")
    report_lines.append(f"漫画目录: {root}")
    report_lines.append("")
    
    if dry_run:
        print("⚠️  预览模式 - 不会实际压缩文件")
        report_lines.append("模式: 预览模式")
    elif fix_missing:
        print("⚠️  修复模式 - 压缩并修复缺失的.ehviewer和.thumb")
        report_lines.append("模式: 压缩并修复缺失的.ehviewer和.thumb")
    else:
        report_lines.append("模式: 压缩模式")
    print()
    
    # 读取数据
    downloads = read_database(db_path)
    folder_count = len(os.listdir(root))
    print(f"扫描到 {folder_count} 个项目")
    
    # 单次遍历收集所有信息
    to_process, stats, scan_data = scan_folders_with_info(root, downloads, force)
    
    # 导出扫描结果
    export_scan_result(scan_data, SCAN_RESULT_FILE)
    
    report_lines.append("=== 缺失文件统计 ===")
    report_lines.append(f"总文件夹数: {stats['total_folders']}")
    report_lines.append(f"缺失.ehviewer: {stats['missing_ehviewer']}")
    report_lines.append(f"缺失.thumb: {stats['missing_thumb']}")
    report_lines.append(f"两者都缺失: {stats['missing_both']}")
    report_lines.append("")
    
    print("缺失文件统计:")
    print(f"  总文件夹数: {stats['total_folders']}")
    print(f"  缺失.ehviewer: {stats['missing_ehviewer']}")
    print(f"  缺失.thumb: {stats['missing_thumb']}")
    print(f"  两者都缺失: {stats['missing_both']}")
    
    # 修复模式 - 同时修复文件和压缩
    if fix_missing:
        report_lines.append("=== 修复详情 ===")
        # 传递folder列表用于修复
        folders = [(item['gid'], item['title'], item['folder']) for item in to_process]
        created_results = fix_missing_files(folders, downloads, report_lines)
        
        # 更新scan_data中的created字段
        for item in scan_data:
            gid = item['gid']
            if gid in created_results:
                item['created_ehviewer'] = created_results[gid][0]
                item['created_thumb'] = created_results[gid][1]
        
        # 重新导出扫描结果
        export_scan_result(scan_data, SCAN_RESULT_FILE)
        
        report_lines.append("")
        report_lines.append("=== 压缩处理 ===")
        
        if not to_process:
            print("没有需要压缩的漫画")
            report_lines.append("没有需要压缩的漫画")
            save_report(report_lines)
            return
        
        stats, interrupted = do_compress(to_process, stats, report_lines, show_progress)
        
        # 压缩完成后重新扫描并导出结果（包含压缩后的状态）
        _, _, scan_data = scan_folders_with_info(root, downloads, force)
        
        # 更新 created 字段（因为 fix_missing 已执行过）
        for item in scan_data:
            gid = item['gid']
            if gid in downloads:
                item['created_ehviewer'] = has_ehviewer(os.path.join(root, item['folder_name']))
                item['created_thumb'] = has_thumb(os.path.join(root, item['folder_name']))
        
        export_scan_result(scan_data, SCAN_RESULT_FILE)
        
        # 如果是中断退出，立即返回
        if interrupted:
            print("\n已导出中断前的状态，程序退出")
            save_report(report_lines)
            return
        
        save_report(report_lines)
        return
    
    # 显示统计
    print()
    print("统计:")
    print(f"  文件夹总数:     {stats['total_folders']}")
    print(f"  数据库有记录:   {stats['in_database']}")
    print(f"  数据库无记录:   {stats['not_in_database']}")
    print(f"  未完成下载:    {stats['not_finished']}")
    print(f"  已压缩:        {stats['already_compressed']}")
    print(f"  等待压缩:      {stats['to_compress']}")
    
    report_lines.append("=== 处理统计 ===")
    report_lines.append(f"文件夹总数: {stats['total_folders']}")
    report_lines.append(f"数据库有记录: {stats['in_database']}")
    report_lines.append(f"数据库无记录: {stats['not_in_database']}")
    report_lines.append(f"未完成下载: {stats['not_finished']}")
    report_lines.append(f"已压缩: {stats['already_compressed']}")
    report_lines.append(f"等待压缩: {stats['to_compress']}")
    report_lines.append("")
    
    if not to_process:
        print("没有需要处理的漫画")
        report_lines.append("没有需要处理的漫画")
        save_report(report_lines)
        return
    
    if dry_run:
        print("\n以下漫画将进行压缩:")
        report_lines.append("=== 待压缩列表 ===")
        for item in to_process[:DRY_RUN_PREVIEW_COUNT]:
            report_lines.append(f"  [{item['gid']}] {item['title'][:50]} - {len(item['images'])} 页")
            print(f"  [{item['gid']}] {item['title'][:50]} - {len(item['images'])} 页")
        if len(to_process) > DRY_RUN_PREVIEW_COUNT:
            report_lines.append(f"  ... 还有 {len(to_process) - DRY_RUN_PREVIEW_COUNT} 个")
            print(f"  ... 还有 {len(to_process) - DRY_RUN_PREVIEW_COUNT} 个")
        save_report(report_lines)
        return
    
    # 执行压缩
    report_lines.append("=== 压缩详情 ===")
    stats, interrupted = do_compress(to_process, stats, report_lines, show_progress)
    
    # 压缩完成后重新扫描并导出结果（包含压缩后的状态）
    _, _, scan_data = scan_folders_with_info(root, downloads, force)
    export_scan_result(scan_data, SCAN_RESULT_FILE)
    
    # 如果是中断退出，立即返回
    if interrupted:
        print("\n已导出中断前的状态，程序退出")
        save_report(report_lines)
        return
    
    save_report(report_lines)


def save_report(report_lines: List[str]):
    """保存报告到文件"""
    try:
        with open(REPORT_FILE, 'w', encoding='utf-8') as f:
            f.write('\n'.join(report_lines))
        print(f"\n报告已保存到: {REPORT_FILE}")
    except Exception as e:
        print(f"\n警告: 保存报告失败: {e}")


def main():
    args = parse_args()
    
    # 验证路径
    if not os.path.isdir(args.root):
        print(f"错误: 漫画文件夹不存在: {args.root}")
        sys.exit(1)
    
    # 检查依赖
    show_progress = not args.no_progress and tqdm is not None
    
    if args.fix_missing and not HAS_PIL:
        print("警告: Pillow未安装，修复.thumb功能将不可用")
    
    process_comics(
        db_path=args.db,
        root=args.root,
        dry_run=args.dry_run,
        fix_missing=args.fix_missing,
        force=args.force,
        show_progress=show_progress
    )


if __name__ == '__main__':
    main()
