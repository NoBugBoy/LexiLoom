import os
import re
import uuid
import shutil
import tempfile
from typing import Any, Tuple, List, Dict
from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.responses import FileResponse
from PIL import Image
from deepdoc import PdfParser, DocxParser, ExcelParser, PptParser
import docx

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


def parse_docx_document(file_path: str, doc_id: str) -> str:
    """Word (.docx) 全文提取：按 Style 还原标题 + 原生 HTML 表格"""
    sections, tbls = docx_parser(file_path)
    sections = sections or []

    # 1. 还原正文段落与标题
    md_paragraphs = []
    for sec in sections:
        if isinstance(sec, (tuple, list)) and len(sec) >= 2:
            text = str(sec[0]).strip()
            style = str(sec[1]).strip().lower()
        else:
            text = str(sec).strip()
            style = ""

        if not text:
            continue

        if "heading 1" in style or "标题 1" in style or "title" in style:
            md_paragraphs.append(f"# {text}")
        elif "heading 2" in style or "标题 2" in style:
            md_paragraphs.append(f"## {text}")
        elif "heading 3" in style or "标题 3" in style:
            md_paragraphs.append(f"### {text}")
        elif "heading 4" in style or "标题 4" in style:
            md_paragraphs.append(f"#### {text}")
        else:
            md_paragraphs.append(text)

    clean_markdown = clean_and_stitch_text("\n\n".join(md_paragraphs))

    # 2. 直接从 Word 实体提取高保真 HTML 表格
    table_htmls = []
    try:
        doc = docx.Document(file_path)
        for idx, table in enumerate(doc.tables):
            rows = []
            for row in table.rows:
                rows.append([cell.text.strip() for cell in row.cells])
            if rows:
                table_htmls.append(matrix_to_html_table(rows, f"表格 {idx + 1}"))
    except Exception:
        # 降级从 docx_parser 返回的 tbls 提取
        for tbl in (tbls or []):
            lines = [l.strip() for l in str(tbl).splitlines() if l.strip()]
            rows = [[c.strip() for c in re.split(r"[;；]", line) if c.strip()] for line in lines]
            if rows:
                table_htmls.append(matrix_to_html_table(rows))

    components = [clean_markdown]
    if table_htmls:
        components.append("\n\n".join(table_htmls))

    return "\n\n".join(c for c in components if c.strip()).strip()


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


@app.post("/api/v1/parse")
async def parse_document(file: UploadFile = File(...)):
    filename = file.filename.lower()
    suffix = os.path.splitext(filename)[-1]
    doc_id = uuid.uuid4().hex[:12]

    if suffix not in [".pdf", ".docx", ".pptx", ".xlsx", ".xls", ".txt", ".md"]:
        raise HTTPException(
            status_code=400,
            detail=f"不支持的文件格式: {suffix}。仅支持 pdf, docx, pptx, xlsx, xls, txt, md"
        )

    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        shutil.copyfileobj(file.file, tmp)
        tmp_path = tmp.name

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
            "blocks": []
        }

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)
