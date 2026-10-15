"""README 展示素材渲染器（PIL 绘制，非真机截图）。

产出 docs/screenshots/ 下 5 张 PNG + 1 张 GIF：
  app-sessions / app-approval-notification / app-conversation / app-working
  + app-lockscreen-approval.png / lockscreen-approval.gif（锁屏审批三态）

绘图约束（改图前必读）：
  1. 所有 fill 必须是不透明 3 元组。ImageDraw 在 RGBA 上**不做 alpha 混合**，
     传半透明色会让同色系文字与底色叠加、文字不可读——用 chip_bg() 先把
     强调色混到底色上。
  2. 不要用 emoji。msyh 字体缺 emoji 字形（⏳🔍🎤💻➕➤✓ 实测全渲染成 □），
     图标一律用几何图元（draw_magnifier/draw_mic/draw_monitor/draw_send）。
"""
from PIL import Image, ImageDraw, ImageFont
import os

W, H = 1080, 2400

# 官方 ZCode 色彩体系 (zai-dark)
BG_COLOR = (22, 22, 22)             # #161616
SURFACE_COLOR = (32, 32, 36)        # #202024
SURFACE_VARIANT = (40, 40, 48)      # #282830
CARD_BG = (34, 34, 40)              # #222228
BORDER_COLOR = (255, 255, 255, 36)  # 14% 微边框
PRIMARY = (124, 158, 255)           # #7C9EFF
PRIMARY_CONTAINER = (35, 48, 84)    # #233054
ON_PRIMARY = (15, 23, 42)
TEXT_WHITE = (226, 232, 240)        # onSurface
TEXT_MUTED = (148, 163, 184)        # onSurfaceVariant
TEXT_SUBTLE = (100, 116, 139)
STATUS_ONLINE = (70, 191, 114)      # #46BF72
STATUS_PENDING = (255, 138, 48)     # #FF8A30
STATUS_ERROR = (255, 92, 92)        # #FF5C5C
TRAJECTORY_PURPLE = (167, 139, 250) # #A78BFA
TRAJECTORY_AMBER = (245, 158, 11)   # #F59E0B

def get_font(size, bold=False):
    font_paths = [
        "C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc",
        "C:/Windows/Fonts/simhei.ttf",
        "C:/Windows/Fonts/segoeui.ttf",
    ]
    for p in font_paths:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                pass
    return ImageFont.load_default()

font_headline = get_font(52, bold=True)
font_title = get_font(38, bold=True)
font_title_small = get_font(32, bold=True)
font_body = get_font(28, bold=False)
font_body_bold = get_font(28, bold=True)
font_caption = get_font(24, bold=False)
font_caption_bold = get_font(24, bold=True)
font_mono = get_font(25, bold=False)
font_status = get_font(28, bold=True)

def draw_status_bar(draw):
    draw.text((64, 40), "09:41", fill=TEXT_WHITE, font=font_status)
    draw.rectangle([W - 170, 48, W - 128, 70], fill=None, outline=TEXT_WHITE, width=3)
    draw.rectangle([W - 166, 52, W - 138, 66], fill=TEXT_WHITE)
    draw.text((W - 224, 42), "5G", fill=TEXT_WHITE, font=font_caption_bold)
    draw.text((W - 296, 42), "WiFi", fill=TEXT_WHITE, font=font_caption)

def draw_nav_bar(draw, active_tab=0, badge_count=1):
    bar_y = H - 180
    draw.rectangle([0, bar_y, W, H], fill=SURFACE_COLOR)
    draw.line([0, bar_y, W, bar_y], fill=(255, 255, 255, 24), width=2)
    
    tab_w = W // 3
    tabs = [("会话", 0), ("待办", 1), ("设置", 2)]
    for name, idx in tabs:
        cx = idx * tab_w + tab_w // 2
        is_active = (idx == active_tab)
        color = PRIMARY if is_active else TEXT_MUTED
        iy = bar_y + 40
        if idx == 0:
            draw.rectangle([cx - 20, iy - 16, cx + 20, iy - 10], fill=color)
            draw.rectangle([cx - 20, iy - 4, cx + 20, iy + 2], fill=color)
            draw.rectangle([cx - 20, iy + 8, cx + 20, iy + 14], fill=color)
        elif idx == 1:
            draw.arc([cx - 18, iy - 20, cx + 18, iy + 16], 0, 360, fill=color, width=4)
            if badge_count > 0:
                bx, by = cx + 30, iy - 30
                draw.ellipse([bx - 17, by - 15, bx + 17, by + 15], fill=BG_COLOR)
                draw.ellipse([bx - 15, by - 13, bx + 15, by + 13], fill=STATUS_PENDING)
                bb = draw.textbbox((0, 0), str(badge_count), font=font_caption_bold)
                draw.text((bx - (bb[2] - bb[0]) // 2 - bb[0], by - (bb[3] - bb[1]) // 2 - bb[1]),
                          str(badge_count), fill=(255, 255, 255), font=font_caption_bold)
        else:
            draw.arc([cx - 18, iy - 18, cx + 18, iy + 18], 0, 360, fill=color, width=5)
            draw.ellipse([cx - 6, iy - 6, cx + 6, iy + 6], fill=color)
            
        draw.text((cx - 24, bar_y + 92), name, fill=color, font=font_caption_bold if is_active else font_caption)
        
    draw.rounded_rectangle([W // 2 - 120, H - 28, W // 2 + 120, H - 20], radius=4, fill=(160, 160, 160, 120))

def draw_card(draw, box, fill=CARD_BG, outline=BORDER_COLOR, radius=24):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=2)

def chip_bg(accent, alpha=0.22, base=CARD_BG):
    """把强调色按 alpha 混到卡片底色上，得到不透明背景色。

    ImageDraw 在 RGBA 上不做 alpha 混合，直接写半透明 fill 会让标签底色与
    同色系文字叠在一起、文字彻底看不见（历史截图的真实缺陷）。
    """
    return tuple(int(base[i] * (1 - alpha) + accent[i] * alpha) for i in range(3))

def draw_chip(draw, box, accent, text, font=None, radius=8, pad_x=18):
    f = font or font_caption_bold
    draw.rounded_rectangle(box, radius=radius, fill=chip_bg(accent), outline=accent, width=2)
    x0, y0, x1, y1 = box
    bb = draw.textbbox((0, 0), text, font=f)
    y = y0 + (y1 - y0 - (bb[3] - bb[1])) // 2 - bb[1]
    draw.text((x0 + pad_x, y), text, fill=accent, font=f)

def text_center(draw, cx, y, text, font, fill):
    bb = draw.textbbox((0, 0), text, font=font)
    draw.text((cx - (bb[2] - bb[0]) // 2 - bb[0], y), text, fill=fill, font=font)

# 图标一律用几何图元绘制：msyh 字体不含 emoji 字形（⏳🔍🎤💻➕➤✓ 实测全部渲染成缺字方块）

def draw_magnifier(draw, cx, cy, color, r=10):
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], outline=color, width=3)
    draw.line([cx + int(r * 0.7), cy + int(r * 0.7), cx + r + 7, cy + r + 7], fill=color, width=3)

def draw_mic(draw, cx, cy, color, w=13, h=24):
    draw.rounded_rectangle([cx - w // 2, cy - h // 2, cx + w // 2, cy + h // 2], radius=w // 2, fill=color)
    draw.arc([cx - w, cy - h // 2, cx + w, cy + h // 2 + 4], 0, 180, fill=color, width=3)
    draw.line([cx, cy + h // 2 + 8, cx, cy + h // 2 + 15], fill=color, width=3)

def draw_monitor(draw, x, y, color, w=34, h=24):
    draw.rounded_rectangle([x, y, x + w, y + h], radius=4, outline=color, width=3)
    draw.line([x + w // 2, y + h, x + w // 2, y + h + 7], fill=color, width=3)
    draw.line([x + w // 2 - 11, y + h + 7, x + w // 2 + 11, y + h + 7], fill=color, width=3)

def draw_send(draw, cx, cy, color, s=15):
    draw.polygon([(cx - s, cy - s), (cx + s, cy), (cx - s, cy + s)], fill=color)

def render_sessions():
    im = Image.new("RGBA", (W, H), BG_COLOR)
    draw = ImageDraw.Draw(im)
    draw_status_bar(draw)
    
    draw.text((48, 120), "ZCode 会话", fill=TEXT_WHITE, font=font_headline)
    draw.ellipse([50, 196, 62, 208], fill=STATUS_ONLINE)
    draw.text((72, 190), "ThinkPad-P16 · 就绪", fill=TEXT_MUTED, font=font_caption)
    
    draw.rounded_rectangle([W - 260, 126, W - 48, 186], radius=16, fill=PRIMARY)
    draw.text((W - 236, 138), "+ 新建会话", fill=ON_PRIMARY, font=font_body_bold)
    
    draw_card(draw, [48, 240, W - 48, 324], fill=SURFACE_COLOR, radius=18)
    draw_magnifier(draw, 90, 278, TEXT_MUTED)
    draw.text((120, 264), "搜索会话标题或工作区路径…", fill=TEXT_MUTED, font=font_caption)
    
    chips = [("全部 (6)", True), ("运行中 (2)", False), ("待处理 (1)", False)]
    cx = 48
    for label, sel in chips:
        bg = PRIMARY if sel else SURFACE_VARIANT
        tc = ON_PRIMARY if sel else TEXT_MUTED
        draw.rounded_rectangle([cx, 348, cx + 160, 404], radius=14, fill=bg)
        draw.text((cx + 22, 362), label, fill=tc, font=font_caption_bold if sel else font_caption)
        cx += 180
        
    sessions_data = [
        ("分析APP并出具整改方案与重构", "F:/AI/Zcode · GLM-4.5 · 运行中", True, False, STATUS_ONLINE),
        ("重构网络通信层与 WebSocket 自动重连", "F:/Project/Relay · 1 项待决议 (请前往「待办」处理)", False, True, STATUS_PENDING),
        ("修复用户设置与密钥存储安全隐患", "F:/AI/Zcode · DeepSeek-R1 · 已完成", False, False, (140, 140, 140)),
        ("多机配对管理与自动设备迁移协议", "F:/AI/Zcode · Claude-3.5-Sonnet · 已完成", False, False, (140, 140, 140)),
        ("HyperOS 锁屏审批通知保活指南配置", "F:/System/Guide · 已完成", False, False, (140, 140, 140)),
        ("桌面端 Widget 实时卡片开发与联调", "F:/AI/Zcode · 已完成", False, False, (140, 140, 140)),
    ]
    
    cy = 434
    for title, meta, is_active, has_pending, dot_color in sessions_data:
        box = [48, cy, W - 48, cy + 220]
        card_fill = (45, 52, 70) if is_active else CARD_BG
        outline = PRIMARY if is_active else (STATUS_PENDING if has_pending else BORDER_COLOR)
        draw_card(draw, box, fill=card_fill, outline=outline, radius=24)
        
        draw.ellipse([80, cy + 42, 94, cy + 56], fill=dot_color)
        draw.text((116, cy + 32), title, fill=TEXT_WHITE, font=font_title_small)
        if is_active:
            draw.rounded_rectangle([W - 190, cy + 32, W - 78, cy + 74], radius=8, fill=PRIMARY)
            draw.text((W - 176, cy + 42), "正在查看", fill=ON_PRIMARY, font=font_caption_bold)
        elif has_pending:
            draw.rounded_rectangle([W - 180, cy + 32, W - 78, cy + 74], radius=8, fill=STATUS_PENDING)
            draw.text((W - 164, cy + 42), "待处理", fill=(255, 255, 255), font=font_caption_bold)
            
        text_color = STATUS_PENDING if has_pending else TEXT_MUTED
        draw.text((116, cy + 96), meta, fill=text_color, font=font_caption)
        cy += 244
        
    draw_nav_bar(draw, active_tab=0, badge_count=1)
    out_dir = "F:/AI/Zcode/zcode-remote-app/docs/screenshots"
    os.makedirs(out_dir, exist_ok=True)
    im.save(os.path.join(out_dir, "app-sessions.png"), "PNG")
    print("app-sessions.png generated")

def render_approvals():
    im = Image.new("RGBA", (W, H), BG_COLOR)
    draw = ImageDraw.Draw(im)
    draw_status_bar(draw)
    
    draw.text((48, 120), "待办与审批", fill=TEXT_WHITE, font=font_headline)
    draw_chip(draw, [W - 300, 126, W - 48, 186], STATUS_PENDING, "2 项待决议", font=font_body_bold, radius=16, pad_x=30)
    
    cy = 220
    draw.text((48, cy), "权限审批请求 (1)", fill=STATUS_PENDING, font=font_title_small)
    cy += 54
    
    card_box = [48, cy, W - 48, cy + 450]
    draw_card(draw, card_box, fill=SURFACE_COLOR, outline=BORDER_COLOR)
    
    draw_chip(draw, [80, cy + 32, 302, cy + 76], TRAJECTORY_AMBER, "工具调用 · Bash")
    draw.text((W - 320, cy + 42), "28s 后自动决议", fill=TEXT_MUTED, font=font_caption)
    
    draw.text((80, cy + 100), "构建并运行自动化单元测试以验证通信模块", fill=TEXT_WHITE, font=font_title_small)
    
    code_box = [80, cy + 160, W - 80, cy + 290]
    draw.rounded_rectangle(code_box, radius=12, fill=(24, 24, 28))
    draw.text((104, cy + 180), "./build.sh && ./gradlew testDebugUnitTest --continue", fill=(210, 220, 240), font=font_mono)
    draw.text((104, cy + 230), "# 需确认命令调用路径与环境权限", fill=TEXT_SUBTLE, font=font_mono)
    
    draw.rounded_rectangle([80, cy + 330, 260, cy + 410], radius=14, fill=None, outline=STATUS_ERROR, width=2)
    draw.text((136, cy + 352), "拒绝", fill=STATUS_ERROR, font=font_body_bold)
    
    draw.rounded_rectangle([280, cy + 330, 620, cy + 410], radius=14, fill=PRIMARY)
    draw.text((380, cy + 352), "允许一次", fill=ON_PRIMARY, font=font_body_bold)
    
    draw.rounded_rectangle([640, cy + 330, W - 80, cy + 410], radius=14, fill=(60, 90, 180))
    draw.text((720, cy + 352), "总是允许", fill=(255, 255, 255), font=font_body_bold)
    
    cy += 500
    
    draw.text((48, cy), "表单与计划应答 (1)", fill=PRIMARY, font=font_title_small)
    cy += 54
    
    card_box2 = [48, cy, W - 48, cy + 560]
    draw_card(draw, card_box2, fill=SURFACE_COLOR, outline=BORDER_COLOR)
    
    draw_chip(draw, [80, cy + 32, 316, cy + 76], TRAJECTORY_PURPLE, "实施方案待批准")
    
    draw.text((80, cy + 100), "重构网络层：迁移至 OkHttp WebSocket 专线", fill=TEXT_WHITE, font=font_title_small)
    
    code_box2 = [80, cy + 160, W - 80, cy + 420]
    draw.rounded_rectangle(code_box2, radius=12, fill=(24, 24, 28))
    plan_lines = [
        "## 阶段一：建立 RpcChannel 帧校验",
        "1. 支持 CRC32 校验与分片自动重组",
        "2. 增加 15s 握手超时硬兜底保底",
        "## 阶段二：接入 SessionsIndexChannel",
        "3. 权威角标状态监听，实时刷新看板",
    ]
    py = cy + 180
    for l in plan_lines:
        draw.text((104, py), l, fill=TEXT_MUTED, font=font_mono)
        py += 44
        
    draw.rounded_rectangle([80, cy + 450, 360, cy + 526], radius=14, fill=None, outline=STATUS_ERROR, width=2)
    draw.text((160, cy + 472), "拒绝方案", fill=STATUS_ERROR, font=font_body_bold)
    
    draw.rounded_rectangle([390, cy + 450, W - 80, cy + 526], radius=14, fill=PRIMARY)
    draw.text((580, cy + 472), "批准并执行", fill=ON_PRIMARY, font=font_body_bold)
    
    draw_nav_bar(draw, active_tab=1, badge_count=2)
    out_dir = "F:/AI/Zcode/zcode-remote-app/docs/screenshots"
    im.save(os.path.join(out_dir, "app-approval-notification.png"), "PNG")
    print("app-approval-notification.png generated")

def render_conversation():
    im = Image.new("RGBA", (W, H), BG_COLOR)
    draw = ImageDraw.Draw(im)
    draw_status_bar(draw)
    
    draw.text((48, 126), "←", fill=TEXT_WHITE, font=font_headline)
    draw.text((116, 116), "分析APP并出具整改方案与重构", fill=TEXT_WHITE, font=font_title)
    draw.text((116, 172), "已连接 · 运行中 · 共 42 行", fill=STATUS_ONLINE, font=font_caption)
    
    cy = 230
    user_bubble = [W - 680, cy, W - 48, cy + 130]
    draw.rounded_rectangle(user_bubble, radius=24, fill=PRIMARY)
    draw.text((W - 640, cy + 34), "帮我重构一下 App 界面，对标官方 ZCode 设计系统", fill=ON_PRIMARY, font=font_body_bold)
    cy += 164
    
    asst_box = [48, cy, W - 48, cy + 1460]
    draw_card(draw, asst_box, fill=SURFACE_COLOR, outline=BORDER_COLOR)
    
    ay = cy + 36
    draw.rounded_rectangle([80, ay, W - 80, ay + 80], radius=14, fill=(42, 38, 56))
    draw.ellipse([110, ay + 32, 126, ay + 48], fill=TRAJECTORY_PURPLE)
    draw.text((144, ay + 24), "思考过程 (350 字符) · 耗时 2.4s", fill=TRAJECTORY_PURPLE, font=font_body_bold)
    draw.text((W - 190, ay + 26), "收起 ▲", fill=TEXT_MUTED, font=font_caption)
    
    ay += 96
    draw.line([96, ay, 96, ay + 220], fill=TRAJECTORY_PURPLE, width=4)
    reason_lines = [
        "1. 诊断现状：当前界面单列堆叠过载，缺少层级；",
        "2. 对标官方 ZCode：采用 zai-dark 15% 微边框色彩体系；",
        "3. 引入 3-Tab 移动架构，将待办与设置彻底解耦；",
        "4. 代码块增加深浅模式自适应，支持一键复制确认。",
    ]
    ry = ay + 10
    for rl in reason_lines:
        draw.text((124, ry), rl, fill=TEXT_MUTED, font=font_caption)
        ry += 48
    ay += 240
    
    draw.rounded_rectangle([80, ay, W - 80, ay + 240], radius=16, fill=(28, 28, 34), outline=(255, 255, 255, 20))
    draw_chip(draw, [104, ay + 20, 326, ay + 62], TRAJECTORY_AMBER, "工具调用 · Bash")
    draw.text((260, ay + 28), "√ 成功", fill=STATUS_ONLINE, font=font_caption_bold)
    draw.text((W - 190, ay + 28), "详情 ▼", fill=TEXT_MUTED, font=font_caption)
    
    draw.text((104, ay + 84), "git status -s && ./build.sh", fill=TEXT_WHITE, font=font_mono)
    draw.text((104, ay + 130), "BUILD SUCCESSFUL in 11s", fill=STATUS_ONLINE, font=font_mono)
    draw.text((104, ay + 176), "生成调试包：app-debug.apk (33MB)", fill=TEXT_MUTED, font=font_mono)
    ay += 270
    
    draw.text((80, ay), "### 重构方案已落地实施完成", fill=TEXT_WHITE, font=font_title_small)
    ay += 60
    draw.text((80, ay), "已成功将界面重构为底部三大专属工作区：", fill=TEXT_WHITE, font=font_body)
    ay += 50
    draw.text((80, ay), "• 会话工作台：聚焦会话流与工作区路径切换；", fill=TEXT_WHITE, font=font_body)
    ay += 46
    draw.text((80, ay), "• 待办审批：集中决议权限与 AskUserQuestion；", fill=TEXT_WHITE, font=font_body)
    ay += 46
    draw.text((80, ay), "• 设置中心：管理多机配对、线路与保活教程。", fill=TEXT_WHITE, font=font_body)
    ay += 70
    
    code_card = [80, ay, W - 80, ay + 280]
    draw.rounded_rectangle(code_card, radius=14, fill=(22, 24, 30), outline=(255, 255, 255, 20))
    draw.rectangle([80, ay, W - 80, ay + 56], fill=(32, 34, 42))
    draw.text((104, ay + 14), "bash", fill=TEXT_MUTED, font=font_caption_bold)
    draw.text((W - 190, ay + 14), "√ 已复制", fill=STATUS_ONLINE, font=font_caption_bold)
    
    draw.text((104, ay + 80), "./build.sh install", fill=(130, 220, 150), font=font_mono)
    draw.text((104, ay + 134), "# 一键编译并在连接的真机上完成安装启动", fill=TEXT_SUBTLE, font=font_mono)
    draw.text((104, ay + 188), "Starting: Intent { cmp=com.zcode.remote/.MainActivity }", fill=(180, 200, 240), font=font_mono)
    
    input_y = H - 160
    draw.rounded_rectangle([48, input_y, W - 48, input_y + 110], radius=32, fill=SURFACE_COLOR, outline=(255, 255, 255, 40), width=2)
    draw.text((84, input_y + 30), "＋", fill=TEXT_MUTED, font=font_title_small)
    draw_mic(draw, 160, input_y + 54, PRIMARY)
    draw.text((220, input_y + 38), "发送指令到 PC 端 Agent…", fill=TEXT_MUTED, font=font_body)
    
    draw.ellipse([W - 138, input_y + 18, W - 66, input_y + 90], fill=PRIMARY)
    draw_send(draw, W - 102, input_y + 54, ON_PRIMARY, s=15)
    
    draw.rounded_rectangle([W // 2 - 120, H - 28, W // 2 + 120, H - 20], radius=4, fill=(160, 160, 160, 120))
    
    out_dir = "F:/AI/Zcode/zcode-remote-app/docs/screenshots"
    im.save(os.path.join(out_dir, "app-conversation.png"), "PNG")
    print("app-conversation.png generated")

def render_settings():
    im = Image.new("RGBA", (W, H), BG_COLOR)
    draw = ImageDraw.Draw(im)
    draw_status_bar(draw)
    
    draw.text((48, 120), "设置与设备", fill=TEXT_WHITE, font=font_headline)
    draw.rounded_rectangle([W - 260, 126, W - 48, 186], radius=16, fill=PRIMARY_CONTAINER)
    draw.text((W - 236, 140), "配对新设备", fill=PRIMARY, font=font_body_bold)
    
    cy = 220
    draw.text((48, cy), "当前连接设备", fill=PRIMARY, font=font_title_small)
    cy += 50
    card1 = [48, cy, W - 48, cy + 180]
    draw_card(draw, card1)
    draw.ellipse([80, cy + 40, 124, cy + 84], fill=PRIMARY_CONTAINER)
    draw_monitor(draw, 88, cy + 50, PRIMARY, w=28, h=20)
    draw.text((150, cy + 34), "ThinkPad-P16 (主工作站)", fill=TEXT_WHITE, font=font_title_small)
    draw.ellipse([152, cy + 96, 164, cy + 108], fill=STATUS_ONLINE)
    draw.text((176, cy + 90), "已就绪 · 会话桥 就绪 · 延迟 12ms", fill=TEXT_MUTED, font=font_caption)
    
    draw.rounded_rectangle([W - 180, cy + 48, W - 80, cy + 112], radius=12, fill=None, outline=BORDER_COLOR)
    draw.text((W - 152, cy + 62), "断开", fill=TEXT_MUTED, font=font_caption_bold)
    cy += 230
    
    draw.text((48, cy), "已配对的其他设备 (2)", fill=PRIMARY, font=font_title_small)
    cy += 50
    card2 = [48, cy, W - 48, cy + 260]
    draw_card(draw, card2)
    draw.text((80, cy + 32), "MacBook-Pro-M3 (移动开发本)", fill=TEXT_WHITE, font=font_body_bold)
    draw.text((80, cy + 76), "SID: d58f29ea10bc… · 上次连接 3小时前", fill=TEXT_MUTED, font=font_caption)
    draw.rounded_rectangle([W - 230, cy + 36, W - 80, cy + 94], radius=10, fill=PRIMARY)
    draw.text((W - 198, cy + 48), "切换连接", fill=ON_PRIMARY, font=font_caption_bold)
    
    draw.line([80, cy + 130, W - 80, cy + 130], fill=(255, 255, 255, 20), width=1)
    draw.text((80, cy + 158), "Desktop-Ubuntu (实验室服务器)", fill=TEXT_WHITE, font=font_body_bold)
    draw.text((80, cy + 202), "SID: 781ac42e9912… · 上次连接 昨天", fill=TEXT_MUTED, font=font_caption)
    draw.rounded_rectangle([W - 230, cy + 162, W - 80, cy + 220], radius=10, fill=PRIMARY)
    draw.text((W - 198, cy + 174), "切换连接", fill=ON_PRIMARY, font=font_caption_bold)
    cy += 310
    
    draw.text((48, cy), "网络与个性化", fill=PRIMARY, font=font_title_small)
    cy += 50
    card3 = [48, cy, W - 48, cy + 340]
    draw_card(draw, card3)
    draw.text((80, cy + 30), "中继线路模式", fill=TEXT_WHITE, font=font_body_bold)
    modes = [("自动判定", True), ("官方主线", False), ("官方备线", False), ("自建中继", False)]
    mx = 80
    for label, sel in modes:
        draw.rounded_rectangle([mx, cy + 74, mx + 170, cy + 128], radius=10, fill=PRIMARY if sel else SURFACE_VARIANT)
        draw.text((mx + 22, cy + 88), label, fill=ON_PRIMARY if sel else TEXT_MUTED, font=font_caption_bold if sel else font_caption)
        mx += 190
        
    draw.line([80, cy + 160, W - 80, cy + 160], fill=(255, 255, 255, 20), width=1)
    draw.text((80, cy + 184), "界面外观模式", fill=TEXT_WHITE, font=font_body_bold)
    themes = [("深色模式", True), ("跟随系统", False), ("浅色模式", False)]
    tx = 80
    for label, sel in themes:
        draw.rounded_rectangle([tx, cy + 228, tx + 180, cy + 282], radius=10, fill=PRIMARY if sel else SURFACE_VARIANT)
        draw.text((tx + 26, cy + 242), label, fill=ON_PRIMARY if sel else TEXT_MUTED, font=font_caption_bold if sel else font_caption)
        tx += 200
    cy += 390
    
    draw.text((48, cy), "软件版本与更新", fill=PRIMARY, font=font_title_small)
    cy += 50
    card4 = [48, cy, W - 48, cy + 220]
    draw_card(draw, card4)
    draw.text((80, cy + 32), "ZCode Remote", fill=TEXT_WHITE, font=font_title_small)
    draw.text((80, cy + 80), "当前版本: v0.5.0-beta1 (Build 11)", fill=TEXT_MUTED, font=font_caption)
    draw.text((80, cy + 126), "√ 已是最新版本 · 官方规范设计系统", fill=STATUS_ONLINE, font=font_caption_bold)
    
    draw.rounded_rectangle([W - 240, cy + 44, W - 80, cy + 116], radius=14, fill=PRIMARY)
    draw.text((W - 212, cy + 62), "检查更新", fill=ON_PRIMARY, font=font_body_bold)
    
    draw_nav_bar(draw, active_tab=2, badge_count=1)
    out_dir = "F:/AI/Zcode/zcode-remote-app/docs/screenshots"
    im.save(os.path.join(out_dir, "app-working.png"), "PNG")
    print("app-working.png generated")

# ---------------------------------------------------------------------------
# 锁屏通知栏审批（核心差异点头图）：静态主图 + 交互演示 GIF
# ---------------------------------------------------------------------------

LOCK_TOP = (9, 10, 15)
LOCK_BOTTOM = (24, 24, 34)
OUT_DIR = "F:/AI/Zcode/zcode-remote-app/docs/screenshots"

def draw_lock_wallpaper(draw, top=LOCK_TOP, bottom=LOCK_BOTTOM):
    for y in range(H):
        t = y / (H - 1)
        c = tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(3))
        draw.line([(0, y), (W, y)], fill=c)

def draw_lock_clock(draw):
    cx = W // 2
    metal = (122, 136, 158)
    draw.arc([cx - 34, 236, cx + 34, 318], 180, 360, fill=metal, width=13)
    draw.rounded_rectangle([cx - 54, 288, cx + 54, 378], radius=18, fill=metal)
    draw.ellipse([cx - 11, 324, cx + 11, 346], fill=LOCK_TOP)
    text_center(draw, cx, 424, "09:41", get_font(168, bold=True), TEXT_WHITE)
    text_center(draw, cx, 636, "10月15日 星期三", get_font(38, bold=False), TEXT_MUTED)

def draw_code_box(draw, box, lines, line_h=48):
    draw.rounded_rectangle(box, radius=14, fill=(20, 20, 26), outline=(255, 255, 255, 20), width=1)
    y = box[1] + 22
    for txt, col in lines:
        draw.text((box[0] + 26, y), txt, fill=col, font=font_mono)
        y += line_h

def draw_button(draw, box, label, kind="primary"):
    x0, y0, x1, y1 = box
    if kind == "danger":
        draw.rounded_rectangle(box, radius=14, fill=None, outline=STATUS_ERROR, width=3)
        color = STATUS_ERROR
    elif kind == "ghost":
        draw.rounded_rectangle(box, radius=14, fill=(60, 90, 180))
        color = (255, 255, 255)
    else:
        draw.rounded_rectangle(box, radius=14, fill=PRIMARY)
        color = ON_PRIMARY
    bb = draw.textbbox((0, 0), label, font=font_body_bold)
    draw.text((x0 + (x1 - x0 - (bb[2] - bb[0])) // 2 - bb[0],
               y0 + (y1 - y0 - (bb[3] - bb[1])) // 2 - bb[1]), label, fill=color, font=font_body_bold)

def draw_notif_app_header(draw, x0, y0, x1, when="现在"):
    draw.rounded_rectangle([x0, y0, x0 + 64, y0 + 64], radius=16, fill=PRIMARY_CONTAINER, outline=PRIMARY, width=2)
    draw.text((x0 + 15, y0 + 16), ">_", fill=PRIMARY, font=font_body_bold)
    draw.text((x0 + 82, y0 + 6), "ZCode Remote", fill=TEXT_WHITE, font=font_body_bold)
    draw.text((x0 + 82, y0 + 44), "远程控制 · 常驻中继", fill=TEXT_SUBTLE, font=font_caption)
    bb = draw.textbbox((0, 0), when, font=font_caption)
    draw.text((x1 - (bb[2] - bb[0]) - bb[0], y0 + 12), when, fill=TEXT_SUBTLE, font=font_caption)

def draw_lock_card(draw, expanded, approved=False):
    if not expanded:
        box = [48, 1930, W - 48, 2150]
        draw_card(draw, box, fill=(30, 30, 38), outline=(255, 255, 255, 32), radius=28)
        draw_notif_app_header(draw, box[0] + 30, box[1] + 30, box[2] - 30)
        draw.text((box[0] + 30, box[1] + 122), "需要你确认：执行权限审批", fill=TEXT_WHITE, font=font_body_bold)
        draw.text((box[0] + 30, box[1] + 170), "工具调用 · Bash 请求执行 · 下拉可内联处理", fill=TEXT_MUTED, font=font_caption)
        return

    box = [48, 1240, W - 48, 1900]
    draw_card(draw, box, fill=(30, 30, 38), outline=(255, 255, 255, 32), radius=28)
    x0 = box[0] + 34
    x1 = box[2] - 34
    draw_notif_app_header(draw, x0, box[1] + 30, x1)

    draw.text((x0, box[1] + 124), "需要你确认：执行权限审批", fill=TEXT_WHITE, font=font_title_small)
    draw.text((x0, box[1] + 178), "桌面端请求执行以下命令，锁屏状态下可直接决策：",
              fill=TEXT_MUTED, font=font_caption)

    draw_code_box(draw, [x0, box[1] + 232, x1, box[1] + 384], [
        ("./build.sh && ./gradlew testDebugUnitTest --continue", (210, 220, 240)),
        ("# 需确认命令调用路径与环境权限", TEXT_SUBTLE),
    ])

    by0, by1 = box[1] + 432, box[1] + 512
    if approved:
        draw.rounded_rectangle([x0, by0, x1, by1], radius=14, fill=chip_bg(STATUS_ONLINE, 0.20), outline=STATUS_ONLINE, width=2)
        draw.text((x0 + 26, by0 + 14), "√", fill=STATUS_ONLINE, font=font_body_bold)
        draw.text((x0 + 78, by0 + 14), "已允许一次 · 已同步到桌面端", fill=STATUS_ONLINE, font=font_body_bold)
    else:
        draw_button(draw, [x0, by0, x0 + 180, by1], "拒绝", kind="danger")
        draw_button(draw, [x0 + 204, by0, x0 + 524, by1], "允许一次", kind="primary")
        draw_button(draw, [x0 + 548, by0, x1, by1], "总是允许", kind="ghost")

    ry0, ry1 = box[1] + 540, box[1] + 620
    draw.rounded_rectangle([x0, ry0, x1, ry1], radius=14, fill=(22, 22, 28), outline=(255, 255, 255, 20), width=1)
    draw.text((x0 + 26, ry0 + 16), "回复…（免解锁内联输入）", fill=TEXT_SUBTLE, font=font_caption)
    # 直接画三角形，避免 emoji/箭头字形在部分字体下渲染成缺字方块
    sy = (ry0 + ry1) // 2
    draw.polygon([(x1 - 68, sy - 13), (x1 - 38, sy), (x1 - 68, sy + 13)], fill=PRIMARY)

def render_lockscreen_approval():
    """产出 3 帧：折叠通知 → 展开可决策 → 已允许；主图取展开帧。"""
    frames = []
    for expanded, approved in [(False, False), (True, False), (True, True)]:
        im = Image.new("RGBA", (W, H), BG_COLOR)
        draw = ImageDraw.Draw(im)
        draw_lock_wallpaper(draw)
        draw_status_bar(draw)
        draw_lock_clock(draw)
        draw_lock_card(draw, expanded, approved)
        frames.append(im)

    os.makedirs(OUT_DIR, exist_ok=True)
    hero_path = os.path.join(OUT_DIR, "app-lockscreen-approval.png")
    frames[1].save(hero_path, "PNG")
    print("app-lockscreen-approval.png generated")

    frame_paths = []
    for i, im in enumerate(frames):
        p = os.path.join(OUT_DIR, f"_lock_frame{i}.png")
        im.save(p, "PNG")
        frame_paths.append(p)

    gif_path = os.path.join(OUT_DIR, "lockscreen-approval.gif")
    save_gif([frame_paths[0], frame_paths[1], frame_paths[2], frame_paths[1]], gif_path,
             durations=[1100, 1900, 1600, 1900], width=520)
    for p in frame_paths:
        os.remove(p)

def save_gif(paths, out_path, durations, width=520):
    frames = []
    for p in paths:
        im = Image.open(p).convert("RGB")
        nh = int(im.height * width / im.width)
        frames.append(im.resize((width, nh), Image.LANCZOS).convert("P", palette=Image.ADAPTIVE, colors=96))
    frames[0].save(out_path, save_all=True, append_images=frames[1:],
                   duration=durations, loop=0, optimize=True, disposal=2)
    print(f"{os.path.basename(out_path)} generated",
          f"({round(os.path.getsize(out_path) / 1024)} KB)")

if __name__ == "__main__":
    render_sessions()
    render_approvals()
    render_conversation()
    render_settings()
    render_lockscreen_approval()
    print("ALL SCREENSHOTS GENERATED SUCCESSFULLY!")
