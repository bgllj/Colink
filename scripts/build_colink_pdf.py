from pathlib import Path
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, PageBreak, Table, TableStyle,
    KeepTogether, HRFlowable
)


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "output" / "pdf" / "project-proposal.pdf"
OUT.parent.mkdir(parents=True, exist_ok=True)


def register_chinese_font():
    candidates = [
        ("MicrosoftYaHei", r"C:\Windows\Fonts\msyh.ttc"),
        ("SimSun", r"C:\Windows\Fonts\simsun.ttc"),
        ("SimHei", r"C:\Windows\Fonts\simhei.ttf"),
    ]
    for name, path in candidates:
        if Path(path).exists():
            try:
                pdfmetrics.registerFont(TTFont(name, path, subfontIndex=0))
                return name
            except Exception:
                continue
    raise RuntimeError("No usable Chinese font found")


FONT = register_chinese_font()
GREEN = colors.HexColor("#1E5545")
GREEN_DARK = colors.HexColor("#123D32")
GREEN_LIGHT = colors.HexColor("#E8F1EC")
SAND = colors.HexColor("#F7F3EA")
INK = colors.HexColor("#1F2925")
MOSS = colors.HexColor("#5D7368")
LINE = colors.HexColor("#D4DED8")
ORANGE = colors.HexColor("#B8794D")


styles = getSampleStyleSheet()
styles.add(ParagraphStyle(
    name="CoverTitle", fontName=FONT, fontSize=28, leading=38,
    textColor=colors.white, alignment=TA_CENTER, spaceAfter=10,
))
styles.add(ParagraphStyle(
    name="CoverSub", fontName=FONT, fontSize=13, leading=22,
    textColor=colors.HexColor("#E4F0E9"), alignment=TA_CENTER,
))
styles.add(ParagraphStyle(
    name="Kicker", fontName=FONT, fontSize=10, leading=15,
    textColor=colors.HexColor("#CFE4D8"), alignment=TA_CENTER,
))
styles.add(ParagraphStyle(
    name="H1C", parent=styles["Heading1"], fontName=FONT, fontSize=18,
    leading=25, textColor=GREEN_DARK, spaceBefore=9, spaceAfter=8,
))
styles.add(ParagraphStyle(
    name="H2C", parent=styles["Heading2"], fontName=FONT, fontSize=12.5,
    leading=19, textColor=GREEN, spaceBefore=7, spaceAfter=5,
))
styles.add(ParagraphStyle(
    name="BodyC", parent=styles["BodyText"], fontName=FONT, fontSize=10.5,
    leading=18, textColor=INK, spaceAfter=7, alignment=TA_LEFT,
))
styles.add(ParagraphStyle(
    name="SmallC", parent=styles["BodyText"], fontName=FONT, fontSize=8.5,
    leading=13, textColor=MOSS, spaceAfter=4,
))
styles.add(ParagraphStyle(
    name="TableHead", fontName=FONT, fontSize=9.5, leading=14,
    textColor=colors.white, alignment=TA_LEFT,
))
styles.add(ParagraphStyle(
    name="TableCell", fontName=FONT, fontSize=8.8, leading=14,
    textColor=INK, alignment=TA_LEFT,
))
styles.add(ParagraphStyle(
    name="TableCellBold", fontName=FONT, fontSize=9, leading=14,
    textColor=GREEN_DARK, alignment=TA_LEFT,
))


def P(text, style="BodyC"):
    # ReportLab paragraph markup needs ampersands escaped in plain copy.
    text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return Paragraph(text, styles[style])


def bullet(text):
    return Paragraph("<font color='#1E5545'>•</font>&nbsp;" + text, styles["BodyC"])


def section(title, body=None):
    flow = [Paragraph(title, styles["H1C"]), HRFlowable(width="100%", thickness=0.7, color=LINE, spaceAfter=9)]
    if body:
        flow.append(P(body))
    return flow


def table(data, widths, header=True):
    converted = []
    for r, row in enumerate(data):
        converted.append([
            cell if hasattr(cell, "wrap") else P(str(cell), "TableHead" if header and r == 0 else "TableCell")
            for cell in row
        ])
    t = Table(converted, colWidths=widths, repeatRows=1 if header else 0, hAlign="LEFT")
    commands = [
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("GRID", (0, 0), (-1, -1), 0.45, LINE),
        ("LEFTPADDING", (0, 0), (-1, -1), 8),
        ("RIGHTPADDING", (0, 0), (-1, -1), 8),
        ("TOPPADDING", (0, 0), (-1, -1), 7),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
    ]
    if header:
        commands += [("BACKGROUND", (0, 0), (-1, 0), GREEN), ("TEXTCOLOR", (0, 0), (-1, 0), colors.white)]
        if len(data) > 1:
            for i in range(1, len(data)):
                if i % 2 == 0:
                    commands.append(("BACKGROUND", (0, i), (-1, i), colors.HexColor("#F7FAF7")))
    t.setStyle(TableStyle(commands))
    return t


def header_footer(canvas, doc):
    canvas.saveState()
    w, h = A4
    if doc.page > 1:
        canvas.setStrokeColor(LINE)
        canvas.setLineWidth(0.5)
        canvas.line(18 * mm, h - 16 * mm, w - 18 * mm, h - 16 * mm)
        canvas.setFont(FONT, 8)
        canvas.setFillColor(MOSS)
        canvas.drawString(18 * mm, h - 12 * mm, "Colink | 项目计划书")
    canvas.setStrokeColor(LINE)
    canvas.setLineWidth(0.5)
    canvas.line(18 * mm, 14 * mm, w - 18 * mm, 14 * mm)
    canvas.setFont(FONT, 8)
    canvas.setFillColor(MOSS)
    canvas.drawString(18 * mm, 9 * mm, "学习成长与协作社区")
    canvas.drawRightString(w - 18 * mm, 9 * mm, f"第 {doc.page} 页")
    canvas.restoreState()


story = []

# Cover
story.append(Spacer(1, 34 * mm))
cover = Table([
    [P("Colink", "CoverTitle")],
    [P("面向大学生的学习成长与协作社区", "CoverSub")],
    [Spacer(1, 8 * mm)],
    [P("项目计划书 · 2026", "Kicker")],
], colWidths=[150 * mm], rowHeights=[29 * mm, 18 * mm, 12 * mm, 12 * mm])
cover.setStyle(TableStyle([
    ("BACKGROUND", (0, 0), (-1, -1), GREEN_DARK),
    ("BOX", (0, 0), (-1, -1), 0, GREEN_DARK),
    ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
    ("LEFTPADDING", (0, 0), (-1, -1), 14),
    ("RIGHTPADDING", (0, 0), (-1, -1), 14),
]))
story.append(cover)
story.append(Spacer(1, 19 * mm))
story.append(P("学习 · 刷题 · 组队 · 成长", "Kicker"))
story.append(Spacer(1, 28 * mm))
story.append(P("版本：竞赛申报版  |  文档用途：项目立项与执行规划", "SmallC"))
story.append(PageBreak())

# 1
story += section("一、项目概述", "Colink是一款面向大学生的综合性学习成长平台，围绕“学习、交流、组队和自我提升”构建线上社区。平台以课程安排和个人目标为线索组织学习内容，以题目和学习记录沉淀能力，以组队和社区互动促成合作，形成“规划 - 练习 - 交流 - 协作 - 复盘”的学习成长闭环。")
story.append(table([
    ["项目要素", "规划内容"],
    ["项目定位", "连接课程、刷题、项目组队与个人成长记录的学习协作社区"],
    ["核心用户", "本科生、研究生及有备考、竞赛、科研、课程作业需求的青年用户"],
    ["核心价值", "减少工具切换，提升学习反馈质量，降低寻找合作者的成本"],
    ["建设原则", "小步验证、模块化开发、数据最小化、社区共建"],
], [35 * mm, 115 * mm]))
story.append(Spacer(1, 5 * mm))

# 2
story += section("二、用户痛点与市场机会", "学生通常需要在教务系统、题库软件、日历、社交群和网盘之间反复切换，课程安排与学习任务难以统一；刷题过程缺少连续的错题分析和阶段反馈；竞赛、课程项目和科研活动往往依赖熟人介绍，专业能力、空闲时间和合作目标难以匹配；个人学习成果分散在不同平台，长期成长轨迹无法沉淀。")
story.append(P("高校学生数量稳定，考研、职业资格考试、学科竞赛、创新创业和课程项目持续产生学习与协作需求。现有产品大多只解决其中一个场景，Colink从高频课程和刷题切入，再通过组队与内容社区提升用户留存，具备在不同高校和专业中复制的空间。"))

# 3
story += section("三、产品方案")
modules = [
    ["模块", "主要功能", "用户收益"],
    ["课程与日程", "个人课程表、单双周、课程标签、作业与考试关联、日程提醒、空闲时间展示", "统一安排学习时间，减少漏课和遗忘"],
    ["刷题与知识巩固", "分类题库、章节练习、模拟测试、限时训练、错题本、相似题推荐、正确率分析", "形成练习、纠错和复习闭环"],
    ["项目组队与协作", "发布需求、技能标签、队友匹配、队伍任务、资料共享、进度状态、阶段复盘", "更高效地找到合适队友并推进项目"],
    ["个人管理与成长档案", "学习目标、待办事项、专注时段、习惯打卡、证书作品和项目经历", "看见长期成长，建立持续行动习惯"],
    ["学习社区", "主题空间、笔记分享、解题讨论、问答、评论、收藏、关注、举报与屏蔽", "获得同伴互助，沉淀可检索的学习内容"],
]
story.append(table(modules, [31 * mm, 82 * mm, 37 * mm]))
story.append(Spacer(1, 5 * mm))
story.append(P("首期题库优先支持计算机基础、英语和公共基础课，后续按用户反馈扩展专业题库。社区内容可关联课程、知识点或项目，便于搜索和长期沉淀。"))

# 4
story += section("四、核心用户流程", "用户注册后完成专业、年级、技能和学习目标设置，导入或创建课程表；系统根据课程安排生成学习空档，用户选择题库进行练习并沉淀错题；遇到课程项目或竞赛时，用户可发布需求或浏览匹配队伍；合作过程中使用任务和资料功能推进项目；每周通过学习数据和目标完成情况进行复盘，调整下一阶段计划。社区内容与个人成长数据在整个流程中持续产生反馈。")
story.append(Spacer(1, 2 * mm))
story.append(table([
    ["阶段", "关键动作", "输出"],
    ["规划", "设置目标，导入课程，安排时间", "周计划与待办清单"],
    ["练习", "完成题目，标记错因，参与讨论", "错题本与知识薄弱点"],
    ["协作", "发布需求，匹配队友，拆分任务", "队伍、任务和阶段成果"],
    ["复盘", "查看数据，记录成果，调整计划", "成长档案与下一阶段目标"],
], [28 * mm, 78 * mm, 44 * mm]))

# 5
story += section("五、产品分期与里程碑")
roadmap = [
    ["阶段", "周期", "交付重点", "验证目标"],
    ["MVP版本", "第1 - 3个月", "账户资料、课程表、题库练习、错题本、待办、动态发布", "验证课程 + 刷题的日常使用价值"],
    ["协作版本", "第4 - 6个月", "项目发布、技能标签、队友匹配、队伍任务、评论与举报", "验证组队需求和协作流程"],
    ["成长版本", "第7 - 12个月", "学习看板、目标习惯、作品档案、题目推荐、活动信息", "提升留存，形成成长记录"],
    ["规模化阶段", "12个月后", "多校多学科、AI答疑、计划生成、知识图谱", "验证跨专业和跨学校复制能力"],
]
story.append(table(roadmap, [25 * mm, 27 * mm, 73 * mm, 25 * mm]))

# 6
story += section("六、技术与运营方案", "采用Android原生客户端与模块化架构，核心模块包括用户、课程、题库、社区、组队和成长数据。服务端提供统一API、权限控制、搜索和消息能力，题目与内容采用结构化标签管理；重要数据进行传输加密和分级存储，个人资料支持删除和导出。")
story.append(P("产品先通过小范围校园社群、课程合作和竞赛团队进行冷启动，以学习挑战、错题分享和组队活动提升内容供给；每月根据活跃率、练习完成率、内容互动率和组队成功率迭代。运营内容坚持真实、有帮助和可追溯，逐步建立学生志愿编辑和优秀内容激励机制。"))

# 7
story += section("七、商业与推广模式", "项目早期以免费基础功能获取用户，课程表、基础题库、社区交流和组队功能保持低门槛。用户规模稳定后，可探索专业题库增值包、竞赛与课程项目服务、校园组织活动工具、合规的教育机构合作和校企实践项目推荐。所有商业化内容明确标识，不干扰社区内容的独立性与公信力。")
story.append(table([
    ["推广阶段", "渠道", "动作"],
    ["校园冷启动", "班级、社团、竞赛团队", "邀请种子用户，围绕一门课程或一项竞赛建立示范社区"],
    ["内容增长", "学习挑战、题目分享、经验帖", "用可复用内容带动搜索和自然传播"],
    ["合作拓展", "学院、实验室、创新创业组织", "提供项目发布、组队和活动工具"],
], [30 * mm, 45 * mm, 75 * mm]))

# 8
story += section("八、阶段性目标与评价指标", "首期测试覆盖不少于3个专业、500名注册用户；次日留存率达到35%，月活跃用户中每人月均完成30道题；题目练习完成率达到50%，错题复习率达到40%；组队需求匹配成功率达到30%，用户对组队结果的满意度达到80%；社区有效内容每月增长20%。指标按月复盘，重点观察真实学习行为和用户留存，而非单纯追求注册量。")
story.append(table([
    ["指标类别", "核心指标", "首期目标"],
    ["用户", "注册用户 / 次日留存", "500人 / 35%"],
    ["学习", "人均月练习量 / 练习完成率", "30题 / 50%"],
    ["复习", "错题复习率", "40%"],
    ["协作", "组队匹配成功率 / 满意度", "30% / 80%"],
    ["社区", "有效内容月增长", "20%"],
], [35 * mm, 75 * mm, 40 * mm]))

# 9
story += section("九、风险与应对")
risks = [
    ["风险", "应对措施"],
    ["题库质量不足", "采用教师、优秀学生和公开授权资源的多重审核，记录版本与反馈。"],
    ["社区广告、抄袭或不当内容", "举报、机器初筛和人工复核结合，明确社区公约和处罚规则。"],
    ["组队匹配不准确", "先缩小专业和项目范围，根据用户反馈调整标签权重和匹配规则。"],
    ["个人数据安全", "最小化收集、明确授权、分级存储，支持查看、导出和删除。"],
    ["功能过多导致复杂", "分阶段发布，以课程、刷题、组队三条核心链路作为版本验收标准。"],
]
story.append(table(risks, [43 * mm, 107 * mm]))

# 10
story += section("十、项目价值", "Colink将学生的学习行为、合作关系和成长成果连接起来，提升学习资源利用率，降低寻找队友和获取经验的门槛，鼓励知识分享与同伴互助，并提供持续、可见、可复盘的成长记录。项目从小范围专业社区验证产品，再逐步扩展题库和校园场景，具备较强的可执行性、迭代性和推广潜力。")
story.append(Spacer(1, 6 * mm))
story.append(Table([[P("项目愿景", "H2C")], [P("让每一次学习都有计划、有反馈、有伙伴，也有可以被记录和看见的成长。", "BodyC")]], colWidths=[150 * mm], style=TableStyle([
    ("BACKGROUND", (0, 0), (-1, -1), GREEN_LIGHT),
    ("BOX", (0, 0), (-1, -1), 0.8, colors.HexColor("#BFD8C8")),
    ("LEFTPADDING", (0, 0), (-1, -1), 14), ("RIGHTPADDING", (0, 0), (-1, -1), 14),
    ("TOPPADDING", (0, 0), (-1, -1), 10), ("BOTTOMPADDING", (0, 0), (-1, -1), 10),
])))


doc = SimpleDocTemplate(
    str(OUT), pagesize=A4, rightMargin=25 * mm, leftMargin=25 * mm,
    topMargin=23 * mm, bottomMargin=21 * mm, title="Colink项目计划书",
    author="Colink项目组",
)
doc.build(story, onFirstPage=header_footer, onLaterPages=header_footer)
print(OUT)
