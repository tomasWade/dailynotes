#!/usr/bin/env python3
"""生成 2 个月日记+笔记测试数据（含真实 PNG 附件），供 import.sh 导入。

用法: python3 gen-testdata.py [输出目录，默认 /tmp/opencode/testdata]
特点：纯标准库；~15% 日期断档（日历视图可见断档效果）；近 3 天保证连续；
      图片为 HSV 渐变 PNG（每天色系不同，512x288，Coil 可直接加载）。
"""
import colorsys
import os
import random
import struct
import sys
import zlib
from datetime import date, timedelta

random.seed(42)
OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/opencode/testdata"
START = date(2026, 8, 22)
END = date(2026, 10, 23)
W, H = 512, 288

MORNING = [
    "早上六点半醒了，窗外有点灰，赖了十分钟才起。",
    "难得睡到自然醒，七点四十，阳光正好斜进屋里。",
    "闹钟响之前就醒了，索性起来烧水泡茶。",
    "今早有雾，远处的楼只剩个轮廓。",
]
WORK = [
    "上午把攒的几封邮件清了，顺手把下周的排期理了一遍。",
    "继续弄那个方案，改到第三版，甲方大概还是不会满意，随他。",
    "debug 了一下午，最后发现是配置写错了行，气笑。",
    "开会两小时，有用的信息十分钟，其他时间在走神。",
    "把手头的数据核了一遍，比想的干净，省事了。",
    "下午集中写了两个小时的文档，进入状态之后其实挺顺的。",
]
NOON = [
    "中午吃的面，加了个蛋，汤喝完了。",
    "外卖到得意外地快，味道一般但便宜。",
    "中午下楼走了圈，风很舒服，买了个烤红薯。",
    "和同事拼了个沙拉，下午饿得很快。",
]
EVE = [
    "傍晚天色很好，云被染成橘色，拍了张照片。",
    "晚上去了趟超市，补了牛奶和水果，排队的人不多。",
    "饭后散步到河边，路灯刚亮，水面碎碎的光。",
    "回家路上堵了一会，正好把播客那一期听完了。",
]
NIGHT = [
    "睡前翻了十几页书，有点困就放下了。",
    "晚上打了两把游戏，赢一输一，正好下线。",
    "和家人视频了一会儿，聊了些琐事，挺好的。",
    "整理了下明天的待办，三项，不多。",
    "夜里有风，开着窗睡应该很舒服。",
]
MOOD = [
    "总体是平静的一天。",
    "有点疲惫，但心里是踏实的。",
    "心情不错，没什么理由的那种不错。",
    "略有烦躁，睡一觉应该就好了。",
    "今天效率意外地高，满意。",
]
NOTES = [
    "冰箱里的酱油快没了，下次买东西记得。",
    "推荐：《置身事内》第四章讲土地财政，值得二刷。",
    "地铁口那家煎饼摊换人了，味道不如以前。",
    "想法：把家里的旧照片扫描存档，找个周末做。",
    "密码提示问题的答案最好也用假信息，别用真实的。",
    "妈妈生日是下个月 15 号，提前订蛋糕。",
    "跑步鞋该换了，右脚外侧磨得厉害。",
    "curl 的 -w 参数可以自定义输出格式，配合 %{http_code} 很好用。",
    "试了用 Markdown 写周报，比 doc 快多了。",
    "楼下橘猫今天终于让摸了，里程碑。",
]


def make_png(path: str, hue: float):
    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    def row(y):
        # 横向渐变 + 纵向亮度变化
        return b"\x00" + bytes(
            v
            for x in range(W)
            for v in (
                lambda rgb: (int(rgb[0] * 255), int(rgb[1] * 255), int(rgb[2] * 255))
            )(colorsys.hsv_to_rgb((hue + x / W * 0.18) % 1.0, 0.62, 0.55 + (1 - y / H) * 0.4))
        )

    raw = b"".join(row(y) for y in range(H))
    ihdr = struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


def hexrand():
    return "".join(random.choice("0123456789abcdef") for _ in range(4))


def gen_diary(d: date) -> str:
    parts = [random.choice(MORNING), random.choice(WORK), random.choice(NOON)]
    if random.random() < 0.7:
        parts.append(random.choice(EVE))
    if random.random() < 0.8:
        parts.append(random.choice(NIGHT))
    if random.random() < 0.5:
        parts.append(random.choice(MOOD))
    return "\n\n".join(parts)


def main():
    for d in ("diary", "notes", "attachments"):
        os.makedirs(os.path.join(OUT, d), exist_ok=True)

    total = days = imgs = 0
    d = START
    while d <= END:
        # 断档：随机 15% 跳过，但最近 3 天保证连续（连续天数/今天入口演示）
        recent = (END - d).days <= 2
        if not recent and random.random() < 0.15:
            d += timedelta(days=1)
            continue

        month_bucket = d.strftime("%Y-%m")
        att_dir = os.path.join(OUT, "attachments", month_bucket)
        os.makedirs(att_dir, exist_ok=True)

        r = random.random()
        n_img = 0 if r < 0.4 else (1 if r < 0.8 else 2)
        refs = []
        for _ in range(n_img):
            hh = random.randint(8, 22)
            mm = random.randint(0, 59)
            name = f"{d.strftime('%Y%m%d')}_{hh:02d}{mm:02d}{random.randint(0,59):02d}_{hexrand()}.png"
            make_png(os.path.join(att_dir, name), ((d - START).days * 0.13 + hh / 24) % 1.0)
            refs.append(f"![](../attachments/{month_bucket}/{name})")
            imgs += 1

        body = gen_diary(d)
        if refs:
            # 图片穿插：首段后放第一张，文末放剩余
            paras = body.split("\n\n")
            paras.insert(min(1, len(paras)), refs[0])
            body = "\n\n".join(paras) + "\n\n" + "\n\n".join(refs[1:])
        with open(os.path.join(OUT, "diary", d.isoformat() + ".md"), "w") as f:
            f.write(body + "\n")
        days += 1
        total += 1
        d += timedelta(days=1)

    # 速记笔记
    for i, text in enumerate(random.sample(NOTES, min(8, len(NOTES)))):
        ts = START + timedelta(days=random.randint(0, 60))
        hh = random.randint(7, 23)
        name = f"{ts.strftime('%Y%m%d')}T{hh:02d}{random.randint(0,59):02d}{random.randint(0,59):02d}-{hexrand()}.md"
        content = text
        if random.random() < 0.25:
            month_bucket = ts.strftime("%Y-%m")
            att_dir = os.path.join(OUT, "attachments", month_bucket)
            os.makedirs(att_dir, exist_ok=True)
            pname = f"{ts.strftime('%Y%m%d')}_{hh:02d}1530_{hexrand()}.png"
            make_png(os.path.join(att_dir, pname), random.random())
            content += f"\n\n![](../attachments/{month_bucket}/{pname})"
            imgs += 1
        with open(os.path.join(OUT, "notes", name), "w") as f:
            f.write(content + "\n")
        total += 1

    print(f"生成完成: {days} 篇日记 + 笔记若干, {imgs} 张图, 共 {total} 个 md, 输出在 {OUT}")


if __name__ == "__main__":
    main()
