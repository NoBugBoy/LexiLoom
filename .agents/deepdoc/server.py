import os
import re
import uuid
import shutil
import tempfile
import time
import logging
import threading
import requests
import io
import numpy as np
from typing import Any, Tuple, List, Dict, Optional
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.responses import FileResponse
from PIL import Image
from deepdoc import PdfParser, DocxParser, ExcelParser, PptParser
import docx
from docx.oxml.text.paragraph import CT_P
from docx.oxml.table import CT_Tbl
from docx.text.paragraph import Paragraph
from docx.table import Table

logger = logging.getLogger("deepdoc")
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

app = FastAPI(title="DeepDoc Multi-Format Standalone Full-Text Extractor Server")

# 静态图片持久化存储目录
STATIC_DIR = "/app/static/figures"
os.makedirs(STATIC_DIR, exist_ok=True)

# 初始化解析器
pdf_parser = PdfParser()
docx_parser = DocxParser()
excel_parser = ExcelParser()
ppt_parser = PptParser()

# DeepDoc 坐标标签正则: @@页码\tx0\tx1\ty0\ty1##
BBOX_PATTERN = re.compile(r"@@[\t 0-9.-]+?##")

# 标题或列表项识别正则（用于保护换行与结构，支持多级数字编号如 3.1、3.2.1）
HEADING_OR_LIST_PATTERN = re.compile(
    r"^(?:#{1,6}\s+|(?:\d+(?:\.\d+)*|\([0-9一二三四五六七八九十]+\)|[一二三四五六七八九十]+)[、\.\s]|-{1,3}\s+|[*•]\s*|第[一二三四五六七八九十0-9]+[章节篇部分条]|方案\s*[0-9一二三四]|操作步骤|优点[：:]|缺点[：:]|步骤\s*[0-9一二三四])"
)

# CJK 中文字符与标点正则范围
CJK_CHAR_PATTERN = re.compile(r"[\u4e00-\u9fa5\u3000-\u303f\uff01-\uff0f\uff1a-\uff20\uff3b-\uff40\uff5b-\uff65]")


def clean_and_stitch_text(text: str) -> str:
    """清洗 OCR 坐标与排版标记，智能缝合中文字符折行并消除单字空格 Bug。"""
    if not text:
        return ""

    # 1. 彻底清除坐标标记 @@...##
    text = BBOX_PATTERN.sub("", text)

    # 2. 统一回车换行符
    text = text.replace("\r\n", "\n").replace("\r", "\n")

    # 3. 按双换行及以上切分为自然物理段落
    raw_paragraphs = re.split(r"\n{2,}", text)
    cleaned_paras = []

    for raw_p in raw_paragraphs:
        p = raw_p.strip()
        if not p:
            continue

        lines = [line.strip() for line in p.split("\n") if line.strip()]
        if not lines:
            continue

        merged_lines = [lines[0]]
        for nxt in lines[1:]:
            # 保护标题行、有序/无序列表项等结构换行
            if HEADING_OR_LIST_PATTERN.match(nxt):
                merged_lines.append(nxt)
                continue

            prev = merged_lines[-1]
            # 保护短标题行：若上一行本身就是章节短标题，绝不能把下一行正文直接贴在标题末尾
            if HEADING_OR_LIST_PATTERN.match(prev) and len(prev) < 60 and not prev.endswith(("。", "；", "!", "？", "：", ":")):
                merged_lines.append(nxt)
                continue

            last_ch = prev[-1] if prev else ""
            first_ch = nxt[0] if nxt else ""

            # 判断折行处两端是否均为 CJK 中文字符或标点
            is_cjk_boundary = bool(
                CJK_CHAR_PATTERN.search(last_ch) and CJK_CHAR_PATTERN.search(first_ch)
            )

            if is_cjk_boundary:
                merged_lines[-1] = prev + nxt
            else:
                merged_lines[-1] = prev + " " + nxt

        cleaned_block = "\n".join(merged_lines)

        # 4. 彻底消除中文与中文之间由于 OCR 字符切分误插入的连续空格 (如 '工 具' -> '工具')
        while re.search(r"([\u4e00-\u9fa5])\s+([\u4e00-\u9fa5])", cleaned_block):
            cleaned_block = re.sub(r"([\u4e00-\u9fa5])\s+([\u4e00-\u9fa5])", r"\1\2", cleaned_block)

        # 5. 消除中文与标点符号之间的多余空格 (如 '集成 ，' -> '集成，')
        cleaned_block = re.sub(r"([\u4e00-\u9fa5])\s+([，。！？；：、“”‘’（）《》【】\"'\)])", r"\1\2", cleaned_block)
        cleaned_block = re.sub(r"([，。！？；：、“”‘’（）《》【】\"'\(])\s+([\u4e00-\u9fa5])", r"\1\2", cleaned_block)

        cleaned_paras.append(cleaned_block)

    return "\n\n".join(cleaned_paras)


def matrix_to_html_table(rows: List[List[str]], caption: str = "") -> str:
    """将二维单元格矩阵渲染为标准语义化 HTML <table>"""
    if not rows or not any(rows):
        return ""
    html = ["<table>"]
    if caption:
        html.append(f"<caption>{caption.strip()}</caption>")
    # 第一行渲染为表头 th
    header_cols = rows[0]
    html.append("<tr>" + "".join(f"<th>{str(c).strip()}</th>" for c in header_cols) + "</tr>")
    # 数据行渲染为 td
    for row in rows[1:]:
        if not any(str(c).strip() for c in row):
            continue
        # 列数对齐补齐
        padded = list(row) + [""] * max(0, len(header_cols) - len(row))
        html.append("<tr>" + "".join(f"<td>{str(c).strip()}</td>" for c in padded[:len(header_cols)]) + "</tr>")
    html.append("</table>")
    return "\n".join(html)


def process_figures_and_tables(items: List[Any], doc_id: str) -> Tuple[List[str], List[str]]:
    """分离提取 HTML 表格与图文并茂的 <figure> 插图卡片"""
    table_htmls = []
    figure_htmls = []
    fig_counter = 0

    for item in items:
        if not item:
            continue

        cropped_img = None
        content = item

        if isinstance(item, (tuple, list)) and len(item) >= 2:
            cropped_img = item[0]
            content = item[1]

        # 1. 识别 HTML 表格 (DeepDoc 原生 return_html=True 返回 <table>)
        content_str = str(content).strip() if content is not None else ""
        if isinstance(content, str) and ("<table" in content.lower() or "<th" in content.lower()):
            table_htmls.append(content.strip())
            continue

        # 2. 识别并提取插图图片与 OCR 文字
        if isinstance(cropped_img, Image.Image):
            # 过滤微小图标与噪点 (长宽均小于 60 像素的图标丢弃)
            if cropped_img.width < 60 and cropped_img.height < 60:
                continue

            # 提取图注/OCR 文字
            ocr_text = ""
            if isinstance(content, (list, tuple)):
                ocr_text = "\n".join(str(x).strip() for x in content if str(x).strip())
            elif isinstance(content, str):
                ocr_text = content.strip()

            ocr_text = clean_and_stitch_text(ocr_text)

            # 过滤缺乏有效信息的极小碎片 (有效字少于 3 个且图片面积较小)
            if len(ocr_text.strip()) < 3 and (cropped_img.width * cropped_img.height < 150 * 150):
                continue

            fig_counter += 1
            img_filename = f"{doc_id}_fig_{fig_counter}.png"
            img_path = os.path.join(STATIC_DIR, img_filename)
            try:
                cropped_img.save(img_path, format="PNG")
                img_url = f"/static/figures/{img_filename}"
            except Exception:
                img_url = ""

            # 组装语义化 HTML5 <figure> 图文并茂卡片
            fig_lines = ['<figure data-type="image_ocr">']
            if img_url:
                fig_lines.append(f'  <img src="{img_url}" alt="插图 {fig_counter}" />')
            fig_lines.append(f'  <figcaption>【插图 {fig_counter}】</figcaption>')
            if ocr_text:
                fig_lines.append(f'  <blockquote class="image-ocr-content">{ocr_text}</blockquote>')
            fig_lines.append('</figure>')

            figure_htmls.append("\n".join(fig_lines))
        elif content_str and not content_str.startswith("<table"):
            # 普通文本描述型表格降维兼容
            lines = [l.strip() for l in content_str.splitlines() if l.strip()]
            rows = [[c.strip() for c in re.split(r"[;；]", line) if c.strip()] for line in lines]
            if rows and max(len(r) for r in rows) >= 2:
                table_htmls.append(matrix_to_html_table(rows))

    return table_htmls, figure_htmls


def parse_pdf_document(file_path: str, doc_id: str) -> str:
    """PDF 全文深度提取：正文缝合 + 原生 HTML 表格 + 图文并茂 <figure> 插图"""
    raw_results = pdf_parser(file_path, need_image=True, return_html=True)
    sections, tbls = [], []
    if isinstance(raw_results, (tuple, list)) and len(raw_results) >= 2:
        sections = raw_results[0] or []
        tbls = raw_results[1] or []
    elif isinstance(raw_results, str):
        sections = [raw_results]

    if isinstance(sections, str):
        raw_text = sections
    elif isinstance(sections, (list, tuple)):
        text_parts = []
        for sec in sections:
            if isinstance(sec, str):
                text_parts.append(sec)
            elif isinstance(sec, (list, tuple)) and len(sec) >= 2 and isinstance(sec[1], (list, tuple)):
                ocr_lines = [str(line).strip() for line in sec[1] if str(line).strip()]
                text_parts.append("\n".join(ocr_lines))
            elif sec is not None:
                text_parts.append(str(sec))
        raw_text = "\n\n".join(text_parts)
    else:
        raw_text = str(sections)

    # 1. 清洗自然正文流
    clean_markdown = clean_and_stitch_text(raw_text)

    # 2. 提取表格 (HTML Table) 与插图 (<figure>)
    table_htmls, figure_htmls = process_figures_and_tables(tbls, doc_id)

    # 3. 结构化拼装为完整长文本
    components = [clean_markdown]
    if figure_htmls:
        components.append("\n\n".join(figure_htmls))
    if table_htmls:
        components.append("\n\n".join(table_htmls))

    return "\n\n".join(c for c in components if c.strip()).strip()


def extract_ocr_from_image(img: Image.Image) -> str:
    """对 PIL 图片调用 OCR 识别图中文本"""
    try:
        rgb_img = img.convert("RGB")
        arr = np.array(rgb_img)
        ocr_res = pdf_parser.ocr(arr)
        if not ocr_res:
            return ""
        lines = []
        for item in ocr_res:
            if isinstance(item, (list, tuple)) and len(item) >= 2:
                rec_res = item[1]
                if isinstance(rec_res, (list, tuple)) and len(rec_res) >= 2:
                    text, score = rec_res[0], rec_res[1]
                    if score >= 0.4 and str(text).strip():
                        lines.append(str(text).strip())
        if lines:
            return clean_and_stitch_text("\n".join(lines))
    except Exception as e:
        logger.warning(f"【DeepDoc OCR】图片识别异常: {e}")
    return ""


def process_docx_image(img_blob: bytes, doc_id: str, fig_counter: int) -> Optional[str]:
    """处理并保存 Word 嵌入图片，执行 OCR 并生成标准 <figure> 卡片"""
    try:
        img = Image.open(io.BytesIO(img_blob))
        # 过滤微小图标与噪点 (长宽均小于 60 像素的图标丢弃)
        if img.width < 60 and img.height < 60:
            return None

        # 转换为 RGB 格式保存
        save_img = img
        if img.mode not in ("RGB", "RGBA"):
            save_img = img.convert("RGB")

        img_filename = f"{doc_id}_fig_{fig_counter}.png"
        img_path = os.path.join(STATIC_DIR, img_filename)
        save_img.save(img_path, format="PNG")
        img_url = f"/static/figures/{img_filename}"

        # 调用 OCR 识别图片内容
        ocr_text = extract_ocr_from_image(img)

        # 组装语义化 HTML5 <figure> 图文并茂卡片 (与 PDF 保持 100% 格式对齐)
        fig_lines = ['<figure data-type="image_ocr">']
        if img_url:
            fig_lines.append(f'  <img src="{img_url}" alt="插图 {fig_counter}" />')
        fig_lines.append(f'  <figcaption>【插图 {fig_counter}】</figcaption>')
        if ocr_text:
            fig_lines.append(f'  <blockquote class="image-ocr-content">{ocr_text}</blockquote>')
        fig_lines.append('</figure>')

        return "\n".join(fig_lines)
    except Exception as e:
        logger.warning(f"【DeepDoc Word插图处理】插图处理失败: {e}")
        return None


def parse_docx_document(file_path: str, doc_id: str) -> str:
    """Word (.docx) 全文深度提取：文档流保序还原正文段落 + 原生 HTML 表格 + 图文并茂 <figure> 插图与 OCR"""
    try:
        doc = docx.Document(file_path)
    except Exception as e:
        logger.warning(f"【DeepDoc Word】读取 docx 异常，降级使用基础解析器: {e}")
        sections, tbls = docx_parser(file_path)
        clean_text = clean_and_stitch_text(
            "\n\n".join(str(s[0] if isinstance(s, (tuple, list)) else s) for s in (sections or []))
        )
        return clean_text

    processed_rids = set()
    fig_counter = 0
    tbl_counter = 0
    document_elements = []

    # 1. 遍历文档主体 block items，按自然阅读顺序保序提取段落、表格与插图
    for child in doc.element.body.iterchildren():
        if isinstance(child, CT_P):
            p = Paragraph(child, doc)
            text = p.text.strip()
            style_name = (p.style.name if p.style and hasattr(p.style, "name") else "").lower()

            md_text = ""
            if text:
                if "heading 1" in style_name or "标题 1" in style_name or style_name == "title":
                    md_text = f"# {text}"
                elif "heading 2" in style_name or "标题 2" in style_name:
                    md_text = f"## {text}"
                elif "heading 3" in style_name or "标题 3" in style_name:
                    md_text = f"### {text}"
                elif "heading 4" in style_name or "标题 4" in style_name:
                    md_text = f"#### {text}"
                else:
                    md_text = text

            if md_text:
                document_elements.append(md_text)

            # 提取段落内嵌入插图
            blips = p._element.xpath('.//a:blip/@r:embed')
            for r_id in blips:
                if r_id in doc.part.related_parts:
                    processed_rids.add(r_id)
                    part = doc.part.related_parts[r_id]
                    if "image" in getattr(part, "content_type", ""):
                        fig_counter += 1
                        fig_html = process_docx_image(part.blob, doc_id, fig_counter)
                        if fig_html:
                            document_elements.append(fig_html)

        elif isinstance(child, CT_Tbl):
            table = Table(child, doc)
            tbl_counter += 1
            rows = []
            for row in table.rows:
                row_cells = []
                for cell in row.cells:
                    row_cells.append(cell.text.strip())
                    # 检查表格单元格中的嵌入图片
                    for r_id in cell._element.xpath('.//a:blip/@r:embed'):
                        if r_id in doc.part.related_parts and r_id not in processed_rids:
                            processed_rids.add(r_id)
                            part = doc.part.related_parts[r_id]
                            if "image" in getattr(part, "content_type", ""):
                                fig_counter += 1
                                fig_html = process_docx_image(part.blob, doc_id, fig_counter)
                                if fig_html:
                                    document_elements.append(fig_html)
                rows.append(row_cells)

            if rows:
                # 提取 Word 表格自身附带的原生标题属性（若无则不伪造自增假编号，避免污染语义）
                tbl_caption = ""
                xml_captions = child.xpath('.//w:tblPr/w:tblCaption/@w:val')
                if xml_captions and str(xml_captions[0]).strip():
                    tbl_caption = str(xml_captions[0]).strip()
                table_html = matrix_to_html_table(rows, tbl_caption)
                document_elements.append(table_html)

    # 2. 兜底扫描：若存在非段落直接内联的浮动插图（未被段落关联的媒体部件），补充到文档末尾
    for r_id, part in doc.part.related_parts.items():
        if r_id not in processed_rids and "image" in getattr(part, "content_type", ""):
            processed_rids.add(r_id)
            fig_counter += 1
            fig_html = process_docx_image(part.blob, doc_id, fig_counter)
            if fig_html:
                document_elements.append(fig_html)

    # 3. 对纯正文段落进行 OCR 字符与连续中文空格清洗缝合，同时保护 <table> 与 <figure> 原子卡片
    final_blocks = []
    text_buffer = []

    for elem in document_elements:
        elem_strip = elem.strip()
        if not elem_strip:
            continue
        if elem_strip.startswith("<table") or elem_strip.startswith("<figure"):
            if text_buffer:
                cleaned = clean_and_stitch_text("\n\n".join(text_buffer))
                if cleaned:
                    final_blocks.append(cleaned)
                text_buffer = []
            final_blocks.append(elem_strip)
        else:
            text_buffer.append(elem_strip)

    if text_buffer:
        cleaned = clean_and_stitch_text("\n\n".join(text_buffer))
        if cleaned:
            final_blocks.append(cleaned)

    result_md = "\n\n".join(final_blocks).strip()
    logger.info(
        f"【DeepDoc Word解析完成】docId={doc_id}, 元素块数={len(final_blocks)}, "
        f"插图数={fig_counter}, 表格数={tbl_counter}, 总字符={len(result_md)}"
    )
    return result_md


def parse_pptx_document(file_path: str) -> str:
    """PPT (.pptx) 全文提取：按幻灯片分段"""
    slides = ppt_parser(file_path) or []
    slide_mds = []

    for idx, slide in enumerate(slides):
        slide_text = str(slide).strip()
        if not slide_text:
            continue
        cleaned = clean_and_stitch_text(slide_text)
        slide_mds.append(f"## 幻灯片 {idx + 1}\n\n{cleaned}")

    return "\n\n".join(slide_mds).strip()


def parse_excel_document(file_path: str) -> str:
    """Excel (.xlsx, .xls) 全文提取：将各工作表格式化为原生 HTML 表格"""
    with open(file_path, "rb") as f:
        tables = excel_parser(f.read()) or []

    sheet_htmls = []
    for idx, tbl in enumerate(tables):
        tbl_str = str(tbl).strip()
        if not tbl_str:
            continue
        lines = [l.strip() for l in tbl_str.splitlines() if l.strip()]
        rows = [[c.strip() for c in re.split(r"[;；]", line) if c.strip()] for line in lines]
        if rows:
            sheet_htmls.append(matrix_to_html_table(rows, f"工作表 {idx + 1}"))

    return "\n\n".join(sheet_htmls).strip()


def parse_txt_document(file_path: str) -> str:
    """纯文本 (.txt, .md) 全文提取与规整"""
    with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
        text = f.read()
    return clean_and_stitch_text(text).strip()


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/static/figures/{filename}")
def get_figure_image(filename: str):
    """静态图片下载与访问端点"""
    safe_filename = os.path.basename(filename)
    file_path = os.path.join(STATIC_DIR, safe_filename)
    if not os.path.exists(file_path):
        raise HTTPException(status_code=404, detail="Figure not found")
    return FileResponse(file_path, media_type="image/png")


def _send_callback_with_retry(callback_url: str, payload: dict):
    logger.info(f"【DeepDoc 回调】开始通知 Java: url={callback_url}, status={payload.get('status')}, docId={payload.get('documentId')}, taskId={payload.get('taskId')}")
    max_retries = 3
    for attempt in range(1, max_retries + 1):
        try:
            resp = requests.post(callback_url, json=payload, timeout=30)
            logger.info(f"【DeepDoc 回调】调用完成: status_code={resp.status_code}, response={resp.text[:200]}")
            if resp.status_code < 400:
                return
            logger.warning(f"【DeepDoc 回调】第 {attempt} 次调用返回 HTTP {resp.status_code}")
        except Exception as e:
            logger.warning(f"【DeepDoc 回调】第 {attempt} 次调用异常: error={str(e)}")
        if attempt < max_retries:
            time.sleep(2)
    logger.error(f"【DeepDoc 回调】多次重试均失败: url={callback_url}")


def _execute_async_parse_and_callback(
    tmp_path: str,
    suffix: str,
    filename: str,
    doc_id: str,
    task_id: str,
    callback_url: str,
    document_id: Optional[str],
    source_file_id: Optional[str],
    workspace_id: Optional[str],
    operator_id: Optional[str],
):
    try:
        logger.info(f"【DeepDoc 异步解析】后台任务启动: taskId={task_id}, file={filename}, docId={doc_id}")
        t0 = time.time()
        if suffix == ".pdf":
            full_content = parse_pdf_document(tmp_path, doc_id)
        elif suffix == ".docx":
            full_content = parse_docx_document(tmp_path, doc_id)
        elif suffix == ".pptx":
            full_content = parse_pptx_document(tmp_path)
        elif suffix in [".xlsx", ".xls"]:
            full_content = parse_excel_document(tmp_path)
        else:
            full_content = parse_txt_document(tmp_path)

        cost_ms = int((time.time() - t0) * 1000)
        logger.info(f"【DeepDoc 异步解析】解析成功: taskId={task_id}, chars={len(full_content)}, 耗时={cost_ms}ms")

        def _to_int(val):
            if val is not None and str(val).strip().isdigit():
                return int(val)
            return None

        success_payload = {
            "code": 200,
            "status": "SUCCESS",
            "taskId": task_id,
            "documentId": _to_int(document_id),
            "sourceFileId": _to_int(source_file_id),
            "workspaceId": _to_int(workspace_id),
            "operatorId": _to_int(operator_id),
            "filename": filename,
            "fileType": suffix.lstrip("."),
            "markdown": full_content,
            "content": full_content,
            "totalChars": len(full_content),
            "chunks": [],
            "blocks": [],
        }
        _send_callback_with_retry(callback_url, success_payload)

    except Exception as e:
        logger.error(f"【DeepDoc 异步解析】任务异常: taskId={task_id}, error={str(e)}", exc_info=True)
        def _to_int(val):
            if val is not None and str(val).strip().isdigit():
                return int(val)
            return None

        fail_payload = {
            "code": 500,
            "status": "FAILED",
            "taskId": task_id,
            "documentId": _to_int(document_id),
            "sourceFileId": _to_int(source_file_id),
            "workspaceId": _to_int(workspace_id),
            "operatorId": _to_int(operator_id),
            "filename": filename,
            "fileType": suffix.lstrip("."),
            "error": str(e),
            "message": f"DeepDoc 解析失败: {str(e)}",
        }
        _send_callback_with_retry(callback_url, fail_payload)

    finally:
        if os.path.exists(tmp_path):
            try:
                os.remove(tmp_path)
            except Exception:
                pass


@app.post("/api/v1/parse")
async def parse_document(
    file: UploadFile = File(...),
    callback_url: Optional[str] = Form(None),
    document_id: Optional[str] = Form(None),
    source_file_id: Optional[str] = Form(None),
    workspace_id: Optional[str] = Form(None),
    operator_id: Optional[str] = Form(None),
    task_id: Optional[str] = Form(None),
):
    filename = file.filename.lower()
    suffix = os.path.splitext(filename)[-1]
    doc_id = uuid.uuid4().hex[:12]
    effective_task_id = task_id or f"task_{uuid.uuid4().hex}"

    if suffix not in [".pdf", ".docx", ".pptx", ".xlsx", ".xls", ".txt", ".md"]:
        raise HTTPException(
            status_code=400,
            detail=f"不支持的文件格式: {suffix}。仅支持 pdf, docx, pptx, xlsx, xls, txt, md"
        )

    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        shutil.copyfileobj(file.file, tmp)
        tmp_path = tmp.name

    # 若提供了 callback_url，进入异步非阻塞后台执行模式并立即返回
    if callback_url and callback_url.strip():
        logger.info(f"【DeepDoc 接收异步任务】taskId={effective_task_id}, file={file.filename}, callbackUrl={callback_url}")
        t = threading.Thread(
            target=_execute_async_parse_and_callback,
            args=(
                tmp_path,
                suffix,
                file.filename,
                doc_id,
                effective_task_id,
                callback_url.strip(),
                document_id,
                source_file_id,
                workspace_id,
                operator_id,
            ),
            daemon=True,
        )
        t.start()

        def _to_int(val):
            if val is not None and str(val).strip().isdigit():
                return int(val)
            return None

        return {
            "code": 200,
            "status": "PROCESSING",
            "task_id": effective_task_id,
            "document_id": _to_int(document_id),
            "source_file_id": _to_int(source_file_id),
            "workspace_id": _to_int(workspace_id),
            "operator_id": _to_int(operator_id),
            "message": "任务已接收，正在后台异步解析中",
        }

    # 同步模式（向后兼容）：阻塞解析并直接返回结果
    try:
        if suffix == ".pdf":
            full_content = parse_pdf_document(tmp_path, doc_id)
        elif suffix == ".docx":
            full_content = parse_docx_document(tmp_path, doc_id)
        elif suffix == ".pptx":
            full_content = parse_pptx_document(tmp_path)
        elif suffix in [".xlsx", ".xls"]:
            full_content = parse_excel_document(tmp_path)
        else:
            full_content = parse_txt_document(tmp_path)

        return {
            "code": 200,
            "filename": file.filename,
            "file_type": suffix.lstrip("."),
            "markdown": full_content,
            "content": full_content,
            "total_chars": len(full_content),
            "chunks": [],
            "blocks": [],
        }

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)
