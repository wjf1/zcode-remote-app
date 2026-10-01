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
                bx, by = cx + 22, iy - 16
                draw.ellipse([bx - 18, by - 14, bx + 18, by + 14], fill=STATUS_PENDING)
                draw.text((bx - 8, by - 12), str(badge_count), fill=(255, 255, 255), font=font_caption_bold)
        else:
            draw.arc([cx - 18, iy - 18, cx + 18, iy + 18], 0, 360, fill=color, width=5)
            draw.ellipse([cx - 6, iy - 6, cx + 6, iy + 6], fill=color)
            
        draw.text((cx - 24, bar_y + 92), name, fill=color, font=font_caption_bold if is_active else font_caption)
        
    draw.rounded_rectangle([W // 2 - 120, H - 28, W // 2 + 120, H - 20], radius=4, fill=(160, 160, 160, 120))

def draw_card(draw, box, fill=CARD_BG, outline=BORDER_COLOR, radius=24):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=2)

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
    draw.text((80, 264), "🔍 搜索会话标题或工作区路径…", fill=TEXT_MUTED, font=font_caption)
    
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
        ("重构网络通信层与 WebSocket 自动重连", "F:/Project/Relay · ⏳ 1 项待决议 (请前往「待办」处理)", False, True, STATUS_PENDING),
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
    draw.rounded_rectangle([W - 260, 126, W - 48, 186], radius=16, fill=(255, 138, 48, 45), outline=STATUS_PENDING)
    draw.text((W - 232, 140), "2 项待决议", fill=STATUS_PENDING, font=font_body_bold)
    
    cy = 220
    draw.text((48, cy), "权限审批请求 (1)", fill=STATUS_PENDING, font=font_title_small)
    cy += 54
    
    card_box = [48, cy, W - 48, cy + 450]
    draw_card(draw, card_box, fill=SURFACE_COLOR, outline=BORDER_COLOR)
    
    draw.rounded_rectangle([80, cy + 32, 280, cy + 76], radius=8, fill=(245, 158, 11, 40))
    draw.text((96, cy + 42), "工具调用 · Bash", fill=TRAJECTORY_AMBER, font=font_caption_bold)
    draw.text((W - 320, cy + 42), "⏳ 28s 后自动决议", fill=TEXT_MUTED, font=font_caption)
    
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
    
    draw.rounded_rectangle([80, cy + 32, 290, cy + 76], radius=8, fill=(167, 139, 250, 40))
    draw.text((96, cy + 42), "实施方案待批准", fill=TRAJECTORY_PURPLE, font=font_caption_bold)
    
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
    draw.rounded_rectangle([104, ay + 20, 240, ay + 62], radius=8, fill=(245, 158, 11, 40))
    draw.text((118, ay + 28), "工具调用 · Bash", fill=TRAJECTORY_AMBER, font=font_caption_bold)
    draw.text((260, ay + 28), "✓ 成功", fill=STATUS_ONLINE, font=font_caption_bold)
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
    draw.text((W - 190, ay + 14), "✓ 已复制", fill=STATUS_ONLINE, font=font_caption_bold)
    
    draw.text((104, ay + 80), "./build.sh install", fill=(130, 220, 150), font=font_mono)
    draw.text((104, ay + 134), "# 一键编译并在连接的真机上完成安装启动", fill=TEXT_SUBTLE, font=font_mono)
    draw.text((104, ay + 188), "Starting: Intent { cmp=com.zcode.remote/.MainActivity }", fill=(180, 200, 240), font=font_mono)
    
    input_y = H - 160
    draw.rounded_rectangle([48, input_y, W - 48, input_y + 110], radius=32, fill=SURFACE_COLOR, outline=(255, 255, 255, 40), width=2)
    draw.text((84, input_y + 32), "➕", fill=TEXT_MUTED, font=font_title_small)
    draw.text((144, input_y + 32), "🎤", fill=PRIMARY, font=font_title_small)
    draw.text((220, input_y + 38), "发送指令到 PC 端 Agent…", fill=TEXT_MUTED, font=font_body)
    
    draw.ellipse([W - 138, input_y + 18, W - 66, input_y + 90], fill=PRIMARY)
    draw.text((W - 114, input_y + 30), "➤", fill=ON_PRIMARY, font=font_title_small)
    
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
    draw.text((94, cy + 42), "💻", font=font_body_bold)
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
    draw.text((80, cy + 126), "✓ 已是最新版本 · 官方规范设计系统", fill=STATUS_ONLINE, font=font_caption_bold)
    
    draw.rounded_rectangle([W - 240, cy + 44, W - 80, cy + 116], radius=14, fill=PRIMARY)
    draw.text((W - 212, cy + 62), "检查更新", fill=ON_PRIMARY, font=font_body_bold)
    
    draw_nav_bar(draw, active_tab=2, badge_count=1)
    out_dir = "F:/AI/Zcode/zcode-remote-app/docs/screenshots"
    im.save(os.path.join(out_dir, "app-working.png"), "PNG")
    print("app-working.png generated")

if __name__ == "__main__":
    render_sessions()
    render_approvals()
    render_conversation()
    render_settings()
    print("ALL 4 SCREENSHOTS GENERATED SUCCESSFULLY!")
